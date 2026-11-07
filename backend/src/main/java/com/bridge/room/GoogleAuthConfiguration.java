package com.bridge.room;

import com.fasterxml.jackson.databind.ObjectMapper;
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
      GoogleCredentialVerifier verifier, CollectionPolicy policy, @Value("${GOOGLE_CLIENT_ID:}") String clientId,
      @Value("${PUBLIC_ORIGIN:}") String publicOrigin, Environment env) {
    boolean production = java.util.Arrays.asList(env.getActiveProfiles()).contains("prod");
    return new GoogleAuthService(rooms, stores.getIfAvailable(), verifier, clientId,
        Set.of(AllowedOrigins.forProfile(production || policy.experiment(), publicOrigin)), Clock.systemUTC(), policy);
  }
}
