"""Compose real native failure detection with active bidding's ten-second grace."""
import asyncio,json,time
from pathlib import Path
import importlib.util
spec=importlib.util.spec_from_file_location('game_checks',Path(__file__).with_name('game-integration.py'))
game=importlib.util.module_from_spec(spec);spec.loader.exec_module(game)
from integration import api,session,Client
from raw_ws import raw_socket
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
async def main():
    g=await game.setup('silent-active');raw=None
    try:
        await game.start(g);await game.resolve(g)
        assert game.action(g,0,'calls',{'type':'BID','level':1,'strain':'CLUB'})[0]==200
        before=game.get(g,1);await g['clients'][1].close();await asyncio.sleep(.2)
        check('bidding disconnect pauses without advancing turn',game.get(g)['game']['paused'] and game.get(g)['game']['auction']['nextSeat']==1)
        g['clients'][1]=await Client(g['users'][1],g['roomId']).connect();await asyncio.sleep(.2)
        restored=game.get(g,1)
        check('bidding reconnect preserves hand calls and turn',not restored['game']['paused'] and restored['you']['ownHand']==before['you']['ownHand'] and restored['game']['auction']==before['game']['auction'])
        await g['clients'][1].close();raw=raw_socket(g['users'][1]['token'],g['roomId']);await asyncio.sleep(9)
        observed=game.get(g,1);p=next(p for p in observed['players'] if p['playerId']==g['users'][1]['playerId'])
        check('native non-Pong detection starts active grace instead of immediate seat loss',observed['phase']=='BIDDING' and observed['game']['paused'] and len(observed['players'])==4 and p['ready'] and not p['connected'] and p['reconnectDeadline'] is not None and observed['you']['ownHand']==before['you']['ownHand'])
        outsider=session('silent-replacement')
        check('active silent-loss seat cannot be replaced during grace',api('POST',g['path']+'/join',outsider['token'],{})[0]==409)
        await asyncio.sleep(10.5);after=game.get(g)
        check('active silent loss forfeits only after subsequent reconnect deadline',after['phase']=='MATCH_RESULT' and after['game']['matchResult']['reason']=='RECONNECT_TIMEOUT' and after['game']['completedDealResults']==[] and after['game']['teamTotals']=={'NS':0,'EW':0} and len(after['players'])==4)
    finally:
        if raw:raw.close()
        await game.cleanup(g)
        Path(__file__).with_name('game-silent-disconnect-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
