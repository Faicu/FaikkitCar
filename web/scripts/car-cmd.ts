// Rulează o comandă shell pe navigație prin aplicația FaikkitCar (comutatorul „Comenzi de la
// distanță” trebuie pornit) și afișează rezultatul. Merge doar cât navigația e trează: mașina
// află de comandă la următoarea stare trimisă (5–60 s).
// Folosire: node --env-file=/opt/faikkitcar/.env scripts/car-cmd.ts [--timeout 30] '<comanda>' ['<comanda>'…]
// Mai multe comenzi rulează pe rând, cu o singură autentificare (serverul dă 10 pe 15 minute).

const args = process.argv.slice(2);
let timeoutS = 30;
if (args[0] === "--timeout") {
  timeoutS = Number(args[1]);
  args.splice(0, 2);
}
const cmds = args.filter((a) => a.trim());
if (!cmds.length) {
  console.error("Folosire: scripts/car-cmd.ts [--timeout 30] '<comanda>'");
  process.exit(2);
}
const base = `http://localhost:${process.env.PORT ?? 3001}`;

async function call<T>(method: string, path: string, token?: string, body?: unknown): Promise<T> {
  const res = await fetch(base + path, {
    method,
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${method} ${path}: HTTP ${res.status} ${await res.text()}`);
  return (await res.json()) as T;
}

const { token } = await call<{ token: string }>("POST", "/api/login", undefined, {
  user: process.env.ADMIN_USER,
  pass: process.env.ADMIN_PASS,
});
let worst = 0;
for (const cmd of cmds) {
  if (cmds.length > 1) console.error(`== ${cmd}`);
  const code = await runOne(cmd);
  if (code !== 0) worst = code;
  if (code === 3) break;
}
process.exit(worst);

async function runOne(cmd: string): Promise<number> {
  const { id } = await call<{ id: number }>("POST", "/api/cmd", token, { cmd, timeoutS });
  console.error(`Comanda #${id} pusă în coadă; aștept mașina…`);
  // Până la ~2 minute pentru preluare (starea vine la cel mult 60 s), plus durata comenzii.
  const deadline = Date.now() + (150 + timeoutS) * 1000;
  for (;;) {
    const r = await call<{ sentAt: string | null; doneAt: string | null; exitCode: number | null; output: string | null }>(
      "GET",
      `/api/cmd/${id}`,
      token,
    );
    if (r.doneAt) {
      process.stdout.write(r.output ?? "");
      console.error(`\n[cod ${r.exitCode}]`);
      return r.exitCode === 0 ? 0 : 1;
    }
    if (Date.now() > deadline) {
      console.error(r.sentAt ? "Mașina a luat comanda, dar n-a trimis rezultatul." : "Mașina n-a luat comanda (adormită sau comutatorul oprit?).");
      return 3;
    }
    await new Promise((ok) => setTimeout(ok, 3000));
  }
}
