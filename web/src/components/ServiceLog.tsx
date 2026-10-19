import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, FileText, Gauge, MapPin, Paperclip, Pencil, Plus, Trash2, Wrench, X } from "lucide-react";
import { useRef, useState } from "react";
import { toast } from "sonner";

import { api, lei, num, type ServiceEntry, type ServiceItem } from "../api";

const today = () => new Date().toLocaleDateString("en-CA"); // AAAA-LL-ZZ, ora locală

/**
 * Jurnalul de service: ce s-a făcut la mașină (ulei, piese, ITP...), când, la ce kilometraj și
 * cu cât, plus bonurile și facturile (poze sau PDF). Costurile intră în raportul lunii.
 */
export function ServiceLog() {
  const { data } = useQuery({ queryKey: ["service"], queryFn: api.service });
  const [edit, setEdit] = useState<Partial<ServiceEntry> | null>(null);
  // Prima lucrare (cea mai nouă) e deschisă; celelalte pe scurt până le atingi.
  const [open, setOpen] = useState<Record<number, boolean>>({});
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
      {(data ?? []).map((e, i) => {
        const isOpen = open[e.id] ?? i === 0;
        return <ServiceRow key={e.id} e={e} open={isOpen} onToggle={() => setOpen({ ...open, [e.id]: !isOpen })} onEdit={() => setEdit(e)} />;
      })}
    </div>
  );
}

/** Rândurile pe grupe, în ordinea în care apar. */
function grouped(items: ServiceItem[]): Array<[string, ServiceItem[]]> {
  const m = new Map<string, ServiceItem[]>();
  for (const it of items) m.set(it.group, [...(m.get(it.group) ?? []), it]);
  return [...m];
}

const sum = (items: ServiceItem[]) => items.reduce((a, it) => a + (it.price ?? 0), 0);

