package com.bridge.room;
import com.bridge.persistence.QuarterMaintenanceGate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
class CutoverSecurityHeadersTest {
  QuarterMaintenanceFilter enabled(){var f=new QuarterMaintenanceFilter(new QuarterMaintenanceGate(true,Clock.systemUTC()));ReflectionTestUtils.setField(f,"cutoverRuntime",true);return f;}
  @Test void exactReadRoutesAllowGetAndHeadAndPostRoutesRemainNarrow()throws Exception{
    for(String route:new String[]{"config","operator.html","operator.css","operator.js"})for(String method:new String[]{"GET","HEAD"}){
      var response=new MockHttpServletResponse();var called=new AtomicBoolean();enabled().doFilter(new MockHttpServletRequest(method,"/initial-cutover/"+route),response,(q,s)->called.set(true));
      assertTrue(called.get());assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));assertEquals("DENY",response.getHeader("X-Frame-Options"));
      assertEquals("same-origin-allow-popups",response.getHeader("Cross-Origin-Opener-Policy"));String csp=response.getHeader("Content-Security-Policy");assertTrue(csp.contains("https://accounts.google.com/gsi/client"));assertTrue(csp.contains("frame-ancestors 'none'"));assertFalse(csp.contains("unsafe-eval"));assertFalse(csp.contains("*"));
    }
    for(String path:new String[]{"session","challenge","prepare","metadata/preview","metadata/execute","history/preview","history/execute","activate/preview","activate/execute","observation/challenge","observation/submit"}){
      var called=new AtomicBoolean();enabled().doFilter(new MockHttpServletRequest("POST","/initial-cutover/"+path),new MockHttpServletResponse(),(q,s)->called.set(true));assertTrue(called.get());
    }
  }
  @Test void downstreamStaticCacheHeadersCannotOverrideNoStoreEvenAfterReset()throws Exception{
    var response=new MockHttpServletResponse();enabled().doFilter(new MockHttpServletRequest("GET","/initial-cutover/operator.js"),response,(q,s)->{
      var r=(HttpServletResponse)s;r.setHeader("cache-control","public,max-age=3600");r.addHeader("Cache-Control","public");r.setHeader("Referrer-Policy","unsafe-url");r.reset();r.addHeader("cache-control","public");r.setHeader("Content-Type","application/javascript");
    });assertEquals(java.util.List.of("no-store"),new java.util.ArrayList<>(response.getHeaders("Cache-Control")));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));assertEquals("application/javascript",response.getHeader("Content-Type"));
  }
  @Test void prefixAndWrongMethodNeverBypassClosedGate()throws Exception{
    for(String[] r:new String[][]{{"GET","/initial-cutover/history/execute"},{"POST","/initial-cutover/operator.js"},{"GET","/initial-cutover/other"},{"GET","/initial-cutover/operator.html/extra"}}){
      var response=new MockHttpServletResponse();enabled().doFilter(new MockHttpServletRequest(r[0],r[1]),response,(q,s)->fail("unexpected admission"));assertEquals(503,response.getStatus());
    }
  }
}
