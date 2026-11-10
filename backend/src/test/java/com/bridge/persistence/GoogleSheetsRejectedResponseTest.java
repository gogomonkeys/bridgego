package com.bridge.persistence;
import java.io.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GoogleSheetsRejectedResponseTest {
 @Test void failingErrorBodyIsNeverReadAndCannotMaskStatusOrDelay() throws Exception {
  var read=new AtomicInteger();var closed=new CountDownLatch(1);
  var body=new InputStream(){
   @Override public int read(){read.incrementAndGet();throw new AssertionError("PRIVATE_RESPONSE_BODY");}
   @Override public void close()throws IOException{closed.countDown();throw new IOException("PRIVATE_CLOSE_ERROR");}
  };
  var failure=GoogleSheetsGateway.rejectedResponse(429,"120",body,Instant.EPOCH);
  assertEquals(429,failure.status);assertEquals(120000,failure.retryAfterMillis);assertTrue(closed.await(2,TimeUnit.SECONDS));
  assertEquals(0,read.get());assertNull(failure.getCause());assertFalse(failure.toString().contains("PRIVATE"));
 }
 @Test void blockedCloseDoesNotBlockResponseOrConsumeBody() throws Exception {
  var closing=new CountDownLatch(1);var release=new CountDownLatch(1);var read=new AtomicInteger();
  var body=new InputStream(){
   @Override public int read(){read.incrementAndGet();return 'x';}
   @Override public void close(){closing.countDown();try{release.await();}catch(InterruptedException stop){Thread.currentThread().interrupt();}}
  };
  var pool=Executors.newSingleThreadExecutor();
  try {
   var result=pool.submit(()->GoogleSheetsGateway.rejectedResponse(429,"999999999999999999999999",body,Instant.EPOCH));
   var failure=result.get(2,TimeUnit.SECONDS);assertTrue(closing.await(2,TimeUnit.SECONDS));
   assertEquals(429,failure.status);assertEquals(Long.MAX_VALUE,failure.retryAfterMillis);assertEquals(0,read.get());
  }finally{release.countDown();pool.shutdownNow();}
 }
 @Test void absentBodyAndBadHeaderStillPreserveReceivedStatus(){
  var failure=GoogleSheetsGateway.rejectedResponse(503,"invalid",null,Instant.EPOCH);
  assertEquals(503,failure.status);assertEquals(0,failure.retryAfterMillis);
 }
}
