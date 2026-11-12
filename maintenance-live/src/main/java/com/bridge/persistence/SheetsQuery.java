package com.bridge.persistence;
import java.util.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
final class SheetsQuery {
  static Map<String,List<String>> query(String query){if(query==null)throw new IllegalArgumentException();Map<String,List<String>> result=new HashMap<>();for(String pair:query.split("&")){String[] kv=pair.split("=",2);if(kv.length!=2)throw new IllegalArgumentException();result.computeIfAbsent(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),k->new ArrayList<>()).add(URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}return result;}
}
