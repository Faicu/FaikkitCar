import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ScanSearch } from "lucide-react";

import { api, relativeTime, type ScanCode, type ScanOverview } from "../api";

type Job = ScanOverview["jobs"][number];

const STATUS: Record<Job["status"], string> = {
  queued: "la rând",
  running: "în curs",
  done: "gata",
  paused: "oprită",
};

const SVC: Record<string, string> = {
  "22": "UDS",
  "01": "OBD live",
  "02": "la eroare",
  "06": "teste",
  "09": "info",
  dtc: "erori",
};

function codeName(c: Pick<ScanCode, "svc" | "id">): string {
  if (c.svc === "22") return c.id.toString(16).toUpperCase().padStart(4, "0");
  if (c.svc === "dtc") return c.id.toString(16).toUpperCase().padStart(2, "0");
  return `${c.svc} ${c.id.toString(16).toUpperCase().padStart(2, "0")}`;
}

/** Octeții în hex, câte doi, și textul lor dacă arată a text (VIN, numere de piesă). */
function bytes(hex: string): { spaced: string; text: string | null } {
  const pairs = hex.match(/../g) ?? [];
  const chars = pairs.map((p) => parseInt(p, 16));
  const printable = chars.filter((x) => x >= 32 && x < 127).length;
  const text = chars.length >= 4 && printable / chars.length > 0.7
    ? chars.map((x) => (x >= 32 && x < 127 ? String.fromCharCode(x) : "·")).join("")
    : null;
  return { spaced: pairs.join(" "), text };
}

function refusal(nrc: number): string {
  if (nrc === 256) return "lent / fără răspuns";
  if (nrc === 0x22) return "condiții neîndeplinite";
  if (nrc === 0x33) return "acces refuzat";
  if (nrc === 0x7e || nrc === 0x7f) return "nu în sesiunea asta";
  return `refuzat (${nrc.toString(16).toUpperCase()})`;
}

function JobRow({ job, current }: { job: Job; current: boolean }) {
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const pct = job.total > 0 ? Math.round((job.done / job.total) * 100) : 0;
  const toggle = async () => {
    setBusy(true);
    try {
      await api.obdScanPause(job.id, job.status !== "paused");
      await qc.invalidateQueries({ queryKey: ["obd-scan"] });
    } finally {
      setBusy(false);
    }
  };
  const loop = job.kind === "loop" || job.kind === "watch";
  const watch = job.kind === "watch";
  return (
    <div className="space-y-1">
      <div className="flex items-baseline gap-2 text-sm">
        <p className={current ? "font-semibold" : ""}>{job.label}</p>
        <p className="text-xs text-muted-foreground">
          {current ? "acum" : STATUS[job.status]}
          {job.parked ? " · doar pe loc" : ""}
          {watch && job.everyS ? ` · la fiecare pornire și la ${Math.round(job.everyS / 60)} min` : ""}
          {loop && job.round > 0 ? ` · trecerea ${job.round + 1}` : ""}
        </p>
        {job.status !== "done" && (
          <button
            type="button"
            disabled={busy}
            onClick={toggle}
            className="ml-auto text-xs text-sky-400 disabled:opacity-40"
          >
            {job.status === "paused" ? "Reia" : "Oprește"}
          </button>
        )}
      </div>
      {job.status !== "done" && job.total > 0 && !watch && (
        <div className="flex items-center gap-2">
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-white/10">
            <div className="h-full rounded-full bg-sky-500" style={{ width: `${pct}%` }} />
          </div>
          <p className="w-24 text-right text-xs tabular-nums text-muted-foreground">
            {job.done}/{job.total} · {pct}%
          </p>
        </div>
      )}
      {loop && job.total === 0 && (
        <p className="text-xs text-muted-foreground">Începe după scanarea modulului (catalogul e gol încă).</p>
      )}
    </div>
  );
}

function Samples({ tx, code }: { tx: string; code: ScanCode }) {
  const { data } = useQuery({
    queryKey: ["obd-samples", tx, code.svc, code.id],
    queryFn: () => api.obdSamples(tx, code.svc, code.id),
  });
  if (!data) return <p className="text-xs text-muted-foreground">Se încarcă…</p>;
  return (
    <div className="max-h-48 space-y-0.5 overflow-y-auto rounded-lg bg-black/20 p-2">
      {data.map((s) => (
        <p key={s.at} className="font-mono text-xs">
          <span className="text-muted-foreground">{new Date(s.at).toLocaleString("ro-RO")}</span> {bytes(s.value).spaced}
        </p>
      ))}
    </div>
  );
}

