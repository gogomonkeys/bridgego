# Native writer adapter (local candidate only)

NativeWriterAdapter bridges the existing server-created FinalMatchEnvelope without reserializing or changing its canonical hash. Both codecs must accept the exact same canonical payload. It uses the extracted Service's managedIdentity and match planning APIs, not the legacy appendAtomic writer.

Identity: issuer+Google subject remains the identity key. A nickname change resolves the same account. Profile updates modify B:D only and the exact expected account row retains column E lifetime. New account+identity is one batch. Four/five-column profile headers must already be migrated; the adapter does not silently upgrade legacy headers.

Match: the Service validates the full immutable match contract and account references. It appends envelope chunks and commit metadata together. Native chunks are 10,000 characters versus the legacy writer's 20,000; chunk count and canonical payload/hash remain the decoding contract. Historical legacy records are NOT upgraded or assigned invented statistics eligibility.

Atomicity: each Service ticket wire already includes the anti-replay receipt, all data mutations and root transition in one Google batch. The adapter does not issue a subsequent journal-write request. UNKNOWN retains the original ticket and only permits read-only reconciliation; it cannot accept another operation or resend the batch.

A separate journal-aware transport is still required before real writer cutover. It must include journal evidence in that same atomic batch and make journal verification part of reconciliation, not write evidence afterward or suppress metadata from native snapshots. The adapter currently has no Spring bean, public network factory or production enablement. Tests use an in-memory native model only. Restart recovery cannot be inferred from the retained in-process ticket alone.

## Journal composition added (still local-only)

JournaledNativeTransport prepares NativeCommitJournal's exact final wire before dispatch and persists journal + receipt + root + data together in that single atomic batch. It does NOT claim a separate durable pre-dispatch intent log. The pending JournalPlan is consumed once; unknown results cannot resend. Every snapshot/control/related read verifies full native journal evidence without stripping foreign metadata. A pending operation only clears on its exact expected APPLIED snapshot; an unchanged BEFORE snapshot stays blocked, allowing a late apply to be verified without a second write.

This deliberately conservative prototype rereads the full bounded workbook for proof verification; it is not yet an efficient narrow-read production transport. Quarter mutation/rotation are rejected because old exact QuarterPlan/WritePermit expectations do not include journal updates. Existing Google capability would reject the combined wire; no live capability is granted. Restart still requires ownership/recovery work: an OPEN root is not reclaimed by a new Service. A CLOSED root alone does not prove exclusive ownership.

The wrapper forwards operation scopes to the underlying transport with all eight tables covered and the original caller duration unchanged. It does not replace a Google Flight with a no-op or enlarge the deadline. Since the conservative proof check uses full snapshots, narrow scopes cannot be claimed; request budgets can be exhausted and must fail closed. No actual Google validation of this combined transport has been performed.
