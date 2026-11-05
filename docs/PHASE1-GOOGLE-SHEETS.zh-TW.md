# 第一階段：訪客、可選 Google 身分連結、完成場次保存

> 後續封閉測試候選已改為「未批准玩家不持久保存」，並新增專用測試資料集與移除前置條件。下列為先前第一階段背景；實際啟用以 [最新文件](RESTRICTED_TEST_AND_ERASURE.zh-TW.md) 為準，勿照舊設定直接啟用。

**狀態：本機候選，未上傳、未部署，未建立 Google 資源，未用真實 Google 帳號或 Sheets 驗收。** 基線是 gogomonkeys/bridgego commit 6b077cb90dfe402e9a07260798da51f5ba469c99（v0.11.0）。不新增統計、私人戰績或排行榜畫面；私有儲存不是玩家可直接存取的文件。

## 使用界線

- 原本訪客玩法不變。未設定 Google/Sheets 時仍可玩，Google 選項不可用，場次標示「未啟用保存」，不冒充成功。
- Google 只在未入房的大廳連結。先向玩家說明並取得明確按鈕同意，才載入 Google Identity Services；玩家可取消，拒絕或失敗都不清除訪客工作階段。
- Google 身分由伺服器驗證 RS256 簽章、Google issuer、指定 client ID audience、有效期限、nonce，再把 issuer/sub 對應到隨機 account UUID。不使用 email、暱稱或頭像認人，不保存 Google ID token。
- 伺服器 nonce 綁定目前訪客 session、五分鐘有效、一次使用；變更要求同源 Origin 與 bearer。取消及重複請求不可把舊回呼附加到新流程。取消在儲存呼叫開始後才到達時，可能已建立帳號對應；取消會阻止連結，但不保證撤回已送出的寫入，也不等於刪除資料。驗證及 Sheets 呼叫不佔住遊戲全域鎖。
- 「解除 Google 連結」僅使之後新場次以訪客記錄；**不登出 Google 網站、不撤銷現有訪客 bearer、不讓其他已登入裝置退出**。玩家若要停止目前遊戲存取，仍須關閉/離開原有工作階段。原本的座位、重連及歷史存取模型不改變。
- 每場開始時凍結 account/guest 歸屬。既有訪客歷史不自動合併到 Google。訪客識別是跨場可連結的假名資料，不能稱真正匿名；瀏覽器資料清除或伺服器重啟後無法用暱稱找回。
- 完成或中止時由伺服器權威結果建立一次不可變紀錄，包含每個已完成局及明確 NO_CONTEST；不接受客戶端自報成績。未完成局不灌零分，不保存姓名、email、手牌或逐張出牌。
- 人類席位曾有電腦接管或被電腦取代時標示 MIXED；起始 BOT 標示 BOT。這不是公開排名或全真人成績認證。

## 保存與可靠性

- 原始場次先成為版本化 canonical JSON envelope，SHA-256 指紋、固定 `match_id:revision` commit ID。使用 `StatsStore` 隔離 Google；`IdentityStore` 獨立處理登入對應，之後可改用資料庫。
- 一次 Sheets `spreadsheets.batchUpdate` 以 appendCells 寫完整 envelope chunks 與 commit marker，字串使用 stringValue，不解讀公式。成功後重新讀取並核對資料、hash及完整性，才回報 CONFIRMED。同 commit 同資料重試不重計，異資料為 CONFLICT。
- 背景有界單寫者，不讓寫入延誤出牌。UI 區分 DISABLED、PENDING、CONFIRMED、FAILED、REJECTED、CONFLICT、UNKNOWN。網路結果未知時先查相同 commit，不產生新 ID。
- **盡力保存，不保證每場都留下。** 無耐久 outbox；免費實例重啟/回收會遺失尚未確認的佇列及當局。端點的狀態只保留在目前服務與可存取的場次範圍。Sheets 成功保存不等於可以恢復遊戲。
- Sheets 沒有唯一約束或分散式鎖。只允許一個寫入服務及受控私有工作表；部署交接要先停止舊寫者，不能用多實例或藍綠重疊寫入。讀取依 commit 去重，不能用 Sheets 列數當場數。
- 每次場次寫入前後會讀 envelopes/commits，全歷史掃描是 O(歷史量)；新帳號也讀 accounts/auth_identities。適合小規模試用，可能先遇 API 每分鐘限額，而非列數上限。429/5xx 有限重試；403 不盲重試；失敗需檢查設定及人工補救。不要宣稱無限免費、零遺失或生產級伸縮能力。
- 不建立付費資料庫、付費佇列或新訂閱；不代表既有主機、Google 政策和免費額度永遠不變。開通時需核對當時額度，禁止自動升級付費。

