import { useEffect, useState } from "react";
import { client } from "./client";
import type { Call } from "./model";
import { symbols, strainNames } from "./game-view";
import {
  validBidSelection,
  selectedLegalBid,
  type BidSelection,
} from "./bid-selection";
export default function BidControls({
  legalCalls,
  locked,
}: {
  legalCalls: Call[];
  locked: boolean;
}) {
  const [draft, setDraft] = useState<BidSelection>({
    level: null,
    strain: null,
  });
  const selection = validBidSelection(draft, legalCalls),
    bid = selectedLegalBid(selection, legalCalls);
  useEffect(() => {
    setDraft((previous) => {
      const next = validBidSelection(previous, legalCalls);
      return next.level === previous.level && next.strain === previous.strain
        ? previous
        : next;
    });
  }, [legalCalls]);
  return (
    <section className="compact-bids" aria-label="選擇叫牌">
      <div className="bid-mobile-selectors">
        <label>
          <span>1 選階數</span>
          <select
            aria-label="叫牌階數"
            value={selection.level ?? ""}
            disabled={locked}
            onChange={(event) =>
              setDraft(
                validBidSelection(
                  {
                    level: event.target.value
                      ? Number(event.target.value)
                      : null,
                    strain: draft.strain,
                  },
                  legalCalls,
                ),
              )
            }
          >
            <option value="">選擇階數</option>
            {[1, 2, 3, 4, 5, 6, 7].map((level) => (
              <option
                key={level}
                value={level}
                disabled={
                  !legalCalls.some(
                    (call) => call.type === "BID" && call.level === level,
                  )
                }
              >
                {level}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>2 選花色</span>
          <select
            aria-label="叫牌花色"
            value={selection.strain ?? ""}
            disabled={locked || selection.level === null}
            onChange={(event) =>
              setDraft({
                level: selection.level,
                strain: event.target.value || null,
              })
            }
          >
            <option value="">選擇花色</option>
            {["CLUB", "DIAMOND", "HEART", "SPADE", "NO_TRUMP"].map((strain) => (
              <option
                key={strain}
                value={strain}
                disabled={
                  !legalCalls.some(
                    (call) =>
                      call.type === "BID" &&
                      call.level === selection.level &&
                      call.strain === strain,
                  )
                }
              >
                {symbols[strain]} {strainNames[strain]}
              </option>
            ))}
          </select>
        </label>
      </div>
      <fieldset disabled={locked}>
        <legend>1 選階數</legend>
        <div className="level-options">
          {[1, 2, 3, 4, 5, 6, 7].map((level) => (
            <button
              key={level}
              type="button"
              aria-pressed={selection.level === level}
              disabled={
                !legalCalls.some(
                  (call) => call.type === "BID" && call.level === level,
                )
              }
              onClick={() =>
                setDraft(
                  validBidSelection(
                    { level, strain: draft.strain },
                    legalCalls,
                  ),
                )
              }
            >
              {level}
            </button>
          ))}
        </div>
      </fieldset>
      <fieldset disabled={locked}>
        <legend>2 選花色</legend>
        <div className="strain-options">
          {["CLUB", "DIAMOND", "HEART", "SPADE", "NO_TRUMP"].map((strain) => (
            <button
              key={strain}
              type="button"
              aria-label={strainNames[strain]}
              aria-pressed={selection.strain === strain}
              disabled={
                selection.level === null ||
                !legalCalls.some(
                  (call) =>
                    call.type === "BID" &&
                    call.level === selection.level &&
                    call.strain === strain,
                )
              }
              onClick={() => setDraft({ level: selection.level, strain })}
            >
              {symbols[strain]}
            </button>
          ))}
        </div>
      </fieldset>
      <div className="bid-submit">
        <button
          className="primary"
          disabled={locked || !bid}
          onClick={() => {
            if (bid) void client.call(bid);
          }}
        >
          確認叫牌{bid ? ` ${bid.level}${symbols[bid.strain!]}` : ""}
        </button>
        <button
          className="secondary"
          disabled={locked || !legalCalls.some((call) => call.type === "PASS")}
          onClick={() =>
            void client.call({ type: "PASS", level: null, strain: null })
          }
        >
          Pass · 不叫
        </button>
      </div>
      <p className="fine">選項不會送出；按確認才叫牌。</p>
    </section>
  );
}
