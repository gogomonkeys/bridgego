#!/usr/bin/env python3
"""Original production JAR negative smoke for fixed-e-overall-v2.
No real credentials, valid startup, preview, HTTP execute, or remote calls are used.
Negative configurations stop before setup; the enabled case uses deliberately non-JSON
credentials which fail parsing before a Google exchange is constructed. Only loopback
health/status GET requests are made; never submit execution approval.
"""
import argparse
import hashlib
import http.client
import time
import json
import os
import pathlib
import socket
import subprocess
import zipfile


def enabled_malformed_smoke(args, jar, evidence):
    # Verified source ordering: FixedEOverallV2Main -> arm/readOnly ->
    # ManagedGoogleSheetsFactory.open -> requireSilentTls (properties only) ->
    # ManagedGoogleServiceAccount.load JSON parse -> THEN OAuthExchange.production.
    # This sentinel is not JSON, so no DNS, token HTTP or Sheets HTTP is reachable.
    sentinel = 'POISON_OVERALL_V2_NON_JSON_NEVER_DISCLOSE'
    with socket.socket() as reserve:
        reserve.bind(('127.0.0.1', 0))
        port = reserve.getsockname()[1]
    env = {'PATH': os.environ.get('PATH', ''), 'PORT': str(port),
           'BRIDGE_RUNTIME_MODE': 'fixed-e-overall-v2',
           'BRIDGE_FIXED_E_OVERALL_V2_ENABLED': 'true',
           'BRIDGE_READONLY_STARTUP_ENABLED': 'false',
           'BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON': sentinel}
    def get(path):
        # HTTPConnection does not honor proxy env or follow redirects.
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=2)
        try:
            connection.request('GET', path)
            response = connection.getresponse()
            return response.status, dict((k.lower(), v) for k, v in response.getheaders()), response.read(8192).decode()
        finally:
            connection.close()
    log = evidence / 'enabled-malformed-credential.log'
    with log.open('wb') as output:
        process = subprocess.Popen([args.java, '-jar', str(jar)], env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                assert process.poll() is None, 'Enabled runner exited before maintenance HTTP'
                try:
                    if get('/status')[2].strip() == 'REVIEW':
                        break
                except (OSError, http.client.HTTPException):
                    pass
                time.sleep(.1)
            assert process.poll() is None
            assert get('/status')[2].strip() == 'REVIEW'
            for path in ('/health/live', '/health/route'):
                status, headers, body = get(path)
                assert status == 200 and body.strip() == 'maintenance_only', path
                assert headers.get('cache-control') == 'no-store', path
            for path in ('/', '/health/ready'):
                assert get(path)[0] == 503, path
            for path in ('/fixed-e/verified-baseline.json', '/api/auth/google', '/api/rooms', '/api/statistics', '/ws'):
                assert get(path)[0] == 404, path
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    text = log.read_text()
    assert 'SYNTHETIC_RUNNER_REVIEW_NO_RETRY' in text
    for forbidden in (sentinel, 'NoClassDefFoundError', 'ClassNotFoundException',
                      'SYNTHETIC_RUNNER_PRIVATE_APPROVAL', 'SYNTHETIC_RUNNER_VERIFIED', 'privateNonce'):
        assert forbidden not in text, forbidden
    return {'case': 'enabled-malformed-credential', 'actualPackagedMaintenanceHttp': True,
            'status': 'REVIEW', 'approval': 'NONE', 'verifiedArtifact': '404',
            'remoteIo': 'Unreachable: malformed JSON rejected before exchange construction; no execution POST submitted.'}


def main(args):
    jar = pathlib.Path(args.jar).resolve()
    evidence = pathlib.Path(args.evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert b'com.bridge.room.RoomApplication' in archive.read('META-INF/MANIFEST.MF')
        for cls in ('FixedEOverallV2Main', 'FixedEOverallV2Runner', 'SyntheticQuarterRunnerLauncher'):
            assert 'BOOT-INF/classes/com/bridge/quartercore/' + cls + '.class' in names, cls
        assert 'BOOT-INF/classes/synthetic-e/overall-v2-verified-baseline.json' in names
        assert not any('PackagedHandoff' in name for name in names), 'Test harness in production JAR'
    base = {'BRIDGE_RUNTIME_MODE': 'fixed-e-overall-v2'}
    enabled = {'BRIDGE_FIXED_E_OVERALL_V2_ENABLED': 'true', 'BRIDGE_READONLY_STARTUP_ENABLED': 'false'}
    cases = [
        ('default-disabled', {}),
        ('explicit-disabled', {'BRIDGE_FIXED_E_OVERALL_V2_ENABLED': 'false', 'BRIDGE_READONLY_STARTUP_ENABLED': 'false'}),
        ('readonly-missing', {'BRIDGE_FIXED_E_OVERALL_V2_ENABLED': 'true'}),
        ('readonly-true', {**enabled, 'BRIDGE_READONLY_STARTUP_ENABLED': 'true'}),
        ('readonly-noncanonical', {**enabled, 'BRIDGE_READONLY_STARTUP_ENABLED': 'FALSE'}),
    ]
    for key in ('BRIDGE_FIXED_E_SPREADSHEET_ID', 'BRIDGE_FIXED_E_DATASET_ID', 'BRIDGE_FIXED_E_CLOCK', 'BRIDGE_FIXED_E_BASELINE'):
        cases.append(('override-' + key.lower(), {**enabled, key: 'FORGED_OVERRIDE_NOT_APPROVED'}))
    # Presence itself must be forbidden, including an empty override.
    cases.append(('empty-target-override', {**enabled, 'BRIDGE_FIXED_E_SPREADSHEET_ID': ''}))
    results = []
    sentinel = 'POISON_OVERALL_V2_NEVER_READ_OR_LOG'
    for name, settings in cases:
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', 0))
            occupied.listen()
            port = occupied.getsockname()[1]
            # No inherited BRIDGE/JAVA/proxy/credential inputs.
            env = {'PATH': os.environ.get('PATH', ''), 'PORT': str(port), 'SERVER_PORT': str(port),
                   'BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON': sentinel, **base, **settings}
            log = evidence / (name + '.log')
            with log.open('wb') as output:
                process = subprocess.run([args.java, '-jar', str(jar)], env=env,
                                         stdout=output, stderr=subprocess.STDOUT, timeout=20)
            output = log.read_text()
            assert process.returncode != 0, name
            assert 'Fixed E overall runner rejected' in output, (name, output)
            for forbidden in (sentinel, 'FORGED_OVERRIDE_NOT_APPROVED', 'Tomcat started', 'PortInUseException',
                              'BindException', 'NoClassDefFoundError', 'Invalid service account',
                              'token exchange', 'APPROVAL', 'EXECUTION_NONCE'):
                assert forbidden not in output, (name, forbidden)
            results.append({'case': name, 'rejectedWithoutHttpOrSecretDisclosure': True,
                            'preCredentialOrdering': 'Source guards and unit tests establish ordering; sanitized failure alone does not prove absence of credential parsing.'})
    results.append(enabled_malformed_smoke(args, jar, evidence))
    (evidence / 'result.json').write_text(json.dumps({
        'productionSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'scope': 'Negative guards plus enabled malformed-credential maintenance startup; no authorized execution or remote verification.',
        'cases': results,
    }, indent=2))
    print('ORIGINAL_PACKAGED_FIXED_E_OVERALL_V2_DISABLED_PASS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--evidence', required=True)
    main(parser.parse_args())
