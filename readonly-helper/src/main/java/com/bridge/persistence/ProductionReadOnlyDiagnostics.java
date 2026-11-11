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
    WORKBOOK_HTTP, WORKBOOK_HEADERS, WORKBOOK_BODY, WORKBOOK_PARSE, WORKBOOK_JSON, ROOT_FIELDS, ROOT_BINDING, ROOT_METADATA, ROOT_TITLE, WORKBOOK_THEME, SHEET_COLLECTION, SHEET_FEATURES, SHEET_PROPERTIES, SHEET_GRID, GRID_BLOCK, GRID_DIMENSIONS, GRID_ROW, GRID_CELL, CELL_FEATURES, CELL_VALUE, CELL_FORMAT, CELL_INHERITED_FORMAT, GRID_COVERAGE, TABLE_HEADERS, TABLE_ROWS, DATASET_MARKER, NUMERIC_ROWS, LAYOUT_HASH, COMPLETE }
  enum ErrorType { NONE, REJECTED, HTTP_STATUS, TIMEOUT, INTERRUPTED, TLS, IO, LINKAGE }
  record Snapshot(Stage stage, ErrorType error) {}
  // Shared immutable vocabulary only. Mutable invocation state and failure snapshots are never shared.
  private static final java.util.List<Snapshot> STAGES=java.util.Arrays.stream(Stage.values()).map(s->new Snapshot(s,ErrorType.NONE)).toList();
  private final AtomicReference<Snapshot> state=new AtomicReference<>(STAGES.get(Stage.START.ordinal()));
  void mark(Stage stage){
    Snapshot next=STAGES.get(stage.ordinal()),old;
    do{old=state.get();if(old.error()!=ErrorType.NONE || old.stage()==stage)return;}while(!state.compareAndSet(old,next));
  }
  void fail(ErrorType error){state.updateAndGet(old->old.error()==ErrorType.NONE?new Snapshot(old.stage(),error):old);}
  void fail(Throwable failure){
    if(failure instanceof ExecutionException && failure.getCause()!=null)failure=failure.getCause();
    fail(failure instanceof TimeoutException || failure instanceof SocketTimeoutException ? ErrorType.TIMEOUT :
      failure instanceof InterruptedException ? ErrorType.INTERRUPTED : failure instanceof SSLException ? ErrorType.TLS :
      failure instanceof IOException ? ErrorType.IO : failure instanceof LinkageError ? ErrorType.LINKAGE : ErrorType.REJECTED);
  }
  Snapshot snapshot(){return state.get();}
}
