// UI fixture only. This module never imports the production client or calls APIs.
import {useSyncExternalStore} from "react";
import {gameRoom,playedRoom,completedDeal} from "../../frontend/src/test-fixtures";
import type {Room,Card,Call,Strain} from "../../frontend/src/model";
const params=new URLSearchParams(location.search),scenario=params.get("scenario")??"playing",own=Number(params.get("seat")??0)%4;
const listeners=new Set<()=>void>();
let room:Room= (["playing","trick-result","hand-stress"].includes(scenario))?playedRoom():gameRoom(scenario==="bidding"?"BIDDING":scenario==="deal-result"?"DEAL_RESULT":scenario==="match-result"?"MATCH_RESULT":"PRIVILEGE_DECISION");
room.players.forEach((player,index)=>player.displayName=(params.has("long")?["非常長的玩家測試名稱甲","VeryLongOpponentName","非常長的隊友名稱乙","OpponentFourLongName"]:["小晴","阿哲","小樂","米米"])[index]);
// V066 QA-only five-grapheme boundary names.
if(params.has("names")){const names=params.get("names")==="wide"?["WWWWW","MMMMM","WMWMW","MWMWM"]:params.get("names")==="emoji"?["🐱🐶🐼🐯🦊","🐶🐼🐯🦊🐱","🐼🐯🦊🐱🐶","🐯🦊🐱🐶🐼"]:["森林動物甲","森林動物乙","森林動物丙","森林動物丁"];room.players.forEach((p,i)=>p.displayName=names[i]);}
if(params.has("duplicate")){room.players[0].displayName="小晴";room.players[2].displayName="小晴";}
room.game!.roster=room.players.map(({playerId,displayName,seat,teamId})=>({playerId,displayName,seat,teamId}));
room.you={...room.you!,playerId:room.players[own].playerId,seat:own,teamId:own%2===0?"NS":"EW"};
room.you.ownHand=[{suit:"SPADE",rank:14},{suit:"SPADE",rank:13},{suit:"SPADE",rank:8},{suit:"HEART",rank:12},{suit:"HEART",rank:9},{suit:"HEART",rank:4},{suit:"DIAMOND",rank:14},{suit:"DIAMOND",rank:10},{suit:"DIAMOND",rank:7},{suit:"CLUB",rank:13},{suit:"CLUB",rank:11},{suit:"CLUB",rank:6},{suit:"CLUB",rank:2}];
room.you.legalCards=room.you.ownHand.filter(card=>card.suit==="HEART");
room.you.legalCalls=[{type:"PASS",level:null,strain:null},{type:"BID",level:2,strain:"HEART"},{type:"BID",level:2,strain:"SPADE"},{type:"BID",level:2,strain:"NO_TRUMP"},{type:"BID",level:3,strain:"CLUB"}];
if(params.has("allbids"))room.you.legalCalls=[{type:"PASS",level:null,strain:null},...Array.from({length:7},(_,i)=>["CLUB","DIAMOND","HEART","SPADE","NO_TRUMP"].map(strain=>({type:"BID",level:i+1,strain}))).flat()] as Call[];
if(room.game!.play){room.game!.play!.nextSeat=own;room.game!.play!.trickNumber=4;room.game!.play!.tricksByTeam={NS:2,EW:1};room.game!.play!.currentTrick=[{seat:(own+1)%4,card:{suit:"HEART",rank:10},source:"MANUAL"}];room.game!.play!.completedTricks=[{number:3,leaderSeat:0,winnerSeat:3,plays:[{seat:0,card:{suit:"CLUB",rank:8},source:"MANUAL"},{seat:1,card:{suit:"CLUB",rank:9},source:"TIMEOUT"},{seat:2,card:{suit:"CLUB",rank:10},source:"MANUAL"},{seat:3,card:{suit:"CLUB",rank:14},source:"MANUAL"}]}];}
if(scenario==="bidding"){room.game!.auction.nextSeat=own;room.game!.auction.highestBid={seat:(own+3)%4,level:2,strain:"DIAMOND"};}
room.game!.auction.calls=[{seat:0,type:"BID",level:1,strain:"CLUB",source:"MANUAL"},{seat:1,type:"PASS",level:null,strain:null,source:"TIMEOUT"}];
if(params.has("allbids")&&scenario==="bidding"){room.game!.auction.highestBid=null;room.game!.auction.calls=[];}
if(room.game!.operationTimer)room.game!.operationTimer!.turnSeat=room.phase==="PRIVILEGE_DECISION"?null:own;
if(room.game!.play){const old=room.game!.play!.completedTricks[0];room.game!.play!.completedTricks=[{...old,number:1,winnerSeat:0,plays:old.plays.map(p=>({...p,card:{...p.card,rank:p.seat===0?14:p.seat+2}}))},{...old,number:2,winnerSeat:2,plays:old.plays.map(p=>({...p,card:{...p.card,rank:p.seat===2?14:p.seat+2}}))},old];}
if(scenario==="playing"){room.you!.ownHand=room.you!.ownHand.slice(0,10);room.you!.legalCards=room.you!.ownHand.filter(card=>card.suit==="HEART");}
if(scenario==="trick-result"){
 room.you!.ownHand=room.you!.ownHand.slice(0,9);room.phase="TRICK_RESULT";room.game!.operationTimer={kind:"TRICK_RESULT",turnSeat:null,remainingMs:2000,paused:false};
 room.game!.play!.currentTrick=[{seat:0,card:{suit:"HEART",rank:10},source:"MANUAL"},{seat:1,card:{suit:"HEART",rank:12},source:"MANUAL"},{seat:2,card:{suit:"HEART",rank:14},source:"MANUAL"},{seat:3,card:{suit:"HEART",rank:9},source:"MANUAL"}];
 room.game!.play!.completedTricks.push({number:4,leaderSeat:0,winnerSeat:2,plays:room.game!.play!.currentTrick});room.game!.play!.nextSeat=null;room.you!.legalCards=[];room.game!.play!.tricksByTeam={NS:3,EW:1};
}
if(["playing","trick-result","bidding","privilege"].includes(scenario)){room.game!.dealIndex=3;room.game!.completedDeals=2;room.game!.teamTotals={NS:7,EW:5};room.game!.completedDealResults=[completedDeal(),{...completedDeal(),dealId:"second",dealIndex:2}];}
if(scenario==="deal-result"||scenario==="match-result"||scenario==="archive"){
 room.game!.completedDealResults=[completedDeal()];room.game!.completedDeals=1;room.game!.teamTotals={NS:7,EW:0};room.you.ownHand=[];
}
if(scenario==="deal-result"){room.game!.operationTimer={kind:"DEAL_RESULT",turnSeat:null,remainingMs:90000,paused:false};room.you.canAcknowledgeResult=true;room.game!.resultAcknowledgedSeats=[(own+1)%4,(own+2)%4];}
if(scenario==="match-result"||scenario==="archive"){
 room.game!.matchResult={outcome:"SCORED",winnerTeamId:"NS",reason:"THREE_DEALS_COMPLETE",failedTeamIds:[]};room.game!.completedDeals=3;room.game!.dealIndex=3;room.game!.teamTotals={NS:12,EW:7};room.game!.completedDealResults=[completedDeal(),{...completedDeal(),dealId:"second",dealIndex:2},{...completedDeal(),dealId:"third",dealIndex:3}];room.game!.operationTimer=null;room.canRematch=own===0;
}
room.game!.completedDealResults.forEach(result=>{result.completedTricks=Array.from({length:13},(_,i)=>({number:i+1,leaderSeat:i%4,winnerSeat:i%4,plays:[0,1,2,3].map(seat=>({seat,card:{suit:"HEART" as const,rank:seat===i%4?14:seat+2},source:"MANUAL" as const}))}));});
if(scenario==="final-trick"){
 const previous=room.game!;room=playedRoom();room.game!.roster=previous.roster;room.players.forEach((p,i)=>p.displayName=previous.roster[i].displayName);room.phase="TRICK_RESULT";room.game!.dealIndex=3;room.game!.completedDeals=3;room.game!.teamTotals={NS:12,EW:7};room.game!.completedDealResults=[completedDeal(),{...completedDeal(),dealId:"second",dealIndex:2},{...completedDeal(),dealId:"third",dealIndex:3}];room.game!.dealId="third";room.game!.matchResult={outcome:"SCORED",winnerTeamId:"NS",reason:"THREE_DEALS_COMPLETE",failedTeamIds:[]};room.game!.operationTimer={kind:"TRICK_RESULT",turnSeat:null,remainingMs:2000,paused:false};room.game!.play!.trickNumber=13;room.game!.play!.completedTricks=Array.from({length:13},(_,i)=>({number:i+1,leaderSeat:i%4,winnerSeat:i%4,plays:[0,1,2,3].map(seat=>({seat,card:{suit:"HEART" as const,rank:seat===i%4?14:seat+2},source:"MANUAL" as const}))}));room.game!.play!.currentTrick=room.game!.play!.completedTricks.at(-1)!.plays;room.game!.play!.nextSeat=null;room.game!.play!.tricksByTeam={NS:7,EW:6};room.you!.ownHand=[];room.you!.legalCards=[];
}
if(scenario==="deal-result"){room.game!.dealId=room.game!.completedDealResults[0].dealId;}
room.game!.completedDealResults.forEach(result=>{result.completedTricks=Array.from({length:13},(_,i)=>({number:i+1,leaderSeat:i%4,winnerSeat:i%4,plays:[0,1,2,3].map(seat=>({seat,card:{suit:"HEART" as const,rank:seat===i%4?14:seat+2},source:"MANUAL" as const}))}));});
// Public-view fixture projection, not a server implementation or security proof.
function auctionRounds(dealId:string,dealIndex:number){return [
 {dealId:dealId+"-redeal",dealIndex,redealCount:0,dealerSeat:0,calls:[0,1,2,3].map(seat=>({seat,type:"PASS" as const,level:null,strain:null,source:"MANUAL" as const})),result:null,status:"ALL_PASS" as const},
 {dealId,dealIndex,redealCount:1,dealerSeat:0,calls:[{seat:0,type:"BID" as const,level:2,strain:"HEART",source:"MANUAL" as const},...[1,2,3].map(seat=>({seat,type:"PASS" as const,level:null,strain:null,source:seat===2?"TIMEOUT" as const:"MANUAL" as const}))],result:{type:"CONTRACT",holderSeat:0,level:2,strain:"HEART",teamId:"NS"},status:"CONTRACT" as const}
];}
room.game!.auctionRounds=room.game!.completedDealResults.filter(d=>d.dealId!==room.game!.dealId).flatMap(d=>auctionRounds(d.dealId,d.dealIndex)).concat(auctionRounds(room.game!.dealId,room.game!.dealIndex)) as NonNullable<Room["game"]>["auctionRounds"];
if(["bidding","privilege"].includes(scenario)){
 room.game!.auctionRounds=room.game!.auctionRounds!.filter(round=>round.dealIndex!==room.game!.dealIndex);
 if(scenario==="bidding")room.game!.auctionRounds.push({dealId:room.game!.dealId,dealIndex:room.game!.dealIndex,redealCount:0,dealerSeat:0,calls:room.game!.auction.calls,result:null,status:"IN_PROGRESS"});
}
room.game!.completedDealResults.forEach(result=>{
 result.auctionRounds=auctionRounds(result.dealId,result.dealIndex) as typeof result.auctionRounds;
 result.completedTricks=result.completedTricks.map(trick=>({...trick,cardsVisible:!(scenario==="final-trick"&&result.dealIndex===3),plays:scenario==="final-trick"&&result.dealIndex===3?[]:trick.plays}));
});
if(room.game!.play)room.game!.play!.completedTricks=room.game!.play!.completedTricks.map(trick=>({...trick,cardsVisible:room.phase==="DEAL_RESULT"||room.phase==="MATCH_RESULT",plays:room.phase==="DEAL_RESULT"||room.phase==="MATCH_RESULT"?trick.plays:[]}));
if(["deal-result","match-result"].includes(scenario)){
 const result=room.game!.completedDealResults.at(-1)!;
 room.game!.dealId=result.dealId;
 room.game!.play={nextSeat:null,leaderSeat:0,trickNumber:13,currentTrick:[],completedTricks:result.completedTricks,tricksByTeam:result.tricksByTeam};
}
// Real server post-privilege phases always expose both team choices.
if(room.phase!=="PRIVILEGE_DECISION"){room.game!.privilege={...room.game!.privilege,status:"REVEALED",canChoose:false,usedByTeam:{NS:true,EW:false}};}
if(params.has("chosen")&&room.phase==="PRIVILEGE_DECISION"){room.game!.privilege.yourChoice=true;room.game!.privilege.canChoose=false;}
if(params.has("threecards")&&room.game!.play){room.game!.play.currentTrick=[{seat:(own+2)%4,card:{suit:"CLUB",rank:13},source:"MANUAL"},{seat:(own+1)%4,card:{suit:"CLUB",rank:6},source:"MANUAL"},{seat:own,card:{suit:"CLUB",rank:2},source:"TIMEOUT"}];room.game!.play.nextSeat=(own+3)%4;room.game!.operationTimer!.turnSeat=(own+3)%4;}
if(params.has("timeout")){room.game!.privilege.yourChoice=false;room.game!.privilege.yourChoiceSource="TIMEOUT";}
if(params.has("maxtricks")&&room.game!.play){room.game!.play.completedTricks=Array.from({length:13},(_,i)=>({number:i+1,leaderSeat:own,winnerSeat:own,cardsVisible:false,plays:[]}));room.game!.play.trickNumber=13;}
// Explicit visual winner positioning stress, independent from the viewer seat.
if(params.has("winner")&&room.phase==="TRICK_RESULT"&&room.game?.play){
 const winner=Number(params.get("winner"))%4;
 room.game.play.completedTricks.at(-1)!.winnerSeat=winner;
 room.game.play.currentTrick=room.game.play.currentTrick.map(p=>({...p,card:{...p.card,rank:p.seat===winner?14:p.seat+5}}));
}
if(params.has("playcount")&&room.game?.play){const count=Math.max(0,Math.min(4,Number(params.get("playcount"))));const leader=Number(params.get("leadseat")??0)%4;room.game.play.currentTrick=[0,1,2,3].slice(0,count).map(i=>({seat:(leader+i*3)%4,card:{suit:"HEART" as const,rank:i+10},source:"MANUAL" as const}));}
// Keep result fixtures internally consistent after playcount overrides.
if(room.phase === "TRICK_RESULT" && room.game?.play?.currentTrick.length === 4){
 const p=room.game.play, t=p.completedTricks.at(-1);
 if(t){p.currentTrick=p.currentTrick.map(x=>({...x,card:{...x.card,rank:x.seat===t.winnerSeat?14:Math.min(x.card.rank,13)}}));t.plays=p.currentTrick;t.leaderSeat=p.currentTrick[0].seat;}
}
// Rightward trick order; preserve each fixture's explicit leader and cards.
if(room.game?.play){
 const p=room.game.play;
 const order=(list:typeof p.currentTrick,leader:number)=>[...list].sort((a,b)=>((leader-a.seat+4)%4)-((leader-b.seat+4)%4));
 if(p.currentTrick.length){p.leaderSeat=p.currentTrick[0].seat;p.currentTrick=order(p.currentTrick,p.leaderSeat);if(p.currentTrick.length<4)p.nextSeat=(p.currentTrick.at(-1)!.seat+3)%4;}
 p.completedTricks=p.completedTricks.map(t=>({...t,plays:order(t.plays,t.leaderSeat)}));
}
if(params.has("autoplay")&&room.game!.play)room.game!.play.currentTrick=room.game!.play.currentTrick.map(p=>({...p,source:"TIMEOUT"}));
if(params.has("zero")&&room.game!.operationTimer)room.game!.operationTimer.remainingMs=0;
if(params.has("paused")){room.game!.paused=true;if(room.game!.operationTimer)room.game!.operationTimer.paused=true;}
if(params.has("otherturn")){
 if(room.phase==="BIDDING")room.game!.auction.nextSeat=(own+1)%4;
 if(room.game!.play&&room.phase==="PLAYING")room.game!.play.nextSeat=(own+1)%4;
 if(room.game!.operationTimer)room.game!.operationTimer.turnSeat=(own+1)%4;
}
// V067 QA-only badge stress: one named seat owns N completed tricks; never an actual game flow.
if(params.has("woncount")&&room.game?.play){const n=Math.max(0,Math.min(13,Number(params.get("woncount"))));const winner=Number(params.get("wonseat")??0)%4;room.game.play.tricksByTeam={NS:winner%2===0?n:0,EW:winner%2===1?n:0};room.game.play.completedTricks=Array.from({length:n},(_,i)=>({number:i+1,leaderSeat:winner,winnerSeat:winner,cardsVisible:false,plays:[]}));}
if(params.has("offlineseat")){const s=Number(params.get("offlineseat"))%4;room.players[s].connected=false;}
// V0610 QA-only central highest-bid scenarios.
if(scenario==="bidding" && params.has("bidlevel")){room.game!.auction.highestBid={seat:Number(params.get("bidseat")??2)%4,level:Number(params.get("bidlevel")),strain:(params.get("bidstrain")??"NO_TRUMP") as Strain};}
if(scenario==="bidding" && params.has("nohighest")){room.game!.auction.highestBid=null;room.game!.auction.calls=[];}
function resetWaiting(){const g=room.game!;room.lastMatch={gameId:g.gameId,roster:g.roster,auctionRounds:g.auctionRounds,teamTotals:g.teamTotals,completedDealResults:g.completedDealResults,matchResult:g.matchResult!,forfeitEvents:g.forfeitEvents};room.game=null;room.phase="WAITING";room.players.forEach(p=>p.ready=false);room.canRematch=false;room.canStart=false;room.allReady=false;room.you!.ownHand=[];}
if(scenario.startsWith("waiting")){room.roomId="94229409-050d-414e-b063-9c9ef9825940";room.phase="WAITING";room.game=null;room.canStart=false;room.allReady=false;room.players.forEach((p,i)=>p.ready=i%2===0);room.you!.ownHand=[];}
if(scenario==="archive")resetWaiting();
let state={room:["lobby","guest"].includes(scenario)?null:room,session:{token:"fixture-not-a-credential",playerId:room.players[own].playerId,displayName:room.players[own].displayName},rooms:[{roomId:"WAIT01",playerCount:2,capacity:4,state:"WAITING",phase:"WAITING",joinable:true},{roomId:"PLAY01",playerCount:4,capacity:4,state:"ACTIVE",phase:"PLAYING",joinable:false},{roomId:"DONE01",playerCount:3,capacity:4,state:"FINISHED",phase:"MATCH_RESULT",joinable:false}],status:"online",connectionEpoch:0,busy:false,initialized:true,error:null as string|null,notice:null,pendingAction:null,snapshotReceivedAt:performance.now()};
if(scenario==="guest")state.session=null as never;
// Sparse waiting fixtures keep a synthetic observer identity, with only requested seats occupied.
if(scenario==="waiting-empty")room.players=[];
if(scenario==="waiting-one")room.players=room.players.filter(p=>p.seat===own);
function emit(_action:string){state={...state,room:state.room?{...room,revision:++room.revision}:null,notice:null,snapshotReceivedAt:performance.now()};listeners.forEach(listener=>listener());}
export const useClient=()=>useSyncExternalStore(callback=>{listeners.add(callback);return()=>listeners.delete(callback);},()=>state);
export const getConnectionDiagnostics=()=>[];
export const client={initialize:async()=>{},refresh:async()=>{},synchronize:async()=>emit("synchronize"),retryCommand:async()=>emit("retry"),clearError:()=>{state={...state,error:null};emit("clearError");},login:async()=>{},create:async()=>emit("create"),join:async()=>emit("join"),forget:()=>emit("forget"),leave:async()=>{state={...state,room:null};emit("leave");},ready:async()=>{room.players[own].ready=!room.players[own].ready;emit("ready");},start:async()=>emit("start"),privilege:async(use:boolean)=>{room.game!.privilege.yourChoice=use;room.game!.privilege.canChoose=false;emit("privilege");},call:async(call:Call)=>{room.game!.auction.calls.push({...call,seat:own,source:"MANUAL"});room.you!.legalCalls=[];emit("call");},play:async(card:Card)=>{room.you!.ownHand=room.you!.ownHand.filter(c=>c.rank!==card.rank||c.suit!==card.suit);room.you!.legalCards=[];room.game!.play!.currentTrick.push({seat:own,card,source:"MANUAL"});emit("play");},acknowledgeResult:async()=>{room.game!.resultAcknowledgedSeats.push(own);room.you!.canAcknowledgeResult=false;emit("acknowledgeResult");},rematch:async()=>{resetWaiting();emit("rematch");}};

