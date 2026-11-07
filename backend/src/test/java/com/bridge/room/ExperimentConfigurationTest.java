package com.bridge.room;
import com.bridge.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class ExperimentConfigurationTest {
  ApplicationContextRunner context(){return new ApplicationContextRunner().withInitializer(c->{c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);c.getEnvironment().setActiveProfiles("prod");})
    .withUserConfiguration(GoogleAuthConfiguration.class).withBean(ObjectMapper.class,ObjectMapper::new).withBean(RoomService.class,RoomService::new)
    .withBean(StatsStore.class,()->new StatsStore(){public SaveStatus submit(FinalMatchEnvelope e){throw new AssertionError();}public SaveStatus status(String id){return SaveStatus.DISABLED;}public List<FinalMatchEnvelope> exportCommitted(){return List.of();}})
    .withBean(IdentityStore.class,()->new IdentityStore(){public boolean enabled(){return true;}public String findOrCreateAccount(String i,String s){throw new AssertionError();}})
    .withPropertyValues("PUBLIC_ORIGIN=https://test.example","GOOGLE_CLIENT_ID=fake-client","BRIDGE_COLLECTION_MODE=single_tester_experiment","BRIDGE_TEST_GOOGLE_IDENTITIES="+ExperimentAdmissionTest.ID,"BRIDGE_PRIVACY_CONTACT=contact","BRIDGE_PRIVACY_RETENTION=experimental","BRIDGE_TEST_DATASET_ID="+ExperimentAdmissionTest.DATASET,"BRIDGE_EXPERIMENT_EXPIRES_AT="+Instant.now().plusSeconds(900));}
  @Test void actualFactoriesKeepReadinessFalse(){context().run(c->{assertNull(c.getStartupFailure());var config=c.getBean(GoogleAuthService.class).config();assertTrue(config.enabled());assertTrue(config.experimentReady());assertFalse(config.collectionReady());assertEquals(1,config.experimentMaxMatches());});}
  @Test void flagsCannotBeSetTrueToPretendVerified(){for(String property:List.of("BRIDGE_ERASURE_WORKFLOW_VERIFIED=true","BRIDGE_QUARTER_POLICY_VERIFIED=true"))context().withPropertyValues(property).run(c->assertNotNull(c.getStartupFailure()));}
  @Test void requiresHttpsEvenForExperiment(){context().withPropertyValues("PUBLIC_ORIGIN=http://localhost:8080").run(c->assertNotNull(c.getStartupFailure()));}
  @Test void experimentCannotFallBackToHttpOriginsWhenProdProfileMissing(){context().withInitializer(c->c.getEnvironment().setActiveProfiles("test")).withPropertyValues("PUBLIC_ORIGIN=http://localhost:8080").run(c->assertNotNull(c.getStartupFailure()));}
}
