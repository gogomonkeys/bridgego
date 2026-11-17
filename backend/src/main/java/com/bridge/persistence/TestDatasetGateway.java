package com.bridge.persistence;

import java.util.*;

/** Every operation must prove it addresses the explicitly selected isolated test dataset. */
public final class TestDatasetGateway implements SheetsGateway {
  private final SheetsGateway delegate;
  private final String expectedDataset;
  public TestDatasetGateway(SheetsGateway delegate, String expectedDataset) {
    if (expectedDataset == null || !UUID.fromString(expectedDataset).toString().equals(expectedDataset))
      throw new IllegalArgumentException("A canonical test dataset UUID is required");
    this.delegate = Objects.requireNonNull(delegate); this.expectedDataset = expectedDataset;
  }
  private List<List<String>> checkedDataset() {
    var marker = delegate.read("dataset");
    if (!marker.equals(List.of(List.of("purpose", "dataset_id"),
        List.of("bridgego-restricted-test", expectedDataset))))
      throw new SheetsGateway.Conflict();
    return marker;
  }
  @Override public List<List<String>> read(String tab) {
    var marker = checkedDataset();
    return tab.equals("dataset") ? marker : delegate.read(tab);
  }
  @Override public void appendAtomic(Map<String,List<List<Object>>> rows) {
    checkedDataset();
    if (rows.containsKey("dataset")) throw new IllegalArgumentException("Test dataset marker is immutable");
    delegate.appendAtomic(rows);
  }
  @Override public Optional<List<List<String>>> readSocialIfPresent() { checkedDataset();return delegate.readSocialIfPresent(); }
  @Override public void replaceSocial(List<List<String>> rows) { checkedDataset();delegate.replaceSocial(rows); }
  @Override public void acknowledgeTutorial(int row) { checkedDataset();delegate.acknowledgeTutorial(row); }
  @Override public void saveAccountProfile(int row,List<String> account,List<String> identity) { checkedDataset();delegate.saveAccountProfile(row,account,identity); }
  @Override public void replaceAtomic(Map<String,List<List<Object>>> rows) {
    checkedDataset();
    if (rows.containsKey("dataset")) throw new IllegalArgumentException("Test dataset marker is immutable");
    delegate.replaceAtomic(rows);
  }
}
