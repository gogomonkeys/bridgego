#!/usr/bin/env python3
"""Runs the real launcher against fake Java processes. No credentials, HTTP or game server."""
import json,os,pathlib,signal,subprocess,tempfile,time,unittest
SCRIPT=pathlib.Path(__file__).with_name('start-managed.sh').resolve()
FAKE='''#!/usr/bin/python3
import json,os,sys,time,signal
helper=any('readonly-startup.jar' in x for x in sys.argv)
record={'event':'helper' if helper else 'game','pid':os.getpid(),'time':time.time(),'args':sys.argv[1:],
        'java_options':os.environ.get('JAVA_TOOL_OPTIONS'),'jdk_options':os.environ.get('JDK_JAVA_OPTIONS'),
        'legacy_options':os.environ.get('_JAVA_OPTIONS'),'classpath':os.environ.get('CLASSPATH'),'other_secret_present':'BRIDGE_OTHER_SECRET' in os.environ}
with open('__TEST_LOG__','a') as f:f.write(json.dumps(record)+'\\n')
if not helper:print('GAME_OK');sys.exit(0)
mode_path='__TEST_MODE__'
mode=open(mode_path).read() if os.path.exists(mode_path) else 'ok'
if mode=='fail':print('SYNTHETIC_PRIVATE_ERROR',file=sys.stderr);sys.exit(7)
if mode=='hang':
 signal.signal(signal.SIGTERM,signal.SIG_IGN)
 while True:time.sleep(0.1)
print('{"status":"OBSERVED_STRUCTURE_ONLY","summary":null}')
'''
class LauncherTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name);(self.root/'java').write_text(FAKE.replace('__TEST_LOG__',str(self.root/'events.jsonl')).replace('__TEST_MODE__',str(self.root/'mode')));(self.root/'java').chmod(0o755);self.log=self.root/'events.jsonl'
  self.env=dict(os.environ,PATH=str(self.root)+':/usr/bin:/bin',TEST_LOG=str(self.log),JAVA_TOOL_OPTIONS='GAME_OPTIONS_PRESERVED',JDK_JAVA_OPTIONS='GAME_JDK_OPTIONS',_JAVA_OPTIONS='GAME_LEGACY_OPTIONS',CLASSPATH='GAME_CLASSPATH',BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON='SYNTHETIC_SECRET_NOT_PRINTED',BRIDGE_OTHER_SECRET='ANOTHER_SYNTHETIC_SECRET')
  self.env.pop('BRIDGE_READONLY_STARTUP_ENABLED',None)
 def tearDown(self):self.tmp.cleanup()
 def events(self):return [json.loads(x) for x in self.log.read_text().splitlines()] if self.log.exists() else []
 def start(self,**env):
  (self.root/'mode').write_text(env.pop('TEST_HELPER_MODE','ok'))
  return subprocess.Popen(['/bin/bash',str(SCRIPT),'argument with space','literal$(not executed)'],env=dict(self.env,**env),stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,preexec_fn=lambda:signal.signal(signal.SIGINT,signal.SIG_DFL))
 def finish(self,p,timeout=8):
  try:out,err=p.communicate(timeout=timeout)
  except subprocess.TimeoutExpired:
   p.send_signal(signal.SIGTERM)
   try:p.communicate(timeout=5)
   except subprocess.TimeoutExpired:p.kill();p.communicate()
   raise
  self.assertNotIn('SYNTHETIC_SECRET',out+err);self.assertNotIn('SYNTHETIC_PRIVATE',out+err);return out,err
 def wait_helper(self,p):
  end=time.time()+3
  while time.time()<end:
   if self.events():return self.events()[0]['pid']
   if p.poll() is not None:break
   time.sleep(.01)
  self.fail('helper did not start')
 def dead(self,pid):
  # Linux can retain a killed descendant briefly as a zombie; it is no longer executing.
  status=pathlib.Path('/proc')/str(pid)/'stat'
  return not status.exists() or status.read_text().split()[2]=='Z'
 def test_default_path_exact_command_and_environment(self):
  p=self.start();out,err=self.finish(p);self.assertEqual(0,p.returncode);self.assertEqual(['game'],[x['event'] for x in self.events()]);e=self.events()[0]
  self.assertEqual(['-jar','/app/bridgego-server.jar','argument with space','literal$(not executed)'],e['args']);self.assertEqual('GAME_OPTIONS_PRESERVED',e['java_options']);self.assertEqual('GAME_JDK_OPTIONS',e['jdk_options']);self.assertEqual('GAME_CLASSPATH',e['classpath'])
 def test_invalid_flag_does_not_start_helper(self):
  p=self.start(BRIDGE_READONLY_STARTUP_ENABLED='TRUE');out,err=self.finish(p);self.assertIn('CONFIG_REJECTED',out);self.assertEqual(['game'],[x['event'] for x in self.events()])
 def test_success_runs_one_sanitized_separate_helper_before_game(self):
  p=self.start(BRIDGE_READONLY_STARTUP_ENABLED='true');out,err=self.finish(p);events=self.events();self.assertEqual(['helper','game'],[x['event'] for x in events]);h,g=events
  for key in ['java_options','jdk_options','legacy_options','classpath']:self.assertIsNone(h[key]);self.assertIsNotNone(g[key])
  self.assertFalse(h['other_secret_present']);self.assertTrue(g['other_secret_present']);self.assertIn('-Xmx128m',h['args']);self.assertIn('-XX:-HeapDumpOnOutOfMemoryError',h['args']);self.assertNotIn('SYNTHETIC_SECRET',str(h['args']));self.assertGreaterEqual(g['time'],h['time']);self.assertTrue(self.dead(h['pid']))
 def test_helper_failure_is_sanitized_and_game_still_starts(self):
  p=self.start(BRIDGE_READONLY_STARTUP_ENABLED='true',TEST_HELPER_MODE='fail');out,err=self.finish(p);self.assertIn('HELPER_FAILED',out);self.assertEqual(['helper','game'],[x['event'] for x in self.events()]);self.assertEqual(0,p.returncode)
 def test_sigterm_stops_helper_group_and_never_starts_game(self):
  p=self.start(BRIDGE_READONLY_STARTUP_ENABLED='true',TEST_HELPER_MODE='hang');pid=self.wait_helper(p);p.send_signal(signal.SIGTERM);self.finish(p);self.assertEqual(143,p.returncode);self.assertEqual(['helper'],[x['event'] for x in self.events()]);self.assertTrue(self.dead(pid))
 def test_sigint_stops_helper_and_never_starts_game(self):
  p=self.start(BRIDGE_READONLY_STARTUP_ENABLED='true',TEST_HELPER_MODE='hang');pid=self.wait_helper(p);p.send_signal(signal.SIGINT);self.finish(p);self.assertEqual(130,p.returncode);self.assertEqual(['helper'],[x['event'] for x in self.events()]);self.assertTrue(self.dead(pid))
 def test_real_35_second_watchdog_kills_stuck_helper_then_starts_game(self):
  begin=time.monotonic();p=self.start(BRIDGE_READONLY_STARTUP_ENABLED='true',TEST_HELPER_MODE='hang');pid=self.wait_helper(p);out,err=self.finish(p,43);elapsed=time.monotonic()-begin
  self.assertGreaterEqual(elapsed,34);self.assertLess(elapsed,42);self.assertIn('HELPER_TIMEOUT',out);self.assertEqual(['helper','game'],[x['event'] for x in self.events()]);self.assertTrue(self.dead(pid))
 def test_new_startups_may_repeat_read_and_do_not_create_claim_files(self):
  for i in range(2):p=self.start(BRIDGE_READONLY_STARTUP_ENABLED='true');self.finish(p)
  self.assertEqual(['helper','game','helper','game'],[x['event'] for x in self.events()]);self.assertEqual({'java','events.jsonl','mode'},{p.name for p in self.root.iterdir()})
if __name__=='__main__':unittest.main(verbosity=2)
