import { useEffect, useState } from "react";
import { Fuel, Navigation } from "lucide-react";

import { CarPosition } from "../components/CarPosition";
import { Info } from "../components/Cells";
import { api, duration, num, relativeTime, Unauthorized, type Live } from "../api";
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
          </div>
        )}
      </div>

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
