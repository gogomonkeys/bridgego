package com.bridge.quartercore;
import java.time.Duration;
import java.util.Objects;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Shared capability reference for the Handle and its existing bounded provider.
 * Replacement is atomic and closes the old capability. It is not an ownership lease. */
final class JournalAuthoritySlot implements AutoCloseable,GoogleDispatchAuthority {
  private JournalSessionAuthority current;
  JournalAuthoritySlot(JournalSessionAuthority authority){current=Objects.requireNonNull(authority);}
  synchronized JournalSessionAuthority current(){return current;}
  public synchronized void requireReadSession(GoogleSheetsBinding binding){current.requireReadSession(binding);}
  public synchronized void requireSession(GoogleSheetsBinding binding){current.requireSession(binding);}
  public synchronized void requireReady(String target){current.requireReady(target);}
  public synchronized JournalSessionAuthority.Dispatch beforeDispatch(String target,String wire){return current.beforeDispatch(target,wire);}
  synchronized void arm(JournalPlan plan){current.arm(plan);}
  synchronized void verified(Book fresh){current.verified(fresh);}
  synchronized boolean renewalDue(){return current.renewalDue();}
  synchronized void renew(Book fresh){current=current.renew(fresh,current.sessionDuration());}
  synchronized void renew(Book fresh,Duration duration){current=current.renew(fresh,duration);}
  public synchronized void closeUnknown(){current.closeUnknown();}
  @Override public synchronized void close(){current.close();}
}
