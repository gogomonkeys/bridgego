package com.bridge.quartercore;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Test source only. No Spring component, flag, endpoint, arm writer, reset, or persistent nonce.
 * The caller must supply the actual complete native parser result, never a projected Book.
 * A native parse failure must stop the caller; rebuilding a Book cannot attest native coverage.
 */
final class BootArm {
  enum Stage { A, B }
  enum Status { READ_ONLY_UNARMED, CONSUMED, BLOCKED }
  static final int ARM_A_ID=10001, ARM_B_ID=10002, MAX_ARM_CHARS=1024;
  static final String ARM_A_KEY="test.arm.A", ARM_B_KEY="test.arm.B";
  private static final Set<String> FIELDS=Set.of("version","run","stage","spreadsheet","dataset","purpose",
      "bootNonce","rootSha256","businessSha256","planSha256","issuedAt","expiresAt");
  // Operator-verified excluded targets from frozen config and preserved UNKNOWN fixtures.
  // This test adapter cannot write them even if a caller fabricates an otherwise matching Book.
  private static final Set<String> FORBIDDEN_TARGETS=Set.of(
      "1jGIsjBfJYm5DnL2EIrW4m1o4YM-5f0I9w2bpMzhMVaU",
      "1fueMlf5u-JqeUMgeo3X_Bpjbbg2alN7SaISq7FL8rac",
      "1viNnpXYkbKoI5fesJEbaVF46D-6D71UIgjaGpTBjMk4",
      "1GrUtidlZVAaEbD9MHEeXAvjCnkseAcHD2iin1mUjZO0",
      "1_AqJ_-jXFNSzUZhsTylnXqTmIbtL-PHA1VDd23tV5p0",
      "1Bss2GLYazdFczRwHFnx700sibaW6JAtpfVpKmbdUJ_I",
      "1IcS-E08MvJ9owehoh_RgMesjlJk2kvLsVBplJwtSHcg");
  private static final BootArm PROCESS=new BootArm();
  private final String nonce;
  private final long bornNanos=System.nanoTime();
  private final Instant bornAt=Instant.now();
  private Status status=Status.READ_ONLY_UNARMED;
  private Plan pinned;
  private int reads;
  private boolean permitIssued;
  private BootArm(){byte[] random=new byte[32];new SecureRandom().nextBytes(random);nonce=HexFormat.of().formatHex(random);}
  static BootArm process(){return PROCESS;}
  String nonce(){return nonce;}
  synchronized Status status(){return status;}

