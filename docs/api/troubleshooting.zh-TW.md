# 問題排查指南

先記錄：玩家裝置／OS／瀏覽器、是否PWA、畫面版本、發生時間與時區、當時遊戲階段、是否鎖屏／切到背景、可見錯誤碼。不要先清除網站資料或重裝，避免遺失訪客身分。

## PWA一直舊版

1. 「使用說明」按檢查更新，區分檢查中／實際下載中／準備好／未發現／失敗。
2. 準備好才關閉所有該站分頁及PWA，重新開啟。保留任一舊client就可能持續waiting，此為避免對局中強制更新的設計。
3. 維護者檢查health版本與sw.js實際bytes；單看根頁HTTP200不能證明SW更新正常。
4. 以1980 If-Modified-Since請求sw.js應回200新內容且無Last-Modified。這是曾發生過的Maven3.10固定時戳錯誤304回歸，不應用清除玩家資料當修復。
5. 保留V0.7.0→新版的「已安裝舊worker」測試，而非只有全新安裝。

## 斷線／熄屏

- 1006只是觀測，先看時間、page visibility、網路切換與服務restart。
- 4001是原生心跳逾時，不能直接判定某個瀏覽器有bug。
- 4002需分清WS未及時subscribe或HTTP加入後席位已釋放；原始加入錯誤與WS錯誤都要記錄。
- 1012或epoch改變：服務維護／重啟，記憶體牌局與訪客不持久化。
- 「保持螢幕開啟」需確認實際成功，不是checkbox勾上就代表OS允許；手動鎖屏或切背景仍可能暫停連線。
- 區分RECONNECTING（10秒暫停）、BOT_TAKEOVER（原席位暫代）與BOT（永久電腦）。全員意外斷線給10秒，不是單人逾時判負；最後在線真人主動離開則立即結束。
- 對照Render正常啟動／關閉與例外時間，不能用一次觀測推定因果。

## 操作失敗

- `STALE_REVISION`：兩個狀態交錯，先同步；不自動以新revision重送選牌。
- `STALE_GAME`／`STALE_DEAL`：已換場／重發牌；舊選擇已無意義。
- `CONTROL_TAKEN_OVER`：原席位已由電腦暫代，先以原訪客身分重新訂閱恢復控制，再讀取新快照；不自動重送舊操作。
- `NOT_YOUR_TURN`：叫牌或出牌皆可能，依當前phase判斷。
- 空JSON／代理500／fetch失敗：命令可能已完成。保留原actionId和payload，由既有手動重試取得APPLIED/REPLAYED，禁止換新ID盲送。
- `ACTION_ID_REUSED`：對照client命令建立、pending意圖與重試流程，不能忽略。
- BOT操作：只有在線房主於WAITING新增／移除；不得用假的真人socket繞過。

## 安全診斷邊界

目前client保留最多20筆斷線摘要（code、wasClean、lifetime、visibility、重試次數等），不包含token、房間/玩家ID、原始URL或frame。這是現有診斷，不是雲端自動收集平台。

日誌不得包含Authorization、subscribe.token、手牌、完整snapshot或未公開歷史。需要對照API時，後續可另設計requestId：一次HTTP一個requestId；actionId仍是同一意圖重試的識別，兩者不能混用。此版本**未新增requestId欄位、診斷複製按鈕或對外log上傳**。

## 維護順序

先執行文件漂移檢查、單元測試，再跑正式整合JAR的HTTP/WS與完整三局。重構一次只拆一項責任，保留RoomService單一鎖、命令冪等性與snapshot個人化；不要同時改遊戲規則、鎖與transport。

目前Read-only健康與靜態bytes驗證不等於實機PWA安裝、手機背景行為或正式網站完整對局測試，報告時要分開說明。

## 倒牌與反覆重發

- phase為REDEAL_DECISION，HAND_REDEAL只會出現在lastRedealReason。沒有合資格手牌會直接進特權，不是漏畫面。
- 只有自己的eligible/canChoose可用；不可從他人公開牌桌推測資格。5點含A不合資格。
- 真人未選擇滿20秒視為繼續；符合資格的電腦會倒牌，所以可能連續重發，這不增加局數。
- INVALID_REDEAL檢查布林payload；REDEAL_NOT_ELIGIBLE檢查當前私有快照；CHOICE_LOCKED表示已選擇。舊dealId或revision先同步，不新建actionId盲重送。
- 驗證隱私時逐一以不同玩家身分取快照，不保存完整快照至log；手牌重發不應累積已丟棄手牌的空白叫牌紀錄，但目前手牌可有一筆IN_PROGRESS占位紀錄。
