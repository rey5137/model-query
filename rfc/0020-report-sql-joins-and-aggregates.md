# RFC 0020 — Report SQL: joins to sources, ordered and filtered aggregates, exact averages, null-safe equality

- **Status:** draft. Prompted by the LitePOS 0.10.0 report trial (gap items 3, 4, 5, 9 and 10).
- **Affects:**
  - **`api/17`:** new R-SRC-21 (a model rooted at an entity joins a source on the source's full key) and R-SRC-22
    (how the join renders, which statements carry it, locks and fetch plans); R-SRC-11's lock bullet and R-SRC-12's
    "every statement" list name the joined source; new AC-SRC-42 to AC-SRC-47. `MQ3035` (a `@Join` *on* a model rooted
    at a source) is unchanged (Part A).
  - **`api/13`:** new R-AGG-16 (`Agg.firstBy`), R-AGG-17 (an aggregate's `filter`), R-AGG-18 (`Agg.avgExact` and
    `Agg.round`); R-AGG-03's table gains the exact average row; new AC-AGG-18 to AC-AGG-26 (Parts B, C, D).
  - **`api/10`:** new R-COL-37, `Expr.round` over a `BigDecimal` scalar (Part D).
  - **`api/12`:** new R-FLT-29, `Filters.notDistinctFrom` and `distinctFrom`; R-FLT-13 gains its null-value
    exception; new AC-FLT-37 to AC-FLT-39 (Part E).
  - **`processor/30`, `processor/32`:** new R-PROC-44 (`@JoinSource`, `@SourceColumn`), R-PROC-45 (`@Aggregate`'s new
    `orderBy`, `filter` and `scale` members, and an `AVG` field typed `BigDecimal`); new `MQ3044`, `MQ3045`;
    `MQ3039` widened to a child model that joins a source.
  - **`vendor/40`:** new R-VND-29, `VendorProfile#aggregates(AggregateFeature)`, a database fact; new R-VND-30,
    `ProviderSupport#aggregates()` returning a `core.AggregateRenderer`, a provider fact; `SourceRenderer` gains
    `join` (R-VND-21's hook list). `vendor/41` gains the Tier-1 answers.
  - **`model-query-hibernate`:** `SourceRenderer#join` over `JpaFrom#join(JpaCteCriteria, SqmJoinType)`; an
    `AggregateRenderer` over `HibernateCriteriaBuilder`'s `arrayAgg`, filtered aggregates and `round`.
  - **`reference/90`:** `MQ1910`, `MQ1411`, `MQ1412` (engine); `MQ1903`, `MQ4014` and `MQ4015` rows widened.
  - **`docs/site`:** the sources page (joining a source), the aggregates page (first-by, filter, exact average), the
    filters page (null-safe equality), each runnable snippet in both tabs (CC-DOC-01).
- **Discussion:** to open
- **Target:** not scheduled; every new type, member and annotation member is `@Incubating`. All five parts are
  additive (§Compatibility). Rule ids, AC numbers and codes are placeholders, renumbered at acceptance against
  RFC 0019, which is drafted at the same time.

## Summary

Five independent parts close the report-SQL gaps LitePOS measured, in the feedback's order of impact:

- **A.** A model rooted at an entity joins a `@SourceModel`, grouped or not, on the source's full key, rendered as a
  join to the source's CTE. It replaces a correlated sum, which plans as one sub-plan per outer row.
- **B.** `Agg.firstBy(value, order...)`, the value of the first row of each group in an order.
- **C.** `filter(...)` on any aggregate, rendered as `FILTER (WHERE …)` where the database has it and as the CASE form
  everywhere else.
- **D.** `Agg.avgExact`, a `BigDecimal` average, and `round` over a `BigDecimal`, for money reports.
- **E.** `Filters.notDistinctFrom`, null-safe equality, built in rather than a custom operator.

Every part is covered on every target, H2 included: it renders, or it is refused before any statement with a code
`PortabilityCheck` reports per declared target.

## Motivation

LitePOS ported 25 hand-written report methods (Hibernate 7, PostgreSQL 16). 17 could be expressed; 4 were too slow
and 7 could not be expressed. The gaps this RFC covers:

- **No join to a source (item 3).** "Unreconciled days" joins each business day to its sales totals. In SQL it is a
  hash join over a grouped sub-query: 1 ms, 20 buffers. In model-query the only route is two `Correlated.sum` columns,
  which PostgreSQL runs as two sub-plans per day: 2–3 ms and about 2,200 buffers over 365 days. RFC 0015 §6 chose
  the scalar form for a correlated column in every position, with no automatic join lowering (D-150); that stays.
  What is missing is a way for a model to *ask* for the join.
- **No first-by aggregate (item 4).** "Top item per group", `(array_agg(name ORDER BY qty DESC))[1]`, blocked 2
  methods.
- **No `FILTER (WHERE …)` (item 5).** `sum(cases(...).when(cond, AMOUNT).otherwise(ZERO))` (R-AGG-13) gives the right
  rows, but the model no longer reads like the SQL it replaces, and inspection shows a CASE where the author wrote a
  condition.
- **`avg` is a `Double` (item 9).** `Agg.avg` returns `Double` (`Agg.java:98`, R-AGG-03), and there is no `ROUND`.
  An average of `numeric(12,2)` amounts loses exactness in the JDBC read even where the database computed it exactly.
- **No null-safe equality (item 10).** `col = ?` never matches NULL, so a key that can be NULL needs
  `or(eq(...), and(isNull(...), …))` by hand, which also has to know whether the value is null.

## Design

### Part A — joining a source (R-SRC-21, R-SRC-22)

**A.1 The model.** A model rooted at an entity declares a joined source with `@JoinSource` and reads its fields with
`@SourceColumn`:

```java
@SourceModel(root = SaleEntity.class)
public record DaySales(
        @PrimaryKey @GroupBy LocalDate businessDate,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "total") BigDecimal total,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "cashTotal") BigDecimal cash) {}

@QueryModel(root = BusinessDayEntity.class)
@JoinSource(name = "sales", source = DaySales.class, on = @JoinSource.On(column = "businessDate", key = "businessDate"))
public record UnreconciledDay(
        @PrimaryKey Long id,
        LocalDate businessDate,
        BigDecimal countedCash,
        @SourceColumn(join = "sales", field = "cash") BigDecimal salesCash) {}
```

- **`on` covers the source's full key, and only it.** Each `On` pairs an outer column (an entity path, through to-one
  joins as `@Column.attribute` allows) with one source key field; the pairs must name every key field once
  (R-SRC-02). Since the source key is unique per row (§2 of `api/17`), the join adds at most one row per outer row: no
  row multiplication, so the outer key, cursors, counts and export pages keep R-PAG-*'s guarantees unchanged.
  A partial key, an extra pair, a field that is not a key field, or a type mismatch (INV-3) is `MQ3044`.
- **The source must be unique.** A source R-SRC-09 treats as non-unique (a set operation it refuses key-based features
  for) is `MQ3044` at compile time and `MQ1910` for a hand-written `SourceJoin` when the query is built.
- **`type` is `JoinKind.LEFT` by default**; `INNER` keeps only outer rows that have a source row. No other kind.
- **`@SourceColumn` instead of a `@Column` member.** `@Column.attribute` is an entity path the processor checks
  against the metamodel; a source field is checked against the source model, by name and type (MQ3001, MQ3002), as an
  outer model rooted at a source already is (R-PROC-39). A separate annotation keeps the two checks apart. Its
  converter is the source field's; a `converter` on it is `MQ3014`, as on a source-rooted outer model.
- **Several joined sources** are allowed, each with its own `name`. A joined source may itself be a source over a
  source (R-SRC-16); its chain is registered on the top statement as R-SRC-18 registers any chain.
- **Unchanged:** `MQ3035`. A `@Join` *on* a model rooted at a source stays refused, since a source root has no
  associations; joining a source to a source-rooted model is not in this RFC.

**A.2 Generated code and runtime API.** The generated `query(...)` takes each joined source, in declaration order,
so a query without its source does not compile, as R-SRC-10's `query(SourceQuery<S>)` does:

```java
public static ModelQuery.Builder<BusinessDayEntity, Long, UnreconciledDay> query(SourceQuery<DaySales> sales) {
    return ModelQuery.builder(ROOT, MAPPER).joinSource(SALES, sales);
}
public static final SourceJoin<UnreconciledDay, DaySales> SALES =
        SourceJoin.of("sales", DaySales.class, JoinKind.LEFT,
                List.of(SourceJoin.on(BUSINESS_DATE, QDaySales.BUSINESS_DATE)));
public static final ColumnField<UnreconciledDay, BigDecimal, BigDecimal> SALES_CASH =
        ColumnField.over(UnreconciledDay.class, SALES.root(), QDaySales.CASH);
```

- `@Incubating SourceJoin<M, S>` (`core`) is immutable (INV-9): a name, the source model, the kind and the key pairs.
  `SALES.root()` is a `TableField.source(...)` root (R-COL-30) whose columns `ColumnField.over` reads by
  `mq_<property>` (R-SRC-12), so filters, orders and `Row.get` treat a joined column as any column.
- `ModelQuery.Builder#joinSource(SourceJoin<M, S>, SourceQuery<S>)` binds the `SourceQuery` to the definition. A
  `SourceJoin` whose pairs do not cover the source's key, or a second binding of one join, is `MQ1910` when the query
  is built. Every builder copy (`withFetch`, `orderedBy`, …) carries the bindings, as it carries a source today.
- A joined column is nullable under `LEFT`. Ordering a keyset page by one follows the existing nullable-key rules
  (R-COL-12, R-PAG-*); this RFC changes none of them.

**A.3 Rendering.** The engine builds the source's body exactly as R-SRC-12 does, registers it with the existing
`SourceRenderer#register`, and joins it with a new hook:

```java
// SourceRenderer, @EngineFacing @Incubating; default throws MQ4014 ("joined sources")
default From<?, Tuple> join(From<?, ?> outer, CteHandle source, JoinType type);
```

The engine then adds the `on` predicates with `Join#on` over standard Criteria, so the key comparison stays in
`core`. SQL, every vendor:

```sql
with sales as (select s.business_date mq_businessDate, sum(s.cash_total) mq_cash from sale s group by s.business_date)
select d.id, d.business_date, d.counted_cash, x.mq_cash
from business_day d left join sales x on x.mq_businessDate = d.business_date
```

- **CTE, not a derived table (recommended).** The CTE reuses R-SRC-12's body, parameter binding and chain
  registration unchanged, and needs one hook. PostgreSQL 12+ inlines a non-recursive CTE referenced once, so its plan
  is the derived table's; MySQL 8.0 merges or materializes a CTE as it does a derived table; H2 runs both. The spike
  compares both forms; a vendor whose CTE plan loses would get the derived form behind the same `join` hook
  (`JpaFrom#join(Subquery, SqmJoinType)`), with no API change.
- **Every statement carries the join** (R-SRC-12's list: content, count, both primary-key-first steps, every export
  page, every `byKeys` chunk, the parent reads of a fetch plan), even a `LEFT` join no filter reads. One rule needs
  no proof that dropping it keeps the count; PostgreSQL removes an unreferenced LEFT join to a relation unique on the
  join keys by itself, which the spike records.
- **Locks.** `withLock(...)` on a query with a joined source is `MQ1903` (widened), for R-SRC-11's reason.
- **Fetch plans.** A parent model that joins a source carries it on its parent reads. A `@Child` whose child model
  joins a source is `MQ3039` (widened): the fetch plan has no `SourceQuery` to pass it.
- **Writes.** `ModelUpdate`, `ModelDelete`, `InsertColumns` and `ModelInsert.select` take no source, as today.
- **Cost, documented, not hidden.** A grouped source aggregates every row its `where` admits, on every statement,
  even for a page of 20; a correlated column probes one index entry per row. RFC 0015 §6's table stands, so the docs
  say: join a source for exports and whole-table reports, or when the correlation has no index; keep `Correlated.*`
  for pages over an indexed child; narrow the source's own `where` either way.

**A.4 Vendors.** The join is a plain CTE: `VendorProfile#sources(SourceFeature.CTE)` answers it (R-VND-21), `true` on
H2, PostgreSQL and MySQL, `false` on `OTHER`, which is `MQ4015` before any statement. No new `SourceFeature`. A
provider without the `join` hook is `MQ4014`. LATERAL stays out (D-145): the join is keyed, not correlated.

**A.5 Hibernate.** Both lines expose `JpaFrom#join(JpaCteCriteria<X>, SqmJoinType)` and
`JpaFrom#join(Subquery<X>, SqmJoinType)` (checked with `javap` on 6.6.58 and 7.4.11), and `JpaJoinedFrom#on`. The
spike confirms the rendered SQL on all three vendors, the `on` over a to-one path, and a joined CTE inside a
statement that already has the root's own CTEs.

### Part B — the first value in a group (R-AGG-16)

```java
public static <M, C> AggregateField<M, C> firstBy(ScalarField<M, C> value, OrderField<M, ?> order,
        OrderField<M, ?>... more);

public static final AggregateField<CategorySales, String> TOP_ITEM =
        Agg.firstBy(QCategorySales.ITEM_NAME, QCategorySales.QTY.desc().nullsLast());
// @Aggregate(fn = AggregateFunction.FIRST_BY, attribute = "item.name", orderBy = "qty desc nulls last") String topItem
```

- **Deterministic.** The engine appends the root's id ascending (every id component, by attribute name), as R-COL-32
  does for `Correlated.first`, so ties pick the same row on every vendor. Over a source root the source key is
  appended. Null precedence follows R-COL-12; the docs advise an explicit `nullsFirst()` / `nullsLast()`.
- **Result type** is the value's own type, converted as `min`/`max` convert (D-93): `value` must be a column with no
  converter or an `OrderedColumnConverter`, or an expression. A group with no row is NULL.
- **Rendering**, through `AggregateRenderer#firstBy(cb, value, orders)` (R-VND-30):

| Vendor | SQL | Answer |
|---|---|---|
| PostgreSQL | `(array_agg(v order by k desc nulls last, id))[1]` | `FIRST_BY` true |
| H2 | `array_agg(v order by k desc nulls last, id)[1]` | `FIRST_BY` true |
| MySQL 8.0 | none: no array aggregate; `group_concat` truncates at `group_concat_max_len`, works on text only and collides with its separator | `FIRST_BY` false, `MQ4015` |
| `OTHER` | — | default false, `MQ4015` |

- **Portable route, documented for MySQL.** A source with a `ROW_NUMBER` window partitioned by the group and ordered
  by the key, read by a source over it (R-SRC-16) filtered on `rank = 1`, then joined (Part A). It runs on every
  Tier-1 vendor today with Part A; the docs give it next to `firstBy`. It is not an automatic fallback: it changes
  the statement's shape and its cost, which the author should choose.
- **Hibernate.** Both lines have `HibernateCriteriaBuilder#arrayAgg(JpaOrder, Expression)`; the element read is
  `arrayGet` (HQL `array_get`), whose PostgreSQL and H2 rendering the spike records. Both also offer `mode(...)`,
  `listagg(...)` and `functionWithinGroup(...)`; none of them returns the first row's value of a non-text column, so
  none is the rendering. A provider without the renderer is `MQ4014`.

### Part C — `filter` on an aggregate (R-AGG-17)

```java
public AggregateField<M, C> filter(UnaryOperator<Filters<M>> condition);   // on AggregateField, returns a new field

public static final AggregateField<DaySummary, BigDecimal> REFUNDS =
        Agg.sum(QDaySummary.AMOUNT).filter(f -> f.eq(QDaySummary.KIND, SaleKind.REFUND));
// @Aggregate(fn = SUM, attribute = "amount", filter = RefundsOnly.class) BigDecimal refunds
```

- **API.** `filter` returns a new immutable `AggregateField` (INV-9); a second `filter` on it is `MQ1412`. Equality
  is R-AGG-01's plus the condition, compared structurally as `Expr.cases` compares its `when` conditions. In the
  annotation, `filter` names a class implementing a new `@Incubating FilterDefinition<M>` (`core`), whose
  `UnaryOperator<Filters<M>> condition()` is read once when the constants initialise, as `ExpressionDefinition` is
  (R-PROC-22); it is declared `Class<?>` for the same module reason.
- **What the condition may read.** Row-level columns and expressions of the aggregated rows, as `where` does. An
  aggregate, a window field or a correlated value inside it is `MQ1412`, when the query is built. `filter` on
  `Agg.of(...)` is `MQ1412` too: the engine cannot place a condition inside a lambda's expression.
- **Rendering**, through `AggregateRenderer#filtered(cb, aggregate, predicate)`: Hibernate renders `sum(x) filter
  (where …)` where its dialect supports the clause (PostgreSQL, H2) and its own CASE emulation elsewhere (MySQL). With
  no renderer the engine renders the CASE form itself in standard Criteria: `fn(case when cond then x end)`, and
  `count(case when cond then 1 end)` for a row count. The two are equal for `count`, `countDistinct`, `sum`, `avg`,
  `min`, `max` and `firstBy` (an `array_agg ... filter`), since every one ignores NULL inputs. So there is no
  refusal and no vendor fact: every target runs it, and the provider only improves the SQL's shape (INV-6: no vendor
  branch in `core`).
