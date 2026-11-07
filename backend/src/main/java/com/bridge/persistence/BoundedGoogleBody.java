package com.bridge.persistence;

import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded response consumption. A timed-out uncooperative reader remains active and blocks overlap. */
final class BoundedGoogleBody {
  private final AtomicBoolean active=new AtomicBoolean();
  byte[] read(InputStream stream,int maximum,long timeoutMillis) {
    if(Thread.currentThread().isInterrupted()||stream==null||maximum<1||timeoutMillis<1||!active.compareAndSet(false,true)) {closeLater(stream);throw stopped();}
    FutureTask<byte[]> task=new FutureTask<>(()->{
      try(stream){byte[] bytes=stream.readNBytes(maximum+1);if(bytes.length>maximum)throw stopped();return bytes;}
      finally{active.set(false);}
    });
    Thread worker=new Thread(task,"bounded-google-response");worker.setDaemon(true);worker.start();
    try{return task.get(timeoutMillis,TimeUnit.MILLISECONDS);}
    catch(InterruptedException stop){task.cancel(true);closeLater(stream);Thread.currentThread().interrupt();throw stopped();}
    catch(Exception failure){task.cancel(true);closeLater(stream);throw stopped();}
  }
  static void closeLater(InputStream stream){if(stream==null)return;Thread closer=new Thread(()->{try{stream.close();}catch(Exception ignored){}},"close-google-response");closer.setDaemon(true);closer.start();}
  private static IllegalStateException stopped(){return new IllegalStateException("Response unavailable or outside limits");}
}
