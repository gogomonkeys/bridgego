/** Public entry probes never carry account tokens or recover/create a session. */
export class EntryReadError extends Error {
  constructor(
    public status: number,
    public retryAt = 0,
    public stopping = false,
  ) {
    super("Entry settings unavailable");
  }
}
export function retryAfterTime(value: string | null, now = Date.now()): number {
  if (!value) return 0;
  const seconds = Number(value);
  const date = /^\d+(\.\d+)?$/.test(value)
    ? now + seconds * 1000
    : Date.parse(value);
  return Number.isFinite(date) ? Math.max(now, date) : 0;
}
export async function publicEntryRead(
  path: string,
  signal?: AbortSignal,
): Promise<unknown> {
  const controller = new AbortController();
  const cancel = () => controller.abort();
  signal?.addEventListener("abort", cancel, { once: true });
  if (signal?.aborted) controller.abort();
  const timer = setTimeout(cancel, 8000);
  try {
    if (controller.signal.aborted)
      throw new DOMException("Request cancelled", "AbortError");
    const response = await fetch(path, {
      method: "GET",
      credentials: "omit",
      cache: "no-store",
      signal: controller.signal,
    });
    if (!response.ok) {
      const errorBody =
        path === "/health/ready"
          ? await response.json().catch(() => null)
          : null;
      throw new EntryReadError(
        response.status,
        retryAfterTime(response.headers?.get("Retry-After") ?? null),
        errorBody?.status === "stopping",
      );
    }
    return await response.json();
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", cancel);
  }
}

export async function readEntryHealth(signal?: AbortSignal): Promise<string> {
  const value = (await publicEntryRead("/health/ready", signal)) as {
    status?: string;
    instanceEpoch?: string;
    version?: string;
  };
  if (
    value.status !== "ready" ||
    typeof value.instanceEpoch !== "string" ||
    typeof value.version !== "string"
  )
    throw new EntryReadError(503);
  return value.instanceEpoch;
}
