# Grouped queries

A grouped query returns one row per group, with aggregates computed over each group, for example orders and revenue
per status. You describe it with a model that has `@GroupBy` and `@Aggregate` fields.

```java
@QueryModel(root = OrderEntity.class)
public record OrderTotals(
        @GroupBy String status,
        @Aggregate(fn = AggregateFunction.COUNT) Long orders,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "total") BigDecimal revenue) {}
```

```java
var totals = QOrderTotals.query()
        .select(QOrderTotals.GROUP_KEYS.with(QOrderTotals.ORDERS, QOrderTotals.REVENUE))
        .orderBy(QOrderTotals.STATUS.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<OrderTotals> rows = executor.list(totals, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<OrderTotals> rows = orders.findAll(totals, Limit.unlimited());
    ```

`GROUP_KEYS` is the generated column set of every `@GroupBy` field, and `QOrderTotals.query()` already groups by it.
Aggregates are in no generated column set, so you add them explicitly with `with(...)`. That keeps a plain query from
turning into a grouped one by accident.

## Aggregate functions

`@Aggregate(fn = ...)` takes `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` or `FIRST_BY`; `attribute` is omitted for `COUNT` over the root,
and `distinct = true` gives `COUNT(DISTINCT ...)`. For a hand-written model the same functions are on `Agg`:
`count`, `countDistinct`, `sum`, `sumAsLong`, `avg`, `avgExact`, `min`, `max`, `firstBy`, and `Agg.of(name, type, expression)` for an
aggregate expression of your own.

An aggregate's Java type is what the database returns, not the column's type:

| Function | Result type |
|---|---|
| `COUNT` | `Long` |
| `SUM` of `BigDecimal`, `Double` or `Long` | the same type |
| `SUM` of `Integer` or `Short` | `Long` (use `sumAsLong`; `Agg.sum` on a 32-bit column fails with `MQ1403`) |
| `AVG` | `Double`; a `BigDecimal` field makes it the exact average (`Agg.avgExact`, below) |
| `MIN`, `MAX` | the column's type |
| `FIRST_BY` | the value's own type |

`SUM` over zero rows is `NULL`, not `0`, so an aggregate field must be a boxed type. The library never invents a
value for you; use `Objects.requireNonNullElse` in `afterMap` if you want zero.

`expression = Def.class` aggregates over an `ExpressionDefinition<Model, C>` instead of a stored `attribute`; a
`COUNT` over it counts non-null values. The field's type is checked against the function's result over `C` (`MQ3202`),
and `attribute` and `expression` together are `MQ3208`. A `@GroupBy` on a `@Computed` field puts it in `GROUP_KEYS`
like any other key, so the query groups by the value the database computes:

```java
@QueryModel(root = OrderEntity.class)
public record OrderBandTotals(
        @GroupBy @Computed(BandStatus.class) String band,
        @Aggregate(fn = AggregateFunction.SUM, expression = BandTotal.class) BigDecimal doubled) {}
```

Tested by `ComputedModelQueryTest`.

## Exact averages and rounding

`Agg.avg` and a plain `AVG` return a `Double`. For money use `Agg.avgExact(column)`, which returns a `BigDecimal`
computed as `sum(x) / count(x)`: an integer input is cast to `BigDecimal` first, so the division does not truncate, and
a `BigDecimal` input is not cast, so it keeps its decimals. Zero rows is `NULL`, as for `sum`. `Agg.avgExact(expression)`
takes an `ExpressionField`. A `Double` or `Float` input is `MQ1411` when the factory is called (use `Agg.avg`), and a
column with a converter is `MQ1408`.

The scale of an unrounded exact average follows the database (PostgreSQL scale 16, H2 40, MySQL the input's scale
plus 4), so **round it for money**: `Agg.round(aggregate, scale)` rounds an `AggregateField<M, BigDecimal>`, and
`Expr.round(column, scale)` does the same per row (`BigDecimal` only, since a `Double` rounds differently across
vendors). Rounding is half away from zero of the value it receives, with a scale from 0 to 30 (`MQ1411` otherwise). A
rounded aggregate is still an aggregate for `having`, `orderBy` and the fit rules.

