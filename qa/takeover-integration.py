"""Local-only real HTTP/WebSocket takeover regression; owns and terminates its JVM."""
import argparse, asyncio, http.client, json, os, subprocess, time, uuid
from pathlib import Path
import websockets
P=argparse.ArgumentParser();P.add_argument('--jar',required=True);P.add_argument('--java',default='java');P.add_argument('--port',type=int,default=18741);P.add_argument('--output',default='/tmp/bridge-takeover-network-results.json');A=P.parse_args()
ORIGIN='https://bridgego-takeover-test.example';RESULTS=[]
def check(case,ok):
    RESULTS.append({'case':case,'status':'PASS' if ok else 'FAIL'});print(RESULTS[-1],flush=True);assert ok,case

def request(method,path,token=None,data=None):
    c=http.client.HTTPConnection('127.0.0.1',A.port,timeout=5);h={'Content-Type':'application/json','Origin':ORIGIN}
    if token:h['Authorization']='Bearer '+token
    c.request(method,path,json.dumps(data) if data is not None else None,h);r=c.getresponse();raw=r.read();status=r.status;c.close()
    return status,json.loads(raw) if raw else None
class Human:
    def __init__(self,u,r):self.u=u;self.r=r;self.snapshots=[];self.errors=[];self.ws=None
    async def connect(self):
        self.ws=await websockets.connect(f'ws://127.0.0.1:{A.port}/ws',origin=ORIGIN,ping_interval=None)
        await self.ws.send(json.dumps({'type':'subscribe','token':self.u['token'],'roomId':self.r}));self.task=asyncio.create_task(self.read());await asyncio.sleep(.1);return self
    async def read(self):
        try:
            async for raw in self.ws:
                msg=json.loads(raw)
                if msg['type']=='snapshot':self.snapshots.append(msg['room'])
                elif msg['type']=='error':self.errors.append(msg)
        except websockets.ConnectionClosed:pass
    async def close(self):
        if self.ws:await self.ws.close();await self.task;self.ws=None

def state(r,u):
    status,s=request('GET','/api/rooms/'+r,u['token']);assert status==200,(status,s);return s

def command(r,u,kind,payload=None):
    s=state(r,u);base='/api/rooms/'+r
    path=base+'/'+kind if kind in ('start','bots') else base+'/games/'+s['game']['gameId']+'/deals/'+s['game']['dealId']+'/'+kind
    return request('PUT' if kind in ('privilege','redeal') else 'POST',path,u['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':s['revision'],**(payload or {})})

async def setup(count, reuse=None):
    users=[];clients=[];room=None
    for i in range(count):
        
        if reuse is None:st,u=request('POST','/api/sessions',data={'displayName':f'T{i}'});assert st==200
        else:u=reuse[i]
        users.append(u)
        if room is None:st,r=request('POST','/api/rooms',u['token'],{});assert st==200;room=r['roomId']
        else:assert request('POST','/api/rooms/'+room+'/join',u['token'],{})[0]==200
        clients.append(await Human(u,room).connect())
    for seat in range(count,4):assert command(room,users[0],'bots',{'seat':seat})[0]==200
    for u in users:assert request('PUT','/api/rooms/'+room+'/ready',u['token'],{'ready':True})[0]==200
    assert command(room,users[0],'start')[0]==200
    return room,users,clients
async def cleanup(r,users,clients):
    for u in users:request('POST','/api/rooms/'+r+'/leave',u['token'],{})
    for c in clients:await c.close()

def privacy(s):
    assert all('ownHand' not in p and 'token' not in p for p in s['players'])
    play=s['game'].get('play') if s.get('game') else None
    if s.get('game') and s['game'].get('redeal',{}).get('yourPoints') is not None:
        hand=s['you']['ownHand'];points=sum({14:4,13:3,12:2,11:1}.get(card['rank'],0) for card in hand)
        assert s['game']['redeal']['yourPoints']==points
    assert all('yourPoints' not in p and 'eligible' not in p for p in s['players'])
    if play:
        for trick in play['completedTricks']:assert trick['cardsVisible'] or trick['plays']==[]
async def act(r,u):
    s=state(r,u);you=s['you'];g=s['game']
    if g.get('redeal',{}).get('canChoose'):command(r,u,'redeal',{'redeal':False})
    elif g['privilege']['canChoose']:command(r,u,'privilege',{'use':False})
    elif you['legalCalls']:command(r,u,'calls',{'type':'PASS'})
    elif you['legalCards']:command(r,u,'plays',{'card':you['legalCards'][0]})
    elif you['canAcknowledgeResult']:command(r,u,'acknowledge-result')

