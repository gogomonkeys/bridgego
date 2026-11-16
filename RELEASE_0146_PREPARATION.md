# v0.14.6 release preparation (not published)

Verified 2026-11-16:
- GitHub deployment branch pwa-beta-v0.7.0 remains 0389754d50607c54a9e16a07741a33ba7537b066; release-v0.14.5-ui-verified is same. master is unrelated 8cf5123bfc5bb217a5ef9f5ace702bb82fd73298; do not use master as release base.
- Render bridgego srv-ctfui4dumphs73ee36j0 live deploy dep-dbtdpo1srm7s73b0crng uses exact 0389754...; autoDeploy=no/off, one instance, free runtime, docker, healthCheckPath=/health/ready, not suspended, maintenance mode false.
- Live health ready, version 0.14.5; live public auth config enabled, managed, collectionReady=true.
- Private environment variables not freshly inspected: Render browser is signed out and connector exposes no read-env endpoint. Do not claim private mode/cleanup flag values were verified.
- Google Console credentials page gave Site Unavailable twice (one initial visit + one reload). No sign-in prompt or project permission was reached. No Console change attempted.
- No other connected computer listed. User may add exact redirect URI in their Google Cloud account; this is separate from Render login and does not require sharing client secret.

Candidate 0.14.6:
- Frontend 734 tests / 64 files, typecheck/lint/build passed.
- Backend package 1192 tests, 0 failures/errors/skips; jar manifest Implementation-Version=0.14.6.
- Prior reviewer blockers fixed and regression-tested. True iOS Home Screen cookie/container return remains a runtime validation gate.

Console setup for owner:
1. Open Google Cloud Console, select BridgeGo project.
2. Google Auth Platform > Clients (or APIs & Services > Credentials).
3. Open existing Web application whose client ID is 616174516143-entpg07e9oa4hqe8s2jb64bf64h8nj4j.apps.googleusercontent.com.
4. Add Authorized redirect URI exactly https://bridgego.onrender.com/api/auth/google/redirect/callback (no trailing slash), preserve all existing origins/URIs; save.
5. Report saved status (or screenshot only this non-secret section). Do not download/send client secret.

Controlled rollout proposal, only on root instruction:
1. Ensure no ongoing game before deployment because restart loses ephemeral sessions.
2. Publish versioned candidate branch first, verify exact remote commit and build checks. Do not alter automatic deploy setting.
3. Confirm existing PUBLIC_ORIGIN=https://bridgego.onrender.com. No new client secret, DB, permissions or paid resources.
4. First deploy candidate with BRIDGE_GOOGLE_REDIRECT_ENABLED=false (default absent=false), verify health/version, working Safari/desktop path. iOS standalone shows Safari fallback.
5. Only after Console URI saved and explicit go-ahead, set BRIDGE_GOOGLE_REDIRECT_ENABLED=true and verify new ready process, config enabled, then one controlled real iPhone Home Screen trial.
6. Keep BRIDGE_RUNTIME_MODE, collection mode, maintenance/quarterly flags unchanged. No quarterly cleanup action.
7. On failure, record exact screen/code and stop. Disable redirect flag or restore exact old commit; never clear accounts or replay an unknown finish.
