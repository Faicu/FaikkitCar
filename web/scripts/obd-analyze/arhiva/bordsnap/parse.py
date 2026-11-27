import re,sys,json,os
D=os.path.dirname(os.path.abspath(__file__))
n=sys.argv[1]; lens=json.load(open(D+'/lens.json')); out={}
for l in open(f'{D}/{n}.raw'):
    if not l.startswith('22'): continue
    req=l.split()[0][2:]; ids=[req[i:i+4] for i in range(0,len(req),4)]
    segs=[s.strip() for s in l.split('->',1)[1].split('|')]
    segs=[s for s in segs if s and s!='7F2278']
    if segs and re.fullmatch(r'[0-9A-F]{3}',segs[0]):
        L=int(segs[0],16); h=''.join(s.split(':',1)[1] for s in segs[1:] if ':' in s)[:L*2]
    else: h=segs[0] if segs else ''
    if not h.startswith('62'): continue
    h=h[2:]
    while len(h)>=4:
        d=h[:4]
        if d not in lens: break
        k=lens[d]*2; out[d]=h[4:4+k]; h=h[4+k:]
with open(f'{D}/{n}.txt','w') as f:
    for d in sorted(out): f.write(f'{d} {out[d]}\n')
print(n,len(out),'coduri')
