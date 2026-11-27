// ---------------------------------------------------------------------------
// Ultimul APK al aplicației din mașină încărcat de CI (actualizarea din aplicație, cu CAR_TOKEN):
// fișierul + metadatele, în data/apk. Panel nu mai are APK pe server (din 27.11 vine din Google Play).
// Server-only.
// ---------------------------------------------------------------------------

import { existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export interface ApkInfo {
  versionCode: number;
  versionName: string;
  size: number;
  uploadedAt: string;
}

function dir(): string {
  return process.env.FAIKKITCAR_APK_DIR ?? "/opt/faikkitcar/data/apk";
}

// Numele păstrate de la prima versiune.
const APK = "FaikkitCar.apk";
const META = "latest.json";

export function apkPath(): string {
  return join(dir(), APK);
}

export function readApkInfo(): ApkInfo | null {
  try {
    return JSON.parse(readFileSync(join(dir(), META), "utf8")) as ApkInfo;
  } catch {
    return null;
  }
}

export function saveApk(data: Buffer, versionCode: number, versionName: string): ApkInfo {
  mkdirSync(dir(), { recursive: true });
  // Scriem alături și redenumim: o descărcare în curs nu vede niciodată un fișier pe jumătate.
  const tmp = `${apkPath()}.tmp`;
  writeFileSync(tmp, data);
  renameSync(tmp, apkPath());
  const info: ApkInfo = {
    versionCode,
    versionName,
    size: data.length,
    uploadedAt: new Date().toISOString(),
  };
  writeFileSync(join(dir(), META), JSON.stringify(info));
  return info;
}

export function apkExists(): boolean {
  return existsSync(apkPath()) && statSync(apkPath()).size > 0;
}
