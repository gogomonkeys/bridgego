package com.bridge.persistence;

import java.time.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

/** Single-process writer only. One atomic write/readback, never retry unknown writes. */
public final class SheetsSocialStore implements SocialStore {
 private final SheetsGateway gateway; private final Clock clock;
 private Instant cachedQuarter=Instant.MIN; private Data cache; private Instant expires=Instant.MIN,cooldown=Instant.MIN;
 private boolean uncertain;
 private record Data(Map<String,String> names,List<List<String>> rows){}
 public SheetsSocialStore(SheetsGateway gateway){this(gateway,Clock.systemUTC());}
 public SheetsSocialStore(SheetsGateway gateway,Clock clock){this.gateway=Objects.requireNonNull(gateway);this.clock=Objects.requireNonNull(clock);}
 public boolean enabled(){return true;}
 private Data read(boolean fresh){
  if(clock.instant().isBefore(cooldown))throw new IllegalStateException("Social read cooldown");
  if(!fresh&&cache!=null&&clock.instant().isBefore(expires)&&cachedQuarter.equals(QuarterlyRetention.currentQuarterStart(clock.instant())))return cache;
  try{
   var tables=gateway.readBatch(List.of("accounts","erasure_ledger","retention_ledger","social"));
   var retention=new RetentionLedger(tables.get("retention_ledger"));
   if(!retention.complete()||!retention.cutoff().equals(QuarterlyRetention.currentQuarterStart(clock.instant())))throw new IllegalStateException("Quarter cleanup pending");
   var accounts=tables.get("accounts");AccountProfileRows.validateTable(accounts);
   var erased=new ErasureLedger(tables.get("erasure_ledger"));var names=new HashMap<String,String>();
   for(var r:accounts.subList(1,accounts.size())){
    if(erased.contains("ACCOUNT",r.get(0)))throw new SheetsGateway.Conflict();
    names.put(r.get(0),r.size()>2?r.get(2):"");
   }
   SocialRows.validate(tables.get("social"),names.keySet());
   cache=new Data(Map.copyOf(names),tables.get("social").stream().map(List::copyOf).toList());expires=clock.instant().plusSeconds(30);cachedQuarter=retention.cutoff();return cache;
  }catch(SheetsGateway.Failure f){cache=null;if(f.status==429)cooldown=clock.instant().plusMillis(Math.max(60000,f.retryAfterMillis));throw f;}
 }
 private static String code(Data d,String id){return d.rows.stream().filter(r->r.get(0).equals("CODE")&&r.get(1).equals(id)).map(r->r.get(3)).findFirst().orElse(null);}
 private static void account(Data d,String id){if(!d.names.containsKey(id)||d.names.get(id).isBlank())throw new IllegalArgumentException("Registered account required");}
 private static String target(Data d,String code){return d.rows.stream().filter(r->r.get(0).equals("CODE")&&r.get(3).equals(code)).map(r->r.get(1)).findFirst().orElseThrow(()->new SocialStore.Rejected("FRIEND_NOT_FOUND"));}
 private Snapshot view(Data d,String id){
  account(d,id);var friends=new ArrayList<Person>();var incoming=new ArrayList<Request>();var outgoing=new ArrayList<Request>();
  for(var r:d.rows)if(r.get(0).equals("EDGE")&&(r.get(1).equals(id)||r.get(2).equals(id))){
   var other=r.get(1).equals(id)?r.get(2):r.get(1);var c=code(d,other);if(c==null)throw new SheetsGateway.Conflict();
   if(r.get(3).equals("FRIEND"))friends.add(new Person(other,c,d.names.get(other)));
   else (r.get(1).equals(id)?outgoing:incoming).add(new Request(r.get(4),other,c,d.names.get(other)));
  }
  friends.sort(Comparator.comparing(Person::code));incoming.sort(Comparator.comparing(Request::requestId));outgoing.sort(Comparator.comparing(Request::requestId));
  return new Snapshot(code(d,id),d.names.get(id),List.copyOf(friends),List.copyOf(incoming),List.copyOf(outgoing));
 }
 public synchronized Snapshot snapshot(String id){return view(read(false),id);}
 private void save(Data before,List<List<String>> rows){
  if(uncertain)throw new IllegalStateException("Prior social write requires operator review");
  if(rows.size()>SocialRows.MAX_ROWS)throw new SocialStore.Rejected("FRIEND_CAPACITY");
  SocialRows.validate(rows,before.names.keySet());cache=null;expires=Instant.MIN;
  // Mark before dispatch; any ambiguous failure stays latched across all mutations in this process.
  uncertain=true;
  gateway.replaceSocial(rows);
  Data after=read(true);
  if(!after.rows.equals(rows))throw new SheetsGateway.Conflict();
  uncertain=false;
 }
 public synchronized String ensureCode(String id){
  Data d=read(true);account(d,id);String c=code(d,id);if(c!=null)return c;
  byte[] bytes=new byte[8];var random=new SecureRandom();do{random.nextBytes(bytes);c=HexFormat.of().withUpperCase().formatHex(bytes);}while(d.rows.stream().anyMatch(r->r.get(0).equals("CODE")&&r.get(3).equals(HexFormat.of().withUpperCase().formatHex(bytes))));
  var rows=new ArrayList<>(d.rows);rows.add(List.of("CODE",id,"code",c,"1"));save(d,rows);return c;
 }
 private Snapshot mutate(Data d,String id,String actionId,String signature,Consumer<List<List<String>>> edit){
  if(!SocialRows.uuid(actionId))throw new IllegalArgumentException("Action identifier required");
  account(d,id);if(code(d,id)==null)throw new IllegalArgumentException("Friend code not initialized");
  String hash=FinalMatchEnvelope.hash(signature);
  var old=d.rows.stream().filter(r->r.get(0).equals("OP")&&r.get(1).equals(id)&&r.get(2).equals(actionId)).findFirst();
  if(old.isPresent()){if(!old.get().get(3).equals(hash))throw new IllegalArgumentException("Action identifier reused");return view(d,id);}
  var rows=new ArrayList<>(d.rows);edit.accept(rows);rows.add(List.of("OP",id,actionId,hash,"1"));save(d,rows);return view(cache,id);
 }
 public synchronized Snapshot request(String id,String targetCode,String actionId){
  Data d=read(true);account(d,id);String other=target(d,targetCode);account(d,other);
  if(id.equals(other))throw new SocialStore.Rejected("FRIEND_SELF");
  return mutate(d,id,actionId,"request:"+targetCode,rows->{
   var existing=rows.stream().filter(r->pair(r,id,other)).findFirst();
   if(existing.isPresent())return; // Crossed requests still require explicit acceptance.
   long own=rows.stream().filter(r->r.get(0).equals("EDGE")&&(r.get(1).equals(id)||r.get(2).equals(id))).count();
   long theirs=rows.stream().filter(r->r.get(0).equals("EDGE")&&(r.get(1).equals(other)||r.get(2).equals(other))).count();
   if(own>=100||theirs>=100)throw new SocialStore.Rejected("FRIEND_LIMIT");
   if(rows.stream().anyMatch(r->r.get(0).equals("EDGE")&&r.get(4).equals(actionId)))throw new SocialStore.Rejected("FRIEND_ACTION_CONFLICT");
   rows.add(List.of("EDGE",id,other,"PENDING",actionId));
  });
 }
 private static boolean pair(List<String> r,String a,String b){return r.get(0).equals("EDGE")&&(r.get(1).equals(a)&&r.get(2).equals(b)||r.get(1).equals(b)&&r.get(2).equals(a));}
 public synchronized Snapshot respond(String id,String requestId,boolean accept,String actionId){
  return mutate(read(true),id,actionId,"respond:"+requestId+":"+accept,rows->{
   var edge=rows.stream().filter(r->r.get(0).equals("EDGE")&&r.get(4).equals(requestId)&&r.get(2).equals(id)&&r.get(3).equals("PENDING")).findFirst().orElseThrow(()->new SocialStore.Rejected("FRIEND_REQUEST_NOT_FOUND"));
   rows.remove(edge);if(accept)rows.add(List.of("EDGE",edge.get(1),edge.get(2),"FRIEND",edge.get(4)));
  });
 }
 public synchronized Snapshot remove(String id,String friendCode,String actionId){
  Data d=read(true);String other=target(d,friendCode);
  return mutate(d,id,actionId,"remove:"+friendCode,rows->rows.removeIf(r->pair(r,id,other)));
 }
}
