package com.bridge.room;
import com.bridge.persistence.*;
import jakarta.servlet.*;import jakarta.servlet.http.*;
import java.io.IOException;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;
import org.springframework.context.*;
/** Explicit fixed-E launcher only. Never component-scanned or enabled by environment flags.
 * The internal writer may be OPEN; no external business request ever reaches the game. */
public final class FixedSyntheticMaintenanceFilter implements Filter,ApplicationListener<ApplicationEvent> {
 private final QuarterMaintenanceGate gate;private final StagedNativeBackend stage;
 private volatile boolean initialized,closed;
 public FixedSyntheticMaintenanceFilter(DatasetBinding binding,QuarterMaintenanceGate gate,StagedNativeBackend stage){
  this.gate=Objects.requireNonNull(gate);this.stage=Objects.requireNonNull(stage);Objects.requireNonNull(binding);
  if(!binding.purpose().equals(DatasetBinding.TEST_PURPOSE)||!binding.datasetId().equals("879a3708-3bc4-4c76-9d9a-53883f71530a")
   ||!digest(binding.spreadsheetId()).equals("89c7ed98cfbaa53e72d9a3562357411e373f8026ee954346c0fc82ac95a40c9f")
   ||!gate.enabled()||!stage.belongsTo(gate))throw new IllegalArgumentException("Fixed isolated target required");
 }
 private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 @Override public void onApplicationEvent(ApplicationEvent event){
  if(event instanceof org.springframework.boot.context.event.ApplicationReadyEvent&&!closed)initialized=true;
  if(event instanceof org.springframework.context.event.ContextClosedEvent||event instanceof org.springframework.boot.context.event.ApplicationFailedEvent){closed=true;initialized=false;}
 }
 @Override public void doFilter(ServletRequest raw,ServletResponse out,FilterChain unused)throws IOException{
  if(!(raw instanceof HttpServletRequest request)||!(out instanceof HttpServletResponse response))throw new IllegalArgumentException("HTTP request required");
  String status="maintenance";int code=503;
  boolean exact="GET".equals(request.getMethod())&&request.getQueryString()==null&&request.getContextPath().isEmpty()&&request.getDispatcherType()==DispatcherType.REQUEST;
  if(exact){switch(request.getRequestURI()){
   case "/health/live" -> {status="isolated-live";code=closed?503:200;}
   case "/health/route" -> {status="isolated-maintenance";code=initialized&&!closed?200:503;}
   case "/health/synthetic-ready" -> {boolean ready=initialized&&!closed&&gate.acceptsNewGames()&&stage.enabled();status=ready?"isolated-ready":"isolated-maintenance";code=ready?200:503;}
   default -> {}
  }}
  response.setStatus(code);response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
  response.getWriter().write("{\"status\":\""+status+"\",\"writeReady\":false}");
 }
}
