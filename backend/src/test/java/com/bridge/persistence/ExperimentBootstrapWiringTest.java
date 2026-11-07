package com.bridge.persistence;
import com.bridge.maintenance.*;
import com.bridge.room.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class ExperimentBootstrapWiringTest {
  @Test void bootstrapGraphHasNoGameplayOrIdentityLinkRoutesAndDefaultsReadOnly(){var f=new ExperimentStorageTest.Fake();var store=f.store();
    new WebApplicationContextRunner().withInitializer(c->{c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);})
      .withUserConfiguration(ExperimentBootstrapApplication.class).withBean(ExperimentStorage.class,()->store)
      .withBean(GoogleCredentialVerifier.class,()->(t,a,n)->new GoogleCredentialVerifier.Principal("https://accounts.google.com","synthetic-owner"))
      .withPropertyValues("spring.profiles.active=experiment-bootstrap","PUBLIC_ORIGIN=https://test.example","GOOGLE_CLIENT_ID=fake","BRIDGE_COLLECTION_MODE=single_tester_experiment","BRIDGE_SHEETS_ENABLED=true","BRIDGE_TEST_SPREADSHEET_ID=isolated_experiment_sheet","BRIDGE_TEST_DATASET_ID="+ExperimentStorageTest.SETTINGS.datasetId(),"BRIDGE_TEST_GOOGLE_IDENTITIES=[{\"issuer\":\"https://accounts.google.com\",\"subject\":\"synthetic-owner\"}]","BRIDGE_PRIVACY_CONTACT=contact","BRIDGE_PRIVACY_RETENTION=experimental","BRIDGE_EXPERIMENT_EXPIRES_AT="+Instant.now().plusSeconds(600),"BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON=not-read")
      .run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(RoomService.class).isEmpty());assertTrue(c.getBeansOfType(RoomController.class).isEmpty());assertTrue(c.getBeansOfType(GoogleAuthService.class).isEmpty());assertFalse(c.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"));assertFalse(c.getBean(ExperimentBootstrapSessions.Settings.class).executionEnabled());assertFalse(c.getBean(ExperimentBootstrapSessions.Settings.class).writersStopped());assertFalse(c.getBean(CollectionPolicy.class).ready());assertEquals(0,f.writes);});
  }
}
