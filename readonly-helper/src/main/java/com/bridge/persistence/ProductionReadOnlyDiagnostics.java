package com.bridge.persistence;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

/** Finite diagnostic vocabulary only: no values, messages, paths, headers or exception class names. */
final class ProductionReadOnlyDiagnostics {
  enum Stage { START, CONFIGURATION, CREDENTIAL_LOAD, CREDENTIAL_VALIDATION, CLIENT_SETUP,
    TOKEN_SIGNING, TOKEN_HTTP, TOKEN_HEADERS, TOKEN_BODY, TOKEN_PARSE,
    WORKBOOK_HTTP, WORKBOOK_HEADERS, WORKBOOK_BODY, WORKBOOK_PARSE, COMPLETE }
  enum ErrorType { NONE, REJECTED, HTTP_STATUS, TIMEOUT, INTERRUPTED, TLS, IO, LINKAGE }
  record Snapshot(Stage stage, ErrorType error) {}
  private final AtomicReference<Snapshot> state=new AtomicReference<>(new Snapshot(Stage.START,ErrorType.NONE));
  void mark(Stage stage){state.updateAndGet(old->old.error()==ErrorType.NONE?new Snapshot(stage,ErrorType.NONE):old);}
  void fail(ErrorType error){state.updateAndGet(old->old.error()==ErrorType.NONE?new Snapshot(old.stage(),error):old);}
  void fail(Throwable failure){
    if(failure instanceof ExecutionException && failure.getCause()!=null)failure=failure.getCause();
    fail(failure instanceof TimeoutException || failure instanceof SocketTimeoutException ? ErrorType.TIMEOUT :
      failure instanceof InterruptedException ? ErrorType.INTERRUPTED : failure instanceof SSLException ? ErrorType.TLS :
      failure instanceof IOException ? ErrorType.IO : failure instanceof LinkageError ? ErrorType.LINKAGE : ErrorType.REJECTED);
  }
  Snapshot snapshot(){return state.get();}
}
