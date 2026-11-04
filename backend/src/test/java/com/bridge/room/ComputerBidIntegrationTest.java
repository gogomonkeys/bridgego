package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ComputerBidIntegrationTest {
  @Test void normalBotsAndDisconnectedTakeoversUseOwnHandAtAllSeats() {
    for (boolean takeover : new boolean[]{false, true}) for (int seat = 0; seat < 4; seat++)
      for (int[] boundary : new int[][]{{4,2,8,2},{5,1,4,1},{5,2,8,3},{6,1,4,1},{6,2,8,4},{7,3,12,4},
          {4,0,7,1},{4,0,8,2},{4,0,9,2},{5,1,7,1},{5,1,8,2},{5,1,9,2},
          {6,0,7,1},{6,0,8,2},{6,0,9,2},{7,1,7,1},{7,1,8,2},{7,1,9,2}})
      for (int highestLevel : new int[]{1, 2, 3, 4}) {
        var clock = new RoomServiceTest.MutableClock();
        var service = new RoomService(clock);
        var players = new RoomService.Identity[4];
        for (int i = 0; i < 4; i++) players[i] = service.createSession("P" + i);
        String roomId = service.create(players[0]).roomId();
        for (int i = 0; i < 4; i++) {
          if (i > 0) service.join(roomId, players[i]);
          service.connect(roomId, players[i], "socket-" + i);
          service.ready(roomId, players[i], true);
        }
        service.start(roomId, players[0], UUID.randomUUID().toString(), service.get(roomId, players[0]).revision());
        KeepDealFixture.keep(service, roomId, players);
        for (var player : players) {
          var s = service.get(roomId, player);
          service.privilege(roomId, player, s.game().gameId(), s.game().dealId(), UUID.randomUUID().toString(), s.revision(), false);
        }
        @SuppressWarnings("unchecked")
        var rooms = (Map<String, RoomService.Room>) ReflectionTestUtils.getField(service, "rooms");
        var room = rooms.get(roomId);
        var game = room.game;
        game.hands.put(seat, boundary[2] >= 7 && boundary[2] <= 9 && boundary[1] < 2
            ? ComputerBidCeilingTest.pointHand("HEART", boundary[0], boundary[2], boundary[1] == 1)
            : ComputerBidCeilingTest.hand("HEART", boundary[0], boundary[1]));
        game.nextSeat = seat;
        game.highest = new GameState.Bid((seat + 1) % 4, highestLevel, "NO_TRUMP");
        room.computerTargetDeal = game.dealId;
        room.computerTargets.put(seat, "HEART");
        if (takeover) {
          service.disconnect(roomId, players[seat].playerId(), "socket-" + seat);
          clock.now.addAndGet(10000); service.expireInitialSubscriptions();
        } else service.leave(roomId, players[seat]);
        service.expireInitialSubscriptions();
        clock.now.addAndGet(900); service.expireInitialSubscriptions();
        var calls = service.get(roomId, players[(seat + 1) % 4]).game().auction().calls();
        assertEquals(1, calls.size());
        var call = calls.get(0);
        assertEquals(seat, call.seat());
        assertEquals(takeover ? "BOT_TAKEOVER" : "BOT", call.source());
        int ceiling = boundary[3];
        boolean eligible = highestLevel < ceiling;
        assertEquals(eligible ? "BID" : "PASS", call.type());
        assertEquals(eligible ? Integer.valueOf(highestLevel + 1) : null, call.level());
        assertEquals(eligible ? "HEART" : null, call.strain());
      }
  }
}
