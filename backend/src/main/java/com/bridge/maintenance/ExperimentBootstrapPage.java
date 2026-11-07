package com.bridge.maintenance;

/** Deliberately separate from the game frontend and service worker. No third-party code before consent. */
final class ExperimentBootstrapPage {
  static final String HTML="""
<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BridgeGo 單人實驗空表初始化</title>
<style>body{font-family:system-ui;max-width:720px;margin:24px auto;padding:0 16px}label{display:block;margin:12px 0}button{padding:12px}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style>
<h1>單人實驗：空白資料集初始化</h1>
<p>僅供已批准的私人實驗資料集。這個維護程序不啟動遊戲，不寫入 Google 身分映射，也不表示刪除或季度清理已驗收。先停止所有遊戲、匯入及維護寫入程序。</p>
<label><input id="consent" type="checkbox">我同意以 Google 驗證指定測試帳號，讀取這份已批准資料集並確認它完全沒有帳號或牌局資料。憑證不會保存。</label>
<button id="preview">Google 驗證並預覽空表</button><div id="google"></div><p id="status" role="status"></p>
<section id="plan" hidden><p id="counts"></p><p>執行會建立本次實驗的非個資一次性標記及當季初始化紀錄。這不是刪除既有資料。只有完整空表才可初始化，標記不可移除或改名來重新取得名額。結果不明時停止，請管理者核對。</p>
<pre id="phrase"></pre><label>完整確認文字<input id="confirmation" type="text" autocomplete="off" style="width:95%"></label>
<label><input id="understood" type="checkbox">我確認指定表、所有寫入已停止，並同意本次空表初始化。</label><button id="execute" disabled>再次驗證並初始化</button></section>
<script src="/maintenance/app.js" defer></script></body></html>
""";
  static final String JS="""
'use strict';
const $=id=>document.getElementById(id);let config=null,plan=null,attempt=0,busy=false;
const messages={WRITER_SHUTDOWN_REQUIRED:'尚未取得管理者已停止所有寫入程序的聲明。',INITIALIZATION_UNAVAILABLE:'初始化未啟用、已嘗試或已過期，請停止並聯絡管理者。',MAINTENANCE_SESSION_EXPIRED:'維護工作階段已過期，請重新載入頁面。',ORIGIN_NOT_ALLOWED:'來源驗證失敗。',RATE_LIMITED:'請稍後再試。'};
function status(text){$('status').textContent=text;}
async function post(path,body){const r=await fetch('/maintenance/'+path,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify(body||{})});const v=await r.json();if(!r.ok)throw new Error(messages[v.code]||'操作未完成。請重新取得預覽；若初始化結果不明，勿自動重試，請聯絡管理者。');return v;}
let scriptPromise;
function googleScript(){if(!scriptPromise)scriptPromise=new Promise((resolve,reject)=>{const s=document.createElement('script');s.src='https://accounts.google.com/gsi/client';s.onload=resolve;s.onerror=()=>{scriptPromise=null;reject(new Error('Google 驗證服務載入失敗。'));};document.head.append(s);});return scriptPromise;}
function canExecute(){return !!(config&&config.executionEnabled&&plan&&$('understood').checked&&$('confirmation').value===plan.confirmationText&&!busy);}
function update(){$('execute').disabled=!canExecute();$('preview').disabled=busy;}
$('confirmation').addEventListener('input',update);$('understood').addEventListener('change',update);
$('consent').addEventListener('change',()=>{if(!$('consent').checked){attempt++;$('google').replaceChildren();busy=false;update();status('已停止本頁驗證流程。已送出的初始化請求無法由取消勾選撤回。');}});
async function start(purpose){if(busy)return;if(!$('consent').checked){status('請先閱讀並勾選 Google 驗證同意。');return;}if(purpose==='execute'&&!canExecute())return;
 const id=++attempt;busy=true;update();const selectedPlan=plan;const confirmation=$('confirmation').value;
 try{if(!config)config=await post('session');if(config.mode!=='experiment-bootstrap')throw new Error('維護模式不符，請停止。');if(!config.writerStoppedAcknowledged)throw new Error(messages.WRITER_SHUTDOWN_REQUIRED);const challenge=await post('challenge',{purpose});await googleScript();if(id!==attempt)return;
 status(purpose==='preview'?'請選擇 Google 帳號以取得預覽。':'請再次驗證預覽中的同一 Google 帳號。');$('google').replaceChildren();
 let credentialConsumed=false;google.accounts.id.initialize({client_id:config.clientId,nonce:challenge.nonce,auto_select:false,callback:async result=>{if(credentialConsumed||id!==attempt||!$('consent').checked)return;credentialConsumed=true;let credential=result.credential;result.credential='';$('google').replaceChildren();
 try{if(purpose==='preview'){const p=await post('preview',{credential,consent:true});credential='';if(id!==attempt)return;plan=p;$('plan').hidden=false;$('counts').textContent='指定表：'+p.target+'；資料集：'+p.dataset+'；完整空表預覽到期：'+p.expiresAt;$('phrase').textContent=p.confirmationText;$('confirmation').value='';$('understood').checked=false;status(config.executionEnabled?'預覽完成。請確認範圍及確認文字。':'預覽完成。目前為唯讀演練，執行開關未啟用。');}
 else{status('正在執行；請勿重複送出或關閉頁面。');const r=await post('execute',{credential,consent:true,planId:selectedPlan.planId,confirmation});credential='';plan=null;$('plan').hidden=true;status(r.outcome==='VERIFIED'?'空表初始化已核對完成；這不代表正式收集或刪除已驗收。':r.outcome==='NOT_APPLIED'?'初始化未套用，請聯絡管理者，勿自動重試。':'初始化結果不確定，請保持停寫並聯絡管理者，勿重試。');}}
 catch(e){status(e.message);}finally{credential='';busy=false;update();}}});google.accounts.id.renderButton($('google'),{type:'standard',text:'continue_with',theme:'outline',size:'large'});
 }catch(e){status(e.message);busy=false;update();}}
$('preview').addEventListener('click',()=>start('preview'));$('execute').addEventListener('click',()=>start('execute'));
""";
}