- **`having` over a filtered aggregate** works as over any aggregate (R-AGG-06).
- **Hibernate.** Both lines have `count`, `sum` and `avg` overloads taking a `JpaPredicate`, and
  `functionAggregate(name, type, filter, args)` for `min` and `max`. `sum(Expression<N>, JpaPredicate)` is typed
  `Number`, so the renderer must keep R-AGG-03's result type; the spike checks what each line reads back.

### Part D — exact average and rounding (R-AGG-18, R-COL-37)

```java
public static <M> AggregateField<M, BigDecimal> avgExact(ColumnField<M, ?, ? extends Number> column);
public static <M> AggregateField<M, BigDecimal> avgExact(ExpressionField<M, ? extends Number> expression);
public static <M> AggregateField<M, BigDecimal> round(AggregateField<M, BigDecimal> value, int scale);   // Agg
public static <M> ExpressionField<M, BigDecimal> round(ScalarField<M, BigDecimal> value, int scale);     // Expr
```

- **Input.** `BigDecimal`, `Long`, `Integer` and `Short` columns or expressions. A `Double` or `Float` is `MQ1411`
  when the factory is called (D-25's reason), since its average is inexact on every vendor; a converted column is
  `MQ1408`, as for `avg`.
- **Annotation.** `@Aggregate(fn = AVG)` on a `BigDecimal` component is the exact average, rather than `MQ3205`;
  `@Aggregate(scale = n)` rounds it. `scale` on a non-`BigDecimal` field is `MQ3045`. The Java type already names the
  intent, so no new `AggregateFunction` constant.
- **Rounding** is half away from zero on PostgreSQL `numeric`, MySQL `DECIMAL` and H2 `NUMERIC`, so `round` takes a
  `BigDecimal` only. A `Double` round differs (MySQL rounds approximate values to even), so it doesn't compile. A
  negative `scale` or one over 38 is `MQ1411`. Standard Criteria has `CriteriaBuilder#round` (Jakarta Persistence 3.1),
  on both lines.
- **Scale of the average** follows the database unless rounded, as a decimal quotient's does (R-VND-09,
  R-COL-17): PostgreSQL's `avg(numeric)` scale, MySQL's `div_precision_increment`. The docs recommend `round` for money.
  Zero rows is NULL, as `sum` is (R-AGG-04).
