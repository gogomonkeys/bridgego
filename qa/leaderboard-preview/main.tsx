import React from 'react';
import {createRoot} from 'react-dom/client';
import StatisticsPanel from '../../frontend/src/StatisticsPanel';
import '../../frontend/src/style.css';
const scenario=new URLSearchParams(location.search).get('scenario')??'normal';
// Every request is handled in memory. CSP separately forbids network connections.
window.fetch=async (input:RequestInfo|URL)=>{
 const path=String(input);
 if(path.endsWith('/auth/status'))return new Response(JSON.stringify({signedIn:true,nickname:'測試猴',nicknameRequired:false}));
 if(path.includes('/statistics/leaderboard')){
  const query=new URL(path,location.origin).searchParams;
  const status=scenario==='disabled'?'DISABLED':scenario==='error'?'UNAVAILABLE':'AVAILABLE';
  const entries=scenario==='empty'?[]:[
   {rank:1,nickname:'小白測試員',totalScore:12345,matches:120,wins:80,losses:30,draws:10,winRate:2/3,mine:false},
   {rank:2,nickname:'猴子🐒玩家',totalScore:12345,matches:100,wins:60,losses:40,draws:0,winRate:.6,mine:false},
   {rank:2,nickname:'測試猴',totalScore:12345,matches:10,wins:6,losses:4,draws:0,winRate:.6,mine:true},
   {rank:4,nickname:'Guest不是',totalScore:0,matches:1,wins:0,losses:0,draws:1,winRate:0,mine:false},
   {rank:5,nickname:'低分測試',totalScore:-12345,matches:20,wins:2,losses:18,draws:0,winRate:.1,mine:false}];
  return new Response(JSON.stringify({status,quarter:'2026-Q4',asOf:'2026-11-14T01:00:00Z',quarterEndsAt:'2026-12-31T16:00:00Z',period:query.get('period'),category:query.get('category'),entries:status==='AVAILABLE'?entries:[],unavailableReason:status==='UNAVAILABLE'?'RATE_LIMITED':null,retryAfterSeconds:status==='UNAVAILABLE'?60:null}));
 }
 throw new Error('Unexpected fixture request: '+path);
};
createRoot(document.getElementById('root')!).render(<div className="app"><main className="statistics-page"><div className="help-version"><span className="eyebrow">本地假資料預覽</span></div><StatisticsPanel token="synthetic-only" initialTab="leaderboard" onBack={()=>{}}/></main></div>);
// DOM-based layout check supplements manual screenshots; only geometry/text is recorded.
setTimeout(()=>{const bad=[...document.querySelectorAll('button,li,input,h1,h2,p')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&(r.left < -1 || r.right>innerWidth+1)});const out=document.createElement('output');out.id='qa-result';out.textContent=JSON.stringify({viewport:innerWidth,documentWidth:document.documentElement.scrollWidth,overflow:bad.map(e=>e.textContent?.slice(0,80))});document.body.append(out);},1000);
