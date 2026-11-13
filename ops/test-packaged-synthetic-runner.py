#!/usr/bin/env python3
"""Actual deployable runner main/HTTP smoke, not target/classes or a fake launcher.
No approval is submitted. Cleared environment supplies malformed, synthetic credentials;
service-account parsing rejects before token exchange/network to Google can be attempted.
Only local loopback health/status requests are made. Requires a compiled synthetic release.
"""
import pathlib, socket, subprocess, sys, tempfile, time, urllib.request, urllib.error, zipfile
jar=pathlib.Path(sys.argv[1]).resolve(); java=sys.argv[2]
with zipfile.ZipFile(jar) as z:
    names=set(z.namelist()); prefix='BOOT-INF/classes/com/bridge/quartercore/'
    for name in ['SyntheticQuarterRunnerMain','SyntheticQuarterRunnerLauncher','SyntheticQuarterRunner','SyntheticECompiledRelease','PlannedMaintenanceStartupFactory','JournalSessionAuthority','JournalQuarterAuthority','JournalQuarterProtocol','NativeStoresBackend','QuarterGoogleFactory','ManagedGoogleServiceAccount']:
        assert prefix+name+'.class' in names, ('missing packaged runner dependency',name)
    assert not any(n.startswith(prefix+'SyntheticC') for n in names), 'legacy C entrypoints must remain excluded'
    assert b'SyntheticCMain' not in z.read(prefix+'SyntheticQuarterRunnerLauncher.class'), 'runtime dependency on excluded C entrypoint'
with socket.socket() as s:
    s.bind(('127.0.0.1',0));port=s.getsockname()[1]
env={'PORT':str(port),'BRIDGE_RUNTIME_MODE':'synthetic-quarter-runner','BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON':'POISON_MALFORMED_NOT_A_CREDENTIAL'}
def get(path):
    try:r=urllib.request.urlopen(f'http://127.0.0.1:{port}'+path,timeout=2)
    except urllib.error.HTTPError as e:r=e
    return r.status,{k.lower():v for k,v in r.headers.items()},r.read().decode()
with tempfile.TemporaryFile() as log:
    p=subprocess.Popen([java,'-jar',str(jar)],env=env,stdout=log,stderr=subprocess.STDOUT)
    try:
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            if p.poll() is not None:raise AssertionError('packaged runner exited before maintenance HTTP startup')
            try:
                if get('/status')[2].strip()=='REVIEW':break
            except OSError:pass
            time.sleep(.1)
        assert p.poll() is None
        for path in ['/health/live','/health/route']:
            status,headers,body=get(path);assert status==200,(path,status)
            assert body.strip()=='maintenance_only';assert headers.get('cache-control')=='no-store'
        assert get('/status')[2].strip()=='REVIEW', 'invalid credential must refuse read/arm'
        for path in ['/','/health/ready']:assert get(path)[0]==503,path
        for path in ['/api/auth/google','/api/rooms','/api/statistics','/ws']:assert get(path)[0]==404,path
        log.seek(0);out=log.read().decode(errors='replace')
        assert 'NoClassDefFoundError' not in out
        assert 'SYNTHETIC_RUNNER_PRIVATE_APPROVAL' not in out, 'must not issue approval without verified read'
        assert 'POISON_MALFORMED_NOT_A_CREDENTIAL' not in out, 'must not log credential input'
        print('PACKAGED_SYNTHETIC_RUNNER_STARTUP_PASS; actual_main=HTTP_READY; malformed_credential=REVIEW; approval=NONE; game=BLOCKED; legacy_C=EXCLUDED')
    except Exception:
        log.seek(0);print(log.read().decode(errors='replace'));raise
    finally:
        p.terminate()
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:p.kill();p.wait()