async def one_human():
    r,users,clients=await setup(1);u=users[0];c=clients[0]
    try:
        await c.close();await asyncio.sleep(.2);s=state(r,u)
        check('last human disconnect pauses with common deadline',s['game']['paused'] and s['allHumansReconnectDeadline'] is not None and s['phase']!='MATCH_RESULT')
        await asyncio.sleep(8.6);s=state(r,u)
        check('last human survives before ten-second deadline',s['phase']!='MATCH_RESULT')
        await c.connect();s=state(r,u)
        check('last human reconnect near boundary resumes original seat',not s['game']['paused'] and s['allHumansReconnectDeadline'] is None and s['players'][0]['controlMode']=='HUMAN')
        await asyncio.sleep(1.6);check('old disconnect deadline cannot terminate reclaimed match',state(r,u)['phase']!='MATCH_RESULT')
        await c.close();await asyncio.sleep(10.8);s=state(r,u);privacy(s)
        check('all-human grace expiry is NO_CONTEST, no rematch',s['phase']=='MATCH_RESULT' and s['game']['matchResult']['reason']=='NO_HUMANS_CONNECTED' and s['game']['matchResult']['outcome']=='NO_CONTEST' and not s['canRematch'])
        check('incomplete deal not exposed or scored',not s['game']['completedDealResults'] and s['game']['teamTotals']=={'NS':0,'EW':0})
        st,lobby=request('GET','/api/rooms',u['token']);check('closed match hidden from lobby',st==200 and r not in json.dumps(lobby))
        extra=[]
        for i in range(2):
            _,v=request('POST','/api/sessions',data={'displayName':f'C{i}'});st,new=request('POST','/api/rooms',v['token'],{});extra.append((v,new));check(f'closed match releases capacity for room {i+1}',st==200)
        for v,new in extra:request('POST','/api/rooms/'+new['roomId']+'/leave',v['token'],{})
    finally:await cleanup(r,users,clients)

async def two_humans():
    r,users,clients=await setup(2);u,v=users;c,d=clients
    try:
        await c.close();await asyncio.sleep(.2);s=state(r,v)
        check('individual grace is reconnecting and paused',s['players'][0]['controlMode']=='RECONNECTING' and s['game']['paused'])
        await asyncio.sleep(10.5);s=state(r,v);p=s['players'][0]
        check('ten seconds activates human takeover without pausing',p['controlMode']=='BOT_TAKEOVER' and p['playerType']=='HUMAN' and not p['connected'] and not s['game']['paused'])
        private=state(r,u);check('takeover disables original action projection',not private['you']['legalCalls'] and not private['you']['legalCards'] and not private['you']['canAcknowledgeResult'] and not private['game']['privilege']['canChoose'])
        st,err=command(r,u,'privilege',{'use':False});check('offline original commands denied',st==409 and err['code']=='CONTROL_TAKEN_OVER')
        end=time.monotonic()+45;seen=False
        while time.monotonic()<end:
            await act(r,v);s=state(r,v);privacy(s)
            if 'BOT_TAKEOVER' in json.dumps(s['game']):seen=True;break
            await asyncio.sleep(.25)
        check('real scheduled takeover action attributed BOT_TAKEOVER',seen)
        await c.connect();s=state(r,u);check('same identity socket automatically reclaims',s['players'][0]['controlMode']=='HUMAN' and s['players'][0]['connected'] and s['you']['playerId']==u['playerId'])
        st,_=request('POST','/api/rooms/'+r+'/leave',u['token'],{});s=state(r,v);replacement=next(p for p in s['players'] if p['seat']==0)
        check('voluntary leave replaces with permanent native bot',st==200 and replacement['playerType']=='BOT' and replacement['controlMode']=='BOT' and replacement['playerId']!=u['playerId'])
        st,err=request('GET','/api/rooms/'+r,u['token']);check('departed identity cannot read private room',st==403)
        await c.close();old_count=len(c.snapshots);await c.connect();check('departed websocket cannot resubscribe',bool(c.errors) and len(c.snapshots)==old_count)
        request('POST','/api/rooms/'+r+'/leave',v['token'],{})
        # No member remains to inspect private terminal snapshot; lobby must release room.
        st,lobby=request('GET','/api/rooms',v['token']);check('last voluntary exit immediately removes playable room',st==200 and r not in json.dumps(lobby))
        for client in clients:
            for snap in client.snapshots:privacy(snap)
        check('all received snapshots preserve hand/history privacy',True)
    finally:await cleanup(r,users,clients)

async def interrupted_play_privacy():
    r,users,clients=await setup(1);u=users[0]
    try:
        end=time.monotonic()+70
        while time.monotonic()<end:
            await act(r,u);s=state(r,u)
            if s['game']['play'] and s['game']['play']['completedTricks']:break
            await asyncio.sleep(.2)
        check('privacy scenario reaches a completed trick in unfinished deal',bool(s['game']['play'] and s['game']['play']['completedTricks']))
        await clients[0].close();await asyncio.sleep(10.8);s=state(r,u);privacy(s)
        check('no-contest does not unlock incomplete trick cards',s['phase']=='MATCH_RESULT' and all(not t['cardsVisible'] and not t['plays'] for t in s['game']['play']['completedTricks']) and not s['game']['completedDealResults'])
        check('no-contest has no winner or incomplete score',s['game']['matchResult']['winnerTeamId'] is None and s['game']['teamTotals']=={'NS':0,'EW':0})
    finally:await cleanup(r,users,clients)

