# RFC 0015 acceptance spike: results

Throwaway research code on local branch `spike/rfc-0015`; no product engine or public SPI changes. The same
Rfc15SpikeTest.java source compiles on Hibernate 6.6.58.Final and 7.4.11.Final (Jakarta Persistence 3.2.0). Every
PASS asserts fixture values, selected ids, counts, or complete export membership.

Targets: H2 embedded (bundled with the TCK); PostgreSQL 16 (`postgres:16-alpine`); MySQL 8.0.31 (`mysql:8.0.31`),
with `ONLY_FULL_GROUP_BY` verified. Probes 1–3 and 5–7 use Hibernate Criteria; probe 4 sends equivalent native SQL
through Hibernate to compare rendering cost without including Criteria construction time.

Raw final observations are in `0015-spike-data/full-run-<version>.tsv`: SPIKE, vendor, Hibernate version, probe,
case, status, elapsed time, asserted values/error, SQL. SQL is captured by StatementInspector without truncation.
Translation failures have an empty SQL field because no SQL reached JDBC. `plans-<version>.txt` contains statement
times and H2 EXPLAIN / PostgreSQL and MySQL EXPLAIN ANALYZE. Timed-out queries retain plain EXPLAIN and the
cancellation error; no actual plan is claimed for those executions.

Each final dataset has 837 cases (279 per vendor). Initial full runs used `rank` as a window-result alias; MySQL
rejected it as a reserved word. The harness now uses `row_pos`. Probe 6 was rerun on all vendors on each line, and
those 540 records replace probe 6 in each final dataset (also kept as `probe6-final-<version>.tsv`). The initial
runs, checkpoint and cost-iteration artifacts stay on the local spike branch only.

## Matrix

P = all named cases PASS. F = at least one named case FAIL, detailed below. P* = PASS with a rendering or cost
finding. Timeouts are FAIL observations, even though Maven succeeds: this is a research harness which records
provider failures instead of failing its parameterized JUnit invocation.

| Probe / cases per vendor and line | H2 6.6 | H2 7.4 | PG 6.6 | PG 7.4 | MySQL 6.6 | MySQL 7.4 |
|---|---|---|---|---|---|---|
| 1 Scalar terminals (19) | P | P | P | P | P | P |
| 2 First, both forms/directions/positions (12) | P* | P* | P* | P* | P* | P* |
| 3 Lifted LEFT, bare reference (1) | F | F | F | F | F | F |
| 3 Lifted LEFT, correlate(join) (1) | P* | P* | P* | P* | P* | P* |
| 3 CTE root, bare / correlate(root) (2) | P* | P* | P* | P* | P* | P* |
| 4 Indexed cost (30) | P* | P* | P* | P* | P* | P* |
| 4 Unindexed pages 20 / 100 (12) | P* | P* | P* | P* | P* | P* |
| 4 Unindexed scalar export / order / where (6) | F | F | F | F | F | F |
| 4 Unindexed derived / CTE export / order / where (12) | F | F | P* | P* | P* | P* |
| 5 Count rewrite (3) | P | P | P | P | P | P |
| 6 Top lookup / returned handle (120) | P* | P* | P* | P* | P* | P* |
| 6 Nested WITH, plain chains (4) | P* | P* | P* | P* | P* | P* |
| 6 Nested WITH, walks + top checks (56) | F | F | F | F | F | F |
| 7 Shared source / API and value parity | P | P | P* | P* | P | P |

6.6.58.Final: H2: 204 PASS / 75 FAIL; PG 16: 216 PASS / 63 FAIL; MySQL 8.0.31: 216 PASS / 63 FAIL.

7.4.11.Final: H2: 204 PASS / 75 FAIL; PG 16: 216 PASS / 63 FAIL; MySQL 8.0.31: 216 PASS / 63 FAIL.

## Notes per probe

1. All 19 scalar cases pass on every target and line: count(*), count(nullable column), exists as CASE, min, max and
sum in select, where and offset ordering, plus sharing one Criteria expression in a statement and a grouped outer
query with a lifted group key. Fixtures distinguish an empty child set, all-NULL quantities and non-NULL quantities.
MySQL confirms ONLY_FULL_GROUP_BY before the grouped assertion.

2. All 12 first-row cases return the same rows across vendors and lines. With id ascending as tie-breaker, order 1
selects note-102 descending and note-104 ascending; order 2 selects note-202 and order 4 selects its only note,
note-401, whose timestamp is NULL. The uncorrelated first is note-202 descending and note-104 ascending. Select,
where and offset-order positions assert exact ids and values. Criteria requests NullPrecedence.LAST in both
directions. Hibernate omits an explicit NULLS LAST token when the dialect has that native default and emulates it on
MySQL where required; the chosen row is identical.

