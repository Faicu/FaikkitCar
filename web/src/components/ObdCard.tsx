import { useState } from "react";
import { AlertTriangle, Plug } from "lucide-react";

import { api, num, relativeTime, type Live } from "../api";

type Obd = Live["obd"];

const STATUS: Record<NonNullable<Obd["status"]>, string> = {
  connected: "conectat",
  searching: "caută adaptorul",
  released: "eliberat pentru Car Scanner",
  off: "motor oprit (adaptorul e liber)",
};

const FAULT_STATE = {
  active: "prezentă acum",
  stored: "memorată (intermitentă)",
  pending: "în așteptare (nu s-a confirmat încă)",
} as const;

/**
 * Erorile mașinii, pe Acum: din toate modulele citite (motorul prin OBD și UDS, bordul,
 * climatronicul, airbag-ul), doar când există vreuna sau martorul motorului e aprins (cererea
 * utilizatorului, 21.11: orice eroare, nu doar martorii din bord; fără notificări).
 */
export function DtcCard({ obd }: { obd: Obd }) {
  const faults = obd.faults ?? [];
  if (!obd.mil && faults.length === 0) return null;
  return (
    <div className="space-y-2 rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4">
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-5 w-5 shrink-0 text-amber-400" />
        <p className="font-semibold">
          {faults.length === 1 ? "O eroare" : faults.length > 1 ? `${faults.length} erori` : "Martorul motorului e aprins"}
        </p>
      </div>
      {faults.map((f) => (
        <div key={`${f.module}-${f.code}`} className="text-sm">
          <p>
            <span className="text-muted-foreground">{f.module}</span> · <span className="font-mono font-semibold">{f.code}</span>{" "}
            · {f.text}
          </p>
          <p className="text-xs text-muted-foreground">
            {FAULT_STATE[f.state]} · apărută {relativeTime(f.since)}
          </p>
        </div>
      ))}
      <p className="text-xs text-muted-foreground">
        {obd.mil ? "Martorul motorului e aprins. " : ""}
        {obd.checkedAt ? `Verificat ${relativeTime(obd.checkedAt)}.` : ""}
      </p>
    </div>
  );
}

/** Adaptorul OBD pe fila Navigație: starea legăturii și eliberarea pentru Car Scanner. */
export function ObdCard({ obd }: { obd: Obd }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const released = obd.releasedFor !== null;
  const release = async (minutes: number) => {
    setBusy(true);
    setError(null);
    try {
      await api.obdRelease(minutes);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const status = obd.status ? STATUS[obd.status] : "fără semnal de la mașină";
  return (
    <div className="space-y-3 rounded-2xl glass-card p-4">
      <div className="flex items-center gap-2">
        <Plug className={`h-4 w-4 shrink-0 ${obd.status === "connected" ? "text-sky-400" : "text-muted-foreground"}`} />
        <p className="font-semibold">Adaptorul OBD</p>
        <p className="ml-auto text-xs text-muted-foreground">
          {status}
          {obd.status === "connected" && obd.hz !== null ? ` · ${num(obd.hz, 1)} citiri/s` : ""}
          {released ? ` · încă ${Math.max(1, Math.round((obd.releasedFor ?? 0) / 60))} min` : ""}
        </p>
      </div>
      <p className="text-xs text-muted-foreground">
        {obd.checkedAt
          ? `Motorul (OBD): ${obd.codes.length === 0 ? "nicio eroare" : obd.codes.map((c) => c.code).join(", ")}${obd.mil ? ", martorul aprins" : ""} · verificat ${relativeTime(obd.checkedAt)}`
          : "Erorile nu au fost citite încă."}
      </p>
      {(obd.modules ?? []).map((m) => (
        <p key={m.module} className="text-xs text-muted-foreground">
          {m.name}: {!m.ok ? "n-a răspuns" : m.count === 0 ? "nicio eroare" : m.count === 1 ? "o eroare" : `${m.count} erori`} ·
          verificat {relativeTime(m.checkedAt)}
        </p>
      ))}
      <button
        type="button"
        disabled={busy}
        onClick={() => release(released ? 0 : 10)}
        className="w-full rounded-lg bg-sky-500 px-3 py-2 font-semibold text-slate-950 disabled:opacity-40"
      >
        {released ? "Reia acum" : "Eliberează 10 min (Car Scanner)"}
      </button>
      <p className="text-xs text-muted-foreground">
        Doar cu motorul pornit aplicația ține adaptorul; după eliberare apare pentru telefon în 1–3 min.
      </p>
      {error && <p className="text-xs text-red-400">{error}</p>}
    </div>
  );
}
