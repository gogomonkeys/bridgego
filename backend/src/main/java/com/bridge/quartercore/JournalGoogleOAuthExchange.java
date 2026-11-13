package com.bridge.quartercore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Journal-session variant of the reviewed bounded OAuth/HTTP exchanger. No retries or route widening.
 * Package-private bounded TLS provider. Exact Google origins and
 * scope; production construction is guarded by the disabled-by-default factory. Tests only use
 * numeric loopback TLS. Raw sockets provide no proxy, redirect, cookie, auth negotiation or retry. */
final class JournalGoogleOAuthExchange implements BatchedSheetsTransport.Exchange, AutoCloseable {
  static final String TOKEN_AUDIENCE=GoogleSheetsBinding.TOKEN_AUDIENCE;
  static final String SHEETS_ORIGIN=GoogleSheetsBinding.SHEETS_ORIGIN;
  static final String SCOPE=GoogleSheetsBinding.SCOPE;
  static final int TOKEN_CAP=16384;
  // Decoded JSON payload only; HTTP headers/chunk framing retain separate limits.
  static final int MAINTENANCE_RESPONSE_CAP=2*1024*1024;
  static final long SECOND=TimeUnit.SECONDS.toNanos(1);
  private static final Semaphore HOST_PROVIDERS=new Semaphore(2);
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(TOKEN_CAP).maxNestingDepth(3)
      .maxStringLength(4096).maxNameLength(64).maxNumberLength(10).maxTokenCount(32).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  static final class Failure extends RuntimeException {
    Failure(){super("MANAGED_GOOGLE_REJECTED",null,false,false);}
  }
  private final int localPort;
  private final boolean maintenanceBudget;
  private final GoogleSheetsBinding binding;
  private final GoogleDispatchAuthority writePermit;
  private final Clock realUtc;
  private final ManagedGoogleServiceAccount.Access access;
  private final ConcurrentMap<BatchedSheetsTransport.Flight,GoogleDispatchAuthority.Dispatch> dispatches=new ConcurrentHashMap<>();
  private boolean ownershipReleased;
  private final SSLSocketFactory tls;
  private final ManagedGoogleServiceAccount identity;
  private final SharedSheetsBudget budget;
  private final LongSupplier nanos;
  private final ReentrantLock refresh=new ReentrantLock();
  private final ArrayDeque<Long> attempts=new ArrayDeque<>();
  private final Set<BatchedSheetsTransport.Flight> active=ConcurrentHashMap.newKeySet();
  private volatile boolean closed,blocked;
  private static final class HeaderBuffer extends ByteArrayOutputStream {byte[] take(){byte[] copy=toByteArray();Arrays.fill(buf,(byte)0);reset();return copy;}}
  private char[] cached;private long expires,expiresUtc,issuedUtc,lastUtc=Long.MIN_VALUE;
  private record Lease(byte[] bytes,long expires,long expiresUtc,long issuedUtc){@Override public String toString(){return "TokenLease[REDACTED]";}}
  final java.util.concurrent.atomic.AtomicInteger tokenPosts=new java.util.concurrent.atomic.AtomicInteger();

