"""v0.5 real HTTP/WS trick presentation, interruption, and terminal-result boundaries."""
import asyncio, importlib.util, json, time
from pathlib import Path
from integration import api, Client
spec=importlib.util.spec_from_file_location('play_checks',Path(__file__).with_name('play-integration.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
async def start(g):
    assert p.command(g,0,'start')[0]==200
    await auction(g)
async def auction(g):
    for i in (0,1):assert p.command(g,i,'privilege',{'use':True})[0]==200
    first=p.view(g)['game']['auction']['nextSeat']
    assert p.command(g,first,'calls',{'type':'BID','level':1,'strain':'NO_TRUMP'})[0]==200
    for _ in range(3):assert p.command(g,p.view(g)['game']['auction']['nextSeat'],'calls',{'type':'PASS'})[0]==200
async def play_trick(g):
    r=await p.await_trick_transition(g)
    moves=[]
    for _ in range(4):
        seat=p.view(g)['game']['play']['nextSeat'];r=p.view(g,seat);card=r['you']['legalCards'][0]
        status,response,body,path=p.command(g,seat,'plays',{'card':card});assert status==200,(status,response)
        moves.append({'seat':seat,'card':card,'source':'MANUAL'})
    return moves,seat,body,path
async def regular():
    g=await p.game.setup('trick-pause')
    try:
        await start(g);moves,seat,body,path=await play_trick(g)
        r=p.view(g);play=r['game']['play'];last=play['completedTricks'][-1];rev=r['revision'];budget=r['game']['operationTimer']['remainingMs']
        check('fourth card stays public with completed winner and no next actor',r['phase']=='TRICK_RESULT' and play['currentTrick']==moves and last['plays']==[] and last['cardsVisible'] is False and play['nextSeat'] is None)
        check('all viewers receive same completed trick and no legal cards',all(p.view(g,i)['game']['play']['currentTrick']==moves and not p.view(g,i)['you']['legalCards'] for i in range(4)))
        check('trick presentation has independent two-second timer',r['game']['operationTimer']['kind']=='TRICK_RESULT' and 0<budget<=2000)
        own=p.view(g,last['winnerSeat'])['you']['ownHand'][0]
        status,_=api('POST',path,g['users'][seat]['token'],body)
        check('fourth-card exact replay cannot duplicate trick or change revision',status==200 and p.view(g)['revision']==rev and len(p.view(g)['game']['play']['completedTricks'])==1)
        status,*_=p.command(g,last['winnerSeat'],'plays',{'card':own})
        check('next lead rejected during presentation without mutation',status==409 and p.view(g)['revision']==rev)
        await asyncio.sleep(.2);await g['clients'][2].close();await asyncio.sleep(.15)
        frozen=p.view(g)['game']['operationTimer'];await asyncio.sleep(2.2);held=p.view(g)
        check('disconnect freezes presentation beyond original deadline',held['phase']=='TRICK_RESULT' and held['game']['paused'] and frozen['paused'] and held['game']['operationTimer']['remainingMs']==frozen['remainingMs'] and held['game']['play']['currentTrick']==moves)
        g['clients'][2]=await Client(g['users'][2],g['roomId']).connect();await asyncio.sleep(.1)
        resumed=p.view(g)
        check('reconnect restores four cards and remaining presentation budget',resumed['phase']=='TRICK_RESULT' and not resumed['game']['paused'] and resumed['game']['operationTimer']['remainingMs']<=frozen['remainingMs'] and resumed['game']['play']['currentTrick']==moves)
        nxt=await p.await_trick_transition(g)
        check('winner leads only after presentation with fresh twenty-second budget',nxt['phase']=='PLAYING' and nxt['game']['play']['nextSeat']==last['winnerSeat'] and nxt['game']['play']['currentTrick']==[] and nxt['game']['operationTimer']['kind']=='PLAY_TURN' and nxt['game']['operationTimer']['remainingMs']>19000)
        check('per-seat wins derived from completed tricks sum to team counts',sum(t['winnerSeat']%2==0 for t in nxt['game']['play']['completedTricks'])==nxt['game']['play']['tricksByTeam']['NS'] and sum(t['winnerSeat']%2==1 for t in nxt['game']['play']['completedTricks'])==nxt['game']['play']['tricksByTeam']['EW'])
        moves,*_=await play_trick(g);await g['clients'][0].close()
        end=time.monotonic()+12
        while time.monotonic()<end and p.view(g,1)['phase']!='MATCH_RESULT':await asyncio.sleep(.1)
        r=p.view(g,1)
        check('nonterminal presentation still applies reconnect forfeiture',r['phase']=='MATCH_RESULT' and r['game']['matchResult']['outcome']=='FORFEIT' and r['game']['matchResult']['winnerTeamId']=='EW' and r['game']['completedDeals']==0)
    finally:await p.game.cleanup(g)
async def final_lock():
    g=await p.game.setup('last-trick-lock')
    try:
        await start(g)
        for deal in range(1,4):
            for _ in range(13):moves,seat,body,path=await play_trick(g)
            pause=p.view(g)
            check('deal '+str(deal)+' score commits once while final four cards remain',pause['phase']=='TRICK_RESULT' and pause['game']['completedDeals']==deal and len(pause['game']['completedDealResults'])==deal and pause['game']['play']['currentTrick']==moves)
            if deal<3:
                await p.await_trick_transition(g)
                for i in range(4):assert p.command(g,i,'acknowledge-result')[0]==200
                await auction(g)
        locked=pause['game']['matchResult'];scores=pause['game']['teamTotals'];history=pause['game']['completedDealResults']
        check('third-deal scored outcome locked before presentation ends',locked is not None and locked['outcome']=='SCORED')
        assert api('POST',g['path']+'/leave',g['users'][0]['token'],{})[0]==200
        await g['clients'][1].close();await asyncio.sleep(2.5)
        final=p.view(g,2)
        check('final presentation exit and disconnect cannot rewrite winner or freeze result',final['phase']=='MATCH_RESULT' and final['game']['matchResult']==locked and final['game']['teamTotals']==scores and [{k:v for k,v in x.items() if k!='completedTricks'} for x in final['game']['completedDealResults']]==[{k:v for k,v in x.items() if k!='completedTricks'} for x in history] and all(t['cardsVisible'] and len(t['plays'])==4 for x in final['game']['completedDealResults'] for t in x['completedTricks']) and final['game']['operationTimer'] is None)
        g['clients'][1]=await Client(g['users'][1],g['roomId']).connect();await asyncio.sleep(.2)
        check('terminal reconnect reads unchanged scored result after final presentation',p.view(g,1)['game']['matchResult']==locked)
    finally:await p.game.cleanup(g)
async def main():
    try:await regular();await final_lock()
    finally:Path(__file__).with_name('trick-feedback-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
