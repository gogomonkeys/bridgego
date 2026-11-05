import { getClientState } from "./client";
import { errorMessages } from "./error-messages";
export class AccountRequestError extends Error {}

export interface AuthStatus {
  signedIn: boolean;
}
export interface AuthConfig {
  enabled: boolean;
  clientId: string;
  verificationEnabled?: boolean;
  collectionMode?: "restricted";
  collectionReady?: boolean;
  privacyContact?: string;
  retention?: string;
}
export interface VerifiedIdentity {
  issuer: string;
  subject: string;
  admittedTester: boolean;
}
export type LoginPurpose = "link" | "verify";
// Deliberately separate from game commands: invalid Google credentials must never
// clear, replace, persist or log the existing guest token.
export async function accountRequest<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const token = getClientState().session?.token;
  if (!token) throw new Error("請先建立訪客身分。");
  const response = await fetch(`/api${path}`, {
    method,
    cache: "no-store",
    signal: AbortSignal.timeout(8000),
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (getClientState().session?.token !== token)
    throw new Error("訪客身分已改變，請重新操作。");
  const data = await response.json();
  if (!response.ok)
    throw new AccountRequestError(
      errorMessages[data?.code] ??
        "無法完成帳號操作，請稍後重試。訪客仍可繼續遊玩。",
    );
  return data as T;
}

export interface GoogleIdentity {
  initialize(options: {
    client_id: string;
    nonce: string;
    auto_select: false;
    callback: (response: { credential?: string }) => void;
  }): void;
  renderButton(
    element: HTMLElement,
    options: { theme: string; size: string; text: string },
  ): void;
  cancel(): void;
}
declare global {
  interface Window {
    google?: { accounts: { id: GoogleIdentity } };
  }
}
let scriptFlight: Promise<GoogleIdentity> | null = null;
// Called exclusively after explicit consent and a nonempty configured client ID.
export function loadGoogleIdentity(): Promise<GoogleIdentity> {
  if (window.google?.accounts.id)
    return Promise.resolve(window.google.accounts.id);
  if (scriptFlight) return scriptFlight;
  scriptFlight = new Promise<GoogleIdentity>((resolve, reject) => {
    let settled = false;
    const script = document.createElement("script");
    const timeout = setTimeout(() => fail(), 10000);
    const fail = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      script.remove();
      scriptFlight = null;
      reject(new Error("Google 登入暫時無法載入，仍可使用訪客遊玩。"));
    };
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.onerror = fail;
    script.onload = () => {
      if (settled) return;
      clearTimeout(timeout);
      const identity = window.google?.accounts.id;
      if (identity) {
        settled = true;
        resolve(identity);
      } else fail();
    };
    document.head.appendChild(script);
  });
  return scriptFlight;
}

export type LoginPhase = "idle" | "loading" | "ready" | "submitting";
export interface LoginAttemptDependencies {
  nonce: (purpose: LoginPurpose) => Promise<{ nonce: string }>;
  load: () => Promise<GoogleIdentity>;
  link: (credential: string) => Promise<AuthStatus>;
  verify: (credential: string) => Promise<VerifiedIdentity>;
  verified: (identity: VerifiedIdentity) => void;
  cancel: (nonce: string) => Promise<unknown>;
  eligible: () => boolean;
  update: (phase: LoginPhase, message?: string, signedIn?: boolean) => void;
}
// An attempt is scoped to a mounted lobby card. Cancellation and navigation
// invalidate callbacks even if the Google popup or script finishes later.
export function createLoginAttempt(deps: LoginAttemptDependencies) {
  let revision = 0,
    phase: LoginPhase = "idle",
    disposed = false;
  let identity: GoogleIdentity | undefined;
  let activeNonce: string | undefined;
  const valid = (id: number) => !disposed && revision === id && deps.eligible();
  const update = (next: LoginPhase, message?: string, signedIn?: boolean) => {
    phase = next;
    if (!disposed) deps.update(next, message, signedIn);
  };
  const cancelNonce = (nonce = activeNonce) => {
    if (nonce) void deps.cancel(nonce).catch(() => undefined);
    if (activeNonce === nonce) activeNonce = undefined;
  };
  return {
    async start(
      clientId: string,
      element: HTMLElement,
      purpose: LoginPurpose = "link",
    ) {
      if (disposed || phase !== "idle" || !clientId || !deps.eligible()) return;
      const id = ++revision;
      update("loading");
      try {
        const { nonce } = await deps.nonce(purpose);
        if (!valid(id)) {
          cancelNonce(nonce);
          return;
        }
        activeNonce = nonce;
        identity = await deps.load();
        if (!valid(id)) return;
        identity.initialize({
          client_id: clientId,
          nonce,
          auto_select: false,
          callback: async ({ credential }) => {
            if (!valid(id) || phase !== "ready" || !credential) return;
            update("submitting");
            try {
              if (purpose === "verify") {
                const result = await deps.verify(credential);
                if (valid(id)) {
                  deps.verified(result);
                  update(
                    "idle",
                    "已完成身分驗證，尚未建立帳號連結或保存帳號對應。",
                    false,
                  );
                }
              } else {
                const result = await deps.link(credential);
                if (valid(id))
                  update(
                    "idle",
                    "已連結 Google。原訪客身分保留；只有合資格測試場次會保存，之前的訪客場次不會補登。",
                    result.signedIn,
                  );
              }
            } catch (error) {
              if (valid(id))
                update(
                  "idle",
                  error instanceof AccountRequestError
                    ? error.message
                    : purpose === "verify"
                      ? "尚無法確認驗證結果；未自動建立帳號連結，可繼續當訪客。"
                      : "尚無法確認登入結果，請重新確認帳號狀態。訪客仍可繼續遊玩。",
                );
            }
            revision++;
          },
        });
        identity.renderButton(element, {
          theme: "outline",
          size: "large",
          text: "signin_with",
        });
        update("ready");
      } catch {
        if (valid(id)) {
          cancelNonce();
          update("idle", "Google 登入暫時無法使用，仍可使用訪客遊玩。");
        }
      }
    },
    cancel() {
      if (phase === "submitting") return;
      revision++;
      identity?.cancel();
      cancelNonce();
      update("idle", "已取消登入，繼續使用原訪客身分。");
    },
    dispose() {
      disposed = true;
      revision++;
      identity?.cancel();
      if (phase === "loading" || phase === "ready") cancelNonce();
    },
  };
}
