# BridgeGo 前端

React + TypeScript + Vite。一般玩家請至 [快速開始](../README.md) 或 [線上試玩](https://bridgego.onrender.com)。

## 本機開發

需要 Node.js `^22.12.0 || ^24.0.0 || >=26.0.0` 與 npm，建議使用 Node.js 24。先依 [後端說明](../backend/README.md) 啟動同版後端，再在 `frontend` 目錄執行：

```sh
npm ci --ignore-scripts
npm run dev
```

開啟 http://127.0.0.1:5173。Vite 將 `/api`、`/ws` 代理到本機 8080 埠；其他本機埠設定見 [.env.example](.env.example)。開發模式不註冊 Service Worker。正式同源建置與 PWA 請看 [部署說明](../DEPLOY.zh-TW.md) 及 [PWA 文件](PWA.md)。

## 檢查

```sh
npm run typecheck
npm run lint
npm test
npm run build
```

## 行為與資料邊界

- 一場可選 1／3／5 局，四席由真人與電腦組成，至少一位真人。
- 後端負責合法操作、計分、倒數與牌局狀態；前端不自行裁定結果。
- 同來源分頁共用訪客身分；手機選牌後需明確確認出牌。
- 已收回合的歷史牌面在該局結算前蓋牌；未出的私人手牌不公開。
- 斷線、重連、電腦接管與操作重試以 [遊戲契約](../CONTRACT.md) 為準。
- 測試工具與歷史驗證資料見 [qa](../qa/)。單元測試、網路測試及真機瀏覽器驗收是不同範圍，不能互相代替。
