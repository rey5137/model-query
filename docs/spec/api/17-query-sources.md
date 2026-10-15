# 17 — Query Sources

**Covers:** `@SourceModel`, `SourceQuery` and its `Builder`, `TableField.source`, `ColumnField.over`, and the outer
`@QueryModel` that reads a source.
**Read when:** declaring a source model, reading one from an outer model, or changing what the processor generates for
either. Windows, set operations and recursion are RFC 0013 §3–§6 and later releases.
**Owns:** `R-SRC-*`, `AC-SRC-*`. Rows `R-SRC-05`..`R-SRC-09` land with the slices that implement them
(M25.4–M25.5); `R-SRC-04` lands with M25.3; the run-time half of `R-SRC-03` and all of `R-SRC-11`..`R-SRC-12` land with
M25.2.

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
statement, so a `@Child` on it is `MQ3030`. `shape` is reserved for a later release (R-PROC-38) and nothing acts on it
yet.

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
unique by construction, or the source is non-unique and refused for those features (R-SRC-09, a later release).

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
        public Builder<E, M, S> where(UnaryOperator<Filters<M>> filters);
        public Builder<E, M, S> having(UnaryOperator<Having<M>> filters);
        public SourceQuery<S> build();
    }
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
  it is built; the builder adds only `where` and `having`. It holds no Criteria object and no per-query state, so one
  instance can be a `static final` constant shared by any number of threads (INV-9). A later release adds the set
  operations, the recursion and the shape (R-PROC-37, R-PROC-38); `having` on an ungrouped source is `MQ1407`, as it
  is on `ModelQuery`.

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

## 4. Window fields

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
- **The provider.** The engine reaches the provider only through `SourceSupport#window(cb, spec)`, the second hook
  beside `cte` (R-SRC-12), so no provider type leaks into `jpa` (INV-7). A vendor whose profile answers `false` for
  `SourceFeature.WINDOW` is `MQ4015`, before any statement (R-SRC-11).

## 5. Building the CTE, and what a source needs to run

**R-SRC-11** What a statement over a source needs, all checked before any statement:

- **The provider's support.** `ProviderSupport#sources()` returns the factory's `SourceSupport`. Empty — the default,
  and the only answer where the factory's provider has no `ProviderSupport` at all — is `MQ4014`, before any statement,
  the same fail-safe as `ProviderSupport#inserts()` (R-VND-14). `model-query-hibernate` serves Hibernate on both lines.
- **The vendor's features.** `VendorProfile#sources(SourceFeature)` answers for each feature the source uses, `false`
  by default so a profile written before a constant was added fails safe. A `false` is `MQ4015`, before any statement,
  naming the feature and the vendor. The refusals are database facts (R-VND-11, INV-6); `PortabilityCheck` reports
  `MQ4014` and `MQ4015` per declared target, with no database (AC-SRC-16).
- **A row lock.** `withLock(...)` over a source is `MQ1903`: the source's rows come from a common table expression,
  which a locking clause cannot follow. Q-16 stays open.

**R-SRC-12** The engine (`core`) builds the source's body, as a standard `CriteriaQuery<Tuple>` whose elements are the
source's fields, aliased by field name, filtered by its `where` and `having` and grouped by its `groupKeys`
(R-SRC-03, D-143). It reaches the provider only through `SourceSupport#cte(outer, body)`, which registers the body as
`outer`'s CTE and returns the root the outer statement reads by those names, so no provider type leaks into `jpa`
(INV-7). The CTE is registered on **every** statement built over the source: the content query, the count, both
primary-key-first steps, each export page, `byKeys` (every chunk) and the parent reads of a fetch plan. A statement
that reaches the database without its CTE is therefore a bug in the engine, not a state the user can hold: the body is
built per statement from the immutable `SourceQuery`, which itself carries no Criteria object (INV-9).

## 6. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-SRC-01 | A plain source model reads the rows its hand-written SQL reads, on every Tier-1 vendor and both Hibernate lines (R-SRC-01). |
| AC-SRC-02 | A grouped source model with a `where` and a `having` builds the CTE its hand-written SQL builds, pinned as an SQL snapshot per vendor (R-SRC-01). |
| AC-SRC-03 | A keyset export over a grouped source visits every group exactly once (R-SRC-02, INV-4). |
| AC-SRC-04 | A source whose `where` crosses a to-many join is `MQ1906`, and the `Filters.exists` form of the same predicate returns the rows the hand-written SQL returns (R-SRC-03). |
| AC-SRC-05 | Every window function reads the hand-written SQL's values, and the SQL is pinned per vendor (R-SRC-04). |
| AC-SRC-06 | A top-N source paged by keyset and by primary-key-first over tied timestamps loses and repeats no row (R-SRC-04). |
| AC-SRC-07 | Each set operation renders the SQL it is written as, pinned per vendor (`Future`, M25.4; R-SRC-05, R-SRC-06). |
| AC-SRC-08 | A discriminated feed is exported by keyset without a repeated key (`Future`, M25.4; R-SRC-05). |
| AC-SRC-09 | An "owned `union` shared" list read from one branch model is paged by keyset (`Future`, M25.4; R-SRC-06). |
| AC-SRC-10 | Offset pages over a non-unique `unionAll` with duplicate keys and tied order values lose no row (`Future`, M25.4; R-SRC-09). |
| AC-SRC-11 | Each `MQ1901` and `MQ2212` refusal is raised before any statement (`Future`, M25.4; R-SRC-09). |
| AC-SRC-12 | A tree read by a recursive source is pinned as an SQL snapshot and returns every node (`Future`, M25.5; R-SRC-07). |
| AC-SRC-13 | A tree deeper than `maxDepth` is `MQ2701` and a cyclic graph is `MQ2702`, with no marked row in the result (`Future`, M25.5; R-SRC-08). |
| AC-SRC-14 | MySQL serves `maxDepth` 998 and refuses 999 with `MQ4015` (`Future`, M25.5; R-SRC-08). |
| AC-SRC-15 | Content, count and both primary-key-first steps of one source are registered on every statement (R-SRC-12). |
| AC-SRC-16 | `MQ4014` and `MQ4015` are reported per declared target without a database (R-SRC-11). |
