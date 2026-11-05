import { useEffect, useRef, useState, type FormEvent } from "react";
import { client, getClientState } from "./client";
import type { Session } from "./model";
import { nicknameValidation } from "./nickname";
import AnimalAvatar from "./AnimalAvatar";
import PrivacyNotice from "./PrivacyNotice";
import { canCompleteEntry, prepareEntryChoice } from "./entry-flow";
import {
  accountRequest,
  createLoginAttempt,
  loadGoogleIdentity,
  type AuthConfig,
  type LoginPhase,
  type VerifiedIdentity,
} from "./google-login";

export default function WelcomeEntry({
  session,
  busy,
  onEnter,
}: {
  session: Session | null;
  busy: boolean;
  onEnter: (token: string) => void;
}) {
  const [name, setName] = useState("");
  const [composing, setComposing] = useState(false);
  const nickname = nicknameValidation(name);
  const [config, setConfig] = useState<AuthConfig | null>(null);
  const [configFailed, setConfigFailed] = useState(false);
  const [phase, setPhase] = useState<LoginPhase>("idle");
  const [message, setMessage] = useState("");
  const [verified, setVerified] = useState<VerifiedIdentity | null>(null);
  const [requestedToken, setRequestedToken] = useState<string | null>(null);
  const [preparing, setPreparing] = useState(false);
  const button = useRef<HTMLDivElement>(null);
  const controller = useRef<ReturnType<typeof createLoginAttempt> | null>(null);
  const live = useRef(true);
  const choiceFlight = useRef(false);
  const pendingVerification = useRef<string | null>(null);
  const token = session?.token;
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  useEffect(() => {
    if (pendingVerification.current !== token)
      pendingVerification.current = null;
    setRequestedToken((requested) => (requested === token ? requested : null));
    setConfig(null);
    setConfigFailed(false);
    setVerified(null);
    setPhase("idle");
    setMessage("");
    if (!token) return;
    let current = true;
    const attempt = createLoginAttempt({
      nonce: (purpose) => accountRequest("/auth/nonce", "POST", { purpose }),
      verify: (credential) =>
        accountRequest("/auth/google/verify", "POST", {
          credential,
          consent: true,
        }),
      // This entry deliberately exposes verification only, never account linking.
      link: async () => {
        throw new Error("Entry does not link accounts");
      },
      verified: setVerified,
      load: loadGoogleIdentity,
      cancel: (nonce) => accountRequest("/auth/cancel", "POST", { nonce }),
      eligible: () =>
        canCompleteEntry(
          token,
          getClientState().session?.token,
          Boolean(getClientState().room),
        ),
      update: (next, text) => {
        setPhase(next);
        setMessage(text ?? "");
        if (next === "idle") button.current?.replaceChildren();
      },
    });
    controller.current = attempt;
    void accountRequest<AuthConfig>("/auth/config")
      .then((value) => {
        if (current) setConfig(value);
      })
      .catch(() => {
        if (current) {
          setConfigFailed(true);
          setMessage("Google 驗證暫時無法使用，仍可當訪客遊玩。");
          pendingVerification.current = null;
          setRequestedToken(null);
        }
      });
    return () => {
      current = false;
      attempt.dispose();
      controller.current = null;
    };
  }, [token]);
  useEffect(() => {
    if (!requestedToken || requestedToken !== token) return;
    if (configFailed) {
      pendingVerification.current = null;
      setRequestedToken(null);
      setMessage("Google 驗證暫時無法使用，仍可當訪客遊玩。");
      return;
    }
    if (!config) return;
    // Consume this deliberate click once, including under StrictMode re-runs.
    if (!pendingVerification.current) return;
    pendingVerification.current = null;
    setRequestedToken(null);
    if (!config.verificationEnabled || !config.clientId) {
      setMessage("Google 驗證尚未開放，仍可當訪客遊玩。");
      return;
    }
    if (button.current)
      void controller.current?.start(config.clientId, button.current, "verify");
  }, [config, configFailed, requestedToken, token]);
  const choose = async (choice: "verify" | "guest") => {
    if (
      choiceFlight.current ||
      busy ||
      (choice === "verify" && (phase !== "idle" || pendingVerification.current))
    )
      return;
    if (!session && (composing || nickname.error)) return;
    choiceFlight.current = true;
    setPreparing(true);
    const previousToken = getClientState().session?.token;
    try {
      const expectedToken = await prepareEntryChoice({
        previousToken,
        createSession: () => client.login(name),
        current: () => ({
          token: getClientState().session?.token,
          hasRoom: Boolean(getClientState().room),
        }),
        isMounted: () => live.current,
      });
      if (!expectedToken) return;
      if (choice === "guest") {
        pendingVerification.current = null;
        setRequestedToken(null);
        controller.current?.dispose();
        onEnter(expectedToken);
      } else {
        setVerified(null);
        setMessage("");
        pendingVerification.current = expectedToken;
        setRequestedToken(expectedToken);
      }
    } finally {
      choiceFlight.current = false;
      if (live.current) setPreparing(false);
    }
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    void choose("guest");
  };
  const disabled =
    busy || preparing || (!session && (composing || Boolean(nickname.error)));
  return (
    <div className="welcome-grid">
      <section
        className="panel welcome entry-choice"
        aria-label="進入大廳前的選擇"
      >
        <span className="eyebrow">WELCOME TO THE TABLE</span>
        <h2>{session ? `你好，${session.displayName}` : "先認識一下你。"}</h2>
        <p>驗證或當訪客，都能一起玩。</p>
        {!session && (
          <form onSubmit={submit}>
            <label htmlFor="guest-name">遊戲暱稱</label>
            <input
              id="guest-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              onCompositionStart={() => setComposing(true)}
              onCompositionEnd={(event) => {
                setName(event.currentTarget.value);
                setComposing(false);
              }}
              aria-describedby="guest-name-hint"
              aria-invalid={
                !composing && Boolean(name) && Boolean(nickname.error)
              }
              required
              autoComplete="nickname"
              placeholder="例如：小白"
              disabled={preparing}
            />
            <small id="guest-name-hint" aria-live="polite">
              {composing
                ? "正在輸入…"
                : `${nickname.count}／5 字${name && nickname.error ? ` · ${nickname.error}` : ""}`}
            </small>
          </form>
        )}
        {verified ? (
          <p className="entry-verified" role="status">
            ✓ Google 驗證成功
          </p>
        ) : (
          <button
            className="primary"
            disabled={disabled || phase !== "idle" || Boolean(requestedToken)}
            onClick={() => void choose("verify")}
          >
            Google 驗證
          </button>
        )}
        <p className="fine">
          選用驗證會連線至 Google；目前僅驗證身分，不建立帳號連結或保存對局。
        </p>
        <div ref={button} className="google-signin-button" />
        {phase !== "idle" && (
          <>
            <p role="status">
              {phase === "loading"
                ? "正在載入 Google…"
                : phase === "submitting"
                  ? "正在驗證…"
                  : "請選擇 Google 帳號。"}
            </p>
            <button
              className="text-button"
              disabled={phase === "submitting"}
              onClick={() => controller.current?.cancel()}
            >
              取消驗證
            </button>
          </>
        )}
        <button
          className="secondary entry-continue"
          disabled={disabled || phase === "submitting"}
          onClick={() => void choose("guest")}
        >
          {verified ? "繼續進入大廳" : "以訪客身分遊玩"}
        </button>
        {message && !verified && <p role="status">{message}</p>}
        {verified && (
          <details className="entry-details">
            <summary>查看測試識別碼</summary>
            <p>
              尚未建立帳號連結。識別碼只顯示在此頁；如需申請測試資格，可自行交給你確認的管理者。
            </p>
            <label htmlFor="entry-subject">Google 帳號識別碼（subject）</label>
            <input id="entry-subject" readOnly value={verified.subject} />
            <button
              className="text-button"
              onClick={() => {
                void navigator.clipboard
                  .writeText(verified.subject)
                  .then(() => {
                    if (live.current) setMessage("已複製，尚未傳送給任何人。");
                  })
                  .catch(() => {
                    if (live.current)
                      setMessage("請自行選取並複製上方識別碼。");
                  });
              }}
            >
              複製識別碼
            </button>
            {message.startsWith("已複製") || message.startsWith("請自行") ? (
              <p role="status">{message}</p>
            ) : null}
          </details>
        )}
        <details className="entry-details">
          <summary>隱私與資料使用說明</summary>
          <p>
            點選 Google 驗證表示同意載入 Google
            元件，並將登入憑證交由本遊戲伺服器驗證。此流程不建立或保存帳號對應，也不保存對局、Google
            名稱、信箱、頭像或登入憑證。驗證結果與自己的帳號識別碼只在此頁暫時顯示，不會自動分享。
          </p>
          <p>
            訪客可完整遊玩。驗證不代表完整帳號登入，不能跨裝置取回牌局；繼續進入大廳仍使用原訪客身分。
          </p>
          <details>
            <summary>封閉測試保存與刪除政策</summary>
            <PrivacyNotice
              privacy={{
                contact: config?.privacyContact,
                retention: config?.retention,
              }}
            />
          </details>
        </details>
      </section>
      <div className="welcome-art forest-welcome" aria-hidden="true">
        <div className="forest-sign">
          <span>THE LANTERN GROVE</span>
          <strong>森林裡，留一個位子給你。</strong>
        </div>
        <div className="welcome-friends">
          {[0, 1, 2, 3].map((seat) => (
            <AnimalAvatar key={seat} seat={seat} large />
          ))}
        </div>
      </div>
    </div>
  );
}
