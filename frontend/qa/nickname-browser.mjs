// Verification status: NOT EXECUTED. 2026-11-14 Chromium startup was blocked
// by environment socket permissions; no browser scenarios were run.
// Real mounted App, intercepted local fixtures only. No real Google/Sheets requests.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '/opt/codex/cua_node/lib/node_modules/playwright');
const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
const base = process.env.ENTRY_BASE_URL || 'http://127.0.0.1:5173';
const reports = [];
for (const mode of ['success','slow','timeout','unconfirmed','missing','ime']) {
 const context = await browser.newContext({ serviceWorkers: 'block' });
 const page = await context.newPage();
 let saves = 0, linked = false, saved = false, release;
 const gate = new Promise(resolve => release = resolve);
 const errors=[]; page.on('pageerror', e=>errors.push(e.message));
 if(mode==='timeout') await page.addInitScript(()=>{ const original=AbortSignal.timeout.bind(AbortSignal); AbortSignal.timeout=ms=>original(ms===60000?150:ms); });
 await page.route('**/*', async route=>{
  const url=new URL(route.request().url());
  if(url.href==='https://accounts.google.com/gsi/client') return route.fulfill({contentType:'application/javascript',body:`window.google={accounts:{id:{initialize(o){window.fixtureCredential=o.callback},renderButton(e){let b=document.createElement('button');b.textContent='Mock Google';b.onclick=()=>window.fixtureCredential({credential:'fixture'});e.append(b)},cancel(){}}}};`});
  assert.equal(url.origin,new URL(base).origin,`Unexpected external request: ${url.origin}`);
  if(!url.pathname.startsWith('/api/')&&!url.pathname.startsWith('/health/')) return route.continue();
  let result;
  switch(url.pathname){
   case '/api/statistics/config': result={enabled:true,consentVersion:'statistics-v4'};break;
   case '/api/statistics/consent':result={accepted:true};break;
   case '/api/auth/config':result={enabled:true,verificationEnabled:true,clientId:'fixture',collectionMode:'managed',collectionReady:true,privacyContact:'fixture@example.test',retention:'quarter'};break;
   case '/api/sessions': result={token:'fixture-session',playerId:'fixture-player',displayName:'玩家'};break;
   case '/api/me':result={roomId:null};break;
   case '/api/rooms':result=[];break;
   case '/api/auth/nonce':result={nonce:'fixture-nonce'};break;
   case '/api/auth/google':linked=true;result={signedIn:true,nicknameRequired:true,nickname:null};break;
   case '/api/auth/cancel':result={cancelled:true};break;
   case '/api/auth/nickname':
    saves++; assert.equal(route.request().postDataJSON().nickname,'小白');
    if(mode==='slow'||mode==='timeout') await gate;
    saved=true; result={signedIn:true,nicknameRequired:false,nickname:'小白'};break;
   case '/api/auth/status':result=saved&&mode==='unconfirmed'?{signedIn:false}: {signedIn:linked,nicknameRequired:!saved||mode==='missing',nickname:saved&&mode!=='missing'?'小白':null};break;
   default: throw new Error(`Unexpected API ${url.pathname}`);
  }
  await route.fulfill({contentType:'application/json',body:JSON.stringify(result)}).catch(()=>{});
 });
 try {
  await page.goto(base);
  if(mode==='ime') {
   await page.getByRole('button',{name:'以訪客身分遊玩',exact:true}).click();
   await page.locator('#guest-name').dispatchEvent('compositionstart');
   await page.getByRole('button',{name:'返回身分選擇',exact:true}).click();
  }
  await page.getByRole('button',{name:'使用 Google 登入',exact:true}).click();
  await page.getByRole('button',{name:'Mock Google',exact:true}).click();
  await page.locator('#guest-name').waitFor();
  await page.locator('#guest-name').fill('小白');
  const submit=page.getByRole('button',{name:'確認暱稱並進入大廳',exact:true});
  await submit.click();
  if(mode==='slow') {
   await page.getByRole('button',{name:'正在保存暱稱…',exact:true}).waitFor();
   assert.equal(await page.getByRole('button',{name:'正在保存暱稱…',exact:true}).isDisabled(),true);
   // Even synthetic duplicate submits must not replay the mutation.
   await page.locator('form').first().dispatchEvent('submit');
   assert.equal(saves,1); release();
  }
  if(mode==='timeout') {
   await page.getByText('暱稱儲存尚未確認。',{exact:false}).waitFor();
   assert.equal(await submit.isDisabled(),true); assert.equal(await page.locator('#guest-name').inputValue(),'小白');
   await page.locator('form').first().dispatchEvent('submit'); assert.equal(saves,1); release();
  } else if(mode==='unconfirmed'||mode==='missing') {
   await page.getByText(mode==='unconfirmed'?'尚未確認登入狀態，':'已收到保存回應，',{exact:false}).waitFor();
   assert.equal(await submit.isDisabled(),true); assert.equal(await page.locator('#guest-name').inputValue(),'小白');
   assert.equal(await page.locator('.create-room').count(),0);
  } else { await page.locator('.create-room').waitFor(); }
  assert.equal(saves,1);assert.deepEqual(errors,[]);reports.push({mode,passed:true,saves});
 } finally {release();await context.close();}
}
await browser.close();console.log(JSON.stringify(reports,null,2));
