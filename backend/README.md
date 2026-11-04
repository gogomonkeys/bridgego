# BridgeGo 後端

Java 17 + Spring Boot，提供訪客、房間、牌局 HTTP API 與 WebSocket。一般玩家請至 [快速開始](../README.md)。

## 本機開發

需要完整 JDK 17 與 Maven 3.9+。在 `backend` 目錄執行：

```sh
mvn test
mvn package
java -jar target/bridge-room-successor-0.11.0.jar
```

預設監聽 `127.0.0.1:8080`。前端開發方式見 [前端說明](../frontend/README.md)。要將前後端打包為同源服務，請使用 [正式建置腳本與部署說明](../DEPLOY.zh-TW.md)，產物為 `backend/target/bridgego-server.jar`。

## 目前功能

- 四席、對家同隊，至少一位真人，可加入電腦；所有人準備後由房主開始。
- 建房可選 1／3／5 局，預設 3 局；每局 13 回合，非最後一局最多等 90 秒確認。
- 左喊右攻、倒牌、特權、自訂計分、規則式電腦玩家與斷線接管。
- 伺服器判定合法操作、倒數、重試冪等與結算；個人手牌依觀看者隔離。
- `/health/live` 與 `/health/ready` 提供服務狀態及版本。

完整遊戲規則、連線與隱私邊界見 [CONTRACT.md](../CONTRACT.md)，介面見 [API 文件](../docs/api/README.zh-TW.md)。

## 資料與部署限制

訪客、房間、牌局及歷史只保存在單一程序的記憶體，服務重啟會清空；沒有持久化資料庫或多節點同步。公開 Beta 採受限訪客大廳，不是私人房間權限系統。正式環境需設定精確的 `PUBLIC_ORIGIN`，不要以萬用來源放寬連線限制。

[qa](../qa/) 保存測試工具與相關驗證資料。歷史測試結果不代表本次重新執行，也不等於 Windows、實體手機或多人真機端對端驗收。

## 來源說明

架構參考公開 AlanChen821/Bridge 的 Java／Spring controller、service、WebSocket 分層（main `a0d01adb114869b58623a43a447bb685d60a4670`）；此專案不連接原服務帳號、資料庫或憑證。依賴由 Maven 管理。