  /** Immutable authoritative inputs, not an allow flag or an arbitrary asserted plan hash.
   * Static mode copies the exact complete finite rehearsal wire sequence. Quarter mode
   * binds one immutable reducer intent and its fixed prefix, then permits only typed late
   * COMPLETE plus verified CLOSE. Both modes start with CLAIM and end CLOSED.
   */
  static final class Plan {
    private final String spreadsheet,dataset,purpose,run,path,sha,rootSha,businessSha,baselineSha;
    private final Stage stage;
    private final Book baseline;
    private final List<String> wires;
    private final Instant issuedAt,expiresAt,businessAt;
    private final int maxReads;
    private final long waitNanos,stageNanos;
    private final Metadata priorArm;
    private final QuarterPlan quarter;
    private final Limits quarterLimits;
    private record QuarterInput(String owner,Clock clock,Limits limits) {}
    /** Closed-baseline authority: exact fixed prefix, then only this intent's late COMPLETE and CLOSE. */
    static Plan quarter(ManualQuarterMaintenance.Binding binding,String run,Stage stage,Book expectedClosed,
        String owner,Clock clock,Limits limits,Instant issuedAt,Instant expiresAt,Duration maxWait,Duration maxStage,
        int maxReads,Metadata exactPriorArm){
      if(clock==null||limits==null||owner==null||owner.isBlank())throw denied();
      return new Plan(binding,run,stage,expectedClosed,null,clock.instant(),issuedAt,expiresAt,maxWait,maxStage,
          maxReads,exactPriorArm,new QuarterInput(owner,clock,limits));
    }
    Plan(String spreadsheet,String dataset,String run,Stage stage,Book expectedClosed,
         List<String> exactWires,Instant businessAt,Instant issuedAt,Instant expiresAt,
         Duration maxWait,Duration maxStage,int maxReads,Metadata exactPriorArm){
      this(new ManualQuarterMaintenance.Binding(spreadsheet,dataset),run,stage,expectedClosed,exactWires,
          businessAt,issuedAt,expiresAt,maxWait,maxStage,maxReads,exactPriorArm);
    }
    Plan(ManualQuarterMaintenance.Binding binding,String run,Stage stage,Book expectedClosed,
         List<String> exactWires,Instant businessAt,Instant issuedAt,Instant expiresAt,
         Duration maxWait,Duration maxStage,int maxReads,Metadata exactPriorArm){
      this(binding,run,stage,expectedClosed,exactWires,businessAt,issuedAt,expiresAt,maxWait,maxStage,maxReads,exactPriorArm,null);
    }
    private Plan(ManualQuarterMaintenance.Binding binding,String run,Stage stage,Book expectedClosed,
         List<String> exactWires,Instant businessAt,Instant issuedAt,Instant expiresAt,
         Duration maxWait,Duration maxStage,int maxReads,Metadata exactPriorArm,QuarterInput input){
      String spreadsheet=binding.spreadsheetId(),dataset=binding.datasetId();
      try{
        if(!binding.purpose().equals("bridgego-restricted-test")||FORBIDDEN_TARGETS.contains(spreadsheet))throw denied();
        if(spreadsheet==null||!spreadsheet.matches("[A-Za-z0-9_-]{1,160}")||run==null||!run.matches("[A-Za-z0-9_-]{1,64}")
            ||stage==null||expectedClosed==null||exactWires==null&&input==null||businessAt==null||issuedAt==null||expiresAt==null
            ||maxWait==null||maxStage==null||maxReads<1||maxReads>16)throw denied();
        uuid(dataset);
        long lifetime=Duration.between(issuedAt,expiresAt).toMillis();
        long waitMillis=maxWait.toMillis(),stageMillis=maxStage.toMillis();
        if(lifetime<1||lifetime>900_000||waitMillis<1||waitMillis>60_000||stageMillis<1||stageMillis>600_000
            ||input==null&&(exactWires.size()<2||exactWires.size()>16))throw denied();
        this.spreadsheet=spreadsheet;this.dataset=dataset;this.purpose=binding.purpose();this.run=run;this.stage=stage;
        this.path="/v4/spreadsheets/"+spreadsheet+":batchUpdate";
        this.businessAt=businessAt;this.issuedAt=issuedAt;this.expiresAt=expiresAt;
        this.waitNanos=maxWait.toNanos();this.stageNanos=maxStage.toNanos();this.maxReads=maxReads;
        this.baseline=expectedClosed.copy();this.priorArm=exactPriorArm;
        validateBook(this.baseline,binding,businessAt);
        Root root=root(new ArrayList<>(this.baseline.metadata.values()));
        if(!root.state().equals("CLOSED"))throw denied();
        if(stage==Stage.A){if(exactPriorArm!=null||armLike(this.baseline).size()!=0)throw denied();}
        else {
          // B is a separate operator release, never recovery for UNKNOWN A. The exact A marker,
          // completed A close and old-process termination must be independently approved upstream.
          if(exactPriorArm==null||exactPriorArm.id()!=ARM_A_ID||!exactPriorArm.key().equals(ARM_A_KEY)
              ||!exactPriorArm.equals(this.baseline.metadata.get(ARM_A_ID))||armLike(this.baseline).size()!=1
              ||!root.operation().startsWith("CLOSE-")||root.next()<4)throw denied();
          Arm prior=parseArm(exactPriorArm);
          if(!prior.stage.equals(Stage.A.name())||!prior.spreadsheet.equals(spreadsheet)
              ||!prior.dataset.equals(dataset)||!prior.purpose.equals(purpose)||!prior.run.equals(run))throw denied();
        }
        if(input!=null){
          ascii(input.owner,40);
          Root claimed=new Root(root.dataset(),root.epoch(),root.anchor(),root.nextSheet(),root.next()+1,
              input.owner,"OPEN","CLAIM-"+input.owner,hash(input.owner));
          String claim=QuarterPlan.phaseWire(root,claimed,List.of());
          this.quarter=QuarterPlan.create(binding,simulate(this.baseline,claim),input.owner,input.clock,input.limits);
          this.quarterLimits=input.limits;
          exactWires=List.of(claim,quarter.prepareTicket().wire(),quarter.foldTicket().wire());
        }else {this.quarter=null;this.quarterLimits=null;}
        this.wires=List.copyOf(exactWires);
        long total=0;Book cursor=this.baseline.copy();
        for(int i=0;i<wires.size();i++){
          String w=wires.get(i);if(w==null||(total+=w.getBytes(StandardCharsets.UTF_8).length)>4L*1024*1024
              ||w.getBytes(StandardCharsets.UTF_8).length>256*1024)throw denied();
          JsonNode n=BoundedSheetsProjection.MAPPER.readTree(w);
          if(n==null||!n.isObject()||n.size()!=1||!n.path("requests").isArray())throw denied();
          rejectArmMutation(n);
          Book after=simulate(cursor,w);BoundedSheetsProjection.validateNarrowControl(after);after.budgets();
          if(!armLike(after).equals(armLike(cursor)))throw denied();
          Root next=root(new ArrayList<>(after.metadata.values()));
          if(!next.dataset().equals(dataset)||i==0&&(!next.state().equals("OPEN")
              ||!next.operation().equals("CLAIM-"+next.owner())||!next.hash().equals(hash(next.owner()))
              ||!after.tables().equals(cursor.tables())||n.path("requests").size()!=3))throw denied();
          cursor=after;
        }
        if(!root(new ArrayList<>(cursor.metadata.values())).state().equals(quarter==null?"CLOSED":"APPLIED"))throw denied();
        this.rootSha=hash(root);this.businessSha=hash(new TreeMap<>(this.baseline.tables()));
        this.baselineSha=bookSha(this.baseline);
        var descriptor=new TreeMap<String,Object>();descriptor.put("spreadsheet",spreadsheet);descriptor.put("dataset",dataset);
        descriptor.put("purpose",purpose);descriptor.put("run",run);descriptor.put("stage",stage.name());descriptor.put("baselineSha256",baselineSha);
        descriptor.put("wires",this.wires);descriptor.put("businessAt",businessAt.toString());
        descriptor.put("issuedAt",issuedAt.toString());descriptor.put("expiresAt",expiresAt.toString());
        descriptor.put("maxWaitNanos",waitNanos);descriptor.put("maxStageNanos",stageNanos);descriptor.put("maxReads",maxReads);
        if(quarter!=null){descriptor.put("mode","bounded-quarter-v1");descriptor.put("quarterDigest",quarter.digest());descriptor.put("quarterLimits",quarterLimits);}
        this.sha=hash(descriptor);
      }catch(Denied failure){throw failure;}catch(Exception failure){throw denied();}
    }
    String sha256(){return sha;}
    String mutationPath(){return path;}
    Stage stage(){return stage;}
    int writeCount(){return quarter==null?wires.size():5;}
    QuarterPlan quarter(){return quarter;}
    Limits quarterLimits(){return quarterLimits;}
    boolean closedMatches(Book armed,Metadata expectedArm){Book expected=baseline.copy();expected.metadata.put(expectedArm.id(),expectedArm);return bookSha(expected).equals(bookSha(armed));}
    int wiresCount(){return wires.size();}
    String wire(int index){return wires.get(index);}
    Instant expiresAt(){return expiresAt;}
    Instant issuedAt(){return issuedAt;}
    long stageNanos(){return stageNanos;}
    /** Pure metadata representation for the independently authorized operator. This never writes it. */
    Metadata expectedArm(String nonce){
      if(nonce==null||!nonce.matches("[0-9a-f]{64}"))throw denied();
      var fields=new TreeMap<String,Object>();fields.put("version",1);fields.put("run",run);fields.put("stage",stage.name());
      fields.put("spreadsheet",spreadsheet);fields.put("dataset",dataset);fields.put("purpose",purpose);fields.put("bootNonce",nonce);
      fields.put("rootSha256",rootSha);fields.put("businessSha256",businessSha);fields.put("planSha256",sha);
      fields.put("issuedAt",issuedAt.toString());fields.put("expiresAt",expiresAt.toString());
      Metadata value=new Metadata(stage==Stage.A?ARM_A_ID:ARM_B_ID,stage==Stage.A?ARM_A_KEY:ARM_B_KEY,json(fields),null);
      if(value.characters()>MAX_ARM_CHARS)throw denied();return value;
    }
  }

