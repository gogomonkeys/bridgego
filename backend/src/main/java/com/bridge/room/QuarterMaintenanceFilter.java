package com.bridge.room;

import com.bridge.persistence.QuarterMaintenanceGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Admission barrier only. Domain timers and persistence must also be quiescent before cleanup. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class QuarterMaintenanceFilter extends OncePerRequestFilter {
  private final QuarterMaintenanceGate gate;
  private boolean cutoverRuntime;
  public QuarterMaintenanceFilter(QuarterMaintenanceGate gate) { this.gate = gate; }
  @org.springframework.beans.factory.annotation.Autowired
  public QuarterMaintenanceFilter(QuarterMaintenanceGate gate,org.springframework.beans.factory.ObjectProvider<com.bridge.persistence.PreinstalledNativeRuntime> installed){
    this(gate);cutoverRuntime=installed.getIfAvailable()!=null;
  }
  private static final java.util.Set<String> CUTOVER_POSTS=java.util.Set.of(
      "/initial-cutover/session","/initial-cutover/challenge","/initial-cutover/prepare",
      "/initial-cutover/metadata/preview","/initial-cutover/metadata/execute",
      "/initial-cutover/history/preview","/initial-cutover/history/execute",
      "/initial-cutover/activate/preview","/initial-cutover/activate/execute",
      "/initial-cutover/observation/challenge","/initial-cutover/observation/submit");
  private static final java.util.Set<String> CUTOVER_GETS=java.util.Set.of(
      "/initial-cutover/config","/initial-cutover/operator.html","/initial-cutover/operator.css","/initial-cutover/operator.js");
  private static final java.util.Map<String,String> CUTOVER_HEADERS=java.util.Map.of(
      "Cache-Control","no-store", "Pragma","no-cache", "X-Content-Type-Options","nosniff",
      "Referrer-Policy","no-referrer", "X-Frame-Options","DENY", "Cross-Origin-Opener-Policy","same-origin-allow-popups",
      "Content-Security-Policy","default-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; "
        +"script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' https://accounts.google.com/gsi/style; "
        +"connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; img-src 'self' data:; font-src 'self'");
  /** Resource handlers must not replace no-store with a default public cache directive. */
  private static HttpServletResponse protectedCutoverResponse(HttpServletResponse response){
    CUTOVER_HEADERS.forEach(response::setHeader);
    return new jakarta.servlet.http.HttpServletResponseWrapper(response){
      private boolean protectedName(String name){return name!=null&&CUTOVER_HEADERS.keySet().stream().anyMatch(k->k.equalsIgnoreCase(name));}
      @Override public void setHeader(String name,String value){if(!protectedName(name))super.setHeader(name,value);}
      @Override public void addHeader(String name,String value){if(!protectedName(name))super.addHeader(name,value);}
      @Override public void reset(){super.reset();CUTOVER_HEADERS.forEach((name,value)->((HttpServletResponse)getResponse()).setHeader(name,value));}
    };
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws IOException, ServletException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if(cutoverRuntime&&((request.getMethod().equals("POST")&&CUTOVER_POSTS.contains(path))
        ||((request.getMethod().equals("GET")||request.getMethod().equals("HEAD"))&&CUTOVER_GETS.contains(path)))){
      chain.doFilter(request,protectedCutoverResponse(response));return;
    }
    // Exact health endpoints only. No blanket /health/* exemption for future business routes.
    if (path.equals("/health/live") || path.equals("/health/ready") || path.equals("/health/route")) {
      chain.doFilter(request, response);
      return;
    }
    QuarterMaintenanceGate.Permit admission;
    try { admission = gate.enterRead(); }
    catch (QuarterMaintenanceGate.Unavailable closed) {
      unavailable(request, response, path);
      return;
    }
    // Counts admitted synchronous requests during draining; does not grant storage-write authority.
    try (admission) { chain.doFilter(request, response); }
  }

  private void unavailable(HttpServletRequest request, HttpServletResponse response, String path)
      throws IOException {
    response.setStatus(503);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Retry-After", "60");
    boolean page = path.equals("/") || path.equals("/index.html");
    response.setContentType(page ? "text/html;charset=UTF-8" : "application/json;charset=UTF-8");
    if (request.getMethod().equals("HEAD")) return;
    response.getWriter().write(page ? """
        <!doctype html><html lang="zh-Hant"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>BridgeGo｜維護中</title>
        <style>body{margin:0;background:#102923;color:#f4f6f3;font:18px/1.7 system-ui,sans-serif;min-height:100vh;display:grid;place-items:center}main{max-width:30rem;padding:2rem}h1{font-size:1.5rem}p{color:#cad8d1}button{font:inherit;padding:.5rem 1rem;cursor:pointer}</style>
        <main><h1>系統維護中</h1><p>正在整理季度資料，請稍後再回來。</p><form method="get" action="/"><button>重新查看</button></form></main></html>
        """ : "{\"code\":\"QUARTER_MAINTENANCE\",\"message\":\"系統維護中，請稍後再試。\"}");
  }
}
