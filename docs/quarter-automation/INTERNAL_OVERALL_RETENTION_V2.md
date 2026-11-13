# Internal overall quarterly retention v2

This completes storage of aggregate history; it does not enable a formal release,
authorize real deletion, or change public statistics responses or screens.

The eight-table layout and five-column aggregate header are retained for legacy
compatibility. Interpret rows by schema_version, not by the legacy rounded-count
column labels. Version 2 uses `[quarter, INTERNAL_COUNTS, canonical_json, "", 2]`.
The payload contains only version and FORMAL/CASUAL six counters: matches,
completedDeals, draws, scoredMatches, forfeitMatches, interruptedMatches.
It contains no identity, match identifier, nickname, email, precise timestamp,
individual score, hand history, or identity-to-contribution lookup.

The managed native quarter plan explicitly selects this retention policy. It
computes counters from the same verified snapshot used for lifetime folding and
raw deletion, and includes them in the same atomic apply phase. Prior synthetic
E evidence and legacy suppress-only CLI behavior are not silently reinterpreted.

Historical reader data is separate from current-season statistics. Old schema-1
SUPPRESSED or rounded CANDIDATE_COUNTS rows do not contain these exact six counters:
their history is unavailable, never six zeros. Do not silently mix accumulated
history into a screen labelled current season. Future display work must describe
coverage and gaps. Historical elapsed time is not inferred from absent values.

These are exact totals without direct identity fields, NOT a guarantee against
inference using outside knowledge in a small group. Storage is distinct from
permission to expose new historical views. Existing account-deletion behavior
must not add an identity contribution index to subtract previously aggregated
totals. Google revision histories and backups are outside active-sheet clearing.

Current fixed formal release remains unprovisioned. Before publishing a release,
revalidate the schema, policy, same-batch fold, cross-quarter nonduplication,
unknown-result fail-closed behavior, and unchanged current-season responses.
