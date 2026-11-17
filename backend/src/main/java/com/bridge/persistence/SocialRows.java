package com.bridge.persistence;
import java.util.*;
/** Optional five-column current social state plus idempotency receipts. Never stores email or credentials. */
public final class SocialRows {
 public static final List<String> HEADER=List.of("kind","account_id","peer_or_key","state_or_value","revision");
 public static final int MAX_ROWS=1000;
 private SocialRows(){}
 public static void validate(List<List<String>> rows,Set<String> accounts){
  if(rows==null||rows.isEmpty()||rows.size()>MAX_ROWS||!rows.get(0).equals(HEADER))throw new SheetsGateway.Conflict();
  Set<String> keys=new HashSet<>(),codes=new HashSet<>(),codedAccounts=new HashSet<>(),requestIds=new HashSet<>();
  for(var row:rows.subList(1,rows.size())){
   if(row.size()!=5||!accounts.contains(row.get(1))||!keys.add(row.get(0)+":"+row.get(1)+":"+row.get(2)))throw new SheetsGateway.Conflict();
   switch(row.get(0)){
    case "CODE" -> {codedAccounts.add(row.get(1));if(!row.get(2).equals("code")||!row.get(3).matches("[A-F0-9]{16}")||!codes.add(row.get(3))||!row.get(4).equals("1"))throw new SheetsGateway.Conflict();}
    case "EDGE" -> {if(!accounts.contains(row.get(2))||row.get(1).equals(row.get(2))||!Set.of("PENDING","FRIEND").contains(row.get(3))||!uuid(row.get(4))||!requestIds.add(row.get(4)))throw new SheetsGateway.Conflict();}
    case "OP" -> {if(!uuid(row.get(2))||!row.get(3).matches("[a-f0-9]{64}")||!row.get(4).equals("1"))throw new SheetsGateway.Conflict();}
    default -> throw new SheetsGateway.Conflict();
   }
  }
  Set<String> pairs=new HashSet<>();
  for(var row:rows)if(row.get(0).equals("EDGE")){
   if(!codedAccounts.contains(row.get(1))||!codedAccounts.contains(row.get(2)))throw new SheetsGateway.Conflict();
   String pair=row.get(1).compareTo(row.get(2))<0?row.get(1)+":"+row.get(2):row.get(2)+":"+row.get(1);
   if(!pairs.add(pair))throw new SheetsGateway.Conflict();
  }
 }
 public static boolean uuid(String s){try{return UUID.fromString(s).toString().equals(s);}catch(RuntimeException e){return false;}}
 public static List<List<String>> erase(List<List<String>> rows,String id){return rows.stream().filter(r->r.equals(HEADER)||!r.get(1).equals(id)&&!(r.get(0).equals("EDGE")&&r.get(2).equals(id))).toList();}
}
