"""Draft v0.3 real HTTP/WS play acceptance. Run only after final contract validation."""
import asyncio, importlib.util, json, uuid, time, os
from pathlib import Path
from integration import api,Client
from play_oracle import winner,legal_cards,score,card_key
spec=importlib.util.spec_from_file_location('game_setup',Path(__file__).with_name('game-integration.py'))
game=importlib.util.module_from_spec(spec);spec.loader.exec_module(game)
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
def view(g,seat=0):
    status,r=api('GET',g['path'],g['users'][seat]['token']);assert status==200,(status,r);return r
def command(g,seat,kind,payload=None,revision=None,action_id=None,deal=None):
    r=view(g,seat);body={'actionId':action_id or str(uuid.uuid4()),'expectedRevision':r['revision'] if revision is None else revision,**(payload or {})}
    path=g['path']+'/start' if kind=='start' else g['path']+'/games/'+r['game']['gameId']+'/deals/'+(deal or r['game']['dealId'])+'/'+kind
    status,response=api('PUT' if kind=='privilege' else 'POST',path,g['users'][seat]['token'],body)
    return status,response,body,path
async def await_trick_transition(g,seconds=5):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        r=view(g)
        if r['phase']!='TRICK_RESULT':return r
        await asyncio.sleep(.05)
    raise AssertionError('trick presentation did not advance')
async def deal_to_play(g,deal_number):
    r=view(g);assert r['phase']=='PRIVILEGE_DECISION'
    # Mixed privilege flags exercise independent score adjustments.
    use_ns=deal_number!=2;use_ew=deal_number==2
    for seat,use in ((0,use_ns),(2,use_ns),(1,use_ew),(3,use_ew)):
        r=view(g,seat)
        if r['game']['privilege']['canChoose']:assert command(g,seat,'privilege',{'use':use})[0]==200
    r=view(g);assert r['phase']=='BIDDING';first=r['game']['auction']['nextSeat']
    strain=('CLUB','NO_TRUMP','SPADE')[deal_number-1]
    assert command(g,first,'calls',{'type':'BID','level':1,'strain':strain})[0]==200
    for _ in range(3):
        r=view(g);assert command(g,r['game']['auction']['nextSeat'],'calls',{'type':'PASS'})[0]==200
    r=view(g);check('deal '+str(deal_number)+' starts play at last bidder left',r['phase']=='PLAYING' and r['game']['play']['nextSeat']==(first+1)%4)
    return r,strain,use_ns,use_ew
