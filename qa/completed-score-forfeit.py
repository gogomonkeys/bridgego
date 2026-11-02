"""Real completed-score retention when the points-leading team forfeits next deal."""
import asyncio,importlib.util,json,time
from pathlib import Path
from integration import api
from play_oracle import legal_cards
spec=importlib.util.spec_from_file_location('play_checks',Path(__file__).with_name('play-integration.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
async def start_play(g,level):
    for i in (0,1):assert p.command(g,i,'privilege',{'use':True})[0]==200
    caller=p.view(g)['game']['auction']['nextSeat']
    assert p.command(g,caller,'calls',{'type':'BID','level':level,'strain':'NO_TRUMP'})[0]==200
    for _ in range(3):assert p.command(g,p.view(g)['game']['auction']['nextSeat'],'calls',{'type':'PASS'})[0]==200
async def run(mode):
    g=await p.game.setup('completed-forfeit-'+mode)
    try:
        assert p.command(g,0,'start')[0]==200;await start_play(g,7)
        for _ in range(52):
            r=await p.await_trick_transition(g);seat=r['game']['play']['nextSeat'];own=p.view(g,seat)
            legal=legal_cards(own['you']['ownHand'],r['game']['play']['currentTrick'])
            assert p.command(g,seat,'plays',{'card':legal[0]})[0]==200
            await asyncio.sleep(.005)
        finished=await p.await_trick_transition(g);totals=finished['game']['teamTotals'];prior=finished['game']['completedDealResults']
        check(mode+': real first deal creates nonzero complete score',len(prior)==1 and totals['NS']!=totals['EW'] and finished['phase']=='DEAL_RESULT')
        for i in range(4):assert p.command(g,i,'acknowledge-result')[0]==200
        leading=max(totals,key=totals.get);seat=0 if leading=='NS' else 1;observer=1 if seat==0 else 0
        await start_play(g,1);r=p.view(g);turn=r['game']['play']['nextSeat'];own=p.view(g,turn)
        assert p.command(g,turn,'plays',{'card':own['you']['legalCards'][0]})[0]==200
        check(mode+': second deal genuinely partially played',len(p.view(g)['game']['play']['currentTrick'])==1 and p.view(g)['game']['completedDeals']==1)
        if mode=='manual':assert api('POST',g['path']+'/leave',g['users'][seat]['token'],{})[0]==200
        else:await g['clients'][seat].close()
        end=time.monotonic()+12
        while time.monotonic()<end and p.view(g,observer)['phase']!='MATCH_RESULT':await asyncio.sleep(.1)
        final=p.view(g,observer);result=final['game']['matchResult']
        check(mode+': points lead cannot override forfeiture',final['phase']=='MATCH_RESULT' and result['outcome']=='FORFEIT' and result['winnerTeamId']!=leading and result['failedTeamIds']==[leading])
        check(mode+': every completed score component retained and partial deal unscored',final['game']['completedDealResults']==prior and final['game']['teamTotals']==totals and final['game']['completedDeals']==1)
        check(mode+': final attribution records real quitter or expired player',{'playerId':g['users'][seat]['playerId'],'seat':seat,'cause':'MANUAL_EXIT' if mode=='manual' else 'RECONNECT_TIMEOUT'} in final['game']['forfeitEvents'])
    finally:await p.game.cleanup(g)
async def main():
    try:await asyncio.gather(run('manual'),run('timeout'))
    finally:Path(__file__).with_name('completed-score-forfeit-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
