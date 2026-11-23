import { useState } from "react";
import { AlertTriangle, Plug } from "lucide-react";

import { api, num, relativeTime, type Live } from "../api";

type Obd = Live["obd"];

export const STATUS: Record<NonNullable<Obd["status"]>, string> = {
  connected: "conectat",
  searching: "caută adaptorul",
  off: "motor oprit (adaptorul e liber)",
  disabled: "oprit (adaptorul e liber)",
};

const FAULT_STATE = {
  active: "prezentă acum",
  stored: "memorată (intermitentă)",
  pending: "în așteptare (nu s-a confirmat încă)",
} as const;

/**
 * Erorile mașinii, pe Navigație (pe Acum până la 21.11: utilizatorul nu le poate rezolva imediat și
 * ocupau loc): din toate modulele citite (motorul prin OBD și UDS, bordul, climatronicul,
 * airbag-ul), doar când există vreuna sau martorul motorului e aprins; fără notificări.
 */
export function DtcCard({ obd }: { obd: Obd }) {
  const faults = obd.faults ?? [];
  if (!obd.enabled || (!obd.mil && faults.length === 0)) return null;
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
          {f.detail && <p className="mt-1 text-xs">{f.detail}</p>}
        </div>
      ))}
      <p className="text-xs text-muted-foreground">
        {obd.mil ? "Martorul motorului e aprins. " : ""}
        {obd.checkedAt ? `Verificat ${relativeTime(obd.checkedAt)}.` : ""}
      </p>
    </div>
  );
}

/**
 * Adaptorul OBD pe fila Navigație: starea legăturii și comutatorul „OBD (teste)”, pornit
 * implicit. Oprit = mașina lasă adaptorul (Car Scanner îl poate lua) și nu citește nimic;
 * călătoriile și costurile nu depind de OBD.
 */
export function ObdCard({ obd }: { obd: Obd }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toggle = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.obdEnable(!obd.enabled);
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
        <p className="font-semibold">Adaptorul OBD (teste)</p>
        <p className="ml-auto text-xs text-muted-foreground">
          {status}
          {obd.status === "connected" && obd.scanHz !== null ? ` · scanează ${obd.scanHz} coduri/s` : ""}
          {obd.status === "connected" && obd.scanHz === null && obd.hz !== null ? ` · ${num(obd.hz, 1)} citiri/s` : ""}
        </p>
      </div>
      {obd.enabled && (
        <>
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
        </>
      )}
      <button
        type="button"
        disabled={busy}
        onClick={toggle}
        className={`w-full rounded-lg px-3 py-2 font-semibold disabled:opacity-40 ${obd.enabled ? "bg-white/10" : "bg-sky-500 text-slate-950"}`}
      >
        {obd.enabled ? "Oprește OBD" : "Pornește OBD"}
      </button>
      <p className="text-xs text-muted-foreground">
        {obd.enabled
          ? "Oprit, mașina lasă adaptorul liber (pentru Car Scanner) și nu mai citește și nu mai scanează nimic. Călătoriile și costurile merg la fel."
          : "Adaptorul e liber: Car Scanner îl poate folosi (apare pe telefon în 1–3 min). Călătoriile și costurile merg la fel."}
      </p>
      {error && <p className="text-xs text-red-400">{error}</p>}
    </div>
  );
}
