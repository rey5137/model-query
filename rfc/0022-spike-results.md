# RFC 0022 acceptance spike: results

**A: keep the module, its discovery and its probe as designed, and take Resolution 4's option (a): Resolution 4 does
not hold on EclipseLink 5.0.2, so M34 has no EclipseLink lane.** EclipseLink 5.0.2's Criteria `From#join(Class)` plus
`on` fails while it resolves the joined entity's attribute: it looks the attribute up on the root
(`IllegalArgumentException: The attribute [code] is not present in the managed type Invoice`). It fails untyped,
typed before `on` and typed after `on`, on all three vendors. The JPQL form `left join Employee e on e.code =
i.repCode` passes and matches the native SQL. The engine boots and maps `Tuple` rows on EclipseLink. On Hibernate 7.4
with Hibernate's renderer withheld, the portable renderer gives the same SQL, binds and rows as
`HibernateEntityJoinRenderer` in all 192 parity cells per vendor. The probe sees the `LinkageError`s that A.4 names,
at the first call, with no connection. EclipseLink 5.0.2 refuses even the self-join probe (`IllegalStateException`).

**B: keep, with §Motivation 2 reworded. The orphan read-only association raises Hibernate's `ObjectNotFoundException`,
not `EntityNotFoundException`. PostgreSQL removes the unreferenced LEFT join from the count on both lines, and
`countQuery`'s copy keeps the column-anchored join on both lines and every vendor, so Resolution 8 keeps the copy
path.** Every B.1 statement kind returns the native SQL's rows on H2, PostgreSQL and MySQL on both lines. The anchor's
path join renders before the entity join. An `INNER` join over a `LEFT`-unmatched anchor path drops those rows, and
`when` removes all 100 colliding-id rows.

**C: keep, with C.4's `inline` sentence corrected. A param stays a bound `?` under `value_handling_mode=inline`, with
the same SQL and binds as the default mode. PostgreSQL renders a `cast(? as …)` where the type isn't implied and
reports no undetermined parameter type. A param used as group key and order key is one node.** Every C.4 statement
binds the params it renders and no others, with the same SQL text across values. One observation is open for M34.4: on
7.4 the captured binds of the source-body phase cell don't show the body's own value (§Findings C.2).

**D: keep. `count(*)` equals `count(id)` for every `INNER` entity join on both lines and every vendor, and differs for
`LEFT`, plain or filtered. `count(id)` costs 9 to 12% on PostgreSQL's grouped read.** The `INNER` cells cover both
anchors, three id forms, a to-one `INNER` join below, grouped and ungrouped. 7.4 renders the filtered count's
`count(case … else null end)` as `sum(case … then 1 else 0 end)`. The numbers are equal in every cell here.

This is throwaway research on `spike/rfc-0022`: one test-source module `spike-rfc0022/` (Maven profile
`spike-rfc0022`), run on Hibernate 6.6.58.Final and 7.4.11.Final (Jakarta Persistence 3.2.0 on 7.4) against H2
2.5.252, PostgreSQL 16 (`postgres:16-alpine`) and MySQL 8.0.31. Part A also ran EclipseLink 5.0.2 (the newest stable
5.0.x) and 4.0.8 offline, and EclipseLink 5.0.2 against all three databases. Production `model-query-*/src/main`,
committed SQL snapshots and `docs/spec/` are unchanged. The dataset is the RFC 0021 closure-table seed plus 50,000
invoices, 1,000 (2%) orphan `rep_code`s, 300 employees in simple, `@IdClass` and `@EmbeddedId` tables, and 202
attachments over invoices and tickets with colliding ids.

**What was emulated.** The library refuses column anchors today, so `Q.java` builds the proposed creation step on
Criteria and calls the production `ProviderSupport#entityJoins()` renderer. Every statement kind is the content
statement with that kind's extra predicate, order and limit, not the executor's own statement of that kind. The
root-id count is a `count(distinct id)` with no treated mapping, so treated-path dispatch is `NOT_RUN`. `Expr.param`
is one Criteria `ParameterExpression` per param, bound by name. A.3, B.3 and D.1 use the same emulation.

