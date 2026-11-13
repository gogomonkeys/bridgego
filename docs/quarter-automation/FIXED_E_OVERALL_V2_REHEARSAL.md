# Fixed E overall-v2 rehearsal candidate (not deployed)

Purpose: test the newly added internal overall counters through the existing
Google native journal protocol without creating another spreadsheet. Previous E
normal-restart and idle/wake evidence is retained; this is not a repeat of those
tests or a claim they tested v2 aggregate storage.

Input is the complete verified E CLOSED sequence-24 artifact, SHA256
`9148b6cf65e5df73536cec0cdfe36d27938e77547281c7d8c2eb64394c6dc6ca`,
full token `2954294101aec13be408deb1f0fd12236f77d8b707f83ca8793d3329252d56bd`.
The old limited CLAIM/CLOSE runner is exhausted and must not be replayed.
Spreadsheet/dataset/issuer/purpose are fixed to E, not accepted from a request,
argument or environment override. Formal binding cannot enter this path.

The business clock alone is fixed at 2027-03-31T16:00:01Z (Taipei Q2).
Authorization expiry, rollback checks and deadlines use real UTC plus monotonic
time. Existing E Q1 behavior and ordinary managed retention rules are unchanged.

After separate deployment/operation approval, mode `fixed-e-overall-v2` requires
`BRIDGE_FIXED_E_OVERALL_V2_ENABLED=true` and
`BRIDGE_READONLY_STARTUP_ENABLED=false`. Health must temporarily use `/health/live`.
The homepage and business readiness remain closed. Startup only reads/arms;
it does not execute. A fresh private one-use nonce and exact preview digest from
the authenticated control plane authorize the fixed operation within five minutes.
No Google interactive operator login is used. Existing service-account credentials
are used only for E; formal spreadsheet settings and keys are not changed.

One operation performs the established five phases: CLAIM, PREPARE, FOLD,
verified COMPLETE, CLOSE. FOLD removes exactly the two synthetic Q1 raw records,
folds their contributions into the two synthetic account lifetime carries, and
appends exact FORMAL/CASUAL counts, each `(1,1,0,1,0,0)`. Old Q3/Q4 SUPPRESSED rows
remain unavailable, not zero. Dataset, identities, erasure rows, old receipts and
unrelated metadata remain. Final sequence is 29 and root next is 31. No ordinary
game writer is opened and no new match fixtures are inserted.

On VERIFIED, `/fixed-e/verified-baseline.json` provides the fully validated
normalized synthetic projection for independent offline review and backup.
It is not the original raw Google response and is not a write authorization.
Unknown or failed phases stop at REVIEW; the maintenance host remains alive and
does not retry. A platform restart only arms a new preview and cannot automatically
resume. Even an unchanged observed baseline does not prove an old request was never
submitted; do not approve another execution after an uncertain prior request.
Receipt-slot collision protection does not replace that operational rule.

Required next authorization is broader than the previous control-only E test:
this rehearsal changes E raw result/carry/aggregate/retention contents as above.
It requires approved public candidate/synthetic snapshot publication, temporary
game interruption/settings, and then restoration. No new service or fee is needed;
ordinary included build/runtime allowance is consumed. No real data is modified.
Do not deploy, mutate E or delete/organize old evidence without that approval.

After the bounded test, restore game mode, disable the test flag, restore
`/health/ready` and the original game, then verify restoration. If state is unknown,
preserve E and investigate read-only rather than replaying. This rehearsal does not
implement a public historical statistics view; current-season DTO/UI are unchanged.
