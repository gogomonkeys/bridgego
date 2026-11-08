package com.bridge.persistence;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.Console;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** One-shot dedicated operator. No Spring/game entry, ADC, env credentials or automatic writes. */
public final class LiveQuarterCli {
  private static final int MANIFEST_BYTES = 8192, KEY_BYTES = 65536;
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MANIFEST_BYTES).maxNestingDepth(8)
          .maxStringLength(4096).maxNameLength(64).maxTokenCount(100).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  interface Terminal { void print(String message); String line(String prompt); String path(String prompt); }
  interface FilesPort {
    byte[] manifest(String path);
    byte[] credentials(String path);
    boolean hasAttempt(String manifestPath);
    void createAttempt(String manifestPath, byte[] receipt);
  }
  interface TransportFactory { LiveQuarterStorage.Transport open(ManualQuarterMaintenance.Binding binding, byte[] key); }
  private LiveQuarterCli() {}

  public static void main(String[] args) {
    if (!launchAllowed(args, System.getenv().keySet(), System.getProperties().stringPropertyNames())) {
      System.err.println("REJECTED: no arguments, application configuration, proxies or credential environment inputs accepted.");
      System.exit(2); return;
    }
    Console console = System.console();
    if (console == null) { System.err.println("Interactive local console required. No file or network access started."); System.exit(2); return; }
    int status = run(new Terminal() {
      public void print(String text) { console.printf("%s%n", text); }
      public String line(String prompt) { return console.readLine("%s", prompt); }
      public String path(String prompt) {
        char[] chars = console.readPassword("%s", prompt);
        if (chars == null) return null;
        try { return new String(chars); } finally { Arrays.fill(chars, '\0'); }
      }
    }, new LocalFiles(), LiveQuarterHttp::open, Clock.systemUTC());
    if (status != 0) System.exit(status);
  }

  static int run(Terminal terminal, FilesPort files, TransportFactory factory, Clock clock) {
    byte[] key = null;
    boolean markerCreated = false;
    try {
      terminal.print("Manual quarter maintenance. Default: read-only preview. A preview may issue a Google token POST, but does not write Sheet cells.");
      terminal.print("Use only a separately approved isolated target. The manifest is a carrier of that approval, not proof or permission by itself.");
      String manifestPath = path(terminal.path("Approved target manifest path (input hidden): "));
      ManualQuarterMaintenance.Binding binding = parseManifest(files.manifest(manifestPath));
      terminal.print("Target Sheet: " + binding.spreadsheetId() + "; dataset: " + binding.datasetId());
      terminal.print("Stop all game, identity, import, maintenance, connector and manual writers; prevent restart. A digest is not a lock.");
      terminal.print("Backups and Google revision history are not erased. Type exactly: " + binding.stoppedConfirmation());
      if (!binding.stoppedConfirmation().equals(terminal.line("Stopped-writers confirmation: "))) return cancelled(terminal);
      boolean prior = files.hasAttempt(manifestPath);
      if (prior) terminal.print("A prior attempt marker exists. This invocation is preview-only; investigate the prior outcome. No marker removal or retry is offered.");
      String mode = terminal.line("Mode: press Enter for PREVIEW, or type EXECUTE for this separately authorized operation: ");
      if (mode == null || !mode.isEmpty() && !mode.equals("EXECUTE")) return cancelled(terminal);
      boolean execution = mode.equals("EXECUTE") && !prior;
      terminal.print("Only the selected local service-account key will be read. No ADC or credential environment is used.");
      terminal.print("It may obtain a Google access token with spreadsheets scope and access only the exact target above. The key itself may have broader permissions.");
      String readConsent = "READ KEY FOR SHEET " + binding.spreadsheetId() + " DATASET " + binding.datasetId();
      terminal.print("Type exactly: " + readConsent);
      if (!readConsent.equals(terminal.line("Local key and Google access consent: "))) return cancelled(terminal);
      String keyPath = path(terminal.path("Service-account JSON path (input hidden): "));
      key = files.credentials(keyPath);
      if (key == null || key.length < 1 || key.length > KEY_BYTES) throw new IllegalStateException();
      // The actual key and actual transport are used only when a human runs this approved local entry.
      // Tests inject an entirely fake file port and HTTP factory; no runtime flag can select fake auth.
      try (var storage = new LiveQuarterStorage(binding, factory.open(binding, key), clock, () -> true)) {
        Arrays.fill(key, (byte)0); key = null;
        var operator = new ManualQuarterMaintenance(binding, storage, clock, () -> true, execution);
        var preview = operator.preview(binding.stoppedConfirmation());
        String wholeFingerprint = storage.fingerprint();
        terminal.print("PREVIEW: cutoff=" + preview.cutoff() + "; expired matches=" + preview.expiredCommits()
            + "; before=" + preview.beforeDigest() + "; whole-workbook=" + wholeFingerprint + "; expires=" + preview.expiresAt());
        terminal.print("Accounts, identities, tombstones and current match JSON/hashes remain; aggregates stay SUPPRESSED. Two atomic phases, not one transaction.");
        if (!execution) { terminal.print("PREVIEW ONLY: no Sheet-cell write attempted. This is not a verification of any prior attempt."); return 0; }
        if (operator.alreadyComplete(preview.planId())) {
          var result = operator.inspect(preview.planId());
          if (!preview.expiresAt().isAfter(clock.instant())) throw new IllegalStateException();
          terminal.print("READ-ONLY RESULT=" + result.outcome() + "; cutoff=" + result.cutoff()
              + "; preview found this quarter already complete. No Sheet-cell write or attempt marker created.");
          return result.outcome() == ManualQuarterMaintenance.Outcome.COMPLETE ? 0 : 3;
        }
        terminal.print("Execution records a non-secret one-use attempt marker beside this manifest before any cell write.");
        String wholeConfirmation = preview.confirmationText() + " WORKBOOK " + wholeFingerprint;
        terminal.print("Fresh exact confirmation: " + wholeConfirmation);
        if (!wholeConfirmation.equals(terminal.line("Execute this plan: "))) return cancelled(terminal);
        // CREATE_NEW is mandatory. A partial marker after a crash still blocks future execution.
        byte[] receipt = JSON.writeValueAsBytes(Map.of("version", 1, "state", "ATTEMPT_STARTED_OR_OUTCOME_REQUIRES_REVIEW",
            "spreadsheet_id", binding.spreadsheetId(), "dataset_id", binding.datasetId(), "plan_id", preview.planId(),
            "before_sha256", preview.beforeDigest(), "workbook_sha256", wholeFingerprint,
            "cutoff", preview.cutoff().toString(), "created_at", clock.instant().toString()));
        files.createAttempt(manifestPath, receipt); markerCreated = true;
        storage.authorize(preview, wholeFingerprint);
        var result = operator.execute(preview.planId(), preview.confirmationText());
        terminal.print("RESULT=" + result.outcome() + "; cutoff=" + result.cutoff() + ". Keep all writers stopped until independent verification and cleanup review.");
        if (result.outcome() == ManualQuarterMaintenance.Outcome.COMPLETE) return 0;
        terminal.print("No write is retried. A restart with this manifest remains preview-only; do not copy/remove the marker to bypass investigation.");
        if ("INSPECT".equals(terminal.line("Type INSPECT for one read-only reconciliation, or Enter to exit: "))) {
          result = operator.inspect(preview.planId()); terminal.print("READ-ONLY RESULT=" + result.outcome());
        }
        return result.outcome() == ManualQuarterMaintenance.Outcome.COMPLETE ? 0 : 3;
      }
    } catch (Exception | LinkageError failure) {
      terminal.print(markerCreated
          ? "STOPPED: execution may be uncertain. Keep writers stopped, retain the attempt marker, and investigate; no automatic retry."
          : "STOPPED: target, input, key, storage or authorization was rejected. No completion asserted; no details displayed.");
      return 3;
    } finally { if (key != null) Arrays.fill(key, (byte)0); }
  }

  static ManualQuarterMaintenance.Binding parseManifest(byte[] bytes) {
    try {
      if (bytes == null || bytes.length < 1 || bytes.length > MANIFEST_BYTES) throw new IllegalStateException();
      JsonNode root = JSON.readTree(bytes);
      Set<String> fields = new HashSet<>(); root.fieldNames().forEachRemaining(fields::add);
      if (!root.isObject() || !fields.equals(Set.of("manifest_version", "purpose", "spreadsheet_id", "dataset_id"))
          || !root.path("manifest_version").isIntegralNumber() || !root.path("manifest_version").canConvertToInt()
          || root.path("manifest_version").intValue() != 1
          || !root.path("purpose").asText().equals("bridgego-manual-quarter-maintenance-v1")
          || !root.path("spreadsheet_id").isTextual() || !root.path("dataset_id").isTextual()) throw new IllegalStateException();
      return new ManualQuarterMaintenance.Binding(root.get("spreadsheet_id").asText(), root.get("dataset_id").asText());
    } catch (Exception failure) { throw new IllegalStateException("Target manifest rejected"); }
  }
  private static String path(String value) {
    if (value == null || value.isBlank() || value.length() > 4096 || value.indexOf('\0') >= 0) throw new IllegalStateException();
    return value;
  }
  private static int cancelled(Terminal terminal) { terminal.print("CANCELLED: no Sheet-cell write attempted."); return 2; }
  static boolean launchAllowed(String[] args, Set<String> env, Set<String> props) {
    if (args == null || args.length != 0) return false;
    for (Set<String> names : List.of(env, props)) for (String name : names) {
      String key = name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
      if (List.of("BRIDGE_", "GOOGLE_", "SPRING_", "SERVER_", "MANAGEMENT_", "LOADER_", "JAVAX_NET_SSL_")
          .stream().anyMatch(key::startsWith) || Set.of("PUBLIC_ORIGIN", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
          "_JAVA_OPTIONS", "JAVA_OPTS", "CLASSPATH", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "HTTP_PROXYHOST",
          "HTTPS_PROXYHOST", "SOCKSPROXYHOST", "JAVA_NET_USESYSTEMPROXIES", "JAVA_SECURITY_MANAGER",
          "JAVA_SECURITY_POLICY").contains(key)) return false;
    }
    return true;
  }

  private static final class LocalFiles implements FilesPort {
    public byte[] manifest(String path) { return read(path, MANIFEST_BYTES); }
    public byte[] credentials(String path) { return read(path, KEY_BYTES); }
    public boolean hasAttempt(String path) { return Files.exists(marker(path), LinkOption.NOFOLLOW_LINKS); }
    public void createAttempt(String path, byte[] receipt) {
      try (var channel = java.nio.channels.FileChannel.open(marker(path), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        var buffer = java.nio.ByteBuffer.wrap(receipt);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      catch (Exception failure) { throw new IllegalStateException("Attempt marker unavailable"); }
    }
    private static Path marker(String path) { return Path.of(path + ".quarter-attempt.json"); }
    private static byte[] read(String value, int limit) {
      try {
        Path path = Path.of(value);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) throw new IllegalStateException();
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
          byte[] data = stream.readNBytes(limit + 1);
          if (data.length < 1 || data.length > limit) { Arrays.fill(data, (byte)0); throw new IllegalStateException(); }
          return data;
        }
      } catch (Exception failure) { throw new IllegalStateException("Selected local input unavailable"); }
    }
  }
}
