package com.bridge.persistence;

import java.util.*;

/** Explicit reviewed selection, never inferred from resource content or a browser query parameter. */
public record ProductionReadOnlyTarget(String spreadsheetId,String expectedTitle,String datasetId,List<Sheet> sheets) {
  public static final String PROFILE="legacy-eight-native-v2-readonly";
  public record Sheet(String title,int sheetId) {}
  public ProductionReadOnlyTarget {
    try {
      if(spreadsheetId==null || !spreadsheetId.matches("[A-Za-z0-9_-]{10,160}") || expectedTitle==null
          || expectedTitle.isBlank() || expectedTitle.length()>256 || expectedTitle.chars().anyMatch(Character::isISOControl)
          || datasetId==null || !UUID.fromString(datasetId).toString().equals(datasetId)
          || sheets==null || sheets.size()!=8)throw new IllegalArgumentException();
      sheets=List.copyOf(sheets);Set<String> titles=new HashSet<>();Set<Integer> ids=new HashSet<>();
      for(Sheet sheet:sheets)if(sheet==null || !ProductionReadOnlyWorkbook.TABS.contains(sheet.title())
          || !titles.add(sheet.title()) || sheet.sheetId()<0 || !ids.add(sheet.sheetId()))throw new IllegalArgumentException();
    }catch(RuntimeException rejected){throw new IllegalArgumentException("Production read-only target rejected");}
  }
  @Override public String toString(){return "ProductionReadOnlyTarget[REDACTED]";}
}
