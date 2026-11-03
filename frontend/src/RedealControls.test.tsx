import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import RedealControls from "./RedealControls";
import GamePanel from "./GamePanel";
import OperationClock from "./OperationClock";
import { gameRoom } from "./test-fixtures";
import type { Game } from "./model";
const eligible: NonNullable<Game["redeal"]> = {
  yourPoints: 4,
  eligible: true,
  yourChoice: null,
  canChoose: true,
};
const render = (decision: Game["redeal"] = eligible, locked = false) =>
  renderToStaticMarkup(<RedealControls decision={decision} locked={locked} />);
describe("private redeal choices", () => {
  it("offers both choices and explicitly permits four points with an ace", () => {
    const html = render();
    expect(html).toContain("保留手牌");
    expect(html).toContain("倒牌重發");
    expect(html).toContain("<strong>4</strong>");
    expect(html).toContain("僅你可見");
    expect(html).not.toContain("disabled");
  });
  it("trusts private server eligibility for five points rather than inferring other hands", () => {
    expect(render({ ...eligible, yourPoints: 5 })).toContain("choice-buttons");
    expect(
      render({ ...eligible, yourPoints: 5, eligible: false, canChoose: false }),
    ).not.toContain("choice-buttons");
  });
  it.each([true, false])(
    "does not allow changing submitted choice %s",
    (choice) => {
      const html = render({ ...eligible, yourChoice: choice });
      expect(html).not.toContain("choice-buttons");
      expect(html).toContain(choice ? "已選倒牌" : "已保留手牌");
    },
  );
  it("does not expose actions while control is unavailable or snapshot missing", () => {
    expect(render({ ...eligible, canChoose: false })).not.toContain(
      "choice-buttons",
    );
    expect(
      renderToStaticMarkup(
        <RedealControls decision={undefined} locked={false} />,
      ),
    ).not.toContain("choice-buttons");
    expect(render(eligible, true).match(/disabled=""/g)).toHaveLength(2);
  });
  it.each(["offline", "busy", "paused", "pending"])(
    "locks actions when %s",
    (mode) => {
      const room = gameRoom("REDEAL_DECISION");
      room.game!.redeal = eligible;
      room.game!.paused = mode === "paused";
      const html = renderToStaticMarkup(
        <GamePanel
          room={room}
          online={mode !== "offline"}
          busy={mode === "busy"}
          pendingAction={mode === "pending" ? "倒牌選擇" : null}
          snapshotReceivedAt={performance.now()}
        />,
      );
      expect(html).toContain('aria-label="私人倒牌選擇"');
      expect(html).toMatch(/disabled="">保留手牌/);
      expect(html).toMatch(/disabled="">倒牌重發/);
      expect(html).not.toContain('aria-label="選擇叫牌"');
      expect(html).not.toContain('aria-label="特權選擇說明"');
    },
  );
  it("labels the shared deadline and waits for server at zero", () => {
    const timer = {
      kind: "REDEAL_DECISION" as const,
      turnSeat: null,
      remainingMs: 20000,
      paused: false,
    };
    expect(
      renderToStaticMarkup(<OperationClock timer={timer} seconds={20} />),
    ).toContain("未選者逾時保留手牌");
    expect(
      renderToStaticMarkup(<OperationClock timer={timer} seconds={0} />),
    ).toContain("此頁不會自行送出操作");
  });
  it("shows bounded redeal count and reason without listing discarded hands", () => {
    const room = gameRoom("REDEAL_DECISION");
    room.game!.redeal = eligible;
    room.game!.redealCount = 25;
    room.game!.lastRedealReason = "HAND_REDEAL";
    const html = renderToStaticMarkup(
      <GamePanel
        room={room}
        online
        busy={false}
        pendingAction={null}
        snapshotReceivedAt={performance.now()}
      />,
    );
    expect(html).toContain("已重發 25 次");
    expect(html).toContain("最近倒牌");
  });
});
