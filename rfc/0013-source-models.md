# RFC 0013 — Source models: CTEs, window fields, set operations and recursive reads

- **Status:** accepted 2026-10-15 as D-143 (revised after two architect reviews; the acceptance spike passed, see
  [0013-spike-results.md](0013-spike-results.md)). M25, 0.10.0; the freeze is renumbered M26, later M29 (D-145), then M30 (D-148).
- **Affects:**
  - **New spec file:** `api/17-query-sources.md`, with R-SRC-01 to R-SRC-12 and AC-SRC-01 to AC-SRC-16.
  - **`SPEC.md`:** `SRC 17` in §1 Areas; `SourceQuery` and `WindowField` in INV-9's list.
  - **`api/10`:** a source root kind for `TableField`.
  - **`processor/30`:** new R-PROC-35 to R-PROC-39: `@SourceModel`, `@Window`, `@Depth`, `@Discriminator`, and a
    source model as `@QueryModel.root`.
  - **`processor/31`:** new R-GEN-37 and R-GEN-38: the `source()` builder and `WindowField` constants.
  - **`processor/32`:** new `MQ3030` to `MQ3039`, with AC-PROC-30 to AC-PROC-39.
  - **`vendor/40`:** new R-VND-21 and R-VND-22: `VendorProfile#sources(SourceFeature)` and `maxRecursionDepth()`.
  - **`vendor/41`:** the Tier-1 MySQL floor rises from 8.0 to 8.0.31, plus the Tier-1 values.
  - **`jpa` SPI:** `ProviderSupport#sources()` and `SourceSupport`.
  - **`reference/90`:** the `MQ19xx` sub-range in §2; `MQ1901` to `MQ1906`, `MQ2212`, `MQ2701`, `MQ2702`, `MQ4014`,
    `MQ4015`.
  - **`reference/92`:** D-143, Q-21 and Q-22.
  - **`delivery/61`:** the incubating list.
  - **`docs/site`:** a "Source models" page; the comparison page's Reach row.
- **Discussion:** to open

## Summary

A query can read from a **source** instead of an entity. A source is a named subquery, rendered as a CTE.

- **The source model.** The source's shape is declared once as a **source model**: an ordinary model over entities
  that may also hold window-function fields.
- **The outer model.** A query model whose `root` is a source model reads the source's fields as columns. The
  processor checks those columns against the source model at compile time, the same way it checks a model against an
  entity today.
- **Kinds of source.** A source can be:
  - a filtered read;
  - a set operation over several reads (`union`, `unionAll`, `intersect`, `except`);
  - a recursive walk from seed rows along a key.
- **What stays the same.** The outer query is an ordinary Model Query query. Filters, ordering and offset paging always
  work on it. Every key-based feature works on it when the source's key is unique per row (§6): keyset paging,
  primary-key-first, `byKeys`, fetch plans and `export`.

```java
// the source's shape: each order with its rank among its customer's orders
@SourceModel(root = OrderEntity.class)
@FilterColumn(name = "status", path = "status")
public record RankedOrder(@PrimaryKey Long id, @Column(attribute = "customer.id") Long customerId,
                          Instant createdAt, BigDecimal total,
                          @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "customer.id",
                                  orderBy = "createdAt desc") Long rank) {}

// a query model over that source: its root is the source model, not an entity
@QueryModel(root = RankedOrder.class)
public record LatestOrder(@PrimaryKey Long id, Long customerId, Instant createdAt, BigDecimal total, Long rank) {}

// the latest three orders of each customer, paged by keyset like any query
var latest = QLatestOrder.query(QRankedOrder.source().where(f -> f.eq(QRankedOrder.STATUS, status)).build())
        .select(QLatestOrder.DEFAULT)
        .where(f -> f.le(QLatestOrder.RANK, Optional.of(3L)))
        .orderBy(QLatestOrder.CUSTOMER_ID.asc(), QLatestOrder.RANK.asc())
        .keyset()
        .build();
```

```sql
-- PostgreSQL, as Hibernate renders it; the source key "o.id" is appended to the window's order (R-SRC-04)
with ranked_order (id, customer_id, created_at, total, rank) as (
    select o.id, o.customer_id, o.created_at, o.total,
           row_number() over (partition by o.customer_id order by o.created_at desc, o.id)
    from orders o where o.status = ?)
select r.id, r.customer_id, r.created_at, r.total, r.rank from ranked_order r
where r.rank <= ? order by r.customer_id, r.rank, r.id fetch first ? rows only
```

## Motivation

The comparison page sends four kinds of reads to plain SQL or Blaze-Persistence: CTEs, window functions, set
operations and recursive queries. They come up in ordinary list screens:

