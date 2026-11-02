// Actual compiled frontend client + Vite + backend; only platform/state observation adapters.
// No fake timers, mocked fetch/WebSocket or server clock control. Not browser rendering QA.
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

if (!isMainThread) {
  globalThis.location = { protocol: 'http:', host: '127.0.0.1:5173' };
  const saved = new Map(Object.entries(workerData.saved || {}));
  globalThis.localStorage = {
    getItem: key => saved.get(key) ?? null,
    setItem: (key, value) => saved.set(key, value),
    removeItem: key => saved.delete(key),
  };
  globalThis.window = { addEventListener() {} };
  const nativeFetch = globalThis.fetch;
  globalThis.fetch = (url, options) => nativeFetch(new URL(url, 'http://127.0.0.1:5173'), options);
  const { client, useClient } = await import('./client-under-test.mjs');
  parentPort.on('message', async ({ id, command, arg }) => {
    try {
      if (command !== 'state') await client[command](arg);
      parentPort.postMessage({ id, state: useClient(), saved: Object.fromEntries(saved) });
    } catch (error) {
      parentPort.postMessage({ id, error: String(error) });
    }
  });
} else {
  const results = [], workers = [];
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
  const check = (name, passed) => {
    results.push({ case: name, status: passed ? 'PASS' : 'FAIL' });
    console.log(results.at(-1));
    assert(passed, name);
  };
  class Client {
    constructor(saved) {
      this.worker = new Worker(new URL(import.meta.url), { workerData: { saved } });
      this.sequence = 0;
      this.pending = new Map();
      this.worker.on('message', message => {
        this.pending.get(message.id)(message);
        this.pending.delete(message.id);
      });
      workers.push(this.worker);
    }
    call(command, arg) {
      const id = ++this.sequence;
      return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          this.pending.delete(id);
          reject(new Error(`Worker command timed out: ${command}`));
        }, 12000);
        this.pending.set(id, result => {
          clearTimeout(timeout);
          if (result.error) reject(new Error(result.error));
          else resolve(result);
        });
        this.worker.postMessage({ id, command, arg });
      });
    }
    async state() { return (await this.call('state')).state; }
  }
  async function until(predicate, timeoutMs = 10000) {
    const deadline = performance.now() + timeoutMs;
    while (performance.now() < deadline) {
      if (await predicate()) return;
      await wait(100);
    }
    throw new Error(`State predicate timed out after ${timeoutMs} ms`);
  }
  const allStates = clients => Promise.all(clients.map(client => client.state()));
  try {
    const clients = [];
    for (let i = 0; i < 4; i++) {
      const client = new Client();
      await client.call('initialize');
      await client.call('login', `Timer frontend ${i}`);
      clients.push(client);
    }
    await clients[0].call('create');
    const roomId = (await clients[0].state()).room.roomId;
    for (const client of clients.slice(1)) await client.call('join', roomId);
    await until(async () => (await clients[0].state()).room.players.length === 4);
    for (const client of clients) await client.call('ready');
    await until(async () => (await clients[0].state()).room.canStart);
    await clients[0].call('start');
    await until(async () => (await allStates(clients)).every(state => state.room?.phase === 'PRIVILEGE_DECISION'));

    let states = await allStates(clients);
    const firstRemaining = states[0].room.game.operationTimer?.remainingMs;
    const originalDeal = states[0].room.game.dealId;
    check('client receives authoritative shared privilege timer snapshots', states.every(state => {
      const timer = state.room.game.operationTimer;
      return timer?.kind === 'PRIVILEGE' && timer.turnSeat === null && !timer.paused
        && timer.remainingMs > 18000 && timer.remainingMs <= 20000
        && state.snapshotReceivedAt > 0;
    }));

    await wait(1300);
    await clients[0].call('synchronize');
    const synchronized = await clients[0].state();
    const synchronizedRemaining = synchronized.room.game.operationTimer.remainingMs;
    check('client synchronize samples remaining budget without resetting privilege timer',
      synchronized.room.phase === 'PRIVILEGE_DECISION'
      && synchronizedRemaining > 0 && synchronizedRemaining <= firstRemaining - 800);

    const sessionCopy = (await clients[0].call('state')).saved;
    const refreshed = new Client(sessionCopy);
    await refreshed.call('initialize');
    await until(async () => (await refreshed.state()).status === 'online');
    const restored = await refreshed.state();
    check('client refresh-style initialization preserves authoritative timer and deal',
      restored.room.phase === 'PRIVILEGE_DECISION'
      && restored.room.game.dealId === originalDeal
      && restored.room.game.operationTimer.remainingMs > 0
      && restored.room.game.operationTimer.remainingMs <= synchronizedRemaining + 100
      && JSON.stringify(restored.room.you.ownHand) === JSON.stringify(synchronized.room.you.ownHand));

    await clients[0].call('synchronize');
    await clients[0].call('privilege', true);
    await until(async () => (await clients[0].state()).room.game.privilege.yourChoice === true);
    states = await allStates(clients);
    check('YES vote preserves shared budget and does not reveal unresolved opponent choice',
      states[0].room.game.operationTimer.remainingMs <= synchronizedRemaining + 100
      && states[0].room.game.privilege.yourChoice === true
      && states[0].room.game.privilege.yourChoiceSource === 'MANUAL'
      && states[1].room.game.privilege.usedByTeam === null);

    // Let the real server phase deadline expire; do not submit the remaining votes.
    await until(async () => (await allStates(clients)).every(state => state.room?.phase === 'BIDDING'), 25000);
    states = await allStates(clients);
    check('natural privilege expiry preserves YES and defaults unresolved team to NO', states.every(state =>
      state.room.game.privilege.status === 'REVEALED'
      && state.room.game.privilege.usedByTeam.NS === true
      && state.room.game.privilege.usedByTeam.EW === false)
      && states[0].room.game.privilege.yourChoice === true
      && states[0].room.game.privilege.yourChoiceSource === 'MANUAL'
      && states[1].room.game.privilege.yourChoice === false
      && states[1].room.game.privilege.yourChoiceSource === 'TIMEOUT');
    const firstBidRemaining = states[0].room.game.operationTimer?.remainingMs;
    check('natural privilege expiry starts authoritative first bid turn budget', states.every(state => {
      const timer = state.room.game.operationTimer;
      return timer?.kind === 'BID_TURN' && timer.turnSeat === 0 && !timer.paused
        && timer.remainingMs > 18000 && timer.remainingMs <= 20000;
    }));

    await wait(1300);
    await clients[1].call('synchronize');
    const bidSynchronized = await clients[1].state();
    check('client synchronize does not restart current bid turn',
      bidSynchronized.room.game.auction.nextSeat === 0
      && bidSynchronized.room.game.operationTimer.remainingMs > 0
      && bidSynchronized.room.game.operationTimer.remainingMs <= firstBidRemaining - 800);

    // Let the first player's real 20-second bid timer expire without an action.
    await until(async () => (await allStates(clients)).every(state => state.room?.game.auction.calls.length === 1), 25000);
    states = await allStates(clients);
    check('natural bid expiry broadcasts exactly one PASS marked TIMEOUT', states.every(state => {
      const call = state.room.game.auction.calls[0];
      return call.seat === 0 && call.type === 'PASS' && call.source === 'TIMEOUT'
        && call.level === null && call.strain === null;
    }));
    check('timeout PASS advances turn with fresh budget without scoring or redealing', states.every(state => {
      const timer = state.room.game.operationTimer;
      return state.room.phase === 'BIDDING' && state.room.game.auction.nextSeat === 1
        && state.room.game.dealId === originalDeal && state.room.game.completedDeals === 0
        && state.room.game.completedDealResults.length === 0
        && timer?.kind === 'BID_TURN' && timer.turnSeat === 1
        && timer.remainingMs > 18000 && timer.remainingMs <= 20000;
    }));
  } finally {
    await Promise.all(workers.map(worker => worker.terminate()));
    await writeFile(new URL('./client-timer-results.json', import.meta.url), JSON.stringify(results, null, 2) + '\n');
  }
}
