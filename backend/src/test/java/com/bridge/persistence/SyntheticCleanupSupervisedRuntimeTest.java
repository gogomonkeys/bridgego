package com.bridge.persistence;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Actual dedicated-profile Boot and client construction; generated throwaway RSA only.
 * The real client is opened/closed without any request. All executed exchanges use FakeClient. */
@EnabledIfSystemProperty(named="bridge.supervised.runtime",matches="true")
class SyntheticCleanupSupervisedRuntimeTest {
  @Test void realBootRuntimeAllocatesRealRestrictedClientWithoutSendingOrReadingCredentials() throws Exception {
    assertInstanceOf(NOPLoggerFactory.class,LoggerFactory.getILoggerFactory());
    assertTrue(ServiceLoader.load(org.slf4j.spi.SLF4JServiceProvider.class).stream().findAny().isEmpty());
    assertThrows(ClassNotFoundException.class,()->Class.forName("ch.qos.logback.classic.LoggerContext"));
    String port;try(var socket=new ServerSocket(0)){port=""+socket.getLocalPort();}
    byte[] key=LiveQuarterHttpTest.generatedCredentials();
    var binding=new ManualQuarterMaintenance.Binding("synthetic_runtime_not_real_12345","123e4567-e89b-12d3-a456-426614174000");
    var app=SyntheticCleanupSupervisedWeb.application(new String[0],name->{
      if(name.equals("PORT"))return port;
      if(name.equals("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON"))throw new AssertionError("Startup cannot request a key");
      return null;
    });
    try(var context=app.run()){
      assertEquals("none",System.getProperty("org.springframework.boot.logging.LoggingSystem"));
      assertTrue(context.isActive());assertInstanceOf(NOPLoggerFactory.class,LoggerFactory.getILoggerFactory());
      try(var actual=LiveQuarterHttp.open(binding,key)){assertNotNull(actual);} // Allocation only, never read/replace.
      var fake=new LiveQuarterHttpTest.FakeClient(request->request.getRequestUri().equals("/token")
          ?LiveQuarterHttpTest.response(200,LiveQuarterHttpTest.validToken())
          :LiveQuarterHttpTest.response(200,"{}".getBytes(StandardCharsets.UTF_8)));
      try(var transport=new LiveQuarterHttp(binding,key,fake)){
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8),transport.readWorkbook());
      }
    }finally{Arrays.fill(key,(byte)0);}
  }
  @Test void runtimeLevelChangesCannotEnableApacheOrCredentialLogsAndEnumDiagnosticsRemain() throws Exception {
    assertInstanceOf(NOPLoggerFactory.class,LoggerFactory.getILoggerFactory());
    var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
    var oldOut=System.out;var oldErr=System.err;
    var properties=Map.of("logging.level.root","TRACE","logging.level.org.apache.hc","TRACE",
        "org.slf4j.simpleLogger.defaultLogLevel","trace","org.slf4j.simpleLogger.log.org.apache.hc.client5.http.wire","trace");
    var previous=new HashMap<String,String>();properties.forEach((k,v)->{previous.put(k,System.getProperty(k));System.setProperty(k,v);});
    java.util.logging.Logger jul=java.util.logging.Logger.getLogger("org.apache.hc");var priorLevel=jul.getLevel();
    try{
      System.setOut(new PrintStream(out,true,StandardCharsets.UTF_8));System.setErr(new PrintStream(err,true,StandardCharsets.UTF_8));
      jul.setLevel(java.util.logging.Level.ALL);
      for(String name:List.of("org.apache.hc.client5.http.wire","org.apache.hc.client5.http.headers",
          "org.apache.hc.client5.http.impl.classic.InternalHttpClient","org.apache.hc.core5.http.impl.io",
          "com.bridge.persistence.LiveQuarterHttp")){
        var log=LoggerFactory.getLogger(name);
        assertFalse(log.isTraceEnabled());assertFalse(log.isDebugEnabled());assertFalse(log.isInfoEnabled());assertFalse(log.isWarnEnabled());assertFalse(log.isErrorEnabled());
        log.trace("CANARY_PRIVATE_KEY");log.debug("CANARY_AUTHORIZATION");log.info("CANARY_OAUTH_ASSERTION");
        log.warn("CANARY_ACCESS_TOKEN");log.error("CANARY_CREDENTIAL",new RuntimeException("CANARY_SECRET_EXCEPTION"));
      }
      LiveQuarterHttp.requireSilentLogging();
      var diagnostic=SyntheticCleanupSupervisedHost.class.getDeclaredMethod("log",SyntheticCleanupSupervisedHost.Diagnostic.class);
      diagnostic.setAccessible(true);
      for(var code:SyntheticCleanupSupervisedHost.Diagnostic.values())diagnostic.invoke(null,code);
    }finally{
      System.setOut(oldOut);System.setErr(oldErr);jul.setLevel(priorLevel);
      previous.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});
    }
    assertEquals("",out.toString(StandardCharsets.UTF_8));
    var lines=err.toString(StandardCharsets.UTF_8).lines().toList();
    assertEquals(SyntheticCleanupSupervisedHost.Diagnostic.values().length,lines.size());
    for(int i=0;i<lines.size();i++)assertEquals("SYNTHETIC_SUPERVISED "+SyntheticCleanupSupervisedHost.Diagnostic.values()[i].name(),lines.get(i));
    assertFalse(err.toString(StandardCharsets.UTF_8).contains("CANARY"));
  }
}
