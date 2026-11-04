# 錯誤碼與處置索引

> 由 `errors.json` 產生，請編輯 JSON 後執行 `python qa/check-api-docs.py --render`。

HTTP 狀態並不決定是否能重試。所有改寫請求都不得盲目自動重送；已接受命令只能以原 actionId 及完全相同 payload 查回結果。

| code | HTTP | 意義 | 客戶端／排查處置 |
|---|---|---|---|
| ACTION_ID_REUSED | 409 | 同一actionId對應不同payload | 排查命令ID重用；不可修改原重試payload |
| ALREADY_IN_ROOM | 409 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| CHOICE_LOCKED | 409 | 本次選擇已鎖定（倒牌或特權） | 保留既有選擇，不重送不同內容 |
| CONNECTION_LIMIT | 429 | 可用連線額度耗盡 | 關閉多餘分頁後核對原席位再重連 |
| CONTROL_TAKEN_OVER | 409 | 原真人席位目前由電腦暫代，尚未恢復控制 | 以原訪客身分重新訂閱房間，取得最新快照後才操作；不要重送舊動作 |
| GAME_IN_PROGRESS | 409 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| GAME_PAUSED | 409 | 有人斷線，既有遊戲暫停 | 等候伺服器恢復或裁定，不用客戶端倒數推進 |
| HOST_OFFLINE | 409 | 房主連線未建立 | 恢復房主連線再操作 |
| HOST_ONLY | 403 | 不是房主 | 僅提供有權限的操作 |
| ILLEGAL_CALL | 409 | 叫價未超過目前最高或不合法 | 依legalCalls重新選擇 |
| ILLEGAL_CARD | 409 | 牌不在手上或未遵守跟花規則 | 依legalCards重新選擇 |
| INVALID_ACTION_ID | 400 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| INVALID_CALL | 400 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| INVALID_CARD | 400 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| INVALID_JSON | 400 | HTTP JSON無法解析 | 排查序列化與Content-Type |
| INVALID_MESSAGE | WS JSON | WS訊息不是合法subscribe/ping格式 | 修正協定；伺服器會嘗試發error後關閉1008 |
| INVALID_NAME | 400 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| INVALID_PLANNED_DEALS | 400 | 建房局數不是整數1、3或5 | 選擇1、3或5局再建立房間；省略或null使用3局 |
| INVALID_PRIVILEGE | 400 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| INVALID_READY | 400 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| INVALID_REDEAL | 400 | 倒牌選擇欄位不是布林值 | 檢查redeal為true或false；不能傳字串或省略 |
| INVALID_REVISION | 400 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| INVALID_SEAT | 400 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| NOT_COMPUTER_PLAYER | 409 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| NOT_MEMBER | 403 | 不再是目前房間成員 | 同步身分/席位，不自動重新加入 |
| NOT_PARTICIPANT | 403 | 不是上一場的原參與者 | 停止讀取該紀錄 |
| NOT_YOUR_TURN | 409 | 叫牌或出牌不是目前行動席位 | 等候最新快照，不自動重送 |
| NO_LAST_MATCH | 404 | 沒有上一場結算 | 顯示空狀態 |
| RATE_LIMITED | 429 | HTTP或WS流量超出上限 | HTTP依Retry-After等待；WS依當前成員狀態恢復，勿自動重送改寫命令 |
| REDEAL_NOT_ELIGIBLE | 409 | 自己的手牌不符合倒牌資格 | 依最新私人快照eligible/canChoose決定操作；不得推測其他玩家資格 |
| REQUEST_TOO_LARGE | 413 | 請求超過16KiB界線 | 排查payload，禁止原樣密集重試 |
| ROOM_FULL | 409 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| ROOM_LIMIT_REACHED | 409 | 目前房間數達上限 | 加入可用等候室或等待其他房間釋出 |
| ROOM_NOT_FOUND | 404 | 房間不存在或已清除 | 回大廳，由使用者選擇房間 |
| SEAT_OCCUPIED | 409 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| SERVER_MAINTENANCE | 503 | 服務正在停止 | 等待服務就緒並核對epoch；不保證舊場次延續 |
| SESSION_CAPACITY | 503 | 訪客session容量上限 | 稍後再建立；不要密集重試 |
| STALE_DEAL | 409 | dealId已不是目前發牌 | 同步新手牌，請玩家重新選擇 |
| STALE_GAME | 409 | gameId已不是目前場次 | 同步最新場次，丟棄舊場次未提交意圖 |
| STALE_REVISION | 409 | 客戶端快照版本過期 | 重新同步，請玩家再次確認後送出新意圖 |
| START_NOT_READY | 409 | 輸入或目前席位/流程不符合要求 | 檢查請求與最新快照；修正後由使用者重新操作，不自動重送 |
| UNAUTHORIZED | 401 | 訪客憑證缺少、失效或過期 | 重新建立身分；不要回填舊token |
| WRONG_PHASE | 409 | 目前階段不允許此操作 | 同步後顯示該階段可用控制 |

## WebSocket close code

| code | 意義 | 處置 |
|---|---|---|
| 1000 | 正常關閉 | 依最新成員狀態處理，不假設需要重連 |
| 1001 | 頁面或服務離開 | 回到頁面後核對狀態 |
| 1006 | 瀏覽器觀測未收到關閉訊框；不可作為wire close送出 | 未知根因，對照時間/visibility/伺服器log，不直接判定伺服器崩潰 |
| 1008 | 訂閱/訊息規則或連線限制不符 | 先看前一個JSON error code |
| 1011 | 傳輸、輸出排隊或write timeout等伺服器WS失敗 | 對照安全日誌cause與時間，不能只憑1011定根因 |
| 1012 | SERVER_STOPPING維護停止 | 檢查健康與新epoch，不重播操作 |
| 4001 | 原生Ping/Pong心跳逾時 | 恢復連線與原席位；系統背景/網路/負載都可能相關 |
| 4002 | 首次WS訂閱逾時 | 核對建立WS後是否立即subscribe，與HTTP加入5秒席位期限分開 |
| 4003 | MEMBERSHIP_ENDED成員身分已結束 | 停止原房訂閱，不能自動冒充重入 |

## 客戶端分類（不是 HTTP wire code）

- **SERVER_RESTARTED**：已確認instanceEpoch改變。原訪客與記憶體房間失效，重新加入。
- **IDENTITY_CHANGED**：非同步回應已不屬於目前身分/epoch/generation。忽略過期回應，不還原舊身分。
- **HttpResponseError**：HTTP回應為空、非JSON或格式異常；不是穩定wire code。改寫結果未知，保留原actionId，禁止自動換ID重播。
- **NETWORK_ERROR**：此為排查分類，不是實際wire code；fetch網路失敗。同樣不得推定改寫未執行。

原始碼位置、HTTP 狀態集合及訊息範例見 [errors.json](errors.json)。此表不收錄私牌、token 或原始請求內容。
