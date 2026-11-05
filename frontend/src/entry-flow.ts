// Entry approval is scoped to the exact guest session. A new session never
// inherits another session's explicit choice to enter the lobby.
export function needsEntry(
  token: string | null,
  enteredToken: string | null,
  hasRoom: boolean,
): boolean {
  return !hasRoom && (!token || token !== enteredToken);
}
export function canCompleteEntry(
  expectedToken: string,
  currentToken: string | undefined,
  hasRoom: boolean,
): boolean {
  return expectedToken === currentToken && !hasRoom;
}

/** Wait for the exact created identity, never whichever identity happens to be
 * current when a delayed login/room refresh finishes. */
export async function prepareEntryChoice(deps: {
  previousToken: string | undefined;
  createSession: () => Promise<{ token: string } | null>;
  current: () => { token: string | undefined; hasRoom: boolean };
  isMounted: () => boolean;
}): Promise<string | null> {
  const expected = deps.previousToken ?? (await deps.createSession())?.token;
  const current = deps.current();
  return expected &&
    deps.isMounted() &&
    canCompleteEntry(expected, current.token, current.hasRoom)
    ? expected
    : null;
}