The [raw data and reproduction commands](0022-spike-data/README.md) hold the SQL, binds, values, timings and plans.
References: **F** is `rfc0022-<line>.tsv`, keyed `vendor | probe | case`; **E** is `rfc0022-eclipselink-5.0.2.tsv`
with stacks in `eclipselink-5.0.2-engine.txt`; **L** is `link-probe-<lane>.tsv`/`.txt`; **J** is `javap-<lane>.txt`;
**T** is `rfc0022-timings-<line>.tsv` and `joined-count-timings-<line>.tsv`, medians of 7 alternating runs in ms,
with plans under `plans/<line>/<vendor>.txt`.

## Matrix

PASS/FAIL/NOT_RUN per probe group (`summarize.py grid`). A FAIL is a cell whose expectation did not hold or that
threw. On the Hibernate lines the only FAIL is the orphan association (B.1, below). The `NOT_RUN`s are A.3 on 6.6,
which keeps API 3.1, and the treated-path dispatch. The B.2 cells are the accepted timed sub-step 2 runs
(`cost-cells-<line>.tsv`), because the sub-step 3 runs skipped the timed cells.

| Probe | H2 6.6 | MySQL 6.6 | PG 6.6 | H2 7.4 | MySQL 7.4 | PG 7.4 | H2 EL5 | MySQL EL5 | PG EL5 |
|---|---|---|---|---|---|---|---|---|---|
| A.3 | 0/0/1 | 0/0/1 | 0/0/1 | 192/0/0 | 192/0/0 | 192/0/0 | - | - | - |
| A.4 | - | - | - | - | - | - | 2/2/7 | 2/2/7 | 2/2/7 |
| A.4-repro | - | - | - | - | - | - | 1/3/0 | 1/3/0 | 1/3/0 |
| B.1 | 78/1/1 | 78/1/1 | 78/1/1 | 78/1/1 | 78/1/1 | 78/1/1 | - | - | - |
| B.2 | 3/0/0 | 3/0/0 | 3/0/0 | 3/0/0 | 3/0/0 | 3/0/0 | - | - | - |
| B.3 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | - | - | - |
| B.4 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | 2/0/0 | - | - | - |
| C.1 | 18/0/0 | 18/0/0 | 18/0/0 | 18/0/0 | 18/0/0 | 18/0/0 | - | - | - |
| C.2 | 28/0/0 | 28/0/0 | 28/0/0 | 28/0/0 | 28/0/0 | 28/0/0 | - | - | - |
| D.1 | 60/0/0 | 60/0/0 | 60/0/0 | 60/0/0 | 60/0/0 | 60/0/0 | - | - | - |
| total | 195/1/2 | 195/1/2 | 195/1/2 | 387/1/1 | 387/1/1 | 387/1/1 | 3/5/7 | 3/5/7 | 3/5/7 |

EL5 is EclipseLink 5.0.2 / API 3.2.0. The offline A.1/A.2 lanes (`summarize.py matrix`):

| Lane | A.1 | A.2 |
|---|---|---|
| Hibernate 6.6 / API 3.1.0 | 0/0/1 | 4/0/0 |
| Hibernate 6.6 / API 3.2.0 forced | 0/0/1 | 4/0/0 |
| Hibernate 7.4 / API 3.2.0 | 1/0/0 | 4/0/0 |
| EclipseLink 4.0.8 / API 3.2.0 forced | 0/0/1 | 4/0/0 |
| EclipseLink 5.0.2 / API 3.2.0 | 0/1/0 | 2/2/0 |

## Findings

### A.1 Signatures: both providers implement the 3.2 methods; only Hibernate returns a usable join

- API 3.2.0 declares `From#join(Class)` and `From#join(Class, JoinType)` returning `Join<X, Y>`. `Join#on` comes as
  `on(Expression<Boolean>)` and `on(Predicate...)`, both returning `Join<Z, X>`, and `Join#getOn()` returns
  `Predicate` (J).
- Hibernate 7.4's `AbstractSqmFrom` implements both `join(Class…)` forms, returning `SqmEntityJoin`, with bridge
  methods to `jakarta…Join` (J). At run time the join is a jakarta `Join` whose `getOn()` is non-null after one `on`,
  with zero connections (L `hibernate-7.4-api3.2/signatures-and-implementation`).
