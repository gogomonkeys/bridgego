# Isolated entry feedback preview

`node qa/entry-feedback-preview/build.mjs` builds the actual WelcomeEntry and its production CSS with local-only fetch, session, and Google prompt substitutes. No credentials, backend, real Google API, or game writes are used. Unknown fetch paths reject rather than fall through to a network request.

Publish ONLY the generated `dist` directory to the approved local preview, not to production. Open `launcher.html?width=320&height=568&mode=guest-nickname` (also 390×844).

Modes: `ready`, `connecting` (request held until local cancellation), `rate-limit` (429 + 180-second Retry-After), `stopping` (503 stopping), `google-failure` (guest stays available), `guest-nickname`, `google-nickname`. The last two click the real selected entry after readiness, for repeatable screenshot framing. Real rendered nickname forms and buttons remain interactive. Completion shows a local fixture message.
