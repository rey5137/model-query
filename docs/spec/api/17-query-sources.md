# 17 — Query Sources

**Covers:** `@SourceModel`, `SourceQuery` and its `Builder`, `TableField.source`, `ColumnField.over`, `@Discriminator`,
the outer `@QueryModel` that reads a source, and `SourceJoin` with `joinSource`, which joins one to an entity-rooted
query.
**Read when:** declaring a source model, reading one from an outer model or joining one to an entity-rooted query, or
changing what the processor generates for either. Windows and set operations are RFC 0013 §3–§6; recursion is
RFC 0013 §5, and §6 below.
**Owns:** `R-SRC-*`, `AC-SRC-*`. Rows `R-SRC-05`..`R-SRC-06` and `R-SRC-09` land with M25.4; `R-SRC-07`..`R-SRC-08`
land with M25.5; `R-SRC-04` lands with M25.3; the run-time half of `R-SRC-03` and all of `R-SRC-11`..`R-SRC-12` land
with M25.2. The M26.1 additions to `R-SRC-02`, `R-SRC-08` and `R-SRC-11` — a composite id as the source key, the one
check site, the grouped count and the composite node key — land with M26.1, as AC-SRC-21's H2 half and AC-SRC-25 do.
`R-SRC-13`..`R-SRC-14`, AC-SRC-21's composite-key check and AC-SRC-24 land with M26.2. M26.3 makes tree sources
unique, completes AC-SRC-18 and AC-SRC-22 for every statement kind, and adds `R-SRC-15` and AC-SRC-17, AC-SRC-19,
AC-SRC-20 and AC-SRC-23. A plain `recursive(...)` source keeps R-SRC-09's non-unique refusals. M32.6 adds `R-SRC-21`
and `R-SRC-22` with AC-SRC-42 to AC-SRC-50 (D-185 Part A, D-187), and names the joined source in `R-SRC-08`,
`R-SRC-11`, `R-SRC-12`, `R-SRC-14`, `R-SRC-15` and `R-SRC-18`; `@JoinSource` and `@SourceColumn` are `processor/30` R-PROC-44. D-192 adds R-SRC-22's count over groups and AC-SRC-52.

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

**R-SRC-01** A source model is declared with `@SourceModel(root = X.class)`. `X` is an `@Entity` or another
`@SourceModel` the source reads (R-SRC-16), and the source model is what an outer model reads by field name. A source
takes the same field and type annotations as
`@QueryModel` — `@Column`, a to-one `@Join`, `@Computed`, `@Aggregate`, `@GroupBy` and `@FilterColumn` — and the
processor validates it against `X` with the same checks and codes (`processor/30` R-PROC-35). A source is one
statement, so a `@Child` on it is `MQ3030`. A source that names a `shape` is a **branch** of that shape's set operations
(§4, R-PROC-38): the processor checks its fields against the shape's, `MQ3034`.

For an entity root, the generated QModel declares a `source()` builder in place of `query()`: `where` and `having`,
no `select` — a source
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

**R-SRC-02** A source model's `@PrimaryKey` is the root entity's id, or the inner source's key fields in order
(R-SRC-16), for an ungrouped source, or the full `@GroupBy` set, for a grouped source; anything else is `MQ3031`. The id
is checked as a whole, so a root entity with an `@IdClass`
or `@EmbeddedId` id gives the source one key column per id attribute, and a key holding part of the id alone is
`MQ3031` still (D-149). Each group-key column must be non-nullable in its own mapping
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
    @Incubating
    public static <E, M> Builder<E, M, M> builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields,
            PrimaryKey<M, ?> key, SourceQuery<E> inner);
    @Incubating
    public static <E, M> Builder<E, M, M> builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields,
            PrimaryKey<M, ?> key, SelectSet<M> groupKeys, SourceQuery<E> inner);

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

**R-SRC-10** A model's source and root, including a source over a source (R-SRC-16).

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

**R-SRC-09** A source over a source inherits uniqueness when ungrouped and establishes it on its group key when
grouped (R-SRC-16). A source that R-SRC-05 doesn't make unique, and that is not unique by construction, is
**non-unique** —
as is a plain `recursive(...)` source (M25.5). A `tree(...)` source is unique under R-SRC-13 (M26.3); the set-operation
rules in R-SRC-05 and R-SRC-06 are unchanged. On a query over a non-unique source, every feature that identifies a row
by its key is refused before any statement:

| Feature | Where | Code |
|---|---|---|
| `keyset()`, `primaryKeyFirst(...)` | the outer `build()` | `MQ1901` |
| `export`, `byKeys`, `one(q, key)`, a fetch plan with a child, join plan or enricher | the executor call | `MQ2212` |

`list`, `count`, `first`, `stream` and offset `page` work. For a recursive source, the `MQ1901` and `MQ2212` messages
name `tree(...)` as the opt-in for a checked recursive walk.

- **Offset paging.** On a non-unique source the outer key can't break ties: two rows with the same key and the same
  order values could swap between the statements of page 1 and page 2, and one row would show twice while the other
  never showed. Offset `page` therefore appends every selected outer column, ascending, as tie-breakers after the
  definition's `orderBy` and the key. A selected column that can't be ordered (a LOB, a JSON type) is `MQ2212`, and
  rows that compare equal under the vendor's ordering can't be told apart, so no row is visibly lost. Orderability
  is judged by Java type, so an `@Lob String` fails at the database rather than as `MQ2212`. Known limits (`engine/21`, M25 gate): the vendor's ordering is not always the Java one, namely a MySQL `_ai_ci` collation or trailing-space
  padding, MySQL `TEXT` sorted by its first `max_sort_length` bytes, and a `BigDecimal` of scale 1.0 against 1.00; rows
  the vendor calls equal are interchangeable to it, not to the caller.
- **The tree exception.** A `tree(...)` source proves its node keys unique on each statement (R-SRC-14, R-SRC-15).
  Offset `page` therefore appends only the key after the definition's order, as R-PAG-01 does for a table. It adds no
  every-column tie-breakers and does not refuse an unorderable selected column on that ground. That holds when the
  query has a primary key (the generated `query(SourceQuery)` always sets one); an ungrouped tree query built without
  `.primaryKey(...)` orders by every selected column, as a non-unique source does, and likewise refuses an
  unorderable selected column with `MQ2212`, whose message points to declaring `.primaryKey(...)` on the query. A plain
  `recursive(...)` source keeps the every-column tie-breakers and the refusals above.

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
  levels 0 to 20. A recursive model must call `recursive(...)` or `tree(...)` (R-SRC-13) before `build()`: without
  either the source would quietly return the seed rows alone.
- **`MQ1902`, before any statement:** a `maxDepth` below 1 or above `Integer.MAX_VALUE - 2` (whose `maxDepth + 2`
  ceiling check would overflow), `recursive(...)` or `tree(...)` on a source whose model declares no `@Depth`, and a
  `@Depth` model built without its step.
- **`MQ3037`, where the model is written:** a recursive source has no `@Window`, `@Aggregate` or `@GroupBy` (every
  database forbids all three in the recursive term), exactly one `@Depth`, and it is an `Integer`. A `@Depth` outside a
  `@SourceModel`, or on a branch or a shape, is `MQ3037` and `MQ3034` (`processor/30`, `processor/32`).
- **Never a branch.** A source with any walk (a recursive source, or one built over a walk), given to `union`, `unionAll`, `intersect` or `except`, on either side, is
  `MQ1904` where the operation is built, since a set operation would read it as its seed rows alone (R-SRC-05).
- **The source never writes.** Recursion is a read: the recursive model generates no `changes()`, `update(...)`,
  `from(...)` or `delete()`, as any source model does not.

