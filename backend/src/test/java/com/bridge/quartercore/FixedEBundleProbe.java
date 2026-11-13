package com.bridge.quartercore;
import java.nio.file.*;
public final class FixedEBundleProbe {
 static NativeSheetsModel.Book load(String path)throws Exception{try(var in=Files.newInputStream(Path.of(path))){return StrictNativeSheetsResource.decodeFull(in,System.nanoTime()+20_000_000_000L,FixedERestartClock.binding().manualBinding());}}
 public static void main(String[] a)throws Exception{
  var base=load(a[0]);var after=load(a[1]);var bundle=FixedEBaselineBundle.isolatedFixture(base);
  if(bundle.completedPairs(after)!=3)throw new AssertionError("Fresh JVM receipt serialization rejected");
  System.out.println("SYNTHETIC_E_BUNDLE_CHILD_OK");
 }
}
