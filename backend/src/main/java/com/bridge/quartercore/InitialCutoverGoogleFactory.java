package com.bridge.quartercore;
import java.time.Duration;import java.nio.charset.StandardCharsets;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Disabled by default configuration; no environment lookup, retry or generic bootstrap writer. */
final class InitialCutoverGoogleFactory {
 private InitialCutoverGoogleFactory(){}
 static Handle open(JournalGoogleFactory.Configuration config,InitialCutoverAuthority authority){
  if(config==null||!config.enabled()||authority==null)throw new Rejected();
  var binding=new GoogleSheetsBinding(config.spreadsheetId(),config.datasetId(),config.purpose(),config.issuer());
  authority.requireSession(binding);if(config.credentials()==null)throw new Rejected();JournalGoogleOAuthExchange.requireSilentTls();
  var identity=ManagedGoogleServiceAccount.load(config.credentials()::open,binding);
  try{var provider=JournalGoogleOAuthExchange.production(binding,identity,SharedSheetsBudget.HOST,authority);
   try{return new Handle(new BatchedSheetsTransport(443,SharedSheetsBudget.HOST,provider,binding.manualBinding()),provider,binding,authority);}
   catch(RuntimeException|Error bad){provider.close();throw bad;}
  }catch(RuntimeException|Error bad){identity.close();authority.closeUnknown();throw bad;}
 }
 static final class Handle implements AutoCloseable {
  private final BatchedSheetsTransport transport;private final AutoCloseable provider;private final GoogleSheetsBinding binding;private final InitialCutoverAuthority authority;private boolean closed,attempted;
  Handle(BatchedSheetsTransport tx,AutoCloseable owner,GoogleSheetsBinding b,InitialCutoverAuthority a){transport=Objects.requireNonNull(tx);provider=Objects.requireNonNull(owner);binding=Objects.requireNonNull(b);authority=Objects.requireNonNull(a);a.requireSession(b);}
  Book execute(){synchronized(this){if(closed||attempted)throw new Rejected();attempted=true;}
   try(var scope=transport.operation("initial-cutover",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){
    return authority.execute(transport::readLegacyFull,this::dispatch,transport::snapshot);
   }finally{close();}
  }
  private void dispatch(String wire){
   byte[] bytes=wire.getBytes(StandardCharsets.UTF_8);
   try{if(bytes.length==0||bytes.length>BatchedSheetsTransport.MAX_REQUEST_BYTES)throw new Rejected();
    var requests=BoundedSheetsProjection.MAPPER.readTree(bytes).path("requests");if(!requests.isArray()||requests.isEmpty())throw new Rejected();
    // Only control metadata changes plus the existing unchanged ledger-header anchor guard.
    // The exact plan also proves every data cell remains identical.
    for(var request:requests)if(request.size()!=1||!(request.has("createDeveloperMetadata")||request.has("updateDeveloperMetadata")||NativeCommitJournal.canonical(request).equals(authority.unchangedAnchorGuard())))throw new Rejected();
    int count=requests.size();
    transport.invoke(true,binding.path()+":batchUpdate",bytes,BatchedSheetsTransport.MAX_BATCH_RESPONSE_BYTES,transport.operationDeadline(),input->{
     byte[] response=BatchedSheetsTransport.smallBody(input,BatchedSheetsTransport.MAX_BATCH_RESPONSE_BYTES);
     try{BatchedSheetsTransport.validateBatchReply(response,binding.spreadsheetId(),count);return Boolean.TRUE;}finally{Arrays.fill(response,(byte)0);}
    });
   }catch(java.io.IOException e){authority.closeUnknown();throw new Rejected();}finally{Arrays.fill(bytes,(byte)0);}
  }
  public void close(){synchronized(this){if(closed)return;closed=true;}authority.closeUnknown();try{transport.close();}finally{try{provider.close();}catch(Exception ignored){}}}
 }
}
