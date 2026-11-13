package com.bridge.quartercore;

import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** Test-only contract for the modeled literal Sheets subset. Not an HTTP client.
 * Full reads occur only at startup/checkpoint/quarter. Each related read is a bounded
 * ordinary grid range (1000 x 26 maximum), not server-side value lookup.
 * A dispatch is attempted once. Any exception is outcome-unknown; callers reconcile,
 * never replay. A production implementation still needs actual bounded response parsing,
 * byte limits, authentication, and explicit source cutover. */
interface NarrowSheetsTransport {
  interface Scope extends AutoCloseable { @Override void close(); }
  /** One caller-owned logical operation, exact root predecessor, no reuse after close/write. */
  default Scope operation(String label,NarrowQuarterPoc.Root predecessor,Set<String> tabs,java.time.Duration budget) { return () -> {}; }
  Book snapshot();
  List<Metadata> control();
  List<List<String>> related(String tab);
  default List<List<String>> matching(String tab,int column,String value) {
    return related(tab).stream().skip(1).filter(r -> r.size()>column && r.get(column).equals(value)).toList();
  }
  void batch(String wire);
  boolean unsupportedStructure();
}
