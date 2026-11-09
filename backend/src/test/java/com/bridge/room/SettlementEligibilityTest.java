package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SettlementEligibilityTest {
  static List<RoomService.Member> humans() {
    List<RoomService.Member> result = new ArrayList<>();
    for (int seat = 0; seat < 4; seat++) {
      var id = new RoomService.Identity("token-" + seat, UUID.randomUUID().toString(), "PRIVATE NAME " + seat);
      var member = new RoomService.Member(id, seat, 0);
      member.connections.add("socket-" + seat); result.add(member);
    }
    return result;
  }

  @Test void fourGuestsMixedLoginOrFourAccountsAreEligible() {
    var members = humans(); var accounts = new HashMap<String, String>();
    for (var member : members) {
      assertEquals(SettlementEligibility.Result.ELIGIBLE, SettlementEligibility.evaluate(members, accounts));
      accounts.put(member.identity.playerId(), UUID.randomUUID().toString());
    }
    assertEquals(SettlementEligibility.Result.ELIGIBLE, SettlementEligibility.evaluate(members, accounts));
  }

  @Test void onlyCurrentControllerMatters() {
    var members = humans(); var member = members.get(1);
    member.takeover = true;
    assertEquals(SettlementEligibility.Result.COMPUTER_AT_SETTLEMENT, SettlementEligibility.evaluate(members, Map.of()));
    member.takeover = false;
    assertEquals(SettlementEligibility.Result.ELIGIBLE, SettlementEligibility.evaluate(members, Map.of()));
    member.computer = true;
    assertEquals(SettlementEligibility.Result.COMPUTER_AT_SETTLEMENT, SettlementEligibility.evaluate(members, Map.of()));
  }

  @Test void disconnectedHumanNeedsPositivePresenceEvidenceAndSecondTabPreservesIt() {
    var members = humans(); var member = members.get(0);
    member.connections.clear(); member.reconnectDueTick = 1000L;
    assertEquals(SettlementEligibility.Result.HUMAN_DISCONNECTED, SettlementEligibility.evaluate(members, Map.of()));
    member.connections.add("other-live-tab"); member.reconnectDueTick = null;
    assertEquals(SettlementEligibility.Result.ELIGIBLE, SettlementEligibility.evaluate(members, Map.of()));
  }

  @Test void rejectsDuplicateKnownAccountWithoutRetainingItsValue() {
    var members = humans(); String account = UUID.randomUUID().toString();
    var accounts = Map.of(members.get(0).identity.playerId(), account, members.get(2).identity.playerId(), account);
    var result = SettlementEligibility.evaluate(members, accounts);
    assertEquals(SettlementEligibility.Result.DUPLICATE_ACCOUNT, result);
    assertFalse(result.toString().contains(account));
  }

  @Test void rejectsDuplicateSession() {
    var members = humans(); members.get(3).identity = members.get(0).identity;
    assertEquals(SettlementEligibility.Result.DUPLICATE_PLAYER, SettlementEligibility.evaluate(members, Map.of()));
  }

  @Test void failsClosedForMissingDuplicateInvalidOrExcessSeats() {
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(null, Map.of()));
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(humans(), null));
    var members = humans(); members.remove(0);
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(members, Map.of()));
    members = humans(); members.add(members.get(0));
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(members, Map.of()));
    members = humans(); members.get(0).seat = 1;
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(members, Map.of()));
    for (int seat : List.of(-1, 4)) {
      members = humans(); members.get(0).seat = seat;
      assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(members, Map.of()));
    }
    members = humans(); members.set(0, null);
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(members, Map.of()));
  }

  @Test void rejectsMissingAndBlankIdentifiers() {
    var members = humans();
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(members,
        Map.of(members.get(0).identity.playerId(), " ")));
    members.get(0).identity = new RoomService.Identity("token", "", "Name");
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(members, Map.of()));
    members.get(0).identity = null;
    assertEquals(SettlementEligibility.Result.INVALID_ROSTER, SettlementEligibility.evaluate(members, Map.of()));
  }
}
