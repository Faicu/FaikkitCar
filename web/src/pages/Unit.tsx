import { UnitCard } from "../components/UnitCard";
import { useLive } from "./Now";

/** Fila Navigație: performanța navigației Teyes, live (aceeași stare ca fila Acum). */
export function UnitPage() {
  const live = useLive();
  if (!live) return <div className="h-40 skeleton-sweep rounded-2xl" />;
  if (!live.unit) {
    return (
      <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">
        Nicio măsurare încă: apare de la FaikkitCar 1.1.62, cât navigația e trează.
      </p>
    );
  }
  return <UnitCard unit={live.unit} health={live.unitHealth} />;
}
