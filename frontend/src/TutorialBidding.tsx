import { useState } from "react";
import type { Strain } from "./model";
import { strainNames, symbols } from "./game-view";
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
/** A local demonstration, not an auction command or a completed contract. */
export default function TutorialBidding({
  busy,
  onBegin,
  onBack,
}: {
  busy: boolean;
  onBegin: () => void;
  onBack: () => void;
}) {
  const [level, setLevel] = useState(1);
  const [strain, setStrain] = useState<Strain>("SPADE");
  const [description, setDescription] = useState<string | null>(null);
  return (
    <section className="tutorial-guide" aria-label="叫牌練習">
      <h2>先練習叫牌</h2>
      {description === null ? (
        <>
          <p>階數決定目標墩數，花色決定王牌。</p>
          <div className="tutorial-actions">
            <label>
              階數{" "}
              <select
                aria-label="叫牌階數"
                value={level}
                disabled={busy}
                onChange={(e) => setLevel(Number(e.target.value))}
              >
                {[1, 2, 3, 4, 5, 6, 7].map((value) => (
                  <option key={value} value={value}>
                    {value} 階
                  </option>
                ))}
              </select>
            </label>
            <label>
              花色{" "}
              <select
                aria-label="叫牌花色"
                value={strain}
                disabled={busy}
                onChange={(e) => setStrain(e.target.value as Strain)}
              >
                {tutorialStrains.map((value) => (
                  <option key={value} value={value}>
                    {symbols[value]} {strainNames[value]}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className="tutorial-actions">
            <button
              className="primary"
              disabled={busy}
              onClick={() =>
                setDescription(practiceBidDescription(level, strain))
              }
            >
              確認叫牌
            </button>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => setDescription("這次不加叫，之後輪到你仍可再叫。")}
            >
              Pass
            </button>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => setDescription("略過叫牌練習。")}
            >
              略過叫牌練習
            </button>
          </div>
        </>
      ) : (
        <>
          <p role="status">{description}</p>
          <p>接下來切換到電腦 2 定約 1♠ 的出牌教學，與剛才的選擇無關。</p>
          <div className="tutorial-actions">
            <button className="primary" disabled={busy} onClick={onBegin}>
              開始出牌教學
            </button>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => setDescription(null)}
            >
              再練一次叫牌
            </button>
          </div>
        </>
      )}
      <button className="secondary" disabled={busy} onClick={onBack}>
        返回教學選擇
      </button>
    </section>
  );
}
