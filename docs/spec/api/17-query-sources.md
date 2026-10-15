# 17 — Query Sources

**Covers:** `@SourceModel`, `SourceQuery` and its `Builder`, `TableField.source`, `ColumnField.over`, and the outer
`@QueryModel` that reads a source.
**Read when:** declaring a source model, reading one from an outer model, or changing what the processor generates for
either. Windows, set operations and recursion are RFC 0013 §3–§6 and later releases.
**Owns:** `R-SRC-*`, `AC-SRC-*`. Rows `R-SRC-04`..`R-SRC-09` and `R-SRC-11`..`R-SRC-12` land with the slices that
implement them (M25.3–M25.5); the run-time half of `R-SRC-03` lands with M25.2.

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
  is `MQ1906`, raised at the source's `build()` and pointing at `Filters.exists(path, inner)`, whose correlation
  R-FLT-11 and R-FLT-12 already define. The predicate is not rewritten silently: two conditions on one joined line
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
  rooted at a source, a `@Child`'s parent-side key names a source field.

- **The generated `query(SourceQuery<S>)` takes the source**, so an outer query without one does not compile:

```java
public static ModelQuery.Builder<OrderTotals, Long, CustomerTotals> query(SourceQuery<OrderTotals> source) {
    return ModelQuery.builder(ROOT, MAPPER, source).primaryKey(KEY);
}
```

A grouped outer model over a source follows `api/13` as an entity-rooted one does; an ungrouped outer model over a
grouped source is the way to page groups by keyset.

## 4. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-SRC-01 | A plain source model reads the rows its hand-written SQL reads, on every Tier-1 vendor and both Hibernate lines (`Future`, M25.2; R-SRC-01). |
| AC-SRC-02 | A grouped source model with a `where` and a `having` builds the CTE its hand-written SQL builds, pinned as an SQL snapshot per vendor (`Future`, M25.2; R-SRC-01). |
| AC-SRC-03 | A keyset export over a grouped source visits every group exactly once (`Future`, M25.2; R-SRC-02, INV-4). |
| AC-SRC-04 | A source whose `where` crosses a to-many join is `MQ1906`, and the `Filters.exists` form of the same predicate returns the rows the hand-written SQL returns (`Future`, M25.2; R-SRC-03). |
| AC-SRC-05 | Every window function reads the hand-written SQL's values, and the SQL is pinned per vendor (`Future`, M25.3; R-SRC-04). |
| AC-SRC-06 | A top-N source paged by keyset and by primary-key-first over tied timestamps loses and repeats no row (`Future`, M25.3; R-SRC-04). |
| AC-SRC-07 | Each set operation renders the SQL it is written as, pinned per vendor (`Future`, M25.4; R-SRC-05, R-SRC-06). |
| AC-SRC-08 | A discriminated feed is exported by keyset without a repeated key (`Future`, M25.4; R-SRC-05). |
| AC-SRC-09 | An "owned `union` shared" list read from one branch model is paged by keyset (`Future`, M25.4; R-SRC-06). |
| AC-SRC-10 | Offset pages over a non-unique `unionAll` with duplicate keys and tied order values lose no row (`Future`, M25.4; R-SRC-09). |
| AC-SRC-11 | Each `MQ1901` and `MQ2212` refusal is raised before any statement (`Future`, M25.4; R-SRC-09). |
| AC-SRC-12 | A tree read by a recursive source is pinned as an SQL snapshot and returns every node (`Future`, M25.5; R-SRC-07). |
| AC-SRC-13 | A tree deeper than `maxDepth` is `MQ2701` and a cyclic graph is `MQ2702`, with no marked row in the result (`Future`, M25.5; R-SRC-08). |
| AC-SRC-14 | MySQL serves `maxDepth` 998 and refuses 999 with `MQ4015` (`Future`, M25.5; R-SRC-08). |
| AC-SRC-15 | Content, count and both primary-key-first steps of one source are registered on every statement (`Future`, M25.2; R-SRC-12). |
| AC-SRC-16 | `MQ4014` and `MQ4015` are reported per declared target without a database (`Future`, M25.2; R-SRC-11). |
