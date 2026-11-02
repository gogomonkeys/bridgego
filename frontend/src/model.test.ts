import { describe, it, expect } from "vitest";
import { acceptSnapshot, type Room } from "./model";
const room = (revision: number, roomId = "one"): Room => ({
  roomId,
  revision,
  capacity: 4,
  phase: "WAITING",
  allReady: false,
  hostPlayerId: "alice",
  canStart: false,
  canRematch: false,
  lastMatch: null,
  game: null,
  you: null,
  players: [],
});
describe("authoritative room revision guard", () => {
  it("accepts initial snapshot only for subscribed room", () => {
    expect(acceptSnapshot(null, room(1), "one")?.revision).toBe(1);
    expect(acceptSnapshot(null, room(1), "other")).toBeNull();
  });
  it("never overwrites a newer WS snapshot with a stale HTTP response", () => {
    const current = room(7);
    expect(acceptSnapshot(current, room(6), "one")).toBe(current);
  });
  it("accepts reconnect snapshot and rejects a previous room", () => {
    expect(acceptSnapshot(room(7), room(8), "one")?.revision).toBe(8);
    const current = room(1, "two");
    expect(acceptSnapshot(current, room(99), "two")).toBe(current);
  });
});
