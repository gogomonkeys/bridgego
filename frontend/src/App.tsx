import WaitingVoicePanel from "./WaitingVoicePanel";
import FriendsHub from "./FriendsHub";
import { friendsEntryPlacement } from "./friends-entry-placement";
import LeaderboardPanel from "./LeaderboardPanel";
import { roomTitle, shortRoomId } from "./room-display";
import "./lobby-feedback.css";
import TutorialHelpEntry from "./TutorialHelpEntry";
import TutorialFlow from "./TutorialFlow";
import RoomTypeChoice, { RoomTypeBadge, roomTypeLabel } from "./RoomTypeChoice";
import type { RoomType } from "./model";
import StatisticsPanel from "./StatisticsPanel";
import { accountRequest, type AuthStatus } from "./google-login";
import { readStatisticsConfig } from "./statistics";
import DataPrivacyHelp from "./DataPrivacyHelp";
import GameHelp from "./GameHelp";
import WelcomeEntry from "./WelcomeEntry";
import { needsEntry } from "./entry-flow";
import PwaStatus from "./PwaStatus";
import ScreenAwake from "./ScreenAwake";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { client, useClient, getConnectionDiagnostics } from "./client";
import GamePanel from "./GamePanel";
import TableSeats from "./TableSeats";
import ComputerPlayers from "./ComputerPlayers";
import { PreviousMatchSummary } from "./ResultPanel";
export default function App() {
  const state = useClient();
  const [friendsEntryTarget, setFriendsEntryTarget] =
    useState<HTMLElement | null>(null);
  const [tutorialAccountEpoch, setTutorialAccountEpoch] = useState(0);
  const [tutorialChecked, setTutorialChecked] = useState<string | null>(null);
  const [tutorialRequested, setTutorialRequested] = useState(false);
  const [tutorialTableActive, setTutorialTableActive] = useState(false);
  const [tutorialAvailability, setTutorialAvailability] = useState<{
    key: string;
    enabled: boolean;
  } | null>(null);
  const [enteredToken, setEnteredToken] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [roomType, setRoomType] = useState<RoomType>("CASUAL");
  const visibleRooms = state.rooms.filter(
    (room) => (room.roomType ?? "CASUAL") === roomType,
  );
  const [plannedDeals, setPlannedDeals] = useState<1 | 3 | 5>(3);
  const [route, setPage] = useState<
    "room" | "help" | "statistics" | "leaderboard"
  >(() =>
    typeof window !== "undefined" && window.location?.hash === "#help"
      ? "help"
      : typeof window !== "undefined" && window.location?.hash === "#statistics"
        ? "statistics"
        : typeof window !== "undefined" &&
            window.location?.hash === "#leaderboard"
          ? "leaderboard"
          : "room",
  );
  const page =
    (route === "statistics" || route === "leaderboard") && state.room
      ? "room"
      : route;
  const statisticsPage = page === "statistics" || page === "leaderboard";
  const navigate = (next: "room" | "help" | "statistics" | "leaderboard") => {
    setPage(next);
    window.location.hash = next;
  };
  useEffect(() => {
    const changed = () =>
      setPage(
        window.location.hash === "#help"
          ? "help"
          : window.location.hash === "#statistics"
            ? "statistics"
            : window.location.hash === "#leaderboard"
              ? "leaderboard"
              : "room",
      );
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  const [statisticsEntryState, setStatisticsEntryState] = useState<
    "loading" | "ready" | "guest" | "disabled" | "error"
  >("loading");
  const [statisticsAuth, setStatisticsAuth] = useState<string | null>(null);
  const [accountEpoch, setAccountEpoch] = useState(0);
  const accountChanging = useRef(false);
  useEffect(() => {
    const changing = () => {
      accountChanging.current = true;
      setTutorialAccountEpoch((value) => value + 1);
      setStatisticsAuth(null);
      setAccountEpoch((value) => value + 1);
    };
    const changed = () => {
      accountChanging.current = false;
      setStatisticsAuth(null);
      setAccountEpoch((value) => value + 1);
    };
    window.addEventListener("bridge-account-changing", changing);
    window.addEventListener("bridge-account-changed", changed);
    window.addEventListener("focus", changed);
    return () => {
      window.removeEventListener("bridge-account-changing", changing);
      window.removeEventListener("bridge-account-changed", changed);
      window.removeEventListener("focus", changed);
    };
  }, []);
  useEffect(() => {
    let live = true;
    setStatisticsAuth(null);
    const token = state.session?.token;
    if (
      !token ||
      state.room ||
      enteredToken !== token ||
      accountChanging.current
    )
      return;
    setStatisticsEntryState("loading");
    void Promise.all([
      readStatisticsConfig(),
      accountRequest<AuthStatus>("/auth/status", "GET", undefined, token),
    ])
      .then(([config, auth]) => {
        if (!live) return;
        setStatisticsEntryState(
          !config.enabled
            ? "disabled"
            : auth.signedIn === true
              ? "ready"
              : "guest",
        );
        if (config.enabled && auth.signedIn === true) setStatisticsAuth(token);
      })
      .catch(() => {
        if (live) setStatisticsEntryState("error"); // Unknown authorization never exposes private data.
      });
    return () => {
      live = false;
    };
  }, [state.session?.token, state.room, enteredToken, accountEpoch, route]);
  const canSeeStatistics = Boolean(
    state.session &&
    !state.room &&
    enteredToken === state.session.token &&
    statisticsAuth === state.session.token &&
    !accountChanging.current,
  );
  const canSeeLeaderboard = Boolean(
    state.session &&
    !state.room &&
    enteredToken === state.session.token &&
    !accountChanging.current,
  );
  const [copied, setCopied] = useState(false);
  const [diagnosticCopy, setDiagnosticCopy] = useState("");
  const diagnosticRef = useRef<HTMLTextAreaElement>(null);
  const diagnostics = getConnectionDiagnostics();
  const diagnosticText = JSON.stringify(
    { version: "0.16.4", disconnects: diagnostics },
    null,
    2,
  );
  useEffect(() => {
    void client.initialize();
  }, []);
  const me = state.room?.players.find(
    (p) => p.playerId === state.session?.playerId,
  );
  const submit = (event: FormEvent, action: () => void) => {
    event.preventDefault();
    action();
  };
  return (
    <div
      className={`shell forest-shell ${tutorialTableActive ? "match-shell tutorial-shell" : state.room && state.room.phase !== "WAITING" && page === "room" ? "match-shell" : ""}`}
    >
      <header>
        <a
          href="#"
          className="brand"
          onClick={(e) => {
            e.preventDefault();
            navigate("room");
          }}
          aria-label="BridgeGo 首頁"
        >
          <span className="brand-symbol">♠</span>
          <span>
            Bridge<span className="brand-go">Go</span>
            <small>一起入座，等好友到齊。</small>
          </span>
        </a>
        <nav>
          {page !== "room" && (
            <button
              className="return-navigation"
              onClick={() => navigate("room")}
            >
              {state.room && state.room.phase !== "WAITING"
                ? "返回牌桌"
                : "等候大廳"}
            </button>
          )}
          <button
            className={page === "help" ? "nav-active" : ""}
            onClick={() => navigate("help")}
          >
            使用說明
          </button>
        </nav>
      </header>
      <main
        className={
          page === "help"
            ? "help-page"
            : statisticsPage
              ? "statistics-page"
              : undefined
        }
      >
        {(page === "help" || statisticsPage) && (
          <div className="help-version">
            <span className="eyebrow">v0.16.4</span>
          </div>
        )}
        <PwaStatus
          help={page === "help"}
          activeGame={
            !!state.room && state.room.phase !== "WAITING" && page === "room"
          }
        />
        <ScreenAwake
          show={page === "help"}
          activeGame={
            !!state.room?.game &&
            !["WAITING", "MATCH_RESULT", "ABORTED"].includes(
              state.room.phase,
            ) &&
            !state.room.game.matchResult
          }
        />
        {state.serviceMaintenance && (
          <p className="notice" role="status">
            服務準備重新啟動，目前牌局無法繼續。
            <button disabled={state.busy} onClick={client.checkService}>
              確認服務狀態
            </button>
          </p>
        )}
        {!tutorialTableActive &&
          page === "room" &&
          (!state.session || state.room?.phase === "WAITING") && (
            <div className="intro">
              <div>
                <span className="eyebrow">v0.16.4</span>
                <h1>
                  {state.room ? "你的牌友，正在這裡。" : "四個座位，一起相聚。"}
                </h1>
              </div>
            </div>
          )}
        {state.error && (
          <div className="alert" role="alert">
            <span>{state.error}</span>
            <button aria-label="關閉錯誤訊息" onClick={client.clearError}>
              ×
            </button>
          </div>
        )}
        {state.session && enteredToken === state.session.token && (
          <FriendsHub
            key={state.session.token}
            token={state.session.token}
            room={state.room}
            entryTarget={friendsEntryTarget}
            entryPlacement={friendsEntryPlacement(page, state.room)}
            disabled={
              tutorialTableActive ||
              !!state.serviceMaintenance ||
              state.networkAvailable === false ||
              accountChanging.current
            }
          />
        )}
        {state.notice && !statisticsPage && (
          <p className="notice" role="status">
            {state.notice}
          </p>
        )}
        {state.pendingAction && !state.busy && (
          <div className="notice" role="status">
            <p>
              「{state.pendingAction}
              」結果尚未確認。請重試同一操作，或重新同步確認最新狀態。
            </p>
            <button
              className="secondary"
              onClick={() => void client.retryCommand()}
            >
              重試上一個操作
            </button>{" "}
            <button
              className="secondary"
              onClick={() => void client.synchronize()}
            >
              重新同步
            </button>
          </div>
        )}
        {page === "help" && diagnostics.length > 0 && (
          <details className="panel diagnostics">
            <summary>
              連線診斷 · 最近 {diagnostics.length} 次（不含身分或權杖）
            </summary>
            <p>可複製這段資料協助排查；記錄只留在目前頁面，最多 20 次。</p>
            <textarea
              ref={diagnosticRef}
              aria-label="可複製的連線診斷"
              readOnly
              value={diagnosticText}
              rows={8}
            />
            <button
              className="secondary"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(diagnosticText);
                  setDiagnosticCopy("已複製診斷資料");
                } catch {
                  diagnosticRef.current?.focus();
                  diagnosticRef.current?.select();
                  setDiagnosticCopy(
                    "無法自動複製，已選取文字，請按 Ctrl+C 或長按複製。",
                  );
                }
              }}
            >
              複製連線診斷
            </button>
            <span role="status">{diagnosticCopy}</span>
          </details>
        )}
        {page === "help" ? (
          <section className="panel help">
            <span className="eyebrow">HOW IT WORKS</span>
            <h2>使用說明</h2>
            <GameHelp />
            <DataPrivacyHelp />
            <button className="primary" onClick={() => navigate("room")}>
              {state.room && state.room.phase !== "WAITING"
                ? "返回牌桌"
                : "回到等候室"}
            </button>
          </section>
        ) : !state.initialized ? (
          <section className="panel">
            <h2>正在恢復訪客身分…</h2>
          </section>
        ) : needsEntry(
            state.session?.token ?? null,
            enteredToken,
            Boolean(state.room),
          ) ? (
          <WelcomeEntry
            session={state.session}
            busy={state.busy}
            onEnter={(token, experiment) => {
              if (experiment) setPlannedDeals(1);
              setEnteredToken(token);
            }}
          />
        ) : accountChanging.current ? (
          <section className="panel">
            <p role="status">正在切換身分…</p>
          </section>
        ) : state.session?.token &&
          !state.room &&
          (tutorialRequested ||
            tutorialChecked !==
              `${state.session.token}:${tutorialAccountEpoch}`) ? (
          <TutorialFlow
            key={`${state.session.token}:${tutorialAccountEpoch}`}
            token={state.session.token}
            force={tutorialRequested}
            onTableActive={setTutorialTableActive}
            onAvailability={(enabled) =>
              setTutorialAvailability({
                key: `${state.session!.token}:${tutorialAccountEpoch}`,
                enabled,
              })
            }
            onDone={() => {
              setTutorialChecked(
                `${state.session!.token}:${tutorialAccountEpoch}`,
              );
              setTutorialRequested(false);
            }}
          />
        ) : page === "leaderboard" && canSeeLeaderboard ? (
          <section className="panel statistics-panel">
            <h2>排行榜</h2>
            <LeaderboardPanel
              key={`${state.session!.token}:${accountEpoch}`}
              token={state.session!.token}
              onBack={() => navigate("room")}
            />
          </section>
        ) : page === "statistics" && canSeeStatistics ? (
          <StatisticsPanel
            key={`${state.session!.token}:${accountEpoch}:${page}`}
            initialTab="personal"
            token={state.session!.token}
            onBack={() => navigate("room")}
          />
        ) : page === "statistics" && !state.room ? (
          <section className="panel">
            <p role="status">
              {statisticsEntryState === "loading"
                ? "正在確認統計權限…"
                : statisticsEntryState === "guest"
                  ? "登入 Google 後可查看個人統計。"
                  : statisticsEntryState === "disabled"
                    ? "統計尚未開放。"
                    : "暫時無法確認統計權限。"}
            </p>
            {statisticsEntryState === "error" && (
              <button
                className="secondary"
                onClick={() => setAccountEpoch((value) => value + 1)}
              >
                重新確認
              </button>
            )}
            <button className="secondary" onClick={() => navigate("room")}>
              回到大廳
            </button>
          </section>
        ) : state.room && state.room.phase !== "WAITING" ? (
          <GamePanel
            room={state.room}
            online={
              state.status === "online" && state.networkAvailable !== false
            }
            busy={state.busy}
            pendingAction={state.pendingAction}
            snapshotReceivedAt={state.snapshotReceivedAt}
            connectionEpoch={state.connectionEpoch}
            onHelp={() => navigate("help")}
          />
        ) : state.room ? (
          <div className="room-layout">
            <section className="panel table-panel">
              <div className="room-heading">
                <div>
                  <span className="eyebrow">PRIVATE WAITING ROOM</span>
                  <h2>
                    <span className="room-display-title">
                      {roomTitle(
                        state.room.players.find(
                          (player) =>
                            player.playerId === state.room!.hostPlayerId,
                        )?.displayName,
                      )}
                    </span>
                    <small data-testid="room-code" title={state.room.roomId}>
                      #
                      {shortRoomId(
                        state.room.roomId,
                        state.rooms.map((room) => room.roomId),
                      )}
                    </small>
                    <RoomTypeBadge type={state.room.roomType} />
                  </h2>
                </div>
                <button
                  className="secondary"
                  onClick={() => {
                    void navigator.clipboard
                      .writeText(state.room!.roomId)
                      .then(() => setCopied(true))
                      .catch(() => setCopied(false));
                  }}
                >
                  {copied ? "已複製" : "複製代碼"}
                </button>
              </div>
              <div className="play-surface waiting-surface">
                <TableSeats room={state.room} turnSeat={null} />
                <div className="table-focus">
                  <span className="eyebrow">WAITING ROOM</span>
                  <h3>{state.room.players.length} / 4 位玩家</h3>
                  <p>你在下方，隊友在對面。</p>
                  <p>本場 {state.room.plannedDeals ?? 3} 局 · 尚未開局</p>
                  <p>
                    {state.room.roomType === "FORMAL"
                      ? "四位真人準備後，由房主開局。"
                      : "全部準備後，由房主手動開局。"}
                  </p>
                </div>
              </div>
              <div className="table-bottom">
                <span className={`connection ${state.status}`} role="status">
                  ●{" "}
                  {state.status === "online"
                    ? "即時同步已連線"
                    : state.status === "connecting"
                      ? "正在連線／重連…"
                      : "連線中斷，正在重試…"}
                </span>
                <details className="waiting-diagnostics">
                  <summary>連線資訊</summary>
                  <small>狀態版本 {state.room.revision}</small>
                </details>
              </div>
            </section>
            <aside className="panel room-controls">
              {state.room.lastMatch && (
                <PreviousMatchSummary
                  match={state.room.lastMatch}
                  viewerPlayerId={
                    state.room.you?.playerId ?? state.session?.playerId
                  }
                />
              )}
              <span className="eyebrow">MAKE YOURSELF AT HOME</span>
              <h2>你好，{state.session!.displayName}</h2>
              <p>
                把房間代碼分享給朋友。
                <br />
                人到齊，再一起準備。
              </p>
              {state.room.roomType === "FORMAL" && (
                <p className="room-type-note">
                  正式場僅限四位真人。開局後主動離開或斷線超過 10
                  秒，依規則判負；雙方皆失敗可能不計勝負。
                </p>
              )}
              <div
                className="room-social-tools"
                role="group"
                aria-label="房間交流"
              >
                <div
                  className="room-friends-slot"
                  ref={setFriendsEntryTarget}
                />
                <WaitingVoicePanel
                  roomId={state.room.roomId}
                  online={
                    state.status === "online" &&
                    state.networkAvailable !== false
                  }
                />
              </div>
              <ComputerPlayers
                room={state.room}
                playerId={state.session!.playerId}
                disabled={
                  state.busy ||
                  state.status !== "online" ||
                  state.networkAvailable === false ||
                  !!state.pendingAction
                }
                onAdd={(seat) => void client.addComputer(seat)}
                onRemove={(playerId) => void client.removeComputer(playerId)}
              />
              <div className="ready-count">
                <strong>
                  {state.room.players.filter((p) => p.ready).length}
                  <span> / 4</span>
                </strong>
                <p>位玩家已準備</p>
              </div>
              {state.room.allReady && (
                <p className="all-ready" role="status">
                  四位玩家已就緒 ✓<br />
                  <small>請等候房主按「開始遊戲」，不會自動開局。</small>
                </p>
              )}
              <button
                className="primary"
                disabled={
                  state.busy ||
                  state.status !== "online" ||
                  !me ||
                  !!state.pendingAction
                }
                onClick={() => void client.ready()}
              >
                {me?.ready ? "取消準備" : "我準備好了 ✓"}
              </button>
              {state.room.hostPlayerId === state.session!.playerId && (
                <button
                  className="primary start-game"
                  disabled={
                    state.busy ||
                    !state.room.canStart ||
                    state.status !== "online" ||
                    !!state.pendingAction
                  }
                  onClick={() => void client.start()}
                >
                  開始遊戲 · 房主
                </button>
              )}
              <button
                className="leave"
                disabled={state.busy || !!state.pendingAction}
                onClick={() => void client.leave()}
              >
                離開房間
              </button>
              <p className="fine">
                手動離開會立即釋出席位。
                <br />
                偵測斷線即釋出席位，回來需重新加入。
              </p>
            </aside>
          </div>
        ) : (
          <div className="lobby-layout forest-lobby">
            <section
              className="panel lobby-account-panel"
              aria-label="玩家與功能"
            >
              <div className="lobby-panel-heading">
                <div className="lobby-greeting">
                  <img
                    className="lobby-welcome-animal"
                    src="/assets/forest/animal-fox.webp"
                    alt=""
                    aria-hidden="true"
                    draggable={false}
                  />
                  <div>
                    <span className="eyebrow">森林牌桌 · v0.16.4</span>
                    <h2>你好，{state.session!.displayName}</h2>
                    <p>找個座位，一起玩一場。</p>
                  </div>
                </div>
                {canSeeLeaderboard && (
                  <div className="lobby-statistics-actions">
                    {canSeeStatistics && (
                      <button
                        type="button"
                        className="secondary lobby-statistics-button"
                        onClick={() => navigate("statistics")}
                      >
                        <svg
                          viewBox="0 0 20 20"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="1.8"
                          strokeLinecap="round"
                          aria-hidden="true"
                          focusable="false"
                        >
                          <path d="M3 16.5h14M5 13V9m5 4V3.5m5 9.5V6" />
                        </svg>
                        <span>戰績統計</span>
                      </button>
                    )}
                    <button
                      type="button"
                      className="secondary lobby-statistics-button"
                      onClick={() => navigate("leaderboard")}
                    >
                      <svg
                        viewBox="0 0 20 20"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinejoin="round"
                        aria-hidden="true"
                        focusable="false"
                      >
                        <path d="M7 17V4h6v13M2 17V9h5m6 2h5v6M1 17h18" />
                      </svg>
                      <span>排行榜</span>
                    </button>
                  </div>
                )}
              </div>
              {statisticsEntryState === "error" && (
                <div className="lobby-entry-retry" role="status">
                  <span>統計入口暫時無法確認</span>
                  <button
                    className="text-button"
                    onClick={() => setAccountEpoch((value) => value + 1)}
                  >
                    重試
                  </button>
                </div>
              )}
              <div className="lobby-tools-row">
                <div className="lobby-tutorial-entry">
                  <TutorialHelpEntry
                    key={`${state.session!.token}:${tutorialAccountEpoch}`}
                    token={state.session!.token}
                    direct
                    enabled={
                      tutorialAvailability?.key ===
                      `${state.session!.token}:${tutorialAccountEpoch}`
                        ? tutorialAvailability.enabled
                        : null
                    }
                    onAvailability={(enabled) =>
                      setTutorialAvailability({
                        key: `${state.session!.token}:${tutorialAccountEpoch}`,
                        enabled,
                      })
                    }
                    onStart={() => {
                      setTutorialRequested(true);
                      navigate("room");
                    }}
                  />
                  <div
                    className="lobby-friends-slot"
                    ref={setFriendsEntryTarget}
                  />
                </div>
                <button
                  className="text-button lobby-identity-return"
                  onClick={client.forget}
                  disabled={state.busy}
                >
                  返回身分選擇
                </button>
              </div>
            </section>
            <section
              className="panel lobby-create-panel"
              aria-labelledby="create-room-title"
            >
              <h2 id="create-room-title">建立房間</h2>
              <RoomTypeChoice
                value={roomType}
                disabled={state.busy}
                onChange={setRoomType}
              />
              <fieldset className="deal-count-choice" disabled={state.busy}>
                <legend>本場局數</legend>
                {[1, 3, 5].map((count) => (
                  <label key={count}>
                    <input
                      type="radio"
                      name="planned-deals"
                      value={count}
                      checked={plannedDeals === count}
                      onChange={() => setPlannedDeals(count as 1 | 3 | 5)}
                    />
                    {count} 局{count === 3 ? "（預設）" : ""}
                  </label>
                ))}
              </fieldset>
              <button
                className="primary create-room"
                disabled={state.busy}
                onClick={() => void client.create(plannedDeals, roomType)}
              >
                ＋ 建立{roomTypeLabel(roomType)}房間
              </button>
            </section>
            <section
              className="panel lobby-join-panel"
              aria-labelledby="join-room-title"
            >
              <h2 id="join-room-title">加入朋友</h2>
              <form
                onSubmit={(event) =>
                  submit(event, () => void client.join(code))
                }
              >
                <label htmlFor="room-code">房間代碼</label>
                <div className="join-row">
                  <input
                    id="room-code"
                    value={code}
                    onChange={(e) => setCode(e.target.value)}
                    placeholder="輸入朋友的代碼"
                    maxLength={64}
                    required
                  />
                  <button
                    className="secondary"
                    disabled={state.busy || !code.trim()}
                  >
                    加入房間
                  </button>
                </div>
              </form>
            </section>
            <section
              className="panel lobby-rooms-panel"
              aria-labelledby="available-rooms-title"
            >
              <div className="list-heading">
                <div>
                  <h2 id="available-rooms-title">
                    目前的{roomTypeLabel(roomType)}房間
                  </h2>
                </div>
                <button
                  className="secondary"
                  disabled={state.busy}
                  onClick={() => void client.refresh()}
                >
                  重新整理
                </button>
              </div>
              {visibleRooms.length ? (
                <ul className="room-list">
                  {visibleRooms.map((room) => (
                    <li key={room.roomId}>
                      <div>
                        <strong className="room-display-title">
                          {roomTitle(room.hostDisplayName)}
                        </strong>
                        <small title={room.roomId}>
                          #
                          {shortRoomId(
                            room.roomId,
                            state.rooms.map((item) => item.roomId),
                          )}
                        </small>
                        <RoomTypeBadge type={room.roomType} />
                        <small>
                          {room.playerCount} / 4 位玩家 ·{" "}
                          {room.plannedDeals ?? 3} 局 ·{" "}
                          {room.state === "FINISHED"
                            ? "已結束"
                            : room.state === "ACTIVE"
                              ? "遊戲中"
                              : "等候中"}
                        </small>
                      </div>
                      <button
                        className="secondary"
                        disabled={state.busy || !room.joinable}
                        onClick={() => void client.join(room.roomId)}
                      >
                        {room.state === "FINISHED"
                          ? "已結束"
                          : room.state === "ACTIVE"
                            ? "遊戲中"
                            : !room.joinable
                              ? "已滿"
                              : "入座 →"}
                      </button>
                    </li>
                  ))}
                </ul>
              ) : (
                <div className="empty">
                  <span>♧</span>
                  <p>
                    目前沒有{roomTypeLabel(roomType)}房間，開一桌邀請朋友吧。
                  </p>
                </div>
              )}
            </section>
          </div>
        )}
      </main>
      <footer>
        <span>BRIDGEGO · 一起玩的起點</span>
      </footer>
    </div>
  );
}