- **Top N per group.** "The latest three orders of each customer" needs a window function in a subquery, and a filter
  on it outside. Criteria has neither.
- **Filtering or paging over aggregates.** A grouped query can't be paged by keyset (`MQ1402`, R-AGG-10). A `having`
  that compares two aggregates needs `Agg.of`.
- **One list from several sources.** Examples are an activity feed of orders, refunds and notes, or a "your items" list
  made of owned rows `union` shared rows. Today that means two queries merged and re-paged in Java, which breaks keyset
  paging and export.
- **Trees.** A category tree, an org chart or a bill of materials. A fetch plan loads one level per round, so a deep
  tree costs one round per level, and an unknown depth needs a loop in the caller.

Native SQL covers all four, but it loses three things: the model's compile-time column checks, the vendor checks and
the paging engine. `QueryCustomizer` can't help either, because none of these four is standard Criteria.

Blaze-Persistence solves the typing problem by declaring each CTE's shape as a `@CTE` entity class, so the outer query
reads typed attributes. This RFC keeps the idea and drops the mechanism:

- **The shape is a model, not a pseudo-entity** added to the persistence unit, so schema tools never see it.
- **Rendering uses Hibernate's own Criteria support** for CTEs, set operations and windows, so no SQL is rewritten.
  The 6.6 and 7.4 lines both have it (checked with `javap` against 6.6.58 and 7.4.11).

## Design

### 1. Source models (R-PROC-35, R-SRC-01)

A source model is declared with `@SourceModel(root = Entity.class)`.

- **Field annotations.** It takes the same field and type annotations as `@QueryModel`: `@Column`, a to-one `@Join`,
  `@Computed`, `@Aggregate`, `@GroupBy` and `@FilterColumn`. It adds `@Window` (§3) and `@Depth` (§5).
- **Validation.** The processor validates a source model against its root entity with the same checks and codes as a
  query model.
- **No children.** A source is one statement, so `@Child` is `MQ3030`.
- **Generated QModel.** The QModel has a `source()` builder in place of `query()` (R-GEN-37).
  - The builder has `where` and `having`, but no `select`: a source always carries every field of its model, which keeps
    the outer model's columns checkable at compile time.
  - `build()` returns an immutable `SourceQuery<S>` (INV-9). `S` is the source's shape: the model itself, or the model
    named by `shape` (§4).
  - Set operations are methods of the immutable `SourceQuery` (§4), not of the builder.

### 2. The source key is unique per row (R-SRC-02, R-SRC-03)

Every key-based feature of the outer query depends on the source key being unique (INV-4). So the key is unique by
construction, or the source is treated as non-unique (§6).

- **Compile time (`MQ3031`).** A source model's `@PrimaryKey` must be one of the following, plus any `@Discriminator`
  fields (§4):
  - the root entity's id, for an ungrouped source;
  - the full `@GroupBy` set, for a grouped source. Each group-key column must be non-nullable in its mapping: an id, a
    primitive, `nullable = false` or `optional = false`, reached through no LEFT join. A NULL group key would otherwise
    surface as `MQ2201` or `MQ2202` mid-export.

  A source model's fields may not read through a to-many join (`MQ3031`).
- **Run time (R-SRC-03).**
  - **Filters.** A `where` predicate inside a source whose path crosses a to-many join is `MQ1906`, raised at the
    source's `build()`. The message points to an explicit `Filters.exists(path, inner)`, whose correlation R-FLT-11 and
    R-FLT-12 already define.
    - The predicate is not rewritten silently. A rewrite would change its meaning: two conditions on one joined line
      would match different lines, `isNull` on a LEFT path would stop matching rows with no line, and `not` would
      become `not exists`.
    - `select distinct` is not a fix either, because window functions run before `distinct`.
  - **Outer key.** The outer model's `@PrimaryKey` must be exactly the source's key fields (`MQ3036`), so the engine's
    tie-breakers and cursors use the unique key.

### 3. Window fields (R-PROC-36, R-SRC-04)

```java
@Window(function = WindowFunction.SUM, argument = "total", partitionBy = "customer.id", orderBy = "createdAt",
        frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal runningTotal
```

- **Functions.** `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG`, `LEAD`, `FIRST_VALUE` and `LAST_VALUE`, and `SUM`, `AVG`,
  `MIN`, `MAX` and `COUNT` used over a window.
- **Paths and types.** `partitionBy`, `orderBy` and `argument` are attribute paths, checked like `@Column`'s (P-2). The
  field type is checked against the function's result type (INV-3). Either failure is `MQ3032`.
- **In a grouped source,** a window's `argument`, `partitionBy` and `orderBy` must each be a `@GroupBy` column or an
  `@Aggregate` field of the model (`MQ3032`).
