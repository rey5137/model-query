# RFC 0020 — Report SQL: joins to sources, ordered and filtered aggregates, exact averages, null-safe equality

- **Status:** accepted (D-185), after the acceptance spike (`rfc/0020-spike-results.md`). Prompted by the LitePOS 0.10.0 report trial (gap items 3, 4, 5, 9 and 10); Part F by RFC 0019's
  spike (`rfc/0019-spike-results.md`, rule 2) and D-184's amendment.
- **Affects:**
  - **`api/17`:** new R-SRC-21 (a model rooted at an entity joins a source on the source's full key) and R-SRC-22
    (how the join renders, which statements carry it, locks and fetch plans); R-SRC-11's lock bullet and R-SRC-12's
    "every statement" list name the joined source; new AC-SRC-42 to AC-SRC-47. `MQ3035` (a `@Join` *on* a model rooted
    at a source) is unchanged (Part A).
  - **`api/13`:** new R-AGG-16 (`Agg.firstBy`), R-AGG-17 (an aggregate's `filter`), R-AGG-18 (`Agg.avgExact` and
    `Agg.round`); R-AGG-03's table gains the exact average row; new AC-AGG-18 to AC-AGG-26 (Parts B, C, D).
  - **`api/10`:** new R-COL-37, `Expr.round` over a `BigDecimal` scalar (Part D).
  - **`api/12`:** new R-FLT-29, `Filters.notDistinctFrom` and `distinctFrom`; §1's `MQ1301` rule (the value form with
    `null`) and AC-FLT-02 gain the exception (R-FLT-13's `Optional` skip is unchanged); new AC-FLT-37 to AC-FLT-39
    (Part E).
  - **`processor/30`, `processor/32`:** new R-PROC-44 (`@JoinSource`, `@SourceColumn`), R-PROC-45 (`@Aggregate`'s new
    `orderBy`, `filter` and `scale` members, and an `AVG` field typed `BigDecimal`); new `MQ3044`, `MQ3045`;
    `MQ3039` widened to a child model that joins a source.
  - **`vendor/40`:** new R-VND-29, `VendorProfile#aggregates(AggregateFeature)`, a database fact; new R-VND-30,
    `ProviderSupport#aggregates()` returning a `core.AggregateRenderer`, a provider fact; R-VND-29 also gains
    `VendorProfile#nativeNullSafeEquality()` and R-VND-30 `ProviderSupport#nullSafeEquality()` (Part E);
    `SourceRenderer` gains `join` (R-VND-21's hook list). `vendor/41` gains the Tier-1 answers.
  - **`model-query-hibernate`:** `SourceRenderer#join` over `JpaFrom#join(JpaCteCriteria, SqmJoinType)`; an
    `AggregateRenderer` over `HibernateCriteriaBuilder`'s `arrayAgg`, filtered aggregates and `round`.
  - **`api/13`, `engine/20`, `api/10`:** new R-AGG-19, a count of a row's identity renders `count(*)` (Part F);
    R-EXE-05's page total and `api/10`'s `count(root.<id>)` sentence follow it; new AC-AGG-27 to AC-AGG-29.
  - **`reference/90`:** new `MQ1910`, `MQ1411`, `MQ1412` (engine) and `MQ3045` (processor, Parts B to D); `MQ1903`, `MQ4014` and
    `MQ4015` rows widened.
  - **`docs/site`:** the sources page (joining a source), the aggregates page (first-by, filter, exact average), the
    filters page (null-safe equality), each runnable snippet in both tabs (CC-DOC-01).
- **Discussion:** two architect rounds and the acceptance spike
- **Target:** 0.11.0, as milestone M32 (the freeze moves to M33); every new type, member and annotation member is
  `@Incubating`. Parts A to E are additive; Part F changes rendered SQL, not results (§Compatibility). The ids below
  were checked against RFC 0019's: D-184 released R-COL-38, R-QRY-18, R-FLT-30, R-EXE-17, R-VND-31, `MQ1210` and
  `MQ2215` unused, and none of this RFC's ids collides with a released one, so they stand as written.

## Summary

Six independent parts close the report-SQL gaps LitePOS measured, in the feedback's order of impact, and one
rendering cost RFC 0019's spike measured:

- **A.** A model rooted at an entity joins a `@SourceModel`, grouped or not, on the source's full key, rendered as a
  join to the source's CTE. It replaces a correlated sum, which plans as one sub-plan per outer row.
- **B.** `Agg.firstBy(value, order...)`, the value of the first row of each group in an order.
- **C.** `filter(...)` on any aggregate, rendered as `FILTER (WHERE …)` where the database has it and as the CASE form
  everywhere else.
- **D.** `Agg.avgExact`, a `BigDecimal` average, and `round` over a `BigDecimal`, for money reports.
- **E.** `Filters.notDistinctFrom`, null-safe equality, built in rather than a custom operator.
- **F.** A count of a row's identity (`Agg.count(table)`, the page total) renders `count(*)` where the counted table
  is on every row, rather than `count(t.id)`, which the database null-checks row by row.

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
  `SALES.root()` is a table field of a new kind, a *joined source* keyed by the join's name, whose columns
  `ColumnField.over` reads by `mq_<property>` (R-SRC-12), so filters, orders and `Row.get` treat a joined column as
  any column. `TableField.source(Class)` (`TableField.java:111`) cannot serve: it is keyed by the source class only,
  so two joins of one source model would collide, and it resolves to `ctx.root`, not to the joined `From`. The new
  kind resolves to its own join in `JoinContext` and is equal only to a field of the same join name.
- **`Agg.count` over a joined source.** `Agg.count(table)` renders `count(1)` for any source table (`Agg.java:50-54`),
  so an unmatched row of a `LEFT`-joined source would count 1. Over a `LEFT`-joined source it renders
  `count(x.mq_<first key field>)` (a NULL key never matches the `on` `=`, so an unmatched row counts 0); over an
  `INNER` one it is the row count (Part F). The kind comes from the `SourceJoin`; the `Join#getParent()` walk of F.1
  applies to entity joins only.
- `ModelQuery.Builder#joinSource(SourceJoin<M, S>, SourceQuery<S>)` binds the `SourceQuery` to the definition. A
  `SourceJoin` whose pairs do not cover the source's key, or a second binding of one join, is `MQ1910` when the query
  is built. Every builder copy (`withFetch`, `orderedBy`, …) carries the bindings, as it carries a source today.
- A joined column is nullable under `LEFT`. Ordering a keyset page by one follows the existing nullable-key rules
  (R-COL-12, R-PAG-*); this RFC changes none of them.

**A.3 Rendering.** The engine builds the source's body exactly as R-SRC-12 does, registers it with the existing
`SourceRenderer#register`, and joins it with a new hook:

```java
// SourceRenderer, @EngineFacing @Incubating; default throws MQ4014 ("joined sources")
default From<?, Tuple> join(From<?, ?> outer, CteHandle source, JoinType type,
        Function<From<?, Tuple>, Predicate> on);
```

The engine builds the key predicate in `core` over standard Criteria; the provider applies it to the new join, once
per statement. `core` never calls `Join#on` itself, since the type the provider holds is not always a `Join` (A.5).
SQL, every vendor:

```sql
with sales as (select s.business_date mq_businessDate, sum(s.cash_total) mq_cash from sale s group by s.business_date)
select d.id, d.business_date, d.counted_cash, x.mq_cash
from business_day d left join sales x on x.mq_businessDate = d.business_date
```

- **CTE, not a derived table (recommended).** The CTE reuses R-SRC-12's body, parameter binding and chain
  registration unchanged, and needs one hook. PostgreSQL 12+ inlines a non-recursive CTE referenced once, so its plan
  is the derived table's; MySQL 8.0 merges or materializes a CTE as it does a derived table. H2 never receives the
  CTE: Hibernate inlines a joined CTE as a derived table, and H2 evaluates it once per outer row (spike A.1, about
  265 ms for 365 outer rows over a grouped source with no index on the join key; 37 to 46 s for a window source).
  H2 is a test database; the docs say a joined source there wants an index on the join key and a few dozen outer
  rows at most. A vendor whose CTE plan loses would get the derived form behind the same `join` hook
  (`JpaFrom#join(Subquery, SqmJoinType)`), with no API change.
- **Every statement carries the join** (R-SRC-12's list: content, count, both primary-key-first steps, every export
  page, every `byKeys` chunk, the parent reads of a fetch plan), even a `LEFT` join no filter reads. One rule needs
  no proof that dropping it keeps the count. PostgreSQL removes an unreferenced LEFT join to a relation unique on
  the join keys by itself (spike A.1: 0.38 ms unfiltered, 3.2 ms once a filter reads the source); MySQL and H2 run
  the source in full in every count, filtered or not (13x the plain count on MySQL, over 5,000x on H2 without an
  index).
- **Locks.** `withLock(...)` on a query with a joined source is `MQ1903` (widened), for R-SRC-11's reason.
- **Fetch plans.** A parent model that joins a source carries it on its parent reads. A `@Child` whose child model
  joins a source is `MQ3039` (widened): the fetch plan has no `SourceQuery` to pass it.
- **Writes.** `ModelUpdate`, `ModelDelete`, `InsertColumns` and `ModelInsert.select` take no source, as today.
- **Cost, documented, not hidden.** A grouped source aggregates every row its `where` admits, on every statement,
  even for a page of 20 and its count (on MySQL and H2 the count pays the whole source); a correlated column probes
  one index entry per row, and a filter on it re-evaluates the sub-select. In the spike the joined source won every
  filtered page, export and count on all three vendors, with or without the child index, and tied only the indexed
  count. So the docs say: join a source for a page, export or report that filters or sorts on the aggregate; keep
  `Correlated.*` for an unfiltered page of a few rows over an indexed child; narrow the source's own `where` either
  way.

**A.4 Vendors.** The join is a plain CTE: `VendorProfile#sources(SourceFeature.CTE)` answers it (R-VND-21), `true` on
H2, PostgreSQL and MySQL, `false` on `OTHER`, which is `MQ4015` before any statement. No new `SourceFeature`. A
provider without the `join` hook is `MQ4014`. LATERAL stays out (D-145): the join is keyed, not correlated.

**A.5 Hibernate.** Both lines expose `JpaFrom#join(JpaCteCriteria<X>, SqmJoinType)` and
`JpaFrom#join(Subquery<X>, SqmJoinType)` (checked with `javap` on 6.6.58 and 7.4.11), but the return type differs. On
7.4.11 `JpaJoinedFrom` does not exist and the CTE overload returns `JpaJoin`. On 6.6.58 it returns `JpaJoinedFrom`,
which extends only `JpaFrom` and is not a `jakarta.persistence.criteria.Join`. So the renderer applies `on` through
whichever type its line returns (`JpaJoin#on` or `JpaJoinedFrom#on`), and the hook returns a plain `From`. The spike
checks the derived-table overload's return type the same way, and confirms the rendered SQL on all three vendors, the `on` over a to-one path, and a joined CTE inside a
statement that already has the root's own CTEs.

### Part B — the first value in a group (R-AGG-16)

```java
@SafeVarargs
public static <M, C> AggregateField<M, C> firstBy(ScalarField<M, C> value, OrderField<M, ?> order,
        OrderField<M, ?>... more);

public static final AggregateField<CategorySales, String> TOP_ITEM =
        Agg.firstBy(QCategorySales.ITEM_NAME, QCategorySales.QTY.desc().nullsLast());
// @Aggregate(fn = AggregateFunction.FIRST_BY, attribute = "item.name", orderBy = "qty desc nulls last") String topItem
```

- **Deterministic.** The engine appends the root's id ascending (every id component, by attribute name), as R-COL-32
  does for `Correlated.first`, so ties pick the same row on every vendor. Over a source root the source key is
  appended. Null precedence follows R-COL-12; the docs advise an explicit `nullsFirst()` / `nullsLast()`.
- **Result type** is the value's own type. The value is read, not compared, so its converter (if any) is applied to
  the result as for any column read; no converter restriction. A group with no row is NULL. The renderer must not
  pass a Java result type to its function pattern: called untyped, Hibernate types the element from the array and
  applies the converter (spike B.1); called with the value's class, it bypasses the converter and fails to read the
  stored value.
- **`orderBy` keys** in `@Aggregate` are model fields of the aggregated root; an unknown or malformed key is
  `MQ3045`. A `firstBy` whose value is read through a to-many join is `MQ1412`: the root id does
  not break ties among the joined rows, so the pick would not be deterministic.
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
- **Hibernate.** Both lines have `HibernateCriteriaBuilder#arrayAgg(JpaOrder, Expression)`. The element read is not
  `arrayGet`: on PostgreSQL it renders `array_agg(…)[?]` without parentheses (a syntax error) on both lines, and on H2
  it aggregates twice (spike B.1). The renderer registers its own function pattern, `(?1)[1]` on PostgreSQL and
  `?1[1]` on H2, the table's SQL. Both also offer `mode(...)`,
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
  `Agg.of(...)` is `MQ1412` too: the engine cannot place a condition inside a lambda's expression, as is `filter`
  on a rounded aggregate (`Agg.round(x).filter(c)`; filter before rounding).
- **Joins first needed by a filter.** A join first needed inside an aggregate's `filter` resolves `LEFT` (as R-FLT-10's
  or/not joins do), so it never drops rows from the statement; a to-many path in a filter condition is `MQ1412`.
- **Rendering**, through `AggregateRenderer#filtered(cb, Kind, Expression<?> argument, Predicate)`. `AggregateField`
  holds one opaque function today (`AggregateField.java:63`); it now carries `(kind, argument function)`, so the
  renderer receives the aggregate's kind and its argument expression and can place the predicate. Hibernate renders
  `sum(x) filter (where …)` where its dialect supports the clause (PostgreSQL, H2) and its own CASE emulation elsewhere
  (MySQL). With no renderer the engine renders the CASE form itself in standard Criteria: `fn(case when cond then x
  end)`, and `count(case when cond then <argument> end)` for a count, the argument being the one the unfiltered count
  uses (F.2). The two are equal for `count`, `countDistinct`, `sum`,
  `avg`, `min` and `max`, since every one ignores NULL inputs. `firstBy` is not: `array_agg` keeps NULLs, so a CASE
  yields a NULL element where `FILTER` skips the row. A filtered `firstBy` needs the native `FILTER`: the default
  `filtered` refuses `Kind.FIRST_BY` with `MQ4014`, since its CASE form is the wrong form for it, and a vendor without
  `FIRST_BY` is `MQ4015` first. Every other aggregate has no refusal and no vendor fact:
  every target runs it, and the provider only improves the SQL's shape (INV-6: no vendor branch in `core`).
- **`having` over a filtered aggregate** works as over any aggregate (R-AGG-06).
- **Hibernate.** Both lines have `count`, `sum` and `avg` overloads taking a `JpaPredicate`, and
  `functionAggregate(name, type, filter, args)` for `min` and `max`. `sum(Expression<N>, JpaPredicate)` is typed
  `Number`, so the renderer must keep R-AGG-03's result type. The spike (C.1) found FILTER and CASE rows equal for
  every aggregate on every vendor and line, and C.2 that an implicit path's inner join drops the rows a LEFT filter
  join keeps.

### Part D — exact average and rounding (R-AGG-18, R-COL-37)

```java
public static <M> AggregateField<M, BigDecimal> avgExact(ColumnField<M, ?, ? extends Number> column);
public static <M> AggregateField<M, BigDecimal> avgExact(ExpressionField<M, ? extends Number> expression);
public static <M> AggregateField<M, BigDecimal> round(AggregateField<M, BigDecimal> value, int scale);   // Agg
public static <M> ExpressionField<M, BigDecimal> round(ScalarField<M, BigDecimal> value, int scale);     // Expr
```

- **Input.** `BigDecimal`, `Long`, `Integer` and `Short` columns or expressions. A `Double` or `Float` is `MQ1411`
  when the factory is called (D-25's reason), since its average is inexact on every vendor; a converted column is
  `MQ1408`, as for `avg`. All three integer types pass with the cast below (spike D.1), so no integer type is
  refused; `MQ1411` keeps the rule for any future input type with no passing rendering.
- **Annotation.** `@Aggregate(fn = AVG)` on a `BigDecimal` component is the exact average, rather than `MQ3205`;
  `@Aggregate(scale = n)` rounds it. `scale` on a non-`BigDecimal` field is `MQ3045`, and so is an `AVG` field over an
  input type the factory refuses with `MQ1411`. The Java type already names the
  intent, so no new `AggregateFunction` constant.
- **Integer inputs.** Uncast, `sum(x) / count(x)` is integer division on PostgreSQL and H2, and on every vendor for
  `bigint` read through Hibernate; H2's own `AVG(INTEGER)` and `AVG(SMALLINT)` are `DOUBLE PRECISION`.
  `Expression#as(BigDecimal.class)` renders no SQL cast. The renderer casts an integer input with
  `HibernateCriteriaBuilder#cast(x, BigDecimal.class)` (`cast(x as numeric(38,2))`, MySQL `decimal(38,2)`; `cb.cast`
  on 7.4's Jakarta Persistence 3.2), and candidate 2 then passes for `bigint`, `integer` and `smallint` on every
  vendor and line (spike D.1). Only integer inputs are cast: a `BigDecimal` input is left as is, since the cast's
  default `numeric(38,2)` would round one with more than 2 decimals.
- **Rounding** is half away from zero on PostgreSQL `numeric`, MySQL `DECIMAL` and H2 `NUMERIC`, so `round` takes a
  `BigDecimal` only. A `Double` round differs (MySQL rounds approximate values to even), so it doesn't compile. A
  negative `scale` or one over 30 is `MQ1411`. 30 is the smallest Tier-1 maximum: MySQL clamps any scale over 30 to
  30 with no error, where PostgreSQL honours 31 to 38 (spike D.2), so a larger bound would let MySQL return fewer
  decimals than asked. Standard Criteria has `CriteriaBuilder#round` (Jakarta Persistence 3.1), on both lines.
- **Scale of the average** follows the database unless rounded, as a decimal quotient's does (R-VND-09,
  R-COL-17): PostgreSQL 16, H2 40, MySQL the input's scale + `div_precision_increment` (4 by default: 6 for a
  `numeric(12,2)` input or a cast integer, spike D.1). The docs recommend `round` for money.
  Zero rows is NULL, as `sum` is (R-AGG-04).
- **Rendering**, through `AggregateRenderer#exactAverage(cb, expression)`. Hibernate types `avg` as `Double` on both
  lines (`JpaExpression<Double> avg(...)`) and reads the column as a double, so a `BigDecimal` result needs the
  provider to type it. The candidates, decided by the spike on all three vendors and both lines:
  1. `avg(x)` rendered with a `BigDecimal` result type (`functionAggregate("avg", BigDecimal.class, …)`), if the
     dialect's average descriptor keeps the argument uncast;
  2. `sum(x) / count(x)` with the numerator typed `BigDecimal`, which every vendor computes in exact arithmetic.
  Candidate 2 is the rendering: candidate 1 reads a `Double` on every vendor and line, since Hibernate's `avg`
  descriptor fixes the type, and on H2 casts integer inputs to `float(53)` (spike D.1). A provider without the
  renderer is `MQ4014`; no vendor answer is needed, since all three compute an exact average.

### Part E — null-safe equality (R-FLT-29)

```java
public <C> Filters<M> notDistinctFrom(ScalarField<M, C> column, C value);              // null value: is null
public <C> Filters<M> notDistinctFrom(ScalarField<M, C> column, Optional<? extends C> value);   // empty: skip
public <C> Filters<M> notDistinctFromColumn(ScalarField<M, C> column, ScalarField<M, C> other);
public <C> Filters<M> distinctFrom(ScalarField<M, C> column, C value);
public <C> Filters<M> distinctFrom(ScalarField<M, C> column, Optional<? extends C> value);
public <C> Filters<M> distinctFromColumn(ScalarField<M, C> column, ScalarField<M, C> other);
```

- **Naming.** `notDistinctFrom(COL, null)` would be ambiguous between `(ScalarField, C)` and
  `(ScalarField, ScalarField)`, and `Filters` has no column-to-column `eq`/`ne` to follow (its column operands are
  `in`/`notIn` lists and `op`), so the column form is `notDistinctFromColumn` / `distinctFromColumn`. The docs show
  `notDistinctFrom(COL, null)` as the null-literal use. The `Optional` overload mirrors `eq`'s (`Filters.java:37`).
- **A value operand resolves when the filter is added.** A non-null value renders `col = ? and col is not null`
  (two-valued: a NULL column gives false, not NULL); a null one renders `col is null`. `distinctFrom` is the `NOT` of
  that: `col is null or col <> ?` for a value, `col is not null` for null. Both are two-valued, so they stay correct
  under `Filters#not` (R-FLT-05, INV-5), where a plain `not(col = ?)` is NULL for a NULL column and drops the row.
  The §1 `MQ1301` rule (and AC-FLT-02) gains its exception: a `null` here is a value, not a skip; an empty `Optional`
  still skips (R-FLT-13 unchanged). Inspection records the operator with its value.
- **Two operands** render the two-valued portable form
  `(a = b and a is not null and b is not null) or (a is null and b is null)` in standard Criteria; `distinctFrom` is
  its `NOT`. A bare `a = b or (a is null and b is null)` is NULL when exactly one side is NULL, so its negation would
  drop rows under `not(...)`. Every vendor runs this form, and both forms give the right rows alone and under
  `not(...)` on every vendor (spike E.1).
- **Native form behind a provider hook.** Where the native form wins, the two-operand form renders it: Hibernate's
  `notDistinctFrom` / `distinctFrom` (H2 `is not distinct from`, MySQL `<=>`), gated by the database fact
  `VendorProfile#nativeNullSafeEquality()` (R-VND-29), `false` by default: `true` on MySQL and H2, `false` on
  PostgreSQL and `OTHER`. The spike's reason: MySQL's `<=>` is a plain lookup where the OR form reads every NULL key per outer row
  (`exists` 1.2 vs 33 to 72 ms, filter 5.1 vs 11.5 ms), and H2 runs only the native form as a lookup (`exists` 0.04
  vs 58 to 89 ms). PostgreSQL keeps the OR form: in a plain filter it has a parameterized `BitmapOr` plan the native
  form lacks, and in a correlated `exists` neither form is parameterized. A provider without the hook, or a vendor
  answering `false`, renders the OR form; the value form (above) is the same everywhere, since its `is not null`
  keeps the index on all three. No public API change. Existing correlations keep their SQL, so Parts A to E change
  no snapshot.
- **Converted columns.** The value is converted as `eq`'s is (R-FLT-06); two operands need the same converter, else
  `MQ1001` (R-COL-14), as any column comparison.
- **Why built in, not a custom operator (RFC 0017).** An operator's value is always a bound parameter its renderer
  receives (R-FLT-21), so it cannot become `is null` when the filter is added; it would render
  `col is not distinct from ?` everywhere, which PostgreSQL does not index. A catalogue operator would also need an
  `OperatorFeature` that H2 and MySQL would answer through Hibernate's emulation, so the gate would say nothing about
  the SQL. A user who needs it before this ships can still write
  `FilterOperator.of("notDistinctFrom", (ctx, l, r) -> ((HibernateCriteriaBuilder) ctx.cb()).notDistinctFrom(l, r))`.
- **Hibernate.** Both lines have `distinctFrom` and `notDistinctFrom` on `HibernateCriteriaBuilder`. The value form
  and the OR form are standard Criteria; the native two-operand form is the provider hook
  `ProviderSupport#nullSafeEquality()` (R-VND-30), an `Optional<NullSafeEqualityRenderer>` with
  `notDistinctFrom(cb, a, b)` and `distinctFrom(cb, a, b)`, empty by default. Unlike Part B's gate, a vendor answering
  `true` with no hook is not an error: the OR form is correct everywhere, only slower.

### Part F — counting rows as `count(*)` (R-AGG-19)

RFC 0019's spike found that its HQL route beat plain JDBC on the grouped read only because it rendered
`count(*) ... group by s1_0.status` where model-query renders `count(s1_0.id) ... group by 1`: PostgreSQL checks each
`count(col)` value for null, about 1 ms (10%) on that read (`rfc/0019-spike-results.md`, rule 2). The count is of a
value that cannot be null on any row the statement reads, so `count(*)` returns the same number.

**F.1 Where it applies.** Today these sites count an identity:

| Site | Renders today |
|---|---|
| `Agg.count(TableField)` (`Agg.java:50`), declared or in `having` | `count(t.id)` |
| `Agg.count(column)` over a table's id attribute (`Agg.java:137`) | `count(t.id)` |
| `@Aggregate(fn = COUNT)` on the id: generated `Agg.of(..., (ctx, cb) -> cb.count(X.path(ctx)))` (`QModelWriter.java:728-733`) | `count(t.id)`, invisible to this Part and refused by Part C's `filter` on `Agg.of` |
| A correlated `count` whose column is the sub-select root's id (`JoinContext.java:728`) | `count(t.id)` |
| The page total of an ungrouped, entity-rooted query without dedup (`DefaultModelQueryExecutor.java:3987`, R-EXE-05) | `count(root.id)` |

**The rule** (recorded in D-185): *R-AGG-19 eligibility: a criteria `Root` or a chain of INNER `Join`s from
one, with no treated node, counting the `From`'s own id, in a statement without a customizer; otherwise
`count(t.id)`.* In detail:

- "Root" means a criteria `Root`, reached from the counted `From` by walking `Join#getParent()` with every hop
  `JoinType.INNER`; it is not `ctx.root`, since a `rootedAt(join)` context (`JoinContext.java:217`; a fetch-plan
  `through`, R-FCH-14) has a `LEFT` join as its root.
- "No treated node on the chain" (R-COL-35, R-EXE-16's wording).
- A joined Part A source is classified by its `SourceJoin` kind, not by this walk: `INNER` renders the row count,
  `LEFT` renders `count(x.mq_<first key field>)`. The `Join#getParent()` walk applies to entity joins only.
- The rule tests the counted `From`'s own id attribute. A foreign-key read on the root (a join-free FK read, Q-20
  open) never qualifies.
- A statement with a customizer keeps `count(t.id)`: the customizer runs after the selection is built and could add a
  `RIGHT` or `FULL` join, which makes the id nullable. No new code.
- The rule is read from the resolved join chain when the query is built; a `LEFT` `@Join`, an optional path's
  inferred `LEFT` join, or any hop that is not inner keeps `count(t.id)`, which counts the matched rows, `0` for none.
- **One core helper** decides eligibility and builds the count for every site, including
  `DefaultModelQueryExecutor.java:3987`; the source-root `count(1)` sites route through it too, so the SQL is
  consistent.

Unchanged: `countDistinct` (including the page total's dedup through a to-many join, R-EXE-04), the treated root's
fresh `buildRootIdCount`, the RFC 0018 inline window and the count of groups (`count(*)` over the grouped
statement), and `count(col)` over any other column. A composite id counts as one identity, as `Agg.count(table)`
already does: `count(root)` over a composite id already renders `count(*)` on all three vendors and both lines
(spike F.1), so Part F changes nothing there. About 106 snapshot files change under the rule, plus about 30
`count(1)` files once the source-root sites route through the helper; the Part F slice owes the exact list.

**F.2 Rendering.** Standard Criteria has no row count; `cb.count(cb.literal(1))` renders `count(1)`, which sources
already use. `HibernateCriteriaBuilder#count()` renders `count(*)` on both lines (it exists from 6.4). So
`AggregateRenderer` (R-VND-30) gains `rowCount(cb, Expression<?> current)`, whose default is `cb.count(current)`, the
argument today's SQL counts (the root's id), so a provider without the Hibernate renderer keeps today's SQL; the
Hibernate renderer returns `count()`. Sources keep `count(1)`. The portable default is not `count(1)`: on
PostgreSQL's page total `count(1)` is 7 to 8% slower than `count(id)` on both lines (spike F.2), where `count(*)`
saves up to 12%. A filtered count passes `filtered` the argument the unfiltered count would use: `cb.literal(1)` only
when the R-AGG-19 eligibility holds, else the counted `From`'s id (for a LEFT-joined Part A source,
`x.mq_<first key field>`). The CASE form is `count(case when c then <argument> end)`. A literal `1` for an ineligible
count is wrong: `Agg.count(REFUND_LINES).filter(f -> f.eq(STATUS, OPEN))` over a LEFT join would report 5 for a group of
5 open sales with no refunds, where the unfiltered count's argument gives 0. Neither Hibernate line has an
argument-less `count(JpaPredicate)`, so an eligible filtered count renders `count(1) filter (where c)`. The field's name, key and result type (`Long`) are unchanged,
and equality (R-AGG-01) is too, except for the generated `@Aggregate(fn = COUNT)` site: the processor now emits
`Agg.count(column).named(...)` in place of `Agg.of(...)`, keeping today's field name (`field.constant()`,
`QModelWriter.java:731-733`), so the only change is the field's kind and equality (R-AGG-01 and inspection).