- EclipseLink 5.0.2's `FromImpl` declares both methods with the API descriptors (J). The call itself refuses a
  self-join before any predicate applies: `IllegalStateException: Cannot add join of ProbeEntity to ProbeEntity
  because the target class is missing an attribute of source type` (L). A.1 is FAIL there. The non-self join is A.4.

### A.2 The probe: the expected `LinkageError`s at the first call, and EclipseLink 5 refuses the self-join

- All five lanes load and verify the probe under `-Xverify:all`. The constructor returns, and every failure comes at
  the first call (L `class-load`, `initialize-verify`).
- (i) API 3.1.0 with Hibernate 6.6: `NoSuchMethodError` on `From.join(Class, JoinType)`. (ii) API 3.2.0 forced over
  Hibernate 6.6: `AbstractMethodError`, as `SqmRoot` doesn't implement it. (iii) EclipseLink 4.0.8 under API 3.2.0:
  `AbstractMethodError` from `RootImpl`. All three open zero connections, for both predicates (L).
- (iv) EclipseLink 5.0.2 does **not** pass. Both predicates hit the same `IllegalStateException` as A.1, a
  non-`LinkageError`, with zero connections. By A.4's last paragraph that is `MQ4017` with the cause. Since A.4 shows
  its non-self join fails too, that refusal is the right outcome.
- Hibernate 7.4 accepts both `conjunction()` and the id self-equality. Cost, info only: `conjunction()` 15.5 ms cold
  (the first Criteria call in the JVM), median 12.5 µs; id self-equality 0.9 ms cold, median 27 µs (L).

### A.3 Parity on 7.4: the portable renderer is Hibernate's, byte for byte

- 192 cells per vendor: 12 statement shapes x `LEFT`/`INNER` x simple, `@IdClass` and `@EmbeddedId` ids x a filter
  on or off from the root, plus `LEFT`- and `INNER`-path anchors with a filter. Each cell has the same SQL, binds and
  rows with Hibernate's renderer withheld, and the same rows as the native SQL (F `A.3`).
- Not shown: the engine's treated root-id count and the fetch-plan `through` load. Their shapes are emulated, as in
  B.1.

### A.4 EclipseLink 5.0.2: the engine runs, the Criteria entity join does not

- Bootstrap through `PersistenceProvider#createContainerEntityManagerFactory`, schema and the 50,000-invoice seed
  pass on all three vendors (E `bootstrap-and-invoice-seed`).
- The production executor runs a page of 20 with no join and maps its `Tuple` aliases to the native rows (E
  `engine-vendor-and-tuple-aliases`). **Vendor detection is not shown.** The cell asserts rows only. The log has
  `model-query uses the conservative OTHER profile … because no provider support detected the database and the
  factory has no jakarta.persistence.nonJtaDataSource` once per vendor. `VendorResolver` reads that key from
  `emf.getProperties()`, and EclipseLink built from a `PersistenceUnitInfo` doesn't expose the unit's data source there.
- The plain reproduction, with no engine and no spike renderer, is `root.join(Employee.class, LEFT)` then
  `join.on(cb.equal(join.get("code"), root.get("repCode")))`. It fails in `FromImpl.get` → `ManagedTypeImpl.
  getAttribute` with `The attribute [code] is not present in the managed type [… Invoice …]`. That holds untyped,
  with `join.<String>get("code")` taken before `on`, and taken after a first `on(cb.conjunction())`, on all three
  vendors (E `A.4-repro`). The JPQL `select i from Invoice i left join Employee e on e.code = i.repCode` returns the
  native SQL's 20 invoice ids (E `A.4-repro/JPQL`). So the failure is in EclipseLink's Criteria `From#join(Class)`, not
  in its entity-join support or in the harness.
- The portable renderer fails the same way for `LEFT` and `INNER` (E `portable-LEFT`, `portable-INNER`). The seven
  joined-engine cells per vendor (`LEFT`, `INNER`, to-one below, filter, count, keyset with `nullsLast`, export) are
  `NOT_RUN`, stopped at join creation. Their harness paths, including the adapter that keeps the explicit key under
  the association's `ON`, run green on Hibernate 7.4.

### B.1 Rendering: column anchors render as the RFC says, on both lines and all vendors

