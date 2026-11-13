# 切換與正常續期候選

## 本輪實作

1. 完整 legacy JSON 的唯讀 preflight 解析橋接，沿用原 bounded Streaming parser，僅接受已知八表、完整 coverage、固定 target/dataset 與空控制 metadata。原正式 decodeFull 未放寬；provenance 明列新增區塊並驗證移除區塊後原始 hash 不變。
2. 符合已知 schema 時，純 metadata-only legacy→native plan：單批新增 root、receipt、journal，所有原 cells、sheet IDs、dimensions 及帳號／戰績保持原樣。不擅自補 lifetime_rule 或清理歷史資料。
3. 外部 freeze/source evidence、固定完整 baseline、到期與撤回在 plan 建立及唯一 dispatch 前核對。未知後只判讀精確後態／前態仍不確定／其他不確定，不重送、不自動授 writer。
4. 同一存活程序的正常 session 續期：READY、無 pending／in-flight、期限尚有效、fresh journal 等於最後確認內容、外部 grant 仍有效才更換能力。原 target/source/owner/approval baseline 不變，舊能力撤銷，沒有額外 Sheet 寫入。Backend 在有界操作邊界處理，保留佇列 permit 計數。

## 不能由本程式單獨證明

- 舊 writer 不遵守 root/receipt，表內 metadata 不能阻止它晚到的 cells 更新。即使切換計畫全部保留原資料，也不能據此授予後續破壞性季度清理權。
- 本輪沒有重新讀取正式表；正式 metadata 是否存在、內容是否完全符合新版以及先前 reported lifetime 資格問題，仍不是已驗收。
- 外部 freeze 是明確操作證據邊界，不是分散式 lease；停用所有舊部署、CLI、匯入與人工寫入、在途請求清點仍必須成立。
- 正常續期不等於重啟恢復。Render 休眠跨越 session 期限、程序重啟或 UNKNOWN 仍須新鮮來源／ownership 核對，不能自動換 session 洗白。

## 最小選項與代價

同表切換最省搬遷，但若無法排除舊在途寫入，只能偵測問題後停住，不能稱已完整 fencing。建議評估保留舊正式表、將完整已核對快照移到新正式表再切固定 target：舊 ID 請求不會落新表，無需新資料庫。這仍需要一次維護窗口、來源尾端核對、新表建立與分享授權，以及新 target 完整讀回；若舊身分也可寫新表，不能宣稱 credential 隔離。尚未建立任何新表或遷移資料。

維持預設關閉；沒有部署、Google 讀寫或費用變更。具體對照見 `docs/NATIVE_CUTOVER_REVIEW.zh-TW.md`。

## 本地驗證

2026-11-12 09:43:12Z 統一 `mvn clean test`：**1250 tests，0 failures/errors/skipped**，42.798 秒。新增切換 assessment 6、完整 legacy parser 3、session renewal 5；既有真 JVM 重啟、季度五步與全部正式／休閒／身份統計回歸仍通過。日誌 `cutover-renewal-final.log`。此結果不是 Google 線上或正式表驗收。
