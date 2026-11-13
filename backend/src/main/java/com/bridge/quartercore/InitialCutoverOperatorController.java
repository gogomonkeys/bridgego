package com.bridge.quartercore;
import java.time.*;import java.security.SecureRandom;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.Rejected;
/** Transport-neutral, fixed-source operator workflow. No Spring registration or default authority. */
final class InitialCutoverOperatorController implements AutoCloseable {
 enum Stage { METADATA,HISTORY,ACTIVATE }
 static final class Proposal {
  private final Object opaque;private final Map<String,Object> details;
  Proposal(Object opaque,Map<String,Object> details){this.opaque=Objects.requireNonNull(opaque);this.details=Map.copyOf(details);}
  Object opaque(){return opaque;}Map<String,Object> details(){return details;}
  @Override public String toString(){return "Proposal[REDACTED]";}
 }
 record Outcome(String code,String afterToken){}
 interface Operations {
  String verifyOperator(String credential,String nonce);
  Map<String,Object> prepare(String operator,String deploymentHint,Runnable liveScope);
  Proposal preview(Stage stage,String operator,Set<String> stoppedSources,boolean noUnknown,Runnable liveScope);
  Outcome execute(Stage stage,String operator,Proposal exactPreview,Runnable liveScope);
  /** A verified write remains verified even if a later in-process handoff fails. No proof from HTTP. */
  default Outcome lastVerified(){return null;}
  default void checkpointRead(Stage next,String operator,String exactToken,Runnable readScope){throw new Rejected();}
 }
 record Session(String cookie,Instant expires){@Override public String toString(){return "OperatorSession[REDACTED]";}}
 record Challenge(String nonce,Instant expires){}
 record Preview(String digest,Map<String,Object> details,Instant expires){}
 private final Operations operations;private final Clock clock;private final String origin,clientId;private final Set<String> sources;
 private String cookie,nonce,purpose,operator;private Instant expires,last;private long deadline;private boolean created,busy,failed,closed,prepared;
 private boolean operationEntered;private int restarts,checkpoints;
 private Instant locatorExpires,checkpointExpires;private long locatorDeadline,checkpointDeadline;
 private PrepareDiagnosticFailure.Stage prepareFailure;
 private int completed;private Proposal proposal;private Stage proposalStage;private String digest;private Outcome lastVerified;
 InitialCutoverOperatorController(String origin,String clientId,Set<String> sources,Operations operations,Clock clock){
  if(origin==null||!origin.matches("https://[^/\\s?#]+")||clientId==null||clientId.isBlank())throw new Rejected();
  this.origin=origin;this.clientId=clientId;this.sources=Set.copyOf(sources);this.operations=Objects.requireNonNull(operations);this.clock=Objects.requireNonNull(clock);
 }
 String origin(){return origin;}Map<String,Object> config(){return Map.of("clientId",clientId,"manualSources",sources);}
 private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
 synchronized Session session(){if(created||closed||failed)throw new Rejected();created=true;cookie=random();last=clock.instant();locatorExpires=last.plusSeconds(1800);locatorDeadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();expires=last.plusSeconds(300);deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();return new Session(cookie,expires);}
 /** Only the abandoned pre-prepare authentication screen may get a new session.
  * Server-side irreversible latch, never a client assertion that no write happened. */
 synchronized Session restartBeforePrepare(String supplied){
  Instant now=clock.instant();
  if(last!=null&&now.isBefore(last)){failed=true;throw new Rejected();}
  locator(supplied);now=clock.instant();
  if(!created||closed||failed||busy||operationEntered||prepared||completed!=0||proposal!=null||lastVerified!=null
      ||!Objects.equals(cookie,supplied)||now.isBefore(last)||restarts>=3)throw new Rejected();
  restarts++;cookie=random();nonce=null;purpose=null;operator=null;last=now;expires=boundedScope(now);
  deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();return new Session(cookie,expires);
 }
 private Instant boundedScope(Instant now){Instant end=now.plusSeconds(300);return end.isBefore(locatorExpires)?end:locatorExpires;}
 synchronized long cookieSeconds(){return Math.max(0,Duration.between(clock.instant(),locatorExpires).toSeconds());}
 private void locator(String supplied){Instant now=clock.instant();if(last!=null&&now.isBefore(last))failed=true;if(!created||closed||failed||!Objects.equals(cookie,supplied)||!now.isBefore(locatorExpires)||System.nanoTime()>=locatorDeadline)throw new Rejected();last=now;}
 private void checkpointEligible(){if(!prepared||completed<1||completed>2||checkpoints!=completed-1||proposal!=null||lastVerified==null||!lastVerified.code().equals(Stage.values()[completed-1].name()+"_VERIFIED"))throw new Rejected();}
 private void checkpointLive(String supplied){locator(supplied);if(checkpointExpires==null||!clock.instant().isBefore(checkpointExpires)||System.nanoTime()>=checkpointDeadline)throw new Rejected();}
 Session checkpoint(String supplied,String credential){String n; synchronized(this){checkpointLive(supplied);checkpointEligible();if(busy||nonce==null||!"checkpoint".equals(purpose))throw new Rejected();busy=true;n=nonce;nonce=null;purpose=null;}
  try{String who=operations.verifyOperator(credential,n);String token;Stage next;synchronized(this){checkpointLive(supplied);if(!Objects.equals(who,operator))throw new Rejected();token=lastVerified.afterToken();next=Stage.values()[completed];}
   operations.checkpointRead(next,who,token,()->{synchronized(this){checkpointLive(supplied);}});
   synchronized(this){checkpointLive(supplied);checkpointEligible();checkpoints++;cookie=random();expires=boundedScope(clock.instant());deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();checkpointExpires=null;return new Session(cookie,expires);}
  }catch(RuntimeException|Error e){stopped();throw e;}finally{end();}
 }
 private void live(String supplied){locator(supplied);Instant now=clock.instant();if(!created||closed||failed||!Objects.equals(cookie,supplied)||now.isBefore(last)||!now.isBefore(expires)||System.nanoTime()>=deadline)throw new Rejected();last=now;}
 private void stage(Stage stage){if(!prepared||completed!=stage.ordinal())throw new Rejected();}
 synchronized Challenge challenge(String supplied,String requested){
  if("checkpoint".equals(requested)){locator(supplied);checkpointEligible();if(busy||nonce!=null)throw new Rejected();nonce=random();purpose=requested;checkpointExpires=clock.instant().plusSeconds(120);checkpointDeadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();return new Challenge(nonce,checkpointExpires.isBefore(locatorExpires)?checkpointExpires:locatorExpires);}
  live(supplied);if(busy||nonce!=null||!Set.of("prepare","metadata-preview","metadata-execute","history-preview","history-execute","activate-preview","activate-execute").contains(requested))throw new Rejected();
  if(requested.equals("prepare")){if(prepared)throw new Rejected();}else{Stage s=Stage.valueOf(requested.split("-")[0].toUpperCase(Locale.ROOT));stage(s);if(requested.endsWith("execute")&&(proposal==null||proposalStage!=s))throw new Rejected();}
  nonce=random();purpose=requested;return new Challenge(nonce,expires);
 }
 private synchronized String begin(String supplied,String expected){live(supplied);if(busy||nonce==null||!expected.equals(purpose))throw new Rejected();busy=true;operationEntered=true;String n=nonce;nonce=null;purpose=null;return n;}
 private String authenticate(String supplied,String credential,String n){String who=operations.verifyOperator(credential,n);synchronized(this){live(supplied);if(who==null||who.isBlank()||operator!=null&&!operator.equals(who))throw new Rejected();operator=who;}return who;}
 private Runnable guard(String supplied){return ()->{synchronized(this){live(supplied);}};}
 private synchronized void end(){busy=false;}
 private void stopped(){Outcome verified=null;try{verified=operations.lastVerified();}catch(RuntimeException ignored){}synchronized(this){if(verified!=null)lastVerified=verified;failed=true;}}
 Map<String,Object> prepare(String supplied,String credential,String deployment){String n=begin(supplied,"prepare");try{
  String who;try{who=authenticate(supplied,credential,n);}catch(RuntimeException bad){throw new PrepareDiagnosticFailure(PrepareDiagnosticFailure.Stage.OPERATOR_AUTH);}Map<String,Object> details=operations.prepare(who,deployment,guard(supplied));synchronized(this){live(supplied);prepared=true;}return Map.of("code","READ_ONLY_PREPARED","details",Map.copyOf(details));
 }catch(RuntimeException|Error e){synchronized(this){prepareFailure=e instanceof PrepareDiagnosticFailure d?d.stage:PrepareDiagnosticFailure.Stage.PREPARE_GUARD;}stopped();throw e;}finally{end();}}
 Preview preview(Stage stage,String supplied,String credential,Set<String> stoppedSources,boolean noUnknown){String n=begin(supplied,stage.name().toLowerCase(Locale.ROOT)+"-preview");try{
  String who=authenticate(supplied,credential,n);if(!sources.equals(stoppedSources)||!noUnknown)throw new Rejected();
  synchronized(this){stage(stage);if(proposal!=null)throw new Rejected();}
  Proposal p=operations.preview(stage,who,stoppedSources,noUnknown,guard(supplied));
  synchronized(this){live(supplied);proposal=p;proposalStage=stage;digest=FinalMatchEnvelope.hash(NativeCommitJournal.canonical(List.of("operator-preview-v1",stage.name(),p.details(),who,cookie,expires.toString())));return new Preview(digest,p.details(),expires);}
 }catch(RuntimeException|Error e){stopped();throw e;}finally{end();}}
 Outcome execute(Stage stage,String supplied,String credential,String exactDigest,boolean approve){String n=begin(supplied,stage.name().toLowerCase(Locale.ROOT)+"-execute");try{
  String who=authenticate(supplied,credential,n);Proposal p;
  synchronized(this){stage(stage);if(!approve||proposal==null||proposalStage!=stage||!Objects.equals(digest,exactDigest))throw new Rejected();p=proposal;proposal=null;}
  Outcome result=Objects.requireNonNull(operations.execute(stage,who,p,guard(supplied)));
  // Never erase successful external writes merely because a short operator scope ended during readback.
  synchronized(this){lastVerified=result;if(!result.code().equals(stage.name()+"_VERIFIED")){failed=true;return result;}completed++;digest=null;proposalStage=null;return result;}
 }catch(RuntimeException|Error e){stopped();throw e;}finally{end();}}
 synchronized Map<String,Object> stoppedStatus(){if(prepareFailure!=null)return Map.of("code","STOPPED_OR_UNCONFIRMED","prepareStage",prepareFailure.name());return lastVerified==null?Map.of("code","STOPPED_OR_UNCONFIRMED"):Map.of("code","STOPPED_OR_UNCONFIRMED","lastVerifiedCode",lastVerified.code(),"lastVerifiedAfterToken",lastVerified.afterToken());}
 @Override public synchronized void close(){closed=true;nonce=null;purpose=null;}
}
