package com.bridge.maintenance;

/** Deliberately separate from the game frontend and service worker. No third-party code before consent. */
final class ExperimentBootstrapPage {
  static final String HTML="""
<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BridgeGo 單人實驗空表初始化</title>
<style>body{font-family:system-ui;max-width:720px;margin:24px auto;padding:0 16px}label{display:block;margin:12px 0}button{padding:12px;margin:4px 4px 4px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}textarea{box-sizing:border-box;width:100%;font:inherit;overflow-wrap:anywhere}#status{padding:12px;background:#f2f4f7}button:disabled{opacity:.55}</style>
<h1>單人實驗：空白資料集初始化</h1>
<p>僅供已批准的私人實驗資料集。這個維護程序不啟動遊戲，不寫入 Google 身分映射，也不表示刪除或季度清理已驗收。先停止所有遊戲、匯入及維護寫入程序。</p>
<h2>1. 驗證並預覽（不寫入）</h2>
<label><input id="consent" type="checkbox">我同意以 Google 驗證指定測試帳號，讀取這份已批准資料集並確認它完全沒有帳號或牌局資料。憑證不會保存。</label>
<button id="preview">Google 驗證並預覽空表</button><button id="cancel" hidden>取消這次驗證</button><div id="google"></div><p id="status" role="status" aria-live="polite">請先閱讀並勾選 Google 驗證同意。</p>
<section id="plan" hidden><h2>2. 核對初始化範圍</h2><p id="counts"></p><p>執行會建立本次實驗的非個資一次性標記及當季初始化紀錄。這不是刪除既有資料。只有完整空表才可初始化，標記不可移除或改名來重新取得名額。結果不明時停止，請管理者核對。</p>
<pre id="phrase"></pre><button id="copy">複製並填入完整確認文字</button><label>完整確認文字<textarea id="confirmation" rows="5" autocomplete="off" spellcheck="false"></textarea></label>
<label><input id="understood" type="checkbox">我確認指定表、所有寫入已停止，並同意本次空表初始化。</label><h2>3. 再次驗證並執行</h2><p>下一次 Google 驗證成功後會立即送出初始化，不會再多一道確認。送出後無法取消，請勿重複操作。</p><button id="execute" disabled>再次驗證並初始化</button></section>
<script src="/maintenance/app.js" defer></script></body></html>
""";
  static final String JS="""
'use strict';
const $=id=>document.getElementById(id);
let config=null,plan=null,attempt=0,busy=false,locked=false,writeStarted=false,authTimer=null,planTimer=null,pendingRead=null;
const messages={WRITER_SHUTDOWN_REQUIRED:'尚未取得管理者已停止所有寫入程序的聲明。',INITIALIZATION_UNAVAILABLE:'初始化未啟用、已嘗試或已過期，請停止並聯絡管理者。',MAINTENANCE_SESSION_EXPIRED:'維護工作階段已過期，請重新載入頁面。',ORIGIN_NOT_ALLOWED:'來源驗證失敗。',RATE_LIMITED:'請稍後再試。'};
function status(text){$('status').textContent=text;}
function current(id){return id===attempt&&!locked&&$('consent').checked;}
function clearAuthTimer(){clearTimeout(authTimer);authTimer=null;}
function clearPlan(){clearTimeout(planTimer);planTimer=null;plan=null;$('plan').hidden=true;$('confirmation').value='';$('understood').checked=false;}
function planFresh(){return !!plan&&Date.now()<Date.parse(plan.expiresAt);}
function canExecute(){return !!(config&&config.executionEnabled&&planFresh()&&$('consent').checked&&$('understood').checked&&$('confirmation').value===plan.confirmationText&&!busy&&!locked);}
function update(){
 $('execute').disabled=!canExecute();$('preview').disabled=busy||locked||!$('consent').checked;
 $('preview').textContent=plan?'重新預覽（原確認將失效）':'Google 驗證並預覽空表';
 $('cancel').hidden=!busy||writeStarted;$('copy').disabled=!planFresh()||busy||locked;
 $('understood').disabled=busy||locked;$('confirmation').readOnly=busy||locked;$('consent').disabled=writeStarted||locked;
}
function cancelAuth(message){
 if(writeStarted||locked)return;
 attempt++;clearAuthTimer();if(pendingRead)pendingRead.abort();pendingRead=null;
 $('google').replaceChildren();try{if(globalThis.google?.accounts?.id?.cancel)google.accounts.id.cancel();}catch(ignored){}
 busy=false;status(message);update();
}
function armPlanExpiry(){
 clearTimeout(planTimer);planTimer=setTimeout(()=>{if(writeStarted||locked)return;cancelAuth('預覽已過期，尚未送出初始化。請重新取得預覽。');clearPlan();update();},Math.max(0,Date.parse(plan.expiresAt)-Date.now()));
}
async function post(path,body,isWrite=false){
 const controller=new AbortController();if(!isWrite)pendingRead=controller;
 const timeout=setTimeout(()=>controller.abort(),45000);
 try{const r=await fetch('/maintenance/'+path,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify(body||{}),signal:controller.signal});const v=await r.json();if(!r.ok)throw new Error(messages[v.code]||'操作未完成，請停止並聯絡管理者。');return v;}
 catch(e){if(e.name==='AbortError')throw new Error(isWrite?'初始化回應逾時，結果不確定。請保持停寫並聯絡管理者，勿重試。':'連線逾時或已取消，尚未送出初始化。');throw e;}
 finally{clearTimeout(timeout);if(pendingRead===controller)pendingRead=null;}
}
let scriptPromise;
function googleScript(){if(!scriptPromise)scriptPromise=new Promise((resolve,reject)=>{const s=document.createElement('script');let settled=false;const failed=()=>{if(settled)return;settled=true;clearTimeout(timer);scriptPromise=null;s.remove();reject(new Error('Google 驗證服務載入失敗或逾時，尚未送出初始化。'));};const timer=setTimeout(failed,20000);s.src='https://accounts.google.com/gsi/client';s.onload=()=>{if(settled)return;settled=true;clearTimeout(timer);resolve();};s.onerror=failed;document.head.append(s);});return scriptPromise;}
$('confirmation').addEventListener('input',update);$('understood').addEventListener('change',update);
$('cancel').addEventListener('click',()=>cancelAuth('已取消這次驗證，尚未送出初始化。'));
$('consent').addEventListener('change',()=>{if(!$('consent').checked)cancelAuth('已停止本頁驗證流程，尚未送出初始化。');update();});
$('copy').addEventListener('click',async()=>{
 if(!planFresh()||busy||locked)return;const selected=plan;const phrase=selected.confirmationText;
 $('confirmation').value=phrase;update();
 try{if(!navigator.clipboard?.writeText)throw new Error('Clipboard unavailable');await navigator.clipboard.writeText(phrase);if(plan===selected&&!busy&&!locked)status('已複製並填入完整文字。請核對範圍、勾選確認，再按步驟 3。');}
 catch(ignored){if(plan===selected&&!busy&&!locked)status('已填入完整文字；瀏覽器未允許複製到剪貼簿。請核對範圍、勾選確認，再按步驟 3。');}
});
async function start(purpose){
 if(busy||locked)return;if(!$('consent').checked){status('請先閱讀並勾選 Google 驗證同意。');return;}if(purpose==='execute'&&!canExecute())return;
 const id=++attempt;const selectedPlan=plan,confirmation=$('confirmation').value;
 if(purpose==='preview')clearPlan();busy=true;writeStarted=false;status('正在準備 Google 驗證，可隨時取消。');update();
 authTimer=setTimeout(()=>{if(current(id)&&!writeStarted)cancelAuth('Google 驗證等待逾時，尚未送出初始化。可以重新準備驗證。');},120000);
 try{
  if(!config){const c=await post('session');if(!current(id))return;config=c;}
  if(config.mode!=='experiment-bootstrap')throw new Error('維護模式不符，請停止。');if(!config.writerStoppedAcknowledged)throw new Error(messages.WRITER_SHUTDOWN_REQUIRED);
  const challenge=await post('challenge',{purpose});if(!current(id))return;await googleScript();if(!current(id))return;
  status(purpose==='preview'?'請點下方 Google 帳號按鈕完成預覽；若視窗已關閉，請按「取消這次驗證」。':'請再次驗證同一 Google 帳號；成功後會立即初始化，送出前仍可取消。');$('google').replaceChildren();
  let credentialConsumed=false;
  google.accounts.id.initialize({client_id:config.clientId,nonce:challenge.nonce,auto_select:false,callback:async result=>{
   let credential=result.credential;result.credential='';
   if(credentialConsumed||!current(id)){credential='';return;}credentialConsumed=true;clearAuthTimer();$('google').replaceChildren();
   try{
    if(purpose==='preview'){
     status('Google 驗證及空表讀取中，尚未寫入。');const p=await post('preview',{credential,consent:true});credential='';if(!current(id))return;
     plan=p;$('plan').hidden=false;$('counts').textContent='指定表：'+p.target+'；資料集：'+p.dataset+'；預覽有效至：'+new Date(p.expiresAt).toLocaleTimeString();$('phrase').textContent=p.confirmationText;$('confirmation').value='';$('understood').checked=false;armPlanExpiry();
     status(config.executionEnabled?'預覽完成，尚未寫入。請往下完成步驟 2、3，不必再按上方預覽。':'預覽完成。目前為唯讀演練，執行開關未啟用。');
    }else{
     if(plan!==selectedPlan||!planFresh()||!$('understood').checked||confirmation!==plan.confirmationText)throw new Error('確認已失效，尚未送出初始化。請重新核對。');
     writeStarted=true;clearTimeout(planTimer);update();status('初始化已送出，無法取消。請等待結果，勿重複操作或關閉頁面。');
     const r=await post('execute',{credential,consent:true,planId:selectedPlan.planId,confirmation},true);credential='';locked=true;clearPlan();
     status(r.outcome==='VERIFIED'?'空表初始化已核對完成；這不代表正式收集或刪除已驗收。':r.outcome==='NOT_APPLIED'?'初始化未套用。請聯絡管理者，勿自動重試。':'初始化結果不確定。請保持停寫並聯絡管理者，勿重試。');
    }
   }catch(e){if(id!==attempt)return;if(writeStarted){locked=true;clearPlan();status('初始化未取得確定成功回應。請保持停寫並聯絡管理者，勿重試。');}else status(e.message);}
   finally{credential='';if(id===attempt){busy=false;update();}}
  }});
  google.accounts.id.renderButton($('google'),{type:'standard',text:'continue_with',theme:'outline',size:'large'});
 }catch(e){if(id!==attempt||writeStarted)return;cancelAuth(e.message);}
}
$('preview').addEventListener('click',()=>start('preview'));$('execute').addEventListener('click',()=>start('execute'));
globalThis.addEventListener('pagehide',()=>{if(!writeStarted&&!locked)cancelAuth('頁面已離開，請重新驗證。');});
globalThis.addEventListener('beforeunload',e=>{if(writeStarted&&busy){e.preventDefault();e.returnValue='';}});
update();
""";
}