- **Only in source models** (`MQ3033`). SQL forbids a window function in `where`, and a keyset predicate is a `where`.
  So a filter, an order or a keyset on a window value happens one level up, where it is a plain column.
- **Not filterable inside the source.** A window field's generated constant is a `WindowField<S, T>`, not a
  `ColumnField` (R-GEN-38), so the source's own `where`, `having` and `orderBy` don't accept it. The outer model reads
  it as an ordinary `ColumnField`.
- **Deterministic by construction (R-SRC-04).** Every run of the source must give each row the same value. This
  matters because the count, each export page and both primary-key-first steps re-run the CTE.
  - **Order-sensitive functions** are `ROW_NUMBER`, `LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE`, and any aggregate with
    a `ROWS` frame. For these, the engine appends the source key, ascending, as the window's last order terms.
  - They must also declare an `orderBy` (`MQ3032`).
  - `RANK`, `DENSE_RANK` and aggregates over a `RANGE` frame or no `orderBy` are deterministic as written, and keep their
    order unchanged: appending the key would turn `RANK` into `ROW_NUMBER`.
- **Explicit frames (`MQ3032`).**
  - `FIRST_VALUE`, `LAST_VALUE` and aggregates with an `orderBy` must declare `frame`. With the SQL default frame,
    `LAST_VALUE` returns the current row (P-3).
  - `FIRST_VALUE` and `LAST_VALUE` take only `ROWS` frames. Appending the key under a `RANGE` frame would make every row
    its own peer group. With a `RANGE` offset it would also be invalid SQL, because PostgreSQL then requires exactly one
    `order by` column.
  - `LAG` and `LEAD` take no frame. SQL ignores a frame on them, and H2 rejects one as a syntax error, so a declared
    `frame` on them is `MQ3032` and `SourceSupport#window` never renders one.
  - Aggregates may take either frame kind. A `RANGE` frame leaves their order as written; a `ROWS` frame gets the key.
- **`WindowFrame` constants:**
  - `ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW`
  - `ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING`
  - `ROWS_CURRENT_ROW_TO_UNBOUNDED_FOLLOWING`
  - `RANGE_UNBOUNDED_PRECEDING_TO_CURRENT_ROW`
  - `RANGE_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING`

  Offset frames (`n preceding`) are left for later.
- **Known limit: windows see other writes.** A window value is computed again on every statement, so a write to
  another row between two pages can change a row's `rank`. A keyset walk ordered by a window value can then skip or
  repeat a row without `MQ2205`. The guard is a single snapshot: a REPEATABLE READ transaction around the walk.
- **Cost.** Each export page also recomputes the whole CTE. On MySQL, which materializes CTEs, an export costs
  quadratic work in the source's size. The source page of `docs/site` says so.
- **Cost of deep recursion without a native `cycle` clause.** PostgreSQL renders `cycle` natively. On H2 and MySQL,
  Hibernate emulates it with a path that grows by one key per level and is searched on every row, so the work grows
  quadratically with depth. In the spike, a 999-level chain took about 14 s on H2 (about 85 ms on PostgreSQL), even
  with the parent column indexed. MySQL's path is a `char(22000)` column, at about the key's length plus 3 characters
  per level; at the default ceiling, 19-digit keys exactly fill it. The source page of `docs/site` says so.

### 4. Set operations (R-PROC-38, R-SRC-05, R-SRC-06)

A branch of a set operation is a source model that names the shared shape. It maps the shape's fields onto its own
root.

```java
@SourceModel(root = OrderEntity.class)
public record FeedItem(@Discriminator("ORDER") @PrimaryKey String kind, @PrimaryKey Long id,
                       @Column(attribute = "customer.id") Long ownerId, Instant at, String title) {}

@SourceModel(root = RefundEntity.class, shape = FeedItem.class)
public record RefundFeedItem(@Discriminator("REFUND") @PrimaryKey String kind, @PrimaryKey Long id,
                             @Column(attribute = "order.customer.id") Long ownerId,
                             @Column(attribute = "refundedAt") Instant at, @Column(attribute = "reason") String title) {}

SourceQuery<FeedItem> feed = QFeedItem.source().where(f -> f.eq(QFeedItem.OWNER_ID, me)).build()
        .unionAll(QRefundFeedItem.source().where(f -> f.eq(QRefundFeedItem.OWNER_ID, me)).build());
```

- **Branch types.** `QRefundFeedItem.source()` builds a `SourceQuery<FeedItem>`, so `unionAll(SourceQuery<S>)` is a
  plain core method. No generated interface is needed, and a branch of another shape doesn't compile.
