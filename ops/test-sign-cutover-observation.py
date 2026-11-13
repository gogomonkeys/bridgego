import importlib.util,pathlib,unittest,datetime as dt,copy,base64
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
s=importlib.util.spec_from_file_location('signer',pathlib.Path(__file__).with_name('sign-cutover-observation.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class SignerTest(unittest.TestCase):
 def setUp(self):
  self.now=dt.datetime(2026,11,13,tzinfo=dt.timezone.utc);self.key=Ed25519PrivateKey.generate()
  self.c={'version':1,'provenance':'trusted-controller-relay-not-render-signed-v1','run':'r','challenge':'a'*64,'target':'b'*64,'workspace':'tea-fixed','service':'srv-fixed','deployment':'dep-new','revision':'reviewed','expires':(self.now+dt.timedelta(seconds=60)).isoformat()}
  self.e={k:self.c[k] for k in ('target','workspace','service','revision')};self.e['legacyDeployments']=['dep-old']
  self.o={'capturedAt':self.now.isoformat(),'workspace':'tea-fixed','service':'srv-fixed','services':1,'instances':1,'autoDeploy':False,'previews':False,'deployments':[{'id':'dep-new','revision':'reviewed','state':'live'},{'id':'dep-old','revision':'old','state':'deactivated'}]}
 def call(self):return m.signed_body(self.c,self.e,self.o,self.key,self.key.public_key(),self.now)
 def test_exact(self):
  r=self.call();self.key.public_key().verify(base64.b64decode(r['signature']),base64.b64decode(r['payload']))
 def test_wrong_target(self):
  self.c['target']='c'*64
  with self.assertRaises(ValueError):self.call()
 def test_stale(self):
  self.o['capturedAt']=(self.now-dt.timedelta(seconds=60)).isoformat()
  with self.assertRaises(ValueError):self.call()
 def test_active_predecessor(self):
  self.o['deployments'][1]['state']='live'
  with self.assertRaises(ValueError):self.call()
 def test_wrong_pin(self):
  with self.assertRaises(ValueError):m.signed_body(self.c,self.e,self.o,self.key,Ed25519PrivateKey.generate().public_key(),self.now)
 def test_bool_not_integer_count(self):
  self.o['services']=True
  with self.assertRaises(ValueError):self.call()
if __name__=='__main__':unittest.main()
