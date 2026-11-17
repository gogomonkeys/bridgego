package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class NicknameTest {
  @Test void acceptsTenVisibleCharacters() {
    for (String name : new String[]{"小", "一二三四五六七八九十", "abcdefghij", "😀".repeat(10), "👨‍👩‍👧‍👦".repeat(10), "🇹🇼".repeat(10)})
      assertEquals(name, Nickname.validate(name));
  }
  @Test void rejectsEmptyOverLimitAndControls() {
    for (String name : new String[]{null, "", "　  ", "一二三四五六七八九十甲", "abcdefghijk", "😀".repeat(11), "👨‍👩‍👧‍👦".repeat(11), "a\nb", "\t小白"})
      assertEquals("INVALID_NAME", assertThrows(RoomService.Failure.class, () -> Nickname.validate(name)).code);
  }
  @Test void normalizesSameBoundariesAndNfcAsFrontend() {
    assertEquals("é", Nickname.validate("\uFEFF\u00a0　e\u0301　\u00a0"));
    assertEquals("é".repeat(10), Nickname.validate("e\u0301".repeat(10)));
  }
  @Test void createSessionCannotBypassLimit() {
    var service = new RoomService(Clock.systemUTC());
    assertThrows(RoomService.Failure.class, () -> service.createSession("一二三四五六七八九十甲"));
    var identity = service.createSession(" 小白 ");
    assertEquals("小白", identity.displayName());
    assertEquals(identity, service.authenticate(identity.token()));
    assertEquals("小白", service.me(identity).displayName());
  }
  @Test void boundsPathologicalCombiningSequences() {
    assertThrows(RoomService.Failure.class, () -> Nickname.validate("a" + "\u0301".repeat(300)));
  }
}
