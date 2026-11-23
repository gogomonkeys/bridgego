import {
  createRedirectAttempt,
  hasRedirectAttempt,
  isIOSWebApp,
} from "./google-redirect";
import { useEntryReadiness } from "./use-entry-readiness";
import { acceptStatistics } from "./statistics";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { client, getClientState } from "./client";
import type { Session } from "./model";
import { nicknameValidation } from "./nickname";
import "./welcome-lobby-theme.css";
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
  type AuthStatus,
  type LoginAttemptDependencies,
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
  const readiness = useEntryReadiness();
  const statisticsConfig = readiness.statistics;
  const config = readiness.auth;
  const configFailed = readiness.authFailed;
  const [nicknameProgress, setNicknameProgress] = useState<
    "idle" | "saving" | "checking"
  >("idle");
  const [nicknameUncertain, setNicknameUncertain] = useState(false);
  const [nicknameStep, setNicknameStep] = useState<"guest" | "account" | null>(
    null,
  );
  const [name, setName] = useState("");
  const [composing, setComposing] = useState(false);
  const nickname = nicknameValidation(name);
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
  const loginUpdates = useRef(0);
  const controller = useRef<ReturnType<typeof createLoginAttempt> | null>(null);
  const resumeRedirect = useRef<(() => void) | null>(null);
  const live = useRef(true);
  const enter = useRef(onEnter);
  enter.current = onEnter;
  const choiceFlight = useRef(false);
  const entryChoice = useRef<"account" | "guest" | null>(null);
  const pending = useRef<{ token: string; purpose: LoginPurpose } | null>(null);
  const token = session?.token;
  const experimentNow = useExperimentClock(config);
  const availablePurpose = entryAccountPurpose(config, experimentNow);
  const experiment = isSingleTesterExperiment(config);
  const managed = isManagedCollection(config);
  const configRef = useRef(config);
  configRef.current = config;
  const experimentRef = useRef(experiment);
  experimentRef.current = experiment;
  const experimentMessage = experimentNotice(config, experimentNow);
  const finishAccount = async (expected: string, afterSave = false) => {
    setNicknameProgress("checking");
    try {
      const result = await accountRequest<AuthStatus>(
        "/auth/status",
        "GET",
        undefined,
        expected,
      );
      if (
        !live.current ||
        entryChoice.current !== "account" ||
        !canCompleteEntry(
          expected,
          getClientState().session?.token,
          Boolean(getClientState().room),
        )
      )
        return;
      if (!result.signedIn) {
        setMessage("尚未確認登入狀態，請重新確認；目前不會進入大廳。");
        if (afterSave) setNicknameUncertain(true);
        return;
      }
      if (result.nicknameRequired) {
        setComposing(false);
        setNicknameStep("account");
        setMessage(
          afterSave
            ? "已收到保存回應，但尚未確認暱稱完成。請返回身分選擇重新登入讀取結果，不要重複送出。"
            : "",
        );
        if (afterSave) setNicknameUncertain(true);
        return;
      }
      if (
        (isManagedCollection(configRef.current) &&
          (typeof result.nickname !== "string" ||
            Boolean(nicknameValidation(result.nickname).error))) ||
        (result.nickname && !client.adoptNickname(expected, result.nickname))
      ) {
        setMessage("暱稱同步尚未確認，請重新確認登入狀態；目前不會進入大廳。");
        if (afterSave) setNicknameUncertain(true);
        return;
      }
      enter.current(expected, experimentRef.current);
    } catch {
      if (live.current) {
        setMessage(
          "尚無法確認登入及暱稱，請重新確認登入狀態；不會重複送出保存。",
        );
        if (afterSave) setNicknameUncertain(true);
      }
    } finally {
      if (live.current) setNicknameProgress("idle");
    }
  };
  useEffect(() => {
    live.current = true;
    return () => {
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
    const dependencies: LoginAttemptDependencies = {
      nonce: (purpose: LoginPurpose) =>
        accountRequest("/auth/nonce", "POST", { purpose }, token),
      verify: (credential: string) =>
        accountRequest(
          "/auth/google/verify",
          "POST",
          { credential, consent: true },
          token,
        ),
      link: (credential: string) =>
        accountRequest(
          "/auth/google",
          "POST",
          {
            credential,
            consent: true,
            profileConsentVersion: "account-profile-v1",
            nicknameAfterLogin: isManagedCollection(configRef.current),
          },
          token,
        ),
      recover: (credential: string) =>
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
      cancel: (nonce: string) =>
        accountRequest("/auth/cancel", "POST", { nonce }, token),
      eligible,
      update: (next: LoginPhase, text?: string, signedIn?: boolean) => {
        if (!eligible()) return;
        loginUpdates.current++;
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
          void finishAccount(token);
        }
      },
    };
    const popup = createLoginAttempt(dependencies);
    const redirect = createRedirectAttempt({
      token,
      eligible,
      load: loadGoogleIdentity,
      update: dependencies.update,
    });
    let redirectSelected = hasRedirectAttempt();
    const attempt = {
      start: (
        clientId: string,
        element: HTMLElement,
        purpose: LoginPurpose = "link",
      ) => {
        redirectSelected =
          isIOSWebApp() &&
          purpose === "link" &&
          isManagedCollection(configRef.current);
        return redirectSelected
          ? redirect.start(clientId, element, purpose)
          : popup.start(clientId, element, purpose);
      },
      cancel: () => {
        if (redirectSelected) redirect.cancel();
        else popup.cancel();
      },
      dispose: () => {
        popup.dispose();
        redirect.dispose();
      },
    };
    controller.current = attempt;
    resumeRedirect.current = () => {
      if (!hasRedirectAttempt()) return;
      redirectSelected = true;
      setPurpose("link");
      entryChoice.current = "account";
      setLinkAttempted(true);
      redirect.resume();
    };
    return () => {
      attempt.dispose();
      controller.current = null;
      resumeRedirect.current = null;
    };
  }, [token]);
  useEffect(() => {
    if (token && config && readiness.ready) resumeRedirect.current?.();
    if (
      typeof location !== "undefined" &&
      new URLSearchParams(location.search).has("google_return") &&
      !hasRedirectAttempt()
    ) {
      setMessage(
        "Google 驗證已返回。若這不是原本的主畫面 App，請關閉此視窗並返回原 App 確認登入；不會自動建立另一個帳號連結。",
      );
    }
  }, [token, config, readiness.ready]);
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
    const target = button.current;
    const attempt = controller.current;
    if (!target || !attempt) {
      setMessage(
        "Google 登入介面尚未準備好，請稍後重新選擇登入；尚未啟動帳號選擇。",
      );
      return;
    }
    const before = loginUpdates.current;
    void attempt
      .start(config.clientId, target, requested.purpose)
      .then(() => {
        if (
          live.current &&
          getClientState().session?.token === requested.token &&
          loginUpdates.current === before
        )
          setMessage(
            "本次 Google 登入未能啟動，請重新確認登入狀態；不會重複送出暱稱保存。",
          );
      })
      .catch(() => {
        if (live.current && getClientState().session?.token === requested.token)
          setMessage("Google 登入介面載入失敗，請稍後重新選擇登入。");
      });
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
      !readiness.ready ||
      !statisticsConfig ||
      choiceFlight.current ||
      recoverySubmitted ||
      busy ||
      phase === "submitting" ||
      (choice === "account" &&
        (!availablePurpose || phase !== "idle" || pending.current))
    )
      return;
    if (choice === "guest" && (composing || nickname.error)) return;
    entryChoice.current = choice;
    setNicknameUncertain(false);
    setComposing(false);
    const chosenPurpose = availablePurpose;
    choiceFlight.current = true;
    setPreparing(true);
    const previousToken = getClientState().session?.token;
    let expectedToken = previousToken;
    try {
      const preparedToken = await prepareEntryChoice({
        previousToken,
        createSession: () => client.login(choice === "account" ? "玩家" : name),
        current: () => ({
          token: getClientState().session?.token,
          hasRoom: Boolean(getClientState().room),
        }),
        isMounted: () => live.current,
      });
      if (!preparedToken) {
        if (live.current)
          setMessage(
            "目前遊戲身分無法確認，請重新確認登入狀態；尚未啟動 Google 登入。",
          );
        return;
      }
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
        const guest = await accountRequest<Session>(
          "/sessions/nickname",
          "POST",
          { displayName: nickname.normalized },
          expectedToken,
        );
        if (!client.adoptNickname(expectedToken, guest.displayName)) return;
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
      if (result.signedIn === true) await finishAccount(token);
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
    if (nicknameStep === "guest") {
      void choose("guest");
      return;
    }
    if (
      !token ||
      composing ||
      nickname.error ||
      choiceFlight.current ||
      nicknameUncertain
    )
      return;
    choiceFlight.current = true;
    setPreparing(true);
    setNicknameProgress("saving");
    setMessage("");
    void accountRequest<AuthStatus>(
      "/auth/nickname",
      "POST",
      { nickname: nickname.normalized },
      token,
    )
      .then(() => finishAccount(token, true))
      .catch(() => {
        if (live.current) {
          setNicknameUncertain(true);
          setMessage(
            "暱稱儲存尚未確認。請返回身分選擇並重新登入 Google 讀取已保存暱稱；不要重複送出。",
          );
        }
      })
      .finally(() => {
        choiceFlight.current = false;
        if (live.current) {
          setPreparing(false);
          setNicknameProgress("idle");
        }
      });
  };
  const disabled =
    !readiness.ready ||
    !statisticsConfig ||
    busy ||
    preparing ||
    nicknameProgress !== "idle";
  return (
    <div className="welcome-grid woodland-entry">
      <div className="woodland-entry-hero" aria-hidden="true">
        <img
          src="/assets/forest/welcome-grass.webp"
          alt=""
          loading="eager"
          draggable={false}
        />
      </div>
      <section
        className="panel welcome entry-choice"
        aria-label="進入大廳前的選擇"
      >
        <span className="eyebrow">WELCOME TO THE TABLE</span>
        <h2>歡迎來到 BridgeGo</h2>
        <p>
          {nicknameStep
            ? nicknameStep === "account"
              ? "設定遊戲暱稱，下次登入會沿用。"
              : "請輸入遊戲暱稱。"
            : "林間留了位子，來打一局吧。"}
        </p>
        {!readiness.ready && (
          <p role="status" aria-live="polite">
            {readiness.phase === "stopped"
              ? "服務暫時無法連線，請稍後重試。"
              : readiness.phase === "maintenance"
                ? "服務正在切換或維護，請稍候…"
                : readiness.phase === "limited"
                  ? "連線較頻繁，請稍候再試。"
                  : readiness.phase === "waiting"
                    ? "服務可能正在喚醒，請稍候…"
                    : "正在連線，請稍候…"}
          </p>
        )}
        {readiness.phase === "stopped" && (
          <button
            className="text-button"
            onClick={readiness.retry}
            disabled={
              !readiness.retryAllowed ||
              busy ||
              preparing ||
              nicknameProgress !== "idle" ||
              phase !== "idle"
            }
          >
            重新連線
          </button>
        )}
        {nicknameStep && (
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
                : `${nickname.count}／10 字${name && nickname.error ? ` · ${nickname.error}` : ""}`}
            </small>
            <button
              className="primary"
              type="submit"
              disabled={
                disabled ||
                composing ||
                Boolean(nickname.error) ||
                nicknameUncertain
              }
            >
              {nicknameProgress === "saving"
                ? "正在保存暱稱…"
                : nicknameProgress === "checking"
                  ? "正在確認登入…"
                  : "確認暱稱並進入大廳"}
            </button>
            <button
              type="button"
              className="text-button"
              disabled={disabled}
              onClick={() => {
                setNicknameStep(null);
                setComposing(false);
                setMessage("");
              }}
            >
              返回身分選擇
            </button>
          </form>
        )}
        {!nicknameStep &&
          (verified ? (
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
                    : configFailed
                      ? "Google 暫時無法連線"
                      : config?.enabled === false
                        ? "Google 登入尚未開放"
                        : config
                          ? "Google 暫時無法登入"
                          : "正在確認 Google 服務…"}
            </button>
          ))}
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
        {!nicknameStep && (
          <button
            className="secondary entry-continue"
            disabled={disabled || recoverySubmitted || phase === "submitting"}
            onClick={() => {
              entryChoice.current = "guest";
              setNicknameUncertain(false);
              controller.current?.cancel();
              setNicknameStep("guest");
              setComposing(false);
              setName("");
              setMessage("");
            }}
          >
            以訪客身分遊玩
          </button>
        )}
        {linkAttempted && phase === "idle" && message && (
          <button
            className="text-button"
            disabled={disabled}
            onClick={() => void checkStatus()}
          >
            重新確認登入狀態
          </button>
        )}
        {nicknameProgress !== "idle" && (
          <p role="status" aria-live="polite">
            {nicknameProgress === "saving"
              ? "正在保存暱稱，請稍候；不需要再次按確認。"
              : "正在確認登入與暱稱，請稍候。"}
          </p>
        )}
        {message && !verified && nicknameProgress === "idle" && (
          <p role="status">{message}</p>
        )}
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
    </div>
  );
}
