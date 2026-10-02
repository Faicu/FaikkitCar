import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { ArrowLeft, Car, Combine, MapPin, Split } from "lucide-react";
import { toast } from "sonner";

import { Info } from "../components/Cells";
import { TripMap } from "../components/TripMap";
import { TripChart } from "../components/TripChart";
import { api, day, duration, hm, lei, liters, num, route, shortDuration, type Trip } from "../api";

const LIVE = { refetchInterval: 15_000, staleTime: 10_000 };
const HIDE_IDLE_KEY = "calatorii.hideIdle";

/** „≈ 0,38 L · 2,66 lei” pentru lista de călătorii. */
function fuelLine(t: Trip): string {
  if (t.fuelL === null) return "";
  return ` · ≈ ${liters(t.fuelL)}${t.cost !== null ? ` · ${lei(t.cost)}` : ""}`;
}

/** Fila Călătorii: doar lista; o călătorie aleasă se deschide pe ecranul ei. */
export function TripsPage() {
  const { data, isLoading } = useQuery({ queryKey: ["trips"], queryFn: api.trips, ...LIVE });
  const all = data ?? [];
  const [hideIdle, setHideIdle] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  useEffect(() => {
    try {
      setHideIdle(localStorage.getItem(HIDE_IDLE_KEY) === "1");
    } catch {
      // localStorage indisponibil — rămân afișate toate
    }
  }, []);
  function toggleIdle() {
    const next = !hideIdle;
    setHideIdle(next);
    try {
      localStorage.setItem(HIDE_IDLE_KEY, next ? "1" : "0");
    } catch {
      // ignoră — alegerea nu se păstrează după reîncărcare
    }
  }

  const index = all.findIndex((t) => t.start === selected);
  const open = index >= 0 ? all[index] : undefined;
  if (open) {
    return (
      <>
        <button
          type="button"
          onClick={() => {
            setSelected(null);
            scrollTo(0, 0);
          }}
          className="flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm text-sky-400 hover:bg-sky-500/10"
        >
          <ArrowLeft className="h-4 w-4" /> Toate călătoriile
        </button>
        <TripDetail
          trip={open}
          older={all[index + 1]}
          newer={index > 0 ? all[index - 1] : undefined}
          onChange={setSelected}
        />
      </>
    );
  }

  const idleCount = all.filter((t) => t.idle).length;
  const trips = hideIdle ? all.filter((t) => !t.idle) : all;
  return (
    <div className="space-y-2">
      {isLoading && <div className="h-40 skeleton-sweep rounded-2xl" />}
      {!isLoading && all.length === 0 && (
        <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">
          Nicio călătorie încă. Aplicația FaikkitCar trimite traseul automat când mașina merge.
        </p>
      )}
      {idleCount > 0 && (
        <div className="flex justify-end">
          <button
            type="button"
            onClick={toggleIdle}
            className="rounded-lg px-3 py-1.5 text-sm text-sky-400 hover:bg-sky-500/10"
          >
            {hideIdle
              ? `Arată pornirile pe loc (${idleCount})`
              : `Ascunde pornirile pe loc (${idleCount})`}
          </button>
        </div>
      )}
      {trips.map((t) => (
        <button
          key={t.start}
          type="button"
          onClick={() => {
            setSelected(t.start);
            scrollTo(0, 0);
          }}
          className="block w-full rounded-2xl glass-card glass-card-hover press-tile p-3 text-left"
        >
          <div className="flex items-center justify-between">
            <span className="font-semibold">
              {day(t.start)} · {hm(t.start)}–{hm(t.end)}
            </span>
            <span className="text-sm text-sky-400">{t.distanceKm} km</span>
          </div>
          {route(t) && <p className="text-sm text-sky-300">{route(t)}</p>}
          <p className="mt-0.5 text-xs text-muted-foreground">
            {t.idle ? "pe loc · " : ""}
            {t.parts > 1 ? `${t.parts} părți, oprire ${duration(t.stopMin)} · ` : ""}
            {duration(t.durationMin)}
            {t.avgSpeed !== null ? ` · medie ${t.avgSpeed} km/h` : ""}
            {t.maxSpeed !== null ? ` · max ${t.maxSpeed} km/h` : ""}
            {fuelLine(t)}
          </p>
        </button>
      ))}
    </div>
  );
}

/** Valorile unei călătorii (și ale celei în curs, pe fila Acum). */
export function TripCells({ trip }: { trip: Trip }) {
  return (
    <div className="mt-3 grid grid-cols-3 gap-2 text-sm">
      <Info label="Distanță" value={`${trip.distanceKm} km`} />
      <Info label="Durată" value={duration(trip.durationMin)} />
      <Info label="Viteză medie" value={trip.avgSpeed !== null ? `${trip.avgSpeed} km/h` : "—"} />
      <Info label="Combustibil (est.)" value={trip.fuelL !== null ? `≈ ${liters(trip.fuelL)}` : "—"} />
      <Info
        label="Consum (est.)"
        value={trip.lPer100 !== null ? `${num(trip.lPer100)} L/100 km` : "—"}
      />
      <Info label="Cost (est.)" value={trip.cost !== null ? lei(trip.cost) : "—"} />
      <Info label="Viteză max" value={trip.maxSpeed !== null ? `${trip.maxSpeed} km/h` : "—"} />
      <Info label="Turație max" value={trip.maxRpm !== null ? `${trip.maxRpm} rpm` : "—"} />
      <Info
        label="Opriri în trafic"
        value={trip.trafficStops > 0 ? `${shortDuration(trip.trafficMin)} (${trip.trafficStops})` : "—"}
      />
    </div>
  );
}

