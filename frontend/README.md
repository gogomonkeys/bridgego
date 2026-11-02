# BridgeGo frontend — v0.6.3 three-deal match

Independent React + TypeScript local prototype. V0.6 adds a warm animal/forest visual world while keeping the four-seat table readable. The supplied Figma screenshots are references, not an exact-copy requirement; the Figma file is untouched. No original service/account integration, external fonts, publication or deployment.

Mobile hands overlap at full card dimensions; selecting does not send a card until explicit confirmation. Previous collected tricks remain face down for all players until that deal enters settlement. Public history uses server-provided visibility flags, not merely CSS. Names come from the immutable match roster. Auction rounds include all-Pass redeals and stay reviewable by deal.

V0.6.3 provides a viewport-aware game mode, clearer mobile bidding controls and consistent player-facing terminology (場 / 局 / 回合). Development-only copy is removed from the primary UI, with state-version diagnostics kept on demand. Fresh patch checks are in TEST_RESULTS.md. The V0.6 105-test / 329-network-check / 600-second reports remain historical evidence; they are not relabeled as fresh patch runs.

## Run and checks
Requires Node `^22.12.0 || ^24.0.0 || >=26.0.0` (tested Node 24.22.1/npm 11.9.0). Start sibling backend 0.6.3, then:
```
npm ci --ignore-scripts
npm run dev
```
Open http://127.0.0.1:5173. Vite proxies `/api` and `/ws` to loopback HTTP 8080. `.env.example` documents another local port; non-loopback/non-HTTP backends are rejected. Production build requires a same-origin reverse proxy. This is not a stand-alone mock backend.
```
npm run typecheck
npm run lint
npm test
npm run build
```

## Match flow
1. Four distinct guest sessions join and ready. Opposite seats form NS/EW teams. Only the host can manually start with four ready, connected players. Fourth ready does not auto-start.
2. Each viewer receives only their own 13 cards. No dummy or double/redouble. Same-origin tabs share one guest/seat; use four independent profiles for four people.
3. Privilege: each player chooses once, team any yes resolves yes, both no resolves no. Both teams reveal together. A shared 20-second server phase budget defaults missing choices to no without overriding yes. Viewer-only timeout provenance identifies only your own defaulted choice.
4. Auction: ascending 1–7 calls, CLUB < DIAMOND < HEART < SPADE < NO_TRUMP. Each turn has 20 seconds; timeout is automatic Pass. Three passes after a bid immediately start play; four opening passes redeal the same deal index without scoring. Dealer starts at seat 0, rotates after a completed deal, remains unchanged on all-pass redeal.
5. Play: last bidder's left leads, then trick winners lead. Must follow led suit if held. Highest trump wins, otherwise highest led suit; Ace high, NT has no trump. Only server-supplied `you.legalCards` can be clicked on your turn. Each turn has 20 seconds; server timeout picks lowest legal rank, with CLUB/DIAMOND/HEART/SPADE tie order. Public current/last trick records identify automatic plays.
Every fourth card first enters a shared 2,000 ms TRICK_RESULT presentation: four cards remain, winning card/seat and personal counts are shown, no next player can act, and the next 20-second budget has not started. A final scored third-deal presentation is immune to later leave/disconnect.

6. After 13 tricks, backend scores exactly once. Deals 1 and 2 enter a separate DEAL_RESULT screen with a **20-second result-confirmation timer**, score breakdown, cumulative totals and each seat's acknowledgment. Four acknowledgments advance early; otherwise the backend advances at expiry. No next-deal hand or privilege clock starts before that transition. Disconnect pauses this timer and retains the unchanged 10-second reconnect rule. The third completed deal locks its result on the final card and enters MATCH_RESULT after the final trick presentation.
7. Third completed deal retains full match summary and score history. Ordinary result is higher total or draw, exactly as the backend reports; no client-side score/winner calculation.

## Exit and recovery
WAITING detected last-socket loss immediately releases seat/ready. Another live same-guest tab preserves presence. First HTTP join has a separate 5-second first-subscription handoff reservation, not reconnect grace.

Active last-socket loss pauses operations and preserves private state for the server's 10-second reconnect grace. Recovery validates membership; it never autojoins or redeals. A failed team forfeits regardless of points; completed scores stay, unfinished deal is not scored. When opposing players already have running grace periods, `FORFEIT_PENDING` waits for those original deadlines without giving another 10 seconds. Late return cannot undo failure. Both teams failed means no-contest, not a scored draw. UI shows authoritative failure teams, waiting seats and event attribution from the frozen roster.

Confirmed manual active exit causes team failure immediately; the confirmation warns about the whole-match outcome. During pending adjudication the backend decides whether this makes both teams failed. MATCH_RESULT is immutable: later leaves/closes do not alter it. Terminal membership permits reconnecting to inspect results until explicit leave. The current online host can explicitly select “再玩一場” to return to WAITING: online seats stay, offline seats are released, everyone must ready again, and no game starts automatically. Host succession uses server authority. Original participants can inspect the safe last-match archive separately in WAITING; replacement guests do not receive it. Archive contains completed public results, not unplayed hands/private choices. Finished rooms are labeled 已結束 and cannot be joined before reset.