- **Rendering**, through `AggregateRenderer#exactAverage(cb, expression)`. Hibernate types `avg` as `Double` on both
  lines (`JpaExpression<Double> avg(...)`) and reads the column as a double, so a `BigDecimal` result needs the
  provider to type it. The candidates, decided by the spike on all three vendors and both lines:
  1. `avg(x)` rendered with a `BigDecimal` result type (`functionAggregate("avg", BigDecimal.class, …)`), if the
     dialect's average descriptor keeps the argument uncast;
  2. `sum(x) / count(x)` with the numerator typed `BigDecimal`, which every vendor computes in exact arithmetic.
  The first that returns the database's exact value with no cast to double is the rendering. A provider without the
  renderer is `MQ4014`; no vendor answer is needed, since all three compute an exact average.

### Part E — null-safe equality (R-FLT-29)

```java
public <C> Filters<M> notDistinctFrom(ScalarField<M, C> column, C value);              // null value: is null
public <C> Filters<M> notDistinctFrom(ScalarField<M, C> column, ScalarField<M, C> other);
public <C> Filters<M> distinctFrom(ScalarField<M, C> column, C value);
public <C> Filters<M> distinctFrom(ScalarField<M, C> column, ScalarField<M, C> other);
```

- **A value operand resolves when the filter is added.** A non-null value renders `col = ?`; a null one renders
  `col is null` (`distinctFrom`: `col <> ? or col is null`, and `col is not null`). R-FLT-13 gains its exception:
  a `null` here is a value, not a skip; an empty `Optional` still skips. The form is exact on every vendor and keeps
  the index the plain filter would use. Inspection records the operator with its value.
