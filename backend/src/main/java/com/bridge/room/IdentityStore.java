package com.bridge.room;

/** Private durable identity mapping. Only verified Google email and the game nickname may accompany an account; never store credentials or nonce. */
public interface IdentityStore {
  boolean enabled();
  record Profile(String accountId, String nickname) {}
  /** Reads the saved nickname; never substitutes a session nickname. */
  default Profile findOrCreateForEntry(String issuer, String subject) {
    throw new UnsupportedOperationException("Nickname onboarding unavailable");
  }
  /** Fill an absent nickname only; repeated/competing requests return the saved choice. */
  default Profile completeEntryProfile(String issuer, String subject, String nickname, String email) {
    return new Profile(findOrCreateAccount(issuer, subject, nickname, email), nickname);
  }
  /** Idempotent issuer/subject mapping to a random UUID. Must fail closed on persistence errors. */
  String findOrCreateAccount(String issuer, String subject);
  default String findOrCreateAccount(String issuer, String subject, String nickname, String verifiedEmail) {
    return findOrCreateAccount(issuer, subject);
  }
}
