package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RedealFlowTest {
  RoomServiceTest.MutableClock clock;
  RoomService service;
  RoomService.Identity[] players;
  String room;
  int eligible;
  String action(){return UUID.randomUUID().toString();}
  RoomService.Snapshot view(int seat){return service.get(room,players[seat]);}
  @BeforeEach void setup() {
    for(int attempt=0;attempt<1000;attempt++) {
      clock=new RoomServiceTest.MutableClock();service=new RoomService(clock);players=new RoomService.Identity[4];
      for(int i=0;i<4;i++)players[i]=service.createSession("P"+i);
      room=service.create(players[0]).roomId();
      for(int i=0;i<4;i++){if(i>0)service.join(room,players[i]);service.connect(room,players[i],"s"+i);service.ready(room,players[i],true);}
      service.start(room,players[0],action(),view(0).revision());
      for(int seat=0;seat<4;seat++)if(view(seat).game().redeal().canChoose()){eligible=seat;return;}
    }
    throw new AssertionError("Could not find eligible deal");
  }
  RoomService.CommandResult choose(int seat,boolean redeal){var s=view(seat);return service.redeal(room,players[seat],s.game().gameId(),s.game().dealId(),action(),s.revision(),redeal);}
  void advance(long ms){clock.now.addAndGet(ms);service.expireInitialSubscriptions();}
  @Test void atomicRetryAndStaleCommandsCannotRedealTwice() throws Exception {
    var initial=view(eligible);String id=action();String old=initial.game().dealId();
    ExecutorService pool=Executors.newFixedThreadPool(2);
    try {
      var task=(Callable<RoomService.CommandResult>)()->service.redeal(room,players[eligible],initial.game().gameId(),old,id,initial.revision(),true);
      var one=pool.submit(task);var two=pool.submit(task);
      var a=one.get();var b=two.get();
      assertEquals(Set.of("APPLIED","REPLAYED"),Set.of(a.outcome(),b.outcome()));
      assertEquals(a.room().game().dealId(),b.room().game().dealId());
      assertEquals(1,view(eligible).game().redealCount());
      assertEquals("STALE_DEAL",assertThrows(RoomService.Failure.class,()->service.redeal(room,players[eligible],initial.game().gameId(),old,action(),view(eligible).revision(),true)).code);
      assertEquals("ACTION_ID_REUSED",assertThrows(RoomService.Failure.class,()->service.redeal(room,players[eligible],initial.game().gameId(),old,id,initial.revision(),false)).code);
    } finally {pool.shutdownNow();}
  }
  @Test void singleSeatDisconnectPausesAndReclaimDoesNotChangeHand() {
    var before=view(eligible);advance(3000);service.disconnect(room,players[eligible].playerId(),"s"+eligible);
    advance(9999);assertEquals(17000,view((eligible+1)%4).game().operationTimer().remainingMs());
    assertFalse(view(eligible).game().redeal().canChoose());
    service.connect(room,players[eligible],"returned");
    assertEquals(before.you().ownHand(),view(eligible).you().ownHand());
    assertTrue(view(eligible).game().redeal().canChoose());choose(eligible,false);
  }
  @Test void allHumanOfflineStopsBotsAndEndsWithoutRedealAfterGrace() {
    var old=view(0).game().dealId();
    for(int seat=0;seat<4;seat++)service.disconnect(room,players[seat].playerId(),"s"+seat);
    advance(9999);assertEquals("REDEAL_DECISION",view(0).phase());assertEquals(old,view(0).game().dealId());
    advance(1);assertEquals("MATCH_RESULT",view(0).phase());assertEquals("NO_HUMANS_CONNECTED",view(0).game().matchResult().reason());
    assertEquals(old,view(0).game().dealId());assertEquals(0,view(0).game().redealCount());
  }
  @Test void timelyReturnResumesRemainingWindowAndOtherExpiredSeatsCanTakeOver() {
    advance(4000);for(int seat=0;seat<4;seat++)service.disconnect(room,players[seat].playerId(),"s"+seat);
    advance(9999);service.connect(room,players[eligible],"returned");
    assertNull(view(eligible).allHumansReconnectDeadline());advance(1);
    assertFalse(view(eligible).game().paused());assertEquals(16000,view(eligible).game().operationTimer().remainingMs());
    assertTrue(view(eligible).game().redeal().canChoose());
  }
  @Test void automatedEligibleSeatRedealsOncePerPacedStepAndOldHumanCannotAct() {
    String old=view(eligible).game().dealId();service.disconnect(room,players[eligible].playerId(),"s"+eligible);
    advance(10000);var s=view(eligible);assertEquals("REDEAL_DECISION",s.phase());assertFalse(s.game().redeal().canChoose());
    assertEquals("CONTROL_TAKEN_OVER",assertThrows(RoomService.Failure.class,()->service.redeal(room,players[eligible],s.game().gameId(),s.game().dealId(),action(),s.revision(),false)).code);
    advance(899);assertEquals(old,view(eligible).game().dealId());advance(1);
    assertNotEquals(old,view(eligible).game().dealId());assertEquals(1,view(eligible).game().redealCount());
    assertEquals("HAND_REDEAL",view(eligible).game().lastRedealReason());
  }
  @Test void voluntaryExitReplacementUsesSameEligibilityWhileLastHumanExitDoesNotRedraw() {
    int observer=(eligible+1)%4;String old=view(observer).game().dealId();service.leave(room,players[eligible]);advance(0);
    advance(899);assertEquals(old,view(observer).game().dealId());advance(1);
    assertNotEquals(old,view(observer).game().dealId());
    for(int seat=0;seat<4;seat++)if(seat!=eligible)service.leave(room,players[seat]);
    assertTrue(service.list().isEmpty());
  }
  @Test void invalidUnauthorizedAndPrivateHttpProjection() throws Exception {
    MockMvc mvc=MockMvcBuilders.standaloneSetup(new RoomController(service)).addFilters(new BetaRequestFilter(service)).build();
    var s=view(eligible);String path="/api/rooms/"+room+"/games/"+s.game().gameId()+"/deals/"+s.game().dealId()+"/redeal";
    String body="{\"actionId\":\""+action()+"\",\"expectedRevision\":"+s.revision()+",\"redeal\":false}";
    mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    mvc.perform(put(path).header("Authorization","Bearer "+players[eligible].token()).contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REDEAL"));
    var outsider=service.createSession("out");
    mvc.perform(put(path).header("Authorization","Bearer "+outsider.token()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    mvc.perform(get("/api/rooms/"+room).header("Authorization","Bearer "+players[eligible].token()))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
      .andExpect(jsonPath("$.game.redeal.yourPoints").value(s.game().redeal().yourPoints()))
      .andExpect(jsonPath("$.game.redeal.eligibleSeats").doesNotExist())
      .andExpect(jsonPath("$.players[0].redeal").doesNotExist());
    mvc.perform(put(path).header("Authorization","Bearer "+players[eligible].token()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
  }
}
