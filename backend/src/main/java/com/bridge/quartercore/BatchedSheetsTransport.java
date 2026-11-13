package com.bridge.quartercore;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Test-source bounded native Sheets transport. Default constructors use numeric-loopback HTTP only.
 * An explicitly injected exchange owns authentication/routing; ManagedGoogleSheetsFactory permits
 * enabled Google READONLY and separate armed loopback tests, never a live Google write route.
 * One operation snapshot is pinned to its exact predecessor. Every write invalidates before dispatch.
 * Raw default sockets provide no proxy, redirect, authentication negotiation or automatic retries.
 */
final class BatchedSheetsTransport implements NarrowSheetsTransport,AutoCloseable {
  static final String PATH="/v4/spreadsheets/"+TARGET;
  static final String FULL_TARGET=PATH+"?includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED";
  static final String FIELDS="spreadsheetId,developerMetadata(metadataId,metadataKey,metadataValue,visibility,location),sheets(properties(sheetId,title,gridProperties(rowCount,columnCount)),developerMetadata(metadataId,metadataKey,metadataValue,visibility,location),data(startRow,startColumn,rowData(values(userEnteredValue,effectiveValue))))";
  static final int MAX_REQUEST_BYTES=256*1024,MAX_BATCH_RESPONSE_BYTES=64*1024,MAX_HEADERS=32,MAX_HEADER_BYTES=16*1024;
  static final int MAX_CHUNKS=65536,MAX_CHUNK_LINE_BYTES=32,MAX_CHUNK_OVERHEAD=512*1024;
  static final Duration MAX_DEADLINE=Duration.ofSeconds(60);
  static final Semaphore HOST_EXCHANGES=new Semaphore(2);
  static final Set<String> ALL=Set.copyOf(ManualQuarterMaintenance.HEADERS.keySet());
  static final Set<String> IDENTITY=Set.of("accounts","auth_identities","erasure_ledger","retention_ledger");
  static final Set<String> PROFILE=Set.of("accounts","retention_ledger");
  static final Set<String> MATCH=Set.of("accounts","envelopes","commits","erasure_ledger","retention_ledger");
  static class Invalid extends RuntimeException {Invalid(){super("SHEETS_INVALID");}}
  /** Finite parser reason only; never carries a field name, body, target or exception cause. */
  static final class BatchReplyRejected extends Invalid {
    final TransportDiagnostic.Code reason;
    BatchReplyRejected(TransportDiagnostic.Code reason){this.reason=reason;}
  }
  static final class Deferred extends RuntimeException {Deferred(){super("SHEETS_DEFERRED");}}
  static final class UnknownWrite extends RuntimeException {UnknownWrite(){super("SHEETS_WRITE_UNKNOWN");}}
  interface Exchange { InputStream execute(Request request,Flight flight)throws Exception; default void completed(Request request,Flight flight,boolean success){} default void abandoned(Request request,Flight flight){} default void mutationAbandoned(){} }
  interface Decoder<T> { T decode(InputStream body)throws Exception; }
  record Request(boolean write,String target,byte[] body,int responseLimit,long deadline) {}
  final int port;final SharedSheetsBudget budget;final Exchange exchange;final ManualQuarterMaintenance.Binding binding;final String path,fullTarget;
  final AtomicReference<Flight> flight=new AtomicReference<>();
  private volatile String diagnostic="NONE",operationDiagnostic="NONE";
  String diagnostic(){return diagnostic;}
  String finalDiagnostic(){return operationDiagnostic;}
  /** An error outside a Flight has no transport cause to attribute. Never erase another owner's
   * active observation, including a caller whose worker is completing asynchronously. */
  private synchronized void unclassified(){
    if(flight.get()==null&&(context==null||context.owner==Thread.currentThread()))publishDiagnostic("UNCLASSIFIED");
  }
  /** Keep the causal failure through read-only reconciliation in the same operation. A newly
   * accepted scope resets this observation; it does not reset any transport or write state. */
  private synchronized void publishDiagnostic(String value){
    diagnostic=value;
    if(context!=null&&context.owner==Thread.currentThread()){
      if(context.failure==null&&!value.equals("NONE"))context.failure=value;
      operationDiagnostic=context.failure==null?value:context.failure;
    }else operationDiagnostic=value;
  }
  final AtomicInteger httpReads=new AtomicInteger(),httpWrites=new AtomicInteger();
  private Context context;private boolean closed;private final Map<String,Integer> physical=new HashMap<>();
  BatchedSheetsTransport(int loopbackPort,SharedSheetsBudget budget){this(loopbackPort,budget,null);}
  BatchedSheetsTransport(int loopbackPort,SharedSheetsBudget budget,Exchange exchange){
    this(loopbackPort,budget,exchange,null);
  }
  BatchedSheetsTransport(int loopbackPort,SharedSheetsBudget budget,Exchange exchange,ManualQuarterMaintenance.Binding binding){
    if(loopbackPort<1||loopbackPort>65535||budget==null)throw new Invalid();port=loopbackPort;this.budget=budget;this.exchange=exchange==null?this::socketExchange:exchange;this.binding=binding;
    path="/v4/spreadsheets/"+(binding==null?TARGET:binding.spreadsheetId());fullTarget=path+"?includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED";
  }
  @Override public synchronized Scope operation(String label,Root predecessor,Set<String> tabs,Duration duration){
    if(closed||context!=null||flight.get()!=null){unclassified();throw new Deferred();}
    if(label==null||!label.matches("[A-Za-z0-9_-]{1,40}")||tabs==null||tabs.isEmpty()||!ALL.containsAll(tabs)||duration==null||duration.isZero()||duration.isNegative()||duration.compareTo(MAX_DEADLINE)>0){unclassified();throw new Invalid();}
    Context c=new Context(predecessor,tabs,System.nanoTime()+duration.toNanos(),Thread.currentThread());context=c;diagnostic="NONE";operationDiagnostic="NONE";
    return ()->{synchronized(BatchedSheetsTransport.this){if(context!=c||c.owner!=Thread.currentThread()){unclassified();throw new Invalid();}c.snapshot=null;context=null;}};
  }
  final class Context {String failure;Root expected;Set<String> tabs;Book snapshot;boolean full;final long deadline;final Thread owner;
    Context(Root root,Set<String> tabs,long deadline,Thread owner){expected=root;this.tabs=Set.copyOf(tabs);this.deadline=deadline;this.owner=owner;}}
  private synchronized Context current(){try{if(closed||context==null||context.owner!=Thread.currentThread())throw new Deferred();remaining(context.deadline);return context;}catch(RuntimeException rejected){unclassified();throw rejected;}}
  private Book read(Set<String> required){Context c=current();if(!c.tabs.containsAll(required)){var union=new HashSet<>(c.tabs);union.addAll(required);c.tabs=Set.copyOf(union);c.snapshot=null;}
    if(c.snapshot==null){Book b=invoke(false,c.full?fullTarget:readTarget(path,c.tabs),new byte[0],c.full?StrictNativeSheetsResource.MAX_RESPONSE_BYTES:BoundedSheetsProjection.MAX_BYTES,c.deadline,
        input->{if(c.full)return StrictNativeSheetsResource.decodeFull(input,c.deadline,binding);byte[] bytes=smallBody(input,BoundedSheetsProjection.MAX_BYTES);try{return BoundedSheetsProjection.decode(bytes,c.tabs,binding);}finally{Arrays.fill(bytes,(byte)0);}});
      try{if(!c.full&&physical.size()!=8)throw new Invalid(); // a projection cannot initialize physical full-resource binding
      Root observed=root(new ArrayList<>(b.metadata.values()));if(c.expected!=null&&!c.expected.equals(observed))throw new Invalid();for(Sheet sheet:b.sheets.values()){Integer pinned=physical.get(sheet.title);int expectedId=sheet.title.equals("retention_ledger")?observed.anchor():pinned==null?sheet.id:pinned;if(sheet.id!=expectedId||sheet.id>=observed.nextSheet())throw new Invalid();}
      b.sheets.values().forEach(sheet->physical.put(sheet.title,sheet.id));c.expected=observed;c.snapshot=b;}catch(RuntimeException rejected){unclassified();throw rejected;}}
    try{remaining(c.deadline);return c.snapshot;}catch(RuntimeException rejected){unclassified();throw rejected;}
  }
  /** Explicit bootstrap-only full legacy read. Never initializes native/root cache or
   * makes projection/native snapshot accept a missing root. */
  long operationDeadline(){return current().deadline;}
  Book readLegacyFull(){return readLegacyFull(()->{});}
  Book readLegacyFull(Runnable parserStarted){return readLegacyFull(parserStarted,ignored->{});}
  Book readLegacyFull(Runnable parserStarted,java.util.function.Consumer<StrictNativeSheetsResource.LegacyStage> diagnostic){Context c=current();if(binding==null)throw new Invalid();
    c.snapshot=null;
    return invoke(false,fullTarget,new byte[0],StrictNativeSheetsResource.MAX_RESPONSE_BYTES,c.deadline,
      input->{parserStarted.run();return StrictNativeSheetsResource.decodeLegacyFull(input,c.deadline,binding,diagnostic);});
  }
  @Override public Book snapshot(){Context c=current();if(!c.full){c.full=true;c.tabs=ALL;c.snapshot=null;}return read(ALL).copy();}
  @Override public List<Metadata> control(){return List.copyOf(read(Set.of()).metadata.values());}
  @Override public List<List<String>> related(String tab){if(!ALL.contains(tab)){unclassified();throw new Invalid();}return read(Set.of(tab)).tables().get(tab);}
  @Override public boolean unsupportedStructure(){return false;} // snapshot uses strict native unmasked decoder; projection never establishes full evidence
  @Override public void batch(String wire){
    try{batchChecked(wire);}catch(RuntimeException failure){try{exchange.mutationAbandoned();}catch(RuntimeException ignored){}throw failure;}
  }
  private void batchChecked(String wire){
    Context c=current();if(wire==null){unclassified();throw new Invalid();}byte[] bytes=wire.getBytes(StandardCharsets.UTF_8);if(bytes.length==0||bytes.length>MAX_REQUEST_BYTES){unclassified();throw new Invalid();}
    Root after=null;int expectedReplyCount;try{var doc=BoundedSheetsProjection.MAPPER.readTree(bytes);keys(doc,Set.of("requests"));var requests=doc.path("requests");if(!requests.isArray()||requests.isEmpty()||requests.size()>10000)throw new Invalid();expectedReplyCount=requests.size();
      for(var r:requests){if(r.has("updateDeveloperMetadata")){var update=r.get("updateDeveloperMetadata");if(lookup(update)==1){if(after!=null)throw new Invalid();after=BoundedSheetsProjection.MAPPER.readValue(text(update.get("developerMetadata"),"metadataValue"),Root.class);}}}
      if(after==null||c.expected==null||!after.dataset().equals(c.expected.dataset()))throw new Invalid();
    }catch(Exception failure){Arrays.fill(bytes,(byte)0);unclassified();throw new Invalid();}
    c.snapshot=null;c.expected=after; // even a pre-dispatch rejection cannot reuse the predecessor snapshot
    invoke(true,path+":batchUpdate",bytes,MAX_BATCH_RESPONSE_BYTES,c.deadline,input->{byte[] response=smallBody(input,MAX_BATCH_RESPONSE_BYTES);
      try{validateBatchReply(response,binding==null?TARGET:binding.spreadsheetId(),expectedReplyCount);return Boolean.TRUE;}
      finally{Arrays.fill(response,(byte)0);}});
  }
  /** Narrow no-comment batch envelope. Payload state is still verified by the existing reads.
   * Sheets documents commentUpdateState; only its explicit no-comment outcome is accepted.
   * ProtoJSON null is unset, so a null updatedSpreadsheet is equivalent to its omission.
   * We never request a spreadsheet in a write response; a non-null one remains rejected. */
  static void validateBatchReply(byte[] response,String expectedSpreadsheet,int expectedReplyCount){
    final com.fasterxml.jackson.databind.JsonNode r;
    try{r=BoundedSheetsProjection.MAPPER.readTree(response);}catch(Exception rejected){throw new BatchReplyRejected(TransportDiagnostic.Code.INVALID_JSON);}
    if(r==null||!r.isObject())throw new BatchReplyRejected(TransportDiagnostic.Code.TYPE_MISMATCH);
    try{keys(r,Set.of("spreadsheetId","replies","updatedSpreadsheet","commentUpdateState"));}
    catch(RuntimeException rejected){throw new BatchReplyRejected(TransportDiagnostic.Code.UNEXPECTED_FIELDS);}
    if(!r.path("spreadsheetId").isTextual()||!expectedSpreadsheet.equals(r.path("spreadsheetId").textValue()))throw new BatchReplyRejected(TransportDiagnostic.Code.TARGET_MISMATCH);
    if(r.has("updatedSpreadsheet")&&!r.get("updatedSpreadsheet").isNull())throw new BatchReplyRejected(TransportDiagnostic.Code.UNEXPECTED_RESOURCE);
    if(r.has("commentUpdateState")&&(!r.get("commentUpdateState").isTextual()||!r.get("commentUpdateState").textValue().equals("NO_UPDATES_REQUESTED")))throw new BatchReplyRejected(TransportDiagnostic.Code.COMMENT_STATE);
    if(!r.path("replies").isArray())throw new BatchReplyRejected(TransportDiagnostic.Code.TYPE_MISMATCH);
    if(r.path("replies").size()!=expectedReplyCount)throw new BatchReplyRejected(TransportDiagnostic.Code.COUNT_MISMATCH);
    for(var reply:r.path("replies"))if(!reply.isObject())throw new BatchReplyRejected(TransportDiagnostic.Code.TYPE_MISMATCH);
  }
  static String readTarget(Set<String> tabs){return readTarget(PATH,tabs);}
  static String readTarget(String path,Set<String> tabs){if(tabs.isEmpty()||!ALL.containsAll(tabs))throw new Invalid();StringBuilder uri=new StringBuilder(path+"?includeGridData=true");
    for(String tab:new TreeSet<>(tabs))uri.append("&ranges=").append(encode("'"+tab+"'!A1:Z1000"));return uri.append("&fields=").append(encode(FIELDS)).toString();}
  static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
  static long remaining(long deadline){long left=deadline-System.nanoTime();if(left<=0||Thread.currentThread().isInterrupted())throw new Deferred();return left;}
  static byte[] smallBody(InputStream input,int cap)throws IOException{byte[] bytes=input.readNBytes(cap+1);if(bytes.length>cap){Arrays.fill(bytes,(byte)0);throw new Invalid();}return bytes;}
  <T> T invoke(boolean write,String target,byte[] body,int cap,long deadline,Decoder<T> decoder){
    try{remaining(deadline);if(!HOST_EXCHANGES.tryAcquire()){Arrays.fill(body,(byte)0);throw new Deferred();}}catch(RuntimeException rejected){unclassified();throw rejected;}
    Flight f=new Flight(write,body,deadline);if(!flight.compareAndSet(null,f)){HOST_EXCHANGES.release();Arrays.fill(body,(byte)0);unclassified();throw new Deferred();}
    Request request=new Request(write,target,body,cap,deadline);
    FutureTask<T> task=new FutureTask<>(()->{boolean success=false;try{remaining(deadline);budget.reserve(write);f.dispatch();f.diagnostic.enter(TransportDiagnostic.Endpoint.SHEETS,TransportDiagnostic.Phase.EXCHANGE);if(write)httpWrites.incrementAndGet();else httpReads.incrementAndGet();T result;try(InputStream input=exchange.execute(request,f)){if(input==null)throw new Invalid();f.diagnostic.enter(TransportDiagnostic.Endpoint.SHEETS,write?TransportDiagnostic.Phase.BATCH_REPLY:target.equals(fullTarget)?TransportDiagnostic.Phase.FULL_NATIVE:TransportDiagnostic.Phase.PROJECTION);
        try{result=decoder.decode(input);}catch(Exception|LinkageError rejected){f.diagnostic.fail(rejected);throw rejected;}remaining(deadline);if(f.stopped)throw new Deferred();}remaining(deadline);if(f.stopped)throw new Deferred();exchange.completed(request,f,true);success=true;return result;}catch(Exception|LinkageError rejected){f.diagnostic.fail(rejected);throw rejected;}finally{try{if(!success)exchange.completed(request,f,false);}finally{f.finished();}}});
    Thread worker=new Thread(task,"synthetic-sheets-http");worker.setDaemon(true);
    try{worker.start();T result=task.get(remaining(deadline),TimeUnit.NANOSECONDS);remaining(deadline);publishDiagnostic(f.diagnostic.finish(true,null));return result;}
    catch(InterruptedException interrupted){publishDiagnostic(f.diagnostic.finish(false,interrupted));abandon(request,f);Thread.currentThread().interrupt();throw f.outcome();}
    catch(ExecutionException failure){publishDiagnostic(f.diagnostic.finish(false,failure.getCause()));abandon(request,f);if(!write&&failure.getCause() instanceof Invalid invalid)throw invalid;throw f.outcome();}
    catch(Exception failure){publishDiagnostic(f.diagnostic.finish(false,failure));abandon(request,f);throw f.outcome();}
    finally{if(worker.getState()==Thread.State.NEW)f.finished();}
  }
  private void abandon(Request request,Flight flight){flight.stop();try{exchange.abandoned(request,flight);}catch(RuntimeException ignored){/* The operation remains failed/unknown. */}}
  final class Flight {
    final boolean write;final byte[] body;final long deadline;volatile boolean attempted,stopped;volatile Socket socket;
    final AtomicBoolean done=new AtomicBoolean();final TransportDiagnostic diagnostic;
    Flight(boolean write,byte[] body,long deadline){this.write=write;this.body=body;this.deadline=deadline;this.diagnostic=new TransportDiagnostic(deadline);}
    synchronized void dispatch(){if(stopped)throw new Deferred();remaining(deadline);attempted=true;}
    void stop(){diagnostic.cancel();stopped=true;Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}}
    RuntimeException outcome(){return write&&attempted?new UnknownWrite():new Deferred();}
    void finished(){if(done.compareAndSet(false,true)){Arrays.fill(body,(byte)0);flight.compareAndSet(this,null);HOST_EXCHANGES.release();}}
  }
  private InputStream socketExchange(Request request,Flight f)throws Exception{
    Socket socket=new Socket(Proxy.NO_PROXY);f.socket=socket;
    boolean handed=false;try{socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),timeout(request.deadline()));socket.setTcpNoDelay(true);
      String headers=(request.write()?"POST ":"GET ")+request.target()+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nAuthorization: Bearer SYNTHETIC_ONLY\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n"+(request.write()?"Content-Type: application/json\r\nContent-Length: "+request.body().length+"\r\n":"")+"\r\n";
      OutputStream out=socket.getOutputStream();remaining(request.deadline());if(f.stopped)throw new Deferred();out.write(headers.getBytes(StandardCharsets.US_ASCII));if(request.write())out.write(request.body());out.flush();remaining(request.deadline());
      InputStream in=socket.getInputStream();int[] count={0};String line=line(in,socket,request.deadline(),count);if(!line.matches("HTTP/1\\.[01] [0-9]{3}(?: [ -~]*)?"))throw new Invalid();int status=Integer.parseInt(line.substring(9,12));Map<String,String> responseHeaders=new HashMap<>();
      for(int n=0;;n++){line=line(in,socket,request.deadline(),count);if(line.isEmpty())break;if(n>=MAX_HEADERS)throw new Invalid();int colon=line.indexOf(':');if(colon<=0)throw new Invalid();String name=line.substring(0,colon).toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();if(!name.matches("[a-z0-9-]+")||responseHeaders.putIfAbsent(name,value)!=null)throw new Invalid();}
      if(status==429)budget.throttle(responseHeaders.get("retry-after"));if(status!=200)throw request.write()?new UnknownWrite():new Deferred();
      if(!responseHeaders.getOrDefault("content-type","").matches("(?i)application/json(?:;\\s*charset=utf-8)?")||!Set.of("","identity").contains(responseHeaders.getOrDefault("content-encoding","")))throw new Invalid();
      InputStream result=responseBody(in,socket,request.responseLimit(),request.deadline(),responseHeaders,()->{if(f.socket==socket)f.socket=null;});handed=true;return result;
    }finally{if(!handed){try{socket.close();}finally{if(f.socket==socket)f.socket=null;}}}
  }
  /** Only unambiguous identity Content-Length or a single chunked coding. No extensions or trailers. */
  static InputStream responseBody(InputStream in,Socket socket,int cap,long deadline,Map<String,String> headers,Runnable afterClose){
    if(cap<1||headers.containsKey("trailer"))throw new Invalid();String coding=headers.get("transfer-encoding");
    if(coding==null)return contentLengthBody(in,socket,cap,deadline,headers,afterClose);
    if(headers.containsKey("content-length")||!coding.equalsIgnoreCase("chunked"))throw new Invalid();
    return new ChunkedBody(in,socket,cap,deadline,afterClose);
  }
  /** Decodes directly into the caller buffer. Size lines never allocate chunk-sized storage. */
  private static final class ChunkedBody extends FilterInputStream {
    final Socket socket;final int cap;final long deadline;final Runnable afterClose;
    long total,left;int chunks,overhead;boolean terminator,ended,closed;
    ChunkedBody(InputStream input,Socket socket,int cap,long deadline,Runnable afterClose){super(input);this.socket=socket;this.cap=cap;this.deadline=deadline;this.afterClose=afterClose;}
    int framingByte()throws IOException{remaining(deadline);socket.setSoTimeout(timeout(deadline));int value=in.read();remaining(deadline);if(value<0||++overhead>MAX_CHUNK_OVERHEAD)throw new Invalid();return value;}
    String chunkLine()throws IOException{StringBuilder line=new StringBuilder();for(int bytes=0;;bytes++){if(bytes>=MAX_CHUNK_LINE_BYTES)throw new Invalid();int value=framingByte();
      if(value=='\r'){if(++bytes>=MAX_CHUNK_LINE_BYTES||framingByte()!='\n')throw new Invalid();return line.toString();}
      if(value<32||value>126)throw new Invalid();line.append((char)value);}}
    void nextChunk()throws IOException{
      if(terminator){if(framingByte()!='\r'||framingByte()!='\n')throw new Invalid();terminator=false;}
      String line=chunkLine();if(!line.matches("[0-9a-fA-F]{1,8}"))throw new Invalid();long size=Long.parseLong(line,16);
      if(size==0){if(!chunkLine().isEmpty())throw new Invalid();remaining(deadline);socket.setSoTimeout(timeout(deadline));if(in.read()!=-1)throw new Invalid();remaining(deadline);ended=true;return;}
      if(++chunks>MAX_CHUNKS||size>cap-total)throw new Invalid();total+=size;left=size;terminator=true;
    }
    @Override public int read()throws IOException{byte[] one=new byte[1];int count=read(one,0,1);return count<0?-1:one[0]&255;}
    @Override public int read(byte[] bytes,int offset,int length)throws IOException{
      Objects.checkFromIndexSize(offset,length,bytes.length);if(closed)throw new IOException("RESPONSE_CLOSED");if(length==0)return 0;remaining(deadline);
      if(ended)return -1;if(left==0){nextChunk();if(ended)return -1;}
      socket.setSoTimeout(timeout(deadline));int n=in.read(bytes,offset,(int)Math.min(length,left));remaining(deadline);if(n<=0)throw new Invalid();left-=n;return n;
    }
    @Override public void close()throws IOException{if(!closed){closed=true;try{socket.close();}finally{afterClose.run();}}}
  }
  /** Exact bounded identity/Content-Length framing. Connection stays owned until parser and close finish. */
  static InputStream contentLengthBody(InputStream in,Socket socket,int cap,long deadline,Map<String,String> headers,Runnable afterClose){
    String length=headers.get("content-length");if(length==null||!length.matches("[0-9]{1,9}"))throw new Invalid();long size=Long.parseLong(length);if(size<1||size>cap)throw new Invalid();
    return new FilterInputStream(in){long left=size;boolean closed,ended;
      @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
      @Override public int read(byte[] bytes,int off,int len)throws IOException{if(closed)throw new IOException("RESPONSE_CLOSED");if(len==0)return 0;remaining(deadline);socket.setSoTimeout(timeout(deadline));
        if(left==0){if(!ended){if(in.read()!=-1)throw new Invalid();remaining(deadline);ended=true;}return -1;}
        int n=in.read(bytes,off,(int)Math.min(len,left));remaining(deadline);if(n<=0)throw new Invalid();left-=n;return n;}
      @Override public void close()throws IOException{if(!closed){closed=true;try{socket.close();}finally{afterClose.run();}}}
    };
  }

  static int timeout(long deadline){return (int)Math.max(1,Math.min(Integer.MAX_VALUE,(remaining(deadline)+999999)/1000000));}
  static String line(InputStream in,Socket socket,long deadline,int[] count)throws IOException{
    ByteArrayOutputStream line=new ByteArrayOutputStream();boolean cr=false;
    for(;;){socket.setSoTimeout(timeout(deadline));int c=in.read();remaining(deadline);if(c<0||++count[0]>MAX_HEADER_BYTES)throw new Invalid();if(cr){if(c!='\n')throw new Invalid();return line.toString(StandardCharsets.US_ASCII);}if(c=='\r'){cr=true;continue;}if(c<32||c>126||line.size()>=8192)throw new Invalid();line.write(c);}
  }
  @Override public synchronized void close(){closed=true;if(context!=null)context.snapshot=null;Flight f=flight.get();if(f!=null)f.stop();}
}