**R-SRC-08** What a recursive source returns, and what it refuses:

- **The width.** A recursive CTE's rows are as wide as the non-recursive case: the source's fields, with the `@Depth`
  field carrying the level. A `@Window` or `@Aggregate` over a recursive result uses a source over that source
  (R-SRC-16, D-150).
- **The depth limit is loud.** The `with recursive` CTE steps to level `maxDepth + 1` and the cycle clause marks the
  row that closes a cycle; the outer query filters on `level <= maxDepth and not mark` (a statement that joins the
  walk puts that filter in the join's `on`, R-SRC-22), so neither reaches a result.
  Every statement that reads or joins the source (R-SRC-15) carries depth and cycle scalar sub-selects over the same
  CTE — the smallest key of the rows past `maxDepth` and the smallest marked key — read from the statement's first
  row. A row past `maxDepth` is `MQ2701` and a marked row is `MQ2702`, each naming the model and the first offending
  key. A too-deep tree and a cyclic graph are refused whole rather than returned in part (INV-5). An empty result
  carries no row to read, so the checks see nothing there. Under READ COMMITTED a concurrent write can deepen a tree
  after the statement read it; REPEATABLE READ around the call is the full guarantee (`engine/21`).
- **One check site.** Every `Tuple` read in `model-query-jpa` goes through the `BuiltQuery` overloads of `rows`,
  `single` and `resultStream`, which read the checks off the statement's first row before any row is returned, and
  `resultStream` reads it before it hands the stream on. A raw `getResultList`, `getSingleResult` or
  `getResultStream` outside those three is a bug AC-SRC-25's ArchUnit rule reports (M26.1).
- **A plain `recursive(...)` source is non-unique.** In a tree each node is reached once; in a graph with two paths
  it is not, and the shape alone cannot tell which one the data is (R-SRC-09): `keyset()` and `primaryKeyFirst(...)` are
  `MQ1901` at the outer `build()`, and `export`, `byKeys`, `one(q, key)`, a fetch plan with a child and an enricher are
  `MQ2212` at the executor call, both before any statement. Offset paging, ordering, filters and the count work as they
  do on any source. `tree(...)` opts in to checked uniqueness (R-SRC-13), lifting these refusals and using only the
  key as the offset tie-breaker (R-SRC-09).
- **The vendor's ceiling.** A vendor whose profile answers `false` for `SourceFeature.RECURSIVE`, or whose
  `maxRecursionDepth()` is below `maxDepth + 2` (the extra level recursion needs and the final, empty iteration), is
  `MQ4015` before any statement (R-SRC-11, R-VND-22). MySQL's default of 1000 serves `maxDepth` 998 and refuses 999.
- **A composite node key.** The source's key is the node's key, so a root id of more than one column makes the node
  key composite, and the recursion's `cycle` clause has to carry every key column at once. That clause is a vendor
  fact: a profile that answers `false` for `SourceFeature.RECURSIVE_COMPOSITE_KEY` is `MQ4015` before any statement,
  naming the feature. H2's emulation is rejected there (`Column count does not match`), so H2 answers `false` and
  PostgreSQL and MySQL answer `true` (M26.1, D-149; R-VND-21, AC-SRC-21).
- **The provider's hook.** `core` builds the seed and the step and names the level and the checks; the provider
  registers the `with recursive` CTE, its cycle clause and its check sub-selects through
  `SourceRenderer#recursive(outer, spec)`, which returns the CTE's root with them, so no provider type leaks into `jpa`
  (INV-7, D-144). A `tree(...)` source adds the duplicate-key check of R-SRC-14.

**R-SRC-13** Opting in to a checked tree source (M26.2, D-147):

- **The call.** `SourceQuery.Builder#tree(step, maxDepth)` takes the same
  `Function<RecursiveStep<M>, RecursiveKeys<M, ?>>` as `recursive(...)`. It declares that the walk reaches each node
  once, checked by R-SRC-14. The seeds, step, `@Depth`, levels, depth bound, cycle clause, `MQ1902` validations and
  `MQ4015` vendor ceiling are the same as R-SRC-07 and R-SRC-08; a tree source cannot be a set-operation branch either.
- **The builder stays immutable.** Calling `tree(...)` or `recursive(...)` returns a new builder. The last call
  replaces the earlier recursion choice and its step and depth, as `where(...)` replaces the seed predicate (INV-9).
- **Checked uniqueness (M26.3).** `SourceQuery#unique()` answers `true` for a tree source. Keyset paging,
  primary-key-first paging, offset and keyset export, `byKeys`, `one(q, key)` and fetch plans with a child, join plan
  or enricher work under their usual rules. Every statement reads the tree's checks under R-SRC-15. Calling
  `recursive(...)` instead keeps the non-unique refusals of R-SRC-08 and R-SRC-09.

**R-SRC-14** The duplicate-key check (M26.2, D-147):

- **One scalar sub-select.** Only a `tree(...)` source carries a third check over its recursive CTE: group by every
  node-key column, keep groups with `count(*) > 1`, order by every key column and fetch the first row's first key
  column. The check is `null` when no key repeats; otherwise it names the smallest duplicated key's first column.
  A composite key is grouped and ordered whole. Hibernate uses the same form on every vendor and both supported
  lines (INV-6); a composite node key still requires `SourceFeature.RECURSIVE_COMPOSITE_KEY` (R-SRC-08).
- **The whole walk.** The sub-select reads the whole CTE, including the row past `maxDepth` and marked rows. An
  outer filter or page that drops the duplicate rows cannot hide them when the statement returns a row.
- **Read order.** The check site reads `MQ2701`, then `MQ2702`, then the duplicate check from the statement's first
  row, before mapping a row, taking its key or giving it to a stream consumer. A duplicate is `MQ2703`; a cycle
  stays `MQ2702`. `one(query)` reads the checks before its size test, so a doubled node is `MQ2703`, not `MQ2003`.
  `MQ2703` names the model and the first column of the smallest duplicated key, says that it names the first column
  for a composite key, and suggests narrowing the seeds or using `recursive(...)` for a graph.
- **No row, no check.** An empty result carries no check value and raises nothing. A plain count always returns a
  row and is checked, even when it counts zero. Under a `LEFT` join to a walk (R-SRC-22) an outer row the walk never
  reached is still a row, and is checked; an `INNER` join that keeps no row returns empty, unchecked. A count over
  groups over a source counts client-side through the check site (R-SRC-08): it is checked when a group is returned,
  and returns `0` unchecked when there is no group.
- **Canonical constructors only.** `RecursiveSpec` and `RecursiveCte` have only their canonical constructors;
  a recursive source passes `tree = false` and `duplicateKey = null` explicitly. A `recursive(...)`
  source selects and reads no duplicate check, even if its renderer returns one, so its SQL stays unchanged.
  `SourceRenderer#recursive` keeps its signature and `ProviderSupport` is unchanged (INV-7).
- **No unchecked tree.** A renderer returning `duplicateKey == null` for a tree is `MQ4014` before any statement:
  "the persistence provider's `SourceRenderer` returned no duplicate-key check for a `tree(...)` source".
  `PortabilityCheck` reports that reason per declared target with no database, as it reports the other R-SRC-11
  refusals. The same renderer still serves `recursive(...)`.

**R-SRC-15** Every statement reads the checks; no state is carried between statements (M26.3, D-147):

- **Every read.** The content query, plain count, count over groups, both `one(query)` and `one(q, key)`, each
  `byKeys` chunk, each keyset page, both primary-key-first steps, a deep offset page, each offset or keyset export
  page and a fetch plan's parent read carry and read the source's checks through R-SRC-08's check site. A
  primary-key-first read's second step re-walks the source with its `IN` list; it checks that whole walk too. A query
  that joins a walk (R-SRC-22) carries them on the same statements, and on two more: the root-id count of a query with
  a treated path (`ModelQuery#buildRootIdCount`, which selects the checks beside its count) and a `through` child load
  whose child query joins the walk. A grouped entity query that joins a walk counts its groups client-side through the
  check site, as a count over groups over a source does.
- **Before using a row.** The check site reads depth, cycle and duplicate-key checks before mapping, taking a key,
  deduping or delivering a row. `MQ2703` therefore precedes a keyset page or export's `MQ2202` or `MQ2205`,
  `byKeys`'s `MQ2005` or `MQ2003`, a primary-key-first second step's dedupe, and either `one` call's `MQ2003`.
  The empty-result limit in R-SRC-14 still applies.
- **Per statement.** Each statement checks the whole walk as of its own snapshot. With stable data and order
  values, export, keyset walks and primary-key-first pages visit every node exactly once. A concurrent write that
  makes a node reachable twice or creates a cycle is `MQ2703` or `MQ2702` on the next statement that reads a check
  row. Pages already delivered stay delivered, as with the depth check. No check state is kept between statements;
  REPEATABLE READ or a snapshot around the call is the full guarantee (`engine/21` R-PAG-01).
- **Fetch children.** Child rounds read the child entity, not the source, so they carry no recursive CTE or check.
  A child model rooted at a source, or one that joins a source, is `MQ3039` at compile time. A hand-written
  `ChildField` whose `query()` joins a walk is the one child round that carries it: its load joins the walk and reads
  its checks as any other statement. The checked parent keys identify the rows children attach to (R-FCH-05); a
  write between parent and child reads is the same race as a table-rooted fetch plan.

## 7. Building the CTE, and what a source needs to run

**R-SRC-11** What a statement over a source needs, all checked before any statement:

- **The provider's support.** `ProviderSupport#sources()` returns the factory's `SourceRenderer`. Empty — the default,
  and the only answer where the factory's provider has no `ProviderSupport` at all — is `MQ4014`, before any statement,
  the same fail-safe as `ProviderSupport#inserts()` (R-VND-14). `SourceRenderer`'s default `recursive`, `window`,
  `join` and `combine` methods also refuse with `MQ4014`: the provider lacks that renderer, rather than the vendor
  lacking a feature (D-150), so a provider without the `join` hook refuses a query that joins a source (R-SRC-22).
  `model-query-hibernate` serves Hibernate on both lines.
- **The vendor's features.** `VendorProfile#sources(SourceFeature)` answers for each feature the source uses, `false`
  by default so a profile written before a constant was added fails safe. A `false` is `MQ4015`, before any statement,
  naming the feature and the vendor. `RECURSIVE_COMPOSITE_KEY` is one of them, answered `false` by H2 alone, so a
  recursive source over a composite node key is the one source feature refused on a Tier-1 vendor (M26.1, D-149;
  AC-SRC-21). The refusals are database facts (R-VND-11, INV-6); `PortabilityCheck` reports `MQ4014` and `MQ4015` per
  declared target, with no database (AC-SRC-16), an H2 target's composite-key refusal included. A joined source
  (R-SRC-21) needs the same features as a source root: `CTE`, `false` on `OTHER` and so `MQ4015` there, and for a
  joined walk `RECURSIVE`, `RECURSIVE_COMPOSITE_KEY` and the `maxRecursionDepth()` ceiling of R-SRC-08, checked as its
  chain registers; `PortabilityCheck` reports each per target (AC-SRC-47, AC-SRC-50).
- **A row lock.** `withLock(...)` over a source, or on a query that joins one (R-SRC-21), is `MQ1903` before any
  statement: the source's rows come from a common table expression, which a locking clause cannot follow. Q-16 stays
  open.

**R-SRC-12** The engine (`core`) builds the source's body, as a standard `CriteriaQuery<Tuple>` whose elements are the
source's fields, aliased by the engine-given column name `mq_<property>` (`SourceQuery.columnName`, D-146: no field name
can collide with a vendor reserved word, INV-6), filtered by its `where` and `having` and grouped by its `groupKeys`
(R-SRC-03, D-143). It reaches the provider only through `SourceRenderer#cte(outer, body)`, which registers the body as
`outer`'s CTE and returns the root the outer statement reads by those names (`ColumnField.over` uses the same `columnName`), so no provider type leaks into `jpa`
(INV-7). The CTE is registered on **every** statement built over the source or joining it (R-SRC-22): the content
query, the count, both primary-key-first steps, each export page, `byKeys` (every chunk) and the parent reads of a
fetch plan. A statement that reaches the database without its CTE is therefore a bug in the engine, not a state the
user can hold: the body is built per statement from the immutable `SourceQuery`, which itself carries no Criteria
object (INV-9). The body's parameters, its `where`'s and `having`'s values with an `Expr.value` included, are bound by
the statement that registers it, under either value-handling setting (`api/12` R-FLT-08, D-179).

