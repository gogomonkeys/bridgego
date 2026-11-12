package com.bridge.quartercore;

/** Immutable caller-authorized binding. Observed workbook contents never select this target or issuer. */
record GoogleSheetsBinding(String spreadsheetId,String datasetId,String purpose,String serviceAccountIssuer) {
  static final String TOKEN_ORIGIN="https://oauth2.googleapis.com", TOKEN_AUDIENCE=TOKEN_ORIGIN+"/token";
  static final String SHEETS_ORIGIN="https://sheets.googleapis.com";
  static final String SCOPE="https://www.googleapis.com/auth/spreadsheets";
  GoogleSheetsBinding {
    try {
      new ManualQuarterMaintenance.Binding(spreadsheetId,datasetId,purpose);
      if(serviceAccountIssuer==null||serviceAccountIssuer.length()>254||!serviceAccountIssuer.matches("[a-z0-9][a-z0-9._-]*@[a-z0-9][a-z0-9.-]*\\.iam\\.gserviceaccount\\.com"))throw new IllegalArgumentException();
    }catch(Exception rejected){throw new ManagedGoogleServiceAccount.Rejected();}
  }
  ManualQuarterMaintenance.Binding manualBinding(){return new ManualQuarterMaintenance.Binding(spreadsheetId,datasetId,purpose);}
  String path(){return "/v4/spreadsheets/"+spreadsheetId;}
  String fullTarget(){return path()+"?includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED";}
  @Override public String toString(){return "GoogleSheetsBinding[REDACTED]";}
}