function Catalog({ tx }: { tx: string }) {
  const [onlyChanging, setOnlyChanging] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const { data } = useQuery({ queryKey: ["obd-codes", tx], queryFn: () => api.obdCodes(tx), staleTime: 30_000 });
  if (!data) return <div className="h-20 skeleton-sweep rounded-xl" />;
  const codes = onlyChanging ? data.filter((c) => c.changes > 0) : data;
  return (
    <div className="space-y-2">
      <label className="flex items-center gap-2 text-xs text-muted-foreground">
        <input type="checkbox" checked={onlyChanging} onChange={(e) => setOnlyChanging(e.target.checked)} />
        Doar cele care se schimbă
      </label>
      <div className="max-h-96 space-y-1 overflow-y-auto">
        {codes.length === 0 && <p className="text-xs text-muted-foreground">Nimic încă.</p>}
        {codes.map((c) => {
          const key = `${c.svc}:${c.id}`;
          const b = c.value ? bytes(c.value) : null;
          return (
            <div key={key} className="rounded-lg bg-white/5 px-2 py-1">
              <button type="button" onClick={() => setOpen(open === key ? null : key)} className="w-full text-left">
                <p className="font-mono text-xs">
                  <span className="font-semibold">{codeName(c)}</span>{" "}
                  <span className="text-muted-foreground">{SVC[c.svc] ?? c.svc}</span>{" "}
                  {b ? b.spaced : <span className="text-amber-400">{refusal(c.nrc ?? 0)}</span>}
                </p>
                <p className="text-xs text-muted-foreground">
                  {b?.text ? `„${b.text}” · ` : ""}
                  {c.changes > 0 ? `s-a schimbat de ${c.changes} ori · ` : ""}
                  {c.reads} citiri · ultima {relativeTime(c.lastAt)}
                </p>
              </button>
              {open === key && <Samples tx={tx} code={c} />}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Scanarea OBD, pe fila Navigație: lucrările în ordine (mașina le face pe rând cu motorul pornit,
 * rezultatele vin singure), ce s-a găsit pe fiecare modul și catalogul cu istoricul valorilor.
 */
export function ObdScanCard() {
  const { data } = useQuery({ queryKey: ["obd-scan"], queryFn: api.obdScan, refetchInterval: 15_000 });
  const [module, setModule] = useState<string | null>(null);
  const [showDone, setShowDone] = useState(false);
  if (!data) return <div className="h-40 skeleton-sweep rounded-2xl" />;
  const active = data.jobs.filter((j) => j.status !== "done");
  const done = data.jobs.filter((j) => j.status === "done");
  return (
    <div className="space-y-3 rounded-2xl glass-card p-4">
      <div className="flex items-center gap-2">
        <ScanSearch className="h-4 w-4 shrink-0 text-sky-400" />
        <p className="font-semibold">Scanarea OBD</p>
        <p className="ml-auto text-xs text-muted-foreground">cu motorul pornit, pe loc și în mers</p>
      </div>
      <div className="space-y-3">
        {active.map((j) => (
          <JobRow key={j.id} job={j} current={j.id === data.current} />
        ))}
      </div>
      {done.length > 0 && (
        <div className="space-y-1">
          <button type="button" onClick={() => setShowDone(!showDone)} className="text-xs text-sky-400">
            {showDone ? "Ascunde" : "Arată"} terminatele ({done.length})
          </button>
          {showDone &&
            done.map((j) => (
              <p key={j.id} className="text-xs text-muted-foreground">
                {j.label} · gata {j.doneAt ? relativeTime(j.doneAt) : ""}
              </p>
            ))}
        </div>
      )}
      <div className="space-y-1 border-t border-white/10 pt-3">
        {data.modules.map((m) => (
          <button
            key={m.tx}
            type="button"
            onClick={() => setModule(module === m.tx ? null : m.tx)}
            className={`block w-full rounded-lg px-2 py-1 text-left text-sm ${module === m.tx ? "bg-white/10" : ""}`}
          >
            <span className="font-semibold">{m.name}</span>{" "}
            <span className="text-xs text-muted-foreground">
              {m.codes} coduri · {m.changing} se schimbă
              {m.refused > 0 ? ` · ${m.refused} refuzate` : ""}
              {m.slow > 0 ? ` · ${m.slow} lente` : ""}
            </span>
          </button>
        ))}
      </div>
      {module && <Catalog tx={module} />}
    </div>
  );
}
