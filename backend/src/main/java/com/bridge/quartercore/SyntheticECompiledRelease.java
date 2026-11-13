package com.bridge.quartercore;
import java.io.*;import java.time.*;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Fixed E only. Caller must explicitly wire this into a dedicated reviewed runner build. */
final class SyntheticECompiledRelease {
 static final String BASELINE="449dfb6c753ad3b22e73f25f7c424f4f96ceeef7789687caa50e25496e0b002c";
 static SyntheticQuarterRunner.Release load(){try{
  var b=new GoogleSheetsBinding("1n-CaTcmMVlTxr_NXYyuw6mAsrQZ3YdMPtou3bSKxKY8","879a3708-3bc4-4c76-9d9a-53883f71530a","bridgego-restricted-test","bridgego-sheets@bridgego-513805.iam.gserviceaccount.com");
  byte[] before=resource("before-native.json","c1364240997d60a85adc9bb015a5edb4a548205619cf290ad971e997ef431fdc");
  var book=StrictNativeSheetsResource.decodeFull(new ByteArrayInputStream(before),System.nanoTime()+20_000_000_000L,b.manualBinding());
  if(!JournalSessionAuthority.fullToken(book).equals(BASELINE))throw new Rejected();NativeCommitJournal.verify(b.manualBinding(),book,Clock.systemUTC());
  var json=JSON.readTree(resource("fixed-envelopes.json","163aac86746440a8c34df7fcb5cc1cde7b15a778e315e479d3042abac6d195fa"));
  return new SyntheticQuarterRunner.Release(b,BASELINE,"synthetic-e-fixed-20261113","user-approved-isolated-E-quarter-test-20261113",Instant.parse("2026-11-13T00:00:00Z"),Instant.parse("2026-12-31T16:00:01Z"),read(json.get("old")),read(json.get("new")),"806191524609716f219f4ba43303f77c34c4a6de6d3609f38e25689ef8ed1ce3");
 }catch(Exception e){throw new Rejected();}}
 private static byte[]resource(String name,String expected)throws Exception{try(var in=SyntheticECompiledRelease.class.getResourceAsStream("/synthetic-e/"+name)){if(in==null)throw new Rejected();byte[] bytes=in.readNBytes(8_388_609);if(bytes.length>8_388_608||!HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new Rejected();return bytes;}}
 private static List<com.bridge.persistence.FinalMatchEnvelope>read(com.fasterxml.jackson.databind.JsonNode nodes){var out=new ArrayList<com.bridge.persistence.FinalMatchEnvelope>();for(var e:nodes)out.add(new com.bridge.persistence.FinalMatchEnvelope(e.get("commitId").asText(),e.get("matchId").asText(),e.get("revision").asInt(),e.get("canonicalJson").asText(),e.get("payloadHash").asText()));return List.copyOf(out);}
}