- **Two operands** render the portable form `(a = b or (a is null and b is null))` in standard Criteria (negated
  for `distinctFrom`), which every vendor runs and PostgreSQL can serve from an index, where its native `IS NOT
  DISTINCT FROM` cannot. This is also what an `exists` correlation or a `Correlated` key over a nullable column uses.
  The spike compares it with Hibernate's `notDistinctFrom` (PostgreSQL and H2 `is not distinct from`, MySQL `<=>`); if
  the native form wins on a vendor, it moves behind a provider hook with this form as its fallback, with no API change.
- **Converted columns.** The value is converted as `eq`'s is (R-FLT-06); two operands need the same converter, else
  `MQ1001` (R-COL-14), as any column comparison.
- **Why built in, not a custom operator (RFC 0017).** An operator's value is always a bound parameter its renderer
  receives (R-FLT-21), so it cannot become `is null` when the filter is added; it would render
  `col is not distinct from ?` everywhere, which PostgreSQL does not index. A catalogue operator would also need an
  `OperatorFeature` that H2 and MySQL would answer through Hibernate's emulation, so the gate would say nothing about
  the SQL. A user who needs it before this ships can still write
  `FilterOperator.of("notDistinctFrom", (ctx, l, r) -> ((HibernateCriteriaBuilder) ctx.cb()).notDistinctFrom(l, r))`.
