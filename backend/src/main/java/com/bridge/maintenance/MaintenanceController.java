package com.bridge.maintenance;

import com.bridge.persistence.OperatorAccountErasure;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class MaintenanceController {
  static final String COOKIE="__Host-bridge_erasure";
  private final MaintenanceSessions sessions;
  private final ErasureMaintenanceApplication.MaintenanceSettings settings;
  public MaintenanceController(MaintenanceSessions sessions,ErasureMaintenanceApplication.MaintenanceSettings settings){this.sessions=sessions;this.settings=settings;}
  public record ChallengeRequest(String purpose) {}
  public record ProofRequest(String credential,Boolean consent) {}
  public record ExecuteRequest(String credential,Boolean consent,String planId,String confirmation) {}
  @GetMapping("/health/ready") public Map<String,String> health(){return Map.of("status","ready","mode","erasure");}
  @GetMapping(value={"/","/maintenance"},produces=MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> page(){return ResponseEntity.ok().header("Content-Security-Policy",
      "default-src 'none'; script-src 'self' https://accounts.google.com/gsi/client; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; style-src 'unsafe-inline' https://accounts.google.com/gsi/style; img-src 'self' https://*.googleusercontent.com; base-uri 'none'; form-action 'none'; frame-ancestors 'none'").body(MaintenancePage.HTML);}
  @GetMapping(value="/maintenance/app.js",produces="application/javascript") public String script(){return MaintenancePage.JS;}
  @PostMapping("/maintenance/session") public Map<String,Object> session(HttpServletResponse response) {
    response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,sessions.create()).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(900).build().toString());
    return Map.of("clientId",settings.clientId(),"executionEnabled",settings.executionEnabled(),
        "writerStoppedAcknowledged",settings.writerStoppedAcknowledged(),"mode","erasure");
  }
  @PostMapping("/maintenance/challenge") public OperatorAccountErasure.Challenge challenge(
      @CookieValue(value=COOKIE,required=false) String cookie,@RequestBody ChallengeRequest request){return sessions.challenge(cookie,request.purpose());}
  @PostMapping("/maintenance/preview") public OperatorAccountErasure.Preview preview(
      @CookieValue(value=COOKIE,required=false) String cookie,@RequestBody ProofRequest request){return sessions.preview(cookie,request.credential(),request.consent());}
  @PostMapping("/maintenance/execute") public Map<String,String> execute(
      @CookieValue(value=COOKIE,required=false) String cookie,@RequestBody ExecuteRequest request){
    return Map.of("outcome",sessions.execute(cookie,request.credential(),request.consent(),request.planId(),request.confirmation()).name());
  }
  @ExceptionHandler(MaintenanceSessions.Failure.class) public ResponseEntity<?> failure(MaintenanceSessions.Failure failure){
    return ResponseEntity.status(failure.status).body(Map.of("code",failure.code));
  }
  @ExceptionHandler(Exception.class) public ResponseEntity<?> rejected(Exception ignored){
    return ResponseEntity.badRequest().body(Map.of("code","ERASURE_REQUEST_REJECTED","message","Request could not be completed. Obtain a fresh preview; never retry an uncertain deletion automatically."));
  }
}
