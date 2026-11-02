"""Real individual reconnect deadlines and immutable final adjudication, v0.3."""
import asyncio,importlib.util,json,time
from pathlib import Path
from integration import api,Client
spec=importlib.util.spec_from_file_location('play_checks',Path(__file__).with_name('play-integration.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
async def until(g,phase,seconds=12,seat=2):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        r=p.view(g,seat)
        if r['phase']==phase:return r
        await asyncio.sleep(.05)
    raise AssertionError('phase deadline '+phase)
async def scenario(mode):
    g=await p.game.setup('forfeit-'+mode)
    try:
        assert p.command(g,0,'start')[0]==200
        initial=p.view(g)
        if mode=='manual':
            before=time.monotonic();assert api('POST',g['path']+'/leave',g['users'][0]['token'],{})[0]==200
            r=p.view(g,1)
            check('manual exit immediately forfeits correct team without unfinished score',time.monotonic()-before<1 and r['phase']=='MATCH_RESULT' and r['game']['matchResult']=={'outcome':'FORFEIT','winnerTeamId':'EW','reason':'MANUAL_EXIT','failedTeamIds':['NS']} and r['game']['completedDeals']==0 and r['game']['teamTotals']=={'NS':0,'EW':0})
            return
        await g['clients'][0].close();await asyncio.sleep(3)
        await g['clients'][1].close()
        first=await until(g,'FORFEIT_PENDING')
        check(mode+': first expiry waits only existing opponent grace',first['game']['pendingForfeit']['failedTeamIds']==['NS'] and first['game']['pendingForfeit']['waitingSeats']==[1] and first['game']['operationTimer'] is None and first['game']['matchResult'] is None)
        check(mode+': pending rejects further game commands',p.command(g,2,'privilege',{'use':True})[0]==409)
        if mode=='recover':
            g['clients'][0]=await Client(g['users'][0],g['roomId']).connect();await asyncio.sleep(.1)
            check('late return cannot erase failed team',p.view(g,2)['game']['pendingForfeit']['failedTeamIds']==['NS'])
            g['clients'][1]=await Client(g['users'][1],g['roomId']).connect()
        elif mode=='new-loss':
            await g['clients'][3].close();await asyncio.sleep(.2)
            check('new opposing disconnect cannot enlarge captured pending window',p.view(g,2)['game']['pendingForfeit']['waitingSeats']==[1])
            g['clients'][1]=await Client(g['users'][1],g['roomId']).connect()
        elif mode=='manual-pending':
            assert api('POST',g['path']+'/leave',g['users'][1]['token'],{})[0]==200
        terminal=await until(g,'MATCH_RESULT',seconds=5)
        result=terminal['game']['matchResult'];both=mode in ('both-expire','manual-pending')
        check(mode+': fair deadline produces correct final outcome',result['outcome']==('NO_CONTEST' if both else 'FORFEIT') and result['winnerTeamId']==(None if both else 'EW') and set(result['failedTeamIds'])==({'NS','EW'} if both else {'NS'}))
        check(mode+': forfeiture attribution identifies actual trigger players',all(e['playerId']==g['users'][e['seat']]['playerId'] and e['cause'] in ('MANUAL_EXIT','RECONNECT_TIMEOUT') for e in terminal['game']['forfeitEvents']) and bool(terminal['game']['forfeitEvents']))
        check(mode+': unfinished deal never receives fabricated score',terminal['game']['completedDealResults']==[] and terminal['game']['teamTotals']=={'NS':0,'EW':0} and terminal['game']['operationTimer'] is None)
        assert api('POST',g['path']+'/leave',g['users'][2]['token'],{})[0]==200
        # Seat3 remains authorized even after terminal socket loss; result/roster immutable.
        await g['clients'][3].close();await asyncio.sleep(.1)
        r=p.view(g,3)
        check(mode+': terminal departure cannot rewrite result or frozen roster',r['game']['matchResult']==result and r['game']['roster']==initial['game']['roster'])
    finally:await p.game.cleanup(g)
async def main():
    try:await asyncio.gather(*(scenario(mode) for mode in ('manual','recover','new-loss','manual-pending','both-expire')))
    finally:Path(__file__).with_name('forfeit-integration-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
