package com.bridge.persistence;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static com.bridge.persistence.NativeSheetsModel.*;
import static com.bridge.persistence.NarrowQuarterPoc.*;

/** One bounded full GET and a private non-sensitive summary. No arm, service or write capability. */
final class ControlReadOnlyHost implements AutoCloseable {
  enum Stage { RESOURCE, CREDENTIAL, FULL_READ, SUMMARY, COMPLETE }
  private final Supplier<ManagedGoogleSheetsFactory.Handle> handles;
  private final Runnable resources;private final Consumer<String> log;
  private final AtomicBoolean started=new AtomicBoolean();private final Object publication=new Object();
  private volatile boolean stopping;private final AtomicReference<ManagedGoogleSheetsFactory.Handle> current=new AtomicReference<>();
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"control-readonly");t.setDaemon(true);return t;});
  private final ExecutorService healthWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"control-readonly-health");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  private HttpServer health;
  ControlReadOnlyHost(Supplier<ManagedGoogleSheetsFactory.Handle> handles,Runnable resources,Consumer<String> log){this.handles=Objects.requireNonNull(handles);this.resources=Objects.requireNonNull(resources);this.log=Objects.requireNonNull(log);}
  static IllegalStateException rejected(){return new IllegalStateException("CONTROL_READONLY_REJECTED");}
  void start(){if(stopping||!started.compareAndSet(false,true))throw rejected();worker.submit(this::run);}
  private void run(){
    Stage stage=Stage.RESOURCE;String status="BLOCKED";ManagedGoogleSheetsFactory.Handle handle=null;
    try{
      resources.run();if(stopping)throw rejected();stage=Stage.CREDENTIAL;handle=handles.get();if(handle==null)throw rejected();current.set(handle);if(stopping)throw rejected();
      stage=Stage.FULL_READ;Book book;
      try(var scope=handle.transport.operation("control-readonly",null,BatchedSheetsTransport.ALL,BatchedSheetsTransport.MAX_DEADLINE)){book=handle.transport.snapshot();}
      stage=Stage.SUMMARY;String summary=summary(book);
      synchronized(publication){if(stopping)throw rejected();log.accept("CONTROL_READONLY_SNAPSHOT "+summary);}
      stage=Stage.COMPLETE;status="COMPLETE";
    }catch(Exception|LinkageError failure){/* No raw exception, cells, metadata values or credentials. */}
    finally{
      var owned=current.getAndSet(null);if(owned!=null)owned.close();
      synchronized(publication){if(stopping)status="STOPPED";String diagnostic=handle==null?"UNCLASSIFIED":handle.transport.finalDiagnostic();if(!status.equals("COMPLETE")&&diagnostic.equals("NONE"))diagnostic="UNCLASSIFIED";log.accept("CONTROL_READONLY_RESULT "+status+" stage="+stage+" diagnostic="+diagnostic);}
    }
  }
  static String summary(Book book){
    Root root=root(new ArrayList<>(book.metadata.values()));
    if(!Set.of("OPEN","CLOSED","PENDING","APPLIED").contains(root.state())||book.metadata.size()>64)throw rejected();
    List<Object> entries=new ArrayList<>();int other=0;
    for(Metadata m:book.metadata.values()){
      String kind=m.id()==1&&m.key().equals(ROOT_KEY)?"ROOT":m.key().equals(RECEIPT_KEY)?"RECEIPT":m.id()==10001&&m.key().equals("test.arm.A")?"ARM_A":"OTHER";
      var entry=new LinkedHashMap<String,Object>();entry.put("id",m.id());entry.put("kind",kind);entry.put("location",m.sheet()==null?"SPREADSHEET":"SHEET");if(m.sheet()!=null)entry.put("sheetId",m.sheet());
      if(kind.equals("OTHER"))other++;else {entry.put("rawValueSha256",rawValueHash(m.value()));entry.put("canonicalValueSha256",canonicalValueHash(m.value()));}entries.add(entry);
    }
    String value=json(Map.of("version",1,"phase",root.state(),"epoch",root.epoch(),"next",root.next(),"metadataCount",book.metadata.size(),"otherCount",other,"entries",entries));
    if(value.getBytes(StandardCharsets.UTF_8).length>16384)throw rejected();return value;
  }
  static String rawValueHash(String value){
    try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw rejected();}
  }
  static String canonicalValueHash(String value){
    try{var node=BoundedSheetsProjection.MAPPER.readTree(value);if(node==null||!node.isObject())throw rejected();return rawValueHash(json(canonical(node)));}catch(Exception e){throw rejected();}
  }
  private static Object canonical(com.fasterxml.jackson.databind.JsonNode n){
    if(n.isObject()){var result=new TreeMap<String,Object>();n.fields().forEachRemaining(e->result.put(e.getKey(),canonical(e.getValue())));return result;}
    if(n.isArray()){var result=new ArrayList<Object>();n.forEach(v->result.add(canonical(v)));return result;}
    if(n.isTextual())return n.textValue();if(n.isIntegralNumber())return n.bigIntegerValue();if(n.isBoolean())return n.booleanValue();if(n.isNull())return null;throw rejected();
  }
  void health(int port)throws java.io.IOException{
    if(port<0||port>65535||health!=null)throw rejected();
    Map.ofEntries(Map.entry("jdk.httpserver.maxConnections","16"),Map.entry("sun.net.httpserver.maxIdleConnections","0"),Map.entry("sun.net.httpserver.idleInterval","2"),Map.entry("sun.net.httpserver.clockTick","250"),Map.entry("sun.net.httpserver.maxReqTime","2"),Map.entry("sun.net.httpserver.maxRspTime","2"),Map.entry("sun.net.httpserver.timerMillis","250"),Map.entry("sun.net.httpserver.maxReqHeaders","64"),Map.entry("sun.net.httpserver.maxReqHeaderSize","8192"),Map.entry("sun.net.httpserver.drainAmount","0"),Map.entry("sun.net.httpserver.debug","false"),Map.entry("sun.net.httpserver.disableHostValidation","false")).forEach(System::setProperty);
    java.util.logging.Logger.getLogger("com.sun.net.httpserver").setLevel(java.util.logging.Level.OFF);
    health=HttpServer.create(new InetSocketAddress("0.0.0.0",port),16);health.setExecutor(healthWorker);
    health.createContext("/",exchange->{try{
      String path=exchange.getRequestURI().toString();boolean get="GET".equals(exchange.getRequestMethod());var headers=exchange.getRequestHeaders();
      boolean body=headers.containsKey("Transfer-Encoding")||headers.containsKey("Content-Length")&&!headers.get("Content-Length").equals(List.of("0"));
      int code=body?400:!get?405:path.equals("/health/live")?200:path.equals("/health/ready")?503:404;
      byte[] bytes=(code==200?"alive\n":code==503?"business_closed\n":"not_found\n").getBytes(StandardCharsets.US_ASCII);
      exchange.getResponseHeaders().set("Connection","close");exchange.getResponseHeaders().set("Content-Type","text/plain; charset=us-ascii");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
    }finally{exchange.close();}});health.start();
  }
  int healthPort(){return health.getAddress().getPort();}
  static void requireLiveResources(){
    try{long max=-1;for(Path p:List.of(Path.of("/sys/fs/cgroup/memory.max"),Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"))){if(Files.isRegularFile(p)){String s=Files.readString(p).trim();if(!s.matches("[0-9]{1,18}"))throw rejected();max=Long.parseLong(s);break;}}if(max!=512L*1024*1024||Runtime.getRuntime().maxMemory()>256L*1024*1024)throw rejected();}
    catch(Exception failure){throw rejected();}
  }
  @Override public void close(){
    synchronized(publication){if(stopping)return;stopping=true;}
    worker.shutdownNow();var h=current.getAndSet(null);if(h!=null)h.close();
    try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    if(health!=null)health.stop(0);healthWorker.shutdownNow();
  }
}
