package com.bridge.maintenance;

import com.bridge.persistence.OperatorAccountErasure;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

/** Bounded, ephemeral browser session state; credentials are only method-local parameters. */
public final class MaintenanceSessions {
  public static final class Failure extends RuntimeException {
    public final int status; public final String code;
    Failure(int status,String code){super(code);this.status=status;this.code=code;}
  }
  private static final long TTL=15*60_000L;
  private static final class Session {
    final long expires; String nonce; String purpose; OperatorAccountErasure.Preview plan; boolean busy;
    Session(long expires){this.expires=expires;}
  }
  private final Map<String,Session> sessions=new HashMap<>();
  private final SecureRandom random=new SecureRandom();
  private final OperatorAccountErasure engine;
  private final ErasureMaintenanceApplication.MaintenanceSettings settings;
  private final Clock clock;
  public MaintenanceSessions(OperatorAccountErasure engine,ErasureMaintenanceApplication.MaintenanceSettings settings,Clock clock) {
    this.engine=engine;this.settings=settings;this.clock=clock;
  }
  public synchronized String create() {
    sessions.entrySet().removeIf(e->e.getValue().expires<=clock.millis()&&!e.getValue().busy);
    if(sessions.size()>=32)throw new Failure(429,"MAINTENANCE_CAPACITY");
    byte[] bytes=new byte[32];random.nextBytes(bytes);String id=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    sessions.put(id,new Session(clock.millis()+TTL));return id;
  }
  private Session session(String cookie) {
    Session session=cookie==null?null:sessions.get(cookie);
    if(session==null || session.expires<=clock.millis())throw new Failure(401,"MAINTENANCE_SESSION_EXPIRED");
    return session;
  }
  public synchronized OperatorAccountErasure.Challenge challenge(String cookie,String purpose) {
    Session session=session(cookie);
    if(session.busy)throw new Failure(409,"MAINTENANCE_BUSY");
    if(purpose==null || !Set.of("preview","execute").contains(purpose))throw new Failure(400,"INVALID_PURPOSE");
    if(!settings.writerStoppedAcknowledged())throw new Failure(503,"WRITER_SHUTDOWN_REQUIRED");
    if(purpose.equals("execute") && !settings.executionEnabled())throw new Failure(403,"ERASURE_DRY_RUN_ONLY");
    if(purpose.equals("execute") && session.plan==null)throw new Failure(409,"PREVIEW_REQUIRED");
    var challenge=engine.challenge();session.nonce=challenge.nonce();session.purpose=purpose;return challenge;
  }
  private synchronized Session begin(String cookie,String purpose,Boolean consent) {
    Session session=session(cookie);
    if(session.busy)throw new Failure(409,"MAINTENANCE_BUSY");
    if(!Boolean.TRUE.equals(consent)) {session.nonce=null;throw new Failure(400,"CONSENT_REQUIRED");}
    if(session.nonce==null || !purpose.equals(session.purpose))throw new Failure(401,"FRESH_AUTHENTICATION_REQUIRED");
    session.busy=true;return session;
  }
  public OperatorAccountErasure.Preview preview(String cookie,String credential,Boolean consent) {
    Session session=begin(cookie,"preview",consent);String nonce;
    synchronized(this){nonce=session.nonce;session.nonce=null;session.plan=null;}
    try {
      var preview=engine.preview(credential,nonce);
      synchronized(this){session(cookie);session.plan=preview;}
      return preview;
    } finally {synchronized(this){session.busy=false;}}
  }
  public OperatorAccountErasure.Outcome execute(String cookie,String credential,Boolean consent,String planId,String confirmation) {
    if(!settings.executionEnabled())throw new Failure(403,"ERASURE_DRY_RUN_ONLY");
    Session session=begin(cookie,"execute",consent);String nonce;OperatorAccountErasure.Preview plan;
    synchronized(this){nonce=session.nonce;session.nonce=null;plan=session.plan;}
    try {
      if(plan==null || !plan.planId().equals(planId) || !plan.confirmationText().equals(confirmation))
        throw new Failure(400,"PLAN_CONFIRMATION_MISMATCH");
      var outcome=engine.execute(plan.planId(),plan.beforeDigest(),confirmation,credential,nonce);
      // Each explicit attempt needs a new preview. Never automatically retry a destructive request.
      synchronized(this){session.plan=null;}
      return outcome;
    } finally {synchronized(this){session.busy=false;}}
  }
}
