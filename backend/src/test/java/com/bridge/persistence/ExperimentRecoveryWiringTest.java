package com.bridge.persistence;

import com.bridge.room.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real production persistence wrappers with a fake, non-network experiment handle. */
class ExperimentRecoveryWiringTest {
  static final String ORIGIN="https://example.test";
  static ApplicationContextRunner context(ExperimentStorage store) {
    return base().withUserConfiguration(PersistenceConfiguration.class,ExperimentRuntimeConfiguration.class)
      .withBean(ExperimentRuntimeConfiguration.Handle.class,()->new ExperimentRuntimeConfiguration.Handle(store));
  }
  static ApplicationContextRunner base() {
    return new ApplicationContextRunner()
      .withInitializer(c->{c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        c.getEnvironment().setActiveProfiles("prod");})
      .withUserConfiguration(GoogleAuthConfiguration.class)
      .withBean(ObjectMapper.class,ObjectMapper::new).withBean(RoomService.class,RoomService::new)
      .withBean(GoogleCredentialVerifier.class,()->(credential,audience,nonce)->
        new GoogleCredentialVerifier.Principal(ExperimentStorageTest.SETTINGS.issuer(),ExperimentStorageTest.SETTINGS.subject()))
      .withPropertyValues("PUBLIC_ORIGIN="+ORIGIN,"GOOGLE_CLIENT_ID=fake","BRIDGE_COLLECTION_MODE=single_tester_experiment",
        "BRIDGE_TEST_GOOGLE_IDENTITIES=[{\"issuer\":\"https://accounts.google.com\",\"subject\":\"synthetic-owner\"}]",
        "BRIDGE_PRIVACY_CONTACT=contact","BRIDGE_PRIVACY_RETENTION=experimental",
        "BRIDGE_TEST_DATASET_ID="+ExperimentStorageTest.SETTINGS.datasetId(),
        "BRIDGE_EXPERIMENT_EXPIRES_AT="+Instant.now().plusSeconds(600),"BRIDGE_SHEETS_ENABLED=true",
        "BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON=must-not-be-read");
  }
  static String seed(ExperimentStorageTest.Fake f){try(var s=f.store()){ExperimentStorageTest.bootstrap(s);ExperimentStorageTest.link(s);}return f.tables.get(ExperimentStorage.IDENTITY).get(1).get(2);}
  @Test void enabledRecoveryUsesHandleBehindRealIdentityWrapperAndExactClaim(){
    var f=new ExperimentStorageTest.Fake();String nonce=seed(f);String account=f.tables.get("accounts").get(1).get(0);
    context(f.store()).withPropertyValues("BRIDGE_EXPERIMENT_RECOVERY_ENABLED=true","BRIDGE_EXPERIMENT_RECOVERY_IDENTITY_CLAIM="+nonce).run(c->{
      assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(IdentityStore.class).size());assertEquals(1,c.getBeansOfType(StatsStore.class).size());
      assertFalse(c.getBean(IdentityStore.class) instanceof ExperimentStorage);assertTrue(c.getBean(IdentityStore.class).enabled());
      var auth=c.getBean(GoogleAuthService.class);assertTrue(auth.config().experimentRecoveryEnabled());assertFalse(auth.config().collectionReady());
      var rooms=c.getBean(RoomService.class);var guest=rooms.createSession("Guest");
      auth.nonce(guest,ORIGIN,"recover");assertTrue(auth.recover(guest,ORIGIN,"synthetic-proof",true).signedIn());
      assertEquals(account,rooms.accountId(guest));assertEquals(3,f.writes);assertNotNull(f.tables.get(ExperimentStorage.RECOVERY));
      assertFalse(auth.config().experimentRecoveryEnabled());assertFalse(c.getBean(IdentityStore.class).enabled());assertTrue(auth.status(guest).signedIn());
      try {
        var envelope=ExperimentStorageTest.envelope(account,"wired-recovery-result");
        var stats=c.getBean(StatsStore.class);assertEquals(StatsStore.SaveStatus.PENDING,stats.submit(envelope));
        assertEquals(StatsStore.SaveStatus.CONFIRMED,ExperimentStorageTest.waitStatus(c.getBean(ExperimentRuntimeConfiguration.Handle.class).store(),envelope));
        assertEquals(StatsStore.SaveStatus.CONFIRMED,stats.status(envelope.commitId()));assertEquals(4,f.writes);
      }catch(Exception failure){throw new AssertionError(failure);}
    });
  }
  @Test void disabledRecoveryUsesNormalWrappersWithoutRequiringClaim(){
    var f=new ExperimentStorageTest.Fake();context(f.store()).withPropertyValues("BRIDGE_EXPERIMENT_RECOVERY_ENABLED=false").run(c->{
      assertNull(c.getStartupFailure());assertFalse(c.getBean(IdentityStore.class) instanceof ExperimentStorage);
      var auth=c.getBean(GoogleAuthService.class);assertFalse(auth.config().experimentRecoveryEnabled());
      var guest=c.getBean(RoomService.class).createSession("Guest");assertThrows(RoomService.Failure.class,()->auth.nonce(guest,ORIGIN,"recover"));assertEquals(0,f.writes);
    });
  }
  @Test void absentRecoveryFlagDefaultsOff(){
    var f=new ExperimentStorageTest.Fake();context(f.store()).run(c->{assertNull(c.getStartupFailure());assertFalse(c.getBean(GoogleAuthService.class).config().experimentRecoveryEnabled());assertEquals(0,f.writes);});
  }
  @Test void incorrectButWellFormedExpectedClaimDoesNotRecover(){
    var f=new ExperimentStorageTest.Fake();seed(f);
    context(f.store()).withPropertyValues("BRIDGE_EXPERIMENT_RECOVERY_ENABLED=true","BRIDGE_EXPERIMENT_RECOVERY_IDENTITY_CLAIM="+UUID.randomUUID()).run(c->{
      assertNull(c.getStartupFailure());var auth=c.getBean(GoogleAuthService.class);var guest=c.getBean(RoomService.class).createSession("Guest");
      auth.nonce(guest,ORIGIN,"recover");assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));assertEquals(2,f.writes);assertFalse(auth.status(guest).signedIn());
    });
  }
  @Test void disabledSheetsInvalidFlagAndMalformedClaimFailClosed(){
    for(String option:new String[]{"BRIDGE_SHEETS_ENABLED=false","BRIDGE_EXPERIMENT_RECOVERY_ENABLED=TRUE","BRIDGE_EXPERIMENT_RECOVERY_IDENTITY_CLAIM=invalid"}){
      var f=new ExperimentStorageTest.Fake();context(f.store()).withPropertyValues("BRIDGE_EXPERIMENT_RECOVERY_ENABLED=true","BRIDGE_EXPERIMENT_RECOVERY_IDENTITY_CLAIM="+UUID.randomUUID(),option)
        .run(c->{assertNotNull(c.getStartupFailure());assertEquals(0,f.writes);});
    }
  }
  @Test void missingHandleAndMissingClaimFailClosed(){
    base().withBean(StatsStore.class,()->new StatsStore(){
        public SaveStatus submit(FinalMatchEnvelope e){throw new AssertionError("No writes");}
        public SaveStatus status(String id){return SaveStatus.DISABLED;}
        public java.util.List<FinalMatchEnvelope> exportCommitted(){return java.util.List.of();}
      }).withBean(IdentityStore.class,()->new IdentityStore(){public boolean enabled(){return true;}public String findOrCreateAccount(String issuer,String subject){throw new AssertionError("No normal admission");}})
      .withPropertyValues("BRIDGE_EXPERIMENT_RECOVERY_ENABLED=true","BRIDGE_EXPERIMENT_RECOVERY_IDENTITY_CLAIM="+UUID.randomUUID())
      .run(c->{assertNotNull(c.getStartupFailure());assertEquals("Recovery requires experiment storage",org.springframework.core.NestedExceptionUtils.getMostSpecificCause(c.getStartupFailure()).getMessage());});
    var f=new ExperimentStorageTest.Fake();context(f.store()).withPropertyValues("BRIDGE_EXPERIMENT_RECOVERY_ENABLED=true")
      .run(c->{assertNotNull(c.getStartupFailure());assertEquals(0,f.writes);});
  }
  @Test void restrictedCollectionCannotEnableRecoveryEvenWithHandle(){
    var f=new ExperimentStorageTest.Fake();context(f.store()).withPropertyValues("BRIDGE_COLLECTION_MODE=restricted","BRIDGE_SHEETS_ENABLED=false",
      "BRIDGE_EXPERIMENT_RECOVERY_ENABLED=true","BRIDGE_EXPERIMENT_RECOVERY_IDENTITY_CLAIM="+UUID.randomUUID())
      .run(c->{assertNotNull(c.getStartupFailure());assertEquals(0,f.writes);});
  }
}
