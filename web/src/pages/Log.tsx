import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Car, Terminal } from "lucide-react";

import { LOG_CATEGORIES } from "../../server/log-category";
import { api, relativeTime, type CarCmd, type LogEntry } from "../api";

// Liniile trimise cu întârziere mare (buffer offline, ceas greșit după boot) primesc o notă.
const LATE_MS = 2 * 60_000;

// Filele jurnalului: „Evenimente” (implicit, fără CAN și comenzi), fiecare categorie, apoi tot.
const TABS: Array<{ id: string; label: string }> = [
  { id: "", label: "Evenimente" },
  ...LOG_CATEGORIES.map((c) => ({ id: c.id as string, label: c.label })),
  { id: "toate", label: "Tot" },
];

function lineColor(line: string): string {
  if (line.startsWith("TREZIRE")) return "text-emerald-400 font-semibold";
  if (/PIERDUT|REFUZAT|eroare|nu am putut|CADERE/i.test(line)) return "text-red-400";
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
  const [tab, setTab] = useState("");
  return (
    <>
      <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setTab(t.id)}
            className={`shrink-0 rounded-full px-3 py-1.5 text-sm ${
              tab === t.id ? "bg-sky-500/20 text-sky-300" : "glass-card text-muted-foreground hover:text-foreground"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab === "comenzi" ? <CmdLog /> : <Lines cat={tab} />}
    </>
  );
}

function Lines({ cat }: { cat: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ["log", cat],
    queryFn: () => api.log(cat || undefined),
    refetchInterval: 5_000,
    staleTime: 3_000,
  });
  const entries: LogEntry[] = data ?? [];
  const lastReceived = entries.reduce<string | null>(
    (max, e) => (max === null || e.receivedAt > max ? e.receivedAt : max),
    null,
  );
  const label = TABS.find((t) => t.id === cat)?.label ?? "";

  return (
    <>
      <div className="flex items-center gap-2.5 rounded-2xl glass-card p-4">
        <Car className="h-5 w-5 text-sky-400" />
        <div>
          <p className="font-semibold">
            {entries.length} linii · {label.toLowerCase()}
          </p>
          <p className="text-xs text-muted-foreground">
            {lastReceived ? `Ultima primită ${relativeTime(lastReceived)} · ` : ""}
            {entries[0]?.version ? `aplicație ${entries[0].version} · ` : ""}ora e cea de pe navigație
          </p>
        </div>
      </div>

      {isLoading && <div className="h-40 skeleton-sweep rounded-2xl" />}

      {!isLoading && entries.length === 0 && (
        <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">Nicio linie aici.</p>
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

function cmdStatus(c: CarCmd): { text: string; color: string } {
  if (c.doneAt === null) return c.sentAt ? { text: "rulează", color: "text-amber-400" } : { text: "în așteptare", color: "text-muted-foreground" };
  if (c.exitCode === 0) return { text: "cod 0", color: "text-emerald-400" };
  return { text: c.exitCode === null ? "fără cod" : `cod ${c.exitCode}`, color: "text-red-400" };
}

/** Comenzile shell rulate pe navigație (tabela car_cmd), cu rezultatul lor. */
function CmdLog() {
  const [open, setOpen] = useState<number | null>(null);
  const { data, isLoading } = useQuery({
    queryKey: ["cmds"],
    queryFn: api.cmds,
    refetchInterval: 5_000,
    staleTime: 3_000,
  });
  const cmds: CarCmd[] = data ?? [];

  return (
    <>
      <div className="flex items-center gap-2.5 rounded-2xl glass-card p-4">
        <Terminal className="h-5 w-5 text-sky-400" />
        <div>
          <p className="font-semibold">{cmds.length} comenzi la distanță</p>
          <p className="text-xs text-muted-foreground">
            Rulate pe navigație doar cu „Comenzi de la distanta” pornit · ora serverului
          </p>
        </div>
      </div>

      {isLoading && <div className="h-40 skeleton-sweep rounded-2xl" />}

      {!isLoading && cmds.length === 0 && (
        <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">Nicio comandă încă.</p>
      )}

      {cmds.map((c) => {
        const st = cmdStatus(c);
        const ms = c.doneAt && c.sentAt ? new Date(c.doneAt).getTime() - new Date(c.sentAt).getTime() : null;
        return (
          <div key={c.id} className="rounded-2xl glass-card p-3 text-xs">
            <button
              type="button"
              onClick={() => setOpen(open === c.id ? null : c.id)}
              className="flex w-full items-start gap-2 text-left"
            >
              <span className="shrink-0 text-muted-foreground">
                #{c.id} · {formatTime(c.createdAt)}
              </span>
              <span className={`shrink-0 ${st.color}`}>{st.text}</span>
              {ms !== null && <span className="shrink-0 text-muted-foreground">{(ms / 1000).toFixed(1)} s</span>}
            </button>
            <pre className="mt-1 whitespace-pre-wrap break-words font-mono text-foreground/90">{c.cmd}</pre>
            {open === c.id && (
              <pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-black/30 p-2 font-mono text-muted-foreground">
                {c.output || "(fără rezultat)"}
              </pre>
            )}
            {open !== c.id && c.output && (
              <button type="button" onClick={() => setOpen(c.id)} className="mt-1 text-sky-400">
                Arată rezultatul ({c.output.length} caractere)
              </button>
            )}
          </div>
        );
      })}
    </>
  );
}
