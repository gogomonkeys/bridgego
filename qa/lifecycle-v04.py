"""Real v0.4 settlement, reset, host and archive boundaries. Synthetic loopback only."""
import asyncio,importlib.util,json,time,uuid
from pathlib import Path
from integration import api,Client,session
spec=importlib.util.spec_from_file_location('play_checks',Path(__file__).with_name('play-integration.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
async def result_room(label):
    g=await p.game.setup(label);assert p.command(g,0,'start')[0]==200
    for i in (0,1):assert p.command(g,i,'privilege',{'use':True})[0]==200
    assert p.command(g,0,'calls',{'type':'BID','level':1,'strain':'NO_TRUMP'})[0]==200
    for i in (1,2,3):assert p.command(g,i,'calls',{'type':'PASS'})[0]==200
    for _ in range(52):
        state=await p.await_trick_transition(g);seat=state['game']['play']['nextSeat'];r=p.view(g,seat)
        assert p.command(g,seat,'plays',{'card':r['you']['legalCards'][0]})[0]==200
        await asyncio.sleep(.003)
    await p.await_trick_transition(g)
    return g
async def until(g,phase,seconds=23,seat=0):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        r=p.view(g,seat)
        if r['phase']==phase:return r
        await asyncio.sleep(.05)
    raise AssertionError('expected '+phase)
async def settlement(mode):
    g=await result_room('result-'+mode)
    try:
        original=p.view(g);deal=original['game']['dealId'];prior=original['game']['completedDealResults']
        check(mode+': completed deal holds empty hands without premature next deal',original['phase']=='DEAL_RESULT' and original['game']['completedDeals']==1 and original['game']['operationTimer']['kind']=='DEAL_RESULT' and all(p.view(g,i)['you']['ownHand']==[] for i in range(4)))
        check(mode+': settlement starts an authoritative ninety-second budget',89000 < original['game']['operationTimer']['remainingMs'] <= 90000)
        if mode=='all-ack':
            outsider=session('ack-outsider');r=p.view(g);path=g['path']+'/games/'+r['game']['gameId']+'/deals/'+deal+'/acknowledge-result'
            check('outsider cannot acknowledge for a player',api('POST',path,outsider['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':r['revision'],'seat':0})[0]==403)
            status,_,body,path=p.command(g,0,'acknowledge-result',{'seat':3,'playerId':g['users'][3]['playerId']});assert status==200
            check('acknowledgment identity ignores forged seat',p.view(g)['game']['resultAcknowledgedSeats']==[0] and not p.view(g,0)['you']['canAcknowledgeResult'] and p.view(g,3)['you']['canAcknowledgeResult'])
            await asyncio.sleep(.4);before=p.view(g)['game']['operationTimer']['remainingMs']
            assert api('POST',path,g['users'][0]['token'],body)[0]==200
            assert p.command(g,0,'acknowledge-result')[0]==200
            check('duplicate acknowledgments cannot count twice or reset timer',p.view(g)['game']['resultAcknowledgedSeats']==[0] and p.view(g)['game']['operationTimer']['remainingMs']<=before)
            assert p.command(g,1,'acknowledge-result')[0]==200
            rev=p.view(g)['revision'];bodies=[{'actionId':str(uuid.uuid4()),'expectedRevision':rev} for _ in (2,3)]
            out=await asyncio.gather(*(asyncio.to_thread(api,'POST',path,g['users'][i]['token'],bodies[i-2]) for i in (2,3)))
            check('concurrent final acknowledgments serialize with stale revision protection',sorted(s for s,_ in out)==[200,409] and p.view(g)['phase']=='DEAL_RESULT')
            loser=2 if out[0][0]!=200 else 3;assert p.command(g,loser,'acknowledge-result')[0]==200
            advanced=p.view(g)
            check('four acknowledgments advance once before deadline',advanced['phase']=='PRIVILEGE_DECISION' and advanced['game']['dealId']!=deal and advanced['game']['dealIndex']==2 and advanced['game']['dealerSeat']==1 and advanced['game']['operationTimer']['remainingMs']>19000)
            rev=advanced['revision'];status,replay=api('POST',path,g['users'][0]['token'],body)
            check('old accepted ack replays current state without redealing',status==200 and replay['outcome']=='REPLAYED' and p.view(g)['revision']==rev and p.view(g)['game']['dealId']==advanced['game']['dealId'])
        elif mode=='timeout':
            assert p.command(g,0,'acknowledge-result')[0]==200
            await asyncio.sleep(2);await g['clients'][2].close();await asyncio.sleep(.2);frozen=p.view(g)['game']['operationTimer']
            await asyncio.sleep(2)
            check('result disconnect freezes shared budget and blocks confirmation',frozen['paused'] and p.view(g)['game']['operationTimer']['remainingMs']==frozen['remainingMs'] and p.command(g,1,'acknowledge-result')[0]==409)
            g['clients'][2]=await Client(g['users'][2],g['roomId']).connect();await asyncio.sleep(.2)
            check('result reconnect resumes same deal and remaining timer',p.view(g)['game']['dealId']==deal and p.view(g)['game']['operationTimer']['remainingMs']<frozen['remainingMs'] and p.view(g)['game']['resultAcknowledgedSeats']==[0])
            await asyncio.sleep(20)
            waiting=p.view(g)
            check('old twenty-second result deadline no longer advances the deal',waiting['phase']=='DEAL_RESULT' and waiting['game']['dealId']==deal and waiting['game']['operationTimer']['remainingMs']>60000)
            advanced=await until(g,'PRIVILEGE_DECISION',seconds=93)
            check('natural result timeout deals once without all acknowledgments',advanced['game']['dealId']!=deal and advanced['game']['dealIndex']==2 and advanced['game']['resultAcknowledgedSeats']==[] and advanced['game']['operationTimer']['kind']=='PRIVILEGE' and advanced['game']['operationTimer']['remainingMs']>19000)
        else:
            await g['clients'][0].close();final=await until(g,'MATCH_RESULT',seconds=12,seat=1)
            check('result-phase grace expiry forfeits without next deal',final['game']['dealId']==deal and final['game']['matchResult']['outcome']=='FORFEIT' and final['game']['matchResult']['winnerTeamId']=='EW' and final['game']['completedDealResults']==prior and final['game']['completedDeals']==1)
    finally:await p.game.cleanup(g)
def forbidden(value):
    bad={'ownHand','legalCards','hands','votes','yourChoice','yourChoiceSource','token','actionId','actions'}
    if isinstance(value,dict):return bool(set(value)&bad) or any(forbidden(v) for v in value.values())
    if isinstance(value,list):return any(forbidden(v) for v in value)
    return False
async def reset_archive():
    g=await p.game.setup('reset');extra=[]
    try:
        assert p.command(g,0,'start')[0]==200;old=p.view(g);oldgame=old['game']['gameId'];olddeal=old['game']['dealId']
        assert p.command(g,0,'privilege',{'use':True})[0]==200
        assert api('POST',g['path']+'/leave',g['users'][0]['token'],{})[0]==200
        r=p.view(g,1);archive=r['lastMatch'];summary=next(x for x in api('GET','/api/rooms',g['users'][1]['token'])[1] if x['roomId']==g['roomId'])
        check('terminal summary is FINISHED and unjoinable',summary['state']=='FINISHED' and summary['phase']=='MATCH_RESULT' and not summary['joinable'])
        check('terminal departure transfers host to online seat',r['hostPlayerId']==g['users'][1]['playerId'] and r['canRematch'])
        check('archive excludes unfinished cards private votes and action data',archive is not None and not forbidden(archive) and p.game.cards(archive)==[] and archive['gameId']==oldgame)
        check('original departed identity can read last archive',api('GET',g['path']+'/last-match',g['users'][0]['token'])==(200,archive))
        for i in (1,2,3):await g['clients'][i].close();await asyncio.sleep(.1)
        check('all-offline terminal has no host but retains result',p.view(g,2)['hostPlayerId'] is None and p.view(g,2)['game']['matchResult']==r['game']['matchResult'])
        for i in (2,3):g['clients'][i]=await Client(g['users'][i],g['roomId']).connect();await asyncio.sleep(.1)
        check('first returning terminal participant becomes host',p.view(g,2)['hostPlayerId']==g['users'][2]['playerId'])
        path=g['path']+'/games/'+oldgame+'/rematch';rev=p.view(g,2)['revision']
        check('nonhost cannot reset finished room',api('POST',path,g['users'][3]['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':rev})[0]==403)
        bodies=[{'actionId':str(uuid.uuid4()),'expectedRevision':rev} for _ in range(2)]
        out=await asyncio.gather(*(asyncio.to_thread(api,'POST',path,g['users'][2]['token'],body) for body in bodies))
        check('concurrent host reset occurs exactly once',sorted(s for s,_ in out)==[200,409])
        winning=bodies[next(i for i,x in enumerate(out) if x[0]==200)];waiting=p.view(g,2)
        check('rematch retains only online seats clears ready and creates no game',waiting['phase']=='WAITING' and waiting['game'] is None and {x['seat'] for x in waiting['players']}=={2,3} and all(not x['ready'] for x in waiting['players']) and waiting['revision']>rev)
        check('rematch releases offline membership but preserves archive permission',api('GET','/api/me',g['users'][1]['token'])[1]['roomId'] is None and api('GET',g['path']+'/last-match',g['users'][1]['token'])==(200,archive))
        stale=await Client(g['users'][1],g['roomId']).connect();await asyncio.sleep(.1)
        check('old offline socket cannot restore released membership',not stale.snaps and bool(stale.errors));await stale.close()
        newcomers=[session('new-seat-'+str(i)) for i in range(2)]
        for u in newcomers:
            status,snap=api('POST',g['path']+'/join',u['token'],{});assert status==200
            c=await Client(u,g['roomId']).connect();extra.append(c);await asyncio.sleep(.1)
            check('new identity in reused seat cannot see old archive',snap['lastMatch'] is None and api('GET',g['path']+'/last-match',u['token'])[0]==403 and all(s['lastMatch'] is None for s in c.snaps))
        for u in newcomers+[g['users'][2],g['users'][3]]:assert api('PUT',g['path']+'/ready',u['token'],{'ready':True})[0]==200
        assert p.command(g,2,'start')[0]==200;new=p.view(g,2)
        check('new match receives distinct game ID and clean counters',new['game']['gameId']!=oldgame and new['game']['completedDeals']==0 and new['game']['teamTotals']=={'NS':0,'EW':0})
        revision=new['revision'];status,replay=api('POST',path,g['users'][2]['token'],winning)
        check('old successful rematch retry cannot reset new match',status==200 and replay['outcome']=='REPLAYED' and p.view(g,2)['revision']==revision and p.view(g,2)['game']['gameId']==new['game']['gameId'])
        stale_path=g['path']+'/games/'+oldgame+'/deals/'+olddeal+'/privilege'
        check('fresh old-game action cannot mutate new match',api('PUT',stale_path,g['users'][2]['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':revision,'use':True})[0]==409 and p.view(g,2)['revision']==revision)
        # A new terminal replaces the archive; all explicit leaves remove the room entirely.
        for u in newcomers+[g['users'][2],g['users'][3]]:assert api('POST',g['path']+'/leave',u['token'],{})[0]==200
        check('all explicit leaves delete room and its archive',api('GET',g['path']+'/last-match',g['users'][0]['token'])[0]==404 and not any(x['roomId']==g['roomId'] for x in api('GET','/api/rooms',g['users'][0]['token'])[1]))
    finally:
        for c in extra:await c.close()
        await p.game.cleanup(g)
async def main():
    try:await asyncio.gather(settlement('all-ack'),settlement('timeout'),settlement('grace'),reset_archive())
    finally:Path(__file__).with_name('lifecycle-v04-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
