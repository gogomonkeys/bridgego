package com.bridge.quartercore;
import java.nio.file.*;import java.io.*;import java.time.*;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Derived local fixture ONLY. Reads a saved artifact; all Google exchanges are local memory/file. */
public final class PackagedEHandoffMain {
 static final GoogleSheetsBinding B=FixedERestartClock.binding();
 static byte[] fullResource(com.fasterxml.jackson.databind.JsonNode projection)throws Exception{
  var n=(com.fasterxml.jackson.databind.node.ObjectNode)projection.deepCopy();n.put("spreadsheetId",B.spreadsheetId());n.put("spreadsheetUrl","https://docs.google.com/spreadsheets/d/"+B.spreadsheetId()+"/edit");n.put("commentsViewMode","COMMENTS_VIEW_MODE_INCLUDED");n.putObject("properties").put("title","Saved normalized E local fixture").put("locale","en_US").put("timeZone","Asia/Taipei");int i=0;
  for(var s:n.path("sheets")){((com.fasterxml.jackson.databind.node.ObjectNode)s.path("properties")).put("index",i++).put("sheetType","GRID");var d=(com.fasterxml.jackson.databind.node.ObjectNode)s.path("data").get(0);var rows=d.putArray("rowMetadata");for(int j=0;j<1000;j++)rows.addObject();var cols=d.putArray("columnMetadata");for(int j=0;j<26;j++)cols.addObject();for(var r:d.path("rowData"))for(var c:r.path("values"))if(c.has("userEnteredValue"))((com.fasterxml.jackson.databind.node.ObjectNode)c).set("effectiveValue",c.get("userEnteredValue").deepCopy());}
  return JSON.writeValueAsBytes(n);
 }
 static Book load(Path file)throws Exception{var n=JSON.readTree(Files.readAllBytes(file));if(n.has("nativeProjection"))n=n.get("nativeProjection");return StrictNativeSheetsResource.decodeFull(new ByteArrayInputStream(fullResource(n)),System.nanoTime()+20_000_000_000L,B.manualBinding());}
 static final class Disk {
  Book b;final Path path;final String mode;int writes;Disk(Path p,String mode)throws Exception{path=p;this.mode=mode;b=load(p);}
  synchronized byte[] resource()throws Exception{try(var channel=java.nio.channels.FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){b=load(path);return fullResource(JSON.valueToTree(b.nativeValue()));}}
  synchronized void write(String wire)throws Exception{try(var channel=java.nio.channels.FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){b=load(path);writes++;b=simulate(b,wire);Path t=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(t,json(b.nativeValue()));Files.move(t,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);if(mode.equals("unknown"))throw new IOException("LOCAL UNKNOWN APPLIED");}}
  BatchedSheetsTransport transport(GoogleDispatchAuthority authority){return new BatchedSheetsTransport(443,new SharedSheetsBudget(System::nanoTime,54,54,Duration.ofMinutes(1)),new BatchedSheetsTransport.Exchange(){GoogleDispatchAuthority.Dispatch receipt;
   public InputStream execute(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f)throws Exception{
    if(r.deadline()<=System.nanoTime())throw new AssertionError("deadline");
    if(!r.write()){if(authority!=null)authority.requireReadSession(B);if(!r.target().equals(B.fullTarget()))throw new AssertionError("wrong read target");return new ByteArrayInputStream(resource());}
    if(authority==null||!r.target().equals(B.path()+":batchUpdate"))throw new AssertionError("unapproved write");String wire=new String(r.body(),java.nio.charset.StandardCharsets.UTF_8);receipt=authority.beforeDispatch(r.target(),wire);write(wire);return new ByteArrayInputStream(JSON.writeValueAsBytes(Map.of("spreadsheetId",B.spreadsheetId(),"replies",Collections.nCopies(JSON.readTree(wire).path("requests").size(),Map.of()))));
   }
   public void completed(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f,boolean ok){if(r.write()&&receipt!=null){if(ok)receipt.acknowledged();else receipt.unknown();}}
   public void abandoned(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f){if(receipt!=null)receipt.unknown();}
  },B.manualBinding());}
  Book read(){try(var t=transport(null);var scope=t.operation("local-E-read",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){return t.snapshot();}}
  PlannedMaintenanceStartupFactory.Channels channels(){return new PlannedMaintenanceStartupFactory.Channels(){public PlannedMaintenanceStartupFactory.Channel ordinary(JournalSessionAuthority a){var slot=new JournalAuthoritySlot(a);var h=new JournalGoogleFactory.Handle(transport(slot),()->{},slot,B);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}public PlannedMaintenanceStartupFactory.Channel quarter(JournalQuarterAuthority a){throw new AssertionError("Fixed Q1 restart must not delete");}};}
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=4)throw new IllegalArgumentException();run(args,FixedEBaselineBundle.reviewedSnapshot(load(Path.of(args[0]))));
 }
 static void run(String[] args,FixedEBaselineBundle bundle)throws Exception{
  if(args.length!=4)throw new IllegalArgumentException();Path artifact=Path.of(args[0]),state=Path.of(args[1]);String mode=args[3];Book baseline=load(artifact);if(bundle.completedPairs(baseline)!=0)throw new Rejected();
  if(mode.equals("pending")){var r=NarrowQuarterPoc.root(new ArrayList<>(baseline.metadata.values()));var n=new NarrowQuarterPoc.Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,r.owner(),"PENDING","local-pending",NarrowQuarterPoc.hash("pending"));var plan=NativeCommitJournal.append(B.manualBinding(),baseline,QuarterPlan.phaseWire(r,n,List.of()),Clock.fixed(FixedERestartClock.BUSINESS_INSTANT,ZoneOffset.UTC));Files.writeString(state,json(plan.expected().nativeValue()));mode="normal";}
  GoogleSheetsBinding selected=mode.equals("formal")?new GoogleSheetsBinding(B.spreadsheetId(),B.datasetId(),"bridgego-managed",B.serviceAccountIssuer()):B;
  var retention=new QuarterRetentionPolicy.Configuration(selected,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"local-E-only");var contract=NativeRestartContract.fixedReviewedBuild(new CleanClosedRestartAssembly.Policy(selected,NativeRestartContract.PROTOCOL_IDENTITY,"local-E-only",retention),"https://fixture.invalid","fixture-client");
  var disk=new Disk(state,mode);var io=new NativeRestartRuntimeAssembly.IO(){public Book readNative(){return disk.read();}public PlannedMaintenanceStartupFactory.Channels channels(){return disk.channels();}};
  System.setProperty("server.port",args[2]);System.setProperty("server.address","127.0.0.1");var context=FixedERestartLauncher.run(bundle,contract,io);context.getBean(com.bridge.persistence.QuarterMaintenanceCoordinator.class).tick();
  if(mode.equals("busy"))context.getBean(com.bridge.persistence.QuarterMaintenanceGate.class).registerMatch("local-E-busy");
  System.out.println("PACKAGED_E_STARTED pid="+ProcessHandle.current().pid()+" writes="+disk.writes+" realSecurityUTC="+Instant.now());
 }
}
