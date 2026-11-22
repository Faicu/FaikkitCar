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

  it("seeds the order once: the short standard OBD first, then the engine", () => {
    expect(scan.seedScanJobs()).toBe(0);
    const jobs = scan.carScanJobs();
    expect(jobs.map((j) => j.label)).toEqual(scan.SEED.slice(0, 3).map((s) => s.label));
    expect(jobs[0]).toMatchObject({ tx: "7E0", svc: "01", kind: "range", first: 0, last: 0xff });
    // Testele de mai jos lucrează pe motor: OBD-ul standard e gata.
    getDb().prepare(`UPDATE obd_scan_job SET status = 'done' WHERE svc IN ('01', '09', '06', '02', 'dtc')`).run();
    expect(scan.carScanJobs()[0]).toMatchObject({ tx: "7E0", rx: "7E8", svc: "22", kind: "range", first: 0, last: 0xffff, next: 0 });
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
    // Restul intervalului verificat: nu există (altfel ar intra toate la recitit, ca pierdute).
    const db = getDb();
    const abs = db.prepare(`INSERT INTO obd_absent (tx, svc, id, at) VALUES ('7E0', '22', ?, 'x')`);
    db.exec("BEGIN");
    for (let id = 0; id <= 0xffff; id++) if (id < 0x0100 || id > 0x0102) abs.run(id);
    db.exec("COMMIT");
    scan.saveScanItems([{ t: "p", j: engine.id, n: 0x10000, d: 1 }]);
    const [retry, next] = scan.carScanJobs();
    expect(retry).toMatchObject({ kind: "list", parked: true, parent: engine.id, ids: [0x0102], tx: "7E0" });
    expect(next.label).toBe("Bord: toate codurile (a doua trecere)");
    // Recitirea terminată nu mai face altă recitire.
    scan.saveScanItems([{ t: "p", j: retry.id, n: 1, d: 1 }]);
    expect(scan.carScanJobs()[0].label).toBe("Bord: toate codurile (a doua trecere)");
  });

  it("fills a loop from the catalog and moves it to the end after each round", () => {
    const db = getDb();
    db.prepare(`UPDATE obd_scan_job SET status = 'done' WHERE kind <> 'loop'`).run();
    const [loop] = scan.carScanJobs();
    // 0x0101 a fost refuzat de condiții (0x22): se reîncearcă; 0x0102 (lent) are recitirea lui.
    expect(loop).toMatchObject({ label: "Recitire: motor", kind: "loop", ids: [0x0100, 0x0101], round: 0 });
    // Progresul unei treceri vechi (retrimis) e ignorat.
    expect(scan.saveScanItems([{ t: "p", j: loop.id, r: 5, n: 1 }]).skipped).toBe(1);
    scan.saveScanItems([
      { t: "r", j: loop.id, x: "7E0", s: "22", i: 0x0100, v: "0A1C", at: Date.parse("2026-11-22T11:00:00Z") },
      { t: "p", j: loop.id, r: 0, n: 2, d: 1 },
    ]);
    const jobs = scan.carScanJobs(10);
    expect(jobs.at(-1)).toMatchObject({ id: loop.id, round: 1, next: 0 });
    const code = scan.readScanCodes("7E0").find((c) => c.id === 0x0100);
    expect(code).toMatchObject({ value: "0A1C", changes: 1 });
    expect(scan.readScanSamples("7E0", "22", 0x0100).map((s) => s.value)).toEqual(["0A1C", "0A1B"]);
  });

  it("re-reads a refused code in a loop and drops a slow code found missing", () => {
    const [loop] = scan.carScanJobs();
    scan.saveScanItems([
      { t: "r", j: loop.id, x: "7E0", s: "22", i: 0x0300, n: 0x22 },
      { t: "r", j: loop.id, x: "7E0", s: "22", i: 0x0301, n: scan.NRC_SLOW },
      { t: "r", j: loop.id, x: "7E0", s: "22", i: 0x0301, n: scan.NRC_MISSING },
      { t: "r", j: loop.id, x: "7E0", s: "22", i: 0x0100, n: scan.NRC_MISSING }, // bun: rămâne
      { t: "p", j: loop.id, r: loop.round, n: loop.ids!.length, d: 1 },
    ]);
    const codes = scan.readScanCodes("7E0");
    expect(codes.some((c) => c.id === 0x0301)).toBe(false);
    expect(codes.find((c) => c.id === 0x0100)?.value).toBe("0A1C");
    expect(scan.carScanJobs(10).find((j) => j.id === loop.id)?.ids).toEqual([0x0100, 0x0101, 0x0300]);
  });

  it("puts the watch jobs first, as loops for the car, and rests them between passes", () => {
    expect(scan.seedWatchJobs("2026-11-22T00:00:00.000Z")).toBe(scan.WATCH.length);
    expect(scan.seedWatchJobs()).toBe(0);
    const t0 = Date.parse("2026-11-22T12:00:00Z");
    const [w] = scan.carScanJobs(3, t0);
    expect(w).toMatchObject({ label: scan.WATCH[0].label, tx: "714", kind: "loop", round: 0, ids: scan.WATCH[0].ids });
    scan.saveScanItems([{ t: "p", j: w.id, r: 0, n: w.ids!.length, d: 1 }]);
    const passAt = Date.now();
    const rest = scan.carScanJobs(10, passAt + 60_000);
    expect(rest.some((j) => j.id === w.id)).toBe(false);
    const back = scan.carScanJobs(3, passAt + 121_000);
    expect(back[0]).toMatchObject({ id: w.id, round: 1, next: 0, ids: scan.WATCH[0].ids });
  });

  it("records missing codes in groups and re-reads what got no clear answer", () => {
    const db = getDb();
    const ins = db.prepare(
      `INSERT INTO obd_scan_job (ord, label, tx, rx, svc, kind, first, last, status, created_at)
       VALUES (0.05, 'Test: interval', '746', '7B0', '22', 'range', 8192, 8199, 'running', '2026-11-22T00:00:00Z')`,
    );
    const id = Number(ins.run().lastInsertRowid);
    scan.saveScanItems([
      { t: "r", j: id, x: "746", s: "22", i: 0x2000, n: scan.NRC_MISSING, c: 3 },
      { t: "r", j: id, x: "746", s: "22", i: 0x2003, v: "01" },
      { t: "r", j: id, x: "746", s: "22", i: 0x2004, n: scan.NRC_MISSING },
      { t: "r", j: id, x: "746", s: "22", i: 0x2005, n: scan.NRC_SLOW },
      // 0x2006, 0x2007: niciun răspuns (pierdute)
    ]);
    expect(scan.unresolved("746", "22", 0x2000, 0x2007)).toEqual([0x2005, 0x2006, 0x2007]);
    expect(scan.readScanOverview().modules.find((m) => m.tx === "746")?.absent).toBe(4);
    scan.saveScanItems([{ t: "p", j: id, n: 0x2008, d: 1 }]);
    const retry = scan.carScanJobs(10).find((j) => j.parent === id);
    expect(retry).toMatchObject({ kind: "list", parked: true, ids: [0x2005, 0x2006, 0x2007] });
    // Un cod bun găsit mai târziu iese dintre cele care nu există.
    scan.saveScanItems([{ t: "r", j: retry!.id, x: "746", s: "22", i: 0x2004, v: "02" }]);
    expect(scan.readScanOverview().modules.find((m) => m.tx === "746")?.absent).toBe(3);
  });

  it("gives an old app only the watch lists", () => {
    const later = Date.now() + 10 * 60_000;
    const old = scan.carScanJobsFor(false, 3, later);
    expect(old.length).toBeGreaterThan(0);
    expect(old.every((j) => j.label.startsWith("Urmărire:"))).toBe(true);
    expect(scan.carScanJobsFor(true, 10, later).some((j) => !j.label.startsWith("Urmărire:"))).toBe(true);
  });

  it("does not re-read unsupported OBD PIDs after a standard OBD range", () => {
    const obd01 = scan.carScanJobs(10).find((j) => j.label === "OBD: valorile live");
    if (!obd01) {
      // Terminată deja într-un test de mai sus: o refacem.
      getDb().prepare(`UPDATE obd_scan_job SET status = 'running' WHERE label = 'OBD: valorile live'`).run();
    }
    const j = scan.carScanJobs(20).find((x) => x.label === "OBD: valorile live")!;
    scan.saveScanItems([
      { t: "r", j: j.id, x: "7E0", s: "01", i: 0x0c, v: "0BB8" },
      { t: "p", j: j.id, r: j.round, n: 0x100, d: 1 },
    ]);
    expect(scan.carScanJobs(20).some((x) => x.parent === j.id)).toBe(false);
  });

  it("keeps a paused watch paused after its pass", () => {
    const w = scan.carScanJobs(20, Date.now() + 10 * 60_000).find((x) => x.label === scan.WATCH[1].label)!;
    scan.setScanJobPaused(w.id, true);
    scan.saveScanItems([{ t: "p", j: w.id, r: w.round, n: w.ids!.length, d: 1 }]);
    expect(scan.readScanOverview().jobs.find((x) => x.id === w.id)?.status).toBe("paused");
    scan.setScanJobPaused(w.id, false);
  });

  it("skips paused jobs", () => {
    const [first] = scan.carScanJobs();
    scan.setScanJobPaused(first.id, true);
    expect(scan.carScanJobs().some((j) => j.id === first.id)).toBe(false);
    scan.setScanJobPaused(first.id, false);
    expect(scan.carScanJobs().some((j) => j.id === first.id)).toBe(true);
  });
});

