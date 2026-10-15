# 17 — Query Sources

**Covers:** `@SourceModel`, `SourceQuery` and its `Builder`, `TableField.source`, `ColumnField.over`, `@Discriminator`,
and the outer `@QueryModel` that reads a source.
**Read when:** declaring a source model, reading one from an outer model, or changing what the processor generates for
either. Windows and set operations are RFC 0013 §3–§6; recursion is RFC 0013 §5, and §6 below.
**Owns:** `R-SRC-*`, `AC-SRC-*`. Rows `R-SRC-05`..`R-SRC-06` and `R-SRC-09` land with M25.4; `R-SRC-07`..`R-SRC-08`
land with M25.5; `R-SRC-04` lands with M25.3; the run-time half of `R-SRC-03` and all of `R-SRC-11`..`R-SRC-12` land
with M25.2.

---

## 1. `@SourceModel` — a query an outer model reads

```java
@SourceModel(root = OrderEntity.class)                                     // the entity the source reads
public record OrderTotals(
        @PrimaryKey @GroupBy Long customerId,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "total") BigDecimal total) {}

@QueryModel(root = OrderTotals.class)                                      // the root is the source, not an entity
public record CustomerTotals(@PrimaryKey Long customerId, BigDecimal total) {}
```

**R-SRC-01** A source model is declared with `@SourceModel(root = X.class)`. `X` is the `@Entity` the source reads,
and the source model is what an outer model reads by field name. A source takes the same field and type annotations as
`@QueryModel` — `@Column`, a to-one `@Join`, `@Computed`, `@Aggregate`, `@GroupBy` and `@FilterColumn` — and the
processor validates it against `X` with the same checks and codes (`processor/30` R-PROC-35). A source is one
statement, so a `@Child` on it is `MQ3030`. A source that names a `shape` is a **branch** of that shape's set operations
(§4, R-PROC-38): the processor checks its fields against the shape's, `MQ3034`.

The generated QModel declares a `source()` builder in place of `query()`: `where` and `having`, no `select` — a source
always carries every field of its model, which keeps an outer model's columns checkable at compile time — and
`build()` returning an immutable `SourceQuery<S>` (R-GEN-37, INV-9). A source QModel declares no `MAPPER` and no
write method, since nothing maps a source row and no write reads a source.

```java
public static SourceQuery.Builder<OrderEntity, OrderTotals, OrderTotals> source() {
    return SourceQuery.builder(OrderTotals.class, ROOT, ALL, KEY, GROUP_KEYS);
}
```

## 2. The source key is unique per row

Every key-based feature of the outer query needs the source's key to be unique in its result (INV-4), so the key is
unique by construction, or the source is non-unique and refused for those features (R-SRC-09, §4).

**R-SRC-02** A source model's `@PrimaryKey` is the root entity's id, for an ungrouped source, or the full `@GroupBy`
set, for a grouped source; anything else is `MQ3031`. Each group-key column must be non-nullable in its own mapping
— an id, a primitive, `nullable = false` or `optional = false` — because a NULL group key surfaces as `MQ2201` or
`MQ2202` in the middle of an export. A group key read through a to-one is nullable by that rule, since its id form
reads the LEFT-joined row (MQ3029), and is `MQ3031` too. A group key that is a `@Computed` expression has no mapping
to read, so its nullability is left to the runtime's `MQ2201`/`MQ2202`. A source model's fields may not read through a to-many join,
which is `MQ3031` too.

**R-SRC-03** What that uniqueness buys, and what keeps it:

- **A to-many filter inside a source is an explicit `exists`.** A `where` predicate whose path crosses a to-many join
  is `MQ1906`, pointing at `Filters.exists(path, inner)`, whose correlation R-FLT-11 and R-FLT-12 already define. The
  joins a predicate crosses are known only where it is resolved, so the code is raised while the source's CTE body is
  built for a statement over it — assembled, never executed (R-SRC-11), and so before any statement. The predicate is
  not rewritten silently: two conditions on one joined line
  would match different lines, `isNull` on a LEFT path would stop matching rows with no line, and `not` would become
  `not exists`. `select distinct` is no fix either, because window functions run before `distinct`. *(The code is
  raised from M25.2; the paths a source model may read are `MQ3031` at compile time today.)*
- **The outer key is the source's key.** An outer model's `@PrimaryKey` must be exactly the source's key fields
  (`MQ3036`), so the engine's tie-breakers and cursors use the unique key.

## 3. `SourceQuery` — the source an outer query carries

