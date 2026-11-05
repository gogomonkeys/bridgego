package com.bridge.persistence;
import com.bridge.room.IdentityStore;
import java.util.*;
import java.time.Clock;
/** One process only: Sheets has no distributed uniqueness / compare-and-set. */
public final class SheetsIdentityStore implements IdentityStore {
    private final SheetsGateway gateway;
    private final Clock clock;
    public SheetsIdentityStore(SheetsGateway gateway){this(gateway,Clock.systemUTC());}
    public SheetsIdentityStore(SheetsGateway gateway,Clock clock){this.gateway=gateway;this.clock=Objects.requireNonNull(clock);}
    public boolean enabled(){return true;}
    public synchronized String findOrCreateAccount(String issuer,String subject) {
        if(!"https://accounts.google.com".equals(issuer)||subject==null||subject.isBlank()||subject.length()>255)throw new IllegalArgumentException("Invalid verified identity");
        for(int attempt=0;attempt<3;attempt++)try {
            QuarterlyRetention.requireCollectionReady(gateway,clock);
            ErasureLedger ledger=new ErasureLedger(gateway.read("erasure_ledger"));
            if(ledger.contains("IDENTITY",ErasureLedger.identityKey(issuer,subject)))throw new SheetsGateway.Conflict();
            String existing=find(issuer,subject); if(existing!=null){if(new ErasureLedger(gateway.read("erasure_ledger")).contains("ACCOUNT",existing))throw new SheetsGateway.Conflict();return existing;}
            String id=UUID.randomUUID().toString();
            QuarterlyRetention.requireCollectionReady(gateway,clock);
            gateway.appendAtomic(Map.of("accounts",List.of(List.of(id,"1")),"auth_identities",List.of(List.of(issuer,subject,id,"1"))));
            String saved=find(issuer,subject); if(!id.equals(saved))throw new SheetsGateway.Conflict();return saved;
        }catch(SheetsGateway.Failure e){
            if(!e.retryable()||e.retryAfterMillis>30000||attempt==2)throw e;
            try{Thread.sleep(Math.min(30000,Math.max(e.retryAfterMillis,250L<<attempt)));}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Identity persistence unavailable");}
        }
        throw new IllegalStateException("Identity persistence unavailable");
    }
    private String find(String issuer,String subject) {
        Set<String> accounts=new HashSet<>();
        for(List<String> row:gateway.read("accounts")){if(row.isEmpty()||row.get(0).equals("account_id"))continue;if(row.size()!=2||!row.get(1).equals("1"))throw new SheetsGateway.Conflict();try{UUID.fromString(row.get(0));}catch(Exception e){throw new SheetsGateway.Conflict();}accounts.add(row.get(0));}
        String result=null;
        for(List<String> row:gateway.read("auth_identities")){
            if(row.isEmpty()||row.get(0).equals("issuer"))continue;
            if(row.size()!=4||!row.get(3).equals("1")||!accounts.contains(row.get(2)))throw new SheetsGateway.Conflict();
            if(row.get(0).equals(issuer)&&row.get(1).equals(subject)){if(result!=null&&!result.equals(row.get(2)))throw new SheetsGateway.Conflict();result=row.get(2);}
        }
        return result;
    }
}
