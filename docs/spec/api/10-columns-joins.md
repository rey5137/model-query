# 10 — Columns, Joins and Row Mapping

**Covers:** `TableField`, `SelectField`, `ColumnField`, `ColumnSet`, `OrderField`, `Row`, `RowMapper`, how joins are
shared within one query, and how null precedence is rendered portably.
**Read when:** defining the columns of a model, adding a join, deciding why two joins appeared, or changing how a row
becomes a model.
**Owns:** `R-COL-*`, `AC-COL-*`. Aggregate selections are `api/13`; filters are `api/12`.

---

## 1. `TableField` — a node in a join path

```java
public final class TableField<P, T> {
    public static <T> TableField<T, T> root(Class<T> entity);
    public static <P, T> TableField<P, T> join(TableField<?, P> parent, String attribute, JoinType type);
    public TableField<P, T> as(String alias);                              // a separate join to the same path
    public TableField<P, T> on(BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition);   // requires as(...)
    public TableField<P, T> withParent(TableField<?, P> newParent);        // keeps alias and ON condition
    public From<?, T> resolve(JoinContext ctx);                            // for custom predicates and customizers
}
```

**R-COL-01** A `TableField` is a definition, not a join. It resolves to a Criteria `From` only through a
`JoinContext`, which is created per query build with `JoinContext.of(root, cb)` (INV-9, D-18). The same constant may
be used by any number of concurrent queries.

**R-COL-02** `JoinContext` caches joins by **join key** — the parent's join key, the attribute, the `JoinType` and the
alias (empty by default) — never by object identity. Two separate `TableField.join(ROOT, "customer", LEFT)` calls
therefore produce one join.

**R-COL-03** The join-sharing rules:

- Same path, same type, no alias → one join, whether the column is selected, filtered or ordered on.
- Different attributes are different paths even when they reach the same table (`billingAddress` and `shippingAddress`
  → two joins to `address`).
- `as("x")` gives the same path its own join. Children of an aliased join are separate too
  (`ITEMS.as("b") → product` is not `ITEMS → product`).
- `on(...)` adds its condition through JPA's `Join#on`, which preserves LEFT-join semantics that a `WHERE` predicate
  would break.

**R-COL-04** A `TableField` carrying an `on(...)` condition must have an alias, because a lambda cannot be compared.
Two `TableField`s with the same join key and different condition instances throw `MQ1101` when the query is built.

**R-COL-05** *(was R13)* **One path, one join.** Selecting, filtering and ordering on the same path uses exactly one
join. A second join to the same path requires `as(...)`.

```java
TableField<OrderEntity, OrderItemEntity> ITEMS   = TableField.join(ROOT, "items", INNER);
TableField<OrderEntity, OrderItemEntity> ITEMS_B = TableField.join(ROOT, "items", INNER).as("itemB");
TableField<OrderEntity, AddressEntity>   BILLING = TableField.join(CUSTOMER_TABLE, "addresses", LEFT)
        .as("billing").on((a, cb) -> cb.equal(a.get("type"), "BILLING"));
```

Two joins to `order_items` express "has item A **and** has item B". For "has an item matching X", prefer
`Filters.exists` (`api/12` §6): it does not multiply rows, so `count` and export need no distinct or dedupe work.

## 2. `SelectField` — anything selectable

```java
public sealed interface SelectField<M, C> permits ColumnField, AggregateField {
    Class<C> type();
    String name();                                  // used in MQ messages
    Expression<C> expression(JoinContext ctx);      // ColumnField returns path(ctx)
    OrderField<M, C> asc();
    OrderField<M, C> desc();
}
```

**R-COL-06** Everything that selects, orders or reads a value accepts a `SelectField`: `ColumnSet`, `orderBy`,
`Row.get`. Everything that builds a `WHERE` predicate accepts a `ColumnField` only, which is what makes an aggregate in
`where` a compile error rather than a runtime failure (P-2, `api/13` R-AGG-05).

## 3. `ColumnField` — one column of a model

```java
public final class ColumnField<M, T, C> implements SelectField<M, C> {
    public static <M, T, C> ColumnField<M, T, C> of(Class<M> model, TableField<?, T> table, String attribute, Class<C> type);
    public Path<C> path(JoinContext ctx);
    public <M2> ColumnField<M2, T, C> withTable(Class<M2> model, TableField<?, T> table);   // re-root under a join
}
```

**R-COL-07** A column does not have to be mapped. A **filter-only column** has no field on the model; the mapper only
reads the columns it knows, so a filter-only column never affects the result even if it ends up in a `ColumnSet`.

**R-COL-08** *(was R11)* **Column types match entity attributes.** Generated columns are checked by the processor
(`processor/32`). Hand-written columns are checked at first path resolution — `path.getJavaType()` against
`ColumnField.type`, through a converter allow-list — and throw `MQ1001` on a mismatch (INV-3).

