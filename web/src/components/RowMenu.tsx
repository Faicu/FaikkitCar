import { useEffect, useRef, useState } from "react";
import { MoreHorizontal } from "lucide-react";

export interface RowAction {
  label: string;
  run: () => void;
  danger?: boolean;
}

/**
 * Butonul „⋯” al unui rând (alimentări, mentenanță, locuri, service), ca în Panel: meniul cu
 * acțiunile lui în loc de un șir de linkuri colorate. Se închide la o atingere în afară.
 */
export function RowMenu({ actions }: { actions: RowAction[] }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: PointerEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    addEventListener("pointerdown", close);
    return () => removeEventListener("pointerdown", close);
  }, [open]);
  return (
    <div ref={ref} className="relative shrink-0" onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        title="Acțiuni"
        onClick={() => setOpen((o) => !o)}
        className="flex h-8 w-8 items-center justify-center rounded-full text-muted-foreground hover:bg-white/[0.08]"
      >
        <MoreHorizontal className="h-5 w-5" />
      </button>
      {open && (
        <div className="absolute right-0 top-9 z-20 min-w-40 overflow-hidden rounded-xl border border-white/10 bg-slate-900 py-1 shadow-xl">
          {actions.map((a) => (
            <button
              key={a.label}
              type="button"
              onClick={() => {
                setOpen(false);
                a.run();
              }}
              className={`block w-full px-4 py-2 text-left text-sm hover:bg-white/[0.06] ${a.danger ? "text-red-400" : ""}`}
            >
              {a.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
