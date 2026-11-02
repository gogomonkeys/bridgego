/** Local presentation only: never changes authoritative game timers or actions. */
export const INFORMATION_NOTICE_MS = 5_000;

export function createTransientNotice(initialKey: string | null) {
  let visibleKey = initialKey;
  let deadline: number | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const seen = new Set<string>();
  const listeners = new Set<() => void>();
  const stop = () => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  };
  const notify = () => listeners.forEach((listener) => listener());
  const activate = (key: string | null) => {
    stop();
    if (key !== visibleKey) {
      visibleKey = key && !seen.has(key) ? key : null;
      deadline = null;
    }
    if (visibleKey === null) {
      notify();
      return;
    }
    seen.add(visibleKey);
    deadline ??= Date.now() + INFORMATION_NOTICE_MS;
    const remaining = deadline - Date.now();
    if (remaining <= 0) {
      visibleKey = null;
      deadline = null;
      notify();
      return;
    }
    timer = setTimeout(() => {
      timer = null;
      visibleKey = null;
      deadline = null;
      notify();
    }, remaining);
    notify();
  };
  return {
    activate,
    // Effect cleanup cancels pending callbacks. A StrictMode re-setup retains
    // the original deadline instead of starting another five-second window.
    stop,
    getSnapshot: () => visibleKey,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
