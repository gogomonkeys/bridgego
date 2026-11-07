package com.bridge.localverification;

import com.bridge.maintenance.MaintenanceController;
import com.bridge.persistence.GoogleSheetsGateway;
import com.bridge.persistence.SheetsGateway;
import com.bridge.persistence.SheetsStatsStore;
import com.bridge.persistence.StatsStore;
import com.bridge.room.BetaRequestFilter;
import com.bridge.room.CollectionPolicy;
import com.bridge.room.GoogleAuthService;
import com.bridge.room.IdentityStore;
import com.bridge.room.RoomService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalVerificationApplicationTest {
  static final String CLIENT = "123456789012-offlineFixtureOnly123.apps.googleusercontent.com";
  static final String[] OFF_FLAGS = {"BRIDGE_SHEETS_ENABLED", "BRIDGE_ERASURE_WORKFLOW_VERIFIED",
      "BRIDGE_QUARTER_POLICY_VERIFIED", "BRIDGE_ERASURE_EXECUTION_ENABLED", "BRIDGE_ERASURE_WRITER_STOPPED_ACK",
      "BRIDGE_QUARTER_EXECUTION_ENABLED", "BRIDGE_QUARTER_WRITER_STOPPED_ACK"};

  @Test void rejectsAllArgumentsBeforeReadingAnyEnvironmentVariable() {
    for (var args : List.of(new String[]{"--server.address=0.0.0.0"}, new String[]{"--server.port=9999"},
        new String[]{"--spring.profiles.active=prod"}, new String[]{"--help"}, new String[]{"secret-do-not-echo"})) {
      var failure = assertThrows(IllegalArgumentException.class,
          () -> LocalVerificationApplication.application(args, name -> { throw new AssertionError("Must not read any variable"); }));
      assertFalse(failure.getMessage().contains(args[0]));
    }
  }

  @Test void onlyPublicClientVariableIsReadAndMalformedInputNeverAppearsInErrors() {
    List<String> requested = new ArrayList<>();
    LocalVerificationApplication.application(new String[0], name -> { requested.add(name); return CLIENT; });
    assertEquals(List.of("GOOGLE_CLIENT_ID"), requested);
    assertEquals("", LocalVerificationApplication.clientId(null));
    assertEquals("", LocalVerificationApplication.clientId(""));
    assertEquals(CLIENT, LocalVerificationApplication.clientId(CLIENT));
    for (String invalid : List.of("client-secret", " ", CLIENT + " ", "https://" + CLIENT,
        "${BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON}", "1234-x.apps.googleusercontent.com", "x\nsecret", "x".repeat(1024))) {
      var failure = assertThrows(IllegalArgumentException.class, () -> LocalVerificationApplication.clientId(invalid));
      assertEquals("GOOGLE_CLIENT_ID must be a public Google web client ID ending in .apps.googleusercontent.com. No supplied value was logged.", failure.getMessage());
    }
  }

  @Test void lockedSourcesContainNoSystemServletOrCredentialInputsAndRejectTampering() {
    var environment = new LocalVerificationApplication.LockedEnvironment(LocalVerificationApplication.lockedProperties(CLIENT));
    assertTrue(environment.getSystemEnvironment().isEmpty());
    assertTrue(environment.getSystemProperties().isEmpty());
    MockServletContext servlet = new MockServletContext();
    servlet.addInitParameter("server.address", "0.0.0.0");
    environment.initPropertySources(servlet, null);
    assertEquals("127.0.0.1", environment.getProperty("server.address"));
    assertNull(environment.getProperty("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON"));
    environment.recheckAndLock();
    environment.getPropertySources().addFirst(new MapPropertySource("unexpected", Map.of("BRIDGE_SHEETS_ENABLED", "true")));
    assertThrows(IllegalStateException.class, environment::recheckAndLock);
    var profiles = new LocalVerificationApplication.LockedEnvironment(LocalVerificationApplication.lockedProperties(""));
    profiles.setActiveProfiles("prod");
    assertThrows(IllegalStateException.class, profiles::recheckAndLock);
  }

  @Test void actualSpringPipelineDefaultsToGuestOnlyWithoutPersistence() throws Exception {
    try (var context = mockApplication(null).run()) { verifyContext(context, false); }
  }

  @Test void optionalPublicClientEnablesOnlyVerification() throws Exception {
    try (var context = mockApplication(CLIENT).run()) { verifyContext(context, true); }
  }

  @Test void hostileProcessVariablesJvmPropertiesAndWorkingDirectoryConfigAreIgnored(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("application.properties"),
        "spring.config.import=file:./THIS-MUST-NOT-BE-READ.properties\nBRIDGE_SHEETS_ENABLED=true\n");
    Files.createDirectory(directory.resolve("config"));
    Files.writeString(directory.resolve("config/application-prod.properties"), "server.address=0.0.0.0\nserver.port=9999\n");
    Files.writeString(directory.resolve("local-verification-kit.properties"), "spring.config.import=file:./MUST-NOT-LOAD.properties\n");
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dserver.address=0.0.0.0", "-Dserver.port=9999", "-Dserver.ssl.enabled=true",
        "-Dspring.profiles.active=prod,erasure-maintenance", "-Dspring.profiles.include=prod",
        "-Dspring.config.location=file:./THIS-MUST-NOT-BE-READ.properties",
        "-Dspring.config.import=file:./THIS-MUST-NOT-BE-READ.properties",
        "-Dspring.main.web-application-type=none", "-Dspring.main.sources=com.bridge.maintenance.ErasureMaintenanceApplication",
        "-Dspring.application.json={malformed-json", "-DGOOGLE_CLIENT_ID=not-the-authorized-input",
        "-DBRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON=invalid-secret-must-never-be-read",
        "-DBRIDGE_TEST_SPREADSHEET_ID=do-not-contact", "-DBRIDGE_TEST_DATASET_ID=invalid",
        "-DBRIDGE_RUNTIME_MODE=erasure"));
    for (String flag : OFF_FLAGS) command.add("-D" + flag + "=true");
    command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Probe.class.getName()));
    var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    var environment = process.environment();
    for (String variable : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) environment.remove(variable);
    environment.putAll(Map.of(
        "SPRING_PROFILES_ACTIVE", "prod,erasure-maintenance", "SPRING_CONFIG_LOCATION", "file:./THIS-MUST-NOT-BE-READ.properties",
        "SPRING_CONFIG_ADDITIONAL_LOCATION", "file:./MUST-NOT-LOAD.properties", "SPRING_APPLICATION_JSON", "{malformed-json",
        "SERVER_ADDRESS", "0.0.0.0", "SERVER_PORT", "9999", "BRIDGE_RUNTIME_MODE", "erasure",
        "BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON", "invalid-secret-must-never-be-read", "GOOGLE_CLIENT_ID", CLIENT));
    environment.put("BRIDGE_TEST_SPREADSHEET_ID", "do-not-contact");
    environment.put("BRIDGE_TEST_DATASET_ID", "invalid");
    environment.put("BRIDGE_TEST_GOOGLE_IDENTITIES", "invalid-json-must-never-be-read");
    environment.put("BRIDGE_COLLECTION_MODE", "public");
    environment.put("PUBLIC_ORIGIN", "https://untrusted.example");
    for (String flag : OFF_FLAGS) environment.put(flag, "true");
    Path log = directory.resolve("probe.log");
    process.redirectOutput(log.toFile());
    Process child = process.start();
    try {
      assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Isolated context probe timed out");
      String output = Files.readString(log);
      assertEquals(0, child.exitValue(), output);
      assertTrue(output.contains("LOCAL_VERIFICATION_ISOLATION_PASS"), output);
      assertFalse(output.contains(CLIENT), "Public client ID must not be printed");
      assertFalse(output.contains("invalid-secret-must-never-be-read"), "Credential variable must not be printed");
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  static SpringApplication mockApplication(String client) {
    return noSocket(LocalVerificationApplication.application(new String[0], name -> client));
  }

  static SpringApplication noSocket(SpringApplication application) {
    // Exercise real SpringApplication/ConfigData/bean pipeline, but no actual listening socket.
    application.setApplicationContextFactory(type -> new GenericWebApplicationContext(new MockServletContext()));
    application.setRegisterShutdownHook(false);
    return application;
  }

  static void verifyContext(ConfigurableApplicationContext context, boolean verification) throws Exception {
    var environment = context.getEnvironment();
    assertArrayEquals(new String[]{"local-verification"}, environment.getActiveProfiles());
    assertEquals("127.0.0.1", environment.getProperty("server.address"));
    assertEquals("8080", environment.getProperty("server.port"));
    assertEquals("game", environment.getProperty("BRIDGE_RUNTIME_MODE"));
    assertNull(environment.getProperty("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON"));
    assertNull(environment.getProperty("BRIDGE_TEST_SPREADSHEET_ID"));
    for (String flag : OFF_FLAGS) assertEquals("false", environment.getProperty(flag), flag);
    var factory = context.getBean(TomcatServletWebServerFactory.class);
    assertEquals("127.0.0.1", factory.getAddress().getHostAddress());
    assertEquals(8080, factory.getPort());
    assertFalse(context.getBean(IdentityStore.class).enabled());
    assertFalse(context.getBean(CollectionPolicy.class).ready());
    assertFalse(context.getBean(CollectionPolicy.class).hasTesters());
    assertEquals(StatsStore.SaveStatus.DISABLED, context.getBean(StatsStore.class).submit(null));
    assertEquals(StatsStore.SaveStatus.DISABLED, context.getBean(StatsStore.class).status("synthetic"));
    assertTrue(context.getBean(StatsStore.class).exportCommitted().isEmpty());
    assertThrows(IllegalStateException.class, () -> context.getBean(SheetsGateway.class).read("accounts"));
    assertThrows(IllegalStateException.class, () -> context.getBean(IdentityStore.class).findOrCreateAccount("issuer", "subject"));
    assertTrue(context.getBeansOfType(GoogleSheetsGateway.class).isEmpty());
    assertTrue(context.getBeansOfType(SheetsStatsStore.class).isEmpty());
    assertTrue(context.getBeansOfType(MaintenanceController.class).isEmpty());
    var config = context.getBean(GoogleAuthService.class).config();
    assertEquals(verification, config.verificationEnabled());
    assertFalse(config.enabled());
    assertFalse(config.collectionReady());
    var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) context)
        .addFilters(context.getBean(BetaRequestFilter.class)).build();
    mvc.perform(get("/api/auth/config")).andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationEnabled").value(verification)).andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.collectionReady").value(false));
    mvc.perform(get("/health/ready")).andExpect(status().isOk());
    mvc.perform(get("/maintenance")).andExpect(status().isNotFound());
    mvc.perform(post("/maintenance/execute")).andExpect(status().isNotFound());
    var rooms = context.getBean(RoomService.class);
    var guest = rooms.createSession("Guest");
    String bearer = "Bearer " + guest.token();
    mvc.perform(post("/api/auth/nonce").header("Authorization", bearer).header("Origin", "http://localhost:8080")
        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"link\"}"))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GOOGLE_AUTH_DISABLED"));
    mvc.perform(post("/api/auth/google").header("Authorization", bearer).header("Origin", "http://localhost:8080")
        .contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"never-verified\",\"consent\":true}"))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GOOGLE_AUTH_DISABLED"));
    mvc.perform(post("/api/auth/nonce").header("Authorization", bearer).header("Origin", "http://localhost:8080")
        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"verify\"}"))
        .andExpect(verification ? status().isOk() : status().isServiceUnavailable());
    assertNull(rooms.accountId(guest));
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      try (var context = noSocket(LocalVerificationApplication.application(new String[0], System::getenv)).run()) {
        verifyContext(context, true);
      }
      System.out.println("LOCAL_VERIFICATION_ISOLATION_PASS");
    }
  }
}
