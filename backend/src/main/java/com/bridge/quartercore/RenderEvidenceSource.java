package com.bridge.quartercore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.Rejected;
import static com.bridge.quartercore.SingleServiceEvidenceProvider.*;

/** Read-only authenticated Render API adapter. No credential discovery, writes, env parsing
 * or process-state invention. Official v1 list-services/list-deploys JSON subset only. */
final class RenderEvidenceSource implements Source {
  static final class NotReady extends RuntimeException { NotReady(){super("READ_ONLY_EVIDENCE_NOT_READY",null,false,false);} }
  @FunctionalInterface interface CredentialSource { char[] readToken(); }
  @FunctionalInterface interface ReadOnlyHttp { byte[] get(URI fixedUri,char[] bearer,long deadline); }
  /** Must come from a separately verified process/cutover source, not Render instance count.
   * An absent source is a hard failure, never synthesized from the requested binding. */
  @FunctionalInterface interface ProcessEvidence { Control read(); }
  record Control(Instant capturedAt,String cutoverReference,String originalBaseline,
      Map<String,ExternalWriterState> writers,Handoff handoff,NativePredecessor nativePredecessor) {
    Control {writers=Map.copyOf(writers);}
  }
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(1024*1024).maxNestingDepth(24).maxStringLength(65536).maxNumberLength(20).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final Binding binding;private final CredentialSource credentials;private final ReadOnlyHttp http;
  private final ProcessEvidence processes;private final Clock clock;private final Duration ttl;
  private Observation cached;private Control control;private Instant lastNow;private long cachedAtNanos;private boolean failed;
  private RenderEvidenceSource(Binding binding,CredentialSource credentials,ReadOnlyHttp http,ProcessEvidence processes,Clock clock,Duration ttl) {
    this.binding=Objects.requireNonNull(binding);this.credentials=Objects.requireNonNull(credentials);this.http=Objects.requireNonNull(http);
    this.processes=Objects.requireNonNull(processes);this.clock=Objects.requireNonNull(clock);this.ttl=Objects.requireNonNull(ttl);
    if(!binding.workspaceId().matches("tea-[A-Za-z0-9]+")||!binding.serviceId().matches("srv-[A-Za-z0-9]+")||ttl.isNegative()||ttl.isZero()||ttl.compareTo(Duration.ofSeconds(60))>0)throw new Rejected();
  }
  static RenderEvidenceSource production(boolean enabled,Binding binding,CredentialSource credentials,ProcessEvidence processes,Clock clock) {
    if(!enabled)throw new Rejected();return new RenderEvidenceSource(binding,credentials,RenderEvidenceSource::httpsGet,processes,clock,Duration.ofSeconds(30));
  }
  static RenderEvidenceSource localTest(Binding b,CredentialSource c,ReadOnlyHttp h,ProcessEvidence p,Clock clock){return new RenderEvidenceSource(b,c,h,p,clock,Duration.ofSeconds(30));}
  @Override public synchronized Observation read() {
    if(failed)throw new Rejected();char[] token=null;
    try {
      long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
      Instant now=clock.instant();if(lastNow!=null&&now.isBefore(lastNow))throw new Rejected();lastNow=now;
      if(cached!=null&&now.isBefore(cached.capturedAt().plus(ttl))&&System.nanoTime()-cachedAtNanos<ttl.toNanos())return cached;
      // Missing process/cutover evidence fails even before token retrieval or API traffic.
      Control next=processes.read();if(next==null)throw new NotReady();
      if(next.capturedAt()==null||next.capturedAt().isAfter(now)||next.capturedAt().plus(ttl).isBefore(now))throw new Rejected();
      if(!binding.cutoverReference().equals(next.cutoverReference())||!binding.approvedBaselineToken().equals(next.originalBaseline()))throw new Rejected();
      requireSilentDiagnostics();
      token=credentials.readToken();if(token==null||token.length<1||token.length>1024)throw new Rejected();
      for(char ch:token)if(ch<33||ch>126)throw new Rejected();
      JsonNode services=request("/v1/services?ownerId="+binding.workspaceId()+"&includePreviews=true&limit=100",token,deadline);
      if(!services.isArray()||services.size()!=1)throw new Rejected(); // More than one service cannot satisfy this scoped deployment policy.
      JsonNode service=services.get(0).path("service");
      if(!text(service,"id").equals(binding.serviceId())||!text(service,"ownerId").equals(binding.workspaceId())||!text(service,"type").equals("web_service")||!text(service,"suspended").equals("not_suspended"))throw new Rejected();
      JsonNode details=service.path("serviceDetails");if(!details.path("numInstances").isIntegralNumber())throw new Rejected();
      int instances=details.path("numInstances").intValue();String auto=text(service,"autoDeploy");if(!Set.of("yes","no").contains(auto))throw new Rejected();
      String previews=text(details.path("previews"),"generation");if(!Set.of("off","automatic","manual").contains(previews))throw new Rejected();
      var deployments=new ArrayList<Deployment>();var cursors=new HashSet<String>();String cursor="";boolean complete=false;
      for(int page=0;page<5;page++) {
        JsonNode rows=request("/v1/services/"+binding.serviceId()+"/deploys?limit=100"+(cursor.isEmpty()?"":"&cursor="+cursor),token,deadline);
        if(!rows.isArray()||rows.size()>100)throw new Rejected();
        for(var row:rows){var d=row.path("deploy");String status=text(d,"status");
          if(!Set.of("created","queued","build_in_progress","update_in_progress","live","deactivated","build_failed","update_failed","canceled","pre_deploy_in_progress","pre_deploy_failed").contains(status))throw new Rejected();
          if(Set.of("build_failed","update_failed","pre_deploy_failed").contains(status))status="failed";
          deployments.add(new Deployment(text(d,"id"),text(d.path("commit"),"id"),status));
        }
        if(rows.size()<100){complete=true;break;}
        cursor=text(rows.get(rows.size()-1),"cursor");if(!cursor.matches("[A-Za-z0-9_-]{1,256}")||!cursors.add(cursor))throw new Rejected();
      }
      if(!complete||deployments.stream().map(Deployment::id).distinct().count()!=deployments.size())throw new Rejected();
      Instant end=clock.instant();if(end.isBefore(now)||Duration.between(now,end).compareTo(Duration.ofSeconds(60))>0)throw new Rejected();
      control=next;cached=new Observation(now,binding.workspaceId(),binding.serviceId(),1,instances,auto.equals("yes"),!previews.equals("off"),deployments,next.writers(),next.cutoverReference(),next.originalBaseline(),next.handoff());cachedAtNanos=System.nanoTime();return cached;
    }catch(NotReady pending){throw pending;}catch(Exception reject){failed=true;cached=null;control=null;throw new Rejected();}
    finally{if(token!=null)Arrays.fill(token,'\0');}
  }
  @Override public synchronized NativePredecessor nativePredecessor(){read();return control.nativePredecessor();}
  private JsonNode request(String path,char[] token,long deadline)throws Exception {remainingMillis(deadline);byte[] raw=http.get(URI.create("https://api.render.com"+path),token,deadline);remainingMillis(deadline);if(raw==null||raw.length>1024*1024)throw new Rejected();return JSON.readTree(raw);}
  private static String text(JsonNode node,String name){var value=node.get(name);if(value==null||!value.isTextual()||value.textValue().isBlank())throw new Rejected();return value.textValue();}
  /** Fixed HTTPS origin, proxy bypass, GET only, redirects disabled, bounded read/deadline.
   * No exception includes response bodies, Authorization or credential source content. */
  static void requireSilentDiagnostics() {
    JournalGoogleOAuthExchange.requireSilentTls();
    for(String logger:List.of("sun.net.www.protocol.http.HttpURLConnection","sun.net.www.protocol.https.HttpsURLConnectionImpl","sun.net.www.http.HttpClient"))
      if(java.util.logging.Logger.getLogger(logger).isLoggable(java.util.logging.Level.FINE))throw new Rejected();
    if(java.net.CookieHandler.getDefault()!=null)throw new Rejected();
  }
  private static int remainingMillis(long deadline){long left=deadline-System.nanoTime();if(left<=0)throw new Rejected();return (int)Math.max(1,Math.min(10000,(left+999999)/1000000));}
  static byte[] httpsGet(URI uri,char[] token,long deadline) {
    requireSilentDiagnostics();remainingMillis(deadline);
    if(!uri.getScheme().equals("https")||!uri.getHost().equals("api.render.com")||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getFragment()!=null
        ||!uri.getRawPath().matches("/v1/services(?:/srv-[A-Za-z0-9]+/deploys)?"))throw new Rejected();
    HttpsURLConnection connection=null;
    try {
      connection=(HttpsURLConnection)uri.toURL().openConnection(Proxy.NO_PROXY);connection.setInstanceFollowRedirects(false);connection.setRequestMethod("GET");
      connection.setConnectTimeout(remainingMillis(deadline));connection.setReadTimeout(remainingMillis(deadline));connection.setUseCaches(false);
      connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Authorization","Bearer "+new String(token));
      connection.setAuthenticator(new java.net.Authenticator(){});
      if(connection.getResponseCode()!=200)throw new Rejected();
      if(connection.getContentLengthLong()>1024*1024)throw new Rejected();
      remainingMillis(deadline);
      try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
        byte[] bytes=new byte[8192];int n;while(true){connection.setReadTimeout(remainingMillis(deadline));n=in.read(bytes);if(n==-1)break;if(System.nanoTime()>deadline||out.size()+n>1024*1024)throw new Rejected();out.write(bytes,0,n);}return out.toByteArray();
      }
    }catch(Exception invalid){throw new Rejected();}finally{if(connection!=null)connection.disconnect();}
  }
}
