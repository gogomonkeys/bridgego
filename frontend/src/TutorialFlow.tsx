import TutorialBidding from "./TutorialBidding";
import { useEffect, useRef, useState } from "react";
import { accountRequest } from "./google-login";
import { TutorialInvitation } from "./TutorialGuide";
import {
  guestTutorialOffered,
  markGuestTutorialOffered,
  type TutorialStatus,
  type TutorialSnapshot,
} from "./tutorial";

import TutorialPlay from "./TutorialPlay";
export default function TutorialFlow({
  token,
  force = false,
  onDone,
  onAvailability,
  onTableActive,
}: {
  token: string;
  force?: boolean;
  onDone: () => void;
  onAvailability?: (enabled: boolean) => void;
  onTableActive?: (active: boolean) => void;
}) {
  const [status, setStatus] = useState<TutorialStatus | null>(null);
  const [biddingPractice, setBiddingPractice] = useState(false);
  const [reviewIntroduction, setReviewIntroduction] = useState(false);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const active = useRef(true),
    pending = useRef(false);
  const leaveAfterReadback = useRef(false);
  const done = useRef(onDone);
  done.current = onDone;
  const availability = useRef(onAvailability);
  availability.current = onAvailability;
  useEffect(() => {
    active.current = true;
    let live = true;
    void readStatus(token)
      .then((next) => {
        if (!live) return;
        setStatus(next);
        availability.current?.(next.enabled);
        if (!next.enabled && !next.game) {
          done.current();
          return;
        }
        if (
          !force &&
          !next.game &&
          (next.signedIn ? next.offered : guestTutorialOffered())
        )
          done.current();
      })
      .catch(() => {
        if (live) setError("教學暫時無法讀取，可以先進大廳。");
      })
      .finally(() => {
        if (live) setBusy(false);
      });
    return () => {
      live = false;
      active.current = false;
    };
  }, [token, force]);
  const update = async (path: string, body: unknown, leave = false) => {
    if (
      pending.current ||
      uncertain ||
      (!status?.enabled &&
        (path === "/tutorial" || path === "/tutorial/choice"))
    )
      return;
    pending.current = true;
    leaveAfterReadback.current = leave;
    setBusy(true);
    setError("");
    try {
      const result = await accountRequest<
        TutorialStatus | TutorialSnapshot | { exited: boolean }
      >(path, "POST", body, token);
      const next: TutorialStatus =
        "signedIn" in result
          ? result
          : {
              enabled: status!.enabled,
              signedIn: status!.signedIn,
              offered: status!.offered,
              game: "sessionId" in result ? result : null,
            };
      if (!active.current) return;
      setStatus(next);
      if (
        next.enabled &&
        !next.signedIn &&
        (path === "/tutorial" || path === "/tutorial/choice")
      )
        markGuestTutorialOffered();
      if (leave) done.current();
    } catch {
      if (!active.current) return;
      setUncertain(true);
      setError("結果尚未確認，請先讀取狀態，不要重複送出。");
    } finally {
      pending.current = false;
      if (active.current) setBusy(false);
    }
  };
  const refresh = async () => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    try {
      const next = await readStatus(token);
      if (!active.current) return;
      setStatus(next);
      availability.current?.(next.enabled);
      setUncertain(false);
      setError("");
      if (!next.enabled && !next.game) {
        done.current();
        return;
      }
      if (
        leaveAfterReadback.current &&
        !next.game &&
        (!next.signedIn || next.offered)
      )
        done.current();
    } catch {
      if (active.current) setError("仍無法讀取教學，請稍後再確認。");
    } finally {
      pending.current = false;
      if (active.current) setBusy(false);
    }
  };
  const skip = () => {
    if (pending.current || !status) return;
    if (!status.enabled) {
      done.current();
      return;
    }
    if (status.signedIn && !status.offered) {
      void update("/tutorial/choice", { start: false }, true);
      return;
    }
    if (!status.signedIn) markGuestTutorialOffered();
    done.current();
  };
  const game = status?.game;
  const showingTable =
    !uncertain && !!status && (!!game || (status.enabled && biddingPractice));
  useEffect(() => {
    onTableActive?.(showingTable);
    return () => onTableActive?.(false);
  }, [showingTable, onTableActive]);
  return (
    <section className="panel tutorial-panel" aria-label="教學場">
      {error && <p role="alert">{error}</p>}
      {uncertain ? (
        <button disabled={busy} onClick={() => void refresh()}>
          讀取教學狀態
        </button>
      ) : !status ? (
        <>
          <p role="status">
            {busy ? "正在準備教學…" : "可稍後從大廳開啟新手教學。"}
          </p>
          <button disabled={busy} onClick={() => done.current()}>
            先進大廳
          </button>
        </>
      ) : !game && !status.enabled ? (
        <p role="status">教學暫停開放。</p>
      ) : game && reviewIntroduction ? (
        <TutorialBidding
          busy={busy}
          initialStep={2}
          onBack={() =>
            void update("/tutorial/exit", { sessionId: game.sessionId }, true)
          }
          onBegin={() => setReviewIntroduction(false)}
        />
      ) : !game && biddingPractice ? (
        <TutorialBidding
          busy={busy}
          onBack={() => setBiddingPractice(false)}
          onBegin={() =>
            void update(
              status.signedIn && !status.offered
                ? "/tutorial/choice"
                : "/tutorial",
              status.signedIn && !status.offered ? { start: true } : {},
            )
          }
        />
      ) : !game ? (
        <TutorialInvitation
          busy={busy}
          onStart={() => setBiddingPractice(true)}
          onSkip={() => skip()}
        />
      ) : (
        <TutorialPlay
          key={game.sessionId}
          game={game}
          onReviewIntroduction={() => setReviewIntroduction(true)}
          busy={busy}
          onPlay={(card) =>
            update("/tutorial/play", {
              sessionId: game.sessionId,
              expectedRevision: game.revision,
              card,
            })
          }
          onExit={() =>
            void update("/tutorial/exit", { sessionId: game.sessionId }, true)
          }
        />
      )}
    </section>
  );
}
async function readStatus(token: string): Promise<TutorialStatus> {
  const status = await accountRequest<TutorialStatus>(
    "/tutorial/status",
    "GET",
    undefined,
    token,
  );
  if (
    typeof status.enabled !== "boolean" ||
    typeof status.signedIn !== "boolean" ||
    typeof status.offered !== "boolean"
  )
    throw new Error("Unknown tutorial status");
  return status;
}
