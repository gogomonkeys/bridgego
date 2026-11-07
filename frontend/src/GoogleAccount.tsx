import { useEffect, useRef, useState } from "react";
import {
  canLinkAccount,
  experimentNotice,
  isSingleTesterExperiment,
} from "./entry-account";
export { canLinkAccount } from "./entry-account";
import { getClientState } from "./client";
import { useExperimentClock } from "./use-experiment-clock";
import {
  accountRequest,
  createLoginAttempt,
  loadGoogleIdentity,
  type AuthConfig,
  type AuthStatus,
  type LoginPhase,
  type LoginPurpose,
  type VerifiedIdentity,
} from "./google-login";

export default function GoogleAccount({ token }: { token: string }) {
  const [config, setConfig] = useState<AuthConfig | null>(null);
  const experimentNow = useExperimentClock(config);
  const experiment = isSingleTesterExperiment(config);
  const experimentMessage = experimentNotice(config, experimentNow);
  const canLink = canLinkAccount(config, experimentNow);
  const [signedIn, setSignedIn] = useState<boolean | null>(null);
  const [phase, setPhase] = useState<LoginPhase>("idle");
  const [message, setMessage] = useState("");
  const [checking, setChecking] = useState(false);
  const [verified, setVerified] = useState<VerifiedIdentity | null>(null);
  const [copyMessage, setCopyMessage] = useState("");
  const [purpose, setPurpose] = useState<LoginPurpose>("verify");
  const button = useRef<HTMLDivElement>(null);
  const mounted = useRef(false);
  const checkingFlight = useRef(false);
  const attempt = useRef<ReturnType<typeof createLoginAttempt> | null>(null);
  useEffect(() => {
    let live = true;
    mounted.current = true;
    const controller = createLoginAttempt({
      nonce: (purpose) =>
        accountRequest("/auth/nonce", "POST", { purpose }, token),
      verify: (credential) =>
        accountRequest(
          "/auth/google/verify",
          "POST",
          {
            credential,
            consent: true,
          },
          token,
        ),
      verified: setVerified,
      load: loadGoogleIdentity,
      link: (credential) =>
        accountRequest(
          "/auth/google",
          "POST",
          { credential, consent: true },
          token,
        ),
      cancel: (nonce) =>
        accountRequest("/auth/cancel", "POST", { nonce }, token),
      eligible: () =>
        !getClientState().room && getClientState().session?.token === token,
      update: (next, text, linked) => {
        setPhase(next);
        setMessage(text ?? "");
        if (linked !== undefined) setSignedIn(linked);
        if (next === "idle") button.current?.replaceChildren();
      },
    });
    attempt.current = controller;
    void Promise.all([
      accountRequest<AuthConfig>("/auth/config", "GET", undefined, token),
      accountRequest<AuthStatus>("/auth/status", "GET", undefined, token),
    ])
      .then(([settings, status]) => {
        if (live) {
          setConfig(settings);
          setSignedIn(
            status.signedIn === true
              ? true
              : status.signedIn === false
                ? false
                : null,
          );
        }
      })
      .catch(() => {
        if (live) setMessage("帳號服務暫時無法確認；不影響訪客遊玩。");
      });
    return () => {
      live = false;
      mounted.current = false;
      controller.dispose();
      attempt.current = null;
    };
  }, [token]);
  useEffect(() => {
    if (
      purpose === "link" &&
      !canLink &&
      (phase === "loading" || phase === "ready")
    )
      attempt.current?.cancel();
  }, [canLink, phase, purpose]);
  const refreshOrLogout = async (logout: boolean) => {
    if (checkingFlight.current || phase !== "idle" || getClientState().room)
      return;
    checkingFlight.current = true;
    setChecking(true);
    try {
      const result = await accountRequest<AuthStatus>(
        logout ? "/auth/logout" : "/auth/status",
        logout ? "POST" : "GET",
        logout ? {} : undefined,
        token,
      );
      if (
        !mounted.current ||
        getClientState().session?.token !== token ||
        getClientState().room
      )
        return;
      if (
        typeof result.signedIn !== "boolean" ||
        (logout && result.signedIn !== false)
      ) {
        setSignedIn(null);
        setMessage("尚無法確認帳號狀態，請重新確認；未確認解除連結。");
        return;
      }
      setSignedIn(result.signedIn);
      setMessage(
        logout
          ? "已解除 Google 連結；原訪客身分仍保留，可繼續遊玩。"
          : "帳號狀態已更新。",
      );
    } catch {
      if (
        mounted.current &&
        getClientState().session?.token === token &&
        !getClientState().room
      )
        setMessage("尚無法確認帳號狀態；請重新確認後再選擇遊玩身分。");
    } finally {
      checkingFlight.current = false;
      if (mounted.current) setChecking(false);
    }
  };
  return (
    <section className="google-account" aria-label="選用 Google 登入">
      <h3>
        {signedIn
          ? experiment
            ? "已連結 Google（單人一場實驗）"
            : canLink
              ? "已連結 Google（封閉測試）"
              : "已連結 Google（收集尚未開放）"
          : "訪客也能完整遊玩"}
      </h3>
      <p>
        Google
        登入為選用，只能在房間外操作。不會更換目前的訪客名稱或身分，也不會將先前訪客場次補登至帳號。
      </p>
      {experimentMessage && (
        <p className="fine" role="status">
          {experimentMessage}
        </p>
      )}
      <p className="fine">
        保存採盡力而為。<a href="#help">使用說明：資料保存與刪除</a>
      </p>
      {signedIn ? (
        <>
          <p className="fine">
            解除連結僅解除本次 Google
            連結，原訪客身分繼續使用，不會刪除已保存的紀錄。帳號連結不保證每場可保存，仍由伺服器判斷全場是否符合測試資格。
          </p>
          <button
            className="secondary"
            disabled={checking}
            onClick={() => void refreshOrLogout(true)}
          >
            解除 Google 連結，保留訪客
          </button>
        </>
      ) : config?.verificationEnabled && config.clientId ? (
        <>
          <p className="fine">
            按下同意後才會連線至 Google
            並載入登入元件；選擇帳號後將登入憑證送至本遊戲伺服器驗證。驗證與連結是兩個獨立選項，不會自動互相轉換。
          </p>
          <p>
            僅驗證身分：不建立或保存帳號對應，也不連結場次。只在此頁顯示你自己的
            Google
            帳號識別碼（subject），不是信箱、密碼或登入憑證。你可自行決定是否複製交給測試管理者申請核准，不會自動傳送。
          </p>
          {phase === "idle" && (
            <>
              <button
                className="secondary"
                disabled={checking || signedIn === null}
                onClick={() => {
                  setPurpose("verify");
                  setVerified(null);
                  setCopyMessage("");
                  if (button.current)
                    void attempt.current?.start(
                      config.clientId,
                      button.current,
                      "verify",
                    );
                }}
              >
                同意僅驗證身分，不保存帳號對應
              </button>
              {canLink ? (
                <>
                  <p>
                    {experiment
                      ? "僅指定帳號可參與。連結表示同意將你的 Google 識別碼、遊戲帳號及最多一場戰績存入私人試算表；刪除仍待驗收，版本歷史或副本可能保留。取消或失敗也可能用掉名額。"
                      : "已獲核准的測試者可選擇下方連結，表示同意上述資料範圍、保存期限與刪除處理方式。伺服器會檢查測試資格；未核准者不建立帳號對應。"}
                  </p>
                  <button
                    className="secondary"
                    disabled={checking || signedIn === null}
                    onClick={() => {
                      setPurpose("link");
                      setVerified(null);
                      setCopyMessage("");
                      if (button.current)
                        void attempt.current?.start(
                          config.clientId,
                          button.current,
                          "link",
                        );
                    }}
                  >
                    {experiment
                      ? "同意單人一場實驗並登入"
                      : "同意測試資料收集並連結 Google"}
                  </button>
                </>
              ) : (
                <p role="status">
                  資料收集尚未開放。僅可驗證自己的識別碼或繼續當訪客，不會因驗證而開始保存。
                </p>
              )}
            </>
          )}
          <div ref={button} className="google-signin-button" />
          {phase !== "idle" && (
            <>
              <p role="status">
                {phase === "loading"
                  ? "載入登入元件…"
                  : phase === "submitting"
                    ? purpose === "verify"
                      ? "正在驗證身分，不保存帳號對應…"
                      : "正在確認帳號連結…"
                    : "可選擇 Google 帳號，或取消繼續當訪客。"}
              </p>
              <button
                className="text-button"
                disabled={phase === "submitting"}
                onClick={() => attempt.current?.cancel()}
              >
                取消登入，繼續當訪客
              </button>
            </>
          )}
        </>
      ) : (
        <p className="fine">
          {config
            ? "Google 登入尚未啟用，可直接建立或加入房間。"
            : "正在確認選用登入服務…"}
        </p>
      )}
      {verified && (
        <section aria-label="僅驗證身分結果">
          <h4>已驗證，尚未連結</h4>
          <p>
            {verified.admittedTester
              ? "此識別碼已列入核准測試者，但這次僅驗證，未建立連結。"
              : "此識別碼尚未獲核准；驗證不代表可收集資料。"}
          </p>
          <label htmlFor="verified-google-subject">
            你的 Google 帳號識別碼（subject）
          </label>
          <input
            id="verified-google-subject"
            value={verified.subject}
            readOnly
          />
          <p className="fine">
            此識別碼仍屬可識別資料，請只交給你確認的測試管理者。關閉此頁會清除畫面上的驗證結果。
          </p>
          <button
            className="secondary"
            onClick={() => {
              void navigator.clipboard
                .writeText(verified.subject)
                .then(() => {
                  if (mounted.current)
                    setCopyMessage("已複製識別碼；尚未傳送給任何人。");
                })
                .catch(() => {
                  if (mounted.current)
                    setCopyMessage("無法自動複製，請自行選取上方識別碼。");
                });
            }}
          >
            複製識別碼，稍後自行分享
          </button>
          {copyMessage && <p role="status">{copyMessage}</p>}
        </section>
      )}
      {message && <p role="status">{message}</p>}
      <button
        className="text-button"
        disabled={checking || phase !== "idle"}
        onClick={() => void refreshOrLogout(false)}
      >
        重新確認帳號狀態
      </button>
    </section>
  );
}
