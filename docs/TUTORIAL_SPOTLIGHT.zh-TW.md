# 精簡牌桌教學（待發布）

沿用正式遊戲的 GamePanel、TableSeats、BidControls、HandPanel，只額外加暗幕與重點框，不另做一套教學牌桌。

## 流程
1. 認識座位與對面隊友。
2. 認識叫牌。
3. 可試叫牌；此選擇只在本機練習，明示與後續固定牌例無關。
4. 看固定牌例的得標者、黑桃王牌及 7 墩目標。
5. 練習跟梅花。
6. 認識贏墩後領出，再跟一次花色。
7. 缺花時練習切王。
8. 完成並返回大廳，不要求打完 13 墩。

## 安全與操作
- 不限時，不進一般房間或統計。
- 上下步僅切換說明；回顧時鎖住出牌，不回放或重送原操作。
- 回顧叫牌介紹後返回同一教學 session，不重新開始伺服器牌局。
- 第三張牌後以既有 FREE_PLAY 狀態表示基本演練結束；前端禁止第四張出牌，完成透過既有 exit API 確認。
- 暫不更改後端枚舉以保持既有客戶端相容。FREE_PLAY 並不代表真正整局結算，教學本來即不寫戰績。
- 操作結果不明維持既有唯讀核對，不自動重送。
- 浮動說明不占牌桌 grid。手機依目標與視窗高度放置、追蹤捲動；文字保持精簡。仍需發布前實際手機視覺驗收，尤其小螢幕與展開手牌。

正常正式場／休閒場的叫牌與出牌 30 秒均不變。未變動季度清理與環境設定。

## 0.14.3 發布前核對
- 由正式 0.14.2 / 05094e0 的獨立 checkout 修改，未切換正式環境。
- 前端、backend pom、health、戰績 app_version 同步為 0.14.3；維護／實驗 allowlist 保留舊版並加入 0.14.3，LifetimeRetentionTest 同步。
- 前端 673 測試、production build、lint 通過。
- 以受支援 sites-preview + Cloud Browser，在隔離假資料 iframe 的 390×844、320×568 viewport 驗證座位／叫牌／定約／跟花提示、合法選牌、展開手牌與確認按鈕；暗幕不擋本步操作。不是 iOS Safari 實機測試。
- 教學結束只移除記憶體 session，無戰績保存；首次選擇的 tutorialOffered 帳號偏好仍沿用。
- 正常遊戲30秒、季度清理開關及正式資料均未更動。
- 後端：Java 21 本機 compile/testCompile 後，以 Maven 解析出的精確 test classpath 與 JUnit Console 執行，1177 項全部通過（含教學及維護相容）。首次廣泛納入 Maven plugin jars 的測試 classpath 曾造成 logger／子程序classpath的12項環境錯誤，改用專案精確classpath後全數消除，未修改測試語意。
- 限制：本機 JRE 缺 release 17 的 ct.sym；標準 Maven Surefire 依賴解析亦失敗。因此這是 Java21 的完整功能回歸，不宣稱已完成 Docker/JDK17 `-Pweb clean package`；正式發布仍須檢查 Render 建置結果。未改動 repository 的 Java17設定，臨時測試工具與preview fixture不納入發布。
