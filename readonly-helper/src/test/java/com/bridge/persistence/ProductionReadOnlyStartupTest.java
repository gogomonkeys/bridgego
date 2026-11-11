package com.bridge.persistence;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure startup model tests; all reads are fakes and all credential text is synthetic. */
class ProductionReadOnlyStartupTest {
  static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"),ZoneOffset.UTC);
  static Map<String,String> enabled(){return new HashMap<>(Map.of(
      "BRIDGE_READONLY_STARTUP_ENABLED","true","BRIDGE_RUNTIME_MODE","game","BRIDGE_SHEETS_ENABLED","true",
      "BRIDGE_SHEETS_SPREADSHEET_ID","1_AqJ_-jXFNSzUZhsTylnXqTmIbtL-PHA1VDd23tV5p0",
      "BRIDGE_DATASET_ID","f89030e0-6629-43d7-8a96-558f488c9da2","BRIDGE_COLLECTION_MODE","managed",
      "BRIDGE_READONLY_STARTUP_EXPIRES_AT","2026-11-11T12:05:00Z","BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON","SYNTHETIC_SECRET"));}
  static ProductionReadOnlyWorkbook.Report summary(){return ProductionReadOnlyWorkbookTest.inspect(ProductionReadOnlyWorkbookTest.fixture());}
  static ProductionReadOnlyStartup.Reader noRead(){return (s,c)->{throw new AssertionError("No HTTP or credential supplier expected");};}
  static ProductionReadOnlyStartup.Outcome run(Map<String,String> env,ProductionReadOnlyStartup.Reader reader){return ProductionReadOnlyStartup.run(key->{if(key.equals("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON"))throw new AssertionError("No secret access");return env.get(key);},CLOCK,new AtomicBoolean(),reader);}
  @Test void defaultOffTouchesOnlyEnableSetting(){var result=ProductionReadOnlyStartup.run(key->{assertEquals("BRIDGE_READONLY_STARTUP_ENABLED",key);return null;},CLOCK,new AtomicBoolean(),noRead());assertEquals("DISABLED",result.status());assertNull(result.summary());}
  @Test void invalidFlagsAndRequiredBindingNeverReadSecrets(){for(String flag:List.of("TRUE","1"," true ")){var e=enabled();e.put("BRIDGE_READONLY_STARTUP_ENABLED",flag);assertEquals("CONFIG_REJECTED",run(e,noRead()).status());}for(String key:List.of("BRIDGE_SHEETS_ENABLED","BRIDGE_COLLECTION_MODE","BRIDGE_SHEETS_SPREADSHEET_ID","BRIDGE_DATASET_ID")){var e=enabled();e.put(key,"wrong");assertEquals("CONFIG_REJECTED",run(e,noRead()).status());}}
  @Test void nonGameModesDoNotRunPreflight(){for(String mode:List.of("erasure","experiment-bootstrap","unknown")){var e=enabled();e.put("BRIDGE_RUNTIME_MODE",mode);assertEquals("CONFIG_REJECTED",run(e,noRead()).status());}}
  @Test void expiryRequiredAndAtMost15Minutes(){var e=enabled();e.remove("BRIDGE_READONLY_STARTUP_EXPIRES_AT");assertEquals("CONFIG_REJECTED",run(e,noRead()).status());for(String expiry:List.of("2026-11-11T12:00:00Z","2026-11-11T11:59:59Z","2026-11-11T12:15:01Z")){e=enabled();e.put("BRIDGE_READONLY_STARTUP_EXPIRES_AT",expiry);assertEquals("WINDOW_REJECTED",run(e,noRead()).status());}}
  @Test void oneAttemptBeforeSecretReadAndNoRetryAfterFailure(){var env=enabled();var attempted=new AtomicBoolean();AtomicInteger reads=new AtomicInteger(),secrets=new AtomicInteger();Function<String,String> source=key->{if(key.equals("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON"))secrets.incrementAndGet();return env.get(key);};ProductionReadOnlyStartup.Reader reader=(settings,credentials)->{reads.incrementAndGet();byte[] own=credentials.get();assertEquals("SYNTHETIC_SECRET",new String(own,java.nio.charset.StandardCharsets.UTF_8));Arrays.fill(own,(byte)0);throw new IllegalStateException("SYNTHETIC_PRIVATE_ERROR");};assertEquals("READ_REJECTED",ProductionReadOnlyStartup.run(source,CLOCK,attempted,reader).status());assertEquals("ALREADY_ATTEMPTED",ProductionReadOnlyStartup.run(source,CLOCK,attempted,reader).status());assertEquals(1,reads.get());assertEquals(1,secrets.get());}
  @Test void enabledUsesPinnedIdentityAndDoesNotExposeTargetOrCredentials(){var e=enabled();var result=ProductionReadOnlyStartup.run(e::get,CLOCK,new AtomicBoolean(),(s,c)->{assertEquals("bridgego-sheets@bridgego-513805.iam.gserviceaccount.com",s.expectedServiceAccountEmail());assertTrue(s.enabled());assertEquals(e.get("BRIDGE_SHEETS_SPREADSHEET_ID"),s.target().spreadsheetId());return summary();});assertEquals("OBSERVED_STRUCTURE_ONLY",result.status());assertFalse(result.toString().contains("SYNTHETIC_SECRET"));assertFalse(result.summary().businessSemanticsVerified());assertFalse(result.summary().writerExclusionVerified());}
  @Test void aNewStartupMayRepeatReadAndDoesNotPretendGlobalOnce(){AtomicInteger reads=new AtomicInteger();for(int startup=0;startup<2;startup++)assertEquals("OBSERVED_STRUCTURE_ONLY",ProductionReadOnlyStartup.run(enabled()::get,CLOCK,new AtomicBoolean(),(s,c)->{reads.incrementAndGet();return summary();}).status());assertEquals(2,reads.get());}
  @Test void sameStartupConcurrentCallsPermitOnlyOneRead() throws Exception {var attempted=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<ProductionReadOnlyStartup.Outcome> result=new AtomicReference<>();Thread first=new Thread(()->result.set(ProductionReadOnlyStartup.run(enabled()::get,CLOCK,attempted,(s,c)->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException();}return summary();})));first.start();assertTrue(entered.await(2,TimeUnit.SECONDS));try{assertEquals("ALREADY_ATTEMPTED",ProductionReadOnlyStartup.run(enabled()::get,CLOCK,attempted,noRead()).status());}finally{release.countDown();first.join(3000);}assertFalse(first.isAlive());assertEquals("OBSERVED_STRUCTURE_ONLY",result.get().status());}
  @Test void missingCredentialOrNullSummaryFailSafely(){var e=enabled();e.remove("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");assertEquals("READ_REJECTED",ProductionReadOnlyStartup.run(e::get,CLOCK,new AtomicBoolean(),(s,c)->{c.get();throw new AssertionError();}).status());assertEquals("READ_REJECTED",ProductionReadOnlyStartup.run(enabled()::get,CLOCK,new AtomicBoolean(),(s,c)->null).status());}
}
