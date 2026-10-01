// ---------------------------------------------------------------------------
// Serverul FaikkitCar (car.faicu.ro): API-ul pentru aplicația din mașină
// (/api/car/*, cheia CAR_TOKEN), API-ul pentru site și aplicația Panel (/api/*,
// login) și site-ul (web/dist). Rulează direct cu Node ≥ 22.18 (tipurile TS se șterg).
// ---------------------------------------------------------------------------

import { createReadStream } from "node:fs";
import { Readable } from "node:stream";
import { serve } from "@hono/node-server";
import { serveStatic } from "@hono/node-server/serve-static";
import { Hono, type Context } from "hono";
import { deleteCookie, setCookie } from "hono/cookie";
import { HTTPException } from "hono/http-exception";

import { apkExists, apkPath, readApkInfo, saveApk } from "./apk.ts";
import {
  SESSION_COOKIE,
  SESSION_DAYS,
  checkLogin,
  createSession,
  isUser,
  loginAllowed,
  requireCarToken,
  requireUser,
} from "./auth.ts";
import {
  deleteReminder,
  markReminderDone,
  readLastPosition,
  readOdometer,
  readReminders,
  saveReminder,
  type ReminderInput,
} from "./car.ts";
import { applyFuel, deleteRefuel, readFuelSummary, saveRefuel, type RefuelInput } from "./fuel.ts";
import { insertLines, MAX_LINES_PER_REQUEST, readLog, type IncomingLine } from "./log.ts";
import { insertPoints, MAX_POINTS_PER_REQUEST, readTripPoints, readTrips, type IncomingPoint } from "./trips.ts";
import { MAX_TTS_CHARS, synthesize } from "./tts.ts";

const app = new Hono();

app.onError((err, c) => {
  if (err instanceof HTTPException) return c.json({ error: err.message }, err.status);
  console.error(err);
  return c.json({ error: err instanceof Error ? err.message : "Eroare" }, 500);
});

async function json<T>(c: Context, maxBytes: number): Promise<T> {
  const body = await c.req.text();
  if (body.length > maxBytes) throw new HTTPException(413, { message: "Too large" });
  try {
    return JSON.parse(body) as T;
  } catch {
    throw new HTTPException(400, { message: "Invalid JSON" });
  }
}

// ----------------------------------------------------------- mașina (CAR_TOKEN)

app.post("/api/car/log", async (c) => {
  requireCarToken(c);
  const p = await json<{ version?: unknown; lines?: unknown }>(c, 256 * 1024);
  if (!Array.isArray(p.lines) || p.lines.length > MAX_LINES_PER_REQUEST) {
    throw new HTTPException(400, { message: "Invalid lines" });
  }
  const version = typeof p.version === "string" ? p.version.slice(0, 40) : null;
  return c.json({ ok: true, added: insertLines(p.lines as IncomingLine[], version) });
});

app.post("/api/car/trip", async (c) => {
  requireCarToken(c);
  const p = await json<{ points?: unknown }>(c, 512 * 1024);
  if (!Array.isArray(p.points) || p.points.length > MAX_POINTS_PER_REQUEST) {
    throw new HTTPException(400, { message: "Invalid points" });
  }
  return c.json({ ok: true, added: insertPoints(p.points as IncomingPoint[]) });
});

// Kilometrajul, mentenanța (salutul vorbit) și ultimul APK (actualizarea din aplicație).
app.get("/api/car/status", (c) => {
  requireCarToken(c);
  const apk = readApkInfo();
  return c.json({
    odometer: readOdometer(),
    reminders: readReminders().map((r) => ({
      title: r.title,
      kmLeft: r.kmLeft,
      daysLeft: r.daysLeft,
      soon: r.soon,
      overdue: r.overdue,
    })),
    apk: apk ? { versionCode: apk.versionCode, versionName: apk.versionName, size: apk.size } : null,
  });
});

app.post("/api/car/tts", async (c) => {
  requireCarToken(c);
  const { text } = await json<{ text?: unknown }>(c, 4096);
  if (typeof text !== "string" || !text.trim() || text.length > MAX_TTS_CHARS) {
    throw new HTTPException(400, { message: "Invalid text" });
  }
  const audio = await synthesize(text);
  return c.body(new Uint8Array(audio), 200, {
    "Content-Type": "audio/mpeg",
    "Cache-Control": "no-store",
  });
});

// GET: metadatele ultimului APK; POST (din CI): APK-ul, cu X-Version-Code / X-Version-Name.
app.get("/api/car/apk", (c) => {
  requireCarToken(c);
  return c.json(readApkInfo() ?? { versionCode: 0 });
});

/** Primește un APK de la CI (octeții + X-Version-Code / X-Version-Name). */
async function receiveApk(c: Context, kind: "car" | "panel") {
  requireCarToken(c);
  const versionCode = Number(c.req.header("x-version-code"));
  const versionName = String(c.req.header("x-version-name") ?? "").slice(0, 40);
  if (!Number.isInteger(versionCode) || versionCode <= 0 || !versionName) {
    throw new HTTPException(400, { message: "Missing version headers" });
  }
  const body = Buffer.from(await c.req.arrayBuffer());
  if (body.length === 0) throw new HTTPException(400, { message: "Empty body" });
  if (body.length > 60 * 1024 * 1024) throw new HTTPException(413, { message: "Too large" });
  // Un APK e o arhivă ZIP: primii octeți sunt „PK”.
  if (body[0] !== 0x50 || body[1] !== 0x4b) throw new HTTPException(400, { message: "Not an APK" });
  return c.json(saveApk(body, versionCode, versionName, kind));
}