## 4. `ColumnSet` — an immutable named set

```java
public final class ColumnSet<M> {
    @SafeVarargs public static <M> ColumnSet<M> of(SelectField<M, ?>... columns);
    public ColumnSet<M> with(SelectField<M, ?>... extra);   // returns a copy
    public ColumnSet<M> with(ColumnSet<M> other);
    public ColumnSet<M> without(SelectField<M, ?>... columns);
    public List<SelectField<M, ?>> columns();               // unmodifiable
}
```

**R-COL-09** A `ColumnSet` is immutable. `with` and `without` return copies, so a shared constant cannot be changed by
one caller and affect every later query (INV-9).

## 5. `Row` and `RowMapper`

```java
public interface Row {
    <C> C get(SelectField<?, C> column);              // null when the column is NULL or wasn't selected
    boolean isSelected(SelectField<?, ?> column);
    Row scoped(TableField<?, ?> join);                // view of a nested model's columns under a join
}

@FunctionalInterface
public interface RowMapper<M> {
    M map(Row row);
    static <M> SetterMapper<M> setters(Supplier<M> factory);    // .bind(ID, OrderView::setId).bind(...)
}
```

**R-COL-10** Queries select a JPA `Tuple`. The engine wraps it as a `Row` keyed by `SelectField` and hands it to the
query's `RowMapper`. Values are never read by tuple index at any layer above the wrapper, so inserting a selection
cannot shift a mapping (`guide/70`).

**R-COL-11** Because a model is built from a complete row in one call, classes and records use the same engine.
Primary keys, keyset cursors and export dedupe read from the `Row` before mapping, so a model needs no key accessor and
no base class.

A hand-written record model passes a lambda: `row -> new OrderView(row.get(ID), row.get(STATUS), …)`. Generated
mappers are `processor/31`.

## 6. `OrderField` and null precedence

```java
public record OrderField<M, C>(SelectField<M, C> column, boolean ascending, NullPrecedence nulls) {
    public OrderField<M, C> nullsFirst();
    public OrderField<M, C> nullsLast();
    public OrderField<M, C> nulls(NullPrecedence nulls);   // for a value chosen at runtime
}

public enum NullPrecedence { DEFAULT, FIRST, LAST }        // DEFAULT = whatever the database does
```

**R-COL-12** `asc()` and `desc()` start at `NullPrecedence.DEFAULT`, which renders no null clause. `nullsFirst()` and
`nullsLast()` mean the same thing on every vendor regardless of that vendor's default:

- With `model-query-hibernate` present, the order is rendered through
  `HibernateCriteriaBuilder#sort(expr, direction, nullPrecedence)`. Hibernate emits `NULLS FIRST/LAST` where the
  dialect supports it (H2, PostgreSQL) and emulates it where it does not (MySQL).
- Without it — plain JPA 3.1 has no null precedence in `Order` — the engine prepends a sort key
  `CASE WHEN col IS NULL THEN 0 ELSE 1 END` (ASC for `FIRST`, DESC for `LAST`). Portable, but it prevents an index on
  `col` from serving the sort.
- If the `VendorProfile` reports that the vendor's default already matches the requested precedence for that
  direction, nothing extra is rendered.

**R-COL-13** Keyset paging on a nullable column with `DEFAULT` precedence uses
`VendorProfile.defaultAscendingNullOrdering()`, and is refused when that is `UNKNOWN` (`engine/21` R-PAG-05).

## 7. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-COL-01 | Selecting, filtering and ordering on one path renders exactly one join; `as(...)` renders two; children of an aliased join stay separate (R-COL-03, R-COL-05). |
| AC-COL-02 | `on(...)` without an alias throws; two `TableField`s with the same key and different conditions throw `MQ1101` (R-COL-04). |
| AC-COL-03 | `on(...)` on a LEFT join keeps rows with no matching child (R-COL-03). |
| AC-COL-04 | A hand-written `ColumnField` whose type does not match the entity attribute throws `MQ1001` at first resolution (R-COL-08). |
| AC-COL-05 | `ColumnSet.with`/`without` leave the original set unchanged (R-COL-09). |
| AC-COL-06 | Every `ColumnField` type round-trips through `Row.get`, including converters, for a class model and an equivalent record model (R-COL-10). |
| AC-COL-07 | A filter-only column filters correctly, adds no join when its filter is skipped, and never reaches the model (R-COL-07). |
| AC-COL-08 | `nullsFirst()`/`nullsLast()` produce identical orderings on every Tier-1 vendor, with and without `model-query-hibernate` (R-COL-12). |
| AC-COL-09 | A `ModelQuery` stored in a `static final` field is used concurrently by 8 threads with identical results (INV-9). |
