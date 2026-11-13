package com.bridge.quartercore;
import java.time.*;
import java.util.Objects;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Read-only gate, not a writer grant. A token is not a reconstructible historical chain. */
final class FixedERestartPreflight {
 static final String INITIAL_TOKEN="391e7a1e413e997f0fa6d186f217dec3bd9ac8239ba90fc0d03020d9a60c6bca";
 @FunctionalInterface interface FullReader {Book read();}
 private FixedERestartPreflight(){}
 static DurableCleanHandoffAssessment.Result initial(FullReader reader){return inspect(reader,INITIAL_TOKEN);}
 /** Synthetic local fixture seam; never selected from an environment/request. */
 static DurableCleanHandoffAssessment.Result isolatedFixture(FullReader reader,String expectedToken){return inspect(reader,expectedToken);}
 private static DurableCleanHandoffAssessment.Result inspect(FullReader reader,String expectedToken){
  if(expectedToken==null||!expectedToken.matches("[0-9a-f]{64}"))throw new Rejected();
  Book book=Objects.requireNonNull(reader).read();
  var result=DurableCleanHandoffAssessment.inspect(FixedERestartClock.binding().manualBinding(),book,Clock.fixed(FixedERestartClock.BUSINESS_INSTANT,ZoneOffset.UTC));
  if(result.state()!=DurableCleanHandoffAssessment.State.CLEAN_CURRENT_RESUMABLE||!expectedToken.equals(result.snapshotToken()))throw new Rejected();
  return result;
 }
}
