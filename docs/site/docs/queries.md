# Running queries

A `ModelQuery` describes what to read: the columns, the filters and the order. It is immutable and holds no database
state, so you can build it once and share it between threads.

## The builder

`QOrderView.query()` returns a builder already configured with the model's root, mapper and primary key. Everything
besides `select` (or `fetch`) is optional.

```java
ModelQuery<OrderEntity, Long, OrderView> q = QOrderView.query()
        .select(QOrderView.DEFAULT)
        .where(f -> f.eq(QOrderView.STATUS, status))
        .orderBy(QOrderView.CREATED_AT.desc())
        .build();
```

| Builder method | Purpose |
|---|---|
| `select(SelectSet)` | The selection. `build()` without it, or a `fetch`, fails. |
| `fetch(FetchPlan)` | The selection of a fetch plan, and the plan. `select` and `fetch` replace each other whole: a `select` after a `fetch` drops the plan and logs a warning, so call `fetch` last. |
| `where(f -> ...)` | The filters; see [Filters](filters.md). |
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

On a repository, `list` is `findAll` and `page` is `findPage`, which takes a Spring `Pageable`; the others keep their
names. A repository runs queries rooted at its own entity, and `stream` opens a read-only transaction when none is
active.

=== "Plain JPA"

    ```java
    var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());

    List<OrderView> rows = executor.list(q, Limit.of(100));
    Slice<OrderView> page = executor.page(q, PageSpec.of(0, 20), CountMode.COUNT);
    long total = executor.count(q);
    ```

=== "Spring repository"

    ```java
    public interface OrderRepository extends JpaRepository<OrderEntity, Long>, ModelQueryRepository<OrderEntity> {}

    List<OrderView> rows = orders.findAll(q, Limit.of(100));
    ModelPage<OrderView> page = orders.findPage(q, PageRequest.of(0, 20), CountMode.COUNT);
    long total = orders.count(q);
    ```

`count` is not inflated by collection joins: a filter-only to-many join renders as a semi-join (`exists`), while for a
customizer's join, or a shape the semi-join excludes (a treated node, a having, or a selected column, order or group
key that keeps the to-many join, or a fetch-plan through load), the engine uses `count(distinct root)`.

## Reading one row

Three methods read at most one model and return an `Optional` (D-123, `@Incubating`):

| Method | Meaning |
|---|---|
| `one(q)` | The single matching row, or empty. A second row throws `MQ2003`. |
| `first(q)` | The first row in a stable order, or empty. Never `MQ2003`. |
| `one(q, key)` | The row with that primary key that also matches `q`'s filter, or empty. |

=== "Plain JPA"

    ```java
    Optional<OrderView> order = executor.one(q);
    Optional<OrderView> newest = executor.first(q);
    Optional<OrderView> byKey = executor.one(q, 42L);
    ```

=== "Spring repository"

    ```java
    Optional<OrderView> order = orders.findOne(q);
    Optional<OrderView> newest = orders.findFirst(q);
    Optional<OrderView> byKey = orders.findByKey(q, 42L);
    ```

`one` reads at a limit of 2, so a second row is found and not hidden; `MQ2003` is thrown before any row is mapped, and
the [fetch plan](fetch-plans.md) runs once, on the one row left. `one` counts rows as `list` returns them: a
filter-only to-many join renders as a semi-join (`R-EXE-16`) and does not repeat the root row, but a customizer's join,
or a shape the semi-join excludes (a treated node, a having, or a selected column, order or group key that keeps the
to-many join, or a fetch-plan through load), can repeat the root row and give `MQ2003`. The message names the join;
replace it with [`Filters.exists`](filters.md#exists), which joins nothing on the outer query.

`first` orders by the query's `orderBy`, then by the primary key (the group keys, for a grouped query) wherever the
`orderBy` does not already cover it, with `nullsLast` stated for those implicit columns. That tie-breaker makes the
same row come back on every database, even when a group key is `NULL`. A query with neither an
`orderBy` nor a key is `MQ2203`, an aggregate-only query excepted since it has one row. When the selection reads
through a to-many join, the key spans several rows and the row `first` picks is not deterministic, as with offset
paging.

`one(q, key)` adds `primary key = key` to `q`'s filter, converting the key as `whereKey` does; contradictory filters
give empty. A `null` key or component, or the wrong number of components for a composite key, throws
`IllegalArgumentException`, a query without a primary key (or a grouped one) is `MQ2203`, and a `@PrimaryKey` on a
non-id column that matches two rows is `MQ2003`. On a Spring repository the three are `findOne`, `findFirst` and
`findByKey`; see [Spring Data](spring.md#the-repository).

## Returning writes

An update, a delete or an insert can give back the rows it wrote in the same statement: `updateReturning`,
`deleteReturning` and `insertReturning`, on the executor and on the Spring repository (R-WRT-50, `@Incubating`). They
are described with the writes in [Returning the changed rows](bulk-writes.md#returning-the-changed-rows),
[Conflict clauses](inserts.md#conflict-clauses) and the [Claim the next job](recipes.md#claim-the-next-job) recipe.
