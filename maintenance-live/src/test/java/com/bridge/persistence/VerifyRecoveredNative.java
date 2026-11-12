package com.bridge.persistence;
import java.nio.file.*;
/** Offline, credential-free check of an operator-saved full native response. */
public final class VerifyRecoveredNative {
  public static void main(String[] args)throws Exception {
    try(var in=Files.newInputStream(Path.of(args[0]))){
      var book=StrictNativeSheetsResource.decodeFull(in,System.nanoTime()+java.time.Duration.ofSeconds(60).toNanos(),MaintenanceRelease.BUNDLED.requireProvisionedBinding().manualBinding());
      MaintenanceRelease.BUNDLED.requireBaseline(book);
      System.out.println("FULL_NATIVE_BASELINE_VERIFIED "+MaintenanceRelease.digest(book));
    }
  }
}
