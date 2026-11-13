#!/usr/bin/env python3
"""Derived-JAR LOCAL SYNTHETIC continuation from saved seq18 plus one locally generated pair; not a real Render idle/wake test. Saved verified baseline only; all Sheets HTTP
is an in-process bounded fake Exchange. No real E/network/credential access.
Business time is fixed Q1; authority/security time remains actual system UTC.
"""
import argparse,pathlib,subprocess,os,socket,time,urllib.request,urllib.error,shutil,json,hashlib

def main(a):
    folder=pathlib.Path(a.evidence);folder.mkdir(parents=True,exist_ok=True);artifact=pathlib.Path(a.artifact).resolve();jar=pathlib.Path(a.jar).resolve();source=json.loads(artifact.read_text());assert source['fullToken']=='2c8db9559c124f1d9d34ffe79c8d6413bb282382772554be55b9abbda33834a7'
    def projection(path):
        j=json.loads(path.read_text());return j.get('nativeProjection',j)
    def tables(path):return projection(path)['sheets'] # all sheet cells, shapes, IDs and per-sheet metadata unchanged
    def root(path):return json.loads(next(x['metadataValue'] for x in projection(path)['developerMetadata'] if x['metadataKey']=='nq.root'))
    expected=tables(artifact);results=[]
    seed=folder/'local-one-pair.json'
    prep=subprocess.run([a.java,'-jar',str(jar),str(artifact),str(seed),'0','prepare-local-one-pair'],env={'PATH':os.environ.get('PATH','')},capture_output=True,text=True,timeout=20)
    assert prep.returncode==0 and 'LOCAL_SYNTHETIC_FIRST_PAIR_ONLY_NOT_ONLINE_EVIDENCE' in prep.stdout
    (folder/'local-seed-generation.log').write_text(prep.stdout+prep.stderr)
    assert tables(seed)==expected

    def record(x):results.append(x);(folder/'result.json').write_text(json.dumps(results,indent=2))
    def completion(state,expected):
        p=subprocess.run([a.java,'-jar',str(jar),str(artifact),str(state),'0','completion-check'],env={'PATH':os.environ.get('PATH','')},capture_output=True,text=True,timeout=20)
        assert (p.returncode==0)==expected
        assert ('LOCAL_EXACT_THREE_PAIR_COMPLETION_VERIFIED' in p.stdout)==expected
    completion(seed,False)

    def http(port,path):
        try:r=urllib.request.urlopen(f'http://127.0.0.1:{port}'+path,timeout=10)
        except urllib.error.HTTPError as e:r=e
        return r.status,r.read().decode()
    def run(state,mode,want):
        with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
        log=folder/(mode+'-'+str(time.time_ns())+'.log');out=log.open('wb');p=subprocess.Popen([a.java,'-jar',str(jar),str(artifact),str(state),str(port),mode],env={'PATH':os.environ.get('PATH','')},stdout=out,stderr=subprocess.STDOUT)
        try:
            until=time.monotonic()+40
            while 'PACKAGED_E_STARTED' not in log.read_text():
                if p.poll() is not None:raise AssertionError(log.read_text())
                if want==503 and mode=='normal':
                    try:
                        if http(port,'/health/route')[0]==200 and http(port,'/health/synthetic-ready')[0]==503:time.sleep(2);break
                    except OSError:pass
                if time.monotonic()>until:raise AssertionError('startup timeout')
                time.sleep(.1)
            assert http(port,'/health/synthetic-ready')[0]==want,log.read_text()
            for path in ['/health/ready','/api/rooms','/api/auth/google','/api/statistics','/ws','/robots.txt','/']:
                assert http(port,path)[0]==503,path
            assert http(port,'/health/route')[0]==200
            return p.pid
        finally:
            p.terminate()
            try:p.wait(timeout=20)
            except subprocess.TimeoutExpired:p.kill();p.wait();raise AssertionError('SIGTERM timeout')
            out.close()
    # Faithful overlapping rollout: second JVM is started before SIGTERM of the first.
    overlap=folder/'overlap.json';shutil.copyfile(seed,overlap)
    def spawn(label):
        with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
        log=folder/(label+'.log');out=log.open('wb');proc=subprocess.Popen([a.java,'-jar',str(jar),str(artifact),str(overlap),str(port),'normal'],env={'PATH':os.environ.get('PATH','')},stdout=out,stderr=subprocess.STDOUT);return proc,port,log,out
    def awaitStatus(proc,port,expected,seconds):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            assert proc.poll() is None
            try:
                if http(port,'/health/synthetic-ready')[0]==expected:return
            except OSError:pass
            time.sleep(.1)
        raise AssertionError('overlap health deadline')
    def terminate(proc,out):
        if proc.poll() is None:
            proc.terminate()
            try:proc.wait(timeout=20)
            except subprocess.TimeoutExpired:proc.kill();proc.wait();raise AssertionError('overlap SIGTERM timeout')
        out.close()
    first,p1,l1,o1=spawn('overlap-first');second=None
    try:
        awaitStatus(first,p1,200,40);before=overlap.read_bytes();second,p2,l2,o2=spawn('overlap-second');awaitStatus(second,p2,503,40)
        time.sleep(3);assert overlap.read_bytes()==before,'second wrote before predecessor clean CLOSE'
        terminate(first,o1);awaitStatus(second,p2,200,35);assert root(overlap)['state']=='OPEN';terminate(second,o2)
        assert root(overlap)['state']=='CLOSED';assert tables(overlap)==expected
        record({'case':'overlap','secondStartsBeforeOldSIGTERM':True,'zeroWritesWhileOldOpen':True,'resumesAfterDurableClose':True,'allSheetContentPreserved':True})
    finally:
        terminate(first,o1)
        if second is not None:terminate(second,o2)
    zero=folder/'zero-pair.json';shutil.copyfile(artifact,zero);before=zero.read_bytes();run(zero,'normal',503);assert zero.read_bytes()==before;record({'case':'zero-pair','rejected':True,'zeroWrites':True})
    normal=folder/'normal.json';shutil.copyfile(seed,normal);pids=[]
    for n in range(2):
        pids.append(run(normal,'normal',200));assert root(normal)['state']=='CLOSED';assert tables(normal)==expected;completion(normal,n==1)
        record({'case':'total-completed-pairs-'+str(n+2),'newJvmPid':pids[-1],'allSheetContentPreserved':True,'durableClosed':True})
    before=normal.read_bytes();run(normal,'normal',503);assert normal.read_bytes()==before;record({'case':'third-pair','maintenance':True,'zeroWrites':True})
    for mode in ['busy','unknown','pending']:
        f=folder/(mode+'.json');shutil.copyfile(seed,f);run(f,mode,200 if mode=='busy' else 503)
        assert root(f)['state']==('PENDING' if mode=='pending' else 'OPEN')
        before=f.read_bytes();run(f,'normal',503);assert f.read_bytes()==before;assert tables(f)==expected
        record({'case':mode,'newJvmMaintenance':True,'noRetryOrAdditionalWrite':True,'allSheetContentPreserved':True})
    f=folder/'formal.json';shutil.copyfile(seed,f);log=folder/'formal.log'
    with log.open('wb') as out:p=subprocess.run([a.java,'-jar',str(jar),str(artifact),str(f),'0','formal'],env={'PATH':os.environ.get('PATH','')},stdout=out,stderr=subprocess.STDOUT,timeout=20)
    assert p.returncode!=0 and f.read_bytes()==seed.read_bytes() and 'PACKAGED_E_STARTED' not in log.read_text();record({'case':'formal-target','rejectedBeforeWriter':True})
    (folder/'inputs.json').write_text(json.dumps({'savedArtifactSha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'derivedJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'businessTime':'2026-12-31T16:00:01Z','securityClock':'Clock.systemUTC(), no test clock injection','scope':'local SIGTERM/new-JVM simulation from synthetic one-pair descendant; NOT actual online lineage or Render idle','network':'local fake bounded Sheets Exchange only'},indent=2));print('PACKAGED_FIXED_E_CONTINUATION_LOCAL_PASS')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['jar','artifact','evidence']:p.add_argument('--'+n,required=True)
    p.add_argument('--java',default='java');main(p.parse_args())
