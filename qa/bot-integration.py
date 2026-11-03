"""Loopback-only prod-profile bot acceptance. Own server only; no external service calls.
Runs concurrent 1-human/3-bot and 2-human/2-bot three-deal matches, then lifecycle checks.
Requires websockets. Usage: --jar ... --java ... [--port 18740] [--output ...]
"""
import argparse, asyncio, http.client, json, os, subprocess, time, uuid
from pathlib import Path
import websockets
from play_oracle import winner, score
P=argparse.ArgumentParser();P.add_argument('--jar',required=True);P.add_argument('--java',default='java');P.add_argument('--port',type=int,default=18740);P.add_argument('--output',default='/tmp/bridge-bot-network-results.json');A=P.parse_args()
ORIGIN='https://bridgego-bot-test.example';RESULTS=[]
def check(case,ok):
    RESULTS.append({'case':case,'status':'PASS' if ok else 'FAIL'});print(RESULTS[-1],flush=True);assert ok,case

def request(method,path,token=None,data=None):
    c=http.client.HTTPConnection('127.0.0.1',A.port,timeout=5)
    h={'Content-Type':'application/json','Origin':ORIGIN}
    if token:h['Authorization']='Bearer '+token
    c.request(method,path,json.dumps(data) if data is not None else None,h);r=c.getresponse();raw=r.read();status=r.status;c.close()
    return status,json.loads(raw)

class Human:
    def __init__(self,u,r):self.u=u;self.r=r;self.snapshots=[];self.errors=[]
    async def connect(self):
        self.ws=await websockets.connect(f'ws://127.0.0.1:{A.port}/ws',origin=ORIGIN,ping_interval=None)
        await self.ws.send(json.dumps({'type':'subscribe','token':self.u['token'],'roomId':self.r}));self.task=asyncio.create_task(self.read());return self
    async def read(self):
        try:
            async for raw in self.ws:
                msg=json.loads(raw)
                if msg['type']=='snapshot':self.snapshots.append(msg['room'])
                elif msg['type']=='error':self.errors.append(msg)
        except websockets.ConnectionClosed:
            pass
    async def close(self):await self.ws.close();await self.task

def state(room,user):
    status,s=request('GET','/api/rooms/'+room,user['token']);assert status==200,(status,s);return s

def command(room,user,kind,payload=None):
    s=state(room,user);base='/api/rooms/'+room
    if kind=='redeal' and not s['game']['redeal']['canChoose']:return False
    if kind=='privilege' and not s['game']['privilege']['canChoose']:return False
    path=base+'/'+kind if kind in ('start','bots') else base+'/games/'+s['game']['gameId']+'/deals/'+s['game']['dealId']+'/'+kind
    body={'actionId':str(uuid.uuid4()),'expectedRevision':s['revision'],**(payload or {})}
    status,result=request('PUT' if kind in ('privilege','redeal') else 'POST',path,user['token'],body)
    # A bot may commit between snapshot and mutation; retry only the explicit rejected stale command.
    if status==409 and result.get('code')=='STALE_REVISION':return False
    assert status==200,(status,result);return True

