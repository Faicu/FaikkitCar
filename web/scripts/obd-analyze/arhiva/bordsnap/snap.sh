#!/bin/bash
# snap.sh <nume>: citește codurile bordului (fără identificare), 3 pe cerere, salvează <nume>.raw și <nume>.txt aici
DIR="$(cd "$(dirname "$0")" && pwd)"
cd /opt/faikkitcar
IDS=$(node -e "
const {DatabaseSync}=require('node:sqlite');const db=new DatabaseSync('data/faikkitcar.db',{readOnly:true});
const ids=db.prepare(\"select id from obd_code where tx='714' and svc='22' and value is not null and (id<0xF100 or id>0xF1FF) and id not in (0x1018,0x1019,0x101A,0x1016,0x1017,0xF15B) order by id\").all().map(r=>r.id.toString(16).toUpperCase().padStart(4,'0'));
const out=[];for(let i=0;i<ids.length;i+=3)out.push('22'+ids.slice(i,i+3).join(''));console.log(out.join(' '))")
timeout 200 runuser -u faikkitcar -- node $DIR/dbcmd.cjs "@obd ATSH714 ATCRA77E ATFCSH714 $IDS ATSH7E0 ATCRA7E8 ATFCSH7E0" 60 > $DIR/$1.raw 2>&1
python3 -I $DIR/parse.py "$1"
