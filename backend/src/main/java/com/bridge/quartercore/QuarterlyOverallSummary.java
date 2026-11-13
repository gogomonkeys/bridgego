package com.bridge.quartercore;
import java.time.Instant;import java.util.*;
/** Exact shared codec adapter; no native copy of the publication/counting rules. */
final class QuarterlyOverallSummary {
 static com.bridge.persistence.QuarterlyOverallSummary.Summary summarize(String quarter,Collection<FinalMatchEnvelope> envelopes,Instant now){return com.bridge.persistence.QuarterlyOverallSummary.summarize(quarter,envelopes.stream().map(e->new com.bridge.persistence.FinalMatchEnvelope(e.commitId(),e.matchId(),e.revision(),e.canonicalJson(),e.payloadHash())).toList(),now);}
 static List<String> encodeRow(String quarter,com.bridge.persistence.QuarterlyOverallSummary.Summary summary){return com.bridge.persistence.QuarterlyOverallSummary.encodeRow(quarter,summary);}
 static com.bridge.persistence.QuarterlyOverallSummary.Decoded decodeRow(List<String> row){return com.bridge.persistence.QuarterlyOverallSummary.decodeRow(row);}
 private QuarterlyOverallSummary(){}
}
