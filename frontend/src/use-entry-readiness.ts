import { useEffect, useState } from "react";
import { readStatisticsConfig, type StatisticsConfig } from "./statistics";
import { readAuthConfig, type AuthConfig } from "./google-login";
import { EntryReadError, readEntryHealth } from "./public-entry-read";

const BUDGET = 120000;
/** All automatic retries are public GETs. Login, consent and nickname saves are not retried here. */
export function useEntryReadiness() {
  const [statistics, setStatistics] = useState<StatisticsConfig | null>(null);
  const [auth, setAuth] = useState<AuthConfig | null>(null);
  const [ready, setReady] = useState(false);
  const [phase, setPhase] = useState<
    "connecting" | "waiting" | "limited" | "maintenance" | "stopped" | "ready"
  >("connecting");
  const [authFailed, setAuthFailed] = useState(false);
  const [retryAt, setRetryAt] = useState(0);
  const [cycle, setCycle] = useState(0);
  const [clock, setClock] = useState(Date.now());
  useEffect(() => {
    let disposed = false;
    let attempts = 0;
    let flight = false;
    let complete = false;
    const started = Date.now();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let abort: AbortController | undefined;
    let health = false;
    let epoch: string | undefined;
    let stats: StatisticsConfig | null = null;
    let config: AuthConfig | null = null;
    let notBefore = 0;
    const deadline = setTimeout(() => {
      if (disposed || complete) return;
      clearTimeout(timer);
      abort?.abort();
      setPhase("stopped");
    }, BUDGET);
    const hidden = () =>
      typeof document !== "undefined" && document.visibilityState === "hidden";
    setPhase("connecting");
    setReady(false);
    setStatistics(null);
    setAuth(null);
    setAuthFailed(false);
    setRetryAt(0);
    setClock(Date.now());
    const run = async () => {
      if (disposed || complete || hidden() || flight) return;
      if (attempts >= 8 || Date.now() - started >= BUDGET) {
        setPhase("stopped");
        return;
      }
      if (Date.now() < notBefore) {
        timer = setTimeout(
          () => void run(),
          Math.min(notBefore - Date.now(), BUDGET - (Date.now() - started)),
        );
        return;
      }
      flight = true;
      attempts++;
      abort = new AbortController();
      const currentAbort = abort;
      const errors: unknown[] = [];
      try {
        // Recheck on each retry: a previously ready instance may be stopping now.
        health = false;
        const nextEpoch = await readEntryHealth(currentAbort.signal);
        if (disposed || currentAbort.signal.aborted) return;
        if (epoch !== undefined && epoch !== nextEpoch) {
          // Never combine settings from the old process with a replacement process.
          stats = null;
          config = null;
          setStatistics(null);
          setAuth(null);
          setReady(false);
        }
        epoch = nextEpoch;
        health = true;
        await Promise.all([
          !stats
            ? readStatisticsConfig(currentAbort.signal)
                .then((value) => {
                  if (disposed || currentAbort.signal.aborted) return;
                  stats = value;
                  setStatistics(value);
                  setReady(health);
                })
                .catch((error) => {
                  errors.push(error);
                })
            : undefined,
          !config
            ? readAuthConfig(currentAbort.signal)
                .then((value) => {
                  if (disposed || currentAbort.signal.aborted) return;
                  config = value;
                  setAuth(value);
                })
                .catch((error) => {
                  errors.push(error);
                })
            : undefined,
        ]);
      } catch (error) {
        errors.push(error);
      } finally {
        flight = false;
        if (!disposed && currentAbort.signal.aborted && !hidden())
          timer = setTimeout(() => void run(), 0);
      }
      for (const error of errors)
        if (error instanceof EntryReadError)
          notBefore = Math.max(notBefore, error.retryAt);
      if (disposed || currentAbort.signal.aborted || hidden()) return;
      setStatistics(stats);
      setAuth(config);
      setReady(health && Boolean(stats));
      setAuthFailed(health && !config);
      if (health && stats && config) {
        complete = true;
        clearTimeout(deadline);
        setPhase("ready");
        return;
      }
      setRetryAt(notBefore);
      setClock(Date.now());
      setPhase(
        errors.some(
          (error) => error instanceof EntryReadError && error.stopping,
        )
          ? "maintenance"
          : errors.some(
                (error) =>
                  error instanceof EntryReadError && error.status === 429,
              )
            ? "limited"
            : "waiting",
      );
      const delay = Math.max(
        Math.min(2000 * 2 ** (attempts - 1), 15000),
        notBefore - Date.now(),
      );
      if (attempts >= 8 || Date.now() + delay - started >= BUDGET) {
        setPhase("stopped");
        return;
      }
      timer = setTimeout(() => void run(), delay);
    };
    const visibility = () => {
      if (hidden()) {
        clearTimeout(timer);
        abort?.abort();
      } else {
        clearTimeout(timer);
        timer = setTimeout(() => void run(), 0);
      }
    };
    void run();
    if (typeof document !== "undefined")
      document.addEventListener("visibilitychange", visibility);
    return () => {
      disposed = true;
      clearTimeout(deadline);
      clearTimeout(timer);
      abort?.abort();
      if (typeof document !== "undefined")
        document.removeEventListener("visibilitychange", visibility);
    };
  }, [cycle]);
  useEffect(() => {
    if (retryAt <= Date.now()) return;
    const timer = setInterval(() => {
      const now = Date.now();
      setClock(now);
      if (now >= retryAt) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [retryAt]);
  const retryAllowed = phase === "stopped" && clock >= retryAt;
  return {
    statistics,
    auth,
    ready,
    phase,
    authFailed,
    retryAllowed,
    retry: () => {
      if (retryAllowed) setCycle((value) => value + 1);
    },
  };
}