describe("jobEtas", () => {
  const job = (id: number, over: Partial<Parameters<typeof scan.jobEtas>[0][number]> = {}) => ({
    id, kind: "range" as const, status: "queued" as const, parked: false, svc: "22" as const, done: 0, total: 1000, ...over,
  });
  it("adds the jobs ahead to the end time and skips watches, loops, paused and parked ones", () => {
    const m = scan.jobEtas(
      [
        job(1, { kind: "watch", total: 20 }),
        job(2, { status: "running", done: 500 }),
        job(3, { status: "paused" }),
        job(4, { parked: true }),
        job(5, { kind: "loop", total: 99 }),
        job(6, { total: 2000 }),
        job(7, { status: "done", done: 1000 }),
      ],
      () => 10,
    );
    expect([...m.keys()]).toEqual([2, 6]);
    expect(m.get(2)).toEqual({ etaS: 50, endsS: 50 });
    expect(m.get(6)).toEqual({ etaS: 200, endsS: 250 });
  });
  it("measures the rate from progress, ignoring pauses and bursts", () => {
    const j = { id: 901, svc: "22" as const, kind: "range" as const };
    scan.noteProgress(901, "22:r", 1000, 0);
    scan.noteProgress(901, "22:r", 1600, 10_000); // 60/s
    scan.noteProgress(901, "22:r", 5000, 10_500); // rafală: dt < 3 s, ignorat
    scan.noteProgress(901, "22:r", 9000, 600_000); // pauză: dt > 90 s, ignorat
    expect(scan.jobEtas([{ ...j, status: "running", parked: false, done: 0, total: 600 }], (x) => scan.rateFor(x))
      .get(901)?.etaS).toBe(10);
  });
});
