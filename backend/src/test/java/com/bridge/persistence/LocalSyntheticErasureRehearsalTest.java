package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LocalSyntheticErasureRehearsalTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);

  @Test void previewIsReadOnlyAndConfirmationMustMatchExactPlan() throws Exception {
    var session = new LocalSyntheticErasureRehearsal.Session(CLOCK, LocalSyntheticErasureRehearsal.Fault.NONE);
    var before = session.snapshot();
    assertEquals(2, session.preview().affectedMatches());
    assertEquals(2, session.preview().affectedSeats());
    assertEquals(0, session.replacements());
    for (String wrong : List.of("", "yes", session.preview().confirmationText() + " ",
        session.preview().confirmationText().toLowerCase(Locale.ROOT))) {
      assertThrows(IllegalArgumentException.class, () -> session.execute(wrong));
      assertEquals(before, session.snapshot());
      assertEquals(0, session.replacements());
    }
    var other = new LocalSyntheticErasureRehearsal.Session(CLOCK, LocalSyntheticErasureRehearsal.Fault.NONE);
    assertThrows(IllegalArgumentException.class, () -> session.execute(other.preview().confirmationText()));
    assertEquals(before, session.snapshot());
  }

  @Test void executionIndependentlyChecksRemovalPreservationHashAndReplay() throws Exception {
    var session = new LocalSyntheticErasureRehearsal.Session(CLOCK, LocalSyntheticErasureRehearsal.Fault.NONE);
    var before = session.snapshot();
    var result = session.execute(session.preview().confirmationText());
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED, result.outcome());
    assertEquals(2, result.clearedSeats());
    assertEquals(2, result.rehashedMatches());
    assertEquals(1, result.unchangedMatches());
    assertEquals(1, session.replacements());
    assertNotEquals(before, session.snapshot());
    assertFalse(session.snapshot().toString().contains("synthetic-erasure-owner"));
    assertEquals(2, session.snapshot().get("accounts").size()); // header + other account
    assertEquals(4, session.snapshot().get("erasure_ledger").size()); // header + sentinel + two tombstones
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED, session.execute(session.preview().confirmationText()).outcome());
    assertEquals(1, session.replacements());
  }

  @Test void safetyScenariosCoverOwnershipStalenessDryRunAndFaultOutcomes() throws Exception {
    assertEquals(8, LocalSyntheticErasureRehearsal.checkSafetyScenarios());
  }

  @Test void unknownOutcomeAndNotAppliedNeverRetryOrReportVerified() throws Exception {
    for (var fault : List.of(LocalSyntheticErasureRehearsal.Fault.BEFORE_WRITE,
        LocalSyntheticErasureRehearsal.Fault.READBACK_UNAVAILABLE)) {
      var session = new LocalSyntheticErasureRehearsal.Session(CLOCK, fault);
      var result = session.execute(session.preview().confirmationText());
      assertEquals(fault == LocalSyntheticErasureRehearsal.Fault.BEFORE_WRITE
          ? OperatorAccountErasure.Outcome.NOT_APPLIED : OperatorAccountErasure.Outcome.UNCERTAIN, result.outcome());
      assertEquals(1, session.replacements());
      assertEquals(0, result.clearedSeats());
    }
  }

  @Test void argumentsAndApplicationOrExternalOverridesAreRejectedBeforePreview() {
    for (String argument : List.of("--help", "--fixture=/tmp/private.json", "--spring.profiles.active=prod", "--execute")) {
      var output = new ByteArrayOutputStream();
      assertEquals(2, LocalSyntheticErasureRehearsal.run(new String[]{argument}, Set.of(), Set.of(),
          new StringReader(""), new PrintStream(output, true, StandardCharsets.UTF_8)));
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("REJECTED"));
      assertFalse(output.toString(StandardCharsets.UTF_8).contains("PREVIEW"));
      assertFalse(output.toString(StandardCharsets.UTF_8).contains(argument));
    }
    for (String key : List.of("GOOGLE_APPLICATION_CREDENTIALS", "BRIDGE_STORAGE_MODE", "PUBLIC_ORIGIN",
        "SPRING_CONFIG_IMPORT", "spring.profiles.active", "bridge.sheets.id", "loader.main",
        "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "https_proxy", "javax.net.ssl.trustStore")) {
      assertThrows(IllegalArgumentException.class,
          () -> LocalSyntheticErasureRehearsal.validateLaunch(new String[0], Set.of(key), Set.of()), key);
      assertThrows(IllegalArgumentException.class,
          () -> LocalSyntheticErasureRehearsal.validateLaunch(new String[0], Set.of(), Set.of(key)), key);
    }
    assertDoesNotThrow(() -> LocalSyntheticErasureRehearsal.validateLaunch(new String[0],
        Set.of("PATH", "JAVA_HOME", "HOME"), Set.of("java.version", "java.class.path", "user.home")));
  }

  @Test void eofWrongAndOversizedInputCancelWithoutSuccessOutput() {
    for (String input : List.of("", "yes\n", "x".repeat(300))) {
      var output = new ByteArrayOutputStream();
      assertEquals(2, LocalSyntheticErasureRehearsal.run(new String[0], Set.of(), Set.of(),
          new StringReader(input), new PrintStream(output, true, StandardCharsets.UTF_8)));
      String text = output.toString(StandardCharsets.UTF_8);
      assertTrue(text.contains("PREVIEW"));
      assertTrue(text.contains("CANCELLED"));
      assertFalse(text.contains("RESULT=VERIFIED"));
      assertFalse(text.contains("Safety scenarios="));
    }
  }

  @Test void cliRequiresItsDisplayedConfirmationAndPrintsOnlySyntheticSummary() {
    var output = new ByteArrayOutputStream();
    Reader displayedConfirmation = new Reader() {
      StringReader input;
      @Override public int read(char[] chars, int offset, int length) throws IOException {
        if (input == null) {
          String printed = output.toString(StandardCharsets.UTF_8);
          assertTrue(printed.contains("PREVIEW"));
          assertFalse(printed.contains("RESULT=VERIFIED"));
          var matcher = Pattern.compile("(?m)^ERASE [^\\r\\n]+ EXCLUSIVE OFFLINE CONFIRMED$").matcher(printed);
          assertTrue(matcher.find());
          input = new StringReader(matcher.group() + "\r\n");
        }
        return input.read(chars, offset, length);
      }
      @Override public void close() {}
    };
    assertEquals(0, LocalSyntheticErasureRehearsal.run(new String[0], Set.of(), Set.of(),
        displayedConfirmation, new PrintStream(output, true, StandardCharsets.UTF_8)));
    String text = output.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("RESULT=VERIFIED (memory only)"));
    assertTrue(text.contains("Safety scenarios=8 PASS"));
    assertTrue(text.contains("NOT_APPLIED and UNCERTAIN"));
    assertFalse(text.contains("synthetic-erasure-owner"));
    assertFalse(text.contains("synthetic-other-owner"));
    assertFalse(text.contains("https://accounts.google.com"));
    assertFalse(text.contains("Exception"));
  }

  @Test void inputFailuresAreBoundedAndDoNotLeakExceptionText() {
    var output = new ByteArrayOutputStream();
    Reader broken = new Reader() {
      public int read(char[] chars, int offset, int length) throws IOException { throw new IOException("PRIVATE input detail"); }
      public void close() {}
    };
    assertEquals(1, LocalSyntheticErasureRehearsal.run(new String[0], Set.of(), Set.of(),
        broken, new PrintStream(output, true, StandardCharsets.UTF_8)));
    assertTrue(output.toString(StandardCharsets.UTF_8).contains("STOPPED"));
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("PRIVATE"));
  }

  @Test void sessionsAlwaysGenerateIndependentMemoryFixtures() {
    var one = new LocalSyntheticErasureRehearsal.Session(CLOCK, LocalSyntheticErasureRehearsal.Fault.NONE);
    var two = new LocalSyntheticErasureRehearsal.Session(CLOCK, LocalSyntheticErasureRehearsal.Fault.NONE);
    assertNotEquals(one.snapshot(), two.snapshot());
    assertNotEquals(one.preview().confirmationText(), two.preview().confirmationText());
    assertThrows(UnsupportedOperationException.class, () -> one.snapshot().clear());
    assertThrows(UnsupportedOperationException.class, () -> one.snapshot().get("accounts").clear());
  }
}
