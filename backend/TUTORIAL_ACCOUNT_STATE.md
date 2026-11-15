# Account tutorial state

Google players use the authenticated stable account ID. `accounts!F` is
`tutorial_status`; blank means not yet acknowledged and `acknowledged-v1`
means the player accepted or skipped the first-use offer. It is not proof
of tutorial completion. Guests keep device-local state, not account rows.

Old A:B, A:D and A:E account headers remain readable. The first acknowledged
choice uses one narrow batch to upgrade the header and set only the target
account's F cell. It preserves profile fields and lifetime carry. The physical
accounts grid must have at least six columns. A narrower grid is rejected,
not silently resized. Unknown field values or mismatched schemas fail closed.
An uncertain write is not retried automatically; a later authenticated status
read can reconcile a successfully saved flag.

Quarter folds retain F, preserve lifetime E and keep account identity. Account
erasure removes F with that account, while preserving other account flags.
Full live-quarter readers/writers understand the six-column account schema.
Legacy isolated synthetic cleanup fixtures remain five-column only and must
not be pointed at a tutorial-enabled account dataset. Old deployed operator
binaries that understand only A:E must be replaced before maintaining a
six-column account dataset. No production table has been migrated by tests.

Rollout checks: deploy all schema-aware readers/writers together; inspect grid
width and current schema before enabling persistence; use only an isolated
fixture for destructive acceptance; confirm real Google choice readback across
sessions/devices online. Preserve existing quarter maintenance admission and
single-writer controls; a sheet marker is not a distributed lock.

## Read-only preflight before publication

1. Confirm the exact configured spreadsheet and dataset marker using the existing
   binding checks. Read sheet metadata and the complete accounts grid, including
   header, values and column count; do not write or resize during this check.
2. Accept only the supported old headers or the exact six-column header above.
   For an old header, F must be empty in every row. For the new header, F values
   must be blank or `acknowledged-v1`. Validate UUID uniqueness, E lifetime values
   and the existing retention/erasure state. Never overwrite unrelated F content.
3. If fewer than six physical columns exist, stop and arrange a separately
   approved layout-only migration during the existing writer freeze: add enough
   blank columns without deleting or moving existing cells. Read back old A:E
   unchanged and F blank before allowing the new application to write its header.
4. Publish compatible gameplay, identity and maintenance readers/writers together.
   The application upgrades the header and target F flag atomically on the user's
   first tutorial choice; it does not mass-rewrite accounts or recompute scores.
5. Confirm that choice using readback, then a different authenticated session of
   the same account. Check another account and guest preferences remain distinct.
   No destructive live cleanup is part of this preflight.

Do not roll back to an A:E-only maintenance/operator binary after F is in use.
If rollback is required, stop writes and select a schema-compatible build;
never drop F merely to make old code accept the workbook. An independently
maintained automatic-quarter candidate not merged into this tree still requires
its own six-column compatibility review and regression tests.

## Publication and recovery sequence (not executed)

- Keep automatic-quarter activation out of this gameplay/tutorial release unless
  its separate candidate has been merged and tested against the same schema.
- Before rollout, coordinate the existing maintenance/writer-stop mechanism so
  old/new writers and A:E-only operators cannot overlap the first F upgrade.
  Deploy the same verified schema-aware release to applicable readers/writers,
  check the actual commit/health, then reopen gameplay. Do not clear real matches.
- Online acceptance can then check a fresh Google tutorial choice/readback,
  a second session of the same account, a different account, guest choice,
  skip/re-entry, tutorial completion with no statistics and an ordinary match.
- Use the schema-compatible tutorial-disable switch documented below rather than
  returning to an A:E-only artifact. Keep F-aware storage and maintenance code.
- On unknown write or online failure, stop new writes and reconcile read-only.
  Do not replay mutations, remove control markers, erase F or restore old raw
  data to force compatibility. Reopen only with a verified schema-aware artifact.


## Schema-compatible tutorial rollback switch

`BRIDGE_TUTORIAL_ENABLED=false` disables new tutorial sessions and first-use
choice writes. The default when absent is `true`; only `true` and `false` are accepted.
Malformed values stop startup rather than silently enabling admission. This is startup configuration, not a live dashboard toggle. Changing
it requires the normal release/restart process and has not been done online.

With admission disabled:

- Authenticated status returns `enabled: false` without reading account tutorial
  preference, so an unavailable preference store does not block the lobby.
- New start and accept/skip requests are rejected before preference writes or
  tutorial allocation. Existing account F values and guest device choices are
  not reset or marked acknowledged by a disabled first-use check.
- The frontend suppresses the offer and Help entry after observing disabled
  status. A stale client cannot bypass the server guard.
- Existing sessions can still read, play and exit while their process survives.
  Sessions are deliberately in-memory: a restart ends them; it does not resume
  a partly played tutorial. The next status is empty and allows lobby access.
- Normal gameplay, account/profile writes, lifetime folding and F-aware
  maintenance remain enabled under their existing controls. This switch is not
  a global writer freeze and does not certify or activate automatic cleanup.

If a write was already in flight when an instance was stopped, the flag cannot
undo it or establish its outcome. Use the existing writer-stop/readback process,
retain F and reconcile uncertain choices before reopening. For rolling releases,
all old tutorial-admitting instances must be drained/stopped; setting one new
instance's flag does not fence another process. Re-enabling must use a verified
F-compatible build; players whose choice was never saved remain eligible for the
first-use offer, while saved Google and guest preferences are preserved.
