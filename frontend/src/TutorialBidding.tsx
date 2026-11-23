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
    [
      "先認識特權",
      "叫牌前，每隊可以決定是否使用特權。同一隊有一人選用就成立；兩隊都決定後，才一起公開各隊結果。",
    ],
    [
      "試著叫牌",
      "在下方選階數、花色並確認，也可以 Pass。階數加 6 是目標墩數；花色決定王牌，無王就沒有王牌。",
    ],
  ];
  const room = tutorialRoom(null, call);
  // Example public team outcomes, for this local explanation only. No votes
  // are collected, and nothing is copied into a live or tutorial session.
  if (step === 1 && room.game) {
    room.game.privilege.usedByTeam = { NS: true, EW: false };
  }
  return (
    <GamePanel
      room={room}
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
                : step === 1
                  ? ".privilege-labelled"
                  : ".phase-main-controls"
            }
            stepKey={`bid:${step}:${!!call}`}
            title={pages[step][0]}
            progress={`${step + 1} / 9`}
            chapter={(step + 1) as 1 | 2 | 3}
            busy={busy}
            allowInteraction={step === 2}
            onExit={onBack}
            onPrevious={step > 0 ? () => setStep(step - 1) : undefined}
            onNext={step < 2 ? () => setStep(step + 1) : onBegin}
            nextLabel={step === 2 ? "繼續出牌示範" : "下一步"}
          >
            <p>{pages[step][1]}</p>
            {step === 1 && (
              <small>
                畫面只示意公開結果，這裡不實際投票。用特權的一隊本局贏加 1
                分，輸扣 1 分。
              </small>
            )}
            {call && step === 2 && (
              <p role="status">
                {call.type === "PASS"
                  ? "Pass 代表這次不加叫。"
                  : practiceBidDescription(call.level!, call.strain as Strain)}
              </p>
            )}
            {step === 2 && (
              <small>
                不計時，可自由試叫或直接繼續。下一段固定示範
                1♠，與剛才的選擇無關。
              </small>
            )}
          </TutorialSpotlight>
        ),
      }}
    />
  );
}
