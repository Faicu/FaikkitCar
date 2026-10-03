// Afișarea unei călătorii: antetul cu traseul și cifrele mari, bara timpului (mers / trafic /
// staționare / opriri) și secțiunile cu detalii. Folosită în detalii și pe fila Acum.

import type { ReactNode } from "react";

import { duration, lei, liters, num, route, shortDuration, type Trip } from "../api";

/** Culorile categoriilor de timp, aceleași în bară și în legendă. */
const TIME = [
  { key: "moving", label: "În mers", color: "bg-sky-400" },
  { key: "traffic", label: "Oprit în trafic", color: "bg-orange-400" },
  { key: "stand", label: "Staționare", color: "bg-slate-400" },
  { key: "stop", label: "Motor oprit", color: "bg-amber-300" },
] as const;

function timeParts(t: Trip): Record<(typeof TIME)[number]["key"], number> {
  return { moving: t.movingMin, traffic: t.trafficMin, stand: t.standMin, stop: t.stopMin };
}

/** Cifrele mari: distanța, durata și costul (sau litrii, dacă nu știm prețul). */
export function TripKpis({ trip }: { trip: Trip }) {
  return (
    <div className="grid grid-cols-3 gap-2">
      <Kpi value={`${num(trip.distanceKm)}`} unit="km" label="distanță" />
      <Kpi value={duration(trip.durationMin)} label="durată" />
      {trip.cost !== null ? (
        <Kpi value={lei(trip.cost).replace(" lei", "")} unit="lei" label={`≈ ${liters(trip.fuelL ?? 0)}`} />
      ) : (
        <Kpi value={trip.fuelL !== null ? liters(trip.fuelL) : "—"} label="combustibil" />
      )}
    </div>
  );
}

function Kpi({ value, unit, label }: { value: string; unit?: string; label: string }) {
  return (
    <div className="rounded-xl bg-white/[0.04] px-3 py-2.5">
      <p className="text-2xl font-semibold tracking-tight">
        {value}
        {unit && <span className="ml-1 text-sm font-normal text-muted-foreground">{unit}</span>}
      </p>
      <p className="text-xs text-muted-foreground">{label}</p>
    </div>
  );
}

/** Bara timpului: cât din drum a fost mers, trafic, staționare și opriri cu motorul oprit. */
export function TimeBar({ trip }: { trip: Trip }) {
  const parts = timeParts(trip);
  const total = Math.max(
    TIME.reduce((s, c) => s + parts[c.key], 0),
    0.1,
  );
  return (
    <div className="space-y-2">
      <div className="flex h-2.5 overflow-hidden rounded-full bg-white/5">
        {TIME.map((c) =>
          parts[c.key] > 0 ? (
            <div key={c.key} className={c.color} style={{ width: `${(parts[c.key] / total) * 100}%` }} />
          ) : null,
        )}
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {TIME.map((c) =>
          parts[c.key] > 0 ? (
            <span key={c.key} className="flex items-center gap-1.5 text-muted-foreground">
              <span className={`h-2 w-2 rounded-full ${c.color}`} />
              {c.label} <span className="text-foreground">{shortDuration(parts[c.key])}</span>
              {c.key === "traffic" && trip.trafficStops > 0 ? ` · ${trip.trafficStops} opriri` : ""}
            </span>
          ) : null,
        )}
      </div>
    </div>
  );
}

/** Antetul: traseul (Acasă → Serviciu), cifrele mari și bara timpului. */
export function TripSummary({ trip, title }: { trip: Trip; title: ReactNode }) {
  return (
    <div className="space-y-3 rounded-2xl glass-card p-4">
      <div>
        <p className="text-sm text-muted-foreground">{title}</p>
        {route(trip) && <p className="text-lg font-semibold">{route(trip)}</p>}
      </div>
      <TripKpis trip={trip} />
      <TimeBar trip={trip} />
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="rounded-2xl glass-card p-4">
      <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">{title}</p>
      <div className="grid grid-cols-2 gap-x-4 gap-y-2.5 text-sm sm:grid-cols-3">{children}</div>
    </div>
  );
}