## 8. Sources over sources

**R-SRC-16** *(D-150)* A `@SourceModel(root = X.class)` may read another `@SourceModel`: a plain or grouped
source, a set-operation shape, a recursive walk, or a checked tree. Its `ROOT` is `TableField.source(X.class)`.
Mapped fields name one CTE column of `X` by field name, with the same type and converter checks as an outer model
(R-SRC-10, R-PROC-39). No association exists on that root: `@Join` is `MQ3035`; a source's `@Child` stays `MQ3030`.
It may add `@Window`, `@Aggregate`, `@GroupBy`, `@Computed` and a `@FilterColumn` naming one column of `X`.
An outer `@QueryModel` rooted at a source still refuses `@FilterColumn` with `MQ3011`.
A source over a source cannot itself be recursive (`@Depth`), a branch, or a shape (`MQ3041`, R-PROC-40).
Its root cannot be a branch model either, nor can a `@QueryModel`'s: root at that branch's shape instead
(`MQ3041`, D-152). Shape/branch checks use
source models within the compilation, including deferred models, as R-PROC-38 does. A cyclic root chain is `MQ3038`.

An ungrouped source's `@PrimaryKey` fields must map exactly to `X`'s key fields, in order. A grouped source's key is
its full `@GroupBy` set. A mismatch is `MQ3031`, whose message names the inner source and its key. Group keys must be
non-nullable: a mapped field inherits the inner field's nullability; a shape field is nullable if any branch's
corresponding field is nullable, considering branches within the compilation, including deferred models.
`@Depth`, `@Discriminator`, ranking windows and `COUNT` are non-nullable; other windows and aggregates are nullable. `@Computed` nullability is checked at execution (`MQ2201`/`MQ2202`), as for an
entity-rooted source. An ungrouped source inherits its inner source's uniqueness; grouping establishes uniqueness
on the whole group key even over a non-unique walk or set operation (R-SRC-09). An aggregate over a walk counts
paths; over a checked tree it counts nodes.

The generated factory is `source(SourceQuery<X> inner)`, replacing `source()` (R-GEN-39). The ungrouped and grouped
`SourceQuery.builder(...)` overloads take the inner source after the key or group keys. Missing or wrong-shaped
inner sources, including through raw types or casts, are `MQ1907` at the builder/build boundary; an entity-rooted
source given an inner, or an inner-bearing builder that also sets recursion or `shape(...)`, is also `MQ1907` at
`build()`. A branch or combined source is checked by its shape, not its branch model. `SourceQuery` retains the
immutable inner definition through every builder copy (INV-9); a chain may have any number of levels. A `@QueryModel`
over the final source takes the built chain in its existing `query(SourceQuery<S>)` factory.

