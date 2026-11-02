"""Owned localhost headed-browser complement: Java + 3 websockets16 native-Pong peers.
Browser user creates a room, prepares and starts via UI. After start this script only
observes snapshots/control Ping; it never bids, votes, plays, retries or reconnects.
Stops monitoring after ten completed all-TIMEOUT redeals or first transport failure.
Keeps Java running for final browser inspection until Ctrl-C, then cleans it up.
"""
import base64,hashlib,json,os,socket,struct,subprocess,threading,time,urllib.request,uuid,shutil
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"build-tools/python-qa"))
from websockets.sync.client import connect as ws_connect
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'qa/headed-autopass-results.json';WORK=Path('/tmp/bridgego-headed-qa');WORK.mkdir(exist_ok=True)
JAVA=ROOT.parent/'build-tools/jdk-17.0.21+9/bin/java';JAR=WORK/'bridgego-server.jar'
JAR.write_bytes((ROOT/'backend/target/bridgego-server.jar').read_bytes())
FE=WORK/'frontend'
if FE.exists():shutil.rmtree(FE)
shutil.copytree(ROOT/'frontend',FE,ignore=shutil.ignore_patterns('node_modules','dist','*.tsbuildinfo'))
config=FE/'vite.config.ts';config.write_text(config.read_text().replace('127.0.0.1:8080','127.0.0.1:18730'))
(FE/'node_modules').symlink_to(ROOT/'frontend/node_modules',target_is_directory=True)
sourceHash=hashlib.sha256(b''.join(p.relative_to(FE).as_posix().encode()+p.read_bytes() for p in sorted(FE.rglob('*')) if p.is_file() and 'node_modules' not in p.parts)).hexdigest()
sha=hashlib.sha256(JAR.read_bytes()).hexdigest();events=[];clients=[];done=threading.Event();latest=None;began=None;guard=threading.Lock()
def save(status):
 OUT.write_text(json.dumps({'status':status,'jarSha256':sha,'frontendSourceSha256':sourceHash,'route':'headed Chromium via Vite5173 -> Java18730; three native-Pong direct Java peers','elapsedSeconds':round(time.monotonic()-began,2) if began else None,'events':events},indent=2)+'\n')
def event(kind,**kw):
 events.append({'elapsed':round(time.monotonic()-began,2) if began else None,'event':kind,**kw});print(events[-1],flush=True);save('running')
def api(method,path,token=None,data=None):
 h={'Content-Type':'application/json'}
 if token:h['Authorization']='Bearer '+token
 with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18730'+path,headers=h,data=json.dumps(data).encode() if data is not None else None,method=method),timeout=5) as r:return json.load(r)
class Peer:
 def __init__(self,u,r):
  self.pongs=None;self.maxbytes=0;self.closed=False;self.last=-1
  self.ws=ws_connect('ws://127.0.0.1:18730/ws',origin='http://127.0.0.1:5173',ping_interval=None,max_size=262144,max_queue=16)
  self.ws.send(json.dumps({'type':'subscribe','token':u['token'],'roomId':r}))
  threading.Thread(target=self.read,daemon=True).start()
 def read(self):
  global latest,began
  try:
   while not self.closed:
    payload=self.ws.recv()
    self.maxbytes=max(self.maxbytes,len(payload));m=json.loads(payload)
    if m.get('type')!='snapshot':continue
    if done.is_set():continue
    r=m['room'];g=r.get('game')
    with guard:
     if done.is_set():continue
     latest=r
     if not g:continue
     if began is None:began=time.monotonic();event('game-started',phase=r['phase'])
     count=g['redealCount']
     if count!=self.last:
      self.last=count
      if self is clients[0] if clients else False:
       event('redeal',count=count,phase=r['phase'],connected=[p['connected'] for p in r['players']],pongs=[c.pongs for c in clients])
     if any(not p['connected'] for p in r['players']) or r['phase'] in('FORFEIT_PENDING','MATCH_RESULT','ABORTED'):
      event('unexpected-disconnect-or-terminal',phase=r['phase'],connected=[p['connected'] for p in r['players']]);done.set()
     if count>=10:
      rounds=g.get('auctionRounds',[]);finished=[a for a in rounds if a.get('status')=='ALL_PASS']
      event('ten-redeals-observed',count=count,phase=r['phase'],rounds=[{'redealCount':a['redealCount'],'status':a['status'],'calls':a['calls']} for a in rounds],maxSnapshotCharacters=[c.maxbytes for c in clients],connected=[p['connected'] for p in r['players']],pongs=[c.pongs for c in clients]);done.set()
  except Exception as e:
   if not self.closed:event('peer-error',error=type(e).__name__+': '+str(e));done.set()
 def close(self):
  self.closed=True
  self.ws.close()
with open(WORK/'server.log','w') as log, open(WORK/'vite.log','w') as viteLog:
 proc=subprocess.Popen([str(JAVA),'-Xms64m','-Xmx320m','-jar',str(JAR),'--server.port=18730','--server.address=127.0.0.1'],stdout=log,stderr=subprocess.STDOUT)
 vite=subprocess.Popen([str(ROOT.parent/'build-tools/node-qa'),str(FE/'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','5173','--strictPort'],cwd=FE,stdout=viteLog,stderr=subprocess.STDOUT)
 try:
  for _ in range(200):
   if proc.poll() is not None:raise RuntimeError('server startup failed')
   try:api('GET','/health/ready');break
   except OSError:time.sleep(.15)
  users=[api('POST','/api/sessions',data={'displayName':'Bot'+str(i)}) for i in range(3)]
  event('ready-for-headed-browser',jarSha256=sha)
  while True:
   rooms=api('GET','/api/rooms',users[0]['token'])
   if rooms:room=rooms[0]['roomId'];break
   time.sleep(.5)
  for u in users:
   api('POST','/api/rooms/'+room+'/join',u['token'],{});clients.append(Peer(u,room));api('PUT','/api/rooms/'+room+'/ready',u['token'],{'ready':True})
  event('three-peers-ready')
  done.wait(1300)
  if not done.is_set():event('observation-timeout')
  finished=[a for a in (latest or {}).get('game',{}).get('auctionRounds',[]) if a.get('status')=='ALL_PASS']
  success=bool(latest and len(finished)>=10 and all(len(a['calls'])==4 and all(c['source']=='TIMEOUT' and c['type']=='PASS' for c in a['calls']) for a in finished) and latest.get('game',{}).get('redealCount',0)>=10 and all(p['connected'] for p in latest['players']))
  save('PASS' if success else 'FAIL');print('MONITOR COMPLETE; Java remains for browser inspection. Ctrl-C cleans up.',flush=True)
  while True:time.sleep(60)
 except KeyboardInterrupt:pass
 finally:
  for c in clients:c.close()
  vite.terminate()
  try:vite.wait(timeout=10)
  except subprocess.TimeoutExpired:vite.kill();vite.wait()
  proc.terminate()
  try:proc.wait(timeout=15)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
