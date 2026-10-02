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

List<OrderTotals> rows = executor.list(totals, Limit.unlimited());
```

`GROUP_KEYS` is the generated column set of every `@GroupBy` field, and `QOrderTotals.query()` already groups by it.
Aggregates are in no generated column set, so you add them explicitly with `with(...)`. That keeps a plain query from
turning into a grouped one by accident.

## Aggregate functions

`@Aggregate(fn = ...)` takes `COUNT`, `SUM`, `AVG`, `MIN` or `MAX`; `attribute` is omitted for `COUNT` over the root,
and `distinct = true` gives `COUNT(DISTINCT ...)`. For a hand-written model the same functions are on `Agg`:
`count`, `countDistinct`, `sum`, `sumAsLong`, `avg`, `min`, `max`, and `Agg.of(name, type, expression)` for an
aggregate expression of your own.

An aggregate's Java type is what the database returns, not the column's type:

| Function | Result type |
|---|---|
| `COUNT` | `Long` |
| `SUM` of `BigDecimal`, `Double` or `Long` | the same type |
| `SUM` of `Integer` or `Short` | `Long` (use `sumAsLong`; `Agg.sum` on a 32-bit column fails with `MQ1403`) |
| `AVG` | `Double` |
| `MIN`, `MAX` | the column's type |

`SUM` over zero rows is `NULL`, not `0`, so an aggregate field must be a boxed type. The library never invents a
value for you; use `Objects.requireNonNullElse` in `afterMap` if you want zero.

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
