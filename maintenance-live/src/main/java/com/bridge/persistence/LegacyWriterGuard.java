package com.bridge.persistence;

import java.util.concurrent.atomic.AtomicBoolean;

/** JDK-only, irreversible local process fence. Not a remote authority revocation. */
public final class LegacyWriterGuard {
  private static final String PROPERTY="BRIDGE_SHEETS_WRITER_PROTOCOL";
  private static final AtomicBoolean denied=new AtomicBoolean();
  private LegacyWriterGuard() {}
  /** Bootstrap must call before instantiating credential-backed beans. There is no reset. */
  public static void denyLegacyForProcess(){denied.set(true);}
  private static boolean legacy(String value){return "legacy".equals(value);}
  public static void requireLegacyProcess() {
    if(denied.get() || !legacy(System.getenv().getOrDefault(PROPERTY,"legacy"))
        || !legacy(System.getProperty(PROPERTY,"legacy")))
      throw new IllegalStateException("Legacy writer is unavailable in guarded mode");
  }
}
