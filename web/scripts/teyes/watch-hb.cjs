const {DatabaseSync}=require('node:sqlite');
const start=new Date().toISOString();const end=Date.now()+8*60e3;
let last='';
(function tick(){const db=new DatabaseSync('/opt/faikkitcar/data/faikkitcar.db',{readOnly:true});
const s=db.prepare('select received_at,data from car_state').get();const d=JSON.parse(s.data);
const k=s.received_at.slice(11,19)+' handbrake='+d.handbrake+' speed='+d.speed+' rpm='+d.rpm;
if(d.handbrake!==JSON.parse(last||'{"h":null}').h){console.log(k);}
last=JSON.stringify({h:d.handbrake});
const logs=db.prepare("select device_at,line from log where received_at>? and line like '%Frana%'").all(start);
for(const l of logs)console.log('LOG',l.device_at,l.line);
db.close();
if(logs.length>=2||Date.now()>end)process.exit(0);setTimeout(tick,5000)})();
