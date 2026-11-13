package com.bridge.quartercore;
import java.util.*;import java.util.function.Supplier;import jakarta.servlet.http.*;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
/** Transport only: cannot create a cycle or accept keys/authority from the request. */
@org.springframework.stereotype.Controller
final class SignedObservationHttpAdapter {
 private final String origin;private final Supplier<SignedObservationControlPlane> current;private long window=System.nanoTime();private int requests;
 SignedObservationHttpAdapter(String origin,Supplier<SignedObservationControlPlane> current){this.origin=Objects.requireNonNull(origin);this.current=Objects.requireNonNull(current);}
 private synchronized boolean budget(){long now=System.nanoTime();if(now-window>=60_000_000_000L){window=now;requests=0;}return ++requests<=60;}
 @PostMapping({"/initial-cutover/observation/challenge","/initial-cutover/observation/submit"}) @ResponseBody ResponseEntity<?> post(HttpServletRequest request){var h=InitialCutoverHttpAdapter.headers();byte[] payload=null,signature=null;try{
  if(!budget())return ResponseEntity.status(429).headers(h).body(Map.of("code","RATE_LIMITED"));
  var n=InitialCutoverHttpAdapter.body(request,origin,92160);var receiver=Objects.requireNonNull(current.get());Object result;
  if(request.getRequestURI().endsWith("/challenge")){InitialCutoverHttpAdapter.fields(n,Set.of());result=receiver.challenge();}
  else{InitialCutoverHttpAdapter.fields(n,Set.of("payload","signature"));String encoded=InitialCutoverHttpAdapter.text(n,"payload"),sig=InitialCutoverHttpAdapter.text(n,"signature");payload=Base64.getDecoder().decode(encoded);signature=Base64.getDecoder().decode(sig);
   if(payload.length>65536||signature.length!=64||!Base64.getEncoder().encodeToString(payload).equals(encoded)||!Base64.getEncoder().encodeToString(signature).equals(sig))throw new IllegalArgumentException();
   receiver.submit(payload,signature);result=Map.of("code","OBSERVATION_ACCEPTED");
  }return ResponseEntity.ok().headers(h).body(result);
 }catch(Exception e){return ResponseEntity.status(409).headers(h).body(Map.of("code","OBSERVATION_NOT_ACCEPTED"));}
 finally{if(payload!=null)Arrays.fill(payload,(byte)0);if(signature!=null)Arrays.fill(signature,(byte)0);}}
}
