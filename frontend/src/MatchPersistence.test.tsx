import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import MatchPersistence, { persistenceLabels } from "./MatchPersistence";

it("keeps match status and links detailed policy instead of repeating it", () => {
  const html = renderToStaticMarkup(
    <MatchPersistence roomId="fixture-room" gameId="fixture-game" />,
  );
  expect(html).toContain('role="status"');
  expect(html).toContain(persistenceLabels.UNKNOWN);
  expect(html).toContain("重新確認保存狀態");
  expect(html).toContain("保存狀態不影響本場勝負");
  expect(html).toContain('<a href="#help">使用說明</a>');
  expect(html).not.toContain("沒有保證不遺失的持久佇列");
  expect(html).not.toContain("已納入統計");
});

it("does not conflate persistence success with statistics eligibility", () => {
  expect(persistenceLabels.CONFIRMED).toBe("本場紀錄已確認保存。");
  expect(persistenceLabels.PENDING).toContain("尚未確認完成");
  expect(persistenceLabels.DISABLED).toContain("未啟用保存");
  for (const state of ["CONFLICT", "FAILED", "UNKNOWN"] as const)
    expect(persistenceLabels[state]).toMatch(/未能確認|尚未確認|無法確認/);
  expect(persistenceLabels.REJECTED).toContain("未排入保存");
});
