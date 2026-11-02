"""Local four-WebSocket all-Pass/redeal regression. No payload/token/hand logging."""
import asyncio,hashlib,http.client,json,os,signal,subprocess,time,uuid,argparse
from pathlib import Path
import websockets
P=argparse.ArgumentParser();P.add_argument('--jar',required=True);P.add_argument('--java',required=True);P.add_argument('--port',type=int,default=18721);P.add_argument('--cycles',type=int,default=10);P.add_argument('--mode',choices=['automatic','manual'],default='automatic');P.add_argument('--output',default='/tmp/bridgego-allpass/results.json');A=P.parse_args()
ORIGIN='https://bridgego-beta.example';started=time.monotonic();rows=[];proc=None
def emit(case,**fields):
 row={'elapsedSeconds':round(time.monotonic()-started,2),'case':case,**fields};rows.append(row);print(json.dumps(row),flush=True)
 Path(A.output).write_text(json.dumps({'mode':A.mode,'jarSha256':hashlib.sha256(Path(A.jar).read_bytes()).hexdigest(),'events':rows},indent=2))
def req(method,path,token=None,data=None):
 c=http.client.HTTPConnection('127.0.0.1',A.port,timeout=5);h={'Content-Type':'application/json'}
 if token:h['Authorization']='Bearer '+token
 c.request(method,path,json.dumps(data) if data is not None else None,h);r=c.getresponse();raw=r.read();c.close()
 b=json.loads(raw);assert r.status in (200,201),('HTTP failure',r.status,b.get('code'));return b
class Client:
 def __init__(self,u,r):self.u=u;self.r=r;self.latest=None;self.count=0;self.maxbytes=0;self.code=None;self.error=None
 async def connect(self):
  self.ws=await websockets.connect(f'ws://127.0.0.1:{A.port}/ws',origin=ORIGIN,ping_interval=None)
  await self.ws.send(json.dumps({'type':'subscribe','token':self.u['token'],'roomId':self.r}))
  self.task=asyncio.create_task(self.read());return self
 async def read(self):
  try:
   async for raw in self.ws:
    b=json.loads(raw)
    if b.get('type')=='snapshot':
     self.latest=b['room'];self.count+=1;self.maxbytes=max(self.maxbytes,len(raw))
    elif b.get('type')=='error':self.error=b.get('code','PROTOCOL_ERROR')
  except websockets.ConnectionClosed as e:self.code=e.code
  finally:self.code=self.ws.close_code
async def wait_for(fn,timeout=5):
 end=time.monotonic()+timeout
 while not fn():
  assert time.monotonic()<end,'Snapshot condition timeout'
  await asyncio.sleep(.02)