// Explicit local fixture controls; never included in production.
export function fixtureControl(action:string){
 if(!state.room?.game)return;
 if(action==="illegal")room.you!.legalCards=[];
 if(action==="paused"){room.game!.paused=true;if(room.game!.operationTimer)room.game!.operationTimer.paused=true;}
 if(action==="next-phase"){room.phase="PLAYING";room.game!.play=playedRoom().game!.play;room.game!.operationTimer={kind:"PLAY_TURN",turnSeat:own,remainingMs:20000,paused:false};}
 if(action==="back-bidding"){room.phase="BIDDING";room.game!.operationTimer={kind:"BID_TURN",turnSeat:own,remainingMs:20000,paused:false};}
 if(action==="snapshot"){}
 if(action==="error")state={...state,error:"測試：連線中斷，需要重新同步"};
 if(action==="resumed"){room.game!.paused=false;if(room.game!.operationTimer)room.game!.operationTimer.paused=false;}
 if(action=="other-turn")room.game!.play!.nextSeat=(own+3)%4;
 if(action==="own-turn")room.game!.play!.nextSeat=own;
 if(action==="offline")state={...state,status:"offline"};
 if(action==="online")state={...state,status:"online"};
 if(action==="pending")state={...state,pendingAction:"fixture-play" as never};
 if(action==="clear-pending")state={...state,pendingAction:null};
 emit(action);
}

