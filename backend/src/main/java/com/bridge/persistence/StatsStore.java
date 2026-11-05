package com.bridge.persistence;
import java.util.List;
public interface StatsStore {
    enum SaveStatus { UNKNOWN, DISABLED, PENDING, CONFIRMED, CONFLICT, FAILED, REJECTED }
    SaveStatus submit(FinalMatchEnvelope envelope);
    SaveStatus status(String commitId);
    List<FinalMatchEnvelope> exportCommitted();
}