  /** Missing current arm permits a bounded read-only poll. Anything malformed/conflicting is terminal.
   * The process latch is consumed here, before caller may start runtime (which sends CLAIM).
   */
  synchronized Optional<WritePermit> consume(Plan plan,Book completeNativeParsedBook){
    if(status!=Status.READ_ONLY_UNARMED)throw denied();
    try{
      if(plan==null||completeNativeParsedBook==null)throw denied();
      if(pinned==null)pinned=plan;else if(pinned!=plan)throw denied();
      Instant now=Instant.now();
      if(++reads>plan.maxReads||System.nanoTime()-bornNanos>plan.waitNanos
          ||now.isBefore(bornAt)||now.isBefore(plan.issuedAt)||!now.isBefore(plan.expiresAt))throw denied();
      if(plan.quarter!=null)plan.quarter.requireWindow(now);
      Book actual=completeNativeParsedBook.copy();validateBook(actual,new ManualQuarterMaintenance.Binding(plan.spreadsheet,plan.dataset,plan.purpose),plan.businessAt);
      List<Metadata> arms=armLike(actual);Metadata current=null;
      for(Metadata m:arms){
        Arm parsed=parseArm(m);
        if(m.id()==ARM_A_ID&&plan.stage==Stage.B){if(!m.equals(plan.priorArm))throw denied();continue;}
        if(current!=null||!parsed.stage.equals(plan.stage.name())||!m.equals(plan.expectedArm(nonce)))throw denied();
        current=m;
      }
      if(plan.stage==Stage.B&&!Objects.equals(actual.metadata.get(ARM_A_ID),plan.priorArm))throw denied();
      Book withoutCurrent=actual.copy();if(current!=null)withoutCurrent.metadata.remove(current.id());
      if(!bookSha(withoutCurrent).equals(plan.baselineSha))throw denied();
      if(current==null)return Optional.empty();
      // Quota and exact-preservation checks include the arm at every planned mutation prefix.
      Book simulated=actual;for(String wire:plan.wires){Book next=simulate(simulated,wire);
        if(!armLike(next).equals(arms))throw denied();simulated=next;}
      if(plan.quarter!=null){
        // The arm is an exact separate envelope, not omitted metadata. Bind it once, then
        // include its real quota cost in every fixed prefix and final-capacity check.
        plan.quarter.validateExactArm(current,plan.quarterLimits);
      }
      status=Status.CONSUMED;
      return Optional.of(WritePermit.issue(this,plan,nonce));
    }catch(RuntimeException failure){status=Status.BLOCKED;pinned=null;throw denied();}
  }
  synchronized boolean takePermitIssuance(Plan plan,String nonce){
    if(permitIssued||!owns(plan,nonce))return false;permitIssued=true;return true;
  }
  synchronized boolean owns(Plan plan,String nonce){return status==Status.CONSUMED&&plan!=null&&pinned==plan&&this.nonce.equals(nonce);}
  synchronized void release(Plan plan,String nonce){if(pinned==plan&&this.nonce.equals(nonce))pinned=null;}
  static final class Denied extends RuntimeException {private Denied(){super("TEST_BOOT_WRITE_DENIED",null,false,false);}}
  static Denied denied(){return new Denied();}
  private record Arm(String stage,String spreadsheet,String dataset,String purpose,String run) {}
  private static Arm parseArm(Metadata m){
    try{
      if(m==null||m.sheet()!=null||m.characters()>MAX_ARM_CHARS
          ||!(m.id()==ARM_A_ID&&m.key().equals(ARM_A_KEY)||m.id()==ARM_B_ID&&m.key().equals(ARM_B_KEY)))throw denied();
      JsonNode n=BoundedSheetsProjection.MAPPER.readTree(m.value());
      if(n==null||!n.isObject()||n.size()!=FIELDS.size())throw denied();keys(n,FIELDS);
      if(integer(n,"version")!=1)throw denied();String stage=text(n,"stage");
      if(!stage.equals(m.id()==ARM_A_ID?"A":"B"))throw denied();
      for(String key:List.of("rootSha256","businessSha256","planSha256","bootNonce"))if(!text(n,key).matches("[0-9a-f]{64}"))throw denied();
      if(!text(n,"spreadsheet").matches("[A-Za-z0-9_-]{1,160}")||!text(n,"run").matches("[A-Za-z0-9_-]{1,64}"))throw denied();
      new ManualQuarterMaintenance.Binding(text(n,"spreadsheet"),text(n,"dataset"),text(n,"purpose"));Instant issued=Instant.parse(text(n,"issuedAt")),expires=Instant.parse(text(n,"expiresAt"));
      if(!issued.toString().equals(text(n,"issuedAt"))||!expires.toString().equals(text(n,"expiresAt"))
          ||!expires.isAfter(issued)||Duration.between(issued,expires).toMillis()>900_000)throw denied();
      return new Arm(stage,text(n,"spreadsheet"),text(n,"dataset"),text(n,"purpose"),text(n,"run"));
    }catch(Exception failure){throw denied();}
  }
  private static List<Metadata> armLike(Book b){return b.metadata.values().stream()
      .filter(m->m.id()==ARM_A_ID||m.id()==ARM_B_ID||m.key().startsWith("test.arm")).toList();}
  private static void rejectArmMutation(JsonNode n){
    for(JsonNode request:n.path("requests")){
      if(!request.isObject()||request.size()!=1)throw denied();
      if(request.has("createDeveloperMetadata")){
        JsonNode d=request.path("createDeveloperMetadata").path("developerMetadata");
        if(d.path("metadataId").asInt()>=ARM_A_ID&&d.path("metadataId").asInt()<=ARM_B_ID
            ||d.path("metadataKey").asText().startsWith("test.arm"))throw denied();
      }
      if(request.has("updateDeveloperMetadata")){
        JsonNode d=request.path("updateDeveloperMetadata");int id=lookup(d);if(id!=1)throw denied();
      }
      if(request.has("deleteDeveloperMetadata")){
        JsonNode filter=request.path("deleteDeveloperMetadata").path("dataFilter").path("developerMetadataLookup");
        int id=integer(filter,"metadataId");if(id<2||id>=10000)throw denied();
      }
    }
  }
  private static void validateBook(Book b,ManualQuarterMaintenance.Binding binding,Instant businessAt){
    String dataset=binding.datasetId();
    b.budgets();if(b.sheets.size()!=8||!b.tables().keySet().equals(ManualQuarterMaintenance.HEADERS.keySet()))throw denied();
    for(var entry:b.sheets.entrySet()){Sheet s=entry.getValue();if(entry.getKey()!=s.id||s.rowCount!=1000||s.columnCount!=26||s.rows.size()>1000)throw denied();validateGrid(s);}
    for(var entry:b.metadata.entrySet()){if(entry.getKey()!=entry.getValue().id())throw denied();BoundedSheetsProjection.strictControlValue(entry.getValue());}
    BoundedSheetsProjection.validateNarrowControl(b);
    Root r=root(new ArrayList<>(b.metadata.values()));if(!r.dataset().equals(dataset))throw denied();
    ManualQuarterMaintenance.validate(new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(),true,b.tables()),
        binding,Clock.fixed(businessAt,ZoneOffset.UTC));
  }
  private static String bookSha(Book b){
    var model=new TreeMap<String,Object>();var sheets=new ArrayList<Object>();
    for(Sheet s:b.sheets.values())sheets.add(List.of(s.id,s.title,s.rowCount,s.columnCount,s.rows));
    model.put("sheets",sheets);model.put("metadata",new ArrayList<>(b.metadata.values()));return hash(model);
  }
}
