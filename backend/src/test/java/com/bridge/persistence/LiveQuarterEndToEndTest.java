package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;

/** Real CLI/service/parser/JWT/HTTP request code, generated test key and fake Apache client only. */
class LiveQuarterEndToEndTest {
  static LiveQuarterCliTest.Scenario scenario() {
    var s = new LiveQuarterCliTest.Scenario() {
      @Override public byte[] credentials(String path) {
        events.add("KEY"); keyReads++; suppliedKey=LiveQuarterHttpTest.generatedCredentials(); return suppliedKey;
      }
    };
    s.mode="EXECUTE"; return s;
  }
  @Test void generatedKeyTokenThenFullReadsAndTwoExactBatchesCompleteWithoutAnyNetworkClient() {
    var s=scenario(); var tokens=new AtomicInteger(); var writes=new AtomicInteger();
    var client=new LiveQuarterHttpTest.FakeClient(request->{
      String uri;
      try {uri=request.getUri().toString();}catch(Exception e){throw new AssertionError(e);}
      if(uri.equals("https://oauth2.googleapis.com/token")){tokens.incrementAndGet();return LiveQuarterHttpTest.response(200,LiveQuarterHttpTest.validToken());}
      assertTrue(uri.startsWith("https://sheets.googleapis.com/v4/spreadsheets/"+s.http.fixture.binding.spreadsheetId()));
      if(request.getMethod().equals("GET"))return LiveQuarterHttpTest.response(200,s.http.readWorkbook());
      assertEquals("POST",request.getMethod());writes.incrementAndGet();
      s.http.replaceWorkbook(EntityUtils.toByteArray(request.getEntity()));
      return LiveQuarterHttpTest.response(200,"{}".getBytes(StandardCharsets.UTF_8));
    });
    assertEquals(0,LiveQuarterCli.run(s,s,(binding,key)->new LiveQuarterHttp(binding,key,client),s.http.fixture.clock));
    assertEquals(1,tokens.get());assertEquals(2,writes.get());assertTrue(s.attempt);assertTrue(s.http.reads>=7);
    s.http.fixture.verify();assertEquals(1,client.closes.get());
    for(byte value:s.suppliedKey)assertEquals(0,value);
  }
  @Test void sheetsUnauthorizedReadNeverWritesOrRefreshesToken() {
    var s=scenario();var tokens=new AtomicInteger();var sheetCalls=new AtomicInteger();
    var client=new LiveQuarterHttpTest.FakeClient(request->{
      try{
        if(request.getUri().getHost().equals("oauth2.googleapis.com")){tokens.incrementAndGet();return LiveQuarterHttpTest.response(200,LiveQuarterHttpTest.validToken());}
      }catch(Exception e){throw new AssertionError(e);}
      sheetCalls.incrementAndGet();assertEquals("GET",request.getMethod());
      return LiveQuarterHttpTest.response(401,"PRIVATE-RESPONSE".getBytes(StandardCharsets.UTF_8));
    });
    assertEquals(3,LiveQuarterCli.run(s,s,(binding,key)->new LiveQuarterHttp(binding,key,client),s.http.fixture.clock));
    assertEquals(1,tokens.get());assertEquals(1,sheetCalls.get());assertEquals(0,s.markerCalls);
    assertFalse(s.output.toString().contains("PRIVATE-RESPONSE"));
  }
  @Test void rateLimitedBatchIsNotReplayedAndAttemptRemainsForOperatorInvestigation() {
    var s=scenario();var tokens=new AtomicInteger();var writes=new AtomicInteger();
    var client=new LiveQuarterHttpTest.FakeClient(request->{
      try{
        if(request.getUri().getHost().equals("oauth2.googleapis.com")){tokens.incrementAndGet();return LiveQuarterHttpTest.response(200,LiveQuarterHttpTest.validToken());}
      }catch(Exception e){throw new AssertionError(e);}
      if(request.getMethod().equals("GET"))return LiveQuarterHttpTest.response(200,s.http.readWorkbook());
      writes.incrementAndGet();return LiveQuarterHttpTest.response(429,"PRIVATE-RESPONSE".getBytes(StandardCharsets.UTF_8));
    });
    assertEquals(3,LiveQuarterCli.run(s,s,(binding,key)->new LiveQuarterHttp(binding,key,client),s.http.fixture.clock));
    assertEquals(1,tokens.get());assertEquals(1,writes.get());assertTrue(s.attempt);assertEquals(0,s.http.posts);
    assertEquals(s.http.fixture.before,s.http.fixture.tables);assertFalse(s.output.toString().contains("PRIVATE-RESPONSE"));
  }
}
