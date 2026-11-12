package com.bridge.quartercore;

import java.util.Set;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Extracted dependency only: unprovisioned, no bundled live target or live write authority. */
final class MaintenanceRelease {
  static final String ISSUER="bridgego-sheets@bridgego-513805.iam.gserviceaccount.com";
  private static final Set<String> EXCLUDED=Set.of("1jGIsjBfJYm5DnL2EIrW4m1o4YM-5f0I9w2bpMzhMVaU","1fueMlf5u-JqeUMgeo3X_Bpjbbg2alN7SaISq7FL8rac","1viNnpXYkbKoI5fesJEbaVF46D-6D71UIgjaGpTBjMk4","1GrUtidlZVAaEbD9MHEeXAvjCnkseAcHD2iin1mUjZO0","1_AqJ_-jXFNSzUZhsTylnXqTmIbtL-PHA1VDd23tV5p0","1Bss2GLYazdFczRwHFnx700sibaW6JAtpfVpKmbdUJ_I","1IcS-E08MvJ9owehoh_RgMesjlJk2kvLsVBplJwtSHcg");
  static final MaintenanceRelease BUNDLED=new MaintenanceRelease();
  private final GoogleSheetsBinding binding;
  final String run,baselineSha256;
  private MaintenanceRelease(){binding=null;run="UNPROVISIONED";baselineSha256="";}
  // Package-private fixture seam, never populated from arguments, environment or remote content.
  MaintenanceRelease(GoogleSheetsBinding binding,String run,String baselineSha256){
    if(binding==null||EXCLUDED.contains(binding.spreadsheetId())||!ISSUER.equals(binding.serviceAccountIssuer())
        ||!"bridgego-restricted-test".equals(binding.purpose())||run==null||!run.matches("[A-Za-z0-9_-]{1,64}")
        ||baselineSha256==null||!baselineSha256.matches("[0-9a-f]{64}"))throw BootArm.denied();
    this.binding=binding;this.run=run;this.baselineSha256=baselineSha256;
  }
  GoogleSheetsBinding requireProvisionedBinding(){if(binding==null)throw BootArm.denied();return binding;}
  GoogleSheetsBinding requireLiveBinding(){throw BootArm.denied();} // Only the immutable shipped release, never a synthetic instance.
  void requireBaseline(Book book){requireProvisionedBinding();if(!baselineSha256.equals(digest(book)))throw BootArm.denied();}
  // Release seals cross JVM boundaries. Immutable Map iteration order is randomized by JVM;
  // recursively sort object keys while retaining arrays and literal types before hashing.
  static String digest(Book book){return hash(canonical(JSON.valueToTree(book.nativeValue())));}
  private static Object canonical(com.fasterxml.jackson.databind.JsonNode value){
    if(value.isObject()){var out=new java.util.TreeMap<String,Object>();value.fields().forEachRemaining(e->out.put(e.getKey(),canonical(e.getValue())));return out;}
    if(value.isArray()){var out=new java.util.ArrayList<Object>();value.forEach(v->out.add(canonical(v)));return out;}
    if(value.isTextual())return value.textValue();if(value.isIntegralNumber())return value.bigIntegerValue();
    if(value.isBoolean())return value.booleanValue();if(value.isNull())return null;
    throw BootArm.denied();
  }
  @Override public String toString(){return "MaintenanceRelease[REDACTED]";}
}
