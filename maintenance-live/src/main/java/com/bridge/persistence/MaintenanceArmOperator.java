package com.bridge.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.bridge.persistence.NativeSheetsModel.*;
import static com.bridge.persistence.NarrowQuarterPoc.*;

/** Pure local payload generator, not a sender. A process latch is NOT durable operator state. */
final class MaintenanceArmOperator {
  private final String capturedDescriptorSha256;
  // Copy-integrity only, not attestation: capture must be from the verified current host private log.
  MaintenanceArmOperator(String capturedDescriptorSha256){if(capturedDescriptorSha256==null||!capturedDescriptorSha256.matches("[0-9a-f]{64}"))throw BootArm.denied();this.capturedDescriptorSha256=capturedDescriptorSha256;}
  private static final java.util.concurrent.atomic.AtomicBoolean EMITTED=new java.util.concurrent.atomic.AtomicBoolean();
  static String descriptor(MaintenanceRelease release,BootArm.Plan plan,String nonce){
    release.requireProvisionedBinding();return json(Map.of("baselineSha256",release.baselineSha256,"owner",plan.quarter().sourceRoot().owner(),"businessAt",plan.quarter().createdAt().toString(),"arm",parse(plan.expectedArm(nonce).value())));
  }
  synchronized String generateOnce(MaintenanceRelease release,Book approvedBaseline,String descriptor,Instant now){
    if(EMITTED.get())throw BootArm.denied();
    var binding=release.requireProvisionedBinding();release.requireBaseline(approvedBaseline);
    try{
      if(descriptor==null||descriptor.length()>2048||now==null||!capturedDescriptorSha256.equals(FinalMatchEnvelope.hash(descriptor)))throw BootArm.denied();
      JsonNode outer=BoundedSheetsProjection.MAPPER.readTree(descriptor);
      keys(outer,Set.of("baselineSha256","owner","businessAt","arm"));
      if(!release.baselineSha256.equals(text(outer,"baselineSha256")))throw BootArm.denied();
      JsonNode arm=outer.path("arm");keys(arm,Set.of("version","run","stage","spreadsheet","dataset","purpose","bootNonce","rootSha256","businessSha256","planSha256","issuedAt","expiresAt"));
      if(integer(arm,"version")!=1||!"A".equals(text(arm,"stage"))||!release.run.equals(text(arm,"run"))
          ||!binding.spreadsheetId().equals(text(arm,"spreadsheet"))||!binding.datasetId().equals(text(arm,"dataset"))||!binding.purpose().equals(text(arm,"purpose")))throw BootArm.denied();
      for(String key:List.of("bootNonce","rootSha256","businessSha256","planSha256"))if(!text(arm,key).matches("[0-9a-f]{64}"))throw BootArm.denied();
      Instant issued=Instant.parse(text(arm,"issuedAt")),expires=Instant.parse(text(arm,"expiresAt"));
      if(now.isBefore(issued)||!now.isBefore(expires)||!expires.isAfter(issued)||Duration.between(issued,expires).compareTo(Duration.ofSeconds(360))>0)throw BootArm.denied();
      Metadata metadata=new Metadata(10001,"test.arm.A",json(arm),null);
      if(metadata.characters()>BootArm.MAX_ARM_CHARS)throw BootArm.denied();
      Instant businessAt=Instant.parse(text(outer,"businessAt"));
      if(!businessAt.equals(issued)||now.isBefore(businessAt)||Duration.between(businessAt,now).compareTo(Duration.ofSeconds(60))>0)throw BootArm.denied();
      ascii(text(outer,"owner"),40);
      if(!hash(root(new ArrayList<>(approvedBaseline.metadata.values()))).equals(text(arm,"rootSha256"))
          ||!hash(new TreeMap<>(approvedBaseline.tables())).equals(text(arm,"businessSha256")))throw BootArm.denied();
      // The random reducer plan ID cannot be reconstructed from baseline. The exact plan digest
      // remains trusted host-descriptor input and is checked again by the source BootArm.
      if(!EMITTED.compareAndSet(false,true))throw BootArm.denied(); // Process-local, not durable submit evidence.
      return wire(List.of(create(metadata)));
    }catch(Exception failure){throw BootArm.denied();}
  }
}
