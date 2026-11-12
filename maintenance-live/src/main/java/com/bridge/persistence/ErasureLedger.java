package com.bridge.persistence;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Residual pseudonymous hashes retained solely to reject restoration of erased identifiers. */
final class ErasureLedger {
    static final List<String> HEADER=List.of("kind","identifier_hash","schema_version");
    static final List<String> SENTINEL=List.of("META","erasure-ledger-v1","1");
    private final Set<String> entries=new HashSet<>();
    ErasureLedger(List<List<String>> rows) {
        if(rows.size()<2 || !rows.get(0).equals(HEADER) || !rows.get(1).equals(SENTINEL))throw new SheetsGateway.Conflict();
        for(int i=2;i<rows.size();i++) { List<String> row=rows.get(i);
            if(row.size()!=3 || !Set.of("ACCOUNT","GUEST","IDENTITY").contains(row.get(0)) || !row.get(1).matches("[0-9a-f]{64}") || !row.get(2).equals("1"))throw new SheetsGateway.Conflict();
            entries.add(row.get(0)+":"+row.get(1));
        }
    }
    static String identityKey(String issuer,String subject) {return issuer+"\u0000"+subject;}
    static String digest(String kind,String id){return FinalMatchEnvelope.hash("bridge-erasure-v1\u0000"+kind+"\u0000"+id);}
    boolean contains(String kind,String id){return entries.contains(kind+":"+digest(kind,id));}
    void requireAllowed(FinalMatchEnvelope envelope) {
        for(JsonNode participant:envelope.payload().path("participants")) {
            for(String field:List.of("account_id","guest_id")) {
                JsonNode id=participant.path(field);
                if(id.isTextual() && contains(field.equals("account_id")?"ACCOUNT":"GUEST",id.asText()))throw new SheetsGateway.Conflict();
            }
        }
    }
}
