package com.bridge.quartercore;
import java.security.PublicKey;import java.time.Clock;import java.util.Objects;
/** One fixed public key, one current server-created challenge. HTTP cannot create cycles. */
final class CurrentSignedObservationRelay implements AutoCloseable {
 private final PublicKey pinned;private final Clock clock;private SignedObservationControlPlane current;private boolean closed;
 CurrentSignedObservationRelay(PublicKey pinned,Clock clock){this.pinned=Objects.requireNonNull(pinned);this.clock=Objects.requireNonNull(clock);}
 synchronized SignedObservationControlPlane begin(SignedObservationControlPlane.Context context){if(closed)throw new NativeSheetsModel.Rejected();if(current!=null)current.close();return current=new SignedObservationControlPlane(pinned,context,clock);}
 synchronized SignedObservationControlPlane current(){if(closed||current==null)throw new NativeSheetsModel.Rejected();return current;}
 public synchronized void close(){closed=true;if(current!=null)current.close();}
}