```java
@Incubating
public final class SourceQuery<S> {
    public static <E, M> Builder<E, M, M> builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields,
            PrimaryKey<M, ?> key);
    public static <E, M> Builder<E, M, M> builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields,
            PrimaryKey<M, ?> key, SelectSet<M> groupKeys);

    public static final class Builder<E, M, S> {
        public <S2> Builder<E, M, S2> shape(Class<S2> shape);
        public Builder<E, M, S> discriminator(ColumnField<M, ?, String> field, String value);
        public Builder<E, M, S> depth(ColumnField<M, ?, Integer> field);
        public Builder<E, M, S> recursive(Function<RecursiveStep<M>, RecursiveKeys<M, ?>> step, int maxDepth);
        public Builder<E, M, S> where(UnaryOperator<Filters<M>> filters);
        public Builder<E, M, S> having(UnaryOperator<Having<M>> filters);
        public SourceQuery<S> build();
    }

    public SourceQuery<S> union(SourceQuery<S> other);
    public SourceQuery<S> unionAll(SourceQuery<S> other);
    public SourceQuery<S> intersect(SourceQuery<S> other);
    public SourceQuery<S> except(SourceQuery<S> other);
}
```

**R-SRC-10** An outer query's source and root.

- **`TableField.source(Class<S>)`.** The source root kind: the generated `ROOT` of a model rooted at a source. It
  resolves no `From` of its own; the outer query reads the CTE's columns by field name, and its `rootEntity()` is the
  source model. A hand-written `ColumnField.of(...)` or `TableField.join(...)` over it is `MQ1905`, because there is no
  metamodel for INV-3 to check it against; a generated outer column uses
  `ColumnField.over(model, sourceRoot, sourceConstant)`, which takes the CTE column alias from the source constant's
  property and copies its converter, so the outer column narrows and converts as the source's does.

- **`SourceQuery` holds the source's structure.** `model`, `fields`, `key` and the optional `groupKeys` are fixed where
  it is built; the builder adds `where` and `having`, and the generated `source()` also names `shape` (R-PROC-38),
  `discriminator` (a key column, else `MQ1904`), `depth` (a column of the fields, else `MQ1902`) and `recursive`
  (R-SRC-07). It holds no Criteria object and no per-query state, so one instance can be a `static final` constant
  shared by any number of threads (INV-9) — a combined one holds its two sides and is as immutable. `having` on an
  ungrouped source is `MQ1407`, as it is on `ModelQuery`.

- **`ModelQuery` carries it.** `ModelQuery.builder(TableField<E, E> root, RowMapper<M> mapper, SourceQuery<E> source)`
  starts a definition over a source, and `ModelQuery.source()` returns the `SourceQuery` it was given, empty for a
  query rooted at an entity. `ModelUpdate.builder`, `ModelDelete.builder`, `InsertColumns.of` and `ModelInsert.select`
  take no source. `MQ1203` is raised when a source root reaches the two-argument `ModelQuery.builder(root, mapper)`, or
  when an entity root or a join reaches the three-argument one; every builder copy — `withLock`, `withFetch`,
  `orderedBy` and the rest — carries the source with the other settings.

- **The root kind is read from the annotation.** `@QueryModel(root = X.class)` reads `X`'s kind from the annotation on
  `X`: an entity root when `X` is `@Entity`, a source root when `X` is `@SourceModel`; neither, or both, is `MQ3038`
  (R-PROC-39). `@BindQueryModel.root` may name a source model, as a model's own `root` does.

- **Every outer field is checked against the source model's fields:** the name first, then the type (INV-3), with
  `MQ3001` and `MQ3002`. Columns are read by field name, so a `@Column` path in an outer model is a single segment, and
  its converter is the source field's — `@Column(converter = ...)` on an outer column is `MQ3014`. A `@FilterColumn`
  of an outer model is `MQ3011`, since it aliases a CTE column of its own and the CTE's columns are the source's
  fields. A `@Join` on a model rooted at a source is `MQ3035`: a source root has no associations. A `@Child` whose
  child model is rooted at a source is `MQ3039`, because the fetch plan has no `SourceQuery` to pass it; on a parent
  rooted at a source, a `@Child`'s parent-side key names a source field, and a `through` child is `MQ3406`, since
  it joins along entity associations the source does not have.

- **The generated `query(SourceQuery<S>)` takes the source**, so an outer query without one does not compile:

