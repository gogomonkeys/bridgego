# BridgeGo API 與排查入口

本文件描述本機目前實作，包含斷線暫代；包含低點數倒牌；本文件不表示已部署。遊戲完整快照及狀態機仍以 [CONTRACT.md](../../CONTRACT.md) 為準。

## 查找順序

- [HTTP OpenAPI 3.0.3](openapi.json)：可匯入支援 OpenAPI 的 API 工具，包含現行 HTTP 操作、認證、參數、請求及回應結構。
- [WebSocket 協定](websocket.zh-TW.md)：訂閱、快照、心跳與關閉流程。
- [錯誤碼索引](errors.zh-TW.md)：HTTP、WS close code、客戶端分類分開說明。
- [排查手冊](troubleshooting.zh-TW.md)：從玩家看見的問題找到證據。
- [機器可讀錯誤來源](errors.json)：穩定 code、HTTP 狀態集合、原始碼來源及處置；文字訊息不是判斷分支依據。

OpenAPI 的 `GameSnapshot`、`MatchArchive` 是明確標示的可擴充摘要，並非完整嚴格驗證器或可直接產生完整前端型別的契約；完整內容、隱私及紅線見 CONTRACT。不要把未列出的快照欄位當成不存在。WebSocket 也不由這份 HTTP 規格涵蓋。

## 身分與流程

1. `POST /api/sessions`，內容 `{ "displayName": "測試員" }`，取得訪客身分與 token。名稱以 Unicode grapheme 計算 1–5 字。
2. 其他 API 以 `Authorization: Bearer <token>` 傳送。不要把 token 放在 URL、截圖、log 或文件實例。
3. `POST /api/rooms` 建桌，或 `POST /api/rooms/{id}/join` 加入，再立刻開啟 `/ws` 並 subscribe。HTTP 加入後有 **5 秒首次訂閱席位期限**。
4. 等候室準備、房主新增／移除電腦；四席皆準備且有人在場才開局。
5. 叫牌／出牌等命令包含 UUID `actionId`、最新 `expectedRevision`；場次／局命令也需最新 gameId/dealId。
6. 用回應中的 personalized `room` 或 WS snapshot 更新畫面。`you.ownHand`／`you.legalCards` 只能是自己的資料。

### 命令與重試

開局、電腦管理、特權、叫牌、出牌、局間確認、重新開始回傳：

```json
{
  "actionId": "11111111-1111-4111-8111-111111111111",
  "outcome": "APPLIED",
  "appliedRevision": 12,
  "room": { "roomId": "EXAMPLE", "revision": 12 }
}
```

上例 room 只示意識別與版本，**實際回應為完整 RoomSnapshot**。`REPLAYED` 表示同一玩家、同一 actionId、相同 payload 已接受，不再造成第二次副作用；回傳的 room 是目前快照，可能比 appliedRevision 新。

- 網路斷掉、空 body 或非 JSON：無法斷定命令是否執行。保留原 actionId／payload，不自動建立新ID重播。
- `409 STALE_REVISION`：重新同步並請玩家確認意圖；不可擅自依新版本重送舊牌。
- `409 ACTION_ID_REUSED`：相同ID但內容改變，屬協定衝突。
- 建立／加入／ready／leave 是既有簡單端點，**沒有相同的 actionId 冪等回應契約**，不能把所有端點都視為可安全重播。
- bot移除目前是 **DELETE with JSON body**；測試工具與代理必須保留 request body。

### HTTP 通用行為

- `/api/*`、`/health/*`、`/ws` 採 `Cache-Control: no-store`，並提供 `X-Bridge-Instance-Epoch`。epoch 改變代表記憶體服務重啟，不是普通網路波動。
- `/health/live` 程序還在時可為200，即使 status是stopping；`/health/ready` 維護停止時為503。
- 靜態資源仍 no-cache，但停用 Last-Modified 日期判定，避免固定1980建置時戳錯誤304阻止PWA更新。
- API request上限16KiB；429回應可能含 `Retry-After: 60`。不要密集重試。
- 應用已知錯誤回 `{code,message}`；代理失敗、框架未知路由或未處理例外不保證同格式，客戶端必須防守空／非JSON。
- 未設置公開試玩網站作預設 server；OpenAPI預設僅localhost，避免工具匯入後誤操作真實牌桌。

## 維護與驗證

```sh
python qa/check-api-docs.py
python qa/check-api-docs.py --render
```

第一個指令完全離線，不需套件，驗證 endpoint覆蓋、路徑參數、認證、內部參照、現行錯誤碼與前端翻譯、產生文件是否過期。`--render`只從errors.json重產生錯誤索引，不更新程式或OpenAPI。

完整 OpenAPI 標準驗證可在**工具專用虛擬環境**安裝 `openapi-spec-validator==0.7.2` 後執行：

```sh
python qa/check-api-docs.py --spec
```

這不是新增正式後端依賴；未安裝時不要聲稱已執行完整規格驗證。前端測試也包含 backend error code／catalog／翻譯的覆蓋比對；新增wire code需同步更新三處。端點增加／刪除須同步OpenAPI。修改遊戲規則需同步CONTRACT與WS文字，且額外跑完整三局及雙桌測試。

## 斷線代打與主動離開

請分開讀取playerType與controlMode：真人斷線10秒後可由BOT_TAKEOVER暫代，身分仍是HUMAN；原身分重新訂閱後接回。主動離開則換成永久BOT，不能接回。全真人斷線另有10秒恢復窗口，逾時NO_CONTEST；最後在線真人主動離開則立即結束。完整期限、私人快照與結果保留條件見[CONTRACT](../../CONTRACT.md)及[WS協定](websocket.zh-TW.md)。

## 倒牌選擇

發牌後先檢查A/K/Q/J為4/3/2/1點；小於5點或恰好5點且無A可選倒牌。REDEAL_DECISION為20秒私人選擇，真人逾時視為繼續；符合資格的電腦自動倒牌。PUT deals/{dealId}/redeal傳actionId、expectedRevision與redeal布林值。HAND_REDEAL是重發原因，不是phase名稱。重發只換dealId與累計次數，不增加局數、不換莊、不改分數。詳見CONTRACT。
