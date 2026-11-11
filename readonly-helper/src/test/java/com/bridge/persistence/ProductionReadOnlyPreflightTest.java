package com.bridge.persistence;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyHttpTest.*;

/** Fake HTTP and ephemeral generated credentials only. No live accounts or sockets. */
class ProductionReadOnlyPreflightTest {
  static final String EMAIL="synthetic-test@synthetic-project.iam.gserviceaccount.com";
  static final String TOKEN="synthetic.fake.token";
  static byte[] credentials;
  static final ProductionReadOnlyTarget TARGET=ProductionReadOnlyWorkbookTest.TARGET;
  static final ProductionReadOnlyPreflight.Settings ENABLED=new ProductionReadOnlyPreflight.Settings(TARGET,EMAIL,true);
  @BeforeAll static void key(){credentials=generatedCredentials();}
  @AfterAll static void clear(){Arrays.fill(credentials,(byte)0);}
  static byte[] resource(){return ProductionReadOnlyWorkbookTest.bytes(ProductionReadOnlyWorkbookTest.fixture());}
  static boolean oauth(org.apache.hc.core5.http.ClassicHttpRequest request){try{return request.getUri().toString().equals("https://oauth2.googleapis.com/token");}catch(Exception e){throw new AssertionError(e);}}
  static ProductionReadOnlyPreflight.Factory factory(FakeClient client){return (t,c,e,enabled)->new ProductionReadOnlyHttp(t,c,e,client);}
  static void rejected(Runnable action){var e=assertThrows(IllegalStateException.class,action::run);assertEquals("Production read-only preflight unavailable or rejected",e.getMessage());assertNull(e.getCause());assertEquals(0,e.getSuppressed().length);}
  static void wiped(byte[] bytes){for(byte b:bytes)assertEquals(0,b);}