  private JournalGoogleOAuthExchange(GoogleSheetsBinding binding,ManagedGoogleServiceAccount identity,SharedSheetsBudget budget,
      GoogleDispatchAuthority permit,SSLSocketFactory tls,int localPort,Clock realUtc,LongSupplier nanos,ManagedGoogleServiceAccount.Access access,boolean maintenanceBudget){
    if(binding==null||identity==null||budget==null||tls==null||realUtc==null||nanos==null||localPort<0||localPort>65535)throw new Failure();requireSilentTls();
    this.binding=binding;this.identity=identity;this.budget=budget;this.writePermit=permit;this.tls=tls;this.localPort=localPort;this.realUtc=realUtc;this.nanos=nanos;this.access=Objects.requireNonNull(access);this.maintenanceBudget=maintenanceBudget;
    if(access==ManagedGoogleServiceAccount.Access.READ_ONLY&&permit!=null||access==ManagedGoogleServiceAccount.Access.MAINTENANCE_WRITE&&permit==null)throw new Failure();
    if(!HOST_PROVIDERS.tryAcquire())throw new Failure();
  }
  /** New journal session route. Original exchange is unchanged; TLS/HTTP implementation below is reused verbatim. */
  static JournalGoogleOAuthExchange production(GoogleSheetsBinding binding,ManagedGoogleServiceAccount identity,SharedSheetsBudget budget,JournalSessionAuthority authority){
    return production(binding,identity,budget,new JournalAuthoritySlot(authority));
  }
  static JournalGoogleOAuthExchange production(GoogleSheetsBinding binding,ManagedGoogleServiceAccount identity,SharedSheetsBudget budget,GoogleDispatchAuthority authority){
    try{if(authority==null)throw new Failure();authority.requireSession(binding);requireSilentTls();SSLContext context=SSLContext.getInstance("TLS");context.init(null,null,new SecureRandom());
      return new JournalGoogleOAuthExchange(binding,identity,budget,authority,context.getSocketFactory(),0,Clock.systemUTC(),System::nanoTime,ManagedGoogleServiceAccount.Access.MAINTENANCE_WRITE,false);
    }catch(Exception|LinkageError rejected){throw new Failure();}
  }
  static JournalGoogleOAuthExchange synthetic(GoogleSheetsBinding binding,ManagedGoogleServiceAccount identity,SharedSheetsBudget budget,JournalSessionAuthority authority,SSLContext context,int port,Clock realUtc,LongSupplier nanos){
    return synthetic(binding,identity,budget,new JournalAuthoritySlot(authority),context,port,realUtc,nanos);
  }
  static JournalGoogleOAuthExchange synthetic(GoogleSheetsBinding binding,ManagedGoogleServiceAccount identity,SharedSheetsBudget budget,GoogleDispatchAuthority authority,SSLContext context,int port,Clock realUtc,LongSupplier nanos){
    if(authority==null||port<1||context==null)throw new Failure();authority.requireSession(binding);
    return new JournalGoogleOAuthExchange(binding,identity,budget,authority,context.getSocketFactory(),port,realUtc,nanos,ManagedGoogleServiceAccount.Access.MAINTENANCE_WRITE,false);
  }
  static void requireSilentTls(){
    for(String p:List.of("javax.net.debug","java.security.debug"))if(!System.getProperty(p,"").isBlank())throw new Failure();
  }
  static boolean allowedProductionRequest(GoogleSheetsBinding binding,String method,String origin,String path){
    // Pure specification predicate: it cannot connect. Only exact Google origins/default TLS/port semantics.
    if("POST".equals(method)&&"https://oauth2.googleapis.com".equals(origin))return "/token".equals(path);
    if(!SHEETS_ORIGIN.equals(origin))return false;
    return "GET".equals(method)&&validSheetsTarget(binding,false,path)||"POST".equals(method)&&validSheetsTarget(binding,true,path);
  }
  private static boolean validSheetsTarget(GoogleSheetsBinding binding,boolean write,String target){
    if(binding==null||target==null||target.length()>8192||target.indexOf('\r')>=0||target.indexOf('\n')>=0)return false;
    if(write)return (binding.path()+":batchUpdate").equals(target);
    if(binding.fullTarget().equals(target))return true;
    try{URI uri=URI.create(target);if(uri.isAbsolute()||uri.getRawAuthority()!=null||uri.getFragment()!=null||!binding.path().equals(uri.getRawPath()))return false;
      Map<String,List<String>> query=SheetsQuery.query(uri.getRawQuery());
      if(!query.keySet().equals(Set.of("includeGridData","ranges","fields"))||!query.get("includeGridData").equals(List.of("true"))||!query.get("fields").equals(List.of(BatchedSheetsTransport.FIELDS)))return false;
      Set<String> tabs=new HashSet<>();for(String range:query.get("ranges")){if(!range.matches("'[a-z_]+'!A1:Z1000"))return false;String tab=range.substring(1,range.indexOf("'!"));if(!BatchedSheetsTransport.ALL.contains(tab)||!tabs.add(tab))return false;}
      return !tabs.isEmpty()&&BatchedSheetsTransport.readTarget(binding.path(),tabs).equals(target);
    }catch(Exception invalid){return false;}
  }
  @Override public InputStream execute(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f){
    if(r==null||f==null||!validSheetsTarget(binding,r.write(),r.target())||r.responseLimit()<1||r.responseLimit()>(!r.write()&&binding.fullTarget().equals(r.target())?StrictNativeSheetsResource.MAX_RESPONSE_BYTES:BoundedSheetsProjection.MAX_BYTES)||r.body()==null||r.body().length>BatchedSheetsTransport.MAX_REQUEST_BYTES||!r.write()&&r.body().length!=0||r.write()&&writePermit==null)throw new Failure();
    if(r.write()){writePermit.requireSession(binding);writePermit.requireReady(r.target());}else writePermit.requireReadSession(binding);
    requireSilentTls();synchronized(this){check(r.deadline(),f);if(!active.add(f))throw new Failure();}
    Lease bearer=null;boolean handed=false;
    try{bearer=acquire(r.deadline(),f);check(r.deadline(),f);InputStream stream=wire(r.write()?"POST":"GET",r.target(),r.body(),"application/json",bearer,maintenanceBudget?Math.min(r.responseLimit(),MAINTENANCE_RESPONSE_CAP):r.responseLimit(),r.deadline(),f,r);handed=true;return new FilterInputStream(stream){boolean released;@Override public void close()throws IOException{if(!released){released=true;try{super.close();}finally{synchronized(JournalGoogleOAuthExchange.this){active.remove(f);releaseOwnership();}}}}};}
    catch(Exception rejected){f.diagnostic.fail(rejected);if(rejected instanceof InterruptedException)Thread.currentThread().interrupt();throw new Failure();}
    finally{if(bearer!=null)wipe(bearer.bytes());if(!handed)synchronized(this){active.remove(f);releaseOwnership();}}
  }
  private Lease acquire(long deadline,BatchedSheetsTransport.Flight f)throws Exception{
    f.diagnostic.enter(TransportDiagnostic.Endpoint.TOKEN,TransportDiagnostic.Phase.ACQUIRE);
    if(!refresh.tryLock(BatchedSheetsTransport.remaining(deadline),TimeUnit.NANOSECONDS))throw new Failure();
    byte[] assertion=null,response=null;
    try{
      check(deadline,f);long now=nanos.getAsLong();
      synchronized(this){long utc=utcMillis();if(cached!=null&&expires-now>60*SECOND&&utc>=issuedUtc&&expiresUtc-utc>60000)return lease();wipeCache();
        while(!attempts.isEmpty()&&now-attempts.peekFirst()>=3600*SECOND)attempts.removeFirst();
        if(attempts.size()>=12||!attempts.isEmpty()&&now-attempts.peekLast()<60*SECOND)throw new Failure();}
      // A token call is inline within the owning Sheets Flight. Keep two seconds for the Sheets step.
      long available=BatchedSheetsTransport.remaining(deadline)-2*SECOND;if(available<=0)throw new Failure();
      long tokenDeadline=System.nanoTime()+Math.min(10*SECOND,available),issued=nanos.getAsLong(),issuedWall=utcMillis();
      if(maintenanceBudget)budget.reserveMaintenanceToken(access);
      synchronized(this){attempts.addLast(issued);}assertion=identity.assertionBody(realUtc,access);tokenPosts.incrementAndGet();
      try(InputStream stream=wire("POST","/token",assertion,"application/x-www-form-urlencoded",null,TOKEN_CAP,tokenDeadline,f,null)){response=BatchedSheetsTransport.smallBody(stream,TOKEN_CAP);}
      f.diagnostic.enter(TransportDiagnostic.Endpoint.TOKEN,TransportDiagnostic.Phase.TOKEN_JSON);
      JsonNode value;
      try{value=JSON.readTree(response);if(value==null||!value.isObject())throw new Failure();
        var names=value.fieldNames();while(names.hasNext())if(!Set.of("access_token","token_type","expires_in","scope").contains(names.next()))throw new Failure();
        if(!value.path("access_token").isTextual()||!"Bearer".equals(value.path("token_type").textValue())||!value.path("expires_in").isIntegralNumber()
            ||!value.path("expires_in").canConvertToInt()||value.path("expires_in").intValue()<1||value.path("expires_in").intValue()>3600
            ||value.has("scope")&&!access.scope.equals(value.path("scope").textValue()))throw new Failure();
        String token=value.path("access_token").textValue();if(token.isEmpty()||token.length()>4096||!token.matches("[A-Za-z0-9._~+/=-]+"))throw new Failure();
        long expiry=issued+(Math.min(600,value.path("expires_in").intValue())-60L)*SECOND;
        if(expiry-nanos.getAsLong()<=60*SECOND)throw new Failure();
        synchronized(this){check(deadline,f);cached=token.toCharArray();expires=expiry;issuedUtc=issuedWall;expiresUtc=Math.addExact(issuedWall,(Math.min(600,value.path("expires_in").intValue())-60L)*1000);if(expiresUtc-utcMillis()<=60000){wipeCache();throw new Failure();}return lease();}
      }catch(Exception invalid){f.diagnostic.fail(invalid);synchronized(this){blocked=true;wipeCache();}throw new Failure();}
    }finally{wipe(assertion);wipe(response);refresh.unlock();}
  }
  private synchronized Lease lease(){if(closed||blocked||cached==null)throw new Failure();byte[] value=new byte[cached.length];for(int i=0;i<cached.length;i++)value[i]=(byte)cached[i];return new Lease(value,expires,expiresUtc,issuedUtc);}
  private synchronized void wipeCache(){if(cached!=null)Arrays.fill(cached,'\0');cached=null;expires=0;expiresUtc=0;issuedUtc=0;}
  private synchronized long utcMillis(){long now=realUtc.millis();if(lastUtc!=Long.MIN_VALUE&&now<lastUtc-1000){blocked=true;wipeCache();throw new Failure();}lastUtc=Math.max(lastUtc,now);return now;}
  private void check(long deadline,BatchedSheetsTransport.Flight f){if(closed||blocked||f.stopped)throw new Failure();BatchedSheetsTransport.remaining(deadline);utcMillis();}
  private InputStream wire(String method,String target,byte[] body,String contentType,Lease bearer,int cap,long deadline,BatchedSheetsTransport.Flight f,BatchedSheetsTransport.Request request)throws Exception{
    // The only production routes are fixed Google hosts on 443. Test routes are numeric loopback only.
    // JVM/OS DNS may outlive the caller deadline. The enclosing Flight retains its host slot until
    // actual exit; caller timeout never releases ownership or starts an automatic replacement.
    // This is a bounded-caller deadline, NOT a claim that OS name resolution is cancellable.
    TransportDiagnostic.Endpoint endpoint=target.equals("/token")?TransportDiagnostic.Endpoint.TOKEN:TransportDiagnostic.Endpoint.SHEETS;
    f.diagnostic.enter(endpoint,TransportDiagnostic.Phase.CONNECT,deadline);
    requireSilentTls();String host=localPort==0?(target.equals("/token")?"oauth2.googleapis.com":"sheets.googleapis.com"):"localhost";int port=localPort==0?443:localPort;Socket raw=new Socket(Proxy.NO_PROXY);f.socket=raw;byte[] headers=null;boolean handed=false;
    try{check(deadline,f);raw.connect(localPort==0?new InetSocketAddress(host,443):new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),port),BatchedSheetsTransport.timeout(deadline));raw.setTcpNoDelay(true);
      f.diagnostic.enter(endpoint,TransportDiagnostic.Phase.TLS,deadline);
      SSLSocket socket=(SSLSocket)tls.createSocket(raw,host,port,true);{
        f.socket=socket;check(deadline,f);SSLParameters params=socket.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");params.setProtocols(new String[]{"TLSv1.3","TLSv1.2"});socket.setSSLParameters(params);socket.setSoTimeout(BatchedSheetsTransport.timeout(deadline));socket.startHandshake();check(deadline,f);
        f.diagnostic.enter(endpoint,TransportDiagnostic.Phase.REQUEST,deadline);
        // Immutable header fragments contain no token. Secret bytes have a bounded mutable lifetime.
        HeaderBuffer builder=new HeaderBuffer();builder.write((method+" "+target+" HTTP/1.1\r\nHost: "+host+(localPort==0?"":":"+port)+"\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n").getBytes(StandardCharsets.US_ASCII));
        if(bearer!=null){builder.write("Authorization: Bearer ".getBytes(StandardCharsets.US_ASCII));builder.write(bearer.bytes());builder.write("\r\n".getBytes(StandardCharsets.US_ASCII));}
        if(method.equals("POST"))builder.write(("Content-Type: "+contentType+"\r\nContent-Length: "+body.length+"\r\n").getBytes(StandardCharsets.US_ASCII));builder.write("\r\n".getBytes(StandardCharsets.US_ASCII));headers=builder.take();
        check(deadline,f);if(bearer!=null){synchronized(this){if(bearer.expires()-nanos.getAsLong()<=0||bearer.expiresUtc()-utcMillis()<=0||utcMillis()<bearer.issuedUtc())throw new Failure();}}
        OutputStream out=socket.getOutputStream();if(request!=null&&request.write()){
          if(writePermit==null)throw new Failure();GoogleDispatchAuthority.Dispatch dispatch=writePermit.beforeDispatch(request.target(),new String(body,StandardCharsets.UTF_8));
          if(dispatch==null)throw new Failure();if(dispatches.putIfAbsent(f,dispatch)!=null){dispatch.unknown();throw new Failure();}check(deadline,f);
        }out.write(headers);if(method.equals("POST"))out.write(body);out.flush();check(deadline,f);
        f.diagnostic.enter(endpoint,TransportDiagnostic.Phase.HTTP_STATUS,deadline);
        InputStream in=socket.getInputStream();int[] count={0};String line=BatchedSheetsTransport.line(in,socket,deadline,count);
        if(!line.matches("HTTP/1\\.[01] [0-9]{3}(?: [ -~]*)?"))throw new Failure();int status=Integer.parseInt(line.substring(9,12));Map<String,String> responseHeaders=new HashMap<>();
        f.diagnostic.enter(endpoint,TransportDiagnostic.Phase.HTTP_HEADERS,deadline);
        for(int n=0;;n++){line=BatchedSheetsTransport.line(in,socket,deadline,count);if(line.isEmpty())break;if(n>=BatchedSheetsTransport.MAX_HEADERS)throw new Failure();int colon=line.indexOf(':');if(colon<=0)throw new Failure();String name=line.substring(0,colon).toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();putResponseHeader(responseHeaders,name,value);}
        // Never follow Location, parse provider error descriptions, negotiate auth or retry statuses.
        if(status==429&&!target.equals("/token"))budget.throttle(responseHeaders.get("retry-after"));
        if(status!=200){f.diagnostic.fail(endpoint,TransportDiagnostic.Phase.HTTP_STATUS,TransportDiagnostic.status(status));if(status==401&&bearer!=null)synchronized(this){blocked=true;wipeCache();}throw new Failure();}
        if(!responseHeaders.getOrDefault("content-type","").matches("(?i)application/json(?:;\\s*charset=utf-8)?")||!Set.of("","identity").contains(responseHeaders.getOrDefault("content-encoding","")))throw new Failure();
        InputStream owned=BatchedSheetsTransport.responseBody(in,socket,cap,deadline,responseHeaders,()->{if(f.socket==socket)f.socket=null;});f.diagnostic.enter(endpoint,TransportDiagnostic.Phase.HTTP_BODY,deadline);handed=true;return f.diagnostic.body(owned,endpoint,deadline);
      }
    }catch(Exception|LinkageError rejected){f.diagnostic.fail(rejected);throw rejected;}finally{wipe(headers);if(!handed){Socket owned=f.socket;try{if(owned!=null)owned.close();}finally{raw.close();f.socket=null;}}}
  }

  /** Vary alone is list-valued here. Physical lines/bytes are still counted by the caller.
   * Combination preserves received order and never affects framing, auth, target or retry policy. */
  static void putResponseHeader(Map<String,String> headers,String name,String value){
    if(!name.matches("[a-z0-9-]+"))throw new Failure();
    String prior=headers.get(name);
    if(prior==null){headers.put(name,value);return;}
    if(!name.equals("vary")||!validVary(prior)||!validVary(value))throw new Failure();
    String combined=prior+", "+value;
    if(combined.length()>BatchedSheetsTransport.MAX_HEADER_BYTES||!validVary(combined))throw new Failure();
    headers.put(name,combined);
  }
  private static boolean validVary(String value){
    if(value.equals("*"))return true;
    for(String member:value.split(",",-1)){String token=member.trim();if(!token.isEmpty()&&!token.matches("[!#$%&'+.^_`|~0-9A-Za-z-]+"))return false;}
    return true;
  }

  static void wipe(byte[] value){if(value!=null)Arrays.fill(value,(byte)0);}
  private synchronized void releaseOwnership(){if(closed&&active.isEmpty()&&!ownershipReleased){ownershipReleased=true;identity.close();HOST_PROVIDERS.release();}}
  @Override public void completed(BatchedSheetsTransport.Request request,BatchedSheetsTransport.Flight flight,boolean success){
    if(!success||closed||flight.stopped)abandoned(request,flight);
    GoogleDispatchAuthority.Dispatch dispatch=dispatches.remove(flight);if(dispatch!=null){if(success&&!closed&&!flight.stopped)dispatch.acknowledged();else unknown(dispatch);}
  }
  @Override public void abandoned(BatchedSheetsTransport.Request request,BatchedSheetsTransport.Flight flight){
    // Caller-observed UNKNOWN closes the stage even before token/TLS/Dispatch, or after worker ACK.
    if(request.write())mutationAbandoned();
  }
  @Override public void mutationAbandoned(){if(writePermit!=null)writePermit.closeUnknown();}
  @Override public void close(){List<BatchedSheetsTransport.Flight> flights;synchronized(this){closed=true;wipeCache();if(writePermit!=null)writePermit.closeUnknown();flights=List.copyOf(active);releaseOwnership();}for(var f:flights){f.stop();GoogleDispatchAuthority.Dispatch dispatch=dispatches.remove(f);if(dispatch!=null)unknown(dispatch);}}
  private static void unknown(GoogleDispatchAuthority.Dispatch dispatch){try{dispatch.unknown();}catch(RuntimeException alreadyClosed){/* Terminal revocation already denied this capability. */}}
  @Override public String toString(){return "JournalGoogleOAuthExchange[REDACTED]";}
}
