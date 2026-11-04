import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  DealBreakdown,
  MatchSummary,
  PreviousMatchSummary,
} from "./ResultPanel";
import { completedDeal, gameRoom } from "./test-fixtures";
import type { DealResult, MatchArchive } from "./model";

const renderDeal = (result: DealResult) =>
  renderToStaticMarkup(<DealBreakdown result={result} />);
const teamCard = (html: string, team: "NS" | "EW") =>
  html.split(`class="score-card team-${team}"`)[1].split("</section>")[0];

describe("authoritative scoring displays", () => {
  it("shows level-two target, exact bonus and independent privilege", () => {
    const html = renderDeal(completedDeal());
    const ns = teamCard(html, "NS");
    expect(html).toContain("目標 8 墩");
    expect(ns).toContain("<b>5</b> 分");
    expect(ns).toContain("<dt>定約基本分</dt><dd>2</dd>");
    expect(ns).toContain("<dt>剛好達標加分</dt><dd>2</dd>");
    expect(ns).toContain("<dt>特權調整 ✦</dt><dd>1</dd>");
    expect(teamCard(html, "EW")).toContain("<dt>防守獎勵</dt><dd>0</dd>");
  });
  it("shows no exact bonus for overtricks and no defender reward on success", () => {
    const result = completedDeal();
    result.tricksByTeam = { NS: 9, EW: 4 };
    result.scores.NS = { ...result.scores.NS, exactTargetBonus: 0, total: 3 };
    result.privilegeUsed.EW = true;
    result.scores.EW = {
      ...result.scores.EW,
      privilegeUsed: true,
      privilegeAdjustment: -1,
      total: -1,
    };
    const html = renderDeal(result);
    expect(teamCard(html, "NS")).toContain("<b>3</b> 分");
    expect(teamCard(html, "NS")).toContain("<dt>剛好達標加分</dt><dd>0</dd>");
    const ew = teamCard(html, "EW");
    expect(ew).toContain("<dt>防守獎勵</dt><dd>0</dd>");
    expect(ew).toContain("<dt>特權調整 ✦</dt><dd>-1</dd>");
    expect(ew).toContain("<b>-1</b> 分");
  });
  it.each([false, true])(
    "preserves uncapped shortfall and independent defender privilege=%s",
    (defenderPrivilege) => {
      const result = completedDeal();
      result.contract.level = 4;
      result.tricksByTeam = { NS: 0, EW: 13 };
      result.privilegeUsed.EW = defenderPrivilege;
      result.scores.NS = {
        won: false,
        privilegeUsed: true,
        base: 0,
        exactTargetBonus: 0,
        penalty: -10,
        privilegeAdjustment: -1,
        total: -11,
      };
      result.scores.EW = {
        won: true,
        privilegeUsed: defenderPrivilege,
        base: 1,
        exactTargetBonus: 0,
        penalty: 0,
        privilegeAdjustment: defenderPrivilege ? 1 : 0,
        total: defenderPrivilege ? 2 : 1,
      };
      const html = renderDeal(result);
      expect(html).toContain("目標 10 墩");
      expect(teamCard(html, "NS")).toContain("<dt>未達標扣分</dt><dd>-10</dd>");
      expect(teamCard(html, "NS")).toContain("<b>-11</b> 分");
      const ew = teamCard(html, "EW");
      expect(ew).toContain("★ 本局勝");
      expect(ew).toContain("<dt>防守獎勵</dt><dd>1</dd>");
      expect(ew).toContain(`<b>${defenderPrivilege ? 2 : 1}</b> 分`);
    },
  );
  it("labels the defending team correctly when east-west holds the contract", () => {
    const result = completedDeal();
    result.contract.teamId = "EW";
    const html = renderDeal(result);
    expect(teamCard(html, "EW")).toContain("定約基本分");
    expect(teamCard(html, "NS")).toContain("防守獎勵");
  });
  it("preserves legacy stored bonuses and archived and match totals without recalculation", () => {
    const legacy = completedDeal();
    legacy.scores.NS = {
      ...legacy.scores.NS,
      exactTargetBonus: 3,
      privilegeAdjustment: 2,
      total: 7,
    };
    const ns = teamCard(renderDeal(legacy), "NS");
    expect(ns).toContain("<b>7</b> 分");
    expect(ns).toContain("<dt>剛好達標加分</dt><dd>3</dd>");
    expect(ns).toContain("<dt>特權調整 ✦</dt><dd>2</dd>");
    const match: MatchArchive = {
      gameId: "legacy",
      roster: [],
      teamTotals: { NS: 7, EW: 0 },
      completedDealResults: [legacy],
      forfeitEvents: [],
      matchResult: {
        outcome: "SCORED",
        winnerTeamId: "NS",
        reason: "PLANNED_DEALS_COMPLETE",
        failedTeamIds: [],
      },
    };
    const archive = renderToStaticMarkup(
      <PreviousMatchSummary match={match} />,
    );
    expect(archive).toContain("南北 <b>7</b> 分");
    expect(archive).toContain("不以新規則重算");
    const game = gameRoom("MATCH_RESULT").game!;
    Object.assign(game, match, { completedDeals: 1, plannedDeals: 1 });
    const summary = renderToStaticMarkup(<MatchSummary game={game} />);
    expect(summary).toContain("南北 7 分");
    expect(summary).toContain("南北隊獲勝");
  });
});