3. The bare lifted outer reference FAIL is retained on all six targets. Expected counts by order are [4, 2, 0, 0,
4]; actual counts are [4, 2, 0, 4, 4]. Hibernate reconstructs customer/region joins inside the subquery and loses
the outer region enabled ON restriction. The disabled region of order 4 is consequently counted. Explicit
Subquery.correlate(outerRegionJoin) returns the expected counts, refers to the outer alias and adds no
customer/region FROM item inside the subquery. Representative exact SQL tokens follow; raw TSVs retain each
dialect’s complete SQL on one line.

H2 / 6.6, `lifted-left-outer-reference`, FAIL:

```sql
select so1_0.id,(select count(sol1_0.id) from spike_order_line sol1_0,spike_customer c2_0 join spike_region r2_0 on
r2_0.id=c2_0.region_id where c2_0.id=so1_0.customer_id and sol1_0.status=r2_0.code) from spike_order so1_0 left join
spike_customer c1_0 on c1_0.id=so1_0.customer_id left join spike_region r1_0 on r1_0.id=c1_0.region_id and
r1_0.enabled order by 1
```

H2 / 6.6, `lifted-left-correlate-join`, PASS:

```sql
select so1_0.id,(select count(sol1_0.id) from spike_order_line sol1_0 where sol1_0.status=r1_0.code) from
spike_order so1_0 left join spike_customer c1_0 on c1_0.id=so1_0.customer_id left join spike_region r1_0 on
r1_0.id=c1_0.region_id and r1_0.enabled order by 1
```

Both CTE-root correlation forms PASS on all vendors and both lines: bare outer reference and
Subquery.correlate(cteRoot) each return [3, 1, 0, 1, 1]. H2 may inline the simple CTE; PostgreSQL and MySQL also
exercise a named CTE root. These findings concern a lifted to-one path and a CTE root; collection correlations
remain outside this probe and retain MQ1311.

4. Each vendor/line has 60 cost cases: 10k and 100k parents, exactly 10 children on average (9/10/11 by parent id),
index absent/present, scalar/derived/CTE, page 20/page 100/full offset export/correlated-order offset export/filter
on the correlated count. Full exports use 10k-row offset pages. Assertions verify every count, row total, id
membership without duplicates, filter membership and ordering. Scratch tables have no declared FK so MySQL cannot
silently index the unindexed case; setup drops stale tables and finally drops both scratch tables. PostgreSQL
DDL/index changes are committed before measurement.

Each statement has a 10-second query timeout (`-Dspike.costTimeoutSeconds` overrides it). Timeouts produce FAIL rows
and censored measurements, not successful value checks. EXPLAIN ANALYZE is saved for successful PostgreSQL/MySQL
samples, including first and last export pages; canceled executions retain plain EXPLAIN. Query timings below
exclude EXPLAIN and setup; export times sum all pages. Zero milliseconds means below timer resolution. This is one
ordered sample per case and line, not a randomized benchmark. Cost measures count(*) only; terminal semantic probes
do not establish the cost of other terminals. 1M parents is implemented behind `-Dspike.big=true` and was not run.

100k parents, query milliseconds (`>10,000` = statement canceled; idx = child FK-column index):