```java
public static ModelQuery.Builder<OrderTotals, Long, CustomerTotals> query(SourceQuery<OrderTotals> source) {
    return ModelQuery.builder(ROOT, MAPPER, source).primaryKey(KEY);
}
```

A grouped outer model over a source follows `api/13` as an entity-rooted one does; an ungrouped outer model over a
grouped source is the way to page groups by keyset.

## 4. Set operations

A set operation combines branches of one shape. A branch is a source model that names the shared shape, and maps the
shape's fields onto its own root.

```java
@SourceModel(root = OrderEntity.class)
public record FeedItem(@Discriminator("ORDER") @PrimaryKey String kind, @PrimaryKey Long id, String title) {}

@SourceModel(root = RefundEntity.class, shape = FeedItem.class)
public record RefundFeedItem(@Discriminator("REFUND") @PrimaryKey String kind, @PrimaryKey Long id,
                             @Column(attribute = "reason") String title) {}

SourceQuery<FeedItem> feed = QFeedItem.source().build().unionAll(QRefundFeedItem.source().build());
```

**R-PROC-38** A branch and its shape (`processor/30`):

- **The shape is a branch.** The shape model is an ordinary source over its own root, and the first branch whenever its
  own `source()` is used, so every branch fixes a discriminator value of its own. The generated
  `QRefundFeedItem.source()` returns a `SourceQuery.Builder<RefundEntity, RefundFeedItem, FeedItem>`, whose `build()` is
  a `SourceQuery<FeedItem>`: no generated interface is needed, and a branch of another shape does not compile.
- **The field match (`MQ3034`).** A branch's fields match the shape's by name, in declaration order, by Java type, by
  what the database stores (the same converter class, `EnumType` and temporal type) and by key. Anything else would
  compile and produce rows that compare unequal.
- **`@Discriminator`.** `@Discriminator("REFUND")` on a field fixes the branch's value for that column; it must be part
  of the key (`MQ3034`), since the key is what keeps the branches apart, and a model declares at most one. A branch of a
  discriminated shape declares the shape's `@Discriminator` field.
- **What a branch is not.** A branch declares no `@Window` field, and neither does a shape with branches; a recursive
  model is neither a branch nor a shape (M25.5).

**R-SRC-05** `union`, `unionAll`, `intersect` and `except` are methods of a `SourceQuery<S>`: each takes another
`SourceQuery<S>`, so only branches of one shape combine, and returns the combined source. The operation is rendered
inside the source's CTE body through `SourceRenderer#combine` (R-SRC-12, D-143). A vendor whose profile answers `false`
for the operation's `SourceFeature` is `MQ4015`, before any statement (R-SRC-11).

- **Whole rows.** A row belongs to an `intersect` or an `except` result only if every column matches; a non-key column
  that differs changes membership.
- **Nesting.** Operations nest in the order they are called: `a.union(b).except(c)` is `(a union b) except c`.
- **Key uniqueness.** The result's key is unique when:
  - `except`: the left branch's key is unique, the result being a subset of its rows;
  - `intersect`: either branch's is, a row appearing at most as many times as in the branch where it is rarest;
  - `union` or `unionAll`: every branch's is, and the branches are disjoint by key (R-SRC-06).
  Anything else is a non-unique source (R-SRC-09).

**R-SRC-06** The branches are disjoint by key in one of two ways:

- **By discriminator.** The shape's key includes a `@Discriminator` field and every branch fixes it to a different
  constant, which the branch renders as a literal. Two branches of one `union` or `unionAll` that fix the same value are
  `MQ1904`, raised where the operation is built; without the error the union would quietly lose key uniqueness.
  `intersect` and `except` don't check the values, since their uniqueness follows R-SRC-05 alone. The values are
  computed per node of the combined source: a leaf holds {v}, `union` and `unionAll` hold L u R, `intersect` L n R and
  `except` L. The `MQ1904` clash check compares the two sides' sets, and uniqueness needs them disjoint; the clash check
  is skipped for a distinct `union` whose leaves all read one model. Two sides whose fields differ in order, property,
  type or converter are `MQ1904` where `combine` is built, since their rows would not compare as the same shape.
- **By one branch model.** A distinct `union` whose branches all read one ungrouped branch model with no `@Window`
  field and one discriminator value: each root row yields the same columns in every branch, so a row two branches reach
  collapses to one. A grouped or windowed model computes its values over each branch's own rows, so it is non-unique
  there (R-SRC-09). This covers "owned `union` shared", where the rows a customer owns are unioned with the rows they
  share.

