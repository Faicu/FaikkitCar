import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Fuel, MapPin } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { api, day, hm, type Suggestion } from "../api";
import { PlaceEditor } from "./Places";

/**
 * Propunerile: o alimentare văzută în rezervor, dar nescrisă în jurnal, și locurile unde
 * parchează des. Se confirmă cu o atingere (completând ce lipsește) sau se resping.
 */
export function Suggestions() {
  const { data } = useQuery({ queryKey: ["suggestions"], queryFn: api.suggestions, refetchInterval: 60_000 });
  if (!data || data.length === 0) return null;
  return (
    <div className="space-y-2 rounded-2xl border border-sky-500/30 bg-sky-500/5 p-4">
      <p className="text-xs font-semibold uppercase tracking-wider text-sky-300">Propuneri</p>
      {data.map((s) => (s.kind === "refuel" ? <RefuelItem key={s.key} s={s} /> : <PlaceItem key={s.key} s={s} />))}
    </div>
  );
}

function useDismiss(s: Suggestion) {
  const qc = useQueryClient();
  return async () => {
    try {
      await api.dismissSuggestion(s);
      await qc.invalidateQueries({ queryKey: ["suggestions"] });
    } catch (e) {
      toast.error((e as Error).message);
    }
  };
}

const btn = "rounded-lg px-3 py-1.5 text-sm";
const input = "w-24 rounded-lg border border-border/40 bg-background/40 px-2 py-1.5 text-sm";

function RefuelItem({ s }: { s: Extract<Suggestion, { kind: "refuel" }> }) {
  const qc = useQueryClient();
  const dismiss = useDismiss(s);
  const { data: fuel } = useQuery({ queryKey: ["fuel"], queryFn: api.fuel });
  const [open, setOpen] = useState(false);
  const [liters, setLiters] = useState(String(s.liters));
  const [price, setPrice] = useState("");
  const [busy, setBusy] = useState(false);
  async function save() {
    setBusy(true);
    try {
      await api.saveRefuel({
        at: s.at,
        liters: Number(liters.replace(",", ".")),
        price: price.trim() ? Number(price.replace(",", ".")) : null,
        odo: null,
        full: false,
        note: null,
      });
      await qc.invalidateQueries();
      toast.success("Alimentare salvată");
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="rounded-xl bg-white/5 p-3">
      <p className="flex items-center gap-2 text-sm">
        <Fuel className="h-4 w-4 shrink-0 text-sky-400" />
        <span>
          Ai alimentat pe <b>{day(s.at)}, {hm(s.at)}</b>? Rezervorul a crescut cu ~{s.liters} L.
        </span>
      </p>
      {open ? (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <label className="text-xs text-muted-foreground">
            Litri <input value={liters} onChange={(e) => setLiters(e.target.value)} className={input} />
          </label>
          <label className="text-xs text-muted-foreground">
            Preț lei/L{" "}
            <input
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              placeholder={fuel?.lastPrice != null ? String(fuel.lastPrice) : ""}
              className={input}
            />
          </label>
          <button
            type="button"
            disabled={busy || !Number(liters.replace(",", "."))}
            onClick={save}
            className={`${btn} bg-sky-500 font-semibold text-slate-950 disabled:opacity-40`}
          >
            Salvează
          </button>
        </div>
      ) : (
        <div className="mt-2 flex gap-2">
          <button type="button" onClick={() => setOpen(true)} className={`${btn} bg-sky-500/15 text-sky-300`}>
            Da, completez bonul
          </button>
          <button type="button" onClick={dismiss} className={`${btn} text-muted-foreground`}>
            Nu
          </button>
        </div>
      )}
    </div>
  );
}

function PlaceItem({ s }: { s: Extract<Suggestion, { kind: "place" }> }) {
  const dismiss = useDismiss(s);
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-xl bg-white/5 p-3">
      <p className="flex items-center gap-2 text-sm">
        <MapPin className="h-4 w-4 shrink-0 text-sky-400" />
        <span>
          Parchezi des aici{s.label ? <b>: {s.label}</b> : ""} ({s.visits} ori, ultima pe {day(s.lastAt)}). Îl
          salvezi ca loc?
        </span>
      </p>
      {open ? (
        <div className="mt-2">
          <PlaceEditor draft={{ name: "", lat: s.lat, lon: s.lon, radius: 100 }} onClose={() => setOpen(false)} />
        </div>
      ) : (
        <div className="mt-2 flex gap-2">
          <button type="button" onClick={() => setOpen(true)} className={`${btn} bg-sky-500/15 text-sky-300`}>
            Salvează ca loc
          </button>
          <button type="button" onClick={dismiss} className={`${btn} text-muted-foreground`}>
            Nu
          </button>
        </div>
      )}
    </div>
  );
}
