import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { act, create } from "react-test-renderer";
import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
import TutorialPlay from "./TutorialPlay";
import GamePanel from "./GamePanel";
import HandPanel from "./HandPanel";
import { tutorialRoom } from "./tutorial-room";
import { client } from "./client";
import type { TutorialSnapshot } from "./tutorial";
import type { Phase } from "./model";
const game: TutorialSnapshot = {
  sessionId: "t",
  revision: 2,
  lesson: "TRUMP_WHEN_VOID",
  hand: [{ suit: "SPADE", rank: 2 }],
  legalCards: [{ suit: "SPADE", rank: 2 }],
  currentTrick: [],
  completedTricks: [],
};
beforeEach(() =>
  vi.stubGlobal("window", {
    matchMedia: () => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  }),
);
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});
describe("shared tutorial isolation", () => {
  it("back/next only reviews: no duplicate play, revision, or official room command", async () => {
    const onPlay = vi.fn().mockResolvedValue(undefined),
      exit = vi.fn(),
      official = vi.spyOn(client, "play");
    let tree!: ReturnType<typeof create>;
    act(() => {
      tree = create(
        <TutorialPlay game={game} busy={false} onPlay={onPlay} onExit={exit} />,
      );
    });
    const btn = (n: string) =>
      tree.root.findAllByType("button").find((b) => b.children.join("") === n)!;
    expect(tree.root.findByType(HandPanel).props.enabled).toBe(true);
    act(() => btn("上一步").props.onClick());
    expect(tree.root.findByType(HandPanel).props.enabled).toBe(false);
    expect(btn("離開教學").props.disabled).toBe(false);
    act(() => btn("下一步").props.onClick());
    expect(tree.root.findByType(HandPanel).props.enabled).toBe(true);
    expect(onPlay).not.toHaveBeenCalled();
    expect(game.revision).toBe(2);
    act(() =>
      tree.root.findByProps({ "aria-label": "黑桃 2" }).props.onClick(),
    );
    await act(async () => btn("出這張牌").props.onClick());
    expect(onPlay).toHaveBeenCalledOnce();
    expect(official).not.toHaveBeenCalled();
    act(() => btn("上一步").props.onClick());
    act(() => btn("下一步").props.onClick());
    expect(onPlay).toHaveBeenCalledOnce();
    act(() => tree.unmount());
  });
  it("ignores even an accidental timed snapshot and never auto plays or passes", async () => {
    vi.useFakeTimers();
    const room = tutorialRoom(game);
    room.game!.operationTimer = {
      kind: "PLAY_TURN",
      turnSeat: 0,
      remainingMs: 1,
      paused: false,
    };
    const onPlay = vi.fn(),
      onBid = vi.fn();
    let tree!: ReturnType<typeof create>;
    act(() => {
      tree = create(
        <GamePanel
          room={room}
          online
          busy={false}
          pendingAction={null}
          snapshotReceivedAt={0}
          tutorial={{ guide: null, onPlay, onBid, onExit: vi.fn() }}
        />,
      );
    });
    expect(tree.root.findAllByProps({ "aria-label": "操作倒數" })).toHaveLength(
      0,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120000);
    });
    expect(onPlay).not.toHaveBeenCalled();
    expect(onBid).not.toHaveBeenCalled();
    act(() => tree.unmount());
  });
  it.each<Phase>([
    "REDEAL_DECISION",
    "PRIVILEGE_DECISION",
    "DEAL_RESULT",
    "MATCH_RESULT",
    "ABORTED",
  ])("never exposes real room mutations for unexpected %s", (phase) => {
    const room = tutorialRoom(game);
    room.phase = phase;
    const html = renderToStaticMarkup(
      <GamePanel
        room={room}
        online
        busy={false}
        pendingAction={null}
        snapshotReceivedAt={0}
        tutorial={{
          guide: null,
          onPlay: async () => {},
          onBid: () => {},
          onExit: () => {},
        }}
      />,
    );
    for (const label of [
      "重新同步",
      "使用特權",
      "確認，準備下一局",
      "再玩一場 · 回等候室",
      "確認退出",
    ])
      expect(html).not.toContain(label);
  });
  it("adapter separates fixed contract, personal trick wins, team wins, and unlimited timers", () => {
    const room = tutorialRoom({
      ...game,
      completedTricks: [
        { number: 1, leaderSeat: 1, winnerSeat: 1, plays: [] },
        { number: 2, leaderSeat: 1, winnerSeat: 2, plays: [] },
      ],
    });
    expect(room.game!.operationTimer).toBeNull();
    expect(room.game!.play!.tricksByTeam).toEqual({ NS: 1, EW: 1 });
    expect(room.game!.auction.result?.holderSeat).toBe(2);
    const practice = tutorialRoom(null, {
      type: "BID",
      level: 7,
      strain: "NO_TRUMP",
    });
    expect(practice.game!.auction.result).toBeNull();
    expect(practice.you!.legalCalls).toHaveLength(36);
  });
  it("keeps the production grid with portal explanations", () => {
    const css = readFileSync(new URL("./style.css", import.meta.url), "utf8");
    expect(css).toContain(".match-shell .tutorial-workspace");
    expect(css).not.toContain(".match-shell .tutorial-workspace > *");
    expect(css).toContain("height: auto");
  });
});

