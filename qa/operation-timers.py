"""Real loopback v0.2.1 timer authority checks; no clock mocks or shortened budgets."""
import asyncio, importlib.util, json, time
from pathlib import Path
spec=importlib.util.spec_from_file_location('game_checks',Path(__file__).with_name('game-integration.py'))
game=importlib.util.module_from_spec(spec);spec.loader.exec_module(game)
from integration import api, Client
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
def timer(g):return game.get(g)['game']['operationTimer']
async def until(g,predicate,seconds=23):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        r=game.get(g)
        if predicate(r):return r
        await asyncio.sleep(.1)
    raise AssertionError('authoritative timer transition not observed')
async def pause_and_expire():
    g=await game.setup('timer-pause')
    try:
        await game.start(g);t=timer(g);rev=game.get(g)['revision']
        check('privilege starts shared full twenty-second budget',t['kind']=='PRIVILEGE' and t['turnSeat'] is None and 19000<t['remainingMs']<=20000 and not t['paused'])
        await asyncio.sleep(2);r=game.get(g)
        check('reads sample decreasing budget without ticking revision',r['revision']==rev and 16000<r['game']['operationTimer']['remainingMs']<19000)
        status,response,body,path=game.action(g,0,'privilege',{'use':True});assert status==200
        before=timer(g)['remainingMs'];await asyncio.sleep(.5)
        status,replay=api('PUT',path,g['users'][0]['token'],body)
        check('vote and accepted replay never restart shared phase budget',status==200 and replay['outcome']=='REPLAYED' and timer(g)['remainingMs']<before and timer(g)['remainingMs']<19000)
        check('opponent timeout cannot expose pending private vote',game.get(g,1)['game']['privilege']['usedByTeam'] is None and game.get(g,1)['game']['privilege']['yourChoice'] is None)
        await g['clients'][1].close();await g['clients'][2].close();await asyncio.sleep(.2);frozen=timer(g)
        await asyncio.sleep(2);check('first offline freezes remaining operation time',frozen['paused'] and timer(g)['remainingMs']==frozen['remainingMs'])
        g['clients'][1]=await Client(g['users'][1],g['roomId']).connect();await asyncio.sleep(1)
        check('one reconnect cannot resume while another player offline',timer(g)['paused'] and timer(g)['remainingMs']==frozen['remainingMs'])
        g['clients'][2]=await Client(g['users'][2],g['roomId']).connect();await asyncio.sleep(.3)
        resumed=timer(g);check('all-connected resumes remaining budget not fresh twenty seconds',not resumed['paused'] and frozen['remainingMs']-1500<resumed['remainingMs']<frozen['remainingMs'])
        r=await until(g,lambda r:r['phase']=='BIDDING');t=r['game']['operationTimer']
        check('privilege expiry preserves resolved yes and defaults unresolved no',r['game']['privilege']['usedByTeam']=={'NS':True,'EW':False})
        check('vote provenance is viewer-only manual or timeout',game.get(g,0)['game']['privilege']['yourChoiceSource']=='MANUAL' and game.get(g,1)['game']['privilege']['yourChoiceSource']=='TIMEOUT')
        check('expiry begins first bidding turn with full budget',t['kind']=='BID_TURN' and t['turnSeat']==0 and 19000<t['remainingMs']<=20000)
        status,response,body,path=game.action(g,0,'calls',{'type':'BID','level':1,'strain':'CLUB'});assert status==200
        await asyncio.sleep(1);before=timer(g)['remainingMs'];status,replay=api('POST',path,g['users'][0]['token'],body)
        stale=game.action(g,1,'calls',{'type':'PASS'},revision=0)[0]
        check('bid replay and stale command do not reset next turn timer',status==200 and replay['outcome']=='REPLAYED' and stale==409 and timer(g)['remainingMs']<=before and before<19500)
        r=await until(g,lambda r:len(r['game']['auction']['calls'])==2)
        call=r['game']['auction']['calls'][-1]
        check('turn expiry appends one attributed timeout Pass',call['seat']==1 and call['type']=='PASS' and call['source']=='TIMEOUT' and r['game']['auction']['nextSeat']==2)
        check('timeout gives next player a full budget',19000<r['game']['operationTimer']['remainingMs']<=20000)
        assert game.action(g,2,'calls',{'type':'PASS'})[0]==200
        r=await until(g,lambda r:r['phase']=='PLAYING')
        check('manual and timeout Pass combine into play without scoring',r['game']['auction']['result']['level']==1 and [c['source'] for c in r['game']['auction']['calls']]==['MANUAL','TIMEOUT','MANUAL','TIMEOUT'] and r['game']['completedDeals']==0 and r['game']['operationTimer']['kind']=='PLAY_TURN')
        check('timed transitions preserve viewer hand privacy in every WS snapshot',all(game.private_valid(s,c.u) for c in g['clients'] for s in c.snaps))
    finally:await game.cleanup(g)
async def all_idle():
    g=await game.setup('timer-idle')
    try:
        await game.start(g);old=game.get(g)
        r=await until(g,lambda r:r['phase']=='BIDDING')
        check('all absent privilege votes default both teams unused',r['game']['privilege']['usedByTeam']=={'NS':False,'EW':False})
        for count in range(1,4):
            r=await until(g,lambda r:len(r['game']['auction']['calls'])==count)
            check('idle timeout Pass advances exactly once at turn '+str(count),r['game']['auction']['nextSeat']==count and len(r['game']['auction']['calls'])==count and all(c['type']=='PASS' and c['source']=='TIMEOUT' for c in r['game']['auction']['calls']) and r['game']['operationTimer']['remainingMs']>19000)
        r=await until(g,lambda r:r['game']['dealId']!=old['game']['dealId'])
        check('four automatic opening passes redeal unscored with fresh privilege timer',r['phase']=='PRIVILEGE_DECISION' and r['game']['redealCount']==1 and r['game']['completedDeals']==0 and r['game']['operationTimer']['kind']=='PRIVILEGE' and r['game']['operationTimer']['remainingMs']>19000 and r['game']['privilege']['usedByTeam'] is None and r['game']['auction']['calls']==[])
        check('idle redeal preserves membership but changes private deal',len(r['players'])==4 and all(p['connected'] for p in r['players']) and r['you']['ownHand']!=old['you']['ownHand'])
    finally:await game.cleanup(g)
async def main():
    try:await asyncio.gather(pause_and_expire(),all_idle())
    finally:Path(__file__).with_name('operation-timers-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
