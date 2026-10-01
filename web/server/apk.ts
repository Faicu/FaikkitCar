// ---------------------------------------------------------------------------
// Ultimele APK-uri încărcate de CI: aplicația din mașină ("car", actualizarea din
// aplicație cu CAR_TOKEN) și aplicația de telefon ("panel", cu login). Câte un fișier
// + metadate, în data/apk. Server-only.
// ---------------------------------------------------------------------------

import { existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export type ApkKind = "car" | "panel";

export interface ApkInfo {
  versionCode: number;
  versionName: string;
  size: number;
  uploadedAt: string;
}

function dir(): string {
  return process.env.FAIKKITCAR_APK_DIR ?? "/opt/faikkitcar/data/apk";
}

// Numele păstrate de la prima versiune: aplicația din mașină = FaikkitCar.apk + latest.json.
const FILES: Record<ApkKind, { apk: string; meta: string }> = {
  car: { apk: "FaikkitCar.apk", meta: "latest.json" },
  panel: { apk: "FaikkitCarPanel.apk", meta: "panel.json" },
};

export function apkPath(kind: ApkKind = "car"): string {
  return join(dir(), FILES[kind].apk);
}

export function readApkInfo(kind: ApkKind = "car"): ApkInfo | null {
  try {
    return JSON.parse(readFileSync(join(dir(), FILES[kind].meta), "utf8")) as ApkInfo;
  } catch {
    return null;
  }
}

export function saveApk(
  data: Buffer,
  versionCode: number,
  versionName: string,
  kind: ApkKind = "car",
): ApkInfo {
  mkdirSync(dir(), { recursive: true });
  // Scriem alături și redenumim: o descărcare în curs nu vede niciodată un fișier pe jumătate.
  const tmp = `${apkPath(kind)}.tmp`;
  writeFileSync(tmp, data);
  renameSync(tmp, apkPath(kind));
  const info: ApkInfo = {
    versionCode,
    versionName,
    size: data.length,
    uploadedAt: new Date().toISOString(),
  };
  writeFileSync(join(dir(), FILES[kind].meta), JSON.stringify(info));
  return info;
}

export function apkExists(kind: ApkKind = "car"): boolean {
  return existsSync(apkPath(kind)) && statSync(apkPath(kind)).size > 0;
}
