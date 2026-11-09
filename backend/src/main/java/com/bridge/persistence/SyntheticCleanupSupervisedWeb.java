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
      <h2>本階段精確預覽（識別資訊遮蔽）</h2><pre id="view">尚未讀取。</pre>
      <label>逐字輸入本階段確認文字：<input id="exact" type="text" autocomplete="off" spellcheck="false"></label>
      <pre id="required"></pre><button id="confirm" disabled>重新驗證並執行本階段</button>
      <div id="google"></div><p id="status" role="status">確認服務設定中。</p><h2>最近結果</h2><pre id="result">尚未執行。</pre>
      <script src="https://accounts.google.com/gsi/client" async defer></script><script src="/supervised.js" defer></script></html>
      """;
  static final String JS = """
      'use strict';
      const el=id=>document.getElementById(id), preview=el('preview'), confirm=el('confirm'), consent=el('consent'), frozen=el('frozen'), exact=el('exact'), holder=el('google');
      let enabled=false, stopped=false, busy=false, next=false, view=null, active=null;
      function update(){const ok=enabled&&!stopped&&!busy&&consent.checked&&frozen.checked;
        preview.disabled=!ok||view!==null;confirm.disabled=!ok||view===null||exact.value!==view.confirmation||Date.parse(view.expiresAt)<=Date.now();}
      function stop(code){stopped=true;busy=false;active=null;holder.replaceChildren();update();el('status').textContent=(typeof code==='string'&&/^[A-Z_]{1,64}$/.test(code)?code+'：':'')+'流程已停止或結果未確認。請保留目前狀態並人工檢查；不要重試或移除控制標記。';}
      function finish(){busy=false;active=null;holder.replaceChildren();consent.checked=false;frozen.checked=false;exact.value='';update();}
      async function post(path,body){const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),credentials:'omit',cache:'no-store'});const data=await response.json();if(!response.ok){const error=Error();error.code=data.code;throw error;}return data;}
      function changed(){if(active){stop();}update();}consent.onchange=changed;frozen.onchange=changed;exact.oninput=()=>{if(active)stop();else update();};
      fetch('/health/ready',{cache:'no-store',credentials:'omit'}).then(r=>r.json()).then(j=>{enabled=j.enabled===true;el('status').textContent=enabled?'尚未讀取。請明確開始第一階段預覽。':'尚未啟用。';update();}).catch(stop);
      async function authorize(action){if(busy||stopped||!consent.checked||!frozen.checked)return;
        const pending=view, phrase=exact.value, a={used:false};active=a;busy=true;update();
        try{if(!window.google?.accounts?.id)throw Error();const c=await post('/supervised/challenge',{});if(active!==a)return;
          el('status').textContent='請完成本次全新 Google 操作者驗證。';
          setTimeout(()=>{if(active===a&&!a.used)stop();},120000);
          google.accounts.id.initialize({client_id:c.clientId,nonce:c.nonce,auto_select:false,callback:async token=>{
            if(active!==a||a.used||stopped||!consent.checked||!frozen.checked)return;a.used=true;holder.replaceChildren();
            const proof={nonce:c.nonce,credential:token.credential,consent:true,writersFrozen:true};
            try{el('status').textContent='執行本次請求中。不會自動重試。';
              const j=await post('/supervised/'+action,action==='confirm'?{proof,planId:pending.planId,exactConfirmation:phrase}:proof);
              if(active!==a)return;
              if(action==='confirm'){el('result').textContent=JSON.stringify(j,null,2);view=null;el('required').textContent='';
                if(j.outcome!=='VERIFIED'){stop();return;}if(j.syntheticFunctionalRunVerified===true){stopped=true;el('status').textContent='合成流程驗證通過。正式清理仍未獲得認證。';}
                else{next=true;preview.textContent='重新驗證並取得下一階段預覽';el('status').textContent='本階段已驗證。下一階段需重新勾選同意並手動開始。';}}
              else{if(typeof j.confirmation!=='string'||!j.confirmation||typeof j.planId!=='string'||!Number.isFinite(Date.parse(j.expiresAt)))throw Error();
                view=j;el('view').textContent=JSON.stringify(j,null,2);el('required').textContent=j.confirmation;el('status').textContent='請檢查每個表格與儲存格變更，重新勾選同意並逐字輸入確認文字。';}
              finish();
            }catch(error){stop(error.code);}}});
          holder.replaceChildren();google.accounts.id.renderButton(holder,{type:'standard',theme:'outline',size:'large'});
        }catch(error){stop(error.code);}}
      preview.onclick=()=>{if(!preview.disabled)authorize(next?'next':'start');};
      confirm.onclick=()=>{if(!confirm.disabled)authorize('confirm');};
      setInterval(update,1000);
      """;
}
