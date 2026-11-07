package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import com.bridge.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/** Real RSA verification and production identity/stat stores, with no Google or Sheets network IO. */
class GoogleSheetsLoginIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ORIGIN = "https://bridge.example";
  private static final String CLIENT = "offline-test-client";
  private static final String ISSUER = "https://accounts.google.com";
  private static final String DATASET = "11111111-2222-4333-8444-555555555555";
  private static KeyPair signingKey;

  private RoomServiceTest.MutableClock clock;
  private RoomService rooms;
  private FakeSheets fake;
  private SheetsGateway isolated;
  private GoogleAuthService auth;

  @BeforeAll static void createSigningKey() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    signingKey = generator.generateKeyPair();
  }

  @BeforeEach void setup() {
    clock = new RoomServiceTest.MutableClock();
    clock.now.set(Instant.parse("2026-11-06T10:00:00Z").toEpochMilli());
    rooms = new RoomService(clock);
    fake = new FakeSheets(clock.instant());
    isolated = new TestDatasetGateway(fake, DATASET);
    auth = authFor(rooms);
  }

  private GoogleAuthService authFor(RoomService sessions) {
    Set<GoogleCredentialVerifier.Principal> testers = new HashSet<>();
    for (int i = 0; i < 4; i++) testers.add(new GoogleCredentialVerifier.Principal(ISSUER, "tester-" + i));
    var verifier = new GoogleJwtVerifier(JSON, clock, kid -> {
      if (!kid.equals("offline-key")) throw new IllegalArgumentException();
      return signingKey.getPublic();
    });
    return new GoogleAuthService(sessions, new SheetsIdentityStore(isolated, clock), verifier,
        CLIENT, Set.of(ORIGIN), clock, new CollectionPolicy(testers, "offline contact", "offline policy"));
  }

  private Map<String,Object> claims(String subject, String nonce) {
    var result = new HashMap<String,Object>();
    result.put("iss", "accounts.google.com"); // the verifier must canonicalize this before admission
    result.put("sub", subject);
    result.put("aud", CLIENT);
    result.put("iat", clock.instant().getEpochSecond() - 1);
    result.put("exp", clock.instant().getEpochSecond() + 300);
    result.put("nonce", nonce);
    result.put("email", "synthetic-person@example.test");
    result.put("name", "Synthetic Google Name");
    result.put("picture", "https://example.test/synthetic-avatar");
    return result;
  }

  private String token(Map<String,Object> claims, KeyPair key) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "RS256", "kid", "offline-key")))
        + "." + encoder.encodeToString(JSON.writeValueAsBytes(claims));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(key.getPrivate());
    signature.update(input.getBytes(StandardCharsets.US_ASCII));
    return input + "." + encoder.encodeToString(signature.sign());
  }

  private String login(GoogleAuthService service, RoomService sessions, RoomService.Identity guest, String subject)
      throws Exception {
    var nonce = service.nonce(guest, ORIGIN);
    String credential = token(claims(subject, nonce.nonce()), signingKey);
    assertTrue(service.login(guest, ORIGIN, credential, true).signedIn());
    assertEquals(guest, sessions.authenticate(guest.token()));
    assertFalse(fake.allText().contains(guest.token()));
    assertFalse(fake.allText().contains(credential));
    return sessions.accountId(guest);
  }

  @Test void signedLoginPersistsOnlyCanonicalMappingAndReusesItAfterRestart() throws Exception {
    var guest = rooms.createSession("Nicky");
    String account = login(auth, rooms, guest, "tester-0");
    assertEquals(UUID.fromString(account).toString(), account);
    assertEquals(List.of(List.of(account, "1")), fake.data("accounts"));
    assertEquals(List.of(List.of(ISSUER, "tester-0", account, "1")), fake.data("auth_identities"));
    assertEquals(1, fake.appends);
    assertEquals(Set.of("accounts", "auth_identities"), fake.lastAppend.keySet());
    for (String excluded : List.of("Nicky", "Synthetic Google Name", "synthetic-person@example.test", "synthetic-avatar"))
      assertFalse(fake.allText().contains(excluded));

    assertFalse(auth.logout(guest, ORIGIN).signedIn());
    assertEquals(1, fake.data("accounts").size()); // logout is not account erasure
    var restarted = new RoomService(clock);
    var newGuest = restarted.createSession("Fresh");
    var newAuth = authFor(restarted);
    assertFalse(newAuth.status(newGuest).signedIn()); // durable mappings never restore bearer sessions
    assertEquals(account, login(newAuth, restarted, newGuest, "tester-0"));
    assertNotEquals(guest.token(), newGuest.token());
    assertEquals(1, fake.appends);
  }

  @Test void timeoutAfterAtomicCommitReadsBackWithoutCreatingDuplicateAccount() throws Exception {
    fake.timeoutAfterCommit = true;
    var guest = rooms.createSession("Guest");
    String account = login(auth, rooms, guest, "tester-0");
    assertEquals(1, fake.appends);
    assertEquals(1, fake.data("accounts").size());
    assertEquals(account, login(auth, rooms, guest, "tester-0"));
    assertEquals(1, fake.appends);
  }

  @Test void failedSwitchLeavesGuestAndPreservesOnlyPreviouslyConfirmedMapping() throws Exception {
    var guest = rooms.createSession("Guest");
    String old = login(auth, rooms, guest, "tester-0");
    var nonce = auth.nonce(guest, ORIGIN);
    assertFalse(auth.status(guest).signedIn());
    String credential = token(claims("tester-1", nonce.nonce()), signingKey);
    fake.failBeforeCommit = 403;
    assertEquals("IDENTITY_STORAGE_UNAVAILABLE", assertThrows(RoomService.Failure.class,
        () -> auth.login(guest, ORIGIN, credential, true)).code);
    assertFalse(auth.status(guest).signedIn());
    assertEquals(List.of(List.of(old, "1")), fake.data("accounts"));
    assertEquals(1, fake.data("auth_identities").size());
    int attempts = fake.appends;
    assertEquals("AUTH_NONCE_EXPIRED", assertThrows(RoomService.Failure.class,
        () -> auth.login(guest, ORIGIN, credential, true)).code);
    assertEquals(attempts, fake.appends);
  }

  @Test void invalidReadbackNeverReportsSignedIn() throws Exception {
    fake.corruptReadback = true;
    var guest = rooms.createSession("Guest");
    var nonce = auth.nonce(guest, ORIGIN);
    String credential = token(claims("tester-0", nonce.nonce()), signingKey);
    assertEquals("IDENTITY_STORAGE_UNAVAILABLE", assertThrows(RoomService.Failure.class,
        () -> auth.login(guest, ORIGIN, credential, true)).code);
    assertFalse(auth.status(guest).signedIn());
    assertEquals(1, fake.appends); // fail closed, no blind retry of an integrity conflict
  }

  @Test void cancelAfterStorageStartsCannotUndoMappingButPreventsSessionAttachment() throws Exception {
    var guest = rooms.createSession("Guest");
    var nonce = auth.nonce(guest, ORIGIN);
    String credential = token(claims("tester-0", nonce.nonce()), signingKey);
    fake.afterCommit = () -> auth.cancel(guest, ORIGIN, nonce.nonce());
    assertEquals("AUTH_CANCELLED", assertThrows(RoomService.Failure.class,
        () -> auth.login(guest, ORIGIN, credential, true)).code);
    assertFalse(auth.status(guest).signedIn());
    assertEquals(1, fake.data("accounts").size());
    fake.afterCommit = () -> {};
    assertEquals(fake.data("accounts").get(0).get(0), login(auth, rooms, guest, "tester-0"));
    assertEquals(1, fake.appends);
  }

  @Test void verifyOnlyAndRejectedSignedClaimsNeverReachSheets() throws Exception {
    var guest = rooms.createSession("Guest");
    var nonce = auth.nonce(guest, ORIGIN, "verify");
    var result = auth.verifyOnly(guest, ORIGIN, token(claims("tester-0", nonce.nonce()), signingKey), true);
    assertEquals(ISSUER, result.issuer());
    assertTrue(result.admittedTester());
    assertFalse(auth.status(guest).signedIn());
    assertEquals(0, fake.reads);
    for (var invalid : Map.<String,Object>of("iss", "https://evil.example", "aud", "different-client",
        "exp", clock.instant().getEpochSecond(), "nonce", "not-this-session", "sub", "not-admitted").entrySet()) {
      nonce = auth.nonce(guest, ORIGIN);
      var claims = claims("tester-0", nonce.nonce());
      claims.put(invalid.getKey(), invalid.getValue());
      String credential = token(claims, signingKey);
      assertThrows(RoomService.Failure.class, () -> auth.login(guest, ORIGIN, credential, true), invalid.getKey());
      assertFalse(auth.status(guest).signedIn());
    }
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    nonce = auth.nonce(guest, ORIGIN);
    String forged = token(claims("tester-0", nonce.nonce()), generator.generateKeyPair());
    assertThrows(RoomService.Failure.class, () -> auth.login(guest, ORIGIN, forged, true));
    assertEquals(0, fake.reads);
    assertEquals(0, fake.appends);
  }

  @Test void wrongDatasetOrMissingQuarterGatePreventsMapping() throws Exception {
    var guest = rooms.createSession("Guest");
    fake.tabs.put("dataset", List.of(List.of("purpose", "dataset_id"), List.of("bridgego-restricted-test", "wrong")));
    assertThrows(RoomService.Failure.class, () -> login(auth, rooms, guest, "tester-0"));
    assertFalse(auth.status(guest).signedIn());
    assertEquals(0, fake.appends);
    fake.tabs.put("dataset", List.of(List.of("purpose", "dataset_id"), List.of("bridgego-restricted-test", DATASET)));
    fake.tabs.put("retention_ledger", List.of(RetentionLedger.HEADER));
    assertThrows(RoomService.Failure.class, () -> login(auth, rooms, guest, "tester-0"));
    assertFalse(auth.status(guest).signedIn());
    assertEquals(0, fake.appends);
  }

  @Test void admittedSignedPlayersPersistOneFinalMatchWithFrozenAccountIds() throws Exception {
    try (var stats = new SheetsStatsStore(isolated, 2, clock)) {
      var game = game(stats, false);
      List<String> accounts = Arrays.stream(game.players).map(rooms::accountId).toList();
      assertTrue(stats.exportCommitted().isEmpty());
      assertEquals("AUTH_IN_ROOM", assertThrows(RoomService.Failure.class,
          () -> auth.nonce(game.players[0], ORIGIN)).code);
      game.auctionToPlay();
      game.playRemainingDeal();
      awaitSave(game, "CONFIRMED");
      var saved = stats.exportCommitted();
      assertEquals(1, saved.size());
      var envelope = saved.get(0);
      for (int seat = 0; seat < 4; seat++) {
        var person = envelope.payload().path("participants").get(seat);
        assertEquals(accounts.get(seat), person.path("account_id").asText());
        assertTrue(person.path("guest_id").isNull());
      }
      String before = envelope.canonicalJson();
      rooms.leave(game.room, game.players[0]);
      auth.logout(game.players[0], ORIGIN);
      String switched = login(auth, rooms, game.players[0], "tester-1");
      assertNotEquals(accounts.get(0), switched);
      assertEquals(before, stats.exportCommitted().get(0).canonicalJson());
      assertEquals(accounts.get(0), stats.exportCommitted().get(0).payload().path("participants").get(0).path("account_id").asText());
      for (String excluded : List.of("tester-", "P0", "synthetic-person@example.test", "nonce", "credential", "hands"))
        assertFalse(before.contains(excluded));
    }
  }

  @Test void aGuestExcludesTheWholeMatchFromDurableStats() throws Exception {
    try (var stats = new SheetsStatsStore(isolated, 2, clock)) {
      var game = game(stats, true);
      assertNull(rooms.accountId(game.players[3]));
      game.auctionToPlay();
      game.playRemainingDeal();
      assertEquals("DISABLED", rooms.persistenceStatus(game.room, game.view(0).game().gameId(), game.players[0]));
      assertTrue(stats.exportCommitted().isEmpty());
      assertEquals(3, fake.data("auth_identities").size());
      assertTrue(fake.data("commits").isEmpty());
      assertTrue(fake.data("envelopes").isEmpty());
    }
  }

  @Test void oneSignedHumanAndThreeBotsSaveOneCompletedDeal() throws Exception {
    oneHumanThreeBots(true);
  }

  @Test void oneGuestAndThreeBotsNeverCreateAnEnvelope() throws Exception {
    oneHumanThreeBots(false);
  }

  private void oneHumanThreeBots(boolean linked) throws Exception {
    try (var stats = new SheetsStatsStore(isolated, 2, clock)) {
      rooms.statsStore(stats);
      var host = rooms.createSession("Test");
      String account = linked ? login(auth, rooms, host, "tester-0") : null;
      String room = rooms.create(host, 1).roomId();
      rooms.connect(room, host, "synthetic-socket");
      for (int seat = 1; seat < 4; seat++)
        rooms.addComputer(room, host, UUID.randomUUID().toString(), rooms.get(room, host).revision(), seat);
      rooms.ready(room, host, true);
      rooms.start(room, host, UUID.randomUUID().toString(), rooms.get(room, host).revision());
      assertTrue(fake.data("commits").isEmpty());
      int moves = 0;
      while (!rooms.get(room, host).phase().equals("MATCH_RESULT") && moves++ < 1000) {
        var s = rooms.get(room, host); var g = s.game(); String action = UUID.randomUUID().toString();
        if (g.redeal().canChoose()) rooms.redeal(room, host, g.gameId(), g.dealId(), action, s.revision(), false);
        else if (g.privilege().canChoose()) rooms.privilege(room, host, g.gameId(), g.dealId(), action, s.revision(), false);
        else if (!s.you().legalCalls().isEmpty()) rooms.call(room, host, g.gameId(), g.dealId(), action, s.revision(), "PASS", null, null);
        else if (!s.you().legalCards().isEmpty()) rooms.play(room, host, g.gameId(), g.dealId(), action, s.revision(), s.you().legalCards().get(0));
        else if (s.you().canAcknowledgeResult()) rooms.acknowledgeResult(room, host, g.gameId(), g.dealId(), action, s.revision());
        clock.now.addAndGet(1000); rooms.expireInitialSubscriptions();
      }
      var result = rooms.get(room, host);
      assertEquals("MATCH_RESULT", result.phase()); assertEquals(1, result.game().completedDeals());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      String status;
      do {
        status = rooms.persistenceStatus(room, result.game().gameId(), host);
        if (!status.equals("PENDING")) break;
        Thread.sleep(10);
      } while (System.nanoTime() < deadline);
      assertEquals(linked ? "CONFIRMED" : "DISABLED", status);
      if (!linked) {
        assertEquals(0, fake.appends); assertTrue(fake.data("envelopes").isEmpty());
        assertTrue(fake.data("commits").isEmpty()); assertTrue(fake.data("auth_identities").isEmpty());
        return;
      }
      var saved = stats.exportCommitted(); assertEquals(1, saved.size());
      var people = saved.get(0).payload().path("participants");
      assertEquals(account, people.get(0).path("account_id").asText());
      assertEquals("HUMAN", people.get(0).path("control_type").asText());
      for (int seat = 1; seat < 4; seat++) {
        assertEquals("BOT", people.get(seat).path("player_type").asText());
        assertTrue(people.get(seat).path("account_id").isNull());
        assertTrue(people.get(seat).path("guest_id").isNull());
      }
      assertEquals(2, fake.appends); // one identity batch plus one final-result batch
    }
  }

  private PlayFlowTest game(SheetsStatsStore stats, boolean withGuest) throws Exception {
    var game = new PlayFlowTest();
    game.clock = clock;
    game.service = rooms;
    game.players = new RoomService.Identity[4];
    rooms.statsStore(stats);
    for (int i = 0; i < 4; i++) {
      game.players[i] = rooms.createSession("P" + i);
      if (i != 3 || !withGuest) login(auth, rooms, game.players[i], "tester-" + i);
    }
    game.room = rooms.create(game.players[0], 1).roomId();
    for (int i = 0; i < 4; i++) {
      if (i > 0) rooms.join(game.room, game.players[i]);
      rooms.connect(game.room, game.players[i], "socket-" + i);
      rooms.ready(game.room, game.players[i], true);
    }
    game.start();
    return game;
  }

  private void awaitSave(PlayFlowTest game, String expected) throws Exception {
    String id = game.view(0).game().gameId();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    String status;
    do {
      status = rooms.persistenceStatus(game.room, id, game.players[0]);
      if (!status.equals("PENDING")) break;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    assertEquals(expected, status);
  }

  @Test void oneUseExperimentRealRsaLoginAndNormalOneDealSaveWithoutProductionReadiness() throws Exception {
    var settings=new ExperimentStorage.Settings("synthetic_experiment_sheet",DATASET,ISSUER,"tester-0",clock.instant().plusSeconds(3600));
    Map<String,List<List<String>>> tabs=new HashMap<>();
    tabs.put("dataset",List.of(List.of("purpose","dataset_id"),List.of(ExperimentStorage.PURPOSE,DATASET)));
    tabs.put("accounts",List.of(List.of("account_id","schema_version")));tabs.put("auth_identities",List.of(List.of("issuer","subject","account_id","schema_version")));
    tabs.put("envelopes",List.of(List.of("commit_id","payload_hash","chunk_index","chunk_count","canonical_json_chunk")));
    tabs.put("commits",List.of(List.of("commit_id","payload_hash","chunk_count","match_id","revision")));
    tabs.put("erasure_ledger",List.of(List.of("kind","identifier_hash","schema_version"),List.of("META","erasure-ledger-v1","1")));
    tabs.put("retention_ledger",List.of(RetentionLedger.HEADER));
    tabs.put("quarterly_aggregates",List.of(QuarterlyRetention.AGGREGATE_HEADER));
    int[] claims={0};ExperimentGateway gateway=new ExperimentGateway(){
      public synchronized Snapshot readComplete(){return new Snapshot(settings.sheetId(),true,tabs);}
      public synchronized void claimAtomic(String guard,List<List<String>> rows,Map<String,List<List<Object>>> additions){if(tabs.containsKey(guard))throw new IllegalStateException();claims[0]++;tabs.put(guard,rows);additions.forEach((name,values)->{var all=new ArrayList<>(tabs.get(name));values.forEach(r->all.add(r.stream().map(Object::toString).toList()));tabs.put(name,all);});}
    };
    try(var store=new ExperimentStorage(settings,gateway,clock)){
      assertEquals("VERIFIED",store.initializeEmpty(store.previewEmptyDigest(),true,true));
      var policy=CollectionPolicy.experiment(JSON,"[{\"issuer\":\"https://accounts.google.com\",\"subject\":\"tester-0\"}]","contact","single experimental deal, deletion unverified",DATASET,settings.expiresAt().toString(),clock);
      rooms.experimentPolicy(policy);rooms.statsStore(store);
      IdentityStore admitted=new IdentityStore(){public boolean enabled(){return store.admissionAvailable();}public String findOrCreateAccount(String issuer,String subject){return store.findOrCreateAccount(issuer,subject);}};
      var verifier=new GoogleJwtVerifier(JSON,clock,kid->signingKey.getPublic());
      var experimentAuth=new GoogleAuthService(rooms,admitted,verifier,CLIENT,Set.of(ORIGIN),clock,policy);
      var host=rooms.createSession("Test");assertFalse(experimentAuth.config().collectionReady());
      String account=login(experimentAuth,rooms,host,"tester-0");assertTrue(experimentAuth.status(host).signedIn());assertFalse(experimentAuth.config().enabled());
      String room=rooms.create(host,1).roomId();rooms.connect(room,host,"experiment-socket");
      for(int seat=1;seat<4;seat++)rooms.addComputer(room,host,UUID.randomUUID().toString(),rooms.get(room,host).revision(),seat);
      rooms.ready(room,host,true);rooms.start(room,host,UUID.randomUUID().toString(),rooms.get(room,host).revision());
      int moves=0;while(!rooms.get(room,host).phase().equals("MATCH_RESULT")&&moves++<1000){
        var view=rooms.get(room,host);var game=view.game();String action=UUID.randomUUID().toString();
        if(game.redeal().canChoose())rooms.redeal(room,host,game.gameId(),game.dealId(),action,view.revision(),false);
        else if(game.privilege().canChoose())rooms.privilege(room,host,game.gameId(),game.dealId(),action,view.revision(),false);
        else if(!view.you().legalCalls().isEmpty())rooms.call(room,host,game.gameId(),game.dealId(),action,view.revision(),"PASS",null,null);
        else if(!view.you().legalCards().isEmpty())rooms.play(room,host,game.gameId(),game.dealId(),action,view.revision(),view.you().legalCards().get(0));
        else if(view.you().canAcknowledgeResult())rooms.acknowledgeResult(room,host,game.gameId(),game.dealId(),action,view.revision());
        clock.now.addAndGet(1000);rooms.expireInitialSubscriptions();
      }
      var finalView=rooms.get(room,host);assertEquals("MATCH_RESULT",finalView.phase());
      long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);String status;
      do{status=rooms.persistenceStatus(room,finalView.game().gameId(),host);if(!status.equals("PENDING"))break;Thread.sleep(10);}while(System.nanoTime()<end);
      assertEquals("CONFIRMED",status);assertEquals(3,claims[0]);assertEquals(1,store.exportCommitted().size());assertEquals(account,store.exportCommitted().get(0).payload().path("participants").get(0).path("account_id").asText());
    }
    try(var restarted=new ExperimentStorage(settings,gateway,clock)){assertThrows(RuntimeException.class,()->restarted.findOrCreateAccount(ISSUER,"tester-0"));assertEquals(3,claims[0]);}
  }

  private static final class FakeSheets implements SheetsGateway {
    final Map<String,List<List<String>>> tabs = new HashMap<>();
    int reads;
    int appends;
    int failBeforeCommit;
    boolean timeoutAfterCommit;
    boolean corruptReadback;
    Runnable afterCommit = () -> {};
    Map<String,List<List<Object>>> lastAppend = Map.of();

    FakeSheets(Instant now) {
      Instant quarter = QuarterlyRetention.currentQuarterStart(now);
      tabs.put("dataset", List.of(List.of("purpose", "dataset_id"), List.of("bridgego-restricted-test", DATASET)));
      tabs.put("retention_ledger", List.of(RetentionLedger.HEADER,
          List.of(QuarterlyRetention.quarter(quarter), quarter.toString(), "COMPLETE", now.toString(), "1")));
      tabs.put("erasure_ledger", List.of(List.of("kind", "identifier_hash", "schema_version"),
          List.of("META", "erasure-ledger-v1", "1")));
      tabs.put("accounts", new ArrayList<>());
      tabs.put("auth_identities", new ArrayList<>());
    }

    @Override public synchronized List<List<String>> read(String tab) {
      reads++;
      return data(tab);
    }

    synchronized List<List<String>> data(String tab) {
      return tabs.getOrDefault(tab, List.of()).stream().map(List::copyOf).toList();
    }

    synchronized String allText() { return tabs.toString(); }

    @Override public synchronized void appendAtomic(Map<String,List<List<Object>>> rows) {
      appends++;
      lastAppend = Map.copyOf(rows);
      if (failBeforeCommit != 0) {
        int status = failBeforeCommit;
        failBeforeCommit = 0;
        throw new Failure(status, 0);
      }
      rows.forEach((tab, additions) -> {
        var values = new ArrayList<>(tabs.getOrDefault(tab, List.of()));
        additions.forEach(row -> values.add(row.stream().map(Object::toString).toList()));
        tabs.put(tab, values);
      });
      if (corruptReadback) tabs.put("auth_identities", List.of(List.of(ISSUER, "tester-0", UUID.randomUUID().toString(), "1")));
      afterCommit.run();
      if (timeoutAfterCommit) {
        timeoutAfterCommit = false;
        throw new Failure(0, 0);
      }
    }
  }
}
