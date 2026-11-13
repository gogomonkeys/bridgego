# Google maintenance sink 與單服務切換盤點

本輪僅本地程式與測試，以及 Render 唯讀服務／部署／錯誤紀錄盤點；未部署、未讀憑證、未讀寫正式試算表、未啟用功能或新增費用。

## 接線成果

- 新 `JournalQuarterGoogleFactory` 以獨立 `JournalQuarterAuthority` 建立維護通道，使用既有 bounded `JournalGoogleOAuthExchange`。抽取的 `GoogleDispatchAuthority` 僅替換授權介面，既有固定 Google host、TLS、HTTP、token、期限與不重試實作不變。
- 權限、固定目標與啟用條件先於憑證載入驗證；raw batch 不開放。只接受本次季度 grant 所產生的精確 plan/固定 wire，HTTP 第一次送出後不可重送。HTTP ACK 不代表完成，完整 journal 回讀後才推進。
- 普通身份／戰績 writer 權限沒有增加季度清理能力。預設未建立任何 production factory 或授權。
- Spring 測試確認 maintenance factory 收到同一 selected backend；legacy 模式不會呼叫 native maintenance factory，native 模式舊 gateway 拒絕寫入。

## 實際外部盤點

Render 於本輪仍顯示一個 BridgeGo web service、Free、1 instance、自動部署關閉、preview 關閉、maintenance 關閉。Live commit 仍為 `c92c69e934b66a8ea0e0b7fd60e4879d44012336`，08:36:46Z rollback 完成；B 隔離測試 deployment 已 deactivated。

08:36:46Z 到 10:11:18Z 的 app logs 搜尋 UNKNOWN、UNCERTAIN、FAILED、Exception、private-sheets-writer 無匹配結果。這不等於證明所有外部手動工具停止或所有請求有終態，但也沒有此查詢支持「正式未知寫入永久未結」的假設。

細節見 `docs/SINGLE_SERVICE_CUTOVER_OWNERSHIP.md`。沿用單服務／同表可行；一次性 cutover 要核對舊部署退出、已知寫入收斂與已知工具停止。正常持續運行時，每季不必重做人工來源切換。實際不可還原資料刪除的逐次批准仍由上層流程提供，程式沒有另加每季登入／手動切換畫面作無條件前提。

## 重啟及驗證界線

新增真正 child JVM，在 OPEN、排空 CLOSED、季度完成 CLOSED、重新 OPEN、UNKNOWN 送出前及晚到套用後的完整 native snapshots 上啟動 fresh lifecycle/coordinator。缺乏 ownership 接管證據時必須保持關閉、零寫入。原 native writer 延遲 wire 在後續版本由 receipt/anchor 防重放拒絕；這不代表不遵循 native 協議的舊 legacy 直接 Google writer 也會被 metadata 自動攔截。

新的 Google sink 測試使用本地模擬 HTTP dispatch 與 provider，未進行實際 Google 網路驗證。完整跨 JVM 自動接管成功仍未實作；外部證據 provider 仍未安裝。不能把本輪結果稱為正式清理已啟用或實際資料刪除驗收。

## 測試結果

2026-11-12 18:13:28（UTC+08）`mvn clean test`：1280 項全數通過，0 failures/errors/skipped，47.073 秒。日誌 `maintenance-sink-final.log`。
