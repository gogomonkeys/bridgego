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
public final class SyntheticCleanupManualQuarterWeb {
  private SyntheticCleanupManualQuarterWeb() {}
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
    // This isolated artifact must use SLF4J's no-provider NOP fallback. Never relax the
    // transport's standalone guard or rely on mutable logger levels/filter configuration.
    LiveQuarterHttp.requireSilentLogging();
    System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");
    var settings = SyntheticCleanupManualQuarterHost.Settings.read(env);
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
      context.getBeanFactory().registerSingleton("syntheticFactory", SyntheticCleanupManualQuarterHost.realFactory(
          settings, () -> env.apply("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON"), Clock.systemUTC()));
    });
    return app;
  }
  @SpringBootConfiguration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Profile("manual-quarter-trial")
  @EnableAutoConfiguration
  static class Configuration {
    @Bean SyntheticCleanupManualQuarterHost preflightService(SyntheticCleanupManualQuarterHost.Settings settings, SyntheticCleanupManualQuarterHost.Factory factory) {
      return new SyntheticCleanupManualQuarterHost(settings, new GoogleJwtVerifier(new ObjectMapper()), factory, Clock.systemUTC());
    }
    @Bean Controller preflightController(SyntheticCleanupManualQuarterHost service, SyntheticCleanupManualQuarterHost.Settings settings) { return new Controller(service, settings); }
    @Bean SyntheticCleanupManualQuarterWebGuard preflightGuard(SyntheticCleanupManualQuarterHost.Settings settings) { return new SyntheticCleanupManualQuarterWebGuard(settings); }
    @Bean org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictJson() {
      return builder -> builder.failOnUnknownProperties(true)
          .featuresToEnable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION,
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .featuresToDisable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
  }
  static Map<String,Object> lockedProperties(String port) {
    var p = new LinkedHashMap<String,Object>();
    p.put("spring.config.location", "optional:classpath:/manual-quarter-trial.properties");
    p.put("spring.config.name", "synthetic-cleanup"); p.put("spring.config.import", ""); p.put("spring.config.additional-location", "");
    p.put("spring.profiles.active", "manual-quarter-trial"); p.put("spring.profiles.default", "manual-quarter-trial");
    p.put("spring.profiles.include", ""); p.put("spring.main.allow-bean-definition-overriding", "false");
    p.put("spring.main.allow-circular-references", "false"); p.put("spring.main.lazy-initialization", "false");
    p.put("spring.main.web-application-type", "servlet"); p.put("spring.main.banner-mode", "off");
    p.put("spring.main.log-startup-info", "false"); p.put("spring.main.add-command-line-properties", "false");
    p.put("spring.application.name", "bridgego-manual-quarter-trial");
    p.put("server.address", "0.0.0.0"); p.put("server.port", port); p.put("server.ssl.enabled", "false");
    p.put("server.forward-headers-strategy", "none"); p.put("server.error.include-message", "never");
    p.put("server.error.include-stacktrace", "never"); p.put("server.tomcat.accesslog.enabled", "false");
    p.put("server.tomcat.threads.max", "12"); p.put("server.tomcat.max-connections", "32"); p.put("server.tomcat.accept-count", "16");
    p.put("server.tomcat.connection-timeout", "10s"); p.put("server.max-http-request-header-size", "16KB");
    p.put("spring.web.resources.add-mappings", "false"); p.put("spring.jmx.enabled", "false");
    p.put("logging.level.root", "ERROR");
    p.put("logging.level.com.bridge.persistence.SyntheticCleanupManualQuarterHost", "INFO"); p.put("server.error.whitelabel.enabled", "false");
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
      if (!Arrays.equals(getActiveProfiles(), new String[] {"manual-quarter-trial"})
          || !Arrays.equals(getDefaultProfiles(), new String[] {"manual-quarter-trial"})) throw new IllegalStateException("Profiles changed");
      install(); ConfigurationPropertySources.attach(this);
    }
    private void install() {
      for (var source : getPropertySources()) getPropertySources().remove(source.getName());
      getPropertySources().addFirst(new MapPropertySource("syntheticLockedConfiguration", locked));
      setActiveProfiles("manual-quarter-trial"); setDefaultProfiles("manual-quarter-trial");
    }
  }

  @org.springframework.web.bind.annotation.RestController
  @org.springframework.context.annotation.Profile("manual-quarter-trial")
  static final class Controller {
    private final SyntheticCleanupManualQuarterHost service;
    private final SyntheticCleanupManualQuarterHost.Settings settings;
    Controller(SyntheticCleanupManualQuarterHost service, SyntheticCleanupManualQuarterHost.Settings settings) {
      this.service=service; this.settings=settings;
    }
    record Confirmation(SyntheticCleanupManualQuarterHost.Proof proof, String planId, boolean confirmed) {}
    @org.springframework.web.bind.annotation.GetMapping("/health/ready")
    Map<String,Object> health() { return Map.of("enabled", settings.enabled(), "productionCleanupCertified", false); }
    @org.springframework.web.bind.annotation.GetMapping(value="/", produces="text/html")
    org.springframework.http.ResponseEntity<String> page() {
      return org.springframework.http.ResponseEntity.ok().header("Content-Security-Policy",
          "default-src 'none'; script-src 'self' https://accounts.google.com/gsi/client; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; style-src 'unsafe-inline' https://accounts.google.com/gsi/style; img-src 'self' https://*.googleusercontent.com; base-uri 'none'; form-action 'none'; frame-ancestors 'none'").body(HTML);
    }
    @org.springframework.web.bind.annotation.GetMapping(value="/quarter.js", produces="application/javascript")
    String script() { return JS; }
    @org.springframework.web.bind.annotation.PostMapping("/quarter/challenge")
    Object challenge() { return service.challenge(); }
    @org.springframework.web.bind.annotation.PostMapping("/quarter/preview")
    Object preview(@org.springframework.web.bind.annotation.RequestBody SyntheticCleanupManualQuarterHost.Proof proof) { return service.preview(proof); }
    @org.springframework.web.bind.annotation.PostMapping("/quarter/confirm")
    Object confirm(@org.springframework.web.bind.annotation.RequestBody Confirmation request) {
      return service.confirm(request.proof(),request.planId(),request.confirmed());
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(SyntheticCleanupManualQuarterHost.Failure.class)
    org.springframework.http.ResponseEntity<?> failure(SyntheticCleanupManualQuarterHost.Failure failure) {
      return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code",failure.code));
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    org.springframework.http.ResponseEntity<?> rejected(Exception ignored) {
      return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code","SUPERVISED_REQUEST_REJECTED"));
    }
  }
  static final String HTML = """
      <!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>季度假資料單次驗證</title><style>body{font:18px system-ui;max-width:42rem;margin:2rem auto;padding:1rem;line-height:1.6}label{display:block;margin:1rem 0}button{font:inherit;padding:.5rem}pre{white-space:pre-wrap}</style>
      <h1>季度假資料單次驗證</h1><p>只操作指定隔離測試表：移除 1 筆過期假戰績，保留 2 筆本季／邊界假戰績、假帳號與身分。版本歷史及備份不會清除。</p>
      <p>一次確認授權兩次寫入：移除過期戰績並記錄 PENDING，再核對並記錄 COMPLETE。會重寫 7 張資料表整個現有格線列範圍（含空白列）的 A:E 資料欄，保留帳號值、不改格式。不是單一交易，也不代表正式清理已獲認證。</p>
      <label><input id="consent" type="checkbox">我同意這次隔離假資料操作。</label>
      <label><input id="frozen" type="checkbox">我已人工停止所有其他寫入者，且只保留此單一程序。</label>
      <button id="preview" disabled>驗證身分並預覽</button>
      <p id="status" role="status">INITIALIZING：介面尚未初始化，尚未嘗試送出試算表操作。</p>
      <noscript>請啟用 JavaScript；尚未嘗試送出操作。</noscript>
      <h2>本次預覽</h2><p id="view">尚未讀取。</p>
      <label><input id="confirmed" type="checkbox" disabled>我確認執行上方這一份預覽：移除 1 筆、保留 2 筆，並保留帳號。</label>
      <button id="confirm" disabled>重新驗證並確認執行一次</button><div id="google"></div>
      <p>未寫入且預覽過期時，可在本程序重新預覽。結果未知時不可重試、重啟或重建假資料；本工具不保證跨重啟防重複。</p>
      <script src="https://accounts.google.com/gsi/client" async defer></script><script src="/quarter.js" defer></script></html>
      """;
  static final String JS = """
      'use strict';
      const el=id=>document.getElementById(id),preview=el('preview'),confirm=el('confirm'),consent=el('consent'),frozen=el('frozen'),confirmed=el('confirmed'),holder=el('google');
      let enabled=false,busy=false,stopped=false,view=null,active=null,writeAttempted=false;
      const safeCodes=new Set(['NOT_CONFIGURED','RUN_STOPPED','FRESH_AUTHENTICATION_REQUIRED','EXPLICIT_CONSENT_AND_FREEZE_REQUIRED','OPERATOR_AUTHENTICATION_FAILED','START_UNVERIFIED','PREVIEW_UNVERIFIED','PREVIEW_NOT_AVAILABLE','CHALLENGE_LIMIT','ORIGIN_NOT_ALLOWED','RATE_LIMITED']);
      function status(code,text){el('status').textContent=code+'：'+text;}
      function expired(){return view&&Date.parse(view.expiresAt)<=Date.now();}
      function update(){const ok=enabled&&!busy&&!stopped&&consent.checked&&frozen.checked;
        preview.disabled=!ok||writeAttempted;confirmed.disabled=!view||busy||stopped||expired();confirm.disabled=!ok||!view||!confirmed.checked||expired()||writeAttempted;
        if(expired()&&!busy&&!stopped&&!writeAttempted){confirmed.checked=false;status('PREVIEW_EXPIRED','尚未嘗試寫入。請重新勾選同意與停止寫入者，在本程序重新預覽。');}}
      function stop(code){if(!safeCodes.has(code)&&!['CONSENT_CHANGED','HEALTH_UNCONFIRMED','GOOGLE_NOT_READY','AUTH_TIMEOUT','CONFIRMATION_CHANGED','GOOGLE_PROOF_INVALID','RESULT_REQUIRES_REVIEW','NETWORK_UNCONFIRMED','RESPONSE_INVALID','REQUEST_REJECTED','CHALLENGE_INVALID','PREVIEW_INVALID','GOOGLE_SETUP_FAILED'].includes(code))code='CLIENT_FAILURE';stopped=true;busy=false;active=null;holder.replaceChildren();update();status(code,writeAttempted?'已嘗試送出執行請求，是否送達及結果未確認。請人工檢查，不可重試或重啟。':'流程停止；尚未嘗試送出寫入請求。請人工檢查。');}
      function finish(){active=null;busy=false;holder.replaceChildren();consent.checked=false;frozen.checked=false;confirmed.checked=false;update();}
      async function post(path,body){let r,j;try{r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),cache:'no-store',credentials:'omit'});}catch(e){throw Error('NETWORK_UNCONFIRMED');}
        try{j=await r.json();}catch(e){throw Error('RESPONSE_INVALID');}if(!r.ok)throw Error(safeCodes.has(j?.code)?j.code:'REQUEST_REJECTED');return j;}
      for(const input of [consent,frozen,confirmed])input.onchange=()=>{if(active)stop('CONSENT_CHANGED');else update();};
      fetch('/health/ready',{cache:'no-store',credentials:'omit'}).then(async r=>{if(!r.ok)throw Error();const j=await r.json();if(typeof j.enabled!=='boolean')throw Error();enabled=j.enabled;status(enabled?'READY':'DISABLED',enabled?'請先勾選同意與停止寫入者，再預覽。':'尚未啟用。');update();}).catch(()=>stop('HEALTH_UNCONFIRMED'));
      async function run(action){if(busy||stopped||writeAttempted||!consent.checked||!frozen.checked)return;
        const plan=view,a={used:false};active=a;busy=true;update();
        try{if(!window.google?.accounts?.id){stop('GOOGLE_NOT_READY');return;}const c=await post('/quarter/challenge',{});if(active!==a)return;
          if(!c||typeof c.nonce!=='string'||!c.nonce||typeof c.clientId!=='string'||!c.clientId)throw Error('CHALLENGE_INVALID');
          status('AUTH_PENDING','請完成這次 Google 操作者驗證。');setTimeout(()=>{if(active===a&&!a.used)stop('AUTH_TIMEOUT');},120000);
          google.accounts.id.initialize({client_id:c.clientId,nonce:c.nonce,auto_select:false,callback:async token=>{
            if(active!==a||a.used||stopped||!consent.checked||!frozen.checked)return;a.used=true;holder.replaceChildren();
            if(action==='confirm'&&(!confirmed.checked||view!==plan)){stop('CONFIRMATION_CHANGED');return;}
            if(action==='confirm'&&expired()){finish();return;}
            if(!token||typeof token.credential!=='string'||!token.credential){stop('GOOGLE_PROOF_INVALID');return;}
            try{const proof={nonce:c.nonce,credential:token.credential,consent:true,writersFrozen:true};if(action==='confirm')writeAttempted=true;
              status('REQUEST_PENDING',action==='confirm'?'已嘗試送出執行請求；等待結果，不會重試。':'正在讀取並檢查固定假資料。');
              const j=await post('/quarter/'+action,action==='confirm'?{proof,planId:plan.planId,confirmed:true}:proof);if(active!==a)return;
              if(action==='preview'){
                if(!j||j.removeMatches!==1||j.keepMatches!==2||typeof j.planId!=='string'||!j.planId||!Number.isFinite(Date.parse(j.expiresAt))||!Number.isFinite(Date.parse(j.cutoff)))throw Error('PREVIEW_INVALID');
                view=j;el('view').textContent='移除 1 筆，保留 2 筆；帳號與身分保留。截止：'+j.cutoff+'；確認期限：'+j.expiresAt+'。';status('PREVIEW_READY','請檢查此預覽，重新勾選同意與停止寫入者，再確認執行。');finish();
              }else if(j?.outcome==='PREVIEW_EXPIRED_NO_WRITE'||j?.outcome==='REJECTED_NO_WRITE'){
                writeAttempted=false;view=null;el('view').textContent='此預覽已失效，未寫入。';finish();status(j.outcome,'服務端確認未寫入；可在本程序重新預覽。');
              }else if(j?.outcome==='SYNTHETIC_QUARTER_VERIFIED'&&j.syntheticQuarterVerified===true&&j.productionCleanupCertified===false){
                stopped=true;finish();status('SYNTHETIC_QUARTER_VERIFIED','季度假資料驗證通過：移除 1 筆、保留 2 筆與帳號。不是正式清理認證。');
              }else stop('RESULT_REQUIRES_REVIEW');
            }catch(error){if(active===a)stop(error.message);}}});
          holder.replaceChildren();google.accounts.id.renderButton(holder,{type:'standard',theme:'outline',size:'large'});
        }catch(error){if(active===a)stop(safeCodes.has(error.message)||['NETWORK_UNCONFIRMED','RESPONSE_INVALID','REQUEST_REJECTED','CHALLENGE_INVALID'].includes(error.message)?error.message:'GOOGLE_SETUP_FAILED');}}
      preview.onclick=()=>{if(!preview.disabled)run('preview');};confirm.onclick=()=>{if(!confirm.disabled)run('confirm');};setInterval(update,1000);
      """;
}