it("final tutorial has one completion exit and keeps previous explanation navigable", () => {
  let tree!: ReturnType<typeof create>;
  act(() => {
    tree = create(
      <TutorialPlay
        game={{ ...game, lesson: "FREE_PLAY" }}
        busy={false}
        onPlay={vi.fn()}
        onExit={vi.fn()}
      />,
    );
  });
  const buttons = tree.root.findAllByType("button");
  expect(
    buttons.filter((b) => b.children.join("") === "完成，回大廳"),
  ).toHaveLength(1);
  expect(
    buttons.filter((b) =>
      ["回到大廳", "離開教學"].includes(b.children.join("")),
    ),
  ).toHaveLength(0);
  expect(tree.root.findByType(HandPanel).props.enabled).toBe(false);
  act(() =>
    buttons.find((b) => b.children.join("") === "上一步")!.props.onClick(),
  );
  expect(
    tree.root
      .findAllByType("button")
      .some((b) => b.children.join("") === "離開教學"),
  ).toBe(true);
  act(() => tree.unmount());
});

it("separates public winner explanation from the second interactive play without rewinding the session", () => {
  const first = {
    number: 1,
    leaderSeat: 1,
    winnerSeat: 3,
    cardsVisible: true,
    plays: [
      {
        seat: 1,
        card: { suit: "CLUB" as const, rank: 2 },
        source: "MANUAL" as const,
      },
    ],
  };
  const snapshot: TutorialSnapshot = {
    ...game,
    lesson: "WIN_TRICK",
    revision: 1,
    completedTricks: [first],
  };
  const play = vi.fn();
  let tree!: ReturnType<typeof create>;
  act(() => {
    tree = create(
      <TutorialPlay
        game={snapshot}
        busy={false}
        onPlay={play}
        onExit={vi.fn()}
      />,
    );
  });
  const panel = () => tree.root.findByType(GamePanel);
  expect(panel().props.room.phase).toBe("TRICK_RESULT");
  expect(panel().props.room.game.play.currentTrick).toEqual(first.plays);
  expect(panel().props.tutorial.actionLocked).toBe(true);
  expect(JSON.stringify(tree.toJSON())).toContain("6 / 9");
  const button = (name: string) =>
    tree.root
      .findAllByType("button")
      .find((b) => b.children.join("") === name)!;
  act(() => button("下一步").props.onClick());
  expect(panel().props.room.phase).toBe("PLAYING");
  expect(panel().props.tutorial.actionLocked).toBe(false);
  expect(JSON.stringify(tree.toJSON())).toContain("7 / 9");
  act(() => button("上一步").props.onClick());
  act(() => button("下一步").props.onClick());
  expect(play).not.toHaveBeenCalled();
  expect(snapshot.revision).toBe(1);
  expect(snapshot.currentTrick).toEqual(game.currentTrick);
  act(() => tree.unmount());
});
