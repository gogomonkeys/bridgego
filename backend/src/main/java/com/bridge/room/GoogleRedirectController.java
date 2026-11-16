package com.bridge.room;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Opt-in redirect bridge. Credentials live only in bounded, short-lived process memory.
 * Callback never writes accounts; only the original bearer session can claim one finish.
 * Restart loses attempts and fails closed. UNKNOWN/SUBMITTING cannot be replaced or retried.
 */
@RestController
@RequestMapping("/api/auth/google/redirect")
public final class GoogleRedirectController {
  // These codes originate solely before IdentityStore is touched by loginForEntry/link.
  // AUTH_CANCELLED, AUTH_IN_ROOM, UNAUTHORIZED and all storage failures are deliberately
  // excluded: the same code can occur after a write or while its result is uncertain.
  private static final Set<String> PRE_STORAGE_FAILURES = Set.of(
      "INVALID_GOOGLE_CREDENTIAL", "AUTH_NONCE_EXPIRED", "AUTH_PURPOSE_MISMATCH",
      "CONSENT_REQUIRED", "PROFILE_CONSENT_REQUIRED", "TESTER_NOT_ADMITTED",
      "ORIGIN_NOT_ALLOWED", "GOOGLE_AUTH_DISABLED", "EXPERIMENT_RECOVERY_REQUIRED");
  private static final String COOKIE = "__Host-bridge_google_redirect";
  private final RoomService rooms;
  private final GoogleAuthService auth;
  private final boolean enabled;
  private final String origin;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Attempt> attempts = new HashMap<>();
  private static final class Attempt {
    final String binding, nonce;
    final long expires;
    String state = "WAITING", credential;
    GoogleAuthService.Status result;
    Attempt(String binding, String nonce, long expires) { this.binding=binding;this.nonce=nonce;this.expires=expires; }
  }
  public record StartRequest(Boolean consent, String profileConsentVersion) {}
  public record Started(String nonce, int expiresInSeconds, String loginUri) {}
  public record State(String state, GoogleAuthService.Status status) {}
  @Autowired public GoogleRedirectController(RoomService rooms, GoogleAuthService auth,
      @Value("${BRIDGE_GOOGLE_REDIRECT_ENABLED:false}") boolean enabled,
      @Value("${PUBLIC_ORIGIN:}") String origin) {
    this(rooms, auth, enabled, origin, Clock.systemUTC());
  }
  GoogleRedirectController(RoomService rooms, GoogleAuthService auth, boolean enabled, String origin, Clock clock) {
    this.rooms=rooms;this.auth=auth;this.enabled=enabled;this.origin=origin;this.clock=clock;
    if (enabled) {
      URI uri=URI.create(origin);
      if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null
          || uri.getFragment()!=null || !uri.getPath().isEmpty()) throw new IllegalStateException("Redirect requires HTTPS PUBLIC_ORIGIN without path");
    }
  }
  private String loginUri() { return origin+"/api/auth/google/redirect/callback"; }
  @GetMapping("/config") public Map<String,Object> config() {
    return Map.of("enabled", enabled && auth.config().enabled() && "managed".equals(auth.config().collectionMode()), "loginUri", enabled ? loginUri() : "");
  }
  private void available() {
    if (!Boolean.TRUE.equals(config().get("enabled"))) throw RoomService.fail(503,"REDIRECT_DISABLED","Redirect sign-in unavailable");
  }
  private RoomService.Identity who(String bearer) {
    if (bearer==null || !bearer.startsWith("Bearer ")) throw RoomService.fail(401,"UNAUTHORIZED","Original guest session required");
    return rooms.authenticate(bearer.substring(7));
  }
  private void origin(String value) {
    if (!origin.equals(value)) throw RoomService.fail(403,"ORIGIN_DENIED","Origin rejected");
  }
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000)
  public synchronized void expireCredentials() {
    for (Attempt a:attempts.values()) if (a.expires<=clock.millis()) {
      a.credential=null;
      if (Set.of("WAITING","RECEIVED").contains(a.state)) a.state="FAILED";
    }
  }
  private synchronized Attempt current(String player) {
    Attempt a=attempts.get(player);
    if (a!=null && a.expires<=clock.millis() && !Set.of("SUBMITTING","UNKNOWN","COMPLETE").contains(a.state)) {
      a.state="FAILED";a.credential=null;
    }
    return a;
  }
  private State state(Attempt a) { return a==null ? new State("FAILED",null) : new State(a.state,a.result); }
  @PostMapping("/start") public synchronized ResponseEntity<Started> start(
      @RequestHeader(value="Authorization",required=false) String bearer,
      @RequestHeader(value="Origin",required=false) String source, @RequestBody StartRequest request) {
    available();origin(source);var who=who(bearer);
    if (!Boolean.TRUE.equals(request.consent()) || !"account-profile-v1".equals(request.profileConsentVersion()))
      throw RoomService.fail(400,"CONSENT_REQUIRED","Current profile consent required");
    Attempt old=current(who.playerId());
    if (old!=null && Set.of("WAITING","RECEIVED","SUBMITTING","UNKNOWN").contains(old.state))
      throw RoomService.fail(409,"REDIRECT_PENDING","Existing attempt must not be replaced");
    // Never evict uncertain write results. Bounded capacity fails closed instead.
    attempts.entrySet().removeIf(e -> e.getValue().expires<clock.millis() && !Set.of("SUBMITTING","UNKNOWN").contains(e.getValue().state));
    if (attempts.size()>=1024 && !attempts.containsKey(who.playerId())) throw RoomService.fail(429,"AUTH_CAPACITY","Sign-in capacity reached");
    var nonce=auth.nonce(who,source,"link");
    byte[] bytes=new byte[32];random.nextBytes(bytes);
    String binding=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    attempts.put(who.playerId(),new Attempt(binding,nonce.nonce(),clock.millis()+300000));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,
        ResponseCookie.from(COOKIE,binding).path("/").httpOnly(true).secure(true).sameSite("None").maxAge(300).build().toString())
        .body(new Started(nonce.nonce(),300,loginUri()));
  }
  // Same-origin GET does not necessarily send Origin. Bearer authentication is mandatory.
  @GetMapping("/status") public synchronized State status(@RequestHeader(value="Authorization",required=false) String bearer) {
    available();return state(current(who(bearer).playerId()));
  }
  @PostMapping("/cancel") public synchronized State cancel(@RequestHeader(value="Authorization",required=false) String bearer,
      @RequestHeader(value="Origin",required=false) String source) {
    available();origin(source);var who=who(bearer);Attempt a=current(who.playerId());
    if (a!=null && Set.of("WAITING","RECEIVED").contains(a.state)) {
      a.state="FAILED";a.credential=null;auth.cancel(who,source,a.nonce);
    }
    return state(a);
  }
  @PostMapping("/finish") public State finish(@RequestHeader(value="Authorization",required=false) String bearer,
      @RequestHeader(value="Origin",required=false) String source) {
    available();origin(source);var who=who(bearer);Attempt a;String credential;
    synchronized(this) {
      a=current(who.playerId());
      if (a==null || !"RECEIVED".equals(a.state)) return state(a);
      a.state="SUBMITTING";credential=a.credential;a.credential=null;
    }
    try {
      // Verifies Google's signature, issuer, audience and session-bound nonce before any storage.
      var result=auth.loginForEntry(who,source,credential,true,"account-profile-v1");
      synchronized(this) { a.result=result;a.state="COMPLETE";return state(a); }
    } catch (RuntimeException failure) {
      boolean rejectedBeforeStorage = failure instanceof RoomService.Failure known
          && PRE_STORAGE_FAILURES.contains(known.code);
      if (rejectedBeforeStorage) {
        // Revoke only this nonce. A newer unrelated challenge must not be cancelled.
        try { auth.cancel(who,source,a.nonce); } catch (RuntimeException ignored) { /* session may have expired */ }
      }
      synchronized(this) { a.credential=null;a.state=rejectedBeforeStorage?"FAILED":"UNKNOWN";return state(a); }
    }
  }
  private static String cookie(HttpServletRequest request,String name) {
    String found=null;int count=0;
    if (request.getCookies()!=null) for (Cookie c:request.getCookies()) if (name.equals(c.getName())) { found=c.getValue();count++; }
    return count==1?found:null;
  }
  private static boolean equal(String a,String b) {
    return a!=null && b!=null && !a.isBlank() && a.length()<=16384 && b.length()<=16384
        && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
  }
  @PostMapping(value="/callback",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public synchronized ResponseEntity<Void> callback(@RequestBody String raw,HttpServletRequest request) {
    available();
    // Parse bounded body ourselves: servlet getParameter can bypass the wrapping input stream.
    if (raw.length()>16384) throw RoomService.fail(413,"REQUEST_TOO_LARGE","Callback too large");
    Map<String,String> form=new HashMap<>();
    try {
      for(String item:raw.split("&")) {
        String[] pair=item.split("=",2);
        String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
        String value=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";
        if(form.putIfAbsent(key,value)!=null) throw new IllegalArgumentException();
      }
    } catch(IllegalArgumentException bad) { throw RoomService.fail(400,"INVALID_CALLBACK","Invalid callback"); }
    if(!equal(cookie(request,"g_csrf_token"),form.get("g_csrf_token"))) throw RoomService.fail(403,"CSRF_REJECTED","Callback verification failed");
    String binding=cookie(request,COOKIE),credential=form.get("credential");
    if(binding==null || credential==null || credential.isBlank() || credential.length()>12000)
      throw RoomService.fail(400,"INVALID_CALLBACK","Callback verification failed");
    Attempt found=null;
    for(var a:attempts.values()) if(equal(a.binding,binding)) { found=a;break; }
    if(found==null || found.expires<=clock.millis() || !"WAITING".equals(found.state))
      throw RoomService.fail(409,"REDIRECT_EXPIRED","Callback already used or expired");
    found.credential=credential;found.state="RECEIVED";
    return ResponseEntity.status(303).location(URI.create(origin+"/?google_return=1"))
        .cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").build();
  }
  @ExceptionHandler(RoomService.Failure.class) public ResponseEntity<?> failure(RoomService.Failure e, HttpServletRequest request) {
    if (request.getRequestURI().equals("/api/auth/google/redirect/callback"))
      return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore())
          .header("Referrer-Policy","no-referrer").header("Content-Security-Policy","default-src 'none'; base-uri 'none'; frame-ancestors 'none'")
          .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
          .body("<!doctype html><html lang=\"zh-Hant\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>登入未完成</title><h1>登入未完成</h1><p>驗證已過期或無法確認。請回到原本的 BridgeGo App 查看登入狀態，勿重複送出。</p><a href=\"/?google_return=failed\">回到 BridgeGo</a></html>");
    return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore()).body(Map.of("code",e.code,"message",e.getMessage()));
  }
}
