// Room transport bridge. No credentials or SDP are persisted or logged.
export type VoiceFrame = Record<string, unknown>;
export interface RoomChatMessage {
  messageId: string;
  name: string;
  text: string;
}
// Memory only: one authenticated waiting-room scope, never browser or server storage.
let chatScope: {
  owner: string;
  roomId: string;
  messages: RoomChatMessage[];
} | null = null;
export function setRoomChatScope(owner: string, roomId: string) {
  if (chatScope?.owner !== owner || chatScope.roomId !== roomId)
    chatScope = { owner, roomId, messages: [] };
}
export function clearRoomChat() {
  chatScope = null;
}
export function getRoomChat(roomId: string): RoomChatMessage[] {
  return chatScope?.roomId === roomId ? [...chatScope.messages] : [];
}
export function rememberRoomChat(roomId: string, message: RoomChatMessage) {
  if (chatScope?.roomId !== roomId) return;
  if (!chatScope.messages.some((m) => m.messageId === message.messageId))
    chatScope.messages = [...chatScope.messages, message].slice(-50);
}
const listeners = new Set<(frame: VoiceFrame) => void>();
let sender: ((frame: VoiceFrame) => boolean) | null = null;
export function bindVoiceTransport(
  next: ((frame: VoiceFrame) => boolean) | null,
) {
  sender = next;
}
export function receiveVoiceFrame(frame: VoiceFrame) {
  // Server terminal events may arrive before a room snapshot, or while the panel is unmounted.
  if (frame.type === "voice-ended" && frame.reason !== "DISCONNECTED")
    clearRoomChat();
  listeners.forEach((listener) => listener(frame));
}
export function endVoiceTransport(reason = "DISCONNECTED") {
  sender = null;
  receiveVoiceFrame({ type: "voice-ended", reason });
}
export function subscribeVoice(listener: (frame: VoiceFrame) => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
export function sendVoice(frame: VoiceFrame): boolean {
  return sender?.(frame) ?? false;
}
