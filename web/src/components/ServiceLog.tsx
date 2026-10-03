import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Paperclip, Pencil, Plus, Trash2, Wrench, X } from "lucide-react";
import { useRef, useState } from "react";
import { toast } from "sonner";

import { api, lei, type ServiceEntry } from "../api";

const today = () => new Date().toLocaleDateString("en-CA"); // AAAA-LL-ZZ, ora locală

/**
 * Jurnalul de service: ce s-a făcut la mașină (ulei, piese, ITP...), când, la ce kilometraj și
 * cu cât, plus bonurile și facturile (poze sau PDF). Costurile intră în raportul lunii.
 */
export function ServiceLog() {
  const { data } = useQuery({ queryKey: ["service"], queryFn: api.service });
  const [edit, setEdit] = useState<Partial<ServiceEntry> | null>(null);
  return (
    <div className="space-y-2 rounded-2xl glass-card p-4">
      <div className="flex items-center justify-between">
        <h2 className="flex items-center gap-2 font-semibold">
          <Wrench className="h-4 w-4 text-sky-400" /> Jurnal de service
        </h2>
        {!edit && (
          <button
            type="button"
            onClick={() => setEdit({ date: today() })}
            className="flex items-center gap-1 rounded-lg px-2 py-1 text-sm text-sky-400 hover:bg-sky-500/10"
          >
            <Plus className="h-4 w-4" /> Adaugă
          </button>
        )}
      </div>
      {edit && <ServiceForm entry={edit} onClose={() => setEdit(null)} />}
      {data && data.length === 0 && !edit && (
        <p className="text-xs text-muted-foreground">
          Nicio lucrare încă. Trece aici schimbul de ulei, piesele, ITP-ul, cu bonul atașat.
        </p>
      )}
      {(data ?? []).map((e) => (
        <ServiceRow key={e.id} e={e} onEdit={() => setEdit(e)} />
      ))}
    </div>
  );
}

function ServiceRow({ e, onEdit }: { e: ServiceEntry; onEdit: () => void }) {
  const qc = useQueryClient();
  return (
    <div className="rounded-xl bg-white/5 p-3">
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="font-medium">{e.title}</p>
          <p className="text-xs text-muted-foreground">
            {new Date(`${e.date}T12:00:00`).toLocaleDateString("ro-RO", { day: "2-digit", month: "short", year: "numeric" })}
            {e.odo !== null ? ` · ${e.odo.toLocaleString("ro-RO")} km` : ""}
            {e.cost !== null ? ` · ${lei(e.cost)}` : ""}
          </p>
          {e.notes && <p className="mt-1 text-sm">{e.notes}</p>}
        </div>
        <button type="button" title="Modifică" onClick={onEdit} className="rounded-lg p-1.5 text-sky-400 hover:bg-sky-500/10">
          <Pencil className="h-4 w-4" />
        </button>
        <button
          type="button"
          title="Șterge"
          onClick={async () => {
            if (!confirm(`Ștergi „${e.title}” cu tot cu fișiere?`)) return;
            await api.deleteService(e.id);
            await qc.invalidateQueries();
          }}
          className="rounded-lg p-1.5 text-red-400 hover:bg-red-500/10"
        >
          <Trash2 className="h-4 w-4" />
        </button>
      </div>
      {e.files.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-2">
          {e.files.map((f) =>
            f.mime.startsWith("image/") ? (
              <a key={f.id} href={`/api/service/files/${f.id}`} target="_blank" rel="noreferrer">
                <img src={`/api/service/files/${f.id}`} alt={f.name} className="h-16 w-16 rounded-lg object-cover" />
              </a>
            ) : (
              <a
                key={f.id}
                href={`/api/service/files/${f.id}`}
                target="_blank"
                rel="noreferrer"
                className="flex items-center gap-1 rounded-lg bg-white/5 px-2 py-1 text-xs"
              >
                <Paperclip className="h-3 w-3" /> {f.name}
              </a>
            ),
          )}
        </div>
      )}
    </div>
  );
}

function ServiceForm({ entry, onClose }: { entry: Partial<ServiceEntry>; onClose: () => void }) {
  const qc = useQueryClient();
  const [title, setTitle] = useState(entry.title ?? "");
  const [date, setDate] = useState(entry.date ?? today());
  const [odo, setOdo] = useState(entry.odo != null ? String(entry.odo) : "");
  const [cost, setCost] = useState(entry.cost != null ? String(entry.cost) : "");
  const [notes, setNotes] = useState(entry.notes ?? "");
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const pick = useRef<HTMLInputElement>(null);

  async function save() {
    setBusy(true);
    try {
      const { id } = await api.saveService({
        id: entry.id,
        title,
        date,
        odo: odo.trim() ? Number(odo) : null,
        cost: cost.trim() ? Number(cost.replace(",", ".")) : null,
        notes: notes.trim() || null,
      });
      for (const f of files) await api.uploadServiceFile(id, f);
      await qc.invalidateQueries();
      toast.success("Lucrare salvată");
      onClose();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const input = "w-full rounded-lg border border-border/40 bg-background/40 px-3 py-2 text-sm";
  return (
    <div className="space-y-2 rounded-xl border border-sky-500/30 bg-sky-500/5 p-3">
      <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Ce s-a făcut (ex. Schimb ulei și filtre)" className={input} />
      <div className="grid grid-cols-3 gap-2">
        <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className={input} />
        <input value={odo} onChange={(e) => setOdo(e.target.value)} inputMode="numeric" placeholder="km (gol = acum)" className={input} />
        <input value={cost} onChange={(e) => setCost(e.target.value)} inputMode="decimal" placeholder="cost, lei" className={input} />
      </div>
      <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} placeholder="Note (service, piese, garanție...)" className={input} />
      {entry.files && entry.files.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {entry.files.map((f) => (
            <span key={f.id} className="flex items-center gap-1 rounded-lg bg-white/5 px-2 py-1 text-xs">
              {f.name}
              <button
                type="button"
                title="Șterge fișierul"
                onClick={async () => {
                  await api.deleteServiceFile(f.id);
                  await qc.invalidateQueries({ queryKey: ["service"] });
                  onClose();
                }}
              >
                <X className="h-3 w-3 text-red-400" />
              </button>
            </span>
          ))}
        </div>
      )}
      <input
        ref={pick}
        type="file"
        accept="image/*,application/pdf"
        multiple
        hidden
        onChange={(e) => setFiles([...files, ...Array.from(e.target.files ?? [])])}
      />
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" onClick={() => pick.current?.click()} className="flex items-center gap-1 rounded-lg bg-white/10 px-3 py-1.5 text-sm">
          <Paperclip className="h-4 w-4" /> Atașează bon / poză
        </button>
        {files.map((f) => (
          <span key={f.name} className="text-xs text-muted-foreground">
            {f.name}
          </span>
        ))}
      </div>
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onClose} className="rounded-lg px-3 py-1.5 text-sm text-muted-foreground">
          Renunță
        </button>
        <button
          type="button"
          disabled={busy || !title.trim() || !date}
          onClick={save}
          className="rounded-lg bg-sky-500 px-3 py-1.5 text-sm font-semibold text-slate-950 disabled:opacity-40"
        >
          Salvează
        </button>
      </div>
    </div>
  );
}
