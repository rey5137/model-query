# RFC 0018 spike data

Acceptance evidence for M30.0 on Hibernate 6.6.58.Final and 7.4.11.Final (Jakarta Persistence 3.2.0
on the latter), H2, PostgreSQL 16 (`postgres:16-alpine`) and MySQL 8.0.31 (`mysql:8.0.31`).
The [results](../0018-spike-results.md) give the verdicts and observations, including expected FAIL cells.
No production source or committed SQL snapshot is changed. New APIs and executor routing remain unimplemented.

## Files

`<version>` below means **both** `6.6.58.Final` and `7.4.11.Final`.
Raw TSV/JSON files exceeding 200,000 bytes are gzip-compressed; the eight `.gz` files include the two
previously compressed TCK SQL logs. Compression preserves decompressed bytes, including row references.

| Preserved input | Contents per version |
|---|---|
| `rfc0018-<version>.tsv.gz` | 1,170 functional cells, including the no-assertions repeat |
| `performance-<version>.tsv.gz` | 336 timing cells: 168 each for PostgreSQL and MySQL |
| `plans-<version>.tsv` | 12 executed plans: six each for PostgreSQL and MySQL |
| `tck-value-audit-<version>.tsv.gz` | Actual inline TCK SQL/test-callback audit; not committed (4.6 MB each): `summarize.py` reads it when present, else takes the SQL totals from `tck-audit-summary-<version>.json` |
| `tck-test-results-<version>.tsv.gz` | Authoritative JUnit results: 1,481 tests |
| `tck-audit-summary-<version>.json` | JUnit/SQL totals, vendor callbacks and profile self-check count |
| `value-path-observations.tsv` | 138 selected audit rows: 23 paths × three vendors × two lines |
| `value-path-audit.md` | Audit method, suite inventory, path references and limits |

| Generated file | Contents |
|---|---|
| `probe-matrix.tsv` | PASS/FAIL/SIMULATED/NOT_RUN/SKIP counts by source, probe, vendor and line |
| `part-matrix.tsv` | Functional counts by part, vendor and line |
| `performance-medians.tsv` | 48 scenario comparisons, min/median/max per route and INLINE_COUNT / COUNT ratios |
| `plan-summary.tsv` | 24 plan strategies, row multiplicity and single EXPLAIN elapsed-time ratios |
| `tck-matrix.tsv` | Per-line JUnit outcomes and SQL detector counts, checked against raw data |
| `value-path-matrix.tsv` | Status and decompressed audit-row references for the 69 paired paths |
| `line-parity.tsv` | 3,068 paired cells, separating status, observation, SQL and plan-shape equality |

The reducer is `summarize.py`, with offline tests in `test_summarize.py`.
Regenerate using only Python's standard library, from this directory:

```sh
python3 -I summarize.py
python3 -I test_summarize.py
```

The reducer reads plain or gzipped inputs, refuses ambiguous duplicates and duplicate cell identities,
and writes deterministic summaries. Timing validation requires two warm-ups and five measured runs per route;
only `phase=timed` enters each route's min/median/max. Ratios divide the INLINE_COUNT median by the COUNT median;
below 1 favors
INLINE_COUNT. Plan ratios are single executed-plan observations, not timing medians. Tests include regeneration
twice, warm-up exclusion, outliers, missing/duplicate runs, row-set disagreement and line-parity differences.
The large generated `line-parity.tsv` is a derived matrix, not an uncompressed raw log.

## Cell conventions

Functional columns are `vendor`, `hibernate`, `probe`, `case`, `status`, `value`, `sql`.
SQL comes from datasource-proxy, followed by JDBC setters after `||`. Tabs/newlines inside fields are flattened.
An `INSPECTOR_REJECTED:` statement was rejected before JDBC. Identify a cell by version + vendor + probe + case.
Timing cells additionally use `phase` + `run`; plan cells use `single|embedded` + `semi|join|distinct` cases.
Selected audit observations refer to the decompressed TCK TSV's row number, including its header.

- **PASS** in functional query cells asserts ids/totals and SQL, or DML counts and round-tripped mapped values.
  `B.1` asserts today's result: equality/EXACT/STARTS_WITH miss the converted prefix, while CONTAINS/ENDS_WITH
  happen to match `[1, 3]`. These are observations of current behavior, rather than proof the bug is fixed.
- **FAIL** is an observed counterexample to the probe's binding/count assertion. Functional JUnit remains green
  when these expected observations are captured. Actual inline TCK failures are preserved separately.
- **SIMULATED** marks absent fallback, no-binder refusal and hoist decision rules. Explicit proposed parameters
  execute real engine SQL and have PASS/FAIL cells; they do not establish that production uses that route.
  B.3's `converts` inspection is a stand-in around real binder/type observations, not the absent public method.
