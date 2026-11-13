package com.bridge.quartercore;
/** Exact-plan channel operations shared by separate, non-interchangeable grants. */
interface GooglePlanAuthority extends GoogleDispatchAuthority {
  void arm(JournalPlan plan);
  void finishDispatch();
}
