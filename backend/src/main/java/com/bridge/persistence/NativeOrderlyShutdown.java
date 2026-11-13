package com.bridge.persistence;
import java.time.Duration;
import java.util.function.BooleanSupplier;
/** Explicit bounded shutdown attempt. False is not evidence of a durable handoff. */
public interface NativeOrderlyShutdown {
 boolean orderlyShutdown(Duration budget, BooleanSupplier domainQuiescent);
}
