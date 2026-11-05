# 封閉測試收集與帳號資料移除：本機候選

本文件取代舊第一階段文件中「所有訪客也保存」的設定。**未部署、未建立/分享/改寫 Google 資源、未讀取既有秘密，也未刪除任何真實資料。** 本機測試不等於真實 Google、隱私法或營運流程已驗收。

## 收集隔離的實際保證與界線

- 原本的訪客遊戲、短期記憶體 session/房間照常運作；「不收集」指不將未批准玩家的身分對應或整場結果寫入持久 Sheets，不是聲稱遊戲完全不處理訪客名稱/手牌。
- 只有 canonical issuer `https://accounts.google.com` + 精確 subject 同時列入部署白名單的測試者，才能建立持久帳號對應。不要用 email、姓名、萬用字元推測身分；名單不向前端公開。
- 每場開始時凍結資格；**任何起始真人是訪客、未連結或未批准，整場都不建立/排入保存封包**。批准測試者搭配 BOT 可以保存；開局後離場被 BOT 接替仍保留該場已凍結的測試者歸屬與 MIXED 標記。沒有公開收集模式。
- 「只驗證我的 Google 識別碼」需要獨立同意、專用一次性 nonce。Google credential 只在記憶體驗證，回傳 caller 自己的 issuer/subject，不建立帳號、不連結、不保存戰績。畫面上的識別碼也不自動送給管理者；玩家自行選擇複製交付，管理者另行批准名單。
- 真正連結與只驗證的 nonce 不可互用。未批准的合法 Google token 也不進入 IdentityStore。
- 名單、聯絡方式、保留說明、已驗收刪除流程缺一，收集保持關閉。字串設定只記錄管理者的政策，不會自動履行保留期限或證明法遵。

## 專用測試資料集，不能沿用正式資料表

原本 `BRIDGE_SHEETS_SPREADSHEET_ID` 不再作為這個候選版的寫入目標。必須明確設定 `BRIDGE_TEST_SPREADSHEET_ID`；如果舊 production ID 仍存在，兩者必須不同。這是避免把測試寫進正式資料，不是建立額外付費服務。新建或分享試算表仍須批准；既有的共享本身不等於允許改用途或新增權限。

另有 `BRIDGE_TEST_DATASET_ID`，為管理者為這份測試資料集選定的隨機 UUID，非秘密。每次讀/寫/移除前都核對以下 dataset 分頁的完整兩列，不符即拒絕。marker 不是分散式鎖，管理者不得將正式資料搬入標示測試的分頁。

- dataset 首列：purpose, dataset_id
- 第二列：bridgego-restricted-test, 與環境設定完全相同的 UUID

加上既有四個分頁及一個防回復記錄分頁：

- envelopes：commit_id, payload_hash, chunk_index, chunk_count, canonical_json_chunk
- commits：commit_id, payload_hash, chunk_count, match_id, revision
- accounts：account_id, schema_version
- auth_identities：issuer, subject, account_id, schema_version
- erasure_ledger 首列：kind, identifier_hash, schema_version
- erasure_ledger 第二列固定：META, erasure-ledger-v1, 1

季度維護另需 retention_ledger 與 quarterly_aggregates 兩分頁；精確表頭及不可偽造 COMPLETE watermark 的規則見 [季度說明](QUARTERLY_RETENTION.md)。

缺少 ledger 不會當成「沒有刪除過」而繼續寫入；會拒絕，避免舊流程漏讀刪除記錄。所有分頁只供伺服器/授權管理者，不公開連結。

## 收集設定（尚未套用）

- `BRIDGE_COLLECTION_MODE=restricted`，其他模式拒絕啟動。
- `BRIDGE_TEST_GOOGLE_IDENTITIES`：JSON array，每筆只有 issuer 與 subject；最多 100 位。此為私人名單，不要貼到公眾程式碼或截圖。
- `BRIDGE_PRIVACY_CONTACT`：已指定公開受理信箱 jody.yycc@gmail.com；本次只記錄需求，未修改主機設定。
- `BRIDGE_PRIVACY_RETENTION`：擁有者確實會執行的保留期限/刪除安排，原文向玩家顯示；目前沒有自動清理排程。
- `BRIDGE_ERASURE_WORKFLOW_VERIFIED=false` 預設。只有擁有者在隔離資料集完成真實權限、本人驗證、停寫、移除、備份處理與結果核對後，才可明確設為 true；單元測試不構成此項確認。
- `BRIDGE_SHEETS_ENABLED=false` 預設；啟用前同時需要 BRIDGE_TEST_SPREADSHEET_ID、BRIDGE_TEST_DATASET_ID 及秘密 BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON。
- `GOOGLE_CLIENT_ID`、`PUBLIC_ORIGIN`、`SPRING_PROFILES_ACTIVE=prod` 沿用精確既有設定。OAuth Web client 使用 JavaScript popup callback，redirect URI 留空。
- `BRIDGE_SHEETS_QUEUE_CAPACITY=128` 預設。有界記憶體佇列不是耐久 outbox，故障/重啟可漏寫。

