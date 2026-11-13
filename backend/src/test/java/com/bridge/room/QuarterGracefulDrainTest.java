package com.bridge.room;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QuarterGracefulDrainTest {
  @Test void formalAndCasualExistingMatchesFinishBeforeFreeze() {
    for(String type:new String[]{"FORMAL","CASUAL"}) {
      var f=FixedRoomModeTest.fixture(type,1);
      var discarded=new java.util.HashSet<String>();f.service.quarterUncollectedMatch(discarded::add);
      assertFalse(f.service.drainAndFreezeForQuarterMaintenance());
      assertFalse(f.service.quarterMaintenance());
      var newcomer=f.service.createSession("New");
      assertThrows(RoomService.Failure.class,()->f.service.create(newcomer,1,type));
      assertThrows(RoomService.Failure.class,()->f.service.join(f.room,newcomer));
      // Repeated maintenance polling has no timeout-forfeit override.
      for(int i=0;i<100;i++)assertFalse(f.service.drainAndFreezeForQuarterMaintenance());
      assertNull(f.view(0).game().matchResult());
      int steps=0;
      while(!f.view(0).phase().equals("MATCH_RESULT")&&steps++<200) {
        if(f.view(0).phase().equals("TRICK_RESULT")){f.finishTrickDisplay();continue;}
        int seat=f.view(0).game().play().nextSeat();var s=f.view(seat);
        f.play(seat,s.you().legalCards().get(0),PlayFlowTest.action(),s.revision(),s.game().dealId());
      }
      assertEquals("MATCH_RESULT",f.view(0).phase());assertEquals("SCORED",f.view(0).game().matchResult().outcome());
      assertTrue(discarded.contains(f.view(0).game().gameId()));
      assertTrue(f.service.drainAndFreezeForQuarterMaintenance());assertTrue(f.service.quarterMaintenance());
      var before=f.view(0);FixedRoomModeTest.tick(f,3_600_000);assertEquals(before.revision(),f.view(0).revision());
      assertThrows(RoomService.Failure.class,()->f.service.start(f.room,f.players[0],PlayFlowTest.action(),before.revision()));
    }
  }
}
