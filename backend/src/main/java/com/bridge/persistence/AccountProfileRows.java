package com.bridge.persistence;

import java.util.*;

/** Private account schema. Legacy rows remain valid; profile data never belongs in public match payloads. */
public final class AccountProfileRows {
  public static final List<String> LEGACY_HEADER = List.of("account_id", "schema_version");
  public static final List<String> HEADER = List.of("account_id", "schema_version", "nickname", "email");
  public static final List<String> LIFETIME_HEADER = List.of("account_id", "schema_version", "nickname", "email", "lifetime_statistics");
  private AccountProfileRows() {}
  public static boolean isHeader(List<String> header) {
    return LEGACY_HEADER.equals(header) || HEADER.equals(header) || LIFETIME_HEADER.equals(header);
  }
  public static void validateTable(List<List<String>> rows) {
    if (rows == null || rows.isEmpty() || !isHeader(rows.get(0))) reject();
    Set<String> ids = new HashSet<>();
    for (var row : rows.subList(1, rows.size())) {
      validateRow(row);
      if (row.size() > rows.get(0).size() || !ids.add(row.get(0))) reject();
    }
  }
  public static void validateRow(List<String> row) {
    if (row == null || row.size() < 2 || row.size() > 5 || row.stream().anyMatch(Objects::isNull)) reject();
    try { if (!UUID.fromString(row.get(0)).toString().equals(row.get(0))) reject(); }
    catch (IllegalArgumentException failure) { reject(); }
    if ("1".equals(row.get(1))) {
      for (int i = 2; i < Math.min(4, row.size()); i++) if (!row.get(i).isEmpty()) reject();
    } else if ("2".equals(row.get(1))) {
      if (row.size() < 3 || !validNickname(row.get(2)) || row.size() >= 4 && !validEmail(row.get(3))) reject();
    } else reject();
    if (row.size() == 5) LifetimeStatistics.decode(row.get(4));
  }
  public static boolean validNickname(String value) {
    return value != null && !value.isBlank() && value.length() <= 256
        && value.chars().noneMatch(Character::isISOControl);
  }
  public static boolean validEmail(String value) {
    return value != null && (value.isEmpty() || value.length() <= 254
        && value.matches("[^\\s@\\p{Cntrl}]+@[^\\s@\\p{Cntrl}]+\\.[^\\s@\\p{Cntrl}]+"));
  }
  private static void reject() { throw new SheetsGateway.Conflict(); }
}
