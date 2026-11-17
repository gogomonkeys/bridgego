# BridgeGo v0.15.2 candidate
Reconstructed from verified v0.15.1 d1a85ac source plus tested nickname10-compatible fallback after original local lobby candidate was unavailable. Not a byte-identical recovery of the earlier candidate.
Scope: lobby section separation/responsive layout, concise nickname entry, max 10 Unicode graphemes frontend and backend. No dataset schema, game/statistics logic, deploy flags, quarter cleanup changes. Dependencies unchanged.
Validation: backend 1222 tests pass; frontend 769 tests pass; typecheck, lint, production build pass; backend package pass. Independent browser visual QA pending at preparation time.
Rollback: do not directly revert to old 5-character validator after longer names saved. Use preserved nickname10-compatible fallback (Library verified artifact) or forward fix. No truncation/migration.
