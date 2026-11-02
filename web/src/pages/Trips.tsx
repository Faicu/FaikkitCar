import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { ArrowLeft, CircleOff, Combine, MapPin, Split } from "lucide-react";
import { toast } from "sonner";

import { PlaceEditor, type PlaceDraft } from "../components/Places";
import { RouteThumb } from "../components/RouteThumb";
import { TripSummary, TripSections } from "../components/TripView";
import { TripMap } from "../components/TripMap";
import {
  api,
  day,
  duration,
  hm,
  lei,
  liters,
  num,
  route,
  shortDuration,
  type Gap,
  type Trip,
} from "../api";

const LIVE = { refetchInterval: 15_000, staleTime: 10_000 };
const HIDE_IDLE_KEY = "calatorii.hideIdle";

/** „≈ 0,38 L · 2,66 lei” pentru lista de călătorii. */
function fuelLine(t: Trip): string {
  if (t.fuelL === null) return "";
  return ` · ≈ ${liters(t.fuelL)}${t.cost !== null ? ` · ${lei(t.cost)}` : ""}`;
}

/**
 * Un drum neînregistrat (aplicația a fost oprită): doar ce se știe din kilometraj, între
 * sosirea călătoriei de dinainte și plecarea celei de după.
 */
function GapCard({ gap }: { gap: Gap }) {
  const sameDay = day(gap.after) === day(gap.before);
  return (
    <div className="rounded-2xl border border-dashed border-amber-500/40 p-3">
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 font-semibold text-amber-300">
          <CircleOff className="h-4 w-4" /> Drum neînregistrat
        </span>
        <span className="text-sm text-amber-300">~{gap.km} km</span>
      </div>
      {route(gap) && <p className="text-sm text-sky-300">{route(gap)}</p>}
      <p className="mt-0.5 text-xs text-muted-foreground">
        între {day(gap.after)} {hm(gap.after)} și {sameDay ? "" : `${day(gap.before)} `}
        {hm(gap.before)} · aplicația era oprită, se știe doar din kilometraj
        {gap.fuelL !== null ? ` · ≈ ${liters(gap.fuelL)}` : ""}
        {gap.cost !== null ? ` · ${lei(gap.cost)}` : ""}
      </p>
    </div>
  );
}

/** Fila Călătorii: doar lista; o călătorie aleasă se deschide pe ecranul ei. */
export function TripsPage() {
  const { data, isLoading } = useQuery({ queryKey: ["trips"], queryFn: api.trips, ...LIVE });
  const all = data ?? [];
  const { data: gaps } = useQuery({ queryKey: ["gaps"], queryFn: api.gaps, ...LIVE });
  const { data: shapes } = useQuery({ queryKey: ["tripShapes"], queryFn: api.tripShapes, ...LIVE });
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
      {trips.map((t, i) => (
        <div key={t.start} className="space-y-2">
          {(i === 0 || dayKey(trips[i - 1].start) !== dayKey(t.start)) && (
            <DayHeader trips={trips} start={t.start} />
          )}
          <TripRow
            trip={t}
            shape={shapes?.[t.start]}
            gaps={gapsBefore(gaps ?? [], t, trips[i - 1])}
            onOpen={setSelected}
          />
        </div>
      ))}
    </div>
  );
}

/** Ziua locală a unei ore ISO, pentru grupare. */
function dayKey(iso: string): string {
  return new Date(iso).toLocaleDateString("sv-SE");
}

