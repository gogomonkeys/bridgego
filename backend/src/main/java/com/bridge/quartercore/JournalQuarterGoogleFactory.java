package com.bridge.quartercore;
import java.time.Duration;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Separate maintenance route using the unchanged bounded Google HTTP implementation.
 * No grant provisioning, environment permission, ordinary-authority conversion or bootstrap. */
final class JournalQuarterGoogleFactory {
  private JournalQuarterGoogleFactory(){}
  static Handle open(JournalGoogleFactory.Configuration config,JournalQuarterAuthority grant){
    if(config==null||!config.enabled()||grant==null)throw new Rejected();
    var binding=new GoogleSheetsBinding(config.spreadsheetId(),config.datasetId(),config.purpose(),config.issuer());
    var channel=new QuarterAuthorityChannel(binding,grant); // Before TLS, credentials, DNS or sockets.
    if(config.credentials()==null)throw new Rejected();JournalGoogleOAuthExchange.requireSilentTls();
    var identity=ManagedGoogleServiceAccount.load(config.credentials()::open,binding);
    try{
      var provider=JournalGoogleOAuthExchange.production(binding,identity,SharedSheetsBudget.HOST,channel);
      try{return new Handle(new BatchedSheetsTransport(443,SharedSheetsBudget.HOST,provider,binding.manualBinding()),provider,channel,binding);}
      catch(RuntimeException|Error failure){provider.close();throw failure;}
    }catch(RuntimeException|Error failure){identity.close();channel.closeUnknown();throw failure;}
  }
  static final class Handle implements AutoCloseable {
    private final NarrowSheetsTransport delegate;private final AutoCloseable provider;private final GooglePlanAuthority channel;
    private final GoogleSheetsBinding binding;private final JournalPlanTransport view;private boolean closed;
    Handle(NarrowSheetsTransport delegate,AutoCloseable provider,GooglePlanAuthority channel,GoogleSheetsBinding binding){
      this.delegate=Objects.requireNonNull(delegate);this.provider=Objects.requireNonNull(provider);this.channel=Objects.requireNonNull(channel);this.binding=Objects.requireNonNull(binding);
      view=new JournalPlanTransport(){
        public Scope operation(String label,NarrowQuarterPoc.Root root,Set<String> tabs,Duration budget){readable();return delegate.operation(label,root,tabs,budget);}
        public Book snapshot(){readable();return delegate.snapshot();}
        public List<Metadata> control(){readable();return delegate.control();}
        public List<List<String>> related(String tab){readable();return delegate.related(tab);}
        public boolean unsupportedStructure(){readable();return delegate.unsupportedStructure();}
        public void batch(String wire){throw new Rejected();}
        public void dispatchPlan(JournalPlan plan){
          readable();try{channel.arm(plan);plan.dispatchOnce(delegate::batch);channel.finishDispatch();}
          catch(RuntimeException|Error unknown){terminate();throw unknown;}
        }
      };
    }
    JournalPlanTransport transport(){readable();return view;}
    private synchronized void readable(){if(closed)throw new Rejected();try{channel.requireReadSession(binding);}catch(RuntimeException|Error invalid){terminate();throw invalid;}}
    private synchronized void terminate(){if(closed)return;closed=true;channel.closeUnknown();try{if(delegate instanceof AutoCloseable c)c.close();}catch(Exception ignored){}finally{try{provider.close();}catch(Exception ignored){}}}
    @Override public synchronized void close(){terminate();}
    @Override public String toString(){return "JournalQuarterGoogleHandle[REDACTED]";}
  }
}