## 實體工作表與遷移

準備案曾規劃 matches/participants/deals 正規化分頁；本機第一階段改採 **envelopes + commits**，避免跨多列契約與權限暴露。canonical JSON 仍包含具型別的 match、participants、completed_deals 正規化邏輯記錄；匯入資料庫時用 `exportCommitted()` 只取通過核對的完整批次。這不是沿用舊空白範本就能直接開啟的設定。

私有 spreadsheet 四個分頁（必須精確同名），首列：

- envelopes：commit_id, payload_hash, chunk_index, chunk_count, canonical_json_chunk
- commits：commit_id, payload_hash, chunk_count, match_id, revision
- accounts：account_id, schema_version
- auth_identities：issuer, subject, account_id, schema_version

不得在原始分頁插入公式或人工改寫；Google subject 仍為私人識別資料，試算表只授予必要擁有者與專用 service account。不得分享「知道連結的任何人」。原始封包包含 schema_version、score_version=`bridgego-custom-v0.11-1`、app_version（本機候選標記），不可用新規則默默重算舊分數。

遷移時先停止唯一寫者、檢查待處理/失敗場次、備份私有資料；透過 exportCommitted 驗證後在單一資料庫交易寫 matches/participants/deals/commits，使用 match/revision、seat、deal UUID 唯一鍵。核對場數、四人席位、已完成局、兩隊分數、NO_CONTEST、hash與版本再切換。衝突/缺列先隔離。回滾須先停止新寫者並補回新資料，不能兩端同時接受正式資料。

## 上線前仍需指定與逐項批准（尚未執行）

1. 指定使用者擁有的 Google Cloud 專案、Google 帳號及私有 spreadsheet；確認玩家告知、保存期限、刪除/備份政策及合法使用目的。現在未開始收集真實資料。
2. 啟用 Google Sheets API。建立專用 service account 與必要的持續存取憑證屬安全敏感操作，需當次批准；不需授予專案 Owner/Editor。服務端用 Sheets scope，試算表明確僅分享 Editor 給該 service account 的精確 email。分享文件及憑證設定也需批准。
3. 建立 Web OAuth client ID，精確設定正式 HTTPS origin；本機測試 origin 另列。Google GIS 是 identity-only，不要求玩家授權 Drive/Sheets scope；OAuth client secret 不需放到前端。不可把已連接的個人 Drive 插件當作遊戲伺服器授權。
4. 設定 OAuth 同意畫面、應用程式名稱、開發者/支援聯絡資訊、必要隱私權政策及授權網域。核對測試使用者限制、驗證/發布要求；未經 Google 驗證不能宣稱已驗證。僅請求基本身分也可能有品牌/網域審核要求，依實際控制台為準。
5. 透過受保護的主機秘密設定管道放入服務帳號 JSON，**不得貼入聊天、git、前端、截圖或 log**。這是為持續存取配置既有憑證，需當次確認；若無可用安全管道由使用者親自設定。
6. 設定下列服務端環境值並重新部署，部署本身需另行批准：
   - BRIDGE_SHEETS_ENABLED=true（預設 false）
   - BRIDGE_SHEETS_SPREADSHEET_ID=指定私有檔案 ID
   - BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON=秘密 JSON
   - GOOGLE_CLIENT_ID=Web OAuth client ID（以實際 AuthConfiguration 為準）
   - PUBLIC_ORIGIN=正式 HTTPS origin
   - BRIDGE_SHEETS_QUEUE_CAPACITY=128（可依小規模容量調整）
7. 先用批准的測試帳號及測試試算表驗收：Google 取消/錯帳號/過期/重播、登入中入房、跨 Origin、儲存超時/拒絕、重啟後帳號對應、每個完成場次及中止場次 readback、私有權限與記錄刪除。才考慮正式啟用；本機 fake 測試不取代以上驗收。

參考官方文件：
- https://developers.google.com/identity/gsi/web/guides/verify-google-id-token
- https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid
- https://developers.google.com/sheets/api/limits
- https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/batchUpdate
