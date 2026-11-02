package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AllowedOriginsTest {
  @Test void localProfileRetainsOnlyKnownLoopbackOrigins() {
    assertArrayEquals(new String[] {"http://127.0.0.1:5173", "http://localhost:5173",
        "http://127.0.0.1:8080", "http://localhost:8080"},
        AllowedOrigins.forProfile(false, "https://example.com"));
  }

  @Test void productionUsesOnlyExplicitHttpsOrigin() {
    assertArrayEquals(new String[] {"https://example.com"},
        AllowedOrigins.forProfile(true, " https://example.com "));
    assertArrayEquals(new String[] {"https://example.com:8443"},
        AllowedOrigins.forProfile(true, "https://example.com:8443"));
  }

  @Test void productionFailsClosedForMissingOrUnsafeOrigin() {
    for (String value : new String[] {"", "*", "http://example.com", "https://*.example.com",
        "https://example.com/", "https://example.com/path", "https://user:secret@example.com",
        "https://example.com?x=1", "https://example.com#fragment", "https://example.com,https://other.test",
        "https://example.com:0", "https://example.com:65536", "https://example.com:bad", "https://"}) {
      assertThrows(IllegalStateException.class, () -> AllowedOrigins.forProfile(true, value), value);
    }
    assertThrows(IllegalStateException.class, () -> AllowedOrigins.forProfile(true, null));
  }
}
