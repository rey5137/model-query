# Queries and Filters

A `ModelQuery` describes what to read: the columns, the filters and the order. It is immutable and holds no database
state, so you can build it once and share it between threads.

## The builder

`QOrderView.query()` returns a builder already configured with the model's root, mapper and primary key. Everything
besides `columns` is optional.

```java
ModelQuery<OrderEntity, Long, OrderView> q = QOrderView.query()
        .columns(QOrderView.DEFAULT)
        .where(f -> f.eq(QOrderView.STATUS, status))
        .orderBy(QOrderView.CREATED_AT.desc())
        .build();
```

| Builder method | Purpose |
|---|---|
| `columns(ColumnSet)` | The selection. `build()` without it fails. |
| `where(f -> ...)` | The filters, see below. |
| `orderBy(OrderField...)` | `asc()` or `desc()` on a column, optionally `.nullsFirst()` or `.nullsLast()`. |
| `keyset()` | Allows keyset paging; see [Paging and export](paging-export.md). |
| `primaryKeyFirst(...)` | Two-step deep paging for large offsets. |
| `afterMap((model, row) -> ...)` | Fills derived fields after the row is mapped. |
| `customize(QueryCustomizer)` | An escape hatch with raw `CriteriaBuilder` access. |
| `groupBy`, `having` | Grouped queries; see [Grouped queries](grouped-queries.md). |

Without `orderBy`, `list` and `count` are unordered. Without `where`, `count` counts the whole table.

## Running a query

The executor, whether from `ModelQueryExecutor.create(em, Entity.class, config)` or as a Spring Data repository,
offers:

| Method | Returns |
|---|---|
| `list(q, Limit)` | The mapped rows, in order. `Limit.unlimited()` applies no cap, as does `Limit.of(null)`, and `Limit.of(0)` returns nothing without querying. |
| `page(q, PageSpec, CountMode)` | A `Slice`; see [Paging and export](paging-export.md). |
| `count(q)` | The number of matching rows (groups, for a grouped query). |
| `stream(q, Limit, body)` | Runs `body` on a `Stream` that the library closes for you. |
| `export(q, ExportOptions, pageTransformer, sink)` | Visits every row exactly once, one page at a time. |

`count` is not inflated by collection joins: the engine uses `count(distinct root)` only when a to-many join exists.

## Filters

`where` hands you a `Filters` builder that ANDs every filter you add. Every method that takes a value has two forms:

- `eq(COLUMN, value)` always applies. A `null` value fails with `MQ1301`, because "no filter" should be explicit.
- `eq(COLUMN, Optional<value>)` applies only when the `Optional` is present. An empty `Optional` skips the filter
  and creates no join for it.

That makes search forms easy to write, with every field optional:

```java
.where(f -> f
        .eq(QOrderView.STATUS, status)                        // Optional<String>
        .range(QOrderView.CREATED_AT, from, to)               // Optional bounds
        .or(g -> g.likeIgnoreCase(QOrderView.CUSTOMER_NAME, q, LikeMode.CONTAINS),
            g -> g.eq(QOrderView.CUSTOMER_EMAIL, q))
        .exists(QOrderView.ITEMS_TABLE, i -> i.eq(QOrderView.ITEM_SKU, sku))
        .when(!includeCancelled, g -> g.ne(QOrderView.STATUS, "CANCELLED")))
```

The available filters:

| Group | Methods |
|---|---|
| Equality and comparison | `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `range`, `between` |
| Sets | `in`, `notIn` |
| Strings | `like`, `likeIgnoreCase`, `eqIgnoreCase`, with a `LikeMode` of `EXACT`, `CONTAINS`, `STARTS_WITH` or `ENDS_WITH` |
| Nulls | `isNull`, `isNotNull`, and a tri-state `isNull(column, Optional<Boolean>)` |
| Column against column | `compare(left, Op, right)` |
| Composition | `or` (two or three branches, or a `List` of them), `not`, `when`, `apply` (reuse a shared fragment) |
| Sub-queries | `exists`, `notExists` |
| Escape hatch | `add((joinContext, criteriaBuilder) -> predicate)` |

### What the rules mean for you

- **Skipping is local.** A skipped filter disappears from its group. An `or` whose branches were all skipped is
  skipped, rather than turning into `FALSE` and matching nothing. Use `exists(path)` with no inner group to ask for
  "has at least one" explicitly.
- **An empty collection is not "skip".** `in(COLUMN, List.of())` matches nothing, and `notIn(COLUMN, List.of())`
  adds no condition, because an empty selection in a UI means "none of these". To skip, pass `Optional.empty()`.
- **Negation includes NULLs.** `ne` and `notIn` on a nullable column also match rows where the column is NULL, which
  is what a report filter means by "not X". Add `isNotNull` for the strict SQL meaning. `not(group)` is plain
  `NOT (...)`, so rows where the group is unknown are excluded.
- **Strings are escaped.** `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` escape `%`, `_` and the escape character, so a
  search for `50%` finds that literal text. `EXACT` passes the pattern through unchanged.
- **`likeIgnoreCase` lower-cases the column**, so it needs a functional index to be fast. Without it, case
  sensitivity follows the column's collation; see [Vendor notes](vendors.md).
- **Every value is a bind parameter.** The library never inlines a value into SQL.
- **Long `IN` lists are split** into chunks of the vendor's limit, within one statement. A query's own statement is
  never split across statements, so one filter with more values than the vendor's bind parameter limit fails with
  `MQ1306` when the query is built, and filters that only together pass it fail with `MQ1307` before the statement
  runs, instead of failing in the database. A keyset export page or write round binds the previous row's sort keys on
  top of the query's own, so a query that fits the first page can pass the limit on a later one; that `MQ1307` says
  how many of the binds are the cursor's.
- **A join first needed inside `or` or `not` is a LEFT join**, so one branch cannot remove rows another branch should
  match.

### `exists`

`exists(ITEMS_TABLE, inner)` renders a correlated sub-query. It joins nothing on the outer query, so `count` and
export need no de-duplication, which makes it the preferred form for "has a child matching X". Columns inside
`inner` must sit on the given path or below it.

`Filters` builds `WHERE` predicates only. Conditions on aggregates go through `having`; see
[Grouped queries](grouped-queries.md).
