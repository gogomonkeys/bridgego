package com.bridge.persistence;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static com.bridge.persistence.NativeSheetsModel.*;
import static com.bridge.persistence.NarrowQuarterPoc.*;

/** Sole maintenance worker. No business endpoint, new ticket API, scheduler, or game fallback. */
final class MaintenanceLauncher implements AutoCloseable {
  interface Handles { ManagedGoogleSheetsFactory.Handle open(WritePermit permit); }
  enum Status { INERT, WAITING, RUNNING, CLOSED, BLOCKED, STOPPED }
  // Fixed diagnostic labels only. Never log exception text, HTTP bodies, cells or credentials.
  enum Stage { BINDING, RESOURCE, CREDENTIAL, FULL_READ, BASELINE, PLAN, ARM, ARMED_CREDENTIAL, TRANSACTION, CLOSE, COMPLETE }
  private final MaintenanceRelease release;private final Handles handles;private final Runnable resourceGate;
  private final Consumer<String> privateOperatorLog;
  private final Clock clock=Clock.systemUTC();
  final QuarterMaintenanceGate gate=new QuarterMaintenanceGate(true,clock);
  private final AtomicBoolean started=new AtomicBoolean(),stopping=new AtomicBoolean();
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"quarter-maintenance");t.setDaemon(true);return t;});
  private final ExecutorService healthWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"maintenance-health");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  private final AtomicReference<ManagedGoogleSheetsFactory.Handle> current=new AtomicReference<>();
  private volatile Status status=Status.INERT;private volatile WritePermit permit;private HttpServer health;
  MaintenanceLauncher(MaintenanceRelease release,Handles handles,Runnable resourceGate,Consumer<String> privateOperatorLog){this.release=Objects.requireNonNull(release);this.handles=Objects.requireNonNull(handles);this.resourceGate=Objects.requireNonNull(resourceGate);this.privateOperatorLog=Objects.requireNonNull(privateOperatorLog);gate.beginDrain();}
  void health(int port)throws java.io.IOException{
    if(port<0||port>65535||health!=null)throw BootArm.denied();
    configureHealthLimits();
    health=HttpServer.create(new InetSocketAddress("0.0.0.0",port),16);health.setExecutor(healthWorker);
    health.createContext("/",exchange->{try{
      String path=exchange.getRequestURI().toString();boolean get="GET".equals(exchange.getRequestMethod());
      var headers=exchange.getRequestHeaders();
      boolean hasBody=headers.containsKey("Transfer-Encoding")||headers.containsKey("Content-Length")
          &&!headers.get("Content-Length").equals(List.of("0"));
      int code=hasBody?400:!get?405:path.equals("/health/live")?200:path.equals("/health/ready")?503:404;
      byte[] body=(code==200?"alive\n":code==503?"business_closed\n":"not_found\n").getBytes(StandardCharsets.US_ASCII);
      exchange.getResponseHeaders().set("Connection","close");
      exchange.getResponseHeaders().set("Content-Type","text/plain; charset=us-ascii");exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);
    }finally{exchange.close();}});health.start();
  }
  /** JDK17 HttpServer consumes these once, before its first initialization in the public host.
   * No ambient property may widen the fixed public health budget. */
  static void configureHealthLimits(){
    Map.ofEntries(Map.entry("jdk.httpserver.maxConnections","16"),
        Map.entry("sun.net.httpserver.maxIdleConnections","0"),Map.entry("sun.net.httpserver.idleInterval","2"),
        Map.entry("sun.net.httpserver.clockTick","250"),Map.entry("sun.net.httpserver.maxReqTime","2"),
        Map.entry("sun.net.httpserver.maxRspTime","2"),Map.entry("sun.net.httpserver.timerMillis","250"),
        Map.entry("sun.net.httpserver.maxReqHeaders","16"),Map.entry("sun.net.httpserver.maxReqHeaderSize","8192"),
        Map.entry("sun.net.httpserver.drainAmount","0"),Map.entry("sun.net.httpserver.debug","false"),
        Map.entry("sun.net.httpserver.disableHostValidation","false")).forEach(System::setProperty);
    // Rejected/hostile request material must never enter private operator logs.
    java.util.logging.Logger.getLogger("com.sun.net.httpserver").setLevel(java.util.logging.Level.OFF);
  }
  int healthPort(){return health.getAddress().getPort();}
  Status status(){return status;}
  void start(){if(!started.compareAndSet(false,true)||stopping.get())throw BootArm.denied();worker.submit(this::run);}
  private void run(){
    Service service=null;Stage stage=Stage.BINDING;
    try{
      var binding=release.requireProvisionedBinding();stage=Stage.RESOURCE;resourceGate.run();if(stopping.get())return;
      BootArm boot=BootArm.process();
      stage=Stage.CREDENTIAL;var reader=handles.open(null);install(reader);
      stage=Stage.FULL_READ;Book baseline=read(reader);stage=Stage.BASELINE;release.requireBaseline(baseline);
      String owner="maintenance-"+UUID.randomUUID().toString().substring(0,24);
      Instant now=clock.instant();
      stage=Stage.PLAN;var plan=BootArm.Plan.quarter(binding.manualBinding(),release.run,BootArm.Stage.A,baseline,owner,Clock.fixed(now,ZoneOffset.UTC),Limits.defaults(),now,now.plusSeconds(360),Duration.ofSeconds(60),Duration.ofSeconds(300),16,null);
      stage=Stage.ARM;
      status=Status.WAITING;privateOperatorLog.accept(MaintenanceArmOperator.descriptor(release,plan,boot.nonce()));
      for(int i=0;i<16&&!stopping.get();i++){
        Optional<WritePermit> consumed=boot.consume(plan,read(reader));
        if(consumed.isPresent()){permit=consumed.get();break;}
        if(i<15)Thread.sleep(3000);
      }
      if(permit==null||stopping.get())throw BootArm.denied();
      current.compareAndSet(reader,null);reader.close(); // Fully retire read-only identity before opening armed identity.
      stage=Stage.ARMED_CREDENTIAL;var armed=handles.open(permit);install(armed);if(stopping.get())throw BootArm.denied();
      service=new Service(armed.transport,clock,binding.manualBinding(),owner,Limits.defaults());permit.bindQuarter(service);
      status=Status.RUNNING;stage=Stage.TRANSACTION;
      try(var scope=armed.transport.operation("maintenance",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){service.start();}
      if(!stopping.get()&&permit.status()==WritePermit.Status.READY&&service.state()==State.OPEN&&service.quarterSummary()!=null){
        stage=Stage.CLOSE;
        try(var scope=armed.transport.operation("maintenance-close",service.known,BatchedSheetsTransport.ALL,Duration.ofSeconds(30))){service.cleanClose();}
      }
      status=!stopping.get()&&service.state()==State.CLOSED&&permit.status()==WritePermit.Status.FINISHED?Status.CLOSED:Status.BLOCKED;
      if(status==Status.CLOSED)stage=Stage.COMPLETE;
    }catch(Exception|LinkageError rejected){status=Status.BLOCKED;}
    finally{
      // Service monitor is touched only by this worker, never by shutdown while I/O holds it.
      if(service!=null)service.close();if(permit!=null&&permit.status()==WritePermit.Status.READY)permit.closeDenied();
      var handle=current.getAndSet(null);if(handle!=null)handle.close();gate.block(QuarterMaintenanceGate.Reason.SHUTDOWN);
      if(stopping.get())status=Status.STOPPED;
      privateOperatorLog.accept("MAINTENANCE_RESULT "+status+" dispatched="+(permit==null?0:permit.dispatched())+" stage="+stage);
    }
  }
  private void install(ManagedGoogleSheetsFactory.Handle handle){current.set(handle);if(stopping.get()){current.compareAndSet(handle,null);handle.close();throw BootArm.denied();}}
  private Book read(ManagedGoogleSheetsFactory.Handle reader){if(stopping.get())throw BootArm.denied();try(var scope=reader.transport.operation("arm-read",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(10))){Book b=reader.transport.snapshot();if(reader.transport.unsupportedStructure())throw BootArm.denied();return b;}}
  static void requireLiveResources(){
    try{
      long maximum=-1;
      for(Path p:List.of(Path.of("/sys/fs/cgroup/memory.max"),Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"))){if(Files.isRegularFile(p)){String s=Files.readString(p).trim();if(!s.matches("[0-9]{1,18}"))throw BootArm.denied();maximum=Long.parseLong(s);break;}}
      if(maximum!=512L*1024*1024||Runtime.getRuntime().maxMemory()>256L*1024*1024)throw BootArm.denied();
    }catch(Exception failure){throw BootArm.denied();}
  }
  @Override public void close(){
    if(!stopping.compareAndSet(false,true))return;
    gate.block(QuarterMaintenanceGate.Reason.SHUTDOWN);
    worker.shutdownNow();
    // Abort active transport without waiting on Service's network-held monitor.
    var handle=current.getAndSet(null);if(handle!=null)handle.close();
    try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    if(health!=null)health.stop(0);healthWorker.shutdownNow();status=Status.STOPPED;
  }
}
