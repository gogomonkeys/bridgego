"""Run existing independent 3-deal oracle against own production jar, default beta limits.
Only local synthetic players. Requires unoccupied localhost:8080 in this process environment.
"""
import argparse,os,subprocess,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--jar',required=True);p.add_argument('--java',default='java');p.add_argument('--output');a=p.parse_args()
root=Path(__file__).resolve().parents[1]
with open('/tmp/bridgego-three-deal-server.log','w') as log:
 proc=subprocess.Popen([a.java,'-Xms64m','-Xmx320m','-jar',str(Path(a.jar).resolve()),'--spring.profiles.active=prod','--server.port=8080'],env={**os.environ,'PUBLIC_ORIGIN':'https://bridgego-beta.example'},stdout=log,stderr=subprocess.STDOUT)
 try:
  for _ in range(200):
   if proc.poll() is not None:raise RuntimeError('server failed')
   try:
    urllib.request.urlopen('http://127.0.0.1:8080/health/ready',timeout=1).close();break
   except OSError:time.sleep(.15)
  else:raise RuntimeError('startup timeout')
  subprocess.run(['python3',str(root/'qa/play-integration.py')],cwd=root,check=True,env={**os.environ, **({'BRIDGE_PLAY_RESULTS':str(Path(a.output).resolve())} if a.output else {})})
 finally:
  proc.terminate()
  try:proc.wait(timeout=15)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
