package com.bridge.persistence;

import java.time.*;
import java.util.*;

/** Non-personal durable deletion watermark. Missing/corrupt metadata fails closed. */
public final class RetentionLedger {
    public static final List<String> HEADER=List.of("quarter","cutoff_utc","state","completed_at","schema_version");
    private Instant cutoff=Instant.MIN;
    private boolean complete;
    public RetentionLedger(List<List<String>> rows) {
        if(rows==null||rows.isEmpty()||!rows.get(0).equals(HEADER))throw new SheetsGateway.Conflict();
        Set<String> quarters=new HashSet<>();
        for(List<String> row:rows.subList(1,rows.size()))try {
            if(row.size()!=5||!row.get(4).equals("1")||!quarters.add(row.get(0)))throw new IllegalArgumentException();
            Instant boundary=Instant.parse(row.get(1));
            if(!QuarterlyRetention.currentQuarterStart(boundary).equals(boundary)||!QuarterlyRetention.quarter(boundary).equals(row.get(0)))throw new IllegalArgumentException();
            boolean done=row.get(2).equals("COMPLETE");
            if(!done&&!row.get(2).equals("PENDING"))throw new IllegalArgumentException();
            if(done){if(Instant.parse(row.get(3)).isBefore(boundary))throw new IllegalArgumentException();}
            else if(!row.get(3).isEmpty())throw new IllegalArgumentException();
            if(boundary.isAfter(cutoff)){cutoff=boundary;complete=done;}
        }catch(RuntimeException bad){throw new SheetsGateway.Conflict();}
    }
    public Instant cutoff(){return cutoff;}
    public boolean complete(){return complete;}
    public void requireAllowed(FinalMatchEnvelope e){if(QuarterlyRetention.finishedAt(e).isBefore(cutoff))throw new SheetsGateway.Conflict();}
}
