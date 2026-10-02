import { useQuery } from "@tanstack/react-query";

import { Maintenance } from "../components/Maintenance";
import { Places } from "../components/Places";
import { api } from "../api";
import { LogPage } from "./Log";

/** Fila Mai mult: mentenanța, aplicația de telefon și jurnalul aplicației din mașină. */
export function MorePage() {
  const { data: car } = useQuery({ queryKey: ["car"], queryFn: api.car, refetchInterval: 60_000 });
  return (
    <>
      {car && <Maintenance reminders={car.reminders} odometer={car.odometer} />}
      <Places />
      <PanelDownload />
      <h2 className="px-1 pt-2 text-sm font-semibold text-muted-foreground">Jurnalul aplicației din mașină</h2>
      <LogPage />
    </>
  );
}

/** Linkul către aplicația de telefon, doar când CI-ul a publicat-o. */
function PanelDownload() {
  const { data } = useQuery({ queryKey: ["panelApk"], queryFn: api.panelApk, staleTime: 60_000 });
  if (!data || data.versionCode <= 0) return null;
  return (
    <a
      href="/api/panel/apk/download"
      className="flex items-center justify-between rounded-2xl glass-card glass-card-hover p-4"
    >
      <div>
        <p className="font-semibold">FaikkitCar Panel pentru telefon</p>
        <p className="text-xs text-muted-foreground">
          Versiunea {data.versionName} · apoi se actualizează singură
        </p>
      </div>
      <span className="text-sm text-sky-400">Descarcă APK</span>
    </a>
  );
}