// V069 QA-only live transition controls. No real transport or credentials.
const fxMode=params.get("fx");
const fxSeat=Number(params.get("fxseat")??0)%4;
function fxSetup(){
 if(!fxMode||!room.game)return;
 const play=playedRoom().game!.play!;
 room.phase=fxMode==="bidding"?"BIDDING":"PLAYING";
 room.game.play={...play,trickNumber:1,leaderSeat:fxSeat,nextSeat:fxSeat,currentTrick:[],completedTricks:[],tricksByTeam:{NS:0,EW:0}};
 room.game.auction.result={...playedRoom().game!.auction.result!,strain:fxMode==="notrump"?"NO_TRUMP":"HEART"};
 if(["cut","fourth","notrump","restored"].includes(fxMode)){
  const n=fxMode==="fourth"?3:fxMode==="restored"?2:1;
  room.game.play.currentTrick=Array.from({length:n},(_,i)=>({seat:(fxSeat+i*3)%4,card:{suit:fxMode==="restored"&&i===1?"HEART":"CLUB",rank:8+i},source:"MANUAL"}));
 }
 room.game.operationTimer={kind:room.phase==="BIDDING"?"BID_TURN":"PLAY_TURN",turnSeat:fxSeat,remainingMs:20000,paused:false};
 room.you!.legalCards=[];
}
fxSetup();
function fxAppend(){
 const play=room.game?.play;if(!play||play.currentTrick.length>=4)return;
 room.phase="PLAYING";
 const i=play.currentTrick.length;
 play.currentTrick.push({seat:(fxSeat+i*3)%4,card:{suit:"HEART",rank:[12,13,14,2][i]},source:params.has("autoplay")?"TIMEOUT":"MANUAL"});
 play.nextSeat=(fxSeat+(i+1)*3)%4;
 room.game!.operationTimer={kind:"PLAY_TURN",turnSeat:play.nextSeat,remainingMs:20000,paused:false};
 if(play.currentTrick.length===4){room.phase="TRICK_RESULT";play.nextSeat=null;room.game!.operationTimer={kind:"TRICK_RESULT",turnSeat:null,remainingMs:2000,paused:false};play.completedTricks.push({number:play.trickNumber,leaderSeat:fxSeat,winnerSeat:(fxSeat+2)%4,cardsVisible:false,plays:[]});play.tricksByTeam[(fxSeat+2)%2===0?"NS":"EW"]++;}
}
export function fxControl(action:string){
 if(!fxMode||!room.game)return;
 if(action==="fx-play")fxAppend();
 if(action==="fx-burst")while(room.game.play!.currentTrick.length<4)fxAppend();
 if(action==="fx-next-wash"){const p=room.game.play!;p.trickNumber++;p.currentTrick=[];room.phase="PLAYING";fxAppend();}
 if(action==="fx-offline")state={...state,status:"offline"};
 if(action==="fx-reconnect"){state={...state,status:"online",connectionEpoch:state.connectionEpoch+1};fxAppend();}
 if(action==="fx-new-deal"){room.game.dealId+="-new";room.game.play!.currentTrick=[];fxAppend();}
 if(action==="fx-clear"){room.phase="DEAL_RESULT";room.game.operationTimer={kind:"DEAL_RESULT",turnSeat:null,remainingMs:90000,paused:false};room.you!.ownHand=[];}
 emit(action);
}
window.addEventListener("message",event=>{if(event.source===window.parent&&event.data?.type==="bridge-qa-effect")fxControl(String(event.data.action));});

