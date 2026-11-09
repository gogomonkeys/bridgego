// Test-only adapter mounting the production GoogleAccount component unchanged.
import React from "react";
import { createRoot, type Root } from "react-dom/client";
import GoogleAccount from "../src/GoogleAccount";
import { getClientState } from "../src/client";
const roots = new WeakMap<Element, Root>();
export function mountAccount(element: Element) {
  const token = getClientState().session?.token;
  if (!token) {
    alert("Enter the application first.");
    return;
  }
  let root = roots.get(element);
  if (!root) {
    root = createRoot(element);
    roots.set(element, root);
  }
  root.render(<GoogleAccount key={token} token={token} />);
}
