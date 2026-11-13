package com.bridge.persistence;
import com.bridge.room.CollectionPolicy;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnProperty(name="BRIDGE_COLLECTION_MODE",havingValue="single_tester_experiment")
public class ExperimentRuntimeConfiguration {
  public static ExperimentStorage.Settings settings(Environment env,CollectionPolicy policy) {
    if(NativePersistenceSelection.selected(env))throw new IllegalStateException("Legacy experiment storage disabled by native cutover");
    if (!policy.experiment() || !Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED","false")))
      throw new IllegalStateException("Explicit experiment storage required");
    String sheet=env.getRequiredProperty("BRIDGE_TEST_SPREADSHEET_ID");
    if(sheet.isBlank()||sheet.equals(env.getProperty("BRIDGE_SHEETS_SPREADSHEET_ID","")))throw new IllegalStateException("Separate experiment dataset required");
    var p=policy.experimentPrincipal();
    return new ExperimentStorage.Settings(sheet,policy.experimentDataset(),p.issuer(),p.subject(),policy.experimentExpiresAt());
  }
  public record Handle(ExperimentStorage store) implements AutoCloseable { public void close(){store.close();} }
  @Bean @ConditionalOnMissingBean(Handle.class)
  public Handle experimentHandle(Environment env,CollectionPolicy policy) {
    var settings=settings(env,policy);Clock clock=Clock.systemUTC();
    return new Handle(new ExperimentStorage(settings,new GoogleExperimentGateway(new GoogleSheetsGateway(settings.sheetId(),
        env.getRequiredProperty("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON")),settings,clock),clock));
  }
}
