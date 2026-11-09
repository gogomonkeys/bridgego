export interface PrivacyMetadata {
  contact?: string;
  retention?: string;
}
export function privacyReady(privacy?: PrivacyMetadata): boolean {
  return Boolean(
    typeof privacy?.contact === "string" &&
    privacy.contact.trim() &&
    typeof privacy?.retention === "string" &&
    privacy.retention.trim(),
  );
}
export default function PrivacyNotice({
  privacy,
  managed = false,
}: {
  privacy?: PrivacyMetadata;
  managed?: boolean;
}) {
  return (
    <section
      className="privacy-notice"
      aria-label={managed ? "帳號資料與刪除說明" : "封閉測試資料與刪除說明"}
    >
      <h4>{managed ? "帳號資料與刪除說明" : "封閉測試資料與刪除說明"}</h4>
      <p>
        {managed
          ? "經 Google 驗證並同意後可連結帳號。統計開放時，所有玩家須先同意資料使用；正常完成計分且結算時為四位不同真人的合資格場次可保存，包含訪客。訪客不保存名稱或識別碼，沒有個人統計或統計入口，先前訪客場次不補登。統計未開放時沿用原保存規則：全場真人皆已登入並同意才保存，有訪客則整場不保存。"
          : "只有核准測試者連結 Google 後的合資格場次才會收集。訪客可以照常遊玩；只要場內有訪客或未核准者，整場都不保存。先前訪客場次不會補登。"}
      </p>
      <p>
        {managed ? "保存資料包括 Google" : "測試資料包括 Google"}
        發行者與帳號識別碼、遊戲帳號識別碼、場次與參與紀錄、座位／隊伍、完成局數、分數、勝負及結束原因。
        {managed ? "資料用於帳號及戰績保存，" : "資料供有限測試使用，"}
        存於遊戲營運者管理的私人 Google 試算表；不保存 Google
        名稱、信箱、頭像、登入憑證或完整手牌。
      </p>
      {managed && (
        <p>
          帳號保留，個人戰績依台灣 finishedAt
          季度每季人工清理；不含個人識別的整體季度數字可依政策保留，統計開放時，僅登入玩家可查看本季個人及整體統計。刪除與季度清理不因登入開放就視為已完成實機驗收；版本歷史、備份與匯出副本可能保留。
        </p>
      )}
      <p>
        保存採盡力而為，沒有保證不遺失的持久佇列，服務重啟或寫入失敗可能遺失。解除
        Google 連結不是刪除：已保存紀錄不會因此移除。
      </p>
      <p>
        如要刪除，請透過下列管道提出申請，營運者須先驗證帳號歸屬。處理範圍為移除你的帳號對應及參與者識別資訊，同場其他玩家的資料與不帶身分的場次分數可保留；不保證所有歷史副本不可逆地完全清除，備份須依另行告知的政策處理。請勿提供密碼或登入憑證。
      </p>
      {privacy?.contact?.trim() ? (
        <p>負責窗口／刪除申請：{privacy.contact}</p>
      ) : (
        <p role="status">刪除管道尚未設定，資料保存不可啟用。</p>
      )}
      {privacy?.retention?.trim() ? (
        <p>保存期限與處理政策：{privacy.retention}</p>
      ) : (
        <p role="status">保存期限尚未設定，資料保存不可啟用。</p>
      )}
      {!privacyReady(privacy) && (
        <p className="fine">目前不具備啟用資料保存的條件，仍可使用訪客遊玩。</p>
      )}
    </section>
  );
}