**F.3 Correctness.** Each eligible site counts a primary key that is non-null on every row the statement produces
(an inner chain yields no row without a match), so `count(*)` and `count(t.id)` are equal row for row, grouped or not,
with any `where`, `having` or R-EXE-16 kept join. A statement with a customizer is not eligible (above), so a
customizer's joins cannot change the count. The spike found equal rows and identical plans on every vendor and
line (F.1).

### Vendor and provider SPI

- **`VendorProfile#aggregates(AggregateFeature)`** (`jpa.spi`, R-VND-29), `@Incubating`, `false` by default, a database
  fact as `sources(SourceFeature)` is. One constant now, `FIRST_BY`: true on PostgreSQL and H2, false on MySQL and
  `OTHER`. False is `MQ4015`, naming the feature, the vendor and the field, before any statement.
- **`ProviderSupport#aggregates()`** (R-VND-30) returns an `Optional<AggregateRenderer>`, empty by default. The
  `@EngineFacing @Incubating core.AggregateRenderer` has `firstBy`, `filtered` and `exactAverage`; `firstBy` and
  `exactAverage` default to `MQ4014`, and `filtered` defaults to the engine's CASE form and refuses `Kind.FIRST_BY` with `MQ4014`. The executor folds the vendor
  answers into an immutable gate carried by `RenderOptions`, as R-VND-23 does for operators, so `core` sees no profile
  (INV-6, INV-7). `PortabilityCheck` reports `MQ4014` and `MQ4015` per declared target, with no database.