- Root anchor, `LEFT` and `INNER` path anchors, by both join kinds, over 12 statement shapes: all match the native SQL
  (F `B.1/<anchor>/<kind>/<shape>`). Counts: root `LEFT` 50,000; root `INNER` 49,000 (the 1,000 orphans drop); an
  `INNER` join over the `LEFT` region path 48,000 (the 2,000 null-region rows drop); `LEFT` over it 50,000.
- Creation order: the anchor's path join comes first,
  `left join region r1_0 on r1_0.code=i1_0.region_code join employee e1_0 on e1_0.code=r1_0.rep_code`, checked in
  every path-anchored cell.
- `@IdClass` and `@EmbeddedId` targets render a two-column `ON` (`ee1_0.code=i1_0.rep_code and
  ee1_0.region_code=i1_0.region_code`). The `LEFT` join filter is ANDed into the one `ON` as a bound `?`.
- Orphans: the column entity join reads `50|INV-50|null` (F `orphan-entity-join`). Beside it, `em.find(Invoice, 50)`
  succeeds, but initializing its read-only `@ManyToOne` raises `org.hibernate.ObjectNotFoundException` on both lines
  and all vendors (F `orphan-read-only-association-em-find`, FAIL). That is not a Jakarta `EntityNotFoundException`.
  The cell expected the RFC's wording, and its FAIL is the finding.
- `NOT_RUN`: treated-path dispatch (no inherited invoice mapping).

### B.2 Cost: PostgreSQL drops the unreferenced join from the count, MySQL and H2 look it up per row

- Page, count and export return the native SQL's rows on every vendor and both lines; the export reads 50,000 rows.
- PostgreSQL's count plan is `Aggregate -> Seq Scan on invoice` with no employee node, on both lines. It removes the
  unreferenced `LEFT` join on `employee`'s primary key, for the renderer and the native SQL alike (`plans/*/
  postgresql.txt`). The page and the export keep a `Nested Loop Left Join` with an `employee_pkey` index scan.
- MySQL keeps the join in the count: `Nested loop left join`, with a single-row covering lookup on `PRIMARY` 50,000
  times. H2 keeps the `PRIMARY_KEY` lookup per outer row as well.
- Generated against native SQL, medians (§Timings): count and export within 3% on MySQL and H2, and every page
  within 0.06 ms. PostgreSQL's count is 1.68 ms against 1.40 ms, because the generated count is `count(i1_0.id)` and
  the native one is `count(*)`. Neither scans `employee`.

### B.3 The group count: the copy keeps the column-anchored join everywhere

- `ProviderSupport#countQuery`'s copy of a grouped query keeps the employee join, and returns the native group count,
  for `LEFT` and `INNER` with each id form on both lines and all three vendors: 36 of 36 cells (F `B.3`). Everywhere,
  `INNER` gives 294 groups for the simple id and 288 for the composite ids. `LEFT` gives one more, the unmatched
  rows' NULL group.
- This differs from RFC 0021, where the copy of a query that joins a CTE failed on 6.6 and on H2 7.4. The difference
  comes from the joined source, not the entity join.

### B.4 The static term: `when` removes the wrong rows and binds once

- Without `when`, 100 of the 202 attachment rows read the invoice whose id collides with their ticket owner. With
  `when`, none do. Both forms keep all 202 rows, including the two with absent owners (F `B.4`).
- `when` renders `and a1_0.owner_type=?` inside the one `ON` and binds `INVOICE` once per statement. Two executions
  give the same SQL. The SQL is the same on both lines.

### C.1 Rendering: one typed node, a cast only where the operand gives no type

- All nine positions pass on every vendor and both lines, in both modes, with both values matching the native SQL
  and the same SQL across calls (F `C.1`). The positions are bare select item, arithmetic, CASE condition operand, CASE
  result, `coalesce`, `function` argument, correlation of a `Correlated` count, a joined source's body and group+order
  key.
- Casts are the same on both lines. PostgreSQL and H2 render `cast(? as varchar)` and `cast(? as bigint)`, and MySQL
  renders `cast(? as char)` and `cast(? as signed)`. They appear for a bare select item, an arithmetic operand, a
  concat/function argument, a CASE result, a CTE body's select item and a group key. There is no cast where a
  comparison or `coalesce` beside a column gives the type: `case when ?='req7'`, `coalesce(?,i1_0.invoice_number)`,
  `i1_0.id>?` in the correlation. No cell reports "could not determine data type of parameter".
