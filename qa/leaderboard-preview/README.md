# Synthetic leaderboard preview

Build: `node qa/leaderboard-preview/build.mjs` from repository root.
Serve the generated `dist` directory using a local static server.
Query options: `?scenario=normal`, `empty`, `disabled`, `error`.

The fixture imports the actual StatisticsPanel and production CSS, supplies synthetic-only responses in memory, and blocks network connections through CSP. It does not contain Google credentials or real player data, and does not call production APIs.

Suggested viewports: 320×740, 390×844, 768×1024, 1440×1000. Check season/lifetime controls (formal ranking only), tied ranks, personal marker, negative scores, disclosure expansion, error/disabled/empty states and horizontal overflow. The `qa-result` output records geometry for overflow inspection when the browser can execute the page.

2026-11-14: fixture build passed (38 modules). Browser visual QA remains **unverified**: installed Chromium aborted because its process socket creation was denied; the separate cloud browser could not connect to the local preview (`ERR_CONNECTION_REFUSED`). No screenshots were obtained, no production site was visited, and the temporary local server was stopped. Do not treat this result as successful visual acceptance.
