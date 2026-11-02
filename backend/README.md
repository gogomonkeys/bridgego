# BridgeGo v0.6.3 — local three-deal bridge variant

Independent Java/Spring successor; no original accounts, DB, credentials or external backend. Guest sessions use opaque bearer tokens. All state is in memory and lost on restart. Default listener is `127.0.0.1:8080`; frontend Vite proxy is loopback only. This is a locally tested prototype, not a public production deployment.

V0.6.3 changes backend artifact metadata only; Java source and protocol remain identical to delivered V0.6.2. Fresh clean-package and targeted smoke evidence is in TEST_RESULTS.md. The V0.6 329-check / 600-second run is historical, not repeated patch evidence.

## Run
Use a full JDK17+ and Maven3.9+:
```sh
mvn test
mvn package
java -jar target/bridge-room-successor-0.6.3.jar
```
This version was built and runtime-tested with full Temurin JDK 17.0.21+9 and the POM Java 17 release check; see TEST_RESULTS.md. No credentials/config copying is needed.

## Implemented flow
- Four real players, fixed seats, opposite teams NS0/2 and EW1/3. Host manually starts only when all four are ready and connected. No bots/dummy/double/redouble.
- Private13-card hands; each player sees only their own remaining cards. Privilege choice is per-deal and immutable; any teammate yes resolves team use, both no resolves unused. Reveal both team decisions together.
- Ascending level1..7 auction in CLUB/DIAMOND/HEART/SPADE/NO_TRUMP order. Three passes after bid start play; four opening passes redeal without completing a deal.
- Last bidder's left-hand player leads, then trick winner leads. Must follow led suit when held; otherwise any card. Highest trump wins, otherwise highest led-suit card; Ace high, NT no trump.
- Privilege phase and each bid/play turn have server-owned20-second budgets. Missing privilege votes default no, bid timeout Pass, play timeout lowest legal rank (suit tie CLUB/DIAMOND/HEART/SPADE). Manual/timeout provenance is visible safely; privilege provenance only to its owner.
-13 tricks produce one transparent score breakdown using confirmed custom scoring. Exact target6+level earns baselevel plus bonuslevel+1; overtrick success baselevel only; failure-level. Defence ordinaryscore0. Each used privilege adds+2 on teamwin/-1 on loss.
- After completed deals 1/2, hold DEAL_RESULT for up to 20 seconds without dealing new cards. All four authenticated participants may acknowledge to continue early; timeout advances once. Only then create the next deal and its fresh privilege timer. Active disconnect pauses this result budget with the existing ten-second reconnect/forfeit rule. After three completed deals retain MATCH_RESULT; higher total wins, equal totals draw.
- Initial dealer0, rotates clockwise after completeddeal; all-pass keeps dealer. These are disclosed implementation defaults. A terminal room can be explicitly returned to WAITING by its online host using “再玩一場”; no automatic reset.

## History projection and review gates (v0.6)

Internal engine tricks retain the authoritative cards. Public TrickView exposes number, leaderSeat, winnerSeat and cardsVisible; plays is empty until that deal reaches DEAL_RESULT/MATCH_RESULT. The current trick remains visible while played and throughout TRICK_RESULT. Each completed deal's immutable public auction rounds include all-Pass redeals. Prior settled deals remain reviewable while later deals are active. Terminal archives refresh through the same projection; committed final scores alone never bypass the two-second presentation gate. Incomplete forfeit deals do not expose collected or unplayed cards through history. This restricts built-in retrospective access, not a user's own recording of already public live cards.

## Disconnects and forfeits
WAITING last detected connection loss immediately releases seat/ready. Another live tab preserves presence. Initial HTTP join has a separate5-second subscription cleanup, not gameplay grace.

Active last connection loss freezes operation countdown and gives that player10 seconds to reconnect. Reconnect resumes remaining time only when all are connected; hands, turn and played cards remain unchanged. Operation and reconnect budgets use monotonic authority; epoch reconnectDeadline is display-only. Refresh/replay does not reset budgets. Sweeps never consume multiple future turns at once.

Manual active exit immediately forfeits that team, regardless points. Network grace expiry marks that team failed. If opponents already have running grace, only those original deadlines may delay adjudication: timely opponent return gives them the win; their expiry yields NO_CONTEST. No newly started grace extends the fixed pending window. Failed team cannot undo failure by returning late. Opposing manual exit during pending also yields NO_CONTEST; failed-team exit does not change outcome. Completed scores remain, unfinished deal is never scored. Safe forfeit events identify actual quitters/timed-out players against an immutable roster.

Terminal outcomes never change after later closes/leaves. MATCH_RESULT socket loss preserves read-only participant result access without gameplay grace or replacement; explicit leave releases membership. Names remain in frozen roster, and all-offline participants can return to inspect no-contest. Restart clears all data.

