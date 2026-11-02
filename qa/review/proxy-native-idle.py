"""Read-only diagnostic: identical native-Pong clients direct vs Vite, no app ping."""
import asyncio,json,time,sys,os
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from integration import api,session
import websockets
async def main():
 clients=[];start=time.monotonic();events=[]
 async def read(c):
  try:
   async for raw in c['ws']:
    b=json.loads(raw)
    if b['type']=='snapshot':c['snapshots']+=1
  except websockets.ConnectionClosed as e:
   c['closed']={'seconds':round(time.monotonic()-start,2),'code':e.code,'reason':e.reason};events.append({'route':c['route'],**c['closed']})
 for route,port in [('direct',8080),('vite',5173)]:
  room=None
  for i in range(4):
   u=session(route+str(i));_,r=api('POST','/api/rooms'+('/'+room+'/join' if room else ''),u['token'],{});room=r['roomId']
   ws=await websockets.connect(f'ws://127.0.0.1:{port}/ws',ping_interval=None,origin='http://127.0.0.1:5173')
   await ws.send(json.dumps({'type':'subscribe','token':u['token'],'roomId':room}))
   c={'route':route,'user':u,'room':room,'ws':ws,'snapshots':0,'closed':None};c['task']=asyncio.create_task(read(c));clients.append(c)
   api('PUT','/api/rooms/'+room+'/ready',u['token'],{'ready':True})
 duration=int(os.environ.get('QA_DURATION','180'));await asyncio.sleep(duration)
 results=[]
 for route in ['direct','vite']:
  cs=[c for c in clients if c['route']==route]
  retained=all(api('GET','/api/me',c['user']['token'])[1]['roomId']==c['room'] for c in cs)
  results.append({'route':route,'clients':len(cs),'duration_seconds':duration,'all_members_retained':retained,'unexpected_closes':[c['closed'] for c in cs if c['closed']],'snapshot_counts':[c['snapshots'] for c in cs]})
 print(json.dumps(results,indent=2),flush=True)
 Path(__file__).with_name('proxy-native-idle-results.json').write_text(json.dumps(results,indent=2)+'\n')
 for c in clients:await c['ws'].close()
 await asyncio.gather(*(c['task'] for c in clients))
asyncio.run(main())
