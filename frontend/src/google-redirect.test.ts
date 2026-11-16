import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {createRedirectAttempt, hasRedirectAttempt, isIOSWebApp} from './google-redirect';
import type {accountRequest, GoogleIdentity} from './google-login';
beforeEach(()=>{
 vi.useFakeTimers();
 const storage = new Map<string,string>();
 vi.stubGlobal('sessionStorage',{getItem:(k:string)=>storage.get(k)??null,setItem:(k:string,v:string)=>storage.set(k,v),removeItem:(k:string)=>storage.delete(k)});
 vi.stubGlobal('document',{visibilityState:'visible',addEventListener:vi.fn(),removeEventListener:vi.fn()});
 vi.stubGlobal('window',{addEventListener:vi.fn(),removeEventListener:vi.fn(),matchMedia:()=>({matches:true})});
 vi.stubGlobal('navigator',{userAgent:'iPhone',platform:'iPhone',maxTouchPoints:5});
 vi.stubGlobal('location',{origin:'https://bridgego.onrender.com'});
 vi.stubGlobal('fetch',vi.fn(async()=>({ok:true,json:async()=>({enabled:true})})));
});
afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals();});
function setup(){
 const request=vi.fn(async(path:string)=>path.endsWith('/start')?{nonce:'nonce',loginUri:'https://bridgego.onrender.com/api/auth/google/redirect/callback',expiresInSeconds:300}:{state:'WAITING'});
 const google:GoogleIdentity={initialize:vi.fn(),renderButton:vi.fn(),cancel:vi.fn()};
 const update=vi.fn();
 const attempt=createRedirectAttempt({token:'original-session',eligible:()=>true,load:async()=>google,request:request as typeof accountRequest,update});
 return {attempt,request,google,update};
}
it('selects iOS standalone but not normal Safari',()=>{
 expect(isIOSWebApp()).toBe(true);
 window.matchMedia=(()=>({matches:false})) as unknown as typeof window.matchMedia;
 expect(isIOSWebApp()).toBe(false);
});
it('uses redirect with exact same origin and stores no credential',async()=>{
 const s=setup();await s.attempt.start('client',{} as HTMLElement);
 expect(s.google.initialize).toHaveBeenCalledWith(expect.objectContaining({ux_mode:'redirect',nonce:'nonce',login_uri:'https://bridgego.onrender.com/api/auth/google/redirect/callback'}));
 expect(hasRedirectAttempt()).toBe(true);s.attempt.dispose();
 expect(s.request.mock.calls.some(c=>c[0].endsWith('/cancel'))).toBe(false);
});
it('never repeats finish after a lost response; reads only',async()=>{
 const s=setup();await s.attempt.start('client',{} as HTMLElement);
 s.request.mockImplementation(async(path:string)=>{if(path.endsWith('/finish'))throw new Error('lost');return {state:'RECEIVED'};});
 await vi.advanceTimersByTimeAsync(7000);
 expect(s.request.mock.calls.filter(c=>c[0].endsWith('/finish'))).toHaveLength(1);
 expect(s.update).not.toHaveBeenCalledWith('idle',expect.anything(),true);
 s.attempt.dispose();
});
it('only accepts verified COMPLETE and original session binding',async()=>{
 const s=setup();await s.attempt.start('client',{} as HTMLElement);
 s.request.mockImplementation(async()=>({state:'COMPLETE',status:{signedIn:true}}));
 await vi.advanceTimersByTimeAsync(2000);
 expect(s.update).toHaveBeenLastCalledWith('idle','已確認 Google 登入成功。',true);
 expect(hasRedirectAttempt()).toBe(false);s.attempt.dispose();
});
it('off switch neither issues nonce nor loads Google',async()=>{
 const s=setup();vi.mocked(fetch).mockResolvedValue({ok:true,json:async()=>({enabled:false})} as Response);
 await s.attempt.start('client',{} as HTMLElement);
 expect(s.request).not.toHaveBeenCalled();expect(s.google.initialize).not.toHaveBeenCalled();s.attempt.dispose();
});
it('rejects callback URL escaping original origin',async()=>{
 const s=setup();s.request.mockResolvedValue({nonce:'nonce',loginUri:'https://evil.invalid/api/auth/google/redirect/callback',expiresInSeconds:300});
 await s.attempt.start('client',{} as HTMLElement);expect(s.google.initialize).not.toHaveBeenCalled();s.attempt.dispose();
});
it('cancel invalidates a delayed status before it can finish',async()=>{
 const s=setup();await s.attempt.start('client',{} as HTMLElement);
 let release!:(v:unknown)=>void;
 s.request.mockImplementation((async(path:string)=> path.endsWith('/status') ? new Promise(r=>{release=r;}) : {state:'FAILED'}) as never);
 await vi.advanceTimersByTimeAsync(2000);
 s.attempt.cancel();release({state:'RECEIVED'});await Promise.resolve();await Promise.resolve();
 expect(s.request.mock.calls.filter(c=>c[0].endsWith('/finish'))).toHaveLength(0);
 s.attempt.dispose();
});
it('cancel while script loads cannot resurrect Google button',async()=>{
 const s=setup();let release!:(v:GoogleIdentity)=>void;
 const attempt=createRedirectAttempt({token:'original-session',eligible:()=>true,load:()=>new Promise(r=>{release=r;}),request:s.request as typeof accountRequest,update:s.update});
 const flight=attempt.start('client',{} as HTMLElement);
 await Promise.resolve();await Promise.resolve();await Promise.resolve();await Promise.resolve();await Promise.resolve();
 attempt.cancel();release(s.google);await flight;
 expect(s.google.renderButton).not.toHaveBeenCalled();attempt.dispose();s.attempt.dispose();
});
it('does not claim cancellation of an already submitted request',async()=>{
 const s=setup();await s.attempt.start('client',{} as HTMLElement);
 s.request.mockResolvedValue({state:'UNKNOWN'});s.attempt.cancel();await Promise.resolve();await Promise.resolve();
 expect(hasRedirectAttempt()).toBe(true);
 expect(s.update.mock.calls.some(c=>c[1]==='已取消登入，可使用訪客遊玩。')).toBe(false);
 s.attempt.dispose();
});
it('lost start response leaves read-only verification and cancellation, never auto-start retry',async()=>{
 const s=setup();s.request.mockRejectedValueOnce(new Error('lost start'));
 await s.attempt.start('client',{} as HTMLElement);
 expect(s.update).toHaveBeenLastCalledWith('ready',expect.stringContaining('未自動重試'));
 s.request.mockResolvedValue({state:'WAITING'});await vi.advanceTimersByTimeAsync(2000);
 expect(s.request.mock.calls.filter(c=>c[0].endsWith('/start'))).toHaveLength(1);
 s.request.mockResolvedValue({state:'FAILED'});s.attempt.cancel();await Promise.resolve();
 expect(hasRedirectAttempt()).toBe(false);s.attempt.dispose();
});
it('a confirmed fresh attempt can finish after a known no-write FAILED attempt',async()=>{
 const s=setup();await s.attempt.start('client',{} as HTMLElement);
 s.request.mockImplementation(async(path:string)=>path.endsWith('/finish')?{state:'FAILED'}:{state:'RECEIVED'});
 await vi.advanceTimersByTimeAsync(2000);
 s.request.mockImplementation(async(path:string)=>path.endsWith('/start')?{nonce:'second',loginUri:'https://bridgego.onrender.com/api/auth/google/redirect/callback',expiresInSeconds:300}:path.endsWith('/finish')?{state:'COMPLETE',status:{signedIn:true}}:{state:'RECEIVED'});
 await s.attempt.start('client',{} as HTMLElement);
 await vi.advanceTimersByTimeAsync(2000);
 expect(s.request.mock.calls.filter(c=>c[0].endsWith('/finish'))).toHaveLength(2);
 expect(s.update).toHaveBeenLastCalledWith('idle','已確認 Google 登入成功。',true);s.attempt.dispose();
});
