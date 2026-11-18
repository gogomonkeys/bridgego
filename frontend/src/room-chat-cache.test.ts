import { beforeEach, expect, it } from "vitest";
import {
  clearRoomChat,
  setRoomChatScope,
  rememberRoomChat,
  getRoomChat,
  endVoiceTransport,
} from "./voice-wire";
beforeEach(clearRoomChat);
const message = { messageId: "1", name: "Friend", text: "Hello" };
it("keeps only one verified owner and room, never resurrects old room history", () => {
  setRoomChatScope("owner1", "r1");
  rememberRoomChat("r1", message);
  setRoomChatScope("owner1", "r1");
  expect(getRoomChat("r1")).toEqual([message]);
  setRoomChatScope("owner2", "r1");
  expect(getRoomChat("r1")).toEqual([]);
  rememberRoomChat("r1", message);
  setRoomChatScope("owner2", "r2");
  expect(getRoomChat("r1")).toEqual([]);
  setRoomChatScope("owner2", "r1");
  expect(getRoomChat("r1")).toEqual([]);
});
it("retains only across transport loss; terminal states erase", () => {
  for (const reason of ["SESSION_ENDED", "GAME_STARTED"]) {
    setRoomChatScope("owner", "r1");
    rememberRoomChat("r1", message);
    endVoiceTransport();
    expect(getRoomChat("r1")).toEqual([message]);
    endVoiceTransport(reason);
    expect(getRoomChat("r1")).toEqual([]);
  }
});
it("bounds and deduplicates received messages and refuses unverified scope", () => {
  rememberRoomChat("r1", message);
  expect(getRoomChat("r1")).toEqual([]);
  setRoomChatScope("owner", "r1");
  for (let i = 0; i < 60; i++)
    rememberRoomChat("r1", { ...message, messageId: String(i) });
  rememberRoomChat("r1", { ...message, messageId: "59" });
  expect(getRoomChat("r1")).toHaveLength(50);
  expect(getRoomChat("r1")[0].messageId).toBe("10");
});