/** Combină cu vecina sau desparte; după combinare, călătoria aleasă începe la noua plecare. */
function JoinActions({
  trip,
  older,
  newer,
  onChange,
}: {
  trip: Trip;
  older?: Trip;
  newer?: Trip;
  onChange: (start: string) => void;
}) {
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  async function run(action: () => Promise<unknown>, start: string, ok: string) {
    setBusy(true);
    try {
      await action();
      await qc.invalidateQueries();
      onChange(start);
      toast.success(ok);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const btn = "flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm text-sky-400 hover:bg-sky-500/10 disabled:opacity-40";
  return (
    <div className="mt-3 flex flex-wrap gap-1 border-t border-border/30 pt-2">
      {older && (
        <button
          type="button"
          disabled={busy}
          className={btn}
          onClick={() => run(() => api.joinTrips(older.start, trip.end), older.start, "Călătorii combinate")}
        >
          <Combine className="h-4 w-4" /> Combină cu precedenta ({hm(older.start)})
        </button>
      )}
      {newer && (
        <button
          type="button"
          disabled={busy}
          className={btn}
          onClick={() => run(() => api.joinTrips(trip.start, newer.end), trip.start, "Călătorii combinate")}
        >
          <Combine className="h-4 w-4" /> Combină cu următoarea ({hm(newer.start)})
        </button>
      )}
      {trip.parts > 1 && (
        <button
          type="button"
          disabled={busy}
          className={btn}
          onClick={() => run(() => api.splitTrip(trip.start), trip.start, "Călătorie despărțită")}
        >
          <Split className="h-4 w-4" /> Desparte ({trip.parts} părți)
        </button>
      )}
    </div>
  );
}

function TripDetail({
  trip,
  older,
  newer,
  onChange,
}: {
  trip: Trip;
  older?: Trip;
  newer?: Trip;
  onChange: (start: string) => void;
}) {
  const { data: points } = useQuery({
    queryKey: ["tripPoints", trip.start, trip.end],
    queryFn: () => api.tripPoints(trip.start, trip.end),
    staleTime: 30_000,
  });
  return (
    <div className="space-y-2">
      <div className="rounded-2xl glass-card p-4">
        <div className="flex items-center gap-2">
          <Car className="h-5 w-5 text-sky-400" />
          <span className="font-semibold">
            {day(trip.start)} · {hm(trip.start)}–{hm(trip.end)}
          </span>
        </div>
        {route(trip) && <p className="mt-1 text-sm text-sky-300">{route(trip)}</p>}
        <TripCells trip={trip} />
        <div className="mt-2 grid grid-cols-3 gap-2 text-sm">
          <Info
            label="Cea mai lungă în trafic"
            value={trip.trafficStops > 0 ? shortDuration(trip.trafficMaxMin) : "—"}
          />
          <Info label="Staționare, motor pornit" value={shortDuration(trip.standMin)} />
          <Info
            label="Viteză în mișcare"
            value={trip.movingAvgSpeed !== null ? `${trip.movingAvgSpeed} km/h` : "—"}
          />
          <Info label="Baterie min" value={trip.minVolt !== null ? `${trip.minVolt} V` : "—"} />
          <Info label="Temp. exterioară" value={trip.tempC !== null ? `${trip.tempC} °C` : "—"} />
          <Info
            label="Kilometraj"
            value={trip.odoEnd !== null ? `${trip.odoEnd.toLocaleString("ro-RO")} km` : "—"}
          />
          <Info
            label="Rezervor (CAN)"
            value={
              trip.fuelStart !== null && trip.fuelEnd !== null
                ? `${trip.fuelStart} → ${trip.fuelEnd} L`
                : "—"
            }
          />
          <Info label="Puncte" value={String(trip.points)} />
          {trip.parts > 1 && (
            <Info label="Opriri între părți" value={duration(trip.stopMin)} />
          )}
        </div>
        {trip.startPos && (
          <p className="mt-2 flex items-center gap-1 text-xs text-muted-foreground">
            <MapPin className="h-3 w-3" /> verde = plecare, roșu = sosire
            {trip.stops.length > 0 ? ", galben = oprire" : ""}
          </p>
        )}
        <JoinActions trip={trip} older={older} newer={newer} onChange={onChange} />
      </div>
      {trip.stops.length > 0 && (
        <div className="rounded-2xl glass-card p-4 text-sm">
          <p className="font-semibold">Opriri</p>
          {trip.stops.map((s) => (
            <p key={s.from} className="mt-1 text-muted-foreground">
              {hm(s.from)}–{hm(s.to)} · {duration(s.minutes)}
              {s.place ? ` · ${s.place}` : ""}
            </p>
          ))}
        </div>
      )}
      {points && <TripMap points={points} stops={trip.stops} />}
      {points && <TripChart points={points} />}
    </div>
  );
}