## Commands, timers and privacy
Start/privilege/call/play/acknowledgment/rematch commands use a UUID actionId and exact expectedRevision. One pending command blocks duplicate actions. After an uncertain failure, explicit retry sends the same canonical path/body/actionId, including the original deal; synchronization is the alternative. Stale revision refetches without auto-playing another card. Server owns legality, phase, scoring, timeouts and idempotency.

Monotonic receipt anchors animate sampled operation budgets only. Equal-revision late snapshots cannot extend the same turn. Play keys include trick number and current-trick length so repeated leaders receive a distinct turn budget. Offline pauses freeze the server remainder, not restart it. Displayed zero waits for server state; no client timer submits actions or closes transport.

Module-level WebSocket lifecycle survives navigation. `/me` and member room GET reconcile before resubscribe; generation guards discard obsolete callbacks. Native server Ping/Pong remains unchanged, with no page heartbeat/watchdog. Guest token alone persists in localStorage; hand/choice/command state is memory-only and cleared from display on transport or identity change. Other players' unplayed cards remain private, including forfeits. Local server restart loses ephemeral sessions/matches.

## Table and information hierarchy
Your actual seat is visually fixed at bottom; teammate is opposite/top, next clockwise seat left and previous seat right. This rotates presentation only, never backend compass/team/turn values. Every badge includes actual direction, name, team and explicit 你/隊友/對手; color is supplementary. On mobile the hand uses overlapping full-size 48-pixel-wide cards, a full-size selected-card preview, explicit confirmation and an expanded-hand alternative.

WAITING, gameplay, DEAL_RESULT and MATCH_RESULT are distinct screens. Active play does not automatically stack previous scores. Current trick/turn/contract/team tricks occupy the table, with own hand below. Bidding uses central level and strain selectors plus explicit “確認叫牌” and independent Pass; selecting an option alone never sends. New snapshots safely clear invalid drafts instead of silently choosing another bid. Privilege choices are also central. Read-only hand cards remain full contrast; legal play is distinguished with outline, not by making other cards unreadable. History is an optional non-modal disclosure with close/Escape and focus return, separate from the live table. Help/navigation unmounts the history disclosure, so returning closes it; reopening does not reset game timers. Exit/synchronize/history are utility controls, not play targets.

## Diagnostics and verification limits
Safe diagnostics retain at most 20 entries: timestamp, lifetime, visibility, numeric code, fixed reason, retry count/clean flag. Copy JSON or select-text fallback. No tokens, identities, URLs, raw reasons, hands or command payloads.

See `TEST_RESULTS.md` for current frontend checks and `../qa/` for independent actual transport evidence. A separate QA-only Vite build at `../qa/ui-preview/` renders the real App/components/CSS with a synthetic no-network client, into `../../browser-preview/v04/`. It is never imported by the production app. Real-browser fixture checks and actual-backend network checks are separate evidence; neither alone proves real-browser end-to-end behavior or Windows stability. Final browser-fixture acceptance passed the corrected central-action layout at 390×844, 1280×720 and 1440×1000, including exact initial-viewport hand/action bounds. Separately, actual-network QA passed 284 checks and the mixed 600-second stability run. See TEST_RESULTS for the earlier issues corrected and the evidence boundaries. See TEST_RESULTS for the exact distinction and remaining platform limits.

## Historical v0.5 information layout and evidence
Seat badges include personal current-deal wins. Settled cumulative totals are visible separately from current tricks. Play/auction records, score summaries and compact previous-match result are separated; privilege icons use clickable tips rather than hover-only help. Winner/own-team cues combine icon, border and text. Mobile hand uses a horizontal rail, not page-wide overflow.

Final checks: 94 frontend tests, typecheck/lint/build; independent actual-network QA 303 checks and corrected full mixed 600-second soak. Real-browser synthetic fixtures cover 360/390/412 mobile widths and small laptop/desktop, not physical-device or live-backend browser E2E. See TEST_RESULTS.md and ../qa/V0_5_BROWSER_REVIEW.md.

## Historical v0.5.1 installation patch
The frontend dependency lock graph was repaired after a clean-install failure. Game/transport/layout implementation is unchanged; only application release labels and artifact version changed. Fresh patch evidence is 528 backend tests, 35 network smoke checks and a clean staging frontend install with 94 tests/checks. Prior 303-check/600-second results above describe v0.5 functional evidence, not a freshly repeated v0.5.1 full soak. Final-ZIP clean-install acceptance is reported with delivery. See TEST_RESULTS.md and ../qa/V0_5_1_INSTALL_RECOVERY.md.