A correlated expression anywhere in a source's field list, including inside another expression, is `MQ1908` at
`SourceQuery.builder(...)`/`build()`. The message directs the caller to select it in an outer query. Generated
`@Computed` constants can initialize normally: the refusal occurs when `source(...)` is called, never in the
QModel's static initializer where it would become `ExceptionInInitializerError`.

**R-SRC-17** *(D-150)* **Reading a source directly.** Every non-branch source QModel declares an `@Incubating`
`Rows` class and `@Incubating query(SourceQuery<S> source)`, returning `ModelQuery.Builder<S, K, S>` for source model
`S` and its key type `K`. A branch declares neither; a shape's own QModel declares both. A source over a source takes
the outer `SourceQuery<S>` built by `source(inner)`, rather than that inner definition.

`Rows.ROOT` is `TableField.source(S.class)`. Each mapped field has a `ColumnField.over(...)` constant over that root,
including aggregate and window fields; ordered columns retain their ordered type. `Rows.DEFAULT`, `Rows.ALL`,
`Rows.KEY` when keyed, and `Rows.MAPPER` select and map the source model itself (R-GEN-40). The factory uses
`Rows.ROOT` and `Rows.MAPPER`, and sets `primaryKey(Rows.KEY)` when keyed. All constants are immutable `static final`
(INV-9). Selecting `Rows.DEFAULT` and filtering on `Rows.RANK`, for example, filters the completed source's window
result in the outer statement.

The two vocabularies cannot mix. At `ModelQuery.Builder.build()`, a source's own constant in the direct read's
select set, `where` or order keys is `MQ1905`, with a message naming the `QX.Rows.FOO` constant to use. At
`SourceQuery.Builder.build()`, a `Rows` column in the source's `where`, fields, group keys, `having` or orders is
`MQ1905`, naming `QX.FOO` instead. Both refusals precede any statement.

Everything in R-SRC-10 about an outer model holds for `Rows`. Its key is the source's key, and key-based features
follow the source's uniqueness (R-SRC-09, R-SRC-13): non-unique sources refuse keyset and primary-key-first with
`MQ1901`, and key-identifying execution with `MQ2212`. Key predicates stay in the top statement (R-SRC-18), preserving
the full source's window ranks and aggregates. There are no fetch plans, because a source model cannot have `@Child`.

**R-SRC-18** *(D-150)* A chain registers every level on the top statement from innermost to outermost, rebuilding its
CTE handles per statement (content, count, each key phase, each export page) with no state on `SourceQuery` or
`ModelQuery` (R-SRC-12, INV-9). A combined inner source registers as one CTE whose body combines its branches; a branch
that itself reads an inner source registers that inner first. Each next level builds its body as its own
`CriteriaQuery<Tuple>` selecting from the inner CTE handle via `SourceRenderer#from(AbstractQuery<?>, CteHandle)`.
`SourceRenderer` gains `@Incubating interface CteHandle {}`, `@Incubating record RecursiveHandle(CteHandle handle,
Expression<?> depthKey, Expression<?> cycleKey, Expression<?> duplicateKey)`,
`register(CriteriaQuery<?> top, CriteriaQuery<Tuple> body)`, `registerRecursive(CriteriaQuery<?> top, RecursiveSpec
spec)` and `from(AbstractQuery<?> query, CteHandle inner)`; the three methods default to `MQ4014` ("chained CTEs"),
leaving `cte` and `recursive` unchanged for single-level sources.

In a chain over a `recursive(...)` or `tree(...)` source:
- **The walk filter goes into the next body.** The next CTE body applies `level <= maxDepth and not mark` in its own
  `where` before its windows and aggregates, so a window never ranks a row past `maxDepth` or on a cycle.
- **Checks stay in the top statement.** The depth, cycle and duplicate-key checks (R-SRC-08, R-SRC-15) are scalar
  sub-selects over the inner recursive CTE returned on `RecursiveHandle` and placed in the top statement of every
  statement kind. R-SRC-14's "no row, no check" applies: `list`, `page` and every other row-returning statement whose
  outer or intermediate `having` drops every row returns empty with no check; a statement that always returns a row (an
  ungrouped `count`) still raises, because its checks read the inner CTE.
- **Key predicates stay in the top statement.** `byKeys`' `IN`, the keyset cursor, `primaryKeyFirst`'s step 2 and
  `one(q, key)` apply only to the top statement, never inside a CTE body with a window or aggregate, so ranks and
  counts are computed over the full source.
- **A chain under a join.** A joined source's chain (R-SRC-21) registers on the top statement the same way, innermost
  first, and the join reads its outermost level. A walk inside the chain keeps the next-body filter above; a joined
  source that is itself the walk is filtered in the join's `on` instead (R-SRC-22). Its checks stay in the top
  statement either way.

**R-SRC-19** *(D-150)* A correlated column renders as a scalar sub-query in every position: select, where and
order. There is no join lowering and no `VendorProfile` fact for choosing a different rendering. Index the
sub-select's correlated columns: without an index, each outer row may scan the inner table. RFC 0015 §6 records
indexed and unindexed costs; the Subqueries and expressions page, §Correlated columns, documents the cost table
and index advice.

**R-SRC-20** *(D-154, D-162)* **Subtypes in a source.** A source over a polymorphic root may declare treated and
`@Subtype` fields, and filter by a treated column, rendered in its CTE body (`vendor/40` R-VND-26 `SOURCE_BODY`):

- **The body selects the root id.** An ungrouped body over an entity root with a treated node keeps every root row
  only while it reads the root untreated (`api/10` R-COL-35). When none of its fields reads the untreated root id, it
  selects it last as the hidden `mq_0id`, which no outer column, `fields()`, mapper or `Rows` reads. In a combined
  source every branch selects it when one needs it, so the branches keep one arity; a `union`, `intersect` or `except`
  branch that would need it is `MQ4016`, and `unionAll` is unaffected. A combination that needs it is `MQ4016` while
  any of its operations, nested ones included, is a `union`, `intersect` or `except`, whose compared rows the anchor
  would change. A grouped, recursive or source-over-source branch can't select it, so a combination that needs it
  with such a branch is `MQ4016` before any statement, as is one whose branches' root ids differ in Java type. A body
  or branch with an `@IdClass` or `@EmbeddedId` root is `MQ4016`, as a content statement is.
- **No group or aggregate over a treated field.** A grouped source's sites are `GROUPED`: a treated group key, an
  aggregate argument and a window function's argument over a treated node are `MQ4016` before any statement. A
  recursive body and a body over another source are unanchored. A `@Subtype` field in a body over a `JOINED` root
  without a discriminator column is `MQ4016`: the provider reads the root's class on every row there.
- **A union of subtype models.** Any entity of the hierarchy may root a branch; R-SRC-05 and R-SRC-06 are unchanged.
  `(kind, id)` is unique by `@Discriminator`; without one, a union of two subtype roots is non-unique (R-SRC-09), even
  where their ids can't collide. Branches rooted at a subtype and its own subtype read a row of the latter twice under
  `unionAll`, with two `kind` values, so the keys stay unique.
- **Which form to use.** A base model reads one polymorphic statement, with NULL in fields that don't apply; a union
  maps each subtype's own attributes into one shape, one table read per branch.

## 9. Joining a source to an entity-rooted query

**R-SRC-21** *(D-185, D-187; `@Incubating`)* **A unique source, on its full key.** A query rooted at an entity joins a
source with `ModelQuery.Builder#joinSource(SourceJoin<M, S>, SourceQuery<S>)` (RFC 0020 A.1, A.2). `@JoinSource`
and `@SourceColumn` (`processor/30` R-PROC-44) generate a `query(...)` that takes each joined source; this rule is the
runtime API they generate against.