function ServiceRow({ e, open, onToggle, onEdit }: { e: ServiceEntry; open: boolean; onToggle: () => void; onEdit: () => void }) {
  const qc = useQueryClient();
  const d = new Date(`${e.date}T12:00:00`);
  const groups = grouped(e.items);
  return (
    <div className="overflow-hidden rounded-xl bg-white/5">
      <button type="button" onClick={onToggle} className="flex w-full items-center gap-3 p-3 text-left">
        <div className="flex w-12 shrink-0 flex-col items-center rounded-lg bg-sky-500/10 py-1 text-sky-300">
          <span className="text-lg font-bold leading-none">{d.getDate()}</span>
          <span className="text-[10px] uppercase">{d.toLocaleDateString("ro-RO", { month: "short" }).replace(".", "")}</span>
          <span className="text-[10px] text-muted-foreground">{d.getFullYear()}</span>
        </div>
        <div className="min-w-0 flex-1">
          <p className="font-medium leading-snug">{e.title}</p>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
            {e.odo !== null && (
              <span className="flex items-center gap-1">
                <Gauge className="h-3 w-3" /> {e.odo.toLocaleString("ro-RO")} km
              </span>
            )}
            {e.shop && (
              <span className="flex items-center gap-1">
                <MapPin className="h-3 w-3" /> {e.shop}
              </span>
            )}
            {e.files.length > 0 && (
              <span className="flex items-center gap-1">
                <Paperclip className="h-3 w-3" /> {e.files.length}
              </span>
            )}
          </p>
        </div>
        {e.cost !== null && <span className="shrink-0 text-base font-semibold">{lei(e.cost)}</span>}
        <ChevronDown className={`h-4 w-4 shrink-0 text-muted-foreground transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <div className="space-y-3 border-t border-white/5 p-3">
          {groups.map(([g, list]) => (
            <div key={g}>
              <div className="mb-1 flex items-baseline justify-between text-xs font-semibold uppercase tracking-wide text-sky-300">
                <span>{g}</span>
                {list.some((it) => it.price !== null) && <span className="text-muted-foreground">{lei(sum(list))}</span>}
              </div>
              <div className="divide-y divide-white/5 rounded-lg bg-black/10">
                {list.map((it, i) => (
                  <div key={i} className="flex items-start gap-2 px-2.5 py-1.5 text-sm">
                    <div className="min-w-0 flex-1">
                      <p className="leading-snug">
                        {it.name}
                        {it.qty !== null && it.qty !== 1 && <span className="text-muted-foreground"> × {num(it.qty, 2)}</span>}
                      </p>
                      {it.code && <p className="font-mono text-[11px] text-muted-foreground">{it.code}</p>}
                    </div>
                    {it.price !== null && (
                      <span className={`shrink-0 tabular-nums ${it.price < 0 ? "text-emerald-400" : ""}`}>{lei(it.price)}</span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
          {e.items.length > 0 && e.cost !== null && (
            <div className="flex items-baseline justify-between border-t border-white/10 pt-2 font-semibold">
              <span>Total</span>
              <span>{lei(e.cost)}</span>
            </div>
          )}
          {e.notes && <p className="whitespace-pre-line rounded-lg bg-white/5 p-2.5 text-sm text-muted-foreground">{e.notes}</p>}
          {e.files.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {e.files.map((f) =>
                f.mime.startsWith("image/") ? (
                  <a key={f.id} href={`/api/service/files/${f.id}`} target="_blank" rel="noreferrer" title={f.name}>
                    <img src={`/api/service/files/${f.id}`} alt={f.name} className="h-24 w-24 rounded-lg object-cover ring-1 ring-white/10" />
                  </a>
                ) : (
                  <a
                    key={f.id}
                    href={`/api/service/files/${f.id}`}
                    target="_blank"
                    rel="noreferrer"
                    className="flex h-24 w-32 flex-col items-center justify-center gap-1 rounded-lg bg-white/5 p-2 text-center text-xs ring-1 ring-white/10 hover:bg-white/10"
                  >
                    <FileText className="h-6 w-6 text-red-300" />
                    <span className="line-clamp-2 break-all">{f.name}</span>
                  </a>
                ),
              )}
            </div>
          )}
          <div className="flex justify-end gap-1">
            <button type="button" onClick={onEdit} className="flex items-center gap-1 rounded-lg px-2 py-1 text-sm text-sky-400 hover:bg-sky-500/10">
              <Pencil className="h-3.5 w-3.5" /> Modifică
            </button>
            <button
              type="button"
              onClick={async () => {
                if (!confirm(`Ștergi „${e.title}” cu tot cu fișiere?`)) return;
                await api.deleteService(e.id);
                await qc.invalidateQueries();
              }}
              className="flex items-center gap-1 rounded-lg px-2 py-1 text-sm text-red-400 hover:bg-red-500/10"
            >
              <Trash2 className="h-3.5 w-3.5" /> Șterge
            </button>
          </div>
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
  const [shop, setShop] = useState(entry.shop ?? "");
  const [items, setItems] = useState<ItemDraft[]>((entry.items ?? []).map(draft));
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
        shop: shop.trim() || null,
        items: items.map(fromDraft).filter((it) => it.name),
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
      <input value={shop} onChange={(e) => setShop(e.target.value)} placeholder="Unde (service, adresă)" className={input} />
      <ItemsEditor items={items} setItems={setItems} onTotal={(t) => setCost(String(t))} />
      <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} placeholder="Note (garanție, rodaj, de verificat...)" className={input} />
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

interface ItemDraft {
  group: string;
  name: string;
  code: string;
  qty: string;
  price: string;
}

const draft = (it: ServiceItem): ItemDraft => ({
  group: it.group,
  name: it.name,
  code: it.code ?? "",
  qty: it.qty !== null ? String(it.qty) : "",
  price: it.price !== null ? String(it.price) : "",
});

const toNum = (s: string) => (s.trim() && Number.isFinite(Number(s.replace(",", "."))) ? Number(s.replace(",", ".")) : null);

const fromDraft = (d: ItemDraft): ServiceItem => ({
  group: d.group.trim() || "Altele",
  name: d.name.trim(),
  code: d.code.trim() || null,
  qty: toNum(d.qty),
  price: toNum(d.price),
});

/** Articolele lucrării: piese (cu cod), manoperă, reduceri (preț negativ); prețul e pe tot rândul. */
function ItemsEditor({ items, setItems, onTotal }: { items: ItemDraft[]; setItems: (x: ItemDraft[]) => void; onTotal: (t: number) => void }) {
  const set = (i: number, k: keyof ItemDraft, v: string) => setItems(items.map((it, j) => (j === i ? { ...it, [k]: v } : it)));
  const total = Math.round(items.reduce((a, it) => a + (toNum(it.price) ?? 0), 0) * 100) / 100;
  const cell = "min-w-0 rounded-md border border-border/40 bg-background/40 px-2 py-1.5 text-sm";
  return (
    <div className="space-y-1.5 rounded-lg bg-black/10 p-2">
      <p className="text-xs font-semibold text-muted-foreground">Articole (piese, manoperă, reduceri cu minus)</p>
      <datalist id="service-groups">
        {["Revizie", "Frâne față", "Frâne spate", "Frâne", "Direcție și transmisie", "Suspensie", "Manoperă", "Reduceri", ...new Set(items.map((it) => it.group))].map((g) => (
          <option key={g} value={g} />
        ))}
      </datalist>
      {items.map((it, i) => (
        <div key={i} className="grid grid-cols-[6rem_1fr_6rem_3rem_5rem_auto] items-center gap-1 max-sm:grid-cols-[1fr_1fr_auto]">
          <input list="service-groups" value={it.group} onChange={(e) => set(i, "group", e.target.value)} placeholder="Grupa" className={cell} />
          <input value={it.name} onChange={(e) => set(i, "name", e.target.value)} placeholder="Piesa / lucrarea" className={`${cell} max-sm:col-span-2`} />
          <input value={it.code} onChange={(e) => set(i, "code", e.target.value)} placeholder="Cod" className={`${cell} font-mono`} />
          <input value={it.qty} onChange={(e) => set(i, "qty", e.target.value)} inputMode="decimal" placeholder="buc" className={cell} />
          <input value={it.price} onChange={(e) => set(i, "price", e.target.value)} inputMode="decimal" placeholder="lei" className={cell} />
          <button type="button" title="Scoate rândul" onClick={() => setItems(items.filter((_, j) => j !== i))} className="p-1">
            <X className="h-4 w-4 text-red-400" />
          </button>
        </div>
      ))}
      <div className="flex items-center justify-between gap-2">
        <button
          type="button"
          onClick={() => setItems([...items, { group: items.at(-1)?.group ?? "", name: "", code: "", qty: "", price: "" }])}
          className="flex items-center gap-1 rounded-lg px-2 py-1 text-sm text-sky-400 hover:bg-sky-500/10"
        >
          <Plus className="h-4 w-4" /> Rând
        </button>
        {items.length > 0 && (
          <button type="button" onClick={() => onTotal(total)} className="text-xs text-muted-foreground hover:text-foreground">
            Suma: {lei(total)} · pune la cost
          </button>
        )}
      </div>
    </div>
  );
}
