// Actual frontend client + real Vite/backend. Platform shims only; not browser rendering.
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

if (!isMainThread) {
  globalThis.location = { protocol: 'http:', host: '127.0.0.1:5173' };
  const data = new Map(Object.entries(workerData.saved || {}));
  globalThis.localStorage = { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k) };
  globalThis.window = { addEventListener() {} };
  const nativeFetch = globalThis.fetch;
  globalThis.fetch = (url, options) => nativeFetch(new URL(url, 'http://127.0.0.1:5173'), options);
  const { client, useClient } = await import('./client-under-test.mjs');
  parentPort.on('message', async ({ id, command, arg }) => {
    try {
      if (command !== 'state') await client[command](arg);
      parentPort.postMessage({ id, state: useClient(), saved: Object.fromEntries(data) });
    } catch (error) { parentPort.postMessage({ id, error: String(error) }); }
  });
} else {
  const results = [], workers = [], wait = ms => new Promise(resolve => setTimeout(resolve, ms));
  const check = (name, passed) => {
    results.push({ case: name, status: passed ? 'PASS' : 'FAIL' });
    console.log(results.at(-1));
    assert(passed, name);
  };
  class Client {
    constructor(saved) {
      this.w = new Worker(new URL(import.meta.url), { workerData: { saved } });
      this.sequence = 0;
      this.pending = new Map();
      this.w.on('message', message => {
        const resolve = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (resolve) resolve(message);
      });
      workers.push(this.w);
    }
    call(command, arg) {
      const id = ++this.sequence;
      return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => { this.pending.delete(id); reject(new Error(`Worker command timed out: ${command}`)); }, 12000);
        this.pending.set(id, result => { clearTimeout(timeout); if (result.error) reject(new Error(result.error)); else resolve(result); });
        this.w.postMessage({ id, command, arg });
      });
    }
    async state() { return (await this.call('state')).state; }
  }
  async function until(predicate) {
    for (let i = 0; i < 100; i++) { if (await predicate()) return; await wait(100); }
    throw new Error('State condition timed out');
  }
  const statesOf = clients => Promise.all(clients.map(client => client.state()));
  const key = card => `${card.suit}:${card.rank}`;
  async function action(client, command, arg) {
    await client.call('synchronize');
    const result = await client.call(command, arg);
    assert(!result.state.error, `Actual client ${command} failed: ${result.state.error}`);
    return result.state;
  }
  try {
    const clients=[];
    for(let i=0;i<4;i++){const c=new Client();await c.call('initialize');await c.call('login','Idle play '+i);clients.push(c);}
    await clients[0].call('create');const roomId=(await clients[0].state()).room.roomId;
    for(const c of clients.slice(1))await c.call('join',roomId);
    await until(async()=> (await clients[0].state()).room.players.length===4);
    for(const c of clients)await c.call('ready');
    await until(async()=> (await clients[0].state()).room.canStart);
    await action(clients[0],'start');
    await action(clients[0],'privilege',true);await action(clients[1],'privilege',true);
    await action(clients[0],'call',{type:'BID',level:1,strain:'HEART'});
    for(let i=1;i<4;i++)await action(clients[i],'call',{type:'PASS',level:null,strain:null});
    await until(async()=> (await statesOf(clients)).every(s=>s.room.phase==='PLAYING'));
    const initial=await statesOf(clients),dealId=initial[0].room.game.dealId;
    const hands=initial.map(s=>s.room.you.ownHand),allCards=hands.flat().map(key);
    check('idle-play soak starts four connected private hands',new Set(allCards).size===52);
    const duration=Number(process.env.QA_GAME_IDLE_SECONDS||60),end=Date.now()+duration*1000;
    const observedTricks=Array.from({length:4},()=>new Map());
    let snapshots=0,lastCount=0,maxSampleGapMs=0,lastSample=performance.now();
    while(Date.now()<end){
      await wait(1000);maxSampleGapMs=Math.max(maxSampleGapMs,performance.now()-lastSample);lastSample=performance.now();
      const states=await statesOf(clients);
      for(let i=0;i<4;i++){
        const s=states[i],r=s.room,g=r.game,p=g.play;
        assert(s.status==='online'&&['PLAYING','TRICK_RESULT'].includes(r.phase)&&!g.paused&&!g.matchResult&&r.players.length===4&&r.players.every(p=>p.connected));
        assert(g.dealId===dealId&&g.completedDeals===0);
        assert(p.completedTricks.every(t=>t.cardsVisible===false && t.plays.length===0));
        const seen=observedTricks[i];
        if(p.currentTrick.length) seen.set(p.trickNumber,p.currentTrick);
        const liveTricks=[...seen.entries()].sort((a,b)=>a[0]-b[0]).map(([,plays])=>plays);
        const played=liveTricks.flat();
        const expectedCount=p.completedTricks.length*4+(r.phase==='TRICK_RESULT'?0:p.currentTrick.length);
        assert.equal(played.length,expectedCount,'live observations must cover completed and current plays');
        assert(new Set(played.map(p=>key(p.card))).size===played.length);
        assert(played.every(p=>p.source==='TIMEOUT'&&hands[p.seat].some(c=>key(c)===key(p.card))));
        const expected=hands[i].filter(c=>!played.some(p=>p.seat===i&&key(p.card)===key(c))).map(key).sort();
        assert.deepEqual(r.you.ownHand.map(key).sort(),expected);
        // Replay public history against each independent initial hand: every automatic card
        // must follow suit and be rank-lowest, suit-order tie break, at that exact turn.
        const remaining=hands.map(h=>h.slice()),suits=['CLUB','DIAMOND','HEART','SPADE'];
        const compare=(a,b)=>a.rank-b.rank||suits.indexOf(a.suit)-suits.indexOf(b.suit);
        for(const trick of liveTricks){
          for(let j=0;j<trick.length;j++){
            const move=trick[j],led=trick[0].card.suit;
            const following=j?remaining[move.seat].filter(c=>c.suit===led):[];
            const legal=(following.length?following:remaining[move.seat]).slice().sort(compare);
            assert.equal(key(move.card),key(legal[0]));
            remaining[move.seat]=remaining[move.seat].filter(c=>key(c)!==key(move.card));
          }
        }
        snapshots++;if(i===0){assert(played.length>=lastCount);lastCount=played.length;}
      }
    }
    check('timed active-play clients stay connected throughout idle soak',snapshots>=duration*3);
    check('natural idle actions remain legal unique private and unscored',lastCount>=Math.floor(duration/20.5)-1&&lastCount<=Math.floor(duration/20)+1);
    results.at(-1).duration_seconds=duration;results.at(-1).automatic_plays=lastCount;results.at(-1).sampled_views=snapshots;results.at(-1).max_sample_gap_ms=Math.round(maxSampleGapMs);
  } finally {
    await Promise.all(workers.map(w=>w.terminate()));
    await writeFile(new URL(process.env.QA_RESULT_FILE||'./client-auto-play-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');
  }
}
