const { DatabaseSync } = require("node:sqlite");
const db = new DatabaseSync(process.argv[2], { readOnly: true });
const ids = process.argv.slice(4).map((x) => parseInt(x, 16));
const pts = db.prepare(`SELECT device_at,rpm,can_speed,volt,temp,map,iat,ect,obd_load FROM trip_point WHERE device_at>='2026-11-20' ORDER BY device_at`).all().map(p=>({...p,t:Date.parse(p.device_at)}));
const near = (t) => { let b=null; for (const p of pts) { const dt=Math.abs(p.t-t); if (dt<=15000&&(!b||dt<b.dt)) b={...p,dt}; } return b; };
for (const id of ids) {
  console.log("== " + id.toString(16).toUpperCase());
  for (const s of db.prepare(`SELECT at,value FROM obd_sample WHERE tx=? AND svc='22' AND id=? ORDER BY at`).all(process.argv[3], id)) {
    const b = s.value.match(/../g).map(x=>parseInt(x,16)); const u16 = b.length>=2?(b[0]<<8)|b[1]:null;
    const p = near(Date.parse(s.at));
    console.log(s.at.slice(5,19), s.value.padEnd(10), "u16", u16, "|", p ? `rpm ${p.rpm} v ${p.can_speed} ect ${p.ect} iat ${p.iat} map ${p.map} load ${p.obd_load} out ${p.temp} V ${p.volt}` : "-");
  }
}
