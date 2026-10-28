import { SignalCard } from "../components/SignalCard";
import { UnitCard } from "../components/UnitCard";
import { useLive } from "./Now";

/** Fila Navigație: performanța navigației Teyes, live (aceeași stare ca fila Acum), și semnalul mobil. */
export function UnitPage() {
  const live = useLive();
  return (
    <div className="space-y-3">
      {!live ? (
        <div className="h-40 skeleton-sweep rounded-2xl" />
      ) : !live.unit ? (
        <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">
          Nicio măsurare încă: apare de la FaikkitCar 1.1.62, cât navigația e trează.
        </p>
      ) : (
        <UnitCard unit={live.unit} health={live.unitHealth} />
      )}
      <SignalCard />
    </div>
  );
}
