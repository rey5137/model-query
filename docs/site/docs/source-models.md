# Source models

!!! warning "Incubating"
    Source models, and every type on this page — `@SourceModel`, `SourceQuery`, `@Window`, `@Depth`,
    `@Discriminator`, `SourceRenderer` and the generated `source()`, `query(source)`, `Rows` and `WindowField`
    members — are new in 0.10.0 and annotated `@Incubating`, as the rest of the API in 0.x is
    (see [API stability](stability.md)). They are complete
    and tested, but their API may still change in a minor release.

A **source model** is a query you name once and read directly or through another model. The library renders it as a
common table expression (CTE) and reads the CTE's columns by field name, so the same query can be an outer model's
rows, a window over them or one branch of a set operation. This is how CTEs, window functions, set operations and
recursion become reachable through a model instead of hand-written SQL:

```java
@SourceModel(root = OrderEntity.class)                     // the entity the source reads
public record OrderSource(@PrimaryKey Long id, String status, BigDecimal total) {}

@QueryModel(root = OrderSource.class)                      // the root is the source, not an entity
public record OrderFeed(@PrimaryKey Long id, String status, BigDecimal total) {}
```

`OrderSource` is an ordinary model otherwise: `@Column`, a to-one `@Join`, `@Computed`, `@Aggregate`, `@GroupBy` and
`@FilterColumn` work as they do on a `@QueryModel`, and the processor validates the source against its entity with the
same checks and codes. The source's `@PrimaryKey` is either the root entity's id, or the full `@GroupBy` set of a
grouped source; anything else is `MQ3031`, because a source's key has to identify one row (see
[Non-unique sources](#non-unique-sources)).

The generated `QOrderSource.source()` builds the source body: `where` and `having`, no `select` (a source always
carries every field of its model), and `build()` giving an immutable `SourceQuery<OrderSource>`. Its
[`query(source)` factory](#reading-a-source) reads the completed source:

```java
public static SourceQuery.Builder<OrderEntity, OrderSource, OrderSource> source() {
    return SourceQuery.builder(OrderSource.class, ROOT, ALL, KEY);
}

public static ModelQuery.Builder<OrderSource, Long, OrderFeed> query(SourceQuery<OrderSource> source) {
    return ModelQuery.builder(ROOT, MAPPER, source).primaryKey(KEY);
}
```

The outer model's `query(SourceQuery<...>)` takes the source, so an outer query without one does not compile. An outer
column is read from the CTE by field name and keeps the source column's type and converter; `@Column(converter = ...)`
on an outer column, a `@FilterColumn` and a `@Join` are refused (`MQ3014`, `MQ3011`, `MQ3035`), as is a `@Child` whose
child model is rooted at a source (`MQ3039`).

Because a source is a CTE, it is one statement: it is read through the executor created for the source model. A Spring
Data repository runs queries rooted at its own entity and a source-rooted query's root is the source model, which is no
entity, so reach the executor from Spring for these queries (`ModelQueryExecutor.create(em, OrderSource.class,
config)`), as the Spring tabs below do.

## Plain and grouped sources

<!-- from SourceQueryExecutionTest#ac_src_01_a_plain_source_reads_the_rows_its_hand_written_sql_reads -->

```java
var feed = QOrderFeed.query(QOrderSource.source().build()).select(QOrderFeed.ALL)
        .orderBy(QOrderFeed.ID.asc()).build();
```

=== "Plain JPA"

    ```java
    List<OrderFeed> rows = executor.list(feed, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<OrderSource> sources = ModelQueryExecutor.create(em, OrderSource.class, config);
    List<OrderFeed> rows = sources.list(feed, Limit.unlimited());
    ```

A grouped source follows [`@GroupBy` and `@Aggregate`](grouped-queries.md) as a grouped `@QueryModel` does; `where`
filters the rows the group is computed over and `having` the groups. An ungrouped outer model over a grouped source is
how you page groups by keyset.

<!-- from SourceQueryExecutionTest#ac_src_02_a_grouped_source_builds_the_cte_its_hand_written_sql_builds -->

```java
@SourceModel(root = OrderItemEntity.class)
public record QuantitySource(
        @PrimaryKey @GroupBy Integer quantity,
        @Aggregate(fn = AggregateFunction.COUNT) Long lineCount,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "unitPrice") BigDecimal revenue) {}

SourceQuery<QuantitySource> source = QQuantitySource.source()
        .where(f -> f.gte(QQuantitySource.QUANTITY, 5))
        .having(h -> h.gt(QQuantitySource.LINE_COUNT, 100L))
        .build();
var feed = QQuantityFeed.query(source).select(QQuantityFeed.ALL).orderBy(QQuantityFeed.QUANTITY.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<QuantityFeed> rows = executor.list(feed, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<QuantitySource> sources = ModelQueryExecutor.create(em, QuantitySource.class, config);
    List<QuantityFeed> rows = sources.list(feed, Limit.unlimited());
    ```

A CTE's column names are owned by the engine (an `mq_` prefix on the field's name), so a field named like a vendor's
reserved word (`rank`, `year`, `key`) is safe.

A keyset walk over a source registers the CTE again on every page, so each page reads the whole CTE body:

<!-- from SourceQueryExecutionTest#ac_src_03_a_keyset_export_over_a_grouped_source_visits_every_group_exactly_once -->

```java
var feed = QQuantityFeed.query(QQuantitySource.source().build()).select(QQuantityFeed.ALL)
        .orderBy(QQuantityFeed.QUANTITY.asc()).keyset().build();
```

=== "Plain JPA"

    ```java
    long rows = executor.export(feed, ExportOptions.of(4), page -> page, exported::add);
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<QuantitySource> sources = ModelQueryExecutor.create(em, QuantitySource.class, config);
    long rows = sources.export(feed, ExportOptions.of(4), page -> page, exported::add);
    ```

A source over a polymorphic entity root can also declare treated columns and `@Subtype` fields in its body; see
[Inheritance roots](inheritance-roots.md#union-of-subtype-models-through-a-source-shape).

## Window fields

`@Window` puts a window function in the source's CTE, so an outer model reads its value as a plain column. The field's
type is the function's result type (`Long` for the ranking and counting functions, the argument's type for
`LAG`, `LEAD`, `MIN` and `MAX`, and `@Aggregate`'s rule for `SUM` and `AVG`); a mismatch is `MQ3032`.

<!-- from WindowFieldExecutionTest#ac_src_05_every_window_function_reads_the_values_its_hand_written_sql_reads -->

```java
@SourceModel(root = OrderEntity.class)
public record WindowSource(
        @PrimaryKey Long id,
        String status,
        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "status", orderBy = "placedAt") Long rowIndex,
        @Window(function = WindowFunction.RANK, partitionBy = "status", orderBy = "placedAt") Long ranking,
        @Window(function = WindowFunction.LAG, argument = "total", partitionBy = "status",
                orderBy = "placedAt") BigDecimal previousTotal,
        @Window(function = WindowFunction.SUM, argument = "total", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal runningTotal) {}

@QueryModel(root = WindowSource.class)
public record WindowFeed(
        @PrimaryKey Long id, String status, Long rowIndex, Long ranking, BigDecimal previousTotal,
        BigDecimal runningTotal) {}

var feed = QWindowFeed.query(QWindowSource.source().build()).select(QWindowFeed.ALL)
        .orderBy(QWindowFeed.ID.asc()).build();
```

=== "Plain JPA"

    ```java
    List<WindowFeed> rows = executor.list(feed, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<WindowSource> sources = ModelQueryExecutor.create(em, WindowSource.class, config);
    List<WindowFeed> rows = sources.list(feed, Limit.unlimited());
    ```

The window's `orderBy` is where its value comes from, and a window whose value depends on rows it cannot tie
(`ROW_NUMBER`, `LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE`, an aggregate under a `ROWS` frame) is order-sensitive: the
engine appends the source key's columns, ascending, after it, so every run gives a row the same value. A key column the
window already orders by is not repeated. `RANK` and `DENSE_RANK` count peers, and an aggregate under a `RANGE` frame
or no frame reads its whole partition, so their order is left as written.

`frame = ...` writes one of the three `ROWS` frames or the two `RANGE` frames, or `NONE` for the database's default.
A frame is rendered only for a function that takes one, so a frame on `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG` or
`LEAD` is `MQ3032`, as is a missing frame on `FIRST_VALUE` or `LAST_VALUE`. In a grouped source a window term names a
`@GroupBy` key or an `@Aggregate` field, so a source can rank its groups by their aggregate.

## Set operations

A set operation combines branches of one **shape**. A branch is a source model that names the shared shape with
`@SourceModel(shape = ...)` and maps the shape's fields onto its own root: same names, in the same order, with the same
Java type, converter, enum and temporal mapping, or `MQ3034`.

<!-- from
SetOperationExecutionTest#ac_src_07_a_union_renders_the_distinct_sql_and_removes_the_rows_both_branches_yield -->

```java
@SourceModel(root = OrderEntity.class)
public record DispatchFeed(
        @Discriminator("live") @PrimaryKey String branch,
        @PrimaryKey Long id,
        @Column(attribute = "status") String label) {}

@SourceModel(root = NullableSortEntity.class, shape = DispatchFeed.class)
public record ShelvedDispatchFeed(
        @Discriminator("shelved") @PrimaryKey String branch,
        @PrimaryKey Long id,
        @Column(attribute = "sortText") String label) {}

SourceQuery<DispatchFeed> live = QDispatchFeed.source().build();
SourceQuery<DispatchFeed> shelved = QShelvedDispatchFeed.source().build();
var feed = QDispatchRow.query(live.union(shelved)).select(QDispatchRow.ALL)
        .orderBy(QDispatchRow.BRANCH.asc(), QDispatchRow.ID.asc()).build();
```

=== "Plain JPA"

    ```java
    List<DispatchRow> rows = executor.list(feed, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<DispatchFeed> dispatch = ModelQueryExecutor.create(em, DispatchFeed.class, config);
    List<DispatchRow> rows = dispatch.list(feed, Limit.unlimited());
    ```

`union`, `unionAll`, `intersect` and `except` take another `SourceQuery` of the same shape, so a branch of another
shape does not compile, and they nest in the order they are called: `a.union(b).except(c)` is
`(a union b) except c`. A row belongs to an `intersect` or an `except` result only if every column matches.

`@Discriminator("...")` fixes a branch's value for one column and must be part of the key: that is what keeps branches
apart. Two branches of one `union` or `unionAll` fixing the same value are `MQ1904` where the operation is built, since
the union would quietly lose key uniqueness. A distinct `union` whose branches all read one branch model is unique too:
every root row yields the same columns, so a row reached by both branches collapses to one.

A union can also combine models rooted at different subtypes of a polymorphic entity hierarchy into a single shape;
see [Inheritance roots](inheritance-roots.md#union-of-subtype-models-through-a-source-shape).

### Non-unique sources

A source is **non-unique** when its key does not identify a row: a `unionAll` of one model with itself, an `except`
whose left branch is non-unique, a `union` of branches that are not disjoint by key, or any recursive source. On a
query over one, every feature that identifies a row by its key is refused before any statement:

| Feature | Refused at | Code |
|---|---|---|
| `keyset()`, `primaryKeyFirst(...)` | the outer `build()` | `MQ1901` |
| `export`, `byKeys`, `one(q, key)`, a fetch plan with a child, join plan or enricher | the executor call | `MQ2212` |

`list`, `count`, `first`, `stream` and offset `page` work. On a non-unique source the key cannot break ties, so offset
paging appends every selected outer column, ascending, as tie-breakers after the definition's `orderBy` and the key: two
rows equal in every column cannot be told apart, but no row is visibly lost. A selected column that cannot be ordered
(a LOB, a JSON type) is `MQ2212`.

## Recursive sources

A source model is **recursive** when it declares a `@Depth` field: its rows are the nodes of a tree or a graph, the CTE
assigns each row its level, and `source()` requires the step that walks it.

<!-- from RecursiveSourceExecutionTest#ac_src_12_a_tree_read_by_a_recursive_source_returns_every_node_at_its_level -->

```java
@SourceModel(root = FolderNodeEntity.class)
public record FolderNode(@PrimaryKey Long id, Long parentId, String name, @Depth Integer level) {}

SourceQuery<FolderNode> tree = QFolderNode.source().where(f -> f.eq(QFolderNode.ID, root))
        .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
var rows = QFolderRow.query(tree).select(QFolderRow.ALL).orderBy(QFolderRow.ID.asc()).build();
```

=== "Plain JPA"

    ```java
    List<FolderRow> nodes = executor.list(rows, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<FolderNode> folders = ModelQueryExecutor.create(em, FolderNode.class, config);
    List<FolderRow> nodes = folders.list(rows, Limit.unlimited());
    ```

The rows `where` keeps are level 0 and each step adds one, so `maxDepth = 20` returns levels 0 to 20. `recursive(step,
maxDepth)` names the two columns a row is a child of another by, both of the source's root entity, so a swapped pair
reads wrong at the call site rather than at run time. A recursive model must call `recursive(...)` or `tree(...)`
before `build()`:
without it the source would quietly return the seed rows alone, which is `MQ1902`, as is a `maxDepth` below 1 or above
`Integer.MAX_VALUE - 2`. A recursive source takes no `@Window`, `@Aggregate` or `@GroupBy` — every database forbids
all three in the recursive term — and exactly one `@Depth`, an `Integer` (`MQ3037`).

The CTE steps to level `maxDepth + 1`, and the cycle clause marks the row that closes a cycle; the outer query filters
both out. The statement reads the smallest key past `maxDepth` and the smallest marked key from its own first row and
refuses the whole result: a row too deep is `MQ2701` and a cycle is `MQ2702`, each naming the model and the first
offending key. Nothing is refused on an empty result, which carries no row to read.

### Trees

Use `tree(...)` when the walk should reach each node once. It takes the same step and depth limit as
`recursive(...)`, and checks that no node key repeats across the whole walk in each statement (R-SRC-13..15).

<!-- from RecursiveSourceExecutionTest#ac_src_17_every_key_identified_read_visits_each_tree_node_once -->

```java
var source = QFolderNode.source().where(f -> f.in(QFolderNode.ID, List.of(1L)))
        .tree(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), 3).build();
var keyset = QFolderRow.query(source).select(QFolderRow.ALL).orderBy(QFolderRow.ID.asc()).keyset().build();
```

The checked source is unique, so keyset and primary-key-first paging, `byKeys`, `one(q, key)`, both export modes and
fetch plans work over it. Offset `page` adds only the key as a tie-breaker to the definition's order. A plain
`recursive(...)` source keeps its `MQ1901` and `MQ2212` refusals and its every-column offset tie-breakers.

<!-- from RecursiveSourceExecutionTest#ac_src_17_every_key_identified_read_visits_each_tree_node_once -->

=== "Plain JPA"

    ```java
    List<FolderRow> exported = new ArrayList<>();
    executor.export(keyset, ExportOptions.of(2), page -> page, exported::add);
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<FolderNode> folders = ModelQueryExecutor.create(em, FolderNode.class, config);
    List<FolderRow> exported = new ArrayList<>();
    folders.export(keyset, ExportOptions.of(2), page -> page, exported::add);
    ```

Overlapping seeds (a node and one of its descendants) or a link-table diamond can reach a node twice. That is
[`MQ2703`](diagnostics.md), naming the model and the first column of the smallest duplicated key: narrow the seeds,
or use `recursive(...)` if repeated paths are intended. The engine checks depth (`MQ2701`), then cycles (`MQ2702`),
then duplicates, before delivering a row. An outer filter cannot hide duplicates when the statement returns a row;
an empty result carries no check value. Each statement checks the whole walk again, with no state carried between
pages or export chunks.

## Sources over sources

A `@SourceModel` can read another source model's completed rows and add windows, aggregates, groups, computed
fields or filter columns. For example, the TCK's `RankedOrderSource` ranks the `OrderSource` rows within each status:

```java
@SourceModel(root = OrderSource.class)
@FilterColumn(name = "ORDER_TOTAL", path = "total")
public record RankedOrderSource(
        @PrimaryKey Long id,
        String status,
        BigDecimal total,
        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "status", orderBy = "total desc") Long rank) {}
```

The generated `@Incubating` factory is `source(SourceQuery<X> inner)` instead of `source()`. Pass the completed
inner definition, then filter the rows the next level will rank. This is the shape used by
`ChainedSourceExecutionTest#ac_src_26_a_source_over_a_plain_source_executes_and_matches_hand_written_sql`:

```java
SourceQuery<OrderSource> inner = QOrderSource.source()
        .where(f -> f.in(QOrderSource.STATUS, List.of("PAID", "SHIPPED")))
        .build();
SourceQuery<RankedOrderSource> ranked = QRankedOrderSource.source(inner)
        .where(f -> f.gte(QRankedOrderSource.ORDER_TOTAL, new BigDecimal("50.00")))
        .build();
var query = QRankedOrderFeed.query(ranked).select(QRankedOrderFeed.ALL)
        .where(f -> f.lte(QRankedOrderFeed.RANK, 3L))
        .orderBy(QRankedOrderFeed.STATUS.asc(), QRankedOrderFeed.RANK.asc()).build();
```

=== "Plain JPA"

    ```java
    List<RankedOrderFeed> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<RankedOrderSource> executor =
            ModelQueryExecutor.create(em, RankedOrderSource.class, config);
    List<RankedOrderFeed> rows = executor.list(query, Limit.unlimited());
    ```

A root may be a plain or grouped source, a set-operation **shape**, a recursive walk or a checked tree. Root at the
shape to read a branch's rows; a branch model itself cannot be the root (`MQ3041`). A source over a source cannot
itself be recursive, a branch or a shape (`MQ3041`). Fields name single columns of the inner model; association
joins remain unavailable (`MQ3035`). A missing or wrong inner definition is `MQ1907` at the builder/build boundary,
including misuse through raw types or casts. The generated factory requires the inner argument at compile time.
A correlated expression in a source's fields is `MQ1908` when the source definition is built; select it in the
[outer query](subqueries-expressions.md#correlated-columns) instead.

An ungrouped level keeps exactly the inner source's key fields, in order, and inherits its uniqueness. A grouped
level uses the full non-null group key and establishes uniqueness even over a non-unique inner source. Over a plain
recursive walk, aggregates count paths; use a checked `tree(...)` source to count nodes instead.

Each level becomes an engine-named CTE in the same statement, from the innermost source out. Chains have no fixed
depth limit, and each content, count, key-phase or export-page statement rebuilds its own chain. If an inner source
is recursive, the next level applies its depth/cycle walk filter **before** computing windows and aggregates.
Depth, cycle and duplicate-key checks still read the inner walk from the top statement. As with a single source,
a row-returning statement that produces no rows carries no check value, including when an intermediate HAVING
removes every group; an ungrouped count still returns a check row.

Key predicates also stay in the top statement: `byKeys`, the keyset cursor, `primaryKeyFirst`'s second step and
`one(query, key)` filter the finished result. They do not narrow a CTE before its ranks or aggregates are computed,
so a rank always counts the full source defined by its own filters.

## Joining a source

A query rooted at an entity can join a unique source on the source's full key, which reads the source's completed
rows (a group's total, a rank, a first value) as columns of the outer row. This is the report shape: one row per day,
with the day's sales alongside. `@JoinSource` joins it, and `@SourceColumn` reads one of its fields. This is adapted
from `JoinSourceModelTest` and `SourceJoinExecutionTest`:

```java
@SourceModel(root = SaleEntity.class)
public record DaySales(
        @PrimaryKey @GroupBy LocalDate businessDate,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "cashTotal") BigDecimal cash) {}

@QueryModel(root = DayEntity.class)
@JoinSource(name = "sales", source = DaySales.class,
        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
public record UnreconciledDay(
        @PrimaryKey Long id, LocalDate businessDate, BigDecimal countedCash,
        @SourceColumn(join = "sales", field = "cash") BigDecimal salesCash) {}
```

The generated `QUnreconciledDay.query(...)` takes one `SourceQuery` for each join, in declaration order, so the
caller supplies the completed source definition (with its own `where`) and the compiler checks that none is missing.
The joined column is an ordinary column of the model: select it, filter it, order by it or page by it.

```java
SourceQuery<DaySales> sales = QDaySales.source().build();
var query = QUnreconciledDay.query(sales)
        .select(QUnreconciledDay.ALL)
        .where(f -> f.distinctFromColumn(QUnreconciledDay.COUNTED_CASH, QUnreconciledDay.SALES_CASH))
        .orderBy(QUnreconciledDay.BUSINESS_DATE.asc()).build();
```

=== "Plain JPA"

    ```java
    List<UnreconciledDay> days = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<UnreconciledDay> rows = days.findAll(query, Limit.unlimited());
    ```

A hand-written query does the same with `SourceJoin.of(name, Source.class, kind, List.of(SourceJoin.on(outer, key)))`,
`ModelQuery.Builder#joinSource(join, sourceQuery)` and `ColumnField.over(Model.class, join.root(), SOURCE_FIELD)`. Two
joins of one source model (a high band and a low band, say) stay apart by name.

- **`LEFT` or `INNER`.** The default, `JoinKind.LEFT`, keeps an outer row the source has no row for and reads its
  joined columns as `NULL`, so a joined field is a boxed type. `JoinKind.INNER` drops that outer row.
- **The full key, once.** `on` must pair every key field of the source exactly once, so the join adds at most one row
  per outer row and the outer key, cursors, counts and export pages keep their guarantees. A missing, extra or
  repeated pair is [`MQ3044`](diagnostics.md) at compile time for `@JoinSource` and [`MQ1910`](diagnostics.md) when a
  hand-written query is built. A hand-written `on(...)` pair is also `MQ1910` when the source key field has a
  converter or the two attribute types differ (`Long` against `Integer`; `int` against `Integer` is fine), since the join
  compares database values.
- **The source must be unique.** `SourceQuery#unique()` decides: a plain or grouped source, a `tree(...)` source, an
  ungrouped source over a tree and a grouped source over any walk qualify. A set operation, a plain `recursive(...)`
  source and an ungrouped source over one do not, because a node reached by two paths would repeat its outer row. They
  are `MQ1910` when the query is built, and the message names `tree(...)` when the source recurses. The generated
  `query(...)` takes the caller's `SourceQuery`, so this check is at build time, never in the processor.
- **One walk per statement.** A statement reads one walk's checks, so joined chains that walk more than once are
  `MQ1910`. A `LEFT` join to a walk keeps an outer row whose node is past the depth limit, marked or never reached,
  with `NULL` columns; an outer row is checked whether or not the walk reached it. A grouped entity query that joins
  a walk counts its groups client-side through the same check site.
- **The join is in every statement.** Content, count, both primary-key-first steps, every export page, every `byKeys`
  chunk and the fetch plan's parent reads carry the CTE and the join, even a `LEFT` join no filter reads.
  Each statement binds the source's parameters once.
- **No locks, no writes.** `withLock(...)` on a query that joins a source is `MQ1903`, as for any source. A
  returning write (`persist(persist, returning)` and the other returning forms) over a query that joins one is
  `MQ1809`. `ModelUpdate`, `ModelDelete`, `InsertColumns` and `ModelInsert.select` take no source, and
  `generateInserts` and `generateChanges` leave a `@SourceColumn` field out. A `@Child` whose child model joins a
  source is `MQ3039`, since a fetch plan has no `SourceQuery` to pass it; a parent that joins one carries it on its
  parent reads.
- **Same vendor needs as a source root.** A joined source needs `CTE`, which `OTHER` lacks (`MQ4015`), and a provider
  whose `SourceRenderer` has the `join` hook (`MQ4014`). `model-query-hibernate` has it on both lines.

### Joining an entity

An entity join reads one row of an entity per outer row, the join a read-only association would express. Its outer
side is either a joined source (RFC 0021) or columns of the root and its to-one paths (RFC 0022 Part B). The
incubating `@JoinEntity` declares either kind; `@Column(entityJoin = ...)` and `@FilterColumn(entityJoin = ...)` read
its entity rather than the root.

**Off a joined source.** A joined source can supply the full id of an entity that the outer model also needs to read.
This generated form is adapted from `JoinEntityModelTest` and `GeneratedEntityJoinExecutionTest`:

```java
@SourceModel(root = OrderEntity.class)
public record EntityJoinKeys(
        @PrimaryKey Long id, @Column(attribute = "customer") Long customerId) {}

@QueryModel(root = OrderEntity.class)
@JoinSource(name = "keys", source = EntityJoinKeys.class,
        on = @JoinSource.On(column = "id", key = "id"))
@JoinEntity(name = "manager", entity = CustomerEntity.class, source = "keys",
        on = @JoinEntity.On(field = "id"))
@JoinEntity(name = "customer", entity = CustomerEntity.class, source = "keys",
        on = @JoinEntity.On(field = "customerId"))
@JoinEntity(name = "copy", entity = OrderEntity.class, source = "keys",
        on = @JoinEntity.On(field = "id"))
@FilterColumn(name = "MANAGER_VIP", entityJoin = "manager", path = "vip")
@FilterColumn(name = "CUSTOMER_VIP", entityJoin = "customer", path = "vip")
public record EntityJoinDetail(
        @PrimaryKey Long id,
        @Column(entityJoin = "manager", attribute = "name") String managerName,
        @Column(entityJoin = "customer", attribute = "name") String customerName,
        @Column(entityJoin = "copy", attribute = "customer.name") String viaOrder) {}
```

The generated factory still takes one completed `SourceQuery` per source join; it registers the entity joins itself.
This read is adapted from `GeneratedEntityJoinExecutionTest`:

```java
var keys = QEntityJoinKeys.source()
        .where(f -> f.gte(QEntityJoinKeys.ID, 990L).lte(QEntityJoinKeys.ID, 1020L)).build();
var query = QEntityJoinDetail.query(keys).select(QEntityJoinDetail.ALL)
        .where(f -> f.gte(QEntityJoinDetail.ID, 990L).lte(QEntityJoinDetail.ID, 1030L))
        .orderBy(QEntityJoinDetail.ID.asc()).build();
```

=== "Plain JPA"

    ```java
    List<EntityJoinDetail> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<EntityJoinDetail> rows = orders.findAll(query, Limit.unlimited());
    ```

The hand-written form uses an immutable `EntityJoin` and `ModelQuery.Builder#joinEntity`. With `SOURCE`, `KEYS`,
`S_ID` and `ID` defined as in `EntityJoinExecutionTest`, this excerpt joins a customer by the source's id:

```java
ColumnField<Detail, Keys, Long> anchor = ColumnField.over(Detail.class, SOURCE.root(), S_ID);
EntityJoin<Detail, CustomerEntity> manager = EntityJoin.of("manager", CustomerEntity.class,
        JoinKind.LEFT, List.of(EntityJoin.on(anchor, "id")));
var name = ColumnField.of(Detail.class, manager.root(), "name", String.class).named("manager");
var query = ModelQuery.builder(ORDERS,
        row -> new Detail(row.get(ID), row.get(name), null, null))
        .joinSource(SOURCE, KEYS).joinEntity(manager)
        .select(SelectSet.of(ID, name)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc()).build();
```

=== "Plain JPA"

    ```java
    List<Detail> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<Detail> rows = orders.findAll(query, Limit.unlimited());
    ```

**Off the root's columns.** With `source = ""` (the default), each `@JoinEntity.On` names a model column or a
`@FilterColumn` over the root or a to-one path from it. The join needs no joined source, and the generated
`query(...)` adds no source parameter. This model is adapted from `GeneratedEntityJoinExecutionTest`:

```java
@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "manager", entity = CustomerEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "id"),
        when = @JoinEntity.When(column = "STATUS", value = "PAID"))
@JoinEntity(name = "copy", entity = OrderEntity.class, type = JoinKind.INNER,
        on = @JoinEntity.On(column = "id"))
@FilterColumn(name = "STATUS", path = "status")
public record ColumnEntityJoinDetail(
        @PrimaryKey Long id,
        @Column(entityJoin = "manager", attribute = "name") String managerName,
        @Column(entityJoin = "copy", attribute = "customer.name") String viaOrder) {}
```

```java
var query = QColumnEntityJoinDetail.query().select(QColumnEntityJoinDetail.ALL)
        .where(f -> f.gte(QColumnEntityJoinDetail.ID, 990L).lte(QColumnEntityJoinDetail.ID, 1030L))
        .orderBy(QColumnEntityJoinDetail.ID.asc()).build();
```

=== "Plain JPA"

    ```java
    List<ColumnEntityJoinDetail> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<ColumnEntityJoinDetail> rows = orders.findAll(query, Limit.unlimited());
    ```

The hand-written form uses the same `EntityJoin` and `joinEntity` with a column anchor. With `ORDERS`, `ID` and
`STATUS` defined as in `ColumnEntityJoinExecutionTest`, this excerpt joins a customer on the root's id and adds the
static term:

```java
record Row(Long id, String manager, String referred, String referrer) {}

var manager = EntityJoin.of("manager", CustomerEntity.class, JoinKind.LEFT,
        List.of(EntityJoin.on(ID, "id"))).when(STATUS, "PAID");
var name = ColumnField.of(Row.class, manager.root(), "name", String.class).named("manager");
var query = ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), row.get(name), null, null))
        .joinEntity(manager).select(SelectSet.of(ID, name)).primaryKey(PrimaryKey.of(ID))
        .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc()).build();
```

=== "Plain JPA"

    ```java
    List<Row> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<Row> rows = orders.findAll(query, Limit.unlimited());
    ```

- **LEFT by default.** `@JoinEntity.type` defaults to `JoinKind.LEFT`; the hand-written forms above supply it
  explicitly. An unmatched source, anchor or entity leaves the outer row with null joined values. `JoinKind.INNER`
  drops either kind of unmatched row, including one whose column anchor is NULL because its own LEFT to-one path was
  unmatched. Separate names joining the same entity remain independent.
- **One anchor kind per join.** Each `@JoinEntity.On` pairs a field of the joined source named by `@JoinEntity#source`
  with an entity id attribute, or names a `column` over the root or a to-one path from it when `source = ""`. Every
  pair of one join uses the same kind, never a mix. Its `id` defaults to the sole basic id; composite ids need explicit
  pairs for every attribute, including flattened embedded paths such as `id = "key.part"`. Hand-written
  `EntityJoin.on(anchor, "id")` names the id explicitly.
  Converted anchors, partial or extra ids, association ids and differing boxed types are refused. `int` and `Integer`
  agree. Names must be unique across source and entity joins.
- **What an anchor may read.** A column anchor is the root or a to-one path from it (`customer.legacyRepCode`), not a
  joined source (use `field`), another entity join or a path below one (no chains), a to-many or treated path, an
  expression, a correlated column, or a to-one read by its id (anchor on the foreign key's basic column). Pairs over
  two anchor kinds, an anchor over another entity join, a converted anchor and a query rooted at a source are
  [`MQ1911`](diagnostics.md) at build; the id rules and a to-many path are `MQ1911` at first resolution, before any
  statement. A generated declaration is [`MQ3046`](diagnostics.md) instead.
- **The static `when` term.** `@JoinEntity.When(column, value)` and `EntityJoin#when(column, value)` AND one constant
  equality into the join's one `ON`, for a polymorphic reference whose ids collide across tables. The column follows
  the anchor rule, and the value must have the column's boxed type and be bindable (a `java.time` value binds; an
  enum, `Date`, `Calendar`, `Optional`, collection, array or entity value does not, and a `null` value is refused
  too). It is a definition constant, so the shared `EntityJoin` or `@JoinEntity` retains the value, as INV-9 allows;
  each statement binds it as a parameter, so the SQL text holds no literal. A second `when` is `MQ3046` from the
  processor or `MQ1911` when a hand-written join is built.
- **Basic columns and to-one paths.** Joined columns can follow to-one paths, as `customer.name` does above, with
  their declared join kind (`@FilterColumn#joinType` applies to the associations on the path; the entity join's own kind is `@JoinEntity#type`). A to-one column below an entity join resolves by its model field name (`viaOrder` here).
  To-many paths, nested `@Join`, a joined-column `@PrimaryKey`, `@JoinEntity` on `@SourceModel`, and `treat` combined
  with `entityJoin` are refused. A `@Child` whose child model declares an entity join is `MQ3039`, since a fetch plan
  passes no join; a nested `@Join` model that declares one is `MQ3044`.
  Generated declarations fail with [`MQ3046`](diagnostics.md); invalid hand-written
  definitions fail with [`MQ1911`](diagnostics.md), at build or first resolution before SQL; an entity class the render
  options' metamodel rejects as unmapped is `MQ1911` too (without a metamodel, the provider decides). A column over an entity join the query does not bind is `MQ1003`.
  A cursor over an entity-join column carries the join in its identity path, so it cannot collide with a root attribute.
- **Root-anchored in every statement.** The engine creates each entity join immediately after the joined sources and
  their entity joins, attached to the statement's root. This includes unused joins, content and count, both
  primary-key-first steps, keyset pages, `byKeys`, both `one` calls, export pages, streams, fetch-parent reads and
  through-child loads. An INNER entity join therefore drops outer rows from content and count alike; an INNER one
  whose anchor or `when` column reads through a `joinFilter` join is `MQ1912`, so declare it `LEFT`. Grouped queries
  with column-anchored entity joins count their groups through the provider's copy, which keeps the join; one that
  joins a source still counts client-side. A count of a joined entity or a descendant follows R-AGG-19: an eligible
  INNER entity join counts `count(*)`, a LEFT one counts its first basic id attribute, so an unmatched or
  filter-blanked entity counts zero, including with composite ids. A filtered `COUNT` over no rows is 0 on both
  provider lines.
- **No CTE gate.** `MQ4015` for an entity join on a vendor without CTEs (`OTHER`) applies only to a join anchored on
  a joined source; a column-anchored join renders on `OTHER`.

Use a [join filter](models.md#join-filters) to blank a LEFT-joined entity's columns for one request while keeping
its outer row. A `where` on a blanked column can still filter that row out.

### What a joined source costs

A grouped source aggregates every row its `where` admits, on every statement, including the count behind a page, not
just the 20 rows you display. The measured costs differ by vendor:

- **PostgreSQL** drops an unreferenced `LEFT` join from the count by itself, but only while no filter reads the
  source. A filter on a joined column runs the source (0.38 ms unfiltered against 3.2 ms filtered in the spike).
- **MySQL** runs the source in full in every count, filtered or not: about 13 times the plain count in the spike.
- **H2** runs the source in full in every count too, and Hibernate inlines the joined CTE as a derived table, which H2
  evaluates once per outer row. Without an index on the join key a grouped source over 365 outer rows took about
  265 ms, and a window source tens of seconds. H2 is a test database: give the join key an index and keep the outer
  rows to a few dozen.

Join a source for a page, export or report that **filters or sorts on the aggregate**: in the spike the joined source
won every such page, export and count on all three vendors. Keep [`Correlated.*`](subqueries-expressions.md#correlated-columns)
for **unfiltered** pages of a few rows over an indexed child, where its probe per row is cheap. Either way, narrow the
source's own `where` to the rows the report needs, so the aggregate does less work.

Attaching an entity join adds a smaller cost than computing the source. RFC 0021's spike measured an attached,
unreferenced LEFT entity join against no entity join on Hibernate 6.6 and 7.4:

| Statement | PostgreSQL | MySQL | H2 |
|---|---|---|---|
| Page of 20, one hierarchy | 2.83 vs 2.84 ms | 8.07 vs 8.01 ms | 6.17 vs 5.51 ms |
| Count, one hierarchy | 2.53 vs 2.42 ms | 12.5 vs 11.8 ms | 6.09 vs 5.42 ms |
| Export, 4,734 rows | 4.77 vs 4.90 ms | 14.4 vs 13.9 ms | 6.27 vs 5.59 ms |
| Count, all hierarchies | 0.90 vs 0.88 ms | 52.1 vs 49.6 ms | 16.5 vs 14.3 ms |
| Export, 14,207 rows | 13.0 vs 14.1 ms | 61.5 vs 59.9 ms | 17.9 vs 15.8 ms |

These are workload measurements, not latency guarantees. PostgreSQL removes the unreferenced LEFT entity join
from count, page and export, even with its ON filter. MySQL and H2 do one primary-key index lookup per outer row:
MySQL added about 3–5% to count/export and H2 about 12–15% in this fixture. Referencing entity columns requires
reading the entity. An INNER entity join affects which rows qualify even when no selected column reads it;
paging content and totals both retain that restriction.

## Reading a source

A separate outer model is optional. Every non-branch source QModel generates the `@Incubating` `Rows` vocabulary
and `query(SourceQuery<S>)` factory, returning rows of source model `S` itself. Shapes generate both; branches are
read through their shape. A source over a source takes its completed **outer** definition in `query(...)`.

`Rows` supplies a column for every field, including windows and aggregates, plus `DEFAULT`, `ALL`, `MAPPER` and,
when keyed, `KEY`. The factory sets that key automatically. Its columns read the completed CTE: a predicate on
`Rows.RANK` filters the window result, rather than the rows entering the window.

<!-- from SourceReadExecutionTest#ac_src_34_a_window_filter_and_keyset_cursor_read_the_completed_source -->

```java
SourceQuery<OrderSource> inner = QOrderSource.source().where(f -> f.lte(QOrderSource.ID, 8L)).build();
SourceQuery<RankedOrderSource> source = QRankedOrderSource.source(inner).build();
var query = QRankedOrderSource.query(source)
        .select(QRankedOrderSource.Rows.DEFAULT)
        .where(f -> f.eq(QRankedOrderSource.Rows.RANK, 1L))
        .orderBy(QRankedOrderSource.Rows.ID.asc()).keyset().build();
```

=== "Plain JPA"

    ```java
    KeysetSlice<RankedOrderSource> first = executor.page(query, KeysetSpec.first(2));
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<RankedOrderSource> executor =
            ModelQueryExecutor.create(em, RankedOrderSource.class, config);
    KeysetSlice<RankedOrderSource> first = executor.page(query, KeysetSpec.first(2));
    ```

Keep the two vocabularies separate. `QRankedOrderSource`'s own constants define the source body;
`QRankedOrderSource.Rows` constants read its result. Mixing them is `MQ1905` before any statement:

- A source's own constant in a direct read's selection, WHERE or order is refused at `ModelQuery.Builder.build()`.
- A `Rows` column in the source body's fields, WHERE, group keys, HAVING or order is refused at
  `SourceQuery.Builder.build()`.

The error names the matching constant to use. For example, select `QRankedOrderSource.Rows.DEFAULT` in the direct
read, and use `QRankedOrderSource.ID` in the source body's filters.

Key-based features follow the source's uniqueness, just as with a separate outer model. A non-unique source
refuses keyset and primary-key-first paging with `MQ1901`, and key-identifying execution with `MQ2212`.
Grouping or a checked tree can establish uniqueness. Key predicates stay above the full source as described
[above](#sources-over-sources), and direct reads have no fetch plans because a source cannot declare `@Child`.

## What a source needs to run: the provider and the vendor

The engine reaches the provider only through `SourceRenderer` (`@Incubating`), the jakarta-typed hook that registers the
CTE and returns the root the outer statement reads:

- `ProviderSupport#sources()` (`@Incubating`) returns the factory's `SourceRenderer`, empty by default. Empty, or a
  factory whose provider has no `ProviderSupport` at all, is `MQ4014` before any statement.
  A renderer that returns no duplicate-key check for `tree(...)` is `MQ4014` too.
- `ProviderSupport#entityJoins()` (`@Incubating`) supplies the root-anchored entity-join renderer. The Hibernate
  provider serves it on both 6.6 and 7.4. An absent hook, or a provider unable to attach the join to the root, is
  `MQ4014` before any statement, after the vendor and source-renderer checks.
- `VendorProfile#sources(SourceFeature)` (`@Incubating`) answers per feature — `CTE`, `RECURSIVE`, `WINDOW`, `UNION`,
  `INTERSECT` and `EXCEPT` — and is `false` by default, so a profile written before a constant was added fails safe. A
  `false` is `MQ4015`, naming the feature and the vendor, before any statement.
- `VendorProfile#maxRecursionDepth()` (`@Incubating`) is the vendor's recursion ceiling. A `maxDepth` whose
  `maxDepth + 2` (the extra level and the final, empty iteration) exceeds it is `MQ4015` too: MySQL's default of 1000
  serves `maxDepth` 998 and refuses 999, while H2 and PostgreSQL accept any depth up to `Integer.MAX_VALUE`.
- A row lock over a source is `MQ1903`: the rows come from a CTE, which a locking clause cannot follow.

`model-query-hibernate` serves Hibernate on both lines, and the Tier-1 profiles answer for all three databases. This is
why Tier-1 MySQL's floor is 8.0.31 rather than 8.0: it is the first version with `intersect` and `except`, so no
Tier-1 answer depends on a server version. The [portability check](portability.md) reports `MQ4014` and `MQ4015` per
declared target with no database attached, because both codes are raised before any statement.

## Known limits

- **Windows see other transactions' writes.** A window value is computed again on every statement, so a write to another
  row between two pages can change a row's `rank`, and a keyset walk ordered by a window value can then skip or repeat a
  row without `MQ2205`. Run the walk in a `REPEATABLE READ` transaction or stronger: one snapshot is the guard.
- **Tree order values can move a subtree.** A concurrent change to a node's parent can change `@Depth` for every node
  below it. A keyset or offset walk ordered by such derived values can skip or repeat a subtree while every statement
  still sees a valid tree; `MQ2205` catches a repeat only on the very next page. Use `REPEATABLE READ` or a snapshot
  around a walk (an export, or keyset or offset paging) ordered by derived values. Ordering by the node key alone
  is unaffected by moves within the walk.
  If a write instead makes a node reachable twice, a later page raises `MQ2703` when it reads a check row; pages
  already delivered stay delivered. Each statement checks its own snapshot, with no check state between pages.
- **MySQL's export cost is quadratic.** Each export page recomputes the whole CTE, and MySQL materializes CTEs, so an
  export over a source costs work quadratic in the source's size. Keyset paging with a small page size has the same
  shape; prefer a narrow source for large exports.
- **Tree checks scan the whole walk per statement.** An export of `N` nodes in pages of `P` runs about `N/P`
  statements, each walking and checking `N` nodes: quadratic work at a fixed page size. The measured vendor costs
  below include the duplicate-key check.
- **The emulated `cycle` cost on H2 and MySQL.** PostgreSQL renders `cycle` natively. On H2 and MySQL Hibernate emulates
  it with a path that grows by one key per level and is searched on every row, so the work grows quadratically with
  depth: in the spike a 999-level chain took about 14 s on H2 against about 85 ms on PostgreSQL, even with the parent
  column indexed. MySQL's path is a fixed-width column, so long keys fill it at the default ceiling.
- **The empty-result race under READ COMMITTED.** Depth, cycle and duplicate checks read from the statement's own
  first row, so an empty result carries nothing to read them from: under READ COMMITTED (PostgreSQL's default) a
  statement can return an empty page while the data has gone too deep, formed a cycle or reached a node twice in the
  meantime. A REPEATABLE READ transaction around the call is the full guarantee.

The tree-check spike measured these costs (RFC 0014 §6):

- **PostgreSQL** computes the recursive CTE once per statement; the duplicate check adds a scan, not another walk.
  A 100k-node content statement took 253 ms with the check against 209 ms without it.
- **MySQL 8.0.31** materializes the recursive CTE once per statement and shares it with the checks: 376 ms against
  315 ms at 100k nodes. A 10k-node keyset export in pages of 100 took 2.4 s against 2.1 s.
- **H2's check cost is a test-database limit.** It re-walks for each check and each outer row: work grows as rows ×
  tree size × checks. A 1k-node content read took 2 ms with no checks, 1.1 s with depth and cycle checks, and 1.8 s
  with the duplicate check too; doubling the tree quadrupled the time. A count has one outer row and avoids that
  multiplier. Keep trees in H2 tests small.

See [Diagnostics](diagnostics.md) for every code, and [Portability](portability.md) for what is refused per target.