- **`SourceRenderer#join`** (Part A), default `MQ4014`.
- **`AggregateRenderer#rowCount(cb, current)`** (Part F), default `cb.count(current)` (today's SQL); no vendor fact,
  since every database counts rows.
- **`VendorProfile#nativeNullSafeEquality()`** (R-VND-29, Part E), `false` by default, `true` on MySQL and H2; and
  **`ProviderSupport#nullSafeEquality()`** (R-VND-30), an `Optional<NullSafeEqualityRenderer>` (`@EngineFacing
  @Incubating core.NullSafeEqualityRenderer`), empty by default, folded into the same `RenderOptions` gate.
- **`model-query-hibernate`** implements all four on both lines; `jpa` still imports no `org.hibernate` (INV-7).

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
2. **A.2 Rendering.** The `join` hook over `JpaFrom#join(JpaCteCriteria, SqmJoinType)` (its return type on each line),
   with `on` over a to-one path, `LEFT` and `INNER`, on
   every statement kind of R-SRC-12's list; two joined sources, and two joins of one source model; `Agg.count` over a
   LEFT-joined unmatched source; a joined source over a source; an outer `where` and an order on a joined column; a
   joined CTE next to a root CTE. The SQL and the rows per vendor and line.
3. **B.1 First-by.** `arrayAgg` plus `arrayGet` on PostgreSQL and H2: SQL, values under ties with the appended id, a
   NULL order key with `nullsLast()`, a converted value; a filtered `firstBy` whose first row in order fails the filter
   (H2 and PostgreSQL, native `FILTER` against the CASE form); what Hibernate's MySQL dialect does with `arrayAgg`
   (expected to fail, recorded). **B.2** the ranked-source route on all three vendors, with A, compared with B.1's plan
   on PostgreSQL, against LitePOS's two blocked methods.
4. **C.1 Filter.** `count`, `sum`, `avg` with a `JpaPredicate`, `min`/`max` via `functionAggregate`, `countDistinct`
   with a filter, and the engine's CASE form: rendered SQL per vendor (FILTER or CASE), equal rows, result types.
   **C.2** a filter over an optional to-one path with NULL foreign keys, beside an unfiltered grouped total (no row
   dropped).
5. **D.1 Exact average.** Both candidate renderings over `numeric(12,2)`, `bigint` and `integer` columns: JDBC type
   read, value and scale per vendor, zero rows, with a per-input-type argument cast for each integer type (H2's
   `AVG(INTEGER)` type recorded). **D.2** `round` over `2.345`, `-2.345`, `2.5` and `-2.5` per vendor, and scales 31
   to 38 (MySQL's limit).
6. **E.1 Null-safe equality.** The value form's SQL; the two-operand OR form against `notDistinctFrom` in a filter and
   in a correlated `exists`: plans and index use on PostgreSQL and MySQL, rows on all three. Rows with exactly one side
   NULL, each form inside `not(...)`, against native `IS DISTINCT FROM` / MySQL `<=>`; whether the extra `is not null`
   keeps the index plan.
7. **F.1 Row count.** RFC 0019's grouped read (cell `A.1/grouped`) and a page total over the sales lines, as
   `count(s1_0.id)`, `count(1)` and `count(*)`: medians of 7 alternating runs, `EXPLAIN (ANALYZE, BUFFERS)` on
   PostgreSQL, rows on all three vendors; the SQL `HibernateCriteriaBuilder#count()` renders on each line, alone and
   with a `JpaPredicate` filter (`count(1) filter`); a filtered `Agg.count` over a LEFT-joined table and over a
   LEFT-joined source with unmatched rows; a `Correlated` count over the sub-select root's id, including an outer row
   with zero matches; a composite-id
   `count(root)` before and after on all three vendors; an inner-joined and a LEFT-joined `Agg.count(table)` side by
   side (equal and unequal rows respectively); and the list of committed SQL snapshots that would change.
8. **Line parity.** The probe module compiles and behaves the same on 6.6 and 7.4.

### New ids (placeholders)

| Id | What |
|---|---|
| R-SRC-21 | A model rooted at an entity joins a unique source on its full key (`@JoinSource`, `SourceJoin`, `joinSource`); a joined source is a table-field kind keyed by the join name; `Agg.count` over a LEFT-joined source counts its first key field |
| R-SRC-22 | The join renders as a join to the source's CTE on every statement; locks `MQ1903`; child models `MQ3039` |
| R-AGG-16 | `Agg.firstBy`, ordered with the appended id; `FIRST_BY` vendor fact |
| R-AGG-17 | `AggregateField#filter`, FILTER or the CASE form |
| R-AGG-18 | `Agg.avgExact`, `Agg.round`; R-AGG-03's exact-average row |
| R-COL-37 | `Expr.round` over a `BigDecimal` scalar |
| R-FLT-29 | `notDistinctFrom`, `distinctFrom` and their `Column` forms, two-valued; §1 `MQ1301`'s null-value exception |
| R-PROC-44 | `@JoinSource` and `@SourceColumn` checks |
| R-PROC-45 | `@Aggregate`'s `orderBy`, `filter`, `scale`; `AVG` typed `BigDecimal` |
| R-VND-29 | `VendorProfile#aggregates(AggregateFeature)`, `VendorProfile#nativeNullSafeEquality()` |
| R-VND-30 | `ProviderSupport#aggregates()`, `AggregateRenderer` (with `rowCount`); `ProviderSupport#nullSafeEquality()`, `NullSafeEqualityRenderer`; `SourceRenderer#join` |
| R-AGG-19 | A count of the identity of a criteria root or INNER chain from one, untreated, in a statement without a customizer, renders the row count; a joined source is classified by its `SourceJoin` kind (`INNER` the row count, `LEFT` its first key field) |

| AC | Asserts |
|---|---|
| AC-SRC-42 | A LEFT-joined grouped source returns the hand-written SQL's rows; an outer row with no source row reads NULL; two joins of one source model stay apart; `Agg.count` over a LEFT-joined unmatched source is 0 |
| AC-SRC-43 | The join is in every statement of R-SRC-12's list, with the body's parameters bound once per statement |
| AC-SRC-44 | `INNER` drops unmatched outer rows in content and count alike; keyset pages over a joined order key lose no row |
| AC-SRC-45 | `MQ3044`: partial key, extra pair, type mismatch, non-unique source; `MQ1910` for the hand-written forms |
| AC-SRC-46 | `MQ1903` for a lock, `MQ3039` for a child model that joins a source, `MQ3035` unchanged |
| AC-SRC-47 | `OTHER` target: `MQ4015` before any statement; `PortabilityCheck` reports it |
| AC-AGG-18 | `firstBy` returns the same value on PostgreSQL and H2 under ties and NULL keys; a filtered `firstBy` uses native `FILTER`; a provider whose `filtered` is the default refuses it with `MQ4014` |
| AC-AGG-19 | `firstBy` on MySQL is `MQ4015` before any statement; `PortabilityCheck` reports it |
| AC-AGG-20 | A filtered `sum`/`count`/`avg`/`min`/`max`/`countDistinct` equals its CASE form on every vendor; a LEFT filter join drops no row |
| AC-AGG-21 | Without a renderer the engine's CASE form runs; a filter reading an aggregate, or on `Agg.of`, is `MQ1412` |
| AC-AGG-22 | A filtered aggregate in `having`; equality of two filtered fields |
| AC-AGG-23 | `avgExact` over `numeric(12,2)` returns the database's exact `BigDecimal` on every vendor; zero rows NULL |
| AC-AGG-24 | `avgExact` over `bigint`, `integer` and `smallint` returns the exact `BigDecimal` on every vendor; over a `Double` or `Float` column, and a `scale` below 0 or over 30: `MQ1411` |
| AC-AGG-25 | `round` half away from zero on every vendor, aggregate and row-level |
| AC-AGG-26 | Generated: `@Aggregate(fn = AVG) BigDecimal`, `scale`, `filter`, `FIRST_BY` with `orderBy`; `MQ3045` cases |
| AC-FLT-37 | `notDistinctFrom(col, null)` renders `is null`; a non-null value renders `= and is not null`; `distinctFrom` both ways, also inside `not(...)` with a NULL column |
| AC-FLT-38 | Two operands match NULL to NULL, and one-sided NULL is distinct, on every vendor, in `where`, under `not(...)` and in a correlated `exists`; MySQL and H2 render the native form, PostgreSQL and a provider without the hook the OR form |
| AC-FLT-39 | An empty `Optional` still skips; inspection records the operator |
| AC-AGG-27 | `Agg.count(table)` over the root and over an inner-joined table renders `count(*)` and returns `count(t.id)`'s rows, grouped and ungrouped, on every vendor and line; a `Correlated` count over the sub-select root's id renders `count(*)` and equals today's rows, including an outer row with zero matches |
| AC-AGG-28 | A LEFT-joined or treated table, a `rootedAt` LEFT root, a statement with a customizer, an FK read, `countDistinct`, the page total's dedup and a non-id column keep their SQL |
| AC-AGG-29 | The page total, a filtered row count (FILTER and CASE form) and a provider without `rowCount` (today's `count(t.id)`); a filtered count over a LEFT-joined table and over a LEFT-joined source with unmatched rows counts 0 for them, as the unfiltered count does |

| Code | Meaning |
|---|---|
| `MQ1910` | A hand-written `SourceJoin` whose pairs miss the source's key, over a non-unique source, or bound twice |
| `MQ1411` | `avgExact` over an inexact column (or a future input type with no passing rendering); a `round` scale below 0 or above 30 |
| `MQ1412` | An aggregate filter that reads an aggregate, window or correlated value, sits on `Agg.of`, repeats, is on a rounded aggregate or reads a to-many path; a `firstBy` value read through a to-many join |
| `MQ3044` | A `@JoinSource` whose `on` misses or exceeds the source key, mismatches a type, names an unknown field or a non-unique source; a `@SourceColumn` naming an unknown join or field |
| `MQ3045` | `orderBy` without `FIRST_BY` or `FIRST_BY` without it; an unknown or malformed `orderBy` key; `filter` naming a class that is not a `FilterDefinition`; `scale` on a non-`BigDecimal` field; an `AVG` field over an input type `avgExact` refuses |
| `MQ1903`, `MQ3039`, `MQ4014`, `MQ4015` | Widened as above; meanings unchanged |

Each new code was checked unused in `reference/90` and `processor/32` when this draft was written.

## Compatibility

All additive. No shipped signature or result changes: `Agg.avg` still returns `Double`; `@Aggregate(fn = AVG)` on a
`BigDecimal` component was `MQ3205` and now compiles as the exact average, which widens what the processor accepts;
`@Aggregate` gains members with defaults; `VendorProfile`, `ProviderSupport`, `SourceRenderer` and `AggregateRenderer`
gain default methods only, so a third-party profile or provider fails safe (`false`, `MQ4014`, the OR form, today's
count). Parts A to E change no committed SQL snapshot. Part F changes rendered SQL but no result: every snapshot with an eligible `count(t.id)` becomes
`count(*)` on the Hibernate provider (a provider without `rowCount` keeps today's SQL), about 106 files plus about 30
source-root `count(1)` files (spike F.1), regenerated and listed in the Part F slice. A test or tool that matches the SQL text sees the change; the changelog says so.
The generated code for `@Aggregate(fn = COUNT)` changes: it emits `Agg.count(column).named(field.constant())` in place of `Agg.of(...)`, so the
name stays and only the field's kind and equality change (R-AGG-01 and inspection), and F.2's "equality unchanged" excludes it. Everything new is
`@Incubating`; the freeze review decides each piece.

## Alternatives

- **Do nothing.** Each gap has a stand-in (a correlated sum, a CASE, a hand-written null check, `Agg.of`), but the
  first costs two orders of magnitude in buffers and the others make a model harder to read than the SQL.
- **Lower a correlated column to a join automatically.** RFC 0015 §6 measured it: it loses on MySQL pages with an
  index and wins only without one. D-150 keeps the scalar form; an explicit join is the author's choice.
- **LATERAL joins.** Rejected by D-145 (H2 has none). A keyed join to a CTE needs no correlation.
- **A derived table instead of a CTE.** Kept as the per-vendor fallback behind the same hook; the spike showed no
  plan loss (H2 already receives the derived table); a CTE reuses all of R-SRC-12.
- **`count(1)` as the portable `rowCount` default** (Part F). Rejected: 7 to 8% slower than today's `count(id)` on
  PostgreSQL page totals (spike F.2).
- **`firstBy` emulated on MySQL** through `group_concat` or a correlated `first` per group. The first truncates and
  works on text only; the second is the per-row sub-plan Part A removes. The ranked-source route is documented
  instead.
- **`filter` refused where the database lacks FILTER.** Needless: the CASE form is exactly equal for every aggregate
  here.
- **A new `AggregateFunction.AVG_EXACT`.** The component's type already says it; a second constant would let the two
  disagree.
- **`round` over `Double`.** Vendors round approximate values differently (R-VND-10: never silently emulated).
- **Scanning for RIGHT/FULL joins after the customizer and refusing** (Part F). Rejected: a new failure for a
  rendering optimisation; a statement with a customizer keeps `count(t.id)` instead.
- **Null-safe equality as a catalogue operator** (RFC 0017). It cannot resolve a null value at add time, and the
  native SQL is unindexed on PostgreSQL (Part E).

## Resolutions to record

No question stays open. Each resolution below is recorded in D-185, with the spike's evidence
(`rfc/0020-spike-results.md`).

1. **A:** the CTE everywhere; a derived table only behind the `join` hook, which no Tier-1 vendor needs (H2 already
   receives the inlined derived table). The docs name the count's cost on MySQL and H2 and narrow the "keep
   `Correlated.*`" advice to unfiltered pages of a few rows over an indexed child.
2. **B:** ship `firstBy` through the renderer's own function pattern (`(?1)[1]` PostgreSQL, `?1[1]` H2), called with
   no Java result type so the value's converter applies; `arrayGet` is not the rendering. Document the ranked-source
   route.
3. **D:** the exact average is candidate 2 (`sum(x) / count(x)`); integer inputs (`bigint`, `integer`, `smallint`) are
   cast with `HibernateCriteriaBuilder#cast(x, BigDecimal.class)`, a `BigDecimal` input is not cast. Every listed
   input type passes; `Double` and `Float` are `MQ1411` (`MQ3045` in the processor).
4. **E:** the value form and the OR form are standard Criteria; the two-operand native form renders behind
   `ProviderSupport#nullSafeEquality()`, gated by `VendorProfile#nativeNullSafeEquality()`, `true` on MySQL and H2,
   `false` on PostgreSQL and `OTHER`. No public API change.
5. **F:** `AggregateRenderer#rowCount(cb, current)` defaults to `cb.count(current)`, today's SQL; the Hibernate
   renderer returns `count(*)`; sources keep `count(1)`.
6. **D:** the `round` scale bound is 30 (MySQL clamps a larger scale silently); raising it later is additive.
7. **F:** a filtered row count is `count(1) filter` (Hibernate has no argument-less `count(JpaPredicate)` on either
   line), with the argument rule of F.2.
8. **F:** an eligible composite id renders the row count, which `count(root)` already does on all three vendors; an
   ineligible one keeps today's SQL.
9. **F:** a statement with a customizer keeps `count(t.id)`; scanning for RIGHT/FULL joins after the customizer is
   rejected (§Alternatives).
