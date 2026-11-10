package com.bridge.room;

import java.util.*;

/** Server-only settlement rule. A result contains no player/account identifiers. */
final class SettlementEligibility {
  static final String RULE_VERSION = com.bridge.persistence.FinalMatchEnvelope.STATISTICS_RULE;

  enum Result {
    ELIGIBLE,
    FIXED_CASUAL,
    INVALID_ROSTER,
    COMPUTER_AT_SETTLEMENT,
    HUMAN_DISCONNECTED,
    NO_HUMAN_AT_SETTLEMENT,
    DUPLICATE_PLAYER,
    DUPLICATE_ACCOUNT
  }

  private SettlementEligibility() {}

  /** Persisted categories are explicit; rejection reasons never become a category. */
  static String category(Result result) {
    return switch (result) {
      case ELIGIBLE -> "FORMAL";
      case COMPUTER_AT_SETTLEMENT, FIXED_CASUAL -> "CASUAL";
      default -> null;
    };
  }

  /** Fixed rooms classify by creation choice, never by computer count at the end. */
  static Result evaluateFixed(Collection<RoomService.Member> members, Map<String, String> accounts,
      String roomType, String outcome) {
    Result checked = validateRoster(members, accounts);
    if (checked != null) return checked;
    if (!Set.of("FORMAL", "CASUAL").contains(roomType)
        || !Set.of("SCORED", "FORFEIT", "NO_CONTEST").contains(outcome)) return Result.INVALID_ROSTER;
    if ("FORMAL".equals(roomType)) {
      if (members.stream().anyMatch(RoomService.Member::automated)) return Result.INVALID_ROSTER;
      if ("SCORED".equals(outcome) && members.stream().anyMatch(m -> !m.humanOnline())) return Result.HUMAN_DISCONNECTED;
      return Result.ELIGIBLE;
    }
    if (!"NO_CONTEST".equals(outcome) && members.stream().allMatch(RoomService.Member::automated))
      return Result.NO_HUMAN_AT_SETTLEMENT;
    return Result.FIXED_CASUAL;
  }

  /**
   * Call under the room transition lock at the first terminal transition only.
   * A previous takeover is irrelevant once a human has reclaimed the seat.
   * Distinct sessions/known accounts are enforceable; distinct physical people
   * behind independent guest sessions cannot be proven by this server.
   */
  static Result evaluate(Collection<RoomService.Member> members, Map<String, String> accounts) {
    Result checked = validateRoster(members, accounts);
    if (checked != null) return checked;
    if (members.stream().noneMatch(m -> !m.automated() && m.humanOnline())) return Result.NO_HUMAN_AT_SETTLEMENT;
    if (members.stream().anyMatch(m -> !m.automated() && !m.humanOnline())) return Result.HUMAN_DISCONNECTED;
    if (members.stream().anyMatch(RoomService.Member::automated)) return Result.COMPUTER_AT_SETTLEMENT;
    return Result.ELIGIBLE;
  }

  private static Result validateRoster(Collection<RoomService.Member> members, Map<String, String> accounts) {
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
    return null;
  }
}
