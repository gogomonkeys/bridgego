#!/usr/bin/env python3
"""Unmodified production JAR fixed-E mode negative checks, loopback only.
Malformed synthetic credentials cannot produce a Google OAuth token. No real secrets,
Sheets requests, approval, writable fixture injection or Start-Class replacement.
"""
import argparse, pathlib, os, socket, subprocess, time, urllib.request, urllib.error, json, zipfile, hashlib

def main(a):
    jar=pathlib.Path(a.jar).resolve();folder=pathlib.Path(a.evidence);folder.mkdir(parents=True,exist_ok=True);results=[]
    with zipfile.ZipFile(jar) as z:
        assert b'com.bridge.room.RoomApplication' in z.read('META-INF/MANIFEST.MF')
        assert not any('PackagedEHandoff' in n or 'PackagedHandoff' in n for n in z.namelist())
    def save(result):results.append(result);(folder/'result.json').write_text(json.dumps({'productionJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'cases':results},indent=2))
    base={'PATH':os.environ.get('PATH',''),'BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON':'POISON_LOCAL_NOT_CREDENTIAL'}
    modes={
        'fixed-e-idle-wake':{'BRIDGE_FIXED_E_IDLE_WAKE_ENABLED':'true','BRIDGE_READONLY_STARTUP_ENABLED':'false'},
        'fixed-e-idle-completion-readonly':{'BRIDGE_FIXED_E_READONLY_PREFLIGHT':'true','BRIDGE_FIXED_E_IDLE_COMPLETION_READONLY_ENABLED':'true','BRIDGE_FIXED_E_COMPLETION_READONLY_ENABLED':'true','BRIDGE_READONLY_STARTUP_ENABLED':'false'}}
    for mode,good in modes.items():
        flag='BRIDGE_FIXED_E_IDLE_WAKE_ENABLED' if mode=='fixed-e-idle-wake' else 'BRIDGE_FIXED_E_IDLE_COMPLETION_READONLY_ENABLED'
        variants=[('missing-flag',{k:v for k,v in good.items() if k!=flag}),('false-flag',{**good,flag:'false'}),('target-override',{**good,'BRIDGE_FIXED_E_SPREADSHEET_ID':'not-approved'}),('clock-override',{**good,'BRIDGE_FIXED_E_CLOCK':'2099-01-01T00:00:00Z'}),('startup-reader-true',{**good,'BRIDGE_READONLY_STARTUP_ENABLED':'true'}),('startup-reader-unspecified',{k:v for k,v in good.items() if k!='BRIDGE_READONLY_STARTUP_ENABLED'})]
        for name,settings in variants:
            log=folder/(mode+'-'+name+'.log')
            with log.open('wb') as out:p=subprocess.run([a.java,'-jar',str(jar)],env={**base,**settings,'BRIDGE_RUNTIME_MODE':mode},stdout=out,stderr=subprocess.STDOUT,timeout=20)
            text=log.read_text();assert p.returncode!=0,(mode,name)
            assert 'POISON_LOCAL_NOT_CREDENTIAL' not in text and 'Tomcat started' not in text and 'FIXED_E_PREFLIGHT_' not in text
            assert ('Fixed E idle wake rejected' if mode=='fixed-e-idle-wake' else 'Fixed E idle completion readonly rejected') in text
            save({'mode':mode,'case':name,'rejectedWithoutHttpOrSecretLeak':True,'precredentialOrdering':'source guards and unit tests; public exception is sanitized'})
        with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
        log=folder/(mode+'-poison-maintenance.log')
        with log.open('wb') as out:
            p=subprocess.Popen([a.java,'-jar',str(jar)],env={**base,**good,'BRIDGE_RUNTIME_MODE':mode,'PORT':str(port),'SERVER_PORT':str(port),'SERVER_ADDRESS':'127.0.0.1'},stdout=out,stderr=subprocess.STDOUT)
            def get(path):
                try:r=urllib.request.urlopen(f'http://127.0.0.1:{port}'+path,timeout=10)
                except urllib.error.HTTPError as e:r=e
                return r.status,r.headers,r.read().decode()
            try:
                until=time.monotonic()+40
                while True:
                    assert p.poll() is None,log.read_text()
                    try:
                        if get('/health/route')[0]==200:break
                    except OSError:pass
                    if time.monotonic()>until:raise AssertionError('maintenance HTTP timeout')
                    time.sleep(.1)
                # Give the local malformed-JSON failure a chance to resolve; no token can be exchanged.
                time.sleep(1)
                for path in ['/health/live','/health/route']:
                    status,h,b=get(path);assert status==200 and h.get('Cache-Control')=='no-store';assert json.loads(b)['writeReady'] is False
                for path in ['/health/ready','/health/synthetic-ready','/fixed-e/verified-baseline.json','/api/rooms','/api/auth/google','/api/statistics','/ws','/']:
                    assert get(path)[0]==503,(mode,path)
                assert 'POISON_LOCAL_NOT_CREDENTIAL' not in log.read_text()
                save({'mode':mode,'case':'poison-credential','maintenanceOnly':True,'artifactUnavailable':True,'gameAndWsBlocked':True})
            finally:
                p.terminate()
                try:p.wait(timeout=20)
                except subprocess.TimeoutExpired:p.kill();p.wait();raise AssertionError('shutdown timeout')
    log=folder/'formal-restart-unprovisioned.log'
    with log.open('wb') as out:p=subprocess.run([a.java,'-jar',str(jar)],env={**base,'BRIDGE_RUNTIME_MODE':'planned-cutover','BRIDGE_NATIVE_BOOT_MODE':'restart'},stdout=out,stderr=subprocess.STDOUT,timeout=20)
    assert p.returncode!=0 and 'Planned cutover startup rejected' in log.read_text() and 'POISON_LOCAL_NOT_CREDENTIAL' not in log.read_text()
    save({'mode':'planned-cutover/restart','formalReleaseStillUnprovisioned':'source pin plus packaged rejection','case':'rejected'})
    print('PACKAGED_FIXED_E_IDLE_MODES_NEGATIVES_PASS')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',required=True);p.add_argument('--java',default='java');p.add_argument('--evidence',required=True);main(p.parse_args())
