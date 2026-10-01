import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Car, MapPin } from "lucide-react";

import { TripMap } from "../components/TripMap";
import { TripChart } from "../components/TripChart";
import { CarPosition } from "../components/CarPosition";
import { Maintenance } from "../components/Maintenance";
import { FuelLog } from "../components/FuelLog";
import { api, type Trip } from "../api";

const LIVE = { refetchInterval: 15_000, staleTime: 10_000 };

function day(iso: string): string {
  return new Date(iso).toLocaleDateString("ro-RO", {
    weekday: "short",
    day: "2-digit",
    month: "short",
  });
}

function hm(iso: string): string {
  return new Date(iso).toLocaleTimeString("ro-RO", { hour: "2-digit", minute: "2-digit" });
}

function duration(min: number): string {
  if (min < 60) return `${Math.round(min)} min`;
  return `${Math.floor(min / 60)} h ${Math.round(min % 60)} min`;
}

function liters(n: number): string {
  return `${n.toLocaleString("ro-RO", { maximumFractionDigits: n < 10 ? 2 : 1 })} L`;
}

function lei(n: number): string {
  return `${n.toLocaleString("ro-RO", { maximumFractionDigits: n < 100 ? 2 : 0 })} lei`;
}

/** „≈ 0,38 L · 2,66 lei” pentru lista de călătorii. */
function fuelLine(t: Trip): string {
  if (t.fuelL === null) return "";
  return ` · ≈ ${liters(t.fuelL)}${t.cost !== null ? ` · ${lei(t.cost)}` : ""}`;
}

const HIDE_IDLE_KEY = "calatorii.hideIdle";

/** Motorul pornit pe loc (încălzire, așteptare), fără deplasare reală. */
function isIdle(t: Trip): boolean {
  return t.distanceKm < 0.3 && (t.maxSpeed ?? 0) < 8;
}

