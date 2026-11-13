# Restart integration local result (2026-11-13)

Local production sources, not a deployed or provisioned production release.

- `mvn -Pweb clean package`: 1,435 tests, no failures/errors/skips.
- Independent review corrected a late coordinator completion reopening domain during shutdown. The native-only irreversible domain latch, terminal-gate guard, and scheduler stop prevent new work; no gate-to-domain lock inversion was added.
- Normal quiescent shutdown uses a bounded native CLOSE and full readback before disposal. Active games, unresolved writes, failed/late shutdown, and incomplete maintenance are not represented as a successful handoff.
- Compiled restart contract is deliberately absent. Environment flags do not establish target/source/retention authorization.
- A fresh JVM can claim only verified durable clean-close lineage under an explicitly approved native-only writer contract. Due state must complete maintenance before ordinary admission.
- Local derived packaged test artifacts retain production class/library bytes and add a test-only main, synthetic file IO and clock initializer. They are not the unmodified production main, Google API or Render lifecycle evidence.
- Free-host forced termination or unresolved OPEN/UNKNOWN can still require investigation; this is not universal crash recovery or guaranteed preservation of an in-progress game.
- No new deployment, formal-sheet write, new sharing, or automatic formal cleanup was performed for this local integration work.

Packaged verification completed:
- Unmodified production JAR: three unprovisioned/forged-environment/disabled startup negatives reject; no test harness classes included.
- Derived JAR: actual SIGTERM followed by a new JVM passed in the same quarter and after the quarter boundary. All eight synthetic tables matched fixture reducer expectations, including retained account/lifetime data.
- Busy OPEN, unresolved current-process CLAIM, and unfinished PENDING cases remain maintenance without blind replay; invalid contract refuses before synthetic storage startup.
- The derived-only clock initializer is registered using an added `META-INF/spring.factories`; the original artifact has no such entry. Earlier attempts using `context.initializer.classes` did not register in this Boot build and correctly failed the cross-quarter readiness check. No production safety condition was relaxed.
