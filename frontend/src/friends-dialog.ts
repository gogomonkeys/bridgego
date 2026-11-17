/** Local dialog lifecycle: no account/network actions and fully reversible cleanup. */
export function mountFriendsDialog(
  dialog: HTMLElement,
  trigger: HTMLElement | null,
  close: () => void,
  doc: Document = document,
) {
  const overflow = doc.body.style.overflow;
  doc.body.style.overflow = "hidden";
  const focusables = () =>
    Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), a[href], summary, [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((element) => element.getClientRects().length > 0);
  const focus =
    dialog.querySelector<HTMLElement>("[data-friends-close]") ??
    focusables()[0] ??
    dialog;
  focus.focus();
  const keydown = (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close();
      return;
    }
    if (event.key !== "Tab") return;
    const elements = focusables();
    const first = elements[0],
      last = elements[elements.length - 1];
    if (!first) {
      event.preventDefault();
      dialog.focus();
      return;
    }
    if (
      event.shiftKey &&
      (doc.activeElement === first || !dialog.contains(doc.activeElement))
    ) {
      event.preventDefault();
      last.focus();
    } else if (
      !event.shiftKey &&
      (doc.activeElement === last || !dialog.contains(doc.activeElement))
    ) {
      event.preventDefault();
      first.focus();
    }
  };
  doc.addEventListener("keydown", keydown);
  return () => {
    doc.removeEventListener("keydown", keydown);
    doc.body.style.overflow = overflow;
    // On identity interruption/unmount the former trigger may no longer exist.
    if (trigger?.isConnected) trigger.focus();
  };
}
