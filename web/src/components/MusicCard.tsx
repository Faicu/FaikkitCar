import { useEffect, useRef, useState } from "react";
import {
  Music,
  Pause,
  Play,
  Search,
  SkipBack,
  SkipForward,
  Volume1,
  Volume2,
  X,
} from "lucide-react";
import { toast } from "sonner";

import { api, Unauthorized } from "../api";
import type { MusicAction, MusicView, SongHit } from "../../server/music.ts";

const VOL_MAX = 36; // treapta maximă pe navigație

/** „3:07” */
function clock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return s >= 3600
    ? `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`
    : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

function send(action: MusicAction, value?: number | string) {
  api
    .musicCmd(action, value)
    .catch((e: Error) => toast.error(`Nu am trimis comanda: ${e.message}`));
}

/**
 * Muzica de pe navigație (YouTube Music), ca în Panel: coperta, piesa, derularea, comenzile,
 * volumul Teyes (0–36) și căutarea unei piese, care pornește în mașină. Long polling pe
 * /api/music; cardul apare doar cât navigația are o sesiune media.
 */
export function MusicCard() {
  const music = useMusic();
  const [, tick] = useState(0);
  const [seek, setSeek] = useState<number | null>(null);
  const [vol, setVol] = useState<number | null>(null);
  const volAt = useRef(0);
  const volSentAt = useRef(0);
  const [searching, setSearching] = useState(false);
  // Optimist: redare/pauză afișată imediat, până confirmă mașina.
  const [playing, setPlaying] = useState<{ v: boolean; at: number } | null>(null);

  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (music?.vol !== null && music?.vol !== undefined && Date.now() - volAt.current > 3000)
      setVol(music.vol);
    setPlaying(null);
  }, [music]);

  if (!music) return null;
  const isPlaying = playing && Date.now() - playing.at < 3000 ? playing.v : music.playing;
  const pos = Math.min(
    music.posMs + (music.playing ? music.ageMs + (Date.now() - music.receivedAt) : 0),
    music.durationMs || Infinity,
  );
  const shownPos = seek ?? pos;
  const sub = [music.artist, music.album && music.album !== music.title ? music.album : ""]
    .filter(Boolean)
    .join(" · ");

  function setVolume(v: number, final: boolean) {
    const nv = Math.max(0, Math.min(VOL_MAX, v));
    setVol(nv);
    volAt.current = Date.now();
    // Cât glisezi: cel mult o comandă la 300 ms (serverul păstrează doar ultima).
    if (final || Date.now() - volSentAt.current > 300) {
      volSentAt.current = Date.now();
      send("vol", nv);
    }
  }

  return (
    <div className="rounded-2xl glass-card p-4">
      <div className="flex items-center gap-3">
        <div className="relative flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-white/[0.06]">
          <Music className="h-6 w-6 text-muted-foreground" />
          {music.artId && (
            <img
              src={`/api/music/art/${music.artId}`}
              alt=""
              className="absolute inset-0 h-full w-full object-cover"
            />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="line-clamp-2 font-semibold leading-snug">
            {music.title || "Nimic de redat"}
          </p>
          {sub && <p className="truncate text-sm text-muted-foreground">{sub}</p>}
        </div>
        <button
          onClick={() => setSearching(true)}
          title="Caută o piesă"
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-white/[0.06] hover:bg-white/[0.1]"
        >
          <Search className="h-5 w-5" />
        </button>
      </div>

      <input
        type="range"
        min={0}
        max={Math.max(1, music.durationMs)}
        value={shownPos}
        onChange={(e) => setSeek(Number(e.target.value))}
        onPointerUp={() => {
          if (seek !== null) send("seek", seek);
          setTimeout(() => setSeek(null), 1500);
        }}
        onKeyUp={() => seek !== null && send("seek", seek)}
        className="music-range mt-4 w-full"
        style={
          {
            "--fill": `${(shownPos / Math.max(1, music.durationMs)) * 100}%`,
          } as React.CSSProperties
        }
      />
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>{clock(shownPos)}</span>
        <span>{music.durationMs > 0 ? clock(music.durationMs) : ""}</span>
      </div>

      <div className="mt-1 flex items-center justify-center gap-8">
        <button
          onClick={() => send("prev")}
          title="Anterioara"
          className="rounded-full p-3 hover:bg-white/[0.06]"
        >
          <SkipBack className="h-6 w-6 fill-current" />
        </button>
        <button
          onClick={() => {
            setPlaying({ v: !isPlaying, at: Date.now() });
            send("toggle");
          }}
          title={isPlaying ? "Pauză" : "Redă"}
          className="flex h-16 w-16 items-center justify-center rounded-full bg-sky-400 text-slate-950 hover:bg-sky-300"
        >
          {isPlaying ? (
            <Pause className="h-7 w-7 fill-current" />
          ) : (
            <Play className="ml-1 h-7 w-7 fill-current" />
          )}
        </button>
        <button
          onClick={() => send("next")}
          title="Următoarea"
          className="rounded-full p-3 hover:bg-white/[0.06]"
        >
          <SkipForward className="h-6 w-6 fill-current" />
        </button>
      </div>

      <div className="mt-3 flex items-center gap-2">
        <button
          disabled={vol === null}
          onClick={() => vol !== null && setVolume(vol - 1, true)}
          title="Mai încet"
          className="rounded-full p-2 text-muted-foreground hover:bg-white/[0.06] disabled:opacity-40"
        >
          <Volume1 className="h-5 w-5" />
        </button>
        <div className="relative flex-1">
          <input
            type="range"
            min={0}
            max={VOL_MAX}
            disabled={vol === null}
            value={vol ?? 0}
            onChange={(e) => setVolume(Number(e.target.value), false)}
            onPointerUp={(e) => setVolume(Number((e.target as HTMLInputElement).value), true)}
            className="music-range music-range-vol w-full"
            style={
              {
                "--fill": `${((vol ?? 0) / VOL_MAX) * 100}%`,
              } as React.CSSProperties
            }
          />
          {/* Valoarea în bulina barei. */}
          {vol !== null && (
            <span
              className="pointer-events-none absolute top-1/2 flex h-7 w-7 -translate-x-1/2 -translate-y-1/2 items-center justify-center text-xs font-bold text-slate-950"
              style={{ left: `calc(14px + (100% - 28px) * ${vol / VOL_MAX})` }}
            >
              {vol}
            </span>
          )}
        </div>
        <button
          disabled={vol === null}
          onClick={() => vol !== null && setVolume(vol + 1, true)}
          title="Mai tare"
          className="rounded-full p-2 text-muted-foreground hover:bg-white/[0.06] disabled:opacity-40"
        >
          <Volume2 className="h-5 w-5" />
        </button>
      </div>
      {searching && <SearchPanel onClose={() => setSearching(false)} />}
    </div>
  );
}

/**
 * Căutarea: panou sus, fără titlu: bara rotunjită cu lupa și „×”, rezultatele pe măsură ce
 * scrii (la 400 ms după ultima tastă). O piesă aleasă pornește în mașină.
 */
function SearchPanel({ onClose }: { onClose: () => void }) {
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<SongHit[] | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, [onClose]);
  useEffect(() => {
    if (q.trim().length < 2) {
      setHits(null);
      return;
    }
    const abort = new AbortController();
    const t = setTimeout(() => {
      setBusy(true);
      api
        .musicSearch(q.trim(), abort.signal)
        .then(setHits)
        .catch(
          (e) => !abort.signal.aborted && toast.error(`Căutarea a eșuat: ${(e as Error).message}`),
        )
        .finally(() => !abort.signal.aborted && setBusy(false));
    }, 400);
    return () => {
      clearTimeout(t);
      abort.abort();
    };
  }, [q]);
  return (
    <div className="fixed inset-0 z-50 bg-black/60" onClick={onClose}>
      <div
        className="mx-auto max-w-xl rounded-b-3xl bg-slate-900 p-4 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2 rounded-full border border-sky-400 bg-white/[0.06] px-4">
          <Search className="h-5 w-5 shrink-0 text-muted-foreground" />
          <input
            autoFocus
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Piesă, artist, album"
            className="min-w-0 flex-1 bg-transparent py-3 text-base outline-none placeholder:text-muted-foreground"
          />
          {q && (
            <button
              onClick={() => setQ("")}
              className="rounded-full p-1.5 text-muted-foreground hover:bg-white/[0.08]"
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </div>
        <div className="mt-2 max-h-[60vh] overflow-y-auto">
          {busy && !hits && <p className="p-3 text-sm text-muted-foreground">Caut...</p>}
          {hits?.length === 0 && <p className="p-3 text-sm text-muted-foreground">Nimic găsit.</p>}
          {hits?.map((h) => (
            <button
              key={h.id}
              onClick={() => {
                send("open", h.id);
                toast.success(`Pornește în mașină: ${h.title}`);
                onClose();
              }}
              className="flex w-full items-center gap-3 rounded-xl p-2 text-left hover:bg-white/[0.06]"
            >
              <div className="h-12 w-12 shrink-0 overflow-hidden rounded-lg bg-white/[0.06]">
                {h.thumb && <img src={h.thumb} alt="" className="h-full w-full object-cover" />}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate font-semibold">{h.title}</p>
                <p className="truncate text-sm text-muted-foreground">
                  {[h.artist, h.album, h.duration].filter(Boolean).join(" · ")}
                </p>
              </div>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

type Received = MusicView & { receivedAt: number };

/** Starea muzicii prin long polling; null = nicio sesiune media pe navigație. */
function useMusic(): Received | null {
  const [music, setMusic] = useState<Received | null>(null);
  useEffect(() => {
    const abort = new AbortController();
    let after: number | null = null;
    async function loop() {
      while (!abort.signal.aborted) {
        if (document.hidden) {
          await new Promise((r) => setTimeout(r, 1000));
          after = null;
          continue;
        }
        try {
          const r = await api.music(after, abort.signal);
          after = r.seq;
          setMusic(r.music ? { ...r.music, receivedAt: Date.now() } : null);
        } catch (e) {
          if (abort.signal.aborted) return;
          if (e instanceof Unauthorized) return;
          await new Promise((r) => setTimeout(r, 5000));
        }
      }
    }
    void loop();
    return () => abort.abort();
  }, []);
  return music;
}
