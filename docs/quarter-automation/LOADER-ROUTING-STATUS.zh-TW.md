# Render loader、維護路由 readiness 與 C 隔離流程

本輪未建 C、未分享檔案、未讀取真實 API token、未部署、未改正式健康設定或資料。

## 已查證平台行為

2026-11-12 直接讀取 Render 官方 `https://render.com/docs/deploys.md` 與 `https://render.com/docs/health-checks.md`：新 instance 通過 health check 才切入流量；之後才對舊 instance 發 SIGTERM。因而同時要求「新部署已 live、舊部署已停止」才讓新 HTTP ready，會形成循環。

本地解法將安全維護路由與資料寫入 readiness 分開。`/health/route` 只表示應用已啟動並能正常服務遊戲或安全維護頁，不代表資料驗收；`/health/ready` 保持原遊戲／寫入 readiness。維護期間業務 HTTP、登入、房間與 socket admission 仍受實際 gate 封鎖。正式 Render 目前仍使用原健康路徑，本輪沒有改為新路徑。

明確 trusted startup factory 可提供 inert staged backend，讓 HTTP 維護模式先完整啟動，identity／統計寫入拒絕。外部來源證據完整後，只能一次 attach 已驗證 backend 與 lifecycle，coordinator 再核對才開放遊戲。預設缺少 factory 仍不能取得 native writer，環境旗標不會自行產生權限；activation UNKNOWN 不能重送。

## 已完成的 authenticated loader

`RenderEvidenceSource` 是實際固定 HTTPS GET adapter，讀取指定 workspace 的服務及固定 service 部署。憑證透過明確來源注入，不掃環境；有固定 host/path、禁 redirect／proxy、回應大小限制、期限、30 秒快取、token 緩衝清除與防除錯洩露檢查。缺必要程序證據時，在讀 token 前回 NotReady，允許尚未建立 authority 的 bootstrap 唯讀等待；衝突不以舊快取覆蓋。完整限制見 `docs/RENDER_EVIDENCE_LOADER.md`，DNS 不宣稱可硬中止。

## 尚未完成且不能偽造的部分

**程序 handoff 證據 loader 尚未完成。** Render API 不能提供某個 native owner 的 queue 是否排空，現有 root／receipt 也沒有 deployment、process generation、審查版本與 accepted-work drained 的完整綁定。需要最小、版本化且與 root／receipt／journal 同原子批次的 process/handoff 證據，才可實作持久程序 loader；不能另外寫一個「完成」旗標或用 instance=1 代替。

因此本輪完成 HTTP bootstrap／readiness 解循環與 Render inventory loader，但不是正式自動接管的全部外部證據已到位。記憶體中從未持久化的 accepted work 也不能靠事後新增欄位恢復。

## C 不需等待所有正式歷史缺口

全新 C 的固定 ID 不會被舊正式 deployment 指向，可依 C 本身的來源隔離與具體批准驗證新 sink，不必捏造正式表 process handoff。這不會替代正式資料的來源排除或歷史資格驗收。

最後已存本地用量為 60 / 500 pipeline minutes，不是先前的 50 / 500；執行前必須重新唯讀核對。零新增費用方案為原 Free 單服務一次候選 build，加一次既有 artifact rollback；不新增 DB／worker／cron、不升級、不購買額度、不自動重 build，先確認月支出上限及可用 rollback。

仍需使用者批准新 C 與既有服務帳號分享、暫停原遊戲／部署候選／恢復設定，以及公開程式中的新表 ID。具體不可還原假資料刪除遵循新的精確 preview 批准，不能沿用 B 批准。B 原證據保留。細節見 `docs/NATIVE_SINK_ISOLATED_VALIDATION_SCOPE.md`。

## 本輪驗證

2026-11-12 18:29:04（UTC+08）全量 `mvn clean test`：1306 項、0 failures/errors/skipped，52.588 秒。包括 Render mock authenticated loader，以及真 Spring staged 啟動、route200與所有業務路徑503並存、一次attach開放、UNKNOWN維護但不可重送等整合測試。日誌 `loader-routing-final.log`。
