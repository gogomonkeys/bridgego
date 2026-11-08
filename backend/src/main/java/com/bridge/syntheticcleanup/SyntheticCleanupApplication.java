package com.bridge.syntheticcleanup;

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

/** Dedicated explicit web graph. No component scan, game graph, schedulers or mutation service. */
public final class SyntheticCleanupApplication {
  private SyntheticCleanupApplication() {}
  public static void main(String[] args) {
    try { application(args, System::getenv).run(); }
    catch (RuntimeException invalid) {
      System.err.println("Synthetic read-only preflight startup rejected. No supplied values are displayed.");
      System.exit(2);
    }
  }
  static SpringApplication application(String[] args, Function<String,String> env) {
    if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()
        || !System.getProperty("javax.net.debug", "").isBlank()) throw new IllegalArgumentException("HTTP/TLS debug logging is not supported");
    if (args == null || args.length != 0) throw new IllegalArgumentException("No command-line options accepted");
    var settings = SyntheticCleanupService.Settings.read(env);
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
      SyntheticCleanupService.Reader reader = settings.enabled()
          ? new SyntheticCleanupSheetsReader(settings.sheetId(), () -> env.apply("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON"))
          : () -> { throw new IllegalStateException("Unconfigured"); };
      context.getBeanFactory().registerSingleton("syntheticReader", reader);
    });
    return app;
  }
  @SpringBootConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class Configuration {
    @Bean SyntheticCleanupService preflightService(SyntheticCleanupService.Settings settings, SyntheticCleanupService.Reader reader) {
      return new SyntheticCleanupService(settings, new GoogleJwtVerifier(new ObjectMapper()), reader, Clock.systemUTC());
    }
    @Bean SyntheticCleanupController preflightController(SyntheticCleanupService service) { return new SyntheticCleanupController(service); }
    @Bean SyntheticCleanupGuard preflightGuard(SyntheticCleanupService.Settings settings) { return new SyntheticCleanupGuard(settings); }
    @Bean org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictJson() {
      return builder -> builder.failOnUnknownProperties(true)
          .featuresToEnable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION,
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .featuresToDisable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
  }
  static Map<String,Object> lockedProperties(String port) {
    var p = new LinkedHashMap<String,Object>();
    p.put("spring.config.location", "classpath:/synthetic-cleanup.properties");
    p.put("spring.config.name", "synthetic-cleanup"); p.put("spring.config.import", ""); p.put("spring.config.additional-location", "");
    p.put("spring.profiles.active", "synthetic-readonly-preflight"); p.put("spring.profiles.default", "synthetic-readonly-preflight");
    p.put("spring.profiles.include", ""); p.put("spring.main.allow-bean-definition-overriding", "false");
    p.put("spring.main.allow-circular-references", "false"); p.put("spring.main.lazy-initialization", "false");
    p.put("spring.main.web-application-type", "servlet"); p.put("spring.main.banner-mode", "off");
    p.put("spring.main.log-startup-info", "false"); p.put("spring.main.add-command-line-properties", "false");
    p.put("spring.application.name", "bridgego-synthetic-readonly-preflight");
    p.put("server.address", "0.0.0.0"); p.put("server.port", port); p.put("server.ssl.enabled", "false");
    p.put("server.forward-headers-strategy", "none"); p.put("server.error.include-message", "never");
    p.put("server.error.include-stacktrace", "never"); p.put("server.tomcat.accesslog.enabled", "false");
    p.put("server.tomcat.threads.max", "12"); p.put("server.tomcat.max-connections", "32"); p.put("server.tomcat.accept-count", "16");
    p.put("server.tomcat.connection-timeout", "10s"); p.put("server.max-http-request-header-size", "16KB");
    p.put("spring.web.resources.add-mappings", "false"); p.put("spring.jmx.enabled", "false");
    p.put("logging.level.root", "ERROR");
    p.put("logging.level.com.bridge.syntheticcleanup.SyntheticCleanupService", "INFO"); p.put("server.error.whitelabel.enabled", "false");
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
      if (!Arrays.equals(getActiveProfiles(), new String[] {"synthetic-readonly-preflight"})
          || !Arrays.equals(getDefaultProfiles(), new String[] {"synthetic-readonly-preflight"})) throw new IllegalStateException("Profiles changed");
      install(); ConfigurationPropertySources.attach(this);
    }
    private void install() {
      for (var source : getPropertySources()) getPropertySources().remove(source.getName());
      getPropertySources().addFirst(new MapPropertySource("syntheticLockedConfiguration", locked));
      setActiveProfiles("synthetic-readonly-preflight"); setDefaultProfiles("synthetic-readonly-preflight");
    }
  }
}
