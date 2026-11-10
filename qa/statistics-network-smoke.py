"""Loopback-only smoke of the packaged candidate; no credentials, cloud, or live data."""
import argparse, http.client, json, os, pathlib, subprocess, time
p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--jar',required=True);p.add_argument('--output',required=True);a=p.parse_args()
origin='https://statistics-fixture.example';port=18973;rows=[]
def check(name,condition):
 rows.append({'case':name,'passed':bool(condition)});assert condition,name

def request(method,path,token=None,body=None,origin_header=None):
 c=http.client.HTTPConnection('127.0.0.1',port,timeout=3);headers={}
 if token:headers['Authorization']='Bearer '+token
 if body is not None:headers['Content-Type']='application/json'
 if origin_header:headers['Origin']=origin_header
 c.request(method,path,None if body is None else json.dumps(body),headers);r=c.getresponse();raw=r.read();status=r.status;h=dict(r.getheaders());c.close()
 try:result=json.loads(raw)
 except ValueError:result=raw.decode()
 return status,result,h

jar=pathlib.Path(a.jar).resolve();env={'PATH':os.environ.get('PATH',''),'LANG':'C.UTF-8','PUBLIC_ORIGIN':origin,'BRIDGE_SHEETS_ENABLED':'false','BRIDGE_STATISTICS_ENABLED':'false','BRIDGE_COLLECTION_MODE':'restricted'}
log=pathlib.Path(a.output).with_suffix('.server.log')
with log.open('w') as stream:
 proc=subprocess.Popen([a.java,'-jar',str(jar),'--spring.profiles.active=prod','--server.address=127.0.0.1',f'--server.port={port}'],env=env,stdout=stream,stderr=subprocess.STDOUT)
 try:
  for i in range(100):
   if proc.poll() is not None:raise RuntimeError('Fixture process exited; see local log')
   try:
    if request('GET','/health/ready')[0]==200:break
   except OSError:pass
   time.sleep(.1)
  else:raise RuntimeError('Fixture startup deadline')
  status,data,headers=request('GET','/api/statistics/config');check('new config endpoint disabled by default',status==200 and data=={'enabled':False,'consentVersion':'statistics-v4'})
  check('statistics responses are no-store',headers.get('Cache-Control')=='no-store')
  status,data,_=request('GET','/api/statistics');check('no session cannot read statistics',status==401 and data.get('code')=='UNAUTHORIZED')
  status,session,_=request('POST','/api/sessions',body={'displayName':'QA'});check('guest gameplay session still works',status==200 and 'token' in session)
  token=session['token'];status,data,_=request('GET','/api/statistics',token);check('guest cannot read personal or overall',status==403 and data.get('code')=='STATS_LOGIN_REQUIRED' and 'overall' not in data)
  status,data,_=request('PUT','/api/statistics/consent',token,{'consent':True,'version':'statistics-v4'},origin);check('disabled statistics cannot accept new consent',status==409 and data.get('code')=='STATS_DISABLED')
  status,data,_=request('PUT','/api/statistics/consent',token,{'consent':True,'version':'statistics-v4'},'https://evil.example');check('consent rejects foreign origin',status==403 and data.get('code')=='AUTH_ORIGIN')
  status,data,_=request('GET','/api/rooms',token);check('guest lobby remains available',status==200 and data==[])
  status,data,_=request('POST','/api/rooms',token,{'plannedDeals':5,'roomType':'FORMAL'},origin);check('formal room creation exposes fixed mode',status==200 and data.get('roomType')=='FORMAL' and data.get('plannedDeals')==5)
  formal_id=data['roomId'];status,data,_=request('GET','/api/rooms',token);check('lobby summary exposes formal mode',status==200 and len(data)==1 and data[0].get('roomType')=='FORMAL')
  request('POST','/api/rooms/'+formal_id+'/leave',token,{},origin)
  status,data,_=request('POST','/api/rooms',token,{},origin);check('legacy room create defaults to casual',status==200 and data.get('roomType')=='CASUAL')
  request('POST','/api/rooms/'+data['roomId']+'/leave',token,{},origin)
  status,data,_=request('POST','/api/rooms',token,{'plannedDeals':1,'roomType':'CASUAL'},origin);check('explicit casual creation exposes fixed mode',status==200 and data.get('roomType')=='CASUAL')
  request('POST','/api/rooms/'+data['roomId']+'/leave',token,{},origin)
  status,data,_=request('POST','/api/rooms',token,{'roomType':None},origin);check('explicit null room mode rejects instead of defaulting',status==400 and data.get('code')=='INVALID_ROOM_TYPE')
  status,data,_=request('GET','/api/rooms',token);check('invalid room creation has no mutation',status==200 and data==[])
  status,html,_=request('GET','/');check('production bundle serves app HTML',status==200 and '<div id="root">' in html)
  status,_,_=request('GET','/qa/statistics-fixtures.js');check('test-only fixtures not published',status==404)
  status,_,_=request('GET','/qa/statistics-account.tsx');check('test-only auth component not published',status==404)
 finally:
  proc.terminate()
  try:proc.wait(timeout=20)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
  pathlib.Path(a.output).write_text(json.dumps({'scope':'local packaged HTTP, persistence off, no real account','checks':rows,'passed':len(rows)==17 and all(r['passed'] for r in rows)},indent=2)+'\n')
print(json.dumps({'checks':len(rows),'passed':len(rows)==17 and all(r['passed'] for r in rows)}))