!!! warning "Rounding twice on MySQL"
    Half away from zero is promised of the value `round` receives, and that value is the database's quotient at its own
    scale. MySQL's quotient has the input's scale plus `div_precision_increment` (4 by default), so a mean with more
    decimals than that is rounded twice: an exact 2.3449995 becomes 2.345000, then 2.35 (AC-AGG-25). If a result must
    never round twice, round the exact average once in your own code.

## Filtered aggregates

`aggregate.filter(f -> ...)` returns a new aggregate over only the rows its condition matches, for `count`,
`countDistinct`, `sum`, `sumAsLong`, `avg`, `min`, `max` and `avgExact`, over a column or an expression:
`Agg.sum(amount).filter(f -> f.eq(kind, "refund"))`. It renders `FILTER (WHERE ...)` where the database has it and an
equal `CASE` form elsewhere. Two fields with equal filters are one selection and different conditions are two, so give
each an `as(...)` name. `MQ1412` for a field that is already filtered (combine the conditions in one `filter`), one made
by `Agg.of`, a rounded aggregate (filter the aggregate it rounds, as `Agg.round(Agg.avgExact(x).filter(...), 2)`), a
condition that leaves no filter or reads a correlated value, and a condition that adds a to-many join (use
`Filters.exists(path, inner)`). A filtered `count` of a row counts `1` under its condition when nothing can leave the identity NULL, else the identity, so a `LEFT`-joined row that
did not match counts 0, as the unfiltered count does.

## Row counts render `count(*)`

A count of a table's identity (`Agg.count(table)`, `Agg.count(column)` over the id, `@Aggregate(fn = COUNT)`, a
`Correlated` count over the sub-select's id and the page total) renders `count(*)` where no row the statement reads can
leave the identity `NULL`: the counted table is the root or reached through `INNER` joins only, nothing is treated and
the statement has no `customize`. The number is the same as `count(t.id)`; only the SQL text differs. A `LEFT` hop, a
treated node, a foreign-key read and a statement with a customizer keep counting the identity. A test that matches
the statement's text sees `count(*)`.

An eligible `INNER` entity join continues the walk to the root it is attached to, so counting the joined entity or a
to-one join below it is `count(*)` as well. A `LEFT` entity join stops the walk and keeps counting the entity's first
basic id attribute, so an unmatched or filter-blanked row counts 0. A filtered `COUNT` over no rows is 0 on both
provider lines.

## Aggregating a joined entity

