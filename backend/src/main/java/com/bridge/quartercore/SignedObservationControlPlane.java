package com.bridge.quartercore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.Rejected;

/** Rebuilt fixed-key, one-use trusted-controller relay. The signature authenticates the
 * installed controller, NOT Render itself. That controller must independently read Render.
 * This is only deployment evidence, never operator consent or a spreadsheet capability. */
final class SignedObservationControlPlane implements AutoCloseable {
  static final String PROVENANCE="trusted-controller-relay-not-render-signed-v1";
  record Context(String run,String target,String workspace,String service,String deployment,String revision,Set<String> legacyDeployments) {
    Context {for(String s:List.of(run,target,workspace,service,deployment,revision))if(s==null||s.isBlank()||s.length()>256)throw new Rejected();
      if(!target.matches("[a-f0-9]{64}"))throw new Rejected();legacyDeployments=Set.copyOf(legacyDeployments);
      if(legacyDeployments.isEmpty()||legacyDeployments.size()>32||legacyDeployments.contains(deployment))throw new Rejected();}
  }
  record Challenge(int version,String provenance,String run,String challenge,String target,String workspace,String service,String deployment,String revision,Instant expires) {}
  record Verified(Instant observedAt,String workspace,String service,String deployment,String revision,int services,int instances,boolean autoDeploy,boolean previews) {}
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(65536).maxNestingDepth(5).maxStringLength(512).maxNumberLength(20).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final PublicKey key;private final Context context;private final Clock clock;private final Challenge challenge;private final long deadline;
  private Instant last;private boolean consumed,closed;private Verified verified;
  SignedObservationControlPlane(PublicKey key,Context context,Clock clock){
    this.key=Objects.requireNonNull(key);this.context=Objects.requireNonNull(context);this.clock=Objects.requireNonNull(clock);
    if(!Set.of("Ed25519","EdDSA").contains(key.getAlgorithm()))throw new Rejected();byte[] random=new byte[32];new SecureRandom().nextBytes(random);
    last=clock.instant();deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();challenge=new Challenge(1,PROVENANCE,context.run(),HexFormat.of().formatHex(random),context.target(),context.workspace(),context.service(),context.deployment(),context.revision(),last.plusSeconds(60));
  }
  private void live(){Instant now=clock.instant();if(closed||now.isBefore(last)||!now.isBefore(challenge.expires())||System.nanoTime()>=deadline){closed=true;throw new Rejected();}last=now;}
  synchronized Challenge challenge(){live();if(consumed)throw new Rejected();return challenge;}
  /** Invalid unsigned traffic does not consume a legitimate pending observation. A valid
   * signature is consumed before context/schema validation; any mismatch closes the cycle. */
  synchronized void submit(byte[] payload,byte[] signature){
    live();if(consumed||payload==null||payload.length>65536||signature==null||signature.length!=64)throw new Rejected();
    try{Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(payload);if(!verifier.verify(signature))throw new Rejected();}
    catch(GeneralSecurityException bad){throw new Rejected();}
    consumed=true;
    try{
      JsonNode n=JSON.readTree(payload);if(n==null||!n.isObject())throw new Rejected();var fields=new HashSet<String>();n.fieldNames().forEachRemaining(fields::add);
      if(!fields.equals(Set.of("version","provenance","run","challenge","target","workspace","service","deployment","revision","capturedAt","services","instances","autoDeploy","previews","deployments")))throw new Rejected();
      if(!n.path("version").isInt()||n.path("version").intValue()!=1)throw new Rejected();
      var fixed=Map.of("provenance",PROVENANCE,"run",context.run(),"challenge",challenge.challenge(),"target",context.target(),"workspace",context.workspace(),"service",context.service(),"deployment",context.deployment(),"revision",context.revision());
      for(var f:fixed.entrySet())if(!n.path(f.getKey()).isTextual()||!f.getValue().equals(n.path(f.getKey()).textValue()))throw new Rejected();
      if(!n.path("services").isInt()||n.path("services").intValue()!=1||!n.path("instances").isInt()||n.path("instances").intValue()!=1||!n.path("autoDeploy").isBoolean()||n.path("autoDeploy").booleanValue()||!n.path("previews").isBoolean()||n.path("previews").booleanValue())throw new Rejected();
      Instant observed=Instant.parse(n.path("capturedAt").textValue());live();if(observed.isAfter(last)||!last.isBefore(observed.plusSeconds(60)))throw new Rejected();
      JsonNode rows=n.path("deployments");if(!rows.isArray()||rows.isEmpty()||rows.size()>100)throw new Rejected();Set<String> seen=new HashSet<>(),inactive=new HashSet<>();int live=0;
      for(JsonNode row:rows){var names=new HashSet<String>();row.fieldNames().forEachRemaining(names::add);if(!names.equals(Set.of("id","revision","state")))throw new Rejected();
        for(String field:names)if(!row.path(field).isTextual()||row.path(field).asText().isBlank()||row.path(field).asText().length()>256)throw new Rejected();
        String id=row.path("id").textValue(),state=row.path("state").textValue();if(!seen.add(id))throw new Rejected();
        if(id.equals(context.deployment())){if(!state.equals("live")||!context.revision().equals(row.path("revision").textValue()))throw new Rejected();live++;}
        else if(state.equals("deactivated"))inactive.add(id);else if(!Set.of("failed","canceled").contains(state))throw new Rejected();
      }
      if(live!=1||!inactive.containsAll(context.legacyDeployments()))throw new Rejected();
      // Bind precisely one canonical document; no alternate encodings or hidden fields.
      String canonical=NativeCommitJournal.canonical(JSON.convertValue(n,Object.class));
      if(!Arrays.equals(payload,canonical.getBytes(StandardCharsets.UTF_8)))throw new Rejected();
      verified=new Verified(observed,context.workspace(),context.service(),context.deployment(),context.revision(),1,1,false,false);notifyAll();
    }catch(Exception bad){closed=true;notifyAll();throw new Rejected();}
  }
  synchronized Verified await(){
    while(verified==null){live();long left=deadline-System.nanoTime();if(left<=0)throw new Rejected();try{wait(Math.max(1,Math.min(1000,left/1_000_000)));}catch(InterruptedException stop){Thread.currentThread().interrupt();closed=true;throw new Rejected();}}
    live();return verified;
  }
  @Override public synchronized void close(){closed=true;notifyAll();}
}
