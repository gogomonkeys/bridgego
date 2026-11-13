package com.bridge.quartercore;
import com.sun.net.httpserver.*;import java.net.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.security.*;import java.nio.charset.StandardCharsets;import java.util.function.Consumer;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Maintenance-only fixed script. No Spring, player login, rooms, arbitrary records or clock endpoint. */
final class SyntheticQuarterRunnerLauncher implements AutoCloseable {
 interface Driver extends AutoCloseable {void arm();void run();String verifiedToken();default byte[] verifiedArtifact(){return null;}void close();}
 enum Status { INERT,READING,WAITING_APPROVAL,RUNNING,VERIFIED,REVIEW,STOPPED }
 private final Map<String,Object> scope;private final Driver driver;private final Clock real;private final Consumer<String> privateLog;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"synthetic-quarter-once");t.setDaemon(true);return t;});
 private final ExecutorService httpWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"synthetic-quarter-health");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 private static final com.fasterxml.jackson.databind.ObjectMapper REQUEST_JSON=new com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxDocumentLength(4096).maxNestingDepth(3).maxStringLength(256).build()).build()).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private Status status=Status.INERT;private boolean started,attempted;private volatile boolean closed;private String nonce,digest;private Instant expires,last;private long deadline;private int bad;private HttpServer server;
 SyntheticQuarterRunnerLauncher(Map<String,Object> fixedScope,Driver driver,Clock real,Consumer<String> privateLog){this.scope=Map.copyOf(fixedScope);this.driver=Objects.requireNonNull(driver);this.real=Objects.requireNonNull(real);this.privateLog=Objects.requireNonNull(privateLog);}
 synchronized Status status(){return status;}
 void start(){synchronized(this){if(started||closed)throw new Rejected();started=true;status=Status.READING;}worker.submit(()->{try{driver.arm();synchronized(this){if(closed)return;last=real.instant();expires=last.plusSeconds(300);deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();byte[] b=new byte[32];new SecureRandom().nextBytes(b);nonce=HexFormat.of().formatHex(b);Arrays.fill(b,(byte)0);digest=FinalMatchEnvelope.hash(NativeCommitJournal.canonical(List.of("synthetic-quarter-runner-v1",scope,expires.toString(),nonce)));status=Status.WAITING_APPROVAL;privateLog.accept(json(Map.of("event","SYNTHETIC_RUNNER_PRIVATE_APPROVAL","scope",scope,"expires",expires.toString(),"previewDigest",digest,"privateNonce",nonce)));}}catch(Throwable e){review();}});}
 synchronized boolean approve(String bearer,String exact){
  if(closed||attempted||status!=Status.WAITING_APPROVAL)return false;Instant now=real.instant();
  if(now.isBefore(last)||!now.isBefore(expires)||System.nanoTime()>=deadline||bad>=8){status=Status.REVIEW;nonce=null;return false;}last=now;
  if(bearer==null||exact==null||!MessageDigest.isEqual(nonce.getBytes(StandardCharsets.US_ASCII),bearer.getBytes(StandardCharsets.US_ASCII))||!digest.equals(exact)){bad++;return false;}
  attempted=true;nonce=null;status=Status.RUNNING;worker.submit(()->{try{driver.run();String token=driver.verifiedToken();synchronized(this){if(closed)return;status=Status.VERIFIED;}privateLog.accept(json(Map.of("event","SYNTHETIC_RUNNER_VERIFIED","afterToken",token)));}catch(Throwable e){review();}});return true;
 }
 private void review(){synchronized(this){if(!closed){status=Status.REVIEW;nonce=null;privateLog.accept("SYNTHETIC_RUNNER_REVIEW_NO_RETRY");}}try{driver.close();}catch(RuntimeException ignored){}}
 void health(int port)throws java.io.IOException{healthLimits();server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),16);server.setExecutor(httpWorker);server.createContext("/",this::request);server.start();}
 /** Keep this maintenance host self-contained: legacy SyntheticC entrypoints are intentionally
  * excluded from the deployable jar. Set bounds before the JDK server is initialized. */
 private static void healthLimits(){
  Map.ofEntries(Map.entry("jdk.httpserver.maxConnections","16"),Map.entry("sun.net.httpserver.maxIdleConnections","0"),Map.entry("sun.net.httpserver.idleInterval","2"),Map.entry("sun.net.httpserver.clockTick","250"),Map.entry("sun.net.httpserver.maxReqTime","2"),Map.entry("sun.net.httpserver.maxRspTime","2"),Map.entry("sun.net.httpserver.timerMillis","250"),Map.entry("sun.net.httpserver.maxReqHeaders","64"),Map.entry("sun.net.httpserver.maxReqHeaderSize","8192"),Map.entry("sun.net.httpserver.drainAmount","0"),Map.entry("sun.net.httpserver.debug","false"),Map.entry("sun.net.httpserver.disableHostValidation","false")).forEach(System::setProperty);
  java.util.logging.Logger.getLogger("com.sun.net.httpserver").setLevel(java.util.logging.Level.OFF);
 }
 private void request(HttpExchange x)throws java.io.IOException{byte[] bytes=null;try{String path=x.getRequestURI().toString(),method=x.getRequestMethod();int code=404;String text="not_available";
  if(method.equals("GET")&&Set.of("/health/live","/health/route").contains(path)){code=closed?503:200;text="maintenance_only";}
  else if(method.equals("GET")&&(path.equals("/")||path.equals("/health/ready"))){code=503;text="business_closed";}
  else if(method.equals("GET")&&path.equals("/status")){code=200;text=status().name();}
  else if(method.equals("GET")&&path.equals("/fixed-e/verified-baseline.json")&&status()==Status.VERIFIED){byte[] artifact=driver.verifiedArtifact();if(artifact!=null&&artifact.length<=FixedEVerifiedArtifact.MAX_BYTES){x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Content-Disposition","attachment; filename=\"fixed-e-verified-baseline.json\"");x.sendResponseHeaders(200,artifact.length);x.getResponseBody().write(artifact);return;}}
  else if(method.equals("POST")&&path.equals("/execute")){code=403;var h=x.getRequestHeaders();
   if(!h.containsKey("Origin")&&!h.containsKey("Transfer-Encoding")&&!h.containsKey("Content-Encoding")&&h.getOrDefault("Authorization",List.of()).size()==1&&h.getOrDefault("Content-Type",List.of()).equals(List.of("application/json"))){bytes=x.getRequestBody().readNBytes(4097);if(bytes.length<=4096){try{var n=REQUEST_JSON.readTree(bytes);if(n!=null&&n.isObject()&&n.size()==1&&n.path("previewDigest").isTextual()){String auth=h.getFirst("Authorization");if(auth.startsWith("Bearer "))code=approve(auth.substring(7),n.path("previewDigest").textValue())?202:409;}}catch(Exception ignored){}}}if(code==202)text="accepted_once";
  }
  byte[] out=(text+"\n").getBytes(StandardCharsets.US_ASCII);x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("Content-Type","text/plain; charset=us-ascii");x.getResponseHeaders().set("Connection","close");x.sendResponseHeaders(code,out.length);x.getResponseBody().write(out);
 }finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);x.close();}}
 int port(){return server.getAddress().getPort();}
 public void close(){synchronized(this){if(closed)return;closed=true;status=Status.STOPPED;nonce=null;}if(server!=null)server.stop(0);worker.shutdownNow();httpWorker.shutdownNow();driver.close();}
}
