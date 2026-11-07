package com.bridge.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.*;
import java.util.*;

/** Offline signature verification against keys fetched only from Google's fixed HTTPS JWKS URL. */
public final class GoogleJwtVerifier implements GoogleCredentialVerifier {
  public interface Keys { PublicKey get(String kid) throws Exception; }
  private final ObjectMapper json;
  private final Clock clock;
  private final Keys keys;
  public GoogleJwtVerifier(ObjectMapper json) {
    this(json, Clock.systemUTC(), new GoogleKeys(json));
  }
  public GoogleJwtVerifier(ObjectMapper json, Clock clock, Keys keys) {
    this.json = json; this.clock = clock; this.keys = keys;
  }
  @Override public Principal verify(String credential, String audience, String nonce) {
    try {
      if (credential == null || credential.length() > 12000 || audience == null || audience.isBlank()
          || nonce == null || nonce.isBlank()) throw new IllegalArgumentException();
      String[] parts = credential.split("\\.", -1);
      if (parts.length != 3) throw new IllegalArgumentException();
      JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
      if (!"RS256".equals(header.path("alg").asText()) || !header.path("kid").isTextual()
          || header.has("crit")) throw new IllegalArgumentException();
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initVerify(keys.get(header.get("kid").asText()));
      signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw new IllegalArgumentException();
      JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
      String issuer = claims.path("iss").asText();
      if (!issuer.equals("accounts.google.com") && !issuer.equals("https://accounts.google.com"))
        throw new IllegalArgumentException();
      // GIS issues a single client audience. Reject arrays and ambiguous authorized-party claims.
      if (!claims.path("aud").isTextual() || !audience.equals(claims.get("aud").asText())
          || (claims.has("azp") && !audience.equals(claims.get("azp").asText())))
        throw new IllegalArgumentException();
      long now = clock.instant().getEpochSecond();
      if (!integerTimestamp(claims.path("exp")) || claims.get("exp").asLong() <= now
          || !integerTimestamp(claims.path("iat")) || claims.get("iat").asLong() > now + 60
          || (claims.has("nbf") && (!integerTimestamp(claims.get("nbf")) || claims.get("nbf").asLong() > now)))
        throw new IllegalArgumentException();
      if (!claims.path("nonce").isTextual() || !MessageDigest.isEqual(
          nonce.getBytes(StandardCharsets.UTF_8), claims.get("nonce").asText().getBytes(StandardCharsets.UTF_8)))
        throw new IllegalArgumentException();
      String subject = claims.path("sub").asText();
      if (!claims.path("sub").isTextual() || subject.isBlank() || subject.length() > 255)
        throw new IllegalArgumentException();
      return new Principal("https://accounts.google.com", subject);
    } catch (Exception invalid) {
      // Deliberately omit upstream exception text: it may contain the credential or claims.
      throw RoomService.fail(401, "INVALID_GOOGLE_CREDENTIAL", "Google sign-in could not be verified");
    }
  }

  private static boolean integerTimestamp(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToLong();
  }

  static final class GoogleKeys implements Keys {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private Map<String, PublicKey> cached = Map.of();
    private Instant expires = Instant.EPOCH;
    private Instant nextRefresh = Instant.EPOCH;
    GoogleKeys(ObjectMapper json) { this.json = json; }
    @Override public synchronized PublicKey get(String kid) throws Exception {
      if (kid == null || kid.isBlank() || kid.length() > 200) throw new IllegalArgumentException();
      Instant now = Instant.now();
      if (now.isBefore(expires) && cached.containsKey(kid)) return cached.get(kid);
      if (now.isBefore(nextRefresh)) throw new IllegalArgumentException();
      nextRefresh = now.plusSeconds(30); // Bound random-kid requests and outage retries.
      var request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/oauth2/v3/certs"))
          .timeout(Duration.ofSeconds(8)).GET().build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (var stream = response.body()) { body = stream.readNBytes(65537); }
      if (response.statusCode() != 200 || body.length > 65536) throw new IllegalStateException();
      Map<String, PublicKey> fresh = new HashMap<>();
      for (JsonNode key : json.readTree(body).path("keys")) {
        if (!"RSA".equals(key.path("kty").asText()) || !"RS256".equals(key.path("alg").asText())
            || !"sig".equals(key.path("use").asText())) continue;
        BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(key.path("n").asText()));
        BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(key.path("e").asText()));
        if (n.bitLength() < 2048) continue;
        fresh.put(key.path("kid").asText(), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
      }
      if (fresh.isEmpty()) throw new IllegalStateException();
      cached = Map.copyOf(fresh); expires = now.plusSeconds(3600);
      PublicKey result = cached.get(kid);
      if (result == null) throw new IllegalArgumentException();
      return result;
    }
  }
}
