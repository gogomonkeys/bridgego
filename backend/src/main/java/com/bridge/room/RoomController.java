package com.bridge.room;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RoomController {
  private final RoomService service;

  public RoomController(RoomService service) {
    this.service = service;
  }

  record SessionRequest(String displayName) {}

  record ReadyRequest(Boolean ready) {}

  private RoomService.Identity identity(String auth) {
    if (auth == null || !auth.startsWith("Bearer "))
      throw RoomService.fail(401, "UNAUTHORIZED", "Bearer guest session required");
    return service.authenticate(auth.substring(7));
  }

  @PostMapping("/sessions")
  public RoomService.Identity session(@RequestBody SessionRequest request) {
    return service.createSession(request.displayName());
  }

  @GetMapping("/me")
  public RoomService.Me me(@RequestHeader(value = "Authorization", required = false) String auth) {
    return service.me(identity(auth));
  }

  @GetMapping("/rooms")
  public List<RoomService.Summary> rooms(
      @RequestHeader(value = "Authorization", required = false) String auth) {
    identity(auth);
    return service.list();
  }

  @PostMapping("/rooms")
  public RoomService.Snapshot create(
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return service.create(identity(auth));
  }

  @PostMapping("/rooms/{id}/join")
  public RoomService.Snapshot join(
      @PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return service.join(id, identity(auth));
  }

  @GetMapping("/rooms/{id}")
  public RoomService.Snapshot get(
      @PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return service.get(id, identity(auth));
  }

  @PutMapping("/rooms/{id}/ready")
  public RoomService.Snapshot ready(
      @PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody ReadyRequest request) {
    RoomService.Identity who = identity(auth);
    if (request.ready() == null)
      throw RoomService.fail(400, "INVALID_READY", "ready must be a boolean");
    return service.ready(id, who, request.ready());
  }

  @PostMapping("/rooms/{id}/leave")
  public Map<String, Boolean> leave(
      @PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    service.leave(id, identity(auth));
    return Map.of("left", true);
  }

  record StartRequest(String actionId, Long expectedRevision) {}

  record PrivilegeRequest(String actionId, Long expectedRevision, Boolean use) {}

  record PlayRequest(String actionId, Long expectedRevision, GameState.Card card) {}

  record CallRequest(
      String actionId, Long expectedRevision, String type, Integer level, String strain) {}

  @PostMapping("/rooms/{id}/start")
  public RoomService.CommandResult start(
      @PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody StartRequest request) {
    return service.start(id, identity(auth), request.actionId(), request.expectedRevision());
  }

  @PutMapping("/rooms/{id}/games/{gameId}/deals/{dealId}/privilege")
  public RoomService.CommandResult privilege(
      @PathVariable String id,
      @PathVariable String gameId,
      @PathVariable String dealId,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody PrivilegeRequest request) {
    RoomService.Identity who = identity(auth);
    if (request.use() == null)
      throw RoomService.fail(400, "INVALID_PRIVILEGE", "use must be a boolean");
    return service.privilege(
        id, who, gameId, dealId, request.actionId(), request.expectedRevision(), request.use());
  }

  @PostMapping("/rooms/{id}/games/{gameId}/deals/{dealId}/calls")
  public RoomService.CommandResult call(
      @PathVariable String id,
      @PathVariable String gameId,
      @PathVariable String dealId,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody CallRequest request) {
    return service.call(
        id,
        identity(auth),
        gameId,
        dealId,
        request.actionId(),
        request.expectedRevision(),
        request.type(),
        request.level(),
        request.strain());
  }

  @PostMapping("/rooms/{id}/games/{gameId}/deals/{dealId}/plays")
  public RoomService.CommandResult play(
      @PathVariable String id,
      @PathVariable String gameId,
      @PathVariable String dealId,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody PlayRequest request) {
    return service.play(
        id,
        identity(auth),
        gameId,
        dealId,
        request.actionId(),
        request.expectedRevision(),
        request.card());
  }

  @GetMapping("/rooms/{id}/last-match")
  public GameState.MatchArchive lastMatch(
      @PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return service.lastMatch(id, identity(auth));
  }

  @PostMapping("/rooms/{id}/games/{gameId}/rematch")
  public RoomService.CommandResult rematch(
      @PathVariable String id,
      @PathVariable String gameId,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody StartRequest request) {
    return service.rematch(
        id, identity(auth), gameId, request.actionId(), request.expectedRevision());
  }

  @PostMapping("/rooms/{id}/games/{gameId}/deals/{dealId}/acknowledge-result")
  public RoomService.CommandResult acknowledgeResult(
      @PathVariable String id,
      @PathVariable String gameId,
      @PathVariable String dealId,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody StartRequest request) {
    return service.acknowledgeResult(
        id, identity(auth), gameId, dealId, request.actionId(), request.expectedRevision());
  }

  @ExceptionHandler(RoomService.Failure.class)
  public ResponseEntity<?> failure(RoomService.Failure e) {
    return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> malformed(HttpMessageNotReadableException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof BoundedBodyRequest.TooLarge)
        return ResponseEntity.status(413).body(Map.of(
            "code", "REQUEST_TOO_LARGE", "message", "Request body is too large"));
    }
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_JSON", "message", "Invalid request body"));
  }
}
