# Waiting-room voice candidate (not released)

Base: v0.15.7, commit 687d84fd4293d9d04c14ca46fe0958a07097197e.

## Scope
- Optional audio-only chat for up to four human occupants of the same waiting room.
- Off by default. A deliberate Join voice action requests microphone permission.
- Leaving, losing the game socket, starting a match or leaving the page stops local microphone tracks and closes peers. Reconnection does not re-enable the microphone.
- No recording, transcription, Sheets writes, schema changes, statistics changes, or quarterly-maintenance configuration changes.
- Existing authenticated room WebSocket relays bounded signaling only. Audio uses WebRTC peer-to-peer mesh, not Render or Google Sheets.

## Cost and privacy limits
- Initial candidate uses Cloudflare's public STUN service; no TURN subscription, billing, credentials or account is created.
- STUN does not guarantee connectivity behind restrictive NAT/firewalls. Voice failure must remain isolated from game controls. Do not advertise universal network support.
- P2P participants may learn each other's public network addresses. BridgeGo does not record, but cannot prevent a participant recording externally.
- Microphone permission belongs to the browser and remains revocable. Do not assume browser permission alone authorizes automatic microphone activation.
- A future TURN option requires separate review of provider, credentials, retention, free allowance and spending limits.

## Acceptance checks before release
- Same-room opt-in peers only; well-formed application requests with cross-room, nonmember, started-game, stale-peer or signaling-budget violations receive voice-error without terminating the game socket. Oversized SDP (>16,000 characters) or ICE (>2,048 characters) within the transport envelope also receive voice-error. Malformed JSON, entire messages over 20,000 bytes, or an exceeded WebSocket transport limit close the socket and remove its voice membership. These transport failures are not voice-only errors.
- Permission denied / unsupported browser / unresolved permission prompt remain recoverable.
- Permission arriving after Leave/unmount cannot start transmission; tracks must stop.
- New participant can connect; departure removes audio; late ICE/SDP cannot revive an old session.
- Start game, room leave, socket loss and page hide shut down voice; no automatic reconnect to voice.
- Audio autoplay rejection offers a user-gesture playback action (Safari and iOS PWA).
- Real two-device and four-device tests across Wi-Fi/mobile networks remain required. Synthetic tests cannot prove NAT traversal, audio quality, echo cancellation or iOS PWA behavior.

## Sources
- https://developers.cloudflare.com/realtime/turn/faq/
- https://developers.cloudflare.com/realtime/sfu/platform/pricing/
- https://developer.mozilla.org/en-US/docs/Web/API/RTCIceCandidate/address
- https://webkit.org/blog/7763/a-closer-look-into-webrtc/

## Scope extension approved during implementation
- Room chat combines ephemeral text and optional voice in a floating overlay, analogous to the friends panel; it does not navigate away from the waiting room.
- Closing the panel hides it without joining/leaving voice. The collapsed control keeps microphone activity visible.
- Text is same-room, plain text, bounded, and sent only while waiting. The server does not store history. Local display is bounded and cleared on room leave/change, match start or disconnect.
- No text or voice UI during an active game; server independently rejects such messages outside WAITING.
- Initial voice-only gates passed: 845 frontend tests and 1232 backend tests. Final combined-feature gates are reported below once complete.

## UI review notes
- Floating room chat should remain independent of the friends panel; hiding it must not unmount the voice engine.
- Keep microphone-active status visible on the collapsed entry.
- Text is not automatically replayed for newly joined/reconnected occupants. This is deliberately ephemeral, not an account chat inbox.
- No release number is assigned yet; package versions remain the verified v0.15.7 base until publication review.

## Combined implementation verification (2026-11-18)
- Backend full Maven regression: **1238 tests, 0 failures, 0 errors, 0 skipped**.
- Includes 9 new voice tests and 4 new text-chat tests; earlier socket tests remain passing.
- All backend modifications are confined to RoomSocket signaling/chat handling and new tests. No persistence/account/score/quarterly-cleanup source was changed.
- Text limit: 200 Unicode code points, 5 attempts per 10 seconds per authenticated socket; voice signaling has a separate 120-per-10-second budget. Server sanitizes message envelopes and supplies sender identity.
- No deployment, live account, live Sheet, live room or billing action performed during implementation.
- Combined frontend gates: **849 tests across 73 files passed**, TypeScript typecheck, ESLint and production build passed.
- 22 added frontend tests cover microphone consent and cancellation races, four-peer offer ordering, ICE queue, autoplay fallback, disconnect/game-start cleanup, panel close preserving voice, chat room isolation/plain text/bounds/clearing, and nonmodal layout constraints.
- Floating UI uses a nonmodal portal; mobile height is capped at 45dvh with a sticky collapse control, desktop placed opposite the room controls. These are source/component checks, **not device visual acceptance**.
- Outstanding before declaring end-to-end ready: two/four real devices, iOS Safari and installed PWA, microphone permission rejection/cancel, cross-network NAT, echo/volume, virtual keyboard/viewport layout, starting while panel is open/closed, and reconnect.
- Final implementation is a candidate only; no GitHub push or Render deployment.

## Review correction
- Valid voice-leave bypasses the signaling budget; withdrawal still checks the current peer ID and repeated leave does not rebroadcast. Exhausted rate limits cannot strand voice membership.
- Added regression coverage for exhausted-budget leave, stale leave, repeat leave, replacement tab join, malformed JSON and oversized transport cleanup.
- Review-corrected full regression: 1238 backend tests, 849 frontend tests, typecheck, lint and production build all passed. No publication.

## Concise copy refinement
- Removed the panel subtitle and repeated operation/network explanations; only the concise public-IP disclosure remains before joining voice. Active microphone status and controls remain visible.
- Help retains waiting-only access, ephemeral text, collapsed-panel voice behavior, cleanup, STUN and recording/IP limitations in three short paragraphs.
- Frontend full regression now 851 tests/73 files, typecheck, lint and build passed. Backend unchanged since 1238-test passing review.

## Publication record (2026-11-18)
Historical candidate-only statements above describe development gates, not the current release status. v0.16.0 was published as commit fa68176557a0617bd1c75c6e1fdcedacd2b836d9; Render deploy dep-dbun4vcipgvc739ed6rg became live at 09:43:28 UTC. Readiness returned ready and the served JavaScript matched the local build (SHA256 f360d75df57f702336bd0bb4fabf7d3767504b4a87c90d7bee735bbad2e028e7). No environment, data or quarterly-cleanup configuration was changed. Real-device voice acceptance remains separate.
