"""Independent v0.2 real HTTP/WS game/privacy acceptance; loopback guests only."""
import asyncio,json,time,uuid
from pathlib import Path
from integration import api,session,Client,public_snapshot
RESULTS=[]
def check(name,condition,detail=''):
    RESULTS.append({'case':name,'status':'PASS' if condition else 'FAIL','detail':detail});print(RESULTS[-1],flush=True)
    assert condition,name+': '+str(detail)
def get(g,i=0):
    s,r=api('GET',g['path'],g['users'][i]['token']);assert s==200,(s,r);return r
def action(g,i,kind,extra=None,revision=None,action_id=None):
    r=get(g,i);body={'actionId':action_id or str(uuid.uuid4()),'expectedRevision':r['revision'] if revision is None else revision,**(extra or {})}
    path=g['path']+'/start' if kind=='start' else g['path']+'/games/'+r['game']['gameId']+'/deals/'+r['game']['dealId']+'/'+kind
    status,response=api('PUT' if kind=='privilege' else 'POST',path,g['users'][i]['token'],body)
    if status==200:
        assert private_valid(response['room'],g['users'][i]),'command response leaked or misattributed private view'
    return status,response,body,path
async def setup(label, planned_deals=None):
    users=[session(label+str(i)) for i in range(4)]
    _,r=api('POST','/api/rooms',users[0]['token'],{} if planned_deals is None else {'plannedDeals':planned_deals});path='/api/rooms/'+r['roomId'];clients=[]
    for i,u in enumerate(users):
        if i:assert api('POST',path+'/join',u['token'],{})[0]==200
        clients.append(await Client(u,r['roomId']).connect())
    await asyncio.sleep(.2)
    for u in users:assert api('PUT',path+'/ready',u['token'],{'ready':True})[0]==200
    await asyncio.sleep(.2)
    return {'users':users,'clients':clients,'path':path,'roomId':r['roomId']}
def cards(value):
    found=[]
    if isinstance(value,dict):
        if 'suit' in value and 'rank' in value:found.append((value['suit'],value['rank']))
        else:
            for v in value.values():found.extend(cards(v))
    elif isinstance(value,list):
        for v in value:found.extend(cards(v))
    return found
def private_valid(r,user):
    you=r.get('you');hand=you and you.get('ownHand')
    allowed={'roomId','revision','phase','capacity','plannedDeals','allReady','hostPlayerId','canStart','players','game','you','canRematch','lastMatch','allHumansReconnectDeadline'}
    if set(r)!=allowed or not you or you['playerId']!=user['playerId']:return False
    if r['game'] and set(r['game'])!={'gameId','dealId','plannedDeals','dealIndex','completedDeals','dealerSeat','redealCount','lastRedealReason','paused','abortReason','redeal','privilege','auction','teamTotals','completedDealResults','operationTimer','roster','play','pendingForfeit','matchResult','forfeitEvents','resultAcknowledgedSeats','auctionRounds'}:return False
    if r['game'] and set(r['game']['privilege'])!={'status','yourChoice','yourChoiceSource','usedByTeam','canChoose'}:return False
    if r['game'] and set(r['game']['redeal'])!={'yourPoints','eligible','yourChoice','canChoose'}:return False
    if r['game'] and r['game']['redeal']['yourPoints'] is not None:
        if not isinstance(r['game']['redeal']['yourPoints'],int):return False
    legal=you.get('legalCards',[])
    if any(c not in hand for c in legal):return False
    # Played cards are intentionally public; private cards may only occur in ownHand/legalCards.
    import copy
    scrubbed=copy.deepcopy(r);scrubbed['you'].pop('legalCards',None)
    scrubbed['lastMatch']=None
    if scrubbed.get('game'):
        scrubbed['game']['play']=None
        scrubbed['game']['completedDealResults']=[]
    return sorted(cards(scrubbed))==sorted(cards(hand)) and 0<=len(hand)<=13
async def start(g):
    s,b,_,_=action(g,0,'start');assert s==200,(s,b);await asyncio.sleep(.2);return b['room']
async def resolve(g):
    for i in (0,1):
        s,b,_,_=action(g,i,'privilege',{'use':True});assert s==200,(s,b)
    await asyncio.sleep(.2)
async def cleanup(g):
    for c in g['clients']:
        await c.close()
