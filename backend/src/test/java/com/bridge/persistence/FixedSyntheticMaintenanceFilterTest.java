package com.bridge.persistence;
import com.bridge.room.FixedSyntheticMaintenanceFilter;
import java.time.*;import org.junit.jupiter.api.Test;import org.springframework.mock.web.*;import org.springframework.context.support.GenericApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
class FixedSyntheticMaintenanceFilterTest {
 static DatasetBinding binding(){return new DatasetBinding("1n-CaTcmMVlTxr_NXYyuw6mAsrQZ3YdMPtou3bSKxKY8","879a3708-3bc4-4c76-9d9a-53883f71530a",DatasetBinding.TEST_PURPOSE);}
 static MockHttpServletResponse request(FixedSyntheticMaintenanceFilter filter,String method,String uri,String query)throws Exception{
  var request=new MockHttpServletRequest(method,uri);request.setQueryString(query);var response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->fail("External request reached game"));assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("\"writeReady\":false"));assertFalse(response.getContentAsString().contains(binding().spreadsheetId()));return response;
 }
 @Test void internalOpenNeverExposesGamesAndOnlyExactProbeIsIsolatedReady()throws Exception{
  var gate=new QuarterMaintenanceGate(true,Clock.systemUTC());var stage=new StagedNativeBackend(gate);var filter=new FixedSyntheticMaintenanceFilter(binding(),gate,stage);
  assertEquals(200,request(filter,"GET","/health/live",null).getStatus());assertEquals(503,request(filter,"GET","/health/route",null).getStatus());
  var context=new GenericApplicationContext();filter.onApplicationEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(new org.springframework.boot.SpringApplication(),new String[0],context,Duration.ZERO));
  assertEquals(200,request(filter,"GET","/health/route",null).getStatus());assertEquals(503,request(filter,"GET","/health/synthetic-ready",null).getStatus());
  stage.activateOnce(()->new StagedNativeBackend.Activation(new NativeSourceCutoverSafetyTest.Backend(),QuarterMaintenanceCoordinator.DurableControl.unavailable()));gate.openVerified(QuarterlyRetention.currentQuarterStart(Instant.now()));
  assertEquals(200,request(filter,"GET","/health/synthetic-ready",null).getStatus());
  for(String path:new String[]{"/","/index.html","/api/auth/google","/api/rooms","/api/statistics","/ws","/health/ready","/health/live/","//health/live","/health/%6cive","/health/live;foo","/error"})assertEquals(503,request(filter,"GET",path,null).getStatus(),path);
  for(String method:new String[]{"POST","HEAD","OPTIONS","PUT"})assertEquals(503,request(filter,method,"/health/live",null).getStatus());
  assertEquals(503,request(filter,"GET","/health/live","probe=1").getStatus());
  filter.onApplicationEvent(new org.springframework.context.event.ContextClosedEvent(context));assertEquals(503,request(filter,"GET","/health/route",null).getStatus());assertEquals(503,request(filter,"GET","/health/synthetic-ready",null).getStatus());stage.close();context.close();
 }
 @Test void explicitConstructorRejectsOtherTargetPurposeOrGate(){
  var gate=new QuarterMaintenanceGate(true,Clock.systemUTC());var stage=new StagedNativeBackend(gate);var b=binding();
  assertThrows(IllegalArgumentException.class,()->new FixedSyntheticMaintenanceFilter(new DatasetBinding("different-isolated-table",b.datasetId(),b.purpose()),gate,stage));
  assertThrows(IllegalArgumentException.class,()->new FixedSyntheticMaintenanceFilter(new DatasetBinding(b.spreadsheetId(),b.datasetId(),DatasetBinding.MANAGED_PURPOSE),gate,stage));
  assertThrows(IllegalArgumentException.class,()->new FixedSyntheticMaintenanceFilter(b,new QuarterMaintenanceGate(true,Clock.systemUTC()),stage));
 }
}
