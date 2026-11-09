import { canCompleteEntry } from "./entry-flow";
import type { AuthConfig, LoginPurpose } from "./google-login";
import { privacyReady } from "./PrivacyNotice";

export function isManagedCollection(config: AuthConfig | null): boolean {
  return config?.collectionMode === "managed";
}

export const managedLoginConsent =
  "登入表示同意將 Google 識別碼、遊戲帳號及合資格戰績存入營運者的私人 Google 試算表。帳號保留，個人戰績每季人工清理；解除連結不是刪除。";

export function isSingleTesterExperiment(config: AuthConfig | null): boolean {
  return config?.collectionMode === "single_tester_experiment";
}

export function experimentExpiry(config: AuthConfig | null): number {
  if (
    typeof config?.experimentExpiresAt !== "string" ||
    !/(?:Z|[+-]\d{2}:\d{2})$/.test(config.experimentExpiresAt)
  )
    return Number.NaN;
  return Date.parse(config.experimentExpiresAt);
}

export function experimentNotice(
  config: AuthConfig | null,
  now = Date.now(),
): string | null {
  if (!isSingleTesterExperiment(config)) return null;
  if (
    Number.isFinite(experimentExpiry(config)) &&
    experimentExpiry(config) <= now
  )
    return "單人一場實驗已到期，停止新增保存；期限到達不等於資料已刪除。";
  if (!canLinkAccount(config, now))
    return "單人一場實驗目前不接受新登入；名額已用或結果不明時，請先聯絡管理者。";
  const deadline = new Date(experimentExpiry(config)).toLocaleString("zh-TW", {
    timeZone: "Asia/Taipei",
    hour12: false,
  });
  if (canRecoverAccount(config, now))
    return `一次性帳號恢復，截止 ${deadline}（台灣時間）；送出後會新增恢復標記，結果不明時不得重試。`;
  return `單人一場實驗，截止 ${deadline}（台灣時間）；取消或失敗也可能用掉名額。`;
}

export function canLinkAccount(
  config: AuthConfig | null,
  now = Date.now(),
): boolean {
  return Boolean(
    config?.enabled === true &&
    config.verificationEnabled === true &&
    typeof config.clientId === "string" &&
    config.clientId.trim() &&
    (((config.collectionMode === "restricted" || isManagedCollection(config)) &&
      config.collectionReady === true) ||
      (isSingleTesterExperiment(config) &&
        config.collectionReady === false &&
        config.experimentReady === true &&
        config.experimentMaxMatches === 1 &&
        Number.isFinite(experimentExpiry(config)) &&
        experimentExpiry(config) > now)) &&
    privacyReady({
      contact: config.privacyContact,
      retention: config.retention,
    }),
  );
}

export function canRecoverAccount(
  config: AuthConfig | null,
  now = Date.now(),
): boolean {
  return (
    config?.experimentRecoveryEnabled === true &&
    isSingleTesterExperiment(config) &&
    canLinkAccount(config, now)
  );
}

export function entryAccountPurpose(
  config: AuthConfig | null,
  now = Date.now(),
): LoginPurpose | null {
  if (canRecoverAccount(config, now)) return "recover";
  if (canLinkAccount(config, now)) return "link";
  // Normal collection never falls back to a subject-display testing flow.
  if (isManagedCollection(config)) return null;
  return config?.verificationEnabled === true &&
    typeof config.clientId === "string" &&
    config.clientId.trim()
    ? "verify"
    : null;
}

// Neither verification-only success nor a truthy/malformed status is login.
export function canEnterAfterAccountLink(
  signedIn: unknown,
  expectedToken: string,
  currentToken: string | undefined,
  hasRoom: boolean,
  mounted: boolean,
): boolean {
  return (
    signedIn === true &&
    mounted &&
    canCompleteEntry(expectedToken, currentToken, hasRoom)
  );
}
