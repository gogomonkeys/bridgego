package com.bridge.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class AccountProfileRowsTest {
  private final String id = UUID.randomUUID().toString();
  @Test void supportsOldRowsAndSparseNewRows() {
    assertDoesNotThrow(() -> AccountProfileRows.validateTable(List.of(AccountProfileRows.LEGACY_HEADER, List.of(id,"1"))));
    assertDoesNotThrow(() -> AccountProfileRows.validateTable(List.of(AccountProfileRows.HEADER, List.of(id,"1","",""))));
    assertDoesNotThrow(() -> AccountProfileRows.validateTable(List.of(AccountProfileRows.HEADER, List.of(id,"2","玩家"))));
    assertDoesNotThrow(() -> AccountProfileRows.validateTable(List.of(AccountProfileRows.HEADER, List.of(id,"2","玩家","name@example.test"))));
  }
  @Test void rejectsUnexpectedColumnsVersionsOrMalformedProfileWithoutLeakingData() {
    for (var row : List.of(List.of(id,"1","secret"), List.of(id,"3","玩家"), List.of(id,"2",""),
        List.of(id,"2","玩家","secret-not-email"), List.of(id,"2","玩家","x@example.test","extra"))) {
      var error = assertThrows(SheetsGateway.Conflict.class, () -> AccountProfileRows.validateRow(row));
      assertFalse(error.getMessage().contains("secret"));
    }
    assertThrows(SheetsGateway.Conflict.class, () -> AccountProfileRows.validateTable(List.of(AccountProfileRows.LEGACY_HEADER,List.of(id,"2","玩家"))));
    assertThrows(SheetsGateway.Conflict.class, () -> AccountProfileRows.validateTable(List.of(AccountProfileRows.HEADER,List.of(id,"1"),List.of(id,"1"))));
  }
}
