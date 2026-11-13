package com.bridge.quartercore;
/** Final-plan-aware sink. A raw business wire is not an authorized journal write. */
interface JournalPlanTransport extends NarrowSheetsTransport {
  default boolean renewalDue(){return false;}
  default void renewSession(){throw new NativeSheetsModel.Rejected();}
  void dispatchPlan(JournalPlan plan);
}