// Live review controls publish synthetic snapshots; no actual game commands.
function bidControl(action:string){
 if(!room.game)return;
 const a=room.game.auction;
 if(action==="bid-3-heart"){a.highestBid={seat:1,level:3,strain:"HEART"};a.calls.push({seat:1,type:"BID",level:3,strain:"HEART",source:"MANUAL"});}
 if(action==="bid-pass")a.calls.push({seat:2,type:"PASS",level:null,strain:null,source:"TIMEOUT"});
 if(action==="bid-redeal"){room.game.dealId+="-redeal";room.game.redealCount++;a.highestBid=null;a.calls=[];}
 if(action==="bid-7-nt"){a.highestBid={seat:3,level:7,strain:"NO_TRUMP"};a.calls.push({seat:3,type:"BID",level:7,strain:"NO_TRUMP",source:"MANUAL"});}
 if(action==="bid-other")a.nextSeat=(own+1)%4;
 if(action==="bid-own")a.nextSeat=own;
 if(action==="bid-pause"){room.game.paused=true;room.game.operationTimer!.paused=true;}
 if(action==="bid-resume"){room.game.paused=false;room.game.operationTimer!.paused=false;}
 if(action==="bid-offline")state={...state,status:"offline"};
 if(action==="bid-online")state={...state,status:"online"};
 if(action==="bid-play"){room.phase="PLAYING";room.game.auction.result={...playedRoom().game!.auction.result!,strain:"HEART"};room.game.play=playedRoom().game!.play;room.game.operationTimer={kind:"PLAY_TURN",turnSeat:own,remainingMs:20000,paused:false};}
 emit(action);
}
window.addEventListener("message",event=>{if(event.source===window.parent&&event.data?.type==="bridge-qa-bid")bidControl(String(event.data.action));});
