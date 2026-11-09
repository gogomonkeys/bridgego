package com.bridge.maintenance;

/** Deliberately separate from the game frontend and service worker. No third-party code before consent. */
final class MaintenancePage {
  static final String HTML="""
<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>帳號資料刪除維護</title>
<style>body{font:18px system-ui;max-width:760px;margin:40px auto;padding:20px;line-height:1.65}button,input{font:inherit;padding:8px}button{margin:10px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}fieldset{margin:20px 0}#status{font-weight:bold}label{display:block}</style></head><body>
<h1>帳號資料刪除維護</h1><p>此模式不提供遊戲或新增帳號。僅處理你透過 Google 驗證的本人帳號，不接受他人的帳號 ID。</p>
<p>必須先由管理者實際停止所有遊戲及帳號資料寫入程序。系統開關只是管理者聲明，無法證明其他程序已停止。</p>
<label><input id="consent" type="checkbox">我同意載入 Google 登入服務，將這次驗證憑證傳給本站以確認本人身分並讀取刪除預覽。憑證不會儲存或顯示。</label>
<button id="preview">驗證本人並預覽</button><div id="google"></div><p id="status" role="status"></p>
<section id="plan" hidden><h2>本次刪除預覽</h2><pre id="counts"></pre>
<p>執行會移除本人的帳號與 Google 對應資料，並清除本人參與紀錄中的帳號／訪客識別欄位，改為隨機刪除標記。場次、分數及其他人的紀錄保留。防止重新建立對應的帳號與 Google 身分雜湊標記會在此資料集存續期間保留，並阻擋重新註冊；這是去識別化處理，不保證法律上的完全匿名。此操作僅處理現用資料表 A:E 欄，不會清除 F 欄以後的資料、Google Sheets 的歷史版本、備份或先前匯出的檔案，也不會推測訪客紀錄屬於你。</p>
<p>這是不可由本工具還原的資料變更。請核對範圍，複製下方完整確認文字貼入欄位，再次驗證同一 Google 帳號後才會執行。重新驗證及預覽皆會過期。</p>
<pre id="phrase"></pre><label>完整確認文字<input id="confirmation" type="text" autocomplete="off" style="width:95%"></label>
<label><input id="understood" type="checkbox">我已閱讀上述保留範圍與不可還原影響，確認要執行本次預覽的刪除。</label>
<button id="execute" disabled>再次驗證本人並執行刪除</button></section>
<script src="/maintenance/app.js" defer></script></body></html>
""";
  static final String JS="""
'use strict';
const $=id=>document.getElementById(id);let config=null,plan=null,attempt=0,busy=false;
const messages={WRITER_SHUTDOWN_REQUIRED:'尚未取得管理者已停止所有寫入程序的聲明。',ERASURE_DRY_RUN_ONLY:'目前只允許預覽，刪除執行尚未啟用。',MAINTENANCE_SESSION_EXPIRED:'維護工作階段已過期，請重新載入頁面。',ORIGIN_NOT_ALLOWED:'來源驗證失敗。',RATE_LIMITED:'請稍後再試。'};
function status(text){$('status').textContent=text;}
async function post(path,body){const r=await fetch('/maintenance/'+path,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify(body||{})});const v=await r.json();if(!r.ok)throw new Error(messages[v.code]||'操作未完成。請重新取得預覽；若刪除結果不明，勿自動重試，請聯絡管理者。');return v;}
let scriptPromise;
function googleScript(){if(!scriptPromise)scriptPromise=new Promise((resolve,reject)=>{const s=document.createElement('script');s.src='https://accounts.google.com/gsi/client';s.onload=resolve;s.onerror=()=>{scriptPromise=null;reject(new Error('Google 驗證服務載入失敗。'));};document.head.append(s);});return scriptPromise;}
function canExecute(){return !!(config&&config.executionEnabled&&plan&&$('understood').checked&&$('confirmation').value===plan.confirmationText&&!busy);}
function update(){$('execute').disabled=!canExecute();$('preview').disabled=busy;}
$('confirmation').addEventListener('input',update);$('understood').addEventListener('change',update);
$('consent').addEventListener('change',()=>{if(!$('consent').checked){attempt++;$('google').replaceChildren();busy=false;update();status('已停止本頁驗證流程。已送出的刪除請求無法由取消勾選撤回。');}});
async function start(purpose){if(busy)return;if(!$('consent').checked){status('請先閱讀並勾選 Google 驗證同意。');return;}if(purpose==='execute'&&!canExecute())return;
 const id=++attempt;busy=true;update();const selectedPlan=plan;const confirmation=$('confirmation').value;
 try{if(!config)config=await post('session');if(!config.writerStoppedAcknowledged)throw new Error(messages.WRITER_SHUTDOWN_REQUIRED);const challenge=await post('challenge',{purpose});await googleScript();if(id!==attempt)return;
 status(purpose==='preview'?'請選擇 Google 帳號以取得預覽。':'請再次驗證預覽中的同一 Google 帳號。');$('google').replaceChildren();
 google.accounts.id.initialize({client_id:config.clientId,nonce:challenge.nonce,auto_select:false,callback:async result=>{if(id!==attempt||!$('consent').checked)return;let credential=result.credential;result.credential='';$('google').replaceChildren();
 try{if(purpose==='preview'){const p=await post('preview',{credential,consent:true});credential='';if(id!==attempt)return;plan=p;$('plan').hidden=false;$('counts').textContent='受影響場次：'+p.affectedMatches+'；本人座位紀錄：'+p.affectedSeats+'；已完成刪除：'+p.alreadyErased+'；預覽到期：'+p.expiresAt;$('phrase').textContent=p.confirmationText;$('confirmation').value='';$('understood').checked=false;status(config.executionEnabled?'預覽完成。請確認範圍及確認文字。':'預覽完成。目前為唯讀演練，執行開關未啟用。');}
 else{status('正在執行；請勿重複送出或關閉頁面。');const r=await post('execute',{credential,consent:true,planId:selectedPlan.planId,confirmation});credential='';plan=null;$('plan').hidden=true;status(r.outcome==='VERIFIED'||r.outcome==='ALREADY_ERASED'?'已核對刪除結果。保留範圍如上方說明。':r.outcome==='NOT_APPLIED'?'已核對資料未套用刪除。請聯絡管理者，勿自動重試。':'刪除結果不確定。請聯絡管理者核對，勿重試。');}}
 catch(e){status(e.message);}finally{credential='';busy=false;update();}}});google.accounts.id.renderButton($('google'),{type:'standard',text:'continue_with',theme:'outline',size:'large'});
 }catch(e){status(e.message);busy=false;update();}}
$('preview').addEventListener('click',()=>start('preview'));$('execute').addEventListener('click',()=>start('execute'));
""";
}
