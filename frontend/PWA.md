# BridgeGo PWA（V0.7.0）

## 使用
正式建置由同一 HTTPS 網域提供前端、/api、/ws 與 /health（localhost 可測試）。
支援安裝的 Chromium 瀏覽器會在「使用說明」提供安裝按鈕；其他瀏覽器顯示原生選單指引。iOS 請在 Safari 使用分享 → 加入主畫面。
此版本沒有離線遊戲；離線只能顯示已下載的靜態啟動畫面。

## 更新安全
不使用 skipWaiting、clients.claim、controllerchange reload，也沒有背景同步。
新版本下載後會等待所有舊版 BridgeGo 分頁／獨立視窗關閉才自然啟用。更新通知只在大廳或使用說明顯示，不占用進行中牌桌空間。牌桌離線時使用既有同步標籤標示「非即時畫面」，不增加頂部段落。請先結束遊戲，再關閉所有視窗後重新開啟。
不提供會在另一個分頁遊戲中強制啟用的「立即更新」操作。
開發模式不註冊 service worker。若曾在同一 localhost port 提供正式版，請使用不同開發 port 或在瀏覽器開發工具移除該 origin 舊 service worker。

## 快取界線
建置時依實際輸出雜湊生成 /sw.js。固定允許 index.html、此版本 JS/CSS、manifest、圖示與森林 WebP。
不快取 /api、/health、/ws；這些 GET 明確使用 no-store。其他路徑、帶 query 的靜態 URL、跨來源請求及非 GET 不攔截。
不保存訪客 token、手牌、房間回應或指令。訪客身分仍沿用既有 localStorage，不進 CacheStorage。
HTTP 錯誤、重新導向、MIME 不符的回應不快取；任一預載資源失敗即取消該版安裝。
不以 index.html 回應未知 API。

## 服務重啟
/api 回應的 X-Bridge-Instance-Epoch 是公開、非身分憑證的啟動識別。
只有與已知 epoch 不同才確認重啟，清除舊身分、房間及待確認指令。舊請求由 transport generation、epoch revision 與 token 防止晚到回應復活。
1006 或一般網路失敗本身不證明重啟；維持既有只讀恢復與 10 秒規則。
maintenance 訊框或1012清除即時牌桌，提供「確認服務狀態」只讀查詢 /health/ready；不自動建房、加入或重送操作。
缺少 epoch 的舊本機後端仍可使用，但無法明確辨別重啟。
免費服務重啟與部署不能無縫保留記憶體中的牌局。
