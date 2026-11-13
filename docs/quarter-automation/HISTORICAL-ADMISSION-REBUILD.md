# Historical casual admission reconstruction

This is a reconstruction from the restored 8fc9e0a workspace, not recovery of the missing verified candidate. No remote dispatch or enablement is provided by this change.

The pure plan requires exact target, verified CLOSED native baseline digest, source commit/hash, canonical UUID approval reference, and exact expected contribution. It replaces one active rev1 record with one rev2 record in a single journalled batch. All other tables and records must remain equal. No parallel duplicate match or invented timing/room-type data is introduced.

The explicit marker is `historical-casual-admission-v1`. The approved shape is 0.12.1, one human at seat 0 plus three bots, one scored deal, NS -1 / EW 5, human loss -1. The plan does not discover or automatically approve additional historical records. No production identity is embedded.

The extension stores source commit, original canonical hash, identity-independent facts hash, UUID approval reference and nullable redacted source hash. Removing the extension and three newly introduced markers and restoring revision 1 reproduces the original canonical source. Account erasure changes only the active identity, checks the invariant facts hash and updates the redacted source hash. It does not retain a second raw identity copy.

Both envelope codecs share exact source. Eligibility is explicit and flows into statistics, career fold, retention and account erasure. Native extraction provenance retains original source hashes and reverses exact documented deltas rather than ignoring changed files.

New tests are local only and must be run by the coordinating thread. No claim of test passage is made in this document.
