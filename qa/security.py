"""Supplemental API boundary cases; local-only synthetic guests."""
import asyncio,json
from pathlib import Path
from integration import api,session,Client,ok,results
async def main():
 a,b=session('boundaryA'),session('boundaryB')
 _,r=api('POST','/api/rooms',a['token'],{});rid=r['roomId'];path='/api/rooms/'+rid
 ca=await Client(a,rid).connect();api('POST',path+'/join',b['token'],{});cb=await Client(b,rid).connect()
 api('PUT',path+'/ready',a['token'],{'ready':True,'playerId':b['playerId']})
 state=api('GET',path,a['token'])[1]
 ok('forged playerId cannot change another player',next(p for p in state['players'] if p['playerId']==a['playerId'])['ready'] and not next(p for p in state['players'] if p['playerId']==b['playerId'])['ready'])
 bad=Client({'token':'invalid'},rid);await bad.connect();await asyncio.sleep(.2)
 ok('invalid WS token denied',bool(bad.errors) and not bad.snaps);await bad.close()
 await cb.ws.send(json.dumps({'type':'ready','ready':True,'playerId':a['playerId']}));await asyncio.sleep(.2)
 ok('WS command injection rejected',any(e['code']=='INVALID_MESSAGE' for e in cb.errors))
 ok('null ready rejected',api('PUT',path+'/ready',a['token'],{'ready':None})[0]==400)
 # Record parser behavior separately: JSON contract requires boolean, coercions are undesirable.
 for value in ['true',1,[],{}]:
  status,body=api('PUT',path+'/ready',a['token'],{'ready':value})
  results.append({'case':'ready rejects nonboolean '+repr(value),'status':'PASS' if status==400 else 'FAIL','detail':str(status)});print(results[-1],flush=True)
 await ca.close();await cb.close()
if __name__=='__main__':
 try:asyncio.run(main())
 finally:Path(__file__).with_name('security-results.json').write_text(json.dumps(results,indent=2)+'\n')