`@Aggregate(entityJoin = ...)` reads `attribute` (and `FIRST_BY`'s `orderBy`) relative to a named `@JoinEntity` instead
of the root, with the same to-one paths as `@Column(entityJoin = ...)`. Omitting `entityJoin` keeps the root meaning
and the generated source, so every existing model is unchanged. This model is adapted from
`GeneratedEntityJoinExecutionTest`:

```java
@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "rep", entity = CustomerEntity.class, type = JoinKind.INNER,
        on = @JoinEntity.On(column = "REFERRER_ID"))
@FilterColumn(name = "REFERRER_ID", path = "referrerId")
@FilterColumn(name = "ID", path = "id")
public record EntityJoinTotals(
        @GroupBy @Column(entityJoin = "rep", attribute = "country") String country,
        @Aggregate(fn = AggregateFunction.COUNT, entityJoin = "rep", attribute = "id") Long reps,
        @Aggregate(fn = AggregateFunction.COUNT, entityJoin = "rep") Long rows,
        @Aggregate(fn = AggregateFunction.MAX, entityJoin = "rep", attribute = "createdAt") LocalDateTime lastHire) {}
```

```java
var totals = QEntityJoinTotals.query()
        .select(SelectSet.of(QEntityJoinTotals.COUNTRY, QEntityJoinTotals.REPS,
                QEntityJoinTotals.ROWS, QEntityJoinTotals.LAST_HIRE))
        .where(f -> f.gte(QEntityJoinTotals.ID, 990L).lte(QEntityJoinTotals.ID, 1030L))
        .orderBy(QEntityJoinTotals.COUNTRY.asc()).build();
```

=== "Plain JPA"

    ```java
    List<EntityJoinTotals> rows = executor.list(totals, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<EntityJoinTotals> rows = orders.findAll(totals, Limit.unlimited());
    ```

A `COUNT` with no `attribute` counts the joined entity's rows, through its first basic id attribute on a `LEFT` join
and as `count(*)` on an eligible `INNER` one. Other functions, `distinct`, filters and rounding keep their usual
semantics. A nonempty `entityJoin` combined with `expression` (including a `@BindExpression`) is `MQ3046` for every
function (D-202), as is an unknown join name; the other aggregate refusals keep their own codes.

## First value in a group

`Agg.firstBy(value, order, more...)` returns the value of the first row of each group in the order you give, in the
value's own type with its converter applied; a group with no row is `NULL`. The engine appends the root's id
ascending after your keys (a source root appends the source's key fields in key order), so ties pick the same row on
every vendor. Say `nullsFirst()` or `nullsLast()` on a nullable key, because the vendors disagree otherwise.

<!-- from FirstByTest#ac_agg_18_a_filtered_first_by_skips_a_first_row_that_fails_the_filter -->

```java
// VALUE and KEY are ColumnFields of the aggregated model
var latest = Agg.firstBy(VALUE, KEY.desc().nullsLast());                    // the value of the highest key
var latestKept = latest.filter(f -> f.ne(VALUE, "b")).as("latestKept");     // skipping rows that fail the filter
```

`@Aggregate(fn = AggregateFunction.FIRST_BY, attribute = "product", orderBy = {"amount desc nulls last"})` generates
the same call. Each `orderBy` key is `"path [asc|desc] [nulls first|nulls last]"` over a basic attribute of the
aggregated root.

`firstBy` renders `array_agg(v order by k, id)[1]`, so it runs on **PostgreSQL and H2**. On MySQL and `OTHER` it is
`MQ4015` before any statement, since there is no array aggregate there. A filtered `firstBy` needs the native `FILTER`
(`MQ4014` without it), because an array aggregate keeps the `NULL` of a skipped row. An order key that is an aggregate
or window field, and a value or order key read through a to-many join, is `MQ1412`.

### The ranked-source route

For MySQL, or when you want one statement shape on every vendor, pick the first row with a source: rank the rows with
a `ROW_NUMBER` window partitioned by the group and ordered by your key, read that source through a source over it
filtered on `rank = 1`, and [join it](source-models.md#joining-a-source) to the outer query. It runs on every Tier-1
vendor, but it is a different statement shape with its own cost (the window is computed for every row; on H2 a joined
window source is slow), so it is a choice you make, never an automatic fallback.

The three new `@Aggregate` members in one model (adapted from `AggregateModelTest`):

```java
@QueryModel(root = SaleEntity.class)
public record SalesSummary(
        @GroupBy String region,
        @Aggregate(fn = AggregateFunction.AVG, attribute = "amount", scale = 2) BigDecimal averageAmount,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "amount", filter = EuOnly.class) BigDecimal euAmount,
        @Aggregate(fn = AggregateFunction.FIRST_BY, attribute = "product", orderBy = {"amount desc nulls last"})
        String topProduct) {}

public final class EuOnly implements FilterDefinition<SalesSummary> {
    @Override
    public UnaryOperator<Filters<SalesSummary>> condition() {
        return f -> f.eq(QSalesSummary.REGION, "EU");
    }
}
```

Query it like any aggregate model:

=== "Plain JPA"

    ```java
    var q = QSalesSummary.query()
            .select(QSalesSummary.GROUP_KEYS.with(QSalesSummary.AVERAGE_AMOUNT, QSalesSummary.EU_AMOUNT,
                    QSalesSummary.TOP_PRODUCT))
            .orderBy(QSalesSummary.REGION.asc()).build();
    List<SalesSummary> rows = executor.list(q, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    var q = QSalesSummary.query()
            .select(QSalesSummary.GROUP_KEYS.with(QSalesSummary.AVERAGE_AMOUNT, QSalesSummary.EU_AMOUNT,
                    QSalesSummary.TOP_PRODUCT))
            .orderBy(QSalesSummary.REGION.asc()).build();
    List<SalesSummary> rows = sales.findAll(q, Limit.unlimited());
    ```

An `AVG` field typed `BigDecimal` is the exact average (`Agg.avgExact`), and `scale` (0 to 30; `-1`, the default, means
unset) rounds a `BigDecimal` result with `Agg.round`. A filter applies to the inner aggregate, before it is rounded.
`MQ3045` for `orderBy` without `FIRST_BY` or the reverse, an unknown or malformed key, a `filter` class that is not a
public `FilterDefinition` of the model with an `INSTANCE` or a no-arg constructor, a `scale` out of range or on a field
that is not a `BigDecimal`, and an `AVG` field typed `BigDecimal` over a `Double` or `Float` attribute.

## Whole-table totals

A model with aggregates and no `@GroupBy` field is a single-group query, like `SELECT count(*) FROM ...`. Declare it
deliberately with `@QueryModel(singleGroup = true)`; otherwise the processor reports `MQ3203`.

## `having`

`having(h -> h.gte(REVENUE, minRevenue))` is the `Filters` DSL over aggregates, with the same `Optional` skipping.
Aggregates cannot appear in `where` and plain columns cannot appear in `having`: both are compile errors.

```java
var q = QOrderTotals.query()
        .select(QOrderTotals.GROUP_KEYS.with(QOrderTotals.ORDERS, QOrderTotals.REVENUE))
        .where(f -> f.eq(QOrderTotals.STATUS, "PAID"))
        .having(h -> h.gte(QOrderTotals.REVENUE, minRevenue))
        .orderBy(QOrderTotals.REVENUE.desc())
        .build();
```

## Choose the grouping at runtime

The case in one line: one aggregate model serves several groupings, and the caller picks the keys per request
(AC-AGG-16).

A model may declare more than one `@GroupBy` field. Its `GROUP_KEYS` is all of them, but `groupBy` overrides that
default: name the keys you want, and each `@GroupBy` field left unselected reads back `null`.

```java
@QueryModel(root = CompositeKeyItemEntity.class)
public record ItemGroupTotals(
        @GroupBy Integer tenantId,
        @GroupBy String label,
        @Aggregate(fn = AggregateFunction.COUNT) Long items,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "amount") BigDecimal amount) {}
```

`groupBy(ScalarField...)` groups by the columns you pass:

```java
var byTenant = QItemGroupTotals.query()
        .select(SelectSet.of(QItemGroupTotals.TENANT_ID, QItemGroupTotals.ITEMS))
        .groupBy(QItemGroupTotals.TENANT_ID)
        .orderBy(QItemGroupTotals.TENANT_ID.asc())
        .build();
```

`groupBy(SelectSet)` takes the same keys as a set, which is what a request that names its own grouping builds:

```java
var byLabel = QItemGroupTotals.query()
        .select(SelectSet.of(QItemGroupTotals.LABEL, QItemGroupTotals.AMOUNT))
        .groupBy(SelectSet.of(QItemGroupTotals.LABEL))
        .orderBy(QItemGroupTotals.LABEL.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<ItemGroupTotals> tenantGroups = executor.list(byTenant, Limit.unlimited());
    List<ItemGroupTotals> labelGroups = executor.list(byLabel, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<ItemGroupTotals> tenantGroups = items.findAll(byTenant, Limit.unlimited());
    List<ItemGroupTotals> labelGroups = items.findAll(byLabel, Limit.unlimited());
    ```

In each result the key that is not grouped is `null`: a row of `tenantGroups` has a `null` label, and a row of
`labelGroups` has a `null` tenant id. One model with several `@GroupBy` fields replaces one model per grouping.

Tested by `AggregatesTest` in the TCK.

## Rules

- **Every selected plain column must be a group key** (`MQ1401`). MySQL would otherwise return an arbitrary value and
  PostgreSQL would fail at run time without naming the column.
- **`orderBy` keys must be group keys or aggregates** in a grouped query, and never an aggregate in an ungrouped one
  (`MQ1406`).
- **`having` needs a grouping** (`MQ1407`): a `groupBy` or a selected aggregate.
- **A grouped query has no primary key**, and `count` counts groups, not rows.
- **No keyset paging** (`MQ1402`). Use `list`, `stream` or offset `export`; export orders by every group key not
  already in your order, so pages never overlap.
- `Agg.sum`, `sumAsLong` and `avg` over a column that has a converter fail with `MQ1408`; use `Agg.of` for that.
  `Agg.min`, `max` and `countDistinct` take an `OrderedColumnField`: a column with no converter or with an
  `OrderedColumnConverter`, such as the built-in `InstantTimestampConverter` and `DateTimestampConverter`, and `min` and
  `max` come back as the model type. Over a column with any other converter they do not compile.
- An `@Aggregate(fn = MIN)` or `MAX` field of type `Instant` or `Date` over a `Timestamp` attribute reads through the
  built-in converter and comes back as the field's type.
