package com.bridge.room;

import com.bridge.persistence.SheetsGateway;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded diagnostics only: never log exceptions, messages, identifiers, payloads or credentials. */
final class StatisticsReadDiagnostics {
  enum Stage { ADMISSION, STORAGE, BOUNDARY, PROJECTION }
  enum Reason { BUSY, COOLDOWN, STORAGE_HTTP, STORAGE_INTEGRITY, STORAGE_STATE, INVALID_DATA, UNEXPECTED }
  private record Key(Stage stage, Reason reason, int status) {}
  private static final long INTERVAL = TimeUnit.SECONDS.toNanos(60);
  private static final Set<Integer> SAFE_HTTP_CODES = Set.of(400,401,403,404,408,429,500,502,503,504);
  private final Logger logger;
  private final LongSupplier ticker;
  private final Map<Key,Long> logged = new HashMap<>();
  StatisticsReadDiagnostics() { this(LoggerFactory.getLogger(StatisticsReadDiagnostics.class), System::nanoTime); }
  StatisticsReadDiagnostics(Logger logger, LongSupplier ticker) { this.logger=logger;this.ticker=ticker; }
  void busy() { report(Stage.ADMISSION, Reason.BUSY, 0, 0); }
  void cooldown() { report(Stage.ADMISSION, Reason.COOLDOWN, 429, 0); }
  void failed(Stage stage, RuntimeException failure, long elapsedNanos) {
    Reason reason;
    int status=0;
    if (failure instanceof SheetsGateway.Failure storage) {
      reason=Reason.STORAGE_HTTP;
      status=SAFE_HTTP_CODES.contains(storage.status)?storage.status:0;
    } else if (failure instanceof SheetsGateway.Conflict) reason=Reason.STORAGE_INTEGRITY;
    else if (failure instanceof IllegalArgumentException) reason=Reason.INVALID_DATA;
    else if (failure instanceof IllegalStateException) reason=Reason.STORAGE_STATE;
    else reason=Reason.UNEXPECTED;
    report(stage,reason,status,Math.max(0,TimeUnit.NANOSECONDS.toMillis(elapsedNanos)));
  }
  private synchronized void report(Stage stage,Reason reason,int status,long elapsedMs) {
    var key=new Key(stage,reason,status);long now=ticker.getAsLong();Long previous=logged.get(key);
    if(previous!=null && now-previous<INTERVAL) return;
    logged.put(key,now);
    // Only closed enums, allowlisted HTTP status and monotonic duration reach the logger.
    try {
      logger.warn("statistics_read_unavailable stage={} reason={} storageStatus={} elapsedMs={}",
          stage,reason,status,elapsedMs);
    } catch (RuntimeException unavailable) {
      // Diagnostics are best-effort and must never replace the original safe response.
    }
  }
}
