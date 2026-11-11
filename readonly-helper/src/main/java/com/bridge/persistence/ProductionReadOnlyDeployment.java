package com.bridge.persistence;

import java.util.List;

/** Non-secret deployment pins confirmed by the owner and bounded schema observation. No configuration reads. */
public final class ProductionReadOnlyDeployment {
  private ProductionReadOnlyDeployment() {}
  private static final String SHEET="1_AqJ_-jXFNSzUZhsTylnXqTmIbtL-PHA1VDd23tV5p0";
  private static final String DATASET="f89030e0-6629-43d7-8a96-558f488c9da2";
  public static ProductionReadOnlyTarget confirmedTarget(String configuredSheet,String configuredDataset,String collectionMode) {
    if(!SHEET.equals(configuredSheet) || !DATASET.equals(configuredDataset) || !"managed".equals(collectionMode))
      throw new IllegalStateException("Production read-only deployment binding rejected");
    return new ProductionReadOnlyTarget(SHEET,"BridgeGo 正式資料庫",DATASET,List.of(
        new ProductionReadOnlyTarget.Sheet("dataset",1902934440),new ProductionReadOnlyTarget.Sheet("accounts",213175021),
        new ProductionReadOnlyTarget.Sheet("auth_identities",1007532550),new ProductionReadOnlyTarget.Sheet("envelopes",382111089),
        new ProductionReadOnlyTarget.Sheet("commits",368883679),new ProductionReadOnlyTarget.Sheet("erasure_ledger",651269087),
        new ProductionReadOnlyTarget.Sheet("retention_ledger",146678111),new ProductionReadOnlyTarget.Sheet("quarterly_aggregates",1882807199)));
  }
}
