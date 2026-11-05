package com.bridge.maintenance;

import com.bridge.persistence.*;
import com.bridge.room.GoogleCredentialVerifier;
import com.bridge.room.GoogleJwtVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Explicit graph only: never scans or starts game services, identity linking or write queues. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Profile("erasure-maintenance")
public class ErasureMaintenanceApplication {
  @Bean MaintenanceSettings maintenanceSettings(Environment env) {
    return new MaintenanceSettings(env.getRequiredProperty("PUBLIC_ORIGIN"), env.getRequiredProperty("GOOGLE_CLIENT_ID"),
        Boolean.parseBoolean(env.getProperty("BRIDGE_ERASURE_EXECUTION_ENABLED", "false")),
        Boolean.parseBoolean(env.getProperty("BRIDGE_ERASURE_WRITER_STOPPED_ACK", "false")));
  }
  public record MaintenanceSettings(String origin, String clientId, boolean executionEnabled, boolean writerStoppedAcknowledged) {
    public MaintenanceSettings {
      URI uri;
      try { uri=URI.create(origin); } catch(Exception invalid) { throw new IllegalArgumentException("Valid public HTTPS origin required"); }
      if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
          || !uri.getRawPath().isEmpty() || uri.getQuery()!=null || uri.getFragment()!=null
          || origin.contains("*") || origin.contains(",") || uri.getPort()==0 || uri.getPort()>65535)
        throw new IllegalArgumentException("Valid public HTTPS origin required");
      if(clientId==null || clientId.isBlank())throw new IllegalArgumentException("Google client ID required");
    }
  }
  @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(SheetsGateway.class)
  SheetsGateway maintenanceGateway(Environment env) {
    String sheet=env.getRequiredProperty("BRIDGE_TEST_SPREADSHEET_ID");
    if(sheet.isBlank() || sheet.equals(env.getProperty("BRIDGE_SHEETS_SPREADSHEET_ID", "")))
      throw new IllegalArgumentException("A separate test dataset is required");
    return new TestDatasetGateway(new GoogleSheetsGateway(sheet,
        env.getRequiredProperty("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON")), env.getRequiredProperty("BRIDGE_TEST_DATASET_ID"));
  }
  @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(GoogleCredentialVerifier.class)
  GoogleCredentialVerifier maintenanceVerifier(ObjectMapper json) { return new GoogleJwtVerifier(json); }
  @Bean OperatorAccountErasure erasureEngine(SheetsGateway gateway, GoogleCredentialVerifier verifier, MaintenanceSettings settings) {
    return new OperatorAccountErasure(gateway, verifier, settings.clientId(), Clock.systemUTC(),
        settings::writerStoppedAcknowledged, settings.executionEnabled());
  }
  @Bean MaintenanceSessions maintenanceSessions(OperatorAccountErasure engine, MaintenanceSettings settings) {
    return new MaintenanceSessions(engine, settings, Clock.systemUTC());
  }
  @Bean MaintenanceController maintenanceController(MaintenanceSessions sessions, MaintenanceSettings settings) {
    return new MaintenanceController(sessions, settings);
  }
  @Bean org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictMaintenanceJson() {
    return builder -> builder.failOnUnknownProperties(true);
  }
  @Bean MaintenanceGuard maintenanceGuard(MaintenanceSettings settings) { return new MaintenanceGuard(settings); }
}