  @Test void defaultDisabledBeforeCredentialSupplierFactoryOrNetwork(){AtomicInteger loads=new AtomicInteger(),opens=new AtomicInteger();var settings=new ProductionReadOnlyPreflight.Settings(TARGET,EMAIL);rejected(()->ProductionReadOnlyPreflight.run(settings,()->{loads.incrementAndGet();throw new AssertionError();},null,false,(t,c,e,arm)->{opens.incrementAndGet();throw new AssertionError();}));assertEquals(0,loads.get());assertEquals(0,opens.get());rejected(()->ProductionReadOnlyPreflight.observeOnce(null,()->{throw new AssertionError();}));}
  @Test void missingIdentityAndReviewPinsRejectBeforeSecretAccess(){Supplier<byte[]> nope=()->{throw new AssertionError("Must not read credentials");};rejected(()->ProductionReadOnlyPreflight.observeOnce(new ProductionReadOnlyPreflight.Settings(TARGET,null,true),nope));rejected(()->ProductionReadOnlyPreflight.verifyOnce(ENABLED,nope,null));rejected(()->ProductionReadOnlyPreflight.verifyOnce(ENABLED,nope,"bad"));}
  @Test void explicitObservationAndReviewedVerificationOnlyReturnSafeSummaryAndClearOwnedCredentials(){
    for(boolean verify:List.of(false,true)) {
      byte[] source=resource(),owned=credentials.clone();String layout=ProductionReadOnlyWorkbook.inspect(source,TARGET).layoutHash();
      var client=new FakeClient(r->response(200,oauth(r)?validToken():source));
      var result=ProductionReadOnlyPreflight.run(ENABLED,()->owned,verify?layout:null,verify,factory(client));
      assertEquals(verify?ProductionReadOnlyWorkbook.Status.STRUCTURE_COMPATIBLE_ONLY:ProductionReadOnlyWorkbook.Status.STRUCTURE_OBSERVED_LAYOUT_UNAPPROVED,result.status());
      assertFalse(result.mutationPermitted());assertFalse(result.businessSemanticsVerified());assertFalse(result.writerExclusionVerified());
      String summary=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result).toString();
      for(String value:List.of(EMAIL,TOKEN,"SYNTHETIC_SUBJECT","synthetic@example.invalid","SYNTHETIC_NOT_A_REAL_PAYLOAD"))assertFalse(summary.contains(value));
      wiped(owned);assertEquals(2,client.calls.get());assertEquals(1,client.closes.get());
    }
  }
  @Test void credentialIdentityMismatchRejectedBeforeClientFactory(){byte[] own=credentials.clone();rejected(()->ProductionReadOnlyPreflight.run(new ProductionReadOnlyPreflight.Settings(TARGET,"other@synthetic-project.iam.gserviceaccount.com",true),()->own,null,false,ProductionReadOnlyHttp::open));wiped(own);}
  @Test void parserFailureOrWrongLayoutDoesNotFallbackOrRepeat(){for(boolean mismatch:List.of(false,true)){byte[] own=credentials.clone();var client=new FakeClient(r->response(200,oauth(r)?validToken():mismatch?resource():"{}".getBytes(StandardCharsets.UTF_8)));rejected(()->ProductionReadOnlyPreflight.run(ENABLED,()->own,mismatch?"0".repeat(64):null,mismatch,factory(client)));wiped(own);assertEquals(2,client.calls.get());assertEquals(1,client.closes.get());}}
  @Test void supplierAndHttpErrorsSanitizedWithNoSecretOrRawData(){rejected(()->ProductionReadOnlyPreflight.observeOnce(ENABLED,()->{throw new IllegalStateException("secret private key private payload");}));byte[] own=credentials.clone();var client=new FakeClient(r->{throw new java.io.IOException("secret token private payload");});rejected(()->ProductionReadOnlyPreflight.run(ENABLED,()->own,null,false,factory(client)));wiped(own);assertEquals(1,client.calls.get());assertEquals(1,client.closes.get());}
  @Test void onlyOnePublicInvocationMayBeActive() throws Exception {CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();Thread first=new Thread(()->{try{ProductionReadOnlyPreflight.run(ENABLED,()->{held.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException();}return credentials.clone();},null,false,factory(new FakeClient(r->response(200,oauth(r)?validToken():resource()))));}catch(Throwable t){failure.set(t);}});first.start();assertTrue(held.await(2,TimeUnit.SECONDS));try{rejected(()->ProductionReadOnlyPreflight.observeOnce(ENABLED,()->{throw new AssertionError("No concurrent secret read");}));}finally{release.countDown();first.join(3000);}assertFalse(first.isAlive());assertNull(failure.get());}
  @Test void noPublicRawOrWriteApi(){assertFalse(java.lang.reflect.Modifier.isPublic(ProductionReadOnlyHttp.class.getModifiers()));for(var method:ProductionReadOnlyPreflight.class.getMethods()){if(method.getDeclaringClass()==ProductionReadOnlyPreflight.class)assertEquals(ProductionReadOnlyWorkbook.Report.class,method.getReturnType());}assertTrue(Arrays.stream(ProductionReadOnlyHttp.class.getDeclaredMethods()).noneMatch(m->m.getName().toLowerCase().contains("write")||m.getName().toLowerCase().contains("batch")));}
  @Test void confirmedDeploymentPinsCannotBeSwitchedByMismatchingConfig(){String sheet="1_AqJ_-jXFNSzUZhsTylnXqTmIbtL-PHA1VDd23tV5p0",dataset="f89030e0-6629-43d7-8a96-558f488c9da2";var target=ProductionReadOnlyDeployment.confirmedTarget(sheet,dataset,"managed");assertEquals(1902934440,target.sheets().get(0).sheetId());assertEquals(1882807199,target.sheets().get(7).sheetId());assertThrows(IllegalStateException.class,()->ProductionReadOnlyDeployment.confirmedTarget(TARGET.spreadsheetId(),dataset,"managed"));assertThrows(IllegalStateException.class,()->ProductionReadOnlyDeployment.confirmedTarget(sheet,TARGET.datasetId(),"managed"));assertThrows(IllegalStateException.class,()->ProductionReadOnlyDeployment.confirmedTarget(sheet,dataset,"restricted"));}

  @Test void facadeWipesOwnedRawResourceAfterBothSuccessAndParserFailure() throws Exception {
    for(boolean valid:List.of(true,false)) {
      AtomicReference<ProductionReadOnlyHttp> ref=new AtomicReference<>();AtomicReference<byte[]> raw=new AtomicReference<>();byte[] source=valid?resource():"{}".getBytes(StandardCharsets.UTF_8);
      var client=new FakeClient(request->{
        if(oauth(request))return response(200,validToken());
        return response(200,new java.io.ByteArrayInputStream(source){@Override public void close(){try{
          var f=ProductionReadOnlyHttp.class.getDeclaredField("running");f.setAccessible(true);var op=((AtomicReference<?>)f.get(ref.get())).get();
          var r=op.getClass().getDeclaredField("response");r.setAccessible(true);raw.set((byte[])((AtomicReference<?>)r.get(op)).get());
        }catch(Exception e){throw new IllegalStateException("test lookup failed");}}},source.length);
      });
      ProductionReadOnlyPreflight.Factory factory=(t,c,e,enabled)->{var http=new ProductionReadOnlyHttp(t,c,e,client);ref.set(http);return http;};
      Runnable action=()->ProductionReadOnlyPreflight.run(ENABLED,()->credentials.clone(),null,false,factory);
      if(valid)assertDoesNotThrow(action::run);else rejected(action);
      assertNotNull(raw.get());assertTrue(raw.get().length>0);wiped(raw.get());
    }
  }
}