function Row({ label, value, warn }: { label: string; value: string; warn?: boolean }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`font-medium ${warn ? "text-amber-400" : ""}`}>
        {value}
        {warn ? " ⚠" : ""}
      </p>
    </div>
  );
}

const kmh = (v: number | null) => (v !== null ? `${v} km/h` : "—");
const volts = (v: number | null) =>
  v === null ? "—" : `${v.toLocaleString("ro-RO", { minimumFractionDigits: 1, maximumFractionDigits: 2 })} V`;

/** Detaliile pe teme: viteză, consum, mașina. Doar ce avem (drumurile vechi au mai puține). */
export function TripSections({ trip }: { trip: Trip }) {
  const driving = Math.max(trip.durationMin - trip.stopMin, 0.1);
  return (
    <>
      <Section title="Viteză">
        <Row label="Medie" value={kmh(trip.avgSpeed)} />
        <Row label="În mișcare" value={kmh(trip.movingAvgSpeed)} />
        <Row label="Maximă" value={kmh(trip.maxSpeed)} />
        <Row label="Turație maximă" value={trip.maxRpm !== null ? `${trip.maxRpm} rpm` : "—"} />
        {trip.trafficStops > 0 && (
          <Row label="Cea mai lungă oprire în trafic" value={shortDuration(trip.trafficMaxMin)} />
        )}
      </Section>
      <Section title="Consum">
        <Row label="Combustibil (est.)" value={trip.fuelL !== null ? `≈ ${liters(trip.fuelL)}` : "—"} />
        <Row label="Consum (est.)" value={trip.lPer100 !== null ? `${num(trip.lPer100)} L/100` : "—"} />
        <Row label="Cost (est.)" value={trip.cost !== null ? lei(trip.cost) : "—"} />
        {trip.boardLPer100 !== null && (
          <Row label="Bordul, în mers*" value={`${num(trip.boardLPer100)} L/100`} />
        )}
        {trip.fuelStart !== null && trip.fuelEnd !== null && (
          <Row label="Rezervor" value={`${trip.fuelStart} → ${trip.fuelEnd} L`} />
        )}
      </Section>
      <Section title="Mașina">
        <Row
          label="Afară"
          value={
            trip.tempC === null
              ? "—"
              : trip.tempStartC !== null && trip.tempStartC !== trip.tempC
                ? `${num(trip.tempStartC)} → ${num(trip.tempC)} °C`
                : `${num(trip.tempC)} °C`
          }
        />
        {trip.acMin !== null && (
          <Row
            label="AC"
            value={trip.acMin > 0 ? `${shortDuration(trip.acMin)} (${Math.round((trip.acMin / driving) * 100)}%)` : "oprit"}
          />
        )}
        {trip.noBeltMin !== null && trip.noBeltMin > 0 && (
          <Row label="Fără centură, în mers" value={shortDuration(trip.noBeltMin)} warn />
        )}
        <Row
          label="Baterie la pornire"
          value={volts(trip.crankVolt)}
          warn={trip.crankVolt !== null && trip.crankVolt < 9.6}
        />
        <Row
          label="Baterie în mers"
          value={volts(trip.runVolt)}
          warn={trip.runVolt !== null && (trip.runVolt < 13.5 || trip.runVolt > 14.9)}
        />
        <Row label="Kilometraj" value={trip.odoEnd !== null ? `${trip.odoEnd.toLocaleString("ro-RO")} km` : "—"} />
      </Section>
      {trip.boardLPer100 !== null && (
        <p className="px-1 text-xs text-muted-foreground">
          * din consumul instantaneu al bordului (c1033), de confirmat cu afișajul din bord.
        </p>
      )}
    </>
  );
}
