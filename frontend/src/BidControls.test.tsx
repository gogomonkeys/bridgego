import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import BidControls from "./BidControls";
import type { Call } from "./model";

const legalCalls: Call[] = [
  { type: "BID", level: 1, strain: "CLUB" },
  { type: "BID", level: 2, strain: "HEART" },
  { type: "PASS", level: null, strain: null },
];

describe("responsive bid selectors share the legal draft model", () => {
  it("offers native mobile selectors without auto-selecting or submitting a bid", () => {
    const html = renderToStaticMarkup(
      <BidControls legalCalls={legalCalls} locked={false} />,
    );
    expect(html).toContain('aria-label="叫牌階數"');
    expect(html).toContain('<option value="1">1</option>');
    expect(html).toContain('<option value="2">2</option>');
    expect(html).toContain('<option value="3" disabled="">3</option>');
    expect(html).toContain('<select aria-label="叫牌花色" disabled="">');
    expect(html).toContain(
      '<button class="primary" disabled="">確認叫牌</button>',
    );
    expect(html).toContain('<button class="secondary">Pass · 不叫</button>');
  });

  it("locks both responsive selectors and both submission actions together", () => {
    const html = renderToStaticMarkup(
      <BidControls legalCalls={legalCalls} locked />,
    );
    expect(html).toContain('<select aria-label="叫牌階數" disabled="">');
    expect(html).toContain('<select aria-label="叫牌花色" disabled="">');
    expect(html.match(/<fieldset disabled="">/g)).toHaveLength(2);
    expect(html).toContain(
      '<button class="secondary" disabled="">Pass · 不叫</button>',
    );
  });

  it("never enables an unavailable pass or bid when no legal calls are supplied", () => {
    const html = renderToStaticMarkup(
      <BidControls legalCalls={[]} locked={false} />,
    );
    expect(html).toContain('<option value="1" disabled="">1</option>');
    expect(html).toContain('<option value="7" disabled="">7</option>');
    expect(html).toContain(
      '<button class="primary" disabled="">確認叫牌</button>',
    );
    expect(html).toContain(
      '<button class="secondary" disabled="">Pass · 不叫</button>',
    );
  });
});
