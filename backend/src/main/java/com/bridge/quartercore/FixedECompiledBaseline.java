package com.bridge.quartercore;
import java.util.*;import java.nio.charset.StandardCharsets;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Fixed synthetic E normalized artifact, not a raw Google response or runtime upload. */
final class FixedECompiledBaseline {
 static final String RESOURCE="/synthetic-e/restart-verified-baseline.json";
 static final String SHA256="6643d9ad0f59c9884d0fa3f28bce7afc1656eddce492c86abc7fa0041598275d";
 static Book snapshot(){try(var in=FixedECompiledBaseline.class.getResourceAsStream(RESOURCE)){if(in==null)throw new Rejected();return decode(in.readNBytes(FixedEVerifiedArtifact.MAX_BYTES+1));}catch(java.io.IOException e){throw new Rejected();}}
 static Book decode(byte[] bytes){return decodePinned(bytes,SHA256,FixedERestartPreflight.INITIAL_TOKEN);}
 static Book decodePinned(byte[] bytes,String expectedSha,String expectedToken){
  if(bytes==null||bytes.length>FixedEVerifiedArtifact.MAX_BYTES||!expectedSha.equals(FinalMatchEnvelope.hash(new String(bytes,StandardCharsets.UTF_8))))throw new Rejected();
  var artifact=parse(new String(bytes,StandardCharsets.UTF_8));var binding=artifact.path("binding");var fixed=FixedERestartClock.binding();
  if(!fixed.spreadsheetId().equals(text(binding,"spreadsheetId"))||!fixed.datasetId().equals(text(binding,"datasetId"))||!fixed.purpose().equals(text(binding,"purpose"))||!fixed.serviceAccountIssuer().equals(text(binding,"serviceAccountIssuer"))||!expectedToken.equals(text(artifact,"fullToken"))||!"fixed-e-validated-normalized-projection-v1".equals(text(artifact,"format")))throw new Rejected();
  var projection=artifact.path("nativeProjection");Book book=new Book();
  for(var sheet:projection.path("sheets")){var p=sheet.path("properties");int id=integer(p,"sheetId");var data=sheet.path("data");if(data.size()!=1)throw new Rejected();var s=new Sheet(id,text(p,"title"),rows(data.get(0).path("rowData")));s.rowCount=integer(p.path("gridProperties"),"rowCount");s.columnCount=integer(p.path("gridProperties"),"columnCount");if(book.sheets.put(id,s)!=null)throw new Rejected();for(var m:sheet.path("developerMetadata")){var md=metadata(m);if(book.metadata.put(md.id(),md)!=null)throw new Rejected();}}
  for(var m:projection.path("developerMetadata")){var md=metadata(m);if(book.metadata.put(md.id(),md)!=null)throw new Rejected();}
  if(!NativeCommitJournal.canonical(projection).equals(NativeCommitJournal.canonical(book.nativeValue())))throw new Rejected();
  FixedERestartPreflight.isolatedFixture(()->book,expectedToken);return book;
 }
 private FixedECompiledBaseline(){}
}
