package com.bridge.quartercore;
/** Fixed reached-stage code only: never retains credentials, response data or original causes. */
final class PrepareDiagnosticFailure extends RuntimeException {
 enum Stage {OPERATOR_AUTH,LEGACY_OPEN,LEGACY_FETCH,LEGACY_PARSE,LEGACY_PARSE_ROOT,LEGACY_PARSE_ROOT_PROPERTIES,LEGACY_PARSE_COMMENTS,LEGACY_PARSE_STYLE,LEGACY_PARSE_SHEET,LEGACY_PARSE_DIMENSION,LEGACY_PARSE_CELL,LEGACY_PARSE_CONTROL,LEGACY_PARSE_BUDGET,CUTOVER_ASSESSMENT,PREPARE_GUARD}
 final Stage stage;
 PrepareDiagnosticFailure(Stage stage){super(stage.name(),null,false,false);this.stage=stage;}
}
