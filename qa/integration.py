"""Independent loopback-only black-box acceptance. Run with live backend on :8080."""
import asyncio, json, time, os, urllib.request, urllib.error
from urllib.parse import urlsplit
from pathlib import Path
import websockets
from raw_ws import raw_socket
BASE=os.environ.get('BRIDGE_BASE_URL','http://127.0.0.1:8080').rstrip('/')
_base=urlsplit(BASE)
if _base.scheme!='http' or _base.hostname not in ('127.0.0.1','localhost','::1'):
    raise ValueError('Integration tests require an HTTP loopback base')
WS_BASE='ws://'+_base.netloc
results=[]
def api(method,path,token=None,data=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path, data=json.dumps(data).encode() if data is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=5) as r: return r.status,json.load(r)
    except urllib.error.HTTPError as e: return e.code,json.load(e)
def ok(name,condition,detail=''):
    results.append({'case':name,'status':'PASS' if condition else 'FAIL','detail':detail})
    print(results[-1],flush=True)
    assert condition, name+': '+str(detail)
_session_names={}
def session(n):
    # Scenario labels may be long; generated fixture names satisfy the 5-grapheme API limit.
    name=_session_names.setdefault(n, 'Q'+str(len(_session_names)))
    s,b=api('POST','/api/sessions',data={'displayName':name}); assert s in (200,201),(s,b); return b
class Client:
    def __init__(self,u,r,heartbeat=True): self.u=u;self.r=r;self.heartbeat=heartbeat;self.snaps=[];self.errors=[]
    async def connect(self):
        self.ws=await websockets.connect(WS_BASE+'/ws')
        await self.ws.send(json.dumps({'type':'subscribe','token':self.u['token'],'roomId':self.r}))
        self.reader=asyncio.create_task(self.read()); self.pinger=asyncio.create_task(self.ping()) if self.heartbeat else None
        return self
    async def read(self):
        try:
            async for raw in self.ws:
                b=json.loads(raw)
                if b['type']=='snapshot': self.snaps.append(b['room'])
                elif b['type']=='error': self.errors.append(b)
        except websockets.ConnectionClosed: pass
    async def ping(self):
        try:
            while True:
                await self.ws.send('{"type":"ping"}'); await asyncio.sleep(1)
        except websockets.ConnectionClosed: pass
    async def close(self):
        if self.pinger:self.pinger.cancel()
        await self.ws.close(); await self.reader
def public_snapshot(s):
    # v0.2 adds authenticated viewer fields; compare only the shared public projection.
    out={k:v for k,v in s.items() if k not in ('you','canStart')}
    if out.get('game'):
        out['game']={**out['game'],'privilege':{k:v for k,v in out['game']['privilege'].items() if k!='yourChoice'}}
    return out

