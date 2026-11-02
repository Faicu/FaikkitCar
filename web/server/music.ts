// ---------------------------------------------------------------------------
// Muzica de pe navigație (YouTube Music), comandată din Panel (02.11). Totul în memorie: o stare
// veche sau o comandă veche nu mai contează. Mașina trimite starea la fiecare schimbare (și la
// 30 s cât e o sesiune) și ține deschisă o cerere care așteaptă comenzi (long polling), deci o
// apăsare pe telefon ajunge în sub o secundă. Piesele se caută aici, în YouTube Music, iar
// mașina deschide linkul ales în aplicație.
// ---------------------------------------------------------------------------

import { HTTPException } from "hono/http-exception";

export interface MusicState {
  pkg: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  posMs: number;
  playing: boolean;
  /** Volumul Teyes (modulul de sunet), null dacă nu se știe. */
  vol: number | null;
  /** Coperta piesei de acum (GET /api/music/art/:id), null fără. */
  artId: string | null;
}

export interface MusicView extends MusicState {
  /** Vechimea poziției (ms) la momentul răspunsului: poziția de acum = posMs + ageMs dacă redă. */
  ageMs: number;
}

export type MusicAction = "play" | "pause" | "toggle" | "next" | "prev" | "seek" | "vol" | "open";
const ACTIONS = new Set<MusicAction>(["play", "pause", "toggle", "next", "prev", "seek", "vol", "open"]);

export interface MusicCmd {
  id: number;
  action: MusicAction;
  /** seek: ms; vol: nivelul; open: ID-ul piesei (11 caractere) sau al listei. */
  value: number | string | null;
}

/** Fără veste de la mașină de atât: muzica nu se mai arată (mașina o retrimite la 30 s). */
const STALE_MS = 75_000;
/** O comandă neluată de atât s-a pierdut (navigația adormită): nu mai pornește muzica mai târziu. */
const CMD_TTL_MS = 20_000;
const WAIT_MS = 25_000;

let state: MusicState | null = null;
let stateAt = 0;
let seq = 0;
const stateWaiters = new Set<() => void>();
const arts = new Map<string, Buffer>();

let cmdSeq = 0;
let queue: Array<MusicCmd & { at: number }> = [];
const carWaiters = new Set<() => void>();

const str = (v: unknown, max = 300) => (typeof v === "string" ? v.slice(0, max) : "");
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : 0);

/** Starea trimisă de mașină (null = nicio sesiune media); `art` = JPEG în base64, doar la piesă nouă. */
export function saveMusic(p: unknown): void {
  const o = (p ?? {}) as Record<string, unknown>;
  if (o.none === true) {
    state = null;
  } else {
    const artId = str(o.artId, 40) || null;
    if (artId && typeof o.art === "string" && o.art.length < 200_000) {
      arts.set(artId, Buffer.from(o.art, "base64"));
      // Doar ultimele coperți: una pe piesă, cât să le mai prindă telefonul.
      while (arts.size > 20) arts.delete(arts.keys().next().value as string);
    }
    state = {
      pkg: str(o.pkg, 100),
      title: str(o.title),
      artist: str(o.artist),
      album: str(o.album),
      durationMs: Math.max(0, num(o.durationMs)),
      posMs: Math.max(0, num(o.posMs)),
      playing: o.playing === true,
      vol: typeof o.vol === "number" ? o.vol : null,
      artId: artId && arts.has(artId) ? artId : null,
    };
  }
  stateAt = Date.now();
  seq++;
  for (const w of [...stateWaiters]) w();
}

export function readMusic(): { seq: number; music: MusicView | null } {
  const now = Date.now();
  if (!state || now - stateAt > STALE_MS) return { seq, music: null };
  return { seq, music: { ...state, ageMs: now - stateAt } };
}

/** Așteaptă o stare cu `seq` mai mare decât `after`, cel mult 25 s. */
export function waitMusic(after: number): Promise<void> {
  // Serverul repornit: seq a luat-o de la 0, deci telefonul primește imediat starea.
  if (after !== seq) return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer);
      stateWaiters.delete(done);
      resolve();
    };
    const timer = setTimeout(done, WAIT_MS);
    stateWaiters.add(done);
  });
}

export function readArt(id: string): Buffer | null {
  return arts.get(id) ?? null;
}

const ID = /^[A-Za-z0-9_-]{6,64}$/;

