# WebSocket 協定

連線地址為同來源 `/ws`：HTTPS頁面使用wss，本機HTTP使用ws。production來源白名單由PUBLIC_ORIGIN控制，不接受任意來源。

## 開啟與訂閱

HTTP已取得原訪客token並加入房間後，開啟WebSocket立即送一次：

```json
{"type":"subscribe","token":"<private guest token>","roomId":"<joined room>"}
```

禁止在log或診斷資料中記錄此完整訊息。伺服器驗證session與成員，成功後送：

```json
{"type":"snapshot","room":{}}
```

room為完整且個人化的RoomSnapshot，含房間固定 `plannedDeals:1|3|5`（預設3），開局後 game.plannedDeals 相同，game.dealIndex／completedDeals 表示目前／已完成局數；上例空物件只表示封包外層。別將某位訂閱者的快照廣播給其他玩家。Live四張牌是公開資料，已收走的牌面須等本局實際結算才公開；完整紅線見CONTRACT。

訂閱後唯一支援的應用JSON請求是：

```json
{"type":"ping"}
```

回應為 `{"type":"pong"}`。叫牌／出牌／新增電腦等操作全部經HTTP命令，**不得透過WS送動作**，也不能在同一連線再次subscribe。

## 心跳與期限不是同一件事

- 原生 WebSocket Ping/Pong 由伺服器與瀏覽器實作；目前ping間隔約2秒、probe期限6秒，帶已測試的伺服器排程延遲保護。瀏覽器JS不自行冒充原生Pong。
- 應用JSON ping/pong不替代原生心跳。
- HTTP加入到首次訂閱的席位期限5秒，和WS開啟到subscribe的5秒限制不同。
- active game 每位真人最後一條連線斷線後保留10秒（RECONNECTING）；期間暫停。若仍有真人在線，逾時轉BOT_TAKEOVER暫代；所有待恢復席位已恢復或暫代後才續行。WAITING斷線立即釋放席位；另一個仍連線分頁可維持presence。
- 最後在線真人斷線後，房間另有10秒全員恢復窗口allHumansReconnectDeadline。期間任一原真人重連可續行；無人返回則NO_CONTEST / NO_HUMANS_CONNECTED。BOT不能延長此期限或維持純電腦對局。
- 主動離開不是斷線：活躍席位立即由永久BOT取代，不能接回；若無在線真人則立即NO_CONTEST / NO_HUMANS_REMAIN。
- BOT不需要HTTP訪客token或WS，不應發送假心跳。
- 原生心跳無法保證手機鎖屏／OS凍結後繼續執行。

## 錯誤與關閉

可解析的協定錯誤先嘗試送 `{"type":"error","code":"NOT_MEMBER","message":"..."}` 再close1008。錯誤frame不保證在網路中斷時抵達，所以沒有收到它不能推斷不存在伺服器錯誤。

維護事件是 `type: maintenance`，帶新舊實例判斷需要的instanceEpoch與安全訊息，接著1012關閉。應核對服務狀態，不把已失效操作重新送出。close codes及處置見 [錯誤索引](errors.zh-TW.md)。

1006是客戶端觀測「未收到關閉訊框」，不能由伺服器作為close frame送出，也不是某一根因的同義詞。1011亦需對照服務log中的安全cause。

## 重連與順序

重新連線應先核對原身分／房間，沿用自己的token。不能自動建立新訪客取代原席位，不能重放舊叫牌／出牌。快照有revision；過期非同步回應須受身分、generation和instanceEpoch保護。客戶端不得以local timer推進遊戲或結算。

房間、座位、遊戲與牌面仍以server snapshot為準。由WebSocket斷線推測玩家輸贏、只靠JS計時器判負、或使用舊快照重新加入，均不屬此協定。

## 暫代與控制權恢復

players[].playerType仍是HUMAN，controlMode從RECONNECTING轉BOT_TAKEOVER，connected仍是false；永久BOT則兩者皆BOT且connected=true。不能單看connected判定遊戲必須暫停。原真人成功subscribe會原子恢復HUMAN控制並取得最新快照，並非只靠HTTP GET或前端按鈕改狀態。已發生的代打不可撤回，重連不重播動作。

暫代中的原身分若透過HTTP送新操作會收到CONTROL_TAKEN_OVER。不要以新revision盲重送；先恢復訂閱與控制，再依最新合法動作操作。BOT_TAKEOVER是歷史source，不是玩家型別；原始roster保留歷史身分，私人手牌仍只給原擁有者。

無真人結束的房間會從大廳隱藏並釋放房間額度，不能再開下一場。原成員可在固定5分鐘保留期內重連查看已完成結算；重連不延長保留時間，未完成牌面不公開。

## 倒牌快照

REDEAL_DECISION期間game.redeal只包含訂閱者自己的yourPoints、eligible、yourChoice、canChoose；不能把某位玩家的快照拿給其他玩家。選擇走HTTP PUT redeal，不增加WS命令。有人倒牌後收到新dealId，必須清除舊局選擇，不重播舊請求；lastRedealReason=HAND_REDEAL與redealCount不揭露發起者。逾時、電腦自動倒牌和斷線暫停皆由後端計時；客戶端只呈現快照。
