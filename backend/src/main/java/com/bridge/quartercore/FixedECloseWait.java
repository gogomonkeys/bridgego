package com.bridge.quartercore;
import java.util.*;import java.util.function.LongSupplier;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Pre-authority read-only overlap wait. Never retries a write, unknown read, or invalid state. */
final class FixedECloseWait {
 @FunctionalInterface interface Pause {void sleep()throws InterruptedException;}
 static Book read(FixedEBaselineBundle bundle,FixedERestartPreflight.FullReader reader){return read(bundle,reader,System::nanoTime,()->Thread.sleep(15000));}
 static Book read(FixedEBaselineBundle bundle,FixedERestartPreflight.FullReader reader,LongSupplier monotonic,Pause pause){
  Objects.requireNonNull(bundle);long started=monotonic.getAsLong(),last=started;
  for(int attempt=0;attempt<13;attempt++){
   long now=monotonic.getAsLong();if(now<last||now-started>180_000_000_000L)throw new Rejected();last=now;
   Book fresh=reader.read();now=monotonic.getAsLong();if(now<last||now-started>180_000_000_000L)throw new Rejected();last=now;
   String state=NarrowQuarterPoc.root(new ArrayList<>(fresh.metadata.values())).state();
   if(state.equals("CLOSED")){bundle.completedPairs(fresh);return fresh;}
   if(!state.equals("OPEN")||!bundle.isPermittedClaimAwaitingClose(fresh)||attempt==12)throw new Rejected();
   try{pause.sleep();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Rejected();}
  }throw new Rejected();
 }
 private FixedECloseWait(){}
}
