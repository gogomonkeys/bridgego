package com.bridge.quartercore;
import java.util.Objects;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** HTTP dispatch gate for only the exact plan produced by one quarter grant. */
final class QuarterAuthorityChannel implements GooglePlanAuthority {
  private final GoogleSheetsBinding binding;private final JournalQuarterAuthority authority;
  private JournalPlan armed;private boolean sent,acknowledged,closed;
  QuarterAuthorityChannel(GoogleSheetsBinding binding,JournalQuarterAuthority authority){this.binding=Objects.requireNonNull(binding);this.authority=Objects.requireNonNull(authority);requireSession(binding);}
  public synchronized void arm(JournalPlan plan){requireSession(binding);authority.requireInFlight(plan);if(armed!=null)throw new Rejected();armed=plan;sent=false;acknowledged=false;}
  public synchronized void requireReadSession(GoogleSheetsBinding requested){if(closed||!binding.equals(requested))throw new Rejected();authority.requireTransport(binding.manualBinding(),true);}
  public synchronized void requireSession(GoogleSheetsBinding requested){if(closed||!binding.equals(requested))throw new Rejected();authority.requireTransport(binding.manualBinding(),false);}
  public synchronized void requireReady(String target){requireSession(binding);if(armed==null||sent||!target.equals(binding.path()+":batchUpdate"))throw new Rejected();authority.requireInFlight(armed);}
  public synchronized Dispatch beforeDispatch(String target,String wire){
    requireReady(target);if(!armed.wire().equals(wire))throw new Rejected();sent=true;
    return new Dispatch(){private boolean done;
      public void acknowledged(){synchronized(QuarterAuthorityChannel.this){if(done||closed||!sent)throw new Rejected();requireSession(binding);done=true;acknowledged=true;}}
      public void unknown(){synchronized(QuarterAuthorityChannel.this){if(done)return;done=true;closeUnknown();}}
    };
  }
  public synchronized void finishDispatch(){requireSession(binding);if(!sent||!acknowledged||armed==null)throw new Rejected();armed=null;}
  public synchronized void closeUnknown(){closed=true;armed=null;if(authority.state()!=JournalQuarterAuthority.State.COMPLETE)authority.unknown();}
}
