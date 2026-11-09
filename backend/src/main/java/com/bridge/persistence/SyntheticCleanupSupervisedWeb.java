package com.bridge.persistence;

import com.bridge.room.GoogleJwtVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.*;
import org.springframework.web.context.support.StandardServletEnvironment;

/** Dedicated isolated supervised web graph. No component scan or normal game graph. */
public final class SyntheticCleanupSupervisedWeb {
  private SyntheticCleanupSupervisedWeb() {}
  public static void main(String[] args) {
    try { application(args, System::getenv).run(); }
    catch (RuntimeException invalid) {
      System.err.println("Synthetic supervised cleanup startup rejected. No supplied values are displayed.");
      System.exit(2);
    }
  }
  static SpringApplication application(String[] args, Function<String,String> env) {
    if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()
        || !System.getProperty("javax.net.debug", "").isBlank()) throw new IllegalArgumentException("HTTP/TLS debug logging is not supported");
    if (args == null || args.length != 0) throw new IllegalArgumentException("No command-line options accepted");
    var settings = SyntheticCleanupSupervisedHost.Settings.read(env);
    String port = env.apply("PORT");
    if (port == null) port = "8080";
    if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)
      throw new IllegalArgumentException("Invalid port");
    var environment = new LockedEnvironment(lockedProperties(port));
    SpringApplication app = new SpringApplication(Configuration.class);
    app.setEnvironment(environment); app.setWebApplicationType(WebApplicationType.SERVLET);
    app.setAddCommandLineProperties(false); app.setBannerMode(Banner.Mode.OFF); app.setLogStartupInfo(false);
    app.addInitializers(context -> {
      if (context.getEnvironment() != environment) throw new IllegalStateException("Environment replaced");
      environment.recheckAndLock();
      context.getBeanFactory().registerSingleton("syntheticSettings", settings);
      context.getBeanFactory().registerSingleton("syntheticFactory", SyntheticCleanupSupervisedHost.realFactory(
          settings, () -> env.apply("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON"), Clock.systemUTC()));
    });
    return app;
  }
  @SpringBootConfiguration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Profile("synthetic-supervised")
  @EnableAutoConfiguration
  static class Configuration {
    @Bean SyntheticCleanupSupervisedHost preflightService(SyntheticCleanupSupervisedHost.Settings settings, SyntheticCleanupSupervisedHost.Factory factory) {
      return new SyntheticCleanupSupervisedHost(settings, new GoogleJwtVerifier(new ObjectMapper()), factory, Clock.systemUTC());
    }
    @Bean Controller preflightController(SyntheticCleanupSupervisedHost service, SyntheticCleanupSupervisedHost.Settings settings) { return new Controller(service, settings); }
    @Bean SyntheticCleanupSupervisedWebGuard preflightGuard(SyntheticCleanupSupervisedHost.Settings settings) { return new SyntheticCleanupSupervisedWebGuard(settings); }
    @Bean org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictJson() {
      return builder -> builder.failOnUnknownProperties(true)
          .featuresToEnable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION,
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .featuresToDisable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
  }
  static Map<String,Object> lockedProperties(String port) {
    var p = new LinkedHashMap<String,Object>();
    p.put("spring.config.location", "optional:classpath:/synthetic-supervised.properties");
    p.put("spring.config.name", "synthetic-cleanup"); p.put("spring.config.import", ""); p.put("spring.config.additional-location", "");
    p.put("spring.profiles.active", "synthetic-supervised"); p.put("spring.profiles.default", "synthetic-supervised");
    p.put("spring.profiles.include", ""); p.put("spring.main.allow-bean-definition-overriding", "false");
    p.put("spring.main.allow-circular-references", "false"); p.put("spring.main.lazy-initialization", "false");
    p.put("spring.main.web-application-type", "servlet"); p.put("spring.main.banner-mode", "off");
    p.put("spring.main.log-startup-info", "false"); p.put("spring.main.add-command-line-properties", "false");
    p.put("spring.application.name", "bridgego-synthetic-supervised");
    p.put("server.address", "0.0.0.0"); p.put("server.port", port); p.put("server.ssl.enabled", "false");
    p.put("server.forward-headers-strategy", "none"); p.put("server.error.include-message", "never");
    p.put("server.error.include-stacktrace", "never"); p.put("server.tomcat.accesslog.enabled", "false");
    p.put("server.tomcat.threads.max", "12"); p.put("server.tomcat.max-connections", "32"); p.put("server.tomcat.accept-count", "16");
    p.put("server.tomcat.connection-timeout", "10s"); p.put("server.max-http-request-header-size", "16KB");
    p.put("spring.web.resources.add-mappings", "false"); p.put("spring.jmx.enabled", "false");
    p.put("logging.level.root", "ERROR");
    p.put("logging.level.com.bridge.persistence.SyntheticCleanupSupervisedHost", "INFO"); p.put("server.error.whitelabel.enabled", "false");
    return Map.copyOf(p);
  }
  static final class LockedEnvironment extends StandardServletEnvironment {
    private final Map<String,Object> locked;
    LockedEnvironment(Map<String,Object> locked) { this.locked = Map.copyOf(locked); install(); }
    @Override protected void customizePropertySources(MutablePropertySources sources) {}
    @Override public Map<String,Object> getSystemProperties() { return Map.of(); }
    @Override public Map<String,Object> getSystemEnvironment() { return Map.of(); }
    @Override protected String doGetActiveProfilesProperty() { return null; }
    @Override protected String doGetDefaultProfilesProperty() { return null; }
    @Override public void initPropertySources(ServletContext servletContext, ServletConfig servletConfig) {}
    @Override protected ConfigurablePropertyResolver createPropertyResolver(MutablePropertySources sources) {
      return ConfigurationPropertySources.createPropertyResolver(sources);
    }
    void recheckAndLock() {
      for (var entry : locked.entrySet()) if (!entry.getValue().toString().equals(getProperty(entry.getKey())))
        throw new IllegalStateException("Preflight configuration changed");
      if (!Arrays.equals(getActiveProfiles(), new String[] {"synthetic-supervised"})
          || !Arrays.equals(getDefaultProfiles(), new String[] {"synthetic-supervised"})) throw new IllegalStateException("Profiles changed");
      install(); ConfigurationPropertySources.attach(this);
    }
    private void install() {
      for (var source : getPropertySources()) getPropertySources().remove(source.getName());
      getPropertySources().addFirst(new MapPropertySource("syntheticLockedConfiguration", locked));
      setActiveProfiles("synthetic-supervised"); setDefaultProfiles("synthetic-supervised");
    }
  }

  @org.springframework.web.bind.annotation.RestController
  @org.springframework.context.annotation.Profile("synthetic-supervised")
  static final class Controller {
    private final SyntheticCleanupSupervisedHost service;
    private final SyntheticCleanupSupervisedHost.Settings settings;
    Controller(SyntheticCleanupSupervisedHost service, SyntheticCleanupSupervisedHost.Settings settings) {
      this.service=service; this.settings=settings;
    }
    record Confirmation(SyntheticCleanupSupervisedHost.Proof proof, String planId, String exactConfirmation) {}
    @org.springframework.web.bind.annotation.GetMapping("/health/ready")
    Map<String,Object> health() { return Map.of("enabled", settings.enabled(), "productionCleanupCertified", false); }
    @org.springframework.web.bind.annotation.GetMapping(value="/", produces="text/html")
    org.springframework.http.ResponseEntity<String> page() {
      return org.springframework.http.ResponseEntity.ok().header("Content-Security-Policy",
          "default-src 'none'; script-src 'self' https://accounts.google.com/gsi/client; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; style-src 'unsafe-inline' https://accounts.google.com/gsi/style; img-src 'self' https://*.googleusercontent.com; base-uri 'none'; form-action 'none'; frame-ancestors 'none'").body(HTML);
    }
    @org.springframework.web.bind.annotation.GetMapping(value="/supervised.js", produces="application/javascript")
    String script() { return JS; }
    @org.springframework.web.bind.annotation.PostMapping("/supervised/challenge")
    Object challenge() { return service.challenge(); }
    @org.springframework.web.bind.annotation.PostMapping("/supervised/start")
    Object start(@org.springframework.web.bind.annotation.RequestBody SyntheticCleanupSupervisedHost.Proof proof) { return service.start(proof); }
    @org.springframework.web.bind.annotation.PostMapping("/supervised/next")
    Object next(@org.springframework.web.bind.annotation.RequestBody SyntheticCleanupSupervisedHost.Proof proof) { return service.next(proof); }
    @org.springframework.web.bind.annotation.PostMapping("/supervised/confirm")
    Object confirm(@org.springframework.web.bind.annotation.RequestBody Confirmation request) {
      return service.confirm(request.proof(),request.planId(),request.exactConfirmation());
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(SyntheticCleanupSupervisedHost.Failure.class)
    org.springframework.http.ResponseEntity<?> failure(SyntheticCleanupSupervisedHost.Failure failure) {
      return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code",failure.code));
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    org.springframework.http.ResponseEntity<?> rejected(Exception ignored) {
      return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code","SUPERVISED_REQUEST_REJECTED"));
    }
  }
  static final String HTML = """
      <!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>合成清理分階段驗證</title><style>body{font:18px system-ui;max-width:60rem;margin:2rem auto;padding:1rem;line-height:1.6}label{display:block;margin:1rem 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}button,input{font:inherit}input[type=text]{width:100%}</style>
      <h1>合成清理分階段驗證</h1><p>僅限指定隔離測試表。每一階段先顯示精確變更，再個別確認與重新驗證操作者。不自動重試，不提供重設。正式清理尚未獲得認證。</p>
      <p>寫入與刪除可能無法復原。重新整理或關閉頁面不會撤銷已送出的操作，也不能恢復或重試本次流程。</p>
      <label><input id="consent" type="checkbox">我同意本次操作僅作用於已授權的隔離合成測試表。</label>
      <label><input id="frozen" type="checkbox">我已停止所有其他寫入者，並將持續停止直到本次流程結束。</label>
      <button id="preview" disabled>重新驗證並取得第一階段預覽</button>
      <p id="status" role="status" aria-live="polite">INITIALIZING：頁面程式尚未完成初始化；尚未送出操作。</p>
      <noscript>JAVASCRIPT_DISABLED：請啟用 JavaScript 才能顯示診斷與驗證介面；尚未送出操作。</noscript>
      <h2>本階段精確預覽（識別資訊遮蔽）</h2><pre id="view">尚未讀取。</pre>
      <label>逐字輸入本階段確認文字：<input id="exact" type="text" autocomplete="off" spellcheck="false"></label>
      <pre id="required"></pre><button id="confirm" disabled>重新驗證並執行本階段</button>
      <div id="google"></div><h2>最近結果</h2><pre id="result">尚未執行。</pre>
      <script src="https://accounts.google.com/gsi/client" async defer></script><script src="/supervised.js" defer></script></html>
      """;
  static final String JS = """
      'use strict';
      const el=id=>document.getElementById(id), preview=el('preview'), confirm=el('confirm'), consent=el('consent'), frozen=el('frozen'), exact=el('exact'), holder=el('google');
      let enabled=false, stopped=false, busy=false, next=false, view=null, active=null, submission='NO_OPERATION_SUBMITTED';
      const backendCodes=new Set(['CHALLENGE_LIMIT','CONFIGURATION_REJECTED','EXPLICIT_CONSENT_AND_FREEZE_REQUIRED','FRESH_AUTHENTICATION_REQUIRED','NOT_CONFIGURED','OPERATOR_AUTHENTICATION_FAILED','OPERATOR_NOT_ALLOWED','PREVIEW_NOT_AVAILABLE','PREVIEW_UNVERIFIED','RUN_ALREADY_STARTED','RUN_STOPPED','START_UNVERIFIED','SUPERVISED_REQUEST_REJECTED','ORIGIN_NOT_ALLOWED','JSON_REQUIRED','REQUEST_TOO_LARGE','RATE_LIMITED','EMPTY_CHALLENGE_REQUIRED']);
      const reasons={HEALTH_NETWORK:'無法取得服務健康狀態。',HEALTH_HTTP:'服務健康狀態請求遭拒。',HEALTH_DECODE:'服務健康狀態不是有效 JSON。',HEALTH_INVALID:'服務健康狀態格式不正確。',SERVICE_DISABLED:'服務尚未啟用。',GOOGLE_NOT_READY:'Google 登入程式尚未就緒。',GOOGLE_SETUP_FAILED:'Google 登入介面無法初始化。',CHALLENGE_NETWORK:'無法取得驗證挑戰。',CHALLENGE_DECODE:'驗證挑戰不是有效 JSON。',CHALLENGE_REJECTED:'驗證挑戰請求遭拒。',CHALLENGE_INVALID:'驗證挑戰格式不正確。',AUTH_TIMEOUT:'等待操作者驗證逾時。',CONSENT_CHANGED:'驗證或請求途中同意勾選已變更。',CONFIRMATION_CHANGED:'驗證或請求途中確認文字已變更。',GOOGLE_PROOF_INVALID:'Google 未提供有效驗證資料。',OPERATION_NETWORK:'操作請求未收到可判讀回應。',OPERATION_DECODE:'操作回應不是有效 JSON。',OPERATION_REJECTED:'操作請求遭拒。',PREVIEW_INVALID:'預覽資料格式不正確。',PREVIEW_EXPIRED:'本階段預覽已過期。',RESULT_INVALID:'執行結果格式不正確。',RESULT_NOT_VERIFIED:'伺服器未回報本階段驗證通過。',CLIENT_FAILURE:'頁面無法完成目前步驟。'};
      const stages={NO_OPERATION_SUBMITTED:'本次尚未嘗試送出試算表操作請求（可能已嘗試送出驗證挑戰）。',READ_SUBMITTED:'本次已嘗試送出讀取請求；是否送達及處理結果不能單憑此欄確認，請參照上方結果代碼。',WRITE_SUBMITTED:'本次已嘗試送出寫入請求；是否送達及處理結果不能單憑此欄確認，請參照上方結果代碼。'};
      function status(code,text){el('status').textContent=code+'：'+text+' ['+submission+'] '+stages[submission];}
      function update(){const ok=enabled&&!stopped&&!busy&&consent.checked&&frozen.checked;
        preview.disabled=!ok||view!==null;confirm.disabled=!ok||view===null||exact.value!==view.confirmation||Date.parse(view.expiresAt)<=Date.now();
        if(view&&!busy&&!stopped&&Date.parse(view.expiresAt)<=Date.now())stop('PREVIEW_EXPIRED');}
      function stop(code){const safe=Object.hasOwn(reasons,code)||backendCodes.has(code)?code:'CLIENT_FAILURE';stopped=true;busy=false;active=null;holder.replaceChildren();update();status(safe,(reasons[safe]||'伺服器拒絕本次請求。')+(submission==='NO_OPERATION_SUBMITTED'?'':' 服務端結果未確認。')+' 流程已停止。請保留狀態並人工檢查；不要重試或移除控制標記。');}
      function finish(){busy=false;active=null;holder.replaceChildren();consent.checked=false;frozen.checked=false;exact.value='';update();}
      function failure(code){const error=Error();error.code=code;return error;}
      async function post(path,body,kind){let response,data;
        try{response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),credentials:'omit',cache:'no-store'});}catch(ignored){throw failure(kind+'_NETWORK');}
        try{data=await response.json();}catch(ignored){throw failure(kind+'_DECODE');}
        if(!response.ok)throw failure(backendCodes.has(data?.code)?data.code:kind+'_REJECTED');return data;}
      function changed(){if(active)stop('CONSENT_CHANGED');else update();}consent.onchange=changed;frozen.onchange=changed;exact.oninput=()=>{if(active)stop('CONFIRMATION_CHANGED');else update();};
      async function health(){let response,j;status('HEALTH_CHECK','確認服務設定中。');
        try{response=await fetch('/health/ready',{cache:'no-store',credentials:'omit'});}catch(ignored){stop('HEALTH_NETWORK');return;}
        if(!response.ok){stop('HEALTH_HTTP');return;}
        try{j=await response.json();}catch(ignored){stop('HEALTH_DECODE');return;}
        if(!j||typeof j.enabled!=='boolean'){stop('HEALTH_INVALID');return;}
        if(!j.enabled){stop('SERVICE_DISABLED');return;}enabled=true;status('READY','請勾選同意與停止寫入者，再明確開始第一階段預覽。');update();}
      health();
      async function authorize(action){if(busy||stopped||!consent.checked||!frozen.checked)return;
        const pending=view, phrase=exact.value, a={used:false};active=a;busy=true;submission='NO_OPERATION_SUBMITTED';update();
        try{if(!window.google?.accounts?.id){stop('GOOGLE_NOT_READY');return;}status('CHALLENGE_PENDING','正在取得一次性驗證挑戰。');
          const c=await post('/supervised/challenge',{},'CHALLENGE');if(active!==a)return;
          if(!c||typeof c.clientId!=='string'||!c.clientId||typeof c.nonce!=='string'||!c.nonce){stop('CHALLENGE_INVALID');return;}
          status('AUTH_PENDING','請完成本次全新 Google 操作者驗證。');
          setTimeout(()=>{if(active===a&&!a.used)stop('AUTH_TIMEOUT');},120000);
          try{google.accounts.id.initialize({client_id:c.clientId,nonce:c.nonce,auto_select:false,callback:async token=>{
            if(active!==a||a.used||stopped||!consent.checked||!frozen.checked)return;a.used=true;holder.replaceChildren();
            if(!token||typeof token.credential!=='string'||!token.credential){stop('GOOGLE_PROOF_INVALID');return;}
            const proof={nonce:c.nonce,credential:token.credential,consent:true,writersFrozen:true};
            try{submission=action==='confirm'?'WRITE_SUBMITTED':'READ_SUBMITTED';status('OPERATION_PENDING','正在等待本次操作回應。不會自動重試。');
              const j=await post('/supervised/'+action,action==='confirm'?{proof,planId:pending.planId,exactConfirmation:phrase}:proof,'OPERATION');
              if(active!==a)return;
              if(action==='confirm'){
                if(!j||typeof j.outcome!=='string'||!Number.isInteger(j.verifiedPhases)||typeof j.syntheticFunctionalRunVerified!=='boolean'||j.productionCleanupCertified!==false){stop('RESULT_INVALID');return;}
                el('result').textContent=JSON.stringify({outcome:['VERIFIED','REJECTED','STOPPED_UNCERTAIN'].includes(j.outcome)?j.outcome:'UNVERIFIED',verifiedPhases:j.verifiedPhases,syntheticFunctionalRunVerified:j.syntheticFunctionalRunVerified,productionCleanupCertified:false},null,2);view=null;el('required').textContent='';
                if(j.outcome!=='VERIFIED'){stop('RESULT_NOT_VERIFIED');return;}if(j.syntheticFunctionalRunVerified===true){stopped=true;status('RUN_VERIFIED','伺服器回報合成流程驗證通過。正式清理仍未獲得認證。');}
                else{next=true;preview.textContent='重新驗證並取得下一階段預覽';status('STAGE_VERIFIED','伺服器回報本階段已驗證。下一階段需重新勾選同意並手動開始。');}}
              else{if(!j||typeof j.confirmation!=='string'||!j.confirmation||typeof j.planId!=='string'||!j.planId||!Number.isFinite(Date.parse(j.expiresAt))){stop('PREVIEW_INVALID');return;}
                view=j;el('view').textContent=JSON.stringify(j,null,2);el('required').textContent=j.confirmation;status('PREVIEW_READY','請檢查每個表格與儲存格變更，重新勾選同意並逐字輸入確認文字。');}
              finish();
            }catch(error){if(active===a)stop(error.code);}}});
            holder.replaceChildren();google.accounts.id.renderButton(holder,{type:'standard',theme:'outline',size:'large'});
          }catch(ignored){stop('GOOGLE_SETUP_FAILED');}
        }catch(error){if(active===a)stop(error.code);}}
      preview.onclick=()=>{if(!preview.disabled)authorize(next?'next':'start');};
      confirm.onclick=()=>{if(!confirm.disabled)authorize('confirm');};
      setInterval(update,1000);
      """;
}
