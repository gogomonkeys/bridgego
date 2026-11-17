package com.bridge.room;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class NicknameRollbackCompatibilityTest {
 @ParameterizedTest @ValueSource(ints={5,6,10,11})
 void storedNicknameSignIn(int count) {
  String name="甲".repeat(count); String account=UUID.randomUUID().toString();
  IdentityStore store=new IdentityStore(){
   public boolean enabled(){return true;}
   public String findOrCreateAccount(String i,String s){throw new AssertionError("unexpected legacy write");}
   public Profile findOrCreateForEntry(String i,String s){return new Profile(account,name);}
   public Profile completeEntryProfile(String i,String s,String n,String e){throw new AssertionError("existing nickname must not be rewritten");}
  };
  var rooms=new RoomService();var guest=rooms.createSession("Guest");String origin="https://bridge.example";
  GoogleCredentialVerifier verifier=(c,a,n)->new GoogleCredentialVerifier.Principal("https://accounts.google.com","synthetic-subject");
  var auth=new GoogleAuthService(rooms,store,verifier,"synthetic-client",Set.of(origin),Clock.systemUTC(),CollectionPolicy.managed("test","test"));
  auth.nonce(guest,origin);
  int max=10;
  if(count<=max){
   var status=auth.loginForEntry(guest,origin,"synthetic",true,"account-profile-v1");
   assertTrue(status.signedIn());assertFalse(status.nicknameRequired());assertEquals(name,status.nickname());
   var current=rooms.authenticate(guest.token());assertEquals(name,current.displayName());assertEquals(account,rooms.accountId(current));
   rooms.create(current,1);
  }else{
   var failure=assertThrows(RoomService.Failure.class,()->auth.loginForEntry(guest,origin,"synthetic",true,"account-profile-v1"));
   assertEquals("IDENTITY_STORAGE_UNAVAILABLE",failure.code);assertFalse(auth.status(guest).signedIn());
  }
  assertEquals(name,store.findOrCreateForEntry("unused","unused").nickname());
 }
}
