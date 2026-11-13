# 正常遊戲到季度清理的 lifecycle 接線

本輪只做本地程式與測試，沒有部署、讀寫正式試算表、建立憑證或新增費用。

## 已接通

- 同一 selected native backend 負責身份、個人資料、戰績與季度 lifecycle，不另開第二個資料來源。
- 當季 OPEN 只核對既有 writer 的完整 journal 與已知 root；季度到期先禁新場，讓註冊中的正式／休閒對局正常完成、保存。
- 對局與已接收的寫入排空後，取得維護 exclusive，核對 cleanClose 回讀，撤銷舊 writer session。
- 使用獨立、精確目標與基準、操作 ID、季度及最長五分鐘的季度授權，依原 receipt/journal 五階段執行。普通 writer 不取得清理權限。
- 五階段與完整回讀均確認後，關閉 maintenance session；以新 owner 的普通 session 核對同一 CLOSED 基準並 CLAIM。所有內容保持一致後才重新開放遊戲。
- 新 owner 避免重複 CLAIM receipt；不放寬原防重放規則。成功後可再處理下個季度。
- 任一步結果未知，不重送、不恢復開放。未知結果即使稍後資料看似完成，也不能被此次 provider 自動洗成成功。

## 驗證範圍

新增真 NativeStoresBackend 非同步保存與正式／休閒 FinalMatchEnvelope 整合測試，覆蓋跨季完成、保存、五階段、重新開放及下一季度。既有實際 52 張出牌排空測試繼續保留。

另測獨立授權五階段的逐階段未送達／已套用但回覆未知、期限、時間倒退、錯誤操作與 owner、防重放，以及 cleanClose／重新 CLAIM 失敗。既有子 JVM journal 重啟測試仍執行，但不表示完整新 provider 的多 JVM 自動接管已通過。

## 仍未開啟

- `NativeQuarterLifecycleFactory` 沒有預設實作；未安裝時 DurableControl 仍 unavailable。legacy 為預設，正式旗標沒有改動。
- 正式 Google maintenance sink 仍需接入獨立季度授權；不可直接使用普通 JournalGoogleHandle 繞過 retention 限制。
- 外部來源切換／唯一 writer 證據、實際刪除批准與正式歷史資料資格仍需核對，不由設定值或完成標記代替。
- 新 JVM 遇到未知 OPEN、未完成季度或晚到請求仍停止，不自動接管。完整正式網路 lifecycle 尚未線上驗證。

## 本輪測試結果

2026-11-12 18:10:03（UTC+08）完成 `mvn clean test`：1272 項，0 failures、0 errors、0 skipped；43.348 秒。日誌 `lifecycle-final.log`。包含連續兩個季度和未知結果晚到回讀的最後修正。
