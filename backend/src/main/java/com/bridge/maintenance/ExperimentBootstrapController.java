package com.bridge.maintenance;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public final class ExperimentBootstrapController {
  private static final String COOKIE="__Host-bridge_experiment_bootstrap";
  private final ExperimentBootstrapSessions sessions;
  private final ExperimentBootstrapSessions.Settings settings;
  public ExperimentBootstrapController(ExperimentBootstrapSessions sessions,ExperimentBootstrapSessions.Settings settings){this.sessions=sessions;this.settings=settings;}
  public record ChallengeRequest(String purpose){}
  public record ProofRequest(String credential,Boolean consent){}
  public record ExecuteRequest(String credential,Boolean consent,String planId,String confirmation){}
  @GetMapping("/health/ready") public Map<String,String> health(){return Map.of("status","ready","mode","experiment-bootstrap");}
  @GetMapping(value={"/","/maintenance"},produces="text/html;charset=UTF-8") public ResponseEntity<String> page(){return ResponseEntity.ok().header("Content-Security-Policy", "default-src 'none'; script-src 'self' https://accounts.google.com/gsi/client; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; style-src 'unsafe-inline' https://accounts.google.com/gsi/style; img-src 'self' https://*.googleusercontent.com; base-uri 'none'; form-action 'none'; frame-ancestors 'none'").body(ExperimentBootstrapPage.HTML);}
  @GetMapping(value="/maintenance/app.js",produces="application/javascript;charset=UTF-8") public String script(){return ExperimentBootstrapPage.JS;}
  @PostMapping("/maintenance/session") public Map<String,Object> session(HttpServletResponse response){response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,sessions.create()).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(900).build().toString());return Map.of("clientId",settings.clientId(),"executionEnabled",settings.executionEnabled(),"writerStoppedAcknowledged",settings.writersStopped(),"mode","experiment-bootstrap","target",settings.sheet(),"dataset",settings.dataset(),"expiresAt",settings.expiry().toString());}
  @PostMapping("/maintenance/challenge") public ExperimentBootstrapSessions.Challenge challenge(@CookieValue(value=COOKIE,required=false) String cookie,@RequestBody ChallengeRequest r){return sessions.challenge(cookie,r.purpose());}
  @PostMapping("/maintenance/preview") public ExperimentBootstrapSessions.Preview preview(@CookieValue(value=COOKIE,required=false) String cookie,@RequestBody ProofRequest r){return sessions.preview(cookie,r.credential(),r.consent());}
  @PostMapping("/maintenance/execute") public Map<String,String> execute(@CookieValue(value=COOKIE,required=false) String cookie,@RequestBody ExecuteRequest r){return Map.of("outcome",sessions.execute(cookie,r.credential(),r.consent(),r.planId(),r.confirmation()));}
  @ExceptionHandler(ExperimentBootstrapSessions.Failure.class) public ResponseEntity<?> rejected(ExperimentBootstrapSessions.Failure e){return ResponseEntity.status(e.status).body(Map.of("code",e.code));}
  @ExceptionHandler(Exception.class) public ResponseEntity<?> failed(Exception ignored){return ResponseEntity.badRequest().body(Map.of("code","INITIALIZATION_REJECTED","message","Initialization did not establish success. Keep writers stopped; do not retry an uncertain operation."));}
}