**R-SRC-09** A source that R-SRC-05 doesn't make unique, and that is not unique by construction, is **non-unique** —
as is a recursive source (M25.5). On a query over one, every feature that identifies a row by its key is refused before
any statement:

| Feature | Where | Code |
|---|---|---|
| `keyset()`, `primaryKeyFirst(...)` | the outer `build()` | `MQ1901` |
| `export`, `byKeys`, `one(q, key)`, a fetch plan with a child, join plan or enricher | the executor call | `MQ2212` |

`list`, `count`, `first`, `stream` and offset `page` work.

- **Offset paging.** On a non-unique source the outer key can't break ties: two rows with the same key and the same
  order values could swap between the statements of page 1 and page 2, and one row would show twice while the other
  never showed. Offset `page` therefore appends every selected outer column, ascending, as tie-breakers after the
  definition's `orderBy` and the key. A selected column that can't be ordered (a LOB, a JSON type) is `MQ2212`, and
  rows that compare equal under the vendor's ordering can't be told apart, so no row is visibly lost. Orderability
  is judged by Java type, so an `@Lob String` fails at the database rather than as `MQ2212`. Known limits (`engine/21`, M25 gate): the vendor's ordering is not always the Java one, namely a MySQL `_ai_ci` collation or trailing-space
  padding, MySQL `TEXT` sorted by its first `max_sort_length` bytes, and a `BigDecimal` of scale 1.0 against 1.00; rows
  the vendor calls equal are interchangeable to it, not to the caller.

## 5. Window fields

```java
@SourceModel(root = OrderEntity.class)
public record RankedOrder(
        @PrimaryKey Long id,                                              // the source's key, as ever
        String customer,
        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "customer",
                orderBy = "createdAt desc") Long rank,                    // one row per order, ranked within its customer
        @Window(function = WindowFunction.SUM, argument = "total", orderBy = "createdAt",
                frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal running) {}
```

**R-SRC-04** A `@Window` field of a source is a window function over the source's rows, rendered in the source's CTE
so an outer model reads its value as a plain column (R-GEN-38, R-PROC-36):

- **The value.** The field's type is the function's result type (INV-3): `Long` for `ROW_NUMBER`, `RANK`, `DENSE_RANK`
  and `COUNT`; the argument's own type for `LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE`, `MIN` and `MAX`; `SUM`'s and
  `AVG`'s rule as `@Aggregate` states it. `LAG` and `LEAD` read one row back and forward: there is no offset to write.
- **The determinism.** A window whose value depends on the order of rows it cannot tie is order-sensitive:
  `ROW_NUMBER`, `LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE`, and an aggregate under a `ROWS` frame. The engine appends
  the source key's columns, ascending, after the window's own `orderBy`, so every run of the same source gives a row the
  same value (R-SRC-02); a key column the window already orders by is not repeated. `RANK` and `DENSE_RANK` count peers,
  and an aggregate under a `RANGE` frame or no frame reads its whole partition, so their order is left as written: a key
  appended to `RANK` would turn it into `ROW_NUMBER`.
- **The frame.** `WindowFrame` writes one of the three `ROWS` frames or the two `RANGE` frames, or `NONE` for the
  database's default. The frame is rendered **only** for a function that takes one: `ROW_NUMBER`, `RANK`, `DENSE_RANK`,
  `LAG` and `LEAD` take none, and H2 rejects one, so `MQ3032` refuses it as well.
- **The group.** In a grouped source a window term names a `@GroupBy` key or an `@Aggregate` field, and an aggregate
  term renders that aggregate's own expression, so a source can rank its groups (`rank() over (order by sum(total))`).
  A window is a select item of the CTE: `where` and `having` do not accept one, since SQL forbids a window function in
  a predicate (R-GEN-38).
- **The provider.** The engine reaches the provider only through `SourceRenderer#window(cb, spec)`, the second hook
  beside `cte` (R-SRC-12), so no provider type leaks into `jpa` (INV-7). A vendor whose profile answers `false` for
  `SourceFeature.WINDOW` is `MQ4015`, before any statement (R-SRC-11).

## 6. Recursive sources

A source model is **recursive** when it declares a `@Depth` field: its rows are the nodes of a tree or graph, and its
`source()` builder requires the step that walks it.