- **`SourceJoin`.** `SourceJoin<M, S>` (`core`) is immutable (INV-9): a name, the source model, a `JoinKind` (`LEFT`,
  or `INNER` to keep only the outer rows a source row matches; no other kind) and the key pairs
  `SourceJoin.on(outer column, source key field)`. `join.root()` is a table field of the *joined source* kind, keyed
  by the join's name and source model, so two joins of one source model stay apart; it resolves to that join in the
  statement's `JoinContext`. `ColumnField.over(model, join.root(), field)` reads a joined column by its `mq_<property>`
  name (R-SRC-12), so filters, orders and `Row.get` treat it as any column. A joined column is nullable under `LEFT`;
  a keyset over one follows the nullable-key rules of R-COL-12 and R-PAG-* unchanged. Every builder copy carries the
  bindings, as it carries a source.
- **The full key, once.** The pairs name every key field of the source once (R-SRC-02), so the join adds at most one
  row per outer row: the outer key, cursors, counts and export pages keep R-PAG-*'s guarantees.
- **A unique source.** The join is allowed exactly when `SourceQuery#unique()` holds (R-SRC-09, R-SRC-13): a plain or
  grouped source, a `tree(...)` source, an ungrouped source over a tree and a grouped source over any walk. A set
  operation R-SRC-09 treats as non-unique, a plain `recursive(...)` source and an ungrouped source over one are not
  joined, since a node reached by two paths would repeat its outer row in content, count and offset pages with no
  check to see it.
- **One walk per statement.** A statement reads one walk's checks (R-SRC-22), so a query whose joined chains walk more
  than once, a combined source's branches counted, is refused.
- **`MQ1910`** when the query is built, before any statement: a partial key, an extra pair or a key field paired
  twice; a non-unique source, whose message names `tree(...)` when it recurses; a join bound twice, by name; a source
  of another model bound through a raw type; a second walk; a hand-written `on(column, key)` pair whose source key
  field has a converter or whose outer column and key differ in boxed attribute type (a converted outer column, `Long`
  against `Integer`; `int` against `Integer` is the same type), since the join compares database values (D-191, as the
  processor's `MQ3044`). `joinSource` on a query rooted at a source is `MQ1905`:
  joining a source to a source-rooted model is not supported, and `MQ3035` (a `@Join` on such a model) is unchanged.
  A `join.root()` passed as the root of `ModelQuery.builder` or `SourceQuery.builder` is `MQ1203`. A query that joins
  a source, `INNER` or `LEFT`, is no returning query: `MQ1809` before any statement (`api/14` R-WRT-48). A fetch plan
  may key a child or an enricher by a joined column, which R-FCH-02's to-many check passes, since it is read through
  no join. A column over the root of a join the query does not bind is `MQ1003`, raised at first resolution; the
  generated `query(...)` takes every joined source, so only hand-written code reaches it.
- **`Agg.count` over a joined source.** Over a `LEFT` join it renders `count(x.mq_<first key field>)`, so an outer row
  the source does not match counts `0`; over an `INNER` join it is the row count where R-AGG-19's eligibility holds,
  an `INNER` join reached through a `LEFT` one excluded (R-AGG-19). A filtered count uses `1` when eligible, else the
  key, under its filter and wraps the count in `coalesce(<count>, 0)` (R-AGG-17).

**R-SRC-22** *(D-185, D-187)* **Rendering.** The engine builds the joined source's body as R-SRC-12 does, registers it
(and its chain, R-SRC-18) on the top statement with `SourceRenderer#register` or `#registerRecursive`, and joins it
with `SourceRenderer#join(outer, handle, type, on)` (`@Incubating`, default `MQ4014`): `core` builds the key predicate
over standard Criteria and the provider applies it to the join it creates, since the type a provider joins a CTE with
need not be a `Join` (RFC 0020 A.3, A.5). `model-query-hibernate` joins through `JpaFrom#join(JpaCteCriteria,
SqmJoinType)` on both lines, looked up by reflection; a line without it, or without `on(Predicate...)` on the join it
returns, refuses a joined source with `MQ4014` and still renders every other source.

- **Every statement.** The join is on every statement of R-SRC-12's list, even a `LEFT` join no filter reads, and on
  the root-id count of a query with a treated path and a `through` child load (R-SRC-15). Each statement binds the
  body's parameters once (`api/12` R-FLT-08, D-179).
- **Count over groups.** A grouped query that joins a source counts its groups client-side on every provider, since
  Hibernate's `createCountQuery()` loses the joined CTE on 6.6 and on 7.4 with H2 (`engine/20` R-EXE-03, D-192).
- **Inlining.** Hibernate inlines a joined non-recursive CTE as a derived table on H2, which evaluates it once per
  outer row; an index on the join key keeps that usable on a test database. A recursive CTE is never inlined: H2, too,
  receives a joined walk's `with recursive`.
- **A joined walk.** When the joined source is itself a walk, its filter `level <= maxDepth and not mark` goes into
  the join's `on`, which is the same as joining the filtered walk (with an `INNER` join the outer rows past `maxDepth` are
  dropped, and if that drops every row the result is empty and its checks are not read, as with a source root over no
  rows; the count statement still reads them): a `LEFT` join keeps an outer row whose node is past
  the limit, marked or never reached, with NULL columns, where the outer `where` would drop it. A walk inside the
  joined chain is filtered in the next body (R-SRC-18). The walk's depth, cycle and, for a tree, duplicate-key checks
  are uncorrelated sub-selects over the whole walk in the top statement's selection, read by the one check site in
  R-SRC-14's order; the message names the join (`join 'nodes' of FolderNode`). An outer row is checked whether or not
  the walk reached it; only an empty result is not (R-SRC-14).
- **Locks, children, writes.** A lock is `MQ1903` (R-SRC-11); a `@Child` whose child model joins a source is `MQ3039`
  (R-PROC-44); `ModelUpdate`, `ModelDelete`, `InsertColumns` and `ModelInsert.select` take no source.

**R-SRC-23** *(D-193, `@Incubating`)* **Entity joins off joined sources.** An immutable `EntityJoin<M, E>`
binds an entity's full id to fields of one registered joined source. `EntityJoin.of(...)` takes a unique name,
the entity class, `annotations.JoinKind` (`LEFT` by default, or `INNER`) and immutable `EntityJoin.on(ColumnField<M, ?, ?> anchor, String idAttribute)`
pairs (the id attribute stays a `String`; the former key type parameter is gone, D-196); `Builder#joinEntity` registers it. Its `root()` is a joined-entity `TableField`, keyed by the name, safe to
share as a `static final` definition (INV-9). Columns read its basic attributes or to-one paths. A LEFT join keeps
an outer row when the source or entity is unmatched and yields null joined values; INNER drops either unmatched
row. Separate names joining the same entity remain separate. Inspection includes each entity join and its kind,
anchor and id pairs, and each join filter in force (R-COL-39).

The pairs cover every basic id attribute exactly once, including all attributes of an `@IdClass` or the flattened
basic attributes of an `@EmbeddedId`. A pair's unconverted source attribute type equals the id attribute's type
after boxing (`int` and `Integer` agree, D-191). Association ids and to-many paths below a joined entity are refused.
`MQ1911` is raised at build, without a metamodel, for a converted anchor, pairs spanning two joined sources, an
unbound source or a name registered twice (including a source join's name). At first resolution, before any
statement, it refuses partial or extra id pairs, repeated or non-id attributes, association id attributes,
boxed-type mismatches and to-many paths, and an entity class the render options' metamodel rejects as unmapped (without a
metamodel, the renderer or provider decides: Hibernate may throw its own `IllegalArgumentException`, and a joined
`From` whose model is no `EntityType` is `MQ4014`). A column over an entity join the query does not bind,
or a named source or entity-join column under a join plan (R-FCH-07, AC-FCH-05), is `MQ1003`; `MQ1910` stays the
source-binding code. A to-one column below an entity join resolves by its model field name, as the root's does. A cursor's identity
path for an entity-join column is `entity(<join>).<attr...>` (R-PAG-19, D-196). The processor forms and `MQ3046`
belong to M33.2.

