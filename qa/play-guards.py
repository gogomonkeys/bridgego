"""Real legality, natural play timeout and active hand recovery checks."""
import asyncio,importlib.util,json,time,uuid
from pathlib import Path
from integration import api,Client,session
from play_oracle import legal_cards,card_key
spec=importlib.util.spec_from_file_location('play_checks',Path(__file__).with_name('play-integration.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
async def main():
    g=await p.game.setup('play-guard')
    try:
        assert p.command(g,0,'start')[0]==200
        for i in (0,1):assert p.command(g,i,'privilege',{'use':True})[0]==200
        assert p.command(g,0,'calls',{'type':'BID','level':1,'strain':'NO_TRUMP'})[0]==200
        for i in (1,2,3):assert p.command(g,i,'calls',{'type':'PASS'})[0]==200
        r=p.view(g,1);hand=r['you']['ownHand'];next_hand=p.view(g,2)['you']['ownHand']
        chosen=next((c for c in hand if any(n['suit']==c['suit'] for n in next_hand) and any(n['suit']!=c['suit'] for n in next_hand)),hand[0])
        # Malformed values, out-of-turn and forged bearer fields must leave state untouched.
        rev=r['revision'];outsider=session('play-outsider')
        path=g['path']+'/games/'+r['game']['gameId']+'/deals/'+r['game']['dealId']+'/plays'
        body={'actionId':str(uuid.uuid4()),'expectedRevision':rev,'card':chosen,'playerId':g['users'][1]['playerId'],'seat':1}
        check('outsider play cannot forge seated identity',api('POST',path,outsider['token'],body)[0]==403 and p.view(g)['revision']==rev)
        check('wrong turn cannot forge current player',p.command(g,0,'plays',{'card':chosen,'playerId':g['users'][1]['playerId'],'seat':1})[0]==409 and p.view(g)['revision']==rev)
        check('invalid card ranks and suits rejected without mutation',all(p.command(g,1,'plays',{'card':c})[0] in (400,409) for c in ({'suit':'CLUB','rank':1},{'suit':'CLUB','rank':15},{'suit':'INVALID','rank':2})) and p.view(g)['revision']==rev)
        foreign=next(c for c in next_hand if c not in hand)
        check('card absent from own hand rejected',p.command(g,1,'plays',{'card':foreign})[0]==409 and p.view(g)['revision']==rev)
        status,response,body,path=p.command(g,1,'plays',{'card':chosen});assert status==200
        status,replay=api('POST',path,g['users'][1]['token'],body)
        check('accepted play exact retry removes card only once',status==200 and replay['outcome']=='REPLAYED' and len(p.view(g,1)['you']['ownHand'])==12 and len(p.view(g)['game']['play']['currentTrick'])==1)
        changed={**body,'card':next(c for c in hand if c!=chosen)}
        check('play actionId cannot be reused for another card',api('POST',path,g['users'][1]['token'],changed)[0]==409)
        legal=legal_cards(next_hand,[{'seat':1,'card':chosen}]);illegal=[c for c in next_hand if c not in legal]
        if illegal:check('must follow led suit when held',p.command(g,2,'plays',{'card':illegal[0]})[0]==409)
        else:check('void player may use all own cards',len(legal)==len(next_hand))
        check('legalCards visible only to current viewer',sorted(p.view(g,2)['you']['legalCards'],key=card_key)==legal and p.view(g,0)['you']['legalCards']==[])
        await asyncio.sleep(3);before=p.view(g,2);await g['clients'][2].close();await asyncio.sleep(.2)
        frozen=p.view(g)['game']['operationTimer'];await asyncio.sleep(2)
        check('play disconnect freezes timer and preserves private hand',frozen['paused'] and p.view(g)['game']['operationTimer']['remainingMs']==frozen['remainingMs'] and p.view(g,2)['you']['ownHand']==before['you']['ownHand'])
        g['clients'][2]=await Client(g['users'][2],g['roomId']).connect();await asyncio.sleep(.2)
        check('play reconnect restores same trick hand and remaining budget',p.view(g,2)['game']['play']==before['game']['play'] and p.view(g,2)['you']['ownHand']==next_hand and not p.view(g)['game']['operationTimer']['paused'] and p.view(g)['game']['operationTimer']['remainingMs']<frozen['remainingMs'])
        end=time.monotonic()+22
        while time.monotonic()<end and len(p.view(g)['game']['play']['currentTrick'])<2:await asyncio.sleep(.1)
        r=p.view(g);play=r['game']['play']['currentTrick'][-1]
        check('natural play timeout selects lowest legal card exactly once',len(r['game']['play']['currentTrick'])==2 and play=={'seat':2,'card':legal[0],'source':'TIMEOUT'} and r['game']['play']['nextSeat']==3)
        check('automatic play removes exactly that card and grants full next turn',len(p.view(g,2)['you']['ownHand'])==12 and legal[0] not in p.view(g,2)['you']['ownHand'] and r['game']['operationTimer']['remainingMs']>19000)
        stale=p.command(g,2,'plays',{'card':legal[0]},revision=before['revision'])[0]
        check('late pre-timeout manual play cannot duplicate automatic play',stale==409 and len(p.view(g)['game']['play']['currentTrick'])==2)
    finally:
        await p.game.cleanup(g)
        Path(__file__).with_name('play-guards-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
