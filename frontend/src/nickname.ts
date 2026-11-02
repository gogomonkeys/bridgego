/** New guest names use visible Unicode grapheme clusters, not UTF-16 units. */
export const MAX_NICKNAME_CHARACTERS = 5;
// Match Java's explicit boundary rule, including full-width spaces, NBSP and BOM.
export function normalizeNickname(value: string): string {
  return value
    .replace(/^[\p{Z}\s\uFEFF]+|[\p{Z}\s\uFEFF]+$/gu, "")
    .normalize("NFC");
}
export function nicknameValidation(value: string): {
  normalized: string;
  count: number;
  error: string | null;
} {
  const normalized = normalizeNickname(value);
  if (typeof Intl.Segmenter !== "function") {
    return { normalized, count: 0, error: "請更新瀏覽器後再輸入訪客名稱。" };
  }
  const count = [
    ...new Intl.Segmenter("und", { granularity: "grapheme" }).segment(
      normalized,
    ),
  ].length;
  const error = !normalized
    ? "請輸入訪客名稱。"
    : /[\u0000-\u001F\u007F-\u009F]/u.test(value)
      ? "訪客名稱不可包含控制字元。"
      : normalized.length > 256 || count > MAX_NICKNAME_CHARACTERS
        ? "訪客名稱最多 5 個字，請縮短後再進入。"
        : null;
  return { normalized, count, error };
}
