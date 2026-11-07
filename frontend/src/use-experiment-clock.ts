import { useEffect, useState } from "react";
import type { AuthConfig } from "./google-login";
import { experimentExpiry, isSingleTesterExperiment } from "./entry-account";

// Display-only clock. Server admission always remains authoritative.
export function useExperimentClock(config: AuthConfig | null): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const expiry = experimentExpiry(config);
    if (!isSingleTesterExperiment(config) || !Number.isFinite(expiry)) return;
    const tick = () => setNow(Date.now());
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [config]);
  return now;
}
