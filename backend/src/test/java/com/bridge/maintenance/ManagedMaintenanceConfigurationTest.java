package com.bridge.maintenance;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class ManagedMaintenanceConfigurationTest {
  @Test void managedConfigurationRejectsMissingBindingBeforeCredentialOrNetworkAccess(){
    var app=new ErasureMaintenanceApplication();
    var env=new MockEnvironment().withProperty("BRIDGE_COLLECTION_MODE","managed").withProperty("BRIDGE_TEST_SPREADSHEET_ID","test_must_not_be_fallback").withProperty("BRIDGE_TEST_DATASET_ID","123e4567-e89b-12d3-a456-426614174000");
    var missing=assertThrows(IllegalStateException.class,()->app.maintenanceGateway(env));assertTrue(missing.getMessage().contains("BRIDGE_SHEETS_SPREADSHEET_ID"));
    env.setProperty("BRIDGE_SHEETS_SPREADSHEET_ID","managed_fixture_not_real_12345");
    var id=assertThrows(IllegalStateException.class,()->app.maintenanceGateway(env));assertTrue(id.getMessage().contains("BRIDGE_DATASET_ID"));
  }
  @Test void managedDoesNotAutomaticallyEnableErasureOrAcknowledgeStoppedWriters(){
    var settings=new ErasureMaintenanceApplication().maintenanceSettings(new MockEnvironment().withProperty("BRIDGE_COLLECTION_MODE","managed").withProperty("PUBLIC_ORIGIN","https://example.test").withProperty("GOOGLE_CLIENT_ID","client"));
    assertFalse(settings.executionEnabled());assertFalse(settings.writerStoppedAcknowledged());
  }
}
