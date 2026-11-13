// Local DOM/API mock only. Not a Google popup or browser/CSP acceptance test.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(require('path').join(__dirname,'../backend/src/main/resources/static/initial-cutover/operator.js'),'utf8');
class E{constructor(tag='div'){this.tag=tag;this.children=[];this.dataset={};this.handlers={};this.textContent='';this.value='';this.checked=false;}addEventListener(k,f){this.handlers[k]=f}append(...c){this.children.push(...c)}replaceChildren(){this.children=[]}querySelectorAll(tag){return this.children.flatMap(c=>[...(c.tag===tag?[c]:[]),...c.querySelectorAll(tag)])}setAttribute(k,v){this[k]=v}removeAttribute(k){delete this[k]}}
async function run(mode){const els={},calls=[],get=id=>els[id]??=new E(),token='a'.repeat(64),hash='b'.repeat(64),expires=new Date(Date.now()+300000).toISOString();let auth;
const fetch=async(path,o)=>{calls.push({path,body:o?.body?JSON.parse(o.body):null});let r={},ok=true;const stage=path.split('/')[2];
 if(path.endsWith('/config'))r={clientId:'123-test.apps.googleusercontent.com',manualSources:['manual']};
 else if(path.endsWith('/challenge'))r={nonce:hash,expires};
 else if(path.endsWith('/session'))r={expires};
 else if(path.endsWith('/prepare'))r={code:'READ_ONLY_PREPARED'};
 else if(path.endsWith('/checkpoint'))r={code:'CHECKPOINT_VERIFIED',expires};
 else if(path.endsWith('/preview'))r={digest:hash,expires,details:{stage:stage.toUpperCase(),beforeToken:token,afterToken:token,contribution:{matches:1,completedDeals:1,wins:0,losses:1,draws:0,totalScore:mode==='scope-mismatch'?100:-1,scoredMatches:1,forfeitMatches:0,interruptedMatches:0},deployment:'dep-test123',revision:'test',policyVersion:'v1',policyZone:'Asia/Taipei',policyRules:'quarter',operator:'DO_NOT_RENDER'}};
 else if(path.endsWith('/execute')){r={code:{metadata:'METADATA_VERIFIED',history:'HISTORY_VERIFIED',activate:'ACTIVATE_VERIFIED'}[stage],afterToken:token};if(mode==='unknown'&&stage==='history'){ok=false;r={code:'STOPPED_OR_UNCONFIRMED',lastVerifiedCode:'METADATA_VERIFIED',lastVerifiedAfterToken:token};}}
 return{ok,text:async()=>JSON.stringify(r)};};
vm.runInNewContext(source,{document:{getElementById:get,createElement:t=>new E(t),querySelectorAll:()=>['prepare','metadata','history','activation'].map(x=>{let e=get('step-'+x);e.id='step-'+x;return e})},window:{google:{accounts:{id:{initialize:c=>auth=c,renderButton:()=>queueMicrotask(()=>auth.callback({credential:'DO_NOT_RENDER'}))}}}},fetch,AbortController,setTimeout,clearTimeout,Date,navigator:{clipboard:{writeText:async()=>{}}}});
await new Promise(setImmediate);get('deployment').value='dep-test123';
async function click(){for(const e of get('checks').querySelectorAll('input')){e.checked=true;e.handlers.change()}assert(!get('action').disabled);await get('action').handlers.click()}
await click();await click();await click();await click();
if(mode==='scope-mismatch'){assert(get('action').disabled);assert(!calls.some(x=>x.path.endsWith('history/execute')))}else{await click();if(mode==='unknown'){assert(get('action').disabled);assert(get('detail').textContent.includes('先前的管理紀錄已驗證'));assert(!calls.some(x=>x.path.includes('/activate/')))}else{await click();await click();assert.equal(get('status').textContent,'B2 啟用已驗證。')}}
function text(e){return e.textContent+e.children.map(text).join('')}const rendered=Object.values(els).map(text).join('');assert(!rendered.includes('DO_NOT_RENDER'));assert(!rendered.includes(token));assert(!rendered.includes(hash));assert(!calls.some(x=>x.path.includes('/observation/')));const challenges=calls.filter(x=>x.path.endsWith('/challenge'));assert.equal(challenges.length,mode==='full'?9:mode==='unknown'?6:5);for(const c of calls.filter(x=>x.path.endsWith('/execute')))assert.equal(c.body.approve,true);
console.log(JSON.stringify({mode,passed:true,requests:calls.length}));}
(async()=>{for(const m of ['full','unknown','scope-mismatch'])await run(m)})().catch(e=>{console.error(e);process.exit(1)});