- **Field match (`MQ3034`).** The processor checks that a branch model's fields match its shape's fields:
  - by name, in declaration order;
  - by Java type;
  - by what the database stores: the same converter class, `EnumType` and temporal type. An `ORDINAL` enum and a
    `STRING` enum, or a converted column and a raw one, would otherwise compile and produce wrong rows;
  - by key: a branch's `@PrimaryKey` fields must be the shape's `@PrimaryKey` fields, by name.
- **The shape is a branch.** The shape model (`FeedItem` above) is an ordinary source model over its own root, and is
  the first branch whenever its own `source()` is used. Other branches name it through `shape`.
- **Branch rules (`MQ3034`).**
  - A branch model has no `@Window` fields. Windows over the combined rows are Q-22.
  - A recursive model (one with `@Depth`) can't be a branch or a shape. Combining one would need a nested `with`.
- **Operations.** The operations are `union`, `unionAll`, `intersect` and `except`. They render through Hibernate's
  `(boolean, CriteriaQuery…)` overloads, which return `JpaCriteriaQuery` on both lines and so can be a CTE body.
  - They compare whole rows: a row belongs to an `intersect` or `except` result only if every column matches. A
    non-key column that differs changes membership.
  - Operations nest in the order they are called: `a.union(b).except(c)` is `(a union b) except c`.
- **Key uniqueness (R-SRC-05).** A result is unique when:
  - `except`: its left branch is unique. The result is a subset of the left rows.
  - `intersect`: either branch is unique. A row appears at most as many times as in the branch where it is rarest.
  - `union` or `unionAll`: every branch is unique, and the branches are disjoint by key, in one of two ways (R-SRC-06):
    - **By discriminator.** The shape's composite `@PrimaryKey` includes a `@Discriminator` field, and every branch
      fixes it to a different constant.
    - **By one branch model.** A distinct `union` whose branches all read one ungrouped branch model with no `@Window`
      field and one discriminator value: each root row yields the same columns in every branch, so a row two branches
      reach collapses to one. A grouped or windowed model computes its values over each branch's own rows, so it is
      non-unique there (§6). This covers "owned `union` shared".
  - Anything else is non-unique (§6).
- **Discriminator values (R-SRC-06).**
  - Each branch renders its discriminator as a constant (`Expr.constant`).
  - In a `union` or `unionAll`, two branches with the same discriminator value are `MQ1904`, raised when the operation
    is built. Without the error, the union would quietly lose key uniqueness.
  - Values are computed per node (a leaf {v}; `union`, `unionAll` L u R; `intersect` L n R; `except` L) for the clash
    check and for uniqueness; the check is skipped for a distinct `union` whose leaves all read one model. Combining
    sources whose fields differ in order, property, type or converter is `MQ1904` (D-146).
  - `intersect` and `except` don't check discriminator values. Uniqueness there follows the rules above.
  - Every branch of a discriminated shape must declare a `@Discriminator` field, and every `@Discriminator` must be part
    of the key (`MQ3034`).

### 5. Recursive sources (R-PROC-37, R-SRC-07, R-SRC-08)

A source model is recursive when it declares a `@Depth` field. Its `source()` builder then requires the step.

```java
@SourceModel(root = CategoryEntity.class)
public record CategoryNode(@PrimaryKey Long id, @Column(attribute = "parent.id") Long parentId, String name,
                           @Depth Integer level) {}

SourceQuery<CategoryNode> tree = QCategoryNode.source()
        .where(f -> f.eq(QCategoryNode.ID, rootId))                                     // the seed rows
        .recursive(step -> step.childKey(QCategoryNode.PARENT_ID).parentKey(QCategoryNode.ID), 20)
        .build();
```

- **The step.** `childKey(ColumnField<S, T, ?>)` returns a stage whose `parentKey` takes a `ColumnField<S, T, ?>` of
  the same `T`.
  - Named steps make a swapped pair read wrong at the call site.
  - The shared `T` makes keys of different types fail to compile.
  - The depth limit is a required argument, so it can't be left out.
  - A value below 1, or above `Integer.MAX_VALUE - 2` (whose ceiling check `maxDepth + 2` would overflow), is
    `MQ1902`.
- **Levels.** Seed rows are level 0, and each step adds 1. `maxDepth = 20` returns levels 0 to 20.
- **Shape rules (`MQ3037`).**
  - A recursive model has no `@Window`, `@Aggregate` or `@GroupBy`, because PostgreSQL forbids them in the recursive
    term.
  - A `@Depth` field is an `Integer`, and there is at most one.
  - Window and aggregate values over a recursive result are Q-22.
