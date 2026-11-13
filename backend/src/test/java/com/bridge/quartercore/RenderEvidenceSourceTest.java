package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.SingleServiceEvidenceProvider.*;
class RenderEvidenceSourceTest {
  static final Clock CLOCK=JournalQuarterProtocolTest.CLOCK;
  static Binding binding(){return new Binding(SingleServiceEvidenceProviderTest.TARGET,"tea-test","srv-test","native-revision","approval","a".repeat(64));}
  static RenderEvidenceSource.Control control(){return new RenderEvidenceSource.Control(CLOCK.instant(),"approval","a".repeat(64),Map.of("operator",ExternalWriterState.NOT_CONFIGURED),null,null);}
  static byte[] services(){return "[{\"service\":{\"id\":\"srv-test\",\"ownerId\":\"tea-test\",\"type\":\"web_service\",\"suspended\":\"not_suspended\",\"autoDeploy\":\"no\",\"serviceDetails\":{\"numInstances\":1,\"previews\":{\"generation\":\"off\"}}}}]".getBytes(StandardCharsets.UTF_8);}
  static byte[] deploys(){return "[{\"deploy\":{\"id\":\"dep-current\",\"commit\":{\"id\":\"native-revision\"},\"status\":\"live\"}}]".getBytes(StandardCharsets.UTF_8);}
  @Test void exactReadOnlyPathsAuthenticatedAndCachedWithoutInventedHandoff() {
    var calls=new AtomicInteger();char[] secret="test-only-render-token".toCharArray();
    var source=RenderEvidenceSource.localTest(binding(),()->secret,(uri,token,deadline)->{
      calls.incrementAndGet();assertEquals("api.render.com",uri.getHost());assertEquals("test-only-render-token",new String(token));
      if(uri.getPath().equals("/v1/services")){assertEquals("ownerId=tea-test&includePreviews=true&limit=100",uri.getQuery());return services();}
      assertEquals("/v1/services/srv-test/deploys",uri.getPath());return deploys();
    },RenderEvidenceSourceTest::control,CLOCK);
    var result=source.read();assertEquals(1,result.instances());assertEquals("native-revision",result.deployments().get(0).revision());
    assertNull(result.handoff());assertNull(source.nativePredecessor());source.read();assertEquals(2,calls.get());
    for(char c:secret)assertEquals('\0',c);
  }
  @Test void disabledMissingEvidenceOrInvalidIdsNeverReadCredential() {
    var reads=new AtomicInteger();RenderEvidenceSource.CredentialSource credentials=()->{reads.incrementAndGet();return "secret".toCharArray();};
    assertThrows(RuntimeException.class,()->RenderEvidenceSource.production(false,binding(),credentials,RenderEvidenceSourceTest::control,CLOCK));
    var source=RenderEvidenceSource.localTest(binding(),credentials,(u,t,deadline)->{throw new AssertionError();},()->null,CLOCK);
    assertThrows(RuntimeException.class,source::read);assertEquals(0,reads.get());
    var bad=new Binding(binding().target(),"tea-test/../../bad","srv-test","r","approval","a".repeat(64));
    assertThrows(RuntimeException.class,()->RenderEvidenceSource.production(true,bad,credentials,RenderEvidenceSourceTest::control,CLOCK));
  }
  @Test void failuresNeverServeStaleOrEchoSecretsAndBodyLimitIsEnforced() {
    var source=RenderEvidenceSource.localTest(binding(),()->"test-only".toCharArray(),(u,t,deadline)->new byte[1024*1024+1],RenderEvidenceSourceTest::control,CLOCK);
    RuntimeException error=assertThrows(RuntimeException.class,source::read);assertNull(error.getMessage());assertThrows(RuntimeException.class,source::read);
    var wrong=RenderEvidenceSource.localTest(binding(),()->"test-only".toCharArray(),(u,t,deadline)->services(),RenderEvidenceSourceTest::control,CLOCK);
    assertThrows(RuntimeException.class,wrong::read);
    assertThrows(RuntimeException.class,()->RenderEvidenceSource.httpsGet(java.net.URI.create("https://evil.example/v1/services"),new char[]{'x'},System.nanoTime()+1_000_000_000L));
    assertThrows(RuntimeException.class,()->RenderEvidenceSource.httpsGet(java.net.URI.create("https://api.render.com/v1/services/srv-test/env-vars"),new char[]{'x'},System.nanoTime()+1_000_000_000L));
  }
  @Test void missingProcessEvidenceCanRetryReadOnlyWithoutMintingAuthority() {
    RenderEvidenceSource.Control[] proof={null};var reads=new AtomicInteger();
    var source=RenderEvidenceSource.localTest(binding(),()->{reads.incrementAndGet();return "test-only".toCharArray();},
      (u,t,deadline)->u.getPath().equals("/v1/services")?services():deploys(),()->proof[0],CLOCK);
    assertThrows(RenderEvidenceSource.NotReady.class,source::read);assertEquals(0,reads.get());
    proof[0]=control();assertNotNull(source.read());assertEquals(1,reads.get());
  }
  @Test void debugConfigurationRejectsBeforeCredentialRead() {
    String old=System.getProperty("javax.net.debug");var reads=new AtomicInteger();
    try {
      System.setProperty("javax.net.debug","ssl");
      var source=RenderEvidenceSource.localTest(binding(),()->{reads.incrementAndGet();return "test-only".toCharArray();},(u,t,d)->{throw new AssertionError();},RenderEvidenceSourceTest::control,CLOCK);
      assertThrows(RuntimeException.class,source::read);assertEquals(0,reads.get());
    }finally{if(old==null)System.clearProperty("javax.net.debug");else System.setProperty("javax.net.debug",old);}
  }
  @Test void failedDeployStatusesAreNotMisclassifiedAsRunning() {
    var source=RenderEvidenceSource.localTest(binding(),()->"test-only".toCharArray(),(u,t,deadline)->u.getPath().equals("/v1/services")?services():new String(deploys(),StandardCharsets.UTF_8).replace("live","build_failed").getBytes(StandardCharsets.UTF_8),RenderEvidenceSourceTest::control,CLOCK);
    assertEquals("failed",source.read().deployments().get(0).state());
  }
}
