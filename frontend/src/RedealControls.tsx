import type { Game } from "./model";
import { client } from "./client";
import IconTip from "./IconTip";

export default function RedealControls({
  decision,
  locked,
}: {
  decision: Game["redeal"];
  locked: boolean;
}) {
  return (
    <section
      className="phase-main-controls redeal-actions"
      aria-label="私人倒牌選擇"
    >
      <p className="redeal-summary">
        {decision?.yourPoints != null ? (
          <>
            你的牌點：<strong>{decision.yourPoints}</strong> 點 · 僅你可見
          </>
        ) : (
          "正在取得你的倒牌資格"
        )}
      </p>
      <IconTip icon="?" label="倒牌規則說明">
        <span>
          A＝4、K＝3、Q＝2、J＝1，其餘0。少於5點，或剛好5點且沒有A，可選倒牌；4點有A也符合。任一人倒牌即同局重發，不改總分。逾時保留。
        </span>
      </IconTip>
      {decision?.eligible &&
      decision.yourChoice === null &&
      decision.canChoose ? (
        <div className="choice-buttons">
          <button
            className="secondary"
            disabled={locked}
            onClick={() => void client.redeal(false)}
          >
            保留手牌
          </button>
          <button
            className="primary"
            disabled={locked}
            onClick={() => void client.redeal(true)}
          >
            倒牌重發
          </button>
        </div>
      ) : (
        <p className="selection-confirmed" role="status">
          {decision?.yourChoice === true
            ? "已選倒牌，等候重發。"
            : decision?.yourChoice === false
              ? "已保留手牌，等候其他玩家。"
              : decision?.eligible
                ? "目前無法選擇，等候連線與控制權確認。"
                : decision
                  ? "你的手牌不符合倒牌條件，等候確認。"
                  : "等候最新狀態。"}
        </p>
      )}
    </section>
  );
}