- **Depth and cycles are loud (R-SRC-07).**
  - **Rendering.** The source renders as `with recursive`. It recurses to level `maxDepth + 1`, and Hibernate's `cycle`
    clause sets a mark on the row that closes a cycle. SQL still emits that row, as a repeated node.
  - **What the outer query sees.** The engine filters on `level <= maxDepth and not mark`, so neither the extra level
    nor a marked row reaches the result.
  - **The check runs in the same statement.** The content and count statements each carry two scalar sub-selects over
    the same CTE: `min(key)` over the rows past `maxDepth` and `min(key)` over the marked rows, so each also names the
    first offending key. The engine reads them from the first row. The sub-selects read the CTE through Hibernate's
    `JpaSubQuery`, so they are built behind `SourceSupport#recursive` and return with its root (D-144).
    - A level past `maxDepth` is `MQ2701`. A set mark is `MQ2702`. Each names the model and the first offending key.
    - So every non-empty result is checked in the snapshot it was read from. Neither a too-deep tree nor a cyclic
      graph comes back as a partial or doubled result (INV-5).
  - **What is left.** An empty result carries no row to read the sub-selects from. Under READ COMMITTED (PostgreSQL's
    default), a statement can return an empty page while the data has gone too deep or formed a cycle in the meantime.
    REPEATABLE READ around the call is the full guarantee, and the docs say so.
  - **Export.** Export is refused on recursive sources (§6), so a per-statement check is consistent with `engine/21`.
    Q-21 has to revisit this, because it would make trees exportable.
- **Vendor ceiling.** `maxDepth + 2 > VendorProfile#maxRecursionDepth()` is `MQ4015`. The check counts the extra level
  recursion needs, plus the final, empty iteration, which MySQL also counts against `cte_max_recursion_depth`. The
  spike measured it: at MySQL's default of 1000, `maxDepth = 998` runs and 999 fails with error 3636. So `maxDepth`
  999 and above is refused here instead.
- **Key uniqueness (R-SRC-08).** In a tree each node is reached once. In a graph with two paths to a node it isn't, and
  the processor can't tell which one the data is. So a recursive source is non-unique (§6).
- **Next to fetch plans.** A fetch plan's `@Child` stays the way to load a known, shallow level. A recursive source is
  for a whole subtree in one statement.

### 6. Non-unique sources (R-SRC-09)

A source is **non-unique** when it is either of the following:
- a set operation that R-SRC-05 doesn't make unique;
- a recursive source.

On a query over a non-unique source, every feature that identifies a row by its key is refused before any statement:

| Feature | Where | Code |
|---|---|---|
| `keyset()`, `primaryKeyFirst(...)` | the outer `build()` | `MQ1901` |
| `export`, `byKeys`, `one(q, key)` (`findByKey`), a fetch plan with a child, join plan or enricher | the executor call | `MQ2212` |

`list`, `count`, `first`, `stream` and offset `page` work.

**Offset paging.** On a non-unique source, the outer key can't break ties: two rows with the same key and the same
order values could swap between the statements of page 1 and page 2, and one row would show twice while the other
never shows. So offset `page` on a non-unique source appends every selected outer column, ascending, as tie-breakers
after the definition's order and the key.
- A selected column that can't be ordered (a LOB, a JSON type) is `MQ2212`.
- Rows that compare equal under the vendor's ordering can't be told apart, so no row is visibly lost (known limits: `api/17` R-SRC-09).

### 7. The outer query (R-PROC-39, R-SRC-10, R-SRC-11)

- **Root kind (R-PROC-39).**
  - `@QueryModel(root = X.class)` reads its kind from the annotation on `X`: an entity root when `X` is `@Entity`, a
    source root when `X` is `@SourceModel`.
  - A root that is neither, or both, is `MQ3038`.
  - `@BindQueryModel.root` (RFC 0012) may name a source model annotated `@SourceModel`. This RFC adds no
    `@BindSourceModel`, so a bound source never has to be resolved before a binding's root can be read (R-PROC-31).
- **Column checks.** The processor checks every outer field against the source model's fields: the name, then the type
  (INV-3).
  - Columns are read by field name, so a `@Column` path in the outer model is a single segment.
  - A `@Join` on a model rooted at a source is `MQ3035`. A source root has no associations; entity joins from a sourced
    model are left for a later RFC.
- **Builder.** The generated `query(SourceQuery<S>)` takes the source, so a query without one doesn't compile.
- **Children over sources.**
  - A `@Child` whose child model is rooted at a source is `MQ3039`, because the fetch plan has no `SourceQuery` to pass
    it.
  - On a parent rooted at a source, the `@Child`'s parent-side key names a source field.
  - A composite parent key is `MQ3404` today, so a discriminated feed can't have children.
