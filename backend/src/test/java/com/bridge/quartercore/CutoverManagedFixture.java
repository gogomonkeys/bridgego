package com.bridge.quartercore;
import java.time.*;import java.util.*;import static com.bridge.quartercore.NativeSheetsModel.*;
/** Synthetic managed B0 with one explicitly reviewable legacy record and current retention. */
final class CutoverManagedFixture {
 static final GoogleSheetsBinding B=new GoogleSheetsBinding(RecoveredFixture.ID,RecoveredFixture.DATASET,"bridgego-managed","test@project.iam.gserviceaccount.com");
 final Book before;final FinalMatchEnvelope historical;final String account;final String approval=UUID.randomUUID().toString();final LifetimeStatistics.Counts contribution;
 CutoverManagedFixture(Clock clock)throws Exception{
  before=RecoveredFixture.book();before.metadata.clear();before.sheet("dataset").rows.get(1).set(0,B.purpose());account=before.sheet("accounts").rows.get(1).get(0);
  var source=com.bridge.persistence.HistoricalAdmissionTest.original(account);historical=new FinalMatchEnvelope(source.commitId(),source.matchId(),source.revision(),source.canonicalJson(),source.payloadHash());
  before.sheet("commits").rows=new ArrayList<>(List.of(new ArrayList<>(ManualQuarterMaintenance.HEADERS.get("commits")),new ArrayList<>(List.of(historical.commitId(),historical.payloadHash(),"1",historical.matchId(),"1"))));
  before.sheet("envelopes").rows=new ArrayList<>(List.of(new ArrayList<>(ManualQuarterMaintenance.HEADERS.get("envelopes")),new ArrayList<>(List.of(historical.commitId(),historical.payloadHash(),"0","1",historical.canonicalJson()))));
  Instant cutoff=QuarterlyRetention.currentQuarterStart(clock.instant());
  before.sheet("retention_ledger").rows=new ArrayList<>(List.of(new ArrayList<>(ManualQuarterMaintenance.HEADERS.get("retention_ledger")),new ArrayList<>(List.of(QuarterlyRetention.quarter(cutoff),cutoff.toString(),"COMPLETE",clock.instant().toString(),"1"))));
  contribution=LifetimeStatistics.fromEnvelopes(account,List.of(HistoricalCasualAdmission.migrate(historical,approval))).casual();
 }
}
