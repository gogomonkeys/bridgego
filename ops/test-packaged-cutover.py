#!/usr/bin/env python3
"""Local production JAR smoke. Synthetic settings only; no remote requests or private credentials."""
import os, pathlib, socket, subprocess, sys, tempfile, time, urllib.request, urllib.error, base64
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
jar=pathlib.Path(sys.argv[1]).resolve(); java=sys.argv[2]
with socket.socket() as s:s.bind(('127.0.0.1',0)); port=s.getsockname()[1]
public=Ed25519PrivateKey.generate().public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
env={'PATH':os.environ.get('PATH',''),'SERVER_PORT':str(port),'SERVER_ADDRESS':'127.0.0.1','BRIDGE_RUNTIME_MODE':'planned-cutover','BRIDGE_PLANNED_CUTOVER_ENABLED':'true','BRIDGE_CUTOVER_SPREADSHEET_ID':'synthetic-packaged-only','BRIDGE_CUTOVER_DATASET_ID':'b5fc0d0a-8e4a-45e1-b223-1c1b18b2434b','BRIDGE_CUTOVER_PURPOSE':'bridgego-managed','BRIDGE_CUTOVER_SERVICE_ACCOUNT_ISSUER':'synthetic@example.iam.gserviceaccount.com','BRIDGE_CUTOVER_POLICY_VERSION':'taiwan-quarter-retention-v1','BRIDGE_CUTOVER_POLICY_RULES':'expired-personal-results-fold-verified-lifetime-preserve-accounts-v1','BRIDGE_CUTOVER_APPROVAL_REFERENCE':'synthetic-smoke-no-write','BRIDGE_CUTOVER_HISTORY_COUNTS':'1,1,0,1,0,-1,1,0,0','BRIDGE_CUTOVER_HISTORY_COMMIT':'d5fc0d0a-8e4a-45e1-b223-1c1b18b2434b','BRIDGE_CUTOVER_HISTORY_HASH':'0'*64,'BRIDGE_CUTOVER_HISTORY_APPROVAL':'ccfc0d0a-8e4a-45e1-b223-1c1b18b2434b','BRIDGE_CUTOVER_ORIGIN':'https://synthetic.invalid','BRIDGE_GOOGLE_CLIENT_ID':'synthetic-audience','BRIDGE_CUTOVER_OPERATOR_SUBJECTS':'synthetic-operator','BRIDGE_CUTOVER_WORKSPACE':'tea-fixed','BRIDGE_CUTOVER_SERVICE':'srv-fixed','BRIDGE_CUTOVER_REVISION':'synthetic-revision','BRIDGE_CUTOVER_LEGACY_DEPLOYMENTS':'dep-old','BRIDGE_CUTOVER_MANUAL_SOURCES':'cli','BRIDGE_CUTOVER_OBSERVATION_PUBLIC_KEY':base64.b64encode(public).decode(),'BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON':'POISON_NOT_READ_AT_STARTUP'}
def get(path):
 try:r=urllib.request.urlopen(f'http://127.0.0.1:{port}'+path,timeout=2)
 except urllib.error.HTTPError as e:r=e
 return r.status,dict(r.headers),r.read()
with tempfile.TemporaryFile() as log:
 p=subprocess.Popen([java,'-jar',str(jar)],env=env,stdout=log,stderr=subprocess.STDOUT)
 try:
  deadline=time.monotonic()+40
  while time.monotonic()<deadline:
   if p.poll() is not None:raise RuntimeError('startup exited')
   try:
    if get('/health/live')[0]==200:break
   except OSError:pass
   time.sleep(.2)
  assert get('/health/ready')[0]==503
  for path in ['/initial-cutover/operator.html','/initial-cutover/operator.css','/initial-cutover/operator.js']:
   status,headers,body=get(path); assert status==200,(path,status)
   assert headers.get('Cache-Control')=='no-store',(path,headers)
   assert body
  assert get('/initial-cutover/config')[0]==200
  for path in ['/api/rooms','/api/statistics','/ws']:assert get(path)[0]==503,path
  print('PACKAGED_CUTOVER_SMOKE_PASS; assets=200/no-store; ready/game=503; poison_credentials_unused')
 except Exception:
  log.seek(0);print(log.read().decode(errors='replace'));raise
 finally:
  p.terminate()
  try:p.wait(timeout=10)
  except subprocess.TimeoutExpired:p.kill();p.wait()
