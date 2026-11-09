// Execute the actual shipped Java text-block script with a local fake DOM/Google/fetch.
// No network requests, credentials, backend calls or automatic retries.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('backend/src/main/java/com/bridge/persistence/SyntheticCleanupSupervisedWeb.java','utf8');
const js=source.split('static final String JS = """')[1].split('""";')[0];
assert(source.indexOf('id="status"')<source.indexOf('id="confirm"'));
assert(source.includes('<noscript>JAVASCRIPT_DISABLED'));
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const good=data=>({ok:true,json:async()=>data});
const challenge={nonce:'fake-nonce',clientId:'fake-client'};
const preview={phase:'SEED_ERASURE',confirmation:'EXACT STAGE ONE',planId:'p1',expiresAt:new Date(Date.now()+60000).toISOString(),changes:[]};
function harness(health=good({enabled:true})){
  const elements=new Map(),requests=[],queue=[],timers=[];
  const get=id=>{if(!elements.has(id))elements.set(id,{checked:false,disabled:true,value:'',textContent:'',replaceChildren(){}});return elements.get(id);};
  let callback;
  const id={initialize(o){callback=o.callback;},renderButton(){}};
  const c=vm.createContext({document:{getElementById:get},Date,JSON,Number,Object,Error,Set,window:{google:{accounts:{id}}},google:{accounts:{id}},setTimeout(fn){timers.push(fn);},setInterval(){},fetch:async(path,options)=>{requests.push({path,body:options?.body});const reply=path==='/health/ready'?health:queue.shift();if(reply instanceof Error)throw reply;return reply;}});
  vm.runInContext(js,c);
  const run=code=>vm.runInContext(code,c),approve=()=>{get('consent').checked=true;get('frozen').checked=true;run('update()');};
  return {get,requests,queue,timers,run,approve,id,callback:token=>callback(token),status:()=>get('status').textContent};
}
async function ready(){const h=harness();await tick();h.approve();return h;}
function stopped(h,code,stage='NO_OPERATION_SUBMITTED'){
  assert(h.status().startsWith(code+'：'),h.status());assert(h.status().includes('['+stage+']'),h.status());
  assert.equal(h.get('preview').disabled,true);assert.equal(h.get('confirm').disabled,true);
  assert(!h.status().includes('private-secret'));
}
async function auth(h){h.queue.push(good(challenge));h.get('preview').onclick();await tick();}
(async()=>{
  for(const [response,code] of [[Error('private-secret'),'HEALTH_NETWORK'],[{ok:false},'HEALTH_HTTP'],[{ok:true,json:async()=>{throw Error('private-secret');}},'HEALTH_DECODE'],[good({}),'HEALTH_INVALID'],[good({enabled:false}),'SERVICE_DISABLED']]){const h=harness(response);await tick();stopped(h,code);assert.equal(h.requests.length,1);}
  {const h=await ready();h.run('window.google=null');h.get('preview').onclick();await tick();stopped(h,'GOOGLE_NOT_READY');assert.equal(h.requests.length,1);}
  for(const [response,code] of [[Error('private-secret'),'CHALLENGE_NETWORK'],[{ok:true,json:async()=>{throw Error('private-secret');}},'CHALLENGE_DECODE'],[{ok:false,json:async()=>({code:'private-secret'})},'CHALLENGE_REJECTED'],[good({}),'CHALLENGE_INVALID'],[{ok:false,json:async()=>({code:'RUN_STOPPED'})},'RUN_STOPPED']]){const h=await ready();h.queue.push(response);h.get('preview').onclick();await tick();stopped(h,code);assert.equal(h.requests.length,2);}
  {const h=await ready();h.id.initialize=()=>{throw Error('private-secret');};await auth(h);stopped(h,'GOOGLE_SETUP_FAILED');}
  {const h=await ready();await auth(h);h.timers[0]();stopped(h,'AUTH_TIMEOUT');await h.callback({credential:'fake'});assert.equal(h.requests.length,2);}
  for(const [input,code] of [['consent','CONSENT_CHANGED'],['exact','CONFIRMATION_CHANGED']]){const h=await ready();await auth(h);h.get(input)[input==='exact'?'oninput':'onchange']();stopped(h,code);await h.callback({credential:'fake'});assert.equal(h.requests.length,2);}
  {const h=await ready();await auth(h);await h.callback({});stopped(h,'GOOGLE_PROOF_INVALID');}
  for(const [response,code] of [[Error('private-secret'),'OPERATION_NETWORK'],[{ok:true,json:async()=>{throw Error('private-secret');}},'OPERATION_DECODE'],[{ok:false,json:async()=>({code:'private-secret'})},'OPERATION_REJECTED'],[good({}),'PREVIEW_INVALID']]){const h=await ready();await auth(h);h.queue.push(response);await h.callback({credential:'fake'});stopped(h,code,'READ_SUBMITTED');assert.equal(h.requests.length,3);}
  {const h=await ready();await auth(h);h.queue.push(good({...preview,expiresAt:'2000-01-01T00:00:00Z'}));await h.callback({credential:'fake'});stopped(h,'PREVIEW_EXPIRED','READ_SUBMITTED');}
  async function confirmReady(){const h=await ready();await auth(h);h.queue.push(good(preview));await h.callback({credential:'fake-read'});assert.equal(h.get('consent').checked,false);assert.equal(h.get('frozen').checked,false);assert.equal(h.get('confirm').disabled,true);h.approve();h.get('exact').value='wrong';h.run('update()');assert.equal(h.get('confirm').disabled,true);h.get('exact').value=preview.confirmation;h.run('update()');assert.equal(h.get('confirm').disabled,false);h.queue.push(good(challenge));h.get('confirm').onclick();await tick();return h;}
  for(const [response,code] of [[Error('private-secret'),'OPERATION_NETWORK'],[{ok:true,json:async()=>{throw Error('private-secret');}},'OPERATION_DECODE'],[good({}),'RESULT_INVALID'],[good({outcome:'STOPPED_UNCERTAIN',verifiedPhases:0,syntheticFunctionalRunVerified:false,productionCleanupCertified:false}),'RESULT_NOT_VERIFIED']]){const h=await confirmReady();h.queue.push(response);await h.callback({credential:'fake-write'});stopped(h,code,'WRITE_SUBMITTED');assert.equal(h.requests.filter(r=>r.path==='/supervised/confirm').length,1);}
  {const h=await confirmReady();let resolve;h.queue.push({ok:true,json:()=>new Promise(r=>resolve=r)});const pending=h.callback({credential:'fake-write'});await tick();h.get('consent').onchange();stopped(h,'CONSENT_CHANGED','WRITE_SUBMITTED');resolve({outcome:'VERIFIED',verifiedPhases:1,syntheticFunctionalRunVerified:false,productionCleanupCertified:false});await pending;stopped(h,'CONSENT_CHANGED','WRITE_SUBMITTED');}
  {const h=await confirmReady();h.queue.push(good({outcome:'VERIFIED',verifiedPhases:1,syntheticFunctionalRunVerified:false,productionCleanupCertified:false}));await h.callback({credential:'fake-write'});assert(h.status().startsWith('STAGE_VERIFIED'));assert.equal(h.requests.filter(r=>r.path==='/supervised/challenge').length,2);assert.deepEqual(JSON.parse(h.requests.find(r=>r.path==='/supervised/confirm').body),{proof:{nonce:challenge.nonce,credential:'fake-write',consent:true,writersFrozen:true},planId:preview.planId,exactConfirmation:preview.confirmation});assert.equal(h.get('exact').value,'');assert.equal(h.get('consent').checked,false);assert.equal(h.get('frozen').checked,false);assert.equal(h.get('preview').disabled,true);assert.equal(h.requests.filter(r=>r.path==='/supervised/next').length,0);}
  console.log('PASS: actual UI script health, Google readiness/setup, challenge, timeout, cancellation, proof, network/decode/rejection, invalid/expired preview, submitted-write uncertainty, stale callbacks, exact stage approval and no retry/reset/advance.');
})().catch(error=>{console.error(error);process.exitCode=1;});
