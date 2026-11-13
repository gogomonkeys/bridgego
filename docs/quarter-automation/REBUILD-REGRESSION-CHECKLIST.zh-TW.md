# 2026-11-13 重建防回歸清單

本清單依前一輪對話、已回傳的工具結果與保留的遠端 C2 原始碼整理。
它是重建需求與先前錯誤記錄，不是本輪測試通過證明。每項須重新執行。
目前可客觀恢復的起點為 8fc9e0a；後續程式屬重建，不能沿用遺失版本測試數字。

|先前問題|本輪必要條件與回歸|
|---|---|
|Sheets 原生 Carlito theme／issuer 超連結被拒|比對 8253dce 已部署原始碼；僅精確已知 Carlito/Arial 色彩、issuer 格與 URL，錯位置、錯色、未知格式仍拒|
|B0 digest 使用不同演算法|初始化 baseline 必須使用 CutoverAssessment.token；native B1/B2 使用 fullToken，不混用|
|初始化後跨部署傳 hash 當 proof|保留私有真 VERIFIED B1，歷史遷移真 B2；同 JVM 同 staged backend，不用環境字串重建 proof|
|歷史遷移成功但尚未批准 activation 即建 startup 而卡住|migration-only先保存B2；獨立activation批准後才derive/build，不重送已成功遷移|
|HTTP 呼叫工作執行緒再驗權而主執行緒持鎖|所有外部觀察／讀寫不持 factory/authority 監視鎖；只短時更新狀態，重入guard無network|
|正常 session 到期被當 UNKNOWN|乾淨到期可完整重讀確認後renew；真正UNKNOWN不可renew/replay|
|啟用等待跨過5分鐘仍可CLAIM|觀察前後、fullread後、dispatch及CLAIM回讀前均檢查expiry/cancel；成功owned後不依短期cookie|
|active局被freeze但coordinator又等active0|先停新場，現有局可完成/排空，然後freeze/cleanClose；超時維護等待，不判負|
|每30秒idle tick掃全表|正常idle使用本地已知狀態；只在實際必要邊界做完整驗證|
|fixture缺本季COMPLETE導致啟用409|補合成fixture的本季COMPLETE；保留缺失時零CLAIM負向測試，不能放寬正式檢查|
|真RoomService時鐘/ticker與native clock不一致|共用可控wallclock，ticker分開控制；不覆寫真Spring policy/store/gate來讓測試通過|
|通用POST /initial-cutover/{action}攔到管理頁GET造成405|路由限制為精確action集合；真HTTP/JAR GET三個管理頁資源200|
|Spring static resource把no-store覆寫為no-cache|planned launcher明確no-store；真HTTP/JAR核對，而不只mock filter|
|前端未登錄QUARTER_MAINTENANCE|error-messages與docs/api/errors.json同步；前端完整測試|
|誤把UI超時當未送出|UI結果未知即停止、不重送；伺服器ACK/完整readback證據才可宣稱成功|
|新JVM缺能力整站無法服務維護頁|預設inert不可activate、不讀憑證、不呼factory；實際prod子JVM live/route200、ready/業務503|
|建置只測classes未測實際JAR|前端bundle、web package、prod java-jar靜態資源與headers smoke；無Docker引擎不可稱image通過|

## 邊界

- 不操作正式表，不重置 C，不建立 D，直到重建候選與必要授權均就緒。
- 所有未知寫入先唯讀核對；不盲目重送或回滾到 legacy writer。
- 固定同 JVM 季度政策不要求每季重新簽外部短期證據；突發重啟仍保守維護人工核對。
- 重要階段提交後，以 Library 源碼封存並重新下載核對 SHA256；本地git不是獨立持久備份。

## Phase 1 本輪實測

`rebuild-phase1-full-final.log`：重新 clean test 1,345 項，0 failures/errors/skips，1 分 53 秒。
已重建歷史納入、同 JVM startup/policy、inert 維護、一次性 metadata authority、有界Google
factory及固定公鑰簽章驗證核心。Carlito四處修正為從已保留遠端C2逐字恢復，其餘為重建。
初跑抓到 exact unchanged ledger-header anchor guard 過嚴及 provenance漏記legacy reader，
均修復後重新全量通過；沒有放寬任意儲存格修改或UNKNOWN重送。
仍缺服務內 B1→B2→啟用串接、窄HTTP/operator頁、真HTTP整鏈和新候選打包驗證。
因此本階段尚不可部署或執行正式清理。備份包含源碼與本輪log，另行下載比對SHA256。

## Phase 2 本輪實測

2026-11-13 `-Pweb clean package`：1,373 後端測試、0 failures/errors/skips，2分6秒，產生實際production JAR。
前端重新安裝後573項全過，TypeScript/Vite production bundle成功。
真Tomcat HTTP完成初始化→遷移→啟用→同Spring正式/休閒保存→季度fold→清理→重開；另有session逾期與兩種已套用但ACK未知的實際HTTP負向，均不重試/啟用。
操作頁Node三情境、離線簽章6項、start-managed實際程序8項通過。
實際production JAR啟動smoke：operator三資源200/no-store、ready/game503、毒化憑證未在啟動時讀取。
本次targeted曾發現測試漏做初次maintenance.tick，補成先保持關閉再真正tick核對開放；沒有放寬正式保護。
打包smoke最初的合成Counts欄位順序錯誤，被啟動驗證拒絕；修正fixture後通過。
Docker命令不可用，因此未宣稱Docker image或Render線上通過。D尚未建立、C及正式資料未動。
