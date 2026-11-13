package com.bridge.quartercore;
import java.time.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Local fixed-E seam only. Not production provisioning, environment configuration or approval. */
final class FixedERestartClock {
 static final Instant BUSINESS_INSTANT=Instant.parse("2026-12-31T16:00:01Z");
 static GoogleSheetsBinding binding(){return new GoogleSheetsBinding("1n-CaTcmMVlTxr_NXYyuw6mAsrQZ3YdMPtou3bSKxKY8","879a3708-3bc4-4c76-9d9a-53883f71530a","bridgego-restricted-test","bridgego-sheets@bridgego-513805.iam.gserviceaccount.com");}
 static void require(GoogleSheetsBinding target,Clock businessClock){if(!binding().equals(target)||businessClock==null||!BUSINESS_INSTANT.equals(businessClock.instant()))throw new Rejected();}
 private FixedERestartClock(){}
}
