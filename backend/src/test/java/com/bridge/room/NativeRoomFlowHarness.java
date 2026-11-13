package com.bridge.room;
import com.bridge.persistence.*;import java.time.*;import static org.junit.jupiter.api.Assertions.*;
/** Real Spring service and injected policies/stores stay intact. Only test clocks are replaced. */
public final class NativeRoomFlowHarness {
 private final PlayFlowTest flow=new PlayFlowTest();
 public NativeRoomFlowHarness(RoomService actual,Clock wallclock){flow.clock=new RoomServiceTest.MutableClock();flow.clock.now.set(wallclock.millis());flow.service=actual;org.springframework.test.util.ReflectionTestUtils.setField(actual,"clock",wallclock);org.springframework.test.util.ReflectionTestUtils.setField(actual,"ticker",(java.util.function.LongSupplier)flow.clock::millis);}
 public void start(StatsStore store,QuarterMaintenanceGate gate,String category,int deals,String account){
  assertTrue(flow.service.statisticsEnabled());assertSame(store,org.springframework.test.util.ReflectionTestUtils.getField(flow.service,"statsStore"));assertTrue(gate.acceptsNewGames());
  flow.players=new RoomService.Identity[4];for(int i=0;i<4;i++){flow.players[i]=flow.service.createSession("P"+i);flow.service.statisticsConsent(flow.players[i],true,StatisticsService.CONSENT_VERSION);if(i==0)flow.service.linkAccountOutsideRoom(flow.players[i],account,true);}
  flow.room=flow.service.create(flow.players[0],deals,category).roomId();for(int i=0;i<4;i++){if(i>0)flow.service.join(flow.room,flow.players[i]);flow.service.connect(flow.room,flow.players[i],"socket-"+i);flow.service.ready(flow.room,flow.players[i],true);}flow.start();flow.auctionToPlay();
 }
 public String matchId(){return flow.view(0).game().gameId();}
 public void finishDeal(){synchronized(flow.service){flow.playRemainingDeal();}}
 public void assertTerminal(){assertEquals("MATCH_RESULT",flow.view(1).phase());}
 public void leaveFinishedRoom(){for(int i=0;i<4;i++)flow.service.leave(flow.room,flow.players[i]);}
}
