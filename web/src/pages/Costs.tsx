import { useQuery } from "@tanstack/react-query";

import { Stat } from "../components/Cells";
import { FuelLog } from "../components/FuelLog";
import { api, duration, lei, liters, num, type MonthStats, type Trip } from "../api";

const LIVE = { refetchInterval: 30_000, staleTime: 15_000 };

function monthName(m: string): string {
  const s = new Date(`${m}-15T12:00:00Z`).toLocaleDateString("ro-RO", { month: "long", year: "numeric" });
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Fila Costuri: ultimele 30 de zile, pe luni și alimentările. */
export function CostsPage() {
  const { data: trips } = useQuery({ queryKey: ["trips"], queryFn: api.trips, ...LIVE });
  const { data: stats } = useQuery({ queryKey: ["stats"], queryFn: api.stats, ...LIVE });
  const { data: fuel } = useQuery({ queryKey: ["fuel"], queryFn: api.fuel, ...LIVE });
  return (
    <>
      {trips && <Last30 trips={trips} />}
      {stats && stats.length > 0 && <Monthly months={stats} />}
      {fuel && <FuelLog fuel={fuel} />}
    </>
  );
}

function Last30({ trips }: { trips: Trip[] }) {
  // Totalurile includ și pornirile pe loc (consumă combustibil); doar numărul le exclude.
  const month = trips.filter((t) => Date.now() - new Date(t.start).getTime() < 30 * 86_400_000);
  const km = month.reduce((s, t) => s + t.distanceKm, 0);
  const min = month.reduce((s, t) => s + t.durationMin, 0);
  const l = month.reduce((s, t) => s + (t.fuelL ?? 0), 0);
  const cost = month.some((t) => t.cost !== null) ? month.reduce((s, t) => s + (t.cost ?? 0), 0) : null;
  return (
    <div className="space-y-2">
      <h2 className="px-1 text-sm font-semibold text-muted-foreground">Ultimele 30 de zile</h2>
      <div className="grid grid-cols-3 gap-2">
        <Stat label="Călătorii" value={String(month.filter((t) => !t.idle).length)} />
        <Stat label="Distanță" value={`${Math.round(km)} km`} />
        <Stat label="Timp la volan" value={duration(min)} />
        <Stat label="Combustibil (est.)" value={`≈ ${liters(l)}`} />
        <Stat label="Consum (est.)" value={km >= 1 ? `${num((l / km) * 100)} L/100` : "—"} />
        <Stat label="Cost (est.)" value={cost !== null ? lei(cost) : "—"} />
      </div>
    </div>
  );
}

/** Lunile, cu bare pentru km și lei (scara = luna cea mai mare). */
function Monthly({ months }: { months: MonthStats[] }) {
  const maxKm = Math.max(1, ...months.map((m) => m.km));
  const maxLei = Math.max(1, ...months.map((m) => m.cost ?? 0));
  return (
    <div className="space-y-2 rounded-2xl glass-card p-4">
      <h2 className="font-semibold">Pe luni</h2>
      {months.map((m) => (
        <div key={m.month} className="space-y-1 border-t border-border/30 pt-2 first:border-0 first:pt-0">
          <div className="flex items-baseline justify-between">
            <span className="font-medium">{monthName(m.month)}</span>
            <span className="text-xs text-muted-foreground">
              {m.trips} călătorii · {duration(m.minutes)}
            </span>
          </div>
          <Bar value={m.km} max={maxKm} color="bg-sky-400" label={`${num(m.km)} km`} />
          <Bar
            value={m.cost ?? 0}
            max={maxLei}
            color="bg-amber-400"
            label={m.cost !== null ? `${lei(m.cost)} (est.)` : "—"}
          />
          <p className="text-xs text-muted-foreground">
            ≈ {liters(m.liters)}
            {m.lPer100 !== null ? ` · ${num(m.lPer100)} L/100 km` : ""}
            {m.refuelLiters > 0
              ? ` · alimentat ${liters(m.refuelLiters)}${m.refuelLei !== null ? ` (${lei(m.refuelLei)})` : ""}`
              : ""}
          </p>
        </div>
      ))}
    </div>
  );
}

function Bar({ value, max, color, label }: { value: number; max: number; color: string; label: string }) {
  return (
    <div className="flex items-center gap-2">
      <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-white/5">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${(value / max) * 100}%` }} />
      </div>
      <span className="w-28 text-right text-xs tabular-nums">{label}</span>
    </div>
  );
}