`Agg.count` over an INNER joined entity, its id column or an INNER to-one join below it renders the row count
`count(*)` when its attached root is eligible (R-AGG-19, D-199), for source and column anchors alike, including
composite and embedded ids. A LEFT join, plain or filtered, stops eligibility and counts the first basic id
attribute; an unmatched or filter-blanked row counts 0. Filtered counts use `1` under the condition when eligible,
else that identity, wrapped in `coalesce(<count>, 0)` so empty input returns 0 on both Hibernate lines.
`firstBy` may read a joined-entity column and returns one value per group. Nullable joined order keys follow the
existing keyset rules, including `nullsLast`.

**R-SRC-24** *(D-193, `@Incubating`)* **Entity-join rendering.** On every statement R-SRC-22 names, the engine
creates each entity join immediately after its anchor source join, including joins no selected column reads.
The provider attaches it to the statement's ROOT, never to the CTE join. Content, count, both primary-key-first
steps, keyset pages, `byKeys`, both `one` calls, every export page, streams, fetch-parent reads and through-child
loads all retain it. An INNER entity join therefore filters rows in content and totals alike. A grouped query
that joins a source and an entity takes D-192's client-side group count, with no warning; R-SRC-25 covers a
column-anchored join.

`EntityJoinRenderer`, exposed by `ProviderSupport#entityJoins()` (R-VND-32), receives the root, entity, join kind
and engine predicate factory. The engine builds the full equality predicate with standard Criteria; the provider
calls `on` exactly once per join. Hibernate resolves `JpaFrom#join(Class, SqmJoinType)` for each supported line
and looks up `on` once per join: Hibernate 6.6 ANDs repeated calls while 7.4 replaces the earlier predicate.
A missing hook, or one unable to attach to the root, is `MQ4014` before any statement, after vendor `MQ4015` and
the `SourceRenderer` `MQ4014`. `PortabilityCheck` reports the refusal separately for every declared target. A
renderer that does not call `on` exactly once, returns a join other than the one it passed to `on` or returns one with
no `ON` is also `MQ4014` naming the renderer class (R-VND-32, AC-VND-22).
Lock `MQ1903`, child-model `MQ3039` and the source-query write restrictions remain unchanged.

**R-SRC-25** *(D-197, `@Incubating`)* **Entity joins anchored on outer columns.** An `EntityJoin` whose pairs read
columns of the root, or of a to-one path from it (R-COL-25), joins with no joined source: `EntityJoin.on(anchor,
idAttribute)` takes such a column, and `Builder#joinEntity` registers it with no `SourceQuery`. R-SRC-23's id rule
holds unchanged (the full basic id, each attribute once, boxed types equal), so the join adds at most one row per
outer row. All pairs of one join read one joined source, or all read the root and its to-one paths; pairs may span
the root and several of its to-one paths.

