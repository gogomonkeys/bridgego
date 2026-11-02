# V0.7.0：PWA 與 Render 免費測試部署準備

## 這份交付做到哪裡

提供單一服務的 Docker 建置設定、正式版前端打包進 Java JAR、PWA 與兩桌測試限制。**不是已經上線，也沒有登入／變更你的 Render 帳號或建立遠端儲存庫。** Docker 映像尚需在有 Docker 的環境實際建置；本機 JAR 驗證不能取代 Render HTTPS、手機安裝與八人同時遊玩的驗收。

帳號與服務由你持有。下一步需要你在自己的帳號授權原始碼儲存庫連接；不要在聊天貼密碼、API 金鑰或登入驗證碼。遇到信用卡、升級或付款要求，先停止，不接受付費方案。

## 架構與限制

- 一個 Render **Free Web Service**，Docker／Java 17，前端與 `/api`、`/ws`、`/health` 同源；不另開資料庫、磁碟或排程服務。
- 最多兩個房間、每房四席。匿名大廳知道網址即可進入；**不公開網址不是權限控管**，連結可能被轉傳。
- 房間、席位、牌局都在記憶體。重新部署、程式崩潰、平台維護或主機重啟會讓當局結束，不能復原。正常維護通知只是盡力送達，強制終止不保證收到。
- Render 免費服務閒置 15 分鐘會休眠，喚醒可能約一分鐘；每個工作區每月共用 750 小時免費執行額度。平台可能隨時重啟，並非只會在閒置時發生。請勿把它當成全天候正式服務。
- 免費規格資源有限，Docker JVM 預設 256 MB 最大 heap、128 MB metaspace，另需原生記憶體。兩桌是產品上限，**不是已經證明免費主機可穩定承載八人**。需實際量測延遲、CPU、記憶體與完整三局。
- 不附固定打服務的防休眠腳本。測試前先開網址等候服務準備好即可。

官方條件可能改變，建立前重新核對：[免費服務](https://render.com/docs/free)、[Web Service](https://render.com/docs/web-services)、[WebSocket](https://render.com/docs/websocket)、[Blueprint 設定](https://render.com/docs/blueprint-spec)。

## 本機建置

先準備 Node.js 24、npm、JDK 17、Maven。原本 `frontend` 的 Vite 開發與後端本機流程仍可使用，不必先部署。

Windows PowerShell，在專案根目錄執行：

```powershell
.\scripts\build-production.ps1
```

Linux／macOS：

```sh
./scripts/build-production.sh
```

腳本先以 lockfile 執行 `npm ci --ignore-scripts`、建置前端，再執行 `mvn -Pweb clean package`（包含後端測試）。產物為 `backend/target/bridgego-server.jar`；靜態檔只進入 `target`，不回寫 `src`。獨立執行 `mvn -Pweb` 前必須先建置前端，否則可能得到缺少／過期前端的 JAR。

本機檢查打包版（預設只監聽 127.0.0.1:8080）：

```sh
java -jar backend/target/bridgego-server.jar
```

開啟 `http://localhost:8080`。localhost 可測試 Service Worker，但不等於真實手機 HTTPS 安裝驗收。不同埠的本機頁面不在預設 WebSocket 白名單內。

若已安裝 Docker，可在根目錄執行 `docker build -t bridgego-beta:0.7.0 .`。正式映像啟用 `prod`，必須設定 `PUBLIC_ORIGIN` 才能啟動，不會以萬用來源降級。

## 之後在自己的 Render 帳號操作

1. 先確認測試原始碼與儲存庫分享範圍；只有你同意後才建立／上傳儲存庫，不連接原正式網站的資料庫或帳號。
2. 用 Render 新增 Web Service，連接該儲存庫，專案根目錄包含 `Dockerfile`。選 **Docker、Free、Singapore**。`render.yaml` 可作設定範本，並非已執行部署；若儲存庫外層還有資料夾，調整 root directory 至 `bridge-successor`。
3. 關閉自動部署，改為測試空檔手動部署，避免推送原始碼直接中斷牌局。Health Check Path 設 `/health/ready`。
4. 環境變數 `SPRING_PROFILES_ACTIVE=prod`。取得 Render 實際分配的 HTTPS 網址後，設定 `PUBLIC_ORIGIN` 為**完全相同的來源**，例如格式 `https://你的實際服務主機`，不含結尾 `/`、路徑、逗號或 `*`。不要照抄範例主機，更不要猜測尚未分配的網址。若初次建置時還不知道網址，可先建立服務，取得網址後補上再部署；缺少此值時程式刻意拒絕啟動。
5. `PORT` 使用 Render 提供的值，未設定時為 8080；正式服務綁定 `0.0.0.0`。不用另外公開後端埠；TLS 由 Render 終止。不得把 `PUBLIC_ORIGIN` 改成萬用字元來解決連線問題。
6. 等建置成功、`/health/ready` 回傳 ready，再進行下列驗收。部署前閱讀 Render 畫面當下的方案與額度；有費用要求就先停止。

## 上線測試檢查表（仍須實際執行）

- HTTPS 首頁／圖示／manifest／sw.js 正確，API 錯誤與不存在的檔案不回傳首頁。
- Android Chrome 與 iPhone Safari 真機加入主畫面、獨立視窗啟動；PWA 不提供離線打牌，離線不得顯示快取的私人牌局 API。
- 兩桌八個獨立玩家完整三局；確認第三桌被拒、桌間私人牌資料隔離，測試自動逾時出牌與長時間背景分頁。
- 超過 15 分鐘的真實線上遊戲觀察、斷網／重連、手動部署／平台重啟後正確提示重新加入，不能把舊席位誤認成新房間。
- 有新版本時不強制中斷進行中的遊戲；回大廳再更新，驗證舊快取清理。
- 觀察 Render 記憶體、CPU、回應延遲與 WebSocket，未通過前不要擴大公開招募。

`/health/live` 是存活探針，`/health/ready` 是接客狀態；不包含玩家私密內容。兩者與 API 不應被快取。靜態檔採需重新驗證的快取策略，沒有全域 SPA fallback，也不使用跨來源 CORS 放行。
