package com.bridge.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GoogleSheetsReplacementTest {
    @Test void oneAtomicBodyOnlyClearsVerifiedColumnsAndNeverEvaluatesStrings() {
        Map<String,List<List<Object>>> rows=new TreeMap<>();Map<String,Integer> ids=new TreeMap<>();int id=0;
        for(String tab:List.of("envelopes","commits","accounts","auth_identities","erasure_ledger")){rows.put(tab,List.of(List.of("=not-a-formula",3)));ids.put(tab,id++);}
        var body=new ObjectMapper().valueToTree(GoogleSheetsGateway.replacementBody(rows,ids));
        assertEquals(5,body.path("requests").size());
        for(var request:body.path("requests")) {
            var update=request.path("updateCells");assertEquals(0,update.path("range").path("startColumnIndex").asInt());assertEquals(5,update.path("range").path("endColumnIndex").asInt());assertEquals("userEnteredValue",update.path("fields").asText());
            var value=update.path("rows").get(0).path("values").get(0).path("userEnteredValue");assertEquals("=not-a-formula",value.path("stringValue").asText());assertFalse(value.has("formulaValue"));
        }
        rows.put("retention_ledger",List.of(List.of("ledger")));ids.put("retention_ledger",5);rows.put("quarterly_aggregates",List.of(List.of("aggregate")));ids.put("quarterly_aggregates",6);assertEquals(7,new ObjectMapper().valueToTree(GoogleSheetsGateway.replacementBody(rows,ids)).path("requests").size());
        rows.put("dataset",List.of(List.of("untouchable")));assertThrows(IllegalArgumentException.class,()->GoogleSheetsGateway.replacementBody(rows,ids));
    }
}
