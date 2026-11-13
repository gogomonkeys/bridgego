package com.bridge.quartercore;
import java.nio.file.*;
import java.time.*;
/** Synthetic fixture subprocess only; no transport, credentials, or write authority. */
public final class DurableCleanHandoffProbe {
  public static void main(String[] args)throws Exception {
    var b=JournalRestartProbe.load(Path.of(args[0]));
    var result=DurableCleanHandoffAssessment.inspect(JournaledNativeWriterTest.BINDING,b,Clock.fixed(Instant.parse(args[1]),ZoneOffset.UTC));
    if(!result.state().name().equals(args[2])||!result.snapshotToken().equals(args[3]))throw new AssertionError("classification mismatch");
    System.out.println("READ_ONLY_HANDOFF_OK");
  }
}
