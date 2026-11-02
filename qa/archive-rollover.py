"""Supplemental original-ID archive permission rollover across two real matches."""
import asyncio,importlib.util,json,uuid
from pathlib import Path
from integration import api,Client,session
spec=importlib.util.spec_from_file_location('play_checks',Path(__file__).with_name('play-integration.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
results=[]
def check(name,ok):
    results.append({'case':name,'status':'PASS' if ok else 'FAIL'});print(results[-1],flush=True);assert ok,name
async def main():
    g=await p.game.setup('archive-rollover');extra=None
    try:
        assert p.command(g,0,'start')[0]==200;first=p.view(g)['game']['gameId']
        assert api('POST',g['path']+'/leave',g['users'][0]['token'],{})[0]==200
        assert api('GET',g['path']+'/last-match',g['users'][0]['token'])[0]==200
        r=p.view(g,1);path=g['path']+'/games/'+first+'/rematch'
        assert api('POST',path,g['users'][1]['token'],{'actionId':str(uuid.uuid4()),'expectedRevision':r['revision']})[0]==200
        newcomer=session('archive-new-player');assert api('POST',g['path']+'/join',newcomer['token'],{})[0]==200
        extra=await Client(newcomer,g['roomId']).connect();await asyncio.sleep(.1)
        assert api('GET',g['path']+'/last-match',newcomer['token'])[0]==403
        for u in [newcomer]+g['users'][1:]:assert api('PUT',g['path']+'/ready',u['token'],{'ready':True})[0]==200
        assert p.command(g,1,'start')[0]==200;second=p.view(g,1)['game']['gameId']
        assert api('POST',g['path']+'/leave',newcomer['token'],{})[0]==200
        status,archive=api('GET',g['path']+'/last-match',newcomer['token'])
        check('new terminal replaces archived game without merging old roster',status==200 and archive['gameId']==second and second!=first and {u['playerId'] for u in archive['roster']}=={u['playerId'] for u in [newcomer]+g['users'][1:]})
        check('archive permission rolls to new original identities not excluded old players',api('GET',g['path']+'/last-match',g['users'][0]['token'])[0]==403 and api('GET',g['path']+'/last-match',g['users'][1]['token'])==(200,archive) and p.view(g,1)['lastMatch']==archive)
    finally:
        if extra:await extra.close()
        await p.game.cleanup(g)
        Path(__file__).with_name('archive-rollover-results.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
