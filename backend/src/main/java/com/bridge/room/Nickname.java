package com.bridge.room;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Validates only newly created identities; existing sessions are never renamed. */
final class Nickname {
  private static final Pattern EDGE_SPACE = Pattern.compile("^[\\p{Z}\\s\\uFEFF]+|[\\p{Z}\\s\\uFEFF]+$");
  private static final Pattern GRAPHEME = Pattern.compile("\\X");

  static String validate(String raw) {
    if (raw == null || raw.chars().anyMatch(Character::isISOControl)) throw invalid();
    String name = Normalizer.normalize(EDGE_SPACE.matcher(raw).replaceAll(""), Normalizer.Form.NFC);
    if (name.isEmpty() || name.length() > 256) throw invalid();
    var matcher = GRAPHEME.matcher(name);
    int count = 0;
    while (matcher.find()) if (++count > 5) throw invalid();
    return name;
  }

  private static RoomService.Failure invalid() {
    return RoomService.fail(400, "INVALID_NAME", "Display name must be 1–5 visible characters without control characters");
  }
}
