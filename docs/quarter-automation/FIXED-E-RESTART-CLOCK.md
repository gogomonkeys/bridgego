# Fixed E restart clock preparation (local only)

The isolated E table was advanced by an earlier synthetic test to 2027-Q1. Running restart against real 2026-Q4 must fail rather than pretending its future ledger is current.

`NativeRestartRuntimeAssembly.fixedE` is an explicit composition seam, not a public route or environment-selected deployment. It creates the security clock with `Clock.systemUTC()` and fixes the separate business clock to `2026-12-31T16:00:01Z`. All four binding values must match the existing E spreadsheet/dataset/restricted purpose/service-account issuer. Formal targets and other times reject before storage reads.

Security continuity, expiry, rollback and grant deadlines use the real security clock. Quarter classification, retention model, protocol and business gate use the fixed business clock. Spring's coordinator now reads its own gate's existing clock, avoiding a second inconsistent clock; normal and legacy gates still use real UTC. No fake clock can be supplied via an environment variable or HTTP request.

No compiled production restart contract was provisioned. No E read/write, deployment, new sharing or new service was performed. Future online composition still needs full native E readback/backup, bounded approved lineage, external maintenance-only routes and a reliable game/config restore plan. The synthetic factory itself does not establish user approval or exclusion of other writers.

Validation: targeted 18 tests pass; full `-Pweb clean package` 1,440 tests pass (zero failures/errors/skips). Independent read-only review found no blocking clock-boundary issue. Test-only packaged reflection can now be removed because the real coordinator is wired to its own gate's clock.

The refreshed derived packaged six-case suite also passes with NO reflective clock injection, NO clock initializer, and NO extra spring.factories metadata. It exercises the real same-gate coordinator clock wiring. The original packaged three disabled/forged-configuration negatives pass again. This remains local synthetic IO, not Google or Render verification.
