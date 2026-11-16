# Synthetic lobby and waiting room visual QA

Run `node qa/lobby-feedback-preview/build.mjs`. Imports the real App, lobby,
room, table seats and leaderboard components. Entry/tutorial auto-advance are
fixture-only aliases. All API fetches are replaced with local synthetic responses;
unrecognized requests fail locally. Client actions only update fixture memory.
No credentials, real Google login, real game API, storage or production writes.

- `launcher.html?width=320&height=568&long=1`: guest lobby with same-name hosts and colliding short ID prefixes.
- `launcher.html?width=390&height=844&identity=google&long=1`: signed-in lobby with statistics and leaderboard.
- `launcher.html?width=1280&height=900&identity=google&long=1`: desktop.
- Add `mode=room` to inspect the waiting room header and complete-ID copy button.
- Lobby room entry buttons open the local waiting-room fixture.

After building the table fixture, copy this dist directory to
`qa/feedback-preview/dist/lobby/` to use the same local preview root without
changing table fixture source/build. Recopy after any table build, which clears
its dist. This is isolated layout QA, not a backend or authentication test.
