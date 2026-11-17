# Friends frontend verification

Baseline: v0.14.6 (production dee99037), new friends development only. No publication or production data touched.

## Implemented
- User-opened portalled friends dialog on desktop / scrollable bottom sheet on mobile, available in lobby and waiting room without reflowing or leaving the current room. Escape, focus containment, trigger-focus restoration and body-scroll cleanup included.
- First explicit Friends click initializes code; GET polling never initializes.
- Friend code and same-table requests; inline Accept/Reject and red count, no request popup.
- Online/In game/Offline; join eligible friend's waiting room or invite into current waiting room.
- Room invitations Join/Ignore, expired/occupied current room Join disabled. Server join result adopted directly, no second join.
- 20-second non-overlapping visible-page polling; unknown mutation result blocks further writes, no mutation retries.
- Identity-change/unmount guards, stale-read generation after mutation, request double-click guard.
- Concise UI; full behavior explanation in Help. No private account IDs or emails in UI.

## Automated checks
2026-11-17 UTC, final feature source:
- `npm run typecheck`: passed.
- `npm run lint`: passed.
- `npm run build`: passed.
- `npm test`: 67 files, 760 tests passed (26 new friends tests).

Tests include guests hidden, explicit initialization, inline requests, waiting-room invite, direct join adoption, unknown-write lock, background pause, account change clear, stale read ignored after accept, double click, authenticated transport, dialog open/close without room changes, compact invitation entry, Tab/Shift-Tab containment, Escape and cleanup, identity change, focus/same-token verification preserving unknown-write lock, verification interrupting an in-flight write, unavailable service and stale/invalid invite expiry.

## Not verified
Actual browser visual/mobile rendering remains unverified in this execution environment. Chromium failed on environment process/socket permission (`ProcessSingleton socket() failed: Operation not permitted`) before opening local fake-data preview. No screenshot pass claimed. Existing static/responsive CSS and component tests are not a substitute for iOS/Android visual verification.

Temporary preview harness was moved outside the application checkout; no preview page is shipped.

## Drawer refinement
After root-agent 320/390px review found the inline panel displaced the waiting room, the panel was replaced by a body portal overlay/bottom sheet. Main action buttons now have explicit primary styles. Outside the sheet multiple room invites use one compact count entry; no automatic request dialog is opened. New bundle assets: `index-C7IeFRlN.css`, `index-DiFJKFpM.js`. Updated visual verification is pending root review of this bundle.

Final review also covered disabled/network interruption during an in-flight mutation: the UI keeps an explicit unknown-result lock while clearing busy state, so read-only reconciliation remains available without resending the mutation. Actual opening button focus is restored when connected.

Final one-line opener wiring correction: `mountFriendsDialog` now receives `openerRef.current ?? triggerRef.current`. Typecheck/build and 20 component/focus targeted tests passed after this change; the immediately preceding full run was 759 tests. An additional source wiring regression test is included.

Final aggregate after opener wiring: 67 files / 760 tests passed. No implementation edits after this run.

Desktop refinement per user direction: >=601px opens a right-side 480px drawer; mobile remains a bottom sheet. Typecheck, lint, production build and all 760 tests passed again after the CSS change. Root has observed the 390px managed mock preview with clear layering/buttons; final 320px and close/focus browser checks are still coordinated by root.
