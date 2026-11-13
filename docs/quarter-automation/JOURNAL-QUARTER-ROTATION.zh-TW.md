# journal版季度與rotation（本地候選，未發布）

## 本輪完成

- JournalQuarterProtocol保留CLAIM、PREPARE、FOLD、VERIFIED、CLOSE五步業務語意。原QuarterPlan僅提供純before/pending業務結果；每步新journal的完整expected Book都保留，沒有剝除metadata去騙舊proof。
- VERIFIED時間只在真實APPLIED完整回讀核對後產生。每個phase有完整operation scope與原生預算，總wallclock/monotonic期限受限。
- JournalRotation同批完成舊anchor/receipt競爭、新anchor ledger複製、舊anchor刪除、epoch/receipt/root/journal更新，所有表值保持相同。舊anchor消失使晚到舊批次失敗。
- 同owner checkpoint在Service鎖內執行；精確回讀後才更新ids/rowCounts/known。成功後profile和match可用新anchor，UNKNOWN則關閉舊writer，不再發送或偷重開。
- NativeCommitJournal僅對「立即前一PENDING、同operation/hash→APPLIED」允許同planId；其餘重複operation仍拒絕。這是原季度語意需要，不是一般重放放寬。

## 真正新JVM測試

test-only harness將完整synthetic native JSON存入暫存檔，再ProcessBuilder啟不同PID的java。子程序以StrictNativeSheetsResource解碼，核對journal、root、全部資料的canonical digest、重複wire/CAS拒絕。包括五步每步狀態、各步送出前/套用後UNKNOWN、普通晚到與rotation。沒有憑證或Google連線。

初次harness失敗來自Map鍵順序跨JVM造成測試full digest不一致，以及另一fixture缺Google原生欄位；修正測試為完整canonical digest及完整synthetic native response，未放寬parser或journal驗證。

## 不能把分類當授權

restart分類CLEAN_CURRENT、READY_DUE、REVIEW、UNVERIFIED只是資料狀態：
- UNKNOWN-before-CLAIM的舊CLOSED前態仍可能READY_DUE。
- UNKNOWN-after-CLOSE可能CLEAN_CURRENT。
- OPEN/PENDING/APPLIED未閉合全部REVIEW。

上述不代表取得writer ownership或可盲重跑。測試刻意驗證缺ownership時仍UNKNOWN。正式切換必須停用legacy入口；所有新協議writer晚到可由receipt/anchor fencing處理，不能只是看COMPLETE旗標。自動恢復未閉合季度尚未實作，本版UNKNOWN即停。

## 仍關閉

沒有Spring Bean切換、正式Google write sink、正式目標初始化、部署或清理。新的final journal wire需要獨立正式能力授權，舊假表WritePermit禁制未放寬。本地協議通過也不代表Google新版隔離回歸已完成。

## 最終結果

2026-11-12 09:16:12Z統一 `mvn clean test`：**1215 tests passed，0 failures/errors/skipped**。其中季度9項、rotation7項、獨立JVM重啟5項（內含多個子程序／未知矩陣）皆通過。日誌journal-quarter-rotation-final.log。正式狀態與資料未動。
