import { useEffect, useState } from "react";
import { AlertTriangle, Fuel, Navigation, Wind } from "lucide-react";

import { CarPosition } from "../components/CarPosition";
import { TripSummary } from "../components/TripView";
import { api, duration, num, relativeTime, shortDuration, Unauthorized, type Live } from "../api";

/** „1 min 20 s” sub 10 minute (semafor), altfel „2 h”, „ieri”. */
function sinceText(iso: string): string {
  const min = (Date.now() - new Date(iso).getTime()) / 60_000;
  return min < 10 ? shortDuration(Math.max(0, min)) : relativeTime(iso).replace(/^acum /, "");
}

type LiveData = NonNullable<Live["data"]>;

const STATES: Record<Live["state"], { label: string; dot: string }> = {
  off: { label: "Oprită", dot: "bg-slate-500" },
  contact: { label: "Doar contact", dot: "bg-amber-400" },
  engine: { label: "Motor pornit, pe loc", dot: "bg-emerald-400" },
  traffic: { label: "Oprit în trafic", dot: "bg-orange-400 animate-pulse" },
  driving: { label: "În mers", dot: "bg-sky-400 animate-pulse" },
};

/**
 * Fila Acum, în ordinea importanței: starea (cu valorile de acum, clima într-un rând și
 * avertizările), călătoria în curs, rezervorul cu autonomia și unde e mașina.
 */
export function NowPage() {
  const live = useLive();
  if (!live) return <div className="h-40 skeleton-sweep rounded-2xl" />;
  const st = STATES[live.state];
  const d = live.data;
  return (
    <>
      <div className="space-y-3 rounded-2xl glass-card p-4">
        <div className="flex items-center gap-3">
          <span className={`h-3.5 w-3.5 shrink-0 rounded-full ${st.dot}`} />
          <div className="min-w-0 flex-1">
            <p className="text-xl font-semibold">{st.label}</p>
            <p className="text-xs text-muted-foreground">
              {live.since ? `de ${sinceText(live.since)}` : "nicio stare primită încă"}
              {live.state === "off" && live.at ? ` · ultimul semnal ${relativeTime(live.at)}` : ""}
            </p>
          </div>
        </div>
        {d && (
          <div className="grid grid-cols-4 gap-2">
            <Tile label="viteză" value={d.speed !== null ? `${Math.round(d.speed)}` : "—"} unit="km/h" />
            <Tile label="turație" value={d.rpm !== null ? `${d.rpm}` : "—"} unit="rpm" />
            <Tile label="baterie" value={d.volt !== null ? num(d.volt, 1) : "—"} unit="V" />
            <Tile label="afară" value={d.temp !== null ? num(d.temp) : "—"} unit="°C" />
          </div>
        )}
        {d?.climate && <ClimateLine climate={d.climate} />}
        {d?.instantL100 != null && (
          <p className="text-sm">
            <span className="text-muted-foreground">Consum acum* </span>
            {num(d.instantL100)} L/100 km
          </p>
        )}
        {d && <CarDetails d={d} />}
        {d?.instantL100 != null && (
          <p className="text-xs text-muted-foreground">
            * consumul instantaneu al bordului (presupus; compară cu afișajul din bord).
          </p>
        )}
      </div>

      {live.trip && (
        <TripSummary
          trip={live.trip}
          title={
            <span className="flex items-center gap-1.5">
              <Navigation className="h-4 w-4 text-sky-400" /> Călătoria în curs · de{" "}
              {duration(live.trip.durationMin)}
            </span>
          }
        />
      )}

      {live.tank && (
        <div className="flex items-center gap-3 rounded-2xl glass-card p-4">
          <Fuel className="h-5 w-5 shrink-0 text-sky-400" />
          <div className="min-w-0 flex-1">
            <p className="font-semibold">
              {num(live.tank.liters, 0)} L{live.range ? ` · ≈ ${live.range.km} km autonomie` : ""}
            </p>
            <p className="text-xs text-muted-foreground">
              {live.range
                ? `la ${num(live.range.lPer100)} L/100 ${live.range.real ? "(real, din rezervor)" : "(estimat din drumuri)"}, până la gol · `
                : ""}
              citit {relativeTime(live.tank.at)}
            </p>
          </div>
        </div>
      )}

      <CarPosition position={live.position} />
    </>
  );
}

