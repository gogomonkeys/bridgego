import PwaStatus from "./PwaStatus";
import ScreenAwake from "./ScreenAwake";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { client, useClient, getConnectionDiagnostics } from "./client";
import GamePanel from "./GamePanel";
import TableSeats from "./TableSeats";
import AnimalAvatar from "./AnimalAvatar";
import { nicknameValidation } from "./nickname";
import { PreviousMatchSummary } from "./ResultPanel";
export default function App() {
  const state = useClient();
  const [name, setName] = useState("");
  const [nameComposing, setNameComposing] = useState(false);
  const nickname = nicknameValidation(name);
  const [code, setCode] = useState("");
  const [page, setPage] = useState<"room" | "help">("room");
  const [copied, setCopied] = useState(false);
  const [diagnosticCopy, setDiagnosticCopy] = useState("");
  const diagnosticRef = useRef<HTMLTextAreaElement>(null);
  const diagnostics = getConnectionDiagnostics();
  const diagnosticText = JSON.stringify(
    { version: "0.7.1", disconnects: diagnostics },
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
      className={`shell forest-shell ${state.room && state.room.phase !== "WAITING" && page === "room" ? "match-shell" : ""}`}
    >
      <header>
        <a
          href="#"
          className="brand"
          onClick={(e) => {
            e.preventDefault();
            setPage("room");
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
              onClick={() => setPage("room")}
            >
              {state.room && state.room.phase !== "WAITING"
                ? "返回牌桌"
                : "等候大廳"}
            </button>
          )}
          <button
            className={page === "help" ? "nav-active" : ""}
            onClick={() => setPage("help")}
          >
            使用說明
          </button>
        </nav>
      </header>
      <main>
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
        {(page === "help" || !state.room || state.room.phase === "WAITING") && (
          <div className="intro">
            <div>
              <span className="eyebrow">v0.7.1</span>
              <h1>
                {page === "help"
                  ? "安心入座，一起等候。"
                  : state.room
                    ? "你的牌友，正在這裡。"
                    : "四個座位，一起相聚。"}
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
        {state.notice && (
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
            <h2>等候室小指南</h2>
            <ol>
              <li>輸入訪客名稱，建立房間或使用房間代碼加入。</li>
              <li>
                每個房間最多四位不同玩家。名稱可以相同，但身分不同。首次加入後有
                5 秒完成 WebSocket 連線，這是首次連線交接，不是斷線保留期。
              </li>
              <li>
                入座後按「我準備好了」。四人全部準備且在線後，由房主手動開局；對家組成南北／東西兩隊，不設夢家或加倍／再加倍。
              </li>
              <li>
                等候室沒有重連保留期。伺服器偵測到最後一個分頁斷線後，立即清除準備並釋出席位，讓其他玩家加入。無聲斷網須先經過伺服器原生心跳偵測；這是連線偵測，不是席位保留期。
              </li>
              <li>
                等候室手動離開會立即釋出席位。開局後斷線保留原手牌 10
                秒並暫停；逾時由後端判定失敗隊伍，主動退出本隊判負。已完成分數保留，未完成局不計分；兩隊均失敗時無勝負。
              </li>
            </ol>
            <h3>三局競賽與局間確認</h3>
            <p>
              每局看完自己的 13
              張牌後選特權。雙方確定後一起公開，再依順時針叫牌。四人開頭全 Pass
              會重發，不算完成一局。跟牌必須跟首引花色；每回合四張牌出齊後會短暫顯示回合勝者；每局
              13 回合後結算；前兩局最多等 90
              秒，四人確認可提前開始下一局，第三局保留整場結果。
            </p>
            <p>
              四人各出一張牌是一個回合，贏得這四張牌算拿下一墩；每局共有 13
              回合。
            </p>
            <h3>特權與叫牌</h3>
            <p>
              特權徽章旁會直接標示我方及對方「已使用／未使用」。點擊問號可查看提示。同隊任一人使用即成立，兩人都不用才確定不使用；兩隊完成決定後一起公開。特權會影響結算，請以結算中的「特權調整」查看本局加減分。
            </p>
            <p>
              叫牌必須高於前價，同階依 ♣ → ♦ → ♥ → ♠ → NT
              排序；不設加倍或再加倍。
            </p>
            <h3>操作倒數由後端判定</h3>
            <p>
              特權階段每人 20 秒，未選者逾時視為不使用；每次輪到叫牌有 20
              秒，逾時自動 Pass；每次出牌也有 20
              秒，逾時出最低合法牌。重新整理不重置。有人斷線時凍結剩餘操作時間，所有人恢復連線後接續；原本的
              10 秒重連期限不變。
            </p>
            <h3>蓋牌紀錄與手機手牌</h3>
            <p>
              每回合四張牌展示約 2
              秒後，所有人的已完成回合紀錄一律蓋牌；進入本局結算後才能回顧該局牌面。各局叫牌與全員
              Pass 重發前的叫牌都會保留。
            </p>
            <p>
              手機可一次展開 13
              張重疊手牌：點選或在手牌上左右滑動選牌，再按「出這張牌」。選牌本身不會出牌；可切換「展開手牌」查看完整牌面。電腦仍可直接點擊合法牌出牌。
            </p>
            <h3>同一瀏覽器 = 同一位訪客</h3>
            <p>
              訪客身分儲存在此瀏覽器，重新整理不會換人。同來源的多個分頁共用身分與席位；只要一個分頁仍連線，就會顯示在線。在等候室，最後一個分頁斷線或重新整理可能釋出席位，返回須重新加入；開局後則保留原遊戲
              10 秒供重連，不重新發牌。每位玩家請使用自己的裝置或瀏覽器設定檔。
            </p>
            <p>
              訪客與房間資料會在伺服器重新啟動後清除。請勿輸入密碼或私人資料。
            </p>
            <button className="primary" onClick={() => setPage("room")}>
              {state.room && state.room.phase !== "WAITING"
                ? "返回牌桌"
                : "回到等候室"}
            </button>
          </section>
        ) : !state.initialized ? (
          <section className="panel">
            <h2>正在恢復訪客身分…</h2>
          </section>
        ) : !state.session ? (
          <div className="welcome-grid">
            <section className="panel welcome">
              <span className="eyebrow">WELCOME TO THE TABLE</span>
              <h2>先認識一下你。</h2>
              <p>用一個喜歡的名字，開始這次相聚。</p>
              <form
                onSubmit={(event) =>
                  submit(event, () => {
                    if (!nameComposing && !nickname.error)
                      void client.login(name);
                  })
                }
              >
                <label htmlFor="guest-name">訪客名稱</label>
                <input
                  id="guest-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  onCompositionStart={() => setNameComposing(true)}
                  onCompositionEnd={(e) => {
                    setName(e.currentTarget.value);
                    setNameComposing(false);
                  }}
                  aria-describedby="guest-name-hint"
                  aria-invalid={!nameComposing && !!name && !!nickname.error}
                  required
                  autoComplete="nickname"
                  placeholder="例如：小白"
                />
                <small id="guest-name-hint" aria-live="polite">
                  {nameComposing
                    ? "正在輸入…"
                    : `${nickname.count}／5 字${name && nickname.error ? ` · ${nickname.error}` : ""}`}
                </small>
                <button
                  className="primary"
                  disabled={state.busy || nameComposing || !!nickname.error}
                >
                  {state.busy ? "連線中…" : "以訪客身分進入 →"}
                </button>
              </form>
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
            onHelp={() => setPage("help")}
          />
        ) : state.room ? (
          <div className="room-layout">
            <section className="panel table-panel">
              <div className="room-heading">
                <div>
                  <span className="eyebrow">PRIVATE WAITING ROOM</span>
                  <h2>
                    房間{" "}
                    <span data-testid="room-code">{state.room.roomId}</span>
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
                  <p>全部準備後，由房主手動開局。</p>
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
                <PreviousMatchSummary match={state.room.lastMatch} />
              )}
              <span className="eyebrow">MAKE YOURSELF AT HOME</span>
              <h2>你好，{state.session.displayName}</h2>
              <p>
                把房間代碼分享給朋友。
                <br />
                人到齊，再一起準備。
              </p>
              <div className="ready-count">
                <strong>
                  {state.room.players.filter((p) => p.ready).length}
                  <span> / 4</span>
                </strong>
                <p>位玩家已準備</p>
              </div>
              {state.room.allReady && (
                <p className="all-ready" role="status">
                  四人在線且已準備完成 ✓<br />
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
              {state.room.hostPlayerId === state.session.playerId && (
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
            <section className="forest-lobby-hero" aria-label="暮色森林大廳">
              <div className="forest-lobby-sign">
                <span>THE LANTERN GROVE</span>
                <h2>燈亮了，朋友也到齊了。</h2>
                <p>選一張桌，開始今晚的三局牌。</p>
              </div>
              <div className="lobby-friends" aria-hidden="true">
                {[0, 1, 2, 3].map((seat) => (
                  <AnimalAvatar key={seat} seat={seat} large />
                ))}
              </div>
            </section>
            <section className="panel">
              <span className="eyebrow">LET’S GET TOGETHER</span>
              <h2>你好，{state.session.displayName}</h2>
              <p>開一個新房間，或加入朋友的邀請。</p>
              <button
                className="primary create-room"
                disabled={state.busy}
                onClick={() => void client.create()}
              >
                ＋ 建立四人房間
              </button>
              <div className="divider">或使用房間代碼</div>
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
              <button
                className="text-button"
                onClick={client.forget}
                disabled={state.busy}
              >
                換一個訪客身分
              </button>
            </section>
            <section className="panel">
              <div className="list-heading">
                <div>
                  <span className="eyebrow">AVAILABLE TABLES</span>
                  <h2>目前的房間</h2>
                </div>
                <button
                  className="secondary"
                  disabled={state.busy}
                  onClick={() => void client.refresh()}
                >
                  重新整理
                </button>
              </div>
              {state.rooms.length ? (
                <ul className="room-list">
                  {state.rooms.map((room) => (
                    <li key={room.roomId}>
                      <div>
                        <strong>{room.roomId}</strong>
                        <small>
                          {room.playerCount} / 4 位玩家 ·{" "}
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
                  <p>還沒有房間，開一桌邀請朋友吧。</p>
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
