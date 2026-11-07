package com.bridge.persistence;
import com.bridge.room.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class ExperimentWiringTest {
  @Test void onlyWrappersAreAutowireCandidatesAndConsumedAdmissionDisablesLink(){var f=new ExperimentStorageTest.Fake();var s=f.store();ExperimentStorageTest.bootstrap(s);
    new ApplicationContextRunner().withInitializer(c->{c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);c.getEnvironment().setActiveProfiles("prod");})
      .withUserConfiguration(GoogleAuthConfiguration.class,PersistenceConfiguration.class,ExperimentRuntimeConfiguration.class)
      .withBean(ObjectMapper.class,ObjectMapper::new).withBean(RoomService.class,RoomService::new)
      .withBean(ExperimentRuntimeConfiguration.Handle.class,()->new ExperimentRuntimeConfiguration.Handle(s))
      .withPropertyValues("PUBLIC_ORIGIN=https://example.test","GOOGLE_CLIENT_ID=fake","BRIDGE_COLLECTION_MODE=single_tester_experiment","BRIDGE_TEST_GOOGLE_IDENTITIES=[{\"issuer\":\"https://accounts.google.com\",\"subject\":\"synthetic-owner\"}]","BRIDGE_PRIVACY_CONTACT=contact","BRIDGE_PRIVACY_RETENTION=experimental","BRIDGE_TEST_DATASET_ID="+ExperimentStorageTest.SETTINGS.datasetId(),"BRIDGE_EXPERIMENT_EXPIRES_AT="+Instant.now().plusSeconds(600),"BRIDGE_SHEETS_ENABLED=true","BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON=not-read")
      .run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(IdentityStore.class).size());assertEquals(1,c.getBeansOfType(StatsStore.class).size());assertTrue(c.getBean(IdentityStore.class).enabled());assertFalse(c.getBean(CollectionPolicy.class).ready());assertEquals(ExperimentStorageTest.link(s),f.tables.get("accounts").get(1).get(0));assertFalse(c.getBean(IdentityStore.class).enabled());assertFalse(c.getBean(GoogleAuthService.class).config().enabled());});
  }
}
