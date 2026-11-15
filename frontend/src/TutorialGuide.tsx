/** Presentation only. Eligibility and persistence policy belong to the caller.
 * Never infers completion from a click or writes first-visit/account state.
 */
export type TutorialLesson =
  "FOLLOW_SUIT" | "WIN_TRICK" | "TRUMP_WHEN_VOID" | "FREE_PLAY";
const lessons: Record<TutorialLesson, { title: string; text: string }> = {
  FOLLOW_SUIT: {
    title: "1 · 跟花",
    text: "有首引花色時，必須出同花色的牌。選一張可出的牌。",
  },
  WIN_TRICK: {
    title: "2 · 贏得一墩",
    text: "同花色比大小；贏得這墩的人，會先出下一墩。留意電腦怎麼贏。",
  },
  TRUMP_WHEN_VOID: {
    title: "3 · 缺花切王",
    text: "沒有首引花色時，可以出王，也可以墊其他花色。",
  },
  FREE_PLAY: { title: "接下來自己試試", text: "照相同規則打完這局吧。" },
};
export function TutorialInvitation({
  busy,
  onStart,
  onSkip,
}: {
  busy: boolean;
  onStart: () => void;
  onSkip: () => void;
}) {
  return (
    <section
      className="tutorial-guide"
      aria-labelledby="tutorial-invitation-title"
    >
      <h2 id="tutorial-invitation-title">先試一局教學？</h2>
      <p>與三位電腦練習，前三次出牌有提示。</p>
      <div className="tutorial-actions">
        <button
          type="button"
          className="primary"
          disabled={busy}
          onClick={onStart}
        >
          開始教學
        </button>
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={onSkip}
        >
          先進大廳
        </button>
      </div>
    </section>
  );
}
/** Caller must use a server-confirmed lesson, never attempted play count. */
export default function TutorialGuide({ lesson }: { lesson: TutorialLesson }) {
  const content = lessons[lesson];
  return (
    <aside
      className="tutorial-guide"
      aria-label="教學提示"
      aria-live="polite"
      aria-atomic="true"
    >
      <strong>{content.title}</strong>
      <p>{content.text}</p>
    </aside>
  );
}
