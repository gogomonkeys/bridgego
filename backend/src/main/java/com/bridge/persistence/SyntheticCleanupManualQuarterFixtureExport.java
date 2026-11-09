package com.bridge.persistence;

/** Local deterministic fixture preparation artifact only. No network or credential access.
 * Excluded from the web JAR; this exporter itself never writes a spreadsheet. */
public final class SyntheticCleanupManualQuarterFixtureExport {
  public static void main(String[] args) throws Exception {
    if(args.length!=0)throw new IllegalArgumentException("No command line arguments");
    String target=System.getenv("BRIDGE_SYNTHETIC_SHEET_ID");
    var fixture=SyntheticCleanupManualQuarterTrial.defaultFixture(target);
    var output=java.util.Map.of("fixtureEpoch",SyntheticCleanupManualQuarterTrial.FIXTURE_EPOCH.toString(),
        "targetSha256",SyntheticCleanupWorkbook.sha256(target),"tables",fixture.tables());
    System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output));
  }
}
