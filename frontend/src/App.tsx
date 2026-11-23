import WaitingVoicePanel from "./WaitingVoicePanel";
import FriendsHub from "./FriendsHub";
import { friendsEntryPlacement } from "./friends-entry-placement";
import LeaderboardPanel from "./LeaderboardPanel";
import { roomTitle, shortRoomId } from "./room-display";
import "./lobby-feedback.css";
import "./waiting-room-design.css";
import TutorialHelpEntry from "./TutorialHelpEntry";
import TutorialFlow from "./TutorialFlow";
import { RoomTypeBadge } from "./RoomTypeChoice";
import WoodlandLobby from "./WoodlandLobby";
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
import { useEffect, useRef, useState } from "react";
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
    { version: "0.17.2", disconnects: diagnostics },
    null,
    2,
  );
  useEffect(() => {
    void client.initialize();
  }, []);
  const me = state.room?.players.find(
    (p) => p.playerId === state.session?.playerId,
  );
  const showLobbyChrome = Boolean(
    page === "room" &&
    state.initialized &&
    state.session?.token &&
    !state.room &&
    enteredToken === state.session.token &&
    !accountChanging.current &&
    !tutorialRequested &&
    tutorialChecked === `${state.session.token}:${tutorialAccountEpoch}`,
  );
  return (
    <div
      className={`shell forest-shell ${showLobbyChrome ? "cabins-lobby-shell" : ""} ${tutorialTableActive ? "match-shell tutorial-shell" : state.room && state.room.phase !== "WAITING" && page === "room" ? "match-shell" : ""}`}
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
          {showLobbyChrome ? (
            <>
              BridgeGo{" "}
              <span className="cabins-brand-club" aria-hidden="true">
                ♣
              </span>
            </>
          ) : (
            <>
              <span className="brand-symbol">♠</span>
              <span>
                Bridge<span className="brand-go">Go</span>
                <small>一起入座，等好友到齊。</small>
              </span>
            </>
          )}
        </a>
        {showLobbyChrome ? (
          <span
            className="cabins-profile"
            aria-label={`玩家：${state.session!.displayName}`}
          >
            {state.session!.displayName}
          </span>
        ) : (
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
        )}
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
            <span className="eyebrow">v0.17.2</span>
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
          !state.session &&
          !state.room && (
            <div className="intro">
              <div>
                <span className="eyebrow">v0.17.2</span>
                <h1>四個座位，一起相聚。</h1>
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
          <section className="panel statistics-panel" aria-label="排行榜">
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
          <div className="room-layout waiting-room">
            <section className="panel table-panel waiting-main">
              <div className="room-heading waiting-room-banner">
                <div>
                  <span className="eyebrow">森林等候室 · v0.17.2</span>
                  <h1>
                    <span className="room-display-title">
                      {roomTitle(
                        state.room.players.find(
                          (player) =>
                            player.playerId === state.room!.hostPlayerId,
                        )?.displayName,
                      )}
                    </span>
                  </h1>
                  <div className="waiting-room-meta">
                    <RoomTypeBadge type={state.room.roomType} />
                    <span>
                      本場 {state.room.plannedDeals ?? 3} 局 · 尚未開局
                    </span>
                  </div>
                  <div className="waiting-code-row">
                    <span>房間代碼</span>
                    <small data-testid="room-code" title={state.room.roomId}>
                      #
                      {shortRoomId(
                        state.room.roomId,
                        state.rooms.map((room) => room.roomId),
                      )}
                    </small>
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
                </div>
                <img
                  className="waiting-banner-animal"
                  src="/assets/forest/animal-fox.webp"
                  alt=""
                  draggable={false}
                />
              </div>
              <div className="waiting-seats-heading">
                <h3>玩家座位</h3>
                <p>
                  {state.room.players.length} / 4 人 ·{" "}
                  {state.room.players.filter((p) => p.ready).length} 人已準備
                </p>
              </div>
              <div className="play-surface waiting-surface waiting-round-table">
                <TableSeats room={state.room} turnSeat={null} />
                <div className="waiting-oval" aria-hidden="true">
                  <div className="waiting-oval-felt">
                    <span>♣</span>
                    <span>♥</span>
                  </div>
                </div>
              </div>
              <p className="waiting-seat-help">
                你在下方，隊友在對面。
                {state.room.roomType === "FORMAL"
                  ? "四位真人準備後，由房主開局。"
                  : "全部準備後，由房主手動開局。"}
              </p>
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
              <span className="eyebrow">房間工具</span>
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
          <WoodlandLobby
            rooms={state.rooms}
            roomType={roomType}
            busy={state.busy}
            code={code}
            plannedDeals={plannedDeals}
            onRoomType={setRoomType}
            onCode={setCode}
            onDeals={setPlannedDeals}
            onCreate={() => void client.create(plannedDeals, roomType)}
            onJoin={(id) => void client.join(id)}
            onRefresh={() => void client.refresh()}
            onHelp={() => navigate("help")}
            onForget={client.forget}
            accessNotice={
              statisticsEntryState === "error" && (
                <div className="lobby-entry-retry" role="status">
                  <span>統計入口暫時無法確認</span>
                  <button
                    type="button"
                    className="text-button"
                    onClick={() => setAccountEpoch((value) => value + 1)}
                  >
                    重試
                  </button>
                </div>
              )
            }
            tools={
              <>
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
                {canSeeLeaderboard && (
                  <button type="button" onClick={() => navigate("leaderboard")}>
                    排行榜
                  </button>
                )}
                {canSeeStatistics && (
                  <button type="button" onClick={() => navigate("statistics")}>
                    戰績統計
                  </button>
                )}
              </>
            }
          />
        )}
      </main>
      <footer>
        <span>BRIDGEGO · 一起玩的起點</span>
      </footer>
    </div>
  );
}
