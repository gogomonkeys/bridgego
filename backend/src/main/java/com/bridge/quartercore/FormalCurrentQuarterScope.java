package com.bridge.quartercore;
import java.time.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Fixed release scope, NOT a deletion approval. No environment/HTTP bypass. */
final class FormalCurrentQuarterScope {
 private static final Instant START=Instant.parse("2026-09-30T16:00:00Z"),END=Instant.parse("2026-12-31T16:00:00Z");
 private FormalCurrentQuarterScope(){}
 private static boolean formal(GoogleSheetsBinding b){
  var fixed=NativeRestartCompiledRelease.pendingReview().binding();
  return fixed.spreadsheetId().equals(b.spreadsheetId())||fixed.datasetId().equals(b.datasetId());
 }
 static void requireStartup(GoogleSheetsBinding b,Clock clock){
  if(!formal(b))return;
  Instant now=clock.instant();
  if(!b.equals(NativeRestartCompiledRelease.pendingReview().binding())||now.isBefore(START)||!now.isBefore(END))throw new Rejected();
 }
 static void requireQuarterDeletionApproval(GoogleSheetsBinding b){
  if(formal(b))throw new Rejected(); // Each irreversible deletion needs a new exact approval.
 }
}
