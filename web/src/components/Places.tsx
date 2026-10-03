import { useQuery, useQueryClient } from "@tanstack/react-query";
import { MapPin, Pencil, Plus, Search, Trash2 } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { toast } from "sonner";
import "leaflet/dist/leaflet.css";

import { api, type GeocodeResult, type Place } from "../api";

/** Ce se editează: un loc existent sau unul nou, cu o poziție de pornire (opțională). */
export interface PlaceDraft {
  id?: number;
  name: string;
  lat: number | null;
  lon: number | null;
  radius: number;
}

const RADII = [50, 100, 150, 250];
// București, cât nu avem altă poziție.
const DEFAULT_CENTER: [number, number] = [44.4268, 26.1025];

/**
 * Locurile salvate (Acasă, Serviciu, Frizerie...): călătoriile, opririle și mașina parcată se
 * denumesc după ele. Un loc se adaugă după adresă, cu pinul mutat pe hartă, sau unde e mașina.
 */
export function Places() {
  const { data: places } = useQuery({ queryKey: ["places"], queryFn: api.places });
  const { data: car } = useQuery({ queryKey: ["car"], queryFn: api.car });
  const [draft, setDraft] = useState<PlaceDraft | null>(null);
  const pos = car?.position;
  return (
    <div className="space-y-2 rounded-2xl glass-card p-4">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold">Locuri salvate</h2>
        {!draft && (
          <button
            type="button"
            onClick={() =>
              setDraft({ name: "", lat: pos?.lat ?? null, lon: pos?.lon ?? null, radius: 100 })
            }
            className="flex items-center gap-1 rounded-lg px-2 py-1 text-sm text-sky-400 hover:bg-sky-500/10"
          >
            <Plus className="h-4 w-4" /> Adaugă
          </button>
        )}
      </div>
      <p className="text-xs text-muted-foreground">
        Călătoriile apar ca „Acasă → Serviciu”, iar mașina parcată „la Serviciu”.
      </p>
      {draft && <PlaceEditor draft={draft} onClose={() => setDraft(null)} />}
      {(places ?? []).map((p: Place) => (
        <div key={p.id} className="flex items-center gap-2 rounded-xl bg-white/5 px-3 py-2">
          <MapPin className="h-4 w-4 text-sky-400" />
          <span className="flex-1 font-medium">
            {p.name} <span className="text-xs font-normal text-muted-foreground">· {p.radius} m</span>
          </span>
          <button
            type="button"
            title="Modifică"
            onClick={() => setDraft({ id: p.id, name: p.name, lat: p.lat, lon: p.lon, radius: p.radius })}
            className="rounded-lg p-1.5 text-sky-400 hover:bg-sky-500/10"
          >
            <Pencil className="h-4 w-4" />
          </button>
          <DeletePlace place={p} />
        </div>
      ))}
    </div>
  );
}

function DeletePlace({ place }: { place: Place }) {
  const qc = useQueryClient();
  return (
    <button
      type="button"
      title="Șterge"
      onClick={async () => {
        if (!confirm(`Ștergi „${place.name}”?`)) return;
        try {
          await api.deletePlace(place.id);
          await qc.invalidateQueries();
          toast.success("Loc șters");
        } catch (e) {
          toast.error((e as Error).message);
        }
      }}
      className="rounded-lg p-1.5 text-red-400 hover:bg-red-500/10"
    >
      <Trash2 className="h-4 w-4" />
    </button>
  );
}

