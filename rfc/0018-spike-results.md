# RFC 0018 acceptance spike: results

**A: keep, amend docs. B: amend the observations/compatibility text; keep the design. C: keep.
D: amend the performance advice; keep the correctness design.**
The user relaxed §Unresolved 1's gate: INLINE_COUNT must not lose to COUNT beyond run-to-run spread on first
pages with selective filters on both databases; a tie qualifies. Flat selective first pages tie on PostgreSQL
and MySQL on both lines, so Part A meets that gate and Q-29 closes as accepted. Flat median INLINE_COUNT / COUNT
ratios are **1.288 / 1.107** on MySQL and **0.961 / 0.988** on PostgreSQL (Hibernate 6.6 / 7.4); below 1 favors
INLINE_COUNT. Grouped first pages lose 14–32x; the user chooses the count mode, with performance advice added
to the docs. Part D also contradicts an assumption of cheap semi-join conversion: MySQL converts it,
but its observed plans cost more elapsed time than both comparison queries, especially for an embedded id.

Throwaway research on `spike/rfc-0018`, using one test-source module, `spike-rfc0018/`, on Hibernate 6.6.58.Final
and 7.4.11.Final (Jakarta Persistence 3.2.0 on 7.4). Tier 1 is H2, PostgreSQL 16 (`postgres:16-alpine`) and
MySQL 8.0.31 (`mysql:8.0.31`). Production `model-query-*/src/main` and committed SQL snapshots are unchanged.
The TCK additions are opt-in audit instrumentation. The future public APIs/executor routes do not exist here.

The [raw-data inventory and reproduction commands](0018-spike-data/README.md) preserve SQL, JDBC binds, ids,
totals, timings, executed plans and actual TCK failures. References below use these cell identities:

- **F**: `rfc0018-<version>.tsv.gz`, keyed by vendor + probe + case. `*` means all three vendors and both lines;
  `/main` observations also repeat as `/no_ea` in a second Surefire execution without assertions.
- **T**: `performance-<version>.tsv.gz`, keyed by vendor + `A.5` + `shape/filter/page/route` + phase + run.
- **P**: `plans-<version>.tsv`, keyed by vendor + `D.3` + `single|embedded/semi|join|distinct`.
- **V**: `value-path-observations.tsv`, keyed by path + vendor + version, pointing to the decompressed TCK audit row.

PASS query observations assert actual ids/totals and SQL; DML also checks affected rows and mapped round trips.
FAIL cells record expected counterexamples in current behavior, not failed spike JUnit invocations. SIMULATED
cells evaluate missing fallback/hoist/refusal rules. Proposed explicit parameters execute real engine SQL, but
do not prove production routing. B.3's converter inspection is also a stand-in for the absent `converts` API.
Actual TCK JUnit outcomes and broad inspector candidates are separate evidence, explained under Part C.

## Matrix

Functional cells per vendor and line, **PASS / FAIL / SIMULATED**. There are no NOT_RUN cells.
The [generated probe matrix](0018-spike-data/probe-matrix.tsv) separates functional, timing and plan sources;
the [part matrix](0018-spike-data/part-matrix.tsv) aggregates functional results only.

| Probe | H2 6.6 | H2 7.4 | PG 6.6 | PG 7.4 | MySQL 6.6 | MySQL 7.4 |
|---|---|---|---|---|---|---|
| A.1 window / alias / page | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 |
| A.2 grouped HAVING | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 |
| A.3 fallbacks | 0/4/2 | 0/4/2 | 0/4/2 | 0/4/2 | 0/4/2 | 0/4/2 |
| A.4 other sources / roots | 10/0/0 | 10/0/0 | 10/0/0 | 10/0/0 | 10/0/0 | 10/0/0 |
| B.1 today's converted filters | 20/0/0 | 20/0/0 | 20/0/0 | 20/0/0 | 20/0/0 | 20/0/0 |
| B.2 lower binder parameter | 24/0/0 | 24/0/0 | 24/0/0 | 24/0/0 | 24/0/0 | 24/0/0 |
| B.3 mapping types / conversion | 32/0/0 | 32/0/0 | 32/0/0 | 32/0/0 | 32/0/0 | 32/0/0 |
| B.4 inline placeholder | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 |
| C.1 value paths | 190/58/4 | 190/58/4 | 190/58/4 | 190/58/4 | 190/58/4 | 190/58/4 |
| C.2 padded IN | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 |
| C.3 computed cursor | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 |
| C.4 default SQL | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 |
| D.1 wrong results / correction | 4/4/0 | 4/4/0 | 4/4/0 | 4/4/0 | 4/4/0 | 4/4/0 |
| D.2 scratch paths / eligibility | 2/0/2 | 2/0/2 | 2/0/2 | 2/0/2 | 2/0/2 | 2/0/2 |
| D.3 embedded equality | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 |
| D.4 predicate shapes / locking | 14/0/0 | 14/0/0 | 14/0/0 | 14/0/0 | 14/0/0 | 14/0/0 |

