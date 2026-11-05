# 維護專用本人移除流程（本機實作，尚未真實驗收）

此流程不是普通遊戲伺服器的管理按鈕。應先停止所有遊戲/身份寫者，再使用相同已批准 HTTPS origin 的獨立維護 runtime。Google OAuth Web client origin 不必因這個路徑改變，redirect URI 仍留空。建立/設定憑證與部署須各自批准；本文件不是任何操作已執行的證明。

## 管理者啟動前

1. 確認資料集為批准的 test Sheet、dataset UUID及 erasure ledger 正確。確認申請範圍、備份/版本歷史限制、資料保留政策。Google credentials 不經聊天、命令列、檔案或 log 傳給申請人。
2. 阻止新遊戲，核對未確認寫入，完全停止舊 game/identity writers及重疊部署；確認不能自動復活。記憶體佇列可能遺失，事先告知。不要把單一 instance或環境旗標當作程序已停止的證據。
3. 使用經批准的安全主機環境設定：
   - BRIDGE_RUNTIME_MODE=erasure
   - PUBLIC_ORIGIN、GOOGLE_CLIENT_ID 保持相同已批准正式來源/client
   - BRIDGE_TEST_SPREADSHEET_ID、BRIDGE_TEST_DATASET_ID、秘密 BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON
   - BRIDGE_ERASURE_WRITER_STOPPED_ACK=true：只有管理者實際查證排他停寫後才能設定；程式無法跨 Render 部署證明這件事。
   - BRIDGE_ERASURE_EXECUTION_ENABLED=false：先進 dry-run。真正執行的維護啟動須另行批准並設為 true；這仍不取代本人每個計畫的確認。
4. 專用 runtime 不建立 RoomService、IdentityStore、StatsStore、遊戲 scheduler；所有普通遊戲 API/資產路徑拒絕。`/health/ready` 回傳維護模式供平台檢查。
5. 給申請人精確入口 `PUBLIC_ORIGIN + /maintenance`，**不要給根目錄 `/`**。既有 PWA service worker可能快取舊根頁；/maintenance 不屬於既有資產白名單。實際 PWA/瀏覽器仍需驗證，不能只靠單元測試。

## 申請人流程

1. 讀取「移除本人帳號與本人直接參照、保留他人及共享分數、防回復hash仍留存、不涵蓋備份/Sheets版本歷史/無法證明的訪客歷史」告知。
2. 明確同意後才載入 Google GIS。伺服器發出一次性 nonce；Google 回傳 credential透過同來源 POST 到維護服務，只在記憶體驗證。session cookie具 Secure、HttpOnly、SameSite=Strict，POST必須精確 Origin。
3. 驗證過的 issuer/subject決定本人帳號，不接受任意 account_id。取得五分鐘計畫，查看受影響場數/席數、殘留範圍；dry-run不寫資料。
4. 執行開關已明確批准時，申請人對該計畫給予明確確認，再完成一次 fresh Google驗證。第二次必須同一人，同一session及未變更資料集；不同帳號、過期或重播拒絕。
5. 只有整體 after-digest readback一致才說「已驗證」。NOT_APPLIED/UNCERTAIN不冒充成功，不自動盲目重試；維持維護狀態，由管理者調查後重新預覽與確認。

## 完成與恢復

核對其他玩家/分數未變、本人對應移除、ledger拒絕舊資料復活；處理已知備份/匯出及供應商限制。只啟動全新 game程序，不能復活舊session或佇列。帳號移除會阻止同一Google subject再註冊，這點與一般解除連結不同；重新註冊政策未實作。

`BRIDGE_ERASURE_WORKFLOW_VERIFIED` 必須在真實但隔離的批准測試帳號/Sheet演練、停寫證據、Google/CSP/cookie/PWA/回復流程都核對後才能設true。本機fake測試與此文件不是該確認。PUBLIC_ORIGIN僅接受HTTPS；本機HTTP測試不代表真實Secure cookie/Google流程通過。

季度清除是另一種操作：刪除過期原始場次但保留帳號，**不能用本人帳號移除來實作季度清除**。