- **Hibernate.** Both lines have `distinctFrom` and `notDistinctFrom` on `HibernateCriteriaBuilder`; the built-in needs
  neither, since both forms are standard Criteria. No provider hook, no vendor fact.

### Vendor and provider SPI

- **`VendorProfile#aggregates(AggregateFeature)`** (`jpa.spi`, R-VND-29), `@Incubating`, `false` by default, a database
  fact as `sources(SourceFeature)` is. One constant now, `FIRST_BY`: true on PostgreSQL and H2, false on MySQL and
  `OTHER`. False is `MQ4015`, naming the feature, the vendor and the field, before any statement.
- **`ProviderSupport#aggregates()`** (R-VND-30) returns an `Optional<AggregateRenderer>`, empty by default. The
  `@EngineFacing @Incubating core.AggregateRenderer` has `firstBy`, `filtered` and `exactAverage`; `firstBy` and
  `exactAverage` default to `MQ4014`, and `filtered` defaults to the engine's CASE form. The executor folds the vendor
  answers into an immutable gate carried by `RenderOptions`, as R-VND-23 does for operators, so `core` sees no profile
  (INV-6, INV-7). `PortabilityCheck` reports `MQ4014` and `MQ4015` per declared target, with no database.
- **`SourceRenderer#join`** (Part A), default `MQ4014`.
- **`model-query-hibernate`** implements all three on both lines; `jpa` still imports no `org.hibernate` (INV-7).

