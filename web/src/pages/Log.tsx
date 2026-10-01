import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Car } from "lucide-react";

import { api, relativeTime, type LogEntry } from "../api";

// Liniile trimise cu întârziere mare (buffer offline, ceas greșit după boot) primesc o notă.
const LATE_MS = 2 * 60_000;

function lineColor(line: string): string {
  if (line.startsWith("TREZIRE")) return "text-emerald-400 font-semibold";
  if (/PIERDUT|REFUZAT|eroare|nu am putut/i.test(line)) return "text-red-400";
  if (/^(Serviciu|Pornit din autostart|BootReceiver)/.test(line)) return "text-sky-400";
  return "text-foreground/90";
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString("ro-RO", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function LogPage() {
  const [eventsOnly, setEventsOnly] = useState(true);
  const { data, isLoading } = useQuery({
    queryKey: ["log", eventsOnly],
    queryFn: () => api.log(eventsOnly),
    refetchInterval: 5_000,
    staleTime: 3_000,
  });
  const entries: LogEntry[] = data ?? [];
  const lastReceived = entries.reduce<string | null>(
    (max, e) => (max === null || e.receivedAt > max ? e.receivedAt : max),
    null,
  );

  return (
    <>
      <p className="text-xs text-muted-foreground">
        {lastReceived ? `Ultima linie primită ${relativeTime(lastReceived)}` : "Nicio linie primită"}
      </p>

      <div className="flex items-center justify-between rounded-2xl glass-card p-4">
        <div className="flex items-center gap-2.5">
          <Car className="h-5 w-5 text-sky-400" />
          <div>
            <p className="font-semibold">
              {entries.length} linii{eventsOnly ? " · doar evenimente" : ""}
            </p>
            <p className="text-xs text-muted-foreground">
              {entries[0]?.version ? `Aplicație ${entries[0].version} · ` : ""}ora afișată e cea de
              pe navigație
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => setEventsOnly((v) => !v)}
            className="rounded-lg px-3 py-1.5 text-sm text-sky-400 hover:bg-sky-500/10"
          >
            {eventsOnly ? "Arată și CAN/diagnostic" : "Doar evenimente"}
          </button>
        </div>
      </div>

      {isLoading && <div className="h-40 skeleton-sweep rounded-2xl" />}

      {!isLoading && entries.length === 0 && (
        <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">
          Nicio linie primită încă. Activează „Trimite jurnalul la server” în aplicația FaikkitCar de pe
          navigație.
        </p>
      )}

      {entries.length > 0 && (
        <div className="rounded-2xl glass-card p-3 font-mono text-xs leading-relaxed">
          {entries.map((e) => {
            const late =
              new Date(e.receivedAt).getTime() - new Date(e.deviceAt).getTime() > LATE_MS;
            return (
              <div key={e.id} className="flex gap-2 border-b border-border/30 py-1 last:border-0">
                <span className="shrink-0 text-muted-foreground">{formatTime(e.deviceAt)}</span>
                <span className={`min-w-0 break-words ${lineColor(e.line)}`}>
                  {e.line}
                  {late && (
                    <span className="ml-1 text-muted-foreground">
                      (primit {formatTime(e.receivedAt)})
                    </span>
                  )}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
