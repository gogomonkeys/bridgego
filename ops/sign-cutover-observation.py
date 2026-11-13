#!/usr/bin/env python3
"""Offline fixed-context signer. Observations MUST be independently authenticated Render reads.
No network, key generation, credential discovery or submission. Challenge is not evidence.
"""
import argparse, base64, datetime as dt, json, pathlib, sys
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

def unique(pairs):
    out = {}
    for k, v in pairs:
        if k in out: raise ValueError()
        out[k] = v
    return out

def read_json(path):
    with pathlib.Path(path).open('rb') as f: raw = f.read(90001)
    if len(raw) > 90000: raise ValueError()
    return json.loads(raw, object_pairs_hook=unique)

def instant(text):
    value = dt.datetime.fromisoformat(text.replace('Z', '+00:00'))
    if value.tzinfo is None: raise ValueError()
    return value

def signed_body(challenge, expected, observed, private, public, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    for field in ('target', 'workspace', 'service', 'revision'):
        if challenge[field] != expected[field]: raise ValueError()
    if challenge['version'] != 1 or challenge['provenance'] != 'trusted-controller-relay-not-render-signed-v1': raise ValueError()
    if not now < instant(challenge['expires']) <= now + dt.timedelta(seconds=60): raise ValueError()
    if not now - dt.timedelta(seconds=60) < instant(observed['capturedAt']) <= now: raise ValueError()
    if observed['workspace'] != expected['workspace'] or observed['service'] != expected['service']: raise ValueError()
    if type(observed['services']) is not int or observed['services'] != 1 or type(observed['instances']) is not int or observed['instances'] != 1: raise ValueError()
    if observed['autoDeploy'] is not False or observed['previews'] is not False: raise ValueError()
    rows = observed['deployments']
    if not isinstance(rows, list) or not 1 <= len(rows) <= 100: raise ValueError()
    if any(set(row) != {'id', 'revision', 'state'} for row in rows): raise ValueError()
    if len({row['id'] for row in rows}) != len(rows): raise ValueError()
    current = [row for row in rows if row['id'] == challenge['deployment']]
    if len(current) != 1 or current[0]['state'] != 'live' or current[0]['revision'] != expected['revision']: raise ValueError()
    if any(row['id'] != challenge['deployment'] and row['state'] not in ('deactivated', 'failed', 'canceled') for row in rows): raise ValueError()
    if not expected['legacyDeployments'] or any(not any(row['id'] == old and row['state'] == 'deactivated' for row in rows) for old in expected['legacyDeployments']): raise ValueError()
    if not isinstance(private, Ed25519PrivateKey): raise ValueError()
    encoded = lambda key: key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if encoded(private.public_key()) != encoded(public): raise ValueError()
    payload = {k: challenge[k] for k in ('version', 'provenance', 'run', 'challenge', 'target', 'workspace', 'service', 'deployment', 'revision')}
    payload.update({k: observed[k] for k in ('capturedAt', 'services', 'instances', 'autoDeploy', 'previews', 'deployments')})
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
    if len(raw) > 65536: raise ValueError()
    return {'payload': base64.b64encode(raw).decode(), 'signature': base64.b64encode(private.sign(raw)).decode()}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('challenge', 'expected', 'observed', 'private-key', 'pinned-public-key'): p.add_argument('--' + name, required=True)
    a = p.parse_args()
    try:
        key_path = pathlib.Path(a.private_key)
        if key_path.stat().st_mode & 0o077 or key_path.stat().st_size > 4096: raise ValueError()
        private = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        public = serialization.load_pem_public_key(pathlib.Path(a.pinned_public_key).read_bytes())
        print(json.dumps(signed_body(read_json(a.challenge), read_json(a.expected), read_json(a.observed), private, public), separators=(',', ':')))
    except Exception:
        print('REFUSED: observation, approved context, expiry or signing key did not match; nothing submitted.', file=sys.stderr)
        return 1
    return 0
if __name__ == '__main__': sys.exit(main())
