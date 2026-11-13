'use strict';
(() => {
  const $ = id => document.getElementById(id), base = '/initial-cutover/';
  let config, busy = false, stopped = false, next = null, authPending = false, activePreview = null, stageName = 'prepare';
  let authGeneration=0, sessionCreated=false, prepareSubmitted=false;
  const results = [];
  const names = {metadata:'管理紀錄',history:'歷史紀錄',activate:'啟用'};
  const verifiedLabels = {METADATA_VERIFIED:'管理紀錄',HISTORY_VERIFIED:'歷史紀錄',ACTIVATE_VERIFIED:'啟用'};
  function status(text, detail = '') { $('status').textContent = text; $('detail').textContent = detail; }
  function record(code) { results.push(`${new Date().toISOString()} ${code}`); $('result').textContent = results.join('\n'); }
  function update() {
    const unchecked = [...$('checks').querySelectorAll('input')].some(c => !c.checked);
    $('action').disabled = stopped || busy || !next || unchecked;
    $('reason').textContent = stopped ? '流程已停止，請人工核對；不要重試或重新整理。' : busy ? (authPending ? '等待本次 Google 驗證。' : '等待伺服器回應，不會自動重送。') : unchecked ? '請先確認上方項目。' : next ? '只進行這一步，不會自動執行下一步寫入。' : '沒有可執行的下一步。';
  }
  function action(label, callback, checks = []) {
    $('action').textContent = label; next = callback; $('checks').replaceChildren();
    for (const text of checks) { const l = document.createElement('label'), c = document.createElement('input'), s = document.createElement('span'); c.type='checkbox'; c.addEventListener('change',update);s.textContent=text;l.append(c,s);$('checks').append(l); } update();
  }
  function stage(name) { stageName = name; for (const n of document.querySelectorAll('#steps li')) { if(n.id === 'step-'+(name==='activate'?'activation':name))n.setAttribute('aria-current','step');else n.removeAttribute('aria-current'); } }
  function show(fields) { $('preview').replaceChildren(); for(const [label,value] of fields){ const a=document.createElement('dt'),b=document.createElement('dd');a.textContent=label;b.textContent=String(value);$('preview').append(a,b); } }
  function halt(write,code) { stopped=true;authPending=false;$('google-wrap').hidden=true;status(write?'執行結果未確認，流程已停止。':'驗證或檢查未完成，流程已停止。',write?'已嘗試送出執行請求。請人工核對，不可重試。':'本步不會繼續執行，請將結果交給管理者。');record(code);update(); }
  async function request(path,body,write=false) {
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),90000);
    try { const r=await fetch(base+path,{method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal:controller.signal});const text=await r.text();if(text.length>65536)throw Error();const data=JSON.parse(text);if(!data||typeof data!=='object'||Array.isArray(data))throw Error();if(!r.ok){halt(write,write?'RESULT_REQUIRES_REVIEW':'READ_OR_AUTH_REQUIRES_REVIEW');if(verifiedLabels[data.lastVerifiedCode]){record('PREVIOUS_'+data.lastVerifiedCode);$('detail').textContent='先前的'+verifiedLabels[data.lastVerifiedCode]+'已驗證；本次結果仍須人工核對，不可重試。';}throw Error('stopped');}return data; }
    catch(_){if(!stopped)halt(write,write?'RESULT_REQUIRES_REVIEW':'READ_OR_AUTH_REQUIRES_REVIEW');throw Error('stopped');}finally{clearTimeout(timer);}
  }
  async function google(purpose) {
    const c=await request('challenge',{purpose});
    if(!/^[a-f0-9]{64}$/.test(c.nonce)||!window.google?.accounts?.id)throw Error('Google not ready');
    const ms=Math.min(120000,Date.parse(c.expires)-Date.now());if(!Number.isFinite(ms)||ms<=0)throw Error('expired');
    $('google-button').replaceChildren();$('google-wrap').hidden=false;
    const generation=++authGeneration;
    return new Promise((resolve,reject)=>{authPending=true;update();const timer=setTimeout(()=>{if(generation!==authGeneration)return;authPending=false;authGeneration++;const e=Error('authentication timeout');e.localPrepareAuth=!prepareSubmitted&&stageName==='prepare';reject(e);},ms);
      window.google.accounts.id.initialize({client_id:config.clientId,nonce:c.nonce,auto_select:false,callback:r=>{if(generation!==authGeneration||!authPending||stopped)return;authPending=false;clearTimeout(timer);$('google-wrap').hidden=true;if(typeof r.credential!=='string'||!r.credential||r.credential.length>12000)reject(Error());else resolve(r.credential);}});
      window.google.accounts.id.renderButton($('google-button'),{type:'standard',theme:'outline',size:'large',text:'signin_with',width:280});
    });
  }
  const digest = x => typeof x==='string'&&/^[a-f0-9]{64}$/.test(x);
  async function prepare() {
    const deploymentId=$('deployment').value.trim();if(!/^dep-[A-Za-z0-9-]{4,90}$/.test(deploymentId)){status('請填入本次 Render 部署 ID。','應以 dep- 開頭。尚未送出請求。');return;}
    if(!sessionCreated){await request('session',{});sessionCreated=true;}let credential=await google('prepare');status('正在唯讀檢查。','等待獨立部署核對，請勿重送。');
    prepareSubmitted=true;const r=await request('prepare',{credential,deploymentId});credential=null;
    if(r.code!=='READ_ONLY_PREPARED')throw Error('unexpected prepare');
    record('READ_ONLY_PREPARED');$('deployment-field').hidden=true;stage('metadata');status('唯讀檢查完成。');
    action('驗證並預覽管理紀錄',()=>readPreview('metadata'),['人工寫入來源均已停止，沒有已知結果未確認的寫入。']);
  }
  async function readPreview(kind) {
    let credential=await google(kind+'-preview');status('正在取得'+names[kind]+'預覽。','等待獨立核對，請勿重送。');
    const p=await request(kind+'/preview',{credential,stoppedSources:config.manualSources,noKnownUnresolved:true});credential=null;
    if(!digest(p.digest)||!Number.isFinite(Date.parse(p.expires)))throw Error('invalid preview');activePreview={kind,digest:p.digest,expires:p.expires,afterToken:p.details?.afterToken};
    // Only explicit reviewed presentation fields may be shown. Never render raw details, nonce, identity or tokens.
    const fields=previewFields(kind,p.details);fields.push(['有效期限',new Date(p.expires).toLocaleString('zh-TW',{timeZone:'Asia/Taipei',hour12:false})]);show(fields);
    record(kind.toUpperCase()+'_PREVIEW_READY');status(names[kind]+'預覽已就緒。','確認後才送出這一次執行請求。');
    action('確認'+names[kind]+'並執行',()=>execute(kind),[kind==='history'?'我確認只處理上述單筆休閒場：1 敗、積分 −1。':kind==='metadata'?'我確認只初始化管理紀錄，不刪除遊戲資料。':'我已核對啟用範圍，同意 B2 啟用。']);
  }
  function previewFields(kind,d) {
    if(!d||typeof d!=='object'||Array.isArray(d)||d.stage!==kind.toUpperCase()||!digest(d.beforeToken)||!digest(d.afterToken))throw Error('preview scope mismatch');
    if(kind==='metadata')return [['範圍','初始化管理紀錄；不刪除資料、不自動開站']];
    if(kind==='history'){
      const expected={matches:1,completedDeals:1,wins:0,losses:1,draws:0,totalScore:-1,scoredMatches:1,forfeitMatches:0,interruptedMatches:0};
      if(!d.contribution||Object.entries(expected).some(([k,v])=>d.contribution[k]!==v))throw Error('historical contribution mismatch');
      return [['範圍','既定單筆休閒場：1 場、1 敗、積分 −1'],['遊玩時間','保留原資料，不補造遊玩時間']];
    }
    for(const key of ['deployment','revision','policyVersion','policyZone','policyRules'])if(typeof d[key]!=='string'||d[key].length>1000)throw Error('invalid policy');
    return [['範圍','B2：啟用同一程序中的 Google 登入、遊戲保存與季度維護'],['部署',d.deployment],['版本',d.revision],['保留規則版本',d.policyVersion],['時區',d.policyZone],['保留規則',d.policyRules]];
  }
  async function execute(kind) {
    if(!activePreview||activePreview.kind!==kind||Date.now()>=Date.parse(activePreview.expires))throw Error('expired preview');
    let credential=await google(kind+'-execute');status('正在核對並執行'+names[kind]+'。','請勿重送；不會自動進行下一步寫入。');
    const r=await request(kind+'/execute',{credential,digest:activePreview.digest,approve:true},true);credential=null;
    const expected={metadata:'METADATA_VERIFIED',history:'HISTORY_VERIFIED',activate:'ACTIVATE_VERIFIED'};
    if(r.code!==expected[kind]||r.afterToken!==activePreview.afterToken){halt(true,'EXECUTION_REQUIRES_REVIEW');return;}
    record(expected[kind]);activePreview=null;
    if(kind==='activate'){status('B2 啟用已驗證。','此次切換完成；對外遊戲狀態仍由管理者核對。');action('操作完成',null);return;}
    const following=kind==='metadata'?'history':'activate';stage(following);status(names[kind]+'已驗證完成。','下一步仍需先預覽與明確確認。');action('驗證並預覽'+names[following],async()=>{let credential=await google('checkpoint');const renewed=await request('checkpoint',{credential});credential=null;if(renewed.code!=='CHECKPOINT_VERIFIED')throw Error('checkpoint failed');authGeneration++;await readPreview(following);},['人工寫入仍保持停止，沒有已知結果未確認的寫入。']);
  }
  $('action').addEventListener('click',async()=>{if(busy||stopped||$('action').disabled||!next)return;busy=true;update();try{await next();}catch(e){if(e.localPrepareAuth&&!prepareSubmitted&&!stopped){authPending=false;$('google-wrap').hidden=true;status('Google 驗證已逾時，尚未送出唯讀檢查。','可重新驗證；舊登入回應將失效。');record('AUTH_NOT_SUBMITTED');action('重新驗證',async()=>{await request('session/restart',{});authGeneration++;await prepare();});}else if(!stopped)halt(false,'LOCAL_AUTH_OR_RESPONSE_REQUIRES_REVIEW');}finally{busy=false;update();}});
  $('copy').addEventListener('click',async()=>{try{await navigator.clipboard.writeText($('result').textContent);$('copy-status').textContent='已複製，不含憑證與帳號資料。';}catch(_){$('copy-status').textContent='請選取上方結果手動複製。';}});
  async function init(){const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);try{const r=await fetch(base+'config',{credentials:'same-origin',cache:'no-store',redirect:'error',signal:controller.signal});if(!r.ok)throw Error();const text=await r.text();if(text.length>16384)throw Error();config=JSON.parse(text);if(!/^[A-Za-z0-9.-]+\.apps\.googleusercontent\.com$/.test(config.clientId)||!Array.isArray(config.manualSources)||config.manualSources.length>32||config.manualSources.some(x=>typeof x!=='string'||x.length>256))throw Error();stage('prepare');status('可以開始唯讀檢查。');action('Google 驗證並開始唯讀檢查',prepare);}catch(_){halt(false,'CONFIG_UNAVAILABLE');}finally{clearTimeout(timer);}}
  init();
})();