Each line has 12 green functional JUnit invocations and 1,170 cells: **948 PASS, 198 FAIL, 24 SIMULATED**.
Per part/line: A 42/12/6; B 240/0/0; C 600/174/12; D 66/12/6.
The separately supplied gated runs have two green vendor invocations per line, **168 PASS timing cells and
six PASS plan cells per database per line**. H2 performance was outside the planned scope.

## Findings

### Part A — correct window rendering, accepted under the user's relaxed gate

1. **A.1:** `F:*/A.1/flat/first-page/hidden-alias/main` renders `count(1) over()` through the existing
   `SourceRenderer.window`, with offset/limit. Ids `[1,2]`, alias `mq_0total`, total 3 and `hasNext=true` agree.
2. **A.2:** `F:*/A.2/grouped/having/main` returns groups `[A,B]`, sums `[30,30]` and window totals `[2,2]`;
   the separate count is 2. The window counts groups surviving HAVING.
3. **A.3:** `F:*/A.3/customizer/{to-many,distinct}/main` both expose window total 3 against two unique roots
   `[1,2]` and COUNT 2. DISTINCT is applied too late to repair the window. These FAILs justify the proposed
   fallbacks; `fallback-routing/main` is SIMULATED, including past-end routing, rather than an executor test.
4. **A.4:** `F:*/A.4/{cte,recursive,set-operation,treated-root,correlated-column}/main` agrees with separate
   COUNT: ids `[1,2,3]` / total 3, except treated-root `[1,3]` / total 2. These are HQL/translated-SQL shape
   observations, not tests of future source/executor routing.
5. **A.5:** all `T:*/A.5/*/timed/{1..5}` cells validate ids, totals, payload and one versus two statements.
   The indexed fixture has 1M roots. Selective is the first 10,000 roots (1%); grouping ten roots per bucket
   yields 1,000 selective / 100,000 unselective groups. Each 50-row page uses offset 0, a rounded midpoint or
   total minus 50. COUNT's page and count run in one read-only repeatable-read transaction; INLINE_COUNT runs
   in its own transaction with the same isolation. These are direct HQL stand-ins for the absent mode.

Two warm-ups per route/scenario are excluded; five measured runs, with alternating route order, determine each
median. The timer includes query construction/execution/materialization, excluding transaction begin/commit
and fixture setup. Full measured min/median/max per route and ratios are in
[performance-medians.tsv](0018-spike-data/performance-medians.tsv).
The first-page selective ratios (**INLINE_COUNT median / COUNT median**) are:

| Shape | PG 6.6 | PG 7.4 | MySQL 6.6 | MySQL 7.4 |
|---|---:|---:|---:|---:|
| Flat | 0.961 | 0.988 | 1.288 | 1.107 |
| Grouped HAVING | 32.377 | 28.023 | 14.591 | 18.728 |

References: `T:{POSTGRESQL,MYSQL}/A.5/{flat,grouped}/selective/first/{INLINE_COUNT,COUNT}/timed/{1..5}`.
The flat selective first-page ranges overlap on both databases and both lines: a tie under the user's relaxed
gate, with no loss beyond run-to-run spread. MySQL 7.4's median loss is a marginal ~0.27 ms. The min/max columns in
[performance-medians.tsv](0018-spike-data/performance-medians.tsv) show:

| Database / line | INLINE_COUNT median [min, max], ms | COUNT median [min, max], ms |
|---|---:|---:|
| PostgreSQL 6.6 | 7.694 [7.577, 8.336] | 8.009 [7.661, 8.424] |
| PostgreSQL 7.4 | 7.729 [7.134, 12.003] | 7.819 [7.175, 8.807] |
| MySQL 6.6 | 3.649 [2.788, 6.384] | 2.834 [2.402, 4.708] |
| MySQL 7.4 | 2.820 [2.790, 2.983] | 2.548 [2.461, 4.927] |

