package com.bridge.room;

/** Private durable identity mapping. Never store email, name, profile, credential or nonce. */
public interface IdentityStore {
  boolean enabled();
  /** Idempotent issuer/subject mapping to a random UUID. Must fail closed on persistence errors. */
  String findOrCreateAccount(String issuer, String subject);
}