function Tile({ label, value, unit }: { label: string; value: string; unit: string }) {
  return (
    <div className="rounded-xl bg-white/[0.04] px-2.5 py-2">
      <p className="whitespace-nowrap text-lg font-semibold leading-tight">
        {value}
        <span className="ml-0.5 text-xs font-normal text-muted-foreground">{unit}</span>
      </p>
      <p className="text-[11px] text-muted-foreground">{label}</p>
    </div>
  );
}

/** Clima într-un singur rând: „AUTO · AC oprit · 21 °C · ventilator 2”, sau „oprit”. */
function ClimateLine({ climate }: { climate: NonNullable<LiveData["climate"]> }) {
  if (climate.on === false) {
    return (
      <p className="flex items-center gap-1.5 text-sm">
        <Wind className="h-4 w-4 shrink-0 text-muted-foreground" />
        <span className="text-muted-foreground">Climatronic</span> oprit
      </p>
    );
  }
  const parts: string[] = [];
  if (climate.auto !== null) parts.push(climate.auto ? "AUTO" : "manual");
  if (climate.ac !== null) parts.push(climate.ac ? "AC pornit" : "AC oprit");
  const l = climate.setLeft;
  const r = climate.setRight;
  if (l !== null || r !== null) parts.push(l === r || r === null ? `${num(l ?? r ?? 0)} °C` : `${num(l ?? 0)} / ${num(r)} °C`);
  if (climate.fan !== null) parts.push(climate.fan === 0 ? "ventilator oprit" : `ventilator ${climate.fan}`);
  return (
    <p className="flex items-center gap-1.5 text-sm">
      <Wind className="h-4 w-4 shrink-0 text-sky-400" />
      <span className="text-muted-foreground">Climatronic</span> {parts.join(" · ")}
    </p>
  );
}

/**
 * Starea live prin „long polling”: fiecare cerere așteaptă la server o stare nouă de la
 * mașină, apoi se pune imediat următoarea. Cât fila e ascunsă, nu întreabă.
 */
function useLive(): Live | null {
  const [live, setLive] = useState<Live | null>(null);
  useEffect(() => {
    const abort = new AbortController();
    let after: string | null = null;
    async function loop() {
      while (!abort.signal.aborted) {
        if (document.hidden) {
          await new Promise((r) => setTimeout(r, 1000));
          after = null; // la revenire, răspuns imediat
          continue;
        }
        try {
          const l: Live = await api.live(after, abort.signal);
          setLive(l);
          after = l.at;
          // Nicio stare primită vreodată: n-avem după ce aștepta, deci întrebăm rar.
          if (!after) await new Promise((r) => setTimeout(r, 15_000));
        } catch (e) {
          if (abort.signal.aborted) return;
          if (e instanceof Unauthorized) {
            location.reload();
            return;
          }
          await new Promise((r) => setTimeout(r, 5000)); // fără rețea: reîncearcă
        }
      }
    }
    void loop();
    return () => abort.abort();
  }, []);
  return live;
}

/** Ușile, centura, frâna de mână, marșarierul, luminile: doar ce e de semnalat. */
function CarDetails({ d }: { d: LiveData }) {
  const warn: string[] = [];
  const ok: string[] = [];
  const doors = d.doorsOpen ?? [];
  if (doors.length > 0) warn.push(`Deschis: ${doors.join(", ")}`);
  const speed = d.speed ?? 0;
  if (d.handbrake === true) ok.push("frâna de mână trasă");
  else if (d.handbrake === false && speed < 1) warn.push("Frâna de mână e eliberată");
  if (d.belt === false && speed >= 5) warn.push("Centura șoferului nu e pusă");
  else if (d.belt === true) ok.push("centura pusă");
  if (d.reverse) warn.push("În marșarier");
  if (d.lights != null) ok.push(d.lights ? "luminile aprinse" : "luminile stinse");
  if (warn.length === 0 && ok.length === 0) return null;
  return (
    <div className="space-y-1 text-sm">
      {warn.map((w) => (
        <p key={w} className="flex items-center gap-1.5 font-medium text-amber-400">
          <AlertTriangle className="h-4 w-4" /> {w}
        </p>
      ))}
      {ok.length > 0 && <p className="text-xs text-muted-foreground">{ok.join(" · ")}</p>}
    </div>
  );
}
