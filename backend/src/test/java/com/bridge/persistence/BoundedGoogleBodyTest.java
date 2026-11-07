package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BoundedGoogleBodyTest {
 @Test void bodyLimitAndNormalRead(){var b=new BoundedGoogleBody();assertArrayEquals(new byte[]{1,2},b.read(new ByteArrayInputStream(new byte[]{1,2}),2,1000));assertThrows(IllegalStateException.class,()->b.read(new ByteArrayInputStream(new byte[3]),2,1000));}
 @Test void stalledBodyTimesOutAndBlocksOverlapUntilWorkerActuallyEnds()throws Exception{
  var b=new BoundedGoogleBody();var release=new CountDownLatch(1);var started=new CountDownLatch(1);
  InputStream stubborn=new InputStream(){public int read(){started.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}return -1;}public void close(){}};
  long before=System.nanoTime();assertThrows(IllegalStateException.class,()->b.read(stubborn,100,50));
  assertTrue(started.await(1,TimeUnit.SECONDS));assertTrue(System.nanoTime()-before<TimeUnit.SECONDS.toNanos(2));
  assertThrows(IllegalStateException.class,()->b.read(new ByteArrayInputStream(new byte[0]),100,50));release.countDown();
 }
 @Test void exceptionDoesNotEchoPrivateResponse(){var b=new BoundedGoogleBody();var failure=assertThrows(IllegalStateException.class,()->b.read(new InputStream(){public int read()throws IOException{throw new IOException("SECRET-TOKEN");}},20,1000));assertFalse(failure.toString().contains("SECRET"));assertNull(failure.getCause());}
 @Test void interruptReturnsAndPreservesInterrupt(){var b=new BoundedGoogleBody();Thread.currentThread().interrupt();try{assertThrows(IllegalStateException.class,()->b.read(new ByteArrayInputStream(new byte[0]),10,1000));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}}
}
