import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { ArrowLeft, Car, MapPin } from "lucide-react";

import { Info } from "../components/Cells";
import { TripMap } from "../components/TripMap";
import { TripChart } from "../components/TripChart";
import { api, day, duration, hm, lei, liters, num, type Trip } from "../api";

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

  const open = all.find((t) => t.start === selected);
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
        <TripDetail trip={open} />
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
          <p className="mt-0.5 text-xs text-muted-foreground">
            {t.idle ? "pe loc · " : ""}
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
      <Info label="Pe loc, motor pornit" value={duration(trip.idleMin)} />
    </div>
  );
}

function TripDetail({ trip }: { trip: Trip }) {
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
        <TripCells trip={trip} />
        <div className="mt-2 grid grid-cols-3 gap-2 text-sm">
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
        </div>
        {trip.startPos && (
          <p className="mt-2 flex items-center gap-1 text-xs text-muted-foreground">
            <MapPin className="h-3 w-3" /> verde = plecare, roșu = sosire
          </p>
        )}
      </div>
      {points && <TripMap points={points} />}
      {points && <TripChart points={points} />}
    </div>
  );
}