- Group and order key: `select cast(? as bigint),count(i1_0.id) … group by 1 order by 1` on PostgreSQL and MySQL, and
  `group by c0 order by 1` on H2. The param renders as one node and PostgreSQL accepts the grouping.
- Under `hibernate.criteria.value_handling_mode=inline`, all 23 C cells per vendor have the same SQL **and** binds as
  under the default, on both lines. An explicit `ParameterExpression` is never inlined.

### C.2 Phases: each statement binds what it renders

- Content, count, root-id count, both primary-key-first steps, keyset, by-keys, one, both export pages, stream,
  fetch-plan parent read, inline-count window and source body pass on every vendor and both lines, in both modes (F
  `C.2`). Each statement's `getParameters()` has exactly the params it renders, each bound once. The counts bind only
  the `where` param (`select count(i1_0.id) … where i1_0.id>=?` binds `7`), and the select-only and order-only params
  are absent. The SQL is the same for the values 7 and 9.
- **Open:** on 7.4 the source-body cell's captured JDBC binds are `7, req7, 7, 7, 20`, where the body param was set to
  `value + 1`. 6.6 shows `8, req7, 7, 7, 20` as set. Yet its rows equal the native SQL with `i.id>=8` on 7.4 too. The
  capture and the rows disagree, and this data can't tell which is right. M34.4's AC-COL-62 test should assert the
  value bound in a source body per statement, on both lines.
  **Resolved by M34.3:** `QueryParamStatementsTest#ac_col_62_a_joined_source_body_binds_its_own_value_in_each_statement`
  asserts the body's own value (8 beside the outer 7) in each statement, and passes on 6.6 and 7.4: both lines bind the
  set value.

### D.1 Counts: the `INNER` hop is exact, `LEFT` keeps the identity

- 60 cells per vendor and line: column and source anchors, three id forms, `INNER` (plain, to-one `INNER` below) and
  `LEFT` (plain, to-one below, `joinFilter`), grouped and ungrouped. Each carries `count(*)`, `count(id)` and the
  filtered `1`-under-condition count, all equal to the native SQL (F `D.1`).
- In all 24 `INNER` cells, `count(*)` equals `count(id)`. In all 24 `LEFT` plain and `LEFT` filtered cells they differ,
  and an unmatched or blanked `LEFT` group counts 0 ids against n rows. `LEFT` with an `INNER` to-one below doesn't
  differ, because the inner to-one join drops the unmatched rows: the flat SQL's meaning.
- 7.4 renders `cb.count(case when … then 1 else null end)` as `sum(case when … then ? else ? end)`, binding 1 and 0
  (with `cast(? as bigint)` on H2). 6.6 keeps `count(case …)`. Every cell has equal numbers. A `sum` over no rows is
  NULL where `count` is 0, and no cell has an empty input.
- PostgreSQL grouped read, info only: `count(id)` is 9 to 12% slower than `count(*)` (§Timings), in line with RFC
  0020 Part F's 10% (§Motivation D, line 97-99).

## Line parity

The spike module compiles from one source on both lines, and every functional cell that runs on both has the same
status (`summarize.py parity`, `parity.txt`). 591 cells are common. The 6.6-only rows are A.3's 3 `NOT_RUN` placeholders, and
the 7.4-only rows are A.3's 576 parity cells. Status differs in **0** cells. Value differs in 3: the
`ObjectNotFoundException` message (6.6 `[…Employee#ORPHAN]`, 7.4 `for entity […Employee with id 'ORPHAN']`). SQL
differs in 192 raw. Normalizing the joined source's alias (`cte01_0` on 6.6, `var_1_11_0` on 7.4) leaves 186: the
180 D.1 cells with 7.4's `count(case …)` to `sum(case …)` rewrite, and the 6 C.2 source-body cells whose binds differ
(above). The B.2 statements have the same SQL, row counts and plan shapes on both lines. The API 3.2 adapter sanity
test and A.3 run on 7.4 only.

## Timings

Medians of 7 alternating runs in ms, info only: this spike defines no latency threshold (T). "gen" is the captured
SQL of the emulated engine statement, "native" is the hand-written SQL, and both are replayed over raw JDBC.

