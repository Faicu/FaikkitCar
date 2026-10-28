import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Signal } from "lucide-react";
import "leaflet/dist/leaflet.css";

import { api, day, esc, hm, num, shortDuration, type SignalSummary } from "../api";

const COLORS: Record<SignalSummary["cells"][number]["level"], string> = {
  good: "#22c55e",
  fair: "#eab308",
  weak: "#f97316",
  none: "#ef4444",
};
const LABELS = { good: "bun", fair: "mediu", weak: "slab", none: "fără rețea" } as const;
const PERIODS = [7, 30, 90];

function pct(x: number): string {
  return `${Math.round(x * 100)}%`;
}

/**
 * Semnalul mobil al navigației (cartela din Teyes): harta pe pătrate de ~200 m (verde = bun,
 * roșu = fără internet), benzile folosite și căderile de internet din mers.
 */
export function SignalCard() {
  const [days, setDays] = useState(30);
  const { data } = useQuery({ queryKey: ["signal", days], queryFn: () => api.signal(days), staleTime: 60_000 });
  return (
    <div className="space-y-3 rounded-2xl glass-card p-4">
      <div className="flex items-center gap-2">
        <Signal className="h-4 w-4 shrink-0 text-sky-400" />
        <p className="font-semibold">Semnal mobil</p>
        <div className="ml-auto flex gap-1">
          {PERIODS.map((d) => (
            <button
              key={d}
              onClick={() => setDays(d)}
              className={`rounded-full px-2 py-0.5 text-xs ${d === days ? "bg-sky-500/20 text-sky-300" : "text-muted-foreground"}`}
            >
              {d} zile
            </button>
          ))}
        </div>
      </div>
      {!data ? (
        <div className="h-40 skeleton-sweep rounded-2xl" />
      ) : data.points === 0 ? (
        <p className="text-sm text-muted-foreground">
          Nicio măsurare încă: apare de la FaikkitCar 1.1.64, pe drumurile făcute de atunci.
        </p>
      ) : (
        <SignalBody data={data} />
      )}
    </div>
  );
}

function SignalBody({ data }: { data: SignalSummary }) {
  const ops = data.ops.map((o) => o.op).join(", ");
  return (
    <>
      <p className="text-sm">
        {data.offline > 0 ? (
          <>
            Fără internet <span className="font-semibold text-red-400">{pct(data.offline)}</span> din timpul de drum
          </>
        ) : (
          "Internet tot timpul"
        )}
        {ops && <span className="text-muted-foreground"> · {ops}</span>}
      </p>
      <div className="flex h-2 overflow-hidden rounded-full">
        {(Object.keys(LABELS) as Array<keyof typeof LABELS>).map((k) =>
          data.levels[k] > 0 ? (
            <div key={k} style={{ width: pct(data.levels[k]), background: COLORS[k] }} title={`${LABELS[k]} ${pct(data.levels[k])}`} />
          ) : null,
        )}
      </div>
      <p className="flex flex-wrap gap-x-3 text-xs text-muted-foreground">
        {(Object.keys(LABELS) as Array<keyof typeof LABELS>).map((k) => (
          <span key={k} className="flex items-center gap-1">
            <span className="inline-block h-2 w-2 rounded-full" style={{ background: COLORS[k] }} />
            {LABELS[k]} {pct(data.levels[k])}
          </span>
        ))}
      </p>
      <SignalMap cells={data.cells} zones={data.zones} />
      {data.bands.length > 0 && (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[11px] text-muted-foreground">
              <th className="font-normal">Banda</th>
              <th className="w-16 text-right font-normal">timp</th>
              <th className="w-20 text-right font-normal">semnal</th>
            </tr>
          </thead>
          <tbody>
            {data.bands.map((b) => (
              <tr key={b.band} className="border-t border-white/5">
                <td className="py-1">{b.band}</td>
                <td className="py-1 text-right tabular-nums">{pct(b.share)}</td>
                <td className="py-1 text-right tabular-nums">{b.sig !== null ? `${b.sig} dBm` : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data.zones.length > 0 && (
        <div className="space-y-1">
          <p className="text-[11px] text-muted-foreground">Căderi de internet (cele mai recente)</p>
          {data.zones.slice(0, 8).map((z) => (
            <p key={z.at} className="flex gap-2 text-sm">
              <span className="shrink-0 text-muted-foreground">
                {day(z.at)} {hm(z.at)}
              </span>
              <span className="whitespace-nowrap">
                {shortDuration(z.minutes)}
                {z.km > 0 ? ` · ${num(z.km, 1)} km` : ""}
              </span>
              {z.band && <span className="ml-auto truncate text-xs text-muted-foreground">după {z.band}</span>}
            </p>
          ))}
        </div>
      )}
    </>
  );
}

function SignalMap({ cells, zones }: { cells: SignalSummary["cells"]; zones: SignalSummary["zones"] }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!ref.current || cells.length === 0) return;
    let map: import("leaflet").Map | null = null;
    let cancelled = false;
    void import("leaflet").then((L) => {
      if (cancelled || !ref.current) return;
      map = L.map(ref.current, { zoomControl: true, attributionControl: true });
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: "© OpenStreetMap",
      }).addTo(map);
      // Cele bune întâi, ca punctele roșii să rămână deasupra.
      const order = { good: 0, fair: 1, weak: 2, none: 3 };
      const group = L.featureGroup().addTo(map);
      for (const c of [...cells].sort((a, b) => order[a.level] - order[b.level])) {
        const tip = [
          c.band ?? LABELS[c.level],
          c.sig !== null ? `${c.sig} dBm` : null,
          c.offline > 0 ? `fără internet ${pct(c.offline)}` : null,
        ]
          .filter(Boolean)
          .join(" · ");
        L.circleMarker([c.lat, c.lon], {
          radius: 5,
          stroke: false,
          fillColor: COLORS[c.level],
          fillOpacity: 0.85,
        })
          .bindTooltip(esc(tip))
          .addTo(group);
      }
      for (const z of zones) {
        L.circleMarker([z.lat, z.lon], { radius: 8, color: "#fff", weight: 2, fillColor: COLORS.none, fillOpacity: 1 })
          .bindTooltip(esc(`${day(z.at)} ${hm(z.at)} · fără internet ${shortDuration(z.minutes)}`))
          .addTo(group);
      }
      map.fitBounds(group.getBounds(), { padding: [16, 16], maxZoom: 15 });
    });
    return () => {
      cancelled = true;
      map?.remove();
    };
  }, [cells, zones]);
  return cells.length > 0 ? <div ref={ref} className="h-80 w-full overflow-hidden rounded-2xl" /> : null;
}
