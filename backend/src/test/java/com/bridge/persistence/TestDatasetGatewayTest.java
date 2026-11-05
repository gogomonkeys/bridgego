package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TestDatasetGatewayTest {
  static class Fake implements SheetsGateway {
    List<List<String>> marker;
    int writes, reads;
    Fake(String id) { marker = List.of(List.of("purpose","dataset_id"), List.of("bridgego-restricted-test",id)); }
    public List<List<String>> read(String tab) { if(tab.equals("dataset")) return marker; reads++; return List.of(); }
    public void appendAtomic(Map<String,List<List<Object>>> rows) { writes++; }
    public void replaceAtomic(Map<String,List<List<Object>>> rows) { writes++; }
  }
  @Test void correctDatasetRequiredBeforeEveryReadWriteAndDeletion() {
    String id=UUID.randomUUID().toString(); Fake fake=new Fake(id); var gateway=new TestDatasetGateway(fake,id);
    gateway.read("commits"); gateway.appendAtomic(Map.of()); gateway.replaceAtomic(Map.of());
    assertEquals(1,fake.reads); assertEquals(2,fake.writes);
    fake.marker=List.of(List.of("purpose","dataset_id"),List.of("bridgego-restricted-test",UUID.randomUUID().toString()));
    assertThrows(SheetsGateway.Conflict.class,()->gateway.read("commits"));
    assertThrows(SheetsGateway.Conflict.class,()->gateway.appendAtomic(Map.of()));
    assertThrows(SheetsGateway.Conflict.class,()->gateway.replaceAtomic(Map.of()));
    assertEquals(1,fake.reads); assertEquals(2,fake.writes);
  }
  @Test void missingWrongPurposeAndMalformedMarkersFailClosed() {
    String id=UUID.randomUUID().toString();Fake fake=new Fake(id);var gateway=new TestDatasetGateway(fake,id);
    for(var marker:List.of(List.<List<String>>of(),List.of(List.of("purpose","dataset_id"),List.of("production",id)),
        List.of(List.of("bridgego-restricted-test",id)))) {
      fake.marker=marker;assertThrows(SheetsGateway.Conflict.class,()->gateway.appendAtomic(Map.of()));
    }
    assertEquals(0,fake.writes);
  }
  @Test void markerCannotBeWrittenByAdapter() {
    String id=UUID.randomUUID().toString();Fake fake=new Fake(id);var gateway=new TestDatasetGateway(fake,id);
    assertThrows(IllegalArgumentException.class,()->gateway.appendAtomic(Map.of("dataset",List.of())));
    assertThrows(IllegalArgumentException.class,()->gateway.replaceAtomic(Map.of("dataset",List.of())));
    assertEquals(0,fake.writes);
  }
}
