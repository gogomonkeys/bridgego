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
  function validCompletedDeal(result, dealIndex, dealerSeat) {
    if (!result || result.dealIndex !== dealIndex || result.dealerSeat !== dealerSeat || result.completedTricks.length !== 13
      || result.contract.holderSeat !== dealerSeat || result.contract.teamId !== (dealerSeat % 2 === 0 ? 'NS' : 'EW')) return false;
    const plays = result.completedTricks.flatMap(trick => trick.plays);
    if (plays.length !== 52 || new Set(plays.map(play => key(play.card))).size !== 52 || plays.some(play => play.source !== 'MANUAL')) return false;
    const wins = { NS: 0, EW: 0 };
    let expectedLeader = (result.contract.holderSeat + 3) % 4;
    for (const [index, trick] of result.completedTricks.entries()) {
      if (trick.number !== index + 1 || trick.leaderSeat !== expectedLeader || trick.plays.length !== 4) return false;
      if (trick.plays.some((play, offset) => play.seat !== (expectedLeader + 3 * offset) % 4)) return false;
      const trump = result.contract.strain;
      const winningSuit = trump !== 'NO_TRUMP' && trick.plays.some(play => play.card.suit === trump) ? trump : trick.plays[0].card.suit;
      const eligible = trick.plays.filter(play => play.card.suit === winningSuit).sort((a, b) => b.card.rank - a.card.rank);
      if (trick.winnerSeat !== eligible[0].seat) return false;
      expectedLeader = trick.winnerSeat;
      wins[trick.winnerSeat % 2 === 0 ? 'NS' : 'EW']++;
    }
    if (wins.NS !== result.tricksByTeam.NS || wins.EW !== result.tricksByTeam.EW) return false;
    const attack = result.contract.teamId, defence = attack === 'NS' ? 'EW' : 'NS';
    const level = result.contract.level, tricks = wins[attack], won = tricks >= level + 6;
    const ordinary = !won ? -level : tricks === level + 6 ? 2 * level + 1 : level;
    return result.privilegeUsed.NS === true && result.privilegeUsed.EW === true
      && result.scores[attack].total === ordinary + (won ? 2 : -1)
      && result.scores[defence].total === (won ? -1 : 2);
  }

  try {
    const clients = [];
    for (let i = 0; i < 4; i++) {
      const client = new Client();
      await client.call('initialize'); await client.call('login', `Game frontend ${i}`); clients.push(client);
    }
    await clients[0].call('create');
    const roomId = (await clients[0].state()).room.roomId;
    for (const client of clients.slice(1)) await client.call('join', roomId);
    await until(async () => (await clients[0].state()).room.players.length === 4);
    for (const client of clients) await client.call('ready');
    await until(async () => (await clients[0].state()).room.canStart);
    await action(clients[0], 'start');
    let states, matchGameId, previousDealId = null;

    for (let dealIndex = 1; dealIndex <= 3; dealIndex++) {
      await until(async () => (await statesOf(clients)).every(state => state.room?.phase === 'PRIVILEGE_DECISION' && state.room.game.dealIndex === dealIndex));
      states = await statesOf(clients);
      const dealId = states[0].room.game.dealId, dealer = states[0].room.game.dealerSeat;
      matchGameId ??= states[0].room.game.gameId;
      const allCards = states.flatMap(state => state.room.you.ownHand.map(key));
      check(`actual frontend deal ${dealIndex} receives four private disjoint hands`,
        states.every(state => state.room.you.playerId === state.session.playerId && state.room.you.ownHand.length === 13
          && state.room.game.gameId === matchGameId && state.room.game.completedDeals === dealIndex - 1)
        && new Set(allCards).size === 52 && dealer === dealIndex - 1 && dealId !== previousDealId);
      await action(clients[0], 'privilege', true);
      await until(async () => (await clients[2].state()).room.game.privilege.canChoose === false);
      check(`actual frontend deal ${dealIndex} hides opponent privilege until joint reveal`, (await clients[1].state()).room.game.privilege.usedByTeam === null);
      await action(clients[1], 'privilege', true);
      await until(async () => (await statesOf(clients)).every(state => state.room.phase === 'BIDDING'));
      check(`actual frontend deal ${dealIndex} reveals both privileges together`, (await statesOf(clients)).every(state => state.room.game.privilege.usedByTeam.NS && state.room.game.privilege.usedByTeam.EW));
      const strain = ['CLUB', 'NO_TRUMP', 'SPADE'][dealIndex - 1];
      await action(clients[dealer], 'call', { type: 'BID', level: dealIndex, strain });
      for (let offset = 1; offset < 4; offset++) {
        const seat = (dealer + offset) % 4;
        await until(async () => (await clients[seat].state()).room.game.auction.nextSeat === seat);
        await action(clients[seat], 'call', { type: 'PASS', level: null, strain: null });
      }
      await until(async () => (await statesOf(clients)).every(state => state.room.phase === 'PLAYING'));
      states = await statesOf(clients);
      check(`actual frontend deal ${dealIndex} auction begins authoritative play without premature scoring`, states.every(state =>
        state.room.game.auction.result.level === dealIndex && state.room.game.auction.result.strain === strain
        && state.room.game.auction.result.holderSeat === dealer && state.room.game.auction.result.teamId === (dealer % 2 === 0 ? 'NS' : 'EW')
        && state.room.game.play.nextSeat === (dealer + 3) % 4 && state.room.game.completedDeals === dealIndex - 1
        && state.room.game.operationTimer.kind === 'PLAY_TURN'));

      if (dealIndex === 1) {
        const saved = (await clients[1].call('state')).saved;
        const ownHand = states[1].room.you.ownHand;
        await clients[1].w.terminate();
        await until(async () => (await clients[0].state()).room.game.paused);
        check('actual frontend active loss pauses play without releasing the private seat', (await clients[0].state()).room.players.length === 4 && (await clients[0].state()).room.game.play.nextSeat === 1);
        clients[1] = new Client(saved); await clients[1].call('initialize');
        await until(async () => (await clients[1].state()).status === 'online');
        check('actual frontend active initialization resumes same deal and private hand', (await clients[1].state()).room.game.dealId === dealId && JSON.stringify((await clients[1].state()).room.you.ownHand) === JSON.stringify(ownHand));
        await until(async () => !(await clients[0].state()).room.game.paused);
        check('actual frontend all connected resumes the preserved play turn', (await clients[0].state()).room.phase === 'PLAYING' && (await clients[0].state()).room.game.play.nextSeat === 1);
      }

      // Play all actual legal cards through the exported client action; never fabricate results.
      for (let playNumber = 0; playNumber < 52; playNumber++) {
        await until(async () => {
          await clients[0].call('synchronize');
          return (await clients[0].state()).room.phase === 'PLAYING';
        });
        const view = (await clients[0].state()).room;
        assert.equal(view.phase, 'PLAYING', `Deal ${dealIndex} must remain playing before card ${playNumber + 1}`);
        const actor = clients[view.game.play.nextSeat];
        await actor.call('synchronize');
        const legalCards = (await actor.state()).room.you.legalCards;
        assert(legalCards.length > 0, 'Current actor must receive viewer-only legal cards');
        await action(actor, 'play', legalCards[0]);
      }
      await until(async () => (await statesOf(clients)).every(state => state.room.game.completedDeals === dealIndex && state.room.phase !== 'TRICK_RESULT'));
      states = await statesOf(clients);
      check(`actual frontend deal ${dealIndex} persists 13 tricks and independently correct custom score`, states.every(state => validCompletedDeal(state.room.game.completedDealResults[dealIndex - 1], dealIndex, dealer)));
      if (dealIndex < 3) {
        check(`actual frontend deal ${dealIndex} holds its result without new hands or privilege timer`, states.every(state => state.room.phase === 'DEAL_RESULT'
          && state.room.game.dealIndex === dealIndex && state.room.game.dealId === dealId
          && state.room.you.ownHand.length === 0 && state.room.you.legalCards.length === 0
          && state.room.you.canAcknowledgeResult && state.room.game.resultAcknowledgedSeats.length === 0
          && state.room.game.operationTimer.kind === 'DEAL_RESULT' && state.room.game.operationTimer.remainingMs > 0));
        const resultBudget = states[0].room.game.operationTimer.remainingMs;
        for (const client of clients.slice(0, 3)) await action(client, 'acknowledgeResult');
        await action(clients[0], 'acknowledgeResult'); // Fresh-ID repeat must be a harmless no-op.
        await clients[0].call('synchronize');
        const partial = (await clients[0].state()).room;
        check(`actual frontend deal ${dealIndex} partial and repeated acknowledgments do not advance or reset timer`,
          partial.phase === 'DEAL_RESULT' && partial.game.dealId === dealId && !partial.you.canAcknowledgeResult
          && JSON.stringify([...partial.game.resultAcknowledgedSeats].sort()) === '[0,1,2]'
          && partial.game.operationTimer.remainingMs <= resultBudget && partial.you.ownHand.length === 0);
        await action(clients[3], 'acknowledgeResult');
        await until(async () => (await statesOf(clients)).every(state => state.room.phase === 'PRIVILEGE_DECISION'));
        states = await statesOf(clients);
        check(`actual frontend deal ${dealIndex} fourth acknowledgment advances exactly once with fresh private hands`, states.every(state =>
          state.room.game.dealIndex === dealIndex + 1 && state.room.game.dealerSeat === (dealer + 1) % 4
          && state.room.game.dealId !== dealId && state.room.game.completedDealResults.length === dealIndex
          && state.room.game.resultAcknowledgedSeats.length === 0 && state.room.you.ownHand.length === 13
          && state.room.game.operationTimer.kind === 'PRIVILEGE' && state.room.game.operationTimer.remainingMs > 15000));
      }
      previousDealId = dealId;
    }

    states = await statesOf(clients);
    const expectedTotals = states[0].room.game.completedDealResults.reduce((total, deal) => ({ NS: total.NS + deal.scores.NS.total, EW: total.EW + deal.scores.EW.total }), { NS: 0, EW: 0 });
    const expectedWinner = expectedTotals.NS === expectedTotals.EW ? null : expectedTotals.NS > expectedTotals.EW ? 'NS' : 'EW';
    check('actual frontend third deal retains a scored match result with summed totals', states.every(state => state.room.phase === 'MATCH_RESULT'
      && state.room.game.completedDeals === 3 && state.room.game.completedDealResults.length === 3
      && state.room.game.operationTimer === null && state.room.game.matchResult.outcome === 'SCORED'
      && state.room.game.matchResult.reason === 'THREE_DEALS_COMPLETE' && state.room.game.matchResult.winnerTeamId === expectedWinner
      && state.room.game.matchResult.failedTeamIds.length === 0
      && state.room.game.teamTotals.NS === expectedTotals.NS && state.room.game.teamTotals.EW === expectedTotals.EW));
    const retainedResult = JSON.stringify(states[0].room.game.matchResult);
    const idleSeconds = Number(process.env.QA_GAME_IDLE_SECONDS || 0);
    if (idleSeconds) {
      await wait(idleSeconds * 1000);
      check('match-result soak stays connected with no timer or extra settlement', (await statesOf(clients)).every(state => state.status === 'online'
        && state.room.phase === 'MATCH_RESULT' && state.room.game.operationTimer === null && state.room.game.completedDeals === 3
        && state.room.game.completedDealResults.length === 3 && JSON.stringify(state.room.game.matchResult) === retainedResult
        && state.room.game.teamTotals.NS === expectedTotals.NS && state.room.game.teamTotals.EW === expectedTotals.EW));
    }
    const saved = (await clients[1].call('state')).saved;
    await clients[1].w.terminate(); await wait(400);
    check('actual frontend terminal disconnect retains membership and scored outcome', (await clients[0].state()).room.phase === 'MATCH_RESULT'
      && (await clients[0].state()).room.players.length === 4 && JSON.stringify((await clients[0].state()).room.game.matchResult) === retainedResult);
    clients[1] = new Client(saved); await clients[1].call('initialize');
    await until(async () => (await clients[1].state()).status === 'online');
    check('actual frontend terminal initialization restores the retained match result', (await clients[1].state()).room.phase === 'MATCH_RESULT'
      && JSON.stringify((await clients[1].state()).room.game.matchResult) === retainedResult);
    await clients[2].call('leave');
    await until(async () => (await clients[0].state()).room.players.length === 3);
    check('actual frontend result-screen leave returns to lobby without rewriting outcome', (await clients[2].state()).room === null
      && JSON.stringify((await clients[0].state()).room.game.matchResult) === retainedResult);

    await action(clients[0], 'rematch');
    await until(async () => (await clients[0].state()).room.phase === 'WAITING');
    const waiting = (await clients[0].state()).room;
    check('actual frontend host rematch retains online seats, clears ready and archives the terminal match',
      waiting.game === null && waiting.players.length === 3 && waiting.players.every(player => player.connected && !player.ready)
      && JSON.stringify(waiting.players.map(player => player.seat).sort()) === '[0,1,3]'
      && waiting.lastMatch.gameId === matchGameId && waiting.lastMatch.completedDealResults.length === 3
      && JSON.stringify(waiting.lastMatch.matchResult) === retainedResult
      && waiting.lastMatch.teamTotals.NS === expectedTotals.NS && waiting.lastMatch.teamTotals.EW === expectedTotals.EW);
    const newcomer = new Client();
    await newcomer.call('initialize'); await newcomer.call('login', 'New match guest');
    await action(newcomer, 'join', roomId);
    await until(async () => (await newcomer.state()).status === 'online');
    check('actual frontend replacement guest cannot see the old participants archive',
      (await newcomer.state()).room.you.seat === 2 && (await newcomer.state()).room.lastMatch === null
      && (await clients[0].state()).room.lastMatch.gameId === matchGameId);
    const nextClients = [clients[0], clients[1], newcomer, clients[3]];
    for (const client of nextClients) await action(client, 'ready');
    await until(async () => (await clients[0].state()).room.canStart);
    await action(clients[0], 'start');
    await until(async () => (await statesOf(nextClients)).every(state => state.room.phase === 'PRIVILEGE_DECISION'));
    const nextStates = await statesOf(nextClients);
    check('actual frontend explicit ready and start create a separate match without rewriting original archive',
      nextStates.every(state => state.room.game.gameId !== matchGameId && state.room.game.dealIndex === 1
        && state.room.game.completedDeals === 0 && state.room.game.completedDealResults.length === 0
        && state.room.game.teamTotals.NS === 0 && state.room.game.teamTotals.EW === 0 && state.room.you.ownHand.length === 13)
      && nextStates[0].room.lastMatch.gameId === matchGameId && nextStates[2].room.lastMatch === null
      && JSON.stringify(nextStates[0].room.lastMatch.matchResult) === retainedResult);
  } finally {
    await Promise.all(workers.map(worker => worker.terminate()));
    await writeFile(new URL('./client-game-results.json', import.meta.url), JSON.stringify(results, null, 2) + '\n');
  }
}
