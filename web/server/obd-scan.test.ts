import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { beforeAll, describe, expect, it } from "vitest";

// Baza de date de test, înainte de prima folosire a lui getDb.
process.env.FAIKKITCAR_DB = join(mkdtempSync(join(tmpdir(), "obd-scan-")), "t.db");

const scan = await import("./obd-scan.ts");
const { getDb } = await import("./db.ts");

describe("codeUpdate", () => {
  it("records the first value as a sample, not as a change", () => {
    expect(scan.codeUpdate(undefined, "16D3", null)).toEqual({ value: "16D3", nrc: null, changed: false, sample: true });
  });
  it("counts a different value as a change", () => {
    expect(scan.codeUpdate({ value: "16D3", nrc: null }, "16D4", null)).toMatchObject({ changed: true, sample: true });
    expect(scan.codeUpdate({ value: "16D3", nrc: null }, "16D3", null)).toMatchObject({ changed: false, sample: false });
  });
  it("keeps a good value when a later read is refused or slow", () => {
    expect(scan.codeUpdate({ value: "16D3", nrc: null }, null, 0x22)).toEqual({ value: "16D3", nrc: null, changed: false, sample: false });
    expect(scan.codeUpdate({ value: null, nrc: scan.NRC_SLOW }, null, 0x22)).toMatchObject({ nrc: 0x22 });
  });
  it("replaces a refusal with the first good answer", () => {
    expect(scan.codeUpdate({ value: null, nrc: 0x22 }, "01", null)).toEqual({ value: "01", nrc: null, changed: false, sample: true });
  });
});

describe("jobProgress", () => {
  it("counts a range from its first code", () => {
    expect(scan.jobProgress({ kind: "range", first: 0x0a3b, last: 0xffff, ids: null, next: 0x0a3b + 10, status: "running" }))
      .toEqual({ done: 10, total: 0xffff - 0x0a3b + 1 });
  });
  it("counts a list by index and a finished job as complete", () => {
    expect(scan.jobProgress({ kind: "list", first: null, last: null, ids: [3, 7, 10], next: 1, status: "running" }))
      .toEqual({ done: 1, total: 3 });
    expect(scan.jobProgress({ kind: "list", first: null, last: null, ids: [3, 7, 10], next: 1, status: "done" }))
      .toEqual({ done: 3, total: 3 });
  });
});

describe("parseScanStatus", () => {
  it("reads good answers, refusals and slow codes; the last one wins", () => {
    const m = scan.parseScanStatus(
      [
        "== 714/77E 0000-FFFF",
        "714 1007: 16 D3",
        '714 22C5: 00 30 30 30  ".000"',
        "746 0670: 7F 22 22",
        "714 1234: lent (fara raspuns)",
        "714 1007: 16 D4",
        "In curs: 714/77E la ECCA (92%, 3703 s)",
      ].join("\n"),
    );
    expect(m.get("714:4103")).toEqual({ tx: "714", id: 0x1007, value: "16D4", nrc: null });
    expect(m.get(`714:${0x22c5}`)?.value).toBe("00303030");
    expect(m.get(`746:${0x0670}`)).toMatchObject({ value: null, nrc: 0x22 });
    expect(m.get(`714:${0x1234}`)).toMatchObject({ nrc: scan.NRC_SLOW });
    expect(m.size).toBe(4);
  });
});

