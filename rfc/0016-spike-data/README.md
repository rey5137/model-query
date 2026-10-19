# RFC 0016 corrected observations

The two `full-run-<version>.tsv` files are complete fresh runs of the final source, not spliced probe reruns.
Headerless fields: marker, vendor, Hibernate version, mode, mapping, probe, raw use/context, status,
elapsed milliseconds, asserted value/error, captured SQL. Tabs/newlines inside fields are flattened.
PASS and P* assert values. P* mechanically marks join/CASE/UNION rendering, not measured performance.
A translation exception leaves SQL empty if nothing reached JDBC. Assertion failures retain actual SQL.
JUnit success means the research harness completed; provider assertion failures remain FAIL observations.

* `cell-matrix.tsv`: every observation key with status on each line; NOT_RUN means mapping bootstrap prevented it.
* `statement-matrix.tsv`: count/read/write statement contexts, conservative use envelopes, and raw line references.
* `line-parity.tsv`: every changed status, including missing downstream subtype SQLRestriction rows on 7.4.
* `renderer-allowlist.tsv`: empirical permissions by statement shape/use envelope/strategy, plus current SPI refusals.
  ALLOW needs value assertions on both lines/all three vendors and zero failing cases in the candidate.
  It is bounded to tested contexts/operators, never authorizes unseen combinations, and excludes mapping variants.
  A count has its own site set. Derived-count FROM_* names are construction provenance, not TreatUse constants.
  Pruned orphan treatment and shape dependence cannot be represented by the current per-read TreatSite alone.
* `junit-<version>.txt`: exact final Surefire summary.
* `summarize.py`: reproducibly regenerates matrices/allow-list from raw data; no network or dependencies needed.

Read the results file for interpretation, mapping gates, MQ4016 versus MQ4015, and unprobed/refused contexts.
Raw use labels identify query probes; they are not themselves exact individual TreatSite records.
Elapsed values are one-shot diagnostics, not benchmarks. Query timeout is 10s; mutations roll back.
Probe labels follow RFC 0016 §11; the test source is the fixture/query definition. No product behavior changed.

S2 adds 414 cells per line (138 per vendor), across engine/fresh/anchored counts, keyless/group/CTE
projections, LEFT entity joins and root restrictions on every core strategy. `check-s2.py` validates coverage
and the expected count/projection findings. S2 ALLOW rows describe measured experimental rewrites; the
current SPI still refuses them pending an engine decision. Existing non-S2 probes are retained.

S3 adds 459 cells per line (153 per vendor), retaining all earlier probes. `summarize-s3.py` validates
fixture-valued detector and fallback assertions and regenerates `s3-confusion.tsv` (per vendor/strategy/line),
`s3-cost.tsv` (per shape, first/repeat translation+walk and memoized lookup nanoseconds) and `s3-fallback.tsv`
(actual last-page IDs and engine-path totals). `s3-api-<version>.txt` records local compiled signatures and
package annotation evidence. Detector classification is dialect-invariant within the 45 tested shapes;
opaque ST derived-table and TPC union SQL needs lexical inspection. This is not a general SQL parser.
The identity-map lookup timing is a prototype verdict cache, not Hibernate's query-plan cache.
S3 does not widen `renderer-allowlist.tsv`; the results section explains the unsupported/unprobed boundaries.

The four largest TSVs (`full-run-*.tsv`, `cell-matrix.tsv`, `statement-matrix.tsv`) are stored gzipped; `gunzip -k` them before running the scripts.
