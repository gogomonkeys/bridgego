/** Display labels only. Joining and copying always use the complete roomId. */
export function roomTitle(hostDisplayName?: string | null): string {
  return hostDisplayName?.trim()
    ? `${hostDisplayName.trim()}的牌桌`
    : "遊戲牌桌";
}
/** Extend colliding prefixes instead of presenting identical short room labels. */
export function shortRoomId(id: string, ids: readonly string[]): string {
  let length = Math.min(8, id.length);
  while (
    length < id.length &&
    ids.some(
      (other) => other !== id && other.slice(0, length) === id.slice(0, length),
    )
  )
    length++;
  return id.slice(0, length);
}
