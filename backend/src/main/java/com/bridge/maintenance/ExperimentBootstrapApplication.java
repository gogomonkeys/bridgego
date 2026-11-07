package com.bridge.maintenance;
import com.bridge.persistence.*;
import com.bridge.room.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Separate explicit graph: no game, identity linking, scheduling or collection queue. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Profile("experiment-bootstrap")
public class ExperimentBootstrapApplication {
  @Bean CollectionPolicy bootstrapPolicy(Environment env,ObjectMapper json){
    if(!"single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE"))
        ||Boolean.parseBoolean(env.getProperty("BRIDGE_ERASURE_WORKFLOW_VERIFIED","false"))
        ||Boolean.parseBoolean(env.getProperty("BRIDGE_QUARTER_POLICY_VERIFIED","false")))throw new IllegalStateException("Explicit experiment only, not production readiness");
    return CollectionPolicy.experiment(json,env.getRequiredProperty("BRIDGE_TEST_GOOGLE_IDENTITIES"),env.getRequiredProperty("BRIDGE_PRIVACY_CONTACT"),
        env.getRequiredProperty("BRIDGE_PRIVACY_RETENTION"),env.getRequiredProperty("BRIDGE_TEST_DATASET_ID"),env.getRequiredProperty("BRIDGE_EXPERIMENT_EXPIRES_AT"),Clock.systemUTC());
  }
  @Bean ErasureMaintenanceApplication.MaintenanceSettings bootstrapGuardSettings(Environment env){return new ErasureMaintenanceApplication.MaintenanceSettings(
      env.getRequiredProperty("PUBLIC_ORIGIN"),env.getRequiredProperty("GOOGLE_CLIENT_ID"),Boolean.parseBoolean(env.getProperty("BRIDGE_EXPERIMENT_INITIALIZATION_ENABLED","false")),
      Boolean.parseBoolean(env.getProperty("BRIDGE_EXPERIMENT_WRITER_STOPPED_ACK","false")));}
  @Bean ExperimentBootstrapSessions.Settings bootstrapSettings(Environment env,CollectionPolicy policy,ErasureMaintenanceApplication.MaintenanceSettings guard){
    var store=ExperimentRuntimeConfiguration.settings(env,policy);return new ExperimentBootstrapSessions.Settings(guard.clientId(),store.sheetId(),store.datasetId(),policy.experimentPrincipal(),policy.experimentExpiresAt(),guard.writerStoppedAcknowledged(),guard.executionEnabled());}
  @Bean @ConditionalOnMissingBean(ExperimentStorage.class) ExperimentStorage bootstrapStorage(Environment env,CollectionPolicy policy){
    var settings=ExperimentRuntimeConfiguration.settings(env,policy);Clock clock=Clock.systemUTC();return new ExperimentStorage(settings,
        new GoogleExperimentGateway(new GoogleSheetsGateway(settings.sheetId(),env.getRequiredProperty("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON")),settings,clock),clock);}
  @Bean @ConditionalOnMissingBean(GoogleCredentialVerifier.class) GoogleCredentialVerifier bootstrapVerifier(ObjectMapper json){return new GoogleJwtVerifier(json);}
  @Bean ExperimentBootstrapSessions bootstrapSessions(ExperimentStorage store,GoogleCredentialVerifier verifier,ExperimentBootstrapSessions.Settings settings){
    return new ExperimentBootstrapSessions(new ExperimentBootstrapSessions.Storage(){public String previewEmptyDigest(){return store.previewEmptyDigest();}
      public String initializeEmpty(String digest,boolean stopped,boolean authorized){return store.initializeEmpty(digest,stopped,authorized);}},verifier,settings,Clock.systemUTC());}
  @Bean ExperimentBootstrapController bootstrapController(ExperimentBootstrapSessions sessions,ExperimentBootstrapSessions.Settings settings){return new ExperimentBootstrapController(sessions,settings);}
  @Bean MaintenanceGuard bootstrapGuard(ErasureMaintenanceApplication.MaintenanceSettings settings){return new MaintenanceGuard(settings);}
  @Bean org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer bootstrapStrictJson(){return b->b.failOnUnknownProperties(true);}
}
