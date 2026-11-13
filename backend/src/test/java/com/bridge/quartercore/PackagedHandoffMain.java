package com.bridge.quartercore;
import java.time.*;import java.util.*;import java.nio.file.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Only added to a derived local test JAR. Never included in the server release. */
public final class PackagedHandoffMain {
 static final GoogleSheetsBinding B=new GoogleSheetsBinding("1hm2CKBAlbTMRVHPZ-7_nTcjGbdb2PNvCgn9Uq_4YY2s","b5fc0d0a-8e4a-45e1-b223-1c1b18b2434b","bridgego-restricted-test","bridgego-sheets@bridgego-513805.iam.gserviceaccount.com");
 static final class Disk {
  final Path path;Book book;final boolean unknown;int writes;
  Disk(Path path,boolean unknown)throws Exception{this.path=path;this.unknown=unknown;book=PackagedHandoffCodec.load(path);}
  synchronized Book read(){return book.copy();}
  synchronized void batch(String wire){writes++;if(unknown)throw new Unknown();Book after=simulate(book,wire);try{Path temporary=path.resolveSibling(path.getFileName()+".tmp");PackagedHandoffCodec.save(temporary,after);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);book=after;}catch(Exception e){throw new Unknown();}}
 }
 static PlannedMaintenanceStartupFactory.Channels channels(Disk disk){return new PlannedMaintenanceStartupFactory.Channels(){
  NarrowSheetsTransport endpoint(GoogleDispatchAuthority auth){return new NarrowSheetsTransport(){
   public Book snapshot(){return disk.read();}public List<Metadata> control(){return new ArrayList<>(disk.read().metadata.values());}public List<List<String>> related(String tab){return disk.read().tables().get(tab);}public boolean unsupportedStructure(){return false;}
   public void batch(String wire){var d=auth.beforeDispatch(B.path()+":batchUpdate",wire);try{disk.batch(wire);d.acknowledged();}catch(RuntimeException e){d.unknown();throw e;}}
  };}
  public PlannedMaintenanceStartupFactory.Channel ordinary(JournalSessionAuthority a){var slot=new JournalAuthoritySlot(a);var h=new JournalGoogleFactory.Handle(endpoint(slot),()->{},slot,B);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}
  public PlannedMaintenanceStartupFactory.Channel quarter(JournalQuarterAuthority a){var cap=new QuarterAuthorityChannel(B,a);var h=new JournalQuarterGoogleFactory.Handle(endpoint(cap),()->{},cap,B);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}
 };}
 public static void main(String[] args)throws Exception{
  if(args.length!=4)throw new IllegalArgumentException("local fixture arguments");
  Path file=Path.of(args[0]);Clock clock=Clock.fixed(Instant.parse(args[1]),ZoneOffset.UTC);int port=Integer.parseInt(args[2]);String mode=args[3];
  var retention=new QuarterRetentionPolicy.Configuration(B,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"local-fixture-retention");
  var policy=new CleanClosedRestartAssembly.Policy(B,mode.equals("invalidcontract")?"not-reviewed":NativeRestartContract.PROTOCOL_IDENTITY,"local-fixture-source",retention);
  var contract=NativeRestartContract.fixedReviewedBuild(policy,"https://fixture.invalid","fixture-client");
  var disk=new Disk(file,mode.equals("unknown"));
  var io=new NativeRestartRuntimeAssembly.IO(){public Book readNative(){return disk.read();}public PlannedMaintenanceStartupFactory.Channels channels(){return PackagedHandoffMain.channels(disk);}};
  var runtime=new NativeRestartRuntimeAssembly(contract,io,clock);
  System.setProperty("server.port",Integer.toString(port));System.setProperty("server.address","127.0.0.1");System.setProperty("BRIDGE_PRIVACY_CONTACT","fixture@example.invalid");System.setProperty("BRIDGE_RETENTION_DESCRIPTION","local-only fixture");System.setProperty("BRIDGE_TEST_ALLOWED_ORIGIN","https://fixture.invalid");
  var context=PlannedCutoverLauncher.runRestart(runtime);
  // Production coordinator derives its clock from the same preinstalled gate.
  var coordinator=context.getBean(com.bridge.persistence.QuarterMaintenanceCoordinator.class);
  coordinator.tick();
  System.out.println("FIXTURE_GATE state="+runtime.gate().state()+" reason="+runtime.gate().reason()+" coordinator="+coordinator.status());
  if(mode.equals("busy")&&runtime.status()==NativeRestartRuntimeAssembly.Status.ATTACHED)runtime.gate().registerMatch("packaged-busy-match");
  System.out.println("PACKAGED_HANDOFF_STARTED pid="+ProcessHandle.current().pid()+" status="+runtime.status()+" writes="+disk.writes);
 }
}