function sendApk(c: Context, kind: "car" | "panel") {
  if (!apkExists(kind)) throw new HTTPException(404, { message: "No APK" });
  const info = readApkInfo(kind);
  return c.body(Readable.toWeb(createReadStream(apkPath(kind))) as ReadableStream, 200, {
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": String(info?.size ?? ""),
    "Content-Disposition": `attachment; filename="${kind === "car" ? "FaikkitCar" : "FaikkitCarPanel"}.apk"`,
    "Cache-Control": "no-store",
  });
}

app.post("/api/car/apk", (c) => receiveApk(c, "car"));

app.get("/api/car/apk/download", (c) => {
  requireCarToken(c);
  return sendApk(c, "car");
});

// Aplicația Panel: CI-ul o încarcă tot cu CAR_TOKEN; telefonul o citește cu login-ul.
app.post("/api/panel/apk", (c) => receiveApk(c, "panel"));

app.get("/api/panel/apk", (c) => {
  requireUser(c);
  return c.json(readApkInfo("panel") ?? { versionCode: 0 });
});

app.get("/api/panel/apk/download", (c) => {
  requireUser(c);
  return sendApk(c, "panel");
});

// ----------------------------------------------------------- login (site + Panel)

app.post("/api/login", async (c) => {
  const ip = c.req.header("cf-connecting-ip") ?? c.req.header("x-forwarded-for") ?? "local";
  if (!loginAllowed(ip)) throw new HTTPException(429, { message: "Prea multe încercări, mai târziu" });
  const { user, pass } = await json<{ user?: unknown; pass?: unknown }>(c, 4096);
  if (typeof user !== "string" || typeof pass !== "string" || !checkLogin(user, pass)) {
    throw new HTTPException(401, { message: "Utilizator sau parolă greșită" });
  }
  const token = createSession();
  setCookie(c, SESSION_COOKIE, token, {
    httpOnly: true,
    secure: c.req.url.startsWith("https") || c.req.header("x-forwarded-proto") === "https",
    sameSite: "Lax",
    path: "/",
    maxAge: SESSION_DAYS * 86_400,
  });
  return c.json({ ok: true, token });
});

app.post("/api/logout", (c) => {
  deleteCookie(c, SESSION_COOKIE, { path: "/" });
  return c.json({ ok: true });
});

app.get("/api/me", (c) => c.json({ user: isUser(c) }));

// ----------------------------------------------------------- date (utilizator)

app.get("/api/trips", (c) => {
  requireUser(c);
  return c.json(applyFuel(readTrips()));
});

app.get("/api/trips/points", (c) => {
  requireUser(c);
  return c.json(readTripPoints(c.req.query("start") ?? "", c.req.query("end") ?? ""));
});

app.get("/api/car", (c) => {
  requireUser(c);
  return c.json({ position: readLastPosition(), odometer: readOdometer(), reminders: readReminders() });
});

app.post("/api/reminders", async (c) => {
  requireUser(c);
  saveReminder(await json<ReminderInput>(c, 8192));
  return c.json({ ok: true });
});

app.delete("/api/reminders/:id", (c) => {
  requireUser(c);
  deleteReminder(Number(c.req.param("id")));
  return c.json({ ok: true });
});

app.post("/api/reminders/:id/done", (c) => {
  requireUser(c);
  markReminderDone(Number(c.req.param("id")));
  return c.json({ ok: true });
});

app.get("/api/fuel", (c) => {
  requireUser(c);
  return c.json(readFuelSummary());
});

app.post("/api/refuels", async (c) => {
  requireUser(c);
  saveRefuel(await json<RefuelInput>(c, 8192));
  return c.json({ ok: true });
});

app.delete("/api/refuels/:id", (c) => {
  requireUser(c);
  deleteRefuel(Number(c.req.param("id")));
  return c.json({ ok: true });
});

app.get("/api/log", (c) => {
  requireUser(c);
  return c.json(readLog(c.req.query("eventsOnly") === "1"));
});

app.all("/api/*", (c) => c.json({ error: "Not found" }, 404));

// ----------------------------------------------------------- site

const dist = new URL("../dist/", import.meta.url).pathname;
app.use("/*", serveStatic({ root: dist }));
// Aplicația e o singură pagină: orice altă cale primește index.html.
app.get("*", serveStatic({ path: `${dist}index.html` }));

const port = Number(process.env.PORT ?? 3001);
// Pe toate interfețele, ca FaikkitBox: tunelul Cloudflare rulează în Docker și vine prin
// gateway-ul rețelei lui (172.20.0.1), nu prin 127.0.0.1.
const hostname = process.env.HOST ?? "0.0.0.0";
serve({ fetch: app.fetch, port, hostname }, () => {
  console.log(`FaikkitCar pe http://${hostname}:${port}`);
});