describe("jobs and results", () => {
  beforeAll(() => {
    scan.seedScanJobs("2026-11-21T00:00:00.000Z");
  });

  it("seeds the order once, engine first", () => {
    expect(scan.seedScanJobs()).toBe(0);
    const jobs = scan.carScanJobs();
    expect(jobs.map((j) => j.label)).toEqual(scan.SEED.slice(0, 3).map((s) => s.label));
    expect(jobs[0]).toMatchObject({ tx: "7E0", rx: "7E8", svc: "22", kind: "range", first: 0, last: 0xffff, next: 0 });
  });

  it("stores results and progress; a resent batch does not move progress back", () => {
    const [engine] = scan.carScanJobs();
    const items = [
      { t: "r", j: engine.id, x: "7E0", s: "22", i: 0x0100, v: "0a1b", at: Date.parse("2026-11-22T10:00:00Z") },
      { t: "r", j: engine.id, x: "7E0", s: "22", i: 0x0101, n: 0x22 },
      { t: "r", j: engine.id, x: "7E0", s: "22", i: 0x0102, n: scan.NRC_SLOW },
      { t: "p", j: engine.id, n: 0x0200 },
    ];
    expect(scan.saveScanItems(items)).toEqual({ ok: 4, skipped: 0 });
    expect(scan.saveScanItems([{ t: "p", j: engine.id, n: 0x0150 }]).ok).toBe(1);
    const after = scan.carScanJobs()[0];
    expect(after).toMatchObject({ id: engine.id, next: 0x0200, status: "running" });
    expect(scan.readScanCodes("7E0").find((c) => c.id === 0x0100)).toMatchObject({ value: "0A1B", nrc: null });
  });

  it("rejects malformed results", () => {
    const r = scan.saveScanItems([
      { t: "r", j: 1, x: "715", s: "22", i: 1, v: "00" }, // airbag: nu se scanează
      { t: "r", j: 1, x: "7E0", s: "2E", i: 1, v: "00" }, // scriere
      { t: "r", j: 1, x: "7E0", s: "22", i: 1, v: "zz" },
      { t: "r", j: 1, x: "7E0", s: "22", i: 1 },
    ]);
    expect(r).toEqual({ ok: 0, skipped: 4 });
  });

  it("queues a parked-only re-read of the slow codes right after a finished range", () => {
    const [engine] = scan.carScanJobs();
    scan.saveScanItems([{ t: "p", j: engine.id, n: 0x10000, d: 1 }]);
    const [retry, next] = scan.carScanJobs();
    expect(retry).toMatchObject({ kind: "list", parked: true, parent: engine.id, ids: [0x0102], tx: "7E0" });
    expect(next.label).toBe("OBD: valorile live");
    // Recitirea terminată nu mai face altă recitire.
    scan.saveScanItems([{ t: "p", j: retry.id, n: 1, d: 1 }]);
    expect(scan.carScanJobs()[0].label).toBe("OBD: valorile live");
  });

  it("fills a loop from the catalog and moves it to the end after each round", () => {
    const db = getDb();
    db.prepare(`UPDATE obd_scan_job SET status = 'done' WHERE kind <> 'loop'`).run();
    const [loop] = scan.carScanJobs();
    expect(loop).toMatchObject({ label: "Recitire: motor", kind: "loop", ids: [0x0100], round: 0 });
    // Progresul unei treceri vechi (retrimis) e ignorat.
    expect(scan.saveScanItems([{ t: "p", j: loop.id, r: 5, n: 1 }]).skipped).toBe(1);
    scan.saveScanItems([
      { t: "r", j: loop.id, x: "7E0", s: "22", i: 0x0100, v: "0A1C", at: Date.parse("2026-11-22T11:00:00Z") },
      { t: "p", j: loop.id, r: 0, n: 1, d: 1 },
    ]);
    const jobs = scan.carScanJobs(10);
    expect(jobs.at(-1)).toMatchObject({ id: loop.id, round: 1, next: 0 });
    const code = scan.readScanCodes("7E0").find((c) => c.id === 0x0100);
    expect(code).toMatchObject({ value: "0A1C", changes: 1 });
    expect(scan.readScanSamples("7E0", "22", 0x0100).map((s) => s.value)).toEqual(["0A1C", "0A1B"]);
  });

  it("skips paused jobs", () => {
    const [first] = scan.carScanJobs();
    scan.setScanJobPaused(first.id, true);
    expect(scan.carScanJobs().some((j) => j.id === first.id)).toBe(false);
    scan.setScanJobPaused(first.id, false);
    expect(scan.carScanJobs().some((j) => j.id === first.id)).toBe(true);
  });
});
