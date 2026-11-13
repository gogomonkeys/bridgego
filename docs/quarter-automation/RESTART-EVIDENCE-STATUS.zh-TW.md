# 外部證據與正常重啟接管

本輪只做本地實作／測試及檢查既有 B 的本地證據；沒有部署、讀憑證、操作線上假表或修改正式資料。

## 三種情況分開

1. **同一程序休眠喚醒**：沿用已驗證 session continuity。正常逾時可完整唯讀重驗後續期，不要求每季重新登入或重新 cutover；真正 UNKNOWN 不可復活。
2. **受控乾淨重啟**：新 JVM 有獨立且精確的 clean handoff、舊程序停止、已接收工作收斂證據，核對當季 CLOSED journal 後，新 owner 單次 CLAIM，正常 coordinator 檢查後開放。
3. **native-only OPEN 重啟**：只在已排除 legacy／外部旁路、前程序終止、已知 accepted work 已結清或持久對帳時，使用獨立 control-only takeover capability 搶占下一個 receipt slot。新 owner 先贏，晚到舊 native wire 失敗；舊 wire 先贏，新接管停止而不重試。成功 ACK 與完整回讀後，撤銷單次 capability，派生新 ordinary session，沒有第二次 CLAIM、沒有改業務資料或放寬普通寫入權限。

PENDING/APPLIED、已知 UNKNOWN、未對帳記憶體 queue、過期季度須清理但未授權等情況，不因 root 或 COMPLETE 標記就恢復遊戲。CAS 能防止晚到覆寫，不能找回從未持久化的戰績。`backend.close()` 或 SIGTERM 本身不等於 clean handoff，沒有自動判負或丟棄 queue 的 shutdown hook。

## 外部證據實作與限制

`SingleServiceEvidenceProvider` 是具體驗證器，不只是原本的空 callback：固定 target／service／workspace／revision／來源批准與原始基線，驗證新鮮度、時間倒退、部署重疊、實例數及已知外部 writer 清單；session verifier 額外綁定 owner 與該次真正驗證過的 baseline。季度後／重啟後的新 baseline 由具體完成證據派生，不允許任意 caller snapshot 授權。

**實際 authenticated Render／程序證據 loader 尚未安裝。** 它不能把環境變數、使用者貼文或本地自述 JSON 當成外部驗證。可使用 bounded cache，但必須遵守 freshness。Render 新部署尚未 live 時與 readiness 的啟動依賴仍需實際部署模式核對；目前保守拒絕，不聲稱可以直接切旗標上線。普通健康季度不需要重新人工來源切換；實際不可還原資料刪除的逐次批准是另一項要求。

## B 與下一次線上隔離驗證

B 原證據缺 `bg.commit.v1` journal，且已完成當季。新 journal 初始化及新測試會修改原證據，因此 B 應保留唯讀，不能直接當「不改證據」的新測試區。

建議另批准一份 C 純假資料表，只分享既有 BridgeGo 服務帳號，保留 A／B／正式表。八表、固定 dataset、A:E 假資料、完整原生格線與 root/receipt/journal metadata，確切列數與基線應由最終 fixture manifest 提供。允許測試新增／清理舊季假記錄、保留當季與假帳號、生涯彙總、writer 換代／受控重啟，最後恢復原遊戲及健康設定；若公開程式包含新表 ID，需一併明示。不得把先前 B 的批准／arm／收據沿用。

現在不是實際季界：線上預設用真 UTC 與預置舊季假資料測清理，跨季遊戲先使用本地已驗證的注入 clock。若需要線上模擬季界，應另外限定 C 的 test-only 遊戲時鐘；OAuth、HTTP、TLS 及正式週期時鐘不改。

詳見 `docs/NATIVE_SINK_ISOLATED_VALIDATION_SCOPE.md`。

## 最後驗證

2026-11-12 18:21:37（UTC+08）全量 `mvn clean test`：1298 項、0 failures/errors/skipped，51.434 秒。包含真 child JVM 的 CLOSED／OPEN 接管後 ordinary authority／Handle profile 寫入、雙向 CAS 競爭及 UNKNOWN 晚到拒絕。日誌 `takeover-final.log`。
