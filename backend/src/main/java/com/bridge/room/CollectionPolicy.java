package com.bridge.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;

/** Immutable deployment admission policy. No public-collection mode exists in this candidate. */
public final class CollectionPolicy {
  private final Set<GoogleCredentialVerifier.Principal> testers;
  private final String privacyContact;
  private final String retention;
  private final boolean erasureWorkflowVerified;
  private final boolean quarterPolicyVerified;
  public CollectionPolicy(Set<GoogleCredentialVerifier.Principal> testers) { this(testers, "", ""); }
  public CollectionPolicy(Set<GoogleCredentialVerifier.Principal> testers, String privacyContact, String retention) {
    this(testers, privacyContact, retention, true, true); // explicit injected policies used by offline tests
  }
  private CollectionPolicy(Set<GoogleCredentialVerifier.Principal> testers, String privacyContact, String retention, boolean verified, boolean quarterVerified) {
    this.erasureWorkflowVerified = verified;
    this.quarterPolicyVerified = quarterVerified;
    for (var tester : testers) validate(tester.issuer(), tester.subject());
    this.testers = Set.copyOf(testers);
    this.privacyContact = privacyContact == null ? "" : privacyContact.trim();
    this.retention = retention == null ? "" : retention.trim();
  }
  public static CollectionPolicy restrictedEmpty() { return new CollectionPolicy(Set.of()); }
  public boolean hasTesters() { return !testers.isEmpty(); }
  public String privacyContact() { return privacyContact; }
  public String retention() { return retention; }
  public CollectionPolicy requireVerifiedErasureWorkflow(boolean verified) {
    return new CollectionPolicy(testers, privacyContact, retention, verified, quarterPolicyVerified);
  }
  public CollectionPolicy requireVerifiedQuarterPolicy(boolean verified) {
    return new CollectionPolicy(testers, privacyContact, retention, erasureWorkflowVerified, verified);
  }
  public boolean ready() { return erasureWorkflowVerified && quarterPolicyVerified && hasTesters() && !privacyContact.isBlank() && !retention.isBlank(); }
  public boolean admits(String issuer, String subject) {
    return issuer != null && subject != null && testers.contains(new GoogleCredentialVerifier.Principal(issuer, subject));
  }
  public static CollectionPolicy parse(ObjectMapper json, String mode, String configured, String privacyContact, String retention) {
    if (!"restricted".equals(mode)) throw new IllegalStateException("Only restricted collection is supported");
    if (configured == null || configured.isBlank()) return new CollectionPolicy(Set.of(), privacyContact, retention);
    try {
      JsonNode entries = json.readTree(configured);
      if (!entries.isArray() || entries.size() > 100) throw new IllegalArgumentException();
      Set<GoogleCredentialVerifier.Principal> testers = new HashSet<>();
      for (JsonNode entry : entries) {
        if (!entry.isObject() || entry.size() != 2 || !entry.path("issuer").isTextual()
            || !entry.path("subject").isTextual()) throw new IllegalArgumentException();
        String issuer = entry.get("issuer").asText(), subject = entry.get("subject").asText();
        validate(issuer, subject);
        testers.add(new GoogleCredentialVerifier.Principal(issuer, subject));
      }
      return new CollectionPolicy(testers, privacyContact, retention);
    } catch (Exception invalid) {
      // Never include the allowlist's private values or parser exception in logs.
      throw new IllegalStateException("Invalid restricted Google identity allowlist");
    }
  }
  private static void validate(String issuer, String subject) {
    if (!"https://accounts.google.com".equals(issuer) || subject == null || subject.isBlank()
        || subject.length() > 255 || !subject.equals(subject.trim()) || subject.contains("@")
        || subject.contains("*") || subject.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Expected exact canonical Google issuer and subject");
  }
}
