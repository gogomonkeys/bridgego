"""Pause only the local backend process started by run-local.sh; queue a timely Pong."""
import os,signal,socket,struct,time,threading,json
from pathlib import Path
from integration import api,session
from raw_ws import raw_socket

def exact(s,n):
 out=b''
 while len(out)<n:
  b=s.recv(n-len(out))
  if not b:raise EOFError()
  out+=b
 return out

def frame(s):
 h=exact(s,2);op=h[0]&15;n=h[1]&127
 if n==126:n=struct.unpack('!H',exact(s,2))[0]
 elif n==127:n=struct.unpack('!Q',exact(s,8))[0]
 return op,exact(s,n)

def pong(s,payload):
 mask=os.urandom(4);s.sendall(bytes([0x8A,0x80|len(payload)])+mask+bytes(v^mask[i%4] for i,v in enumerate(payload)))

pid=int(os.environ['QA_SERVER_PID'])
u=session('pause-review');_,r=api('POST','/api/rooms',u['token'],{});rid=r['roomId']
s=raw_socket(u['token'],rid);s.settimeout(15);api('PUT','/api/rooms/'+rid+'/ready',u['token'],{'ready':True})
while True:
 op,payload=frame(s)
 if op==9:break
os.kill(pid,signal.SIGSTOP)
try:
 pong(s,payload) # TCP receives it while server callbacks cannot run.
 time.sleep(9)
finally:os.kill(pid,signal.SIGCONT)
stop=threading.Event();closed=[];responses=[]
def respond():
 try:
  while not stop.is_set():
   op,p=frame(s)
   if op==9:pong(s,p);responses.append(1)
   if op==8:
    closed.append(struct.unpack('!H',p[:2])[0] if len(p)>=2 else None);break
 except (OSError,EOFError):pass
thread=threading.Thread(target=respond,daemon=True);thread.start();time.sleep(12)
status,room=api('GET','/api/rooms/'+rid,u['token'])
passed=status==200 and not closed and room['players'][0]['ready'] and room['players'][0]['connected']
result={'case':'server nine-second pause with queued matching Pong recovers without eviction','status':'PASS' if passed else 'FAIL','pause_seconds':9,'post_resume_seconds':12,'native_pongs_after_resume':len(responses),'close_codes':closed}
print(result,flush=True);Path(__file__).with_name('server-pause-results.json').write_text(json.dumps([result],indent=2)+'\n')
stop.set();s.close();thread.join(1)
if not passed:raise SystemExit(1)
