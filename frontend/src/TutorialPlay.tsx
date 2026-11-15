import { useState } from "react";
import GamePanel from "./GamePanel";
import PublicTrick from "./PublicTrick";
import TutorialGuide, { type TutorialLesson } from "./TutorialGuide";
import { tutorialRoom } from "./tutorial-room";
import type { TutorialSnapshot } from "./tutorial";
import type { Card } from "./model";
const lessons: TutorialLesson[] = [
  "FOLLOW_SUIT",
  "WIN_TRICK",
  "TRUMP_WHEN_VOID",
  "FREE_PLAY",
];
/** Navigation only changes explanations. The server snapshot remains authoritative. */
export default function TutorialPlay({
  game,
  busy,
  onPlay,
  onExit,
}: {
  game: TutorialSnapshot;
  busy: boolean;
  onPlay: (card: Card) => Promise<unknown>;
  onExit: () => void;
}) {
  const latest = game.lesson === "COMPLETE" ? 3 : lessons.indexOf(game.lesson);
  const [review, setReview] = useState<{
    revision: number;
    step: number;
  } | null>(null);
  const step = review?.revision === game.revision ? review.step : latest;
  const room = tutorialRoom(game);
  const previous = game.completedTricks.at(-1);
  return (
    <GamePanel
      room={room}
      online
      busy={busy}
      pendingAction={null}
      snapshotReceivedAt={0}
      tutorial={{
        actionLocked: step !== latest,
        onPlay,
        onBid: () => {},
        onExit,
        exitLabel: game.lesson === "COMPLETE" ? "進入大廳" : "離開教學",
        guide: (
          <div>
            {game.lesson === "COMPLETE" && <p role="status">教學完成！</p>}
            <TutorialGuide lesson={lessons[step]} />
            <nav className="tutorial-actions" aria-label="教學步驟">
              <button
                disabled={busy || step === 0}
                onClick={() =>
                  setReview({ revision: game.revision, step: step - 1 })
                }
              >
                上一步
              </button>
              <button
                disabled={busy || step === latest}
                onClick={() =>
                  setReview({ revision: game.revision, step: step + 1 })
                }
              >
                下一步
              </button>
              {step !== latest && (
                <span>正在回顧說明，牌局保持不變。回到目前步驟再出牌。</span>
              )}
            </nav>
            {previous && (
              <details aria-label="上一墩回顧">
                <summary>上一墩回顧</summary>
                <p>
                  {previous.winnerSeat === 0
                    ? "你"
                    : `電腦${previous.winnerSeat}`}
                  獲勝
                </p>
                <PublicTrick
                  plays={previous.plays}
                  roster={room.players}
                  ownSeat={0}
                  winnerSeat={previous.winnerSeat}
                  effects={[]}
                />
              </details>
            )}
          </div>
        ),
      }}
    />
  );
}
