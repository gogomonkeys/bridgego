# Authenticated Render evidence loader: implemented boundary and remaining facts

## Implemented, not executed externally

`RenderEvidenceSource.production(false, ...)` rejects before credential retrieval. Explicitly enabled construction installs an actual standard-TLS HTTPS GET client to `api.render.com`, with injected `CredentialSource`, not environment discovery. It reads only:

* `/v1/services?ownerId=<fixed>&includePreviews=true&limit=100`
* `/v1/services/<fixed>/deploys?limit=100[&cursor=<bounded>]`

Paths are pinned and identifiers validated. No scopes are created or requested; no token is generated, saved or read during development. Redirects, proxies, cookie handlers, TLS debug and HTTP header debug logging are rejected/disabled. Exceptions omit bodies, paths and credentials. Returned token char buffers are wiped; the standard Java Authorization header necessarily creates a temporary immutable String, so this is not a guarantee of JVM heap erasure.

The official Render list-services/list-deploys reference was checked. Failed deploy statuses normalize to `failed`; unknown status/schema rejects. Service enumeration includes previews. The scoped single-service policy rejects a list with more than one service rather than silently filtering it out. Deploy history follows bounded cursors (at most five pages); duplicate IDs/cursors or truncated history reject. Exact reviewed revision and process/deployment membership remain checked by SingleServiceEvidenceProvider.

Successful observations cache for 30 seconds with wall and monotonic checks. One refresh has a shared 60-second monotonic budget, each connect/read uses remaining time, and each body is at most 1 MiB. The JVM/OS DNS resolver is not independently cancellable by HttpsURLConnection, so this is not a hard wall-clock guarantee for arbitrary DNS stalls. A separate bounded resolver/executor would be needed for that stronger guarantee; no additional executor framework is invented here. No explicit HTTP retry loop exists; standard Java GET connection behavior is not claimed to be a raw-socket exactly-once transport (these are reads).

Missing process evidence returns `NotReady` before token access and can be retried by bootstrap before any authority exists. A malformed/conflicting API observation is terminal for the loader; it never serves stale cached evidence after failure. A bootstrap must wait for this source BEFORE constructing a stateful authority verifier, rather than repeatedly poison/recreate authorities after a real conflict.

## Not facts that Render can supply

Render service/deployment inventory does not identify a terminated JVM's native owner or prove its accepted queue was drained. The existing native root has owner/epoch/receipt/state; the close receipt binds operation/hash/phase. Neither records the owner's Render deployment/process generation/reviewed release nor accepted-work reconciliation facts.

`ProcessEvidence` therefore remains a required separate verified source. No loader here synthesizes it from `instances=1`, request parameters, environment strings or self-signed local JSON. Missing evidence means no write authority, not successful restart certification. The current implementation is an authenticated Render loader plus an honestly unimplemented program-evidence source, not a complete automatic restart provider.

## Smallest durable handoff extension to evaluate next

A fixed, journal-covered process binding could bind `{version, owner, deploymentId, processGeneration, sourceRevision}` when claiming an owner. A clean-close handoff could additionally bind `{closedOperation, predecessorSnapshotToken, acceptedWorkState: drained, queueCount: 0}` to the exact close receipt. These fields would have to be added in the SAME verified atomic native batch as root/receipt/journal, under a versioned plan with precise parser and budget updates. Do not append a later separate "all done" flag.

The `drained` fact is valid only when the reviewed backend has frozen admission, acquired exclusive gate and verified zero queued/active operations before that close; a caller string cannot supply it. Accepted but not yet durable records killed before a clean handoff cannot be reconstructed by adding a field afterward. Such an unplanned restart still requires known-work reconciliation or a separately designed durable admission/outbox protocol. This note does not introduce that larger protocol or claim a normal JVM kill is lossless.

A source reader could then combine an authenticated full native journal read (exact process/handoff fields) with authenticated Render predecessor deployment termination and fixed reviewed release semantics. Even that does not make Render's single instance proof of a specific OS process exit within the same deployment: a separately verifiable process-generation/termination source, or a narrower CAS-safe policy with explicitly reconciled accepted work, remains necessary.

No production schema was changed; these are the specific missing facts and a bounded proposal, not completed durable process evidence.

References checked: https://api-docs.render.com/reference/list-services , https://api-docs.render.com/reference/list-deploys , https://api-docs.render.com/reference/service-fields
