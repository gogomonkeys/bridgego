package com.bridge.persistence;

import com.bridge.persistence.SyntheticCleanupExecutor.*;
import com.bridge.persistence.SyntheticCleanupRawProtocol.PhaseManifest;
import com.bridge.persistence.SyntheticSheetsErasurePreparation.Workbook;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;

/** One supervised synthetic-only rehearsal. Not a production host, CLI, web route or scheduler.
 * No credential discovery, disk access or automatic retries. The caller must independently obtain
 * approval for this isolated target and credentials, freeze all writers, display each preview, and
 * pass that action's exact confirmation. The caller cannot use this as evidence of that approval.
 * Process loss abandons this session; control markers must never be removed to allow a retry. */
final class SyntheticCleanupSupervisedSession implements AutoCloseable {
  record CellChange(String table, int row, int column, String before, String after) {}
  record TableImpact(String table, int rowsBefore, int rowsAfter, int changedCells) {}
  record ActionPreview(Preview confirmation, String purpose, List<TableImpact> tables,
      List<CellChange> changes, JsonNode exactBatchPreview) {
    ActionPreview {
      tables=List.copyOf(tables); changes=List.copyOf(changes);
      exactBatchPreview=exactBatchPreview.deepCopy();
    }
    @Override public JsonNode exactBatchPreview(){return exactBatchPreview.deepCopy();}
  }
  record Status(String outcome, int verifiedPhases, boolean syntheticFunctionalRunVerified,
      boolean productionCleanupCertified) {}
  private final SyntheticCleanupHttpWire wire;
  private final SyntheticCleanupRawProtocol protocol;
  private final SyntheticCleanupExecutor executor;
  private final FixtureSet fixtures;
  private PhaseManifest pending;
  private int completed;
  private boolean stopped;

  /** Constructs the real restricted transport but performs no network request. The payload must
   * have been supplied through a separately approved host; it is never read from a path/env here. */
  static SyntheticCleanupSupervisedSession open(String approvedTarget, byte[] authorizedKey,
      byte[] verifiedScaffold, Clock clock, ExclusiveWriterLease manualFreeze) {
    Objects.requireNonNull(manualFreeze);
    if(!manualFreeze.held())throw rejected();
    var wire=SyntheticCleanupHttpWire.open(approvedTarget,authorizedKey,verifiedScaffold,clock);
    try {return new SyntheticCleanupSupervisedSession(approvedTarget,verifiedScaffold,wire,clock,manualFreeze);}
    catch(RuntimeException | LinkageError failure){wire.close();throw rejected();}
  }
  /** Local integration tests inject only the underlying HTTP transport; all protocol checks remain. */
  SyntheticCleanupSupervisedSession(String target, byte[] scaffold, SyntheticCleanupHttpWire wire,
      Clock clock, ExclusiveWriterLease manualFreeze) {
    this.wire=Objects.requireNonNull(wire);
    Objects.requireNonNull(manualFreeze);
    if(!manualFreeze.held())throw rejected();
    fixtures=SyntheticCleanupExecutor.generate(target,clock.instant());
    protocol=new SyntheticCleanupRawProtocol(fixtures,wire,scaffold);
    executor=new SyntheticCleanupExecutor(fixtures,protocol,clock,manualFreeze);
  }
  synchronized ActionPreview preview() {
    if(stopped || pending!=null || completed>=Phase.values().length)throw rejected();
    try {
      Phase phase=Phase.values()[completed];
      Preview preview=executor.preview(phase);
      pending=protocol.manifest(preview);
      Workbook before=fixtures.states.get(completed),after=fixtures.states.get(completed+1);
      List<TableImpact> tables=new ArrayList<>();List<CellChange> changes=new ArrayList<>();
      for(String table:before.sheets().keySet()) {
        var b=before.sheets().get(table).cells();var a=after.sheets().get(table).cells();int count=0;
        for(int r=0;r<b.size();r++)for(int c=0;c<b.get(r).size();c++) {
          var old=b.get(r).get(c);var next=a.get(r).get(c);
          if(!old.equals(next)){count++;changes.add(new CellChange(table,r+1,c+1,old.value(),next.value()));}
        }
        tables.add(new TableImpact(table,nonemptyRows(b),nonemptyRows(a),count));
      }
      return new ActionPreview(preview,purpose(phase),tables,changes,pending.exportPreview());
    } catch(RuntimeException | LinkageError failure){close();throw rejected();}
  }
  /** Confirmation is intentionally not inferred from a previous stage or from displaying a preview. */
  synchronized Status confirm(String planId, String exactConfirmation) {
    if(stopped || pending==null)return status("REJECTED");
    var p=pending.preview();
    if(!p.planId().equals(planId)||!p.confirmation().equals(exactConfirmation))return status("REJECTED");
    try {
      wire.arm(pending);
      var result=executor.execute(planId,exactConfirmation);
      pending=null;
      if(result.outcome()!=Outcome.VERIFIED){close();return status(result.outcome().name());}
      completed++;
      if(completed==Phase.values().length)close();
      return status("VERIFIED");
    } catch(RuntimeException | LinkageError failure){close();return status("STOPPED_UNCERTAIN");}
  }
  private Status status(String outcome) {
    return new Status(outcome,completed,completed==Phase.values().length,false);
  }
  private static int nonemptyRows(List<List<SyntheticSheetsErasurePreparation.Cell>> rows) {
    return (int)rows.stream().skip(1).filter(row->row.stream().anyMatch(c->!c.value().isEmpty())).count();
  }
  private static String purpose(Phase p) {
    return switch(p) {
      case SEED_ERASURE -> "建立個人刪除用假資料；僅限固定隔離表。";
      case ERASE_ACCOUNT -> "執行實際個人刪除引擎；核對目標帳號與身分移除、其餘帳號及戰績保留。";
      case SEED_QUARTER -> "以季度測試假資料替換上一階段假資料；不保留前一組假資料。";
      case PURGE_QUARTER -> "執行實際季度引擎；核對過期戰績移除、本季邊界與帳號資料保留。";
    };
  }
  @Override public synchronized void close(){stopped=true;pending=null;wire.close();}
  @Override public String toString(){return "Synthetic supervised session (redacted)";}
  private static IllegalStateException rejected(){return new IllegalStateException("Supervised synthetic session stopped");}
}
