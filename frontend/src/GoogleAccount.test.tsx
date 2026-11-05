import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import GoogleAccount from "./GoogleAccount";
import MatchPersistence, { persistenceLabels } from "./MatchPersistence";
describe("account and persistence disclosure", () => {
  it("keeps guest identity and best-effort limitation explicit without external embeds", () => {
    const html = renderToStaticMarkup(<GoogleAccount token="test" />);
    expect(html).toContain("訪客也能完整遊玩");
    expect(html).toContain("不會將先前訪客場次補登至帳號");
    expect(html).toContain("盡力而為");
    expect(html).not.toContain("<script");
    expect(html).not.toContain("<iframe");
    expect(html).not.toContain("accounts.google.com");
  });
  it("never labels an unconfirmed record as saved", () => {
    const html = renderToStaticMarkup(
      <MatchPersistence roomId="r" gameId="g" />,
    );
    expect(html).toContain("無法確認本場是否已保存");
    expect(html).not.toContain(persistenceLabels.CONFIRMED);
    expect(Object.keys(persistenceLabels)).toHaveLength(7);
  });
});
