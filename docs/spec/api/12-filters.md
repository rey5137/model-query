# 12 — Filters DSL

**Covers:** every `Filters` operator, the `Optional` skip contract, `or`/`not`/`when`/`apply`, `exists`, `like`
escaping, and `IN`-list splitting.
**Read when:** adding an operator, or explaining why a filter did or did not narrow a query.
**Owns:** `R-FLT-*`, `AC-FLT-*`. Aggregate conditions are `api/13` (`having`).

---

## 1. Shape

`where(UnaryOperator<Filters<M>>)` receives a builder that ANDs every filter added to it. Every method that takes a
value has two overloads:

- `(column, C value)` — the filter always applies; `null` throws `MQ1301`, because "no filter" must be explicit (P-3).
  A predicate from `add(...)` that returns `null` throws `MQ1305` (D-24). A `Filters` used outside its own operator,
  or while a nested operator runs, throws `MQ1303` (D-23).
- `(column, Optional<? extends C> value)` — `Optional.empty()` skips the filter, and no join is created for it.

Only the value-form signatures are listed below; each has an `Optional` twin.

```java
public interface Filters<M> {
    // Equality and comparison
    <C> Filters<M> eq(ColumnField<M, ?, C> column, C value);
    <C> Filters<M> ne(ColumnField<M, ?, C> column, C value);                  // NULL rows match, see R-FLT-04
    <C extends Comparable<? super C>> Filters<M> gt (ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> lt (ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> range(ColumnField<M, ?, C> column,
                                                       Optional<? extends C> fromInclusive,
                                                       Optional<? extends C> toExclusive);
    <C extends Comparable<? super C>> Filters<M> between(ColumnField<M, ?, C> column, C fromInclusive, C toInclusive);

    // Sets
    <C> Filters<M> in(ColumnField<M, ?, C> column, Collection<? extends C> values);
    <C> Filters<M> notIn(ColumnField<M, ?, C> column, Collection<? extends C> values);

    // Strings
    Filters<M> like(ColumnField<M, ?, String> column, String value, LikeMode mode);   // EXACT | CONTAINS | STARTS_WITH | ENDS_WITH
    Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, String value, LikeMode mode);
    Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, String value);

    // Nulls
    Filters<M> isNull(ColumnField<M, ?, ?> column);
    Filters<M> isNotNull(ColumnField<M, ?, ?> column);
    Filters<M> isNull(ColumnField<M, ?, ?> column, Optional<Boolean> isNull);         // tri-state request flag

    // Column against column
    <C> Filters<M> compare(ColumnField<M, ?, C> left, Op op, ColumnField<M, ?, C> right);   // EQ, NE, LT, LTE, GT, GTE

    // Composition
    Filters<M> or(UnaryOperator<Filters<M>>... branches);    // each branch is an AND group
    Filters<M> not(UnaryOperator<Filters<M>> group);
    Filters<M> when(boolean condition, UnaryOperator<Filters<M>> group);
    Filters<M> apply(UnaryOperator<Filters<M>> fragment);    // reuse a shared fragment, e.g. NOT_DELETED

    // Correlated sub-queries
    Filters<M> exists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner);
    Filters<M> exists(TableField<?, ?> path);                 // "has at least one"
    Filters<M> notExists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner);

    // Escape hatch
    Filters<M> add(BiFunction<JoinContext, CriteriaBuilder, Predicate> custom);    // D-24
}
```

```java
.where(f -> f
        .eq(QOrderView.STATUS, status)                                   // Optional<String>
        .range(QOrderView.CREATED_AT, from, to)
        .or(g -> g.likeIgnoreCase(QOrderView.CUSTOMER_NAME, q, CONTAINS),
            g -> g.eq(QOrderView.CUSTOMER_EMAIL, q))
        .exists(QOrderView.ITEMS_TABLE, i -> i.eq(QOrderView.ITEM_SKU, sku).gt(QOrderView.ITEM_QTY, 0))
        .when(!includeCancelled, g -> g.ne(QOrderView.STATUS, "CANCELLED")))
```

## 2. Skipping

**R-FLT-01** **Skipping is local.** A skipped filter disappears from its group. An `or(...)` branch whose filters were
all skipped is dropped. If every branch was dropped, the whole `or` is skipped rather than becoming `FALSE` and
matching nothing. The same holds for `not`, and for `exists` with an inner group: when all its inner filters were
skipped, the `exists` is skipped too. Use `exists(path)` to ask for "has at least one" explicitly.

**R-FLT-02** **Empty collections are not "skip".** `in(col, List.of())` renders `FALSE` and `notIn(col, List.of())`
renders nothing, because an empty selection in a UI means "none of these". `Optional.empty()` is the way to skip
(P-3).

