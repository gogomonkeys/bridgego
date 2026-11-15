package com.bridge.persistence;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LiveQuarterTutorialTest {
 static final Instant NOW=Instant.parse("2026-11-15T00:00:00Z");
 @Test void fullWorkbookReaderPreservesFlag(){var f=new LocalManualQuarterRehearsal.Fixture();var rows=f.tables.get("accounts");rows.set(0,AccountProfileRows.TUTORIAL_HEADER);for(int i=1;i<rows.size();i++)rows.set(i,List.of(rows.get(i).get(0),"1","","","",AccountProfileRows.TUTORIAL_ACKNOWLEDGED));var parsed=LiveQuarterWorkbook.parse(LiveQuarterWorkbookTest.fixtureJson(f),f.binding,Clock.fixed(NOW,ZoneOffset.UTC));assertEquals(AccountProfileRows.TUTORIAL_ACKNOWLEDGED,parsed.snapshot().tables().get("accounts").get(1).get(5));}
}
