package com.bridge.quartercore;
import java.nio.charset.StandardCharsets;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Only an exact verified E snapshot may become downloadable. No uploads or grants. */
final class FixedEVerifiedArtifact {
 static final String PATH="/fixed-e/verified-baseline.json";
 static final int MAX_BYTES=2*1024*1024;
 private FixedEBaselineBundle completionBundle;private final String expectedToken;private boolean attempted;private volatile byte[] artifact;
 private FixedEVerifiedArtifact(String token){expectedToken=token;}
 static FixedEVerifiedArtifact completedRestart(){var a=new FixedEVerifiedArtifact(null);a.completionBundle=FixedEBaselineBundle.compiled();return a;}
 static FixedEVerifiedArtifact initial(){return new FixedEVerifiedArtifact(FixedERestartPreflight.INITIAL_TOKEN);}
 /** Local synthetic tests only; no HTTP/env token selection. */
 static FixedEVerifiedArtifact isolatedFixture(String token){return new FixedEVerifiedArtifact(token);}
 void verifyOnce(FixedERestartPreflight.FullReader reader){
  synchronized(this){if(attempted)throw new Rejected();attempted=true;}
  Book snapshot=Objects.requireNonNull(reader.read()).copy();
  if(completionBundle==null)FixedERestartPreflight.isolatedFixture(()->snapshot,expectedToken);else {int count=completionBundle.completedPairs(snapshot);if(count<1||count>3)throw new Rejected();}
  var b=FixedERestartClock.binding();var payload=new LinkedHashMap<String,Object>();
  payload.put("format","fixed-e-validated-normalized-projection-v1");
  payload.put("notice","Validated normalized native projection; not the original raw Google response. Not a write authorization.");
  payload.put("binding",Map.of("spreadsheetId",b.spreadsheetId(),"datasetId",b.datasetId(),"purpose",b.purpose(),"serviceAccountIssuer",b.serviceAccountIssuer()));
  payload.put("fullToken",JournalSessionAuthority.fullToken(snapshot));payload.put("nativeProjection",snapshot.nativeValue());
  byte[] encoded=json(payload).getBytes(StandardCharsets.UTF_8);if(encoded.length>MAX_BYTES)throw new Rejected();artifact=encoded;
 }
 byte[] response(String method,String rawPath,String rawQuery){byte[] ready=artifact;if(ready==null||!"GET".equals(method)||!PATH.equals(rawPath)||rawQuery!=null)return null;return ready.clone();}
}
