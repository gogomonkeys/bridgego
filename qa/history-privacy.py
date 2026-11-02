"""v0.6 independent public-projection and per-deal auction history boundaries."""
import asyncio, importlib.util, json, time
from pathlib import Path
from integration import api, Client
spec=importlib.util.spec_from_file_location('trick_setup',Path(__file__).with_name('trick-feedback.py'))
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t);p=t.p
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
def hidden(ts):return all(x['cardsVisible'] is False and x['plays']==[] for x in ts)
def visible(ts):return all(x['cardsVisible'] is True and len(x['plays'])==4 for x in ts)
def projection(r):
    g=r['game']
    if not g:return True
    review=r['phase'] in ('DEAL_RESULT','MATCH_RESULT') and g['completedDeals']==g['dealIndex']
    if g['play']:
        if not (visible(g['play']['completedTricks']) if review else hidden(g['play']['completedTricks'])):return False
    for deal in g['completedDealResults']:
        can=deal['dealId']!=g['dealId'] or review
        if not (visible(deal['completedTricks']) if can else hidden(deal['completedTricks'])):return False
    if r['lastMatch'] and r['lastMatch']['gameId']==g['gameId']:
        for deal in r['lastMatch']['completedDealResults']:
            can=deal['dealId']!=g['dealId'] or review
            if not (visible(deal['completedTricks']) if can else hidden(deal['completedTricks'])):return False
    return True
async def until(g,predicate,seconds=5):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        r=p.view(g)
        if predicate(r):return r
        await asyncio.sleep(.05)
    raise AssertionError('state condition timed out')
