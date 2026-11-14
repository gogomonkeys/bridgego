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
    @Override public synchronized String findOrCreateAccount(String issuer,String subject,String nickname,String verifiedEmail) {
        if(!"https://accounts.google.com".equals(issuer)||subject==null||subject.isBlank()||subject.length()>255)throw new IllegalArgumentException("Invalid verified identity");
        if(nickname==null||nickname.isBlank()||nickname.length()>256||nickname.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid nickname");
        String email=verifiedEmail==null?"":verifiedEmail;
        if(!AccountProfileRows.validEmail(email))throw new IllegalArgumentException("Invalid verified email");
        QuarterlyRetention.requireCollectionReady(gateway,clock);
        ErasureLedger ledger=new ErasureLedger(gateway.read("erasure_ledger"));
        if(ledger.contains("IDENTITY",ErasureLedger.identityKey(issuer,subject)))throw new SheetsGateway.Conflict();
        AccountProfileRows.validateTable(gateway.read("accounts"));
        String id=find(issuer,subject);
        if(id!=null&&ledger.contains("ACCOUNT",id))throw new SheetsGateway.Conflict();
        var rows=gateway.read("accounts");
        int row=0;
        if(id!=null)for(int i=1;i<rows.size();i++)if(rows.get(i).get(0).equals(id)){if(row!=0)throw new SheetsGateway.Conflict();row=i+1;}
        if(id!=null&&row==0)throw new SheetsGateway.Conflict();
        if(id==null)id=UUID.randomUUID().toString();
        List<String> desired=List.of(id,"2",nickname,email);
        if(row>0){var current=new ArrayList<>(rows.get(row-1));while(current.size()<4)current.add("");if(current.subList(0,4).equals(desired))return id;}
        String priorLifetime = row > 0 && rows.get(row-1).size() == 5 ? rows.get(row-1).get(4) : "";
        boolean lifetimeHeader = AccountProfileRows.LIFETIME_HEADER.equals(rows.get(0));
        QuarterlyRetention.requireCollectionReady(gateway,clock);
        // One write attempt only. Uncertain responses are reconciled by later reads, never replayed here.
        gateway.saveAccountProfile(row,desired,row==0?List.of(issuer,subject,id,"1"):null);
        if(!id.equals(find(issuer,subject)))throw new SheetsGateway.Conflict();
        var saved=gateway.read("accounts");
        AccountProfileRows.validateTable(saved);
        boolean matched=false;
        for(int i=1;i<saved.size();i++)if(saved.get(i).get(0).equals(id)) {
            List<String> value=new ArrayList<>(saved.get(i));while(value.size()<4)value.add("");
            String savedLifetime = value.size() == 5 ? value.get(4) : "";
            if(!value.subList(0,4).equals(desired) || !priorLifetime.equals(savedLifetime)
                || lifetimeHeader && !AccountProfileRows.LIFETIME_HEADER.equals(saved.get(0)) || matched)
              throw new SheetsGateway.Conflict();
            matched=true;
        }
        if(!matched)throw new SheetsGateway.Conflict();
        return id;
    }
    private static final List<String> ENTRY_TABLES = List.of("retention_ledger", "erasure_ledger", "accounts", "auth_identities");
    private record EntrySnapshot(Map<String,List<List<String>>> tables, String account, int row) {
        Profile profile() {
            if(account==null)return null;
            var value=tables.get("accounts").get(row-1);
            return new Profile(account,"2".equals(value.get(1))?value.get(2):null);
        }
    }
    /** A bounded, dataset-bound snapshot, with no persistent cache or write retries. */
    private EntrySnapshot entrySnapshot(String issuer,String subject) {
        if(!"https://accounts.google.com".equals(issuer)||subject==null||subject.isBlank()||subject.length()>255)
            throw new IllegalArgumentException("Invalid verified identity");
        var tables=SheetsGateway.immutableBatch(ENTRY_TABLES,gateway.readBatch(ENTRY_TABLES));
        var retention=new RetentionLedger(tables.get("retention_ledger"));
        if(!retention.complete()||!retention.cutoff().equals(QuarterlyRetention.currentQuarterStart(clock.instant())))
            throw new IllegalStateException("Quarter cleanup pending; collection blocked");
        var ledger=new ErasureLedger(tables.get("erasure_ledger"));
        if(ledger.contains("IDENTITY",ErasureLedger.identityKey(issuer,subject)))throw new SheetsGateway.Conflict();
        var accounts=tables.get("accounts");AccountProfileRows.validateTable(accounts);
        var ids=new HashMap<String,Integer>();
        for(int i=1;i<accounts.size();i++)ids.put(accounts.get(i).get(0),i+1);
        String account=null;
        for(var row:tables.get("auth_identities")) {
            if(row.isEmpty()||row.get(0).equals("issuer"))continue;
            if(row.size()!=4||!"1".equals(row.get(3))||!ids.containsKey(row.get(2)))throw new SheetsGateway.Conflict();
            if(row.get(0).equals(issuer)&&row.get(1).equals(subject)) {
                if(account!=null&&!account.equals(row.get(2)))throw new SheetsGateway.Conflict();
                account=row.get(2);
            }
        }
        if(account!=null&&ledger.contains("ACCOUNT",account))throw new SheetsGateway.Conflict();
        return new EntrySnapshot(tables,account,account==null?0:ids.get(account));
    }
    @Override public synchronized Profile findOrCreateForEntry(String issuer,String subject) {
        var snapshot=entrySnapshot(issuer,subject);
        if(snapshot.account()!=null)return snapshot.profile();
        // Existing creation path is retained; only the subsequent profile read is batched.
        String created=findOrCreateAccount(issuer,subject);
        var saved=entrySnapshot(issuer,subject);
        if(!created.equals(saved.account()))throw new SheetsGateway.Conflict();
        return saved.profile();
    }
    @Override public synchronized Profile completeEntryProfile(String issuer,String subject,String nickname,String email) {
        if(!AccountProfileRows.validNickname(nickname)||!AccountProfileRows.validEmail(email==null?"":email))
            throw new IllegalArgumentException("Invalid profile");
        var before=entrySnapshot(issuer,subject);
        if(before.account()==null) {
            // Legacy lobby linking may complete the profile without the entry-login step.
            String created=findOrCreateAccount(issuer,subject);
            before=entrySnapshot(issuer,subject);
            if(!created.equals(before.account()))throw new SheetsGateway.Conflict();
        }
        if(before.profile().nickname()!=null)return before.profile();
        var checked=entrySnapshot(issuer,subject);
        if(!before.tables().equals(checked.tables()))throw new SheetsGateway.Conflict();
        var desired=List.of(before.account(),"2",nickname,email==null?"":email);
        // Exactly one narrow write. Do not retry an unknown response, including failed readback.
        gateway.saveAccountProfile(before.row(),desired,null);
        var after=entrySnapshot(issuer,subject);
        if(!before.account().equals(after.account())||before.row()!=after.row())throw new SheetsGateway.Conflict();
        for(String tab:List.of("retention_ledger","erasure_ledger","auth_identities"))
            if(!before.tables().get(tab).equals(after.tables().get(tab)))throw new SheetsGateway.Conflict();
        var oldRows=before.tables().get("accounts");var saved=after.tables().get("accounts");
        if(saved.size()!=oldRows.size()||!saved.get(0).equals(AccountProfileRows.LIFETIME_HEADER.equals(oldRows.get(0))
                ?AccountProfileRows.LIFETIME_HEADER:AccountProfileRows.HEADER))throw new SheetsGateway.Conflict();
        for(int i=1;i<saved.size();i++) {
            if(i!=before.row()-1) {if(!oldRows.get(i).equals(saved.get(i)))throw new SheetsGateway.Conflict();continue;}
            var value=new ArrayList<>(saved.get(i));while(value.size()<4)value.add("");
            String priorLifetime=oldRows.get(i).size()==5?oldRows.get(i).get(4):"";
            if(!value.subList(0,4).equals(desired)||!priorLifetime.equals(value.size()==5?value.get(4):""))throw new SheetsGateway.Conflict();
        }
        return after.profile();
    }
    private String find(String issuer,String subject) {
        Set<String> accounts=new HashSet<>();
        for(List<String> row:gateway.read("accounts")){if(row.isEmpty()||row.get(0).equals("account_id"))continue;AccountProfileRows.validateRow(row);try{UUID.fromString(row.get(0));}catch(Exception e){throw new SheetsGateway.Conflict();}accounts.add(row.get(0));}
        String result=null;
        for(List<String> row:gateway.read("auth_identities")){
            if(row.isEmpty()||row.get(0).equals("issuer"))continue;
            if(row.size()!=4||!row.get(3).equals("1")||!accounts.contains(row.get(2)))throw new SheetsGateway.Conflict();
            if(row.get(0).equals(issuer)&&row.get(1).equals(subject)){if(result!=null&&!result.equals(row.get(2)))throw new SheetsGateway.Conflict();result=row.get(2);}
        }
        return result;
    }
}