async def match(number,humans):
    users=[];clients=[];room=None
    try:
        for i in range(humans):
            st,u=request('POST','/api/sessions',data={'displayName':f'Q{number}{i}'});assert st==200;users.append(u)
            if i==0:
                st,r=request('POST','/api/rooms',u['token'],{});assert st==200;room=r['roomId']
            else:assert request('POST','/api/rooms/'+room+'/join',u['token'],{})[0]==200
            clients.append(await Human(u,room).connect());await asyncio.sleep(.05)
        for seat in range(humans,4):assert command(room,users[0],'bots',{'seat':seat})
        for u in users:assert request('PUT','/api/rooms/'+room+'/ready',u['token'],{'ready':True})[0]==200
        s=state(room,users[0]);check(f'{number}: bots ready without sockets and explicit type',s['allReady'] and all(p['playerType']==('HUMAN' if p['seat']<humans else 'BOT') for p in s['players']))
        assert command(room,users[0],'start');started=time.monotonic();seen=set()
        while time.monotonic()-started<420:
            s=state(room,users[0])
            if s['phase']=='MATCH_RESULT':break
            for user in users:
                s=state(room,user);g=s['game'];you=s['you']
                if g['redeal']['canChoose']:command(room,user,'redeal',{'redeal':False})
                elif g['privilege']['canChoose']:command(room,user,'privilege',{'use':False})
                elif you['legalCalls']:command(room,user,'calls',{'type':'PASS'})
                elif you['legalCards']:command(room,user,'plays',{'card':you['legalCards'][0]})
                elif you['canAcknowledgeResult']:command(room,user,'acknowledge-result')
            await asyncio.sleep(.25)
        s=state(room,users[0]);check(f'{number}: full three deals complete with bots',s['phase']=='MATCH_RESULT' and s['game']['completedDeals']==3)
        totals={'NS':0,'EW':0}
        for deal in s['game']['completedDealResults']:
            hands={seat:[] for seat in range(4)}
            for trick in deal['completedTricks']:
                for move in trick['plays']:hands[move['seat']].append(move['card'])
            initial={seat:list(cards) for seat,cards in hands.items()}
            for seat in range(humans,4):
                points=sum({14:4,13:3,12:2,11:1}.get(c['rank'],0) for c in initial[seat])
                assert points>5 or (points==5 and any(c['rank']==14 for c in initial[seat])), 'Eligible bot failed to redeal' 
            check(f'{number}: deal{deal["dealIndex"]} bot low-point hands are always redealt',True)
            check(f'{number}: deal{deal["dealIndex"]} conserves52cards',sum(map(len,hands.values()))==52 and len({(c['suit'],c['rank']) for h in hands.values() for c in h})==52)
            strain=deal['contract']['strain'];lead=(deal['contract']['holderSeat']+3)%4
            for trick in deal['completedTricks']:
                table=[]
                for offset,move in enumerate(trick['plays']):
                    seat=move['seat'];card=move['card'];hand=hands[seat]
                    assert seat==(lead+3*offset)%4 and card in hand
                    assert move['source']==('MANUAL' if seat<humans else 'BOT')
                    if seat>=humans:
                        if not table:assert card['rank']==max(c['rank'] for c in hand if c['suit']==card['suit'])
                        else:
                            follow=[c for c in hand if c['suit']==table[0]['card']['suit']]
                            lead_suit=table[0]['card']['suit']
                            eligible=[p for p in table if p['card']['suit']==strain] or [p for p in table if p['card']['suit']==lead_suit]
                            leading=max(eligible,key=lambda p:p['card']['rank'])
                            if len(table)==3 and leading['seat']%2==seat%2:
                                non_trumps=[c for c in hand if c['suit']!=strain]
                                legal=follow or non_trumps or hand
                                assert card in legal and card['rank']==min(c['rank'] for c in legal)
                            elif follow:
                                maximum=max(c['rank'] for c in follow)
                                can_win=leading['card']['suit']==lead_suit and maximum>leading['card']['rank']
                                expected_rank=maximum if can_win else min(c['rank'] for c in follow)
                                assert card in follow and card['rank']==expected_rank
                            else:
                                high=max([p['card']['rank'] for p in table if p['card']['suit']==strain],default=0)
                                cuts=[c for c in hand if c['suit']==strain and c['rank']>high]
                                if cuts:assert card in cuts and card['rank']==min(c['rank'] for c in cuts)
                                else:
                                    discards=[c for c in hand if c['suit']!=strain] or hand
                                    assert card in discards and card['rank']==min(c['rank'] for c in discards)
                    hand.remove(card);table.append(move)
                lead=winner(table,strain);assert lead==trick['winnerSeat']
            check(f'{number}: deal{deal["dealIndex"]} everybotplaymatchespolicy and rightorder',True)
            for auction in deal['auctionRounds']:
                highest=None;targets={}
                for call in auction['calls']:
                    seat=call['seat'];assert call['source']==('MANUAL' if seat<humans else 'BOT')
                    if seat>=humans:
                        if highest and highest['seat']%2==seat%2:assert call['type']=='PASS'
                        if call['type']=='BID':
                            counts={suit:sum(c['suit']==suit for c in initial[seat]) for suit in ('CLUB','DIAMOND','HEART','SPADE')}
                            assert counts[call['strain']]==max(counts.values()) and call['level']<=2
                            assert targets.setdefault(seat,call['strain'])==call['strain']
                    if call['type']=='BID':highest=call
            attack=deal['contract']['teamId'];defence='EW' if attack=='NS' else 'NS'
            expected=score(deal['contract']['level'],deal['tricksByTeam'][attack],False,False)
            assert deal['scores'][attack]==expected['attack'] and deal['scores'][defence]==expected['defence']
            for team in totals:totals[team]+=deal['scores'][team]['total']
        check(f'{number}: score and longest-suit bids match independent oracle',s['game']['teamTotals']==totals)
        for client in clients:
            assert not client.errors
            for snap in client.snapshots:
                assert snap['you']['playerId']==client.u['playerId']
                assert all('ownHand' not in p and 'token' not in p for p in snap['players'])
                if snap['game']:
                    assert set(snap['game']['redeal'])=={'yourPoints','eligible','yourChoice','canChoose'}
                    if snap['phase']=='REDEAL_DECISION':
                        expected_points=sum({14:4,13:3,12:2,11:1}.get(card['rank'],0) for card in snap['you']['ownHand'])
                        assert snap['game']['redeal']['yourPoints']==expected_points
                if snap['game'] and snap['game']['play']:
                    for trick in snap['game']['play']['completedTricks']:
                        assert trick['cardsVisible'] or trick['plays']==[]
        check(f'{number}: human WS private projection and hidden history',True)
        body={'actionId':str(uuid.uuid4()),'expectedRevision':s['revision']}
        st,r=request('POST','/api/rooms/'+room+'/games/'+s['game']['gameId']+'/rematch',users[0]['token'],body)
        check(f'{number}: rematch preservesbots and resets humans',st==200 and r['room']['phase']=='WAITING' and all(p['ready']==(p['playerType']=='BOT') for p in r['room']['players']))
    finally:
        if room:
            for u in users:request('POST','/api/rooms/'+room+'/leave',u['token'],{})
        for c in clients:await c.close()

async def main():
    logfile=open('/tmp/bridge-bot-network.log','w');proc=subprocess.Popen([A.java,'-Xmx320m','-jar',str(Path(A.jar).resolve()),'--spring.profiles.active=prod',f'--server.port={A.port}',f'--bridge.public-origin={ORIGIN}'],env={**os.environ,'PUBLIC_ORIGIN':ORIGIN},stdout=logfile,stderr=subprocess.STDOUT)
    try:
        for _ in range(300):
            try:
                if request('GET','/health/ready')[0]==200:break
            except OSError:pass
            await asyncio.sleep(.1)
        await asyncio.gather(match(1,1),match(2,2))
    finally:
        proc.terminate();proc.wait(timeout=20);logfile.close()
        Path(A.output).write_text(json.dumps(RESULTS,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
