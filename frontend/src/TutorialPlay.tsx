import { useState } from "react";
import GamePanel from "./GamePanel";
import TutorialSpotlight from "./TutorialSpotlight";
import { tutorialRoom } from "./tutorial-room";
import type { TutorialSnapshot } from "./tutorial";
import type { Card } from "./model";
const pages = [
  {
    title: "跟著首家花色",
    text: "首家出梅花，你有梅花就要跟梅花。點選亮框牌；手機選好後再按「出這張牌」。",
  },
  {
    title: "誰贏得這一墩？",
    text: "回看第一墩：沒有王牌時，首家花色最大者獲勝，並先出下一墩。",
  },
  {
    title: "再跟一張牌",
    text: "回到目前牌局。有首家花色就要跟花，請再選一張合法的牌。",
  },
  {
    title: "缺花時可以切王",
    text: "沒有首家花色時，可以出王牌，也可以墊其他花色。這次試試黑桃。",
  },
  {
    title: "你已掌握基本玩法",
    text: "看看最近完成的一墩和贏家。贏家下一墩先出。教學完成，不必打完整局，也不計戰績。",
  },
];
export default function TutorialPlay({
  game,
  busy,
  onPlay,
  onExit,
  onReviewIntroduction,
}: {
  game: TutorialSnapshot;
  busy: boolean;
  onPlay: (card: Card) => Promise<unknown>;
  onExit: () => void;
  onReviewIntroduction?: () => void;
}) {
  const latest =
    game.lesson === "FOLLOW_SUIT"
      ? 0
      : game.lesson === "WIN_TRICK"
        ? 2
        : game.lesson === "TRUMP_WHEN_VOID"
          ? 3
          : 4;
  const initialStep = latest === 0 ? -1 : latest === 2 ? 1 : latest;
  const [review, setReview] = useState({
    revision: game.revision,
    step: initialStep,
  });
  const step = review.revision === game.revision ? review.step : initialStep;
  const practicing = step === latest && latest < 4;
  const content =
    step === -1
      ? {
          title: "王牌與目標",
          text: "這個示範由隊友得標 1♠。牌桌上方會顯示得標者、王牌與進度：黑桃是王，我方至少要拿 7 墩。",
        }
      : pages[step];
  const go = (next: number) =>
    setReview({ revision: game.revision, step: next });
  const room = tutorialRoom(game);
  // Read-only replay of an already confirmed public trick. It never rewinds
  // the server session/revision or re-enables a past play action.
  const shownTrick =
    step === 1
      ? game.completedTricks[0]
      : step === 4
        ? game.completedTricks.at(-1)
        : undefined;
  if (shownTrick && room.game?.play) {
    room.phase = "TRICK_RESULT";
    room.game.play = {
      ...room.game.play,
      currentTrick: shownTrick.plays,
      completedTricks: [shownTrick],
      trickNumber: shownTrick.number,
      leaderSeat: shownTrick.leaderSeat,
      nextSeat: null,
    };
  }
  return (
    <GamePanel
      room={room}
      online
      busy={busy}
      pendingAction={null}
      snapshotReceivedAt={0}
      tutorial={{
        actionLocked: !practicing,
        onPlay: (card) =>
          practicing && !busy ? onPlay(card) : Promise.resolve(),
        onBid: () => {},
        onExit,
        exitLabel: latest === 4 ? "回到大廳" : "離開教學",
        guide: (
          <TutorialSpotlight
            target={
              step === 4
                ? shownTrick
                  ? ".trick-positions"
                  : ""
                : step === -1
                  ? ".contract-badge"
                  : step === 1
                    ? ".trick-positions"
                    : ".own-hand-panel"
            }
            includeTargetChildren={
              shownTrick
                ? ".played-card, .public-lead-badge, .public-card-corner"
                : undefined
            }
            targetPadding={shownTrick ? 8 : 0}
            stepKey={`${game.revision}:${step}`}
            title={content.title}
            progress={step === -1 ? "4 / 9" : `${step + 5} / 9`}
            chapter={step === -1 ? 4 : step === 4 ? 6 : 5}
            busy={busy}
            complete={step === 4}
            allowInteraction={practicing}
            onExit={onExit}
            onPrevious={step > -1 ? () => go(step - 1) : onReviewIntroduction}
            onNext={
              step < latest
                ? () => go(step + 1)
                : latest === 4
                  ? onExit
                  : undefined
            }
            nextLabel={step === 4 ? "完成，回大廳" : "下一步"}
          >
            <p>{content.text}</p>
            {step < latest && step >= 0 && (
              <small>回顧說明，不會重出牌；按下一步回到目前練習。</small>
            )}
          </TutorialSpotlight>
        ),
      }}
    />
  );
}
