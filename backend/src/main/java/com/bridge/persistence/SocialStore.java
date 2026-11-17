package com.bridge.persistence;
import java.util.List;
/** Server-only social persistence. DTO account identifiers must never be serialized to clients. */
public interface SocialStore {
  record Person(String accountId,String code,String nickname) {}
  record Request(String requestId,String accountId,String code,String nickname) {}
  record Snapshot(String ownCode,String nickname,List<Person> friends,List<Request> incoming,List<Request> outgoing) {}
  final class Rejected extends IllegalArgumentException {
    public final String code; public Rejected(String code){super(code);this.code=code;}
  }
  boolean enabled();
  Snapshot snapshot(String accountId);
  String ensureCode(String accountId);
  Snapshot request(String accountId,String targetCode,String actionId);
  Snapshot respond(String accountId,String requestId,boolean accept,String actionId);
  Snapshot remove(String accountId,String friendCode,String actionId);
}
