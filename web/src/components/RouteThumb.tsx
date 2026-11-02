/**
 * Miniatura unui traseu (lista de călătorii), ca în Panel: doar forma drumului, fără hartă,
 * plecarea verde și sosirea albastră; proporțiile reale (longitudinea × cos(latitudine)).
 */
export function RouteThumb({ shape }: { shape: Array<[number, number]> }) {
  const S = 64;
  const pad = 9;
  const k = Math.cos((shape[0][0] * Math.PI) / 180);
  const xs = shape.map(([, lon]) => lon * k);
  const ys = shape.map(([lat]) => lat);
  const minX = Math.min(...xs),
    maxX = Math.max(...xs),
    minY = Math.min(...ys),
    maxY = Math.max(...ys);
  // Un drum foarte scurt nu se umflă până la margini.
  const span = Math.max(maxX - minX, maxY - minY, 0.004);
  const s = (S - 2 * pad) / span;
  const cx = (minX + maxX) / 2,
    cy = (minY + maxY) / 2;
  const pts = shape.map((_, i) => [S / 2 + (xs[i] - cx) * s, S / 2 - (ys[i] - cy) * s] as const);
  const d = pts.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`).join("");
  const [x0, y0] = pts[0];
  const [x1, y1] = pts[pts.length - 1];
  return (
    <svg viewBox={`0 0 ${S} ${S}`} className="h-16 w-16 shrink-0 rounded-xl bg-white/[0.04]">
      <path
        d={d}
        fill="none"
        stroke="#38bdf8"
        strokeWidth="2.2"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
      <circle cx={x0} cy={y0} r="4.5" fill="#0f172a" />
      <circle cx={x0} cy={y0} r="3.2" fill="#22c55e" />
      <circle cx={x1} cy={y1} r="4.5" fill="#0f172a" />
      <circle cx={x1} cy={y1} r="3.2" fill="#38bdf8" />
    </svg>
  );
}