## Transport and security
Native WebSocket Ping/Pong retained from v0.1.3:2-second probes,6-second post-send response allowance, one bounded verification when an expired probe accumulated at least2seconds local scheduler lateness beyond the250ms cadence after its actual send. Output uses per-connection FIFO outside domain lock,64-message bound,15-second write-progress bound. No JavaScript heartbeat/watchdog. Logs have safe causes/timing/counters, never tokens/hands/exception bodies. Global service lock atomically enforces membership, capacity, turns, privacy projection, deadlines, replay ledger and scoring-once. Personalized immutable snapshots captured before enqueue, revision guard prevents regression; stale socket IDs cannot evict resumed clients.

Only room participants receive snapshots. Already-played cards are public; unplayed hands and unrevealed vote details stay private even at forfeit. No JWT/original-login integration is claimed. This single-process prototype has no persistence, distributed locking, session revocation/production rate-limit infrastructure or public hosting hardening.

## Provenance and preserved baselines
Architecture follows the useful Java/Spring controller/service/WebSocket shape of public AlanChen821/Bridge main `a0d01adb114869b58623a43a447bb685d60a4670`, with independent local-only implementation. No secret configs copied. Dependencies are official Maven Central artifacts. Stable v0.1.3/v0.2.0/v0.2.1 artifacts remain separate, not silently replaced. See ../CONTRACT.md, TEST_RESULTS.md and ../qa/ for exact interfaces and evidence.

### v0.3 scheduling regression and final validation
The initial mixed600-second run failed with broad latency/4001 disconnects. A fresh isolated600-second PLAYING run passed, so the exact host/load cause is not established. Deterministic tests separately demonstrated that the previous single-gap>6s guard forgot earlier/cumulative shorter local stalls. The bounded guard now tracks local lateness across the outstanding probe; it does not increase the6-second Pong deadline or15-second write bound, and only a valid Pong resets the one-verification allowance. Failed evidence is retained in QA diagnostics. The revised exact JAR subsequently passed all243network checks, including mixed600-second waiting/match-result/active-play/native soaks with the strict2-second native probe assertion unchanged. See TEST_RESULTS.md; the original host/load cause remains unproven.

## v0.4 room reuse and archive privacy
Terminal hosts who leave or lose their last socket transfer authority to the lowest online seat. If none are online, host is null; the first returning member becomes host. Scored, forfeit and no-contest outcomes share this policy.

Host-only rematch retains online members at their existing seats, releases offline memberships, clears all readiness and per-game/grace state, and returns to WAITING. No new game/deal exists until four ready and connected players pass normal host Start. Room revision and accepted-action ledger remain monotonic/preserved so old rematch or gameplay retries cannot reset/mutate a new game. Stale socket callbacks cannot inherit a replaced seat.

The last terminal result is an immutable safe archive: original roster, public completed-deal results, totals, match result and attribution. It excludes unplayed cards, private votes, tokens and action ledger. Only original participant IDs can see snapshot lastMatch or GET last-match; a newcomer taking the same seat receives no archive. An original participant who left can read it while that room still exists. A newer terminal match replaces the last archive. All explicit leaves delete the room and memory history; restart also clears it. Room summaries clearly distinguish WAITING, ACTIVE and FINISHED; finished rooms become joinable only after explicit rematch.

## Historical v0.5 trick presentation and validation
Every fourth card enters authoritative TRICK_RESULT for 2,000 ms, preserving all four cards and winner with no next actor/legal cards. The next 20-second play budget starts afterward. Ordinary disconnect freezes presentation under existing reconnect rules. Final thirteenth-trick score commits once before presentation ends; the third-deal scored outcome is immutable against late leave/disconnect.

Backend clean package and 528 unit tests passed. Independent runtime validation passed 303 actual-network checks, including full mixed 600-second stability, strict native probes and real server pause. An initial QA stale-snapshot polling assertion was corrected; that partial mixed run was excluded and the final full mixed run passed without product changes. Frontend/browser evidence is separate; no Windows/physical-mobile acceptance claim. See TEST_RESULTS.md and ../qa/.

## Historical v0.5.1 installation patch
The frontend dependency lock graph was repaired after a clean-install failure. Game/transport/layout implementation is unchanged; only application release labels and artifact version changed. Fresh patch evidence is 528 backend tests, 35 network smoke checks and a clean staging frontend install with 94 tests/checks. Prior 303-check/600-second results above describe v0.5 functional evidence, not a freshly repeated v0.5.1 full soak. Final-ZIP clean-install acceptance is reported with delivery. See TEST_RESULTS.md and ../qa/V0_5_1_INSTALL_RECOVERY.md.