- **NOT_RUN** is reserved for unexecuted cells; no recorded functional cell has it. Performance/plans target
  PostgreSQL and MySQL only. H2 is outside that planned performance scope.
- TCK SQL PASS/FAIL means the broad literal detector did/did not find a candidate. It does not assert row
  correctness or classify every candidate as an input leak; see `value-path-audit.md`.

The MySQL image's default case-folding collation compares `A12` and `a12` equally. Exact equality therefore
expects `[1, 3]` on MySQL and `[1]` elsewhere; ignore-case expects `[1, 3]` everywhere. MySQL conflict updates
report two affected rows versus one on PostgreSQL/H2; probes check the final mapped value too.

Part A uses `SourceRenderer.window` for Criteria windows. Its CTE, recursive, set-operation, treated-root and
correlated-column comparisons use HQL and translated SQL. Hidden totals are read by tuple alias.
Part D executes Criteria EXISTS/entity equality, including both embedded-id columns, OR/LEFT and AND/INNER,
same-child conjunction, NOT, nested correlation, locking, selected-child exclusions, totals and root aggregates.
The decision to choose those routes remains simulated. `B.1` uses the actual ModelQuery executor; B.2/B.4 use
its binder. Part C combines isolated Criteria primitives with the actual TCK audit; inserts use InsertSupport.
C.4 checks exact default Criteria SQL equality without regenerating production snapshots.

## Reproduction

The spike module is excluded from the default reactor and requires `-Pspike-rfc0018`; deployment is skipped.
Offline reactor selection can be checked without running probes:

```sh
./mvnw -q -o validate -Pspike-rfc0018 -pl spike-rfc0018 -am
```

Recorded functional runs completed 12 JUnit invocations per line with no JUnit failures:

```sh
./mvnw -q verify -Pspike-rfc0018 -pl spike-rfc0018 -am \
  -Dtest=Rfc0018SpikeTest,Rfc0018CountJoinSpikeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q verify -Pspike-rfc0018 -pl spike-rfc0018 -am \
  -Dtest=Rfc0018SpikeTest,Rfc0018CountJoinSpikeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
```

The first Surefire execution starts a fresh functional TSV; `no-assertions` appends `/no_ea` cells.
Run versions sequentially. A new capture writes plain TSVs: archive the corresponding retained `.gz` files
before reducing a replacement run, since the reducer refuses two inputs with the same basename.
The separate opt-in TCK audit commands and expected failures are documented in
[value-path-audit.md](value-path-audit.md). It ran 1,481 tests per line: 551 PASS, 912 FAIL and 18 SKIP.
Functional totals per line are 948 PASS, 198 expected FAIL and 24 SIMULATED. Their 1,170 identities/statuses match.

Performance/plan runs are gated by `rfc0018.performance=true`; each line completed two vendor invocations,
336 PASS timing cells and 12 PASS plan cells. These runs were supplied by the calling session and were not
rerun for the write-up:

```sh
./mvnw -q verify -Pspike-rfc0018 -pl spike-rfc0018 -am -Dtest=Rfc0018PerformanceSpikeTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Drfc0018.performance=true
./mvnw -q verify -Pspike-rfc0018 -pl spike-rfc0018 -am -Dtest=Rfc0018PerformanceSpikeTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Drfc0018.performance=true \
  -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
```

The indexed fixture has 1M roots; selective means `id <= 10000` (1%), unselective means all 1M.
Flat results have 10,000/1M rows; grouping ten roots per bucket yields 1,000/100,000 groups, with HAVING.
Pages contain 50 rows: offset 0, the rounded midpoint, and total minus 50. Each route runs in its own read-only
repeatable-read transaction. COUNT executes the page and separate count inside that transaction; INLINE_COUNT
executes one page statement. The timer includes query construction/execution/materialization, excludes fixture
setup and transaction begin/commit, and checks exact page ids, payload, total and statement count afterwards.
Two warm-ups precede five measured runs per scenario/route, alternating route order.

Plans replay captured JDBC SQL/binds using PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` or MySQL JSON
EXPLAIN plus EXPLAIN ANALYZE. All routes return the same 10,000 unique roots; the raw join returns 20,000 rows.
The single/embedded fixtures each have 100,000 parents and 300,000 children (two matches per parent).

Machine caveat: **Testcontainers on a developer laptop (macOS)**. Compare ratios, not absolute latencies;
plan timings are one observation each and are not warmed benchmark medians. Do not infer a Hibernate-caused
optimizer change from different plans for identical SQL. DDL warnings in the supplied logs did not fail either run.
DML probes roll back, factories close and containers stop. H2 memory lasts until JVM exit. Functional query
timeouts are ten seconds; performance/plan queries allow 180 seconds. Container startup uses Testcontainers defaults.
