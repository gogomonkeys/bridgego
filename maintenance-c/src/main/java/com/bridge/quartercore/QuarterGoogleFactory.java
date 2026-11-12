package com.bridge.quartercore;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.Set;

/** Explicit, non-Spring factory. No environment lookup, automatic startup, ADC or retry.
 * Enabling reads grants neither a write scope nor maintenance authority. The service-account
 * key may itself have broader privileges; the transport is an additional application boundary.
 */
public final class QuarterGoogleFactory {
    private QuarterGoogleFactory() {}
    @FunctionalInterface public interface CredentialSource { InputStream open() throws IOException; }
    public record ReadConfiguration(boolean enabled, String spreadsheetId, String datasetId,
                                    String purpose, String issuer, CredentialSource credentials) {
        public ReadConfiguration(String spreadsheetId, String datasetId, String purpose,
                                 String issuer, CredentialSource credentials) {
            this(false, spreadsheetId, datasetId, purpose, issuer, credentials);
        }
        @Override public String toString() { return "QuarterGoogleReadConfiguration[REDACTED]"; }
    }
    public static ReadOnly openReadOnly(ReadConfiguration config) {
        // Before binding validation, credential reads, TLS setup, DNS or OAuth requests.
        if (config == null || !config.enabled()) throw new ManagedGoogleServiceAccount.Rejected();
        var binding = new GoogleSheetsBinding(config.spreadsheetId(), config.datasetId(), config.purpose(), config.issuer());
        if (config.credentials() == null) throw new ManagedGoogleServiceAccount.Rejected();
        var handle = ManagedGoogleSheetsFactory.open(new ManagedGoogleSheetsFactory.Configuration(
                true, binding, config.credentials()::open, null), SharedSheetsBudget.HOST);
        return new ReadOnly(handle.transport, handle, new QuarterNativeControl.Target(
                binding.spreadsheetId(), binding.datasetId(), binding.purpose()));
    }
    /** Package-private write seam only. Caller must supply the existing one-shot, exact-wire
     * BootArm capability. No boolean, environment flag or observed Sheet cell can mint one.
     * BUNDLED is UNPROVISIONED in this integration: live writes remain unavailable. This seam
     * does not make ordinary game writes durable and is not a production release provisioner.
     */
    static ManagedGoogleSheetsFactory.Handle openAuthorizedMaintenance(MaintenanceRelease release,
            ManagedGoogleServiceAccount.Source credentials, SharedSheetsBudget budget, WritePermit permit) {
        if (release == null || permit == null) throw new ManagedGoogleServiceAccount.Rejected();
        return ManagedGoogleSheetsFactory.openMaintenance(release, credentials, budget, permit);
    }
    public static final class ReadOnly implements AutoCloseable {
        private final NarrowSheetsTransport view;
        private final AutoCloseable owner;
        private final QuarterNativeControl.Target target;
        private boolean closed;
        // Package-private injection for local tests; never creates a credential/network source.
        ReadOnly(NarrowSheetsTransport delegate, AutoCloseable owner, QuarterNativeControl.Target target) {
            if (delegate == null || owner == null) throw new IllegalArgumentException();
            this.owner = owner;
            this.target = Objects.requireNonNull(target);
            this.view = new NarrowSheetsTransport() {
                public Scope operation(String label, NarrowQuarterPoc.Root predecessor, Set<String> tabs, Duration budget) {
                    requireOpen(); return delegate.operation(label, predecessor, tabs, budget);
                }
                public NativeSheetsModel.Book snapshot() { requireOpen(); return delegate.snapshot(); }
                public List<NativeSheetsModel.Metadata> control() { requireOpen(); return delegate.control(); }
                public List<List<String>> related(String tab) { requireOpen(); return delegate.related(tab); }
                public boolean unsupportedStructure() { requireOpen(); return delegate.unsupportedStructure(); }
                public void batch(String wire) { throw new ManagedGoogleServiceAccount.Rejected(); }
            };
        }
        /** Construct a read-only control bound to this handle's original target. No second target,
         * raw-response reconstruction or write session is accepted. Callers must supply real
         * independent evidence verifiers; local flags or observed CLOSED cells are not proof.
         * The handle must remain open for the control's lifetime. */
        public QuarterNativeControl control(QuarterNativeControl.OwnershipVerifier ownership,
                QuarterNativeControl.CommittedSnapshotVerifier committed, Clock clock) {
            requireOpen();
            return new QuarterNativeControl(target, this::readVerified,
                    Objects.requireNonNull(ownership), Objects.requireNonNull(committed), null,
                    Objects.requireNonNull(clock));
        }
        /** Uses the real persisted journal proof; still read-only and still requires an
         * independent ownership proof. Missing/uninitialized journal remains UNKNOWN. */
        public QuarterNativeControl controlWithJournal(QuarterNativeControl.OwnershipVerifier ownership, Clock clock) {
            requireOpen();
            return control(ownership, NativeCommitJournal.verifier(target, this::readVerified, clock), clock);
        }
        /** Fail-closed convenience: no ownership or committed-state evidence is assumed. */
        public QuarterNativeControl control(Clock clock) {
            return control(QuarterNativeControl.OwnershipVerifier.unavailable(),
                    QuarterNativeControl.CommittedSnapshotVerifier.unavailable(), clock);
        }
        /** Full strict-decoded snapshot for a same-package adapter. Do not serialize Book.nativeValue()
         * back into a Google response: that model deliberately uses a synthetic placeholder ID. */
        NativeSheetsModel.Book readVerified() {
            requireOpen();
            try (var scope = view.operation("quarter-read-only", null, BatchedSheetsTransport.ALL, Duration.ofSeconds(60))) {
                return view.snapshot();
            }
        }
        /** Adapter-only access. Never exposes the underlying exchange or writable transport. */
        NarrowSheetsTransport transport() { requireOpen(); return view; }
        private synchronized void requireOpen() { if (closed) throw new ManagedGoogleServiceAccount.Rejected(); }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            try { owner.close(); } catch (Exception failure) { throw new ManagedGoogleServiceAccount.Rejected(); }
        }
        @Override public String toString() { return "QuarterGoogleReadOnly[REDACTED]"; }
    }
}
