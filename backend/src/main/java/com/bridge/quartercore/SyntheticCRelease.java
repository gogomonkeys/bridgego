package com.bridge.quartercore;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Immutable release target. No target/baseline/authority may come from HTTP, args or env. */
final class SyntheticCRelease {
  static final SyntheticCRelease BUNDLED=new SyntheticCRelease(); // Deliberately unprovisioned.
  static final String ISSUER="bridgego-sheets@bridgego-513805.iam.gserviceaccount.com";
  private static final Set<String> EXCLUDED=Set.of(
    "1jGIsjBfJYm5DnL2EIrW4m1o4YM-5f0I9w2bpMzhMVaU","1fueMlf5u-JqeUMgeo3X_Bpjbbg2alN7SaISq7FL8rac",
    "1viNnpXYkbKoI5fesJEbaVF46D-6D71UIgjaGpTBjMk4","1GrUtidlZVAaEbD9MHEeXAvjCnkseAcHD2iin1mUjZO0",
    "1_AqJ_-jXFNSzUZhsTylnXqTmIbtL-PHA1VDd23tV5p0","1Bss2GLYazdFczRwHFnx700sibaW6JAtpfVpKmbdUJ_I",
    "1IcS-E08MvJ9owehoh_RgMesjlJk2kvLsVBplJwtSHcg","1hm2CKBAlbTMRVHPZ-7_nTcjGbdb2PNvCgn9Uq_4YY2s");
  final GoogleSheetsBinding binding;final String baselineToken,revision,run,beforeArtifactSha;
  private SyntheticCRelease(){binding=null;baselineToken="";beforeArtifactSha="";revision="UNPROVISIONED";run="UNPROVISIONED";}
  SyntheticCRelease(GoogleSheetsBinding binding,String baselineToken,String revision,String run){this(binding,baselineToken,revision,run,baselineToken);}
  SyntheticCRelease(GoogleSheetsBinding binding,String baselineToken,String revision,String run,String beforeArtifactSha){
    if(binding==null||EXCLUDED.contains(binding.spreadsheetId())||!binding.purpose().equals("bridgego-restricted-test")||!ISSUER.equals(binding.serviceAccountIssuer())
        ||baselineToken==null||!baselineToken.matches("[0-9a-f]{64}")||revision==null||!revision.matches("[A-Za-z0-9._-]{1,96}")||run==null||!run.matches("[A-Za-z0-9_-]{1,64}"))throw new Rejected();
    if(beforeArtifactSha==null||!beforeArtifactSha.matches("[0-9a-f]{64}"))throw new Rejected();
    this.beforeArtifactSha=beforeArtifactSha;this.binding=binding;this.baselineToken=baselineToken;this.revision=revision;this.run=run;
  }
  GoogleSheetsBinding require(){if(binding==null)throw new Rejected();return binding;}
  void verify(Book book){require();if(!JournalSessionAuthority.fullToken(book).equals(baselineToken))throw new Rejected();}
  /** Immutable equivalent full-native before fixture is bundled before any credential read. */
  void requireSavedBefore(){
    require();try(var input=SyntheticCRelease.class.getResourceAsStream("/synthetic-c/before-native.json")){
      if(input==null)throw new Rejected();byte[] bytes=input.readNBytes(8_388_609);if(bytes.length>8_388_608)throw new Rejected();
      String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));if(!sha.equals(beforeArtifactSha))throw new Rejected();
      var book=StrictNativeSheetsResource.decodeFull(new java.io.ByteArrayInputStream(bytes),System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos(),binding.manualBinding());verify(book);
      NativeCommitJournal.verify(binding.manualBinding(),book,java.time.Clock.systemUTC());
    }catch(Exception failure){throw new Rejected();}
  }
  @Override public String toString(){return "SyntheticCRelease[REDACTED]";}
}