**R-FLT-03** A skipped filter creates no join. A filter-only column whose filter was skipped must not appear in the
rendered SQL at all (`api/10` AC-COL-07).

## 3. Negation and NULLs

**R-FLT-04** `ne` and `notIn` over a nullable column render `col <> ? OR col IS NULL`, which is what a report filter
means by "not X"; plain SQL `<>` drops NULL rows silently. For the strict SQL meaning, combine with `isNotNull(col)`.

**R-FLT-05** `not(group)` is plain SQL `NOT (…)`, because three-valued logic over an arbitrary group cannot be
rewritten portably. The Javadoc states that rows where the group is UNKNOWN are excluded.

## 4. Strings

**R-FLT-06** `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` escape `%`, `_` and the escape character in the value and render
`ESCAPE '\'`, so a search for `50%` finds that literal text. `EXACT` passes the pattern through as given.

**R-FLT-07** `likeIgnoreCase` renders `lower(col) LIKE ?`; the value is lower-cased in Java with `Locale.ROOT` and
bound (R-FLT-08, D-22). A database whose `lower()` folds only ASCII, such as PostgreSQL with C collation, can disagree
on non-ASCII text. The user guide notes that this needs a functional index to be fast, and that case sensitivity
otherwise follows collation (`vendor/40` §4).

## 5. Bind parameters and large sets

**R-FLT-08** Every value is a bind parameter. The engine never inlines a value into SQL, for any operator.

**R-FLT-09** Lists longer than `VendorProfile.maxInListSize()` render as `col IN (…) OR col IN (…)`, and `notIn` as an
AND of `NOT IN` chunks. Chunking also respects `maxBindParameters()` (`vendor/41`).

## 6. Joins created inside `or` and `not`

**R-FLT-10** *(was R14)* A join first needed inside `or` or `not` is resolved as LEFT. An INNER join created for one
branch would remove rows another branch should match. If the same path is already joined as INNER elsewhere in the
query, that join is reused, because those rows are already required.

## 7. `exists`

**R-FLT-11** `exists(ITEMS_TABLE, inner)` renders `EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id AND
<inner>)`. Columns inside `inner` must sit on `path` or below it and are re-rooted to the sub-query; any other column
throws `MQ1302` naming the column. Aliased paths and `on(...)` conditions are carried into the sub-query. `path` must
be a join, else `MQ1304`. A nested `exists` correlates to the enclosing `exists` path: on a path below it, to rows of
that child; on exactly the same path, to the **same child row**, so its filters narrow the row the enclosing `exists`
found rather than asking for another row of that path.

**R-FLT-12** Because `exists` joins nothing on the outer query, `count` and export need no distinct or dedupe work
(`engine/20` R-EXE-04). It is the preferred form for "has a child matching X".

## 8. Scope

**R-FLT-13** *(was R15)* Filter values never change a query's meaning by accident: empty `Optional` skips; an empty
collection means "none"; negation includes NULLs; `like` input is escaped; long `IN` lists are split.

**R-FLT-14** `Filters` builds `WHERE` predicates only. Conditions on aggregates go through `having(...)`
(`api/13` §3), never through `Filters` and never through `QueryCustomizer`.

## 9. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-FLT-01 | Every operator behaves identically in value and `Optional` form on every Tier-1 vendor. |
| AC-FLT-02 | A value-form filter with `null` throws `MQ1301` naming the column; an `add(...)` predicate returning `null` throws `MQ1305` (§1). |
| AC-FLT-03 | An `or` whose every branch was skipped disappears; the query returns the same rows as one without it (R-FLT-01). |
| AC-FLT-04 | `exists(path, inner)` with every inner filter skipped is skipped; `exists(path)` still renders (R-FLT-01). |
| AC-FLT-05 | `in(col, List.of())` returns no rows; `notIn(col, List.of())` returns every row (R-FLT-02). |
| AC-FLT-06 | `ne` and `notIn` include rows where the column is NULL (R-FLT-04). |
| AC-FLT-07 | `like` with `%`, `_` and `\` in the value matches those characters literally (R-FLT-06). |
| AC-FLT-08 | An `IN` list above the vendor limit is split and returns the same rows as an unsplit one (R-FLT-09). |
| AC-FLT-09 | An `or` branch over a LEFT-joined column keeps rows that have no joined row (R-FLT-10). |
| AC-FLT-10 | A column outside the `exists` path throws `MQ1302`; a root as the path throws `MQ1304`; an aliased path works inside `exists`; a nested `exists` on the same path tests the same child row (R-FLT-11). |
| AC-FLT-11 | `count` over a query using `exists` equals `count` over the equivalent join query with distinct (R-FLT-12). |
