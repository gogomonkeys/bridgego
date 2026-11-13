#!/usr/bin/env python3
"""Unmodified production java -jar negative smoke. Never supplies real credentials.
The approved compiled native contract may be provisioned. Mismatched initial settings,
unsupported modes and CLI overrides still reject before credentials or HTTP. This
negative-only script deliberately does NOT test or claim that correct restart is disabled.
"""
import argparse, hashlib, json, os, pathlib, socket, subprocess, zipfile

def complete_managed_initial_settings():
    # Fully parseable settings, synthetic binding only. Neither this environment nor
    # a forged approval can replace the fixed NativeRestartCompiledRelease.current.
    return {
        'BRIDGE_RUNTIME_MODE':'planned-cutover', 'BRIDGE_NATIVE_BOOT_MODE':'initial',
        'BRIDGE_PLANNED_CUTOVER_ENABLED':'true',
        'BRIDGE_CUTOVER_SPREADSHEET_ID':'synthetic-managed-initial-disabled-table',
        'BRIDGE_CUTOVER_DATASET_ID':'12345678-1234-1234-1234-123456789abc',
        'BRIDGE_CUTOVER_PURPOSE':'bridgego-managed',
        'BRIDGE_CUTOVER_SERVICE_ACCOUNT_ISSUER':'synthetic@synthetic-project.iam.gserviceaccount.com',
        'BRIDGE_CUTOVER_POLICY_VERSION':'taiwan-quarter-retention-v1',
        'BRIDGE_CUTOVER_POLICY_RULES':'expired-personal-results-fold-verified-lifetime-preserve-accounts-overall-v2',
        'BRIDGE_CUTOVER_APPROVAL_REFERENCE':'synthetic-not-a-real-approval',
        'BRIDGE_CUTOVER_HISTORY_COUNTS':'1,1,0,1,0,-1,1,0,0',
        'BRIDGE_CUTOVER_HISTORY_COMMIT':'synthetic-legacy-commit',
        'BRIDGE_CUTOVER_HISTORY_HASH':'a'*64,
        'BRIDGE_CUTOVER_HISTORY_APPROVAL':'23456789-1234-1234-1234-123456789abc',
        'BRIDGE_CUTOVER_ORIGIN':'https://synthetic.example',
        'BRIDGE_GOOGLE_CLIENT_ID':'synthetic-client.apps.googleusercontent.com',
        'BRIDGE_CUTOVER_OPERATOR_SUBJECTS':'synthetic-operator',
        'BRIDGE_CUTOVER_WORKSPACE':'tea-synthetic',
        'BRIDGE_CUTOVER_SERVICE':'srv-synthetic',
        'BRIDGE_CUTOVER_REVISION':'f'*40,
        'BRIDGE_CUTOVER_LEGACY_DEPLOYMENTS':'dep-synthetic-predecessor',
        'BRIDGE_CUTOVER_MANUAL_SOURCES':'synthetic-manual-writer',
        # Deliberately invalid: compiled release must reject before parsing this or JSON.
        'BRIDGE_CUTOVER_OBSERVATION_PUBLIC_KEY':'POISON_PUBLIC_KEY_NEVER_PARSED_OR_LOGGED',
    }

def main(a):
    jar=pathlib.Path(a.jar).resolve();evidence=pathlib.Path(a.evidence);evidence.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(jar) as z:
        assert not any('PackagedHandoff' in n for n in z.namelist()),'Test harness leaked into production JAR'
        assert b'com.bridge.room.RoomApplication' in z.read('META-INF/MANIFEST.MF')
    sanitized='Planned cutover startup rejected'
    cases=[
        ('unknown-boot-mode',{'BRIDGE_RUNTIME_MODE':'planned-cutover','BRIDGE_NATIVE_BOOT_MODE':'not-approved'},[],sanitized),
        ('restart-cli-override',{'BRIDGE_RUNTIME_MODE':'planned-cutover','BRIDGE_NATIVE_BOOT_MODE':'restart'},['--target=forged'],sanitized),
        ('cutover-disabled',{'BRIDGE_RUNTIME_MODE':'planned-cutover'},[],sanitized),
        ('managed-initial-complete-env-wrong-binding',complete_managed_initial_settings(),[],sanitized),
        ('unknown-runtime-mode',{'BRIDGE_RUNTIME_MODE':'not-approved'},[],'Unknown runtime mode'),
    ]
    results=[]
    for name,settings,cli,expected in cases:
        # Occupied listener proves accidental HTTP binding cannot silently succeed.
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1',0));occupied.listen();port=occupied.getsockname()[1]
            env={'PATH':os.environ.get('PATH',''),'PORT':str(port),'SERVER_PORT':str(port),'BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON':'POISON_NEVER_READ_OR_LOG',**settings}
            log=evidence/(name+'.log')
            with log.open('wb') as out:
                p=subprocess.run([a.java,'-jar',str(jar),*cli],env=env,stdout=out,stderr=subprocess.STDOUT,timeout=20)
            text=log.read_text();assert p.returncode!=0
            assert expected in text,(name,text)
            for forbidden in ('POISON_NEVER_READ_OR_LOG','POISON_PUBLIC_KEY_NEVER_PARSED_OR_LOGGED','NoClassDefFoundError','ClassNotFoundException','Tomcat started','PortInUseException','Invalid service account','token exchange'):
                assert forbidden not in text,(name,forbidden)
            results.append({'case':name,'publicMainRejectedWithoutHttpOrSecretLeak':True,'preCredentialOrdering':'verified by source guards and unit tests, not disclosed by sanitized exception'})
    (evidence/'result.json').write_text(json.dumps({'productionSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'scope':'Provisioned-contract negative guards only; correct restart is intentionally not invoked or claimed disabled.','cases':results},indent=2))
    print('ORIGINAL_PACKAGED_NATIVE_CONTRACT_GUARDS_PASS')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',required=True);p.add_argument('--java',default='java');p.add_argument('--evidence',required=True);main(p.parse_args())
