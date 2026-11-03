package com.bridge.room;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceHealthController {
  private final RoomService service;
  private final String version;

  public ServiceHealthController(RoomService service,
      @Value("${bridge.app-version:0.8.0}") String version) {
    this.service = service;
    this.version = version;
  }

  @GetMapping("/health/live")
  public ResponseEntity<?> live() { return response(false); }

  @GetMapping("/health/ready")
  public ResponseEntity<?> ready() { return response(true); }

  private ResponseEntity<?> response(boolean readiness) {
    boolean stopping = service.stopping();
    return ResponseEntity.status(readiness && stopping ? 503 : 200)
        .cacheControl(CacheControl.noStore())
        .header("X-Bridge-Instance-Epoch", service.instanceEpoch())
        .body(Map.of("status", stopping ? "stopping" : "ready",
            "instanceEpoch", service.instanceEpoch(), "version", version));
  }
}
