package com.bridge.room;

/** Private durable identity mapping. Only verified Google email and the game nickname may accompany an account; never store credentials or nonce. */
public interface IdentityStore {
  boolean enabled();
  /** Idempotent issuer/subject mapping to a random UUID. Must fail closed on persistence errors. */
  String findOrCreateAccount(String issuer, String subject);
  default String findOrCreateAccount(String issuer, String subject, String nickname, String verifiedEmail) {
    return findOrCreateAccount(issuer, subject);
  }
}