```java
@SourceModel(root = CategoryEntity.class)
public record CategoryNode(@PrimaryKey Long id, @Column(attribute = "parentId") Long parentId, String name,
                           @Depth Integer level) {}

SourceQuery<CategoryNode> tree = QCategoryNode.source()
        .where(f -> f.eq(QCategoryNode.ID, rootId))                                    // the seed rows, level 0
        .recursive(step -> step.childKey(QCategoryNode.PARENT_ID).parentKey(QCategoryNode.ID), 20)
        .build();
```

**R-SRC-07** How a recursive source is written and built:

- **The `@Depth` field.** It is where the CTE's level column is read from and written to: the CTE assigns it, and the
  model maps it from no attribute (R-PROC-37). The generated `source()` names it, `.depth(LEVEL)`, exactly as a branch's
  names its shape and discriminator (R-PROC-38), so no caller can leave the level column unnamed.
- **The step.** `Builder#recursive(step, maxDepth)` takes a `Function<RecursiveStep<M>, RecursiveKeys<M, ?>>`:
  `step.childKey(child).parentKey(parent)` names the two columns a row is a child of another by, both of the source's
  root entity and typed by one column type `C` (`RecursiveKeys<S, C>`), so a pair of different key types fails to
  compile and a swapped pair reads wrong at the call site.
- **The levels.** The seed is the rows `where` keeps, at level 0, and each step adds 1, so `maxDepth = 20` returns
  levels 0 to 20. A recursive model must call `recursive(...)` before `build()`: without it the source would quietly
  return the seed rows alone.
- **`MQ1902`, before any statement:** a `maxDepth` below 1 or above `Integer.MAX_VALUE - 2` (whose `maxDepth + 2`
  ceiling check would overflow), `recursive(...)` on a source whose model declares no `@Depth`, and a `@Depth` model
  built without its step.
- **`MQ3037`, where the model is written:** a recursive source has no `@Window`, `@Aggregate` or `@GroupBy` (every
  database forbids all three in the recursive term), exactly one `@Depth`, and it is an `Integer`. A `@Depth` outside a
  `@SourceModel`, or on a branch or a shape, is `MQ3037` and `MQ3034` (`processor/30`, `processor/32`).
- **Never a branch.** A recursive source given to `union`, `unionAll`, `intersect` or `except`, on either side, is
  `MQ1904` where the operation is built, since a set operation would read it as its seed rows alone (R-SRC-05).
- **The source never writes.** Recursion is a read: the recursive model generates no `changes()`, `update(...)`,
  `from(...)` or `delete()`, as any source model does not.

**R-SRC-08** What a recursive source returns, and what it refuses:

- **The width.** A recursive CTE's rows are as wide as the non-recursive case: the source's fields, with the `@Depth`
  field carrying the level. A `@Window` or `@Aggregate` over a recursive result is Q-22.
- **The depth limit is loud.** The `with recursive` CTE steps to level `maxDepth + 1` and the cycle clause marks the
  row that closes a cycle; the outer query filters on `level <= maxDepth and not mark`, so neither reaches a result.
  The content statement and the count each carry two scalar sub-selects over the same CTE — the smallest key of the
  rows past `maxDepth` and the smallest marked key — which the engine reads from the statement's first row: a row past
  `maxDepth` is `MQ2701` and a marked row is `MQ2702`, each naming the model and the first offending key. So a too-deep
  tree and a cyclic graph are refused whole rather than returned in part (INV-5). An empty result carries no row to
  read, so the checks see nothing there; under READ COMMITTED a concurrent write can still deepen a tree after the
  statement read it, and REPEATABLE READ around the call is the full guarantee (`engine/21`).
- **A recursive source is non-unique.** In a tree each node is reached once, in a graph with two paths to a node it is
  not, and the shape alone cannot tell which one the data is (R-SRC-09): so `keyset()` and `primaryKeyFirst(...)` are
  `MQ1901` at the outer `build()`, and `export`, `byKeys`, `one(q, key)`, a fetch plan with a child and an enricher are
  `MQ2212` at the executor call, both before any statement. Offset paging, ordering, filters and the count work as they
  do on any source.
- **The vendor's ceiling.** A vendor whose profile answers `false` for `SourceFeature.RECURSIVE`, or whose
  `maxRecursionDepth()` is below `maxDepth + 2` (the extra level recursion needs and the final, empty iteration), is
  `MQ4015` before any statement (R-SRC-11, R-VND-22). MySQL's default of 1000 serves `maxDepth` 998 and refuses 999.
- **The provider's hook.** `core` builds the seed and the step and names the level and the two checks; the provider
  registers the `with recursive` CTE, its cycle clause and its two check sub-selects through
  `SourceRenderer#recursive(outer, spec)`, which returns the CTE's root with them, so no provider type leaks into `jpa`
  (INV-7, D-144).

