package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Single-operator, single-process, one-run host. No game graph, durable replay, or reset route. */
final class SyntheticCleanupManualQuarterHost implements AutoCloseable {
  record Settings(boolean enabled,String origin,String clientId,String target,String operatorHash) {
    Settings {
      if(enabled){
        SyntheticCleanupWorkbook.requireApprovedTarget(target);
        URI uri=URI.create(origin);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
            ||!uri.getRawPath().isEmpty()||uri.getQuery()!=null||uri.getFragment()!=null
            ||uri.getPort()==0||uri.getPort()>65535||origin.contains(",")||origin.contains("*"))throw fail("CONFIGURATION_REJECTED");
        if(clientId==null||!clientId.matches("[0-9]{6,30}-[A-Za-z0-9_-]{8,128}\\.apps\\.googleusercontent\\.com")
            ||operatorHash==null||!operatorHash.matches("[0-9a-f]{64}"))throw fail("CONFIGURATION_REJECTED");
      }
    }
    static Settings read(Function<String,String> env){
      String value=env.apply("BRIDGE_MANUAL_QUARTER_TRIAL_ENABLED");
      if(value==null||value.equals("false"))return new Settings(false,"","","","");
      if(!value.equals("true"))throw fail("CONFIGURATION_REJECTED");
      return new Settings(true,env.apply("PUBLIC_ORIGIN"),env.apply("GOOGLE_CLIENT_ID"),
          env.apply("BRIDGE_SYNTHETIC_SHEET_ID"),env.apply("BRIDGE_SYNTHETIC_OPERATOR_SHA256"));
    }
    @Override public String toString(){return "Supervised settings (redacted)";}
  }
  record Proof(String nonce,String credential,boolean consent,boolean writersFrozen) {
    @Override public String toString(){return "Operator proof (redacted)";}
  }
  record Challenge(String nonce,String clientId,Instant expiresAt){}
  record Result(String outcome,boolean syntheticQuarterVerified,boolean productionCleanupCertified){}
  interface Factory { SyntheticCleanupManualQuarterTrial open(); }
  static final class Failure extends RuntimeException {
    final String code;
    Failure(String code){super(code);this.code=code;}
  }
  enum Diagnostic { AUTHENTICATION_STARTED, AUTHENTICATION_SUCCESS, AUTHENTICATION_FAILED,
    VERIFIER_REJECTED, VERIFIED_PRINCIPAL_REJECTED, OPERATOR_HASH_MISMATCH, CHALLENGE_EXPIRED }
  private static void log(Diagnostic code){System.err.println("SYNTHETIC_QUARTER_TRIAL "+code.name());}
  private final Consumer<Diagnostic> diagnostics;
  private final Settings settings;
  private final GoogleCredentialVerifier verifier;
  private final Factory factory;
  private final Clock clock;
  private final SecureRandom random=new SecureRandom();
  private final Map<String,Instant> challenges=new HashMap<>();
  private SyntheticCleanupManualQuarterTrial session;
  private boolean attempted,stopped;
  private SyntheticCleanupManualQuarterTrial.View pending;

  SyntheticCleanupManualQuarterHost(Settings settings,GoogleCredentialVerifier verifier,Factory factory,Clock clock){
    this(settings,verifier,factory,clock,SyntheticCleanupManualQuarterHost::log);
  }
  SyntheticCleanupManualQuarterHost(Settings settings,GoogleCredentialVerifier verifier,Factory factory,Clock clock,Consumer<Diagnostic> diagnostics){
    this.settings=Objects.requireNonNull(settings);this.verifier=Objects.requireNonNull(verifier);
    this.factory=Objects.requireNonNull(factory);this.clock=Objects.requireNonNull(clock);
    this.diagnostics=Objects.requireNonNull(diagnostics);
  }
  /** Called only after authenticated explicit start. Initial scaffold read uses the fixed route.
   * No keys are loaded at startup or supplied through browser fields. */
  static Factory realFactory(Settings settings,Supplier<String> credentials,Clock clock){
    return ()->{
      if(!settings.enabled())throw fail("NOT_CONFIGURED");
      byte[] key=null;
      try{
        String supplied=credentials.get();
        if(supplied==null||supplied.length()>20000)throw fail("START_UNVERIFIED");
        key=supplied.getBytes(StandardCharsets.UTF_8);
        return SyntheticCleanupManualQuarterTrial.open(SyntheticCleanupManualQuarterTrial.defaultFixture(settings.target()),key,clock,()->true);
      }catch(RuntimeException | LinkageError invalid){throw fail("START_UNVERIFIED");}
      finally{if(key!=null)Arrays.fill(key,(byte)0);}
    };
  }

  synchronized Challenge challenge(){
    enabled();if(stopped)throw fail("RUN_STOPPED");
    challenges.entrySet().removeIf(e->!e.getValue().isAfter(clock.instant()));
    if(challenges.size()>=8)throw fail("CHALLENGE_LIMIT");
    byte[] nonce=new byte[32];random.nextBytes(nonce);
    String n=Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);Instant expiry=clock.instant().plusSeconds(120);
    challenges.put(n,expiry);return new Challenge(n,settings.clientId(),expiry);
  }
  synchronized SyntheticCleanupManualQuarterTrial.View preview(Proof proof){
    authenticate(proof);
    if(session==null){
      if(attempted)throw fail("RUN_ALREADY_STARTED");attempted=true;
      try{session=factory.open();}catch(RuntimeException | LinkageError invalid){close();throw fail("START_UNVERIFIED");}
    }
    try{pending=session.preview();return pending;}
    catch(RuntimeException | LinkageError invalid){close();throw fail("PREVIEW_UNVERIFIED");}
  }
  synchronized Result confirm(Proof proof,String planId,boolean confirmed){
    authenticate(proof);
    if(session==null||pending==null)throw fail("PREVIEW_NOT_AVAILABLE");
    if(!confirmed||!pending.planId().equals(planId))return new Result("REJECTED_NO_WRITE",false,false);
    // Expiry before any core execution is a refreshable read-only state, not an uncertain write.
    if(!pending.expiresAt().isAfter(clock.instant())){pending=null;return new Result("PREVIEW_EXPIRED_NO_WRITE",false,false);}
    try{
      var outcome=session.confirm(planId,true);pending=null;
      if(!outcome.equals("PREVIEW_EXPIRED_NO_WRITE")&&!outcome.equals("REJECTED_NO_WRITE"))close();
      return new Result(outcome,outcome.equals("SYNTHETIC_QUARTER_VERIFIED"),false);
    }catch(RuntimeException | LinkageError invalid){close();return new Result("STOPPED_RESULT_REQUIRES_REVIEW",false,false);}
  }
  private void authenticate(Proof p){
    enabled();if(stopped)throw fail("RUN_STOPPED");
    Instant expiry=p==null?null:challenges.remove(p.nonce());
    if(expiry==null||!expiry.isAfter(clock.instant())){diagnostics.accept(Diagnostic.CHALLENGE_EXPIRED);throw fail("FRESH_AUTHENTICATION_REQUIRED");}
    if(!p.consent()||!p.writersFrozen()||p.credential()==null||p.credential().isBlank()||p.credential().length()>12000)
      throw fail("EXPLICIT_CONSENT_AND_FREEZE_REQUIRED");
    diagnostics.accept(Diagnostic.AUTHENTICATION_STARTED);
    Diagnostic rejectedAt=Diagnostic.VERIFIER_REJECTED;
    try{
      var principal=verifier.verify(p.credential(),settings.clientId(),p.nonce());
      rejectedAt=Diagnostic.VERIFIED_PRINCIPAL_REJECTED;
      if(principal==null||!"https://accounts.google.com".equals(principal.issuer())||principal.subject()==null
          ||principal.subject().isBlank()||principal.subject().startsWith("SYNTHETIC-ONLY"))throw fail("OPERATOR_NOT_ALLOWED");
      rejectedAt=Diagnostic.OPERATOR_HASH_MISMATCH;
      String hash=SyntheticCleanupWorkbook.sha256(principal.issuer()+"\n"+principal.subject());
      if(!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),settings.operatorHash().getBytes(StandardCharsets.US_ASCII)))
        throw fail("OPERATOR_NOT_ALLOWED");
      rejectedAt=Diagnostic.CHALLENGE_EXPIRED;
      if(!expiry.isAfter(clock.instant()))throw fail("FRESH_AUTHENTICATION_REQUIRED");
      diagnostics.accept(Diagnostic.AUTHENTICATION_SUCCESS);
    }catch(RuntimeException | LinkageError invalid){diagnostics.accept(rejectedAt);diagnostics.accept(Diagnostic.AUTHENTICATION_FAILED);throw fail("OPERATOR_AUTHENTICATION_FAILED");}
  }
  private void enabled(){if(!settings.enabled())throw fail("NOT_CONFIGURED");}
  @Override public synchronized void close(){stopped=true;pending=null;challenges.clear();if(session!=null)session.close();}
  private static Failure fail(String code){return new Failure(code);}
}
