#!/usr/bin/env python3
"""Unmodified production java -jar negative smoke. Never supplies real credentials.
The compiled native restart contract is deliberately unprovisioned. An environment
boolean, arbitrary target or forged approval cannot enable it. No HTTP is expected.
"""
import argparse, hashlib, json, os, pathlib, socket, subprocess, zipfile

def main(a):
    jar=pathlib.Path(a.jar).resolve();evidence=pathlib.Path(a.evidence);evidence.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(jar) as z:
        assert not any('PackagedHandoff' in n for n in z.namelist()),'Test harness leaked into production JAR'
        assert b'com.bridge.room.RoomApplication' in z.read('META-INF/MANIFEST.MF')
    cases=[('unprovisioned',{'BRIDGE_RUNTIME_MODE':'planned-cutover','BRIDGE_NATIVE_BOOT_MODE':'restart'}),
           ('forged-config',{'BRIDGE_RUNTIME_MODE':'planned-cutover','BRIDGE_NATIVE_BOOT_MODE':'restart','BRIDGE_PLANNED_CUTOVER_ENABLED':'true','BRIDGE_NATIVE_RESTART_ENABLED':'true','BRIDGE_CUTOVER_SPREADSHEET_ID':'arbitrary-not-approved','BRIDGE_CUTOVER_APPROVAL_REFERENCE':'forged'}),
           ('cutover-disabled',{'BRIDGE_RUNTIME_MODE':'planned-cutover'})]
    results=[]
    for name,settings in cases:
        # Occupied listener proves accidental HTTP binding cannot silently succeed.
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1',0));occupied.listen();port=occupied.getsockname()[1]
            env={'PATH':os.environ.get('PATH',''),'PORT':str(port),'SERVER_PORT':str(port),'BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON':'POISON_NEVER_READ_OR_LOG',**settings}
            log=evidence/(name+'.log')
            with log.open('wb') as out:
                p=subprocess.run([a.java,'-jar',str(jar)],env=env,stdout=out,stderr=subprocess.STDOUT,timeout=20)
            text=log.read_text();assert p.returncode!=0
            expected='Planned cutover startup rejected' # public main deliberately sanitizes internal failure
            assert expected in text,(name,text)
            for forbidden in ('POISON_NEVER_READ_OR_LOG','Tomcat started','PortInUseException','Invalid service account','token exchange'):
                assert forbidden not in text,(name,forbidden)
            results.append({'case':name,'publicMainRejectedWithoutHttpOrSecretLeak':True,'preCredentialOrdering':'verified by source guards and unit tests, not disclosed by sanitized exception'})
    (evidence/'result.json').write_text(json.dumps({'productionSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'cases':results},indent=2))
    print('ORIGINAL_PACKAGED_RESTART_DISABLED_PASS')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',required=True);p.add_argument('--java',default='java');p.add_argument('--evidence',required=True);main(p.parse_args())
