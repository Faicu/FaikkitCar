import { useEffect, useState } from "react";
import { AlertTriangle, Fuel, Navigation, Thermometer } from "lucide-react";

import { CarPosition } from "../components/CarPosition";
import { Info } from "../components/Cells";
import { api, duration, num, relativeTime, Unauthorized, type Live } from "../api";

type LiveData = NonNullable<Live["data"]>;
import { TripCells } from "./Trips";

const STATES: Record<Live["state"], { label: string; dot: string }> = {
  off: { label: "Oprită", dot: "bg-slate-500" },
  contact: { label: "Doar contact", dot: "bg-amber-400" },
  engine: { label: "Motor pornit, pe loc", dot: "bg-emerald-400" },
  driving: { label: "În mers", dot: "bg-sky-400 animate-pulse" },
};

/** Fila Acum: starea mașinii, călătoria în curs, poziția și rezervorul. */
export function NowPage() {
  const live = useLive();
  if (!live) return <div className="h-40 skeleton-sweep rounded-2xl" />;
  const st = STATES[live.state];
  const d = live.data;
  return (
    <>
      <div className="rounded-2xl glass-card p-4">
        <div className="flex items-center gap-3">
          <span className={`h-3.5 w-3.5 shrink-0 rounded-full ${st.dot}`} />
          <div>
            <p className="text-lg font-semibold">{st.label}</p>
            <p className="text-xs text-muted-foreground">
              {live.since ? `de ${relativeTime(live.since).replace(/^acum /, "")}` : "nicio stare primită încă"}
              {live.state === "off" && live.at ? ` · ultimul semnal ${relativeTime(live.at)}` : ""}
            </p>
          </div>
        </div>
        {d && (
          <div className="mt-3 grid grid-cols-3 gap-2 text-sm">
            <Info label="Viteză" value={d.speed !== null ? `${Math.round(d.speed)} km/h` : "—"} />
            <Info label="Turație" value={d.rpm !== null ? `${d.rpm} rpm` : "—"} />
            <Info label="Baterie" value={d.volt !== null ? `${num(d.volt, 2)} V` : "—"} />
            <Info label="Temp. afară" value={d.temp !== null ? `${num(d.temp)} °C` : "—"} />
            <Info
              label="Kilometraj"
              value={d.odo !== null ? `${d.odo.toLocaleString("ro-RO")} km` : "—"}
            />
            {d.instantL100 != null && (
              <Info label="Consum acum*" value={`${num(d.instantL100)} L/100`} />
            )}
          </div>
        )}
        {d && <CarDetails d={d} />}
        {d?.instantL100 != null && (
          <p className="mt-2 text-xs text-muted-foreground">
            * consumul instantaneu al bordului (presupus; compară cu afișajul din bord).
          </p>
        )}
      </div>

      {d?.climate && <ClimateCard d={d} climate={d.climate} />}

      {live.trip && (
        <div className="rounded-2xl glass-card p-4">
          <div className="flex items-center gap-2">
            <Navigation className="h-5 w-5 text-sky-400" />
            <span className="font-semibold">Călătoria în curs</span>
            <span className="text-xs text-muted-foreground">
              · de {duration(live.trip.durationMin)}
            </span>
          </div>
          <TripCells trip={live.trip} />
        </div>
      )}

      {live.tank && (
        <div className="rounded-2xl glass-card p-4">
          <div className="flex items-center gap-2">
            <Fuel className="h-5 w-5 text-sky-400" />
            <span className="font-semibold">Rezervor</span>
          </div>
          <div className="mt-3 grid grid-cols-3 gap-2 text-sm">
            <Info label="În rezervor" value={`${num(live.tank.liters, 0)} L`} />
            <Info label="Autonomie" value={live.range ? `≈ ${live.range.km} km` : "—"} />
            <Info
              label={live.range?.real ? "Consum real" : "Consum (est.)"}
              value={live.range ? `${num(live.range.lPer100)} L/100` : "—"}
            />
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            Autonomia e până la gol, la consumul mediu
            {live.range?.real ? " din nivelul rezervorului" : " estimat din drumuri (scurte, deci mare)"}
            ; citit {relativeTime(live.tank.at)}.
          </p>
        </div>
      )}

      <CarPosition position={live.position} />
    </>
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
  else if (d.climate) ok.push("uși închise");
  const speed = d.speed ?? 0;
  if (d.handbrake === true) ok.push("frâna de mână trasă");
  else if (d.handbrake === false && speed < 1) warn.push("Frâna de mână e eliberată");
  if (d.belt === false && speed >= 5) warn.push("Centura șoferului nu e pusă");
  else if (d.belt === true) ok.push("centura pusă");
  if (d.reverse) warn.push("În marșarier");
  if (d.lights != null) ok.push(d.lights ? "lumini aprinse" : "lumini stinse");
  if (warn.length === 0 && ok.length === 0) return null;
  return (
    <div className="mt-3 space-y-1 text-sm">
      {warn.map((w) => (
        <p key={w} className="flex items-center gap-1.5 font-medium text-amber-400">
          <AlertTriangle className="h-4 w-4" /> {w}
        </p>
      ))}
      {ok.length > 0 && <p className="text-xs text-muted-foreground">{ok.join(" · ")}</p>}
    </div>
  );
}

function ClimateCard({ d, climate }: { d: LiveData; climate: NonNullable<LiveData["climate"]> }) {
  const temp = (t: number | null) => (t !== null ? `${num(t)} °C` : "—");
  return (
    <div className="rounded-2xl glass-card p-4">
      <div className="flex items-center gap-2">
        <Thermometer className="h-5 w-5 text-sky-400" />
        <span className="font-semibold">Climatronic și temperaturi</span>
      </div>
      <div className="mt-3 grid grid-cols-3 gap-2 text-sm">
        <Info label="Afară" value={temp(d.temp)} />
        <Info label="Setat stânga" value={temp(climate.setLeft)} />
        <Info label="Setat dreapta" value={temp(climate.setRight)} />
        <Info label="AC" value={climate.ac === null ? "—" : climate.ac ? "pornit" : "oprit"} />
        <Info label="Mod" value={climate.auto === null ? "—" : climate.auto ? "AUTO" : "manual"} />
        <Info
          label="Ventilator"
          value={climate.fan === null ? "—" : climate.fan === 0 ? "oprit" : `treapta ${climate.fan}`}
        />
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
        Temperatura din habitaclu nu e transmisă de decodorul CAN, deci nu apare. Temperaturile
        setate sunt calculate din codul decodorului; dacă diferă de afișaj, spune-mi.
      </p>
    </div>
  );
}
