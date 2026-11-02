"""Independent v0.7 beta network acceptance. Starts ONLY its own loopback server.
Usage: python3 qa/beta-network.py --jar backend/target/bridgego-server.jar --java /path/java
No cloud deployment, browser emulation or external accounts. Requires websockets.
"""
import argparse, asyncio, hashlib, http.client, json, os, signal, subprocess, time, uuid
from pathlib import Path
import websockets
P=argparse.ArgumentParser();P.add_argument('--jar',required=True);P.add_argument('--java',default='java');P.add_argument('--port',type=int,default=18700);P.add_argument('--output',default=str(Path(__file__).with_name('beta-network-results.json')));A=P.parse_args()
RESULTS=[];PROC=None;LOG=None
ORIGIN='https://bridgego-beta.example';BASE=f'127.0.0.1:{A.port}'
def check(name,ok,detail=None):
    row={'case':name,'status':'PASS' if ok else 'FAIL'}
    if detail is not None:row['detail']=detail
    RESULTS.append(row);print(json.dumps(row),flush=True);assert ok,name

def request(method,path,token=None,data=None,chunked=False,origin=None):
    c=http.client.HTTPConnection('127.0.0.1',A.port,timeout=5)
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if origin:headers['Origin']=origin
    body=json.dumps(data).encode() if data is not None else None
    if chunked:body=iter([body[:100],body[100:]])
    c.request(method,path,body,headers,encode_chunked=chunked);r=c.getresponse();raw=r.read();h=dict((k.lower(),v) for k,v in r.getheaders());status=r.status;c.close()
    try:b=json.loads(raw)
    except Exception:b=raw.decode(errors='replace')
    return status,b,h

def start():
    global PROC,LOG
    LOG=open('/tmp/bridgego-beta-independent.log','a')
    PROC=subprocess.Popen([A.java,'-Xms64m','-Xmx320m','-XX:MaxMetaspaceSize=128m','-jar',str(Path(A.jar).resolve()),'--spring.profiles.active=prod',f'--server.port={A.port}',f'--bridge.public-origin={ORIGIN}'],env={**os.environ,'PUBLIC_ORIGIN':ORIGIN},stdout=LOG,stderr=subprocess.STDOUT)
    end=time.monotonic()+45
    while time.monotonic()<end:
        if PROC.poll() is not None:raise RuntimeError('server exited; inspect /tmp/bridgego-beta-independent.log')
        try:
            s,b,h=request('GET','/health/ready')
            if s==200:return b['instanceEpoch']
        except OSError:pass
        time.sleep(.15)
    raise RuntimeError('startup timeout')

def stop(hard=False):
    global PROC,LOG
    if PROC and PROC.poll() is None:
        PROC.send_signal(signal.SIGKILL if hard else signal.SIGTERM)
        PROC.wait(timeout=20)
    if LOG:LOG.close()

class Client:
    def __init__(self,u,r):self.u=u;self.r=r;self.messages=[];self.code=None
    async def connect(self):
        self.ws=await websockets.connect(f'ws://{BASE}/ws',origin=ORIGIN,ping_interval=None)
        await self.ws.send(json.dumps({'type':'subscribe','token':self.u['token'],'roomId':self.r}))
        self.reader=asyncio.create_task(self.read());return self
    async def read(self):
        try:
            async for raw in self.ws:self.messages.append(json.loads(raw))
        except websockets.ConnectionClosed as e:self.code=e.code
        finally:self.code=self.ws.close_code
    async def close(self):await self.ws.close();await self.reader

def session(i):
    s,b,h=request('POST','/api/sessions',data={'displayName':'Q'+str(i)});assert s in(200,201),(s,b);return b

def snapshot(r,u):
    s,b,h=request('GET','/api/rooms/'+r,u['token']);assert s==200,(s,b);return b

def action(r,u,kind,data=None):
    b=snapshot(r,u);path='/api/rooms/'+r
    path+= '/start' if kind=='start' else '/games/'+b['game']['gameId']+'/deals/'+b['game']['dealId']+'/'+kind
    payload={'actionId':str(uuid.uuid4()),'expectedRevision':b['revision'],**(data or {})}
    return request('PUT' if kind=='privilege' else 'POST',path,u['token'],payload),path,payload

