const animals = ["rabbit", "fox", "panda", "bear"];
export default function AnimalAvatar({
  seat,
  large = false,
}: {
  seat: number;
  large?: boolean;
}) {
  return (
    <span
      className={`animal-avatar ${large ? "large" : ""}`}
      aria-hidden="true"
    >
      <img
        src={`/assets/forest/animal-${animals[seat % 4]}.webp`}
        alt=""
        draggable={false}
      />
    </span>
  );
}
