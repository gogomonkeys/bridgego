# 持久提交證據 journal（本地候選，未發布）

本頁更新 NATIVE-ADAPTER-STATUS 的 journal 缺口：已實作表內持久 proof 與普通 writer 本地整合；正式Google寫入/來源切換/季度新授權仍未啟用。

## 簡單儲存方式

只使用一筆固定 Google developerMetadata（ID20001, key bg.commit.v1），保存最新提交證據，避免每局增加 journal 歷史或另付資料庫費用。包含目標綁定、序號、epoch、receipt slot、operation及整體資料digest。資料digest只排除這筆journal自身，其他root/receipt、八張表、sheet ID與layout全部包含。

`beforeDigest`與`predecessorProofHash`是建立本次plan時的綁定，不代表重啟後可重建全部歷史。這不是完整稽核日誌，也不是簽章或排他鎖。

## 原子性及防重放

- JournalPlan產生**包含資料/root/receipt/journal的最終單一batch**，不另發寫後補記錄。
- 原receipt ID唯一create作競爭防護，重複/晚到同一slot的batch整批失敗。
- 要求wire精確的guard→唯一receipt create→cells mutations→updateRoot框架，禁止先刪receipt再create的繞過。
- 送出旗標在進sink前消耗；unknown不重送。觀測BEFORE仍不放行，精確APPLIED才接受。
- 缺journal不可自動初始化。bootstrap須明確已核對baseline digest，且只在已隔離CLOSED來源建立。
- 固定proof大小與native request/read/metadata預算受檢查；epoch rotation目前拒絕，不能刪收據假裝釋放容量。

## 真正回讀接線

`NativeCommitJournal.verifier` 重新讀完整原生資料，核對表中stored proof及caller snapshot token，不再用記憶體Set。

`QuarterGoogleFactory.ReadOnly.controlWithJournal` 已接這個實作，沿用factory固定target且仍唯讀；OwnershipVerifier仍獨立預設不可用。正式表尚無journal，因此不會自動將既有表視為可信或修改它。

## 身份/profile/戰績

NativeWriterAdapter重用原Service普通writer。JournaledNativeTransport在同batch加入proof，完整回讀保留所有metadata：
- 同Google身份改暱稱保持同帳號；更新B:D保留E生涯。
- 戰績保留canonical payload/hash，chunks大小不同但既有decoder兼容。
- 排隊/單一writer接線仍在正式舊gateway骨架，**未切換Spring Bean至新native writer**。
- 需要先做來源隔離與明確bootstrap，再用新正式授權的Google sink發送最終combined wire。舊WritePermit精確wire不接受journal新wire，沒有放寬。

## 限制與下一步

1. JournaledNativeTransport只支援普通身份/profile/戰績、claim/close；季度與rotation目前拒絕。季度五步的exact expected metadata必須以新journal版本重建並驗證，不能偷偷隱藏proof或沿用舊permit。
2. 排他ownership與舊程序停用尚須正式來源切換證明；手動/legacy寫入會使proof失效。若有人協同修改資料與proof，無密鑰簽章的journal不防此攻擊。
3. 全表proof讀取沿用原operation scope、預算不延長。實際Google效能與quota需在新版隔離回歸確認。
4. 尚未部署或讀寫正式表；無新增費用。歷史lifetime資格未驗證仍阻擋清理。

## 最終本地測試

2026-11-12 09:08:10Z，統一 `mvn clean test`：**1194 tests passed，0 failures/errors/skipped**。包括重啟從stored proof核對、晚到/競爭/重複batch、unknown前後回讀、metadata容量/collision、同Google帳號暱稱更新與生涯保留、hash兼容、唯讀factory不依賴記憶體commit Set。日誌 `journal-final.log`。未執行Google API或正式寫入。
