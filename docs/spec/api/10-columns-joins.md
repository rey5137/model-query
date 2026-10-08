# 10 — Columns, Joins and Row Mapping

**Covers:** `TableField`, `SelectField`, `ScalarField`, `ColumnField`, `ExpressionField`, `Expr`, `SelectSet`, `OrderField`, `Row`, `RowMapper`, how joins are
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
    public TableField<P, T> withParent(TableField<?, P> newParent);        // keeps alias, ON condition and presence key
    public TableField<P, T> presentBy(PrimaryKey<?, ?> key);               // the key that tells a match from a miss
    public From<?, T> resolve(JoinContext ctx);                            // for custom predicates and customizers
}
```

**R-COL-01** A `TableField` is a definition, not a join. It resolves to a Criteria `From` only through a
`JoinContext`, which is created per query build with `JoinContext.of(root, cb)` (INV-9, D-18). The same constant may
be used by any number of concurrent queries. A context carries the build's `RenderOptions`: `JoinContext.of` uses
`RenderOptions.portable()`, and `ModelQuery.buildQuery(cb, phase, options)` the executor's (D-34).

**R-COL-02** `JoinContext` caches joins by **join key** — the parent's join key, the attribute, the `JoinType` and the
alias (empty by default) — never by object identity. Two separate `TableField.join(ROOT, "customer", LEFT)` calls
therefore produce one join.

**R-COL-15** **A join may carry a presence key.** `presentBy(key)` names the primary key whose non-null value means
the join matched a row. It is kept by `as`, `on` and `withParent`, is not part of the join key, and throws `MQ1104` on a
root. The engine selects it with any column of the join (`api/11` R-QRY-04), and a nested model's mapper reads it to
tell a LEFT-join miss from a match whose other columns are `NULL` (`processor/31` R-GEN-13, D-38).

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
public sealed interface SelectField<M, C> permits ScalarField, AggregateField {
    Class<C> type();
    String name();                                  // used in MQ messages
    Expression<C> expression(JoinContext ctx);      // ColumnField returns path(ctx)
    OrderField<M, C> asc();
    OrderField<M, C> desc();
}

public sealed interface ScalarField<M, C> extends SelectField<M, C> permits ColumnField, ExpressionField {}
```

**R-COL-06** Everything that selects, orders or reads a value accepts a `SelectField`: `SelectSet`, `orderBy`,
`Row.get`. Everything that builds a `WHERE` predicate or a group key, and every `Expr` argument, accepts a
`ScalarField`: a `ColumnField` or an `ExpressionField`. An aggregate in `where`, in `groupBy` or inside an expression is
therefore a compile error rather than a runtime failure (P-2, `api/13` R-AGG-05).

**R-COL-16** *(D-115)* **`ScalarField` is one value per row.** `SelectField` permits `ScalarField` and
`AggregateField`, and `ScalarField` permits `ColumnField` and `ExpressionField`. A `switch` over `SelectField` names
three cases.

## 3. `ColumnField` — one column of a model

```java
public sealed class ColumnField<M, T, C> implements SelectField<M, C> permits OrderedColumnField {
    public static <M, T, C> OrderedColumnField<M, T, C> of(Class<M> model, TableField<?, T> table, String attribute,
            Class<C> type);
    public static <M, T, C, F> ColumnField<M, T, C> of(Class<M> model, TableField<?, T> table, String attribute,
            Class<C> type, Class<F> attributeType, ColumnConverter<C, F> converter);
    public static <M, T, C, F> OrderedColumnField<M, T, C> of(Class<M> model, TableField<?, T> table, String attribute,
            Class<C> type, Class<F> attributeType, OrderedColumnConverter<C, F> converter);
    public Path<C> path(JoinContext ctx);
    public <M2> ColumnField<M2, T, C> withTable(Class<M2> model, TableField<?, T> table);   // re-root under a join
}

public final class OrderedColumnField<M, T, C> extends ColumnField<M, T, C> { /* withTable returns OrderedColumnField */ }
```

`OrderedColumnField` is a column with no converter or with an `OrderedColumnConverter`; any other converter gives a
plain `ColumnField`. `ColumnField.of` returns the narrower type whenever it can, also at run time when the converter's
static type is wider; `equals` ignores the subclass (D-93).

