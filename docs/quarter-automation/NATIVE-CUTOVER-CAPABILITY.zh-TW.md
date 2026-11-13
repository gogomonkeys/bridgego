# Native writer 切換與 Google capability 本地候選

本輪沒有部署、Google 連線、正式資料讀寫或費用變更。原線上版本及正式表保持原狀。

## 切換範圍

- `BRIDGE_PERSISTENCE_DRIVER` 預設 `legacy`；選擇 `native-journal-v1` 後，身份與戰績共用同一個已驗證後端。
- native 模式的 legacy gateway 是拒絕存取物件，不建立舊 Google client；選擇或 factory 失敗不回退 legacy。
- 實驗模式也在建構 client 前拒絕 native 混用。獨立 CLI、維護程式、其他部署不受本程序 Bean 控制，仍必須在切換時確實停止。
- native 後端保留有界非同步戰績佇列，permit 在排隊前取得。UNKNOWN 封閉後續寫入，不偷偷重試。
- 統計取一份完整 journal 驗證快照，再使用現有 decoder；保留本季／生涯及正式／休閒算法。Google 身份以 issuer/subject 為依據，改暱稱不新增帳號或覆寫 carry。

## 能力分工

普通身份及戰績寫入採已授權來源切換後的 session，再為每一批 exact journal wire 建立一次性發送權限；不是每場遊戲要求管理者 Google 登入。

季度刪除不因普通 writer session 而取得授權。session 不允許修改 retention、aggregate 或 erasure 資料，也不允許削減普通記錄。舊 BootArm 假表限制及原唯讀交換器保持不變。

新 bounded Google sink 延續固定 Google 主機、TLS、大小限制、deadline、無 redirect/proxy/retry 與秘密清理。前置核對在讀取憑證與建立連線前完成；ACK 後仍須完整 journal 回讀一致才能接受下一批。未知結果封閉 session。

## 尚未證明／未啟用

- 本候選預設沒有 source-cutover authority provider，也沒有實際來源遷移／正式 target journal 初始化。旗標與 baseline 字串只是設定，不能當核准或 ownership 證據。
- 尚無新 Google sink 的真實隔離線上驗證；本地測試不等於 Google 已接受新 wire。
- native writer 的正式 Bean provider 尚未 provision。禁止僅設定旗標就嘗試上線。
- 季度控制 Bean 仍預設 unavailable，普通 writer session 不負責刪除或 mint 季度授權。
- 原有 historical lifetime 未驗證資料、活躍遊戲維護前排空策略及跨程序 UNKNOWN 恢復仍需獨立處理。
- session 有效期／使用額度到達即停，沒有不經核對的自動續期。

## 驗證結果

2026-11-12 09:35:41Z，統一 `mvn clean test`：**1236 tests，0 failures/errors/skipped**，41.950 秒。日誌 `native-capability-final.log`。

本輪新增 21 項：capability 8、來源切點 3、身份／正式休閒與聚合 4、backend 組裝 1、完整 Handle/backend 組合 2、真 Spring Context 3。完整組合測試要求每次 native 操作有 scope；驗證 profile 及兩種場次都經 final-plan authority，UNKNOWN 套用後不重送。Spring 驗證身份／統計唯一 Bean 與來源共用。

過程修正：舊測試同步假設改為限時等待 async CONFIRMED；Spring fallback Factory 在一般 Configuration 的載入順序會產生兩個候選，已移除 fallback Bean，改由 ObjectProvider 在無 provider 時 fail closed；真 Spring 雙序測試已通過。未調整 OS／VM 安全設定；測試 fixture 改用真 RoomService 與必要 CollectionPolicy，避免 Mockito 自我附掛依賴。
