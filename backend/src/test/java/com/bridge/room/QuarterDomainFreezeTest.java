package com.bridge.room;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuarterDomainFreezeTest {
  @Test void formalAndCasualFreezeNeverInventForfeitOrBotTakeover() {
    for(String type: new String[]{"FORMAL","CASUAL"}) {
      var f=FixedRoomModeTest.fixture(type,3);
      var before=f.service.get(f.room,f.players[0]);
      assertFalse(f.service.freezeForQuarterMaintenance());
      for(int i=0;i<4;i++)f.service.disconnect(f.room,f.players[i].playerId(),"socket-"+i);
      FixedRoomModeTest.tick(f,3_600_000);
      var after=f.service.get(f.room,f.players[0]);
      assertEquals(before.revision(),after.revision());
      assertEquals(before.phase(),after.phase());
      assertNull(after.game().matchResult());
      assertThrows(IllegalStateException.class,f.service::finishQuarterMaintenance);
      assertThrows(RoomService.Failure.class,()->f.service.createSession("Blocked"));
    }
  }
  @Test void emptyDomainCanReopenOnlyWhenNotShuttingDown() {
    var service=new RoomService();
    assertTrue(service.freezeForQuarterMaintenance());
    assertTrue(service.quarterMaintenance());
    service.finishQuarterMaintenance();
    assertNotNull(service.createSession("Open"));
    service.beginMaintenance();
    assertTrue(service.freezeForQuarterMaintenance());
    assertThrows(IllegalStateException.class,service::finishQuarterMaintenance);
  }
}