Part A EXPLAIN remains skipped; the grouped first-page planner explanation below is inferred.
Grouped selective first pages lose 14–32x. **Inferred cause:** the INLINE SQL
is identical across pages, with different offset binds, yet PostgreSQL 6.6 first-page samples take
294–299.5 ms versus middle/deep medians of 7.7/8.2 ms; MySQL first pages take 90–99 ms versus ~4 ms
middle/deep. At offset 0 / limit 50, the planner likely walks the ORDER BY `perf_bucket` index
([fixture index](../spike-rfc0018/src/test/java/com/rey/modelquery/spike/rfc0018/Rfc0018PerformanceSpikeTest.java#L88))
expecting an early stop. `count(1) over ()` instead forces reading every group, traversing all 1M index entries
while filtering `id <= 10000`. This points to a fixture-dependent planner hazard from separate ordering and
filter keys, rather than the window's own cost. A real INLINE_COUNT would emit the same SQL. Check the real
first-page plan for this index/filter layout and account for this hazard when choosing the count mode.
The measured regression is retained, not discarded as an outlier or relabeled warm-up.

Performance advice must cover the slow shapes. Unselective flat first pages cost 18.61–20.32× on PostgreSQL and
8.14–8.34× on MySQL. Their middle/deep pages also lose (PG 2.35–2.46× / 1.33–1.37×; MySQL 7.76–7.84× /
7.11–7.18×). Grouped selective middle/deep pages improve, and grouped unselective middle/deep pages improve
(roughly 0.53–0.70× across the databases/lines), while grouped unselective first pages still lose.
INLINE_COUNT suits selective filters. With unselective filters, the window counts every matching row before
the page is cut, while COUNT's separate count can use a cheaper index-only plan: roughly 8–20x on first pages
and 2–8x on middle/deep flat pages (the exact deep PostgreSQL ratios above are lower, about 1.3–1.4x).
There is no automatic fallback for slow shapes: the user chooses the count mode. The rule-based correctness
fallbacks (including customizer distinct/to-many joins and treated roots) stay: they prevent wrong totals.

Machine caveat: **Testcontainers on a developer laptop (macOS)**. Ratios describe this fixture and measured
interval, not production latency promises. The supplied performance runs exited 0 on both lines; schema-drop
DDL warning stack traces did not fail the runs. No performance run was repeated for this write-up.

**Verdict A: keep, amend docs.** The user explicitly relaxed §Unresolved 1 from requiring a selective first-page
win to requiring no loss beyond run-to-run spread on both databases; a tie qualifies. Flat selective first pages
tie on PostgreSQL and MySQL on both lines, satisfying that gate. Close Q-29 as accepted when recording acceptance.
Retain Part A's mode, capability, design and correctness fallbacks; add the performance advice above, with count
mode selection left to the user and no automatic performance fallback. This is the user's acceptance decision,
not a new performance result; no probes were rerun.

### Part B — converted comparison works; qualify the original failure claim

1. **B.1:** `F:*/B.1/eqIgnoreCase/today/{bind,inline}/main` returns `[]` for `A12` under the `SKU:` converter.
   `likeIgnoreCase/today/{EXACT,STARTS_WITH}/{bind,inline}/main` also returns `[]`. Correcting the broader
   failure description: **CONTAINS and ENDS_WITH already return `[1,3]`**, because their leading `%` tolerates
   the stored prefix (`.../{CONTAINS,ENDS_WITH}/...`). They are accidental successes for this converter,
   not evidence that Hibernate ran the converter on the original untyped operand.
2. **B.2:** `F:*/B.2/lower-binder-parameter/{bind,inline}/main` and
   `likeIgnoreCase/{EXACT,CONTAINS,STARTS_WITH,ENDS_WITH}/{bind,inline}/main` return `[1,3]` with the binder's
   mapped parameter under SQL `lower(?)`. JDBC binds include the converted value; PostgreSQL accepts its type.
   `without-binder/plain-lower/...` preserves the plain-column branch.
3. **B.3:** `F:*/B.3/{sku,join,embedded,min,max,plain,enum,auto}/typesBy-converts/{bind,inline}/main`
   demonstrates the binder's actual mapping typing and successful ids/SQL. The converter inspection stand-in
   reports true for direct/join/embedded/min/max/auto-applied converted mappings, false for plain String and
   built-in enum conversion. It does not exercise the absent public `converts` method.
4. **B.4:** `F:*/B.4/inline-binder-placeholder/{bind,inline}/main` retains `?` and mapped JDBC binding even
   under inline handling (D-170); actual ids are checked.

MySQL's default case-folding collation makes the non-folded converted equality in B.3/B.4 return `[1,3]`,
versus `[1]` on H2/PostgreSQL. The plain-column control in B.2 differs similarly. This is a vendor observation,
not a Hibernate-line difference or a failure of `lower(?)`. Ignore-case results are `[1,3]` everywhere.

**Verdict B: amend the observations and compatibility wording; keep the design.** All four probes support the
mapped-parameter/lower rendering on both lines. Name both accidental suffix/contains successes, and replace
the tentative `eqIgnoreCase` claim with its observed miss. Keep the RFC's folding-property and missing-binder
limitations: encryption/encoding behavior and arbitrary converters were not proven correct by this fixture.

### Part C — real execution-family audit establishes the binding work

1. **C.1 / audit:** the [value-path audit](0018-spike-data/value-path-audit.md) runs the actual filter, paging,
   keyset, byKeys, fetch-plan, write, insert, conflict and source TCK families under inline handling with an
   execution-stack-aware marker inspector. Per line, authoritative JUnit outcomes are **551 PASS / 912 FAIL /
   18 SKIP = 1,481**, including three profile self-checks; 491 vendor callbacks each plus eight unparameterized
   callbacks. The inspector records **20,739 SQL FAIL candidates** on each line and **4,703 / 4,705 SQL PASS**
   observations on 6.6 / 7.4. These are deliberately exposed current failures, not a green production profile.
2. **Confirmed input paths:** `V:{comparison-eq,comparison-ne,comparison-gt,comparison-ge,comparison-lt,
   comparison-le,comparison-range,filter-membership,eq-ignore-case,like,ignore-case,subquery-filter,
   byKeys-chunk,fetch-key,keyset-UUID,converted-enum,converted-Boolean,update-assignment,delete-filter,
   insert-select-filter,conflict-condition,source-keyset}` contains supplied input literals, on all vendors
   and both lines. The [path matrix](0018-spike-data/value-path-matrix.tsv) gives every decompressed audit row;
   the 138 selected observations comprise these 22 failing paths plus `typed-insert-binds` succeeding.
   PAG-18 SQL additionally covers decimal, timestamp, binary and primary-key cursor prefixes.
3. **Do not overcount defects:** structural source tags, conflict-arbiter predicates needed for partial-index
   matching, LIKE escapes, user expression literals, discriminator restrictions and SQL NULL can trip the broad
   detector. Neither 20,739 SQL candidates nor 912 failing tests means that many independently confirmed leaks.
   Snapshot mismatches or an earlier assertion can stop a test before all row assertions. SQL PASS only means
   the detector found no candidate; successful exact-row claims come from isolated functional probes.
4. **C.1 / fixes as engine observations:** `F:*/C.1/*/valueOperand/{bind,inline}/main` uses proposed explicit
   parameters to validate primitive and mapped values (`sku/state/enabled/token/autoCode`), updates, source
   predicates, insert-select and delete. Insert-values and conflict parameter paths already bind; typed
   insert values round-trip their mappings. Insert-select assignment columns bind while its source predicate
   leaks. `no-binder/mapping-independent/...` binds a String; `no-binder/reject-enum/...` is SIMULATED and
   issues no SQL, so it is not a production MQ1502 test.
5. **C.2:** `F:*/C.2/in-33-parameters/padding/{bind,inline}/main` returns `[1,2,3]` with 64 placeholders for
   33 parameters, preserving Hibernate's padding under the setting.
6. **C.3:** `F:*/C.3/computed-keyset/declared-parameter/{bind,inline}/main` returns `[2,3]` with a declared
   String parameter where the binder does not type the computed expression (`typesBy=false`).
7. **C.4:** `F:*/C.4/default-sql-parity/main` checks exact default Criteria SQL equality and ids `[1]`.
   No committed snapshots were regenerated. This is the targeted default-setting control; it is not a new
   whole-project default TCK verification in this write-up session.

**Verdict C: keep.** The audit supplies the execution-family evidence required by C.4 and the concrete path
inventory for R-FLT-08 / AC-FLT-35. The proposed explicit-parameter routes work on both lines without losing
mapped types, and the default control stays equal. Future implementation must retain mapping-aware binding,
the narrow no-binder refusal and the ongoing TCK profile; arbitrary user expressions and absent APIs remain
outside observed coverage. The broad detector requires candidate classification, not an indiscriminate ban
on every SQL literal.

### Part D — correct semi-join semantics, qualified optimizer results

1. **D.1 / today:** `F:*/D.1/today/offset-last-page/main` has two unique roots `[1,2]`, COUNT/page total 2,
   but last-page ids `[1]`; joined window total is 3. `today/grouped-root-sum/main` produces sum 40 rather than
   root sum 30. These FAIL cells capture the current count/page/aggregate inconsistency.
   `semi-join/flat-count-window-last-page/main` fixes it to total 2 and last-page `[2]`;
   `semi-join/grouped-root-sum-and-window/main` returns one group, sum 30 and total 1.
2. **D.2:** `F:*/D.2/scratch-join-paths/OR-LEFT-AND-INNER/main` observes the same `children` join path with
   AND/INNER ids `[1,2]` and OR/LEFT ids `[1,2,3]`. Path identity must ignore join type.
   `probe-decision/path-not-JoinKey/main` is SIMULATED: an outer selected path prevents hoisting. The future
   Part D scratch-render decision API was not exercised.
3. **D.3 / equality:** the single-id child case in D.4 and
   `F:*/D.3/semi-join/embedded-id/main` execute Criteria EXISTS with `innerRoot = outerRoot`; embedded equality
   renders both id columns and returns `[1,2]`. Every `P:*/D.3/{single,embedded}/{semi,join,distinct}` also
   checks exact unique ids 1..10,000. Semi/distinct return 10,000 rows, while join returns 20,000.
4. **D.4:** `F:*/D.4/semi-join/{child,or-root,same-child,not-child,correlated,locked-read}/main` returns
   respectively `[1,2]`, `[1,2,3]`, `[1]`, `[1]`, `[1]`, `[1,2]`, with no outer join. These cover whole-predicate
   OR, same-child conjunction, NOT and nested correlation; the locked query executes on every vendor/line.
   `selected-to-many/outer-join-retained/main` keeps the selected join and pairs `[1:1,1:2,2:4]`.
   Excluded customizer/treated/other kept-join eligibility remains a design rule, not an implemented router.

The [plan summary](0018-spike-data/plan-summary.tsv) preserves strategies and elapsed-time ratios. Each ratio
below comes from **one executed EXPLAIN**, not repeated timing medians. The plain join has different row
multiplicity, while DISTINCT is a deduplicated comparison. The semi-join SQL keeps the RFC's second root.

| Database / line | Id | Observed semi-join strategy | Semi / join | Semi / DISTINCT |
|---|---|---|---:|---:|
| PostgreSQL 6.6 | single | Merge Semi Join, index-only scans | 0.319 | 0.288 |
| PostgreSQL 7.4 | single | Merge Semi Join, index-only scans | 0.352 | 0.316 |
| PostgreSQL 6.6 | embedded | Nested Loop Semi Join, index-only scans | 0.502 | 0.446 |
| PostgreSQL 7.4 | embedded | Nested Loop Semi Join, index-only scans | 0.504 | 0.469 |
| MySQL 6.6 | single | duplicate weedout, temporary table | 1.809 | 1.357 |
| MySQL 7.4 | single | duplicate weedout, temporary table | 1.836 | 1.438 |
| MySQL 6.6 | embedded | duplicate weedout, temporary table | 3.576 | 3.478 |
| MySQL 7.4 | embedded | Nested loop semijoin | 1.451 | 1.378 |

References: `P:{POSTGRESQL,MYSQL}/D.3/{single,embedded}/{semi,join,distinct}`, on each indicated version.
PostgreSQL converts both forms to semi-joins with index-only scans; the join and DISTINCT alternatives have
hash joins/scans and extra deduplication where requested. MySQL also transforms EXISTS, but single-id weedout
adds a second root lookup and temporary deduplication. The 6.6 embedded run scans all 300,000 child index rows;
the 7.4 embedded run uses an outer primary-key range, a second root lookup and a child-index semi-join.
Both MySQL embedded observations are slower than join and DISTINCT. Identical captured SQL produced those
different embedded plans; this does not establish that Hibernate caused the optimizer difference.

**Verdict D: amend performance advice; keep the correctness design.** The functional counterexamples and all
boolean/equality/locking probes support the rewrite. §Unresolved 7 explicitly says to document a slower common
shape, rather than restore the duplicating join or add an unsupported unique-per-root escape hatch. State the
MySQL single/embedded regressions and second-root/weedout work, qualify any implication that semi-join
conversion guarantees speed, and advise checking the application's real plans and indexes. Single-plan laptop
ratios are a warning and shape comparison, not a stable performance bound.

### Line parity

[line-parity.tsv](0018-spike-data/line-parity.tsv) contains all 3,068 paired identities: 1,170 functional,
336 timing samples including warm-up, 12 plans, 1,481 JUnit results and 69 selected value paths. **Every status
matches.** Equality of status does not imply equality of SQL, timing or optimizer strategy:

- Functional observations differ in two MySQL C.1 Boolean failure messages (`mapping/enabled/today/inline`,
  main/no_ea): 6.6 emits `=1`, 7.4 emits `=true`. Those selects plus the equivalent update cells account for
  four SQL differences. The other four SQL differences are PostgreSQL/MySQL D.4 locking SQL: 7.4 adds
  `of p1_0`; ids/statuses agree. The total is eight SQL differences, with 1,168/1,170 equal observation texts.
- All 336 paired timing samples have equal ids/totals and SQL; elapsed times differ and are intentionally
  excluded from semantic parity. All 12 plan pairs have equal row counts/unique ids and SQL. Eleven strategy
  summaries match; the MySQL embedded semi-join strategy differs as described above.
- All 1,481 JUnit statuses agree; 27 failure texts differ (24 RecursiveSourceExecutionTest, three BulkWriteTest).
  Raw SQL PASS counts are 4,703 versus 4,705, with one extra H2 SQL observation each in WRT-25 and WRT-43 on 7.4;
  FAIL candidates stay 20,739. No claim of byte-identical audit logs follows from equal failure totals.
- All 69 selected path pairs have equal statuses, test ids and SQL. Case-folding collation is a MySQL-versus-other
  vendors difference shared by both lines, not a line-parity exception.

## Consequences for the RFC

Line ranges refer to `rfc/0018-inline-count-and-converted-ignore-case.md` as inspected for this spike.
The RFC itself is unchanged; recording the user's acceptance decision and spec edits belongs to the calling
session's gate.

- **A — keep, amend docs:** retain 125–259's mode/capability/design, compatibility 487–488 and dependent
  references.
  Record the user's relaxed gate at 256–259 and 584–586, closing Q-29 as accepted. Add performance advice to
  §A's API/cost text at 135–137 and 146–149, motivation 75–77, compatibility 487–488 and the docs/site advice
  named at 29–30: selective filters suit INLINE_COUNT; unselective flat pages can lose roughly 8–20x first and
  2–8x middle/deep (see exact ratios above); grouped first pages lost 14–32x here, with an inferred planner cause,
  so check the real plan; grouped middle/deep pages improved ~0.53–0.70x. The user chooses the count mode.
  Keep 186–207's correctness fallbacks; add no automatic performance fallback. Part A EXPLAIN stays skipped.
- **B — amend; keep design:** update 81–84 with the confirmed equality miss and both CONTAINS/ENDS_WITH
  successes; qualify 490–494's implication that all converted patterns previously missed. Keep the rendering
  and limitations at 283–310.
- **C — keep:** carry the observed inventory into the planned R-FLT-08 / AC-FLT-35 work at 342–379 and 381–397;
  distinguish input leaks from structural literals. No new design amendment is required.
- **D — amend; keep design:** add the MySQL caveat to effects/advice 450–464 and compatibility 505–518;
  record the 480–483 plan observations against 595–597's document-rather-than-revert criterion.

No production AC is claimed complete by this throwaway spike. Evidence supports proposed AC-FLT-22/35/36 and
AC-EXE-22/23/24 implementation work; absent APIs, refusal/eligibility routing and exhaustive excluded shapes
remain for those slices. No snapshots or spec decision ids were changed, and no commit was made.
