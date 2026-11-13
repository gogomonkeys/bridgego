# Formal restart candidate: review only, disabled

`NativeRestartCompiledRelease.pendingReview()` contains the known formal target,
managed dataset, existing service-account issuer, public game origin and public
Google client ID supplied by the owner (relayed by the coordinating thread).
The audience is pinned review data, not proof of a successful Google sign-in.
Recheck it against the deployed game's public configuration before actual cutover.
No credentials or operator subject are embedded here.

The candidate is deliberately not a `CleanClosedRestartAssembly.Policy` or a
`NativeRestartContract`. It has no conversion/enable method. `current()` remains
null and `require()` rejects. Flags, requests, native metadata and this document
cannot activate it. No approval reference has been invented.

A later explicitly approved reviewed build must install one fixed contract after
approving the actual target, native-only source exclusion/legacy shutdown,
restart reconciliation and Taiwan retention policy. Initial cutover and restart
must use exactly the same binding, origin, audience, cutover approval reference
and retention configuration, including its approval reference.
`NativeRestartContract.requireInitialSettings` enforces these equalities before
initial runtime use; the new tests exercise each mismatch.

The retention candidate preserves accounts and folds only verified eligible
expired personal results into lifetime totals, using the existing reducer.
This change neither converts unknown historical lifetime data nor approves
irreversible deletion. Applicable action-time authorization remains required for
real destructive execution. Same-process ownership and durable clean CLOSE
recovery constraints remain enforced by the existing runtime; pending contract
metadata does not prove an old writer was stopped or an unknown queue reconciled.

No runtime, Spring bean, external observation, schema, reducer, or write path is
changed in this review-only patch. Tests are local and use synthetic settings.
