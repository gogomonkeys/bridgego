// Real frontend source + real Vite/backend; platform shims, NOT browser rendering.
import {Worker,isMainThread,parentPort,workerData} from 'node:worker_threads';
import {writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
if(!isMainThread){
 globalThis.location={protocol:'http:',host:'127.0.0.1:5173'};
 const data=new Map(Object.entries(workerData.saved||{}));
 globalThis.localStorage={getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k)};
 globalThis.window={addEventListener(){}};
 const rawFetch=globalThis.fetch;globalThis.fetch=(url,opts)=>rawFetch(new URL(url,'http://127.0.0.1:5173'),opts);
 const {client,useClient}=await import('./client-under-test.mjs');
 parentPort.on('message',async({id,command,arg})=>{try{if(command!=='state')await client[command](arg);parentPort.postMessage({id,state:useClient(),saved:Object.fromEntries(data)});}catch(e){parentPort.postMessage({id,error:String(e)});}});
}else{
 const results=[],workers=[],wait=ms=>new Promise(r=>setTimeout(r,ms));
 const check=(name,condition)=>{results.push({case:name,status:condition?'PASS':'FAIL'});console.log(results.at(-1));assert(condition,name);};
 class Client {
  constructor(saved){this.w=new Worker(new URL(import.meta.url),{workerData:{saved}});this.seq=0;this.pending=new Map();this.w.on('message',m=>{this.pending.get(m.id)(m);this.pending.delete(m.id);});workers.push(this.w);}
  async call(command,arg){const id=++this.seq;return new Promise(resolve=>{this.pending.set(id,resolve);this.w.postMessage({id,command,arg});});}
  async state(){return (await this.call('state')).state;}
 }
 async function until(f){for(let i=0;i<100;i++){if(await f())return;await wait(100);}throw Error('state condition timed out');}
 try{
  const clients=[];
  for(let i=0;i<5;i++){const c=new Client();await c.call('initialize');await c.call('login','Frontend QA '+i);clients.push(c);}
  await clients[0].call('create');const roomId=(await clients[0].state()).room.roomId;
  for(const c of clients.slice(1,4))await c.call('join',roomId);
  await until(async()=>{const s=await Promise.all(clients.slice(0,4).map(c=>c.state()));return s.every(x=>x.status==='online'&&x.room?.players.length===4);});
  let states=await Promise.all(clients.slice(0,4).map(c=>c.state()));
  check('actual frontend four independent clients via Vite HTTP+WS proxy',states.every(s=>s.room.roomId===roomId)&&new Set(states.map(s=>s.session.playerId)).size===4);
  for(const c of clients.slice(0,4))await c.call('ready');
  await until(async()=> (await clients[0].state()).room.allReady);
  states=await Promise.all(clients.slice(0,4).map(c=>c.state()));
  check('actual frontend receives consistent all-ready snapshots',states.every(s=>JSON.stringify({...s.room,you:null,canStart:false})===JSON.stringify({...states[0].room,you:null,canStart:false})&&s.room.phase==='WAITING'));
  const idleSeconds=Number(process.env.QA_IDLE_SECONDS||0);
  if(idleSeconds){
   await wait(idleSeconds*1000);
   const idleStates=await Promise.all(clients.slice(0,4).map(c=>c.state()));
   check('actual frontend clients remain ready and connected across extended idle',idleStates.every(s=>s.status==='online'&&s.room?.allReady&&s.room.players.length===4));
  }
  await clients[4].call('join',roomId);check('frontend room-full server error',(await clients[4].state()).error?.includes('房間已滿'));
  const saved=(await clients[0].call('state')).saved;
  const restored=new Client(saved);await restored.call('initialize');await until(async()=> (await restored.state()).status==='online');
  check('frontend stored session restores same player and ready',(await restored.state()).session.playerId===states[0].session.playerId&&(await restored.state()).room.players.find(p=>p.playerId===states[0].session.playerId).ready);
  check('same-session multi-tab uses one seat',(await restored.state()).room.players.length===4);
  await clients[0].w.terminate();await wait(500);
  check('remaining tab keeps player connected',(await restored.state()).room.players.find(p=>p.playerId===states[0].session.playerId).connected);
  const reconnectSaved=(await restored.call('state')).saved;
  await restored.w.terminate();
  await until(async()=> (await clients[1].state()).room.players.length===3);
  check('frontend snapshot releases last disconnected tab seat',(await clients[1].state()).room.players.length===3);
  await clients[4].call('join',roomId);await until(async()=> (await clients[4].state()).status==='online');
  check('frontend replacement immediately takes vacant seat',(await clients[4].state()).room.players.length===4);
  const reconnected=new Client(reconnectSaved);await reconnected.call('initialize');
  check('frontend stored identity has no guaranteed former membership',(await reconnected.state()).room===null);
  await reconnected.call('join',roomId);
  check('frontend original player sees full room instead of reclaiming seat',(await reconnected.state()).error?.includes('房間已滿'));
  await clients[4].call('leave');await until(async()=> (await clients[1].state()).room.players.length===3);
  check('frontend manual leave clears room immediately',(await clients[4].state()).room===null&&(await clients[4].state()).notice.includes('立即清除'));
  await reconnected.call('join',roomId);await until(async()=> (await reconnected.state()).status==='online');
  check('frontend fresh rejoin resets ready',!(await reconnected.state()).room.players.find(p=>p.playerId===states[0].session.playerId).ready);
  const invalid=new Client({'bridgego.phase1.guest':JSON.stringify({token:'invalid-synthetic-token',playerId:'none',displayName:'invalid'})});await invalid.call('initialize');
  const bad=await invalid.call('state');check('frontend invalid stored session cleared after401',bad.state.session===null&&!bad.saved['bridgego.phase1.guest']);
 }finally{await Promise.all(workers.map(w=>w.terminate()));await writeFile(new URL(process.env.QA_RESULT_FILE||'./client-transport-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');}
}
