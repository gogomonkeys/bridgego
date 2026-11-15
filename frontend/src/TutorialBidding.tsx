import { useState } from "react";
import type { Call, Strain } from "./model";
import { strainNames, symbols } from "./game-view";
import GamePanel from "./GamePanel";
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
}: {
  busy: boolean;
  onBegin: () => void;
  onBack: () => void;
}) {
  const [step, setStep] = useState(0);
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
          <aside className="tutorial-guide" aria-label="叫牌練習">
            <strong>{pages[step][0]}</strong>
            <p>{pages[step][1]}</p>
            {call && (
              <>
                <p role="status">
                  {call.type === "PASS"
                    ? "這次不加叫，之後輪到你仍可再叫。"
                    : practiceBidDescription(
                        call.level!,
                        call.strain as Strain,
                      )}
                </p>
                <p>接下來切換到電腦 2 定約 1♠ 的出牌教學，與剛才的選擇無關。</p>
              </>
            )}
            <div className="tutorial-actions">
              <button
                disabled={busy || step === 0}
                onClick={() => setStep((s) => Math.max(0, s - 1))}
              >
                上一步
              </button>
              <button
                disabled={busy || step === 2}
                onClick={() => setStep((s) => Math.min(2, s + 1))}
              >
                下一步
              </button>
              <button disabled={busy} onClick={onBegin}>
                {call ? "開始出牌教學" : "略過叫牌練習並開始出牌"}
              </button>
            </div>
            {!call && (
              <p className="fine">出牌練習使用預設 1♠，不沿用這次叫牌選擇。</p>
            )}
          </aside>
        ),
      }}
    />
  );
}
