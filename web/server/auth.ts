// ---------------------------------------------------------------------------
// Autentificarea: (1) mașina și CI-ul cu cheia CAR_TOKEN ("Authorization: Bearer …");
// (2) utilizatorul (site și aplicația Panel) cu ADMIN_USER / ADMIN_PASS, care primește
// o sesiune semnată cu SESSION_SECRET: cookie pentru site, token Bearer pentru Panel.
// ---------------------------------------------------------------------------

import { createHmac, timingSafeEqual } from "node:crypto";
import type { Context } from "hono";
import { getCookie } from "hono/cookie";
import { HTTPException } from "hono/http-exception";

export const SESSION_COOKIE = "fc_session";
export const SESSION_DAYS = 180;

function env(name: string): string {
  const v = process.env[name];
  if (!v) throw new HTTPException(500, { message: `${name} lipsește din .env` });
  return v;
}

function same(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

function bearer(c: Context): string {
  const h = c.req.header("authorization") ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : "";
}

/** Cheia mașinii (aplicația de pe navigație și CI-ul care publică APK-ul). */
export function requireCarToken(c: Context): void {
  if (!same(bearer(c), env("CAR_TOKEN"))) throw new HTTPException(401, { message: "Invalid token" });
}

function sign(payload: string): string {
  return createHmac("sha256", env("SESSION_SECRET")).update(payload).digest("base64url");
}

export function checkLogin(user: string, pass: string): boolean {
  // Ambele comparații rulează mereu: timpul nu trădează care câmp e greșit.
  const u = same(user, env("ADMIN_USER"));
  const p = same(pass, env("ADMIN_PASS"));
  return u && p;
}

export function createSession(): string {
  const payload = Buffer.from(
    JSON.stringify({ exp: Date.now() + SESSION_DAYS * 86_400_000 }),
  ).toString("base64url");
  return `${payload}.${sign(payload)}`;
}

function validSession(token: string): boolean {
  const [payload, sig] = token.split(".");
  if (!payload || !sig || !same(sig, sign(payload))) return false;
  try {
    const { exp } = JSON.parse(Buffer.from(payload, "base64url").toString()) as { exp: number };
    return typeof exp === "number" && exp > Date.now();
  } catch {
    return false;
  }
}

/** Utilizatorul logat: cookie (site) sau Bearer (aplicația Panel). */
export function isUser(c: Context): boolean {
  const token = getCookie(c, SESSION_COOKIE) ?? bearer(c);
  return token !== "" && validSession(token);
}

export function requireUser(c: Context): void {
  if (!isUser(c)) throw new HTTPException(401, { message: "Neautentificat" });
}

// Încercări de login: cel mult 10 pe 15 minute de la aceeași adresă.
const attempts = new Map<string, number[]>();
export function loginAllowed(ip: string): boolean {
  const now = Date.now();
  const recent = (attempts.get(ip) ?? []).filter((t) => now - t < 15 * 60_000);
  recent.push(now);
  attempts.set(ip, recent);
  return recent.length <= 10;
}