### Spike plan

A throwaway branch `spike/rfc-0020` with one test-source module `spike-rfc0020/`, on Hibernate 6.6.58.Final and
7.4.11.Final, against H2, PostgreSQL 16 (`postgres:16-alpine`) and MySQL 8.0.31. Results go to
`rfc/0020-spike-results.md` in `rfc/0018-spike-results.md`'s shape: a verdict per part first, then **Matrix**,
**Findings** (one section per part), **Line parity** and **Consequences for the RFC**; raw data (SQL, binds, row
results, timings, plans) in `rfc/0020-spike-data/` with a README of reproduction commands. Production `src/main` and
committed SQL snapshots stay unchanged.

The dataset is LitePOS's shape: 365 business days, 30 days of sales at about 3k lines a day, a year of inventory
movements, with and without an index on the sale's business-date column.

1. **A.1 Plans first.** "Unreconciled days" as (a) two correlated sums, (b) a LEFT join to the grouped CTE, (c) a LEFT
   join to the grouped derived table; as a page of 20, a full export and a count. `EXPLAIN (ANALYZE, BUFFERS)` on
   PostgreSQL, `EXPLAIN ANALYZE` on MySQL, `EXPLAIN` on H2, and medians of 7 alternating runs. Records whether
   PostgreSQL inlines the CTE, whether MySQL merges or materializes it, and whether PostgreSQL removes an unreferenced
   LEFT join in the count.
