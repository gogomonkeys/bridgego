import { describe, expect, it } from "vitest";
import { renderToStaticMarkup as render } from "react-dom/server";
import TableSeats from "./TableSeats";
import GamePanel from "./GamePanel";
import { MatchSummary, TrickRecord } from "./ResultPanel";
import { AuctionRecords } from "./RecordsPanel";
import { playedRoom } from "./test-fixtures";

describe("human control and computer takeover presentation", () => {
  it("keeps original human name and shows takeover without false offline styling", () => {
    const room = playedRoom();
    room.players[1].controlMode = "BOT_TAKEOVER";
    room.players[1].connected = false;
    const html = render(<TableSeats room={room} turnSeat={1} />);
    expect(html).toContain("電腦代打");
    expect(html).toContain(room.players[1].displayName);
    expect(html).not.toContain("is-offline");
    expect(html).not.toContain("電腦玩家");
  });
  it("distinguishes grace, reclaimed human and permanent replacement", () => {
    const room = playedRoom();
    room.players[1].controlMode = "RECONNECTING";
    room.players[1].connected = false;
    expect(render(<TableSeats room={room} turnSeat={1} />)).toContain("重連中");
    room.players[1].controlMode = "HUMAN";
    room.players[1].connected = true;
    expect(render(<TableSeats room={room} turnSeat={1} />)).not.toContain(
      "電腦代打",
    );
    room.players[1].playerType = "BOT";
    room.players[1].controlMode = "BOT";
    room.players[1].displayName = "代補電腦";
    const html = render(<TableSeats room={room} turnSeat={null} />);
    expect(html).toContain("代補電腦");
    expect(html).toContain("電腦玩家");
  });
  it("shows common all-human grace and no team-forfeit promise", () => {
    const room = playedRoom();
    room.game!.paused = true;
    room.allHumansReconnectDeadline = Date.now() + 10000;
    const html = render(
      <GamePanel
        room={room}
        online
        busy={false}
        pendingAction={null}
        snapshotReceivedAt={performance.now()}
      />,
    );
    expect(html).toContain("等候任一真人回來");
    expect(html).toContain("逾時由電腦代打");
    expect(html).not.toContain("逾時依規則判負");
  });
  it.each(["NO_HUMANS_CONNECTED", "NO_HUMANS_REMAIN"] as const)(
    "explains %s as no contest",
    (reason) => {
      const game = playedRoom().game!;
      game.matchResult = {
        outcome: "NO_CONTEST",
        winnerTeamId: null,
        reason,
        failedTeamIds: [],
      };
      const html = render(<MatchSummary game={game} />);
      expect(html).toContain("本場無勝負");
      expect(html).not.toContain("判負");
      expect(html).toContain(
        reason === "NO_HUMANS_CONNECTED"
          ? "所有真人皆未在 10 秒內恢復連線"
          : "所有真人皆已離開",
      );
    },
  );
  it("labels takeover records distinctly without exposing hidden plays", () => {
    const game = playedRoom().game!;
    const plays = [
      {
        seat: 1,
        source: "BOT_TAKEOVER" as const,
        card: { suit: "SPADE" as const, rank: 14 },
      },
    ];
    expect(
      render(
        <TrickRecord
          trick={{
            number: 1,
            leaderSeat: 1,
            winnerSeat: 1,
            plays,
            cardsVisible: true,
          }}
        />,
      ),
    ).toContain("電腦代打出牌");
    expect(
      render(
        <TrickRecord
          trick={{
            number: 1,
            leaderSeat: 1,
            winnerSeat: 1,
            plays,
            cardsVisible: false,
          }}
        />,
      ),
    ).not.toContain("電腦代打出牌");
    const rounds = [
      {
        dealId: game.dealId,
        dealIndex: 1,
        redealCount: 0,
        dealerSeat: 0,
        calls: [
          {
            seat: 1,
            source: "BOT_TAKEOVER" as const,
            type: "PASS" as const,
            level: null,
            strain: null,
          },
        ],
        result: null,
        status: "IN_PROGRESS" as const,
      },
    ];
    expect(
      render(<AuctionRecords rounds={rounds} roster={game.roster} />),
    ).toContain("電腦代打叫牌");
  });
});
