package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Single-operator, single-process, one-run host. No game graph, durable replay, or reset route. */
final class SyntheticCleanupSupervisedHost implements AutoCloseable {
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
      String value=env.apply("BRIDGE_SYNTHETIC_REHEARSAL_ENABLED");
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
  record View(String phase,String purpose,List<SyntheticCleanupSupervisedSession.TableImpact> tables,
      List<SyntheticCleanupSupervisedSession.CellChange> changes,String confirmation,String planId,
      Instant expiresAt,String targetHash,String scopeWarning){}
  record Result(String outcome,int verifiedPhases,boolean syntheticFunctionalRunVerified,boolean productionCleanupCertified){}
  interface Factory { SyntheticCleanupSupervisedSession open(); }
  static final class Failure extends RuntimeException {
    final String code;
    Failure(String code){super(code);this.code=code;}
  }
  private final Settings settings;
  private final GoogleCredentialVerifier verifier;
  private final Factory factory;
  private final Clock clock;
  private final SecureRandom random=new SecureRandom();
  private final Map<String,Instant> challenges=new HashMap<>();
  private SyntheticCleanupSupervisedSession session;
  private boolean attempted,stopped,pending;
  private String pendingPlan,pendingConfirmation;
  private int verifiedPhases;

  SyntheticCleanupSupervisedHost(Settings settings,GoogleCredentialVerifier verifier,Factory factory,Clock clock){
    this.settings=Objects.requireNonNull(settings);this.verifier=Objects.requireNonNull(verifier);
    this.factory=Objects.requireNonNull(factory);this.clock=Objects.requireNonNull(clock);
  }
  /** Called only after authenticated explicit start. Initial scaffold read uses the fixed route.
   * No keys are loaded at startup or supplied through browser fields. */
  static Factory realFactory(Settings settings,Supplier<String> credentials,Clock clock){
    return ()->{
      if(!settings.enabled())throw fail("NOT_CONFIGURED");
      byte[] key=null,scaffold=null;LiveQuarterStorage.Transport http=null;
      try{
        String supplied=credentials.get();
        if(supplied==null||supplied.length()>20000)throw fail("START_UNVERIFIED");
        key=supplied.getBytes(StandardCharsets.UTF_8);
        http=LiveQuarterHttp.open(new ManualQuarterMaintenance.Binding(settings.target(),"00000000-0000-4000-8000-000000000001"),key);
        scaffold=http.readWorkbook();
        var wire=new SyntheticCleanupHttpWire(settings.target(),scaffold,http,clock);
        var result=new SyntheticCleanupSupervisedSession(settings.target(),scaffold,wire,clock,()->true);
        http=null;return result;
      }catch(RuntimeException | LinkageError invalid){throw fail("START_UNVERIFIED");}
      finally{
        if(key!=null)Arrays.fill(key,(byte)0);if(scaffold!=null)Arrays.fill(scaffold,(byte)0);
        if(http!=null)try{http.close();}catch(Exception ignored){}
      }
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
  synchronized View start(Proof proof){
    authenticate(proof);if(attempted||session!=null)throw fail("RUN_ALREADY_STARTED");
    attempted=true;
    try{session=factory.open();return preview();}
    catch(RuntimeException | LinkageError invalid){close();throw fail("START_UNVERIFIED");}
  }
  synchronized View next(Proof proof){
    authenticate(proof);if(session==null||pending)throw fail("PREVIEW_NOT_AVAILABLE");
    return preview();
  }
  private View preview(){
    try{
      var p=session.preview();pending=true;pendingPlan=p.confirmation().planId();pendingConfirmation=p.confirmation().confirmation();
      var changes=p.changes().stream().map(c->new SyntheticCleanupSupervisedSession.CellChange(c.table(),c.row(),c.column(),
          redact(c.before()),redact(c.after()))).toList();
      return new View(p.confirmation().phase().name(),p.purpose(),p.tables(),changes,
          p.confirmation().confirmation(),p.confirmation().planId(),p.confirmation().expiresAt(),p.confirmation().targetHash(),
          "僅修改指定隔離假表；每階段單批次新增防重放標記並更新八張表 A1:E32，重設該範圍值與格式。人工停止其他寫入，這不是分散式鎖。版本歷史與備份不會清除。每步須另行確認，未知結果不得重試。");
    }catch(RuntimeException | LinkageError invalid){close();throw fail("PREVIEW_UNVERIFIED");}
  }
  synchronized Result confirm(Proof proof,String planId,String exactConfirmation){
    authenticate(proof);if(session==null||!pending)throw fail("PREVIEW_NOT_AVAILABLE");
    if(!pendingPlan.equals(planId)||!pendingConfirmation.equals(exactConfirmation))return new Result("REJECTED",verifiedPhases,false,false);
    try{
      var r=session.confirm(planId,exactConfirmation);
      pending=false;verifiedPhases=r.verifiedPhases();
      if(!r.outcome().equals("VERIFIED")||r.syntheticFunctionalRunVerified())close();
      return new Result(r.outcome(),r.verifiedPhases(),r.syntheticFunctionalRunVerified(),false);
    }catch(RuntimeException | LinkageError invalid){close();return new Result("STOPPED_UNCERTAIN",verifiedPhases,false,false);}
  }
  private String redact(String value){return value.replace(settings.target(),"[指定隔離假表]");}
  private void authenticate(Proof p){
    enabled();if(stopped)throw fail("RUN_STOPPED");
    Instant expiry=p==null?null:challenges.remove(p.nonce());
    if(expiry==null||!expiry.isAfter(clock.instant()))throw fail("FRESH_AUTHENTICATION_REQUIRED");
    if(!p.consent()||!p.writersFrozen()||p.credential()==null||p.credential().isBlank()||p.credential().length()>12000)
      throw fail("EXPLICIT_CONSENT_AND_FREEZE_REQUIRED");
    try{
      var principal=verifier.verify(p.credential(),settings.clientId(),p.nonce());
      if(principal==null||!"https://accounts.google.com".equals(principal.issuer())||principal.subject()==null
          ||principal.subject().isBlank()||principal.subject().startsWith("SYNTHETIC-ONLY"))throw fail("OPERATOR_NOT_ALLOWED");
      String hash=SyntheticCleanupWorkbook.sha256(principal.issuer()+"\n"+principal.subject());
      if(!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),settings.operatorHash().getBytes(StandardCharsets.US_ASCII)))
        throw fail("OPERATOR_NOT_ALLOWED");
      if(!expiry.isAfter(clock.instant()))throw fail("FRESH_AUTHENTICATION_REQUIRED");
    }catch(RuntimeException | LinkageError invalid){throw fail("OPERATOR_AUTHENTICATION_FAILED");}
  }
  private void enabled(){if(!settings.enabled())throw fail("NOT_CONFIGURED");}
  @Override public synchronized void close(){stopped=true;pending=false;challenges.clear();if(session!=null)session.close();}
  private static Failure fail(String code){return new Failure(code);}
}
