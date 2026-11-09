package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManagedCollectionTest {
  static final String ORIGIN="https://bridge.example";
  static CollectionPolicy policy(){return CollectionPolicy.managed("jody.yycc@gmail.com","Accounts retained; personal match records cleaned manually each Taiwan quarter; historical copies may remain.");}
  @Test void explicitlyManagedAdmissionDoesNotRequireOrAssertAcceptanceFlags(){
    var p=new GoogleAuthConfiguration().collectionPolicy(new ObjectMapper(),"managed","","contact","manual quarterly",false,false,"","");
    assertTrue(p.managed());assertTrue(p.ready());assertFalse(p.experiment());assertFalse(p.hasTesters());
    assertTrue(p.requireVerifiedErasureWorkflow(false).requireVerifiedQuarterPolicy(false).ready());
    assertTrue(p.admits("https://accounts.google.com","new-google-subject"));
    for(String invalid:Arrays.asList(null,"","*","private@example.com"," leading"))assertFalse(p.admits("https://accounts.google.com",invalid));
    assertFalse(p.admits("https://evil.test","subject"));assertFalse(CollectionPolicy.managed("","policy").ready());
    assertFalse(CollectionPolicy.managed("contact","").ready());
    assertFalse(CollectionPolicy.parse(new ObjectMapper(),"restricted","","contact","policy").ready());
  }
  @Test void consentNonceAndOriginRemainRequiredAndGuestIdentityRemainsUsable(){
    var rooms=new RoomService();var guest=rooms.createSession("Guest");var calls=new AtomicInteger();
    IdentityStore store=new IdentityStore(){public boolean enabled(){return true;}public String findOrCreateAccount(String issuer,String subject){calls.incrementAndGet();return UUID.randomUUID().toString();}};
    var auth=new GoogleAuthService(rooms,store,(t,a,n)->new GoogleCredentialVerifier.Principal("https://accounts.google.com","not-allowlisted"),"client",Set.of(ORIGIN),Clock.systemUTC(),policy());
    assertEquals("managed",auth.config().collectionMode());assertTrue(auth.config().enabled());assertFalse(auth.config().experimentReady());
    assertThrows(RoomService.Failure.class,()->auth.nonce(guest,"https://evil.test"));
    auth.nonce(guest,ORIGIN);assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"fake",false));assertEquals(0,calls.get());
    auth.nonce(guest,ORIGIN);assertTrue(auth.login(guest,ORIGIN,"fake",true).signedIn());assertEquals(1,calls.get());assertEquals(guest,rooms.authenticate(guest.token()));
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"fake",true));assertEquals(1,calls.get());
    assertFalse(auth.logout(guest,ORIGIN).signedIn());assertNull(rooms.accountId(guest));assertEquals(guest,rooms.authenticate(guest.token()));assertNotNull(rooms.create(guest,1));
  }
  @Test void actualManagedLoginSavesAllConsentedHumansButNeverMixedGuestMatch(){
    for(boolean mixed:List.of(false,true)){
      var f=new PlayFlowTest();f.clock=new RoomServiceTest.MutableClock();f.clock.now.set(Instant.parse("2026-11-09T00:00:00Z").toEpochMilli());
      f.service=new RoomService(f.clock);f.service.experimentPolicy(policy());f.service.quarterRetentionPolicy(policy(),false);
      var store=new MatchPersistenceTest.RecordingStore();f.service.statsStore(store);f.players=new RoomService.Identity[4];
      IdentityStore identity=new IdentityStore(){public boolean enabled(){return true;}public String findOrCreateAccount(String issuer,String subject){return UUID.nameUUIDFromBytes(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}};
      var auth=new GoogleAuthService(f.service,identity,(token,aud,nonce)->new GoogleCredentialVerifier.Principal("https://accounts.google.com",token),"client",Set.of(ORIGIN),Clock.systemUTC(),policy());
      for(int i=0;i<4;i++){f.players[i]=f.service.createSession("P"+i);if(!(mixed&&i==3)){auth.nonce(f.players[i],ORIGIN);assertTrue(auth.login(f.players[i],ORIGIN,"GoogleSubject"+i,true).signedIn());}}
      f.room=f.service.create(f.players[0],1).roomId();
      for(int i=0;i<4;i++){if(i>0)f.service.join(f.room,f.players[i]);f.service.connect(f.room,f.players[i],"socket-"+i);f.service.ready(f.room,f.players[i],true);}
      f.start();f.auctionToPlay();f.playRemainingDeal();assertEquals(mixed?0:1,store.writes.size());
      if(mixed)assertEquals("DISABLED",f.service.persistenceStatus(f.room,f.view(0).game().gameId(),f.players[0]));
    }
  }
  @Test void managedQuarterRulesApplyWithoutFalseVerificationClaim(){
    var f=new MatchPersistenceTest().fixture(new MatchPersistenceTest.RecordingStore(),null,Instant.parse("2026-03-31T15:55:00Z").toEpochMilli());
    f.service.quarterRetentionPolicy(policy(),false);f.auctionToPlay();f.playRemainingDeal();String account=f.service.accountId(f.players[0]);
    f.clock.now.set(Instant.parse("2026-03-31T16:00:00Z").toEpochMilli());
    assertEquals("ROOM_NOT_FOUND",assertThrows(RoomService.Failure.class,()->f.view(0)).code);assertEquals(account,f.service.accountId(f.players[0]));
  }
}
