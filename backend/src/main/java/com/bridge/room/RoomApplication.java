package com.bridge.room;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.bridge.room", "com.bridge.persistence"})
@EnableScheduling
public class RoomApplication {
  public static void main(String[] args) {
    String mode = System.getenv().getOrDefault("BRIDGE_RUNTIME_MODE", "game");
    if (mode.equals("game")) SpringApplication.run(RoomApplication.class, args);
    else if (mode.equals("erasure")) {
      var app = new SpringApplication(com.bridge.maintenance.ErasureMaintenanceApplication.class);
      app.setAdditionalProfiles("erasure-maintenance");
      app.setDefaultProperties(java.util.Map.of("spring.web.resources.add-mappings", "false"));
      app.run(args);
    } else if (mode.equals("experiment-bootstrap")) {
      var app = new SpringApplication(com.bridge.maintenance.ExperimentBootstrapApplication.class);
      app.setAdditionalProfiles("experiment-bootstrap");
      app.setDefaultProperties(java.util.Map.of("spring.web.resources.add-mappings", "false"));
      app.run(args);
    } else throw new IllegalArgumentException("Unknown runtime mode");
  }
}
