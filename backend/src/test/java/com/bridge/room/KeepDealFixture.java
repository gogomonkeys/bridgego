package com.bridge.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

/** Explicitly decline optional redeals in tests whose subject begins at privilege or play. */
final class KeepDealFixture {
  private KeepDealFixture() {}

  static int keep(RoomService service, String room, RoomService.Identity[] players) {
    int choices = 0;
    for (var player : players) {
      var snapshot = service.get(room, player);
      if (snapshot.game().redeal().canChoose()) {
        service.redeal(room, player, snapshot.game().gameId(), snapshot.game().dealId(),
            UUID.randomUUID().toString(), snapshot.revision(), false);
        choices++;
      }
    }
    assertEquals("PRIVILEGE_DECISION", service.get(room, players[0]).phase());
    return choices;
  }

  static void keep(GameState game) {
    for (int seat = 0; seat < 4; seat++)
      if (game.canChooseRedeal(seat, false)) game.chooseRedeal(seat, false);
    assertEquals("PRIVILEGE_DECISION", game.phase);
  }
}
