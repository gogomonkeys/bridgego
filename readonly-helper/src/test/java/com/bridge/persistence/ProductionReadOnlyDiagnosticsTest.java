package com.bridge.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyHttpTest.*;

/** All bodies, credentials and exceptions are generated synthetic canaries; no real network. */
class ProductionReadOnlyDiagnosticsTest {
  static final String CANARY="PRIVATE_CANARY_must_not_escape";
  static final String EMAIL="synthetic-test@synthetic-project.iam.gserviceaccount.com";
  static byte[] credentials;
  static final ProductionReadOnlyTarget TARGET=ProductionReadOnlyWorkbookTest.TARGET;
  @BeforeAll static void setup(){credentials=generatedCredentials();}
  @AfterAll static void clear(){Arrays.fill(credentials,(byte)0);}
  static String json(ProductionReadOnlyDiagnostics d) throws Exception {String text=new ObjectMapper().writeValueAsString(d.snapshot());assertFalse(text.contains(CANARY));assertFalse(text.contains(EMAIL));return text;}
  static boolean token(org.apache.hc.core5.http.ClassicHttpRequest r){try{return r.getUri().getHost().equals("oauth2.googleapis.com");}catch(Exception e){throw new AssertionError(e);}}
  static void rejected(ProductionReadOnlyDiagnostics d,FakeClient client){try(var http=new ProductionReadOnlyHttp(TARGET,credentials,EMAIL,client).withDiagnostics(d)){assertThrows(IllegalStateException.class,http::readFullResource);}}
  @Test void credentialCanaryIsRejectedWithoutExposingOrAllocatingClient() throws Exception {
    var d=new ProductionReadOnlyDiagnostics();assertThrows(IllegalStateException.class,()->ProductionReadOnlyHttp.open(TARGET,CANARY.getBytes(StandardCharsets.UTF_8),EMAIL,true,d));
    assertEquals(ProductionReadOnlyDiagnostics.Stage.CREDENTIAL_VALIDATION,d.snapshot().stage());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.REJECTED,d.snapshot().error());assertEquals("{\"stage\":\"CREDENTIAL_VALIDATION\",\"error\":\"REJECTED\"}",json(d));
  }
  @Test void knownIoTlsAndSocketErrorsUseFiniteTypesOnly() throws Exception {
    List<java.io.IOException> errors=List.of(new java.io.IOException(CANARY),new javax.net.ssl.SSLException(CANARY),new java.net.SocketTimeoutException(CANARY));
    var expected=List.of(ProductionReadOnlyDiagnostics.ErrorType.IO,ProductionReadOnlyDiagnostics.ErrorType.TLS,ProductionReadOnlyDiagnostics.ErrorType.TIMEOUT);
    for(int i=0;i<errors.size();i++){var d=new ProductionReadOnlyDiagnostics();var error=errors.get(i);rejected(d,new FakeClient(r->{throw error;}));assertEquals(ProductionReadOnlyDiagnostics.Stage.TOKEN_HTTP,d.snapshot().stage());assertEquals(expected.get(i),d.snapshot().error());json(d);}
  }
  @Test void non200TokenResponseNeverPrintsBodyOrNumericCode() throws Exception {
    var d=new ProductionReadOnlyDiagnostics();rejected(d,new FakeClient(r->response(403,CANARY.getBytes(StandardCharsets.UTF_8))));assertEquals(ProductionReadOnlyDiagnostics.Stage.TOKEN_HTTP,d.snapshot().stage());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.HTTP_STATUS,d.snapshot().error());assertFalse(json(d).contains("403"));
  }
  @Test void tokenHeadersBodyAndJsonParsingAreDistinctWithoutDynamicDetails() throws Exception {
    var header=new ProductionReadOnlyDiagnostics();rejected(header,new FakeClient(r->{var reply=response(200,validToken());reply.setHeader("Content-Type",CANARY);return reply;}));assertEquals(ProductionReadOnlyDiagnostics.Stage.TOKEN_HEADERS,header.snapshot().stage());json(header);
    var body=new ProductionReadOnlyDiagnostics();rejected(body,new FakeClient(r->response(200,new java.io.InputStream(){public int read()throws java.io.IOException{throw new java.io.IOException(CANARY);}},-1)));assertEquals(ProductionReadOnlyDiagnostics.Stage.TOKEN_BODY,body.snapshot().stage());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.IO,body.snapshot().error());json(body);
    var parse=new ProductionReadOnlyDiagnostics();rejected(parse,new FakeClient(r->response(200,("{\"unexpected\":\""+CANARY+"\"}").getBytes(StandardCharsets.UTF_8))));assertEquals(ProductionReadOnlyDiagnostics.Stage.TOKEN_PARSE,parse.snapshot().stage());json(parse);
  }
  @Test void workbookHttpFailureIdentifiesOnlyFixedStage() throws Exception {
    var d=new ProductionReadOnlyDiagnostics();rejected(d,new FakeClient(r->response(token(r)?200:500,token(r)?validToken():CANARY.getBytes(StandardCharsets.UTF_8))));assertEquals(ProductionReadOnlyDiagnostics.Stage.WORKBOOK_HTTP,d.snapshot().stage());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.HTTP_STATUS,d.snapshot().error());json(d);
  }
  @Test void parserFailureIsDistinctFromTransportAndBodyCanaryAbsent() throws Exception {
    var d=new ProductionReadOnlyDiagnostics();var settings=new ProductionReadOnlyPreflight.Settings(TARGET,EMAIL,true);var fake=new FakeClient(r->response(200,token(r)?validToken():("{\"private\":\""+CANARY+"\"}").getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalStateException.class,()->ProductionReadOnlyPreflight.run(settings,()->credentials.clone(),null,false,(t,c,e,en)->new ProductionReadOnlyHttp(t,c,e,fake),d));assertEquals(ProductionReadOnlyDiagnostics.Stage.ROOT_FIELDS,d.snapshot().stage());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.REJECTED,d.snapshot().error());json(d);
  }
  @Test void timeoutSnapshotCannotBeOverwrittenByLateWorkerOrCleanup() throws Exception {
    var d=new ProductionReadOnlyDiagnostics();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
    var fake=new FakeClient(r->{entered.countDown();while(true){try{if(release.await(20,TimeUnit.MILLISECONDS))break;}catch(InterruptedException ignored){}}exited.countDown();throw new java.io.IOException(CANARY);});
    try(var http=new ProductionReadOnlyHttp(TARGET,credentials,EMAIL,fake,Duration.ofMillis(300)).withDiagnostics(d)){
      assertThrows(IllegalStateException.class,http::readFullResource);assertEquals(0,entered.getCount());var frozen=d.snapshot();assertEquals(ProductionReadOnlyDiagnostics.ErrorType.TIMEOUT,frozen.error());release.countDown();assertTrue(exited.await(2,TimeUnit.SECONDS));Thread.sleep(30);assertEquals(frozen,d.snapshot());d.mark(ProductionReadOnlyDiagnostics.Stage.COMPLETE);d.fail(ProductionReadOnlyDiagnostics.ErrorType.IO);assertEquals(frozen,d.snapshot());json(d);
    }finally{release.countDown();}
  }
  @Test void independentInvocationsCannotReusePriorFailure() throws Exception {
    var first=new ProductionReadOnlyDiagnostics();first.mark(ProductionReadOnlyDiagnostics.Stage.TOKEN_HTTP);first.fail(new java.io.IOException(CANARY));
    var next=new ProductionReadOnlyDiagnostics();assertEquals(ProductionReadOnlyDiagnostics.ErrorType.NONE,next.snapshot().error());next.mark(ProductionReadOnlyDiagnostics.Stage.COMPLETE);assertEquals(ProductionReadOnlyDiagnostics.Stage.TOKEN_HTTP,first.snapshot().stage());assertEquals(ProductionReadOnlyDiagnostics.Stage.COMPLETE,next.snapshot().stage());json(first);json(next);
  }
  @Test void configurationAndWindowRejectionsNeverReportErrorNone(){
    var env=ProductionReadOnlyStartupTest.enabled();env.put("BRIDGE_SHEETS_ENABLED","false");
    var config=ProductionReadOnlyStartup.run(env::get,ProductionReadOnlyStartupTest.CLOCK,new java.util.concurrent.atomic.AtomicBoolean(),ProductionReadOnlyStartupTest.noRead());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.REJECTED,config.diagnostic().error());
    env=ProductionReadOnlyStartupTest.enabled();env.put("BRIDGE_READONLY_STARTUP_EXPIRES_AT","2026-11-11T11:00:00Z");
    var window=ProductionReadOnlyStartup.run(env::get,ProductionReadOnlyStartupTest.CLOCK,new java.util.concurrent.atomic.AtomicBoolean(),ProductionReadOnlyStartupTest.noRead());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.REJECTED,window.diagnostic().error());
  }
  @Test void successAndDisabledOutcomesContainNoPriorCanaryOrFailure() throws Exception {
    var env=ProductionReadOnlyStartupTest.enabled();var d=new ProductionReadOnlyDiagnostics();
    var success=ProductionReadOnlyStartup.run(env::get,ProductionReadOnlyStartupTest.CLOCK,new java.util.concurrent.atomic.AtomicBoolean(),(s,c)->ProductionReadOnlyStartupTest.summary(),d);
    assertEquals(ProductionReadOnlyDiagnostics.Stage.COMPLETE,success.diagnostic().stage());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.NONE,success.diagnostic().error());
    var disabled=ProductionReadOnlyStartup.run(k->null,ProductionReadOnlyStartupTest.CLOCK,new java.util.concurrent.atomic.AtomicBoolean(),ProductionReadOnlyStartupTest.noRead());assertEquals("DISABLED",disabled.status());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.NONE,disabled.diagnostic().error());String text=new ObjectMapper().writeValueAsString(disabled);assertFalse(text.contains(CANARY));assertFalse(text.contains("credential"));
  }
}
