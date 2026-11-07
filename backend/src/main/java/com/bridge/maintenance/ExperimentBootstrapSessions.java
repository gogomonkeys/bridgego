package com.bridge.maintenance;

import com.bridge.room.GoogleCredentialVerifier;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;

/** Explicit one-use initialization only. No identity records are written by this browser service. */
public final class ExperimentBootstrapSessions {
  public interface Storage {
    String previewEmptyDigest();
    String initializeEmpty(String digest, boolean writersStopped, boolean authorized);
  }
  public record Settings(String clientId, String sheet, String dataset, GoogleCredentialVerifier.Principal tester,
      Instant expiry, boolean writersStopped, boolean executionEnabled) {}
  public record Challenge(String nonce, int expiresInSeconds) {}
  public record Preview(String planId, String beforeDigest, String confirmationText, Instant expiresAt,
      String target, String dataset) {}
  public static final class Failure extends RuntimeException {
    public final int status; public final String code;
    Failure(int status,String code){super(code);this.status=status;this.code=code;}
  }
  private static final class Session {
    Instant expiry,nonceExpiry; String nonce,purpose; boolean busy; Preview plan;
    Session(Instant expiry){this.expiry=expiry;}
  }
  private final Map<String,Session> sessions=new HashMap<>();
  private final SecureRandom random=new SecureRandom();
  private final Storage storage; private final GoogleCredentialVerifier verifier; private final Settings settings; private final Clock clock;
  // Attempt is process-wide, not browser-session-local. Durable bootstrap guard handles restarts/races.
  private boolean attempted;
  public ExperimentBootstrapSessions(Storage storage,GoogleCredentialVerifier verifier,Settings settings,Clock clock){
    this.storage=Objects.requireNonNull(storage);this.verifier=Objects.requireNonNull(verifier);
    this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);
  }
  private String random(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
  private void active(){if(!clock.instant().isBefore(settings.expiry()))throw new Failure(403,"EXPERIMENT_EXPIRED");if(!settings.writersStopped())throw new Failure(503,"WRITER_SHUTDOWN_REQUIRED");}
  public synchronized String create(){active();sessions.entrySet().removeIf(e->!e.getValue().busy&&!clock.instant().isBefore(e.getValue().expiry));
    if(sessions.size()>=32)throw new Failure(429,"MAINTENANCE_CAPACITY");String id=random();sessions.put(id,new Session(clock.instant().plusSeconds(900)));return id;}
  private Session session(String cookie){Session s=cookie==null?null:sessions.get(cookie);if(s==null||!clock.instant().isBefore(s.expiry))throw new Failure(401,"MAINTENANCE_SESSION_EXPIRED");return s;}
  public synchronized Challenge challenge(String cookie,String purpose){active();Session s=session(cookie);if(s.busy)throw new Failure(409,"MAINTENANCE_BUSY");
    if(!Set.of("preview","execute").contains(purpose==null?"":purpose))throw new Failure(400,"INVALID_PURPOSE");
    if(purpose.equals("execute")&&(!settings.executionEnabled()||attempted||s.plan==null))throw new Failure(403,"INITIALIZATION_UNAVAILABLE");
    s.nonce=random();s.nonceExpiry=clock.instant().plusSeconds(300);s.purpose=purpose;return new Challenge(s.nonce,300);}
  private synchronized Session begin(String cookie,String purpose,Boolean consent){active();Session s=session(cookie);
    if(s.busy)throw new Failure(409,"MAINTENANCE_BUSY");
    if(!Boolean.TRUE.equals(consent)||s.nonce==null||!purpose.equals(s.purpose)||!clock.instant().isBefore(s.nonceExpiry)){
      s.nonce=null;throw new Failure(401,"FRESH_CONSENT_AND_AUTHENTICATION_REQUIRED");}
    s.busy=true;return s;}
  private void verify(String credential,String nonce){var actual=verifier.verify(credential,settings.clientId(),nonce);if(!settings.tester().equals(actual))throw new Failure(403,"TESTER_NOT_ADMITTED");}
  public Preview preview(String cookie,String credential,Boolean consent){Session s=begin(cookie,"preview",consent);String nonce;
    synchronized(this){nonce=s.nonce;s.nonce=null;s.plan=null;}
    try{verify(credential,nonce);synchronized(this){active();session(cookie);if(!clock.instant().isBefore(s.nonceExpiry))throw new Failure(401,"FRESH_CONSENT_AND_AUTHENTICATION_REQUIRED");}String digest=storage.previewEmptyDigest();
      synchronized(this){active();session(cookie);if(!clock.instant().isBefore(s.nonceExpiry))throw new Failure(401,"FRESH_CONSENT_AND_AUTHENTICATION_REQUIRED");String id=random();Instant expires=clock.instant().plusSeconds(300);if(expires.isAfter(settings.expiry()))expires=settings.expiry();
        Preview p=new Preview(id,digest,"INITIALIZE EMPTY EXPERIMENT SHEET "+settings.sheet()+" DATASET "+settings.dataset()+" PLAN "+id+" BEFORE "+digest,expires,settings.sheet(),settings.dataset());s.plan=p;return p;}}
    finally{synchronized(this){s.busy=false;}}}
  public String execute(String cookie,String credential,Boolean consent,String planId,String confirmation){Session s=begin(cookie,"execute",consent);String nonce;Preview plan;
    synchronized(this){nonce=s.nonce;s.nonce=null;plan=s.plan;s.plan=null;}
    try{verify(credential,nonce);synchronized(this){active();session(cookie);if(!clock.instant().isBefore(s.nonceExpiry))throw new Failure(401,"FRESH_CONSENT_AND_AUTHENTICATION_REQUIRED");
      if(!settings.executionEnabled()||attempted||plan==null||!plan.planId().equals(planId)||!plan.confirmationText().equals(confirmation)||!clock.instant().isBefore(plan.expiresAt()))throw new Failure(409,"INITIALIZATION_UNAVAILABLE");
      attempted=true;}
      try{return storage.initializeEmpty(plan.beforeDigest(),true,true);}catch(RuntimeException uncertain){return "UNCERTAIN";}}
    finally{synchronized(this){s.busy=false;}}}
}