2. **A.2 Rendering.** `JpaFrom#join(JpaCteCriteria, SqmJoinType)` with `on` over a to-one path, `LEFT` and `INNER`, on
   every statement kind of R-SRC-12's list; two joined sources; a joined source over a source; an outer `where` and an
   order on a joined column; a joined CTE next to a root CTE. The SQL and the rows per vendor and line.
3. **B.1 First-by.** `arrayAgg` plus `arrayGet` on PostgreSQL and H2: SQL, values under ties with the appended id, a
   NULL order key with `nullsLast()`, a converted value; what Hibernate's MySQL dialect does with `arrayAgg` (expected
   to fail, recorded). **B.2** the ranked-source route on all three vendors, with A, compared with B.1's plan on
   PostgreSQL, against LitePOS's two blocked methods.
4. **C.1 Filter.** `count`, `sum`, `avg` with a `JpaPredicate`, `min`/`max` via `functionAggregate`, `countDistinct`
   with a filter, and the engine's CASE form: rendered SQL per vendor (FILTER or CASE), equal rows, result types.
5. **D.1 Exact average.** Both candidate renderings over `numeric(12,2)`, `bigint` and `integer` columns: JDBC type
   read, value and scale per vendor, zero rows. **D.2** `round` over `2.345`, `-2.345`, `2.5` and `-2.5` per vendor.
6. **E.1 Null-safe equality.** The value form's SQL; the two-operand OR form against `notDistinctFrom` in a filter and
   in a correlated `exists`: plans and index use on PostgreSQL and MySQL, rows on all three.
7. **Line parity.** The probe module compiles and behaves the same on 6.6 and 7.4.

### New ids (placeholders)

| Id | What |
|---|---|
| R-SRC-21 | A model rooted at an entity joins a unique source on its full key (`@JoinSource`, `SourceJoin`, `joinSource`) |
| R-SRC-22 | The join renders as a join to the source's CTE on every statement; locks `MQ1903`; child models `MQ3039` |
| R-AGG-16 | `Agg.firstBy`, ordered with the appended id; `FIRST_BY` vendor fact |
| R-AGG-17 | `AggregateField#filter`, FILTER or the CASE form |
| R-AGG-18 | `Agg.avgExact`, `Agg.round`; R-AGG-03's exact-average row |
| R-COL-37 | `Expr.round` over a `BigDecimal` scalar |
| R-FLT-29 | `notDistinctFrom` and `distinctFrom`; R-FLT-13's null-value exception |
| R-PROC-44 | `@JoinSource` and `@SourceColumn` checks |
| R-PROC-45 | `@Aggregate`'s `orderBy`, `filter`, `scale`; `AVG` typed `BigDecimal` |
| R-VND-29 | `VendorProfile#aggregates(AggregateFeature)` |
| R-VND-30 | `ProviderSupport#aggregates()`, `AggregateRenderer`; `SourceRenderer#join` |

| AC | Asserts |
|---|---|
| AC-SRC-42 | A LEFT-joined grouped source returns the hand-written SQL's rows; an outer row with no source row reads NULL |
| AC-SRC-43 | The join is in every statement of R-SRC-12's list, with the body's parameters bound once per statement |
| AC-SRC-44 | `INNER` drops unmatched outer rows in content and count alike; keyset pages over a joined order key lose no row |
| AC-SRC-45 | `MQ3044`: partial key, extra pair, type mismatch, non-unique source; `MQ1910` for the hand-written forms |
| AC-SRC-46 | `MQ1903` for a lock, `MQ3039` for a child model that joins a source, `MQ3035` unchanged |
| AC-SRC-47 | `OTHER` target: `MQ4015` before any statement; `PortabilityCheck` reports it |
| AC-AGG-18 | `firstBy` returns the same value on PostgreSQL and H2 under ties and NULL keys |
| AC-AGG-19 | `firstBy` on MySQL is `MQ4015` before any statement; `PortabilityCheck` reports it |
| AC-AGG-20 | A filtered `sum`/`count`/`avg`/`min`/`max`/`countDistinct` equals its CASE form on every vendor |
| AC-AGG-21 | Without a renderer the engine's CASE form runs; a filter reading an aggregate, or on `Agg.of`, is `MQ1412` |
| AC-AGG-22 | A filtered aggregate in `having`; equality of two filtered fields |
| AC-AGG-23 | `avgExact` over `numeric(12,2)` returns the database's exact `BigDecimal` on every vendor; zero rows NULL |
| AC-AGG-24 | `avgExact` over a `Double` column, a bad `scale`: `MQ1411` |
| AC-AGG-25 | `round` half away from zero on every vendor, aggregate and row-level |
| AC-AGG-26 | Generated: `@Aggregate(fn = AVG) BigDecimal`, `scale`, `filter`, `FIRST_BY` with `orderBy`; `MQ3045` cases |
| AC-FLT-37 | `notDistinctFrom(col, null)` renders `is null`; a non-null value renders `=`; `distinctFrom` both ways |
| AC-FLT-38 | Two operands match NULL to NULL on every vendor, in `where` and in a correlated `exists` |
| AC-FLT-39 | An empty `Optional` still skips; inspection records the operator |

