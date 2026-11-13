package com.bridge.quartercore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Maintenance-only one-shot launcher. Public responses never contain nonce, cells or credentials.
 * Approval nonce is disclosed only in the explicitly private operator log. No sheet arm writes. */
final class SyntheticCLauncher implements AutoCloseable {
  enum Status { INERT, READING, WAITING_APPROVAL, RUNNING, VERIFIED, REVIEW, STOPPED }
  record Session(NarrowSheetsTransport transport,Runnable release) implements AutoCloseable {public void close(){release.run();}}
  interface PostVerification extends AutoCloseable { Book run(Book verifiedQuarterClosed,JournalQuarterAuthority completed); default void close(){} }
  interface Handles { Session readOnly(); Session quarter(JournalQuarterAuthority grant); }
  private final SyntheticCRelease release;private final Handles handles;private final Clock clock;private final Consumer<String> privateLog;private final Runnable requireSavedBefore;private final PostVerification postVerification;private Session current;
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"synthetic-c-once");t.setDaemon(true);return t;});
  private final ExecutorService httpWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"synthetic-c-health");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  private Status status=Status.INERT;private boolean started,approved;private volatile boolean closed;private String nonce,digest,operation,owner;private Instant expires,quarter;
  private long approvalDeadline;private Book baseline;private HttpServer server;private int badApprovals;
  SyntheticCLauncher(SyntheticCRelease release,Handles handles,Clock clock,Consumer<String> privateLog,Runnable requireSavedBefore,PostVerification postVerification){this.release=Objects.requireNonNull(release);this.handles=Objects.requireNonNull(handles);this.clock=Objects.requireNonNull(clock);this.privateLog=Objects.requireNonNull(privateLog);this.requireSavedBefore=Objects.requireNonNull(requireSavedBefore);this.postVerification=Objects.requireNonNull(postVerification);}
  synchronized Status status(){return status;}
  synchronized void start(){if(started||closed)throw new Rejected();started=true;release.require();requireSavedBefore.run();status=Status.READING;worker.submit(this::prepare);}
  private Session install(Session session){synchronized(this){if(!closed){current=session;return session;}}session.close();throw new Rejected();}
  private Book full(Session h,String label){try(var scope=h.transport().operation(label,null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){Book b=h.transport().snapshot();if(h.transport().unsupportedStructure())throw new Rejected();NativeCommitJournal.verify(release.require().manualBinding(),b,clock);return b;}}
  private void prepare(){try{
    Book b;try(Session reader=install(handles.readOnly())){b=full(reader,"c-preview");}
    release.verify(b);if(JournalQuarterProtocol.inspect(release.require().manualBinding(),b,clock).state()!=JournalQuarterProtocol.RecoveryState.READY_DUE)throw new Rejected();
    synchronized(this){if(closed)return;baseline=b.copy();operation=UUID.randomUUID().toString();owner="c-"+UUID.randomUUID();quarter=QuarterlyRetention.currentQuarterStart(clock.instant());expires=clock.instant().plusSeconds(300);approvalDeadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();
      byte[] random=new byte[32];new SecureRandom().nextBytes(random);nonce=HexFormat.of().formatHex(random);Arrays.fill(random,(byte)0);
      var preview=new TreeMap<String,Object>();preview.put("kind","SYNTHETIC_C_JOURNAL_QUARTER");preview.put("spreadsheet",release.binding.spreadsheetId());preview.put("dataset",release.binding.datasetId());preview.put("revision",release.revision);preview.put("run",release.run);preview.put("baseline",release.baselineToken);preview.put("operation",operation);preview.put("owner",owner);preview.put("quarter",quarter.toString());preview.put("expires",expires.toString());preview.put("phases",5);preview.put("postVerification","fixed-synthetic-identity-profile-formal-casual-clean-close-v1");preview.put("beforeArtifactSha256",release.beforeArtifactSha);
      digest=FinalMatchEnvelope.hash(NativeCommitJournal.canonical(preview));status=Status.WAITING_APPROVAL;
      privateLog.accept(NativeSheetsModel.json(Map.of("event","C_PRIVATE_APPROVAL","preview",preview,"previewDigest",digest,"privateNonce",nonce)));
    }
  }catch(RuntimeException|Error failure){review("PREVIEW_UNVERIFIED");}}
  /** Trusted operator submits the exact fresh preview within the already authorized restorable C scope. */
  synchronized boolean approve(String bearer,String expectedDigest){
    if(closed||status!=Status.WAITING_APPROVAL||approved)return false;
    if(!clock.instant().isBefore(expires)||System.nanoTime()>=approvalDeadline||!QuarterlyRetention.currentQuarterStart(clock.instant()).equals(quarter)){status=Status.REVIEW;return false;}
    if(bearer==null||expectedDigest==null||!MessageDigest.isEqual(nonce.getBytes(StandardCharsets.US_ASCII),bearer.getBytes(StandardCharsets.US_ASCII))||!digest.equals(expectedDigest)){badApprovals++;return false;}
    approved=true;nonce=null;status=Status.RUNNING;worker.submit(this::execute);return true;
  }
  private void execute(){try{
    var binding=release.require();var grant=JournalQuarterAuthority.provision(binding.manualBinding(),baseline,owner,operation,(b,q,token,o,id)->{
      synchronized(this){if(closed||!approved||status!=Status.RUNNING||!b.equals(binding.manualBinding())||!quarter.equals(q)||!release.baselineToken.equals(token)||!owner.equals(o)||!operation.equals(id)||!clock.instant().isBefore(expires)||System.nanoTime()>=approvalDeadline)throw new Rejected();}
    },clock,Duration.between(clock.instant(),expires));
    Book quarterAfter;int phases;
    try(Session armed=install(handles.quarter(grant))){
      Book fresh=full(armed,"c-before-dispatch");release.verify(fresh);
      var protocol=new JournalQuarterProtocol(binding.manualBinding(),armed.transport(),clock,owner,grant);protocol.start();
      if(protocol.status()!=JournalQuarterProtocol.Status.VERIFIED)throw new Rejected();
      Book after=full(armed,"c-final-readback");if(JournalQuarterProtocol.inspect(binding.manualBinding(),after,clock).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();
      quarterAfter=after;phases=protocol.dispatched();
    }
    synchronized(this){if(closed)throw new Rejected();}
    Book finalAfter=postVerification.run(quarterAfter.copy(),grant);
    NativeCommitJournal.verify(binding.manualBinding(),finalAfter,clock);
    if(!NarrowQuarterPoc.root(new ArrayList<>(finalAfter.metadata.values())).state().equals("CLOSED"))throw new Rejected();
    synchronized(this){if(closed)throw new Rejected();status=Status.VERIFIED;}
    privateLog.accept(NativeSheetsModel.json(Map.of("event","C_VERIFIED","operation",operation,"afterToken",JournalSessionAuthority.fullToken(finalAfter),"phases",phases,"ordinaryChecks","completed")));
  }catch(RuntimeException|Error failure){review("EXECUTION_UNCERTAIN");readOnlyReview();}}
  private void readOnlyReview(){if(closed)return;try(Session reader=install(handles.readOnly())){
    Book observed=full(reader,"c-review-only");privateLog.accept(NativeSheetsModel.json(Map.of("event","C_READ_ONLY_REVIEW","token",JournalSessionAuthority.fullToken(observed),"classification",JournalQuarterProtocol.inspect(release.require().manualBinding(),observed,clock).state().name())));
  }catch(RuntimeException|Error unknown){privateLog.accept("C_REVIEW_UNAVAILABLE");}}
  private synchronized void review(String label){if(!closed)status=Status.REVIEW;privateLog.accept(label);}
  synchronized void health(int port)throws java.io.IOException {
    if(server!=null||port<0||port>65535)throw new Rejected();SyntheticCMain.healthLimits();server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),16);server.setExecutor(httpWorker);
    server.createContext("/",exchange->{try{
      String path=exchange.getRequestURI().toString(),method=exchange.getRequestMethod();int code=404;
      if(path.equals("/")&&method.equals("GET"))code=503;
      else if((path.equals("/health/live")||path.equals("/health/route"))&&method.equals("GET"))code=closed?503:200;
      else if(path.equals("/health/ready")&&method.equals("GET"))code=503;
      else if(path.equals("/execute")&&method.equals("POST")){
        code=403;
        var headers=exchange.getRequestHeaders();
        if(!headers.containsKey("Origin")&&!headers.containsKey("Transfer-Encoding")&&headers.getOrDefault("Authorization",List.of()).size()==1&&headers.getOrDefault("Content-Type",List.of()).equals(List.of("application/json"))){
          byte[] bytes=exchange.getRequestBody().readNBytes(4097);
          if(bytes.length<=4096){try{var body=JSON.readTree(bytes);var fields=new HashSet<String>();body.fieldNames().forEachRemaining(fields::add);
            String auth=headers.getFirst("Authorization");if(fields.equals(Set.of("previewDigest"))&&body.path("previewDigest").isTextual()&&auth.startsWith("Bearer "))code=approve(auth.substring(7),body.path("previewDigest").asText())?202:409;
          }catch(Exception rejected){code=400;}}
        }
      }
      byte[] response=(code==202?"accepted_once\n":code==200?"maintenance_only\n":code==503?"business_closed\n":"not_available\n").getBytes(StandardCharsets.US_ASCII);
      exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Connection","close");exchange.getResponseHeaders().set("Content-Type","text/plain; charset=us-ascii");exchange.sendResponseHeaders(code,response.length);exchange.getResponseBody().write(response);
    }finally{exchange.close();}});server.start();
  }
  synchronized int port(){return server.getAddress().getPort();}
  @Override public void close(){
    Session active;HttpServer http;
    synchronized(this){if(closed)return;closed=true;status=Status.STOPPED;nonce=null;active=current;http=server;}
    if(http!=null)http.stop(0);if(active!=null)try{active.close();}catch(RuntimeException ignored){}
    try{postVerification.close();}catch(RuntimeException ignored){}worker.shutdownNow();httpWorker.shutdownNow();
  }
}