async def complete():
    g=await p.game.setup('history-review'); observed={}
    try:
        assert p.command(g,0,'start')[0]==200
        # Real automatic Pass survives an all-Pass redeal and later completed-deal archive.
        for i in (0,1):assert p.command(g,i,'privilege',{'use':True})[0]==200
        initial=p.view(g)['game']['dealId']
        await until(g,lambda r:len(r['game']['auction']['calls'])==1,23)
        first=p.view(g)['game']['auction']['calls'][0]
        assert first['type']=='PASS' and first['source']=='TIMEOUT'
        for _ in range(3):assert p.command(g,p.view(g)['game']['auction']['nextSeat'],'calls',{'type':'PASS'})[0]==200
        r=p.view(g);rounds=r['game']['auctionRounds'];old=rounds[0]
        check('all-Pass redeal retains immutable four-call round including timeout source',r['phase']=='PRIVILEGE_DECISION' and r['game']['dealId']!=initial and r['game']['redealCount']==1 and old['dealId']==initial and old['status']=='ALL_PASS' and len(old['calls'])==4 and old['calls'][0]==first and rounds[-1]['status']=='IN_PROGRESS')
        check('auction rounds do not expose private votes or card data',p.game.cards(rounds)==[] and all(set(x)=={'dealId','dealIndex','redealCount','dealerSeat','calls','result','status'} for x in rounds))
        for deal in range(1,4):
            await t.auction(g)
            current_id=p.view(g)['game']['dealId'];observed[current_id]=[]
            calls=p.view(g)['game']['auction']['calls']
            check('deal '+str(deal)+' contract round stores complete public auction',len(calls)==4 and p.view(g)['game']['auctionRounds'][-1]['status']=='CONTRACT' and p.view(g)['game']['auctionRounds'][-1]['calls']==calls)
            for number in range(1,14):
                moves,seat,body,path=await t.play_trick(g);observed[current_id].append(moves)
                snapshots=[p.view(g,i) for i in range(4)]
                assert all(projection(r) for r in snapshots)
                assert all(r['game']['play']['currentTrick']==moves for r in snapshots)
                if number==1:
                    check('deal '+str(deal)+' all four viewers get face-down collected history while current cards remain public',all(hidden(r['game']['play']['completedTricks']) for r in snapshots))
                    if deal==1:
                        await p.await_trick_transition(g)
                        status,replay=api('POST',path,g['users'][seat]['token'],body)
                        check('accepted old play replay returns current redacted history not old card snapshot',status==200 and replay['outcome']=='REPLAYED' and replay['room']['game']['play']['currentTrick']==[] and hidden(replay['room']['game']['play']['completedTricks']))
                        await g['clients'][2].close();await asyncio.sleep(.15)
                        g['clients'][2]=await Client(g['users'][2],g['roomId']).connect();await asyncio.sleep(.15)
                        check('reconnect restores counts without retrospective card faces',g['clients'][2].snaps and hidden(g['clients'][2].snaps[-1]['game']['play']['completedTricks']) and g['clients'][2].snaps[-1]['game']['play']['currentTrick']==[])
                if number==13:
                    pause=p.view(g)
                    check('deal '+str(deal)+' score commitment does not unlock card history during final presentation',pause['phase']=='TRICK_RESULT' and pause['game']['completedDeals']==deal and hidden(pause['game']['completedDealResults'][-1]['completedTricks']))
                    if deal==3:
                        status,archive=api('GET',g['path']+'/last-match',g['users'][0]['token'])
                        check('early terminal archive cannot bypass final presentation review gate',status==200 and hidden(archive['completedDealResults'][-1]['completedTricks']) and all(visible(x['completedTricks']) for x in archive['completedDealResults'][:-1]))
            settled=await p.await_trick_transition(g)
            result=settled['game']['completedDealResults'][-1]
            check('deal '+str(deal)+' settlement reveals exactly observed live cards for every viewer',all(projection(p.view(g,i)) and visible(p.view(g,i)['game']['completedDealResults'][-1]['completedTricks']) for i in range(4)) and [x['plays'] for x in result['completedTricks']]==observed[current_id])
            expected_rounds=2 if deal==1 else 1
            check('deal '+str(deal)+' settlement preserves its auction and redeal boundaries',len(result['auctionRounds'])==expected_rounds and result['auctionRounds'][-1]['calls']==calls and all(x['dealIndex']==deal for x in result['auctionRounds']))
            if deal<3:
                for i in range(4):assert p.command(g,i,'acknowledge-result')[0]==200
                next_=p.view(g)
                check('deal '+str(deal)+' review remains available during next deal without exposing new cards',visible(next_['game']['completedDealResults'][-1]['completedTricks']) and next_['game']['auctionRounds'][0]==old and all(p.game.private_valid(p.view(g,i),g['users'][i]) for i in range(4)))
        await asyncio.sleep(.15)
        check('all captured WebSocket snapshots obey phase-specific redaction for all identities',all(projection(r) for c in g['clients'] for r in c.snaps))
        status,archive=api('GET',g['path']+'/last-match',g['users'][0]['token'])
        check('final archive refresh reveals settled deals and retains all auction rounds',status==200 and all(visible(x['completedTricks']) for x in archive['completedDealResults']) and len(archive['auctionRounds'])==4 and archive['auctionRounds'][0]==old)
    finally:await p.game.cleanup(g)
async def unfinished():
    g=await p.game.setup('hidden-forfeit')
    try:
        await t.start(g)
        await t.play_trick(g);await p.await_trick_transition(g)
        assert api('POST',g['path']+'/leave',g['users'][0]['token'],{})[0]==200
        r=p.view(g,1);status,archive=api('GET',g['path']+'/last-match',g['users'][1]['token'])
        check('incomplete forfeit never unlocks collected cards or archives unplayed hands',r['phase']=='MATCH_RESULT' and r['game']['completedDeals']==0 and hidden(r['game']['play']['completedTricks']) and status==200 and p.game.cards(archive)==[] and archive['completedDealResults']==[])
        check('incomplete terminal archive still retains public auction without private privilege votes',len(archive['auctionRounds'])==1 and len(archive['auctionRounds'][0]['calls'])==4 and p.game.cards(archive['auctionRounds'])==[])
    finally:await p.game.cleanup(g)
async def main():
    try:await complete();await unfinished()
    finally:Path(__file__).with_name('history-privacy-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