| Code | Meaning |
|---|---|
| `MQ1910` | A hand-written `SourceJoin` whose pairs miss the source's key, over a non-unique source, or bound twice |
| `MQ1411` | `avgExact` over an inexact column; a `round` scale below 0 or above 38 |
| `MQ1412` | An aggregate filter that reads an aggregate, window or correlated value, sits on `Agg.of`, or repeats |
| `MQ3044` | A `@JoinSource` whose `on` misses or exceeds the source key, mismatches a type, names an unknown field or a non-unique source; a `@SourceColumn` naming an unknown join or field |
| `MQ3045` | `orderBy` without `FIRST_BY` or `FIRST_BY` without it; `filter` naming a class that is not a `FilterDefinition`; `scale` on a non-`BigDecimal` field |
| `MQ1903`, `MQ3039`, `MQ4014`, `MQ4015` | Widened as above; meanings unchanged |

Each new code was checked unused in `reference/90` and `processor/32` when this draft was written.

## Compatibility

All additive. No shipped signature or result changes: `Agg.avg` still returns `Double`; `@Aggregate(fn = AVG)` on a
`BigDecimal` component was `MQ3205` and now compiles as the exact average; `@Aggregate` gains members with defaults;
`VendorProfile`, `ProviderSupport`, `SourceRenderer` and `AggregateRenderer` gain default methods only, so a
third-party profile or provider fails safe (`false`, `MQ4014`). No committed SQL snapshot changes, since nothing
existing renders differently. Everything new is `@Incubating`; the freeze review decides each piece.

## Alternatives

- **Do nothing.** Each gap has a stand-in (a correlated sum, a CASE, a hand-written null check, `Agg.of`), but the
  first costs two orders of magnitude in buffers and the others make a model harder to read than the SQL.
- **Lower a correlated column to a join automatically.** RFC 0015 §6 measured it: it loses on MySQL pages with an
  index and wins only without one. D-150 keeps the scalar form; an explicit join is the author's choice.
- **LATERAL joins.** Rejected by D-145 (H2 has none). A keyed join to a CTE needs no correlation.
- **A derived table instead of a CTE.** Kept as the per-vendor fallback behind the same hook if the spike shows a
  plan loss; a CTE reuses all of R-SRC-12.
- **`firstBy` emulated on MySQL** through `group_concat` or a correlated `first` per group. The first truncates and
  works on text only; the second is the per-row sub-plan Part A removes. The ranked-source route is documented
  instead.
- **`filter` refused where the database lacks FILTER.** Needless: the CASE form is exactly equal for every aggregate
  here.
- **A new `AggregateFunction.AVG_EXACT`.** The component's type already says it; a second constant would let the two
  disagree.
- **`round` over `Double`.** Vendors round approximate values differently (R-VND-10: never silently emulated).
- **Null-safe equality as a catalogue operator** (RFC 0017). It cannot resolve a null value at add time, and the
  native SQL is unindexed on PostgreSQL (Part E).

## Unresolved questions

Only what the spike measures; each has its default:

1. **A:** whether the CTE join plans as the derived-table join on PostgreSQL and MySQL (default: CTE everywhere).
2. **B:** how Hibernate renders the array element read on PostgreSQL and H2 on each line, and whether the
   ranked-source route covers LitePOS's two blocked methods at a comparable plan (default: ship `firstBy` as above).
3. **D:** which exact-average candidate reads the database's exact value on all three vendors (default: candidate 2).
4. **E:** whether the OR form keeps its index on MySQL as it does on PostgreSQL (default: the OR form everywhere).
