package com.bridge.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bridge.persistence.ExperimentRuntimeConfiguration;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class GoogleAuthConfiguration {
  @Bean @ConditionalOnMissingBean(GoogleCredentialVerifier.class)
  GoogleCredentialVerifier googleCredentialVerifier(ObjectMapper json) { return new GoogleJwtVerifier(json); }
  @Bean CollectionPolicy collectionPolicy(ObjectMapper json,
      @Value("${BRIDGE_COLLECTION_MODE:restricted}") String mode,
      @Value("${BRIDGE_TEST_GOOGLE_IDENTITIES:}") String identities,
      @Value("${BRIDGE_PRIVACY_CONTACT:}") String privacyContact,
      @Value("${BRIDGE_PRIVACY_RETENTION:}") String retention,
      @Value("${BRIDGE_ERASURE_WORKFLOW_VERIFIED:false}") boolean erasureVerified,
      @Value("${BRIDGE_QUARTER_POLICY_VERIFIED:false}") boolean quarterVerified,
      @Value("${BRIDGE_TEST_DATASET_ID:}") String dataset,
      @Value("${BRIDGE_EXPERIMENT_EXPIRES_AT:}") String expiresAt) {
    if ("single_tester_experiment".equals(mode)) {
      if (erasureVerified || quarterVerified) throw new IllegalStateException("Experiment is not production readiness");
      return CollectionPolicy.experiment(json, identities, privacyContact, retention, dataset, expiresAt, Clock.systemUTC());
    }
    return CollectionPolicy.parse(json, mode, identities, privacyContact, retention)
        .requireVerifiedErasureWorkflow(erasureVerified).requireVerifiedQuarterPolicy(quarterVerified);
  }
  @Bean GoogleAuthService googleAuthService(RoomService rooms, ObjectProvider<IdentityStore> stores,
      GoogleCredentialVerifier verifier, CollectionPolicy policy,
      ObjectProvider<ExperimentRuntimeConfiguration.Handle> experiments, @Value("${GOOGLE_CLIENT_ID:}") String clientId,
      @Value("${PUBLIC_ORIGIN:}") String publicOrigin, Environment env) {
    boolean production = java.util.Arrays.asList(env.getActiveProfiles()).contains("prod");
    IdentityStore store = stores.getIfAvailable();
    GoogleAuthService.Recovery recovery = null;
    String recoverFlag = env.getProperty("BRIDGE_EXPERIMENT_RECOVERY_ENABLED", "false");
    if (!Set.of("true", "false").contains(recoverFlag)) throw new IllegalStateException("Invalid recovery flag");
    if ("true".equals(recoverFlag)) {
      if (!policy.experiment() || !Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED", "false")))
        throw new IllegalStateException("Recovery requires experiment storage");
      var handle = experiments.getIfAvailable();
      if (handle == null || handle.store() == null || store == null)
        throw new IllegalStateException("Recovery requires experiment storage");
      var experiment = handle.store();
      String expected = env.getRequiredProperty("BRIDGE_EXPERIMENT_RECOVERY_IDENTITY_CLAIM");
      try {
        if (!java.util.UUID.fromString(expected).toString().equals(expected)) throw new IllegalArgumentException();
      } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid recovery claim"); }
      recovery = (issuer, subject) -> experiment.recoverAccount(issuer, subject, expected);
    }
    return new GoogleAuthService(rooms, store, verifier, clientId,
        Set.of(AllowedOrigins.forProfile(production || policy.experiment() || policy.managed(), publicOrigin)), Clock.systemUTC(), policy, recovery);
  }
}
