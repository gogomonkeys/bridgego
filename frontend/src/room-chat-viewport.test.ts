import { describe, expect, it } from "vitest";
import { roomChatViewportStyle } from "./room-chat-viewport";
describe("room chat visible viewport", () => {
  it.each([
    [320, 568],
    [360, 640],
    [393, 852],
    [430, 932],
    [740, 360],
  ])(
    "uses %sx%s fallback when visual viewport unavailable",
    (width, height) => {
      expect(
        roomChatViewportStyle({
          innerWidth: width,
          innerHeight: height,
          visualViewport: null,
        }),
      ).toMatchObject({
        "--room-chat-visible-width": `${width}px`,
        "--room-chat-visible-height": `${height}px`,
        "--room-chat-visible-top": "0px",
      });
    },
  );
  it("includes keyboard resize plus Safari pan offsets", () => {
    const viewport = {
      width: 360,
      height: 280,
      offsetTop: 120,
      offsetLeft: 4,
    } as VisualViewport;
    expect(
      roomChatViewportStyle({
        innerWidth: 393,
        innerHeight: 852,
        visualViewport: viewport,
      }),
    ).toMatchObject({
      "--room-chat-visible-width": "360px",
      "--room-chat-visible-height": "280px",
      "--room-chat-visible-top": "120px",
      "--room-chat-visible-left": "4px",
    });
  });
  it("rejects transient zero/NaN dimensions and negative offsets", () => {
    const viewport = {
      width: 0,
      height: NaN,
      offsetTop: -40,
      offsetLeft: NaN,
    } as VisualViewport;
    expect(
      roomChatViewportStyle({
        innerWidth: 393,
        innerHeight: 852,
        visualViewport: viewport,
      }),
    ).toMatchObject({
      "--room-chat-visible-width": "393px",
      "--room-chat-visible-height": "852px",
      "--room-chat-visible-top": "0px",
      "--room-chat-visible-left": "0px",
    });
  });
});
