package com.bridge.room;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
class GoogleJwtVerifierDiagnosticsTest {
  @BeforeAll static void generatedKey() throws Exception {GoogleJwtVerifierTest.keys();}
  @Test void fixedPhasesNeverIncludeCredentialClaimsKeyIdentifierOrException() throws Exception {
    var helper=new GoogleJwtVerifierTest();
    var cases=new LinkedHashMap<String,GoogleJwtVerifier.Diagnostic>();
    cases.put("CANARY_MALFORMED_TOKEN",GoogleJwtVerifier.Diagnostic.FORMAT_REJECTED);
    cases.put(helper.token(helper.claims(),"RS256","CANARY_PRIVATE_KID",GoogleJwtVerifierTest.key),GoogleJwtVerifier.Diagnostic.KEY_LOOKUP_FAILED);
    var pair=java.security.KeyPairGenerator.getInstance("RSA");pair.initialize(2048);
    cases.put(helper.token(helper.claims(),"RS256","test",pair.generateKeyPair()),GoogleJwtVerifier.Diagnostic.SIGNATURE_REJECTED);
    var claims=helper.claims();claims.put("aud","CANARY_PRIVATE_AUDIENCE");cases.put(helper.token(claims),GoogleJwtVerifier.Diagnostic.CLAIMS_REJECTED);
    claims=helper.claims();claims.put("exp",GoogleJwtVerifierTest.NOW);cases.put(helper.token(claims),GoogleJwtVerifier.Diagnostic.TIME_REJECTED);
    claims=helper.claims();claims.put("nonce","CANARY_PRIVATE_NONCE");cases.put(helper.token(claims),GoogleJwtVerifier.Diagnostic.NONCE_REJECTED);
    var captured=new ByteArrayOutputStream();var prior=System.err;
    try{
      System.setErr(new PrintStream(captured,true,StandardCharsets.UTF_8));
      for(var entry:cases.entrySet()){
        var seen=new ArrayList<GoogleJwtVerifier.Diagnostic>();
        var verifier=new GoogleJwtVerifier(GoogleJwtVerifierTest.JSON,Clock.fixed(Instant.ofEpochSecond(GoogleJwtVerifierTest.NOW),ZoneOffset.UTC),kid->{if(!kid.equals("test"))throw new IOException("CANARY_PRIVATE_UPSTREAM_ERROR");return GoogleJwtVerifierTest.key.getPublic();},code->{seen.add(code);System.err.println("SYNTHETIC_QUARTER_GOOGLE "+code.name());});
        var failure=assertThrows(RoomService.Failure.class,()->verifier.verify(entry.getKey(),"client","challenge"));
        assertEquals("INVALID_GOOGLE_CREDENTIAL",failure.code);assertEquals(401,failure.status);assertNull(failure.getCause());
        assertEquals(List.of(entry.getValue()),seen);
      }
    }finally{System.setErr(prior);}
    var lines=captured.toString(StandardCharsets.UTF_8).lines().toList();
    assertEquals(cases.values().stream().map(c->"SYNTHETIC_QUARTER_GOOGLE "+c.name()).toList(),lines);
    assertFalse(captured.toString(StandardCharsets.UTF_8).contains("CANARY"));
  }
  @Test void defaultConsumerIsSilentAndThrowingConsumerCannotChangeRejection() throws Exception {
    var captured=new ByteArrayOutputStream();var prior=System.err;
    try{
      System.setErr(new PrintStream(captured,true,StandardCharsets.UTF_8));
      var silent=new GoogleJwtVerifier(GoogleJwtVerifierTest.JSON,Clock.systemUTC(),kid->{throw new AssertionError("No lookup");});
      assertThrows(RoomService.Failure.class,()->silent.verify("CANARY","aud","nonce"));
      var throwing=new GoogleJwtVerifier(GoogleJwtVerifierTest.JSON,Clock.systemUTC(),kid->{throw new AssertionError("No lookup");},code->{throw new IllegalStateException("CANARY_DIAGNOSTIC_EXCEPTION");});
      assertEquals("INVALID_GOOGLE_CREDENTIAL",assertThrows(RoomService.Failure.class,()->throwing.verify("CANARY","aud","nonce")).code);
    }finally{System.setErr(prior);}
    assertEquals("",captured.toString(StandardCharsets.UTF_8));
  }
  @Test void successfulVerificationEmitsNoRejectionDiagnostic() throws Exception {
    var helper=new GoogleJwtVerifierTest();var seen=new ArrayList<GoogleJwtVerifier.Diagnostic>();
    var v=new GoogleJwtVerifier(GoogleJwtVerifierTest.JSON,Clock.fixed(Instant.ofEpochSecond(GoogleJwtVerifierTest.NOW),ZoneOffset.UTC),kid->GoogleJwtVerifierTest.key.getPublic(),seen::add);
    assertEquals("subject",v.verify(helper.token(helper.claims()),"client","challenge").subject());assertTrue(seen.isEmpty());
  }
}
