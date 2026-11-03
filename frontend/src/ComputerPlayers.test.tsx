import { describe, expect, it } from "vitest";
import { renderToStaticMarkup as render } from "react-dom/server";
import ComputerPlayers from "./ComputerPlayers";
import TableSeats from "./TableSeats";
import { AuctionRecords } from "./RecordsPanel";
import { TrickRecord } from "./ResultPanel";
import { playedRoom } from "./test-fixtures";
const waiting = () => {
  const room = playedRoom();
  room.phase = "WAITING";
  room.game = null;
  room.players = room.players.filter((p) => p.playerId === room.you!.playerId);
  room.hostPlayerId = room.you!.playerId;
  return room;
};
const noop = () => {};
describe("computer player lobby controls", () => {
  it.each([0, 1, 2, 3])("offers three other seats for host at %i", (seat) => {
    const room = waiting();
    room.you!.seat = seat;
    room.players[0].seat = seat;
    const html = render(
      <ComputerPlayers
        room={room}
        playerId={room.hostPlayerId!}
        disabled={false}
        onAdd={noop}
        onRemove={noop}
      />,
    );
    expect(html.match(/加入電腦玩家/g)).toHaveLength(3);
    expect(html).not.toContain("移除電腦");
  });
  it("hides controls for nonhosts and active games", () => {
    const room = waiting();
    expect(
      render(
        <ComputerPlayers
          room={room}
          playerId="outsider"
          disabled={false}
          onAdd={noop}
          onRemove={noop}
        />,
      ),
    ).toBe("");
    room.phase = "BIDDING";
    expect(
      render(
        <ComputerPlayers
          room={room}
          playerId={room.hostPlayerId!}
          disabled={false}
          onAdd={noop}
          onRemove={noop}
        />,
      ),
    ).toBe("");
  });
  it("distinguishes bot removal, human occupancy, empty seat and pending", () => {
    const room = waiting(),
      own = room.players[0],
      seats = [0, 1, 2, 3].filter((s) => s !== own.seat);
    room.players.push({
      ...own,
      playerId: "bot-1",
      displayName: "電腦二",
      seat: seats[0],
      playerType: "BOT",
      ready: true,
    });
    room.players.push({
      ...own,
      playerId: "human-2",
      displayName: "小米",
      seat: seats[1],
      playerType: "HUMAN",
    });
    const html = render(
      <ComputerPlayers
        room={room}
        playerId={room.hostPlayerId!}
        disabled
        onAdd={noop}
        onRemove={noop}
      />,
    );
    expect(html.match(/<button/g)).toHaveLength(2);
    expect(html.match(/disabled=""/g)).toHaveLength(2);
    expect(html).toContain("移除電腦");
    expect(html).toContain("真人玩家");
  });
  it("labels computer presence, not a human connection", () => {
    const room = playedRoom();
    room.players[1].playerType = "BOT";
    expect(render(<TableSeats room={room} turnSeat={1} />)).toContain(
      "電腦 · 輪到",
    );
    room.phase = "WAITING";
    expect(render(<TableSeats room={room} turnSeat={null} />)).toContain(
      "電腦 · 已準備",
    );
  });
  it("preserves source in records without exposing hidden faces", () => {
    const room = playedRoom();
    const html = render(
      <AuctionRecords
        roster={room.game!.roster}
        rounds={[
          {
            dealId: "d",
            dealIndex: 1,
            redealCount: 0,
            dealerSeat: 0,
            calls: [
              {
                seat: 1,
                type: "PASS",
                level: null,
                strain: null,
                source: "BOT",
              },
            ],
            result: null,
            status: "IN_PROGRESS",
          },
        ]}
      />,
    );
    expect(html).toContain("電腦叫牌");
    expect(html).not.toContain("逾時自動");
    const trick = {
      number: 1,
      leaderSeat: 1,
      winnerSeat: 1,
      cardsVisible: true,
      plays: [
        {
          seat: 1,
          card: { suit: "HEART" as const, rank: 14 },
          source: "BOT" as const,
        },
      ],
    };
    expect(
      render(<TrickRecord trick={trick} roster={room.game!.roster} />),
    ).toContain("電腦出牌");
    expect(
      render(
        <TrickRecord
          trick={{ ...trick, cardsVisible: false }}
          roster={room.game!.roster}
        />,
      ),
    ).not.toContain("♥ A");
  });
});
