#!/usr/bin/env python3
"""Unmodified packaged main negatives + malformed-credential maintenance HTTP.
No real credential, E read, signature, approval, write or external request is supplied.
"""
import argparse,json,os,pathlib,socket,subprocess,time,urllib.request,urllib.error,hashlib

def main(a):
    root=pathlib.Path(a.evidence);root.mkdir(parents=True,exist_ok=True);jar=pathlib.Path(a.jar).resolve();results=[]
    for name,extra in [('flag-absent',{}),('flag-false',{'BRIDGE_FIXED_E_READONLY_PREFLIGHT':'false'}),('target-override',{'BRIDGE_FIXED_E_READONLY_PREFLIGHT':'true','BRIDGE_FIXED_E_SPREADSHEET_ID':'unapproved-target'})]:
        env={'PATH':os.environ.get('PATH',''),'BRIDGE_RUNTIME_MODE':'fixed-e-readonly','BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON':'POISON_NOT_A_CREDENTIAL',**extra};log=root/(name+'.log')
        with log.open('wb') as out:p=subprocess.run([a.java,'-jar',str(jar)],env=env,stdout=out,stderr=subprocess.STDOUT,timeout=20)
        text=log.read_text();assert p.returncode!=0 and 'POISON_NOT_A_CREDENTIAL' not in text
        assert 'FIXED_E_' not in text,'Must not start preflight'
        assert 'Tomcat started' not in text
        results.append({'case':name,'rejectedWithoutPreflightOrHttp':True,'precredentialOrder':'source guards, not sanitized exception internals'})
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    env={'PATH':os.environ.get('PATH',''),'PORT':str(port),'BRIDGE_RUNTIME_MODE':'fixed-e-readonly','BRIDGE_FIXED_E_READONLY_PREFLIGHT':'true','BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON':'POISON_NOT_A_CREDENTIAL'}
    log=root/'invalid-credential-maintenance.log'
    def get(path):
        try:r=urllib.request.urlopen(f'http://127.0.0.1:{port}'+path,timeout=5)
        except urllib.error.HTTPError as e:r=e
        return r.status,r.headers,r.read().decode()
    with log.open('wb') as out:
        p=subprocess.Popen([a.java,'-jar',str(jar)],env=env,stdout=out,stderr=subprocess.STDOUT)
        try:
            until=time.monotonic()+25
            while time.monotonic()<until and 'FIXED_E_PREFLIGHT_UNVERIFIED' not in log.read_text():
                assert p.poll() is None,log.read_text();time.sleep(.1)
            assert 'FIXED_E_PREFLIGHT_UNVERIFIED' in log.read_text()
            for path in ['/health/live','/health/route']:
                status,h,b=get(path);assert status==200 and h.get('Cache-Control')=='no-store';assert json.loads(b)['writeReady'] is False
            for path in ['/health/ready','/health/synthetic-ready','/fixed-e/verified-baseline.json','/api/rooms','/api/auth/google','/api/statistics','/ws','/']:
                assert get(path)[0]==503,path
            assert 'POISON_NOT_A_CREDENTIAL' not in log.read_text();results.append({'case':'invalid-credential','maintenanceOnly':True,'artifactNotPublished':True,'writesDisabled':True})
        finally:
            p.terminate()
            try:p.wait(timeout=10)
            except subprocess.TimeoutExpired:p.kill();p.wait();raise AssertionError('shutdown timed out')
    (root/'result.json').write_text(json.dumps({'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'cases':results},indent=2));print('PACKAGED_FIXED_E_READONLY_NEGATIVES_PASS')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',required=True);p.add_argument('--java',default='java');p.add_argument('--evidence',required=True);main(p.parse_args())
