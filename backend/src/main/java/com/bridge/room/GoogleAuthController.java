package com.bridge.room;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
public class GoogleAuthController {
  private final RoomService rooms;
  private final GoogleAuthService auth;
  public GoogleAuthController(RoomService rooms, GoogleAuthService auth) { this.rooms = rooms; this.auth = auth; }
  public record LoginRequest(String credential, Boolean consent) {}
  public record CancelRequest(String nonce) {}
  public record NonceRequest(String purpose) {}
  private RoomService.Identity identity(String bearer) {
    if (bearer == null || !bearer.startsWith("Bearer "))
      throw RoomService.fail(401, "UNAUTHORIZED", "Bearer guest session required");
    return rooms.authenticate(bearer.substring(7));
  }
  @GetMapping("/config") public GoogleAuthService.Config config() { return auth.config(); }
  @GetMapping("/status") public GoogleAuthService.Status status(
      @RequestHeader(value="Authorization", required=false) String bearer) { return auth.status(identity(bearer)); }
  @PostMapping("/nonce") public GoogleAuthService.Challenge nonce(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin, @RequestBody(required=false) NonceRequest request) {
    return auth.nonce(identity(bearer), origin, request == null ? "link" : request.purpose());
  }
  @PostMapping("/google") public GoogleAuthService.Status login(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin, @RequestBody LoginRequest request) {
    return auth.login(identity(bearer), origin, request.credential(), request.consent());
  }
  @PostMapping("/google/verify") public GoogleAuthService.VerifiedIdentity verifyOnly(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin, @RequestBody LoginRequest request) {
    return auth.verifyOnly(identity(bearer), origin, request.credential(), request.consent());
  }
  @PostMapping("/logout") public GoogleAuthService.Status logout(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin) { return auth.logout(identity(bearer), origin); }
  @PostMapping("/cancel") public Map<String, Boolean> cancel(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin, @RequestBody CancelRequest request) {
    auth.cancel(identity(bearer), origin, request.nonce()); return Map.of("cancelled", true);
  }
  @ExceptionHandler(RoomService.Failure.class) public ResponseEntity<?> failure(RoomService.Failure e) {
    return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
  }
  @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<?> malformed(HttpMessageNotReadableException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof BoundedBodyRequest.TooLarge)
        return ResponseEntity.status(413).body(Map.of("code", "REQUEST_TOO_LARGE", "message", "Request body is too large"));
    }
    return ResponseEntity.badRequest().body(Map.of("code", "INVALID_JSON", "message", "Invalid request body"));
  }
}
