// Room transport bridge. No credentials or SDP are persisted or logged.
export type VoiceFrame = Record<string, unknown>;
const listeners = new Set<(frame: VoiceFrame) => void>();
let sender: ((frame: VoiceFrame) => boolean) | null = null;
export function bindVoiceTransport(
  next: ((frame: VoiceFrame) => boolean) | null,
) {
  sender = next;
}
export function receiveVoiceFrame(frame: VoiceFrame) {
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
