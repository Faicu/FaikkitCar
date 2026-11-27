import { useQuery } from "@tanstack/react-query";

import { Maintenance } from "../components/Maintenance";
import { Places } from "../components/Places";
import { ServiceLog } from "../components/ServiceLog";
import { api } from "../api";
import { LogPage } from "./Log";

/** Fila Mai mult: mentenanța, aplicația de telefon și jurnalul aplicației din mașină. */
export function MorePage() {
  const { data: car } = useQuery({ queryKey: ["car"], queryFn: api.car, refetchInterval: 60_000 });
  return (
    <>
      {car && <Maintenance reminders={car.reminders} odometer={car.odometer} />}
      <ServiceLog />
      <Places />
      <h2 className="px-1 pt-2 text-sm font-semibold text-muted-foreground">Jurnalul aplicației din mașină</h2>
      <LogPage />
    </>
  );
}
