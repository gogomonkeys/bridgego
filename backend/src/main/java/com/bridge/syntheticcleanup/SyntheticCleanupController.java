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
  @PostMapping("/preflight/read") Object read(@RequestBody Proof proof) { return service.preflight(proof.nonce(), proof.credential(), proof.consent()); }
  @ExceptionHandler(SyntheticCleanupService.Failure.class) ResponseEntity<?> failure(SyntheticCleanupService.Failure failure) {
    return ResponseEntity.status(failure.status).body(Map.of("code", failure.code));
  }
  @ExceptionHandler(Exception.class) ResponseEntity<?> invalid(Exception ignored) {
    return ResponseEntity.badRequest().body(Map.of("code", "PREFLIGHT_REQUEST_REJECTED"));
  }
  private static final String HTML = """
      <!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>BridgeGo 唯讀預檢</title><style>body{font:18px system-ui;margin:2rem auto;padding:0 1rem;max-width:36rem;line-height:1.6}button{font:inherit;padding:.6rem}pre{white-space:pre-wrap;overflow-wrap:anywhere}label{display:block;margin:1rem 0}</style>
      <h1>合成測試表唯讀預檢</h1><p>只檢查指定測試表的完整資料與註解可見性。不寫入、不執行帳號刪除或季度清理。</p>
      <label><input id="consent" type="checkbox">我同意這次讀取指定的合成測試表。</label>
      <button id="start" disabled>以 Google 驗證並預檢一次</button><div id="google"></div><pre id="result" role="status">檢查服務設定中…</pre>
      <script src="https://accounts.google.com/gsi/client" async defer></script><script src="/preflight.js" defer></script></html>
      """;
  private static final String JS = """
      'use strict';
      const button=document.getElementById('start'),consent=document.getElementById('consent'),output=document.getElementById('result'),holder=document.getElementById('google');
      let busy=false,ready=false,generation=0;
      function reset(){busy=false;holder.replaceChildren();button.disabled=!ready||!consent.checked;}
      async function post(path,body){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),credentials:'omit',cache:'no-store'});const j=await r.json();if(!r.ok)throw Error(j.code||'PREFLIGHT_UNVERIFIED');return j;}
      consent.onchange=()=>{generation++;reset();};
      fetch('/health/ready',{cache:'no-store',credentials:'omit'}).then(r=>r.json()).then(j=>{ready=j.configured===true;output.textContent=ready?'已設定。尚未讀取任何試算表。':'尚未設定。清理功能仍停用。';reset();}).catch(()=>{output.textContent='服務無法確認；未執行預檢。';});
      button.onclick=async()=>{if(busy||!consent.checked)return;busy=true;button.disabled=true;const version=++generation;output.textContent='請完成 Google 驗證。';
        try{if(!window.google?.accounts?.id)throw Error('GOOGLE_SIGN_IN_NOT_READY');const c=await post('/preflight/challenge',{});if(version!==generation||!consent.checked)return;
          google.accounts.id.initialize({client_id:c.clientId,nonce:c.nonce,auto_select:false,callback:async proof=>{if(version!==generation||!consent.checked)return;generation++;holder.replaceChildren();output.textContent='正在唯讀預檢，不會自動重試。';
            try{const result=await post('/preflight/read',{nonce:c.nonce,credential:proof.credential,consent:true});output.textContent=result.result+'\\n註解：'+result.comments+'\\n分頁：'+result.sheetCount+'\\n清理仍未就緒；寫入停用。';}
            catch(error){output.textContent=String(error.message)+'\\n完整預檢未確認。未寫入，請勿視為清理成功。';}finally{reset();}}});
          holder.replaceChildren();google.accounts.id.renderButton(holder,{type:'standard',theme:'outline',size:'large'});
        }catch(error){output.textContent=String(error.message);reset();}};
      """;
}
