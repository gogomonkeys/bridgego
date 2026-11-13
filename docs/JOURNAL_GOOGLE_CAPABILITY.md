# Journal Google session capability (local candidate only)

## Implemented

`JournalGoogleFactory` assembles the existing bounded `BatchedSheetsTransport` with a new `JournalGoogleOAuthExchange` and `JournalSessionAuthority`. The new exchange reuses the reviewed OAuth/TLS/HTTP implementation; changes are the separate journal-session factory and authority type/hooks. The original exchange, read-only factory, BootArm exclusions and extracted 30-file provenance remain unchanged.

There are no new HTTP retries, redirects, proxies, arbitrary hosts or paths. Production routes remain Google OAuth `/token` and the exactly configured Sheets resource on HTTPS port 443. TLS endpoint identity, request/header/body/deadline limits and secret-buffer cleanup remain in place. No network was used in the local composition tests.

The source-cutover capability is a bounded **ordinary gameplay writer session**, not permission to clean up personal data:

- Fixed spreadsheet, dataset, purpose, service-account issuer, owner and approved source/baseline.
- Explicit trusted source-cutover approval and ownership verifier required before credential reads and at each operation. No environment flag creates this authority.
- Maximum one hour and 128 dispatched batches; wall-clock rollback or monotonic expiry closes admission.
- No automatic renewal, persistence/reconstruction of authority, or automatic takeover after restart.
- Each write requires one exact `JournalPlan`. The plan's ordinary/rotation wire is re-derived and checked again at the capability boundary, not merely compared by simulated end state.
- The one-shot final-wire permit is consumed immediately before the first Sheets POST byte.
- An ACK is insufficient: the authority remains VERIFYING until a fresh full native snapshot matches the journal plan. Unknown outcomes retire the whole session.
- Raw `batch(String)` calls on the factory view are denied. `JournaledNativeTransport` passes the typed final plan through `JournalPlanTransport.dispatchPlan`; the plan is consumed only once.

Permitted ordinary shapes are CLAIM/CLOSE without data changes, one identity insertion, one existing profile update, one match append, or exact data-preserving journal rotation. Account IDs and career carry cannot change; the existing schema version may advance from a legacy row to the profile schema. Retention ledger, aggregate policy and erasure ledger cannot change. PENDING/APPLIED and quarterly-delete batches are not authorized by this capability.

## Not provisioned / not certified

No production approval/ownership provider is installed. `JournalSessionAuthority.provision` is a package-private trusted integration seam, not an owner-facing authorization workflow. Test verifiers are fixtures, not evidence that a live service has exclusive ownership.

This code has not been deployed, supplied with live credentials, or used to write the production Sheet. Tests cover the bounded factory's local plan/transport composition, not a live Google write acceptance.

A session expiry/unknown/close cannot silently resume; it requires fresh source/ownership verification. Closing Java resources does not itself prove a remote CLOSED handoff or absence of delayed requests.

A separately scoped maintenance authority is still needed for the journal quarterly protocol. The old fake-table BootArm permit is intentionally not widened or reused. Ordinary recording delegation must not be mistaken for authorization to permanently erase data.

## Local checks

`JournalSessionAuthorityTest` covers denied/missing cutover approval, default-off credential boundary, exact target/wire, duplicate dispatch, ACK-vs-readback, unknown latching, expiry/clock rollback, maintenance rejection, deceptive receipt-deletion framing, assembled handle writes, deterministic cleanup on delegate-close failure and Google host/path restrictions. Full backend composition tests are maintained with the native backend suite.
