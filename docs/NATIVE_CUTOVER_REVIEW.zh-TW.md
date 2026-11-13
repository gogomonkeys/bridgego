# Legacy → Native 切換：最小操作邊界（本地審查）

## 已知與未知

- 本地紀錄確認 2026-11-12 新隔離 B 表五階段驗證及回復 v0.13.2 遊戲；該驗證未修改正式表。
- 本審查沒有新增 Google／Render 讀寫，不能據此宣稱目前正式表符合 native schema 或已切換。
- 父任務報告：之前正式唯讀檢查仍有 1 項 lifetime 資格未驗證。本輪不重讀個資；切換 assessment 必須明列這項尚未解除，不能當作已通過。
- Legacy 程式不建立 native root 是程式行為推論；是否正式表目前缺 root／journal，尚不能由本輪本地審查確認。
- Legacy `GoogleSheetsGateway` 直接 append/update cells，沒有 native root／receipt／anchor CAS。Native journal 可以發現資料遭外部改動，但不能阻止持有編輯權限的 legacy／人工寫入。
- 主遊戲使用 `room`、`persistence` 掃描；native 選擇會封閉主 Bean 的 legacy gateway。Experiment 同程序路徑另有早期拒絕。
- 獨立 ErasureMaintenanceApplication、ExperimentBootstrapApplication、QuarterRetentionOperator、先前部署／本機 operator／人工編輯不受這個 Bean 選擇限制。必須另行停止或隔離其權限。

## 不需要新增的東西

不必為切換額外建立資料庫、分散式鎖服務、永久維護程序或保活服務。程式可以驗證目標、schema、完整內容、journal、精確批次及重放；來源停用與授權則需一份明確的操作紀錄，不能從 Sheet 的 CLOSED 字樣推導。

## 建議最小方案

1. 一次維護窗口禁止新遊戲／登入寫入，等待已承認的背景佇列與活動操作結束，再停止舊部署及獨立操作程式。不能只顯示維護頁。
2. 盤點是否仍有未確認的 legacy 請求。若有，先查證；固定等待若干秒或連續兩次相同 digest，均不是「未來不會晚到」的證據。
3. 最小先做現有表純 plan：保留全部 cells，以完整唯讀資料檢查表頭、8 張表、帳號／身分、戰績／生涯與時間邊界，列出必須補的 native metadata，不自動補資料或刪除不合資格紀錄。若來源完整停用證據成立，就不必另換表；若仍有無法收回的舊 credential／editor，才改新 native 表作隔離邊界。
4. 沿用既有服務帳號或原表時，確認所有舊 credential 持有者／人工 editor 已停止。若做不到而改新 native 表，才只給新執行身分必要編輯權限，不給舊服務帳號。僅停 Render 或刪除一把 key 不應被宣稱已撤銷所有既有 bearer token 或在途請求。
5. 精確目標／baseline／版本／授權核對後，明確執行一次 native schema／journal bootstrap。資料、控制 metadata、讀回證明與內容筆數皆須一致；不以 env flag 推定遷移同意。
6. 切換主服務來源，讓身份、戰績及統計使用同一 native backend。確認新 Google 登入、正式／休閒結算、本季／生涯讀取，之後才開放。舊部署不能恢復指向新表的寫入權限。

新表與新執行身分是較小的「權限邊界」，不是新增架構平台；建立、分享、撤權及資料遷移仍需各自的適當授權。若沿用同表且已有可核實的完整停寫，也可接受操作性切換，但應清楚保留無法由應用程式自行證明的條件。

## UNKNOWN 與續期

- 新協議的舊請求受 anchor／receipt CAS 約束，不能因重放而重複套用；這不適用於 legacy 純 cell 更新。
- UNKNOWN 不得自動續期、換 owner、重新生成 plan 或回退 legacy。
- 新程序看見未變的 CLOSED 前態，不能判定舊請求從未送達；看見完成後態，也不單憑此判定所有寫入來源已停止。
- 正常期限屆滿與 UNKNOWN 分開處理。正常續期須無 pending／in-flight、驗證最新 journal 與既有 source fence，使用新的有限期能力；不能把舊 UNKNOWN 洗成新 session。
- 切換後若回復，先停止新寫入；不得直接重新啟用舊表舊程式，否則會分叉遺失新資料。

## 同表與新表比較（本輪只評估，不建立／遷移）

| 項目 | 同表補 native metadata | 新正式目標表 |
|---|---|---|
| 改動 | 保留 cells，補 schema/control/journal，切 driver | 驗證快照複製至新表，補控制 metadata，切 driver＋目標 |
| 舊在途 cell 寫入 | 仍能打到同一張表；journal 只能發現改動，不是 fence | 固定舊 spreadsheet ID 的在途請求不會被重新導到新表 |
| 權限 | 必須真正隔離所有 legacy 寫入者；只停畫面或主 Bean 不夠 | 新表分享需批准。若仍給舊身分廣泛權限，不能把「換表」說成完整憑證隔離 |
| 資料尾端 | 舊寫入可能與改造後直接交錯 | 必須核對舊表停寫前後尾端，避免快照切點之後的已承認戰績遺漏 |
| 回復 | 原地 metadata／schema 變更使回復較複雜 | 舊表保留，但新表開始寫入後不可直接回退舊表，須先處理新增差異 |
| 季度破壞性操作 | 僅 late detection 不足以宣稱安全；需要外部真正 fence | 可把 legacy 在途請求限制在舊目標，再在新目標實施單寫入者協議 |

### 建議

若目標是後續可執行季度刪除，而無法明確排除 legacy 在途更新，**新正式目標表較直截了當**。不必同時改資料庫或大改應用架構：保留舊表、驗證快照、核對複製結果、切兩個目標設定即可。純同表 plan 仍有價值，可作 schema／資料資格評估，但不能把該 plan 成功等同於排他寫入已解決。

最低驗收應包含：

1. 明確記錄來源切點與所有已確認成功的操作；暫停舊服務新請求並清點 pending／UNKNOWN。
2. 以來源完整 validated snapshot 建新表；比對所有業務 cell、表頭、帳號／Google 身分映射、生涯 carry、每筆 commit 與 canonical payload hash。控制 metadata 依新目標重新產生，不能複製舊 owner 或 arm 當授權。
3. 切換前重新核對舊表尾端。晚到但合法的新資料要另外驗證後納入；衝突／未能判定的結果需列出並處理，不能靜默略過或直接重送。
4. 新表只向批准的執行身分分享；確認沒有其他舊程式被設定為新 ID。若需要強憑證隔離，使用新執行身分，讓舊身分無新表編輯權。
5. 解除父任務報告的那 1 項 lifetime 資格未驗證，或明確界定其影響並取得對應決策；不得為遷移自行重算／補造該資料。
6. 新表讀回、單一 native writer 與正式／休閒結算＋統計驗證成功後才開站。UNKNOWN 仍不授權自動 renewal。

換新表保護的是新目的地免受「已發往舊 ID」的請求影響；它不自動證明來源快照包含所有應保留資料，也不阻擋仍有新表權限且知道新 ID 的其他程式。這兩個範圍必須分開驗收。
