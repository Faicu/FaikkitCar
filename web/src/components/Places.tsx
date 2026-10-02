import { useQuery, useQueryClient } from "@tanstack/react-query";
import { MapPin, Trash2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { api, type Place } from "../api";

/**
 * Locurile salvate (Acasă, Serviciu...): călătoriile și mașina parcată se denumesc după ele.
 * Un loc nou se salvează unde e mașina acum (ultima poziție GPS).
 */
export function Places() {
  const qc = useQueryClient();
  const { data: places } = useQuery({ queryKey: ["places"], queryFn: api.places });
  const { data: car } = useQuery({ queryKey: ["car"], queryFn: api.car });
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  async function run(action: () => Promise<unknown>, ok: string) {
    setBusy(true);
    try {
      await action();
      await qc.invalidateQueries();
      toast.success(ok);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const pos = car?.position;
  return (
    <div className="space-y-2 rounded-2xl glass-card p-4">
      <h2 className="font-semibold">Locuri salvate</h2>
      <p className="text-xs text-muted-foreground">
        Călătoriile apar ca „Acasă → Serviciu”, iar mașina parcată „la Serviciu” (rază 150 m).
      </p>
      {(places ?? []).map((p: Place) => (
        <div key={p.id} className="flex items-center gap-2 rounded-xl bg-white/5 px-3 py-2">
          <MapPin className="h-4 w-4 text-sky-400" />
          <span className="flex-1 font-medium">{p.name}</span>
          <a
            href={`https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}`}
            target="_blank"
            rel="noreferrer"
            className="text-xs text-sky-400"
          >
            hartă
          </a>
          <button
            type="button"
            disabled={busy}
            title="Șterge"
            onClick={() => {
              if (confirm(`Ștergi „${p.name}”?`)) void run(() => api.deletePlace(p.id), "Loc șters");
            }}
            className="rounded-lg p-1.5 text-red-400 hover:bg-red-500/10"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      ))}
      {pos && (
        <form
          className="flex gap-2 pt-1"
          onSubmit={(e) => {
            e.preventDefault();
            void run(() => api.savePlace({ name, lat: pos.lat, lon: pos.lon }), "Loc salvat").then(() =>
              setName(""),
            );
          }}
        >
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Nume (ex. Părinți)"
            className="min-w-0 flex-1 rounded-lg border border-border/40 bg-background/40 px-3 py-1.5 text-sm"
          />
          <button
            disabled={busy || !name.trim()}
            className="rounded-lg bg-sky-500 px-3 py-1.5 text-sm font-semibold text-slate-950 disabled:opacity-40"
          >
            Salvează unde e mașina
          </button>
        </form>
      )}
    </div>
  );
}
