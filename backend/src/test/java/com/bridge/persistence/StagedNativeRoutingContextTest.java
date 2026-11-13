package com.bridge.persistence;
import com.bridge.room.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class StagedNativeRoutingContextTest {
  void blocked(QuarterMaintenanceFilter filter)throws Exception {
    for(String path:new String[]{"/","/api/auth/google","/api/rooms","/api/statistics","/ws"}){
      var response=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("POST",path),response,(a,b)->fail("Business admission while staged"));assertEquals(503,response.getStatus());
    }
    var route=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/health/route"),route,(a,b)->b.getWriter().write("probe"));assertEquals("probe",route.getContentAsString());
  }
  @Test void realSpringInertStartupRoutesMaintenanceThenVerifiedActivationOpensSameFacades(){
    var ref=new AtomicReference<StagedNativeBackend>();
    new NativeSpringCutoverContextTest().base().withBean(NativePersistenceSelection.Factory.class,()->(request,gate)->{var b=new StagedNativeBackend(gate);ref.set(b);return b;})
      .withUserConfiguration(PersistenceConfiguration.class,ServiceHealthController.class,QuarterMaintenanceFilter.class).run(context->{
        assertNull(context.getStartupFailure());var staged=ref.get();var health=context.getBean(ServiceHealthController.class);var gate=context.getBean(QuarterMaintenanceGate.class);var coordinator=context.getBean(QuarterMaintenanceCoordinator.class);
        var identity=context.getBean(IdentityStore.class);var stats=context.getBean(StatsStore.class);var filter=context.getBean(QuarterMaintenanceFilter.class);
        assertEquals(503,health.route().getStatusCode().value());health.initialized(null);coordinator.tick();
        assertEquals(QuarterMaintenanceGate.State.STARTING,gate.state());assertEquals(200,health.route().getStatusCode().value());assertEquals(503,health.ready().getStatusCode().value());
        assertFalse(identity.enabled());assertThrows(IllegalStateException.class,()->identity.findOrCreateAccount("issuer","subject"));assertEquals(StatsStore.SaveStatus.REJECTED,stats.submit(null));blocked(filter);
        var backend=new NativeSourceCutoverSafetyTest.Backend();
        staged.activateOnce(()->{assertEquals(200,health.route().getStatusCode().value());assertEquals(503,health.ready().getStatusCode().value());blockedUnchecked(filter);return new StagedNativeBackend.Activation(backend,new QuarterMaintenanceCoordinator.DurableControl(){
          public QuarterMaintenanceCoordinator.Observation inspect(java.time.Instant quarter){return new QuarterMaintenanceCoordinator.Observation(QuarterMaintenanceCoordinator.Phase.COMPLETE,quarter,"verified-local-token","",true,true,true,true);}
          public void executeOnce(java.time.Instant q,QuarterMaintenanceCoordinator.Observation o,String id){throw new AssertionError();}
        });});
        coordinator.tick();assertEquals(QuarterMaintenanceGate.State.OPEN,gate.state());assertEquals(200,health.ready().getStatusCode().value());assertEquals(200,health.route().getStatusCode().value());
        assertSame(identity,context.getBean(IdentityStore.class));assertSame(stats,context.getBean(StatsStore.class));assertEquals("account",identity.findOrCreateAccount("issuer","subject","nickname","mail@example.com"));
        gate.blockManual(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);context.getBean(RoomService.class).freezeForQuarterMaintenance();
        assertEquals(200,health.route().getStatusCode().value());assertEquals(503,health.ready().getStatusCode().value());blocked(filter);
        health.contextClosed(null);assertEquals(503,health.route().getStatusCode().value());
      });
  }
  void blockedUnchecked(QuarterMaintenanceFilter filter){try{blocked(filter);}catch(Exception e){throw new RuntimeException(e);}}
  @Test void failedActivationIsNotRetriedAndStillServesMaintenance(){
    var ref=new AtomicReference<StagedNativeBackend>();
    new NativeSpringCutoverContextTest().base().withBean(NativePersistenceSelection.Factory.class,()->(request,gate)->{var b=new StagedNativeBackend(gate);ref.set(b);return b;})
      .withUserConfiguration(PersistenceConfiguration.class,ServiceHealthController.class,QuarterMaintenanceFilter.class).run(context->{
        assertNull(context.getStartupFailure());var health=context.getBean(ServiceHealthController.class);health.initialized(null);
        assertThrows(IllegalStateException.class,()->ref.get().activateOnce(()->{throw new IllegalStateException("Uncertain provisioning");}));
        assertThrows(IllegalStateException.class,()->ref.get().activateOnce(()->{throw new AssertionError("retry");}));
        context.getBean(QuarterMaintenanceCoordinator.class).tick();assertEquals(200,health.route().getStatusCode().value());assertEquals(503,health.ready().getStatusCode().value());blocked(context.getBean(QuarterMaintenanceFilter.class));
        health.initializationFailed(null);assertEquals(503,health.route().getStatusCode().value());
      });
  }
}
