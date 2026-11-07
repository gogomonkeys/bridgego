package com.bridge.room;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExperimentRoomLimitTest {
  final RoomServiceTest.MutableClock clock=new RoomServiceTest.MutableClock();
  final RoomService rooms=new RoomService(clock);
  ExperimentRoomLimitTest(){clock.now.set(Instant.parse("2026-11-07T08:00:00Z").toEpochMilli());rooms.experimentPolicy(CollectionPolicy.experiment(new ObjectMapper(),ExperimentAdmissionTest.ID,"contact","retention",ExperimentAdmissionTest.DATASET,"2026-11-07T09:00:00Z",clock));}
  String prepared(RoomService.Identity who){String room=rooms.create(who,1).roomId();rooms.connect(room,who,UUID.randomUUID().toString());for(int seat=1;seat<4;seat++)rooms.addComputer(room,who,UUID.randomUUID().toString(),rooms.get(room,who).revision(),seat);rooms.ready(room,who,true);return room;}
  void start(String r,RoomService.Identity p){rooms.start(r,p,UUID.randomUUID().toString(),rooms.get(r,p).revision());}
  @Test void guestDoesNotConsumeTesterSlotAndDifferentRoomCannotStartSecondLinkedGame(){var guest=rooms.createSession("guest");String guestRoom=prepared(guest);start(guestRoom,guest);rooms.leave(guestRoom,guest);String account=UUID.randomUUID().toString();var first=rooms.createSession("Test");rooms.linkAccountOutsideRoom(first,account,true);start(prepared(first),first);var other=rooms.createSession("Other");rooms.linkAccountOutsideRoom(other,account,true);String second=prepared(other);assertEquals("EXPERIMENT_MATCH_LIMIT",assertThrows(RoomService.Failure.class,()->start(second,other)).code);}
  @Test void expiredLinkedTesterCannotStartButGuestStillCanPlay(){var p=rooms.createSession("Test");rooms.linkAccountOutsideRoom(p,UUID.randomUUID().toString(),true);String r=prepared(p);clock.now.addAndGet(3600000);rooms.connect(r,p,"reconnected");rooms.ready(r,p,true);assertEquals("EXPERIMENT_EXPIRED",assertThrows(RoomService.Failure.class,()->start(r,p)).code);var guest=rooms.createSession("guest");assertDoesNotThrow(()->start(prepared(guest),guest));}
  @Test void acceptedStartReplayDoesNotConsumeTwice(){var p=rooms.createSession("Test");rooms.linkAccountOutsideRoom(p,UUID.randomUUID().toString(),true);String r=prepared(p);String action=UUID.randomUUID().toString();long rev=rooms.get(r,p).revision();rooms.start(r,p,action,rev);assertEquals("REPLAYED",rooms.start(r,p,action,rev).outcome());}
}
