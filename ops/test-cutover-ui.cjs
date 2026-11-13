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
 else if(path.endsWith('/preview'))r={digest:hash,expires,details:{stage:stage.toUpperCase(),beforeToken:token,afterToken:token,contribution:{matches:1,completedDeals:1,wins:0,losses:1,draws:0,totalScore:mode==='scope-mismatch'?100:-1,scoredMatches:1,forfeitMatches:0,interruptedMatches:0},deployment:'dep-test123',revision:'test',policyVersion:'v1',policyZone:'Asia/Taipei',policyRules:'quarter',operator:'DO_NOT_RENDER'}};
 else if(path.endsWith('/execute')){r={code:{metadata:'METADATA_VERIFIED',history:'HISTORY_VERIFIED',activate:'ACTIVATE_VERIFIED'}[stage],afterToken:token};if(mode==='unknown'&&stage==='history'){ok=false;r={code:'STOPPED_OR_UNCONFIRMED',lastVerifiedCode:'METADATA_VERIFIED',lastVerifiedAfterToken:token};}}
 return{ok,text:async()=>JSON.stringify(r)};};
vm.runInNewContext(source,{document:{getElementById:get,createElement:t=>new E(t),querySelectorAll:()=>['prepare','metadata','history','activation'].map(x=>{let e=get('step-'+x);e.id='step-'+x;return e})},window:{google:{accounts:{id:{initialize:c=>auth=c,renderButton:()=>queueMicrotask(()=>auth.callback({credential:'DO_NOT_RENDER'}))}}}},fetch,AbortController,setTimeout,clearTimeout,Date,navigator:{clipboard:{writeText:async()=>{}}}});
await new Promise(setImmediate);get('deployment').value='dep-test123';
async function click(){for(const e of get('checks').querySelectorAll('input')){e.checked=true;e.handlers.change()}assert(!get('action').disabled);await get('action').handlers.click()}
await click();await click();await click();await click();
if(mode==='scope-mismatch'){assert(get('action').disabled);assert(!calls.some(x=>x.path.endsWith('history/execute')))}else{await click();if(mode==='unknown'){assert(get('action').disabled);assert(get('detail').textContent.includes('先前的管理紀錄已驗證'));assert(!calls.some(x=>x.path.includes('/activate/')))}else{await click();await click();assert.equal(get('status').textContent,'B2 啟用已驗證。')}}
function text(e){return e.textContent+e.children.map(text).join('')}const rendered=Object.values(els).map(text).join('');assert(!rendered.includes('DO_NOT_RENDER'));assert(!rendered.includes(token));assert(!rendered.includes(hash));assert(!calls.some(x=>x.path.includes('/observation/')));const challenges=calls.filter(x=>x.path.endsWith('/challenge'));assert.equal(challenges.length,mode==='full'?7:mode==='unknown'?5:4);for(const c of calls.filter(x=>x.path.endsWith('/execute')))assert.equal(c.body.approve,true);
console.log(JSON.stringify({mode,passed:true,requests:calls.length}));}
(async()=>{for(const m of ['full','unknown','scope-mismatch'])await run(m)})().catch(e=>{console.error(e);process.exit(1)});
