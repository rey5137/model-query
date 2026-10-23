# 13 — Aggregates and Grouped Queries

**Covers:** `Agg`, `AggregateField`, `groupBy`, `having`, aggregate result types, and every rule that applies once a
query is grouped.
**Read when:** building a report summary, adding an aggregate function, or deciding what a grouped query may and may
not do.
**Owns:** `R-AGG-*`, `AC-AGG-*`. `SelectField` is `api/10` §2; grouped export is `engine/21` §5.
What a built query records of its `having` filters is `api/16`.

---

Report summaries are projection-first reads too: `SELECT region, product_code, sum(line_total), count(*) … GROUP BY …` mapped into
a summary model. `api/10`'s `ColumnField` is a table attribute and resolves to a `Path`; an aggregate resolves to an
`Expression`. `SelectField` (`api/10` R-COL-06) is what lets both be selected, ordered and read from a `Row`.

## 1. `Agg`

```java
public final class AggregateField<M, C> implements SelectField<M, C> { /* … */ }

public final class Agg {
    public static <M> AggregateField<M, Long> count(TableField<?, ?> table);
    public static <M> AggregateField<M, Long> countDistinct(OrderedColumnField<M, ?, ?> column);

    public static <M, C extends Number> AggregateField<M, C>      sum(ColumnField<M, ?, C> column);
    public static <M>                   AggregateField<M, Long>   sumAsLong(ColumnField<M, ?, ? extends Number> column);
    public static <M, C extends Number> AggregateField<M, Double> avg(ColumnField<M, ?, C> column);

    public static <M, C extends Comparable<? super C>> AggregateField<M, C> min(OrderedColumnField<M, ?, C> column);
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> max(OrderedColumnField<M, ?, C> column);

    // over an expression (R-AGG-13); count takes any ScalarField and counts its non-null values
    public static <M> AggregateField<M, Long> count(ScalarField<M, ?> value);
    public static <M> AggregateField<M, Long> countDistinct(ExpressionField<M, ?> expression);
    public static <M, C extends Number> AggregateField<M, C>      sum(ExpressionField<M, C> expression);
    public static <M>                   AggregateField<M, Long>   sumAsLong(ExpressionField<M, ? extends Number> expression);
    public static <M, C extends Number> AggregateField<M, Double> avg(ExpressionField<M, C> expression);
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> min(ExpressionField<M, C> expression);
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> max(ExpressionField<M, C> expression);

    public static <M, C> AggregateField<M, C> of(String name, Class<C> type,
                                                BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression);
}
```

```java
public static final AggregateField<ProductSales, Long>       LINES   = Agg.count(QProductSales.ROOT);
public static final AggregateField<ProductSales, BigDecimal> REVENUE = Agg.sum(QProductSales.LINE_TOTAL);
```

**R-AGG-01** An `AggregateField` is equal to another with the same function, source column (by the column's own join
key and attribute) and alias — never by object identity, the same rule joins follow (`api/10` R-COL-02); a `min` or
`max` over a converted column also needs a converter of the same class (`api/10` R-COL-14). Two
`Agg.sum(LINE_TOTAL)` calls are therefore one selection and one `Row` key. `as("…")` gives an aggregate its own
key when the same function over the same column is needed twice.

**R-AGG-02** `Agg.of(...)` is keyed by its `name`, because a lambda cannot be compared. Two `Agg.of` fields sharing a
name with different expression instances throw `MQ1103` when the query is built. An `Agg.of` function that returns
`null`, or an expression whose Java type is not the declared type (a declared `java.util.Date` also takes a `java.sql.Date`,
`Time` or `Timestamp`, D-122), throws `MQ1405` when the query is built, rather than
a `ClassCastException` when a row is read. `Agg.of` is for aggregate expressions `Agg` over an expression cannot express (R-AGG-13).

## 2. Result types

**R-AGG-03** An aggregate's Java type is what the database returns, not the source column's type:

| Expression | Source type | Result type |
|---|---|---|
| `count` / `countDistinct` | any | `Long` |
| `sum` | `BigDecimal` / `Double` / `Long` | same type |
| `sum` | `Integer` / `Short` | `Long` — `Agg.sum` on a 32-bit column throws `MQ1403` when it is called; use `sumAsLong` |
| `avg` | any numeric | `Double` |
| `avgExact` | `BigDecimal` / `Long` / `Integer` / `Short` | `BigDecimal` — exact (R-AGG-18) |
| `min` / `max` | any comparable | same type |

*(was R16)* Checked by the processor for generated models (`processor/32`) and at build time for hand-written ones.
`Agg.sum` accepts `BigDecimal`, `Double` and `Long` columns; `Agg.sumAsLong` accepts `Integer`, `Short`, `Long` and
`Byte` columns. Any other column throws `MQ1403` when the factory is called, since Java cannot overload on a type
argument (D-25). For generated columns the processor (M4) reports the same mistake as a compile error
(`processor/32` `MQ3205`).

**R-AGG-04** `sum` over zero rows is `NULL`, not `0`. `Row.get` returns `null`, and a mapped field must be a boxed
type; a primitive field for a `sum` column is a processor error `MQ3201`. A caller that wants `0` uses a
`ColumnConverter` or `Objects.requireNonNullElse` in the mapper — the engine never invents a value (INV-5).
`sum`, `sumAsLong` and `avg` over a converted column throw `MQ1408`: the database aggregates attribute values, which
the column's converter cannot be applied to (`api/10` R-COL-14). `Agg.of` is the way to aggregate such an attribute.
`min`, `max` and `countDistinct` commute with an order-preserving, injective conversion, so they take an
`OrderedColumnField`, a column with no converter or with an `OrderedColumnConverter`; over a column with any other
converter they do not compile (D-93). `min` and `max` return the converter's `toModel` of the value
the database returned, typed as the column, and a `having` value on them binds `toAttribute` of it; `countDistinct`
stays a `Long`. `sum`, `sumAsLong` and `avg` keep `MQ1408` for every converter (D-84, D-93).

## 3. Grouping and `having`

```java
ModelQuery<OrderItemEntity, ?, ProductSales> q = QProductSales.query()
        .select(QProductSales.GROUP_KEYS.with(LINES, REVENUE))
        .where(f -> f.range(QProductSales.PLACED_AT, from, to)
                     .in(QProductSales.REGION, regions)
                     .not(g -> g.eq(QProductSales.ORDER_STATUS, OrderStatus.CANCELLED)))
        .groupBy(QProductSales.GROUP_KEYS)
        .having(h -> h.gte(REVENUE, minRevenue))
        .orderBy(REVENUE.desc(), QProductSales.PRODUCT_CODE.asc())
        .afterMap((model, row) -> model.setAverageLine(averageLine(model)))
        .build();
```

**R-AGG-05** `groupBy` accepts a `SelectSet<M>` or explicit `ScalarField`s, so an expression may be a group key. Passing a `SelectSet` keeps the group-by
list and the selection in step from one constant, which is where hand-written builders drift. A
`SelectSet` holding an aggregate throws `MQ1404`, since an aggregate cannot be a group key (D-115).

**R-AGG-06** `having(UnaryOperator<Having<M>>)` is the `Filters` DSL (`api/12`) over `AggregateField` instead of
`ColumnField`, with the same skip and `Optional` semantics. Aggregates cannot appear in `where` and plain columns cannot
appear in `having`: both are compile errors, because `Filters` takes `ScalarField` and `Having` takes `AggregateField`
(P-2).

## 4. Rules for a grouped query

**R-AGG-07** A query is **grouped** when `groupBy` is non-empty. Selecting an `AggregateField` with no `groupBy` is a
grouped query with one group — what `SELECT count(*) FROM …` means — and returns exactly one row. Nothing else groups a
query: `having(...)` does not, so a `having` on a query with neither a `groupBy` nor a selected aggregate throws
`MQ1407` at build time, whether or not its filters were skipped. Grouping is therefore fixed by the definition, never by
a request's values (D-28).

