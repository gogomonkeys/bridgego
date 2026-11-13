package com.bridge.quartercore;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Fixed, non-sensitive, per-flight observation only. No exception text, response data or identifiers.
 * Only the waiting caller publishes finish(). Late workers and cleanup cannot change its result.
 * Native structure errors intentionally share FULL_NATIVE/REJECTED, not guessed field reasons. */
final class TransportDiagnostic {
  enum Endpoint { LOCAL, TOKEN, SHEETS }
  enum Phase { ADMISSION, EXCHANGE, ACQUIRE, CONNECT, TLS, REQUEST, HTTP_STATUS, HTTP_HEADERS, HTTP_BODY, TOKEN_JSON, FULL_NATIVE, PROJECTION, BATCH_REPLY, COMPLETE }
  enum Code { REJECTED, IO, TIMEOUT, CANCELLED, STATUS_3XX, STATUS_401, STATUS_403, STATUS_429, STATUS_4XX, STATUS_5XX, STATUS_OTHER, INVALID_JSON, UNEXPECTED_FIELDS, TARGET_MISMATCH, COUNT_MISMATCH, TYPE_MISMATCH, UNEXPECTED_RESOURCE, COMMENT_STATE }
  record Failure(Endpoint endpoint,Phase phase,Code code) {
    Failure { Objects.requireNonNull(endpoint);Objects.requireNonNull(phase);Objects.requireNonNull(code); }
    String label(){return endpoint.name()+"_"+phase.name()+"_"+code.name();}
  }
  private final long deadline;private long phaseDeadline;
  private Endpoint endpoint=Endpoint.LOCAL;private Phase phase=Phase.ADMISSION;
  private Failure failure;private String terminal;
  TransportDiagnostic(long deadline){this.deadline=deadline;this.phaseDeadline=deadline;}
  synchronized void enter(Endpoint next,Phase step){enter(next,step,deadline);}
  synchronized void enter(Endpoint next,Phase step,long limit){if(terminal==null&&failure==null){endpoint=Objects.requireNonNull(next);phase=Objects.requireNonNull(step);phaseDeadline=limit;}}
  synchronized void cancel(){fail(endpoint,phase,Code.CANCELLED);}
  synchronized void fail(Throwable cause){fail(endpoint,phase,code(cause));}
  synchronized void fail(Endpoint where,Phase step,Throwable cause,long limit){fail(where,step,code(cause,limit));}
  synchronized void fail(Endpoint where,Phase step,Code code){if(terminal==null&&failure==null)failure=new Failure(where,step,code);}
  synchronized String finish(boolean success,Throwable cause){
    if(terminal==null){if(!success&&failure==null)fail(cause);terminal=failure==null?"NONE":failure.label();}
    return terminal;
  }
  private Code code(Throwable cause){return code(cause,phaseDeadline);}
  private Code code(Throwable cause,long limit){
    if(cause instanceof InterruptedException||Thread.currentThread().isInterrupted())return Code.CANCELLED;
    if(cause instanceof TimeoutException||cause instanceof SocketTimeoutException||(deadline-System.nanoTime()<=0||limit-System.nanoTime()<=0))return Code.TIMEOUT;
    if(cause instanceof BatchedSheetsTransport.BatchReplyRejected rejected)return rejected.reason;
    return cause instanceof IOException&&!(cause instanceof com.fasterxml.jackson.core.JsonProcessingException)?Code.IO:Code.REJECTED;
  }
  static Code status(int status){return switch(status){case 401->Code.STATUS_401;case 403->Code.STATUS_403;case 429->Code.STATUS_429;default->switch(status/100){case 3->Code.STATUS_3XX;case 4->Code.STATUS_4XX;case 5->Code.STATUS_5XX;default->Code.STATUS_OTHER;};};}
  InputStream body(InputStream input,Endpoint where){return body(input,where,deadline);}
  InputStream body(InputStream input,Endpoint where,long limit){return new FilterInputStream(input){
    @Override public int read()throws IOException{try{return in.read();}catch(IOException|RuntimeException failure){fail(where,Phase.HTTP_BODY,failure,limit);throw failure;}}
    @Override public int read(byte[] bytes,int offset,int length)throws IOException{try{return in.read(bytes,offset,length);}catch(IOException|RuntimeException failure){fail(where,Phase.HTTP_BODY,failure,limit);throw failure;}}
    @Override public void close()throws IOException{try{super.close();}catch(IOException|RuntimeException failure){fail(where,Phase.HTTP_BODY,failure,limit);throw failure;}}
  };}
}
