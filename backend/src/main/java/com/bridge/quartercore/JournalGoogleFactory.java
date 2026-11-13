package com.bridge.quartercore;

import java.time.Duration;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** Actual bounded Google journal sink factory, inert unless a trusted source-cutover
 * provider has provisioned a session authority. No environment flag creates that authority.
 * Existing read-only factory and BootArm paths are unchanged. No startup/bootstrap writes.
 */
public final class JournalGoogleFactory {
  private JournalGoogleFactory() {}
  public record Configuration(boolean enabled,String spreadsheetId,String datasetId,String purpose,String issuer,
      QuarterGoogleFactory.CredentialSource credentials) {
    public Configuration(String spreadsheetId,String datasetId,String purpose,String issuer,QuarterGoogleFactory.CredentialSource credentials) {
      this(false,spreadsheetId,datasetId,purpose,issuer,credentials);
    }
    @Override public String toString(){return "JournalGoogleConfiguration[REDACTED]";}
  }
  public static Handle open(Configuration configuration,JournalSessionAuthority authority) {
    if(configuration==null||!configuration.enabled()||authority==null)throw new Rejected();
    GoogleSheetsBinding binding=new GoogleSheetsBinding(configuration.spreadsheetId(),configuration.datasetId(),configuration.purpose(),configuration.issuer());
    authority.requireSession(binding); // Before TLS initialization, credential reads, DNS or sockets.
    if(configuration.credentials()==null)throw new Rejected();JournalGoogleOAuthExchange.requireSilentTls();
    var identity=ManagedGoogleServiceAccount.load(configuration.credentials()::open,binding);
    try{
      var slot=new JournalAuthoritySlot(authority);
      var provider=JournalGoogleOAuthExchange.production(binding,identity,SharedSheetsBudget.HOST,slot);
      try{return new Handle(new BatchedSheetsTransport(443,SharedSheetsBudget.HOST,provider,binding.manualBinding()),provider,slot,binding);}
      catch(RuntimeException|Error bad){provider.close();throw bad;}
    }catch(RuntimeException|Error bad){identity.close();authority.closeUnknown();throw bad;}
  }
  public static final class Handle implements AutoCloseable {
    private final NarrowSheetsTransport delegate;private final AutoCloseable provider;private final JournalAuthoritySlot authority;
    private final GoogleSheetsBinding binding;private final JournalPlanTransport view;private boolean closed;
    // Package-private no-network composition seam for deterministic local tests.
    Handle(NarrowSheetsTransport delegate,AutoCloseable provider,JournalSessionAuthority authority,GoogleSheetsBinding binding) {
      this(delegate,provider,new JournalAuthoritySlot(authority),binding);
    }
    Handle(NarrowSheetsTransport delegate,AutoCloseable provider,JournalAuthoritySlot authority,GoogleSheetsBinding binding) {
      this.delegate=Objects.requireNonNull(delegate);this.provider=Objects.requireNonNull(provider);this.authority=Objects.requireNonNull(authority);this.binding=Objects.requireNonNull(binding);
      view=new JournalPlanTransport(){
        public Scope operation(String label,NarrowQuarterPoc.Root predecessor,Set<String> tabs,Duration duration){readyRead();return delegate.operation(label,predecessor,tabs,duration);}
        public Book snapshot(){readyRead();return delegate.snapshot();}
        public List<Metadata> control(){readyRead();return delegate.control();}
        public List<List<String>> related(String tab){readyRead();return delegate.related(tab);}
        public boolean unsupportedStructure(){readyRead();return delegate.unsupportedStructure();}
        public void batch(String wire){throw new Rejected();}
        public boolean renewalDue(){readyRead();return authority.renewalDue();}
        public void renewSession(){
          readyRead();try{Book fresh=delegate.snapshot();authority.renew(fresh);}
          catch(RuntimeException|Error unknown){terminateUnknown();throw unknown;}
        }
        public void dispatchPlan(JournalPlan plan){
          ready();authority.arm(plan);
          try{plan.dispatchOnce(delegate::batch);Book fresh=delegate.snapshot();authority.verified(fresh);}
          catch(RuntimeException|Error unknown){authority.closeUnknown();throw unknown;}
        }
      };
    }
    JournalPlanTransport transport(){readyRead();return view;}
    private synchronized void readyRead(){
      if(closed)throw new Rejected();
      try{authority.requireReadSession(binding);}catch(RuntimeException|Error invalid){terminateUnknown();throw invalid;}
    }
    /** Failed revalidation is terminal; wipe provider credentials/cache without erasing UNKNOWN. */
    private synchronized void terminateUnknown(){
      if(closed)return;closed=true;authority.closeUnknown();
      try{if(delegate instanceof AutoCloseable c)c.close();}catch(Exception ignored){}
      finally{try{provider.close();}catch(Exception ignored){}}
    }
    private synchronized void ready(){if(closed)throw new Rejected();authority.requireSession(binding);}
    @Override public synchronized void close(){
      if(closed)return;closed=true;authority.close();boolean failed=false;
      try{if(delegate instanceof AutoCloseable c)c.close();}catch(Exception failure){failed=true;}
      finally{try{provider.close();}catch(Exception failure){failed=true;}}
      if(failed)throw new Rejected();
    }
    @Override public String toString(){return "JournalGoogleHandle[REDACTED]";}
  }
}
