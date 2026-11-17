/** Decorative guide only: never replaces the real player's seat or game state. */
export default function TutorialCompanion({ welcome = false }: { welcome?: boolean }) {
  return (
    <span className={`tutorial-companion${welcome ? " tutorial-companion-welcome" : ""}`} aria-hidden="true">
      <img src={`/assets/forest/animal-${welcome ? "rabbit" : "fox"}.webp`} alt="" draggable={false} />
    </span>
  );
}
