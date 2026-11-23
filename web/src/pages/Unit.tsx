import { useState } from "react";
import { ChevronDown, Plug } from "lucide-react";

import { DtcCard, ObdCard, STATUS } from "../components/ObdCard";
import { ObdScanCard } from "../components/ObdScanCard";
import { SignalCard } from "../components/SignalCard";
import { UnitCard } from "../components/UnitCard";
import { useLive } from "./Now";

const OPEN_KEY = "unit.obdTestsOpen";

/**
 * Fila Navigație: erorile mașinii întâi, apoi performanța navigației Teyes (live, aceeași stare ca
 * fila Acum) și semnalul mobil; adaptorul OBD și scanarea lui (de test) strânse jos, închise implicit.
 */
export function UnitPage() {
  const live = useLive();
  const [open, setOpen] = useState(() => localStorage.getItem(OPEN_KEY) === "1");
  const toggle = () => {
    localStorage.setItem(OPEN_KEY, open ? "0" : "1");
    setOpen(!open);
  };
  return (
    <div className="space-y-3">
      {live && <DtcCard obd={live.obd} />}
      {!live ? (
        <div className="h-40 skeleton-sweep rounded-2xl" />
      ) : !live.unit ? (
        <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">
          Nicio măsurare încă: apare cât navigația e trează.
        </p>
      ) : (
        <UnitCard unit={live.unit} health={live.unitHealth} />
      )}
      <SignalCard />
      {live && (
        <div className="space-y-3">
          <button
            type="button"
            onClick={toggle}
            className="flex w-full items-center gap-2 rounded-2xl glass-card px-4 py-3 text-left"
          >
            <Plug className="h-4 w-4 shrink-0 text-sky-400" />
            <span className="font-semibold">Teste OBD</span>
            <span className="truncate text-sm text-muted-foreground">
              · {live.obd.enabled ? (live.obd.status ? STATUS[live.obd.status] : "fără semnal") : "oprit"}
            </span>
            <ChevronDown className={`ml-auto h-4 w-4 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} />
          </button>
          {open && <ObdCard obd={live.obd} />}
          {open && live.obd.enabled && <ObdScanCard />}
        </div>
      )}
    </div>
  );
}
