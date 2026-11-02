import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import PwaStatus from "./PwaStatus";
import GamePanel from "./GamePanel";
import { playedRoom } from "./test-fixtures";

afterEach(() => vi.unstubAllGlobals());
describe("PWA notices preserve table space", () => {
  it("uses lobby offline explanation but no extra paragraph above an active table", () => {
    vi.stubGlobal("navigator", { onLine: false });
    expect(renderToStaticMarkup(<PwaStatus />)).toContain("目前離線");
    expect(renderToStaticMarkup(<PwaStatus activeGame />)).toBe("");
  });
  it("active table itself prominently marks disconnected state as non-live", () => {
    const html = renderToStaticMarkup(
      <GamePanel
        room={playedRoom()}
        online={false}
        busy={false}
        pendingAction={null}
        snapshotReceivedAt={performance.now()}
        connectionEpoch={1}
      />,
    );
    expect(html).toContain("非即時畫面");
    expect(html).toContain("多人遊戲需要網路");
    expect(html).not.toContain("● 即時同步");
  });
});
