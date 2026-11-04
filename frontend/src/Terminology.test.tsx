import { describe, expect, it, vi, afterEach } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
import OperationClock from "./OperationClock";
import HandPanel from "./HandPanel";
import { DealBreakdown, TrickRecord } from "./ResultPanel";
import { CurrentDealRecords, SettlementRecords } from "./RecordsPanel";
import { completedDeal, playedRoom } from "./test-fixtures";

afterEach(() => vi.unstubAllGlobals());

describe("player terminology: match, deal and four-card round", () => {
  it("documents three deals of thirteen four-player rounds without changing the turn timer", () => {
    const readme = readFileSync(
      new URL("../../README.zh-TW.md", import.meta.url),
      "utf8",
    );
    expect(readme).toContain("一場可選 1、3 或 5 局（預設 3 局），一局共有 13 回合");
    expect(readme).toContain("四人各出一張牌才算完成一個回合");
    expect(readme).toContain("每次出牌 20 秒");
    expect(readme).toContain("贏得這四張牌算拿下一墩");
    expect(readme).not.toContain("每回合 20 秒");
  });

  it("names the individual actor, rather than calling their single play a round", () => {
    const room = playedRoom();
    const html = renderToStaticMarkup(
      <OperationClock
        timer={room.game!.operationTimer}
        seconds={20}
        roster={room.game!.roster}
      />,
    );
    expect(html).toContain("輪到alice出牌");
    expect(html).not.toContain("每回合");
  });

  it("keeps the hand timer scoped to each individual play", () => {
    const room = playedRoom();
    const html = renderToStaticMarkup(
      <HandPanel
        cards={room.you!.ownHand}
        legalCards={room.you!.legalCards}
        enabled
        collecting={false}
        playing
      />,
    );
    expect(html).toContain("每次出牌 20 秒");
    expect(html).not.toMatch(/每回合 20 秒|不設夢家/);
  });

  it("uses deal and round terms in settlement and hidden records", () => {
    const room = playedRoom();
    const result = completedDeal();
    const html = renderToStaticMarkup(
      <>
        <DealBreakdown result={result} />
        <SettlementRecords game={room.game!} />
        <CurrentDealRecords game={room.game!} />
        <TrickRecord
          trick={{
            number: 1,
            leaderSeat: 0,
            winnerSeat: 2,
            plays: [],
            cardsVisible: false,
          }}
          roster={room.game!.roster}
        />
      </>,
    );
    expect(html).toContain("第 1 局結算");
    expect(html).toContain("第 1 回合");
    expect(html).toContain("carol贏得本回合");
    expect(html).toContain("本局結算後可回顧");
    expect(html).not.toContain("副");
    expect(html).toContain("拿下 8 墩");
    expect(html).toContain("拿下 5 墩");
    expect(html).not.toContain("8 回合");
  });

  it("does not display the inactive mobile confirmation row", () => {
    vi.stubGlobal("window", { matchMedia: () => ({ matches: true }) });
    const room = playedRoom();
    const html = renderToStaticMarkup(
      <HandPanel
        cards={room.you!.ownHand}
        legalCards={[]}
        enabled={false}
        collecting
        playing
      />,
    );
    expect(html).not.toContain('class="hand-confirm"');
    expect(html).toContain("本回合結算中");
  });
});