// Controlled timer/GSI callbacks: no real timer waits, Google popup, or browser claims.
async function restartScenario(rejectRestart){
 const els={},calls=[],callbacks=[],timers=new Map();let id=0;
 const get=x=>els[x]??=new E(), expires=new Date(Date.now()+300000).toISOString();
 const fetch=async(path,o)=>{calls.push(path);let ok=true,r={};
  if(path.endsWith('/config'))r={clientId:'123-test.apps.googleusercontent.com',manualSources:[]};
  else if(path.endsWith('/session/restart')){ok=!rejectRestart;r={expires};}
  else if(path.endsWith('/session'))r={expires};
  else if(path.endsWith('/challenge'))r={nonce:'b'.repeat(64),expires};
  else if(path.endsWith('/prepare'))r={code:'READ_ONLY_PREPARED'};
  else throw Error('Unexpected endpoint '+path);
  return {ok,text:async()=>JSON.stringify(r)};
 };
 vm.runInNewContext(source,{document:{getElementById:get,createElement:t=>new E(t),querySelectorAll:()=>[]},window:{google:{accounts:{id:{initialize:c=>callbacks.push(c.callback),renderButton:()=>{}}}}},fetch,AbortController,Date,setTimeout:(fn,ms)=>{timers.set(++id,{fn,ms});return id},clearTimeout:i=>timers.delete(i),navigator:{clipboard:{writeText:async()=>{}}}});
 const flush=()=>new Promise(setImmediate);await flush();get('deployment').value='dep-test123';
 const first=get('action').handlers.click();await flush();assert.equal(callbacks.length,1);
 const timeout=[...timers.values()].find(t=>t.ms===120000);assert(timeout);timeout.fn();await first;
 assert.equal(get('action').textContent,'重新驗證');assert(!get('action').disabled);
 assert.equal(calls.filter(x=>x.endsWith('/prepare')).length,0);
 callbacks[0]({credential:'old-before-restart'});await flush();assert.equal(calls.filter(x=>x.endsWith('/prepare')).length,0);
 const retry=get('action').handlers.click();await flush();
 assert.equal(calls.filter(x=>x.endsWith('/session/restart')).length,1);
 if(rejectRestart){await retry;assert(get('action').disabled);assert.equal(callbacks.length,1);callbacks[0]({credential:'late'});await get('action').handlers.click();assert.equal(calls.filter(x=>x.endsWith('/prepare')).length,0);assert.equal(calls.filter(x=>x.endsWith('/session/restart')).length,1);}
 else{assert.equal(callbacks.length,2);callbacks[0]({credential:'old-during-new-login'});await flush();assert.equal(calls.filter(x=>x.endsWith('/prepare')).length,0);callbacks[1]({credential:'fresh'});await retry;callbacks[1]({credential:'duplicate'});callbacks[0]({credential:'old-after-success'});await flush();assert.equal(calls.filter(x=>x.endsWith('/prepare')).length,1);assert.equal(calls.filter(x=>x.endsWith('/session')).length,1);assert.equal(get('status').textContent,'唯讀檢查完成。');}
 console.log(JSON.stringify({mode:rejectRestart?'restart-refused-halts':'timeout-restart-stale-callback-fenced',passed:true,requests:calls.length}));
}
(async()=>{await restartScenario(false);await restartScenario(true)})().catch(e=>{console.error(e);process.exit(1)});