| Line | Vendor | Page gen | Page native | Count gen | Count native | Export gen | Export native |
|---|---|---:|---:|---:|---:|---:|---:|
| 6.6 | H2 | 0.08 | 0.07 | 14.19 | 14.14 | 19.56 | 19.55 |
| 6.6 | MySQL | 0.42 | 0.42 | 30.92 | 30.24 | 47.41 | 47.91 |
| 6.6 | PostgreSQL | 0.40 | 0.39 | 1.68 | 1.40 | 18.01 | 18.48 |
| 7.4 | H2 | 0.04 | 0.04 | 14.90 | 14.64 | 19.33 | 19.31 |
| 7.4 | MySQL | 0.38 | 0.37 | 30.14 | 29.44 | 44.05 | 43.95 |
| 7.4 | PostgreSQL | 0.44 | 0.39 | 1.69 | 1.43 | 17.85 | 17.99 |

D.1, PostgreSQL grouped read (`select e.name, count(…) … group by e.name`, `INNER`, simple id):

| Line | Anchor | `count(*)` | `count(e.code)` |
|---|---|---:|---:|
| 6.6 | column | 7.60 | 8.43 |
| 6.6 | source | 7.68 | 8.51 |
| 7.4 | column | 7.66 | 8.60 |
| 7.4 | source | 7.76 | 8.49 |

A.2 probe cost on Hibernate 7.4: `conjunction()` median 12.5 µs, id self-equality 27 µs, after one cold call (L).

## Consequences for the RFC

Each item names the RFC section or Resolution it amends and the wording to put there. The orchestrator applies them
at acceptance; this file doesn't edit the RFC.

1. **Resolution 4 (line 672-676) and M34.3's row (line 711).** Resolution 4 does not hold on EclipseLink 5.0.2, so
   take (a): Hibernate 7.4 with Hibernate's renderer withheld, in the module's tests, and no EclipseLink lane in M34.
   Record the evidence: "EclipseLink 5.0.2's Criteria `From#join(Class)` plus `on` resolves the joined entity's
   attributes against the root (`IllegalArgumentException … not present in the managed type Invoice`), untyped and
   typed before or after `on`, on H2, PostgreSQL and MySQL. Its JPQL entity join works (spike A.4)." Strike
   "(Resolution 4) EclipseLink lanes" from M34.3.
2. **A.1 (line 108-114), §Motivation A (line 54) and §Motivation 3 (line 81-83); Resolution 1 (line 662-663).**
   "With Part B, a model … runs on any 3.2 provider" and "the first entity join that renders on a 3.2 provider other
   than Hibernate" hold for no tested provider. Replace them with "on a 3.2 provider whose Criteria
   `From#join(Class, JoinType)` works; EclipseLink 5.0.2's does not (spike A.4)". Decision to close at acceptance:
   Part A then serves only Hibernate 7.4 with its renderer withheld, a configuration no user runs. Either keep
   Resolution 1's (b) with the providers page saying so, or defer A under (c) until a provider passes A.4. The
   spike doesn't contradict (b) itself.
3. **A.4 (line 180-205) and Spike plan item 2 (line 523-529).** Add after the probe: "EclipseLink 5.0.2 refuses the
   self-join with `IllegalStateException` before any predicate (spike A.2), so the probe's non-`LinkageError` clause
   refuses it with `MQ4017`. Its non-self join fails as well (A.4)." Replace "(iv) EclipseLink 5.0 (passes)" with the
   observed refusal. The probe predicate stays `cb.conjunction()`: Hibernate 7.4 accepts both forms, `conjunction()`
   at a 12.5 µs median against 27 µs, and it needs no id attribute.
4. **A.3 (line 174-175), "vendor detection then falls back to `DatabaseMetaData` (R-VND-06)".** Add: "An
   EclipseLink factory built from a `PersistenceUnitInfo` doesn't expose its data source as
   `jakarta.persistence.nonJtaDataSource`, so detection gives `OTHER` unless `ModelQueryConfig.vendor(...)` is set
   (spike A.4 log)." This is a finding about R-VND-06 on non-Hibernate providers today. It isn't caused by this RFC.
