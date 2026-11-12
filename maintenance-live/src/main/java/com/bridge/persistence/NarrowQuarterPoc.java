package com.bridge.persistence;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.bridge.persistence.NativeSheetsModel.*;

/** Package-private quarter maintenance protocol kernel. No game runtime or automatic launcher. */
final class NarrowQuarterPoc {
  static final String ROOT_KEY="nq.root", RECEIPT_KEY="nq.receipt", TARGET="SYNTHETIC_NARROW_ONLY";
  static final int CONTROL_MAX=1024;
  // PREPARE, FOLD, VERIFIED, CLOSED plus rotation arbitration + rotation replacement receipt.
  static final int TRANSITION_SLOTS=6, TRANSITION_RESERVE=TRANSITION_SLOTS*CONTROL_MAX;
  record ManagedIdentityResult(String account,Ticket ticket,Root verifiedRoot) {}
  enum State { STARTING, OPEN, PREPARED, UNKNOWN, MAINTENANCE, BLOCKED_MANUAL, CLOSED }
  record Limits(int requestBytes,int futureReadBytes,int rowsPerSheet) {
    Limits { if(requestBytes<1||futureReadBytes<1||rowsPerSheet<2||rowsPerSheet>1000)throw new IllegalArgumentException(); }
    static Limits defaults(){return new Limits(256*1024,2*1024*1024,1000);}
  }
  record Root(String dataset,int epoch,int anchor,int nextSheet,int next,String owner,String state,String operation,String hash) {
    Root { uuid(dataset);ascii(owner,40);ascii(operation,96);ascii(state,24);if(!hash.matches("[0-9a-f]{64}")||epoch<1||anchor<0||nextSheet<=anchor||next<2||next>10000||epoch>1000000)throw new IllegalArgumentException(); }
  }
  record Check(String tab,int column,String key,List<List<String>> expected) { Check {expected=List.copyOf(expected.stream().map(List::copyOf).toList());} }
  record Ticket(String operation,String hash,String uuid,String wire,int slot,int epoch,int anchor,String owner,Root after,List<Check> checks,int futureUpper,Map<String,Integer> rowCounts,Map<String,List<List<String>>> fullExpected,Instant cutoff) {
    Ticket(String operation,String hash,String uuid,String wire,int slot,int epoch,int anchor,String owner,Root after,List<Check> checks,int futureUpper,Map<String,Integer> rowCounts){this(operation,hash,uuid,wire,slot,epoch,anchor,owner,after,checks,futureUpper,rowCounts,null,null);}
    Ticket {ascii(operation,96);if(!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();if(uuid!=null)NarrowQuarterPoc.uuid(uuid);checks=List.copyOf(checks);rowCounts=Map.copyOf(rowCounts);if(fullExpected!=null)fullExpected=ManualQuarterMaintenance.immutable(fullExpected);}
  }
  static void ascii(String value,int max){if(value==null||value.length()>max||value.chars().anyMatch(c->c<32||c>126))throw new IllegalArgumentException();}
  static void uuid(String id){if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
  static String hash(Object o){return FinalMatchEnvelope.hash(json(o));}
  static String rootText(Root r){String value=json(r);ascii(value,CONTROL_MAX-ROOT_KEY.length());return value;}
  static Root root(List<Metadata> metadata) {
    List<Metadata> roots=metadata.stream().filter(m->m.key().equals(ROOT_KEY)).toList();if(roots.size()!=1||roots.get(0).id()!=1||roots.get(0).sheet()!=null)throw new Rejected();
    Root r;try {r=JSON.readValue(roots.get(0).value(),Root.class);}catch(Exception e){throw new Rejected();}
    for(Metadata m:metadata)if(m.key().startsWith("nq.")&&!Set.of(ROOT_KEY,RECEIPT_KEY).contains(m.key()))throw new Rejected();
    for(int i=2;i<r.next();i++){final int id=i;Metadata m=metadata.stream().filter(x->x.id()==id).findFirst().orElseThrow(Rejected::new);if(!m.key().equals(RECEIPT_KEY)||m.sheet()!=null)throw new Rejected();}
    if(metadata.stream().anyMatch(m->m.id()==r.next()||m.key().equals(RECEIPT_KEY)&&(m.id()<2||m.id()>=r.next())))throw new Rejected();
    return r;
  }
  static Map<String,Object> guard(int id,String title){return update(id,0,1,0,5,List.of(RetentionLedger.HEADER));}
  static Map<String,Object> rename(int id,String title){return Map.of("updateSheetProperties",Map.of("properties",Map.of("sheetId",id,"title",title),"fields","title"));}
  static Map<String,Object> create(Metadata m){return Map.of("createDeveloperMetadata",Map.of("developerMetadata",m.nativeValue()));}
  static Map<String,Object> updateRoot(Root r){return Map.of("updateDeveloperMetadata",Map.of("dataFilters",List.of(Map.of("developerMetadataLookup",Map.of("metadataId",1))),"developerMetadata",Map.of("metadataValue",rootText(r)),"fields","metadataValue"));}
  static Map<String,Object> deleteMetadata(int id){return Map.of("deleteDeveloperMetadata",Map.of("dataFilter",Map.of("developerMetadataLookup",Map.of("metadataId",id))));}
  static Map<String,Object> append(int id,List<List<String>> rows){return Map.of("appendCells",Map.of("sheetId",id,"rows",rowData(rows),"fields","userEnteredValue"));}
  static Map<String,Object> update(int id,int start,int end,int col,int columns,List<List<String>> rows){return Map.of("updateCells",Map.of("range",Map.of("sheetId",id,"startRowIndex",start,"endRowIndex",end,"startColumnIndex",col,"endColumnIndex",columns),"rows",rowData(rows),"fields","userEnteredValue"));}
  static String wire(List<Object> requests){return json(Map.of("requests",requests));}
  static Metadata receipt(Root old,String op,String hash,String phase){String value=json(Map.of("epoch",old.epoch,"owner",old.owner,"operation",op,"hash",hash,"phase",phase));ascii(value,CONTROL_MAX-RECEIPT_KEY.length());return new Metadata(old.next,RECEIPT_KEY,value,null);}
  static boolean sameRows(String tab,List<List<String>> actual,List<List<String>> expected){if(!tab.equals("accounts"))return actual.equals(expected);actual.forEach(AccountProfileRows::validateRow);expected.forEach(AccountProfileRows::validateRow);return actual.stream().map(NativeSheetsModel::trimRangeRow).toList().equals(expected.stream().map(NativeSheetsModel::trimRangeRow).toList());}
  static int spreadsheetChars(List<Metadata> all){return all.stream().filter(m->m.sheet()==null).mapToInt(Metadata::characters).sum();}

  static final class Service implements AutoCloseable {
    // All identity/profile/match/clock/close operations share this single monitor and one pending ticket.
    final Object serial=new Object();final NarrowSheetsTransport endpoint;final Clock clock;final Limits limits;final String owner;
    final ManualQuarterMaintenance.Binding binding;final java.util.function.Supplier<String> identityIds;
    State state=State.STARTING;Ticket pending;List<List<String>> rotationRows;boolean attempted,closed;Root known;
    Map<String,Integer> ids=new TreeMap<>(),rowCounts=new TreeMap<>();int fullUpper;
    ScheduledExecutorService scheduler;
    private QuarterPlan quarterIntent;private QuarterPlan.Completion quarterProof;private int quarterPhase;
    private WritePermit.QuarterRun quarterRun;private WritePermit quarterPermit;
    void bindQuarterRun(WritePermit.QuarterRun run){synchronized(serial){
      if(state!=State.STARTING||closed||quarterRun!=null||quarterPermit!=null||run==null)throw new Rejected();
      run.intent().requireWindow(clock);quarterRun=run;quarterPermit=run.permit();
    }}
    boolean quarterAuthorityUsable(){synchronized(serial){return quarterPermit==null||quarterPermit.status()==WritePermit.Status.READY;}}
    private Instant quarterObservedAt;private boolean quarterWindowClosed;private QuarterPlan.Summary quarterSummary;
    Service(NarrowSheetsTransport endpoint,Clock clock,String dataset,String owner,Limits limits){this(endpoint,clock,new ManualQuarterMaintenance.Binding(TARGET,dataset),owner,limits);}
    Service(NarrowSheetsTransport endpoint,Clock clock,ManualQuarterMaintenance.Binding binding,String owner,Limits limits){this(endpoint,clock,binding,owner,limits,()->UUID.randomUUID().toString());}
    Service(NarrowSheetsTransport endpoint,Clock clock,ManualQuarterMaintenance.Binding binding,String owner,Limits limits,java.util.function.Supplier<String> identityIds){this.endpoint=endpoint;this.clock=clock;this.owner=owner;ascii(owner,40);this.limits=limits;this.binding=Objects.requireNonNull(binding);this.identityIds=Objects.requireNonNull(identityIds);}
    State state(){synchronized(serial){return state;}}
    void start(){start(false);}
    void startStoredStateOnlyForSyntheticTest(){start(true);}
    private void start(boolean storedStateOnly){synchronized(serial){if(state!=State.STARTING)return;try {
      Book b=full();if(quarterRun!=null){quarterRun.intent().requireWindow(clock);if(!quarterRun.closedMatches(b))throw new Rejected();}
      adoptBaseline(b);known=root(new ArrayList<>(b.metadata.values()));
      if(!known.dataset.equals(binding.datasetId())||!(known.state.equals("CLOSED")||storedStateOnly&&known.state.equals("OPEN")))throw new Rejected();
      Root after=new Root(known.dataset,known.epoch,known.anchor,known.nextSheet,known.next+1,owner,"OPEN","CLAIM-"+owner,hash(owner));
      Ticket claim=make(known,after.operation,after.hash,null,after,List.of(),List.of(),new TreeMap<>(rowCounts),true);
      pending=claim;state=State.PREPARED;send(claim);if(state==State.OPEN)tick();
    }catch(RuntimeException failure){block();}}}
    Book full(){Book b=endpoint.snapshot();if(endpoint.unsupportedStructure())throw new Rejected();
      ManualQuarterMaintenance.validate(new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(),true,b.tables()),binding,clock);
      if(b.sheets.size()!=8||!b.tables().keySet().equals(ManualQuarterMaintenance.HEADERS.keySet()))throw new Rejected();
      b.budgets();Root control=root(new ArrayList<>(b.metadata.values()));if(!control.dataset.equals(binding.datasetId())||control.anchor!=b.sheet("retention_ledger").id||b.sheets.keySet().stream().anyMatch(id->id>=control.nextSheet))throw new Rejected();int bytes=bytes(b.nativeValue());if(bytes>limits.futureReadBytes)throw new Rejected();
      for(Sheet s:b.sheets.values())if(s.rowCount!=1000||s.columnCount!=26||s.rows.size()>limits.rowsPerSheet)throw new Rejected();return b;
    }
    void adoptBaseline(Book b){var nextIds=new TreeMap<String,Integer>();var counts=new TreeMap<String,Integer>();for(Sheet s:b.sheets.values()){nextIds.put(s.title,s.id);counts.put(s.title,s.rows.size());}ids=nextIds;rowCounts=counts;fullUpper=bytes(b.nativeValue());}
    void startScheduler(long periodMillis){synchronized(serial){if(periodMillis<1||scheduler!=null)throw new IllegalArgumentException();scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"synthetic-quarter-clock");t.setDaemon(true);return t;});scheduler.scheduleWithFixedDelay(this::tick,0,periodMillis,TimeUnit.MILLISECONDS);}}
    void tick(){synchronized(serial){if(state!=State.OPEN||closed||!quarterAuthorityUsable())return;try{
      Root r=readOwner();if(quarterInProgress()){state=State.MAINTENANCE;quarter(r);return;}List<List<String>> ledger=endpoint.related("retention_ledger");RetentionLedger l=new RetentionLedger(ledger);
      if(l.complete()&&l.cutoff().equals(QuarterlyRetention.currentQuarterStart(clock.instant())))return;
      state=State.MAINTENANCE;quarter(r);
    }catch(RuntimeException failure){block();}}}
    Root readOwner(){Root r=root(endpoint.control());if(known==null||!r.equals(known)||!r.owner.equals(owner)||!r.state.equals("OPEN"))throw new Rejected();return r;}
    Instant openRequired(){if(state!=State.OPEN||closed)throw new Rejected();RetentionLedger l=new RetentionLedger(endpoint.related("retention_ledger"));Instant admitted=QuarterlyRetention.currentQuarterStart(clock.instant());if(!l.complete()||!l.cutoff().equals(admitted)){block();throw new Rejected();}return admitted;}
    void ensureReserve(Instant admitted){List<Metadata> all=endpoint.control();Root r=root(all);if(spreadsheetChars(all)+CONTROL_MAX+TRANSITION_RESERVE>METADATA_LIMIT){rotate(r,admitted);if(state!=State.OPEN)throw new Rejected();}}
    Ticket identity(String op,String account,String subject){synchronized(serial){try{Instant admitted=openRequired();ascii(op,96);uuid(account);ascii(subject,255);if(subject.isBlank())throw new Rejected();ensureReserve(admitted);Root r=readOwner();
      List<List<String>> existing=endpoint.matching("auth_identities",1,subject);if(!existing.isEmpty())throw new Rejected();
      if(!endpoint.matching("accounts",0,account).isEmpty())throw new Rejected();ErasureLedger e=new ErasureLedger(endpoint.related("erasure_ledger"));if(e.contains("ACCOUNT",account)||e.contains("IDENTITY",ErasureLedger.identityKey("https://accounts.google.com",subject)))throw new Rejected();
      List<String> a=List.of(account,"1"),i=List.of("https://accounts.google.com",subject,account,"1");var rows=new TreeMap<>(rowCounts);rows.compute("accounts",(k,v)->v+1);rows.compute("auth_identities",(k,v)->v+1);
      return prepare(r,op,hash(List.of(a,i)),account,List.of(append(ids.get("accounts"),List.of(a)),append(ids.get("auth_identities"),List.of(i))),List.of(new Check("accounts",0,account,List.of(a)),new Check("auth_identities",1,subject,List.of(i))),rows,admitted);
    }catch(RuntimeException failure){if(state!=State.PREPARED)block();throw failure;}}}
    Ticket profile(String op,String account,String nickname,String email){synchronized(serial){try{Instant admitted=openRequired();ascii(op,96);uuid(account);if(!AccountProfileRows.validNickname(nickname)||!AccountProfileRows.validEmail(email))throw new Rejected();ensureReserve(admitted);Root r=readOwner();
      List<List<String>> rows=endpoint.related("accounts");AccountProfileRows.validateTable(rows);int index=-1;for(int j=1;j<rows.size();j++)if(rows.get(j).get(0).equals(account))index=j;if(index<0)throw new Rejected();
      // Existing literal schema must already support profile columns; no implicit schema migration.
      if(rows.get(0).size()<4)throw new Rejected();var expected=new ArrayList<>(rows.get(index));while(expected.size()<4)expected.add("");expected.set(1,"2");expected.set(2,nickname);expected.set(3,email);AccountProfileRows.validateRow(expected);
      return prepare(r,op,hash(expected),account,List.of(update(ids.get("accounts"),index,index+1,1,4,List.of(List.of("2",nickname,email)))),List.of(new Check("accounts",0,account,List.of(expected))),rowCounts,admitted);
    }catch(RuntimeException failure){if(state!=State.PREPARED)block();throw failure;}}}
    /** Runs only on the runtime's sole worker. Lookup and first profile share the same
     * current source, guard, receipt and pending ticket as all other business writes. */
    ManagedIdentityResult managedIdentity(String op,String issuer,String subject,String nickname,String email,boolean profile){return managedIdentity(op,issuer,subject,nickname,email,profile,true);}
    private ManagedIdentityResult managedIdentity(String op,String issuer,String subject,String nickname,String email,boolean profile,boolean allowMutation){synchronized(serial){try{
      Instant admitted=openRequired();ascii(op,96);ManagedIdentityValidation.validate(issuer,subject,nickname,email,profile);
      Root r=readOwner();List<List<String>> accounts=endpoint.related("accounts");AccountProfileRows.validateTable(accounts);
      List<List<String>> identities=endpoint.related("auth_identities");
      if(identities.isEmpty()||!identities.get(0).equals(ManualQuarterMaintenance.HEADERS.get("auth_identities")))throw new Rejected();
      ErasureLedger erasures=new ErasureLedger(endpoint.related("erasure_ledger"));
      Map<String,Integer> accountRows=new HashMap<>();for(int n=1;n<accounts.size();n++){
        String id=accounts.get(n).get(0);if(erasures.contains("ACCOUNT",id))throw new Rejected();accountRows.put(id,n);
      }
      Set<String> keys=new HashSet<>();String account=null;List<String> mapping=null;
      for(int n=1;n<identities.size();n++){
        List<String> row=identities.get(n);if(row.size()!=4||!"1".equals(row.get(3))||!accountRows.containsKey(row.get(2)))throw new Rejected();
        ManagedIdentityValidation.validate(row.get(0),row.get(1),null,null,false);
        String key=ErasureLedger.identityKey(row.get(0),row.get(1));
        if(!keys.add(key)||erasures.contains("IDENTITY",key))throw new Rejected();
        if(row.get(0).equals(issuer)&&row.get(1).equals(subject)){account=row.get(2);mapping=row;}
      }
      if(erasures.contains("IDENTITY",ErasureLedger.identityKey(issuer,subject)))throw new Rejected();
      if(profile&&accounts.get(0).size()<4)throw new Rejected(); // Explicit schema migration is a separate operation.
      int index=account==null?-1:accountRows.get(account);
      if(account!=null&&!profile){readOwner();openRequired();return new ManagedIdentityResult(account,null,r);}
      List<String> expected;
      if(account==null){if(!allowMutation)throw new Rejected();account=identityIds.get();uuid(account);if(accountRows.containsKey(account)||erasures.contains("ACCOUNT",account))throw new Rejected();
        mapping=List.of(issuer,subject,account,"1");expected=profile?List.of(account,"2",nickname,email):List.of(account,"1");
      }else{
        var desired=new ArrayList<>(accounts.get(index));while(desired.size()<4)desired.add("");
        desired.set(1,"2");desired.set(2,nickname);desired.set(3,email);expected=List.copyOf(desired);
        if(sameRows("accounts",List.of(accounts.get(index)),List.of(expected))){readOwner();openRequired();return new ManagedIdentityResult(account,null,r);}
      }
      if(!allowMutation)throw new Rejected();
      AccountProfileRows.validateRow(expected);ensureReserve(admitted);r=readOwner();
      List<Object> mutations=new ArrayList<>();var counts=new TreeMap<>(rowCounts);
      if(index<0){mutations.add(append(ids.get("accounts"),List.of(expected)));mutations.add(append(ids.get("auth_identities"),List.of(mapping)));
        counts.compute("accounts",(k,v)->v+1);counts.compute("auth_identities",(k,v)->v+1);
      }else mutations.add(update(ids.get("accounts"),index,index+1,1,4,List.of(List.of("2",nickname,email))));
      // Updates touch only B:D. The immutable expected row also proves exact lifetime E preservation.
      List<Check> checks=List.of(new Check("accounts",0,account,List.of(expected)),new Check("auth_identities",1,subject,List.of(mapping)),
          new Check("erasure_ledger",1,ErasureLedger.digest("ACCOUNT",account),List.of()),
          new Check("erasure_ledger",1,ErasureLedger.digest("IDENTITY",ErasureLedger.identityKey(issuer,subject)),List.of()));
      Ticket t=prepare(r,op,hash(List.of(expected,mapping)),account,mutations,checks,counts,admitted);
      return new ManagedIdentityResult(account,t,r);
    }catch(RuntimeException failure){if(state!=State.PREPARED)block();throw failure;}}}
    /** Read-only expiry recovery is not write recovery: verify the exact prior account and
     * root, and refuse before allocating UUIDs or preparing/dispatching any changed intent. */
    void reconcileManagedLookup(String op,String issuer,String subject,String nickname,String email,boolean profile,String account,Root originalRoot){synchronized(serial){
      try{
        if(state!=State.OPEN||pending!=null||account==null||originalRoot==null||!originalRoot.equals(known))throw new Rejected();
        ManagedIdentityResult result=managedIdentity(op,issuer,subject,nickname,email,profile,false);
        if(result.ticket()!=null||!account.equals(result.account())||!originalRoot.equals(result.verifiedRoot()))throw new Rejected();
      }catch(RuntimeException failure){block();throw failure;}
    }}
    /** An expired caller cannot publish a late success. Re-open only its original local
     * ticket for positive read-only reconciliation, never manufacture or send a replacement. */
    void retainOriginalForReconciliation(Ticket t){synchronized(serial){
      if(state==State.UNKNOWN&&pending==t)return;
      if(closed||t==null||state!=State.OPEN||pending!=null||!t.after().equals(known))throw new Rejected();
      pending=t;attempted=true;state=State.UNKNOWN;
    }}
    Ticket match(String op,FinalMatchEnvelope e){synchronized(serial){try{Instant admitted=openRequired();ascii(op,96);ensureReserve(admitted);Root r=readOwner();QuarterlyRetention.requireCurrent(e,clock,new RetentionLedger(endpoint.related("retention_ledger")));new ErasureLedger(endpoint.related("erasure_ledger")).requireAllowed(e);
      List<List<String>> commits=endpoint.related("commits");if(commits.stream().skip(1).anyMatch(row->row.get(0).equals(e.commitId())||row.get(3).equals(e.matchId()))||!endpoint.matching("envelopes",0,e.commitId()).isEmpty())throw new Rejected();
      // Validate the exact envelope and account references using the existing complete validator on a small related dataset.
      Map<String,List<List<String>>> subset=new TreeMap<>();ManualQuarterMaintenance.HEADERS.forEach((k,v)->subset.put(k,List.of(v)));
      subset.put("dataset",List.of(ManualQuarterMaintenance.HEADERS.get("dataset"),List.of(binding.purpose(),binding.datasetId())));
      List<List<String>> accounts=new ArrayList<>();accounts.add(AccountProfileRows.LIFETIME_HEADER);Set<String> seen=new HashSet<>();
      for(var p:e.payload().path("participants"))if(p.path("account_id").isTextual()){String a=p.get("account_id").asText();if(seen.add(a))accounts.addAll(endpoint.matching("accounts",0,a));}
      subset.put("accounts",accounts);subset.put("erasure_ledger",endpoint.related("erasure_ledger"));subset.put("retention_ledger",endpoint.related("retention_ledger"));
      List<List<String>> chunks=new ArrayList<>();String text=e.canonicalJson();int count=(text.length()+9999)/10000;for(int n=0;n<count;n++)chunks.add(List.of(e.commitId(),e.payloadHash(),Integer.toString(n),Integer.toString(count),text.substring(n*10000,Math.min(text.length(),(n+1)*10000))));
      List<String> commit=List.of(e.commitId(),e.payloadHash(),Integer.toString(count),e.matchId(),Integer.toString(e.revision()));
      var env=new ArrayList<>(subset.get("envelopes"));env.addAll(chunks);subset.put("envelopes",env);subset.put("commits",List.of(ManualQuarterMaintenance.HEADERS.get("commits"),commit));
      ManualQuarterMaintenance.validate(new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(),true,subset),binding,clock);
      var counts=new TreeMap<>(rowCounts);counts.compute("envelopes",(k,v)->v+chunks.size());counts.compute("commits",(k,v)->v+1);
      return prepare(r,op,e.payloadHash(),null,List.of(append(ids.get("envelopes"),chunks),append(ids.get("commits"),List.of(commit))),List.of(new Check("envelopes",0,e.commitId(),chunks),new Check("commits",0,e.commitId(),List.of(commit))),counts,admitted);
    }catch(RuntimeException failure){if(state!=State.PREPARED)block();throw failure;}}}
    Ticket prepare(Root r,String op,String hash,String uuid,List<Object> mutations,List<Check> checks,Map<String,Integer> counts,Instant admitted){if(!admitted.equals(QuarterlyRetention.currentQuarterStart(clock.instant())))throw new Rejected();Root after=new Root(r.dataset,r.epoch,r.anchor,r.nextSheet,r.next+1,owner,"OPEN",op,hash);Ticket base=make(r,op,hash,uuid,after,mutations,checks,counts,true);Ticket t=new Ticket(base.operation,base.hash,base.uuid,base.wire,base.slot,base.epoch,base.anchor,base.owner,base.after,base.checks,base.futureUpper,base.rowCounts,null,admitted);pending=t;attempted=false;state=State.PREPARED;return t;}
    Ticket make(Root before,String op,String hash,String uuid,Root after,List<Object> mutations,List<Check> checks,Map<String,Integer> counts,boolean reserve){
      ascii(op,96);List<Object> req=new ArrayList<>();req.add(guard(before.anchor,"retention_ledger"));Metadata receipt=receipt(before,op,hash,after.state);req.add(create(receipt));req.addAll(mutations);req.add(updateRoot(after));String wire=wire(req);
      List<Metadata> all=endpoint.control();if(!root(all).equals(before))throw new Rejected();for(Metadata old:all)if(old.key().equals(RECEIPT_KEY)&&parse(old.value()).path("operation").asText().equals(op))throw new Rejected();int growth=Math.max(0,rootText(after).length()-rootText(before).length());
      if(spreadsheetChars(all)+receipt.characters()+growth+(reserve?TRANSITION_RESERVE:0)>METADATA_LIMIT)throw new Rejected();
      int size=wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      // Native literal representation only: request JSON contains at least each changed cell's full encoding.
      // 4x is a conservative bound for cell/row wrappers plus metadata appearing in full native readback.
      long future=(long)fullUpper+4L*size;if(size>limits.requestBytes||future>limits.futureReadBytes||counts.values().stream().anyMatch(n->n>limits.rowsPerSheet))throw new Rejected();
      return new Ticket(op,hash,uuid,wire,before.next,before.epoch,before.anchor,before.owner,after,checks,(int)future,counts,null,QuarterlyRetention.currentQuarterStart(clock.instant()));
    }
    void send(Ticket t){synchronized(serial){if(closed||pending!=t||attempted||state!=State.PREPARED)throw new Rejected();if(t.cutoff!=null&&!t.cutoff.equals(QuarterlyRetention.currentQuarterStart(clock.instant()))){block();return;}try{if(isQuarterTicket(t))requireQuarterWindow();}catch(RuntimeException expired){block();return;}attempted=true;state=State.UNKNOWN;
      try{endpoint.batch(t.wire);}catch(RuntimeException ignored){if(quarterPermit!=null)quarterPermit.closeUnknown();return;}reconcile();
    }}
    boolean reconcile(){synchronized(serial){boolean verified=reconcileCurrent();
      if(!verified&&pending!=null&&attempted&&quarterPermit!=null)quarterPermit.closeUnknown();return verified;
    }}
    private boolean reconcileCurrent(){synchronized(serial){if(closed||pending==null||!attempted)return false;Ticket t=pending;if(rotationRows!=null){boolean ok=reconcileRotation(t,rotationRows);if(ok){ids.put("retention_ledger",t.anchor);rotationRows=null;}return ok;}try{
      if(isQuarterTicket(t))requireQuarterWindow();
      List<Metadata> metadata=endpoint.control();Root r=root(metadata);if(!r.equals(t.after))return false;
      Metadata m=metadata.stream().filter(x->x.id()==t.slot).findFirst().orElseThrow(Rejected::new);Metadata expected=receipt(new Root(r.dataset,t.epoch,t.anchor,r.nextSheet,t.slot,t.owner,"OPEN","",hash("")),t.operation,t.hash,r.state);
      if(!m.equals(expected))return false;
      for(Check check:t.checks)if(!sameRows(check.tab,endpoint.matching(check.tab,check.column,check.key),check.expected))return false;
      Book checked=null;if(t.fullExpected!=null){checked=full();if(!checked.tables().equals(t.fullExpected)||!root(new ArrayList<>(checked.metadata.values())).equals(t.after))return false;}
      if(t.cutoff!=null&&!t.cutoff.equals(QuarterlyRetention.currentQuarterStart(clock.instant())))return false;
      if(isQuarterTicket(t)){requireQuarterWindow();if(checked==null||!(quarterRun==null?quarterIntent.expectedMatches(t,quarterProof,checked):quarterRun.expectedMatches(t,quarterProof,checked)))return false;}
      if(quarterPermit!=null&&r.state().equals("CLOSED")){checked=full();if(!quarterPermit.verifiedClosed(checked))return false;}
      if(quarterRun!=null&&quarterProof!=null&&t==quarterProof.ticket())quarterRun.verifiedComplete(quarterProof,checked,clock);
      if(checked!=null)adoptBaseline(checked);else {fullUpper=t.futureUpper;rowCounts=new TreeMap<>(t.rowCounts);}known=r;pending=null;attempted=false;state=r.state.equals("CLOSED")?State.CLOSED:r.state.equals("OPEN")?State.OPEN:State.MAINTENANCE;if(quarterProof!=null&&t==quarterProof.ticket()){
        quarterSummary=new QuarterPlan.Summary(quarterIntent.planId(),quarterIntent.digest(),quarterIntent.cutoff(),quarterProof.verifiedAt(),r);
        quarterPhase=4;quarterIntent=null;quarterProof=null;quarterRun=null;quarterObservedAt=null;quarterWindowClosed=true;
        // Release source/expired-raw references only after exact full COMPLETE proof. This
        // is not guaranteed heap erasure, and never discards active or UNKNOWN recovery state.
      }return true;
    }catch(RuntimeException failure){return false;}}}
    void block(){if(quarterPermit!=null){if(state==State.UNKNOWN)quarterPermit.closeUnknown();else quarterPermit.closeDenied();}
      if(state!=State.UNKNOWN){state=State.BLOCKED_MANUAL;if(quarterPermit!=null)releaseQuarterState();}}

    /** Heavy diagnostics are active-only; confirmed completion retains just a small summary. */
    QuarterPlan quarterPlan(){synchronized(serial){return quarterIntent;}}
    QuarterPlan.Completion quarterCompletion(){synchronized(serial){return quarterProof;}}
    QuarterPlan.Summary quarterSummary(){synchronized(serial){return quarterSummary;}}
    boolean quarterInProgress(){synchronized(serial){return quarterIntent!=null&&quarterPhase<4;}}
    private boolean isQuarterTicket(Ticket t){return quarterIntent!=null&&(t==quarterIntent.prepareTicket()||t==quarterIntent.foldTicket()||quarterProof!=null&&t==quarterProof.ticket());}
    private void installQuarter(QuarterPlan plan){quarterIntent=plan;quarterProof=null;quarterPhase=0;quarterObservedAt=plan.createdAt();quarterWindowClosed=false;}
    /** Expiry/observed wall-clock rollback permanently fences this retained execution,
     * even if a later caller moves the business clock back into the original window. */
    private void requireQuarterWindow(){
      if(quarterIntent==null||quarterWindowClosed)throw new Rejected();Instant now=clock.instant();
      try{if(now.isBefore(quarterObservedAt))throw new Rejected();quarterIntent.requireWindow(now);quarterObservedAt=now;}
      catch(RuntimeException invalid){quarterWindowClosed=true;throw invalid;}
    }
    /** Read-only immutable planning, once per local intent. No completion time is invented. */
    QuarterPlan planQuarter(){synchronized(serial){
      try{
        if(closed||state!=State.OPEN||pending!=null)throw new Rejected();
        if(quarterInProgress()){requireQuarterWindow();return quarterIntent;}
        Root r=readOwner();Book before=full();if(!root(new ArrayList<>(before.metadata.values())).equals(r))throw new Rejected();
        QuarterPlan plan;
        if(quarterRun==null)plan=QuarterPlan.create(binding,before,owner,clock,limits);
        else {plan=quarterRun.intent();if(!quarterRun.sourceMatches(before))throw new Rejected();plan.requireWindow(clock);}
        installQuarter(plan);return plan;
      }catch(RuntimeException failure){block();throw failure;}
    }}
    /** Install only the exact original object. A matching textual plan ID is insufficient. */
    void executeQuarter(QuarterPlan plan){synchronized(serial){
      try{
        if(closed||plan==null||state!=State.OPEN||pending!=null||quarterPhase!=0
            ||quarterIntent!=null&&quarterIntent!=plan||!binding.equals(plan.binding())
            ||!owner.equals(plan.sourceRoot().owner()))throw new Rejected();
        if(quarterIntent==null)installQuarter(plan);requireQuarterWindow();Book actual=full();requireQuarterWindow();
        if(!(quarterRun==null?plan.sourceMatches(actual):quarterRun.sourceMatches(actual))||!plan.sourceRoot().equals(known))throw new Rejected();
        adoptBaseline(actual);state=State.MAINTENANCE;advanceQuarter();
      }catch(RuntimeException failure){block();throw failure;}
    }}
    /** Same-process continuation only. Reconcile never sends; this method sends only a
     * phase whose original ticket has not yet been attempted, after exact fresh readback. */
    void resumeQuarter(){synchronized(serial){
      try{
        if(closed||!quarterInProgress())throw new Rejected();requireQuarterWindow();
        if(quarterPhase==0){executeQuarter(quarterIntent);return;}
        if(pending!=null){if(!isQuarterTicket(pending)||!attempted||state!=State.UNKNOWN)throw new Rejected();if(!reconcile())return;}
        if(quarterPhase==4||!quarterAuthorityUsable())return;
        if(state!=State.MAINTENANCE)throw new Rejected();advanceQuarter();
      }catch(RuntimeException failure){block();throw failure;}
    }}
    private void advanceQuarter(){
      QuarterPlan plan=quarterIntent;
      while(quarterPhase<4){
        requireQuarterWindow();
        if(pending!=null){if(!isQuarterTicket(pending)||!reconcileIfNeeded())return;if(quarterPhase==4)return;}
        // A positive read-only reconciliation never renews an UNKNOWN/denied arm.
        // Do not select, freeze, or install another phase after that authority is terminal.
        if(!quarterAuthorityUsable())return;
        Book fresh=full();requireQuarterWindow();if(!quarterAuthorityUsable())return;Ticket next;
        if(quarterPhase==0){if(!(quarterRun==null?plan.sourceMatches(fresh):quarterRun.sourceMatches(fresh))||!plan.sourceRoot().equals(known))throw new Rejected();next=plan.prepareTicket();}
        else if(quarterPhase==1){if(!(quarterRun==null?plan.preparedMatches(fresh):quarterRun.preparedMatches(fresh))||!plan.prepareTicket().after().equals(known))throw new Rejected();next=plan.foldTicket();}
        else if(quarterPhase==2){
          if(!(quarterRun==null?plan.foldedMatches(fresh):quarterRun.foldedMatches(fresh))||!plan.foldTicket().after().equals(known)||quarterProof!=null)throw new Rejected();
          quarterProof=quarterRun==null?plan.completeAfterVerifiedRead(fresh,clock):quarterRun.completeAfterVerifiedRead(fresh,clock);next=quarterProof.ticket();
        }else throw new Rejected(); // An attempted COMPLETE can only be read-only reconciled.
        requireQuarterWindow();if(!quarterAuthorityUsable())return;quarterPhase++;pending=next;attempted=false;state=State.PREPARED;send(next);
        if(!reconcileIfNeeded())return;
        if(quarterPhase<4&&state!=State.MAINTENANCE)return;
      }
    }
    void quarter(Root r){
      // A retained preview is already source-bound. Never rotate or replan around it.
      if(quarterInProgress()){requireQuarterWindow();if(quarterPhase==0){state=State.OPEN;executeQuarter(quarterIntent);}else resumeQuarter();return;}
      // Rotate only before creating the immutable intent. Rotation never causes replanning.
      List<Metadata> all=endpoint.control();if(!root(all).equals(r))throw new Rejected();
      if(spreadsheetChars(all)+TRANSITION_RESERVE>METADATA_LIMIT){if(quarterRun!=null)throw new Rejected();state=State.OPEN;rotate(r,QuarterlyRetention.currentQuarterStart(clock.instant()));if(state!=State.OPEN)return;}
      state=State.OPEN;QuarterPlan plan=planQuarter();executeQuarter(plan);
    }
    boolean reconcileIfNeeded(){return pending==null||reconcile();}
    void checkpoint(){synchronized(serial){Instant admitted=openRequired();try{rotate(readOwner(),admitted);}catch(RuntimeException e){block();}}}
    void rotate(Root r,Instant admitted){
      if(!r.owner.equals(owner)||!r.state.equals("OPEN")||pending!=null)throw new Rejected();
      // Read current anchor contents under the same consumed head; the batch competes for its exact next slot.
      List<List<String>> rows=endpoint.related("retention_ledger");List<Metadata> all=endpoint.control();if(!root(all).equals(r)||endpoint.unsupportedStructure()||all.stream().anyMatch(m->Objects.equals(m.sheet(),r.anchor)))throw new Rejected();
      int newId=r.nextSheet;Root after=new Root(r.dataset,r.epoch+1,newId,newId+1,3,owner,"OPEN","ROTATE-"+r.epoch,hash(rows));
      List<Object> req=new ArrayList<>();req.add(guard(r.anchor,"retention_ledger"));req.add(create(receipt(r,after.operation,after.hash,"ROTATE")));
      req.add(Map.of("addSheet",Map.of("properties",Map.of("sheetId",newId,"title","nq_rotation_temporary","gridProperties",Map.of("rowCount",1000,"columnCount",26)))));
      req.add(update(newId,0,rows.size(),0,5,rows));req.add(Map.of("deleteSheet",Map.of("sheetId",r.anchor)));req.add(rename(newId,"retention_ledger"));
      for(Metadata m:all)if(m.key().equals(RECEIPT_KEY))req.add(deleteMetadata(m.id()));req.add(deleteMetadata(r.next));
      req.add(create(new Metadata(2,RECEIPT_KEY,json(Map.of("epoch",after.epoch,"owner",owner,"operation",after.operation,"hash",after.hash,"phase","OPEN")),null)));req.add(updateRoot(after));
      String w=wire(req);int chars=spreadsheetChars(all);if(chars+CONTROL_MAX>METADATA_LIMIT)throw new Rejected();
      int size=w.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(size>limits.requestBytes||(long)fullUpper+4L*size>limits.futureReadBytes)throw new Rejected();
      Ticket t=new Ticket(after.operation,after.hash,null,w,2,after.epoch,newId,owner,after,List.of(new Check("retention_ledger",0,rows.get(0).get(0),List.of())),fullUpper+4*size,rowCounts,null,admitted);
      // Rotation uses a different epoch receipt; positive verification also checks all preserved literal anchor rows.
      if(!admitted.equals(QuarterlyRetention.currentQuarterStart(clock.instant())))throw new Rejected();pending=t;rotationRows=rows;attempted=true;state=State.UNKNOWN;try{endpoint.batch(w);}catch(RuntimeException unknown){return;}
      if(reconcileRotation(t,rows)){ids.put("retention_ledger",newId);rotationRows=null;}
    }
    boolean reconcileRotation(Ticket t,List<List<String>> rows){try{Root actual=root(endpoint.control());if(t.cutoff!=null&&!t.cutoff.equals(QuarterlyRetention.currentQuarterStart(clock.instant())))return false;if(!actual.equals(t.after)||!endpoint.related("retention_ledger").equals(rows))return false;Metadata m=endpoint.control().stream().filter(x->x.id()==2).findFirst().orElseThrow();if(!m.value().equals(json(Map.of("epoch",t.epoch,"owner",owner,"operation",t.operation,"hash",t.hash,"phase","OPEN"))))return false;Book checked=full();if(!root(new ArrayList<>(checked.metadata.values())).equals(t.after)||checked.sheet("retention_ledger").id!=t.anchor||!checked.sheet("retention_ledger").rows.equals(rows)||t.cutoff!=null&&!t.cutoff.equals(QuarterlyRetention.currentQuarterStart(clock.instant())))return false;adoptBaseline(checked);known=actual;pending=null;attempted=false;state=State.OPEN;return true;}catch(RuntimeException e){return false;}}
    void cleanClose(){synchronized(serial){if(state!=State.OPEN)throw new Rejected();Root r=readOwner();Root after=new Root(r.dataset,r.epoch,r.anchor,r.nextSheet,r.next+1,owner,"CLOSED","CLOSE-"+owner,hash(owner));Ticket t=make(r,after.operation,after.hash,null,after,List.of(),List.of(),rowCounts,false);pending=t;attempted=false;state=State.PREPARED;send(t);}}
    private void releaseQuarterState(){quarterIntent=null;quarterProof=null;quarterRun=null;quarterObservedAt=null;quarterWindowClosed=true;pending=null;rotationRows=null;}
    @Override public void close(){synchronized(serial){closed=true;if(quarterPermit!=null){if(state!=State.CLOSED)quarterPermit.closeUnknown();releaseQuarterState();}if(scheduler!=null)scheduler.shutdownNow();if(state!=State.CLOSED)state=State.BLOCKED_MANUAL;}}

  }
}
