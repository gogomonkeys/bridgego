package com.bridge.persistence;
import com.bridge.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class QuarterBeanWiringTest {
    @Test void defaultSchedulerIsDisabledAndDoesNotFreezeRooms() {
        var config=new PersistenceConfiguration();var gate=config.quarterMaintenanceGate(new MockEnvironment());var rooms=new RoomService();
        var coordinator=config.quarterMaintenanceCoordinator(gate,config.quarterDurableControl(),rooms);
        config.quarterMaintenanceScheduler(coordinator).tick();
        assertEquals(QuarterMaintenanceCoordinator.Status.DISABLED,coordinator.status());assertTrue(gate.isOpen());assertFalse(rooms.quarterMaintenance());
    }
    @Test void enabledWiringHasNoDurableTransportAndCannotOpen() {
        var config=new PersistenceConfiguration();var gate=config.quarterMaintenanceGate(new MockEnvironment().withProperty("BRIDGE_QUARTER_GATE_ENABLED","true"));
        assertEquals(QuarterMaintenanceGate.State.STARTING,gate.state());
        var coordinator=config.quarterMaintenanceCoordinator(gate,config.quarterDurableControl(),new RoomService());
        var scheduler=config.quarterMaintenanceScheduler(coordinator);scheduler.tick();scheduler.tick();
        assertFalse(gate.isOpen());assertEquals(QuarterMaintenanceCoordinator.Status.BLOCKED_MANUAL,coordinator.status());
        assertThrows(IllegalStateException.class,()->config.quarterDurableControl().executeOnce(java.time.Instant.now(),QuarterMaintenanceCoordinator.Observation.unavailable(),"test"));
    }
}
