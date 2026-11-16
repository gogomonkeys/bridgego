import { accountRequest, type AuthStatus, type GoogleIdentity, type LoginPhase, type LoginPurpose } from './google-login';

const MARKER = 'bridge-google-redirect-v1';
const LIMIT = 300_000;
export function isIOSWebApp() {
  if (typeof navigator === 'undefined' || typeof window === 'undefined') return false;
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  return ios && (window.matchMedia?.('(display-mode: standalone)').matches === true ||
    (navigator as Navigator & {standalone?: boolean}).standalone === true);
}
export function hasRedirectAttempt() {
  try { const at = Number(sessionStorage.getItem(MARKER)); return at > 0 && Date.now() - at < LIMIT; }
  catch { return false; }
}
export type RedirectState = { state: 'WAITING'|'RECEIVED'|'SUBMITTING'|'COMPLETE'|'FAILED'|'UNKNOWN'; status?: AuthStatus };
type Dependencies = {
  token: string;
  eligible: () => boolean;
  load: () => Promise<GoogleIdentity>;
  update: (phase: LoginPhase, text?: string, signedIn?: boolean) => void;
  request?: typeof accountRequest;
};
// Only a non-secret timestamp survives navigation. Credentials stay on the server;
// original bearer session is required for status and the one-shot finish request.
export function createRedirectAttempt(deps: Dependencies) {
  const request = deps.request ?? accountRequest;
  let revision = 0;
  let buttonRendered = false;
  let disposed = false, started = false, checking = false, finishSent = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let deadline = Date.now() + LIMIT;
  const valid = () => !disposed && deps.eligible();
  const clearMarker = () => { try { sessionStorage.removeItem(MARKER); } catch { /* no credentials stored */ } };
  const stop = (text: string) => { clearTimeout(timer); if(valid()) deps.update('idle', text); };
  const check = async () => {
    if (!valid() || checking || !started) return;
    if (Date.now() >= deadline) { stop('登入等候已結束。請確認登入狀態；若主畫面 App 無法返回，可先改用 Safari。'); return; }
    checking = true;
    const generation = revision;
    const current = () => valid() && generation === revision;
    try {
      let result = await request<RedirectState>('/auth/google/redirect/status', 'GET', undefined, deps.token);
      if (!current()) return;
      if (result.state === 'RECEIVED' && !finishSent) {
        finishSent = true;
        deps.update('submitting', '正在確認登入…');
        // Never retry this POST, even if its response is lost.
        result = await request<RedirectState>('/auth/google/redirect/finish', 'POST', {}, deps.token);
        if (!current()) return;
      }
      if (result.state === 'COMPLETE' && result.status?.signedIn === true) {
        clearMarker(); started = false; deps.update('idle', '已確認 Google 登入成功。', true); return;
      }
      if (result.state === 'FAILED') { clearMarker(); started = false; stop('登入未完成或已過期，可重新選擇登入；若主畫面 App 無法操作，可先改用 Safari。'); return; }
      if (result.state === 'UNKNOWN') { started = false; stop('登入結果尚未確認，請確認帳號狀態；不要重複送出登入。'); return; }
      if (result.state === 'SUBMITTING') finishSent = true;
      if (result.state === 'WAITING') deps.update('ready', buttonRendered ? '請點下方 Google 按鈕；驗證後返回此 App。也可取消登入。' : '尚未收到 Google 驗證。若已關閉驗證畫面，請取消這次登入後再開始。');
    } catch {
      // Read-only retry only. A lost finish response cannot cause another POST.
      if (current()) deps.update(finishSent ? 'submitting' : 'ready', '正在確認登入回應，請稍候…');
    } finally {
      checking = false;
      if (current() && started) timer = setTimeout(() => void check(), 2000);
    }
  };
  const wake = () => { if (document.visibilityState === 'visible') { clearTimeout(timer); void check(); } };
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', wake);
  if (typeof window !== 'undefined') window.addEventListener?.('pageshow', wake);
  return {
    async start(clientId: string, element: HTMLElement, purpose: LoginPurpose = 'link') {
      if (!valid() || started || purpose !== 'link') return;
      const generation = ++revision;
      const current = () => valid() && generation === revision;
      deps.update('loading', '正在準備 Google 登入…');
      try {
        const config = await fetch('/api/auth/google/redirect/config', {cache:'no-store', signal:AbortSignal.timeout(8000)}).then(r => {if(!r.ok) throw new Error(); return r.json();});
        if (!current()) return;
        if (config.enabled !== true) { stop('主畫面 App 登入暫未支援，請先使用 Safari 開啟 BridgeGo 登入。'); return; }
        // If storage is unavailable do not create an attempt we cannot resume.
        sessionStorage.setItem(MARKER, String(Date.now()));
        const result = await request<{nonce:string;loginUri:string;expiresInSeconds:number}>('/auth/google/redirect/start', 'POST', {consent:true,profileConsentVersion:'account-profile-v1'}, deps.token);
        if (!current()) return;
        // Only a confirmed new server attempt may reset one-shot submission state.
        finishSent = false;
        buttonRendered = false;
        const uri = new URL(result.loginUri);
        if(uri.origin !== location.origin || uri.pathname !== '/api/auth/google/redirect/callback' || uri.search || uri.hash) throw new Error();
        deadline = Date.now() + Math.min(LIMIT, result.expiresInSeconds * 1000);
        const google = await deps.load();
        if (!current()) return;
        google.initialize({client_id:clientId,nonce:result.nonce,auto_select:false,ux_mode:'redirect',login_uri:uri.href,callback:()=>{ /* redirect must never use popup callback */ }});
        google.renderButton(element,{theme:'outline',size:'large',text:'signin_with'});
        buttonRendered = true;
        started = true;
        deps.update('ready','請點下方 Google 按鈕；驗證後返回此 App。也可取消登入。');
        timer = setTimeout(() => void check(), 2000);
      } catch {
        if (!current()) return;
        if (hasRedirectAttempt()) {
          started = true;
          deps.update('ready','Google 登入尚未準備完成，未自動重試。請取消這次登入後再開始，或先使用 Safari。');
          timer = setTimeout(() => void check(), 2000);
        } else stop('Google 登入尚未準備完成，未自動重試。可先使用 Safari，或確認登入狀態。');
      }
    },
    resume() {
      if (!valid() || started || !hasRedirectAttempt()) return;
      started = true;
      try { deadline = Number(sessionStorage.getItem(MARKER)) + LIMIT; } catch { return; }
      void check();
    },
    cancel() {
      if (finishSent) return;
      const generation = ++revision;
      started = false; clearTimeout(timer);
      void request<RedirectState>('/auth/google/redirect/cancel','POST',{},deps.token).then(result => {
        if (!valid() || revision !== generation) return;
        if (result.state === 'FAILED') { clearMarker(); stop('已取消登入，可使用訪客遊玩。'); }
        else if (result.state === 'COMPLETE' && result.status?.signedIn === true) {
          clearMarker(); deps.update('idle','已確認 Google 登入成功。',true);
        } else {
          finishSent = true;
          stop('登入可能已送出，取消尚未確認。請確認登入狀態，不要重複送出。');
        }
      }).catch(() => { if(valid() && revision === generation) stop('取消結果未確認，請先確認登入狀態。'); });
    },
    dispose() {
      disposed = true; revision++; clearTimeout(timer);
      if (typeof document !== 'undefined') document.removeEventListener('visibilitychange',wake);
      if (typeof window !== 'undefined') window.removeEventListener?.('pageshow',wake);
      // Navigation is expected: do not cancel the server attempt on page unload.
    },
  };
}
