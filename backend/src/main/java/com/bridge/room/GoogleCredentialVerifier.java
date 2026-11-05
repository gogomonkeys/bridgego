package com.bridge.room;

public interface GoogleCredentialVerifier {
  record Principal(String issuer, String subject) {}
  Principal verify(String credential, String audience, String nonce);
}
