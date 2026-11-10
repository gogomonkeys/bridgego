# Integrated statistics UI and diagnostic candidate

Date: 2026-11-10. Baseline: published v0.13.0 commit `b1c03f720428f45aab34c063a446d21ed6a04b92`.

**Release candidate v0.13.1 combines a presentation update with diagnostics, not a confirmed fix for the intermittent read failure.** Publication is authorized; this document records pre-release validation, while actual deployment is verified separately.

## Scope

- Compact equal-width scope, category and period switches; mobile two-column personal metrics.
- Personal statistics retain quarter/lifetime and formal/casual isolation, original formulas, negative scores, consent and privacy rules.
- Personal and overall statistics retain four named outcomes: 總場次 (`matches`), 完成場次 (`scoredMatches`), 中途離席 (`forfeitMatches`) and 無勝負場次 (`interruptedMatches`). Overall shows these four plus completed deals and draws in a six-card grid; personal keeps the outcome detail expandable.
- Overall completed matches do not include forfeits or interruptions; draws are a subset, not an additive category. Missing `scoredMatches` displays unknown instead of substituting all finalized matches.
- This supersedes the earlier four-card design that removed finalized/forfeit cards. No category is removed. The text states that total matches equal the three outcome subcategories, and 中途離席 counts matches decided by departure/forfeit, not how often the viewing player personally left. API values and persisted records are unchanged.
- Detailed formulas and retention notes remain expandable. Only the statistics page hides lobby slogans and stale room notices; help retains its previous notice behavior. Error/maintenance messages are preserved.
- Bounded privacy-safe read diagnostics distinguish admission, storage, boundary and projection failures. See `STATISTICS_READ_DIAGNOSTICS.md`.

## Integrated checks

- Frontend: **569 tests / 49 files passed**.
- ESLint, TypeScript and Vite production build: passed.
- Packaged backend: **1,101 tests passed**, zero failures/errors/skips.
- Final JAR loopback HTTP: **17 checks passed**, credential-free with persistence/statistics disabled. See `STATISTICS_UI_DIAGNOSTICS_NETWORK_VALIDATION.json`.
- `git diff --check`: passed. API/document structural drift gate: 32 operations / 68 errors passed.
- All four isolated operator/rehearsal profiles package successfully for 0.13.1; no operator tool was executed against external storage.
- Independent backend diagnostic review found no blocking privacy or behavior issue; its best-effort logger safeguard was included.
- The compact source UI was exercised through CUA at desktop and mobile widths for equal controls, isolated values, details interaction and error recovery. The latest six-card CUA recheck also passed at 400 CSS pixels: two columns by three rows, no horizontal overflow, all four new labels, distinct formal/casual values, expandable disclosures and unknown legacy subcounts shown as a dash. Personal lifetime synthetic counts were verified independently from overall figures.
- An additional headless browser regression attempt could not launch because the environment rejected Chromium's socket creation. No automated browser-suite pass is claimed for that attempt.

## Limits

Pre-release validation performed no production login, game creation, Sheets write, cleanup, migration, configuration change or deployment. The authorized release uses the existing service and is verified separately. Prior read errors were not logged with a reason; successful subsequent reads and offline compatibility replay do not prove the original cause or that the intermittent issue is fixed.
