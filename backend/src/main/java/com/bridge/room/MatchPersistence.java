package com.bridge.room;

import com.bridge.persistence.FinalMatchEnvelope;
import com.bridge.persistence.StatsStore;
import java.time.Instant;
import java.util.*;

/** Server-only, privacy-minimized final facts. Never serialize GameState or its card history. */
final class MatchPersistence {
  static final String SCORE_VERSION = "bridgego-custom-v0.11-1";
  static final String APP_VERSION = "0.12.1";
  private final String matchId;
  private final int plannedDeals;
  private final Instant startedAt;
  private final boolean collectionEligible;
  private final List<Map<String, Object>> participants;
  private final Set<Integer> mixed = new HashSet<>();
  private boolean finalized;
  private Instant finishedAt;
  private FinalMatchEnvelope envelope;
  private String initialStatus = "DISABLED";

  MatchPersistence(String matchId, int plannedDeals, Instant startedAt,
      Collection<RoomService.Member> members, Map<String, String> accounts, boolean collectionEligible) {
    this.matchId = matchId;
    this.collectionEligible = collectionEligible;
    this.plannedDeals = plannedDeals;
    this.startedAt = startedAt;
    participants = members.stream().sorted(Comparator.comparingInt(m -> m.seat)).map(m -> {
      String account = m.computer ? null : accounts.get(m.identity.playerId());
      return row("seat", m.seat, "team_id", GameState.team(m.seat),
          "player_type", m.computer ? "BOT" : "HUMAN", "account_id", account,
          "guest_id", m.computer || account != null ? null : m.identity.playerId());
    }).toList();
  }

  boolean expiredAtTaiwanQuarter(Instant now) {
    if (finishedAt == null) return false;
    var zone = java.time.ZoneId.of("Asia/Taipei");
    var finished = finishedAt.atZone(zone); var current = now.atZone(zone);
    return finished.getYear() < current.getYear() || finished.getYear() == current.getYear()
        && (finished.getMonthValue()-1)/3 < (current.getMonthValue()-1)/3;
  }

  String matchId() { return matchId; }

  void observe(Collection<RoomService.Member> members) {
    if (finalized) return;
    for (RoomService.Member m : members) {
      if (m.automated() && participants.stream().anyMatch(p ->
          p.get("seat").equals(m.seat) && p.get("player_type").equals("HUMAN"))) mixed.add(m.seat);
    }
  }

  void finish(GameState.MatchArchive archive, Instant finishedAt, StatsStore store) {
    if (finalized) return;
    this.finishedAt = finishedAt;
    finalized = true; // terminal presentation refresh / retries must not change timestamps or hash
    // A single visitor excludes the entire match: do not create, queue or persist any payload.
    if (!collectionEligible) return;
    var result = archive.matchResult();
    var match = row("match_id", matchId, "revision", 1, "schema_version", 1,
        "score_version", SCORE_VERSION, "app_version", APP_VERSION,
        "started_at", startedAt.toString(), "finished_at", finishedAt.toString(),
        "planned_deals", plannedDeals, "completed_deals", archive.completedDealResults().size(),
        "outcome", result.outcome(), "winner_team_id", result.winnerTeamId(),
        "reason", result.reason(), "ns_total", archive.teamTotals().get("NS"),
        "ew_total", archive.teamTotals().get("EW"));
    List<Map<String, Object>> people = participants.stream().map(p -> {
      var copy = new LinkedHashMap<>(p);
      copy.put("match_id", matchId);
      copy.put("control_type", p.get("player_type").equals("BOT") ? "BOT"
          : mixed.contains((Integer)p.get("seat")) ? "MIXED" : "HUMAN");
      return (Map<String, Object>)copy;
    }).toList();
    List<Map<String, Object>> deals = archive.completedDealResults().stream().map(d -> {
      var c = d.contract();
      var data = row("match_id", matchId, "deal_id", d.dealId(), "deal_index", d.dealIndex(),
          "contract_type", c.type(), "contract_level", c.level(), "contract_strain", c.strain(),
          "attacking_team_id", c.teamId(), "ns_tricks", d.tricksByTeam().get("NS"),
          "ew_tricks", d.tricksByTeam().get("EW"));
      for (String team : List.of("NS", "EW")) {
        var score = d.scores().get(team);
        String prefix = team.toLowerCase(Locale.ROOT) + "_";
        data.put(prefix + "privilege_used", score.privilegeUsed());
        data.put(prefix + "base", score.base());
        data.put(prefix + "exact_bonus", score.exactTargetBonus());
        data.put(prefix + "penalty", score.penalty());
        data.put(prefix + "privilege_adjustment", score.privilegeAdjustment());
        data.put(prefix + "total", score.total());
      }
      return data;
    }).toList();
    try {
      envelope = FinalMatchEnvelope.create(matchId, 1,
          row("match", match, "participants", people, "completed_deals", deals));
      initialStatus = store == null ? "DISABLED" : store.submit(envelope).name();
    } catch (RuntimeException e) {
      // A persistence failure must not undo an already-authoritative game transition.
      // No payload/credentials enter logs. Status remains visible to original participants.
      initialStatus = "FAILED";
    }
  }

  String status(StatsStore store) {
    if (envelope == null || store == null || !initialStatus.equals("PENDING")) return initialStatus;
    return store.status(envelope.commitId()).name();
  }

  static Map<String, Object> row(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i + 1]);
    return result;
  }
}
