# BridgeGo feedback implementation — 2026-11-16

Status: local implementation and synthetic UI verification complete. NOT published.
Baseline: bae9250b45ce8c6b0a0ebba02a3531d073444999 (v0.14.3). Version unchanged pending release.

## Delivered
- Guest leaderboard viewing with session validation, private-statistics restrictions preserved; guest does not become ranked. Failure recovery and mobile lobby actions.
- Current host nickname room titles and collision-expanded short IDs. Full UUID join/copy unchanged; no table/schema migration.
- Bounded health/config GET readiness checks, Retry-After handling, manual retry, hidden/unmount abort. Google failure does not prevent an otherwise-ready guest. No mutation retries.
- Corner bidder markers, balanced contract/privilege panel, selected card spacing.
- Viewer-relative results including forfeits, ties, no-contest and archived identity. Stored scores unchanged.
- Nine actual-game tutorial steps, correct self highlight, measured rounded spotlight, primary/hint hierarchy, unique final completion action. Public trick explanation is separate from playable follow-suit step.

## Automated verification
- Frontend: 63 files, 709 tests passed. Production build and ESLint passed.
- Backend: 1181 tests passed using freshly compiled source, JDK21/source21. This environment lacks JDK17 ct.sym; production JDK17 build remains for release pipeline.
- git diff --check passed.

## Supported browser visual verification
Used CUA with managed sites-preview, isolated synthetic API fixtures; no production service calls or credentials.
- 390x844 all nine tutorial steps completed through visible controls; public trick and hand targets differ correctly; final action unique.
- 320x568 self step and public-trick step: found popup/card collision, corrected placement and rechecked clear 6px minimum gap.
- 390x844 / 430x932 bidder seats and long names checked; selected card does not cover expand-hand.
- 1200x800 table: fixed obsolete tutorial clock column and rechecked balanced header.
- Guest lobby at 320px: leaderboard only; signed-in 390px: statistics/leaderboard in row, tutorial separate.
- Long host room at 320px: wraps within card, short identifier separate, full ID retained.
- Google unavailable 390px: guest button enabled; entering guest shows nickname-specific copy and no duplicate guest-entry action.

Limits: Chromium responsive iframe checks are not physical iPhone/Safari verification. Synthetic fixtures validate presentation, not live OAuth or real multiplayer transport. Overlapping narrow hands retain expand-hand option. No live persistence checks performed in this task.

## Preview
Managed preview active at http://127.0.0.1:4173/ . Browser tab retained at launcher.html?width=390&height=844&mode=bidding . Main fixture supports all nine steps with Next, continue, select card and confirm; expand hand if desired. Read qa/feedback-preview/README.md and sibling fixture READMEs for modes/build instructions. Preview is local only and is NOT a public user link.

## Release considerations
Guest leaderboard viewing expands the audience of existing display names/ranks from signed-in users to visitors; help/consent text updated accordingly. Tell owner before publish. No email or internal account identifiers added to public output.
No production changes, no Sheet mutations, no quarterly-cleanup enabling. Keep existing release approval procedure.

## Release candidate v0.14.4
Version synchronized across UI, package manifests, backend artifact, health and persisted match version. Retention compatibility allowlists extend to 0.14.4 while retaining every prior version. Owner approved visitor visibility of existing ranked nicknames/formal results through parent conversation. GitHub deployment ref and Render live deployment both confirmed at e38bb8e0231d63f95ab688c17cb2aa94360e7482; auto deploy OFF, service unsuspended, maintenance disabled, health /health/ready. No service or ref changed during preparation.
