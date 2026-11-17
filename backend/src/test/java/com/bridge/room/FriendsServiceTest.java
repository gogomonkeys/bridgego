package com.bridge.room;
import com.bridge.persistence.SocialStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FriendsServiceTest {
  static class Store implements SocialStore {
    Map<String,Snapshot> data=new HashMap<>();
    public boolean enabled(){return true;}
    public Snapshot snapshot(String id){return data.get(id);}
    public String ensureCode(String id){return snapshot(id).ownCode();}
    public Snapshot request(String a,String c,String id){return snapshot(a);}
    public Snapshot respond(String a,String c,boolean b,String id){return snapshot(a);}
    public Snapshot remove(String a,String c,String id){return snapshot(a);}
  }
  static class TestClock extends Clock {
    long time=1_000_000;
    public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}
    public Instant instant(){return Instant.ofEpochMilli(time);}
  }
  TestClock clock=new TestClock();
  RoomService rooms=new RoomService(clock);
  Store store=new Store();
  FriendsService friends=new FriendsService(rooms,store,clock);
  RoomService.Identity alice=rooms.createSession("Alice"),bob=rooms.createSession("Bob");
  String aid=UUID.randomUUID().toString(),bid=UUID.randomUUID().toString();
  FriendsServiceTest(){
    rooms.linkAccountOutsideRoom(alice,aid);rooms.linkAccountOutsideRoom(bob,bid);
    store.data.put(aid,new SocialStore.Snapshot("AAAAAAAAAAAAAAAA","Alice",List.of(new SocialStore.Person(bid,"BBBBBBBBBBBBBBBB","Bob")),List.of(),List.of()));
    store.data.put(bid,new SocialStore.Snapshot("BBBBBBBBBBBBBBBB","Bob",List.of(new SocialStore.Person(aid,"AAAAAAAAAAAAAAAA","Alice")),List.of(),List.of()));
  }
  String action(){return UUID.randomUUID().toString();}
  @Test void guestRejectedAndPrivateIdsNotInView(){
    var guest=rooms.createSession("Guest");
    assertEquals(403,assertThrows(RoomService.Failure.class,()->friends.read(guest)).status);
    var view=friends.read(alice);
    assertFalse(view.toString().contains(aid));assertFalse(view.toString().contains(bid));
    assertFalse(view.toString().contains(alice.token()));
  }
  @Test void multiSessionPresenceExpiresAndUnlinkImmediatelyOffline(){
    friends.read(bob); assertEquals("ONLINE",friends.read(alice).friends().get(0).presence());
    clock.time+=76_000;assertEquals("OFFLINE",friends.read(alice).friends().get(0).presence());
    friends.read(bob);rooms.unlinkAccountOutsideRoom(bob);
    assertEquals("OFFLINE",friends.read(alice).friends().get(0).presence());
  }
  @Test void invitationAcceptsOnlyTargetAndReplaysWithoutDoubleSeat(){
    var room=rooms.create(alice,1,"CASUAL");friends.read(bob);
    friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),action());
    var inv=friends.read(bob).invites().get(0);
    assertEquals(404,assertThrows(RoomService.Failure.class,()->friends.join(alice,inv.inviteId(),action())).status);
    String action=action();var joined=friends.join(bob,inv.inviteId(),action);
    assertEquals(2,joined.players().size());assertEquals(2,friends.join(bob,inv.inviteId(),action).players().size());
    assertTrue(friends.read(bob).invites().isEmpty());
  }
  @Test void sameRoomFriendCannotBeInvitedAgain() {
    var room=rooms.create(alice);rooms.join(room.roomId(),bob);friends.read(bob);
    assertTrue(friends.read(alice).friends().get(0).sameRoom());
    assertEquals("FRIENDS_SAME_ROOM",assertThrows(RoomService.Failure.class,
        ()->friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),action())).code);
    assertTrue(friends.read(bob).invites().isEmpty());
  }
  @Test void earlierInvitationKeepsExistingSeatEvenWhenRoomIsNowFull() {
    var room=rooms.create(alice);friends.read(bob);
    friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),action());
    var invitation=friends.read(bob).invites().get(0);
    assertEquals(room.roomId(),invitation.roomId());
    rooms.join(room.roomId(),bob);
    rooms.join(room.roomId(),rooms.createSession("Guest1"));
    rooms.join(room.roomId(),rooms.createSession("Guest2"));
    var before=rooms.get(room.roomId(),bob);String id=action();
    var after=friends.join(bob,invitation.inviteId(),id);
    assertEquals(before.players(),after.players());
    assertEquals(4,after.players().size());
    assertEquals(4,friends.join(bob,invitation.inviteId(),id).players().size());
    assertTrue(friends.read(bob).invites().isEmpty());
  }
  @Test void inviteCannotAbandonDifferentRoom() {
    var room=rooms.create(alice);friends.read(bob);
    friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),action());
    var invitation=friends.read(bob).invites().get(0);
    var other=rooms.create(bob);
    assertEquals("FRIENDS_ALREADY_IN_ROOM",assertThrows(RoomService.Failure.class,
        ()->friends.join(bob,invitation.inviteId(),action())).code);
    assertEquals(other.roomId(),rooms.me(bob).roomId());
  }
  @Test void inviteExpiresWithoutJoining(){
    var room=rooms.create(alice);friends.read(bob);
    friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),action());
    String id=friends.read(bob).invites().get(0).inviteId();clock.time+=60_001;
    assertThrows(RoomService.Failure.class,()->friends.join(bob,id,action()));
    assertNull(rooms.me(bob).roomId());
  }
  @Test void friendDirectJoinCannotAbandonAnotherRoomOrAnotherAccountSession(){
    var room=rooms.create(alice);friends.read(alice);friends.read(bob);
    assertTrue(friends.read(bob).friends().get(0).joinable());
    rooms.create(bob);assertEquals(409,assertThrows(RoomService.Failure.class,()->friends.joinFriend(bob,"AAAAAAAAAAAAAAAA",action())).status);
    assertNotEquals(room.roomId(),rooms.me(bob).roomId());
  }
  @Test void sameAccountSecondSessionCannotJoinAnotherSeat(){
    rooms.create(alice);friends.read(alice);
    var bob2=rooms.createSession("Bob2");rooms.linkAccountOutsideRoom(bob2,bid);
    friends.joinFriend(bob,"AAAAAAAAAAAAAAAA",action());
    assertEquals(409,assertThrows(RoomService.Failure.class,()->friends.joinFriend(bob2,"AAAAAAAAAAAAAAAA",action())).status);
  }
  @Test void duplicateInviteActionDoesNotCreateSecondInvitation(){
    var room=rooms.create(alice);friends.read(bob);String id=action();
    friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),id);
    friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),id);
    assertEquals(1,friends.read(bob).invites().size());
    assertThrows(RoomService.Failure.class,()->friends.invite(alice,"AAAAAAAAAAAAAAAA",room.roomId(),id));
  }
  @Test void guestAndBotsCannotBecomeSameTableTargets(){
    var room=rooms.create(alice);var guest=rooms.createSession("Guest");rooms.join(room.roomId(),guest);
    assertFalse(friends.read(alice).tablePlayers().get(0).eligible());
    assertThrows(RoomService.Failure.class,()->friends.request(alice,null,room.roomId(),guest.playerId(),action()));
  }
  @Test void staleInviteCannotJoinAfterInviterLeaves(){
    var room=rooms.create(alice);friends.read(bob);
    var guest=rooms.createSession("Guest");rooms.join(room.roomId(),guest);
    friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),action());
    String id=friends.read(bob).invites().get(0).inviteId();rooms.leave(room.roomId(),alice);
    assertThrows(RoomService.Failure.class,()->friends.join(bob,id,action()));
    assertNull(rooms.me(bob).roomId());
  }
  @Test void staleInviteCannotJoinFullRoom(){
    var room=rooms.create(alice);friends.read(bob);
    friends.invite(alice,"BBBBBBBBBBBBBBBB",room.roomId(),action());
    String id=friends.read(bob).invites().get(0).inviteId();
    for(int n=0;n<3;n++)rooms.join(room.roomId(),rooms.createSession("G"+n));
    assertThrows(RoomService.Failure.class,()->friends.join(bob,id,action()));
    assertNull(rooms.me(bob).roomId());
  }
  @Test void targetMutationCannotInitializeBeforeActor(){
    store.data.put(aid,new SocialStore.Snapshot(null,"Alice",List.of(),List.of(),List.of()));
    var room=rooms.create(alice);rooms.join(room.roomId(),bob);
    assertEquals("FRIENDS_NOT_INITIALIZED",assertThrows(RoomService.Failure.class,
        ()->friends.request(alice,null,room.roomId(),bob.playerId(),action())).code);
  }
}