5. **§Motivation 2 (line 77-79) and Spike plan item 5 (line 539).** Replace "a proxy that throws
   `EntityNotFoundException`" with "a proxy that throws Hibernate's `ObjectNotFoundException` when initialized
   (spike B.1, both lines, all three vendors)". The argument stands: the column entity join reads the orphan as NULL.
   Other providers weren't run here, so the sentence names Hibernate's exception only.
6. **B.3 (line 293-297) and B.4 (line 306-310).** Add "(spike B.1)" to "Each anchor's own to-one joins resolve first"
   and to "That includes rows whose anchor is NULL because the anchor's own to-one path was `LEFT` and unmatched".
   The spike renders the path join first and drops 2,000 null-path rows under `INNER` on both lines and all vendors.
7. **B.5 "Every statement carries the join" (line 317-320).** Add the cost evidence: "PostgreSQL removes an
   unreferenced `LEFT` entity join on the target's primary key from the count, on both lines. MySQL and H2 keep one
   primary-key lookup per outer row (spike B.2)." The rule is unchanged.
8. **B.5 "The count over groups" (line 322-325) and Resolution 8 (line 686-687).** Close it with the copy path:
   "`ProviderSupport#countQuery`'s copy keeps a column-anchored entity join and returns the native group count for
   `LEFT` and `INNER`, simple, `@IdClass` and `@EmbeddedId`, on both lines and all three vendors (spike B.3). The copy
   path ships for this case. A grouped model that joins a source still counts client-side (D-192)." The D-n records
   the copy path.
9. **B.6 (line 335-352) and Resolution 7 (line 683-685).** Add the evidence to Resolution 7: "without `when` 100 of
   202 attachment rows read the colliding invoice, with it none; `when` binds once per statement as a `?` (spike
   B.4)". Include stays.
10. **C.4 "Caching and compiled queries" (line 450-453).** Replace "Under Hibernate's `value_handling_mode=inline`
    (R-VND-28, D-177) a param inlines as `Expr.value` does" with "Under Hibernate's `value_handling_mode=inline`
    (R-VND-28, D-177) a param stays a bound parameter, as every value does (R-FLT-08). Spike C.1/C.2 found the same
    SQL and binds in both modes, on both lines and every vendor."
11. **C.1 "Typed by declaration" (line 370-374).** Add: "Where no operand gives the type (a bare select item, an
    arithmetic or concat operand, a CASE result, a group key), the parameter renders as `cast(? as <type>)` on both
    lines. As a comparison operand, in `coalesce` beside a column or in a correlation it renders bare, and PostgreSQL
    reports no undetermined type (spike C.1). As a group and order key it renders one node (`group by 1 order by 1`)."
12. **C.4 "Every statement binds what it renders" (line 444-449) and AC-COL-62 (line 590).** Add "(spike C.2)" to
    the statement list. Add to AC-COL-62: "the value bound in a source body is asserted per statement on both lines".
    On 7.4 the spike's captured source-body binds disagree with its rows (§Findings C.2). M34.4 settles it with a
    test, not with the spike's data.
13. **D.1 (line 464-478) and Resolution 13 (line 696-697).** Add the evidence: "`count(*)` equals `count(id)` for
    every `INNER` entity join, column- or source-anchored, three id forms, with a to-one `INNER` below, grouped and
    ungrouped, on both lines and Tier 1; `LEFT`, plain or filtered, differs (spike D.1)". To "Unchanged: … a filtered
    count's `1`-under-condition form (R-AGG-17)" add: "Hibernate 7.4 renders that form as `sum(case … then 1 else 0
    end)`, which is NULL over no rows where 6.6's `count` is 0." M34.5 adds a filtered count over an empty input on
    both lines. This is a finding about R-AGG-17 on 7.4 today, not caused by this RFC.
14. **Spike plan (line 509-562).** Mark every item done. A.4's seven joined-engine cells are `NOT_RUN`, stopped at
    join creation by the EclipseLink failure, and B.1's treated-path dispatch is `NOT_RUN` (no inherited mapping).
    Nothing else is deferred.

No change is proposed to Parts A.2, A.5 and A.6, B.1 and B.2, C.2 and C.3, D.2, or to Resolutions 2, 3, 5, 6 and
9 to 12, or 14.
