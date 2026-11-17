package com.bridge.room;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/friends")
public class FriendsController {
  private final RoomService rooms;
  private final FriendsService friends;
  private final Set<String> origins;
  public record Action(String actionId,String friendCode,String roomId,String playerId) {}
  public FriendsController(RoomService rooms,FriendsService friends,CollectionPolicy policy,
      @Value("${PUBLIC_ORIGIN:}") String origin,Environment env) {
    this.rooms=rooms;this.friends=friends;
    origins=Set.of(AllowedOrigins.forProfile(Arrays.asList(env.getActiveProfiles()).contains("prod")
        ||policy.managed()||policy.experiment(),origin));
  }
  private RoomService.Identity identity(String auth) {
    if(auth==null||!auth.startsWith("Bearer "))throw RoomService.fail(401,"UNAUTHORIZED","Session required");
    return rooms.authenticate(auth.substring(7));
  }
  private RoomService.Identity mutation(String auth,String origin,Action action) {
    if(!origins.contains(origin==null?"":origin))throw RoomService.fail(403,"AUTH_ORIGIN","Allowed origin required");
    if(action==null)throw RoomService.fail(400,"INVALID_JSON","Action required");
    return identity(auth);
  }
  @GetMapping public FriendsService.View read(@RequestHeader(value="Authorization",required=false)String a) {return friends.read(identity(a));}
  @PostMapping("/initialize") public FriendsService.View initialize(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.initialize(mutation(a,o,r),r.actionId());}
  @PostMapping("/requests") public FriendsService.View request(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.request(mutation(a,o,r),r.friendCode(),r.roomId(),r.playerId(),r.actionId());}
  @PostMapping("/requests/{id}/accept") public FriendsService.View accept(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.respond(mutation(a,o,r),id,true,r.actionId());}
  @PostMapping("/requests/{id}/reject") public FriendsService.View reject(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.respond(mutation(a,o,r),id,false,r.actionId());}
  @DeleteMapping("/{code}") public FriendsService.View remove(@PathVariable String code,@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.remove(mutation(a,o,r),code,r.actionId());}
  @PostMapping("/invites") public FriendsService.View invite(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.invite(mutation(a,o,r),r.friendCode(),r.roomId(),r.actionId());}
  @PostMapping("/invites/{id}/join") public RoomService.Snapshot join(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.join(mutation(a,o,r),id,r.actionId());}
  @PostMapping("/invites/{id}/ignore") public FriendsService.View ignore(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.ignore(mutation(a,o,r),id,r.actionId());}
  @PostMapping("/{code}/join") public RoomService.Snapshot joinFriend(@PathVariable String code,@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="Origin",required=false)String o,@RequestBody Action r){return friends.joinFriend(mutation(a,o,r),code,r.actionId());}
  @ExceptionHandler(RoomService.Failure.class)public ResponseEntity<?> failure(RoomService.Failure e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
  @ExceptionHandler(HttpMessageNotReadableException.class)public ResponseEntity<?> malformed(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_JSON","message","Invalid request"));}
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> invalid(IllegalArgumentException e){
    if(e instanceof com.bridge.persistence.SocialStore.Rejected r)return ResponseEntity.badRequest().body(Map.of("code",r.code,"message","好友操作無法完成，請確認好友碼或重新讀取清單。"));
    return ResponseEntity.badRequest().body(Map.of("code","FRIENDS_INVALID_REQUEST","message","好友操作無法完成，請確認好友碼或重新讀取清單。"));
  }
  @ExceptionHandler({IllegalStateException.class,com.bridge.persistence.SheetsGateway.Failure.class,com.bridge.persistence.SheetsGateway.Conflict.class})
  public ResponseEntity<?> storage(){return ResponseEntity.status(503).body(Map.of("code","FRIENDS_STORAGE_UNAVAILABLE","message","好友資料暫時無法確認，請稍後重新讀取。"));}
}
