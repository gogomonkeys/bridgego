// No network, real Google identities, tokens or spreadsheet data. Run with node --test.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/java/com/bridge/maintenance/ExperimentBootstrapPage.java'), 'utf8');
const js = source.split('static final String JS="""\n')[1].split('\n""";')[0];
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function fixture(options = {}) {
  let now = Date.parse('2030-01-01T00:00:00Z'), serial = 0;
  const timers = new Map(), handlers = {}, callbacks = [], requests = [], elements = {};
  for (const id of ['consent', 'confirmation', 'understood', 'execute', 'preview', 'cancel', 'copy', 'google', 'status', 'plan', 'counts', 'phrase']) elements[id] = {
    checked: false, disabled: false, hidden: true, value: '', textContent: '', readOnly: false,
    handlers: {}, addEventListener(name, fn) { this.handlers[name] = fn; }, replaceChildren() {},
  };
  elements.consent.checked = true;
  const plan = { planId: 'synthetic-plan', target: 'synthetic-sheet', dataset: 'synthetic-dataset', confirmationText: 'INITIALIZE EMPTY SYNTHETIC PLAN', expiresAt: new Date(now + 300000).toISOString() };
  class FakeDate extends Date { static now() { return now; } }
  const context = {
    Date: FakeDate, AbortController, navigator: { clipboard: { writeText: async text => { if (options.clipboardFails) throw Error('denied'); context.copied = text; } } },
    setTimeout(fn, ms) { const id = ++serial; timers.set(id, { fn, when: now + ms }); return id; }, clearTimeout(id) { timers.delete(id); },
    addEventListener(name, fn) { handlers[name] = fn; },
    document: { getElementById: id => elements[id], createElement: () => ({ remove() {} }), head: { append(s) { if (!options.scriptHangs) s.onload(); } } },
    google: { accounts: { id: { initialize(settings) { callbacks.push(settings.callback); }, renderButton() { if (options.renderFails) throw Error('render failed'); }, cancel() { if (options.cancelFails) throw Error('cancel failed'); } } } },
    async fetch(url, request) {
      const endpoint = url.split('/').pop(); requests.push({ endpoint, body: JSON.parse(request.body) });
      let pending = options[endpoint];
      if (pending) {
        pending = typeof pending === 'function' ? pending() : pending;
        return Promise.race([pending, new Promise((_, reject) => request.signal.addEventListener('abort', () => { const e = Error('aborted'); e.name = 'AbortError'; reject(e); }))]);
      }
      const value = endpoint === 'session' ? { clientId: 'synthetic-client', executionEnabled: true, writerStoppedAcknowledged: true, mode: 'experiment-bootstrap' } : endpoint === 'challenge' ? { nonce: 'synthetic-nonce' } : endpoint === 'preview' ? plan : { outcome: options.outcome || 'VERIFIED' };
      return { ok: true, json: async () => value };
    },
  };
  vm.createContext(context); vm.runInContext(js, context);
  return {
    elements, requests, callbacks, plan, context, handlers,
    state: name => vm.runInContext(name, context),
    start: purpose => vm.runInContext(`start('${purpose}')`, context),
    click: id => elements[id].handlers.click(),
    async authenticate(index = callbacks.length - 1) { const result = { credential: 'synthetic-proof' }; await callbacks[index](result); assert.equal(result.credential, ''); },
    async advance(ms) { now += ms; for (const [id, timer] of [...timers]) if (timers.has(id) && timer.when <= now) { timers.delete(id); timer.fn(); } await flush(); },
    async ready() { await this.start('preview'); await this.authenticate(); await this.click('copy'); elements.understood.checked = true; elements.understood.handlers.change(); assert.equal(elements.execute.disabled, false); },
  };
}
test('mobile page has steps, multiline confirmation, explicit cancel and copy', () => {
  for (const text of ['id="cancel"', 'id="copy"', '<textarea', '成功後會立即送出初始化', '2. 核對初始化範圍']) assert.ok(source.includes(text));
});
test('preview is read-only, copy fills all text, one explicit execute and duplicate proof ignored', async () => {
  const f = fixture(); await f.ready(); assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 0);
  assert.equal(f.context.copied, f.plan.confirmationText); await f.start('execute'); const cb = f.callbacks.length - 1;
  await f.authenticate(cb); await f.authenticate(cb); assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 1);
  assert.equal(f.state('locked'), true); assert.match(f.elements.status.textContent, /已核對完成/);
});
test('clipboard unavailable still fills complete text without executing', async () => {
  const f = fixture({ clipboardFails: true }); await f.ready(); assert.equal(f.elements.confirmation.value, f.plan.confirmationText);
  assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 0);
});
test('cancel Google wait unlocks and ignores late credentials', async () => {
  const f = fixture(); await f.start('preview'); f.click('cancel'); await f.authenticate(0);
  assert.equal(f.state('busy'), false); assert.equal(f.elements.preview.disabled, false); assert.equal(f.requests.filter(r => r.endpoint === 'preview').length, 0);
});
test('Google wait expires without a request or automatic retry', async () => {
  const f = fixture(); await f.start('preview'); await f.advance(120000); await f.authenticate(0);
  assert.equal(f.state('busy'), false); assert.match(f.elements.status.textContent, /等待逾時/); assert.equal(f.requests.length, 2);
});
test('Google cancellation exception cannot trap the UI', async () => {
  const f = fixture({ cancelFails: true }); await f.start('preview'); f.click('cancel'); assert.equal(f.state('busy'), false); await f.authenticate(0);
  assert.equal(f.requests.filter(r => r.endpoint === 'preview').length, 0);
});
test('script load timeout unlocks without Google proof or write', async () => {
  const f = fixture({ scriptHangs: true }); const p = f.start('preview'); await flush(); await f.advance(20000); await p;
  assert.equal(f.state('busy'), false); assert.equal(f.callbacks.length, 0); assert.match(f.elements.status.textContent, /載入失敗或逾時/);
});
test('script timeout permits a fresh user-initiated script load', async () => {
  const options = { scriptHangs: true }, f = fixture(options); const p = f.start('preview'); await flush(); await f.advance(20000); await p;
  options.scriptHangs = false; await f.start('preview'); assert.equal(f.callbacks.length, 1); await f.authenticate(); assert.equal(f.elements.plan.hidden, false);
});
test('cancel during setup invalidates response and prevents late Google render', async () => {
  const d = deferred(), f = fixture({ session: d.promise }); const p = f.start('preview'); await flush(); f.click('cancel');
  d.resolve({ ok: true, json: async () => ({ mode: 'experiment-bootstrap' }) }); await p;
  assert.equal(f.callbacks.length, 0); assert.equal(f.state('config'), null); assert.equal(f.state('busy'), false);
});
test('old preview failure cannot clear busy or overwrite newer attempt status', async () => {
  const d = deferred(), f = fixture({ preview: d.promise }); await f.start('preview'); const old = f.authenticate(0); await flush();
  f.click('cancel'); await f.start('preview'); const status = f.elements.status.textContent; d.reject(Error('old failure')); await old;
  assert.equal(f.state('busy'), true); assert.equal(f.elements.status.textContent, status);
});
test('new preview discards old plan and exact confirmation', async () => {
  const f = fixture(); await f.ready(); await f.start('preview'); assert.equal(f.state('plan'), null);
  assert.equal(f.elements.confirmation.value, ''); assert.equal(f.elements.plan.hidden, true);
});
test('expired plan cancels auth and prevents a late execute callback', async () => {
  const f = fixture(); await f.ready(); await f.start('execute'); const cb = f.callbacks.length - 1;
  await f.advance(300000); await f.authenticate(cb); assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 0); assert.equal(f.state('plan'), null);
});
test('unchecking consent before proof invalidates execute callback', async () => {
  const f = fixture(); await f.ready(); await f.start('execute'); f.elements.consent.checked = false; f.elements.consent.handlers.change(); await f.authenticate();
  assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 0);
});
test('in-flight execute cannot be cancelled or replaced and warns before leaving', async () => {
  const d = deferred(), f = fixture({ execute: d.promise }); await f.ready(); await f.start('execute'); const p = f.authenticate(); await flush();
  assert.equal(f.elements.cancel.hidden, true); assert.equal(f.elements.consent.disabled, true); f.click('cancel'); await f.start('preview');
  let warned = false; f.handlers.beforeunload({ preventDefault() { warned = true; } }); assert.equal(warned, true);
  assert.equal(f.state('busy'), true); d.resolve({ ok: true, json: async () => ({ outcome: 'VERIFIED' }) }); await p;
  assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 1);
});
test('execute network error locks all retries and removes plan', async () => {
  const f = fixture({ execute: Promise.reject(Error('network error')) }); await f.ready(); await f.start('execute'); await f.authenticate();
  await f.start('execute'); await f.start('preview'); assert.equal(f.state('locked'), true); assert.equal(f.state('plan'), null);
  assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 1); assert.match(f.elements.status.textContent, /勿重試/);
});
test('execute timeout is uncertain and never retries', async () => {
  const d = deferred(), f = fixture({ execute: d.promise }); await f.ready(); await f.start('execute'); const p = f.authenticate(); await flush();
  await f.advance(45000); await p; await f.start('execute'); assert.equal(f.state('locked'), true); assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 1);
});
for (const response of [{ ok: false, json: async () => ({ code: 'INITIALIZATION_REJECTED' }) }, { ok: true, json: async () => { throw Error('invalid JSON'); } }]) test('execute HTTP or malformed JSON failure stays locked', async () => {
  const f = fixture({ execute: Promise.resolve(response) }); await f.ready(); await f.start('execute'); await f.authenticate(); await f.start('execute');
  assert.equal(f.state('locked'), true); assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 1);
});
for (const outcome of ['UNCERTAIN', 'NOT_APPLIED']) test(`${outcome} remains terminal without retry`, async () => {
  const f = fixture({ outcome }); await f.ready(); await f.start('execute'); await f.authenticate(); await f.start('execute');
  assert.equal(f.state('locked'), true); assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 1);
});
test('render failure invalidates previously registered Google callback', async () => {
  const f = fixture({ renderFails: true }); await f.start('preview'); await f.authenticate(0); assert.equal(f.requests.filter(r => r.endpoint === 'preview').length, 0);
});
test('leaving and returning before execution invalidates pending callback', async () => {
  const f = fixture(); await f.start('preview'); f.handlers.pagehide(); await f.authenticate(0); assert.equal(f.state('busy'), false);
  assert.equal(f.requests.filter(r => r.endpoint === 'preview').length, 0);
});
test('pagehide after execute cannot clear the in-flight write', async () => {
  const d = deferred(), f = fixture({ execute: d.promise }); await f.ready(); await f.start('execute'); const p = f.authenticate(); await flush(); f.handlers.pagehide();
  assert.equal(f.state('writeStarted'), true); assert.equal(f.state('busy'), true); d.resolve({ ok: true, json: async () => ({ outcome: 'VERIFIED' }) }); await p;
  assert.equal(f.state('locked'), true); assert.equal(f.requests.filter(r => r.endpoint === 'execute').length, 1);
});