async def redeal_takeover():
    # Reuse four identities while seeking a random weak hand; no seeded production hooks.
    reused=None;r=None;users=[];clients=[]
    for attempt in range(40):
        r,users,clients=await setup(4,reused);reused=users
        candidates=[i for i,u in enumerate(users) if state(r,u)['game']['redeal']['eligible']]
        if candidates:break
        await cleanup(r,users,clients);r=None
    check('joint scenario obtains eligible human hand',bool(r and candidates))
    try:
        target=candidates[0];u=users[target];old=state(r,u);old_deal=old['game']['dealId'];game_id=old['game']['gameId']
        for client in clients:await client.close()
        await asyncio.sleep(.2);s=state(r,u)
        check('all humans offline pauses redeal and hides commands',s['phase']=='REDEAL_DECISION' and s['game']['paused'] and not s['game']['redeal']['canChoose'])
        await asyncio.sleep(8.4);s=state(r,u)
        check('redeal remains unchanged before global grace deadline',s['game']['dealId']==old_deal and s['phase']=='REDEAL_DECISION')
        for client in clients:await client.connect()
        s=state(r,u);check('all-human reconnect resumes same eligible choice',not s['game']['paused'] and s['game']['dealId']==old_deal and s['game']['redeal']['canChoose'])
        # Other eligible humans explicitly keep. Target's pending choice remains undecided.
        for i,v in enumerate(users):
            if i!=target and state(r,v)['game']['redeal']['canChoose']:assert command(r,v,'redeal',{'redeal':False})[0]==200
        await clients[target].close();end=time.monotonic()+12
        while time.monotonic()<end:
            snap=state(r,u)
            if next(p for p in snap['players'] if p['playerId']==u['playerId'])['controlMode']=='BOT_TAKEOVER':break
            await asyncio.sleep(.05)
        await clients[target].connect();await asyncio.sleep(1.3);s=state(r,u)
        check('reclaim before scheduled bot decision cancels stale redeal action',s['game']['dealId']==old_deal and s['game']['redeal']['canChoose'])
        await clients[target].close();await asyncio.sleep(10.5)
        s=state(r,users[(target+1)%4]);check('eligible disconnected human becomes takeover',next(p for p in s['players'] if p['playerId']==u['playerId'])['controlMode']=='BOT_TAKEOVER')
        end=time.monotonic()+8
        while time.monotonic()<end and state(r,u)['game']['dealId']==old_deal:await asyncio.sleep(.15)
        s=state(r,u);check('takeover chooses redeal with fresh deal id',s['game']['dealId']!=old_deal and s['game']['redealCount']>old['game']['redealCount'])
        await clients[target].connect();s=state(r,u)
        check('reclaim after automated redeal sees fresh private hand',next(p for p in s['players'] if p['playerId']==u['playerId'])['controlMode']=='HUMAN' and len(s['you']['ownHand'])==13)
        st,err=request('PUT',f'/api/rooms/{r}/games/{game_id}/deals/{old_deal}/redeal',u['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':s['revision'],'redeal':False})
        check('fresh-deal old command rejected without mutation',st==409 and err['code']=='STALE_DEAL')
        for client in clients:
            for snap in client.snapshots:privacy(snap)
        check('joint redeal snapshots expose only recipient own points',True)
        for client in clients:await client.close()
        await asyncio.sleep(10.8);s=state(r,u);privacy(s)
        check('joint all-human expiry terminates without private point leak',s['phase']=='MATCH_RESULT' and s['game']['matchResult']['outcome']=='NO_CONTEST' and s['game']['redeal']['yourPoints'] is None)
    finally:
        if r:await cleanup(r,users,clients)

async def main():
    logfile=open('/tmp/bridge-takeover-network.log','w');proc=subprocess.Popen([A.java,'-Xmx320m','-jar',str(Path(A.jar).resolve()),'--spring.profiles.active=prod',f'--server.port={A.port}',f'--bridge.public-origin={ORIGIN}'],env={**os.environ,'PUBLIC_ORIGIN':ORIGIN},stdout=logfile,stderr=subprocess.STDOUT)
    try:
        ready=False
        for _ in range(300):
            try:
                if request('GET','/health/ready')[0]==200:ready=True;break
            except OSError:pass
            await asyncio.sleep(.1)
        assert ready,'server startup failed'
        await one_human();await two_humans();await interrupted_play_privacy();await redeal_takeover()
    finally:
        proc.terminate();proc.wait(timeout=20);logfile.close();Path(A.output).write_text(json.dumps(RESULTS,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
