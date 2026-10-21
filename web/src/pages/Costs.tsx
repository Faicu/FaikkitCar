import { useQuery } from "@tanstack/react-query";

import { Stat } from "../components/Cells";
import { FuelLog } from "../components/FuelLog";
import {
  api,
  day,
  duration,
  hm,
  lei,
  liters,
  num,
  type Commute,
  type MonthStats,
  type PeriodStats,
  type RouteStats,
} from "../api";

const LIVE = { refetchInterval: 30_000, staleTime: 15_000 };

function monthName(m: string): string {
  const s = new Date(`${m}-15T12:00:00Z`).toLocaleDateString("ro-RO", { month: "long", year: "numeric" });
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Fila Costuri: ultimele 30 de zile, pe luni și alimentările (totalurile vin de la server). */
export function CostsPage() {
  const { data: stats } = useQuery({ queryKey: ["stats"], queryFn: api.stats, ...LIVE });
  const { data: fuel } = useQuery({ queryKey: ["fuel"], queryFn: api.fuel, ...LIVE });
  return (
    <>
      {stats && stats.months.length > 0 && <MonthReport months={stats.months} />}
      {stats?.commute && <CommuteCard c={stats.commute} />}
      {stats && stats.routes.length > 0 && <Routes routes={stats.routes} />}
      {stats && <Last30 s={stats.last30} />}
      {stats && stats.months.length > 0 && <Monthly months={stats.months} />}
      {fuel && <FuelLog fuel={fuel} />}
    </>
  );
}

function Last30({ s }: { s: PeriodStats }) {
  return (
    <div className="space-y-2">
      <h2 className="px-1 text-sm font-semibold text-muted-foreground">Ultimele 30 de zile</h2>
      <div className="grid grid-cols-3 gap-2">
        <Stat label="Călătorii" value={String(s.trips)} />
        <Stat label="Distanță" value={`${Math.round(s.km)} km`} />
        <Stat label="Timp la volan" value={duration(s.minutes)} />
        <Stat label="Combustibil (est.)" value={`≈ ${liters(s.liters)}`} />
        <Stat label="Consum (est.)" value={s.lPer100 !== null ? `${num(s.lPer100)} L/100` : "—"} />
        <Stat label="Cost (est.)" value={s.cost !== null ? lei(s.cost) : "—"} />
      </div>
      {s.trafficMin > 0 && (
        <p className="px-1 text-xs text-muted-foreground">
          Din timpul la volan, {duration(s.trafficMin)} oprit în trafic.
        </p>
      )}
    </div>
  );
}

/**
 * Raportul lunii: luna curentă față de cea trecută (întreagă), cu diferențele, cea mai lungă
 * călătorie și locurile unde ai ajuns cel mai des.
 */
function MonthReport({ months }: { months: MonthStats[] }) {
  const [cur, prev] = months;
  const rows: Array<{ label: string; value: string; delta: number | null; lowerIsBetter?: boolean }> = [
    { label: "Distanță", value: `${num(cur.km)} km`, delta: change(cur.km, prev?.km) },
    { label: "Călătorii", value: String(cur.trips), delta: change(cur.trips, prev?.trips) },
    { label: "Timp la volan", value: duration(cur.minutes), delta: change(cur.minutes, prev?.minutes) },
    { label: "Oprit în trafic", value: duration(cur.trafficMin), delta: change(cur.trafficMin, prev?.trafficMin), lowerIsBetter: true },
    { label: "Combustibil (est.)", value: `≈ ${liters(cur.liters)}`, delta: change(cur.liters, prev?.liters), lowerIsBetter: true },
    {
      label: "Consum (est.)",
      value: cur.lPer100 !== null ? `${num(cur.lPer100)} L/100` : "—",
      delta: change(cur.lPer100, prev?.lPer100),
      lowerIsBetter: true,
    },
    {
      label: "Cost (est.)",
      value: cur.cost !== null ? lei(cur.cost) : "—",
      delta: change(cur.cost, prev?.cost),
      lowerIsBetter: true,
    },
    {
      label: "Staționare (motor pornit)",
      value: `${duration(cur.standMin)}${cur.standCost !== null ? ` · ${lei(cur.standCost)}` : ""}`,
      delta: change(cur.standMin, prev?.standMin),
      lowerIsBetter: true,
    },
  ];
  if (cur.serviceLei !== null) {
    rows.push({ label: "Service", value: lei(cur.serviceLei), delta: null });
  }
  return (
    <div className="space-y-3 rounded-2xl glass-card p-4">
      <div>
        <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Raportul lunii</p>
        <p className="text-lg font-semibold">{monthName(cur.month)}</p>
        {prev && <p className="text-xs text-muted-foreground">față de {monthName(prev.month).toLowerCase()} (întreagă)</p>}
      </div>
      <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-3">
        {rows.map((r) => (
          <div key={r.label}>
            <p className="text-xs text-muted-foreground">{r.label}</p>
            <p className="font-medium">
              {r.value}
              {r.delta !== null && (
                <span
                  className={`ml-1.5 text-xs ${
                    r.delta === 0 ? "text-muted-foreground" : (r.delta < 0) === !!r.lowerIsBetter ? "text-emerald-400" : "text-amber-400"
                  }`}
                >
                  {r.delta > 0 ? "▲" : r.delta < 0 ? "▼" : "="} {Math.abs(r.delta)}%
                </span>
              )}
            </p>
          </div>
        ))}
      </div>
      {(cur.longest || cur.topPlaces.length > 0) && (
        <div className="space-y-1 border-t border-border/30 pt-2 text-sm">
          {cur.longest && (
            <p>
              <span className="text-muted-foreground">Cea mai lungă: </span>
              {num(cur.longest.km)} km, {day(cur.longest.start)}
              {cur.longest.from || cur.longest.to ? ` · ${cur.longest.from ?? "…"} → ${cur.longest.to ?? "…"}` : ""}
            </p>
          )}
          {cur.topPlaces.length > 0 && (
            <p>
              <span className="text-muted-foreground">Cel mai des: </span>
              {cur.topPlaces.map((p) => `${p.name} (${p.visits})`).join(", ")}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

/** „azi”, „mâine” sau ziua (ora României, ca pe telefon). */
function dayWord(iso: string): string {
  const key = (d: Date) => d.toLocaleDateString("ro-RO");
  const now = new Date();
  if (key(new Date(iso)) === key(now)) return "azi";
  if (key(new Date(iso)) === key(new Date(now.getTime() + 86_400_000))) return "mâine";
  return day(iso);
}

/**
 * Drumul la serviciu pe ture (zi 08–20, noapte 20–08, apoi două zile libere): următoarea tură
 * cu minutul până la care să pleci ca să ajungi cu 15 min înainte și, pe fiecare tură, cum
 * pleci și ajungi de obicei. Ora sigură ține cont și de zilele proaste (percentila 90).
 */
function CommuteCard({ c }: { c: Commute }) {
  const next = c.next.find((n) => Date.parse(n.start) > Date.now());
  const nextShift = next && c.shifts.find((s) => s.name === next.shift);
  return (
    <div className="space-y-2 rounded-2xl glass-card p-4">
      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        La {c.work.toLowerCase()} (ture 12/24, 90 de zile)
      </p>
      {next && nextShift && (
        <div className="rounded-xl bg-emerald-500/10 p-3">
          <p className="text-sm text-muted-foreground">
            Următoarea tură: {dayWord(next.start)}, {nextShift.name.replace("Tura de ", "")} {hm(next.start)}
          </p>
          {next.leaveBy ? (
            <>
              <p className="mt-1 text-xl font-semibold text-emerald-400">Pleacă până la {hm(next.leaveBy)}</p>
              <p className="text-xs text-muted-foreground">
                ca să fii acolo cu {c.marginMin} min înainte (drumul: ~{duration(nextShift.toWork!.driveMin)}, în
                zilele proaste {duration(nextShift.toWork!.safeMin)})
                {nextShift.toWork!.warmupMin >= 1 &&
                  `; cu încălzirea de ~${Math.round(nextShift.toWork!.warmupMin)} min, pornește motorul la ${hm(
                    new Date(Date.parse(next.leaveBy) - Math.round(nextShift.toWork!.warmupMin) * 60_000).toISOString(),
                  )}`}
              </p>
            </>
          ) : (
            <p className="mt-1 text-xs text-muted-foreground">Ora de plecare apare după câteva drumuri la tura asta.</p>
          )}
        </div>
      )}
      {c.shifts.map((s) => (
        <div key={s.name} className="rounded-xl bg-white/5 p-3 text-sm">
          <div className="flex items-baseline justify-between gap-2">
            <p className="font-semibold">{s.name}</p>
            <p className="text-xs text-muted-foreground">
              {s.start}–{s.end}
            </p>
          </div>
          {s.toWork ? (
            <>
              {s.toWork.leaveBy && (
                <p className="mt-1">
                  <span className="text-emerald-400">Pleacă până la {s.toWork.leaveBy}</span>
                  <span className="text-muted-foreground"> (drum ~{duration(s.toWork.driveMin)}, cel mult {duration(s.toWork.safeMin)})</span>
                </p>
              )}
              <p className="text-xs text-muted-foreground">
                De obicei pleci la {s.toWork.usualLeave} și ajungi la {s.toWork.usualArrive}
                {s.toWork.tight > 0 && (
                  <span className="text-amber-400">
                    {" "}
                    · de {s.toWork.tight} din {s.toWork.trips} ori cu sub {c.marginMin} min înainte
                    {s.toWork.late > 0 ? `, ${s.toWork.late} ${s.toWork.late === 1 ? "întârziere" : "întârzieri"}` : ""}
                  </span>
                )}
              </p>
            </>
          ) : (
            <p className="mt-1 text-xs text-muted-foreground">Încă niciun drum spre {c.work.toLowerCase()} la tura asta.</p>
          )}
          {s.fromWork && (
            <p className="text-xs text-muted-foreground">
              Înapoi: pleci pe la {s.fromWork.usualLeave}, ești {c.home.toLowerCase()} pe la {s.fromWork.usualArrive} (~
              {duration(s.fromWork.driveMin)})
            </p>
          )}
        </div>
      ))}
    </div>
  );
}

/** Drumurile pe care le faci des (de la 10 deplasări, între două locuri salvate): cât costă și cât durează. */
function Routes({ routes }: { routes: RouteStats[] }) {
  return (
    <div className="space-y-2 rounded-2xl glass-card p-4">
      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Drumurile tale (90 de zile)</p>
      {routes.map((r) => (
        <div key={`${r.from}→${r.to}`} className="rounded-xl bg-white/5 p-3">
          <div className="flex items-baseline justify-between gap-2">
            <p className="font-semibold">
              {r.from} → {r.to}
            </p>
            <p className="text-xs text-muted-foreground">{r.trips} drumuri</p>
          </div>
          <p className="mt-1 text-sm">
            ~{num(r.km)} km · {duration(r.minutes)}
            {r.trafficMin > 0 ? ` (${duration(r.trafficMin)} în trafic)` : ""}
            {r.cost !== null ? ` · ${lei(r.cost)} pe drum` : ""}
          </p>
          {r.monthCost !== null && <p className="text-xs text-muted-foreground">Luna aceasta: {lei(r.monthCost)}</p>}
        </div>
      ))}
    </div>
  );
}

/** Diferența procentuală față de luna trecută; null fără termen de comparație. */
function change(cur: number | null, prev: number | null | undefined): number | null {
  if (cur === null || prev == null || prev === 0) return null;
  return Math.round(((cur - prev) / prev) * 100);
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
