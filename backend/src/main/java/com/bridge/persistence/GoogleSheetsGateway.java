package com.bridge.persistence;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** No credentials, response bodies, or player identifiers are logged. */
public final class GoogleSheetsGateway implements SheetsGateway {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String spreadsheet,email; private final PrivateKey key;
    private final BoundedGoogleBody boundedBody=new BoundedGoogleBody();
    private volatile String token; private long expires; private Map<String,Integer> sheetIds;
    public GoogleSheetsGateway(String spreadsheet,String credentials) {
        try {
            if(spreadsheet==null||!spreadsheet.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            this.spreadsheet=spreadsheet;
            JsonNode c=JSON.readTree(credentials); email=c.path("client_email").asText();
            if(!c.path("type").asText().equals("service_account")||email.isBlank())throw new IllegalArgumentException();
            String pem=c.path("private_key").asText().replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
            key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        }catch(Exception e){throw new IllegalArgumentException("Invalid server Sheets configuration");}
    }
    private synchronized String token() {
        if(token!=null&&System.currentTimeMillis()<expires)return token;
        try {
            long now=Instant.now().getEpochSecond();
            String signing=b64(JSON.writeValueAsBytes(Map.of("alg","RS256","typ","JWT")))+"."+b64(JSON.writeValueAsBytes(Map.of("iss",email,"scope","https://www.googleapis.com/auth/spreadsheets","aud","https://oauth2.googleapis.com/token","iat",now,"exp",now+3600)));
            Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(key);signer.update(signing.getBytes(StandardCharsets.US_ASCII));
            String jwt=signing+"."+b64(signer.sign());
            HttpRequest req=HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="+jwt)).build();
            JsonNode result=send(req); token=result.path("access_token").asText(); if(token.isEmpty())throw new Failure(401,0);
            expires=System.currentTimeMillis()+Math.max(1,result.path("expires_in").asLong(3600)-60)*1000;return token;
        }catch(Failure e){throw e;}catch(Exception e){throw new Failure(0,0);}
    }
    private static String b64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private JsonNode request(String path,Object body) {
        try {
            HttpRequest.Builder req=HttpRequest.newBuilder(URI.create("https://sheets.googleapis.com/v4/spreadsheets/"+spreadsheet+path)).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token());
            if(body!=null)req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))); else req.GET();
            return send(req.build());
        }catch(Failure e){throw e;}catch(Exception e){throw new Failure(0,0);}
    }
    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<java.io.InputStream> response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());int status=response.statusCode();
            if(status<200||status>=300) {
                boundedBody.read(response.body(),64*1024,25000);
                long retry=0;try {String v=response.headers().firstValue("Retry-After").orElse("0");try{retry=Long.parseLong(v)*1000;}catch(NumberFormatException ex){retry=Math.max(0,java.time.ZonedDateTime.parse(v,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis());}}catch(Exception ignored){}
                throw new Failure(status,retry);
            }
            return JSON.readTree(boundedBody.read(response.body(),4*1024*1024,25000));
        }catch(Failure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(0,0);}catch(Exception e){throw new Failure(0,0);}
    }
    /** Dedicated experiment adapter only; no arbitrary URLs, field masks or ranges. */
    byte[] experimentWorkbook() {
        try {
            HttpRequest req=HttpRequest.newBuilder(URI.create("https://sheets.googleapis.com/v4/spreadsheets/"+spreadsheet+"?includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED"))
                .timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token()).header("Accept","application/json").header("Accept-Encoding","identity").GET().build();
            HttpResponse<java.io.InputStream> response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
            try {
                if(response.statusCode()!=200 || !response.uri().equals(req.uri())
                    || !response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?")
                    || !Set.of("","identity").contains(response.headers().firstValue("Content-Encoding").orElse("")))throw new Failure(response.statusCode(),0);
                long declared=response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if(declared>4*1024*1024)throw new Failure(0,0);
                byte[] bytes=boundedBody.read(response.body(),4*1024*1024,25000);
                if(bytes.length>4*1024*1024 || declared>=0 && declared!=bytes.length)throw new Failure(0,0);
                return bytes; // Strict experiment parser receives original bytes, including duplicate-key evidence.
            } catch(RuntimeException invalid) {BoundedGoogleBody.closeLater(response.body());throw invalid;}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(0,0);}
        catch(Failure e){throw e;}catch(Exception e){throw new Failure(0,0);}
    }
    void experimentBatch(Map<String,Object> body){request(":batchUpdate",body);}
    public List<List<String>> read(String tab) {
        if(!Set.of("envelopes","commits","accounts","auth_identities","erasure_ledger","dataset","retention_ledger","quarterly_aggregates").contains(tab))throw new IllegalArgumentException();
        JsonNode values=request("/values/"+tab+"!A:E?valueRenderOption=UNFORMATTED_VALUE",null).path("values");
        List<List<String>> result=new ArrayList<>();
        for(JsonNode row:values){List<String> r=new ArrayList<>();row.forEach(v->r.add(v.asText()));result.add(r);}return result;
    }
    public synchronized void replaceAtomic(Map<String,List<List<Object>>> rows) {
        ensureSheetIds();request(":batchUpdate",replacementBody(rows,sheetIds));
    }
    static Map<String,Object> replacementBody(Map<String,List<List<Object>>> rows,Map<String,Integer> sheetIds) {
        if(!rows.keySet().equals(Set.of("envelopes","commits","accounts","auth_identities","erasure_ledger")) && !rows.keySet().equals(Set.of("envelopes","commits","accounts","auth_identities","erasure_ledger","retention_ledger","quarterly_aggregates")))throw new IllegalArgumentException("Whole erasure dataset required");
        List<Object> requests=new ArrayList<>();
        for(var entry:rows.entrySet()) {
            Integer id=sheetIds.get(entry.getKey());if(id==null)throw new Failure(400,0);
            List<Object> data=new ArrayList<>();
            for(List<Object> row:entry.getValue()) {
                List<Object> cells=new ArrayList<>();
                for(Object value:row) { Map<String,Object> typed=value instanceof Number?Map.of("numberValue",value):value instanceof Boolean?Map.of("boolValue",value):Map.of("stringValue",value==null?"":value.toString());cells.add(Map.of("userEnteredValue",typed)); }
                data.add(Map.of("values",cells));
            }
            // Only the A:E schema range is read, fingerprinted, and replaced. Never clear F onward.
            requests.add(Map.of("updateCells",Map.of("range",Map.of("sheetId",id,"startColumnIndex",0,"endColumnIndex",5),"rows",data,"fields","userEnteredValue")));
        }
        return Map.of("requests",requests);
    }
    private void ensureSheetIds() {
        if(sheetIds==null){Map<String,Integer> ids=new HashMap<>();for(JsonNode sheet:request("?fields=sheets.properties",null).path("sheets"))ids.put(sheet.path("properties").path("title").asText(),sheet.path("properties").path("sheetId").asInt());sheetIds=ids;}
    }
    @Override public synchronized void saveAccountProfile(int row, List<String> account, List<String> identity) {
        if (row < 0 || row == 1 || account.size() != 4 || !"2".equals(account.get(1))
            || (row == 0 ? identity == null || identity.size() != 4 : identity != null)) throw new IllegalArgumentException();
        ensureSheetIds();
        request(":batchUpdate", profileBody(row, account, identity, sheetIds));
    }
    /** Pure narrow write builder, tested without credentials or network. */
    static Map<String,Object> profileBody(int row, List<String> account, List<String> identity, Map<String,Integer> sheetIds) {
        if (row < 0 || row == 1 || account.size() != 4 || !"2".equals(account.get(1))
            || (row == 0 ? identity == null || identity.size() != 4 : identity != null)) throw new IllegalArgumentException();
        int accountSheet = sheetIds.get("accounts");
        List<Object> requests = new ArrayList<>();
        requests.add(Map.of("updateCells", Map.of("start",Map.of("sheetId",accountSheet,"rowIndex",0,"columnIndex",2),
            "rows",List.of(profileRow(List.of("nickname","email"))),"fields","userEnteredValue")));
        if (row == 0) {
            requests.add(Map.of("appendCells",Map.of("sheetId",accountSheet,"rows",List.of(profileRow(account)),"fields","userEnteredValue")));
            requests.add(Map.of("appendCells",Map.of("sheetId",sheetIds.get("auth_identities"),"rows",List.of(profileRow(identity)),"fields","userEnteredValue")));
        } else requests.add(Map.of("updateCells",Map.of("start",Map.of("sheetId",accountSheet,"rowIndex",row-1,"columnIndex",0),
            "rows",List.of(profileRow(account)),"fields","userEnteredValue")));
        return Map.of("requests",requests);
    }
    private static Map<String,Object> profileRow(List<String> values) {
        // Explicit stringValue prevents spreadsheet formula injection from user nicknames.
        return Map.of("values",values.stream().map(v->Map.of("userEnteredValue",Map.of("stringValue",v))).toList());
    }
    public synchronized void appendAtomic(Map<String,List<List<Object>>> rows) {
        ensureSheetIds();
        List<Object> requests=new ArrayList<>();
        for(var entry:rows.entrySet()) {
            Integer id=sheetIds.get(entry.getKey());if(id==null)throw new Failure(400,0);
            List<Object> data=new ArrayList<>();
            for(List<Object> row:entry.getValue()){
                List<Object> cells=new ArrayList<>();for(Object value:row){Map<String,Object> typed=value instanceof Number?Map.of("numberValue",value):value instanceof Boolean?Map.of("boolValue",value):Map.of("stringValue",value==null?"":value.toString());cells.add(Map.of("userEnteredValue",typed));}data.add(Map.of("values",cells));
            }
            requests.add(Map.of("appendCells",Map.of("sheetId",id,"rows",data,"fields","userEnteredValue")));
        }
        request(":batchUpdate",Map.of("requests",requests));
    }
}