async def main():
    groups=[]
    try:
        g=await setup('game-main');groups.append(g);outsider=session('game-outsider')
        check('four ready players do not auto-start',get(g)['phase']=='WAITING' and get(g)['canStart'])
        check('nonhost cannot start including forged identity',action(g,1,'start',{'playerId':g['users'][0]['playerId']})[0]==403)
        rev=get(g)['revision'];s,b=api('POST',g['path']+'/start',outsider['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':rev})
        check('outsider cannot start',s==403)
        api('PUT',g['path']+'/ready',g['users'][3]['token'],{'ready':False})
        check('host start requires all four ready',action(g,0,'start')[0]==409)
        api('PUT',g['path']+'/ready',g['users'][3]['token'],{'ready':True})
        await g['clients'][3].close();await asyncio.sleep(.1)
        assert api('POST',g['path']+'/join',g['users'][3]['token'],{})[0]==200
        assert api('PUT',g['path']+'/ready',g['users'][3]['token'],{'ready':True})[0]==200
        check('host start rejects ready but not-yet-connected fourth player',action(g,0,'start')[0]==409)
        g['clients'][3]=await Client(g['users'][3],g['roomId']).connect();await asyncio.sleep(.2)
        rev=get(g)['revision'];bodies=[{'actionId':str(uuid.uuid4()),'expectedRevision':rev} for _ in range(2)]
        rs=await asyncio.gather(*(asyncio.to_thread(api,'POST',g['path']+'/start',g['users'][0]['token'],body) for body in bodies))
        check('concurrent start creates one game only',sorted(s for s,_ in rs)==[200,409])
        winner=next(i for i,(s,_) in enumerate(rs) if s==200);started=rs[winner][1]['room'];await asyncio.sleep(.2)
        s,replay=api('POST',g['path']+'/start',g['users'][0]['token'],bodies[winner])
        check('start replay does not reshuffle',s==200 and replay['outcome']=='REPLAYED' and replay['room']['game']['dealId']==started['game']['dealId'])
        s,forged=api('POST',g['path']+'/start',g['users'][1]['token'],bodies[winner])
        check('cross-player action key cannot replay hosts private response',s in (403,409) and 'room' not in forged)
        views=[get(g,i) for i in range(4)];hands=[cards(r['you']['ownHand']) for r in views]
        check('private deal has four disjoint thirteen-card hands',all(len(h)==13 and len(set(h))==13 for h in hands) and len(set(sum(hands,[])))==52 and all(s in ['CLUB','DIAMOND','HEART','SPADE'] and 2<=r<=14 for s,r in sum(hands,[])))
        check('GET and WS never include any other hand',all(private_valid(r,u) for r,u in zip(views,g['users'])) and all(private_valid(s,c.u) for c in g['clients'] for s in c.snaps))
        check('opposite seats form teams',all(r['you']['teamId']==('NS' if r['you']['seat']%2==0 else 'EW') for r in views))
        bad=await Client(outsider,g['roomId']).connect();await asyncio.sleep(.2)
        check('outsider active subscription receives no hand or snapshot',not bad.snaps and bool(bad.errors));await bad.close()
        check('outsider GET and room listing disclose no cards',api('GET',g['path'],outsider['token'])[0]==403 and not cards(api('GET','/api/rooms',outsider['token'])[1]))
        check('active game rejects replacement and ready mutation',api('POST',g['path']+'/join',outsider['token'],{})[0]==409 and api('PUT',g['path']+'/ready',g['users'][0]['token'],{'ready':False})[0]==409)
        check('auction cannot begin before privilege reveal',action(g,0,'calls',{'type':'PASS'})[0]==409)
        s,b,choice_body,choice_path=action(g,0,'privilege',{'use':True,'playerId':g['users'][1]['playerId'],'teamId':'EW'})
        check('privilege identity comes from bearer only',s==200 and b['room']['game']['privilege']['yourChoice'] is True and get(g,1)['game']['privilege']['yourChoice'] is None)
        await asyncio.sleep(.2)
        check('resolved first team remains hidden until other team resolves',all(get(g,i)['game']['privilege']['usedByTeam'] is None for i in range(4)) and all(c.snaps[-1]['game']['privilege']['usedByTeam'] is None for c in g['clients']))
        s,b=api('PUT',choice_path,g['users'][0]['token'],choice_body)
        check('privilege exact retry idempotent',s==200 and b['outcome']=='REPLAYED')
        check('partner yes locks own team without revealing opponents',not get(g,2)['game']['privilege']['canChoose'] and get(g,1)['game']['privilege']['canChoose'])
        check('privilege choice cannot be changed',action(g,0,'privilege',{'use':False})[0]==409)
        s,b,_,_=action(g,1,'privilege',{'use':False});assert s==200,(s,b)
        check('single no vote does not resolve team or leak votes',get(g)['phase']=='PRIVILEGE_DECISION' and get(g)['game']['privilege']['usedByTeam'] is None and get(g,3)['game']['privilege']['yourChoice'] is None)
        s,b,_,_=action(g,3,'privilege',{'use':False});assert s==200,(s,b);await asyncio.sleep(.2)
        check('both-team simultaneous reveal starts auction',all(get(g,i)['phase']=='BIDDING' and get(g,i)['game']['privilege']['usedByTeam']=={'NS':True,'EW':False} for i in range(4)))
        check('unneeded teammates need not vote after team yes',get(g,2)['game']['privilege']['yourChoice'] is None and action(g,2,'privilege',{'use':True})[0]==409)
        check('out-of-turn call rejected',action(g,2,'calls',{'type':'PASS'})[0]==409)
        before=get(g)['revision']
        bad=[{'type':'DOUBLE'},{'type':'REDOUBLE'},{'type':'BID','level':0,'strain':'CLUB'},{'type':'BID','level':8,'strain':'CLUB'},{'type':'BID','level':1,'strain':'INVALID'}]
        check('invalid auction inputs reject without mutation',all(action(g,0,'calls',x)[0] in (400,409) for x in bad) and get(g)['revision']==before)
        s,b,bid_body,bid_path=action(g,0,'calls',{'type':'BID','level':1,'strain':'CLUB'});assert s==200,(s,b)
        s,replay=api('POST',bid_path,g['users'][0]['token'],bid_body)
        check('duplicate auction call does not advance twice',s==200 and replay['outcome']=='REPLAYED' and len(get(g)['game']['auction']['calls'])==1)
        changed={**bid_body,'type':'PASS'};changed.pop('level');changed.pop('strain')
        check('action id cannot be reused for different payload',api('POST',bid_path,g['users'][0]['token'],changed)[0]==409)
        check('stale revision cannot mutate current auction',action(g,1,'calls',{'type':'PASS'},revision=before)[0]==409 and len(get(g)['game']['auction']['calls'])==1)
        assert action(g,1,'calls',{'type':'BID','level':1,'strain':'DIAMOND'})[0]==200
        check('lower bid rejected',action(g,2,'calls',{'type':'BID','level':1,'strain':'CLUB'})[0]==409)
        assert action(g,2,'calls',{'type':'BID','level':1,'strain':'HEART'})[0]==200
        assert action(g,3,'calls',{'type':'BID','level':1,'strain':'SPADE'})[0]==200
        assert action(g,0,'calls',{'type':'BID','level':1,'strain':'NO_TRUMP'})[0]==200
        check('equal bid rejected',action(g,1,'calls',{'type':'BID','level':1,'strain':'NO_TRUMP'})[0]==409)
        assert action(g,1,'calls',{'type':'BID','level':2,'strain':'CLUB'})[0]==200
        for i in (2,3,0):assert action(g,i,'calls',{'type':'PASS'})[0]==200
        end=get(g);result=end['game']['auction']['result']
        check('ascending strains and three passes begin play with correct contract',end['phase']=='PLAYING' and result=={'type':'CONTRACT','holderSeat':1,'level':2,'strain':'CLUB','teamId':'EW'})
        check('auction completion never fabricates game results',end['game']['completedDeals']==0 and end['game']['teamTotals']=={'NS':0,'EW':0} and end['game']['completedDealResults']==[] and action(g,1,'calls',{'type':'PASS'})[0]==409)
        check('all delivered active snapshots stay viewer-private',all(private_valid(s,c.u) and (s['game'] is None or sorted(cards(s['you']['ownHand']))==sorted(hands[i])) for i,c in enumerate(g['clients']) for s in c.snaps))
        start_at=time.monotonic();api('POST',g['path']+'/leave',g['users'][0]['token'],{});ended=get(g,1)
        check('manual active exit immediately forfeits unscored',time.monotonic()-start_at<1 and ended['phase']=='MATCH_RESULT' and ended['game']['matchResult']['reason']=='MANUAL_EXIT' and ended['game']['completedDeals']==0 and len(ended['players'])==3)
        # Passed-out redeal is a new private deal, not a scored completed deal.
        h=await setup('game-redeal');groups.append(h);await start(h);await resolve(h);old=get(h)
        for i in range(4):assert action(h,i,'calls',{'type':'PASS'})[0]==200
        new=get(h)
        check('four opening passes redeal without completing deal',new['phase']=='PRIVILEGE_DECISION' and new['game']['gameId']==old['game']['gameId'] and new['game']['dealId']!=old['game']['dealId'] and new['game']['redealCount']==1 and new['game']['completedDeals']==0)
        check('redeal resets privilege and private hands',new['game']['privilege']=={'status':'PENDING','yourChoice':None,'yourChoiceSource':None,'usedByTeam':None,'canChoose':True} and new['you']['ownHand']!=old['you']['ownHand'] and len(set(sum([cards(get(h,i)['you']['ownHand']) for i in range(4)],[])))==52)
        wrong_path=h['path']+'/games/'+old['game']['gameId']+'/deals/'+old['game']['dealId']+'/privilege'
        check('old deal command cannot mutate redealt hands',api('PUT',wrong_path,h['users'][0]['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':new['revision'],'use':True})[0]==409 and get(h)['game']['dealId']==new['game']['dealId'])
        await resolve(h);rev=get(h)['revision'];current=get(h)['game'];call_path=h['path']+'/games/'+current['gameId']+'/deals/'+current['dealId']+'/calls'
        concurrent=[{'actionId':str(uuid.uuid4()),'expectedRevision':rev,'type':'PASS'},{'actionId':str(uuid.uuid4()),'expectedRevision':rev,'type':'BID','level':1,'strain':'CLUB'}]
        called=await asyncio.gather(*(asyncio.to_thread(api,'POST',call_path,h['users'][0]['token'],body) for body in concurrent))
        check('concurrent calls advance turn exactly once',sorted(s for s,_ in called)==[200,409] and len(get(h)['game']['auction']['calls'])==1 and get(h)['game']['auction']['nextSeat']==1)
        # Active last-socket loss retains same hand, pauses and resumes; multi-tab is one identity.
        p=await setup('game-presence');groups.append(p);await start(p);original=get(p,1);extra=await Client(p['users'][1],p['roomId']).connect();await asyncio.sleep(.2);await p['clients'][1].close();await asyncio.sleep(.2)
        check('active multi-tab preserves live membership without pause',not get(p)['game']['paused'])
        await extra.close();await asyncio.sleep(.2);paused=get(p)
        offline=next(x for x in paused['players'] if x['playerId']==p['users'][1]['playerId'])
        check('active last socket loss pauses and retains ten-second seat',paused['game']['paused'] and len(paused['players'])==4 and offline['ready'] and not offline['connected'] and 9990<=offline['reconnectDeadline']-offline['disconnectedAt']<=10010)
        check('paused game rejects commands',action(p,0,'privilege',{'use':True})[0]==409)
        await asyncio.sleep(2);resumed=await Client(p['users'][1],p['roomId']).connect();p['clients'][1]=resumed;await asyncio.sleep(.2)
        restored=get(p,1)
        check('active reconnect restores identical hand and deal',not restored['game']['paused'] and restored['you']['ownHand']==original['you']['ownHand'] and restored['game']['dealId']==original['game']['dealId'])
        await asyncio.sleep(8.2)
        check('old deadline cannot abort resumed active player',get(p)['phase']=='PRIVILEGE_DECISION' and not get(p)['game']['paused'])
        await p['clients'][1].close();await p['clients'][2].close();await asyncio.sleep(.2);p['clients'][1]=await Client(p['users'][1],p['roomId']).connect();await asyncio.sleep(.2)
        check('one reconnect cannot resume while teammate still offline',get(p)['game']['paused'])
        await asyncio.sleep(10.3);aborted=get(p)
        check('active deadline forfeits without fabricated scores',aborted['phase']=='MATCH_RESULT' and aborted['game']['matchResult']['reason']=='RECONNECT_TIMEOUT' and aborted['game']['completedDealResults']==[] and aborted['game']['teamTotals']=={'NS':0,'EW':0})
        check('timed-out player retains terminal result-view membership',api('GET','/api/me',p['users'][2]['token'])[1]['roomId']==p['roomId'] and get(p,2)['phase']=='MATCH_RESULT')
        check('connected player can leave terminal game for lobby',api('POST',p['path']+'/leave',p['users'][0]['token'],{})[0]==200 and api('GET','/api/me',p['users'][0]['token'])[1]['roomId'] is None)
    finally:
        for g in groups:await cleanup(g)
        Path(__file__).with_name('game-integration-results.json').write_text(json.dumps(RESULTS,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