/** „Azi”, „Ieri”, altfel „Sâmbătă, 31 oct.”. */
function dayTitle(iso: string): string {
  const k = dayKey(iso);
  const now = new Date();
  if (k === now.toLocaleDateString("sv-SE")) return "Azi";
  if (k === new Date(now.getTime() - 86_400_000).toLocaleDateString("sv-SE")) return "Ieri";
  const s = new Date(iso).toLocaleDateString("ro-RO", { weekday: "long", day: "numeric", month: "short" });
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Antetul unei zile: numele și totalurile ei (km, lei). */
function DayHeader({ trips, start }: { trips: Trip[]; start: string }) {
  const k = dayKey(start);
  const same = trips.filter((t) => dayKey(t.start) === k);
  const km = same.reduce((a, t) => a + t.distanceKm, 0);
  const cost = same.reduce((a, t) => a + (t.cost ?? 0), 0);
  return (
    <div className="flex items-baseline justify-between px-1 pt-3">
      <span className="font-semibold">{dayTitle(start)}</span>
      <span className="text-xs text-muted-foreground">
        {num(km)} km{cost > 0 ? ` · ${lei(cost)}` : ""}
      </span>
    </div>
  );
}

/** Golurile dintre o călătorie și cea mai nouă de deasupra ei din listă. */
function gapsBefore(gaps: Gap[], trip: Trip, newer: Trip | undefined): Gap[] {
  return gaps.filter((g) => g.after >= trip.end && (!newer || g.before <= newer.start));
}

function TripRow({
  trip: t,
  shape,
  gaps,
  onOpen,
}: {
  trip: Trip;
  shape: Array<[number, number]> | undefined;
  gaps: Gap[];
  onOpen: (start: string) => void;
}) {
  return (
    <>
      {gaps.map((g) => (
        <GapCard key={g.after} gap={g} />
      ))}
      <button
        key={t.start}
        type="button"
        onClick={() => {
          onOpen(t.start);
          scrollTo(0, 0);
        }}
        className="flex w-full items-center gap-3 rounded-2xl glass-card glass-card-hover press-tile p-3 text-left"
      >
        {shape && shape.length >= 2 && <RouteThumb shape={shape} />}
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between">
            <span className="font-semibold">
              {hm(t.start)}–{hm(t.end)}
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
        </div>
      </button>
    </>
  );
}

/** Plecarea sau sosirea într-un loc nesalvat: se poate salva direct de aici (ex. „Frizerie”). */
function SavePlaces({ trip }: { trip: Trip }) {
  const [draft, setDraft] = useState<PlaceDraft | null>(null);
  const options = [
    { label: "Salvează sosirea ca loc", pos: trip.toPlace ? null : trip.endPos },
    { label: "Salvează plecarea ca loc", pos: trip.fromPlace ? null : trip.startPos },
  ].filter((o): o is { label: string; pos: [number, number] } => o.pos !== null);
  if (options.length === 0) return null;
  return (
    <div className="space-y-2 rounded-2xl glass-card p-3">
      {draft ? (
        <PlaceEditor draft={draft} onClose={() => setDraft(null)} />
      ) : (
        <div className="flex flex-wrap gap-1">
          {options.map((o) => (
            <button
              key={o.label}
              type="button"
              onClick={() => setDraft({ name: "", lat: o.pos[0], lon: o.pos[1], radius: 100 })}
              className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm text-sky-400 hover:bg-sky-500/10"
            >
              <MapPin className="h-4 w-4" /> {o.label}
            </button>
          ))}
        </div>
      )}
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
    <div className="flex flex-wrap gap-1">
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
    <div className="space-y-3">
      <TripSummary
        trip={trip}
        title={
          <>
            {day(trip.start)} · {hm(trip.start)}–{hm(trip.end)}
            {trip.parts > 1 ? ` · ${trip.parts} părți` : ""}
          </>
        }
      />
      {points && <TripMap points={points} stops={trip.stops} />}
      {trip.startPos && (
        <p className="-mt-1 flex items-center gap-1 px-1 text-xs text-muted-foreground">
          <MapPin className="h-3 w-3" /> verde = plecare, roșu = sosire
          {trip.stops.length > 0 ? ", galben = oprire cu motorul oprit" : ""}
        </p>
      )}
      {trip.stops.length > 0 && (
        <div className="rounded-2xl glass-card p-4 text-sm">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Opriri cu motorul oprit
          </p>
          {trip.stops.map((s) => (
            <p key={s.from} className="mt-1">
              {hm(s.from)}–{hm(s.to)} <span className="text-muted-foreground">· {duration(s.minutes)}</span>
              {s.place ? <span className="text-muted-foreground"> · {s.place}</span> : ""}
            </p>
          ))}
        </div>
      )}
      <SavePlaces trip={trip} />
      <TripSections trip={trip} />
      <div className="rounded-2xl glass-card px-2 py-1">
        <JoinActions trip={trip} older={older} newer={newer} onChange={onChange} />
      </div>
    </div>
  );
}
