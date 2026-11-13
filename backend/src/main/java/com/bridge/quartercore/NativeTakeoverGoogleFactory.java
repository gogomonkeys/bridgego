package com.bridge.quartercore;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Bounded Google sink for one control-only OPEN CAS takeover. No authority creation. */
final class NativeTakeoverGoogleFactory {
  private NativeTakeoverGoogleFactory(){}
  static JournalQuarterGoogleFactory.Handle open(JournalGoogleFactory.Configuration config,NativeTakeoverAuthority authority){
    if(config==null||!config.enabled()||authority==null)throw new Rejected();
    var binding=new GoogleSheetsBinding(config.spreadsheetId(),config.datasetId(),config.purpose(),config.issuer());
    authority.requireSession(binding); // before credentials, TLS, DNS or sockets
    if(config.credentials()==null)throw new Rejected();JournalGoogleOAuthExchange.requireSilentTls();
    var identity=ManagedGoogleServiceAccount.load(config.credentials()::open,binding);
    try{
      var provider=JournalGoogleOAuthExchange.production(binding,identity,SharedSheetsBudget.HOST,authority);
      try{return new JournalQuarterGoogleFactory.Handle(new BatchedSheetsTransport(443,SharedSheetsBudget.HOST,provider,binding.manualBinding()),provider,authority,binding);}
      catch(RuntimeException|Error failure){provider.close();throw failure;}
    }catch(RuntimeException|Error failure){identity.close();authority.closeUnknown();throw failure;}
  }
}
