package com.bridge.localverification;

import com.bridge.room.RoomApplication;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.web.context.support.StandardServletEnvironment;

/**
 * A separate, non-deployable entry point for the local verification kit.
 *
 * Only the public GOOGLE_CLIENT_ID is read from the process environment. The normal
 * game's main method is deliberately not called: it can select maintenance mode.
 * Spring configuration is isolated before its environment post-processors run,
 * not just overridden after external configuration may already have been loaded.
 * This is application isolation, not a sandbox for Java agents/JVM startup options.
 */
public final class LocalVerificationApplication {
  private static final String PROFILE = "local-verification";
  private static final String SOURCE = "localVerificationLockedConfiguration";

  private LocalVerificationApplication() {}

  public static void main(String[] args) {
    final SpringApplication application;
    try {
      application = application(args, System::getenv);
    } catch (IllegalArgumentException invalid) {
      // Do not print arguments, environment values, credentials or parser details.
      System.err.println(invalid.getMessage());
      System.exit(2);
      return;
    }
    var context = application.run();
    boolean configured = !context.getEnvironment().getProperty("GOOGLE_CLIENT_ID", "").isEmpty();
    System.out.println("LOCAL VERIFICATION KIT: http://localhost:8080 (127.0.0.1 only)");
    System.out.println("Google verification-only: " + (configured ? "configured" : "not configured")
        + "; account linking, Sheets persistence, collection, erasure and quarter execution: OFF.");
  }

  static SpringApplication application(String[] args, Function<String, String> processVariable) {
    if (args == null || args.length != 0)
      throw new IllegalArgumentException("This local verification kit accepts no command-line arguments.");
    String clientId = clientId(processVariable.apply("GOOGLE_CLIENT_ID"));
    LockedEnvironment environment = new LockedEnvironment(lockedProperties(clientId));
    SpringApplication application = new SpringApplication(RoomApplication.class);
    application.setEnvironment(environment); // custom environment: Boot must not convert/repopulate it
    application.setWebApplicationType(WebApplicationType.SERVLET);
    application.setAddCommandLineProperties(false);
    application.setBannerMode(Banner.Mode.OFF);
    application.setLogStartupInfo(false);
    application.addInitializers(context -> {
      if (context.getEnvironment() != environment)
        throw new IllegalStateException("Local verification environment was replaced; startup refused.");
      environment.recheckAndLock(); // after ConfigData/main binding, before bean creation or server binding
    });
    return application;
  }

  static String clientId(String supplied) {
    if (supplied == null || supplied.isEmpty()) return "";
    if (!supplied.matches("[0-9]{6,30}-[A-Za-z0-9_-]{8,128}\\.apps\\.googleusercontent\\.com"))
      throw new IllegalArgumentException("GOOGLE_CLIENT_ID must be a public Google web client ID ending in .apps.googleusercontent.com. No supplied value was logged.");
    return supplied;
  }

  static Map<String, Object> lockedProperties(String clientId) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("spring.config.location", "classpath:/local-verification-kit.properties");
    properties.put("spring.config.additional-location", "");
    properties.put("spring.config.import", "");
    properties.put("spring.config.name", "local-verification-kit");
    properties.put("spring.profiles.active", PROFILE);
    properties.put("spring.profiles.default", PROFILE);
    properties.put("spring.profiles.include", "");
    properties.put("spring.main.web-application-type", "servlet");
    properties.put("spring.main.add-command-line-properties", "false");
    properties.put("spring.main.allow-bean-definition-overriding", "false");
    properties.put("spring.main.allow-circular-references", "false");
    properties.put("spring.main.lazy-initialization", "false");
    properties.put("spring.main.log-startup-info", "false");
    properties.put("spring.main.banner-mode", "off");
    properties.put("spring.application.name", "bridgego-local-verification");
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", "8080");
    properties.put("server.ssl.enabled", "false");
    properties.put("server.forward-headers-strategy", "none");
    properties.put("server.error.include-message", "never");
    properties.put("server.error.include-stacktrace", "never");
    properties.put("server.tomcat.accesslog.enabled", "false");
    properties.put("spring.jackson.mapper.allow-coercion-of-scalars", "false");
    properties.put("spring.web.resources.cache.cachecontrol.no-cache", "true");
    properties.put("spring.web.resources.cache.use-last-modified", "false");
    properties.put("spring.jmx.enabled", "false");
    properties.put("BRIDGE_RUNTIME_MODE", "game");
    properties.put("BRIDGE_SHEETS_ENABLED", "false");
    properties.put("BRIDGE_COLLECTION_MODE", "restricted");
    properties.put("BRIDGE_TEST_GOOGLE_IDENTITIES", "");
    properties.put("BRIDGE_PRIVACY_CONTACT", "");
    properties.put("BRIDGE_PRIVACY_RETENTION", "");
    properties.put("BRIDGE_ERASURE_WORKFLOW_VERIFIED", "false");
    properties.put("BRIDGE_ERASURE_EXECUTION_ENABLED", "false");
    properties.put("BRIDGE_ERASURE_WRITER_STOPPED_ACK", "false");
    properties.put("BRIDGE_QUARTER_POLICY_VERIFIED", "false");
    properties.put("BRIDGE_QUARTER_EXECUTION_ENABLED", "false");
    properties.put("BRIDGE_QUARTER_WRITER_STOPPED_ACK", "false");
    properties.put("GOOGLE_CLIENT_ID", clientId);
    properties.put("PUBLIC_ORIGIN", "");
    properties.put("bridge.public-origin", "");
    return Map.copyOf(properties);
  }

  static final class LockedEnvironment extends StandardServletEnvironment {
    private final Map<String, Object> locked;

    LockedEnvironment(Map<String, Object> locked) {
      this.locked = Map.copyOf(locked);
      installLockedSources();
    }

    @Override protected void customizePropertySources(MutablePropertySources sources) {
      // No system properties, process environment, JNDI or servlet property sources.
    }
    @Override public Map<String, Object> getSystemProperties() { return Map.of(); }
    @Override public Map<String, Object> getSystemEnvironment() { return Map.of(); }
    @Override protected String doGetActiveProfilesProperty() { return null; }
    @Override protected String doGetDefaultProfilesProperty() { return null; }
    @Override public void initPropertySources(ServletContext servletContext, ServletConfig servletConfig) {
      // The embedded servlet context must not add another configuration input later.
    }
    @Override protected ConfigurablePropertyResolver createPropertyResolver(MutablePropertySources sources) {
      return ConfigurationPropertySources.createPropertyResolver(sources);
    }

    void recheckAndLock() {
      for (var entry : locked.entrySet()) {
        if (!entry.getValue().toString().equals(getProperty(entry.getKey())))
          throw new IllegalStateException("Local verification configuration changed; startup refused.");
      }
      if (!Arrays.equals(getActiveProfiles(), new String[] {PROFILE})
          || !Arrays.equals(getDefaultProfiles(), new String[] {PROFILE}))
        throw new IllegalStateException("Local verification profiles changed; startup refused.");
      // Remove ConfigData/random/adapters and attach a fresh adapter to only our trusted values.
      installLockedSources();
      ConfigurationPropertySources.attach(this);
    }

    private void installLockedSources() {
      MutablePropertySources sources = getPropertySources();
      for (var source : sources) sources.remove(source.getName());
      sources.addFirst(new MapPropertySource(SOURCE, locked));
      setActiveProfiles(PROFILE);
      setDefaultProfiles(PROFILE);
    }
  }
}