- **Anchor rule.** `MQ1911` at build, without a metamodel: pairs mixing a joined source's columns with the root's, an
  anchor over another entity join or a path below one (no chains), over a treated node (R-COL-35), over a source, an
  `Outer` lift, a converted anchor, and any entity join on a query rooted at a source. At first resolution, before
  any statement: R-SRC-23's id checks, an anchor through a to-many path, and an anchor that reads a to-one by its id
  (R-COL-29; anchor on the foreign key's basic column instead).
- **The static `when` term.** `EntityJoin#when(column, value)` returns a copy of the join whose one `ON` also ANDs
  `column = value`, for a polymorphic reference whose ids collide across tables. The value is a definition constant
  (INV-9), bound as a statement parameter, through the operand binder under inline value handling. Its column
  follows the anchor rule above on every entity join, a source-anchored one included: the root or a to-one path from
  it, unconverted. A second `when`, or a value that is null, not of the column's boxed attribute type, or of a type
  R-COL-18 does not bind (an enum, `Optional`, a collection, an array, `Date`, `Calendar`, an entity), is `MQ1911`.
- **Rendering.** On every statement R-SRC-24 names, after the joined sources and their entity joins, the engine
  creates each column-anchored join in registration order. Each anchor's and the `when` column's own to-one joins
  resolve first, so the `ON` reads only tables before it. Hibernate may read a to-one path's id as the foreign key and
  leave that path's join out. The join goes through `EntityJoinRenderer` as R-SRC-24 says (`MQ4014` unchanged).
- **Join kind and filters.** Under `LEFT` an orphan anchor reads NULL columns and keeps the row. `INNER` drops
  unmatched rows, including rows whose anchor is NULL because its own `LEFT` path was unmatched, in content and
  totals. An `INNER` join whose anchor or `when` column reads through a `joinFilter` join (R-COL-39) is `MQ1912` at
  statement build (D-195 extended); declare it `LEFT`.
- **No CTE gate.** `MQ4015` for an entity join on a vendor without `SourceFeature.CTE` applies only to a query that
  joins a source; a column-anchored join renders on `OTHER`.
- **Count over groups.** A grouped query with column-anchored entity joins and no joined source counts its groups
  through `ProviderSupport#countQuery`'s copy, which keeps the join (spike B.3); one that joins a source stays
  client-side (D-192).
- **Locks.** A locked read of a query with an entity join is `MQ2211` before any statement (R-EXE-14): the provider
  reports the entity join as a join of the statement.
- **Children.** A hand-written `ChildField` whose child query has a column-anchored entity join loads it in a
  `through` child statement, with the anchors read on the path's last join; `@Child` over such a model is the
  processor's.
- **Writes.** Columns over an entity join are read-only, as columns over a `@Join` path are:
  `ModelUpdate`'s `set` on one is `MQ1604` (a column of another entity type does not compile, R-WRT-06);
  `InsertColumns#add` on one is `MQ1801`; a `ModelUpdate` or `ModelDelete` `where` reading one is `MQ1003` at
  statement build, since a write binds no entity join; `persist` returning a model of a query with `joinEntity(...)`
  is `MQ1809`.

## 10. Acceptance criteria

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
| AC-SRC-13 | A tree deeper than `maxDepth` is `MQ2701` and a cyclic graph is `MQ2702`, on the content query, on the count and on a count over the source's groups — which counts client-side, keeps the check columns and logs no R-EXE-03 warning — with no marked row in the result (M25.5, the grouped count M26.1; R-SRC-08). |
| AC-SRC-14 | Every vendor serves `maxDepth` 998, and MySQL refuses 999 with `MQ4015` before any statement (M25.5; R-SRC-08). |
| AC-SRC-15 | Content, count and both primary-key-first steps of one source are registered on every statement (R-SRC-12). |
| AC-SRC-16 | `MQ4014` and `MQ4015` are reported per declared target without a database (R-SRC-11). |
| AC-SRC-17 | A tree exported by keyset and offset, paged by keyset and primary-key-first, read by `byKeys` and `one(q, key)`, and given a fetch-plan child returns every node exactly once; the check is pinned as an SQL snapshot per Tier-1 vendor and both Hibernate lines (R-SRC-13, R-SRC-15). |
| AC-SRC-18 | Overlapping seeds and a link-table diamond raise `MQ2703` on every R-SRC-15 statement kind: content, count, count over groups, `one(q, key)`, each `byKeys` chunk, keyset page, both primary-key-first steps, deep offset page, offset and keyset export pages, and fetch-plan parent read. It names the model and first column of the smallest duplicated key, precedes keyset's `MQ2202` or `MQ2205` and `byKeys`'s `MQ2005` or `MQ2003`, and is checked again on later chunks and primary-key-first step 2 after a concurrent break; a cycle stays `MQ2702` (R-SRC-14, R-SRC-15). `one(query)` is covered by AC-SRC-22; the `recursive(...)` count-over-groups depth and cycle checks are AC-SRC-13. |
| AC-SRC-19 | A duplicate made between two export pages on a second connection at READ COMMITTED is `MQ2703` on the next offset or keyset page, before any repeated-row `MQ2205`; pages already delivered stay delivered. At REPEATABLE READ the export visits every node once (R-SRC-15). |
| AC-SRC-20 | Offset `page` over a tree orders by the definition's order plus the key only, pinned as an SQL snapshot per vendor and both Hibernate lines. A plain `recursive(...)` source keeps `MQ1901`, `MQ2212` and the every-column tie-breakers; the messages name `tree(...)`. `MQ1902` for a `@Depth` model built without a step names `recursive(...)` and `tree(...)` (R-SRC-09, R-SRC-13). |
| AC-SRC-21 | A `recursive(...)` or `tree(...)` source over a composite node key is `MQ4015` naming `RECURSIVE_COMPOSITE_KEY` before any statement on H2, and `PortabilityCheck` reports it for an H2 target with no database (the feature guard lands with M26.1, the tree call with M26.2; R-SRC-07, R-SRC-11, R-VND-21). On PostgreSQL and MySQL a tree is checked on the whole key, and `MQ2703` says it names the key's first column (the check half lands with M26.2; R-SRC-14). |
| AC-SRC-22 | `one(query)` and `one(q, key)` over a tree that reaches a node twice raise `MQ2703` before the single-row `MQ2003` check (R-SRC-14, R-SRC-15). |
| AC-SRC-23 | Over a tree that reaches a node twice, an outer filter matching no row gives an empty `list`, `MQ2703` from `page`'s count, and an empty count-free page. A count over groups matching no group returns `0`; one matching a group is `MQ2703` (R-SRC-14, R-SRC-15). |
| AC-SRC-24 | A `SourceRenderer` returning a `RecursiveCte` without a duplicate check for a tree source is `MQ4014` before any statement, and `PortabilityCheck` reports the no-check reason per declared target with no database. The same renderer still serves `recursive(...)`; a renderer returning a duplicate check for `recursive(...)` leaves its SQL snapshot unchanged (M26.2; R-SRC-14). |
| AC-SRC-25 | In `model-query-jpa`, `getResultList`, `getSingleResult` and `getResultStream` on a `Tuple` query are called only from methods named `rows`, `single` or `resultStream` — the check-site overloads — so no `Tuple` row reaches a caller before its source's checks are read (an ArchUnit rule, M26.1; R-SRC-08). The rule is stated **by method**, the RFC's fallback, because ArchUnit sees a call's erased target types and not the statement's `Tuple` element; the other half of that fallback, no untyped read of a `BuiltQuery`'s statement, is the compiler's: the executor's `rows` and `single` without a `BuiltQuery` take only an entity and a `Long` query, so a `Tuple` query has no unchecked overload to call. `resultStream` reads the first row before it returns the stream, so a body's `count()` cannot skip it. |
| AC-SRC-26 | A source over a plain source compiles; its generated factory requires the correctly typed inner source, carries it through immutable builder copies, and rejects missing or wrong inner definitions with `MQ1907` (R-SRC-16, R-GEN-39). |
| AC-SRC-27 | A source over a grouped source compiles and its generated `source(inner)` builds; a grouped source over a source passes its group keys and inner to the builder (R-SRC-16, R-GEN-39). |
| AC-SRC-28 | A source over a union shape compiles and builds with the combined source or a branch of that shape; inner validation compares the shape rather than the branch model (R-SRC-16). |
| AC-SRC-29 | A source over a recursive walk compiles and builds; an ungrouped definition inherits non-uniqueness and refuses keyset and primary-key-first with `MQ1901`, while a grouped definition is unique (R-SRC-16, R-SRC-09). |
| AC-SRC-30 | A source over a checked tree compiles and builds, retaining uniqueness (R-SRC-16). |
| AC-SRC-31 | Three source levels compile, build and execute through generated factories; a `SourceRenderer` without `register`/`registerRecursive`/`from` refuses `MQ4014` ("chained CTEs") before any statement (R-SRC-16, R-SRC-18). |
| AC-SRC-32 | In a chain's statement on every statement kind, the walk filter (`level <= maxDepth and not mark`) runs inside the next CTE body before its windows and aggregates (so a window never ranks a row past `maxDepth` or on a cycle), depth/cycle/duplicate-key checks live in the top statement over the inner CTE (`list`, `page` and every other row-returning statement whose outer or intermediate `having` drops every row returns empty with no check, while an ungrouped `count` still raises because its checks read the inner CTE), and key predicates (`byKeys`, keyset cursor, `primaryKeyFirst` step 2, `one(q, key)`) apply only to the top statement so ranks and counts are computed over the full source (R-SRC-18). |
| AC-SRC-33 | `Rows`/`query` goldens: plain, grouped, window; neither on a branch; user `Rows` compiles (R-GEN-40). |
| AC-SRC-34 | Direct `Rows.DEFAULT`, window `where`, unique `Rows.KEY` keyset; non-unique is `MQ1901` (R-SRC-17). |
| AC-SRC-35 | Own constants in a direct read's select/where/order give `MQ1905`: use `QX.Rows.FOO` (R-SRC-17). |
| AC-SRC-36 | `Rows` in source fields, `where` or orders gives `MQ1905` naming `QX.FOO` (R-SRC-17). |
| AC-SRC-37 | Direct and nested correlated expressions in source fields are `MQ1908` at the builder boundary; a generated QModel with such a `@Computed` initializes successfully and refuses only when `source(...)` is called (R-SRC-16). |
| AC-SRC-38 | Direct chain/tree reads: count, `byKeys`, `one(q, key)` and every export page (R-SRC-17, R-SRC-18). |
| AC-SRC-39 | Per strategy, treated and `@Subtype` fields in a source body read as AC-COL-44 and AC-COL-49, with the root id selected in the body (R-SRC-20, D-162). |
| AC-SRC-40 | Per strategy, a `unionAll` of card and bank branches by `@Discriminator` returns each `(kind, id)` once with `reference` from `number` or `iban`; without the discriminator it is non-unique (`MQ2212` on export) (R-SRC-20). |
| AC-SRC-41 | Branches rooted at `CardPayment` and `PremiumCardPayment` return a premium row twice with two `kind` values and unique keys (R-SRC-20). |
| AC-SRC-42 | On every Tier-1 vendor and both Hibernate lines, two `LEFT` joins of one grouped source model return the hand-written SQL's rows, an outer row with no source row reading NULL and the joins staying apart; `Agg.count` over a `LEFT`-joined source is `0` for an unmatched row (R-SRC-21, AC-AGG-29). |
| AC-SRC-43 | The join is in every statement of R-SRC-12's list (content, count, both primary-key-first steps, keyset page, `byKeys`, `one(q, key)`, every export page) with each body's parameters bound once per statement; a provider without `SourceRenderer#join`, or a Hibernate line without `JpaFrom#join(JpaCteCriteria, SqmJoinType)`, is `MQ4014` before any statement; a fetch plan whose enricher reads a joined column runs (R-SRC-21, R-SRC-22). |
| AC-SRC-44 | `INNER` drops an unmatched outer row in content and count alike; keyset pages and a keyset export ordered by a joined column lose no row (R-SRC-21). |
| AC-SRC-45 | `MQ1910` for a partial key, an extra pair, a key field paired twice, a join bound twice, a non-unique source and a plain `recursive(...)` source (naming `tree(...)`), before any statement, whether hand-written or passed to a generated `query(...)` (the processor does not check uniqueness, D-190); the same join over a `tree(...)` source builds; a `join.root()` as a `ModelQuery` or `SourceQuery` builder root is `MQ1203`, and a returning query that joins a source, `INNER` or `LEFT`, is `MQ1809` before any statement (R-SRC-21, R-WRT-48). `MQ3044` for a malformed generated join is `processor/32` AC-PROC-46; a non-unique source is not a compile-time error, only `MQ1910` at build time (D-190, narrowing RFC 0020's compile-time check). |
| AC-SRC-46 | `withLock(...)` on a query that joins a source is `MQ1903` before any statement (R-SRC-11). `MQ3039` for a child model that joins a source (`processor/32` AC-PROC-46, R-PROC-44). |
| AC-SRC-47 | A profile without `SourceFeature.CTE` is `MQ4015` before any statement, and `PortabilityCheck` reports `MQ4015` on an `OTHER` target alone (R-SRC-11). |
| AC-SRC-48 | Per vendor and Hibernate line, a SQL snapshot of a `LEFT`-joined tree: `with recursive` on H2 too, the walk filter in the join's `on`, the depth, cycle and duplicate-key sub-selects in the selection, and every outer row kept (R-SRC-22). |
| AC-SRC-49 | Through a joined tree, `MQ2701`, `MQ2702` and `MQ2703` name the join on content, count, count over groups, offset, primary-key-first and keyset pages, both `one` calls, `byKeys`, every export, a stream, a fetch plan's parent read and a `through` child load; an unreached `LEFT` row still raises, an empty `INNER` read returns empty; `MQ2703` precedes `MQ2003`, `MQ2005` and `MQ2202`; `Agg.count` over the `LEFT` join is `0` for an unreached node (R-SRC-14, R-SRC-15, R-SRC-22). |
| AC-SRC-50 | A grouped source over a `recursive(...)` walk, joined, aggregates levels 0 to `maxDepth` with its filter in the grouped body and raises `MQ2701` and `MQ2702`; a second walk is `MQ1910`; a joined tree over a composite node key is `MQ4015` on H2 and read on PostgreSQL and MySQL, and `PortabilityCheck` reports it and MySQL's `maxDepth` 999 ceiling per target (R-SRC-11, R-SRC-18, R-SRC-21). |
| AC-SRC-51 | A hand-written `on(...)` pair whose source key field has a converter, or whose outer column and key differ in boxed attribute type (a converted outer column, `Long` against `Integer`), is `MQ1910` before any statement; `int` against `Integer` is accepted (R-SRC-21, D-191). |
| AC-SRC-52 | `count` and a counted page of a grouped query that joins a source return the number of groups on every vendor and Hibernate line, counted client-side with no warning (R-SRC-22, `engine/20` R-EXE-03, D-192). |
| AC-SRC-53 | On Tier 1, a LEFT entity join matches native SQL; unmatched source and entity rows yield null joined columns; two names joining the same entity remain independent; to-one paths below the entity retain their declared join kind (R-SRC-23). The processor-generated form is covered by `GeneratedEntityJoinExecutionTest.ac_src_53_ac_proc_48_generated_left_entity_joins_match_native_sql`. |
| AC-SRC-54 | Entity joins, including unused ones, occur after their anchor source join in every R-SRC-22 statement, including fetch-parent reads and through-child loads (R-SRC-24). |
| AC-SRC-55 | INNER entity joins drop missing entity and LEFT-source rows in content and totals; nullable joined keyset ordering with `nullsLast` loses no row under the existing cursor rules; an unmatched LEFT entity count is 0 (R-SRC-23, R-SRC-24). |
| AC-SRC-56 | Hand-written forms raise `MQ1911` at build or first resolution as R-SRC-23 specifies, and an unregistered joined-entity column is `MQ1003`; an entity class the render options' metamodel rejects as unmapped is `MQ1911` (without a metamodel the renderer or provider decides). **M33.2 covered by `EntityJoinChecksTest.ac_src_56_*` and `JoinEntityModelTest.ac_proc_48_*` (AC-PROC-48):** processor `MQ3046` cases: partial or extra id pair, non-id or association id, converted field, boxed-type mismatch, unknown join or field, `source` not a joined source, to-many path, nested `@Join`, joined-column `@PrimaryKey`, `@SourceModel`, duplicate name shared with source joins, unknown `entityJoin` member, `treat` combined with `entityJoin`, and a `source` that names no `@JoinSource`, once per join. Joined-entity aggregate MQ3046 cases are covered by AC-PROC-50: unknown `entityJoin` and nonempty `entityJoin` combined with an expression (R-PROC-47, D-202). |
| AC-SRC-57 | Lock `MQ1903` and child-model `MQ3039` remain unchanged; entity joins to `@IdClass` and `@EmbeddedId` targets match every id attribute (R-SRC-23, R-SRC-24). |
| AC-SRC-58 | Missing `entityJoins()` is `MQ4014` before SQL, after vendor `MQ4015` and SourceRenderer `MQ4014`, reported per PortabilityCheck target. LEFT entity and descendant counts use the first id attribute on both Hibernate lines, including composite ids; eligible INNER entity joins use `count(*)` (R-AGG-19, AC-AGG-31); `on` is called once per entity join, and a renderer breaking that is `MQ4014` (AC-VND-22); `firstBy` returns one value per group (R-SRC-24, R-AGG-19, R-VND-32). |
| AC-SRC-59 | A `LEFT` entity join anchored on a root column, and one on a to-one path's column, match native SQL on Tier 1 and both Hibernate lines; orphan anchors read NULL and keep the row; two joins of one entity stay apart; a non-id anchor on a to-one path renders that path's join before the entity join, whose `ON` reads it; a vendor without `SourceFeature.CTE` renders it (R-SRC-25, D-197). |
| AC-SRC-60 | An `INNER` column-anchored join drops unmatched rows, including rows whose `LEFT` anchor path is unmatched, in content and totals; it and an unread `LEFT` join are in every R-SRC-24 statement (list, count, counted page, primary-key-first, keyset pages, `byKeys`, `one`, export, stream); keyset over a nullable joined column with `nullsLast` loses no row; `count` and a counted page of a grouped query with an `INNER` column-anchored join return the group count from the provider's count copy, which keeps the join (R-SRC-25, D-197). |
| AC-SRC-61 | `MQ1911` at build for mixed anchor kinds, chains, treated, converted, lifted and source anchors and a source-rooted query, and at first resolution for a to-many or id-form anchor and the id rules, before SQL; `MQ1912` for an `INNER` join anchored through a `joinFilter` join; `MQ2211` for a lock; `MQ1604`, `MQ1801` and `MQ1003` for the writes, and `MQ1809` for a `persist` returning a query with `joinEntity(...)`, naming the clause, before any statement; a hand-written `through` child load keeps the join and returns the rows its anchors match (R-SRC-25). |
| AC-SRC-62 | `when` turns the matches of other owner types into NULL columns, as the native `ON ... and o.status = 'PAID'` does; it binds as `?` with the same SQL on each run; a second term, a converted or off-root column and a null, mistyped or unbindable value are `MQ1911` (R-SRC-25). |