| Line | Vendor | idx | Form | p20 | p100 | export | ordered | where |
|---|---|---|---|---|---|---|---|---|
| 6.6 | H2 | no | scalar | 633 | 2618 | >10,000 | >10,000 | >10,000 |
| 6.6 | H2 | no | derived | 582 | 2852 | >10,000 | >10,000 | >10,000 |
| 6.6 | H2 | no | cte | 569 | 2932 | >10,000 | >10,000 | >10,000 |
| 6.6 | H2 | yes | scalar | 0 | 0 | 142 | 1360 | 156 |
| 6.6 | H2 | yes | derived | 0 | 0 | 893 | 1712 | 169 |
| 6.6 | H2 | yes | cte | 0 | 0 | 870 | 1686 | 168 |
| 6.6 | PG 16 | no | scalar | 537 | 1823 | >10,000 | >10,000 | >10,000 |
| 6.6 | PG 16 | no | derived | 93 | 49 | 644 | 935 | 98 |
| 6.6 | PG 16 | no | cte | 43 | 42 | 619 | 936 | 141 |
| 6.6 | PG 16 | yes | scalar | 3 | 1 | 870 | 1802 | 200 |
| 6.6 | PG 16 | yes | derived | 1 | 1 | 594 | 1263 | 124 |
| 6.6 | PG 16 | yes | cte | 2 | 1 | 599 | 1270 | 126 |
| 6.6 | MySQL 8.0.31 | no | scalar | 1160 | 5742 | >10,000 | >10,000 | >10,000 |
| 6.6 | MySQL 8.0.31 | no | derived | 177 | 166 | 1976 | 2331 | 227 |
| 6.6 | MySQL 8.0.31 | no | cte | 170 | 165 | 2022 | 2330 | 220 |
| 6.6 | MySQL 8.0.31 | yes | scalar | 2 | 1 | 338 | 2130 | 256 |
| 6.6 | MySQL 8.0.31 | yes | derived | 140 | 105 | 1344 | 1642 | 160 |
| 6.6 | MySQL 8.0.31 | yes | cte | 109 | 105 | 1330 | 1650 | 150 |
| 7.4 | H2 | no | scalar | 965 | 2736 | >10,000 | >10,000 | >10,000 |
| 7.4 | H2 | no | derived | 562 | 2773 | >10,000 | >10,000 | >10,000 |
| 7.4 | H2 | no | cte | 570 | 2768 | >10,000 | >10,000 | >10,000 |
| 7.4 | H2 | yes | scalar | 0 | 0 | 151 | 1228 | 163 |
| 7.4 | H2 | yes | derived | 0 | 0 | 818 | 1607 | 158 |
| 7.4 | H2 | yes | cte | 0 | 0 | 930 | 1629 | 157 |
| 7.4 | PG 16 | no | scalar | 530 | 1803 | >10,000 | >10,000 | >10,000 |
| 7.4 | PG 16 | no | derived | 51 | 43 | 591 | 891 | 84 |
| 7.4 | PG 16 | no | cte | 40 | 44 | 605 | 869 | 83 |
| 7.4 | PG 16 | yes | scalar | 2 | 1 | 895 | 1671 | 209 |
| 7.4 | PG 16 | yes | derived | 1 | 1 | 583 | 1274 | 126 |
| 7.4 | PG 16 | yes | cte | 1 | 2 | 597 | 1289 | 119 |
| 7.4 | MySQL 8.0.31 | no | scalar | 1157 | 5743 | >10,000 | >10,000 | >10,000 |
| 7.4 | MySQL 8.0.31 | no | derived | 174 | 164 | 1975 | 2208 | 227 |
| 7.4 | MySQL 8.0.31 | no | cte | 170 | 195 | 1941 | 2209 | 217 |
| 7.4 | MySQL 8.0.31 | yes | scalar | 2 | 1 | 307 | 2059 | 245 |
| 7.4 | MySQL 8.0.31 | yes | derived | 109 | 103 | 1326 | 1576 | 155 |
| 7.4 | MySQL 8.0.31 | yes | cte | 106 | 104 | 1315 | 1608 | 155 |

H2: all 18 unindexed export/order/filter executions time out on each line, across all three renderings. Its
unindexed pages pass. The grouped joins do not establish a wide win; the indexed scalar export is faster than either
joined form in these samples. PostgreSQL and MySQL: only the six unindexed scalar export/order/filter cases time out
on each line. Grouped derived and CTE forms finish and validate values. Both show a wide win for unindexed
filtering; PostgreSQL also completes unindexed exports in under a second of query time. MySQL indexed small pages
strongly favor scalar evaluation. The plans and the separate indexed rows matter: no unconditional joined-form win
is established.

5. All three count rewrites pass everywhere: selection-only correlated expressions disappear from count (5 parents),
a correlated predicate remains (4 parents), and the lifted INNER customer/region join with its enabled ON condition
remains (3 parents). The count statement drops select items and ordering before execution while preserving
row-count-changing joins. Assertions include both counts and retained SQL joins.

6. TOP_LOOKUP builds the next body as its own Criteria and selects from top.getCteCriteria(inner.getName());
RETURNED_HANDLE selects from the JpaCteCriteria returned by top.with(...). Each route passes all 60 cases on every
vendor and line. NESTED_WITH registers the inner CTE inside the next body, then registers that body on the top
statement: its four plain/grouped/union-window/three-level chains pass, but its 56 walk/check cases fail during SQM
translation, before SQL, with `Could not find CTE for name 'walk_inner'!`. The top scalar checks cannot resolve a
declaration nested inside the next body. This scope failure is identical across vendors and lines.

The union chain combines orders and refunds, partitions a row_number window by owner, and verifies the two newest
activities per owner: ids [4, 12, 11, 5], kinds [ORDER, REFUND, REFUND, ORDER], owners [0, 0, 1, 1] and positions
[1, 2, 1, 2]. The reserved alias correction changes the SQL identifier only; none of these expected values was
relaxed.

