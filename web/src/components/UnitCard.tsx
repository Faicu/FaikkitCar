import { useEffect, useState } from "react";
import { AlertTriangle, Cpu } from "lucide-react";

import { api, duration, num, relativeTime, type Live, type UnitPoint, type UnitStats } from "../api";

// Fără măsurare nouă atâta timp, navigația doarme (sau aplicația nu rulează).
const STALE_MS = 90_000;
// Între două măsurări mai departe de atât, linia se întrerupe (somn, aplicația oprită).
const GAP_MS = 120_000;

/**
 * Performanța navigației (Teyes), din aceeași stare live: procesorul, memoria, temperatura,
 * cu graficele pe ultima oră, și procesele care consumă cel mai mult.
 */
export function UnitCard({ unit, health }: { unit: UnitStats | null; health: Live["unitHealth"] }) {
  const history = useUnitHistory(unit?.at ?? null);
  if (!unit) return null;
  const awake = Date.now() - new Date(unit.at).getTime() < STALE_MS;
  const swapUsed = unit.swapTotal !== null && unit.swapFree !== null ? unit.swapTotal - unit.swapFree : null;
  const procs = [...(unit.procs ?? [])].sort((a, b) => (b.cpu ?? 0) - (a.cpu ?? 0) || b.mb - a.mb);
  const facts: string[] = [];
  if (unit.memTotal !== null) facts.push(`${num(unit.memTotal / 1024, 1)} GB memorie`);
  if (swapUsed !== null && unit.swapTotal) facts.push(`swap ${Math.round((swapUsed / unit.swapTotal) * 100)}% plin`);
  if (unit.uptime !== null) facts.push(`pornită de ${duration(unit.uptime / 60)}`);
  if (unit.load !== null) facts.push(`încărcare ${num(unit.load, 1)}${unit.cores ? ` (${unit.cores} nuclee)` : ""}`);
  if (unit.appMb !== null) facts.push(`FaikkitCar ${unit.appMb} MB${unit.appAdj !== null ? `, adj ${unit.appAdj}` : ""}`);
  if (unit.diskGb !== null) facts.push(`liber ${num(unit.diskGb, 1)} GB`);
  return (
    <div className="space-y-3 rounded-2xl glass-card p-4">
      <div className="flex items-center gap-2">
        <Cpu className={`h-4 w-4 shrink-0 ${awake ? "text-sky-400" : "text-muted-foreground"}`} />
        <p className="font-semibold">Navigația</p>
        <p className="ml-auto text-xs text-muted-foreground">
          {awake ? "trează · acum" : `doarme · ultima măsurare ${relativeTime(unit.at)}`}
        </p>
      </div>
      {health && health.level !== "ok" && (
        <p className={`flex items-center gap-1.5 text-sm font-medium ${health.level === "bad" ? "text-red-400" : "text-amber-400"}`}>
          <AlertTriangle className="h-4 w-4 shrink-0" /> {health.text}
        </p>
      )}
      {health?.level === "ok" && <p className="text-sm">{health.text[0].toUpperCase() + health.text.slice(1)}</p>}
      <div className="grid grid-cols-3 gap-2">
        <Spark
          label="procesor"
          unit="%"
          now={unit.cpu}
          digits={0}
          history={history}
          pick={(p) => p.cpu}
          max={100}
        />
        <Spark
          label="memorie liberă"
          unit="MB"
          now={unit.memAvail}
          digits={0}
          history={history}
          pick={(p) => p.memAvail}
          max={unit.memTotal ?? undefined}
        />
        <Spark label="temperatură" unit="°C" now={unit.temp} digits={0} history={history} pick={(p) => p.temp} />
      </div>
      {facts.length > 0 && <p className="text-xs text-muted-foreground">{facts.join(" · ")}</p>}
      {awake && procs.length > 0 && (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[11px] text-muted-foreground">
              <th className="font-normal">Procese (ultimele ~30 s)</th>
              <th className="w-16 text-right font-normal">procesor</th>
              <th className="w-20 text-right font-normal">memorie</th>
            </tr>
          </thead>
          <tbody>
            {procs.map((p, i) => (
              <tr key={i} className="border-t border-white/5">
                <td className="max-w-0 truncate py-1 pr-2" title={p.name}>
                  {p.label}
                </td>
                <td className="py-1 text-right tabular-nums">{p.cpu !== null ? `${num(p.cpu, 1)}%` : "—"}</td>
                <td className="py-1 text-right tabular-nums">{p.mb} MB</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** Istoricul pe ultima oră, cerut din nou la cel mult un minut (când vin măsurări noi). */
function useUnitHistory(at: string | null): UnitPoint[] {
  const [history, setHistory] = useState<UnitPoint[]>([]);
  const [fetchedAt, setFetchedAt] = useState(0);
  useEffect(() => {
    if (!at || Date.now() - fetchedAt < 60_000) return;
    setFetchedAt(Date.now());
    api.unitHistory(60).then(setHistory, () => {});
  }, [at, fetchedAt]);
  return history;
}

/**
 * O valoare cu graficul ei pe ultima oră (o singură serie, deci fără legendă). La atingere /
 * hover, valoarea și ora punctului cel mai apropiat iau locul celei de acum.
 */
function Spark({
  label,
  unit,
  now,
  digits,
  history,
  pick,
  max,
}: {
  label: string;
  unit: string;
  now: number | null;
  digits: number;
  history: UnitPoint[];
  pick: (p: UnitPoint) => number | null;
  max?: number;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const end = Date.now();
  const start = end - 60 * 60_000;
  const pts = history
    .map((p) => ({ t: new Date(p.at).getTime(), v: pick(p) }))
    .filter((p): p is { t: number; v: number } => p.v !== null && p.t >= start);
  const values = pts.map((p) => p.v);
  const lo = max !== undefined ? 0 : Math.min(...values, now ?? Infinity) - 2;
  const hi = max ?? Math.max(...values, now ?? -Infinity) + 2;
  const W = 120;
  const H = 36;
  const x = (t: number) => ((t - start) / (end - start)) * W;
  const y = (v: number) => H - 1 - ((v - lo) / (hi - lo || 1)) * (H - 2);
  let d = "";
  pts.forEach((p, i) => {
    const jump = i === 0 || p.t - pts[i - 1].t > GAP_MS;
    d += `${jump ? "M" : "L"}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`;
  });
  const h = hover !== null ? pts[hover] : null;
  const shown = h ? h.v : now;
  function onMove(e: React.PointerEvent<SVGSVGElement>) {
    if (pts.length === 0) return;
    const r = e.currentTarget.getBoundingClientRect();
    const t = start + ((e.clientX - r.left) / r.width) * (end - start);
    let best = 0;
    for (let i = 1; i < pts.length; i++) if (Math.abs(pts[i].t - t) < Math.abs(pts[best].t - t)) best = i;
    setHover(best);
  }
  return (
    <div className="rounded-xl bg-white/[0.04] px-2.5 py-2">
      <p className="whitespace-nowrap text-lg font-semibold leading-tight">
        {shown !== null ? num(shown, digits) : "—"}
        <span className="ml-0.5 text-xs font-normal text-muted-foreground">{unit}</span>
        {h && (
          <span className="ml-1.5 text-xs font-normal text-muted-foreground">
            la {new Date(h.t).toLocaleTimeString("ro-RO", { hour: "2-digit", minute: "2-digit" })}
          </span>
        )}
      </p>
      <p className="truncate text-[11px] text-muted-foreground">{label}</p>
      {pts.length > 1 && (
        <svg
          viewBox={`0 0 ${W} ${H}`}
          preserveAspectRatio="none"
          className="mt-1 h-9 w-full touch-none"
          onPointerMove={onMove}
          onPointerDown={onMove}
          onPointerLeave={() => setHover(null)}
          role="img"
          aria-label={`${label}, ultima oră`}
        >
          <line x1="0" x2={W} y1={H - 0.5} y2={H - 0.5} className="stroke-white/10" strokeWidth="1" vectorEffect="non-scaling-stroke" />
          <path d={d} fill="none" className="stroke-sky-400" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
          {/* Doar linia verticală: cu preserveAspectRatio="none" un cerc ar ieși oval. */}
          {h && <line x1={x(h.t)} x2={x(h.t)} y1="0" y2={H} className="stroke-white/40" strokeWidth="1" vectorEffect="non-scaling-stroke" />}
        </svg>
      )}
      {pts.length > 1 && <p className="flex justify-between text-[10px] text-muted-foreground"><span>−1 h</span><span>acum</span></p>}
    </div>
  );
}
