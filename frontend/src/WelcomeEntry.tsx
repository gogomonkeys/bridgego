import {
  acceptStatistics,
  readStatisticsConfig,
  type StatisticsConfig,
} from "./statistics";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { client, getClientState } from "./client";
import type { Session } from "./model";
import { nicknameValidation } from "./nickname";
import AnimalAvatar from "./AnimalAvatar";
import { useExperimentClock } from "./use-experiment-clock";
import { canCompleteEntry, prepareEntryChoice } from "./entry-flow";
import {
  canEnterAfterAccountLink,
  entryAccountPurpose,
  experimentNotice,
  isSingleTesterExperiment,
  isManagedCollection,
  managedLoginConsent,
} from "./entry-account";
import {
  accountRequest,
  createLoginAttempt,
  loadGoogleIdentity,
  readAuthConfig,
  type AuthConfig,
  type AuthStatus,
  type LoginPhase,
  type LoginPurpose,
  type VerifiedIdentity,
} from "./google-login";

export default function WelcomeEntry({
  session,
  busy,
  onEnter,
}: {
  session: Session | null;
  busy: boolean;
  onEnter: (token: string, experiment?: boolean) => void;
}) {
  const [statisticsConfig, setStatisticsConfig] =
    useState<StatisticsConfig | null>(null);
  const [statisticsFailed, setStatisticsFailed] = useState(false);
  const loadStatistics = () => {
    setStatisticsFailed(false);
    void readStatisticsConfig()
      .then((value) => {
        if (live.current) setStatisticsConfig(value);
      })
      .catch(() => {
        if (live.current) setStatisticsFailed(true);
      });
  };
  const [name, setName] = useState("");
  const [composing, setComposing] = useState(false);
  const nickname = nicknameValidation(name);
  const [config, setConfig] = useState<AuthConfig | null>(null);
  const [configFailed, setConfigFailed] = useState(false);
  const [phase, setPhase] = useState<LoginPhase>("idle");
  const [purpose, setPurpose] = useState<LoginPurpose>("verify");
  const [message, setMessage] = useState("");
  const [verified, setVerified] = useState<VerifiedIdentity | null>(null);
  const [requested, setRequested] = useState<{
    token: string;
    purpose: LoginPurpose;
  } | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [recoverySubmitted, setRecoverySubmitted] = useState(false);
  const [linkAttempted, setLinkAttempted] = useState(false);
  const button = useRef<HTMLDivElement>(null);
  const controller = useRef<ReturnType<typeof createLoginAttempt> | null>(null);
  const live = useRef(true);
  const enter = useRef(onEnter);
  enter.current = onEnter;
  const choiceFlight = useRef(false);
  const pending = useRef<{ token: string; purpose: LoginPurpose } | null>(null);
  const token = session?.token;
  const experimentNow = useExperimentClock(config);
  const availablePurpose = entryAccountPurpose(config, experimentNow);
  const experiment = isSingleTesterExperiment(config);
  const managed = isManagedCollection(config);
  const experimentRef = useRef(experiment);
  experimentRef.current = experiment;
  const experimentMessage = experimentNotice(config, experimentNow);
  useEffect(() => {
    live.current = true;
    let current = true;
    void readStatisticsConfig()
      .then((value) => {
        if (current) setStatisticsConfig(value);
      })
      .catch(() => {
        if (current) setStatisticsFailed(true);
      });
    void readAuthConfig()
      .then((value) => {
        if (current) setConfig(value);
      })
      .catch(() => {
        if (current) setConfigFailed(true);
      });
    return () => {
      current = false;
      live.current = false;
    };
  }, []);
  useEffect(() => {
    if (pending.current?.token !== token) pending.current = null;
    setRequested((request) => (request?.token === token ? request : null));
    setVerified(null);
    setPhase("idle");
    setMessage("");
    setLinkAttempted(false);
    if (!token) return;
    const eligible = () =>
      live.current &&
      canCompleteEntry(
        token,
        getClientState().session?.token,
        Boolean(getClientState().room),
      );
    const attempt = createLoginAttempt({
      nonce: (purpose) =>
        accountRequest("/auth/nonce", "POST", { purpose }, token),
      verify: (credential) =>
        accountRequest(
          "/auth/google/verify",
          "POST",
          { credential, consent: true },
          token,
        ),
      link: (credential) =>
        accountRequest(
          "/auth/google",
          "POST",
          { credential, consent: true, profileConsentVersion: "account-profile-v1" },
          token,
        ),
      recover: (credential) =>
        accountRequest(
          "/auth/google/recover",
          "POST",
          { credential, consent: true },
          token,
        ),
      recoverySubmitted: () => setRecoverySubmitted(true),
      verified: setVerified,
      load: loadGoogleIdentity,
      readStatus: () =>
        accountRequest<AuthStatus>("/auth/status", "GET", undefined, token),
      cancel: (nonce) =>
        accountRequest("/auth/cancel", "POST", { nonce }, token),
      eligible,
      update: (next, text, signedIn) => {
        if (!eligible()) return;
        setPhase(next);
        setMessage(text ?? "");
        if (next === "idle") button.current?.replaceChildren();
        if (
          canEnterAfterAccountLink(
            signedIn,
            token,
            getClientState().session?.token,
            Boolean(getClientState().room),
            live.current,
          )
        ) {
          enter.current(token, experimentRef.current);
        }
      },
    });
    controller.current = attempt;
    return () => {
      attempt.dispose();
      controller.current = null;
    };
  }, [token]);
  useEffect(() => {
    if (!requested || requested.token !== token || !pending.current) return;
    pending.current = null;
    setRequested(null);
    // Never change the purpose the user consented to, even if config changes.
    if (!config || availablePurpose !== requested.purpose) {
      setMessage("Google 服務設定已變更，請重新選擇；仍可當訪客遊玩。");
      return;
    }
    setPurpose(requested.purpose);
    setLinkAttempted(requested.purpose !== "verify");
    if (button.current)
      void controller.current?.start(
        config.clientId,
        button.current,
        requested.purpose,
      );
  }, [config, availablePurpose, requested, token]);
  useEffect(() => {
    // An open Google prompt must not silently remain a linking offer after expiry.
    // Submission cannot be retracted here; the server checks the same deadline.
    if (
      purpose !== "verify" &&
      availablePurpose !== purpose &&
      (phase === "loading" || phase === "ready")
    )
      controller.current?.cancel();
  }, [availablePurpose, phase, purpose]);
  const choose = async (choice: "account" | "guest") => {
    if (
      !statisticsConfig ||
      choiceFlight.current ||
      recoverySubmitted ||
      busy ||
      phase === "submitting" ||
      (choice === "account" &&
        (!availablePurpose || phase !== "idle" || pending.current))
    )
      return;
    if (!session && (composing || nickname.error)) return;
    const chosenPurpose = availablePurpose;
    choiceFlight.current = true;
    setPreparing(true);
    const previousToken = getClientState().session?.token;
    let expectedToken = previousToken;
    try {
      const preparedToken = await prepareEntryChoice({
        previousToken,
        createSession: () => client.login(name),
        current: () => ({
          token: getClientState().session?.token,
          hasRoom: Boolean(getClientState().room),
        }),
        isMounted: () => live.current,
      });
      if (!preparedToken) return;
      expectedToken = preparedToken;
      if (choice === "guest") {
        pending.current = null;
        setRequested(null);
        controller.current?.cancel();
        // A restored session may still be linked. Never label it a guest until
        // the server confirms unlinking; logout does not delete saved records.
        if (previousToken) {
          const status = await accountRequest<AuthStatus>(
            "/auth/logout",
            "POST",
            {},
            expectedToken,
          );
          if (status.signedIn !== false)
            throw new Error("Guest status not confirmed");
        }
        if (
          live.current &&
          canCompleteEntry(
            expectedToken,
            getClientState().session?.token,
            Boolean(getClientState().room),
          )
        ) {
          await acceptStatistics(expectedToken, statisticsConfig);
          if (
            !live.current ||
            !canCompleteEntry(
              expectedToken,
              getClientState().session?.token,
              Boolean(getClientState().room),
            )
          )
            return;
          controller.current?.dispose();
          enter.current(expectedToken);
        }
      } else if (chosenPurpose) {
        await acceptStatistics(expectedToken, statisticsConfig);
        if (
          !live.current ||
          !canCompleteEntry(
            expectedToken,
            getClientState().session?.token,
            Boolean(getClientState().room),
          )
        )
          return;
        setVerified(null);
        setMessage("");
        pending.current = { token: expectedToken, purpose: chosenPurpose };
        setRequested(pending.current);
      }
    } catch {
      if (live.current && getClientState().session?.token === expectedToken) {
        setMessage("尚無法確認身分或資料使用同意，請重試；目前仍停留在入口。");
      }
    } finally {
      choiceFlight.current = false;
      if (live.current) setPreparing(false);
    }
  };
  const checkStatus = async () => {
    if (
      !token ||
      !linkAttempted ||
      busy ||
      choiceFlight.current ||
      phase !== "idle"
    )
      return;
    choiceFlight.current = true;
    setPreparing(true);
    try {
      const result = await accountRequest<AuthStatus>(
        "/auth/status",
        "GET",
        undefined,
        token,
      );
      if (
        !live.current ||
        !canCompleteEntry(
          token,
          getClientState().session?.token,
          Boolean(getClientState().room),
        )
      )
        return;
      if (result.signedIn === true) enter.current(token, experimentRef.current);
      else
        setMessage(
          recoverySubmitted
            ? "尚未確認恢復成功；請聯絡管理者，不要再次登入或重試恢復。"
            : "尚未確認帳號連結成功，可重新嘗試或當訪客遊玩。",
        );
    } catch {
      if (live.current && getClientState().session?.token === token)
        setMessage("尚無法確認登入結果，請稍後重試；目前仍停留在入口。");
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
    !statisticsConfig ||
    busy ||
    preparing ||
    (!session && (composing || Boolean(nickname.error)));
  return (
    <div className="welcome-grid">
      <section
        className="panel welcome entry-choice"
        aria-label="進入大廳前的選擇"
      >
        <span className="eyebrow">WELCOME TO THE TABLE</span>
        <h2>{session ? `你好，${session.displayName}` : "先認識一下你。"}</h2>
        <p>選用 Google 帳號，或直接當訪客遊玩。</p>
        {!statisticsConfig && (
          <p role="status">
            {statisticsFailed
              ? "統計與資料使用設定暫時無法確認，請重試後再進入。"
              : "正在確認資料使用設定…"}
            {statisticsFailed && (
              <button className="text-button" onClick={loadStatistics}>
                重試設定
              </button>
            )}
          </p>
        )}
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
            ✓ Google 驗證成功（僅驗證）
          </p>
        ) : (
          <button
            className="primary"
            disabled={
              disabled ||
              recoverySubmitted ||
              !availablePurpose ||
              phase !== "idle" ||
              Boolean(requested)
            }
            onClick={() => void choose("account")}
          >
            {availablePurpose === "recover"
              ? "Google 恢復既有帳號（限一次）"
              : availablePurpose === "link"
                ? experiment
                  ? "Google 登入（單人一場實驗）"
                  : "使用 Google 登入"
                : availablePurpose === "verify"
                  ? "Google 驗證（測試）"
                  : config || configFailed
                    ? "Google 登入尚未開放"
                    : "正在確認 Google 服務…"}
          </button>
        )}
        {experimentMessage && (
          <p className="fine" role="status">
            {experimentMessage}
          </p>
        )}
        <p className="fine entry-consent">
          {availablePurpose === "recover"
            ? "恢復表示同意重新驗證本人 Google 帳號，並在私人試算表新增一次性恢復標記；接回原帳號，不刪資料或重設名額。送出後即使失敗也不可重試，請只查詢登入狀態或聯絡管理者。"
            : availablePurpose === "link"
              ? experiment
                ? "登入表示同意將你的 Google 識別碼、遊戲帳號及最多一場戰績存入私人試算表；刪除仍在測試，歷史副本可能保留。重新登入會解除原連結。"
                : managed
                  ? managedLoginConsent
                  : "登入表示同意 Google 驗證、帳號連結及核准測試場次保存；重新登入會解除原連結。"
              : availablePurpose === "verify"
                ? "按下驗證表示同意連線至 Google；目前僅驗證身分，不建立帳號連結或保存對局。"
                : "Google 為選用服務，不影響訪客遊玩。"}{" "}
          {statisticsConfig?.enabled && (
            <>訪客遊玩表示同意正式場與休閒場納入不含個人識別的整體統計。 </>
          )}
          <a href="#help">使用說明：資料保存與刪除</a>
        </p>
        <div ref={button} className="google-signin-button" />
        {phase !== "idle" && (
          <>
            <p role="status">
              {phase === "loading"
                ? "正在載入 Google…"
                : phase === "submitting"
                  ? purpose === "link"
                    ? "正在確認帳號連結…"
                    : "正在驗證…"
                  : "請選擇 Google 帳號。"}
            </p>
            <button
              className="text-button"
              disabled={phase === "submitting"}
              onClick={() => controller.current?.cancel()}
            >
              {purpose === "link" ? "取消登入" : "取消驗證"}
            </button>
          </>
        )}
        <button
          className="secondary entry-continue"
          disabled={disabled || recoverySubmitted || phase === "submitting"}
          onClick={() => void choose("guest")}
        >
          以訪客身分遊玩
        </button>
        {linkAttempted && phase === "idle" && message && (
          <button
            className="text-button"
            disabled={disabled}
            onClick={() => void checkStatus()}
          >
            重新確認登入狀態
          </button>
        )}
        {message && !verified && <p role="status">{message}</p>}
        {verified && (
          <details className="entry-details">
            <summary>查看測試識別碼</summary>
            <p>
              這次驗證不建立帳號連結。識別碼只顯示在此頁；如需申請測試資格，可自行交給你確認的管理者。
            </p>
            <label htmlFor="entry-subject">Google 帳號識別碼（subject）</label>
            <input id="entry-subject" readOnly value={verified.subject} />
            <button
              className="text-button"
              onClick={() => {
                const expectedToken = token;
                void navigator.clipboard
                  .writeText(verified.subject)
                  .then(() => {
                    if (
                      live.current &&
                      getClientState().session?.token === expectedToken
                    )
                      setMessage("已複製，尚未傳送給任何人。");
                  })
                  .catch(() => {
                    if (
                      live.current &&
                      getClientState().session?.token === expectedToken
                    )
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