## 季度統計與保留期限分開

使用者指定個人統計每季歸零重新計算，帳號資料保留。這不是原始場次資料自動刪除授權，也不是每季刪除 Google 身分對應。已確認台灣時間（Asia/Taipei）每年1、4、7、10月1日切換，季末移除原始個人場次資料、帳號保留。跨季比賽歸屬及小樣本整體統計規則尚未批准；候選整體統計預設全部抑制，不擅自保留可能識別個人的小群體計數。免費主機休眠時不保證準點實體清除，讀取限制與實際完成時間分開報告。本版沒有統計畫面。

`BRIDGE_QUARTER_POLICY_VERIFIED=false` 預設，跨季歸屬/實體清理/整體統計政策與演練未完成前，收集 gate 不得啟用。

## 移除範圍及殘留資料必須向申請者明說

- 重新驗證 Google 本人身分後，只找該 verified issuer/subject 對應的帳號。不能接受任意 account_id，也不能用暱稱/email猜測。Google 所有權不能證明過去訪客 session 的所有權；不會順便合併或移除無法證明的訪客歷史。
- 移除該帳號及其 Google 身分對應。共享場次內只清空該人的 account_id/guest_id，為每場每席產生不同隨機 erased_marker；保留其他玩家、分數、場次/局數、座位及結果。隨機 marker 不提供跨場的固定對應，但時間/同場其他資訊仍可能被人推知，**不保證匿名化或法律上的完整刪除**。
- 所有受影響 canonical envelope/hash/commit marker 與帳號/身分/ledger 在同一批次原子替換，之後重新讀取驗證。更新僅涵蓋已讀取的 A:E 欄位，不能清除未讀取的其他欄。正式使用資料分頁不應額外保存私密欄位。
- erasure_ledger 保留舊帳號與 Google identity 的 SHA-256 防回復 hash。這是仍需保護的假名控制資料，不等於「所有個資都已消失」；用途僅為阻止舊封包重試/匯入恢復已移除識別。同一 Google subject 因 ledger 會被阻止再建立帳號；重新註冊須另行設計明確的再同意流程，不能直接刪 tombstone。
- 一般「解除 Google 連結」不執行以上移除、不撤銷訪客 bearer、不登出 Google。
- 備份、匯出、Google Sheets 版本歷史、Google 供應商保留政策及其他擁有者持有的副本，不會被 API 的目前儲存格改寫自動抹除。管理者須訂定可行的刪除/到期政策；恢復備份前必須保留並重新套用最新 ledger，不可直接還原移除前完整副本。不得承諾已清除無法控制的副本。

## 停寫與確認，不能省略

Sheets 沒有跨程序 CAS/唯一鎖。資料集 digest 能偵測讀取到的變更，不能取代排他存取。因此必須先阻止新對局/登入、核對已確認保存的場次，停止所有 game/identity writers，確認舊程序終止且不可自動重啟。未確認佇列可能遺失，須向受影響測試者說明。單 instance 或關閉 auto-deploy 不足以保證部署期間零重疊。

移除流程預設 dry-run：先以 fresh nonce-bound Google proof 產生自己的計畫，顯示受影響場數/席數與保留/殘留範圍；執行須再次取得新 proof、相同本人、未過期計畫、完全相同 dataset digest 及明確 plan-bound 確認。資料在預覽後變更就停止重做預覽。寫入 timeout 不盲目重試；重新讀取只能回報已驗證、尚未套用或不確定，不能把不確定說成成功。

完成後先驗證 canonical hash、其他玩家資料不變、舊 payload 被 ledger 拒絕、本人身份不能被舊 token/舊 session重新帶回。丟棄舊程序/佇列；只從新乾淨 game process 恢復，不能讓持有刪除前帳號連結的程序復活。

維護專用 browser runtime 與操作步驟見 [維護說明](ERASURE_MAINTENANCE.zh-TW.md)；未完成真實驗收，仍視為未準備好。不把可注入 fake verifier 的測試引擎當成已可營運的刪除服務。