Walk cases cover clean traversal, depth sentinel, cycle, diamond duplicate and overlapping seeds. The next grouped
body applies level <= maxDepth AND NOT mark before counting per seed. Depth, cycle and duplicate checks are scalar
subselects over walk_inner selected by the top statement, never aggregates over the filtered body. The 11 statement
shapes cover content, offset, count, keyset, byKeys, both primaryKeyFirst phases, one, offset export, an outer
HAVING which drops every group, and count over zero groups. Empty content has no row carrying checks; count over
zero groups still returns 0 plus the inner error key. Exact expected keys are depth=4, cycle=100, diamond
duplicate=203 and overlapping-seed duplicate=2.

7. Both dependency profiles compile the same source. All 657 non-cost cases have the same status on both lines, and
every passing case has identical asserted output (`line-parity.tsv`). The bare lifted-reference bug and nested-check
scope failure also reproduce on both. Cost cases validate the same values whenever they finish; timings are
observations, not a line-parity equality assertion.

## What this means for the RFC

§6 rendering choice: PostgreSQL and MySQL both have a wide grouped-join win for unindexed filtering (scalar exceeds
10 seconds while derived/CTE finish in hundreds of milliseconds), with unindexed exports also improving. H2 shows no
wide joined-form win: all unindexed export/filter forms time out, and indexed scalar export wins. Preserve scalar
rendering for indexed small pages; the pure-equality grouped lowering is worth retaining for large exports/filters,
especially on PostgreSQL and MySQL. These single-machine samples do not establish a universal crossover or justify
always joining; cost and index availability must inform any later VendorProfile threshold.

§4 chained-CTE SPI: both Hibernate lines support an independently built next body selecting a sibling CTE registered
on the top statement, through either top.getCteCriteria(name) or the handle returned by top.with(...), on all three
vendors. Pass the top-statement context and the registered inner handle to the chained-body renderer, register the
walk there first, then attach the next body. Nested WITH works for plain chains but cannot support the required
top-level scalar walk checks. Rebuild the handles within each content/count/key-phase/export statement so every
check shares that statement’s inner CTE.

§3 source-root correlation: allow correlation from a source model’s CTE root on both lines; both correlate(root) and
outer-root reference pass all targets. For a lifted to-one column the engine must render explicit
Subquery.correlate(the outer join), preserving its alias and ON condition. A bare reference is unsafe on every
target and remains a recorded FAIL. The collection-path MQ1311 restriction is unchanged by this evidence.

§2 null precedence: the same row is selected on all vendors and both lines, for correlated and uncorrelated forms,
ascending and descending timestamp order, ties and NULL timestamps. Request NullPrecedence.LAST explicitly and
append the id tie-breaker; Hibernate renders native/default or emulated SQL as appropriate. SQL need not contain the
literal NULLS LAST token when that precedence is already the dialect default.

Line difference (probe 7): the Criteria APIs and returned values match between 6.6.58.Final and 7.4.11.Final. The
same bare-reference and nested-scope failures reproduce, and the same cost categories are censored. PostgreSQL cost
cancellation wrappers differ: all six use QueryTimeoutException on 6.6 and PersistenceException on 7.4, with the
same root PSQLException reporting user-request cancellation. No version-specific Criteria rendering branch is
supported; cancellation handling must inspect the root cause rather than depend on the outer exception wrapper.

## Commands and verification

All commands ran from the spike worktree via /bin/bash, with no install, deploy or full-project verify. The full
class ran once on each line; only probe 6 was repeated after the alias correction. Maven exits 0 while the TSV
retains research FAIL observations.

```sh
./mvnw -q -pl model-query-tck -am verify -Dtest=Rfc15SpikeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl model-query-tck -am verify -Dtest=Rfc15SpikeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
```

Additional runs: checkpoint 2 used -Dspike.vendors=h2 -Dspike.probes=6 on each line; cost iteration used
-Dspike.vendors=h2 -Dspike.probes=4,7 on 6.6. Final alias verification used -Dspike.probes=6 on each line, covering
all vendors. The complete Surefire summaries are saved verbatim in the four files below; all four invocations report
3 tests, 0 failures, 0 errors and 0 skipped.

- [verify-6.6.58.Final.txt](0015-spike-data/verify-6.6.58.Final.txt)
- [verify-7.4.11.Final.txt](0015-spike-data/verify-7.4.11.Final.txt)
- [verify-probe6-6.6.58.Final.txt](0015-spike-data/verify-probe6-6.6.58.Final.txt)
- [verify-probe6-7.4.11.Final.txt](0015-spike-data/verify-probe6-7.4.11.Final.txt)

## Left for later

The RFC/engine owners implement the SPI and rendering choices; this branch only contains research. The optional
1M-parent run and repeated randomized performance measurements are not part of this default run. No SQL product
snapshots or specification ACs were changed. No unresolved question blocks this spike.

