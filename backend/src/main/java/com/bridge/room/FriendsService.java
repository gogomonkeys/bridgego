package com.bridge.room;

import com.bridge.persistence.SocialStore;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Single-instance social coordination. Heartbeats and short-lived invitations never write Sheets. */
@Service
public class FriendsService {
  public record Friend(String friendCode,String nickname,String presence,boolean joinable,boolean sameRoom) {}
  public record Request(String requestId,String friendCode,String nickname,String direction) {}
  public record Invitation(String inviteId,String inviterNickname,String roomName,String expiresAt,String roomId) {}
  public record Peer(String playerId,String nickname,boolean eligible,String relation) {}
  public record View(boolean enabled,String friendCode,List<Friend> friends,List<Request> requests,
      List<Invitation> invites,int pendingCount,List<Peer> tablePlayers) {}
  private record Seen(String account,String token,long time) {}
  private record Invite(String id,String actor,String target,String actorToken,String nickname,
      String roomId,String roomName,long expires) {}
  private record Receipt(String signature,Object result,long time) {}
  private final RoomService rooms;
  private final SocialStore store;
  private final Clock clock;
  private final Map<String,Seen> seen = new HashMap<>();
  private final Map<String,Invite> invites = new LinkedHashMap<>();
  private final Map<String,Receipt> receipts = new LinkedHashMap<>();
  private final Map<String,Deque<Long>> actions = new HashMap<>();
  static final long ONLINE_MS=75_000, INVITE_MS=60_000;
  @org.springframework.beans.factory.annotation.Autowired
  public FriendsService(RoomService rooms,SocialStore store) { this(rooms,store,Clock.systemUTC()); }
  FriendsService(RoomService rooms,SocialStore store,Clock clock) {
    this.rooms=rooms;this.store=store;this.clock=clock;
  }
  private String account(RoomService.Identity who) {
    String account=rooms.accountId(who);
    if(account==null) throw RoomService.fail(403,"FRIENDS_LOGIN_REQUIRED","Sign in with Google to use friends");
    if(!store.enabled()) throw RoomService.fail(503,"FRIENDS_UNAVAILABLE","Friends are unavailable");
    return account;
  }
  private void sweep() {
    long now=clock.millis();
    seen.values().removeIf(s->now-s.time()>ONLINE_MS || !rooms.socialSessionLinked(s.token(),s.account()));
    invites.values().removeIf(i->i.expires()<=now || !rooms.socialSessionLinked(i.actorToken(),i.actor()));
    receipts.values().removeIf(r->now-r.time()>300_000);
    actions.values().forEach(q->q.removeIf(t->now-t>=60_000)); actions.values().removeIf(Deque::isEmpty);
  }
  private String presence(String account) {
    if(seen.values().stream().noneMatch(s->s.account().equals(account))) return "OFFLINE";
    return rooms.socialPlaying(account) ? "IN_GAME" : "ONLINE";
  }
  private void rate(String account) {
    sweep();
    if(actions.size()>=1024&&!actions.containsKey(account))
      throw RoomService.fail(429,"FRIENDS_RATE_LIMITED","Try again later");
    var queue=actions.computeIfAbsent(account,k->new ArrayDeque<>());
    if(queue.size()>=12) throw RoomService.fail(429,"FRIENDS_RATE_LIMITED","Try again later");
    queue.add(clock.millis());
  }
  private String key(String account,String actionId) {
    try { UUID.fromString(actionId); } catch(Exception e) {
      throw RoomService.fail(400,"INVALID_ACTION_ID","Action identifier required");
    }
    return account+":"+actionId;
  }
  private Object previous(String key,String signature) {
    Receipt r=receipts.get(key);
    if(r==null) return null;
    if(!r.signature().equals(signature)) throw RoomService.fail(409,"ACTION_CONFLICT","Action already used");
    return r.result();
  }
  private void receipt(String key,String signature,Object value) {
    if(receipts.size()>=2048) receipts.remove(receipts.keySet().iterator().next());
    receipts.put(key,new Receipt(signature,value,clock.millis()));
  }
  public synchronized View read(RoomService.Identity who) {
    String account=account(who); sweep();
    if(seen.size()<1024||seen.containsKey(who.token()))
      seen.put(who.token(),new Seen(account,who.token(),clock.millis()));
    var s=store.snapshot(account);
    var friends=s.friends().stream().map(p->new Friend(p.code(),p.nickname(),presence(p.accountId()),!presence(p.accountId()).equals("OFFLINE") && rooms.socialJoinableRoom(p.accountId())!=null,rooms.socialPeers(who).stream().anyMatch(peer->p.accountId().equals(peer.accountId())))).toList();
    var requests=new ArrayList<Request>();
    s.incoming().forEach(r->requests.add(new Request(r.requestId(),r.code(),r.nickname(),"INCOMING")));
    s.outgoing().forEach(r->requests.add(new Request(r.requestId(),r.code(),r.nickname(),"OUTGOING")));
    var notifications=invites.values().stream().filter(i->i.target().equals(account))
        .filter(i->s.friends().stream().anyMatch(p->p.accountId().equals(i.actor())))
        .map(i->new Invitation(i.id(),i.nickname(),i.roomName(),Instant.ofEpochMilli(i.expires()).toString(),i.roomId())).toList();
    var peers=rooms.socialPeers(who).stream().map(p->{
      boolean eligible=p.accountId()!=null&&!p.accountId().equals(account);
      String relation=s.friends().stream().anyMatch(f->f.accountId().equals(p.accountId()))?"FRIEND":
          java.util.stream.Stream.concat(s.incoming().stream(),s.outgoing().stream())
              .anyMatch(r->r.accountId().equals(p.accountId()))?"PENDING":"NONE";
      return new Peer(p.playerId(),p.nickname(),eligible,relation);
    }).toList();
    return new View(true,s.ownCode(),friends,List.copyOf(requests),notifications,s.incoming().size(),peers);
  }
  public synchronized View initialize(RoomService.Identity who,String actionId) {
    String a=account(who); key(a,actionId);rate(a);store.ensureCode(a);return read(who);
  }
  public synchronized View request(RoomService.Identity who,String code,String roomId,String playerId,String actionId) {
    String a=account(who);key(a,actionId);rate(a);
    if(code!=null&&(roomId!=null||playerId!=null)) throw RoomService.fail(400,"INVALID_FRIEND_TARGET","Choose one target");
    if(store.snapshot(a).ownCode()==null) throw RoomService.fail(409,"FRIENDS_NOT_INITIALIZED","Open friends first");
    if(code==null) code=store.ensureCode(rooms.socialTarget(who,roomId,playerId));
    store.request(a,code.trim().toUpperCase(Locale.ROOT),actionId);return read(who);
  }
  public synchronized View respond(RoomService.Identity who,String requestId,boolean accept,String actionId) {
    String a=account(who);key(a,actionId);rate(a);store.respond(a,requestId,accept,actionId);return read(who);
  }
  public synchronized View remove(RoomService.Identity who,String code,String actionId) {
    String a=account(who);key(a,actionId);rate(a);store.remove(a,code,actionId);return read(who);
  }
  public synchronized View invite(RoomService.Identity who,String code,String roomId,String actionId) {
    String a=account(who),key=key(a,actionId),signature="invite:"+code+":"+roomId;
    sweep();if(previous(key,signature)!=null)return read(who);rate(a);
    var s=store.snapshot(a);
    var target=s.friends().stream().filter(p->p.code().equals(code)).findFirst()
        .orElseThrow(()->RoomService.fail(403,"FRIENDS_NOT_FRIENDS","Friend required"));
    if(rooms.socialPeers(who).stream().anyMatch(p->target.accountId().equals(p.accountId())))
      throw RoomService.fail(409,"FRIENDS_SAME_ROOM","Friend is already in your room");
    String roomName=rooms.socialWaitingRoom(who,roomId);
    if(presence(target.accountId()).equals("OFFLINE"))
      throw RoomService.fail(409,"FRIENDS_OFFLINE","Friend is offline");
    if(invites.size()>=256) throw RoomService.fail(429,"FRIENDS_RATE_LIMITED","Try again later");
    invites.values().removeIf(i->i.actor().equals(a)&&i.target().equals(target.accountId()));
    String id=UUID.randomUUID().toString();
    invites.put(id,new Invite(id,a,target.accountId(),who.token(),s.nickname(),roomId,roomName,clock.millis()+INVITE_MS));
    receipt(key,signature,id);return read(who);
  }
  public synchronized RoomService.Snapshot join(RoomService.Identity who,String inviteId,String actionId) {
    String a=account(who),key=key(a,actionId),signature="join:"+inviteId;
    sweep();Object old=previous(key,signature);
    // Never replay an old room snapshot (it can contain private hand state).
    if(old instanceof String roomId) return rooms.get(roomId,who);
    rate(a);Invite i=invites.get(inviteId);
    if(i==null||!i.target().equals(a))throw RoomService.fail(404,"FRIENDS_INVITE_EXPIRED","Invitation expired");
    if(store.snapshot(a).friends().stream().noneMatch(p->p.accountId().equals(i.actor())))
      throw RoomService.fail(403,"FRIENDS_NOT_FRIENDS","Friend required");
    // An invitation may arrive before the recipient joins through another route.
    // Keep their existing seat and readiness instead of requesting leave/rejoin.
    if(i.roomId().equals(rooms.me(who).roomId())) {
      var current=rooms.get(i.roomId(),who);
      invites.remove(inviteId);receipt(key,signature,current.roomId());return current;
    }
    RoomService.Identity inviter=rooms.authenticate(i.actorToken());
    rooms.socialWaitingRoom(inviter,i.roomId());
    var room=rooms.socialJoin(i.roomId(),who,i.actor());
    invites.remove(inviteId);receipt(key,signature,room.roomId());return room;
  }
  public synchronized RoomService.Snapshot joinFriend(RoomService.Identity who,String code,String actionId) {
    String a=account(who),key=key(a,actionId),signature="friendJoin:"+code;
    sweep();Object old=previous(key,signature);
    if(old instanceof String roomId)return rooms.get(roomId,who);
    rate(a);
    var friend=store.snapshot(a).friends().stream().filter(p->p.code().equals(code)).findFirst()
        .orElseThrow(()->RoomService.fail(403,"FRIENDS_NOT_FRIENDS","Friend required"));
    String roomId=rooms.socialJoinableRoom(friend.accountId());
    if(roomId==null || presence(friend.accountId()).equals("OFFLINE"))
      throw RoomService.fail(409,"FRIENDS_ROOM_UNAVAILABLE","Friend has no available waiting room");
    var room=rooms.socialJoin(roomId,who,friend.accountId());receipt(key,signature,roomId);return room;
  }
  public synchronized View ignore(RoomService.Identity who,String inviteId,String actionId) {
    String a=account(who);key(a,actionId);rate(a);Invite i=invites.get(inviteId);
    if(i!=null&&i.target().equals(a))invites.remove(inviteId);
    return read(who);
  }
}
