package com.bridge.quartercore;
import com.fasterxml.jackson.core.*;import com.fasterxml.jackson.databind.*;import jakarta.servlet.http.*;
import java.util.*;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
/** Explicitly assembled only. These exact mappings do not intercept static GET resources. */
@org.springframework.stereotype.Controller
final class InitialCutoverHttpAdapter {
 private final InitialCutoverOperatorController controller;private long window=System.nanoTime();private int requests;
 InitialCutoverHttpAdapter(InitialCutoverOperatorController c){controller=Objects.requireNonNull(c);}
 private synchronized boolean budget(){long now=System.nanoTime();if(now-window>=60_000_000_000L){window=now;requests=0;}return ++requests<=60;}
 @GetMapping("/initial-cutover/config") @ResponseBody ResponseEntity<?> config(){return ResponseEntity.ok().headers(headers()).body(controller.config());}
 @PostMapping({"/initial-cutover/checkpoint","/initial-cutover/session/restart","/initial-cutover/session","/initial-cutover/challenge","/initial-cutover/prepare","/initial-cutover/metadata/preview","/initial-cutover/metadata/execute","/initial-cutover/history/preview","/initial-cutover/history/execute","/initial-cutover/activate/preview","/initial-cutover/activate/execute"})
 @ResponseBody ResponseEntity<?> post(HttpServletRequest request){var h=headers();try{
  if(!budget())return ResponseEntity.status(429).headers(h).body(Map.of("code","RATE_LIMITED"));
  JsonNode n=body(request,controller.origin(),16384);String path=request.getRequestURI();Object result;
  if(path.equals("/initial-cutover/session")){fields(n,Set.of());var s=controller.session();h.add("Set-Cookie","__Host-bridge_cutover="+s.cookie()+"; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age="+controller.cookieSeconds());result=Map.of("expires",s.expires());}
  else{String cookie=cookie(request);
   if(path.equals("/initial-cutover/session/restart")){fields(n,Set.of());var s=controller.restartBeforePrepare(cookie);h.add("Set-Cookie","__Host-bridge_cutover="+s.cookie()+"; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age="+controller.cookieSeconds());result=Map.of("expires",s.expires(),"code","AUTH_SESSION_RESTARTED");}
   else if(path.equals("/initial-cutover/checkpoint")){fields(n,Set.of("credential"));var s=controller.checkpoint(cookie,text(n,"credential"));h.add("Set-Cookie","__Host-bridge_cutover="+s.cookie()+"; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age="+controller.cookieSeconds());result=Map.of("expires",s.expires(),"code","CHECKPOINT_VERIFIED");}
   else if(path.equals("/initial-cutover/challenge")){fields(n,Set.of("purpose"));result=controller.challenge(cookie,text(n,"purpose"));}
   else if(path.equals("/initial-cutover/prepare")){fields(n,Set.of("credential","deploymentId"));result=controller.prepare(cookie,text(n,"credential"),text(n,"deploymentId"));}
   else{String[] segments=path.split("/");if(segments.length!=4)throw new IllegalArgumentException();var stage=InitialCutoverOperatorController.Stage.valueOf(segments[2].toUpperCase(Locale.ROOT));
    if(segments[3].equals("preview")){fields(n,Set.of("credential","stoppedSources","noKnownUnresolved"));result=controller.preview(stage,cookie,text(n,"credential"),sources(n),bool(n,"noKnownUnresolved"));}
    else{fields(n,Set.of("credential","digest","approve"));result=controller.execute(stage,cookie,text(n,"credential"),text(n,"digest"),bool(n,"approve"));}
   }
  }return ResponseEntity.ok().headers(h).body(result);
 }catch(Exception e){return ResponseEntity.status(409).headers(h).body(controller.stoppedStatus());}}
 private static String cookie(HttpServletRequest request){String value=null;int count=0;if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals("__Host-bridge_cutover")){value=c.getValue();count++;}if(count!=1)throw new IllegalArgumentException();return value;}
 static HttpHeaders headers(){var h=new HttpHeaders();h.set("Cache-Control","no-store");h.set("Pragma","no-cache");h.set("X-Content-Type-Options","nosniff");h.set("Referrer-Policy","no-referrer");h.set("X-Frame-Options","DENY");return h;}
 private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(92160).maxNestingDepth(5).maxStringLength(90000).maxNumberLength(20).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 static JsonNode body(HttpServletRequest r,String origin,int limit)throws java.io.IOException{
  if(r.getQueryString()!=null||!origin.equals(r.getHeader("Origin"))||Collections.list(r.getHeaders("Origin")).size()!=1||r.getHeader("Content-Encoding")!=null||r.getContentType()==null||!r.getContentType().matches("application/json(?:;\\s*charset=UTF-8)?")||r.getContentLengthLong()>limit)throw new IllegalArgumentException();
  byte[] bytes=r.getInputStream().readNBytes(limit+1);try{if(bytes.length>limit)throw new IllegalArgumentException();JsonNode n=JSON.readTree(bytes);if(n==null||!n.isObject())throw new IllegalArgumentException();return n;}finally{Arrays.fill(bytes,(byte)0);}
 }
 static void fields(JsonNode n,Set<String> expected){Set<String> actual=new HashSet<>();n.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw new IllegalArgumentException();}
 static String text(JsonNode n,String key){if(!n.path(key).isTextual())throw new IllegalArgumentException();return n.path(key).textValue();}
 static boolean bool(JsonNode n,String key){if(!n.path(key).isBoolean())throw new IllegalArgumentException();return n.path(key).booleanValue();}
 private static Set<String> sources(JsonNode n){var a=n.path("stoppedSources");if(!a.isArray()||a.size()>32)throw new IllegalArgumentException();Set<String> values=new HashSet<>();for(var v:a)if(!v.isTextual()||v.textValue().length()>256||!values.add(v.textValue()))throw new IllegalArgumentException();return Set.copyOf(values);}
}
