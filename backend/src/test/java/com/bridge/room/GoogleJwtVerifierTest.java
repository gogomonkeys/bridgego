package com.bridge.room;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
class GoogleJwtVerifierTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static KeyPair key;
  static final long NOW = 1700000000;
  GoogleJwtVerifier verifier;
  @BeforeAll static void keys() throws Exception {
    var gen = KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); key = gen.generateKeyPair();
  }
  @BeforeEach void setup() {
    verifier = new GoogleJwtVerifier(JSON, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC),
        kid -> { if (!kid.equals("test")) throw new IllegalArgumentException(); return key.getPublic(); });
  }
  Map<String,Object> claims() {
    return new HashMap<>(Map.of("iss", "https://accounts.google.com", "sub", "subject", "aud", "client",
        "iat", NOW-1, "exp", NOW+300, "nonce", "challenge"));
  }
  String token(Map<String,Object> claims) throws Exception { return token(claims, "RS256", "test", key); }
  String token(Map<String,Object> claims, String alg, String kid, KeyPair signing) throws Exception {
    var enc = Base64.getUrlEncoder().withoutPadding();
    String input = enc.encodeToString(JSON.writeValueAsBytes(Map.of("alg",alg,"kid",kid))) + "."
        + enc.encodeToString(JSON.writeValueAsBytes(claims));
    Signature sig = Signature.getInstance("SHA256withRSA"); sig.initSign(signing.getPrivate());
    sig.update(input.getBytes(StandardCharsets.US_ASCII)); return input + "." + enc.encodeToString(sig.sign());
  }
  @Test void acceptsSignedCredentialAndCanonicalizesIssuer() throws Exception {
    var c = claims(); c.put("iss", "accounts.google.com");
    assertEquals(new GoogleCredentialVerifier.Principal("https://accounts.google.com", "subject"), verifier.verify(token(c),"client","challenge"));
  }
  @Test void emailMustBeVerifiedBooleanAndIsNeverUsedAsIdentity() throws Exception {
    for(Object flag:List.of(false,"true",1)) {
      var c=claims();c.put("email","person@example.test");c.put("email_verified",flag);
      assertNull(verifier.verify(token(c),"client","challenge").verifiedEmail());
    }
    var c=claims();c.put("email","person@example.test");c.put("email_verified",true);
    var principal=verifier.verify(token(c),"client","challenge");
    assertEquals("person@example.test",principal.verifiedEmail());assertEquals("subject",principal.subject());
    assertFalse(principal.toString().contains("person@"));
    c.put("email","bad\n@example.test");assertNull(verifier.verify(token(c),"client","challenge").verifiedEmail());
  }
  @Test void rejectsInvalidClaims() throws Exception {
    for (var change : Map.<String,Object>of("iss","https://evil.test", "aud","other", "sub","", "exp",NOW,
        "iat",NOW+61,"nonce","other","nbf",NOW+1,"azp","other").entrySet()) {
      var c = claims(); c.put(change.getKey(), change.getValue()); String jwt = token(c);
      assertThrows(RoomService.Failure.class, () -> verifier.verify(jwt,"client","challenge"), change.getKey());
    }
    for (String missing : List.of("iss","aud","sub","exp","iat","nonce")) {
      var c = claims(); c.remove(missing); String jwt = token(c);
      assertThrows(RoomService.Failure.class, () -> verifier.verify(jwt,"client","challenge"), missing);
    }
  }
  @Test void rejectsWrongSignatureAlgorithmKidAndMalformedTokens() throws Exception {
    var gen = KeyPairGenerator.getInstance("RSA"); gen.initialize(2048);
    for (String jwt : List.of(token(claims(),"RS256","test",gen.generateKeyPair()), token(claims(),"none","test",key),
        token(claims(),"RS256","unknown",key), "a.b.c", "", "a.b", "a".repeat(12001))) {
      var failure = assertThrows(RoomService.Failure.class, () -> verifier.verify(jwt,"client","challenge"));
      assertEquals(401, failure.status);
    }
  }
  @Test void rejectsArrayAudienceAndTextTimestamps() throws Exception {
    var c = claims(); c.put("aud",List.of("client")); String jwt = token(c);
    assertThrows(RoomService.Failure.class, () -> verifier.verify(jwt,"client","challenge"));
    c = claims(); c.put("exp",Long.toString(NOW+300)); String other = token(c);
    assertThrows(RoomService.Failure.class, () -> verifier.verify(other,"client","challenge"));
  }
  @Test void rejectsOutOfRangeSignedTimestampsRatherThanWrappingToLong() throws Exception {
    for (String field : List.of("exp", "iat", "nbf")) {
      for (BigInteger value : List.of(BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(NOW+100)),
          BigInteger.ONE.shiftLeft(64).negate().add(BigInteger.valueOf(NOW-100)))) {
        var c=claims();c.put(field,value);String jwt=token(c);
        assertEquals("INVALID_GOOGLE_CREDENTIAL",assertThrows(RoomService.Failure.class,
            ()->verifier.verify(jwt,"client","challenge"),field).code);
      }
    }
  }
  @Test void rejectsWrongClaimTypesAndExactNonceMismatch() throws Exception {
    for (var change : Map.<String,Object>of("sub",123,"nonce",123,"iat",NOW-0.5,
        "exp",NOW+300.5,"nbf",Long.toString(NOW),"aud",List.of("client"),"azp",List.of("client")).entrySet()) {
      var c=claims();c.put(change.getKey(),change.getValue());String jwt=token(c);
      assertThrows(RoomService.Failure.class,()->verifier.verify(jwt,"client","challenge"),change.getKey());
    }
    String jwt=token(claims());
    assertThrows(RoomService.Failure.class,()->verifier.verify(jwt,"client","challenge "));
    assertThrows(RoomService.Failure.class,()->verifier.verify(jwt,"client",null));
    assertThrows(RoomService.Failure.class,()->verifier.verify(jwt,"", "challenge"));
  }
  @Test void signedBoundaryClaimsAndMatchingAuthorizedPartyAreAccepted() throws Exception {
    var c=claims();c.put("iat",NOW+60);c.put("nbf",NOW);c.put("exp",NOW+61);c.put("azp","client");
    assertEquals("subject",verifier.verify(token(c),"client","challenge").subject());
  }
}
