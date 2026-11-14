package com.bridge.room;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

/** Session-bound, expiring, one-use challenges. Never persists bearer tokens or Google credentials. */
public final class GoogleAuthService {
  public record Config(boolean enabled, String clientId, boolean verificationEnabled, String collectionMode, boolean collectionReady, String privacyContact, String retention, boolean experimentReady, String experimentExpiresAt, Integer experimentMaxMatches, boolean experimentRecoveryEnabled) {}
  public record VerifiedIdentity(String issuer, String subject, boolean admittedTester) {}
  public record Challenge(String nonce, int expiresInSeconds) {}
  public record Status(boolean signedIn, boolean nicknameRequired, String nickname) {
    public Status(boolean signedIn) { this(signedIn, false, null); }
  }
  private record EntryProfile(String account, String issuer, String subject, String email, String nickname) {}
  private final Map<String, EntryProfile> entryProfiles = new HashMap<>();
  private record Pending(String nonce, long expires, String purpose) {}
  @FunctionalInterface public interface Recovery { String recover(String issuer, String subject); }
  private final Recovery recovery;
  private final CollectionPolicy policy;
  private final RoomService rooms;
  private final IdentityStore store;
  private final GoogleCredentialVerifier verifier;
  private final String clientId;
  private final Set<String> origins;
  private final Clock clock;
  private final Map<String, Pending> pending = new HashMap<>();
  private final Map<String, Pending> inFlight = new HashMap<>();
  private final SecureRandom random = new SecureRandom();
  public GoogleAuthService(RoomService rooms, IdentityStore store, GoogleCredentialVerifier verifier,
      String clientId, Set<String> origins, Clock clock) {
    this(rooms, store, verifier, clientId, origins, clock, CollectionPolicy.restrictedEmpty());
  }
  public GoogleAuthService(RoomService rooms, IdentityStore store, GoogleCredentialVerifier verifier,
      String clientId, Set<String> origins, Clock clock, CollectionPolicy policy) {
    this(rooms, store, verifier, clientId, origins, clock, policy, null);
  }
  public GoogleAuthService(RoomService rooms, IdentityStore store, GoogleCredentialVerifier verifier,
      String clientId, Set<String> origins, Clock clock, CollectionPolicy policy, Recovery recovery) {
    if (recovery != null && !policy.experiment()) throw new IllegalArgumentException("Recovery requires bounded experiment");
    this.recovery = recovery;
    this.rooms = rooms; this.store = store; this.verifier = verifier; this.policy = Objects.requireNonNull(policy);
    this.clientId = clientId == null ? "" : clientId.trim(); this.origins = Set.copyOf(origins); this.clock = clock;
  }
  public Config config() {
    boolean verificationEnabled = !clientId.isBlank();
    boolean experimentReady = policy.experimentActive(clock) && store != null && store.enabled();
    boolean enabled = verificationEnabled && store != null && store.enabled() && (policy.ready() || experimentReady);
    return new Config(enabled, verificationEnabled ? clientId : "", verificationEnabled,
        policy.experiment() ? "single_tester_experiment" : policy.managed() ? "managed" : "restricted", policy.ready(), policy.privacyContact(), policy.retention(),
        experimentReady, policy.experiment() ? policy.experimentExpiresAt().toString() : null, policy.experiment() ? 1 : null, recovery != null && enabled && experimentReady);
  }
  public synchronized Status status(RoomService.Identity who) {
    String account = rooms.accountId(who);
    var profile = entryProfiles.get(who.playerId());
    if (account == null) return new Status(false);
    if (profile == null || !profile.account.equals(account)) return new Status(true, false, who.displayName());
    return new Status(true, profile.nickname == null, profile.nickname);
  }
  public Status loginForEntry(RoomService.Identity who, String origin, String credential, Boolean consent, String profileConsentVersion) {
    if (!policy.managed() || !"account-profile-v1".equals(profileConsentVersion))
      throw RoomService.fail(400, "PROFILE_CONSENT_REQUIRED", "Current account profile consent required");
    return link(who, origin, credential, consent, false, true, true);
  }
  public Status completeNickname(RoomService.Identity who, String origin, String nickname) {
    checkOrigin(origin); enabled(); rooms.requireOutsideRoom(who);
    String validated = Nickname.validate(nickname);
    EntryProfile before;
    synchronized (this) {
      before = entryProfiles.get(who.playerId());
      if (before == null || !before.account.equals(rooms.accountId(who)))
        throw RoomService.fail(409, "PROFILE_NOT_PENDING", "Start Google sign-in again");
    }
    IdentityStore.Profile saved;
    try {
      saved = before.nickname != null ? new IdentityStore.Profile(before.account, before.nickname)
          : store.completeEntryProfile(before.issuer, before.subject, validated, before.email);
    } catch (RuntimeException failure) {
      throw RoomService.fail(503, "IDENTITY_STORAGE_UNAVAILABLE", "Nickname save not confirmed");
    }
    synchronized (this) {
      if (entryProfiles.get(who.playerId()) != before || !before.account.equals(rooms.accountId(who)))
        throw RoomService.fail(409, "AUTH_CANCELLED", "Account changed while saving nickname");
      if (!before.account.equals(saved.accountId()) || saved.nickname() == null)
        throw RoomService.fail(503, "IDENTITY_STORAGE_UNAVAILABLE", "Nickname save not confirmed");
      rooms.renameOutsideRoom(who, saved.nickname());
      entryProfiles.put(who.playerId(), new EntryProfile(before.account, before.issuer, before.subject, before.email, saved.nickname()));
      return new Status(true, false, saved.nickname());
    }
  }
  public Challenge nonce(RoomService.Identity who, String origin) { return nonce(who, origin, "link"); }
  public synchronized Challenge nonce(RoomService.Identity who, String origin, String purpose) {
    checkOrigin(origin);
    if (purpose == null) purpose = "link";
    if (!purpose.equals("link") && !purpose.equals("verify") && !purpose.equals("recover"))
      throw RoomService.fail(400, "INVALID_AUTH_PURPOSE", "Unknown sign-in purpose");
    if (purpose.equals("recover")) recoveryEnabled();
    else if (purpose.equals("link")) { enabled(); normalAdmission(); } else verificationEnabled();
    rooms.requireOutsideRoom(who);
    long now = clock.millis();
    pending.entrySet().removeIf(e -> e.getValue().expires <= now);
    if (!pending.containsKey(who.playerId()) && pending.size() >= 2048)
      throw RoomService.fail(429, "AUTH_CAPACITY", "Try signing in again later");
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    // An accepted link challenge starts an explicit replacement, not a fallback to the
    // previous account. Cancelled/failed replacements must remain guests. Verification
    // alone is read-only and must never change an existing link.
    if (purpose.equals("recover") && rooms.accountId(who) != null)
      throw RoomService.fail(409, "EXPERIMENT_RECOVERY_UNAVAILABLE", "Already linked; use existing session");
    if (purpose.equals("link")) { rooms.unlinkAccountOutsideRoom(who); entryProfiles.remove(who.playerId()); }
    inFlight.remove(who.playerId());
    pending.put(who.playerId(), new Pending(value, now + 300000, purpose));
    return new Challenge(value, 300);
  }
  public Status login(RoomService.Identity who, String origin, String credential, Boolean consent) {
    return link(who, origin, credential, consent, false, false);
  }
  public Status recover(RoomService.Identity who, String origin, String credential, Boolean consent) {
    return link(who, origin, credential, consent, true, false);
  }
  public Status login(RoomService.Identity who, String origin, String credential, Boolean consent, String profileConsentVersion) {
    return link(who, origin, credential, consent, false, "account-profile-v1".equals(profileConsentVersion));
  }
  private Status link(RoomService.Identity who, String origin, String credential, Boolean consent, boolean recovering, boolean profileConsent) {
    return link(who, origin, credential, consent, recovering, profileConsent, false);
  }
  private Status link(RoomService.Identity who, String origin, String credential, Boolean consent, boolean recovering, boolean profileConsent, boolean entryFlow) {
    checkOrigin(origin); enabled();
    if (recovering) recoveryEnabled(); else normalAdmission();
    rooms.requireOutsideRoom(who);
    Pending challenge;
    synchronized (this) {
      challenge = pending.remove(who.playerId());
      if (challenge != null) inFlight.put(who.playerId(), challenge);
    }
    try {
    if (!Boolean.TRUE.equals(consent))
      throw RoomService.fail(400, "CONSENT_REQUIRED", "Consent is required to link Google and save future results");
    if (challenge == null || challenge.expires <= clock.millis())
      throw RoomService.fail(401, "AUTH_NONCE_EXPIRED", "Start Google sign-in again");
    if (!challenge.purpose.equals(recovering ? "recover" : "link"))
      throw RoomService.fail(400, "AUTH_PURPOSE_MISMATCH", "Start a new sign-in for this action");
    var principal = verifier.verify(credential, clientId, challenge.nonce);
    if (!policy.admits(principal.issuer(), principal.subject()))
      throw RoomService.fail(403, "TESTER_NOT_ADMITTED", "This Google identity is not admitted for test collection");
    // Validate the exact live session and room state again immediately before storage.
    // Verification can block on key retrieval while the session expires or enters a room.
    synchronized (this) {
      if (inFlight.get(who.playerId()) != challenge || challenge.expires <= clock.millis())
        throw RoomService.fail(409, "AUTH_CANCELLED", "Google sign-in was cancelled");
      rooms.requireOutsideRoom(who);
      experimentWindow();
    }
    // Once storage IO starts, cancellation cannot undo a mapping already written; it still
    // prevents session attachment below. Do not hold auth or gameplay locks over network IO.
    // Cancel/logout are not account deletion operations.
    String account;
    IdentityStore.Profile profile = null;
    try {
      if (entryFlow) {
        profile = store.findOrCreateForEntry(principal.issuer(), principal.subject());
        account = profile.accountId();
      } else if (!recovering && policy.managed() && profileConsent) {
        profile = store.completeEntryProfile(principal.issuer(), principal.subject(), who.displayName(), principal.verifiedEmail());
        account = profile.accountId();
      } else account = recovering ? recovery.recover(principal.issuer(), principal.subject())
          : store.findOrCreateAccount(principal.issuer(), principal.subject());
      UUID.fromString(account);
      if (profile != null && profile.nickname() != null) Nickname.validate(profile.nickname());
    } catch (Exception failure) {
      if (policy.experiment()) throw RoomService.fail(503, "EXPERIMENT_UNAVAILABLE", "Experiment admission is unavailable or uncertain; do not retry");
      throw RoomService.fail(503, "IDENTITY_STORAGE_UNAVAILABLE", "Account storage is unavailable; continue as guest");
    }
    // Recheck atomically with attachment: room entry while verification is in flight must not upgrade seats.
    synchronized (this) {
      if (inFlight.get(who.playerId()) != challenge || challenge.expires <= clock.millis())
        throw RoomService.fail(409, "AUTH_CANCELLED", "Google sign-in was cancelled");
      experimentWindow();
      rooms.linkAccountOutsideRoom(who, account, true);
      if (profile != null) {
        if (profile.nickname() == null) rooms.requireNicknameBeforeEntry(who);
        entryProfiles.put(who.playerId(), new EntryProfile(account, principal.issuer(), principal.subject(), principal.verifiedEmail(), profile.nickname()));
        if (profile.nickname() != null) rooms.renameOutsideRoom(who, profile.nickname());
      }
    }
    return profile != null ? new Status(true, profile.nickname() == null, profile.nickname()) : new Status(true);
    } finally {
      synchronized (this) { inFlight.remove(who.playerId(), challenge); }
    }
  }
  /** Returns verified identity only to this requesting session. Never writes account mappings. */
  public VerifiedIdentity verifyOnly(RoomService.Identity who, String origin, String credential, Boolean consent) {
    checkOrigin(origin); verificationEnabled(); rooms.requireOutsideRoom(who);
    Pending challenge;
    synchronized (this) {
      challenge = pending.remove(who.playerId());
      if (challenge != null) inFlight.put(who.playerId(), challenge);
    }
    try {
      if (!Boolean.TRUE.equals(consent))
        throw RoomService.fail(400, "CONSENT_REQUIRED", "Consent is required to verify and display your Google identifier");
      if (challenge == null || challenge.expires <= clock.millis())
        throw RoomService.fail(401, "AUTH_NONCE_EXPIRED", "Start Google verification again");
      if (!challenge.purpose.equals("verify"))
        throw RoomService.fail(400, "AUTH_PURPOSE_MISMATCH", "Start a new verification for this action");
      var principal = verifier.verify(credential, clientId, challenge.nonce);
      synchronized (this) {
        if (inFlight.get(who.playerId()) != challenge || challenge.expires <= clock.millis())
          throw RoomService.fail(409, "AUTH_CANCELLED", "Google verification was cancelled");
        rooms.requireOutsideRoom(who);
        return new VerifiedIdentity(principal.issuer(), principal.subject(), policy.admits(principal.issuer(), principal.subject()));
      }
    } finally {
      synchronized (this) { inFlight.remove(who.playerId(), challenge); }
    }
  }
  public Status logout(RoomService.Identity who, String origin) {
    checkOrigin(origin);
    synchronized (this) {
      rooms.unlinkAccountOutsideRoom(who);
      pending.remove(who.playerId()); inFlight.remove(who.playerId()); entryProfiles.remove(who.playerId());
    }
    return new Status(false);
  }
  public void cancel(RoomService.Identity who, String origin, String nonce) {
    checkOrigin(origin);
    synchronized (this) {
      // Cancellation only invalidates a challenge. It must remain available after room
      // entry so a later leave cannot revive a request whose storage IO is still running.
      rooms.requireCurrentSession(who);
      pending.computeIfPresent(who.playerId(), (id, value) -> value.nonce.equals(nonce) ? null : value);
      inFlight.computeIfPresent(who.playerId(), (id, value) -> value.nonce.equals(nonce) ? null : value);
    }
  }
  private void checkOrigin(String origin) {
    if (origin == null || !origins.contains(origin))
      throw RoomService.fail(403, "ORIGIN_NOT_ALLOWED", "A trusted browser origin is required");
  }
  private void verificationEnabled() {
    if (!config().verificationEnabled()) throw RoomService.fail(503, "GOOGLE_AUTH_DISABLED", "Google verification is not configured");
  }
  private void experimentWindow() {
    if (policy.experiment() && !policy.experimentActive(clock))
      throw RoomService.fail(403, "EXPERIMENT_EXPIRED", "The bounded experiment has expired");
  }
  private void normalAdmission() {
    if (recovery != null) throw RoomService.fail(409, "EXPERIMENT_RECOVERY_REQUIRED", "Use explicit one-time recovery");
  }
  private void recoveryEnabled() {
    experimentWindow();
    if (!config().experimentRecoveryEnabled())
      throw RoomService.fail(503, "EXPERIMENT_RECOVERY_UNAVAILABLE", "One-time recovery is disabled or expired");
  }
  private void enabled() {
    experimentWindow();
    if (policy.experiment() && !config().enabled()) throw RoomService.fail(503, "EXPERIMENT_UNAVAILABLE", "Experiment admission is unavailable");
    if (!config().enabled()) throw RoomService.fail(503, "GOOGLE_AUTH_DISABLED", "Google sign-in is not configured");
  }
}
