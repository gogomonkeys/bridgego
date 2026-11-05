package com.bridge.maintenance;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** No request logging; strict same-origin mutation, bounded body and global request budget. */
public final class MaintenanceGuard extends OncePerRequestFilter {
  private final ErasureMaintenanceApplication.MaintenanceSettings settings;
  private long window; private int count;
  public MaintenanceGuard(ErasureMaintenanceApplication.MaintenanceSettings settings){this.settings=settings;}
  private synchronized boolean allowed(){long now=System.nanoTime();if(now-window>60_000_000_000L){window=now;count=0;}return ++count<=120;}
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");
    response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Referrer-Policy","strict-origin-when-cross-origin");
    response.setHeader("X-Frame-Options","DENY");
    if(!java.util.Set.of("/", "/maintenance", "/maintenance/app.js", "/maintenance/session",
        "/maintenance/challenge", "/maintenance/preview", "/maintenance/execute", "/health/ready")
        .contains(request.getRequestURI())) {fail(response,404,"NOT_FOUND");return;}
    if(!request.getRequestURI().equals("/health/ready") && !allowed()){fail(response,429,"RATE_LIMITED");return;}
    if(!request.getMethod().equals("GET")&&!request.getMethod().equals("HEAD")) {
      if(!settings.origin().equals(request.getHeader("Origin"))){fail(response,403,"ORIGIN_NOT_ALLOWED");return;}
      if(request.getContentLengthLong()>16384){fail(response,413,"REQUEST_TOO_LARGE");return;}
      byte[] body=request.getInputStream().readNBytes(16385);
      if(body.length>16384){fail(response,413,"REQUEST_TOO_LARGE");return;}
      HttpServletRequest wrapped=new HttpServletRequestWrapper(request) {
        @Override public ServletInputStream getInputStream(){
          var bytes=new ByteArrayInputStream(body);
          return new ServletInputStream(){public int read(){return bytes.read();}public boolean isFinished(){return bytes.available()==0;}
            public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
      };
      chain.doFilter(wrapped,response);return;
    }
    chain.doFilter(request,response);
  }
  private void fail(HttpServletResponse response,int status,String code)throws IOException {
    response.setStatus(status);response.setContentType("application/json");response.getWriter().write("{\"code\":\""+code+"\"}");
  }
}
