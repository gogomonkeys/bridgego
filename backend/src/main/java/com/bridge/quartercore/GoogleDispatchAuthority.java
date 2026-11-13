package com.bridge.quartercore;
/** Minimal bounded HTTP authorization seam; no provisioning or renewal API. */
interface GoogleDispatchAuthority {
  void requireReadSession(GoogleSheetsBinding binding);
  void requireSession(GoogleSheetsBinding binding);
  void requireReady(String exactTarget);
  Dispatch beforeDispatch(String exactTarget,String exactWire);
  void closeUnknown();
  interface Dispatch { void acknowledged();void unknown(); }
}
