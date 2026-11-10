package com.bridge.room;

public interface GoogleCredentialVerifier {
  record Principal(String issuer, String subject, String verifiedEmail) {
    public Principal(String issuer, String subject) { this(issuer, subject, null); }
    @Override public String toString() { return "Principal[redacted]"; }
  }
  Principal verify(String credential, String audience, String nonce);
}
