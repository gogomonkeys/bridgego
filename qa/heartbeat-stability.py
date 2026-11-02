"""Real loopback transport stability. No UI rendering; no synthetic tokens logged."""
import asyncio,json,time,os,socket,base64,struct
from pathlib import Path
from integration import api,session,Client
import websockets
VERSION=os.environ.get('QA_VERSION','baseline-0.1.1')
DURATION=int(os.environ.get('QA_DURATION','65'))
FIXED=os.environ.get('QA_EXPECT_FIXED')=='1'
DIAGNOSTIC=os.environ.get('QA_DIAGNOSTIC')=='1'
results=[]
def record(case,passed,**detail):
 r={'case':case,'status':'PASS' if passed else 'FAIL',**detail};results.append(r);print(r,flush=True)
from raw_ws import raw_socket
async def main():
 normal=session('stability-normal');_,r=api('POST','/api/rooms',normal['token'],{});normal_client=await Client(normal,r['roomId']).connect()
 delayed=session('stability-delayed-js');_,r=api('POST','/api/rooms',delayed['token'],{});rid=r['roomId']
 ws=await websockets.connect('ws://127.0.0.1:8080/ws',ping_interval=None)
 await ws.send(json.dumps({'type':'subscribe','token':delayed['token'],'roomId':rid}));await ws.recv()
 api('PUT','/api/rooms/'+rid+'/ready',delayed['token'],{'ready':True})
 start=time.monotonic();lost_at=None;protocol_pongs=0;probe_timeouts=0;max_probe_rtt=0
 # No application JSON ping is sent. Python transport still automatically answers native server PING.
 # Independent protocol PING -> PONG verifies the connection is healthy despite absent app heartbeat.
 while time.monotonic()-start<DURATION:
  if lost_at is None:
   try:
    probe_start=time.monotonic();pong=await ws.ping();await asyncio.wait_for(pong,2);protocol_pongs+=1;max_probe_rtt=max(max_probe_rtt,time.monotonic()-probe_start)
   except websockets.ConnectionClosed:lost_at=round(time.monotonic()-start,2)
   except asyncio.TimeoutError:
    if not DIAGNOSTIC:raise
    probe_timeouts+=1;print({'diagnostic':'two-second client probe timeout; continuing to observe actual socket','elapsed_seconds':round(time.monotonic()-start,2)},flush=True)
  await asyncio.sleep(1)
 if DIAGNOSTIC:print({'diagnostic':'native probe summary','probe_timeouts':probe_timeouts,'max_successful_probe_rtt_seconds':round(max_probe_rtt,3),'actual_close_seconds':lost_at},flush=True)
 normal_ok=api('GET','/api/me',normal['token'])[1]['roomId'] is not None
 delayed_me=api('GET','/api/me',delayed['token'])[1]
 record('normal app-heartbeat idle stability',normal_ok,duration_seconds=DURATION)
 if FIXED:
  status,room=api('GET','/api/rooms/'+rid,delayed['token'])
  record('transport-responsive client survives absent JS heartbeats',lost_at is None and delayed_me['roomId']==rid and status==200 and room['players'][0]['ready'],duration_seconds=DURATION,protocol_pongs=protocol_pongs)
 else:
  record('baseline reproduces healthy transport evicted without app heartbeat',lost_at is not None and lost_at<10 and delayed_me['roomId'] is None,disconnect_seconds=lost_at,successful_protocol_pongs_before_close=protocol_pongs)
 await ws.close();await normal_client.close()
 if FIXED:
  dead=session('stability-dead');_,r=api('POST','/api/rooms',dead['token'],{});raw=raw_socket(dead['token'],r['roomId']);start=time.monotonic();released=None
  await asyncio.sleep(.2)
  while time.monotonic()-start<35:
   if api('GET','/api/me',dead['token'])[1]['roomId'] is None:released=round(time.monotonic()-start,2);break
   await asyncio.sleep(.25)
  raw.close();record('nonresponsive transport released after detection without grace',released is not None and released<=9.5,release_seconds=released,maximum_expected_seconds=9.5)
  closing=session('stability-close');_,r=api('POST','/api/rooms',closing['token'],{});c=await Client(closing,r['roomId']).connect();await asyncio.sleep(.1);start=time.monotonic();await c.close();await asyncio.sleep(.2)
  record('explicit socket close releases immediately',api('GET','/api/me',closing['token'])[1]['roomId'] is None,elapsed_seconds=round(time.monotonic()-start,2))
if __name__=='__main__':
 try:asyncio.run(main())
 finally:Path(__file__).with_name('heartbeat-'+VERSION+'-results.json').write_text(json.dumps(results,indent=2)+'\n')
 if any(r['status']=='FAIL' for r in results):raise SystemExit(1)
