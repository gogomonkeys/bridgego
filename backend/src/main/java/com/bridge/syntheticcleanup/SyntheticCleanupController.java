package com.bridge.syntheticcleanup;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
final class SyntheticCleanupController {
  private final SyntheticCleanupService service;
  SyntheticCleanupController(SyntheticCleanupService service) { this.service = service; }
  record Proof(String nonce, String credential, Boolean consent) {}
  @GetMapping("/health/ready") Map<String,Object> health() { return service.health(); }
  @GetMapping(value="/", produces=MediaType.TEXT_HTML_VALUE) ResponseEntity<String> page() {
    return ResponseEntity.ok().header("Content-Security-Policy", "default-src 'none'; script-src 'self' https://accounts.google.com/gsi/client; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; style-src 'unsafe-inline' https://accounts.google.com/gsi/style; img-src 'self' https://*.googleusercontent.com; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
        .body(HTML);
  }
  @GetMapping(value="/preflight.js", produces="application/javascript") String script() { return JS; }
  @PostMapping("/preflight/challenge") SyntheticCleanupService.Challenge challenge() { return service.challenge(); }
  @PostMapping("/preflight/read") Object read(@RequestBody Proof proof) { service.preflight(proof.nonce(), proof.credential(), proof.consent());
    return Map.of("code", "PREFLIGHT_SUCCESS", "authentication", "SUCCESS", "read", "SUCCESS", "schema", "SUCCESS",
        "retryAfterSeconds", service.retryAfterSeconds(), "cleanupReady", false, "writesEnabled", false); }
  @ExceptionHandler(SyntheticCleanupService.Failure.class) ResponseEntity<?> failure(SyntheticCleanupService.Failure failure) {
    return ResponseEntity.status(failure.status).header("Retry-After", Integer.toString(failure.retryAfterSeconds)).body(failure.body());
  }
  @ExceptionHandler(Exception.class) ResponseEntity<?> invalid(Exception ignored) {
    return ResponseEntity.badRequest().body(Map.of("code", "PREFLIGHT_REQUEST_REJECTED"));
  }
  private static final String HTML = """
      <!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>BridgeGo 唯讀預檢</title><style>body{font:18px system-ui;margin:2rem auto;padding:0 1rem;max-width:36rem;line-height:1.6}button{font:inherit;padding:.6rem}pre{white-space:pre-wrap;overflow-wrap:anywhere}label{display:block;margin:1rem 0}</style>
      <h1>合成測試表唯讀預檢</h1><p>只檢查指定測試表的完整資料與註解可見性。不寫入、不執行帳號刪除或季度清理。</p>
      <label><input id="consent" type="checkbox">我同意這次讀取指定的合成測試表。</label>
      <button id="start" disabled>以 Google 驗證並預檢一次</button><div id="google"></div><pre id="status" role="status">檢查服務設定中…</pre><h2>本頁最近完成的結果</h2><pre id="result">尚未執行。重新整理後不保留結果；伺服器冷卻仍有效。</pre>
      <script src="https://accounts.google.com/gsi/client" async defer></script><script src="/preflight.js" defer></script></html>
      """;
  private static final String JS = """
      'use strict';
      const button=document.getElementById('start'),consent=document.getElementById('consent'),output=document.getElementById('result'),status=document.getElementById('status'),holder=document.getElementById('google');
      let ready=false,active=null,sequence=0,cooldownUntil=0,wasCooling=false;
      const labels={NOT_ATTEMPTED:'未執行',UNVERIFIED:'未確認',SUCCESS:'通過'};
      const codes=new Set(['PREFLIGHT_SUCCESS','OPERATOR_AUTHENTICATION_FAILED','OPERATOR_NOT_ALLOWED','FRESH_AUTHENTICATION_REQUIRED','READ_COOLDOWN','PREFLIGHT_BUSY','COMPLETE_PREFLIGHT_UNVERIFIED','EXPLICIT_READ_CONSENT_REQUIRED','PREFLIGHT_NOT_CONFIGURED','PREFLIGHT_REQUEST_REJECTED']);
      function seconds(){return Math.max(0,Math.ceil((cooldownUntil-Date.now())/1000));}
      function update(){button.disabled=!ready||!consent.checked||active!==null||seconds()>0;
        if(!active&&seconds()>0)status.textContent='冷卻剩餘 '+seconds()+' 秒；結束後請明確開始新一次驗證，不會自動重試。';
        else if(!active&&ready&&wasCooling)status.textContent='冷卻已結束；請明確開始新一次驗證。先前結果保留於下方。';
        wasCooling=seconds()>0;}
      function cooldown(j){if(Number.isInteger(j.retryAfterSeconds)&&j.retryAfterSeconds>=0&&j.retryAfterSeconds<=60)cooldownUntil=Math.max(cooldownUntil,Date.now()+j.retryAfterSeconds*1000);}
      function result(j){const phase=k=>Object.hasOwn(labels,j[k])?labels[j[k]]:'未確認';
        output.textContent=['結果代碼：'+(codes.has(j.code)?j.code:'PREFLIGHT_UNVERIFIED'),'Google 操作者驗證：'+phase('authentication'),'完整唯讀讀取：'+phase('read'),'格式與註解檢查：'+phase('schema'),'清理未執行；清理仍未就緒；寫入停用。'].join(String.fromCharCode(10));}
      async function post(path,body){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),credentials:'omit',cache:'no-store'});const j=await r.json();return {ok:r.ok,data:j};}
      function finish(a){if(active!==a)return;active=null;holder.replaceChildren();status.textContent=a.cancelled?'本次等待已結束；取消後不會自動重試。若已送出讀取，請查看下方結果。':'本次請求已結束；請查看下方結果。新一次需手動開始。';update();}
      consent.onchange=()=>{if(active){active.cancelled=true;holder.replaceChildren();
        if(active.phase==='google')finish(active);
        else status.textContent=active.phase==='read'?'已取消後續操作；已送出的讀取無法撤回，等待本次結果。':'已取消後續操作；等待目前請求結束。';}update();};
      setInterval(update,250);
      fetch('/health/ready',{cache:'no-store',credentials:'omit'}).then(r=>{if(!r.ok)throw Error();return r.json();}).then(j=>{ready=j.configured===true;cooldown(j);status.textContent=ready?'已設定。尚未讀取任何試算表。':'尚未設定。清理功能仍停用。';update();}).catch(()=>{status.textContent='服務無法確認；未執行預檢。';});
      button.onclick=async()=>{if(button.disabled||active||!ready||!consent.checked||seconds()>0)return;
        const a={id:++sequence,phase:'challenge',cancelled:false,used:false};active=a;update();status.textContent='準備一次性驗證；先前結果仍保留。';
        try{if(!window.google?.accounts?.id){finish(a);status.textContent='Google 登入尚未就緒；未執行預檢。';return;}
          const reply=await post('/preflight/challenge',{});if(active!==a)return;cooldown(reply.data);
          if(a.cancelled||!consent.checked){finish(a);return;}
          if(!reply.ok){finish(a);status.textContent=seconds()>0?'冷卻剩餘 '+seconds()+' 秒；請稍後手動重新驗證。':'無法建立新驗證；未執行讀取。';return;}
          const c=reply.data;a.phase='google';status.textContent='請完成 Google 驗證。驗證逾時後需重新開始。';
          setTimeout(()=>{if(active===a&&a.phase==='google'){a.cancelled=true;finish(a);status.textContent='驗證已逾時；未執行讀取。請明確開始新一次驗證。';}},120000);
          google.accounts.id.initialize({client_id:c.clientId,nonce:c.nonce,auto_select:false,callback:async proof=>{
            if(active!==a||a.cancelled||a.used||!consent.checked)return;a.used=true;a.phase='read';holder.replaceChildren();
            status.textContent='正在唯讀預檢，不會自動重試。';
            try{const response=await post('/preflight/read',{nonce:c.nonce,credential:proof.credential,consent:true});if(active!==a)return;
              cooldown(response.data);result(response.data);
            }catch(ignored){if(active===a){cooldownUntil=Math.max(cooldownUntil,Date.now()+60000);result({});}}
            finally{finish(a);}}});
          holder.replaceChildren();google.accounts.id.renderButton(holder,{type:'standard',theme:'outline',size:'large'});
        }catch(ignored){if(active===a){finish(a);status.textContent='無法完成驗證準備；未執行讀取。請手動重新開始。';}}};
      """;
}
