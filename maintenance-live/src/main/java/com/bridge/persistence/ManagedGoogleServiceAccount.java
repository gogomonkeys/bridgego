package com.bridge.persistence;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.*;

/** Same-process, explicitly supplied service-account JSON only. No environment, ADC, metadata,
 * delegated subject, refresh-token, subprocess, secret logging or implicit credential discovery. */
final class ManagedGoogleServiceAccount implements AutoCloseable {
  static final int MAX_BYTES=64*1024;
  enum Access {READ_ONLY("https://www.googleapis.com/auth/spreadsheets.readonly"),SYNTHETIC_WRITE(GoogleSheetsBinding.SCOPE),MAINTENANCE_WRITE(GoogleSheetsBinding.SCOPE);final String scope;Access(String scope){this.scope=scope;}}
  static final class Rejected extends RuntimeException {Rejected(){super("MANAGED_GOOGLE_REJECTED",null,false,false);}}
  @FunctionalInterface interface Source {InputStream open()throws IOException;}
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_BYTES).maxNestingDepth(4)
          .maxStringLength(20000).maxNameLength(128).maxNumberLength(20).maxTokenCount(128).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> FIELDS=Set.of("type","project_id","private_key_id","private_key","client_email","client_id","auth_uri","token_uri","auth_provider_x509_cert_url","client_x509_cert_url","universe_domain");
  private final String issuer;
  private PrivateKey key;
  private ManagedGoogleServiceAccount(String issuer,PrivateKey key){this.issuer=issuer;this.key=key;}
  /** Defers all file access until the enabled factory invokes the source. Never follows a final symlink. */
  static Source explicitFile(Path path){
    return ()->{if(path==null)throw new Rejected();BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
      if(!a.isRegularFile()||a.size()<1||a.size()>MAX_BYTES)throw new Rejected();
      return Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);};
  }
  static ManagedGoogleServiceAccount load(Source source,GoogleSheetsBinding binding){
    byte[] bytes=null,encoded=null;PrivateKey parsed=null;
    try{
      if(source==null||binding==null)throw new Rejected();
      try(InputStream input=source.open()){if(input==null)throw new Rejected();bytes=input.readNBytes(MAX_BYTES+1);}
      if(bytes.length<1||bytes.length>MAX_BYTES)throw new Rejected();JsonNode root=JSON.readTree(bytes);
      if(root==null||!root.isObject())throw new Rejected();root.fieldNames().forEachRemaining(n->{if(!FIELDS.contains(n))throw new Rejected();});
      if(!"service_account".equals(text(root,"type"))||!GoogleSheetsBinding.TOKEN_AUDIENCE.equals(text(root,"token_uri"))
          ||!binding.serviceAccountIssuer().equals(text(root,"client_email")))throw new Rejected();
      exact(root,"universe_domain","googleapis.com");exact(root,"auth_uri","https://accounts.google.com/o/oauth2/auth");exact(root,"auth_provider_x509_cert_url","https://www.googleapis.com/oauth2/v1/certs");
      for(String field:List.of("project_id","private_key_id","client_id"))if(root.has(field)){
        String value=text(root,field);if(value.length()>256||!value.matches("[A-Za-z0-9_-]+"))throw new Rejected();}
      if(root.has("client_x509_cert_url")){
        String actual=text(root,"client_x509_cert_url"),prefix="https://www.googleapis.com/robot/v1/metadata/x509/",email=binding.serviceAccountIssuer();
        if(!actual.equals(prefix+email)&&!actual.equals(prefix+email.replace("@","%40")))throw new Rejected();}
      String pem=text(root,"private_key");if(pem.length()>16384||!pem.matches("-----BEGIN PRIVATE KEY-----\\r?\\n[A-Za-z0-9+/=\\r\\n]+-----END PRIVATE KEY-----(?:\\r?\\n)?"))throw new Rejected();
      String raw=pem.substring("-----BEGIN PRIVATE KEY-----".length(),pem.indexOf("-----END PRIVATE KEY-----")).replace("\r","").replace("\n","");
      encoded=Base64.getDecoder().decode(raw);parsed=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
      if(!(parsed instanceof RSAPrivateKey rsa)||rsa.getModulus().bitLength()<2048||rsa.getModulus().bitLength()>8192)throw new Rejected();
      ManagedGoogleServiceAccount result=new ManagedGoogleServiceAccount(binding.serviceAccountIssuer(),parsed);parsed=null;return result;
    }catch(Exception|LinkageError rejected){throw new Rejected();}
    finally{wipe(bytes);wipe(encoded);destroy(parsed);}
  }
  synchronized byte[] assertionBody(Clock realUtc,Access access){
    byte[] signing=null,signature=null;
    try{
      if(key==null||realUtc==null||access==null)throw new Rejected();long now=realUtc.instant().getEpochSecond();
      String prefix=b64(JSON.writeValueAsBytes(Map.of("alg","RS256","typ","JWT")))+"."+b64(JSON.writeValueAsBytes(Map.of("iss",issuer,"scope",access.scope,"aud",GoogleSheetsBinding.TOKEN_AUDIENCE,"iat",now,"exp",Math.addExact(now,300))));
      signing=prefix.getBytes(StandardCharsets.US_ASCII);Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(key);signer.update(signing);signature=signer.sign();
      return ("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="+prefix+"."+b64(signature)).getBytes(StandardCharsets.US_ASCII);
    }catch(Exception|LinkageError rejected){throw new Rejected();}finally{wipe(signing);wipe(signature);}
  }
  private static String text(JsonNode node,String field){JsonNode value=node.get(field);if(value==null||!value.isTextual())throw new Rejected();return value.textValue();}
  private static void exact(JsonNode root,String field,String expected){if(root.has(field)&&!expected.equals(text(root,field)))throw new Rejected();}
  private static String b64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
  static void wipe(byte[] bytes){if(bytes!=null)Arrays.fill(bytes,(byte)0);}
  private static void destroy(PrivateKey key){if(key!=null)try{key.destroy();}catch(Exception ignored){}}
  @Override public synchronized void close(){PrivateKey old=key;key=null;destroy(old);}
  @Override public String toString(){return "ManagedGoogleServiceAccount[REDACTED]";}
}