export function TripsPage() {
  const { data, isLoading } = useQuery({ queryKey: ["trips"], queryFn: api.trips, ...LIVE });
  const { data: car } = useQuery({ queryKey: ["car"], queryFn: api.car, ...LIVE });
  const { data: fuel } = useQuery({ queryKey: ["fuel"], queryFn: api.fuel, ...LIVE });
  const all = data ?? [];
  const [hideIdle, setHideIdle] = useState(false);
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
  const idleCount = all.filter(isIdle).length;
  const trips = hideIdle ? all.filter((t) => !isIdle(t)) : all;
  const [selected, setSelected] = useState<string | null>(null);
  const current = trips.find((t) => t.start === selected) ?? trips[0];

  // Totalurile includ și pornirile pe loc (consumă combustibil); doar lista și numărul se filtrează.
  const recent = (t: Trip) => Date.now() - new Date(t.start).getTime() < 30 * 86_400_000;
  const month = all.filter(recent);
  const km = month.reduce((s, t) => s + t.distanceKm, 0);
  const min = month.reduce((s, t) => s + t.durationMin, 0);
  const monthL = month.reduce((s, t) => s + (t.fuelL ?? 0), 0);
  const monthCost = month.some((t) => t.cost !== null)
    ? month.reduce((s, t) => s + (t.cost ?? 0), 0)
    : null;

  return (
    <>
      {car && <CarPosition position={car.position} />}

      <div className="grid grid-cols-3 gap-2">
        <Stat label="Călătorii (30 zile)" value={String(trips.filter(recent).length)} />
        <Stat label="Distanță" value={`${Math.round(km)} km`} />
        <Stat label="Timp la volan" value={duration(min)} />
        <Stat label="Combustibil (est.)" value={`≈ ${liters(monthL)}`} />
        <Stat
          label="Consum (est.)"
          value={
            km >= 1
              ? `${((monthL / km) * 100).toLocaleString("ro-RO", { maximumFractionDigits: 1 })} L/100`
              : "—"
          }
        />
        <Stat label="Cost (est.)" value={monthCost !== null ? lei(monthCost) : "—"} />
      </div>

      {isLoading && <div className="h-40 skeleton-sweep rounded-2xl" />}
      {!isLoading && all.length === 0 && (
        <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">
          Nicio călătorie încă. Aplicația FaikkitCar trimite traseul automat când mașina merge.
        </p>
      )}

      {current && <TripDetail trip={current} />}

      {fuel && <FuelLog fuel={fuel} />}

      {car && <Maintenance reminders={car.reminders} odometer={car.odometer} />}

      <PanelDownload />

      <div className="space-y-2">
        {idleCount > 0 && (
          <div className="flex justify-end">
            <button
              type="button"
              onClick={toggleIdle}
              className="rounded-lg px-3 py-1.5 text-sm text-sky-400 hover:bg-sky-500/10"
            >
              {hideIdle
                ? `Arată și pornirile pe loc (${idleCount})`
                : `Ascunde pornirile pe loc (${idleCount})`}
            </button>
          </div>
        )}
        {trips.map((t) => (
          <button
            key={t.start}
            type="button"
            onClick={() => setSelected(t.start)}
            className={`block w-full rounded-2xl glass-card glass-card-hover press-tile p-3 text-left ${
              t.start === current?.start ? "ring-1 ring-sky-400/60" : ""
            }`}
          >
            <div className="flex items-center justify-between">
              <span className="font-semibold">
                {day(t.start)} · {hm(t.start)}–{hm(t.end)}
              </span>
              <span className="text-sm text-sky-400">{t.distanceKm} km</span>
            </div>
            <p className="mt-0.5 text-xs text-muted-foreground">
              {duration(t.durationMin)}
              {t.avgSpeed !== null ? ` · medie ${t.avgSpeed} km/h` : ""}
              {t.maxSpeed !== null ? ` · max ${t.maxSpeed} km/h` : ""}
              {fuelLine(t)}
            </p>
          </button>
        ))}
      </div>
    </>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl glass-card p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-lg font-semibold">{value}</p>
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
        <div className="mt-3 grid grid-cols-3 gap-2 text-sm">
          <Info label="Distanță" value={`${trip.distanceKm} km`} />
          <Info label="Durată" value={duration(trip.durationMin)} />
          <Info
            label="Viteză medie"
            value={trip.avgSpeed !== null ? `${trip.avgSpeed} km/h` : "—"}
          />
          <Info label="Viteză max" value={trip.maxSpeed !== null ? `${trip.maxSpeed} km/h` : "—"} />
          <Info label="Turație max" value={trip.maxRpm !== null ? `${trip.maxRpm} rpm` : "—"} />
          <Info label="Baterie min" value={trip.minVolt !== null ? `${trip.minVolt} V` : "—"} />
          <Info label="Temp. exterioară" value={trip.tempC !== null ? `${trip.tempC} °C` : "—"} />
          <Info
            label="Kilometraj"
            value={trip.odoEnd !== null ? `${trip.odoEnd.toLocaleString("ro-RO")} km` : "—"}
          />
          <Info
            label="Combustibil (est.)"
            value={trip.fuelL !== null ? `≈ ${liters(trip.fuelL)}` : "—"}
          />
          <Info
            label="Consum (est.)"
            value={
              trip.lPer100 !== null
                ? `${trip.lPer100.toLocaleString("ro-RO", { maximumFractionDigits: 1 })} L/100 km`
                : "—"
            }
          />
          <Info label="Cost (est.)" value={trip.cost !== null ? lei(trip.cost) : "—"} />
          <Info label="Pe loc, motor pornit" value={duration(trip.idleMin)} />
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

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="font-medium">{value}</p>
    </div>
  );
}

/** Linkul către aplicația de telefon, doar când CI-ul a publicat-o. */
function PanelDownload() {
  const { data } = useQuery({ queryKey: ["panelApk"], queryFn: api.panelApk, staleTime: 60_000 });
  if (!data || data.versionCode <= 0) return null;
  return (
    <a
      href="/api/panel/apk/download"
      className="flex items-center justify-between rounded-2xl glass-card glass-card-hover p-4"
    >
      <div>
        <p className="font-semibold">FaikkitCar Panel pentru telefon</p>
        <p className="text-xs text-muted-foreground">
          Versiunea {data.versionName} · apoi se actualizează singură
        </p>
      </div>
      <span className="text-sm text-sky-400">Descarcă APK</span>
    </a>
  );
}
