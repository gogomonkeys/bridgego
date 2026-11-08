package com.bridge.syntheticcleanup;

import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupApplicationTest {
  @Test void disabledDedicatedGraphStartsWithoutGameSchedulersSecretOrNetworkReaders() throws Exception {
    String port; try (ServerSocket socket = new ServerSocket(0)) { port = Integer.toString(socket.getLocalPort()); }
    AtomicInteger secretAccess = new AtomicInteger();
    var app = SyntheticCleanupApplication.application(new String[0], key -> {
      if (key.equals("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON")) { secretAccess.incrementAndGet(); throw new AssertionError("No secret access"); }
      if (key.equals("PORT")) return port;
      if (key.equals("BRIDGE_SYNTHETIC_PREFLIGHT_ENABLED")) return "false";
      throw new AssertionError("Unexpected environment access: " + key);
    });
    try (var context = app.run()) {
      assertEquals(0, secretAccess.get());
      assertTrue(context.getBeansOfType(com.bridge.room.RoomService.class).isEmpty());
      assertTrue(context.getBeansOfType(com.bridge.persistence.SheetsGateway.class).isEmpty());
      assertFalse(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"));
      assertEquals(false, context.getBean(SyntheticCleanupService.class).health().get("writesEnabled"));
      assertEquals(false, context.getBean(SyntheticCleanupService.class).health().get("configured"));
      assertEquals("synthetic-readonly-preflight", context.getEnvironment().getActiveProfiles()[0]);
    }
  }
  @Test void argumentsAndSystemConfigurationCannotSelectGameOrExternalConfig() {
    assertThrows(IllegalArgumentException.class, () -> SyntheticCleanupApplication.application(new String[] {"--spring.profiles.active=prod"}, key -> null));
    var env = new SyntheticCleanupApplication.LockedEnvironment(SyntheticCleanupApplication.lockedProperties("8080"));
    assertTrue(env.getSystemEnvironment().isEmpty()); assertTrue(env.getSystemProperties().isEmpty());
    assertEquals("classpath:/synthetic-cleanup.properties", env.getProperty("spring.config.location"));
    assertFalse(SyntheticCleanupApplication.Configuration.class.isAnnotationPresent(org.springframework.context.annotation.ComponentScan.class));
    assertFalse(SyntheticCleanupApplication.Configuration.class.isAnnotationPresent(org.springframework.scheduling.annotation.EnableScheduling.class));
  }
  @Test void debugWireLoggingIsRejectedBeforeConfigurationOrCredentials() {
    String prior = System.getProperty("jdk.httpclient.HttpClient.log");
    try {
      System.setProperty("jdk.httpclient.HttpClient.log", "headers");
      assertThrows(IllegalArgumentException.class, () -> SyntheticCleanupApplication.application(new String[0], key -> {
        throw new AssertionError("Must reject before configuration");
      }));
    } finally { if (prior == null) System.clearProperty("jdk.httpclient.HttpClient.log"); else System.setProperty("jdk.httpclient.HttpClient.log", prior); }
  }

}