- **Core (R-SRC-10).**
  - `TableField` gains a source root kind. Generated QModels create it; a hand-written `ColumnField` over a source root
    is `MQ1905`, since there is no metamodel for INV-3 to check it against.
  - A grouped outer model over a source follows `api/13` as an entity-rooted one does: a grouped keyset is still
    `MQ1402`. An ungrouped outer model over a grouped source is the way to page groups by keyset.
- **What works unchanged.** Filters, ordering, select sets, `afterMap` and `count` work on every source. On a unique
  source, so do every paging strategy, `export`, `byKeys`, `one(q, key)`, fetch plans (`@Child` keyed by the outer key)
  and enrichers. §6 lists what a non-unique source refuses.
- **Not supported.**
  - A row lock on a query that reads a source is `MQ1903`; Q-16 stays open.
  - Writes from a source.
  - `QueryCustomizer` receives the outer query only.

### 8. Provider and vendor (R-SRC-12, R-VND-21, R-VND-22)

**Provider (INV-7).** `model-query-jpa` builds every CTE body itself as a standard `CriteriaQuery<Tuple>` aliased by
field name: filters, columns, `exists` predicates and the discriminator constant. It reaches the provider only through
narrow hooks on standard JPA types, the same way `InsertSupport` and `LockSupport` work. No `org.hibernate` type leaks
into `jpa`.

```java
public interface SourceSupport {                       // ProviderSupport#sources(): Optional<SourceSupport>
    CriteriaQuery<Tuple> combine(CriteriaBuilder cb, SetOperation op, List<CriteriaQuery<Tuple>> branches);
    Expression<?> window(CriteriaBuilder cb, WindowSpec spec);         // functions, partition, order, frame
    Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body);
    RecursiveCte recursive(CriteriaQuery<?> outer, RecursiveSpec spec); // the CTE root and the two checks
}
```

`RecursiveCte(root, depthKey, cycleKey)` is what the recursive hook returns: the CTE's root, and the two check
expressions it built over the same CTE with `JpaSubQuery.from(cte)`, which `core` cannot name (D-144).

- **Every statement re-registers the CTE (R-SRC-12).** `jpa` calls `cte` or `recursive` on each `CriteriaQuery` it
  builds over a source: the content query, the count, both primary-key-first steps, each export page, `byKeys` and the
  parent reads of a fetch plan. A recursive source refuses all but the content query and the count (R-SRC-09), and its
  two checks are selected by those two statements rather than by a statement of their own (§5, D-144).
- **Hibernate implementation.** `model-query-hibernate` implements the hooks for both lines.
  - `LAG`, `LEAD`, `MIN` and `MAX` have no typed `JpaWindow` methods, so they render through
    `windowFunction(name, …)`.
  - The discriminator constant renders with `cb.literal`, inline. PostgreSQL accepts it in a union branch, and a
    bound value would also work, because Hibernate casts it there.
  - Set operations use the `(boolean, CriteriaQuery, CriteriaQuery…)` overloads, identical on both lines.
  - The recursive CTE is `withRecursiveUnionAll(seed, term)` plus the `cycle` clause, and the two checks are
    sub-queries over it; the hook makes the same calls on 6.6 and 7.4, so it hides no line difference. The rendered SQL
    still differs the way Hibernate 7 renders every `WITH` clause — the CTE's own alias is `var_1` there and `cte0` on
    6.6, and the cycle mark's literal is a real boolean — so the recursive snapshots carry `hibernate7/` variants, as
    the write snapshots do. No `search` clause is rendered: the outer query owns the order, and the CTE is read by it
    alone.
  - Another provider, or no `ProviderSupport`, is `MQ4014` before any statement.

**Vendor (INV-6).**
- `default boolean sources(SourceFeature feature)` returns `false`. `SourceFeature` lives in `jpa.spi`, next to
  `VendorProfile`. Its Javadoc says an implementation returns `true`
  only for the constants it knows, never for a constant added later.
- The constants are `CTE`, `RECURSIVE`, `WINDOW`, `UNION`, `INTERSECT` and `EXCEPT`.
- `default int maxRecursionDepth()` returns `Integer.MAX_VALUE`.
- A missing feature, or a depth above the ceiling, is `MQ4015`.

**Tier-1 values.** This RFC raises vendor/41's MySQL floor from 8.0 to **8.0.31**, the first version with `intersect`
and `except`, so no Tier-1 answer depends on a server version. Current `mysql:8.0` images are newer.

