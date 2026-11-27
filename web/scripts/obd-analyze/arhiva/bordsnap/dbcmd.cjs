// Pune o comandă direct în car_cmd (fără login) și așteaptă rezultatul. Rulat ca faikkitcar.
const {DatabaseSync}=require('node:sqlite');
const db=new DatabaseSync('/opt/faikkitcar/data/faikkitcar.db');
const cmd=process.argv[2], to=Number(process.argv[3]||60);
const id=db.prepare("insert into car_cmd (cmd,timeout_s,created_at) values (?,?,?)").run(cmd,to,new Date().toISOString()).lastInsertRowid;
console.error('Comanda #'+id);
const end=Date.now()+(150+to)*1000;
(function poll(){const r=db.prepare('select done_at,exit_code,output from car_cmd where id=?').get(id);
 if(r.done_at){process.stdout.write(r.output||'');process.exit(0)}
 if(Date.now()>end){console.error('expirat');process.exit(3)}setTimeout(poll,1000)})();