async def main():
    g=await game.setup('three-deal');
    try:
        assert command(g,0,'start')[0]==200
        cumulative={'NS':0,'EW':0};initial_hands={}
        for deal_number in range(1,4):
            start,strain,use_ns,use_ew=await deal_to_play(g,deal_number)
            deal_id=start['game']['dealId'];contract=start['game']['auction']['result'];hands={i:view(g,i)['you']['ownHand'][:] for i in range(4)}
            initial_hands[deal_id]={i:h[:] for i,h in hands.items()}
            check('deal '+str(deal_number)+' has fifty-two unique private cards',all(len(h)==13 for h in hands.values()) and len({(c['suit'],c['rank']) for h in hands.values() for c in h})==52)
            counts={'NS':0,'EW':0};next_seat=start['game']['play']['nextSeat'];played=[]
            for trick_index in range(13):
                trick=[];leader=next_seat
                for offset in range(4):
                    seat=(leader+offset)%4;r=view(g,seat);legal=legal_cards(hands[seat],trick)
                    assert r['game']['play']['nextSeat']==seat
                    assert sorted(r['you']['ownHand'],key=card_key)==sorted(hands[seat],key=card_key)
                    assert sorted(r['you']['legalCards'],key=card_key)==legal
                    chosen=legal[0]
                    old_revision=r['revision'];status,response,body,path=command(g,seat,'plays',{'card':chosen});assert status==200,(status,response)
                    await asyncio.sleep(.01)
                    hands[seat].remove(chosen);trick.append({'seat':seat,'card':chosen,'source':'MANUAL'});played.append(chosen)
                    if trick_index in (0,12) and offset==3:
                        status,replay=api('POST',path,g['users'][seat]['token'],body)
                        assert status==200 and replay['outcome']=='REPLAYED'
                        assert view(g)['revision']==response['room']['revision'],'replay mutated revision'
                    if trick_index==0 and offset==0:
                        before=view(g)['revision'];status,_,_,_=command(g,(seat+1)%4,'plays',{'card':hands[(seat+1)%4][0]},revision=old_revision)
                        check('deal '+str(deal_number)+' stale play cannot advance trick',status==409 and view(g)['revision']==before)
                won=winner(trick,strain);counts['NS' if won%2==0 else 'EW']+=1;next_seat=won
                after=view(g)
                assert after['phase']=='TRICK_RESULT' and after['game']['play']['nextSeat'] is None
                assert after['game']['play']['currentTrick']==trick
                assert after['game']['play']['completedTricks'][-1]['winnerSeat']==won
                assert all(t['cardsVisible'] is False and t['plays']==[] for t in after['game']['play']['completedTricks'])
                assert after['game']['operationTimer']['kind']=='TRICK_RESULT'
                assert all(view(g,i)['you']['legalCards']==[] for i in range(4))
                if trick_index==12:
                    check('deal '+str(deal_number)+' final four cards visible before settlement',len(after['game']['play']['currentTrick'])==4 and after['game']['completedDeals']==deal_number)
                after=await await_trick_transition(g)
                if trick_index<12:
                    completed=after['game']['play']['completedTricks'][-1]
                    assert completed['winnerSeat']==won and after['game']['play']['nextSeat']==won
                    assert all(t['cardsVisible'] is False and t['plays']==[] for t in after['game']['play']['completedTricks'])
                else:
                    check('deal '+str(deal_number)+' settles and advances exactly once',len(after['game']['completedDealResults'])==deal_number and after['game']['completedDeals']==deal_number and after['phase']==('MATCH_RESULT' if deal_number==3 else 'DEAL_RESULT'))
            check('deal '+str(deal_number)+' conserves thirteen tricks and fifty-two plays',sum(counts.values())==13 and all(not h for h in hands.values()) and len({(c['suit'],c['rank']) for c in played})==52)
            # Independently verify every immutable settlement component, not just aggregate totals.
            attack_team=contract['teamId'];defence_team='EW' if attack_team=='NS' else 'NS';priv={'NS':use_ns,'EW':use_ew}
            expected=score(contract['level'],counts[attack_team],priv[attack_team],priv[defence_team])
            settled=view(g)['game']['completedDealResults'][-1]
            check('deal '+str(deal_number)+' score breakdown and trick history match oracle',settled['dealId']==deal_id and settled['tricksByTeam']==counts and settled['scores'][attack_team]==expected['attack'] and settled['scores'][defence_team]==expected['defence'] and len(settled['completedTricks'])==13 and all(t['cardsVisible'] and len(t['plays'])==4 for t in settled['completedTricks']))
            cumulative[attack_team]+=expected['attack']['total'];cumulative[defence_team]+=expected['defence']['total']
            check('deal '+str(deal_number)+' cumulative custom score matches independent oracle',view(g)['game']['teamTotals']==cumulative)
            if deal_number<3:
                check('settlement blocks new hands until confirmation',view(g)['game']['dealId']==deal_id and view(g)['game']['operationTimer']['kind']=='DEAL_RESULT' and all(view(g,i)['you']['ownHand']==[] for i in range(4)))
                for i in range(4):assert command(g,i,'acknowledge-result')[0]==200
                revision=view(g)['revision'];old_command={**body,'actionId':str(uuid.uuid4()),'expectedRevision':revision}
                check('old deal fresh play cannot affect next deal',api('POST',path,g['users'][seat]['token'],old_command)[0]==409 and view(g)['revision']==revision)
                check('four acknowledgments begin next deal with fresh privilege budget',view(g)['game']['dealId']!=deal_id and view(g)['game']['operationTimer']['kind']=='PRIVILEGE' and view(g)['game']['operationTimer']['remainingMs']>18000)
        await asyncio.sleep(.3)
        for seat,c in enumerate(g['clients']):
            observed={}
            for snap in c.snaps:
                if not snap['game'] or snap['game']['dealId'] not in initial_hands:continue
                own=initial_hands[snap['game']['dealId']][seat][:]
                public=snap['game']['play']
                if public:
                    # Reconstruct from cards legitimately observed live, never hidden history.
                    seen=observed.setdefault(snap['game']['dealId'],{})
                    for move in public['currentTrick']:
                        seen[(move['card']['suit'],move['card']['rank'])]=move
                    for t in public['completedTricks']:
                        assert t['cardsVisible'] or t['plays']==[]
                        for move in t['plays']:
                            seen[(move['card']['suit'],move['card']['rank'])]=move
                    for move in seen.values():
                        if move['seat']==seat:own.remove(move['card'])
                assert sorted(snap['you']['ownHand'],key=card_key)==sorted(own,key=card_key),'WS exposed nonremaining private hand'
                assert game.private_valid(snap,c.u),'unexpected private card projection'
        check('all three-deal WS histories expose only own remaining private hand',True)
        final=view(g);check('third deal retains terminal match and clears operation timer',final['phase']=='MATCH_RESULT' and final['game']['operationTimer'] is None and final['game']['matchResult'] is not None)
        expected_winner=None if cumulative['NS']==cumulative['EW'] else max(cumulative,key=cumulative.get)
        check('scored match winner follows independent cumulative totals',final['game']['matchResult']=={'outcome':'SCORED','winnerTeamId':expected_winner,'reason':'THREE_DEALS_COMPLETE','failedTeamIds':[]})
        for c in g['clients']:await c.close()
        await asyncio.sleep(.3)
        check('all-offline completed match retains immutable result and memberships',all(view(g,i)['game']['matchResult']==final['game']['matchResult'] for i in range(4)))
        g['clients'][0]=await Client(g['users'][0],g['roomId']).connect();await asyncio.sleep(.2)
        check('terminal reconnect restores unchanged final result',g['clients'][0].snaps[-1]['game']['matchResult']==final['game']['matchResult'])
        for i in range(3):assert api('POST',g['path']+'/leave',g['users'][i]['token'],{})[0]==200
        check('late explicit leaves cannot rewrite scored match result',view(g,3)['game']['matchResult']==final['game']['matchResult'])
    finally:
        await game.cleanup(g)
        Path(os.environ.get('BRIDGE_PLAY_RESULTS', str(Path(__file__).with_name('play-integration-results.json')))).write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