| Feature | H2 2.2+ | PostgreSQL 14+ | MySQL 8.0.31+, 8.4 |
|---|---|---|---|
| CTE, recursive, window, union, intersect, except | yes | yes | yes |
| `maxRecursionDepth()` | `Integer.MAX_VALUE` | `Integer.MAX_VALUE` | 1000 (`cte_max_recursion_depth`'s default) |

The portability check (RFC 0011) finds `MQ4014` and `MQ4015` for each declared target without a database, because both
are raised before any statement.

### New ids

| Id | Where | What |
|---|---|---|
| R-SRC-01..12 | `api/17` | Source models, key uniqueness, windows, set operations, discriminators, recursion, non-unique sources, the outer query, CTE registration |
| R-PROC-35..39 | `processor/30` | `@SourceModel`, `@Window`, `@Depth`, `@Discriminator` and `shape`, the root kind |
| R-GEN-37, R-GEN-38 | `processor/31` | The `source()` builder and `query(SourceQuery<S>)`; `WindowField` constants |
| R-VND-21, R-VND-22 | `vendor/40` | `sources(SourceFeature)`; `maxRecursionDepth()` |
| MQ1901 | `api/17` | `keyset()` or `primaryKeyFirst(...)` over a non-unique source |
| MQ1902 | `api/17` | A recursive source's `maxDepth` below 1, or above `Integer.MAX_VALUE - 2` |
| MQ1903 | `api/17` | A row lock on a query that reads a source |
| MQ1904 | `api/17` | Two branches of one `union` or `unionAll` with the same discriminator value |
| MQ1905 | `api/17` | A hand-written `ColumnField` over a source root |
| MQ1906 | `api/17` | A `where` predicate inside a source whose path crosses a to-many join |
| MQ2212 | `engine/21` | `export`, `byKeys`, `one(q, key)` or a keyed fetch plan over a non-unique source, or an offset `page` that would need an unorderable column as a tie-breaker |
| MQ2701 | `api/17` | A recursive source has a row deeper than `maxDepth` |
| MQ2702 | `api/17` | A recursive source meets a cycle |
| MQ3030 | `processor/32` | `@Child` on a source model |
| MQ3031 | `processor/32` | A source model's `@PrimaryKey` is not the root id or the full group set (plus discriminators), a group key is nullable, or a field reads through a to-many join |
| MQ3032 | `processor/32` | A `@Window` with an unknown path, a mismatched type, an order-sensitive function without `orderBy`, a missing `frame`, `FIRST_VALUE` or `LAST_VALUE` with a `RANGE` frame, a frame on `LAG` or `LEAD`, or, in a grouped source, a window term that is neither a group key nor an aggregate |
| MQ3033 | `processor/32` | `@Window` outside a source model |
| MQ3034 | `processor/32` | A branch whose fields or key fields differ from its shape (name, type, converter, enum or temporal type), a branch with `@Window`, a recursive branch or shape, or a misplaced or missing `@Discriminator` |
| MQ3035 | `processor/32` | `@Join` on a model rooted at a source |
| MQ3036 | `processor/32` | An outer model's `@PrimaryKey` is not exactly its source's key |
| MQ3037 | `processor/32` | A recursive source model with `@Window`, `@Aggregate` or `@GroupBy`, or a `@Depth` that is not one `Integer` |
| MQ3038 | `processor/32` | A `root` that is neither `@Entity` nor `@SourceModel`, or both |
| MQ3039 | `processor/32` | A `@Child` whose child model is rooted at a source |
| MQ4014 | `reference/90` | The factory's provider has no `SourceSupport` |
| MQ4015 | `reference/90` | The vendor lacks a source feature the query uses, or `maxDepth + 2` exceeds its recursion ceiling |
| AC-SRC-01..16 | `api/17` | TCK cases on every Tier-1 vendor, listed below |
| AC-PROC-30..39 | `processor/32` | A compile-testing case per `MQ303x` code, plus a positive case per annotation |

The AC-SRC-01..16 cases are:
- each feature;
- SQL snapshots;
- a keyset export over a grouped source;
- the top-N example paged by keyset and by primary-key-first over tied timestamps, with no row lost or repeated;
- `MQ1906` on a to-many filter inside a source, and the explicit `exists` form returning the same rows as the SQL
  written by hand;
- a discriminated feed exported by keyset;
- an "owned `union` shared" list from one branch model, paged by keyset;
- offset pages over a non-unique `unionAll` with duplicate keys and tied order values, losing no row;
- `MQ2701` and `MQ2702` on a deep tree and a cyclic graph, with no marked row in the result.

**D-143.**
- Sources are CTEs built by `jpa` from standard Criteria bodies through narrow provider hooks.
- A source key is the root id or the group set, plus any discriminators. It is unique per row by construction, or the
  source is refused for every key-based feature.
- Offset paging over a non-unique source breaks ties on every selected column.
- A to-many filter inside a source must be an explicit `exists`.
- Order-sensitive windows are made deterministic by appending the source key; `FIRST_VALUE` and `LAST_VALUE` take `ROWS` frames, and `LAG` and `LEAD` take none.
- Recursion fails loudly at its depth limit and on cycles, checked in the same statement that reads the rows. The race
  left on an empty result under READ COMMITTED is documented.

**Q-21** and **Q-22** are the two open questions listed under Unresolved questions.

## Compatibility

Everything here is additive and `@Incubating`, apart from one support-floor change.

- **`@QueryModel.root` may now name a source model.** This is source and binary compatible: it is still `Class<?>`,
  and an existing model names an entity.
- **Users without Hibernate.** Plain JPA users on another provider get `MQ4014`, as bulk inserts and row locks are
  refused today.
- **MySQL floor (8.0 → 8.0.31).** This removes Tier-1 support for MySQL 8.0.0 to 8.0.30, all past end of life with
  8.0's line. Existing features keep working there; the TCK just stops running on those versions. The CHANGELOG and
  vendor notes say so in the release that ships this.

## Alternatives

- **Copy Blaze-Persistence's `@CTE` entities.** They give typed attributes through the JPA metamodel. The cost is a
  pseudo-entity in every persistence unit, which schema tools must be told to skip, plus a dependency on whether the
  provider allows it. A model already gives the processor the shape.
- **A generated shape interface for set operations.** The processor can't add `implements` to a user's record, so every
  branch would have to write the interface by hand. `shape = …` names the shared model instead.
- **Derived tables (`from (select …)`) instead of CTEs.** Hibernate supports both. A CTE is the only form recursion can
  use, and it renders the same plan on every Tier-1 database. Rendering non-recursive sources as derived tables is a
  renderer choice inside `SourceSupport`, not an API one.
- **Set operations on `ModelQuery` directly** (`q1.union(q2)` as the query). Paging would then have to order and limit
  around the combined statement, with the same key problem. Making the union a source reuses the outer query's ordering
  and paging.
- **`@Window` fields in any query model.** A filter, an order or a keyset on one would be wrong SQL, or a refusal at
  every use. Restricting them to source models, with their own constant type, makes the illegal case not compile.
- **A version-aware vendor answer for MySQL 8.0.x before 8.0.31.** This needs a server version, which `VendorProfile`
  doesn't model and the offline portability check doesn't have. Raising the floor is simpler, and those versions are
  past end of life.
- **Silent truncation at `maxDepth`, or the SQL `CYCLE` clause's marked row passed through.** Both return partial or
  doubled results with no error, against INV-5. An opt-in `truncateAt(n)` could come later if someone needs it.
- **Strings (`QueryCustomizer` plus native SQL).** These lose INV-3 and the vendor checks, which is the gap this RFC
  closes.
- **Do nothing.** Keep sending these reads to native SQL or Blaze-Persistence. That's reasonable for an application
  with a few of them, but each one loses the model's checks and the paging engine.

## Unresolved questions

- **Spike: done.** Every hook the design relies on works on Hibernate 6.6.58 and 7.4.11 with H2, PostgreSQL 16 and
  MySQL 8.0.31, with identical results on both lines. [0013-spike-results.md](0013-spike-results.md) has the matrix
  and the SQL. Its findings are folded in above: no frame on `LAG`/`LEAD` (§3), the `maxDepth + 2` ceiling and the
  probe through `JpaSubQuery` (§5), the cost of emulated cycle detection (§3), and `cb.literal` for the
  discriminator (§8).
- **Q-21:** a duplicate-key probe that would let a recursive source over a tree count as unique, so fetch plans and
  keyset paging work on trees. The spike shows one statement can detect a repeated key on all three vendors:
  `(select count(*) from cte)` against `(select count(distinct key) from cte)`, counting the cycle-marked row too.
  It re-scans the CTE for each sub-select and checks only the statement it runs in, so it could make R-SRC-08 a
  runtime check, but it does not by itself make a tree safe for export or keyset paging across statements.
- **Q-22:**
  - window and aggregate fields over the combined rows of a set operation or a recursive source (a source over a
    source);
  - a source model's QModel also offering `query()`, so a source can be read without a trivial outer model.
- **Later, not in this RFC:**
  - entity joins from a model rooted at a source (`@Join(entity = …, on = …)`);
  - `@BindSourceModel` for a source model in an API module (RFC 0012).
- **Release (decided 2026-10-15):** M25, shipped in 0.10.0 before the freeze, which is renumbered M26, later M29
  (D-145), then M30 (D-148). Every new public type and member is `@Incubating`, so the M30.1 freeze review decides each one.
