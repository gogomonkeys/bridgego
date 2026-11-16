/** Synthetic visual QA only; no credentials, service APIs or production writes. */
import React, {useState} from "react";
import {createRoot} from "react-dom/client";
import TutorialBidding from "../../frontend/src/TutorialBidding";
import TutorialPlay from "../../frontend/src/TutorialPlay";
import GamePanel from "../../frontend/src/GamePanel";
import {tutorialRoom} from "../../frontend/src/tutorial-room";
import type {TutorialSnapshot} from "../../frontend/src/tutorial";
import type {Card, Trick} from "../../frontend/src/model";
import "../../frontend/src/style.css";
const p=new URLSearchParams(location.search);
const hand:Card[]=[{suit:"CLUB",rank:2},{suit:"CLUB",rank:3},{suit:"HEART",rank:2},{suit:"HEART",rank:3},...Array.from({length:9},(_,i)=>({suit:"SPADE" as const,rank:i+2}))];
const trick=(i:number):Trick=>({number:i+1,leaderSeat:1,winnerSeat:3,plays:[1,2,3,0].map((seat,j)=>({seat,card:{suit:(i===0?"CLUB":i===1?"HEART":"DIAMOND") as Card["suit"],rank:j===2?14:j+2},source:"MANUAL"})),cardsVisible:true});
const snap=(n:number):TutorialSnapshot=>({sessionId:"synthetic",revision:n,lesson:(["FOLLOW_SUIT","WIN_TRICK","TRUMP_WHEN_VOID","FREE_PLAY"] as const)[Math.min(n,3)],hand,legalCards:hand.filter(c=>c.suit===(n===0?"CLUB":n===1?"HEART":"SPADE")),completedTricks:Array.from({length:n},(_,i)=>trick(i)),currentTrick:trick(Math.min(n,2)).plays.slice(0,3)});
function Fixture(){const [n,setN]=useState(Number(p.get("play")||0));const [phase,setPhase]=useState(p.get("mode")||"bidding");const [done,setDone]=useState(false);if(done)return <p>已回大廳（假資料測試）</p>;const room=tutorialRoom(snap(n));room.game!.privilege.usedByTeam={NS:true,EW:false};room.game!.auction.result!.holderSeat=Number(p.get("holder")||2);room.game!.auction.result!.teamId=room.game!.auction.result!.holderSeat%2===0?"NS":"EW";if(p.has("long")){room.players.forEach(x=>x.displayName="森林長暱稱玩家");room.game!.roster=room.players;}return <main className="forest-shell match-shell"><section className="panel tutorial-panel">{phase==="table"?<GamePanel room={room} online busy={false} pendingAction={null} snapshotReceivedAt={0} tutorial={{guide:null,onExit:()=>setDone(true),onPlay:async()=>{},onBid:()=>{}}}/>:phase==="bidding"?<TutorialBidding busy={false} onBegin={()=>setPhase("play")} onBack={()=>setDone(true)}/>:<TutorialPlay game={snap(n)} busy={false} onPlay={async()=>setN(Math.min(n+1,3))} onExit={()=>setDone(true)} onReviewIntroduction={()=>setPhase("bidding")}/>}</section></main>}
createRoot(document.getElementById("root")!).render(<Fixture/>);