/** Numele, căutarea adresei, harta cu pinul (atinge harta sau trage pinul) și raza. */
export function PlaceEditor({ draft, onClose }: { draft: PlaceDraft; onClose: () => void }) {
  const qc = useQueryClient();
  const [name, setName] = useState(draft.name);
  const [pin, setPin] = useState<[number, number] | null>(
    draft.lat !== null && draft.lon !== null ? [draft.lat, draft.lon] : null,
  );
  const [radius, setRadius] = useState(draft.radius);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<GeocodeResult[] | null>(null);
  const [busy, setBusy] = useState(false);

  async function search(e: FormEvent) {
    e.preventDefault();
    if (query.trim().length < 3) return;
    setBusy(true);
    try {
      const r = await api.geocode(query);
      setResults(r);
      if (r.length === 1) setPin([r[0].lat, r[0].lon]);
    } catch (err) {
      toast.error((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    if (!pin) return;
    setBusy(true);
    try {
      await api.savePlace({ id: draft.id, name, lat: pin[0], lon: pin[1], radius });
      await qc.invalidateQueries();
      toast.success("Loc salvat");
      onClose();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const input = "w-full rounded-lg border border-border/40 bg-background/40 px-3 py-2 text-sm";
  return (
    <div className="space-y-2 rounded-xl border border-sky-500/30 bg-sky-500/5 p-3">
      <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Nume (ex. Frizerie)" className={input} />
      <form onSubmit={search} className="flex gap-2">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Adresa (ex. Bulevardul Timișoara 48)"
          className={input}
        />
        <button
          disabled={busy || query.trim().length < 3}
          className="flex items-center gap-1 rounded-lg bg-white/10 px-3 text-sm disabled:opacity-40"
        >
          <Search className="h-4 w-4" /> Caută
        </button>
      </form>
      {results && results.length === 0 && (
        <p className="text-xs text-muted-foreground">Nicio adresă găsită; mută pinul direct pe hartă.</p>
      )}
      {results && results.length > 1 && (
        <div className="space-y-1">
          {results.map((r) => (
            <button
              key={`${r.lat},${r.lon}`}
              type="button"
              onClick={() => setPin([r.lat, r.lon])}
              className="block w-full rounded-lg bg-white/5 px-3 py-1.5 text-left text-xs hover:bg-white/10"
            >
              {r.label}
            </button>
          ))}
        </div>
      )}
      <PinMap pin={pin} radius={radius} onPin={setPin} />
      <p className="text-xs text-muted-foreground">
        {pin ? "Atinge harta sau trage pinul ca să-l muți." : "Caută adresa sau atinge harta ca să pui pinul."}
      </p>
      <div className="flex flex-wrap items-center gap-1 text-sm">
        <span className="mr-1 text-muted-foreground">Rază</span>
        {RADII.map((r) => (
          <button
            key={r}
            type="button"
            onClick={() => setRadius(r)}
            className={`rounded-lg px-2.5 py-1 ${r === radius ? "bg-sky-500/20 text-sky-300" : "bg-white/5"}`}
          >
            {r} m
          </button>
        ))}
      </div>
      <div className="flex justify-end gap-2 pt-1">
        <button type="button" onClick={onClose} className="rounded-lg px-3 py-1.5 text-sm text-muted-foreground">
          Renunță
        </button>
        <button
          type="button"
          disabled={busy || !pin || !name.trim()}
          onClick={save}
          className="rounded-lg bg-sky-500 px-3 py-1.5 text-sm font-semibold text-slate-950 disabled:opacity-40"
        >
          Salvează
        </button>
      </div>
    </div>
  );
}

/** Harta cu pinul mutabil și cercul razei (Leaflet, încărcat doar în browser). */
function PinMap({
  pin,
  radius,
  onPin,
}: {
  pin: [number, number] | null;
  radius: number;
  onPin: (p: [number, number]) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const state = useRef<{
    L?: typeof import("leaflet");
    map?: import("leaflet").Map;
    marker?: import("leaflet").Marker;
    circle?: import("leaflet").Circle;
  }>({});
  const onPinRef = useRef(onPin);
  onPinRef.current = onPin;

  // Harta se creează o singură dată; pinul și raza se actualizează pe ea.
  useEffect(() => {
    let cancelled = false;
    void import("leaflet").then((L) => {
      if (cancelled || !ref.current) return;
      const map = L.map(ref.current).setView(pin ?? DEFAULT_CENTER, pin ? 17 : 12);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: "© OpenStreetMap",
      }).addTo(map);
      map.on("click", (e) => onPinRef.current([e.latlng.lat, e.latlng.lng]));
      state.current = { L, map };
      update();
    });
    return () => {
      cancelled = true;
      state.current.map?.remove();
      state.current = {};
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function update() {
    const { L, map } = state.current;
    if (!L || !map || !pin) return;
    if (!state.current.marker) {
      const icon = L.divIcon({
        className: "",
        html: '<div style="width:18px;height:18px;border-radius:50%;background:#38bdf8;border:3px solid #fff;box-shadow:0 1px 4px #0008"></div>',
        iconSize: [18, 18],
        iconAnchor: [9, 9],
      });
      const marker = L.marker(pin, { draggable: true, icon }).addTo(map);
      marker.on("dragend", () => {
        const p = marker.getLatLng();
        onPinRef.current([p.lat, p.lng]);
      });
      state.current.marker = marker;
      state.current.circle = L.circle(pin, { radius, color: "#38bdf8", weight: 1, fillOpacity: 0.12 }).addTo(map);
    } else {
      state.current.marker.setLatLng(pin);
      state.current.circle?.setLatLng(pin).setRadius(radius);
    }
  }

  useEffect(() => {
    update();
    const { map } = state.current;
    if (map && pin && !map.getBounds().contains(pin)) map.setView(pin, 17);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pin, radius]);

  return <div ref={ref} className="h-56 w-full overflow-hidden rounded-xl" />;
}
