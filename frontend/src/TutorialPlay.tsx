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
    text: "沒有王牌時，首家花色最大者贏這一墩，並先出下一墩。再試著跟一張合法的牌。",
  },
  {
    title: "缺花時可以切王",
    text: "沒有首家花色時，可以出王牌，也可以墊其他花色。這次試試黑桃。",
  },
  {
    title: "你已掌握基本玩法",
    text: "教學到這裡就完成了！回大廳選一般場，再慢慢體驗，不必打完這一局。",
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
        ? 1
        : game.lesson === "TRUMP_WHEN_VOID"
          ? 2
          : 3;
  const [review, setReview] = useState({
    revision: game.revision,
    step: latest === 0 ? -1 : latest,
  });
  const step = review.revision === game.revision ? review.step : latest;
  const practicing = step === latest && latest < 3;
  const content =
    step === -1
      ? {
          title: "王牌與目標",
          text: "這個示範由隊友得標 1♠：黑桃是王牌，同隊目標剛好 7 墩。下方會顯示目前進度。",
        }
      : pages[step];
  const go = (next: number) =>
    setReview({ revision: game.revision, step: next });
  return (
    <GamePanel
      room={tutorialRoom(game)}
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
        exitLabel: latest === 3 ? "回到大廳" : "離開教學",
        guide: (
          <TutorialSpotlight
            target={
              step === -1 || step === 3 ? ".contract-badge" : ".own-hand-panel"
            }
            stepKey={`${game.revision}:${step}`}
            title={content.title}
            progress={step === -1 ? "4 / 8" : `${step + 5} / 8`}
            busy={busy}
            allowInteraction={practicing}
            onExit={onExit}
            onPrevious={step > -1 ? () => go(step - 1) : onReviewIntroduction}
            onNext={
              step < latest
                ? () => go(step + 1)
                : latest === 3
                  ? onExit
                  : undefined
            }
            nextLabel={step === 3 ? "完成，回大廳" : "下一步"}
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
