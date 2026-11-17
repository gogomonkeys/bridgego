package com.bridge.persistence;
import com.bridge.room.IdentityStore;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.List;

@Configuration
public class PersistenceConfiguration {
    @Bean public SheetsGateway sheetsGateway(Environment env) {
        if("single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE")) || !Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED","false")))return new SheetsGateway(){public List<List<String>> read(String tab){throw new IllegalStateException("Sheets disabled");}public void appendAtomic(java.util.Map<String,List<List<Object>>> rows){throw new IllegalStateException("Sheets disabled");}};
        return DatasetBinding.open(env, sheet -> new GoogleSheetsGateway(sheet,
            env.getRequiredProperty("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON")));
    }
    @Bean public SocialStore socialStore(Environment env,SheetsGateway gateway) {
        if(Boolean.parseBoolean(env.getProperty("BRIDGE_FRIENDS_ENABLED","false"))
            && Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED","false"))
            && !"single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE")))return new SheetsSocialStore(gateway);
        return new SocialStore(){
            public boolean enabled(){return false;}
            private IllegalStateException disabled(){return new IllegalStateException("Friends unavailable");}
            public Snapshot snapshot(String id){throw disabled();} public String ensureCode(String id){throw disabled();}
            public Snapshot request(String id,String code,String action){throw disabled();}
            public Snapshot respond(String id,String request,boolean accept,String action){throw disabled();}
            public Snapshot remove(String id,String code,String action){throw disabled();}
        };
    }
    @Bean public StatsStore statsStore(Environment env,SheetsGateway gateway,org.springframework.beans.factory.ObjectProvider<ExperimentRuntimeConfiguration.Handle> experiment) {
        if("single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE"))) {
            var store=experiment.getObject().store();
            return new StatsStore(){public SaveStatus submit(FinalMatchEnvelope e){return store.submit(e);}public SaveStatus status(String id){return store.status(id);}public List<FinalMatchEnvelope> exportCommitted(){return store.exportCommitted();}};
        }
        if(!Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED","false")))return new StatsStore(){public SaveStatus submit(FinalMatchEnvelope e){return SaveStatus.DISABLED;}public SaveStatus status(String id){return SaveStatus.DISABLED;}public List<FinalMatchEnvelope> exportCommitted(){return List.of();}};
        return new SheetsStatsStore(gateway,Integer.parseInt(env.getProperty("BRIDGE_SHEETS_QUEUE_CAPACITY","128")));
    }
    @Bean public IdentityStore identityStore(Environment env,SheetsGateway gateway,org.springframework.beans.factory.ObjectProvider<ExperimentRuntimeConfiguration.Handle> experiment) {
        if("single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE"))) {
            var store=experiment.getObject().store();
            return new IdentityStore(){public boolean enabled(){return store.admissionAvailable();}public String findOrCreateAccount(String issuer,String subject){return store.findOrCreateAccount(issuer,subject);}};
        }
        if(!Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED","false")))return new IdentityStore(){public boolean enabled(){return false;}public String findOrCreateAccount(String issuer,String subject){throw new IllegalStateException("Durable identity storage disabled");}};
        return new SheetsIdentityStore(gateway);
    }
}
