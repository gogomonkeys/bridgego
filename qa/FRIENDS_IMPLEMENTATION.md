# Friends candidate implementation

Baseline: production `dee99037c4c1f4a80c1ec4c3b00e52814d84c78c` / v0.14.6. All repository blob hashes were compared to the recovered source before editing; no differences. No production data or deployment was changed.

## Functionality
- Google-linked accounts only; guests still play normally.
- Explicit Friends entry initializes a random friend code. Code and same-table friend requests, mutual accept/reject, inline pending count; no unsolicited friend-request modal.
- Persistent friendships and pending requests; no email/account IDs in public DTOs.
- Presence heartbeat: one visible-page poll per 20 seconds; offline after 75 seconds without an eligible linked session. Aggregated across sessions. This is connection presence, not proof someone is viewing the screen.
- Waiting-room logged-in players can invite, independent of host role. Room invitations last 60 seconds, are in-memory, and do not reserve seats. Recipients Join/Ignore.
- Mutual friends can directly join an eligible public waiting room. Atomic server checks ensure friend still present, room waiting/available, and no session of the joining account is already seated. Never leaves an existing room automatically.
- Application restart discards presence/invitations; durable friendships survive.
- Details in Help, concise labels in the interface.

## Safety and operations
See `backend/FRIENDS_STORAGE.md` for exact schema, activation gate, quarterly/account erasure rules, and capacity limits. Friends default OFF, no automatic Sheet provisioning. Enabling requires explicit approved social-sheet initialization and rebuilt maintenance/account-erasure tools.

Single app writer only. Persistent operation UUID receipts prevent duplicate friend mutations. Uncertain writes stop further changes; restarting is not a recovery shortcut. Readback/reconciliation must inspect the original action. Runtime invitations are separately bounded; game mechanics and scoring were not changed.

## Validation
- Backend full default regression: 109 suites / 1,218 tests, 0 failures/errors/skips.
- Additional live-maintenance-profile focused regression: 85 tests passed before the final additional cross-quarter cache test; final cache test and full suite also passed.
- Independent reviewer focused regression: 56 tests passed across runtime, storage, account erasure and quarter maintenance.
- Frontend aggregate checks and actual visual verification status are recorded in `FRIENDS_FRONTEND_VERIFICATION.md`.

Independent review found and corrected known input errors incorrectly reported as unknown writes, a friend-leaves-between-check-and-join race, and pre-initialization same-table mutation ordering. Frontend session-epoch/unknown-state protection received a separate follow-up review.

This is a tested local candidate, not a deployed/activated production feature or actual-device acceptance certificate.

## Overlay refinement
After root visual feedback, friends are shown in a user-opened body portal dialog / mobile bottom sheet, with explicit button styles. Room invitations are a compact count entry; their details do not push the room down. Escape/Tab focus handling, actual opener focus restoration, scroll cleanup, and network/maintenance interruption unknown-state protection are tested. Final frontend aggregate: 67 suites / 760 tests passed; typecheck, lint, and production build also passed. Final managed visual preview review remains coordinated by root.
