package com.bridge.quartercore;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Process-shared rolling budget. Not an account/project-wide quota authority. */
final class SharedSheetsBudget {
  static final SharedSheetsBudget HOST=new SharedSheetsBudget(System::nanoTime,54,54,Duration.ofMinutes(1));
  final LongSupplier clock;
  final int readLimit,writeLimit;
  final long window;
  private final ArrayDeque<Long> reads=new ArrayDeque<>(),writes=new ArrayDeque<>();
  private final ArrayDeque<Long> maintenanceTokens=new ArrayDeque<>();
  private final java.util.EnumMap<ManagedGoogleServiceAccount.Access,Long> lastToken=new java.util.EnumMap<>(ManagedGoogleServiceAccount.Access.class);
  private int tokenTotal;
  synchronized void reserveMaintenanceToken(ManagedGoogleServiceAccount.Access access){
    long now=clock.getAsLong(),hour=Duration.ofHours(1).toNanos(),minute=Duration.ofMinutes(1).toNanos();
    while(!maintenanceTokens.isEmpty()&&now-maintenanceTokens.peekFirst()>=hour)maintenanceTokens.removeFirst();
    Long previous=lastToken.get(access);
    if(maintenanceTokens.size()>=12||previous!=null&&now-previous<minute)throw new ManagedGoogleOAuthExchange.Failure();
    maintenanceTokens.addLast(now);lastToken.put(access,now);tokenTotal++;
  }
  synchronized int maintenanceTokenAttempts(){return tokenTotal;}
  private long cooldownUntil;
  private boolean cooldown,unboundedCooldown;
  SharedSheetsBudget(LongSupplier clock,int readLimit,int writeLimit,Duration window){
    if(clock==null||readLimit<1||readLimit>54||writeLimit<1||writeLimit>54||window==null||window.isNegative()||window.isZero()||window.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException();
    this.clock=clock;this.readLimit=readLimit;this.writeLimit=writeLimit;this.window=window.toNanos();
  }
  synchronized void reserve(boolean write){
    long now=clock.getAsLong();prune(reads,now);prune(writes,now);
    var q=write?writes:reads;int max=write?writeLimit:readLimit;
    if(unboundedCooldown||cooldown&&cooldownUntil-now>0||q.size()>=max)throw new BatchedSheetsTransport.Deferred();
    q.addLast(now); // Failed and unknown dispatched attempts still consume their reservation.
  }
  private void prune(ArrayDeque<Long> q,long now){while(!q.isEmpty()&&now-q.peekFirst()>=window)q.removeFirst();}
  synchronized void throttle(String retryAfter){
    long seconds=60;
    if(retryAfter!=null){
      if(!retryAfter.matches("[0-9]{1,5}")){unboundedCooldown=true;return;}
      seconds=Math.max(5,Long.parseLong(retryAfter));
      if(seconds>86400){unboundedCooldown=true;return;} // unsupported duration stays blocked, never shortened
    }
    long until=clock.getAsLong()+Duration.ofSeconds(seconds).toNanos();
    if(!cooldown||until-cooldownUntil>0)cooldownUntil=until;cooldown=true;
  }
  synchronized long delayNanos(){long now=clock.getAsLong();return unboundedCooldown?Long.MAX_VALUE:cooldown?Math.max(0,cooldownUntil-now):0;}
}
