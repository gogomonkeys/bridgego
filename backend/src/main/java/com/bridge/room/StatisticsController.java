package com.bridge.room;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/statistics")
public final class StatisticsController {
  private final RoomService rooms;
  private final StatisticsService statistics;
  private final Set<String> origins;
  public record Config(boolean enabled, String consentVersion) {}
  public record Consent(Boolean consent, String version) {}

  public StatisticsController(RoomService rooms, StatisticsService statistics, CollectionPolicy policy,
      @Value("${PUBLIC_ORIGIN:}") String publicOrigin, Environment env) {
    this.rooms = rooms; this.statistics = statistics;
    origins = Set.of(AllowedOrigins.forProfile(
        Arrays.asList(env.getActiveProfiles()).contains("prod") || policy.managed() || policy.experiment(), publicOrigin));
  }

  private RoomService.Identity identity(String bearer) {
    if (bearer == null || !bearer.startsWith("Bearer "))
      throw RoomService.fail(401, "UNAUTHORIZED", "Bearer session required");
    return rooms.authenticate(bearer.substring(7));
  }

  @GetMapping("/config") public Config config() {
    return new Config(rooms.statisticsEnabled(), StatisticsService.CONSENT_VERSION);
  }

  @PutMapping("/consent") public Map<String, Boolean> consent(
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestHeader(value = "Origin", required = false) String origin, @RequestBody Consent request) {
    if (origin == null || !origins.contains(origin)) throw RoomService.fail(403, "AUTH_ORIGIN", "Allowed origin required");
    if (request == null) throw RoomService.fail(400, "STATS_CONSENT_INVALID", "Current statistics notice acknowledgement required");
    rooms.statisticsConsent(identity(bearer), request.consent(), request.version());
    return Map.of("accepted", Boolean.TRUE.equals(request.consent()));
  }

  @GetMapping public StatisticsService.Report read(
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    return statistics.read(identity(bearer));
  }

  @ExceptionHandler(RoomService.Failure.class) public ResponseEntity<?> failure(RoomService.Failure e) {
    return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<?> malformed() {
    return ResponseEntity.badRequest().body(Map.of("code", "INVALID_JSON", "message", "Invalid request body"));
  }
}