/** O comandă de pe telefon; mașina o ia la următoarea cerere (o ține deja deschisă). */
export function queueMusic(p: unknown): number {
  const o = (p ?? {}) as Record<string, unknown>;
  const action = o.action as MusicAction;
  if (!ACTIONS.has(action)) throw new HTTPException(400, { message: "Comandă necunoscută" });
  let value: number | string | null = null;
  if (action === "seek" || action === "vol") {
    if (typeof o.value !== "number" || !Number.isFinite(o.value) || o.value < 0) {
      throw new HTTPException(400, { message: "Valoare greșită" });
    }
    value = Math.round(o.value);
    if (action === "vol" && value > 36) throw new HTTPException(400, { message: "Volum prea mare" });
  } else if (action === "open") {
    if (typeof o.value !== "string" || !ID.test(o.value)) throw new HTTPException(400, { message: "Piesă greșită" });
    value = o.value;
  }
  const now = Date.now();
  // Apăsările repetate de volum: rămâne doar ultima.
  if (action === "vol") queue = queue.filter((q) => q.action !== "vol");
  queue.push({ id: ++cmdSeq, action, value, at: now });
  for (const w of [...carWaiters]) w();
  return cmdSeq;
}

/** Cererea mașinii: comenzile noi, sau așteaptă una cel mult 25 s. */
export async function takeMusic(): Promise<MusicCmd[]> {
  const fresh = () => {
    const cut = Date.now() - CMD_TTL_MS;
    queue = queue.filter((q) => q.at >= cut);
    return queue.length > 0;
  };
  if (!fresh()) {
    await new Promise<void>((resolve) => {
      const done = () => {
        clearTimeout(timer);
        carWaiters.delete(done);
        resolve();
      };
      const timer = setTimeout(done, WAIT_MS);
      carWaiters.add(done);
    });
    fresh();
  }
  const out = queue.map(({ id, action, value }) => ({ id, action, value }));
  queue = [];
  return out;
}

// ---------------------------------------------------------------- căutarea

export interface SongHit {
  id: string;
  title: string;
  artist: string;
  album: string;
  duration: string;
  thumb: string | null;
}

/** Filtrul „Melodii” din căutarea YouTube Music. */
const SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";

export async function searchSongs(q: string): Promise<SongHit[]> {
  const query = q.trim().slice(0, 200);
  if (!query) return [];
  const res = await fetch(`https://music.youtube.com/youtubei/v1/search?prettyPrint=false`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      origin: "https://music.youtube.com",
      "user-agent": "Mozilla/5.0",
    },
    body: JSON.stringify({
      context: { client: { clientName: "WEB_REMIX", clientVersion: "1.20241023.01.00", hl: "ro", gl: "RO" } },
      query,
      params: decodeURIComponent(SONGS),
    }),
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) throw new HTTPException(502, { message: `YouTube Music: HTTP ${res.status}` });
  return parseSongs(await res.json());
}

/** Rândurile de piese din răspunsul YouTube Music (pur, testat în music.test.ts). */
export function parseSongs(data: unknown, max = 20): SongHit[] {
  const out: SongHit[] = [];
  const seen = new Set<string>();
  const walk = (o: unknown): void => {
    if (out.length >= max || !o || typeof o !== "object") return;
    if (Array.isArray(o)) {
      for (const v of o) walk(v);
      return;
    }
    const r = (o as Record<string, unknown>).musicResponsiveListItemRenderer as Record<string, any> | undefined;
    if (r) {
      const id = r.playlistItemData?.videoId as string | undefined;
      const cols: string[][] = (r.flexColumns ?? []).map((c: any) =>
        (c?.musicResponsiveListItemFlexColumnRenderer?.text?.runs ?? []).map((x: any) => String(x.text ?? "")),
      );
      if (id && ID.test(id) && !seen.has(id) && cols[0]?.length) {
        seen.add(id);
        // A doua coloană: „Artist • Album • 4:27” (bucăți separate de „ • ”).
        const parts = (cols[1] ?? []).join("").split(" • ");
        const dur = parts.length > 1 && /^\d+:\d\d(:\d\d)?$/.test(parts[parts.length - 1]) ? parts.pop()! : "";
        const thumbs = r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails as Array<{ url: string }> | undefined;
        out.push({
          id,
          title: cols[0].join(""),
          artist: parts[0] ?? "",
          album: parts.slice(1).join(" • "),
          duration: dur,
          thumb: thumbs?.length ? thumbs[0].url : null,
        });
      }
      return;
    }
    for (const v of Object.values(o)) walk(v);
  };
  walk(data);
  return out;
}