**R-COL-07** A column does not have to be mapped. A **filter-only column** has no field on the model; the mapper only
reads the columns it knows, so a filter-only column never affects the result even if it ends up in a `SelectSet`.

**R-COL-08** *(was R11)* **Column types match entity attributes.** Generated columns are checked by the processor
(`processor/32`). Hand-written columns are checked at first path resolution — `path.getJavaType()` against
`ColumnField.type`, through a converter allow-list (D-20) — and throw `MQ1001` on a mismatch (INV-3). The same
resolution throws `MQ1002` for an attribute, of a column or a join, that the entity does not have, and `MQ1003` for a
column whose path starts at a root the query is not rooted at, which would otherwise read the query root's attribute of
the same name. An attribute may be a dotted path through embedded values (`"address.city"`); a segment that is unknown
or crosses an association throws `MQ1002` naming it (D-41).

**R-COL-14** **A converted column.** A column built with a `ColumnConverter<C, F>` has the model type `C` as its type
and reads an attribute of type `F`: `MQ1001` compares the attribute against `attributeType`, `Row.get` returns
`toModel` of a non-null value, and a value filter binds `toAttribute` of its value. The converter is stateless and is
never given `null`. `withTable` keeps it, and two columns are equal only when their converters are of the same class.
`path(ctx)` is the attribute's path, so a custom predicate on it compares attribute values (D-37). An
`OrderedColumnConverter<C, F>`, a `ColumnConverter` with no method of its own, promises that the conversion preserves
order both ways, and so is injective: the column is an `OrderedColumnField`, which `min`, `max` and `countDistinct`
take, while a column with any other converter is a plain `ColumnField` and they do not compile (`api/13` R-AGG-04,
D-93).
`core` ships two, each a singleton `INSTANCE`: `InstantTimestampConverter` (`Timestamp.from`/`toInstant`, nanoseconds
kept) and `DateTimestampConverter`, whose `toModel` returns the `Timestamp` itself typed as `Date`, so a value read
back binds exactly what was read. Range filters and `orderBy` on a converter that is not ordered stay allowed: they
compare stored values, and keyset cursors read `Row.raw` (D-84). A value a converter cannot convert (its `toAttribute`
throws `IllegalArgumentException`, as `InstantTimestampConverter` does for an `Instant` beyond `Timestamp`'s range) is
refused with `MQ1308` when a value filter or a write converts it. A plain `java.util.Date` binds at whole milliseconds, so
an inclusive upper bound is written half-open, `lt(nextDayStart)`: `lte(23:59:59.999)` excludes a stored
`23:59:59.999500`.

## 3a. `ExpressionField` and `Expr` — a value the database computes

```java
public sealed class ColumnField<M, T, C> implements ScalarField<M, C> permits OrderedColumnField { … }   // §3

public final class ExpressionField<M, C> implements ScalarField<M, C> {
    public Class<C> type();
    public String name();                                  // the property when named, else canonical text: coalesce(OrderView.discount, ?)
    public Expression<C> expression(JoinContext ctx);      // one Criteria node per JoinContext for equal expressions
    public ExpressionField<M, C> named(String property);   // not part of equals, as ColumnField.named (D-55)
    public Optional<String> property();
    @EngineFacing public List<ColumnField<M, ?, ?>> columns();   // every column read in the row, CASE conditions included
}

public final class Expr {
    public static <M, C> ExpressionField<M, C> coalesce(ScalarField<M, C> first, ScalarField<M, C> second);
    public static <M, C> ExpressionField<M, C> coalesce(ScalarField<M, C> first, C fallback);
    public static <M, C> ExpressionField<M, C> nullIf(ScalarField<M, C> value, C sentinel);
    // plus, minus, times: same type, a value, or mixed operands with an explicit result type
    public static <M, C extends Number> ExpressionField<M, C> plus(ScalarField<M, C> a, ScalarField<M, C> b);
    public static <M, C extends Number> ExpressionField<M, C> plus(ScalarField<M, C> a, C b);
    public static <M, C extends Number> ExpressionField<M, C> plus(ScalarField<M, ? extends Number> a,
                                                                   ScalarField<M, ? extends Number> b, Class<C> type);
    public static <M, C extends Number> ExpressionField<M, C> dividedBy(ScalarField<M, C> a, ScalarField<M, C> b);
    public static <M, C extends Number> ExpressionField<M, C> dividedBy(ScalarField<M, ? extends Number> a,
                                                                        ScalarField<M, ? extends Number> b, Class<C> type);
    public static <M, C extends Number> ExpressionField<M, C> negate(ScalarField<M, C> value);
    public static <M> ExpressionField<M, String> concat(ScalarField<M, String> a, ScalarField<M, String> b);
    public static <M> ExpressionField<M, String> concat(ScalarField<M, String> a, String b);
    public static <M> ExpressionField<M, String> concat(String a, ScalarField<M, String> b);
    @SafeVarargs public static <M, C> ExpressionField<M, C> function(String name, Class<C> type, ScalarField<M, ?>... args);
    public static <M> ExpressionField<M, String>  constant(String value);   // SQL text, never a request value
    public static <M> ExpressionField<M, Integer> constant(int value);
    public static <M> ExpressionField<M, Long>    constant(long value);
    public static <M> ExpressionField<M, Boolean> constant(boolean value);
    public static <M, R> Cases<M, R> cases(Class<M> model, Class<R> type);

    public static final class Cases<M, R> {                // only when(...): a CASE with no WHEN does not compile
        public When<M, R> when(UnaryOperator<Filters<M>> condition, ScalarField<M, R> result);
        public When<M, R> when(UnaryOperator<Filters<M>> condition, R result);
    }
    public static final class When<M, R> {
        /* the two when(...) */ public ExpressionField<M, R> otherwise(ScalarField<M, R> result);
        public ExpressionField<M, R> otherwise(R result);
        public ExpressionField<M, R> orNull();
    }
}
```

`Expr` mirrors `Agg`: it builds the field, and `ExpressionField` is the field type. `M` is the column vocabulary, a
generated model or the entity class for hand-written columns, and `C` the Java type; there is no `T`, because an
expression may span tables. `cases` takes the model class so the condition lambda's `Filters<M>` is inferred.

**R-COL-17** *(D-115)* **An expression is a typed value computed by the database.**
- It is built only by `Expr`'s factories, each one JPA `CriteriaBuilder` construct: `coalesce`, `nullIf`,
  `cases(...).when(...).otherwise|orNull`, `plus`, `minus`, `times`, `dividedBy`, `negate`, `concat`, `function`,
  `constant`. It reads columns of one vocabulary `M`, and holds no aggregate, join, order or SQL text of its own.
- **Arithmetic** takes operands of one numeric type, or declares the result type when they differ; the provider's
  resolved type must equal the declared one, else `MQ1507` at first resolution. `dividedBy` over two integral operands
  is `MQ1503`.
- **`concat`** is NULL when any operand is NULL.
- **CASE conditions** are a `Filters` group with local skipping (`api/12` R-FLT-01). A condition left with no filter is
  `MQ1504`; `add`, `exists` and sub-selects are `MQ1505`.
- **`function(name, type, args)`** takes a plain identifier that is not a built-in aggregate, else `MQ1506`. It is
  vendor SQL, and must be deterministic to order a paged or exported query (`engine/21` R-PAG-25).
- **A column with a `ColumnConverter`** is `MQ1501`.

**R-COL-18** *(D-115)* **Values bind, constants are text.**
- A value given to a factory (a `coalesce` fallback, a `nullIf` sentinel, an arithmetic or `concat` operand, a CASE
  result) is a bind parameter typed by its operand (`api/12` R-FLT-08).
- `null`, arrays, `Date`, `Calendar`, enums and entities are `MQ1502`.
- `Expr.constant(String|int|long|boolean)` is SQL text of the definition, rendered by the provider's literal formatter,
  for a function's mode argument or a key a functional index must match. It is never a request value.

**R-COL-19** *(D-115)* **One expression, one Criteria node per statement.** `expression(ctx)` returns the same
Criteria expression for equal expressions within one `JoinContext`. Selection, group key, order key, tie-breaker, count
and filters of one statement therefore share it: its values bind once per occurrence, and a provider that references
select items by identity (Hibernate) renders `GROUP BY` and `ORDER BY` as references, so PostgreSQL matches a key that
binds a value. The columns it reads join as each column would where the expression is used (`api/12` R-FLT-10 inside
`or` and `not`).

**R-COL-20** *(D-115)* **Equality and naming.** Two expressions are equal when built from equal calls: node kind,
operands, values (`equals`, so `BigDecimal` scale counts), declared type, function name and CASE conditions.
`named(property)` sets the model field's name for `orderedBy` and messages and is not part of equality, as
`ColumnField.named` is (D-55). Equal expressions are one selection, and each reads it through `Row.get`. An expression
is immutable and may be a constant (INV-9).

## 4. `SelectSet` — an immutable named set

```java
public final class SelectSet<M> {
    @SafeVarargs public static <M> SelectSet<M> of(SelectField<M, ?>... columns);
    public SelectSet<M> with(SelectField<M, ?>... extra);   // returns a copy
    public SelectSet<M> with(SelectSet<M> other);
    public SelectSet<M> without(SelectField<M, ?>... columns);
    public List<SelectField<M, ?>> fields();                // unmodifiable
    @Incubating public boolean contains(SelectField<M, ?> field);
    @Incubating public SelectSet<M> selectedIn(Row row);
}
```

**R-COL-09** A `SelectSet` is immutable. `with` and `without` return copies, so a shared constant cannot be changed by
one caller and affect every later query (INV-9).

**R-COL-21** *(D-120)* **Membership and equality.** `contains(field)` is exact membership by field equality, never
through a converter, as `Row.isSelected` is; it is `@Incubating`. Two sets are equal when they hold the same fields,
whatever their order, and `hashCode` agrees (set semantics), so a record holding one stays comparable. `toString` lists
the fields in set order, like `[OrderView.id, OrderView.status]`. Equality is not separately marked: it freezes with
`SelectSet`.

**R-COL-22** *(D-120)* **`selectedIn(Row)`.** Returns the subset of this set's fields for which `Row.isSelected` is
true (a scoped row answers for its nested model's columns), in set order; returns `this` when all are selected; never
`null`. It is `@Incubating`. It keeps a single-entry memo keyed by the selection as a `long[]` bit mask, held in a
`volatile` immutable holder, so the rows of one query share one instance and the memo is not observable (INV-9). It is
safe under concurrent calls with different selections: a call that loses the race returns a correct result and the
last stored entry wins.

**R-COL-23** *(D-121)* **`FieldIndex.resolve(names)`.** A `FieldIndex<M>` (`@Incubating`) holds a model's select fields,
filter-only columns, sets and children under exact, case-sensitive keys (RFC 0006). `resolve(names)` turns each name
into a select field, a set or a child, looked up in that order of kinds, and collects every name that matches none of
them in `unknown`, distinct, in input order. `select` holds the select fields in input order, a set expanded in place,
the first occurrence of a field kept. `children` holds each child once, in input order. A filter-only column counts as
unknown, and so does `""`; a `null` element throws `NullPointerException`; otherwise `resolve` never throws. An input
with no select field or set gives an empty `select`. The `Resolution` lists are copies (CC-IMM). `select(name)`,
`filter(name)` (a column, filter-only ones included, or an expression), `set(name)` and `child(name)` look one name up
by kind; `names()` is what `resolve` accepts and `filterNames()` what `filter` accepts. The key of a select field is a
column's property path, else its attribute path, an expression's name and an aggregate's `named` property, else its
name: tier 1 of `orderedBy` (R-QRY-14), through one helper both use. A filter-only column's key is its attribute path;
a join set's key is its join's property path. `AggregateField.named(String)` sets the property, outside `equals` and
`hashCode`, as `ExpressionField.named` does (R-COL-20). Within one kind, a key given twice with an equal field keeps one
entry; with a different field, `FieldIndex.Builder.build()` throws `IllegalStateException`. The filter names (select
columns and expressions, then filter-only columns) are one kind for this rule.

**R-COL-24** *(D-121)* **`FieldIndex.only(names)`.** Returns an index holding just those keys, for a public API that
exposes part of a model. A kept set holds only the select fields the narrowed index keeps, so `only(List.of("ALL",
"id"))` can't select more than `id`. A name that is not a key (of `names()` or `filterNames()`), or a kept set left
empty, throws `MQ1105` naming it and the index's `names()` and `filterNames()`.

## 5. `Row` and `RowMapper`

```java
public interface Row {
    <C> C get(SelectField<?, C> column);              // null when the column is NULL or wasn't selected
    Object raw(SelectField<?, ?> column);             // the value as read, before any ColumnConverter
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
cannot shift a mapping. Columns of one model over the same attribute of the same table, whatever their types and
converters, are one selection under one alias, and each reads the value through its own converter.

**R-COL-11** Because a model is built from a complete row in one call, classes and records use the same engine.
Primary keys, keyset cursors and export dedupe read from the `Row` before mapping, so a model needs no key accessor and
no base class. They read `Row.raw`, the attribute value, so a converter that maps two attribute values to one model
value cannot merge two keys or move a cursor (D-37).

A hand-written record model passes a lambda: `row -> new OrderView(row.get(ID), row.get(STATUS), …)`. A class model
may use `RowMapper.setters(factory)`, whose `bind` takes a `SelectField<M, C>` of the mapper's own model: another
model's column is never selected, so it would set `null` on every row, and it does not compile (D-88). Generated
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
- Without it the key is rendered even where the vendor's default already matches the requested precedence: a bare
  order would take a default null ordering the provider is configured with, which nothing reports there (D-36).
  With it, Hibernate omits the clause where the dialect's default matches.
- On a grouped query ordered by an expression with `nullsFirst()` or `nullsLast()`, the engine always renders the
  portable key, wrapped in `MIN(...)`, then the expression, with or without `model-query-hibernate` *(D-115)*. A null
  test over a key that binds a value re-renders it with its own parameters, which PostgreSQL and MySQL do not match to
  the `GROUP BY` item, while an aggregate may read any column; the key is constant within a group, so `MIN` is its
  value. Hibernate's own emulation, where the dialect has no `NULLS FIRST/LAST`, would re-render it the same way.

**R-COL-13** Keyset paging on a nullable column with `DEFAULT` precedence uses
`VendorProfile.defaultAscendingNullOrdering()`, and is refused when that is `UNKNOWN` (`engine/21` R-PAG-05). A
default null ordering the persistence provider is configured with replaces the profile's (D-36).

## 7. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-COL-01 | Selecting, filtering and ordering on one path renders exactly one join; `as(...)` renders two; children of an aliased join stay separate (R-COL-03, R-COL-05). |
| AC-COL-02 | `on(...)` without an alias throws; two `TableField`s with the same key and different conditions throw `MQ1101`; `as(...)` or `on(...)` on a root throws `MQ1104` (R-COL-04). |
| AC-COL-03 | `on(...)` on a LEFT join keeps rows with no matching child (R-COL-03). |
| AC-COL-04 | A hand-written `ColumnField` whose type does not match the entity attribute throws `MQ1001` at first resolution; an unknown attribute throws `MQ1002` and a column on another entity's root `MQ1003` (R-COL-08). |
| AC-COL-05 | `SelectSet.with`/`without` leave the original set unchanged (R-COL-09). |
| AC-COL-06 | Every `ColumnField` type round-trips through `Row.get`, including converters, for a class model and an equivalent record model (R-COL-10). |
| AC-COL-07 | A filter-only column filters correctly, adds no join when its filter is skipped, and never reaches the model (R-COL-07). |
| AC-COL-08 | `nullsFirst()`/`nullsLast()` produce identical orderings on every Tier-1 vendor, with and without `model-query-hibernate` (R-COL-12). |
| AC-COL-09 | A `ModelQuery` stored in a `static final` field is used concurrently by 8 threads with identical results (INV-9). |
| AC-COL-10 | A column whose attribute is a dotted path through an embedded value selects and filters that value; an unknown segment, or one crossing an association, throws `MQ1002` naming the segment (R-COL-08, D-41). |
| AC-COL-11 | A column built with a `ColumnConverter` round-trips through `Row.get` and through a filter on the same column, while `Row.raw` returns the value unconverted, which primary keys and keyset cursors are read as; `sum`, `sumAsLong` and `avg` over it throw `MQ1408`, and `min`, `max` and `countDistinct` take an ordered one only (AC-AGG-13) (R-COL-14, R-COL-11, D-37). |
| AC-COL-12 | Selecting a column of a `presentBy` join also selects the join's presence key, and that of every such join above it, in every model phase and once only, so a mapper tells an absent row from a match whose columns are all `NULL`; a grouped query whose group keys lack the key throws `MQ1409`, and `presentBy` on a root `MQ1104` (R-COL-15, D-38). |
| AC-COL-13 | `InstantTimestampConverter` and `DateTimestampConverter` round-trip with nanoseconds kept and keep order both ways; a `Timestamp` attribute holding sub-millisecond digits is read, filtered with `eq`, `gt`, `lte` and `between`, sorted and keyset-paged through each, and a value read back through `DateTimestampConverter` binds exactly what was read; an `Instant` beyond `Timestamp`'s range is refused with `MQ1308` (R-COL-14, D-84). |
| AC-COL-14 | A model selecting one attribute through several columns, a `Timestamp` read as an `Instant`, a `Date` and itself, is read through `list`, offset and primary-key-first `page`, `count`, `stream` and keyset export across several pages, and as two group keys through `list`, `count` and export, each column holding its own converted value (R-COL-10). |
| AC-COL-15 | A generated `@Join` follows a `@JoinColumn(referencedColumnName)` association to a unique non-key column: the nested model is selected, its columns filter and sort the query, and a fetch plan loads it, each matched through the referenced column and not the target's key (R-COL-01, R-COL-03, D-111). |
| AC-COL-16 | A generated `@Join` follows a Hibernate `@JoinFormula` association: the nested model is selected, filtered, sorted and fetched through the computed key (R-COL-01, R-COL-03, D-111). |
| AC-COL-17 | A hand-written `TableField` with `as(...)` and `on(...)` adds an extra ON condition to a `ModelQuery` join, keeping the rows whose join then misses with a `NULL` nested column — the documented replacement for the declined `@Join(on = ...)` (R-COL-03, R-COL-04, D-111). |
| AC-COL-18 | An `AggregateField` passed to a `Filters` operator, to `groupBy(...)` or to an `Expr` factory, and `Expr.cases` with no `when` before `otherwise`, each have a compile-failure case. Two expressions built from equal calls are equal and hash alike, whatever `named` says; they differ by a value, a `BigDecimal` scale, the declared type and a function name. An expression constant is used by 8 threads with identical results (R-COL-16, R-COL-20, INV-9). |
| AC-COL-19 | On every Tier-1 vendor, each factory returns the expected value and Java type: `coalesce` and `nullIf` over NULL and non-NULL; same-type and mixed `times` (`Integer × BigDecimal` gives `BigDecimal`); `dividedBy` over decimals; `concat` with a NULL operand (NULL); a CASE with column and value branches; a CASE whose every branch is a value with three decimal places, returned exactly; `function` with an `Expr.constant` mode argument. `MQ1501` to `MQ1507` each have a case (R-COL-17, R-COL-18). |
| AC-COL-20 | A selected expression round-trips through `Row.get` for a class and a record model. Two equal expressions under different `named` properties are one selection, and each reads the value. The PostgreSQL statement log shows one rendering of an expression used in select, order and filter of one statement (R-COL-19, R-COL-20, R-COL-10). |
| AC-COL-21 | `contains` is exact: a field equal by value is found, a column with another converter or table is not. Sets with the same fields in another order are equal and hash alike; `toString` reads `[OrderView.id, OrderView.status]` (R-COL-21). |
| AC-COL-22 | `selectedIn` returns the selected subset in set order, `this` when all are selected, an empty set when none; it works on a scoped row and on a set of more than 64 fields, returns one shared instance for rows of one selection, and is correct under 8 threads with different selections (R-COL-22, INV-9). |
| AC-COL-23 | `resolve` returns known names' fields in input order with a set expanded in place and a repeated field once, children once each in input order, and unknown names distinct in input order; a filter-only name and `""` are unknown, a `null` element throws `NullPointerException`, an empty input gives an empty `select`, and the `Resolution` lists are copies. `select`, `filter`, `set`, `child`, `names()` and `filterNames()` agree with it (R-COL-23). |
| AC-COL-24 | `only` narrows a kept set to the kept select fields and keeps filter-only columns and children by name; an unknown name, or a set left empty, throws `MQ1105` naming the name and the index's names (R-COL-24). |
| AC-COL-25 | The builder derives each key as R-COL-23 says (property path, else attribute path; a filter-only column by its attribute path; a join set by its join's property path), keeps an equal field given twice once, and throws `IllegalStateException` for a different field under one key. |
| AC-COL-26 | `AggregateField.named` is outside `equals` and `hashCode`, is kept by `as`, and is the aggregate's index key; a sort property equal to the `named` property or to the name selects the aggregate (R-COL-23, R-QRY-14). |