async def main():
    users=[session(str(i)) for i in range(12)]
    for token in (None,'not-a-token'):
        ok('authentication '+str(token),api('GET','/api/rooms',token)[0]==401)
    s,room=api('POST','/api/rooms',users[0]['token'],{});assert s in (200,201),(s,room)
    rid=room['roomId'];path='/api/rooms/'+rid
    clients=[]
    clients.append(await Client(users[0],rid).connect())
    for u in users[1:4]:
        assert api('POST',path+'/join',u['token'],{})[0]==200
        clients.append(await Client(u,rid).connect())
    await asyncio.sleep(.3)
    ok('four distinct players and seats',len({p['playerId'] for p in clients[0].snaps[-1]['players']})==4 and {p['seat'] for p in clients[0].snaps[-1]['players']}=={0,1,2,3})
    ok('four websocket views consistent',all(public_snapshot(c.snaps[-1])==public_snapshot(clients[0].snaps[-1]) for c in clients))
    rev=api('GET',path,users[0]['token'])[1]['revision']
    repeat=api('POST',path+'/join',users[0]['token'],{})
    ok('duplicate join idempotent',repeat[0]==200 and repeat[1]['revision']==rev and len(repeat[1]['players'])==4)
    ok('fifth rejected',api('POST',path+'/join',users[4]['token'],{})[0]==409)
    for u in users[:4]: assert api('PUT',path+'/ready',u['token'],{'ready':True})[0]==200
    await asyncio.sleep(.3)
    ok('ready broadcasts and remains WAITING',all(public_snapshot(c.snaps[-1])==public_snapshot(clients[0].snaps[-1]) for c in clients) and clients[0].snaps[-1]['allReady'] and clients[0].snaps[-1]['phase']=='WAITING')
    for method,suffix,body in [('GET','',None),('PUT','/ready',{'ready':False})]:
        status,b=api(method,path+suffix,users[4]['token'],body)
        ok('outsider '+method+suffix,status in (403,404),str((status,b)))
    outsider_leave=api('POST',path+'/leave',users[4]['token'],{})
    ok('outsider leave cannot remove another member',outsider_leave[0] in (200,403,404) and len(api('GET',path,users[0]['token'])[1]['players'])==4)
    bad=await Client(users[4],rid).connect(); await asyncio.sleep(.2)
    ok('unauthorized subscription has no snapshot',not bad.snaps and bool(bad.errors)); await bad.close()
    _,roomB=api('POST','/api/rooms',users[4]['token'],{});ridB=roomB['roomId']; b=await Client(users[4],ridB).connect();await asyncio.sleep(.2)
    before=len(b.snaps);api('PUT',path+'/ready',users[0]['token'],{'ready':False});await asyncio.sleep(.2)
    ok('room isolation',len(b.snaps)==before and all(s['roomId']==ridB for s in b.snaps))
    ok('one room membership',api('POST','/api/rooms/'+ridB+'/join',users[0]['token'],{})[0]==409)
    # Extra same-token connection must preserve presence on one close.
    extra=await Client(users[1],rid).connect();await asyncio.sleep(.2);await clients[1].close();await asyncio.sleep(.2)
    p=next(p for p in api('GET',path,users[0]['token'])[1]['players'] if p['playerId']==users[1]['playerId'])
    ok('multi-tab last-socket policy',p['connected'] and p['ready'])
    outsider=session('replacement')
    oldseat=p['seat']
    start=time.monotonic();await extra.close();await asyncio.sleep(.2)
    snapshot=api('GET',path,users[0]['token'])[1]
    ok('last socket close releases seat immediately',len(snapshot['players'])==3 and not any(p['playerId']==users[1]['playerId'] for p in snapshot['players']) and time.monotonic()-start<1)
    status,replacement=api('POST',path+'/join',outsider['token'],{})
    replacement_client=await Client(outsider,rid).connect()
    ok('replacement can immediately occupy released seat',status==200 and next(p for p in replacement['players'] if p['playerId']==outsider['playerId'])['seat']==oldseat)
    status,body=api('POST',path+'/join',users[1]['token'],{})
    ok('returning player cannot reclaim occupied seat',status==409 and body['code']=='ROOM_FULL')
    denied_return=await Client(users[1],rid).connect();await asyncio.sleep(.2)
    ok('returning socket without fresh join cannot subscribe',bool(denied_return.errors) and not denied_return.snaps)
    api('POST',path+'/leave',outsider['token'],{})
    status,rejoined=api('POST',path+'/join',users[1]['token'],{})
    ok('fresh rejoin after vacancy resets ready',status==200 and not next(p for p in rejoined['players'] if p['playerId']==users[1]['playerId'])['ready'])
    resumed=await Client(users[1],rid).connect();await asyncio.sleep(.2)
    ok('fresh joined player receives current room snapshot',bool(resumed.snaps) and len(resumed.snaps[-1]['players'])==4)
    start=time.monotonic();assert api('POST',path+'/leave',users[2]['token'],{})[0]==200
    ok('manual leave immediate',len(api('GET',path,users[0]['token'])[1]['players'])==3 and time.monotonic()-start<1)
    # Atomic competing joins: room B has one member; eight simultaneous contenders for three seats.
    contenders=users[5:12]+[users[2]]
    joined=await asyncio.gather(*(asyncio.to_thread(api,'POST','/api/rooms/'+ridB+'/join',u['token'],{}) for u in contenders))
    ok('capacity race admits exactly three remaining seats',sum(s==200 for s,_ in joined)==3 and sum(s==409 for s,_ in joined)==5,str([s for s,_ in joined]))
    # Silent socket removal follows heartbeat detection, with no extra waiting-room grace.
    api('POST',path+'/leave',users[3]['token'],{})
    api('POST',path+'/join',users[3]['token'],{})
    api('PUT',path+'/ready',users[3]['token'],{'ready':True})
    silent=raw_socket(users[3]['token'],rid)
    await asyncio.sleep(9)
    snap=api('GET',path,users[0]['token'])[1]
    ok('silent heartbeat failure releases seat without extra grace',not any(p['playerId']==users[3]['playerId'] for p in snap['players']))
    status,rejoined=api('POST',path+'/join',users[3]['token'],{})
    silent_resumed=await Client(users[3],rid).connect();await asyncio.sleep(.2)
    ok('silent loss fresh join resets ready',status==200 and not next(p for p in rejoined['players'] if p['playerId']==users[3]['playerId'])['ready'])
    initial=session('initial-delay')
    _,initial_room=api('POST','/api/rooms',initial['token'],{})
    await asyncio.sleep(6)
    ok('unsubscribed provisional join cannot strand seat',api('GET','/api/me',initial['token'])[1]['roomId'] is None)
    silent.close()
    for c in clients+[b,resumed,silent_resumed,replacement_client,denied_return]: await c.close()
if __name__=='__main__':
    try:asyncio.run(main())
    finally:Path(__file__).with_name('integration-results.json').write_text(json.dumps(results,indent=2)+'\n')