async def main():
 global proc
 log=open(str(Path(A.output).with_suffix('.server.log')),'w')
 proc=subprocess.Popen([A.java,'-Xms64m','-Xmx320m','-XX:MaxMetaspaceSize=128m','-jar',str(Path(A.jar).resolve()),'--spring.profiles.active=prod',f'--server.port={A.port}',f'--bridge.public-origin={ORIGIN}'],env={**os.environ,'PUBLIC_ORIGIN':ORIGIN},stdout=log,stderr=subprocess.STDOUT)
 clients=[]
 try:
  for _ in range(300):
   assert proc.poll() is None,'server exited'
   try:health=req('GET','/health/ready');break
   except OSError:await asyncio.sleep(.1)
  else:raise AssertionError('startup timeout')
  users=[req('POST','/api/sessions',data={'displayName':'S'+str(i)}) for i in range(4)]
  room=req('POST','/api/rooms',users[0]['token'],{})['roomId']
  for i,u in enumerate(users):
   if i:req('POST',f'/api/rooms/{room}/join',u['token'],{})
   clients.append(await Client(u,room).connect())
  await wait_for(lambda:all(c.latest is not None for c in clients))
  for u in users:req('PUT',f'/api/rooms/{room}/ready',u['token'],{'ready':True})
  s=req('GET',f'/api/rooms/{room}',users[0]['token'])
  req('POST',f'/api/rooms/{room}/start',users[0]['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':s['revision']})
  await wait_for(lambda:all(c.latest.get('game') for c in clients))
  initial=clients[0].latest['game'];gameId=initial['gameId'];dealIndex=initial['dealIndex'];seen={initial['dealId']};completed=0
  emit('START',status='RUNNING',targetRedeals=A.cycles,utc=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),epoch=health['instanceEpoch'])
  deadline=time.monotonic()+A.cycles*110+60
  previousHands=[{(x['suit'],x['rank']) for x in c.latest['you']['ownHand']} for c in clients]
  while completed<A.cycles:
   assert time.monotonic()<deadline,'soak exceeded expected timer progression'
   assert proc.poll() is None,'server exited'
   assert all(c.code is None and c.error is None for c in clients),'socket unexpectedly closed or rejected'
   if A.mode=='manual':
    s=req('GET',f'/api/rooms/{room}',users[0]['token']);g=s['game']
    if s['phase']=='PRIVILEGE_DECISION':
     for u in users:
      s=req('GET',f'/api/rooms/{room}',u['token']);g=s['game']
      if s['phase']=='PRIVILEGE_DECISION' and g['privilege']['canChoose']:
       req('PUT',f"/api/rooms/{room}/games/{gameId}/deals/{g['dealId']}/privilege",u['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':s['revision'],'use':False})
    elif s['phase']=='BIDDING':
     u=users[g['auction']['nextSeat']]
     req('POST',f"/api/rooms/{room}/games/{gameId}/deals/{g['dealId']}/calls",u['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':s['revision'],'type':'PASS'})
   g=clients[0].latest['game']
   assert clients[0].latest['phase'] not in ('MATCH_RESULT','ABORTED','FORFEIT_PENDING'),'unexpected terminal phase'
   if g['redealCount']>completed:
    completed=g['redealCount'];newid=g['dealId']
    await wait_for(lambda:all(c.latest['game']['dealId']==newid for c in clients))
    assert newid not in seen;seen.add(newid)
    hands=[]
    for c in clients:
     s=c.latest;g=s['game']
     assert g['gameId']==gameId and g['dealIndex']==dealIndex and g['completedDeals']==0
     assert s['phase']=='PRIVILEGE_DECISION' and not g['paused'] and g['matchResult'] is None
     assert len(s['players'])==4 and all(p['connected'] and p['reconnectDeadline'] is None for p in s['players'])
     assert g['operationTimer']['kind']=='PRIVILEGE' and 18500<g['operationTimer']['remainingMs']<=20000
     rounds=g['auctionRounds'];assert len(rounds)==completed+1
     assert all(x['status']=='ALL_PASS' and len(x['calls'])==4 and all(y['type']=='PASS' for y in x['calls']) for x in rounds[:-1])
     assert all(y['source']==('TIMEOUT' if A.mode=='automatic' else 'MANUAL') for x in rounds[:-1] for y in x['calls'])
     assert 'ownHand' not in json.dumps(g) and 'hands' not in g
     hand={(x['suit'],x['rank']) for x in s['you']['ownHand']};assert len(hand)==13;hands.append(hand)
    assert len(set.union(*hands))==52
    assert hands!=previousHands;previousHands=hands
    rss=next((x for x in Path(f'/proc/{proc.pid}/status').read_text().splitlines() if x.startswith('VmRSS:')),'unknown')
    emit('REDEAL',status='PASS',completedRedeals=completed,auctionRounds=completed+1,snapshots=[c.count for c in clients],maxSnapshotBytes=max(c.maxbytes for c in clients),rss=rss)
   await asyncio.sleep(.15 if A.mode=='manual' else .3)
  emit('COMPLETE',status='PASS',completedRedeals=completed,allFourSocketsConnected=all(c.code is None for c in clients),note='Controlled Linux client test; not proof of Edge background behavior.')
 except Exception as e:
  emit('FAILED',status='FAIL',errorType=type(e).__name__,message=str(e),closeCodes=[c.code for c in clients])
  raise
 finally:
  if proc and proc.poll() is None:
   proc.send_signal(signal.SIGTERM)
   await asyncio.to_thread(proc.wait,20)
  for c in clients:
   await c.ws.close();await c.task
  log.close()
asyncio.run(main())
