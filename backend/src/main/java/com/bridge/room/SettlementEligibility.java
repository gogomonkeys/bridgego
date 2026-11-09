package com.bridge.room;

import java.util.*;

/** Server-only settlement rule. A result contains no player/account identifiers. */
final class SettlementEligibility {
  static final String RULE_VERSION = com.bridge.persistence.FinalMatchEnvelope.STATISTICS_RULE;

  enum Result {
    ELIGIBLE,
    INVALID_ROSTER,
    COMPUTER_AT_SETTLEMENT,
    HUMAN_DISCONNECTED,
    DUPLICATE_PLAYER,
    DUPLICATE_ACCOUNT
  }

  private SettlementEligibility() {}

  /**
   * Call under the room transition lock at the first terminal transition only.
   * A previous takeover is irrelevant once a human has reclaimed the seat.
   * Distinct sessions/known accounts are enforceable; distinct physical people
   * behind independent guest sessions cannot be proven by this server.
   */
  static Result evaluate(Collection<RoomService.Member> members, Map<String, String> accounts) {
    if (members == null || accounts == null || members.size() != 4) return Result.INVALID_ROSTER;
    Set<Integer> seats = new HashSet<>();
    Set<String> players = new HashSet<>();
    Set<String> knownAccounts = new HashSet<>();
    for (var member : members) {
      if (member == null || member.identity == null || member.seat < 0 || member.seat > 3
          || !seats.add(member.seat) || member.identity.playerId() == null
          || member.identity.playerId().isBlank()) return Result.INVALID_ROSTER;
      if (!players.add(member.identity.playerId())) return Result.DUPLICATE_PLAYER;
      String account = accounts.get(member.identity.playerId());
      if (account != null && account.isBlank()) return Result.INVALID_ROSTER;
      if (account != null && !knownAccounts.add(account)) return Result.DUPLICATE_ACCOUNT;
    }
    if (members.stream().anyMatch(RoomService.Member::automated)) return Result.COMPUTER_AT_SETTLEMENT;
    if (members.stream().anyMatch(m -> !m.humanOnline())) return Result.HUMAN_DISCONNECTED;
    return Result.ELIGIBLE;
  }
}
