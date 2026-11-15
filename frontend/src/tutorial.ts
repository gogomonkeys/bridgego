import type { Card, Play, Trick } from "./model";
import type { TutorialLesson } from "./TutorialGuide";
export interface TutorialSnapshot {
  sessionId: string;
  revision: number;
  lesson: TutorialLesson | "COMPLETE";
  hand: Card[];
  legalCards: Card[];
  currentTrick: Play[];
  completedTricks: Trick[];
}
export interface TutorialStatus {
  enabled: boolean;
  signedIn: boolean;
  offered: boolean;
  game: TutorialSnapshot | null;
}
const guestKey = "bridge-tutorial-offered-v1";
export function guestTutorialOffered(): boolean {
  try {
    return localStorage.getItem(guestKey) === "yes";
  } catch {
    return false;
  }
}
export function markGuestTutorialOffered() {
  try {
    localStorage.setItem(guestKey, "yes");
  } catch {
    /* Optional preference: play remains available. */
  }
}
