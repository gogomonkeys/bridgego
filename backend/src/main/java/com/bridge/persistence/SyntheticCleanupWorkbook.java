package com.bridge.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/** Phase 1 only: exact isolated scaffold, not an engine-ready fixture or permission to mutate. */
public final class SyntheticCleanupWorkbook {
  // The private identifier is supplied at deployment, never accepted merely because it is configured.
  public static final String APPROVED_TARGET_SHA256 = "e248f80801b5a0b1e4844f5e11c899e4a91c87ae4b852479fac3b43c527ab86e";
  private static final String PROBE_DATASET = "00000000-0000-4000-8000-000000000001";
  private SyntheticCleanupWorkbook() {}
  public record Summary(String result, String comments, int sheetCount, String fingerprint,
      boolean cleanupReady, boolean writesEnabled) {}
  public static void requireApprovedTarget(String sheetId) {
    if (sheetId == null || !sheetId.matches("[A-Za-z0-9_-]{10,160}")
        || !APPROVED_TARGET_SHA256.equals(sha256(sheetId))) throw new IllegalArgumentException("Isolated target rejected");
  }
  public static Summary inspect(byte[] raw, String sheetId, String expectedMarker) {
    requireApprovedTarget(sheetId);
    var workbook = LiveQuarterWorkbook.parseScaffold(raw,
        new ManualQuarterMaintenance.Binding(sheetId, PROBE_DATASET), Clock.systemUTC(), expectedMarker);
    return new Summary("SCAFFOLD_READ_VERIFIED", "INCLUDED", workbook.grids().size(), workbook.fingerprint(), false, false);
  }
  public static String sha256(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception impossible) { throw new IllegalStateException("Digest unavailable"); }
  }
}
