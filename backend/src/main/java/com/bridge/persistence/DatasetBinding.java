package com.bridge.persistence;

import java.util.*;
import java.util.function.Function;
import org.springframework.core.env.Environment;

/** Explicit target and dataset purpose shared by gameplay and manual maintenance.
 * Selection never comes from observed workbook content. No provisioning or startup writes. */
public record DatasetBinding(String spreadsheetId, String datasetId, String purpose) {
  public static final String MANAGED_PURPOSE = "bridgego-managed";
  public static final String TEST_PURPOSE = "bridgego-restricted-test";
  public DatasetBinding {
    if (spreadsheetId == null || !spreadsheetId.matches("[A-Za-z0-9_-]{10,160}")
        || datasetId == null || purpose == null || !Set.of(MANAGED_PURPOSE, TEST_PURPOSE).contains(purpose))
      throw new IllegalArgumentException("Explicit dataset binding required");
    try {
      if (!UUID.fromString(datasetId).toString().equals(datasetId)) throw new IllegalArgumentException();
    } catch (RuntimeException invalid) { throw new IllegalArgumentException("Canonical dataset identifier required"); }
  }
  public static DatasetBinding from(Environment env) {
    String mode=env.getProperty("BRIDGE_COLLECTION_MODE","restricted");
    if ("managed".equals(mode)) {
      String sheet=env.getRequiredProperty("BRIDGE_SHEETS_SPREADSHEET_ID");
      if (sheet.equals(env.getProperty("BRIDGE_TEST_SPREADSHEET_ID","")))
        throw new IllegalArgumentException("Managed data must not select the configured test spreadsheet");
      return new DatasetBinding(sheet,env.getRequiredProperty("BRIDGE_DATASET_ID"),MANAGED_PURPOSE);
    }
    if (!"restricted".equals(mode)) throw new IllegalArgumentException("Unsupported dataset binding mode");
    String sheet=env.getRequiredProperty("BRIDGE_TEST_SPREADSHEET_ID");
    if (sheet.equals(env.getProperty("BRIDGE_SHEETS_SPREADSHEET_ID","")))
      throw new IllegalArgumentException("Test dataset must be distinct from the production spreadsheet");
    return new DatasetBinding(sheet,env.getRequiredProperty("BRIDGE_TEST_DATASET_ID"),TEST_PURPOSE);
  }
  public static SheetsGateway open(Environment env, Function<String,SheetsGateway> connector) {
    var binding=from(env); // Validate complete selection before any credential/client factory access.
    return binding.gateway(connector.apply(binding.spreadsheetId()));
  }
  public SheetsGateway gateway(SheetsGateway delegate) {
    Objects.requireNonNull(delegate);
    return new SheetsGateway() {
      private List<List<String>> checked() {
        var marker=delegate.read("dataset");
        if (!List.of(List.of("purpose","dataset_id"),List.of(purpose,datasetId)).equals(marker))
          throw new SheetsGateway.Conflict();
        return marker;
      }
      public List<List<String>> read(String tab) { var marker=checked();return "dataset".equals(tab)?marker:delegate.read(tab); }
      public void appendAtomic(Map<String,List<List<Object>>> rows) { checked();mutable(rows);delegate.appendAtomic(rows); }
      public void replaceAtomic(Map<String,List<List<Object>>> rows) { checked();mutable(rows);delegate.replaceAtomic(rows); }
      private void mutable(Map<String,List<List<Object>>> rows) {
        if(rows.containsKey("dataset"))throw new IllegalArgumentException("Dataset marker is immutable");
      }
    };
  }
  @Override public String toString() { return "DatasetBinding[redacted]"; }
}