async def main():
    epoch=start();clients=[]
    try:
        s,b,h=request('GET','/health/ready')
        check('health minimal boot epoch, no-store',s==200 and set(b)=={'status','instanceEpoch','version'} and b['status']=='ready' and h.get('cache-control')=='no-store' and h.get('x-bridge-instance-epoch')==epoch)
        s,b,h=request('GET','/api/rooms')
        check('unauthorized API JSON no-store same epoch',s==401 and isinstance(b,dict) and h.get('cache-control')=='no-store' and h.get('x-bridge-instance-epoch')==epoch)
        for path in ['/api/missing','/missing.js','/assets/missing.js','/sw-missing.js']:
            s,b,h=request('GET',path);check('no shell fallback '+path,s==404 and not(isinstance(b,str) and '<div id="root"' in b))
        s,b,h=request('GET','/');check('embedded production shell served',s==200 and isinstance(b,str) and '<div id="root"' in b)
        for path in ['/sw.js','/manifest.webmanifest','/icons/icon-192.png','/icons/icon-512.png']:
            s,b,h=request('GET',path);check('public PWA asset '+path,s==200 and (path!='/manifest.webmanifest' or 'json' in h.get('content-type','')))
        for chunked in (False,True):
            s,b,h=request('POST','/api/sessions',data={'displayName':'a'*20000},chunked=chunked)
            check('bounded oversized '+('chunked' if chunked else 'fixed')+' body returns JSON',s in(400,413) and isinstance(b,dict) and h.get('cache-control')=='no-store',{'status':s,'code':b.get('code') if isinstance(b,dict) else None})
        for bad_origin in ['https://other.example',ORIGIN+'.evil.example','http://bridgego-beta.example']:
            denied=False
            try:
                ws=await websockets.connect(f'ws://{BASE}/ws',origin=bad_origin)
                await ws.close()
            except Exception as e:
                denied='403' in str(e)
            check('production WebSocket origin rejected '+bad_origin,denied)
        users=[session(i) for i in range(10)]
        # Concurrent create attempts must reserve exactly two occupied rooms.
        rs=await asyncio.gather(*(asyncio.to_thread(request,'POST','/api/rooms',u['token'],{}) for u in users[:3]))
        check('concurrent room creation admits exactly two',sum(s in(200,201) for s,b,h in rs)==2 and sum(s==409 and b.get('code')=='ROOM_LIMIT_REACHED' for s,b,h in rs)==1)
        rooms=[];seats=[];available=users[3:]
        for i,(s,b,h) in enumerate(rs):
            if s not in(200,201):continue
            r=b['roomId'];us=[users[i]]+[available.pop(0) for _ in range(3)];rooms.append(r);seats.append(us)
            for j,u in enumerate(us):
                if j:assert request('POST','/api/rooms/'+r+'/join',u['token'],{})[0]==200
                clients.append(await Client(u,r).connect())
        await asyncio.sleep(.4)
        check('two rooms have eight distinct connected seats',all(len(snapshot(r,us[0])['players'])==4 and all(p['connected'] for p in snapshot(r,us[0])['players']) for r,us in zip(rooms,seats)))
        outsider=available[0]
        s,b,h=request('POST','/api/rooms',outsider['token'],{});check('third room rejected while two waiting rooms occupied',s==409 and b['code']=='ROOM_LIMIT_REACHED')
        for r,us in zip(rooms,seats):
            for u in us:assert request('PUT','/api/rooms/'+r+'/ready',u['token'],{'ready':True})[0]==200
            assert action(r,us[0],'start')[0][0]==200
            for i in(0,1):assert action(r,us[i],'privilege',{'use':True})[0][0]==200
        await asyncio.sleep(.3)
        hands=[]
        for r,us in zip(rooms,seats):
            views=[snapshot(r,u) for u in us]
            hs=[v['you']['ownHand'] for v in views]
            check('private hands remain viewer-specific '+str(rooms.index(r)),all(len(x)==13 for x in hs) and len({(c['suit'],c['rank']) for hand in hs for c in hand})==52)
            check('private room response no-store '+str(rooms.index(r)),request('GET','/api/rooms/'+r,us[0]['token'])[2].get('cache-control')=='no-store')
        s,b,h=request('GET','/api/rooms',outsider['token']);check('lobby does not leak ownHand or legalCards',s==200 and 'ownHand' not in json.dumps(b) and 'legalCards' not in json.dumps(b))
        status=Path(f'/proc/{PROC.pid}/status').read_text();rss=next(x for x in status.splitlines() if x.startswith('VmRSS:'))
        check('eight native-heartbeat WebSockets alive',all(c.ws.close_code is None and any(m.get('type')=='snapshot' for m in c.messages) for c in clients),{'rss':rss,'note':'local process observation; not Render capacity guarantee'})
        old=action(rooms[0],seats[0][0],'calls',{'type':'BID','level':1,'strain':'NO_TRUMP'})
        PROC.send_signal(signal.SIGTERM)
        await asyncio.gather(*(asyncio.wait_for(c.reader,20) for c in clients))
        PROC.wait(timeout=20)
        check('graceful stop sends maintenance before 1012 to eight clients',all(c.code==1012 and any(m.get('type')=='maintenance' and m.get('instanceEpoch')==epoch for m in c.messages) for c in clients),[c.code for c in clients])
        stop();epoch2=start();check('restart changes boot epoch',epoch2!=epoch)
        s,b,h=request('POST',old[1],seats[0][0]['token'],old[2]);check('old action cannot mutate replacement instance',s==401 and h.get('x-bridge-instance-epoch')==epoch2)
        u=session(99);s,b,h=request('POST','/api/rooms',u['token'],{});c=await Client(u,b['roomId']).connect();await asyncio.sleep(.2)
        PROC.kill();await asyncio.wait_for(c.reader,10);PROC.wait();check('hard kill has abnormal close, no fabricated maintenance',c.code==1006 and not any(m.get('type')=='maintenance' for m in c.messages))
        stop();epoch3=start();check('hard-restart epoch is independently confirmed by health',epoch3 not in(epoch,epoch2))
    finally:
        for c in clients:
            if c.ws.close_code is None:await c.close()
        stop()

if __name__=='__main__':
    try:asyncio.run(main())
    finally:
        stop();Path(A.output).write_text(json.dumps({'jarSha256':hashlib.sha256(Path(A.jar).read_bytes()).hexdigest(),'results':RESULTS},indent=2)+'\n')
