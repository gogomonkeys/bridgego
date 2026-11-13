package com.bridge.quartercore;
/** Fixed reached-stage code only: never retains credentials, response data or original causes. */
final class PrepareDiagnosticFailure extends RuntimeException {
 enum Stage {OPERATOR_AUTH,LEGACY_OPEN,LEGACY_FETCH,LEGACY_PARSE,CUTOVER_ASSESSMENT,PREPARE_GUARD}
 final Stage stage;
 PrepareDiagnosticFailure(Stage stage){super(stage.name(),null,false,false);this.stage=stage;}
}
