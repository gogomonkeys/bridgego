import { useState } from "react";
import type { Call, Strain } from "./model";
import { strainNames, symbols } from "./game-view";
import GamePanel from "./GamePanel";
import TutorialSpotlight from "./TutorialSpotlight";
import { tutorialRoom } from "./tutorial-room";
export const tutorialStrains: Strain[] = [
  "CLUB",
  "DIAMOND",
  "HEART",
  "SPADE",
  "NO_TRUMP",
];
export function practiceBidDescription(level: number, strain: Strain): string {
  if (
    !Number.isInteger(level) ||
    level < 1 ||
    level > 7 ||
    !tutorialStrains.includes(strain)
  )
    throw new Error("Invalid practice bid");
  return `你練習叫了 ${level}${symbols[strain]}：若成為定約，同隊目標 ${6 + level} 墩，${strain === "NO_TRUMP" ? "無王" : `${strainNames[strain]}為王`}。`;
}
/** Local practice using exactly the production table and bidding controls. No room commands. */
export default function TutorialBidding({
  busy,
  onBegin,
  onBack,
  initialStep = 0,
}: {
  busy: boolean;
  onBegin: () => void;
  onBack: () => void;
  initialStep?: number;
}) {
  const [step, setStep] = useState(initialStep);
  const [call, setCall] = useState<Call | null>(null);
  const pages = [
    [
      "認識牌桌",
      "你在下方，對面是隊友，左右是對手。這裡使用與一般遊戲相同的牌桌。",
    ],
    ["認識叫牌", "階數加 6 是目標墩數，花色決定王牌；無王代表沒有王牌。"],
    [
      "試著叫牌",
      "在下方選階數、花色並確認，也可以 Pass。不計時，不會自動操作。",
    ],
  ];
  return (
    <GamePanel
      room={tutorialRoom(null, call)}
      online
      busy={busy}
      pendingAction={null}
      snapshotReceivedAt={0}
      tutorial={{
        actionLocked: step !== 2,
        onPlay: async () => {},
        onExit: onBack,
        exitLabel: "返回教學選擇",
        onBid: (next) => {
          if (!busy && step === 2) setCall(next);
        },
        guide: (
          <TutorialSpotlight
            target={
              step === 0
                ? ".table-player.position-bottom"
                : ".phase-main-controls"
            }
            stepKey={`bid:${step}:${!!call}`}
            title={pages[step][0]}
            progress={`${step + 1} / 9`}
            busy={busy}
            allowInteraction={step === 2}
            onExit={onBack}
            onPrevious={step > 0 ? () => setStep(step - 1) : undefined}
            onNext={step < 2 ? () => setStep(step + 1) : onBegin}
            nextLabel={step === 2 ? "繼續出牌示範" : "下一步"}
          >
            <p>{pages[step][1]}</p>
            {call && step === 2 && (
              <p role="status">
                {call.type === "PASS"
                  ? "Pass 代表這次不加叫。"
                  : practiceBidDescription(call.level!, call.strain as Strain)}
              </p>
            )}
            {step === 2 && (
              <small>
                可自由試叫或直接繼續。下一段固定示範 1♠，與剛才的選擇無關。
              </small>
            )}
          </TutorialSpotlight>
        ),
      }}
    />
  );
}
