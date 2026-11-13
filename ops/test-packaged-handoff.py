#!/usr/bin/env python3
"""Run local derived packaged server across real SIGTERM/new JVM boundaries.
Requires build-packaged-handoff-fixture.py output and PackagedHandoffSeedTest input.
No remote endpoints or credentials; only loopback health and temporary synthetic files.
"""
import argparse, datetime, hashlib, json, os, pathlib, shutil, signal, socket, subprocess, time, urllib.request, urllib.error

def port():
    with socket.socket() as s: s.bind(('127.0.0.1',0)); return s.getsockname()[1]
def status(p):
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{p}/health/ready',timeout=10) as r:return r.status
    except urllib.error.HTTPError as e:return e.code
    except OSError:return 0
def ready(p,expected):
    last=0
    for _ in range(3):
        last=status(p)
        if last==expected:return last
        if last not in (0,):return last
    return last
def root(path):
    j=json.loads(path.read_text())
    return json.loads(next(m['metadataValue'] for m in j['developerMetadata'] if m['metadataKey']=='nq.root'))
def tables(path):
    result={}
    for sheet in json.loads(path.read_text())['sheets']:
        rows=[]
        for row in sheet.get('data',[{}])[0].get('rowData',[]):
            cells=[cell.get('userEnteredValue',{}).get('stringValue','') for cell in row.get('values',[])]
            rows.append(cells)
        result[sheet['properties']['title']]=rows
    return result
def boot(java,jar,file,when,mode,folder):
    p=port(); env={k:v for k,v in os.environ.items() if not k.startswith(('BRIDGE_','GOOGLE_','SPRING_','RENDER_','SERVER_','PORT'))}
    log=folder/f'{mode}-{time.time_ns()}.log'; output=log.open('wb')
    proc=subprocess.Popen([java,'-jar',str(jar),str(file),when,str(p),mode],env=env,stdout=output,stderr=subprocess.STDOUT)
    until=time.monotonic()+50
    while time.monotonic()<until:
        if proc.poll() is not None:raise AssertionError(log.read_text())
        if 'PACKAGED_HANDOFF_STARTED' in log.read_text():return proc,p,log,output
        time.sleep(.2)
    proc.kill();raise AssertionError('packaged startup timeout: '+str(log))
def stop(proc,output):
    proc.send_signal(signal.SIGTERM)
    try:proc.wait(timeout=20)
    except subprocess.TimeoutExpired:proc.kill();proc.wait();raise AssertionError('orderly shutdown timed out')
    finally:output.close()
def main(a):
    folder=pathlib.Path(a.evidence);folder.mkdir(parents=True,exist_ok=True); seed=pathlib.Path(a.seed); now=pathlib.Path(a.time).read_text().strip(); jar=pathlib.Path(a.jar).resolve()
    dt=datetime.datetime.fromisoformat(now.replace('Z','+00:00')); taiwan=dt+datetime.timedelta(hours=8);month=((taiwan.month-1)//3+1)*3+1;year=taiwan.year+(month>12);month=1 if month>12 else month
    due=(datetime.datetime(year,month,1,tzinfo=datetime.timezone.utc)-datetime.timedelta(hours=8)+datetime.timedelta(seconds=1)).isoformat().replace('+00:00','Z')
    expectedTables=json.loads((seed.parent/'expected-tables.json').read_text())
    results=[]
    def record(result):
        results.append(result)
        (folder/'result.json').write_text(json.dumps(results,indent=2))
    # Invalid compiled-contract fixture rejects before Disk construction or Spring startup.
    invalid=folder/'invalidcontract.json';shutil.copyfile(seed,invalid); before=invalid.read_bytes()
    log=folder/'invalidcontract.log'
    with log.open('wb') as out:
        p=subprocess.run([a.java,'-jar',str(jar),str(invalid),now,str(port()),'invalidcontract'],env={'PATH':os.environ.get('PATH','')},stdout=out,stderr=subprocess.STDOUT,timeout=20)
    assert p.returncode!=0 and invalid.read_bytes()==before
    assert 'PACKAGED_HANDOFF_STARTED' not in log.read_text()
    record({'scenario':'invalidcontract','rejectedBeforeDiskAndSpring':True})
    for scenario,secondTime in [('same',now),('due',due),('busy',now),('unknown',now)]:
        f=folder/(scenario+'.json');shutil.copyfile(seed,f)
        first=boot(a.java,jar,f,now,scenario if scenario in ('busy','unknown') else 'normal',folder)
        proc,p,log,out=first
        try:
            expected=503 if scenario=='unknown' else 200
            assert ready(p,expected)==expected,(scenario,log.read_text())
        finally:stop(proc,out)
        if scenario=='busy':
            assert root(f)['state']=='OPEN'; before=f.read_bytes(); proc,p,log,out=boot(a.java,jar,f,now,'normal',folder)
            try:assert ready(p,503)==503,log.read_text();assert f.read_bytes()==before
            finally:stop(proc,out)
            assert tables(f)==expectedTables['same']
            record({'scenario':'busy','secondJvmMaintenance':True,'noResumeWrites':True,'allEightTablesUnchanged':True})
            continue
        if scenario=='unknown':
            assert root(f)['state']=='CLOSED';assert f.read_bytes()==seed.read_bytes()
            record({'scenario':'unknown','currentJvmMaintenance':True,'noAppliedWritesOrRetry':True})
            continue
        assert root(f)['state']=='CLOSED';assert tables(f)==expectedTables['same'],'First shutdown changed data'
        firstOwner=root(f)['owner'];second=boot(a.java,jar,f,secondTime,'normal',folder)
        proc,p,log,out=second
        try:assert ready(p,200)==200,log.read_text();assert root(f)['owner']!=firstOwner
        finally:stop(proc,out)
        assert root(f)['state']=='CLOSED'
        assert tables(f)==expectedTables[scenario],scenario+' all-table reducer expectation mismatch'
        record({'scenario':scenario,'newJvm':True,'closed':True,'allEightTablesMatchExpected':True,'accountsLifetimeAndRetainedRowsVerified':True,'expectedSource':'pure QuarterPhasePlans fixture reducer, not independent alternate implementation','sha256':hashlib.sha256(f.read_bytes()).hexdigest()})
    pendingSeed=seed.parent/'pending.json'
    if pendingSeed.exists():
        f=folder/'pending.json';shutil.copyfile(pendingSeed,f);before=f.read_bytes();proc,p,log,out=boot(a.java,jar,f,due,'normal',folder)
        try:assert ready(p,503)==503,log.read_text();assert f.read_bytes()==before
        finally:stop(proc,out)
        assert f.read_bytes()==before
        record({'scenario':'pending','freshJvmMaintenance':True,'zeroWrites':True})
    else:raise AssertionError('Missing incomplete-maintenance fixture')
    (folder/'result.json').write_text(json.dumps(results,indent=2));print('PACKAGED_HANDOFF_LOCAL_OK')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for n in ('jar','seed','time','evidence'):p.add_argument('--'+n,required=True)
    p.add_argument('--java',default='java');main(p.parse_args())
