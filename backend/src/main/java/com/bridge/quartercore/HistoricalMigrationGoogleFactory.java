package com.bridge.quartercore;
import java.time.Duration;import java.nio.charset.StandardCharsets;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Disabled by default configuration; no environment lookup, retry or generic bootstrap writer. */
final class HistoricalMigrationGoogleFactory {
 private HistoricalMigrationGoogleFactory(){}
 static Handle open(JournalGoogleFactory.Configuration config,HistoricalMigrationAuthority authority){
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
  private final BatchedSheetsTransport transport;private final AutoCloseable provider;private final GoogleSheetsBinding binding;private final HistoricalMigrationAuthority authority;private boolean closed,attempted;
  Handle(BatchedSheetsTransport tx,AutoCloseable owner,GoogleSheetsBinding b,HistoricalMigrationAuthority a){transport=Objects.requireNonNull(tx);provider=Objects.requireNonNull(owner);binding=Objects.requireNonNull(b);authority=Objects.requireNonNull(a);a.requireSession(b);}
  Book execute(){synchronized(this){if(closed||attempted)throw new Rejected();attempted=true;}
   try(var scope=transport.operation("historical-migration",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){
    return authority.execute(transport::snapshot,transport::batch,transport::snapshot);
   }finally{close();}
  }
  public void close(){synchronized(this){if(closed)return;closed=true;}authority.closeUnknown();try{transport.close();}finally{try{provider.close();}catch(Exception ignored){}}}
 }
}