**R-AGG-08** Every selected non-aggregate column or expression must fit the group-by (R-AGG-14). Checked when the query is built, throwing
`MQ1401` with the offending column's name. MySQL would accept it and return an arbitrary value; PostgreSQL would fail at
execution time with a message naming neither the model nor the column. Ordering follows the same rule: every `orderBy`
key of a grouped query fits the group-by (R-AGG-14) or is an aggregate, and no `orderBy` key of an ungrouped query is an aggregate. A key
that does not fit throws `MQ1406` at build time (D-28).

**R-AGG-09** A grouped query needs no primary key (`api/11` R-QRY-03). The `engine/21` R-PAG-03 "primary key not
selected" check is skipped, because a group has no row identity. When `primaryKey` is set on a grouped query it is
ignored and logged once at `DEBUG`. No presence key is added either (`api/10` R-COL-15): a selected column under a
`presentBy` join whose key columns are not all group keys throws `MQ1409` at `build()`, since the nested model would
map as absent (D-38).

**R-AGG-10** *(was R17)* A grouped query refuses keyset paging. `.keyset()` and `primaryKeyFirst(...)` throw `MQ1402`
at build time naming the model: both need a unique per-row key, and R-PAG-04/05 cannot hold without one. Grouped reads
use `list`, `stream` or offset `export`.

**R-AGG-11** `count` over a grouped query counts groups, not rows (`engine/20` R-EXE-03).

**R-AGG-12** Generated `SelectSet`s (`DEFAULT`, `ALL`) never include aggregates, since including one would turn every
plain query into a grouped one. Aggregates are added explicitly with `with(...)`.

**R-AGG-13** *(D-115)* **Aggregates over an expression.**
- `sum`, `sumAsLong`, `avg`, `min`, `max` and `countDistinct` take an `ExpressionField` too, and `count` takes any
  `ScalarField`, counting its non-null values. A conditional count is `count(cases(...).when(cond, 1).orNull())`, and a
  conditional sum is `sum(cases(...).when(cond, AMOUNT).otherwise(ZERO))`.
- R-AGG-03's result types apply to the expression's type (an `Integer` expression is `MQ1403` for `sum`).
- Equality is R-AGG-01's, with the expression's structure as the source.

**R-AGG-14** *(D-115)* **Expression group keys and the fit rule.**
- On a grouped query, a selected or ordered `ScalarField` fits when it equals a group key, ignoring `named`, or when it
  is an expression every one of whose columns is a group-key column. Anything else is `MQ1401` or `MQ1406`.
- A sub-expression is not matched to a group key, because a database that matches by text cannot equal two bound
  parameters.
- `having` stays over aggregates; a condition on a group key goes in `where`.

**R-AGG-15** *(D-137)* **No group key over a whole to-one entity.** A runtime `groupBy` or group key over a column that
selects a whole-entity to-one (the D-124 form) throws `MQ1410` before any statement, since the database groups by the
foreign key while the select lists every column of the entity (PostgreSQL refuses it). The id form (`api/10` R-COL-29)
stays allowed as a group key; `@GroupBy` on the whole-entity form is `MQ3028` at compile time.

**R-AGG-17** *(D-185, D-186, `@Incubating`)* **Filtered aggregates.** `AggregateField#filter(UnaryOperator<Filters<M>>)`
returns a new immutable field (INV-9) that aggregates only the rows its condition matches: `Agg.sum(amount).filter(f ->
f.eq(kind, "refund"))`. It works on `count`, `countDistinct`, `sum`, `sumAsLong`, `avg`, `min` and `max`, over a column
or an expression, and on a field that has `as(...)` or `named(...)`, which both keep the filter. Equality and `hashCode`
include the condition, compared structurally as `Expr.cases` compares its `when` conditions: two fields with equal
filters are one selection, and different conditions are two (give them an `as(...)` name, since both render the same
default name). The result type is R-AGG-03's. The filter is also usable in `having` as any aggregate (R-AGG-06).
`Filters` takes only a `ScalarField`, so a condition cannot read an aggregate or a window field. A join the condition
is the first to need resolves `LEFT` (R-FLT-10), so a NULL path drops no row from the statement: an implicit inner join
would. Rendering goes through `ProviderSupport#aggregates()` (R-VND-30): `Hibernate` renders `FILTER (WHERE ...)` where
the database has it and its own CASE emulation elsewhere. With no such hook the engine renders the CASE form,
`fn(case when c then x end)` (a count passes the argument the unfiltered count uses); the two forms give equal rows on
every vendor. `MQ1412` when the field is already filtered (combine the conditions in one `filter`), when it comes from
`Agg.of` (the engine cannot place a condition inside an opaque expression), when the condition leaves no filter, when
it reads a correlated value (thrown by `filter`), when a filtered `count(table)` has an embedded or composite id with no usable first id attribute (the filtered count counts that id attribute), or when the condition adds a to-many join (thrown when the query is built): a to-many join already in the statement, such as the argument's own, is the aggregated rows' own and is allowed, and `Filters.exists(path, inner)` filters on a to-many without joining it.