## 7. Building the CTE, and what a source needs to run

**R-SRC-11** What a statement over a source needs, all checked before any statement:

- **The provider's support.** `ProviderSupport#sources()` returns the factory's `SourceRenderer`. Empty — the default,
  and the only answer where the factory's provider has no `ProviderSupport` at all — is `MQ4014`, before any statement,
  the same fail-safe as `ProviderSupport#inserts()` (R-VND-14). `model-query-hibernate` serves Hibernate on both lines.
- **The vendor's features.** `VendorProfile#sources(SourceFeature)` answers for each feature the source uses, `false`
  by default so a profile written before a constant was added fails safe. A `false` is `MQ4015`, before any statement,
  naming the feature and the vendor. The refusals are database facts (R-VND-11, INV-6); `PortabilityCheck` reports
  `MQ4014` and `MQ4015` per declared target, with no database (AC-SRC-16).
- **A row lock.** `withLock(...)` over a source is `MQ1903`: the source's rows come from a common table expression,
  which a locking clause cannot follow. Q-16 stays open.

**R-SRC-12** The engine (`core`) builds the source's body, as a standard `CriteriaQuery<Tuple>` whose elements are the
source's fields, aliased by the engine-given column name `mq_<property>` (`SourceQuery.columnName`, D-146: no field name
can collide with a vendor reserved word, INV-6), filtered by its `where` and `having` and grouped by its `groupKeys`
(R-SRC-03, D-143). It reaches the provider only through `SourceRenderer#cte(outer, body)`, which registers the body as
`outer`'s CTE and returns the root the outer statement reads by those names (`ColumnField.over` uses the same `columnName`), so no provider type leaks into `jpa`
(INV-7). The CTE is registered on **every** statement built over the source: the content query, the count, both
primary-key-first steps, each export page, `byKeys` (every chunk) and the parent reads of a fetch plan. A statement
that reaches the database without its CTE is therefore a bug in the engine, not a state the user can hold: the body is
built per statement from the immutable `SourceQuery`, which itself carries no Criteria object (INV-9).

## 8. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-SRC-01 | A plain source model reads the rows its hand-written SQL reads, on every Tier-1 vendor and both Hibernate lines (R-SRC-01). |
| AC-SRC-02 | A grouped source model with a `where` and a `having` builds the CTE its hand-written SQL builds, pinned as an SQL snapshot per vendor (R-SRC-01). |
| AC-SRC-03 | A keyset export over a grouped source visits every group exactly once (R-SRC-02, INV-4). |
| AC-SRC-04 | A source whose `where` crosses a to-many join is `MQ1906`, and the `Filters.exists` form of the same predicate returns the rows the hand-written SQL returns (R-SRC-03). |
| AC-SRC-05 | Every window function reads the hand-written SQL's values, and the SQL is pinned per vendor (R-SRC-04). |
| AC-SRC-06 | A top-N source paged by keyset and by primary-key-first over tied timestamps loses and repeats no row (R-SRC-04). |
| AC-SRC-07 | Each set operation renders the SQL it is written as, pinned per vendor (R-SRC-05, R-SRC-06). |
| AC-SRC-08 | A discriminated feed is exported by keyset without a repeated key (R-SRC-05). |
| AC-SRC-09 | An "owned `union` shared" list read from one branch model is paged by keyset (R-SRC-06). |
| AC-SRC-10 | Offset pages over a non-unique `unionAll` with duplicate keys and tied order values lose no row (R-SRC-09). |
| AC-SRC-11 | Each `MQ1901` and `MQ2212` refusal is raised before any statement (R-SRC-09). |
| AC-SRC-12 | A tree read by a recursive source is pinned as an SQL snapshot and returns every node, as does its count (M25.5; R-SRC-07). |
| AC-SRC-13 | A tree deeper than `maxDepth` is `MQ2701` and a cyclic graph is `MQ2702`, on the content query and on the count, with no marked row in the result (M25.5; R-SRC-08). |
| AC-SRC-14 | Every vendor serves `maxDepth` 998, and MySQL refuses 999 with `MQ4015` before any statement (M25.5; R-SRC-08). |
| AC-SRC-15 | Content, count and both primary-key-first steps of one source are registered on every statement (R-SRC-12). |
| AC-SRC-16 | `MQ4014` and `MQ4015` are reported per declared target without a database (R-SRC-11). |
