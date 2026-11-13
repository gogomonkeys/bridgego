package com.bridge.room;

import java.util.Map;
import com.bridge.persistence.QuarterMaintenanceGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceHealthController {
  private final RoomService service;
  private final String version;
  private final QuarterMaintenanceGate maintenance;
  private final com.bridge.persistence.NativePersistenceSelection source;
  private volatile boolean applicationReady;

  @Autowired
  public ServiceHealthController(RoomService service, QuarterMaintenanceGate maintenance,
      @Value("${bridge.app-version:0.13.2}") String version,com.bridge.persistence.NativePersistenceSelection source) {
    this.service = service;
    this.maintenance = maintenance;
    this.version = version;
    this.source = source;
  }

  public ServiceHealthController(RoomService service,QuarterMaintenanceGate maintenance,String version){this(service,maintenance,version,null);}

  public ServiceHealthController(RoomService service, String version) {
    this(service, QuarterMaintenanceGate.disabled(), version);
  }

  @org.springframework.context.event.EventListener
  public void initialized(org.springframework.boot.context.event.ApplicationReadyEvent event){applicationReady=true;}
  @org.springframework.context.event.EventListener
  public void initializationFailed(org.springframework.boot.context.event.ApplicationFailedEvent event){applicationReady=false;}
  @org.springframework.context.event.EventListener
  public void contextClosed(org.springframework.context.event.ContextClosedEvent event){applicationReady=false;}

  /** Routing readiness is not write readiness. Safe maintenance response lets the platform
   * retire its predecessor; ownership verification still gates every business endpoint. */
  @GetMapping("/health/route")
  public ResponseEntity<?> route(){
    if(!applicationReady||service.stopping())return routingResponse(false,"initializing-or-stopping");
    if(maintenance.isOpen())return routingResponse(!service.quarterMaintenance(),service.quarterMaintenance()?"maintenance-unverified":"game-serving");
    boolean safe=maintenance.enabled()&&source!=null&&source.maintenanceRoutingSafe()
        &&(service.quarterMaintenance()||service.emptyForStartupRouting());
    return routingResponse(safe,safe?"maintenance-serving":"routing-unverified");
  }
  private ResponseEntity<?> routingResponse(boolean safe,String status){
    return ResponseEntity.status(safe?200:503).cacheControl(CacheControl.noStore())
        .header("X-Bridge-Instance-Epoch",service.instanceEpoch())
        .body(Map.of("status",status,"writeReady",maintenance.acceptsNewGames()&&!service.quarterMaintenance()&&!service.stopping(),"version",version));
  }

  @GetMapping("/health/live")
  public ResponseEntity<?> live() { return response(false); }

  @GetMapping("/health/ready")
  public ResponseEntity<?> ready() { return response(true); }

  private ResponseEntity<?> response(boolean readiness) {
    boolean stopping = service.stopping();
    boolean unavailable = !maintenance.isOpen() || service.quarterMaintenance();
    return ResponseEntity.status(readiness && (stopping || unavailable) ? 503 : 200)
        .cacheControl(CacheControl.noStore())
        .header("X-Bridge-Instance-Epoch", service.instanceEpoch())
        .body(Map.of("status", stopping ? "stopping" : unavailable ? "maintenance" : "ready",
            "instanceEpoch", service.instanceEpoch(), "version", version));
  }
}
