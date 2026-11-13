# 原生 adapter 實作狀態（本地候選，未發布）

## 本輪真正新增

- `com.bridge.quartercore` 抽取31個已線上隔離B驗證的核心類。30個只改namespace，SHA provenance test逐檔比對；唯一語義差異是MaintenanceRelease收緊為UNPROVISIONED，沒有可執行live release。
- `QuarterNativeControl` 實作 `DurableControl`，真正使用原生全讀解析器、schema/binding/layout檢查、receipt格式/階段檢查、原Service與WritePermit的五步協議，不是回傳固定布林的假adapter。
- `QuarterGoogleFactory` 提供有明確binding且預設關閉的Google唯讀來源；權限仍READ_ONLY，拒絕batch。既有exact-wire/nonce/期限permit寫入seam保留，但live release仍不能取得。
- 測試使用原生模型與原permit，不存取Google：成功後fresh readback、五步各自發送前/後未知（10種）、相同operation不再送、重啟後不採用未知狀態、carry/當季record/profile/中間receipt變動拒絕、錯target/native格式拒絕。

## 必須保留的拒絕

1. CLOSED根記錄及最後一張receipt的hash(owner)不是整張表或生涯數字的提交證據。
2. 須獨立 `OwnershipVerifier` 與 `CommittedSnapshotVerifier`。預設均拒絕；測試使用明確的模擬獨立journal，不把這當Google正式journal已實作。
3. 合法的後續戰績/profile變更也必須由單一writer更新可信commit proof，而不是永久釘住舊digest，也不是接受任意新digest。
4. 重啟後只有完整已提交snapshot證明及排他writer證明存在時才能辨識乾淨COMPLETE。UNKNOWN不重送、不以CLOSED標記自行解除。
5. 既有BootArm是一次性假表/test.arm協議，明確排除正式表。本輪不移除該禁制、不自行生產正式寫入授權。

## 尚未實作的正式啟用必要件

- 新的正式持久授權/operation commit-proof協議及其Google儲存實作（不能用測試Set、callback或環境變數代替）。
- 真正game/profile writer切換至同一root/receipt/commit-proof，阻擋舊進程與其他入口。
- 排他來源切換證明、重啟恢復能力，含late/unknown request。
- 正式歷史資料lifetime資格未驗證項的唯讀盤點與必要決策。

所以目前正式Spring Bean仍刻意為DurableControl.unavailable，沒有連到Google，也不可開旗標上線。已提供可重用的native adapter與安全邊界，但**不是正式持久寫入與自動清理已全部完成**。

## 本輪最終驗證

2026-11-12 08:59:27Z，統一 `mvn clean test` 成功，預設backend **1178 tests passed，0 failures/errors/skipped**。此前新adapter測試4項失敗已定位為fixture沒有前季COMPLETE，修正fixture並新增拒絕空ledger測試；沒有放寬正式驗證。最終日誌：native-adapter-final.log。未執行獨立operator profiles或任何Google寫入。
