// ---------------------------------------------------------------------------
// Ultimul APK al aplicației FaikkitCar, încărcat de CI (POST /api/car/apk) și descărcat
// de aplicație pentru actualizarea din aplicație. Un singur fișier + metadate. Server-only.
// ---------------------------------------------------------------------------

import { mkdirSync, readFileSync, renameSync, writeFileSync, existsSync, statSync } from "node:fs";
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

export function apkPath(): string {
  return join(dir(), "FaikkitCar.apk");
}

export function readApkInfo(): ApkInfo | null {
  try {
    return JSON.parse(readFileSync(join(dir(), "latest.json"), "utf8")) as ApkInfo;
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
  writeFileSync(join(dir(), "latest.json"), JSON.stringify(info));
  return info;
}

export function apkExists(): boolean {
  return existsSync(apkPath()) && statSync(apkPath()).size > 0;
}
