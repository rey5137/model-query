# RFC 0020 acceptance spike: results

**A: keep the join, amend the cost text and H2. B: keep `firstBy`, amend its rendering (no `arrayGet`; a pattern
with no implied Java result type). C: keep. D: keep candidate 2, amend the integer casts and the scale text; the
scale bound is open. E: keep the value form; the two-operand OR form gets an index plan only in a plain filter on
PostgreSQL and loses to the native form on MySQL and H2. F: keep; the portable `rowCount` default is open.**
PostgreSQL inlines the joined CTE (its plan and buffers equal the derived table's) and drops an unreferenced LEFT join
from the count. Over the RFC's "unreconciled days" filter the join reads 335 buffers against the correlated form's
4,013 with an index and 474,764 without, and wins every page and export; only the indexed count is a tie. MySQL
materializes both forms identically and keeps the join in every count. H2 never sees a `with`: Hibernate inlines the
joined CTE as a derived table and H2 re-runs it once per outer row, so a joined ranked source (B.2) takes 37 to 46 s
for 365 days. Hibernate's `arrayAgg` + `arrayGet` is unusable on PostgreSQL (no parentheses) and throws on MySQL; a
`(?1)[1]` pattern works on PostgreSQL and H2, and keeps the converter unless the call implies a Java result type.
MySQL silently clamps `round` to 30 decimals. `count(*)` saves 8 to 12% on PostgreSQL and on MySQL's page total, with
identical plans and rows, but the portable default `count(1)` is 7 to 8% slower than `count(id)` on PostgreSQL's page
total. Every cell has the same status on both lines.

Throwaway research on `spike/rfc-0020`, one test-source module `spike-rfc0020/` (Maven profile `spike-rfc0020`), on
Hibernate 6.6.58.Final and 7.4.11.Final (Jakarta Persistence 3.2.0 on 7.4), against H2, PostgreSQL 16
(`postgres:16-alpine`) and MySQL 8.0.31. Production `model-query-*/src/main` and committed SQL snapshots are
unchanged; the probes call Hibernate's Criteria API directly, since the proposed API does not exist.

The [raw-data inventory and reproduction commands](0020-spike-data/README.md) hold the SQL, binds, values, timings and
plans. References below use:

- **F**: `rfc0020-<line>.tsv`, keyed `vendor | probe | case`. `*` means all three vendors and both lines.
- **T**: `rfc0020-timings-<line>.tsv`, keyed `vendor | cell | case | index`; the full plan is under the same header in
  `plans/<line>/<vendor>.txt`. Medians of 7 alternating runs in ms, read through JDBC with every column fetched.

Entry points the probes stand in for: `Agg.count(TableField)` `model-query-core/.../Agg.java:41-51` (`count(1)` for a
source, `count(t.id)` otherwise), `Agg.count(ScalarField)` `Agg.java:133-137`, `Correlated.count`
`Correlated.java:21`, the source CTE through `JpaCteCriteria` in `HibernateSourceRenderer.java:20-29`, `Filters`
`Filters.java:31`, `VendorProfile` `jpa/spi/VendorProfile.java:20`. `AggregateRenderer` (R-VND-30) does not exist
yet; the probes are what its methods would call.

## Matrix

PASS / FAIL / NOT_RUN per probe group (from `summarize.py matrix`). A FAIL is data: a planned refusal, a vendor
limit, or a rendering the RFC must not adopt.

| Probe | H2 6.6 | H2 7.4 | PG 6.6 | PG 7.4 | MySQL 6.6 | MySQL 7.4 |
|---|---|---|---|---|---|---|
| A.2 joined source, every statement kind | 31/0/0 | 31/0/0 | 31/0/0 | 31/0/0 | 31/0/0 | 31/0/0 |
| B.1 first-by over `array_agg` | 18/2/0 | 18/2/0 | 9/11/0 | 9/11/0 | 0/20/0 | 0/20/0 |
| B.2 ranked source | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 | 4/0/0 |
| C.1 filtered aggregates | 19/0/0 | 19/0/0 | 19/0/0 | 19/0/0 | 19/0/0 | 19/0/0 |
| C.2 filter over an optional path | 3/2/0 | 3/2/0 | 3/2/0 | 3/2/0 | 3/2/0 | 3/2/0 |
| D.1 exact average | 40/24/0 | 40/24/0 | 40/24/0 | 40/24/0 | 42/22/0 | 42/22/0 |
| D.2 rounding and scales | 21/0/0 | 21/0/0 | 21/0/0 | 21/0/0 | 13/8/0 | 13/8/0 |
| E.1 null-safe equality | 30/0/0 | 30/0/0 | 30/0/0 | 30/0/0 | 30/0/0 | 30/0/0 |
| F.1 row count | 26/0/0 | 26/0/0 | 26/0/0 | 26/0/0 | 26/0/0 | 26/0/0 |
| **total** | 192/28/0 | 192/28/0 | 183/37/0 | 183/37/0 | 168/52/0 | 168/52/0 |

Timed cells (T): 48 per vendor and line, plus 9 PostgreSQL re-runs under `enable_seqscan = off`; every one PASS except
B.1 on MySQL (no statement, all four) and B.1 `arrayGet` on PostgreSQL (no statement, two), recorded `NOT_RUN` because
their functional cells failed.

## Findings

### Part A — the join is right on every vendor; its cost is not the RFC's on MySQL and H2

**Rendering (A.2, A.5).** All 31 A.2 cells pass on every vendor and line (F `* | A.2/LEFT|A.2/INNER | …`): content
page, count, both primary-key-first steps, export page, `byKeys` chunk and a fetch plan's parent read over LEFT and
INNER, an `on` over a to-one path, two sources, one model joined twice, a source over a source, a root CTE next to a
joined one, and the derived overload. The fetch-plan cell reads the parents through the join, then their children
(sales) keyed by the parents' dates without it, and finds 1,000 children per matched parent and none for an
unmatched one (F `* | A.2/LEFT|A.2/INNER | fetch-plan parent read …`). `JpaFrom#join(JpaCteCriteria, SqmJoinType)`
returns `JpaJoinedFrom` on 6.6 (not a `jakarta Join`) and `JpaJoin` on 7.4; `join(Subquery, SqmJoinType)` returns
`JpaDerivedJoin` on both, a `Join` only on 7.4; the runtime type is `SqmCteJoin` (F `* | A.2/types`). Both have
`on(Predicate...)`, so one reflective hook serves both lines. `count(x.mq_businessDate)` counts 0 for an unmatched
LEFT row where `count(1)` counts 1 (F `* | A.2/count-unmatched`).

**CTE vs derived table.** PostgreSQL and MySQL get `with cte0 (…) … left join cte0`; H2 gets no `with` at all:
Hibernate renders the joined CTE as a derived table, the same SQL as the derived overload (T `H2 | A.1/* | b cte`).

**Timings (A.1).** "Unreconciled days" keeps the days whose `counted_cash` is distinct from the day's cash sum, in the
RFC's two-valued `distinctFromColumn` form (`not((a = b and a is not null and b is not null) or (a is null and b is
null))`), on the page, the export and the count; `count unfiltered` drops the filter, so the join is unreferenced.
The filter keeps all 365 days (a day with no sales has a NULL sum; no sales day reconciles), so it costs its
evaluation, not a selection. In the correlated form it evaluates the cash sub-select again: a sum in the select list
and one or two in the `where` (T `POSTGRESQL | A.1/page of 20 | a correlated` plan: `SubPlan 1` to `5`).

| A.1, median ms (6.6 / 7.4) | PG correlated | PG CTE | PG derived | MySQL correlated | MySQL CTE | MySQL derived | H2 correlated | H2 CTE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| page of 20, no index | 49.0 / 48.1 | 3.7 / 3.6 | 3.7 / 3.5 | 2,083 / 2,026 | 11.6 / 11.4 | 11.1 / 11.0 | 72 / 70 | 18 / 17 |
| full export, no index | 1,473 / 821 | 8.3 / 4.2 | 7.6 / 3.8 | 4,149 / 3,921 | 12.4 / 12.2 | 12.7 / 11.8 | 918 / 905 | 272 / 267 |
| count, no index | 484 / 416 | 3.7 / 3.2 | 3.3 / 3.1 | 2,096 / 1,906 | 11.2 / 10.9 | 10.7 / 10.5 | 328 / 332 | 263 / 270 |
| count unfiltered, no index | 0.39 / 0.32 | 0.39 / 0.38 | 0.34 / 0.33 | 1.4 / 0.8 | 10.6 / 10.2 | 10.5 / 10.1 | 0.05 / 0.04 | 261 / 270 |
| page of 20, date index | 5.2 / 4.4 | 4.2 / 3.4 | 3.8 / 3.5 | 48.0 / 46.7 | 22.0 / 21.7 | 22.8 / 21.9 | 13.9 / 12.8 | 5.3 / 4.5 |
| full export, date index | 7.9 / 6.8 | 4.8 / 3.6 | 4.4 / 3.6 | 59.0 / 57.4 | 22.8 / 22.2 | 22.8 / 21.9 | 19.5 / 18.9 | 6.7 / 6.8 |
| count, date index | 2.7 / 2.6 | 2.8 / 2.9 | 2.9 / 2.8 | 20.3 / 20.1 | 20.8 / 20.8 | 20.7 / 20.7 | 10.0 / 9.5 | 6.6 / 6.7 |
| count unfiltered, date index | 0.21 / 0.31 | 0.21 / 0.32 | 0.22 / 0.30 | 0.96 / 0.96 | 21.0 / 21.4 | 20.7 / 21.5 | 0.005 / 0.004 | 6.6 / 6.7 |

- **PostgreSQL inlines the CTE.** No `CTE Scan` in any plan; CTE and derived plans are node for node the same
  (`Hash Left Join > … HashAggregate > Seq Scan`, 334 or 335 buffers) on both lines (T `POSTGRESQL | A.1/* | b cte|c
  derived`). PostgreSQL 6.6's no-index export medians (8.3, 7.6 ms) are run-to-run spread: the plans and buffers equal
  7.4's.
- **PostgreSQL removes the unreferenced LEFT join from the count.** The unfiltered count's plan is `Aggregate > Seq
  Scan on business_day`, 3 buffers, for both forms with and without the index (T `POSTGRESQL | A.1/count unfiltered |
  b cte|c derived`). Once the filter reads the joined column, the count runs the join in full: 3.2 ms against 0.38
  (T `POSTGRESQL | A.1/count | b cte`, 335 buffers).
- **MySQL materializes, never merges**, the grouped CTE (`Materialize CTE cte0`) and the derived table
  (`Materialize`), then probes an `<auto_key0>`; same cost (T `MYSQL | A.1/full export`). It **keeps the join in every
  count**, filtered or not: about 10 ms without the index against 0.8 ms for the plain count (T `MYSQL | A.1/count
  unfiltered`). With the date index it picks an index-ordered `Group aggregate` over `ix_sale_business_date` and
  doubles every joined statement (11 → 22 ms).
- **H2 re-runs the derived table per outer row** with the join pushed in (`WHERE S1_0.BUSINESS_DATE IS NOT DISTINCT FROM
  ?1`, T `H2 | A.1/count | b cte` plan): a table scan per day without the index (about 265 ms for every statement), an
  index lookup with it (5 to 7 ms); the count keeps the join, filtered or not.
- **Correlated vs join.** Without an index the join wins every statement: 13x on the PostgreSQL page (49 vs 3.7 ms),
  about 190x on its export (474,764 vs 335 buffers), 180x on the MySQL page. With the index PostgreSQL's correlated
  export reads 4,013 buffers and is 1.6 to 1.9x slower than the join; its page of 20 walks `business_day` backwards
  by date and stops after 20 rows (890 buffers) but is still slower than the join (4.4 vs 3.4 ms on 7.4, 5.2 vs 4.2 on
  6.6), so no PostgreSQL page favours the correlated form on this query. Only the indexed count is a tie (2.6 vs 2.9
  ms). On MySQL and H2 the join wins the indexed page and export by 2 to 3x; MySQL's indexed count is a tie (20 ms
  each).

### Part B — `firstBy` works on PostgreSQL and H2 only through a pattern; `arrayGet` is not the rendering

- **`arrayAgg` + `arrayGet`.** PostgreSQL: `array_agg(…)[?]` without parentheses, a syntax error, on both lines and
  in HQL `array_get` too (F `POSTGRESQL | B.1/first-by|B.1/hql|B.1/litepos | …`). H2 runs it as `case when
  array_length(array_agg(…))>=? then array_agg(…)[?] end`, the aggregate twice (F `H2 | B.1/litepos | …arrayGet…`);
  values are right, and the converted `tag` reads `Tag.HIGH` (F `H2 | B.1/first-by | criteria + appended id (SQM):
  converted tag`). MySQL: `arrayAgg` throws `NullPointerException` in SQM (F `MYSQL | B.1/* `), as expected.
- **`arrayAgg` takes one `JpaOrder`**; the appended id needed the internal SQM ordering, which worked on both lines.
- **Spike pattern `(?1)[1]`** (`SpikeFunctions`, typed by Hibernate's element-type resolver) is right on PostgreSQL and
  H2 for ties broken by id, `nullsFirst` and `nullsLast`, and with native `FILTER` (F `POSTGRESQL|H2 |
  B.1/first-by|B.1/filtered | pattern …`). **The converter depends on the call.** Called with no implied result type
  (`cb.function(FIRST, null, array)`), the resolver types the result as the array's element and the converted `tag`
  reads `Tag.HIGH` (F `POSTGRESQL|H2 | B.1/first-by | pattern (?1)[1] untyped + appended id: converted tag`, PASS).
  Called with the value's Java type (`Tag.class`), Hibernate reads `Tag` as a plain enum and throws on the stored `H`
  (H2 `Data conversion error`, PostgreSQL `Bad value for type byte`; F `… | pattern (?1)[1] + appended id: converted
  tag`, FAIL). The first round's "skips the converter" came from the probe passing `Object.class`, which read the raw
  `H`.
- **CASE form** returns NULL when the first row in order fails the filter (F `H2|POSTGRESQL | B.1/filtered | CASE
  form…`, FAIL), as the RFC predicts, so the `MQ4014` refusal of a CASE-filtered `firstBy` stands.
- **B.2 vs B.1 (T `* | B/litepos day|category`), 6.6 / 7.4.** PostgreSQL: the ranked source beats the pattern, 44.6 /
  42.3 vs 81.3 / 78.1 ms per day and 77.2 / 77.6 vs 99.9 / 98.0 ms per category (both plans a hash join into
  `WindowAgg` or `GroupAggregate` with a sort spilling to temp). MySQL runs only B.2: 111 / 110 ms per day, 112 / 113
  per category. H2: B.2 per category (source as root) 74 / 62 ms, but B.2 per day (source LEFT-joined to
  `business_day`) **45.7 s / 36.6 s** (3 runs): the window re-runs for each of 365 days (Part A's H2 behaviour).

### Part C — filtered aggregates are right on every vendor

All 19 C.1 cells pass everywhere (F `* | C.1/*`): PostgreSQL and H2 render `FILTER (where …)`, MySQL Hibernate's CASE
emulation; FILTER and CASE rows equal for `count`, `sum`, `avg`, `min`/`max` through `functionAggregate`,
`countDistinct`, and the engine's CASE form. C.2: a LEFT join to the optional customer keeps every row under FILTER
and CASE; the implicit path renders an **inner** join and drops the 42% of sales with a NULL customer
(F `* | C.2/optional | implicit path …`, FAIL), which is why RFC line 251 resolves a filter's first join `LEFT`.

### Part D — candidate 2 is the rendering; integer inputs need a real SQL cast; MySQL caps the scale at 30

- **Candidate 1 never passes**: `functionAggregate("avg", BigDecimal.class, …)` still reads a `Double` on every
  vendor and line, with or without a cast argument; Hibernate's `avg` descriptor fixes the type
  (F `* | D.1/avg | * cand1 …`). On PostgreSQL and MySQL, and on H2 for `numeric(12,2)`, the database computed an
  exact value that JDBC reads as `numeric`/`DECIMAL`. On H2 an integer input is not exact: Hibernate renders
  `avg(cast(x as float(53)))` for `bigint`, `integer` and `smallint` (JDBC reads `DECFLOAT(27,0)`; F `H2 | D.1/avg |
  * avg (Double)|* cand1 avg typed BigDecimal`), which is what RFC line 287-288 anticipates.
- **H2's own `AVG` per type** (plan item 5; JDBC, no Hibernate, F `H2 | D.1/native-avg`): `AVG(INTEGER)` and
  `AVG(SMALLINT)` are `DOUBLE PRECISION` (1.6666666666666667, a `Double`), `AVG(BIGINT)` is `NUMERIC(29,10)`,
  `AVG(NUMERIC(12,2))` is `NUMERIC(22,12)`. PostgreSQL returns `numeric` scale 16 for all four; MySQL `DECIMAL` at the
  input's scale + 4 (F `POSTGRESQL|MYSQL | D.1/native-avg`).
- **Candidate 2 `sum(x) / count(x)`** passes for `numeric(12,2)` on every vendor, and over the 30,000 sale totals equals
  the database's own `avg` (F `* | D.1/sale-total | cand2 …`). Zero rows read NULL for every candidate.
- **Integer inputs.** Without a cast: integer division on PostgreSQL and H2 for `integer`/`smallint` (`1`) and on
  every vendor for `bigint` read through Hibernate (`Long`). `Expression#as(BigDecimal.class)` renders **no** SQL cast
  (the `cand2 sum(cast(x))` cells only pass where `sum` already widens: `bigint` everywhere, all types on MySQL).
  `HibernateCriteriaBuilder#cast(x, BigDecimal.class)` renders `cast(x as numeric(38,2))` (MySQL `decimal(38,2)`) and
  candidate 2 then passes for `bigint`, `integer` and `smallint` on all three vendors and both lines (F `* | D.1/avg |
  * cand2 sum(sql cast(x))/count(x)`). Scale of the average: PostgreSQL 16, H2 40, MySQL the input's scale + 4
  (`div_precision_increment`): 4 for an uncast integer column, 6 for `numeric(12,2)` and for any input cast to
  `decimal(38,2)` (F `MYSQL | D.1/native-avg`, `MYSQL | D.1/avg | * cand2 sum(sql cast(x))/count(x)`). On MySQL the
  cast target's scale therefore sets the result's scale.
- **D.2.** `round` is half away from zero on all three (2.345 → 2.35, -2.345 → -2.35, 2.5 → 3, -2.5 → -3); H2
  keeps the column's scale (`2.350`) (F `* | D.2/round`). Over a value with more decimals than the scale
  (`round(1/3 to 40 decimals, s)`, s = 30 to 38, a bound `BigDecimal` that Hibernate inlines as a literal), PostgreSQL
  rounds at s and returns scale s, H2 rounds at s and pads to the literal's scale 40, and **MySQL clamps to 30
  decimals with no error** (warnings not read): every s from 31 to 38 reads the same 30-decimal value
  (`DECIMAL(32,30)`; F `MYSQL | D.2/scale-wide | round(1/3 to 40 decimals, 31..38)`, FAIL). The first round's
  `round(2.345, 31..38)` cells (F `* | D.2/scale`) pass everywhere only because 2.345 has 3 decimals.

### Part E — the value form keeps the index; the two-operand OR form loses to the native form on MySQL and H2

Rows: every form gives the right ids, alone and under `not(...)`, on every vendor (F `* | E.1/*`, 30/0/0). The timed
cells run with `ix_sale_promo_code` on `sale(promo_code)`; `filter-2op` and `exists` add `ix_promo_code` on
`promo(code)`. The promos now include 20 codes no sale has (`X1`..`X20`), so an `exists` must prove a miss; sales
carry `P1`..`P55` or NULL (50%). `filter-2op` is the two-operand form in a filter: `sale, promo where
<form>(s.promo_code, p.code) and p.id in (7, 51, 61, 62, 63)` (one matched code, one NULL code, three misses).
PostgreSQL re-runs both two-operand cells under `set enable_seqscan = off`.

| E.1, median ms 6.6 / 7.4 | PG | PG plan | PG `seqscan=off` | MySQL | MySQL plan | H2 | H2 plan |
|---|---:|---|---:|---:|---|---:|---|
| filter `= ?` (baseline) | 0.48 / 0.69 | bitmap | | 0.56 / 0.63 | covering lookup | 0.03 / 0.03 | lookup |
| filter `= ? and is not null` | 0.83 / 0.74 | bitmap | | 0.61 / 0.64 | covering lookup | 0.03 / 0.03 | lookup |
| filter native, one value | 2.19 / 2.31 | **seq scan** | | 0.62 / 0.65 | covering lookup | 0.03 / 0.03 | lookup |
| filter-2op `=` (baseline) | 2.74 / 2.42 | hash join, seq scan | 0.79 / 0.75 | 0.74 / 0.72 | lookup | 0.90 / 0.81 | lookup |
| filter-2op OR form | 9.25 / 9.99 | nested loop, seq scan | 9.04 / 9.20 | 11.6 / 11.4 | `=` or NULL lookup | 13.4 / 12.6 | full index scan |
| filter-2op bare OR | 8.29 / 8.68 | `BitmapOr` | 8.10 / 9.01 | 11.4 / 11.2 | `=` or NULL lookup | 11.9 / 11.0 | full index scan |
| filter-2op native | 7.75 / 8.37 | nested loop, seq scan | 54.0 / 54.4 | 5.11 / 5.50 | lookup | 1.68 / 1.56 | lookup |
| exists `=` (baseline) | 0.77 / 0.54 | index-only | 0.69 / 0.54 | 0.89 / 0.96 | lookup | 3.85 / 3.77 | lookup |
| exists `=`, non-null codes only | 0.55 / 0.54 | index-only | 0.49 / 0.51 | 0.85 / 0.84 | lookup | 0.03 / 0.03 | lookup |
| exists OR form | 31.3 / 21.9 | **seq scan, materialized** | 33.4 / 21.7 | 70.9 / 72.5 | weedout, `=` or NULL lookup | 88.8 / 84.8 | full index scan |
| exists bare OR | 28.6 / 20.8 | **seq scan, materialized** | 29.8 / 20.7 | 33.1 / 33.8 | `=` or NULL lookup | 60.9 / 57.7 | full index scan |
| exists native | 24.3 / 17.3 | **seq scan, materialized** | 25.1 / 17.2 | 1.16 / 1.15 | lookup | 0.06 / 0.04 | lookup |

- The extra `is not null` keeps the index plan on all three (T `* | E.1/filter | value form`).
- PostgreSQL does not index `is not distinct from ?`, as the RFC says (T `POSTGRESQL | E.1/filter | native …`).
- **PostgreSQL, filter.** An index plan for the two-operand OR form exists: a nested loop over the five promos with a
  parameterized `BitmapOr` of two `ix_sale_promo_code` scans (`= p.code` and `is null`), chosen by default for the
  bare OR and under `enable_seqscan = off` for the OR form (T `POSTGRESQL | E.1/filter-2op | or-form|bare-or |
  …enable_seqscan=off`). The native form has no index plan at all: with sequential scans disabled it still scans
  `sale` (cost 10^10, 54 ms with JIT). On this data, where half the sales match the NULL branch, the index plan is no
  faster than the native form's seq scan (9.0 vs 7.8 ms).
- **PostgreSQL, correlated `exists`.** No form but `=` is ever parameterized: the OR, bare OR and native forms all
  plan a nested-loop semi join over a materialized scan of `sale`, 605,822 rows removed by the join filter; under
  `enable_seqscan = off` the materialized input becomes a full bitmap scan with no `Index Cond` (T `POSTGRESQL |
  E.1/exists | or-form|bare-or|native | …`). With the 20 missed codes this costs 17 to 31 ms against 0.5 for `=`.
- **MySQL** serves the OR forms with an `=`-or-NULL lookup (`ref_or_null`) that reads every NULL key (about 15,000)
  for each outer row even when the outer code is not NULL, and its OR-form `exists` turns into a weedout join; the
  native `<=>` is a plain lookup. Native wins: 1.2 vs 33 to 72 ms in `exists`, 5.1 vs 11.5 ms in the filter.
- **H2** runs its native form as an index lookup and the OR forms as a full index scan per outer row: 0.04 vs 58 to
  89 ms in `exists`, 1.6 vs 11 to 13 ms in the filter. The first round's 0.04 ms against 3.8 ms for `=` is not an
  artifact: the `=` cell's extra time comes from its 10 NULL-code promos (probably an `= NULL` lookup walking the
  NULL index entries); with them excluded `=` takes 0.03 ms, as native does (T `H2 | E.1/exists | eq baseline,
  non-null codes`).

### Part F — `count(*)` returns the same rows, with identical plans, and saves up to 12%

- **Rows.** `count(id)`, `count(1)` and `count(*)` are equal at every eligible site on every vendor and line: the
  grouped read, the page total, a correlated count with zero-match days, the composite id, an inner join
  (F `* | F.1/grouped|page-total|correlated|composite|inner-vs-left`). A LEFT join differs, 30 days of 30
  (F `* | F.1/inner-vs-left | … LEFT …`), and a filtered `count(c.id)` over a LEFT join is 883 against `count(1)`'s
  1,455 (F `* | F.1/left-table`), the F.2 case for keeping the id argument.
- **Rendering.** `HibernateCriteriaBuilder#count()` renders `count(*)` on both lines; `count(literal(1), p)` keeps
  `1` inlined with `FILTER` on PostgreSQL/H2 (F `* | F.1/render`). **Composite id**: `count(root)` over
  `stock_snapshot` already renders `count(*)` on H2, PostgreSQL and MySQL on both lines (F `* | F.1/composite |
  count(root) …`), so "before" and "after" are the same SQL.
- **Timings (T `* | F.1/*`), 6.6 / 7.4, ms.** Plans are identical across the three forms (same nodes, same buffers).

| | `count(id)` | `count(1)` | `count(*)` | `count(*)` vs id | `count(1)` vs id |
|---|---:|---:|---:|---:|---:|
| PG grouped | 3.45 / 3.47 | 3.44 / 3.31 | 3.17 / 3.04 | -8% / -12% | 0% / -5% |
| PG page total | 3.81 / 3.89 | 4.10 / 4.15 | 3.48 / 3.59 | -8% / -8% | **+8% / +7%** |
| MySQL grouped | 7.15 / 7.48 | 7.20 / 7.63 | 7.35 / 7.74 | +3% / +3% | +1% / +2% |
| MySQL page total | 8.63 / 8.62 | 7.63 / 7.50 | 7.60 / 7.55 | -12% / -12% | -12% / -13% |
| H2 grouped | 1.75 / 1.66 | 1.61 / 1.52 | 1.58 / 1.53 | -9% / -8% | -8% / -8% |
| H2 page total | 4.18 / 4.19 | 3.81 / 4.00 | 3.95 / 3.88 | -5% / -7% | -9% / -4% |

- **PostgreSQL's page total is slower under `count(1)`** than under today's `count(id)` on both lines; on 7.4 every
  one of the 7 `count(1)` runs (4.13 to 4.33 ms) is slower than every `count(id)` run (3.85 to 4.03 ms) (T
  `POSTGRESQL | F.1/page-total | F.1/page-total/one`). `count(1)` is F.2's portable `rowCount` default, so a provider
  without the Hibernate renderer would see this regression on PostgreSQL page totals, not the gain.
- **Snapshots that would change** (`f1-snapshots.txt`, list only): every `count(<alias>.id)` in the committed TCK
  snapshots counts a statement's root or a sub-select's root; none counts a joined alias. That is 106 files (H2 36,
  PostgreSQL 34, MySQL 34, `mysql/hibernate7` 2), an upper bound: `col-52-*` count a treated root, which F.1 keeps,
  and a customizer is not visible in a snapshot. 30 more files (6 per vendor, 4 per vendor's `hibernate7`
  directory) hold a source root's `count(1)`, which changes only if routed through the helper as F.1 says.

## Line parity

All 660 functional cells have the same status on 6.6 and 7.4 (`summarize.py parity`). The 153 timed statements are
the same SQL on both lines except the joined source's alias (`cte01_0` / `derived1_0` on 6.6, `var_1_11_0` on 7.4),
with the same row counts and plan shapes; medians agree within run-to-run spread. The 6.6 line was run twice in this
round; its first run's PostgreSQL and MySQL F.1 medians spread up to 2x between runs and were replaced by the second
run's, which is the data in `rfc0020-*-6.6.58.Final.tsv`. The probe module compiles on both
lines from one source, reaching `join(JpaCteCriteria, SqmJoinType)` and its `on` reflectively.

## Consequences for the RFC

1. **A.3 "H2 runs both" (RFC line 164-168).** H2 never receives the CTE: Hibernate inlines a joined CTE as a derived
   table, and H2 evaluates it once per outer row. A grouped source costs a scan per outer row without an index on
   the join key (about 265 ms for 365 days, every statement); a window source (B.2's route) costs the window per row,
   37 to 46 s for 365 rows. The A.3 text and the docs' cost paragraph (line 177-180) should say so; H2 is a test
   database, but the B.2 route is not usable there past a few dozen outer rows.
2. **"Every statement carries the join" (line 169-172).** PostgreSQL removes the join from a count only while no
   filter reads the source (0.38 ms unfiltered, 3.2 ms with the "unreconciled" filter, T `POSTGRESQL | A.1/count
   unfiltered|A.1/count | b cte`). MySQL and H2 run the source in full in every count, filtered or not: 13x (MySQL,
   10.9 vs 0.8 ms) and over 5,000x (H2 without an index, 270 vs 0.04 ms) the plain count. The rule stays correct; the
   cost paragraph should name the count, not only "a page of 20".
3. **Docs advice (line 179-180), "keep `Correlated.*` for pages over an indexed child".** Not supported by this data on
   any vendor once the page filters on the correlated value: with the date index the joined source wins PostgreSQL's
   page of 20 (3.4 vs 4.4 ms on 7.4) and export (3.6 vs 6.8 ms), and MySQL's and H2's by 2 to 3x; only the indexed
   count is a tie (PostgreSQL 2.6 vs 2.9 ms, MySQL 20 ms each). A filter on a correlated column re-evaluates its
   sub-select (the RFC's two-valued form repeats the operand). The first round's "correlated page is fastest on
   PostgreSQL (3.1 vs 3.4 ms)" timed an unfiltered read and is withdrawn. MySQL also slows the joined source when the
   date index exists (11 → 22 ms). The advice should either narrow to unfiltered pages of a few rows or go.
4. **B.1 rendering (line 216-221, 227-228).** `arrayGet` is not the rendering: PostgreSQL gets `array_agg(…)[?]`
   without parentheses (syntax error) on both lines, H2 aggregates twice. The renderer needs its own function
   pattern, `(array_agg(…))[1]` on PostgreSQL and `array_agg(…)[1]` on H2, as the RFC's table already shows.
5. **B.1 "its converter (if any) is applied to the result as for any column read" (line 208-209).** Holds when the
   renderer calls its pattern with no implied result type, so Hibernate's element-type resolver types it from the
   array (F `POSTGRESQL|H2 | B.1/first-by | pattern (?1)[1] untyped + appended id: converted tag`, `Tag.HIGH`). Passing
   the value's Java class instead (`Tag.class`) bypasses the converter and fails to read the stored value. The RFC
   text stands; the renderer's contract should say it must not pass a Java result type to the pattern. The first
   round's "a function result skips the converter" came from the probe's `Object.class` and is withdrawn.
6. **D "the spike adds per-input-type argument casts" (line 287-289) and "Scale of the average" (line 295-297).**
   `Expression#as` renders no cast; the cast must be `HibernateCriteriaBuilder#cast` (or `cb.cast` on 7.4's Jakarta
   Persistence 3.2), and then `bigint`, `integer` and `smallint` all pass on every vendor. On H2, Hibernate's own `avg`
   casts integer inputs to `float(53)`, and H2's native `AVG(INTEGER)`/`AVG(SMALLINT)` is `DOUBLE PRECISION` (line
   288's "may return DOUBLE" is confirmed). Hibernate's default target `numeric(38,2)` would round a `BigDecimal` input
   with more than 2 decimals, so only integer inputs should be cast, or the cast should carry the input's own scale.
   Either choice moves MySQL's result: its average's scale is the input's scale + 4 (`div_precision_increment`), so a
   cast to `decimal(38,2)` gives 6 decimals and an uncast or scale-0 integer gives 4; the cast target's scale sets the
   MySQL scale, and line 296's "MySQL's `div_precision_increment`" should say "input scale + `div_precision_increment`".
   Candidate 1 fails on every vendor (line 298-304): the provider cannot type `avg` as `BigDecimal`.
7. **E "PostgreSQL can serve it [the two-operand form] from an index" (line 331-332) and the native-form rule (line
   334-335, resolution 4 at line 569).** In a plain filter the claim holds: PostgreSQL has a parameterized
   `BitmapOr` plan for the OR form, where the native form has none even with sequential scans disabled; on this data
   it is not faster (9.0 vs 7.8 ms). In a correlated `exists`, the shape the RFC's two-operand use mostly takes,
   PostgreSQL never parameterizes the OR form: it plans it as the native form, a materialized scan of `sale`, even
   under `enable_seqscan = off`. Elsewhere the native form wins clearly: MySQL's `<=>` is a plain lookup where the OR
   form reads every NULL key per outer row (`exists` 1.2 vs 33 to 72 ms, filter 5.1 vs 11.5 ms), and H2 runs only the
   native form as a lookup (`exists` 0.04 vs 58 to 89 ms, filter 1.6 vs 11 to 13 ms). By line 334-335's own rule the
   native form moves behind the provider hook on MySQL and H2, which contradicts line 345's "No provider hook, no
   vendor fact" and changes resolution 4 (line 569: MySQL keeps the index, but `ref_or_null` loses to `<=>`). The
   first round's "the two-operand form loses its index claim on PostgreSQL" was a planner cost choice on data where
   every promo matched early, and is withdrawn.
8. **F (line 386-388) composite id.** `count(root)` over a composite id already renders `count(*)` on all three vendors
   and both lines; Part F changes nothing there.
9. **F.1 snapshot count.** About 106 snapshot files change under the rule (plus 30 `count(1)` files if source roots
   route through the helper); the RFC's line 335 statement that "Parts A to E change no snapshot" is unaffected,
   since every change comes from Part F.
10. **F.2 portable `rowCount` default `count(1)` (line 391, 418).** On PostgreSQL's page total `count(1)` is 7 to 8%
    slower than today's `count(id)` on both lines (4.10 vs 3.81, 4.15 vs 3.89 ms), with non-overlapping runs on 7.4;
    on MySQL and H2 it saves 4 to 13%. A provider without the Hibernate renderer would regress PostgreSQL page totals.

### Open points for acceptance

- **The `round` scale bound (Part D, line 292-293): 30 or 38.** MySQL clamps any scale over 30 to 30 with no error
  (F `MYSQL | D.2/scale-wide`); PostgreSQL honours 31 to 38; H2 rounds at s and pads. A bound of 30 is what every
  Tier-1 vendor returns as asked; 38 would let MySQL return fewer decimals than requested. The spike records the data
  only; the bound is decided at acceptance.
- **The portable `rowCount` default (Part F, line 391, 418).** `count(1)` regresses PostgreSQL page totals by 7 to 8%
  against today's `count(id)` (consequence 10); keeping it, or defaulting to the root id where one exists, is decided
  at acceptance.
