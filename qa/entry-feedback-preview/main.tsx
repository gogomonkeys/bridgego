/** Synthetic QA only. All requests are intercepted locally; never reaches a backend or Google. */
import React,{useEffect,useState} from "react";
import {createRoot} from "react-dom/client";
import WelcomeEntry from "../../frontend/src/WelcomeEntry";
import {getClientState} from "./client-stub";
import "../../frontend/src/style.css";
const mode=new URLSearchParams(location.search).get("mode")||"ready";
let saved=false;
window.fetch=async(input,init)=>{
 const path=String(input);
 const json=(value:unknown,status=200,headers={})=>new Response(JSON.stringify(value),{status,headers:{"Content-Type":"application/json",...headers}});
 if(path==="/health/ready") {
  if(mode==="connecting") return new Promise((_resolve,reject)=>{init?.signal?.addEventListener("abort",()=>reject(new DOMException("preview cancellation","AbortError")),{once:true});});
  if(mode==="rate-limit")return json({},429,{"Retry-After":"180"});
  if(mode==="stopping")return json({status:"stopping"},503);
  return json({status:"ready",version:"synthetic",instanceEpoch:"synthetic-entry"});
 }
 if(path==="/api/statistics/config")return json({enabled:true,consentVersion:"statistics-v4"});
 if(path==="/api/auth/config") {
  if(mode==="google-failure")throw Error("synthetic Google configuration failure");
  return json({enabled:true,clientId:"synthetic",verificationEnabled:true,collectionMode:"managed",collectionReady:true,privacyContact:"fixture@example.test",retention:"quarter"});
 }
 if(path==="/api/auth/status")return json({signedIn:true,nicknameRequired:!saved,nickname:saved?"森林小白":null});
 if(path==="/api/auth/nickname"){saved=true;return json({signedIn:true,nickname:"森林小白",nicknameRequired:false});}
 if(path==="/api/auth/logout")return json({signedIn:false});
 if(path==="/api/sessions/nickname")return json({...getClientState().session,displayName:JSON.parse(String(init?.body)).displayName});
 if(path==="/api/statistics/consent")return json({accepted:true});
 throw Error("Blocked synthetic preview request: "+path);
};
function Fixture(){
 const [entered,setEntered]=useState(false);
 useEffect(()=>{
  if(!["guest-nickname","google-nickname"].includes(mode))return;
  const timer=setInterval(()=>{
    const label=mode==="guest-nickname"?"以訪客身分遊玩":"使用 Google 登入";
    const button=Array.from(document.querySelectorAll("button")).find(button=>button.textContent===label);
    if(button&&!button.disabled){clearInterval(timer);button.click();}
  },100);
  return()=>clearInterval(timer);
 },[]);
 return <main className="forest-shell"><header className="forest-header"><div className="brand"><strong>BridgeGo</strong><small>隔離假資料預覽 · {mode}</small></div></header>{entered?<section className="panel"><h2>已進入大廳（假資料）</h2><p>未連線任何正式服務。</p></section>:<WelcomeEntry session={getClientState().session} busy={false} onEnter={()=>setEntered(true)}/>}</main>;
}
createRoot(document.getElementById("root")!).render(<Fixture/>);
