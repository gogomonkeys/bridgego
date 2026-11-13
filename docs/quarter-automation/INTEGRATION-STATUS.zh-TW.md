# 自動季度清理接線盤點（2026-11-12，本地候選，未發布）

基準：正式遊戲 c92c69e934b66a8ea0e0b7fd60e4879d44012336。線上仍為原遊戲，此工作不修改正式資料、環境變數或部署設定。

## 已驗證及不可混淆的範圍

隔離 B 假表實際五步維護已通過：CLOSED、dispatched=5、stage=COMPLETE；過期資料刪除、當季保留、帳號及生涯保存均已回讀。測試後原遊戲與 /health/ready 已還原。這不是正式自動季度清理已啟用。

B 候選 MaintenanceRelease 硬綁假表，明確排除正式表。不得直接換 ID，或拿測試用 arm 當正式執行授權。

## 正式系統差距

| 範圍 | 正式現況 | 還要完成 |
|---|---|---|
| 戰績寫入 | SheetsStatsStore 的單線程 queue，讀控制表後 appendAtomic；失敗可重試 | 與身份/維護共享持久協議；未明結果不得重送；排空及跨季度處理 |
| 身份及個人資料 | SheetsIdentityStore 自有 synchronized，appendAtomic/saveAccountProfile | 同一維護閘門、完整讀寫作業 admission，不能只鎖最後一次 HTTP |
| 資料綁定 | DatasetBinding 每次檢查 dataset，但不是鎖 | 持久 root/receipt、舊 writer 阻擋、重啟核對 |
| 季度計算 | 已有台北時區季度邊界與過期拒收 | 啟動/喚醒後補檢與排程；免費服務休眠時不能承諾準點執行 |
| 清理 | QuarterlyRetention 為 offline-only、沒有 scheduler | 接隔離驗證過的 plan/readback協議，不能直接自動呼叫舊 execute |
| 維護頁 | 尚未接全局 admission | 停新開房/登入寫入、在途場次明確處理、HTTP與socket一致；一小時是預估不是強制開站時間 |
| 復原 | 候選 UNKNOWN會停住 | 以持久回讀辨識已完成/未完成/未知；不能只看單一 completed 欄位盲目重跑 |
| 開站 | 原遊戲 health/ready 與清理不共用協議 | COMPLETE且資料/生涯/控制記錄核對成功才恢復，不按逾時強制開站 |

## 本輪本地可用成果

1. 正式共用 gateway 的可選、預設關閉寫入閘門骨架。啟用後從 BLOCKED 開始，不會自行批准資料寫入；UNKNOWN不能自行放行。此為同一 JVM 的 gateway mutation 邊界，**不是分散式鎖、不是整個業務讀寫交易，也不是完整排程**。
2. 無網路、無寫入的歷史相容性盤點器，輸入已驗證 envelope，輸出不含姓名/email的計數。不能用結果修改或推定舊資料內容。

## 歷史資料需要先決定的事項

正式 LifetimeStatistics 只折入相容 lifetime_rule/score_version 的紀錄；舊 QuarterlyRetention 卻可能刪除不相容的過期原始資料。隔離候選 QuarterPlan 已有拒絕保護，正式接線必須保留。

目前**沒有重新讀正式表**，因此不能宣稱存在幾筆受影響紀錄。下一步先唯讀盤點：
- 沒有 lifetime_rule 的過期場次；
- 計分版本不同的過期場次；
- 相同 match 多revision/不一致內容、已折入區間重疊等 integrity conflict。

若數量為零，不需另問歷史政策。若有，需使用者選擇：可證明語義相同者經明確核准轉換並對帳；無法可靠還原者另訂不納入生涯與清理方式。不得猜分數、補造身份、靜默漏算；不相容資料未處理前保持清理停止，亦不能把無限保留當已符合原季度保留承諾。

## 下一個可驗收版本的必要測試

- 真正 game/profile writer 與維護同時到達時，未取得 admission 者零寫入；已進入者排空後才建 plan。
- 跨季度/重啟/休眠喚醒只對應正確季度，未驗證狀態啟動維護而非先放玩家進入。
- 每個 mutation 前後斷線、Google成功但回應丟失、舊進程遲到與重複回呼；無重複折入、無亂序覆蓋。
- 正式與休閒、訪客與已登入、正常/判負/無關勝負、零完成局、當季與過期、生涯已有carry和舊版本，核對積分/勝負/場次。
- 維護頁、socket/HTTP、健康檢查、成功開站及未知維持關閉。
- 完成一次正式部署環境的隔離回歸，再提出正式啟用與資料處理的具體範圍。

## 本輪測試結果

2026-11-12 08:43:15Z：正式 backend 預設 profile 全套 Maven tests **1134 passed，0 failures/errors/skipped**，包含本輪新閘門6項與歷史盤點4項。這不包含被 pom 排除的獨立 synthetic/live operator profiles，不代表線上季度自動化已驗收。日誌 `docs/quarter-automation/local-test.log`。

## 第二輪：業務層與維護生命周期接線（仍未部署）

- 已重用隔離候選 QuarterMaintenanceGate；加公平序列鎖，身份/profile及戰績整個read/write/readback作業共用。戰績在submit入列前取得permit，排隊也算在排空；UNKNOWN不走既有重試。
- 啟動/喚醒後30秒週期檢查台北季度。先凍結domain與拒絕新請求，再等所有permit退出；預檢後只dispatch一次，獨立fresh readback核對季度/operation/ownership/receipt才開站。
- HTTP繁中維護頁與API 503/no-store、socket訊息admission、health/live及ready區分已接。既有同步HTTP/socket action也計入排空。現行無async servlet支援承諾。
- RoomService季度freeze停止timer與斷線判負；正式/休閒不會因維護被憑空判負。若仍有active game，維持維護、不得清理或直接重開。**尚需決定在維護前停止開房並排空活動遊戲的政策**，不能將freeze本身當作已完成遊戲排空。
- 歷史lifetime資格此前正式唯讀檢查已有一項未能驗證，仍是阻擋項；本輪未讀正式資料，不推定相容、不轉換、不刪除。

### 實質未完成：持久 Google adapter

`DurableControl` 目前是明確的 interface＋狀態機測試，正式Bean刻意接 `unavailable()`，啟用季度gate會保持維護。**不是可上線的自動清理實作**。還需將候選native schema/root/receipt/防重放協議接入真實遊戲writer與Google transport：舊gateway不會因為包上本地gate就擁有持久收據，也不能防止另一個舊程序或人工寫入。重啟後UNKNOWN的自動恢復尚未實作。

不得為了讓測試通過而換成舊QuarterlyRetention.execute、只檢查完成欄位，或將假表固定binding改為正式表。需分離可重用candidate核心、增加正式binding與完整transport驗證後再做隔離線上回歸。

第二輪完整backend預設profile測試：**1159 passed，0 failures/errors/skipped**（2026-11-12 08:49:37Z）。包括formal/casual freeze、queued/serialized writers、unknown/no-retry、startup/wake/quarter transition、HTTP/socket/readiness。日誌 operation-integration-test.log。獨立工具profile與正式Google adapter未由此覆蓋。
