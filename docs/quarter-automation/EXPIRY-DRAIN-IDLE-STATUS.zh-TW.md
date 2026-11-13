# 正常到期、對局排空與閒置讀取修復

本輪收斂處理三項具體缺口，不部署、不修改正式資料。

## 正常到期與未知寫入分離

READY 且沒有 pending/in-flight 的正常逾時變成 EXPIRED_CLEAN，不再當作 UNKNOWN。原 Handle 的固定目標唯讀通道可重新核對完整 journal、owner/source 與仍有效的外部授權；一致後換新 capability，撤銷舊引用。未完成的 POST、VERIFYING 過期、時鐘倒退與真正 UNKNOWN 不能走此路徑。重新核對失敗關閉能力及 provider。

此項處理同一存活程序閒置／睡眠後正常過期；不是新 JVM 自動接管。

## 現有對局先完成

FINISHING 只禁止開新場、加入／開始新局，現有遊戲與必要連線繼續。開場時即登記 match ID，避免季度邊界早於 30 秒 scheduler 的競態。只有已登記場次取得受限結算 grant；身份／profile 普通寫入拒絕但不誤認未知寫入。

跨季結算採受限追加路徑，不新增暫存資料庫。既有場次的新季結果先保存、隨後清理保留；先完成但排隊跨界的舊季結果先保存、隨後清理折入生涯。正常 match/openRequired 不放寬，專用路徑仍驗完整 codec、舊 cutoff、時間、owner、erasure 與 attached permit。只有 CONFIRMED 才消耗註冊；明確不收集的完成場次另行釋放，取消／排隊滿不冒充成功。

對局完成後才凍結、等待已承認 writes 排空；等待不因維護超時自動判負。真正 UNKNOWN 仍立即安全封閉。

## Idle tick

同 JVM、同季度、已驗 OPEN 且 gate 未失效時，30 秒 tick 只看本地狀態。等待對局／佇列排空也不反覆全表讀取。啟動、季度變更、明確失效，以及執行清理前的 exclusive 後完整讀回仍保留。閒置快取不是宣稱遠端資料持續新鮮；下一次正常儲存操作仍核對 journal。

## 實際來源盤點（2026-11-12 09:56Z 左右，僅唯讀 Render）

指定工作區只列出一個 BridgeGo web service：Free、單實例、autoDeploy off、health=/health/ready、未啟用維護模式。live 仍是 c92c69e 的 rollback；先前 B 測試部署已 deactivated。

自 08:36:46 rollback 後，查詢 UNKNOWN／UNCERTAIN／FAILED／Exception／private-sheets-writer 的 app logs 沒有符合紀錄。這不是全域在途請求為零的證明，亦沒有證據可宣稱正式未知寫入永久存在。不預設必須換新表；實際切換時再檢查主服務佇列／在途、獨立 operator 與人工寫入。先前未知隔離測試表不能混同正式表。

本輪沒有讀寫正式 Google Sheet、沒有變更 Render 設定或觸發建置。完整正式 OPEN writer→cleanClose→季度 driver provider 仍未啟用，不以這次本地修復宣稱已部署自動排程。

## 驗證

2026-11-12 10:00:09Z，統一 `mvn clean test`：**1260 tests，0 failures/errors/skipped**，44.224 秒。包括正常過期睡眠後重驗、真正UNKNOWN不復活、正式／休閒實際正常完局、跨季scheduler前結算授權、取消／未收集註冊生命週期、舊季排隊寫入折算生涯、新季完成結果保留、同季idle及明確失效後只重新核對一次。日誌 `expiry-drain-idle-final.log`。

新增核心 settlement extension 已獨立標記，原 normal match/openRequired 未改；provenance 去掉明示區塊後仍逐字比對原核心 SHA。rename-only 類別為 28/31，另外三項分別為 inert release、legacy preflight 與受限 settlement extension。
