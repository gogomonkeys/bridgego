import { describe, expect, it } from "vitest";
import { roomTitle, shortRoomId } from "./room-display";
describe("room display labels", () => {
  it("uses the current host name and never invents one", () => {
    expect(roomTitle("小白")).toBe("小白的牌桌");
    expect(roomTitle("新房主")).toBe("新房主的牌桌");
    expect(roomTitle(null)).toBe("遊戲牌桌");
  });
  it("extends colliding prefixes without changing the original IDs", () => {
    const ids = ["abcdefgh-111111", "abcdefgh-222222", "different-3333"];
    expect(shortRoomId(ids[0], ids)).toBe("abcdefgh-1");
    expect(shortRoomId(ids[1], ids)).toBe("abcdefgh-2");
    expect(shortRoomId(ids[2], ids)).toBe("differen");
    expect(ids[0]).toBe("abcdefgh-111111");
  });
  it("handles prefix IDs and repeated same room safely", () => {
    expect(shortRoomId("same", ["same", "same-long"])).toBe("same");
    expect(shortRoomId("same-long", ["same", "same-long"])).toBe("same-lon");
    expect(
      shortRoomId("abcdefgh-long", ["abcdefgh-long", "abcdefgh-long"]),
    ).toBe("abcdefgh");
  });
});