**R-AGG-18** *(D-185, `@Incubating`)* **Exact average and rounding.** `Agg.avgExact(ColumnField)` and
`Agg.avgExact(ExpressionField)` return an `AggregateField<M, BigDecimal>` for a `BigDecimal`, `Long`, `Integer` or
`Short` input, and `Agg.round(AggregateField<M, BigDecimal>, int scale)` rounds one (`Expr.round` is the row-level form,
`api/10` R-COL-37). Hibernate types `avg` as `Double`, so the average is `sum(x) / count(x)` with the numerator typed
`BigDecimal`, rendered by `AggregateRenderer#exactAverage(cb, argument)` (R-VND-30): an integer input is cast to
`BigDecimal` first (`HibernateCriteriaBuilder#cast`, `cb.cast` on Jakarta Persistence 3.2), since integer division
truncates on PostgreSQL and H2; a `BigDecimal` input is not cast, because the cast's default `numeric(38,2)` would
round one with more decimals. Zero rows is NULL, as `sum`. The scale of an unrounded average follows the database
(PostgreSQL 16, H2 40, MySQL the input's scale + 4), so `round` it for money. A provider without the renderer is
`MQ4014`. A `Double` or `Float` input, and any other type with no exact rendering, is `MQ1411` when the factory is
called; a converted column is `MQ1408`, as for `avg`. `round` takes a scale from 0 to 30 (`MQ1411` otherwise, as MySQL
clamps a larger scale to 30) and rounds half away from zero on PostgreSQL `numeric`, MySQL `DECIMAL` and H2 `NUMERIC`;
it takes a `BigDecimal` only, since a `Double` rounds differently across vendors. Half away from zero is promised of the
value `round` receives, and that value is the database's quotient at its own scale (PostgreSQL 16, H2 40, MySQL the
input's scale + `div_precision_increment`, 4 by default: 6 for `numeric(12,2)` or a cast integer), so on MySQL a mean with
more decimals than that is rounded twice (an exact 2.3449995 becomes 2.345000, then 2.35). A rounded aggregate is still an
aggregate for the fit rule (R-AGG-08), `having` and `orderBy`; two equal rounds (same inner aggregate and scale) are
one selection (R-AGG-06). A `filter` (R-AGG-17) on `avgExact` filters the argument before the sum and the count, which
keeps the quotient exact. A rounded aggregate is not itself filterable: `filter` on it is `MQ1412`; filter the inner
aggregate, `Agg.round(Agg.avgExact(x).filter(...), 2)`.

**R-AGG-19** *(D-185, `@Incubating`)* **Row counts.** A count of a table's identity renders the provider's row count,
`AggregateRenderer#rowCount` (R-VND-30, `count(*)` on Hibernate), where no row the statement reads can leave that
identity null: the counted `From` is a criteria `Root` or reached from one through `INNER` joins only (walked through
`Join#getParent()`, so a `rootedAt` context whose root is a `LEFT` join does not qualify), no node on that chain is
treated (R-COL-35), the count reads the `From` itself or its own single id attribute, and the statement has no
customizer (which could add a `RIGHT` or `FULL` join). The sites are `Agg.count(table)`, `Agg.count(column)` over the
id, the generated `@Aggregate(fn = COUNT)` (emitted as `Agg.count(column).named(...)`), a `Correlated` count over the
sub-select root's id, and the page total (`engine/20` R-EXE-05). Everything else keeps counting the identity,
`count(t.id)`: a `LEFT` hop, a treated node, a foreign-key read, a statement with a customizer. Unchanged:
`countDistinct` (and the page total's dedup, R-EXE-04), a treated root's page total, the inline count window, the
count of groups, and `count(col)` over a non-id column; a composite id already counts as one identity. A source's row
is counted as `count(1)`. A filtered count (R-AGG-17) counts `1` under its condition when the count is eligible, else
the identity, so a `LEFT`-joined unmatched row counts 0 as the unfiltered count does. The row count returns the same
number as `count(t.id)`, grouped or not. A joined source is classified by its join kind (`INNER` the row count,
`LEFT` its first key field).

## 5. Generated support

`processor/30` defines `@Aggregate` and `@GroupBy`; `processor/31` defines what they generate. In short: an
`@Aggregate` field yields one `AggregateField` constant and is mapped like any other column, `@GroupBy` fields form the
generated `GROUP_KEYS` `SelectSet`, and `QProductSales.query()` comes pre-configured with `groupBy(GROUP_KEYS)`.

## 6. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-AGG-01 | Every `Agg` function returns the R-AGG-03 type on every Tier-1 vendor. |
| AC-AGG-02 | `Agg.sum` over an `Integer` column throws `MQ1403` when called; `sumAsLong` accepts it (R-AGG-03). |
| AC-AGG-03 | `sum` over zero matching rows reaches the model as `null`, not `0` (R-AGG-04). |
| AC-AGG-04 | Two identical `Agg.sum(...)` constants render one selection and one `Row` key (R-AGG-01). |
| AC-AGG-05 | Two `Agg.of` fields with the same name and different expressions throw `MQ1103`; an `Agg.of` returning `null` or an expression of another Java type throws `MQ1405` (R-AGG-02). |
| AC-AGG-06 | An aggregate in `where` and a plain column in `having` fail to compile (compile-testing) (R-AGG-06). |
| AC-AGG-07 | `having` skip semantics match `api/12` AC-FLT-03 for aggregates: an all-skipped `or` is skipped, and an empty `or(List)` is recorded as `FALSE` (R-AGG-06, D-92). |
| AC-AGG-08 | A selected column missing from the group-by throws `MQ1401` naming the column; an `orderBy` key that does not fit the grouping throws `MQ1406`; an expression is checked the same way (R-AGG-08, R-AGG-14). |
| AC-AGG-09 | `.keyset()` on a grouped query throws `MQ1402`; a grouped query with no `primaryKey` exports successfully (R-AGG-09, R-AGG-10). |
| AC-AGG-10 | An aggregate selection with no `groupBy` returns exactly one row (R-AGG-07). |
| AC-AGG-11 | `afterMap` on a grouped query runs once per group and sees every selected aggregate (`api/11` R-QRY-05). |
| AC-AGG-12 | `having` on a query with neither a `groupBy` nor a selected aggregate throws `MQ1407`, even when every filter was skipped; an aggregate in the `groupBy` set throws `MQ1404` (R-AGG-05, R-AGG-07). |
| AC-AGG-13 | `Agg.min`, `max` and `countDistinct` over a column with an `OrderedColumnConverter` return the model-typed `min` and `max` and the `Long` count the database computes over the attribute on every Tier-1 vendor, and `having` and `orderBy` on them compare attribute values; `sum`, `sumAsLong` and `avg` over any converted column throw `MQ1408`, and `min`, `max` and `countDistinct` over a column whose converter is not ordered do not compile: the column is a plain `ColumnField`, and the processor declares a generated column as `OrderedColumnField` only when it has no converter or an ordered one (R-AGG-04, D-84, D-93). |
| AC-AGG-14 | On every Tier-1 vendor: `sum`, `sumAsLong`, `avg`, `min`, `max` and `countDistinct` over an expression, and `count` over a column and over a CASE (a conditional count), return R-AGG-03's types; a conditional `sum` through `cases` equals the filtered `sum`; `Agg.sum` over an `Integer` expression throws `MQ1403`; two equal aggregates over equal expressions are one selection; `having` on an aggregate over an expression filters groups (R-AGG-13, R-AGG-01). |
| AC-AGG-15 | For a query grouped by an expression key: `list`, `count` and grouped export visit every group once on every Tier-1 vendor, and on PostgreSQL that holds with a key that binds a value, ordered by it with and without explicit null precedence, with and without `model-query-hibernate`; a selected expression over group-key columns only is accepted; a selected expression reading another column throws `MQ1401`; an expression that only contains a group-key expression throws `MQ1401`; an order key that does not fit throws `MQ1406`; `groupBy(SelectSet)` holding an expression builds (R-AGG-14, R-AGG-05, R-AGG-08, R-COL-19). |
| AC-AGG-16 | A runtime `groupBy(ScalarField...)` or `groupBy(SelectSet)` over one of a model's `@GroupBy` fields selects that key and an aggregate and leaves the other `@GroupBy` fields unselected, reading `null`, with the groups and sums the rows hold, on every Tier-1 vendor (R-AGG-05, R-PROC-16). |
| AC-AGG-17 | A runtime `groupBy` over a whole-entity to-one column throws `MQ1410` before any statement; over the id form it groups (R-AGG-15). |
| AC-AGG-20 | On every Tier-1 vendor and both Hibernate lines, a filtered `count`, `countDistinct`, `sum`, `sumAsLong`, `avg`, `min` and `max` equals the hand-written `Expr.cases` form; a filter through an optional to-one with NULL foreign keys resolves `LEFT` and drops no row from the statement (R-AGG-17, R-FLT-10). |
| AC-AGG-21 | `filter` on an `Agg.of` field, a second `filter`, a condition that leaves no filter, one that reads a correlated value, and one that adds a to-many join each throw `MQ1412`; with no aggregate renderer the engine's CASE form runs (R-AGG-17, R-VND-30). |
| AC-AGG-22 | A filtered aggregate in `having` keeps the groups over the threshold on every vendor; two equal filtered fields are one selection and different conditions are two (R-AGG-17, R-AGG-06). |
| AC-AGG-23 | `avgExact` over `numeric(12,2)` returns the database's exact `BigDecimal` on every Tier-1 vendor and both Hibernate lines, and zero rows is NULL; a filtered `avgExact` averages only the matched rows, exactly; with no `exactAverage` renderer it is `MQ4014` (R-AGG-18, R-AGG-17, R-VND-30). |
| AC-AGG-24 | `avgExact` over `bigint`, `integer` and `smallint` returns the exact `BigDecimal` on every Tier-1 vendor; over a `Double` or `Float` column, and a `round` scale below 0 or over 30: `MQ1411` (R-AGG-18). |
| AC-AGG-25 | `round` is half away from zero on every Tier-1 vendor, as an aggregate and per row (`Expr.round`, `api/10` R-COL-37): 2.345 to 2.35, -2.345 to -2.35, 2.5 to 3, -2.5 to -3; a rounded aggregate groups, filters in `having` and orders; equal rounds are one selection. For `Agg.round` the inputs are values whose quotient is exact at every vendor's scale; a mean with more decimals than MySQL's quotient scale is rounded twice there (R-AGG-18, R-COL-37). |
| AC-AGG-27 | On every Tier-1 vendor and both Hibernate lines, `Agg.count(table)` over the root and over an inner-joined table, and `count` over the root's id column, render `count(*)` and return `count(t.id)`'s rows, grouped and ungrouped; a `Correlated` count over the sub-select root's id renders `count(*)` and equals the identity count, `0` for an outer row with no match (R-AGG-19). |
| AC-AGG-28 | A `LEFT`-joined or treated table, a `rootedAt` `LEFT` root, a statement with a customizer, a foreign-key read, `countDistinct`, the page total's dedup and a non-id column keep counting the identity or column (R-AGG-19). |
| AC-AGG-29 | The page total of an entity root renders `count(*)`; a filtered row count renders `count(1) filter (where c)` or its CASE form; with no `rowCount` the count is `count(t.id)`; a filtered count over a `LEFT`-joined table counts 0 for an unmatched row, as the unfiltered count does (R-AGG-19, R-AGG-17). |
