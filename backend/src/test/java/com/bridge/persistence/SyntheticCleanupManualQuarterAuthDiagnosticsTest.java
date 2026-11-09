package com.bridge.persistence;
import com.bridge.room.GoogleCredentialVerifier;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SyntheticCleanupManualQuarterAuthDiagnosticsTest {
  @Test void classifiesVerifierPinAndPostVerificationExpiryWithoutOpeningSession(){
    for(String mode:List.of("verifier","principal","pin","expiry")){
      var now=new AtomicReference<>(Instant.parse("2026-11-09T00:00:00Z"));
      Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
      var events=new ArrayList<SyntheticCleanupManualQuarterHost.Diagnostic>();var opens=new AtomicInteger();
      var settings=new SyntheticCleanupManualQuarterHost.Settings(true,"https://example.test",SyntheticCleanupManualQuarterHostTest.CLIENT,SyntheticCleanupExecutorTest.target(),SyntheticCleanupWorkbook.sha256("https://accounts.google.com\nallowed"));
      var host=new SyntheticCleanupManualQuarterHost(settings,(token,audience,nonce)->{
        if(mode.equals("verifier"))throw new IllegalStateException("CANARY_PRIVATE_TOKEN_AND_CLAIMS");
        if(mode.equals("principal"))return null;
        if(mode.equals("expiry"))now.set(now.get().plusSeconds(121));
        return new GoogleCredentialVerifier.Principal("https://accounts.google.com",mode.equals("pin")?"CANARY_PRIVATE_SUBJECT":"allowed");
      },()->{opens.incrementAndGet();throw new AssertionError("No factory access");},clock,events::add);
      var challenge=host.challenge();var failure=assertThrows(SyntheticCleanupManualQuarterHost.Failure.class,()->host.preview(new SyntheticCleanupManualQuarterHost.Proof(challenge.nonce(),"CANARY_PRIVATE_CREDENTIAL",true,true)));
      assertEquals("OPERATOR_AUTHENTICATION_FAILED",failure.code);assertNull(failure.getCause());assertEquals(0,opens.get());
      var reason=switch(mode){case "verifier"->SyntheticCleanupManualQuarterHost.Diagnostic.VERIFIER_REJECTED;case "principal"->SyntheticCleanupManualQuarterHost.Diagnostic.VERIFIED_PRINCIPAL_REJECTED;case "pin"->SyntheticCleanupManualQuarterHost.Diagnostic.OPERATOR_HASH_MISMATCH;default->SyntheticCleanupManualQuarterHost.Diagnostic.CHALLENGE_EXPIRED;};
      assertEquals(List.of(SyntheticCleanupManualQuarterHost.Diagnostic.AUTHENTICATION_STARTED,reason,SyntheticCleanupManualQuarterHost.Diagnostic.AUTHENTICATION_FAILED),events);
      assertFalse(events.toString().contains("CANARY"));
    }
  }
}
