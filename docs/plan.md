# Model Query — Library Plan

An open-source Java library for **typed, projection-first queries on top of JPA**. It adds:

- typed column and join definitions (`ColumnField`, `TableField`) that map query results straight into plain model classes,
- an **annotation processor** that generates those definitions as `QModel` classes,
- a query execution engine for page, count, stream, and exports of large results (offset or keyset paging),
- bulk `UPDATE` and `DELETE` driven by the same filters, with generated change sets that write only the fields that
  were set,
- vendor-aware behaviour for **H2, PostgreSQL and MySQL** first, behind an SPI that other databases can implement.

This document is the full plan: goals, architecture, API, correctness requirements, multi-vendor design, testing,
open-source setup and milestones. It is self-contained.

It holds **what to build, never progress**. There are no checkboxes, status columns or session notes here. Milestone
status is read from git (§11.1), and working notes such as progress logs and agent instructions are kept outside this
repository.

---

## 1. Goals and non-goals

### Goals

1. **Projection-first reads.** Select only the columns a use case needs and map them into a non-entity model class.
   No entity loading, no persistence context, no N+1.
2. **Type safety from model to entity.** A column's Java type, the entity attribute it reads, and the model field it
   populates are checked at compile time.
3. **Minimal boilerplate.** Users write a model, either a class with fields or a record. The processor generates the rest.
4. **Correct paging over large tables.** Exports of millions of rows must visit every row exactly once, with bounded
   memory, on every supported database.
5. **Multi-vendor.** H2, PostgreSQL and MySQL are Tier 1 from the first release. Vendor differences live behind one SPI.
6. **Framework-optional.** The core works with a plain `EntityManager`. Spring Data and Spring Boot integration are
   separate modules.
7. **Filter-driven bulk writes.** Update or delete the rows a filter matches in one statement, and write only the
   fields a caller actually set, so a partial update (HTTP PATCH) never needs a hand-written `set(...)` per field.

### Non-goals

- Replacing JPA entities or entity-level writes. Inserts, cascades, lifecycle callbacks and dirty checking stay with
  JPA. Writes are bulk `UPDATE` and `DELETE` statements keyed by filters (§4.6), and they bypass the persistence
  context by design.
- A general SQL builder like jOOQ. The library stays on the JPA Criteria API and entity mappings.
- Generating code from a database schema. The source of truth is the JPA entity model.
- Abstracting collation, JSON column types, or time-zone handling. These stay with the entity mapping and the driver
  (see §7.6).

### Positioning

| Library | Difference |
|---|---|
| Querydsl | Querydsl generates Q-types for **entities** and builds JPQL. This library generates `QModel`s for **projection models** and owns mapping, paging and export on top of that. |
| Blaze-Persistence Entity Views | Similar projection idea, but interface-based views and a much larger runtime. This library is small, uses plain classes or records, and has no custom query language. |
| jOOQ | Schema-first and a separate SQL stack. This library reuses JPA entities and the `EntityManager`. |
| Spring Data projections | Interface or DTO projections with no join control, no keyset export engine and no compile-time column checks. |

---

## 2. Concepts

| Concept | What it is |
|---|---|
| **Entity** | A normal JPA `@Entity`, used only as the mapping source. |
| **Model** | A plain class or a record (for example `OrderView`) that receives query results. It needs no base class or interface: the engine reads primary keys from the result row, not from the model. |
| **`TableField<P, T>`** | A node in a join path: root entity, or `parent → attribute` with a `JoinType`. Resolved lazily to a Criteria `From`. Joins are shared by path: every use of the same path in one query (select, filter, order) reuses one join, and a second join to the same path needs an explicit alias (`as(...)`). |
| **`ColumnField<M, T, C>`** | One selectable column of model `M`: a `TableField`, an attribute name and a Java type `C`. It holds no setter; values are read into a `Row` and a `RowMapper` builds the model. It can be re-rooted under another join with `withTable(...)`. A column doesn't have to be mapped: a **filter-only column** is only used in filters or ordering and has no field on the model. |
| **`Row` / `RowMapper<M>`** | `Row` is one result row, keyed by `ColumnField`. `RowMapper<M>` turns a `Row` into a model, via setters for classes or the canonical constructor for records. QModels generate it. |
| **`OrderField<M, T, C>`** | A column plus direction and null precedence (`nullsFirst()` / `nullsLast()`, or the database default). |
| **`ColumnSet<M>`** | An immutable, named set of columns (for example `DEFAULT`, `ALL`, `CUSTOMER`). |
| **`QModel`** | A generated companion class (`QOrderView`) holding all `TableField`, `ColumnField` and `ColumnSet` constants for one model. |
| **`ModelQuery<E, P, M>`** | The query definition: root, selected columns, primary key, order, filters, and paging mode. Built with a builder. |
| **`ModelQueryExecutor<E>`** | Runs a `ModelQuery` against an `EntityManager`: `list`, `page`, `count`, `stream`, `export`, and the bulk writes `update` and `delete`. |
| **Update model** | A class or record annotated `@UpdateModel` that lists the root-entity attributes an update may write. It holds no data itself; the processor generates a change set for it. |
| **`Changes<M>`** | A generated, mutable change set for model `M` (`OrderPatchChanges`). Each setter records that its column was set, so "set to NULL" and "not set" stay different. |
| **`ModelUpdate<E, M>` / `ModelDelete<E, M>`** | Bulk write definitions: assignments (for updates), filters, and safety options. Built with a builder, immutable like `ModelQuery`. |
| **`VendorProfile`** | An SPI holding all database-specific behaviour. |

### Example

```java
@Entity class OrderEntity {
    @Id Long id;
    String status;
    BigDecimal total;
    Instant createdAt;
    @ManyToOne(fetch = LAZY) CustomerEntity customer;
    @OneToMany(mappedBy = "order") List<OrderItemEntity> items;
}

@QueryModel(root = OrderEntity.class)
@FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country")   // filter-only: no field on the model
public class OrderView {
    @PrimaryKey private Long id;
    private OrderStatus status;                                     // enum in model, String in entity → needs a converter
    private BigDecimal total;
    private Instant createdAt;
    @Join(attribute = "customer")
    private Optional<CustomerView> customer = Optional.empty();     // nested model → joined columns, empty when not joined/matched
    // getters/setters (hand-written or Lombok)
}

// The same model as a record: equally supported
@QueryModel(root = OrderEntity.class)
public record OrderView(
        @PrimaryKey Long id,
        OrderStatus status,
        BigDecimal total,
        Instant createdAt,
        @Join(attribute = "customer") Optional<CustomerView> customer) {}
```

```java
List<OrderView> rows = executor.list(
        QOrderView.query()
                .columns(QOrderView.DEFAULT, QOrderView.CUSTOMER)
                .where(f -> f.eq(QOrderView.STATUS, Optional.of("PAID"))
                             .range(QOrderView.CREATED_AT, from, to)
                             .eq(QOrderView.CUSTOMER_COUNTRY, country))       // Optional<String>; skipped when empty
                .orderBy(QOrderView.CUSTOMER_NAME.asc().nullsFirst(), QOrderView.CREATED_AT.desc())
                .build(),
        Limit.of(100));

rows.get(0).getCustomer().map(CustomerView::getName);              // Optional.empty() if the order has no customer

long exported = executor.export(
        QOrderView.query().columns(QOrderView.ALL).orderBy(QOrderView.CREATED_AT.asc()).keyset().build(),
        ExportOptions.pageSize(5_000),
        row -> csv.write(row));
```

---

## 3. Modules

```
model-query/
├── model-query-bom                  Version alignment
├── model-query-annotations          @QueryModel, @UpdateModel, @PrimaryKey, @Column, @Join, @FilterColumn, @ExcludeFromDefaults, @Transient    (no deps)
├── model-query-core                 TableField, ColumnField, OrderField, ColumnSet, Row, RowMapper, ModelQuery,
│                                    ModelUpdate, ModelDelete, Changes, Filters DSL, JoinContext, SPI interfaces      (jakarta.persistence-api)
├── model-query-jpa                  ModelQueryExecutor, paging, export and bulk-write engine, built-in VendorProfiles  (core)
├── model-query-hibernate            Hibernate 6.x extras: dialect-based vendor detection, grouped count,
│                                    null precedence                                                                   (jpa + hibernate-core, optional)
├── model-query-processor            Annotation processor generating QModel classes                                    (annotations, JavaPoet shaded)
├── model-query-spring-data          ModelQueryRepository + repository factory, Page/Pageable/Sort adapters            (jpa + spring-data-jpa)
├── model-query-spring-boot-starter  Auto-configuration, ModelQueryProperties, SPI beans                              (spring-data + boot)
├── model-query-tck                  Testcontainers suite against every supported vendor                               (test)
└── samples/                         Plain JPA sample, Spring Boot sample
```

Dependency rules, enforced with ArchUnit in the build:

- `core` imports only `jakarta.persistence.*` and the JDK.
- `jpa` doesn't import `org.hibernate.*`. Hibernate features are found with `ServiceLoader` and have a portable fallback.
- Only `spring-*` modules import `org.springframework.*`.
- No Lombok in the library. Consumers can use Lombok on their models.

### Baselines

| Item | Version |
|---|---|
| Java | 17 (tested on 17, 21, 25) |
| Jakarta Persistence | 3.1+ |
| Hibernate ORM | 6.6+ (CI matrix: 6.6 and latest 7.x) |
| Spring Boot (starter) | 3.4+ |
| Build | Maven, with `maven-wrapper` |

---

## 4. Core API

### 4.1 Columns and tables

```java
public final class TableField<P, T> {
    public static <T> TableField<T, T> root(Class<T> entity);
    public static <P, T> TableField<P, T> join(TableField<?, P> parent, String attribute, JoinType type);
    public TableField<P, T> as(String alias);                              // a separate join to the same path
    public TableField<P, T> on(BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition);   // extra ON condition; requires as(...)
    public TableField<P, T> withParent(TableField<?, P> newParent);       // keeps alias and ON condition
    From<?, T> resolve(JoinContext ctx);                                   // looked up by join key, joined once per query
}

public final class ColumnField<M, T, C> {
    public static <M, T, C> ColumnField<M, T, C> of(Class<M> model, TableField<?, T> table, String attribute, Class<C> type);
    public Path<C> path(JoinContext ctx);
    public <M2> ColumnField<M2, T, C> withTable(Class<M2> model, TableField<?, T> table);   // re-root under a join
    public OrderField<M, T, C> asc();
    public OrderField<M, T, C> desc();
}

public record OrderField<M, T, C>(ColumnField<M, T, C> column, boolean ascending, NullPrecedence nulls) {
    public OrderField<M, T, C> nullsFirst();                // NULLs before all non-null values, for ASC and DESC
    public OrderField<M, T, C> nullsLast();                 // NULLs after all non-null values, for ASC and DESC
    public OrderField<M, T, C> nulls(NullPrecedence nulls); // for values chosen at runtime (e.g. from a request)
}

public enum NullPrecedence { DEFAULT, FIRST, LAST }         // DEFAULT = whatever the database does
```

**Row mapping.** Queries select a JPA `Tuple`. The engine wraps it as a `Row` and hands it to the query's `RowMapper`:

```java
public interface Row {
    <C> C get(ColumnField<?, ?, C> column);           // null when the column is NULL or wasn't selected
    boolean isSelected(ColumnField<?, ?, ?> column);
    Row scoped(TableField<?, ?> join);                 // view of a nested model's columns under a join
}

@FunctionalInterface
public interface RowMapper<M> {
    M map(Row row);

    // for hand-written class models: setter-style binding without the processor
    static <M> SetterMapper<M> setters(Supplier<M> factory);    // .bind(ID, OrderView::setId).bind(...)
}
```

Because models are built from a complete row in one call, classes and records use the same engine. Primary keys,
keyset cursors and export dedupe (R1–R4) read from the `Row` before mapping, so models need no key accessor. Records
normally come from the processor, since only generated code knows the constructor's parameter order. A hand-written
record passes a lambda: `row -> new OrderView(row.get(ID), row.get(STATUS), …)`.

**Null precedence.** `asc()` and `desc()` start with `NullPrecedence.DEFAULT`. `nullsFirst()` and `nullsLast()` mean
the same thing on every vendor, regardless of that vendor's default:

- With `model-query-hibernate` on the classpath, the order is rendered through Hibernate's criteria extension
  (`HibernateCriteriaBuilder#sort(expr, direction, nullPrecedence)`). Hibernate emits `NULLS FIRST/LAST` where the
  dialect supports it (H2, PostgreSQL) and emulates it where it doesn't (MySQL).
- Without it (plain JPA 3.1 has no null precedence in `Order`), the engine adds a leading sort key
  `CASE WHEN col IS NULL THEN 0 ELSE 1 END` (ASC for `FIRST`, DESC for `LAST`) before the column itself. That is
  portable, but it prevents the database from using an index on `col` for the sort.
- If the `VendorProfile` says the vendor's default already matches the requested precedence for that direction
  (for example `nullsLast()` on an ASC column in PostgreSQL), nothing extra is rendered.
- `DEFAULT` renders no null clause. Keyset paging on a nullable column with `DEFAULT` precedence uses the vendor's
  known default from `VendorProfile.defaultAscendingNullOrdering()`, and is refused when that is `UNKNOWN` (R5).

The Spring Data adapter maps `Sort.Order.nullsFirst()/nullsLast()/nullsNative()` to `FIRST`/`LAST`/`DEFAULT`.

**Join sharing.** `JoinContext` caches joins by a **join key**: the parent's join key, the attribute, the `JoinType`
and the alias (empty by default). It never uses object identity, so two `TableField.join(ROOT, "customer", LEFT)`
calls in different places still produce one join. The rules:

- Same path, same type, no alias → one join, whether the column is selected, filtered or ordered on.
- Different attributes are different paths, even to the same table (`billingAddress` and `shippingAddress` →
  two joins to `address`).
- `as("x")` gives the same path its own join. Children of an aliased join are separate too
  (`ITEMS.as("b") → product` isn't `ITEMS → product`).
- `on(...)` adds a condition to the join's `ON` clause through JPA's `Join#on`, which keeps LEFT-join semantics that a
  `WHERE` predicate would break. Because a lambda can't be compared, a join with a condition must have an alias, and
  two `TableField`s with the same key but different condition instances throw when the query is built.

```java
TableField<OrderEntity, OrderItemEntity> ITEMS   = TableField.join(ROOT, "items", INNER);
TableField<OrderEntity, OrderItemEntity> ITEMS_B = TableField.join(ROOT, "items", INNER).as("itemB");
TableField<OrderEntity, AddressEntity> BILLING  = TableField.join(CUSTOMER_TABLE, "addresses", LEFT)
        .as("billing").on((a, cb) -> cb.equal(a.get("type"), "BILLING"));

ColumnField<OrderView, OrderItemEntity, String> SKU   = ColumnField.of(OrderView.class, ITEMS,   "sku", String.class);
ColumnField<OrderView, OrderItemEntity, String> SKU_B = ColumnField.of(OrderView.class, ITEMS_B, "sku", String.class);

.where(f -> f.eq(SKU, skuA).eq(SKU_B, skuB))     // two joins to order_items: has item A AND has item B
```

For "has an item matching X" on a collection, `Filters.exists(...)` (§4.4) is usually the better choice: it doesn't
multiply rows, so `count` and export need no distinct or dedupe work.

`JoinContext` is created per query build and passed explicitly. Builders hold no mutable per-query state, so a
`ModelQuery` is immutable and thread-safe and can be stored in a `static final` field.

### 4.2 Column sets

```java
public final class ColumnSet<M> {
    @SafeVarargs public static <M> ColumnSet<M> of(ColumnField<M, ?, ?>... columns);
    public ColumnSet<M> with(ColumnField<M, ?, ?>... extra);   // returns a copy
    public ColumnSet<M> with(ColumnSet<M> other);
    public ColumnSet<M> without(ColumnField<M, ?, ?>... columns);
    public List<ColumnField<M, ?, ?>> columns();               // unmodifiable
}
```

Column sets are immutable. A shared constant can't be changed by one caller and then affect every later query.

### 4.3 Query definition

```java
ModelQuery<OrderEntity, Long, OrderView> q = ModelQuery.builder(QOrderView.ROOT, QOrderView.MAPPER)
        .primaryKey(PrimaryKey.of(QOrderView.ID))           // generated for QModels; composite: PrimaryKey.composite(A, B)
        .columns(QOrderView.DEFAULT)
        .where(f -> f.eq(QOrderView.STATUS, status))
        .groupBy(...)                                         // optional
        .orderBy(QOrderView.CREATED_AT.desc())
        .keyset()                                             // optional: allow keyset paging
        .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10_000))   // optional: two-step deep paging
        .customize(QueryCustomizer)                           // escape hatch: raw CriteriaBuilder access
        .build();
```

`QueryCustomizer` replaces subclassing hooks. It receives `(QuerySpec, JoinContext, CriteriaQuery, CriteriaBuilder, Phase)`
and can add predicates, selections or group-by expressions for the phases `MODEL`, `PRIMARY_KEY` and `MODEL_BY_KEYS`.

### 4.4 Filters DSL

`where(UnaryOperator<Filters<M>>)` receives a builder that ANDs every filter added to it. Every method that takes a
value has two overloads:

- `(column, C value)`: the filter always applies. `null` throws, because "no filter" must be explicit.
- `(column, Optional<? extends C> value)`: `Optional.empty()` skips the filter, and no join is created for it. This is
  the form for optional request parameters.

Only the value-form signatures are listed below; each has an `Optional` twin.

```java
public interface Filters<M> {
    // Equality and comparison
    <C> Filters<M> eq(ColumnField<M, ?, C> column, C value);
    <C> Filters<M> ne(ColumnField<M, ?, C> column, C value);                  // NULL rows match, see below
    <C extends Comparable<? super C>> Filters<M> gt (ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> lt (ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> range(ColumnField<M, ?, C> column,
                                                       Optional<? extends C> fromInclusive,
                                                       Optional<? extends C> toExclusive);   // each bound optional
    <C extends Comparable<? super C>> Filters<M> between(ColumnField<M, ?, C> column, C fromInclusive, C toInclusive);

    // Sets
    <C> Filters<M> in(ColumnField<M, ?, C> column, Collection<? extends C> values);    // empty collection → matches nothing
    <C> Filters<M> notIn(ColumnField<M, ?, C> column, Collection<? extends C> values); // empty collection → no-op; NULL rows match

    // Strings
    Filters<M> like(ColumnField<M, ?, String> column, String value, LikeMode mode);     // EXACT | CONTAINS | STARTS_WITH | ENDS_WITH
    Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, String value, LikeMode mode);
    Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, String value);

    // Nulls
    Filters<M> isNull(ColumnField<M, ?, ?> column);
    Filters<M> isNotNull(ColumnField<M, ?, ?> column);
    Filters<M> isNull(ColumnField<M, ?, ?> column, Optional<Boolean> isNull);          // for a tri-state request flag

    // Column against column (both sides may use different joins)
    <C> Filters<M> compare(ColumnField<M, ?, C> left, Op op, ColumnField<M, ?, C> right);  // Op: EQ, NE, LT, LTE, GT, GTE

    // Composition
    Filters<M> or(UnaryOperator<Filters<M>>... branches);    // each branch is an AND group
    Filters<M> not(UnaryOperator<Filters<M>> group);
    Filters<M> when(boolean condition, UnaryOperator<Filters<M>> group);
    Filters<M> apply(UnaryOperator<Filters<M>> fragment);    // reuse shared fragments, e.g. NOT_DELETED

    // Correlated sub-queries on a collection (or any) join path
    Filters<M> exists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner);
    Filters<M> exists(TableField<?, ?> path);                 // "has at least one"
    Filters<M> notExists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner);

    // Escape hatch
    Filters<M> add(Function<JoinContext, Predicate> custom);
}
```

```java
.where(f -> f
        .eq(QOrderView.STATUS, status)                                   // Optional<String>
        .range(QOrderView.CREATED_AT, from, to)
        .or(g -> g.likeIgnoreCase(QOrderView.CUSTOMER_NAME, q, CONTAINS),
            g -> g.eq(QOrderView.CUSTOMER_EMAIL, q))
        .exists(QOrderView.ITEMS_TABLE, i -> i.eq(QOrderView.ITEM_SKU, sku).gt(QOrderView.ITEM_QTY, 0))   // filter-only columns on items
        .when(!includeCancelled, g -> g.ne(QOrderView.STATUS, "CANCELLED")))
```

Semantics the engine guarantees, each with a TCK test:

- **Skipping is local.** A skipped filter disappears from its group. An `or(...)` branch whose filters were all skipped
  is dropped. If every branch was dropped, the whole `or` is skipped, rather than becoming `FALSE` and matching nothing.
  The same goes for `not` and for `exists` with an inner group: when all its inner filters were skipped, `exists`
  is skipped too. Use `exists(path)` to ask for "has at least one" explicitly.
- **Empty collections are not "skip".** `in(col, List.of())` renders `FALSE` (and `notIn` renders nothing), because an
  empty selection in a UI means "none of these". Pass `Optional.empty()` to skip.
- **Negation includes NULLs.** `ne` and `notIn` over a nullable column render `col <> ? OR col IS NULL`, which is
  what a report filter means by "not X". SQL's plain `<>` drops NULL rows silently. For the strict SQL meaning,
  combine with `isNotNull(col)`. `not(group)` is plain SQL `NOT (…)`, since three-valued logic over an arbitrary group
  can't be rewritten portably; the Javadoc says that rows where the group is UNKNOWN (NULL) are excluded.
- **`like` escapes its input.** `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` escape `%`, `_` and the escape character in
  the value and render `ESCAPE '\'`, so a search for `50%` finds that literal text. `EXACT` passes the pattern as
  given. `likeIgnoreCase` renders `lower(col) LIKE lower(?)`; the user guide notes that this needs a
  functional index to be fast.
- **Large `IN` lists are split.** Lists longer than the vendor's IN-list limit (§7.3) are rendered as
  `col IN (…) OR col IN (…)`, and `notIn` as an AND of `NOT IN` chunks. Every value is a bind parameter; the engine
  never inlines values into SQL.
- **Joins inside `or` and `not` are LEFT.** An INNER join created only for one `or` branch would remove rows that
  another branch should match. When a join is first needed inside `or` or `not`, the engine resolves it as LEFT. If the
  same path is already joined as INNER elsewhere in the query, that join is reused, because those rows are already
  required.
- **`exists` is a correlated sub-query.** `exists(ITEMS_TABLE, inner)` renders
  `EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id AND <inner>)`. Columns inside `inner` must sit on
  `path` or below it and are re-rooted to the sub-query; any other column throws with the column's name. Aliased
  paths and `on(...)` conditions are carried into the sub-query. Because nothing is joined on the outer query, `count`
  and export need no distinct or dedupe work (R7, R1–R2).

Filters build predicates only. Conditions on aggregates (`HAVING`) go through `QueryCustomizer` with `Phase.MODEL`.

**Filter-only columns.** Filters accept any `ColumnField<M, …>`, whether or not the model has a field for it. The
mapper only reads the columns it knows, so a filter-only column never affects the model:

```java
public static final ColumnField<OrderView, CustomerEntity, String> CUSTOMER_COUNTRY =
        ColumnField.of(OrderView.class, QOrderView.CUSTOMER_TABLE, "country", String.class);

.where(f -> f.eq(CUSTOMER_COUNTRY, country))
```

QModels generate these from `@FilterColumn` (§6.1). If a filter-only column ends up in a `ColumnSet`, it is selected
and then ignored by the mapper.

### 4.5 Executor

```java
public interface ModelQueryExecutor<E> {
    <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit);
    <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode);        // COUNT, NO_COUNT, ONLY_COUNT
    long count(ModelQuery<E, ?, ?> q);
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body); // owns and closes the stream
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
                       Function<List<M>, List<S>> pageTransformer, Consumer<S> sink);

    long update(ModelUpdate<E, ?> u);                                               // rows affected (§4.6)
    long delete(ModelDelete<E, ?> d);                                               // rows affected (§4.6)
}
```

`ModelQueryExecutor.create(EntityManager, Class<E>, ModelQueryConfig)` is enough to use it without Spring.

### 4.6 Bulk updates and deletes

A bulk write is "change the rows these filters match", rendered as one JPA `CriteriaUpdate` or `CriteriaDelete` (or a
chunked series of them). It reuses the filters DSL, join resolution, converters and vendor limits of queries. It
doesn't load entities, run lifecycle callbacks or cascade (§1 non-goals, R21).

**Change sets.** Writing `set(...)` once per field is tedious for wide entities, and it can't express "only the fields
the client sent". An update model lists the writable attributes once:

```java
@UpdateModel(root = OrderEntity.class)
@FilterColumn(name = "CREATED_AT", path = "createdAt")                 // filter-only, as on query models
@FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country")
public record OrderPatch(                       // a class works too; it is only read by the processor
        @PrimaryKey Long id,                    // never written; used by whereKey(...)
        OrderStatus status,                     // converter from @Column or the entity mapping, as for queries
        BigDecimal total,
        Instant deliveredAt,
        Instant updatedAt,
        String note,
        @Column(attribute = "customer") Long customerId) {}   // to-one by id: writes the foreign key
```

The processor generates `QOrderPatch` (columns, like a QModel) and `OrderPatchChanges`, a mutable change set that
records which setters were called (§6.5):

```java
OrderPatchChanges c = QOrderPatch.changes()
        .status(OrderStatus.SHIPPED)
        .deliveredAt(null);                     // explicitly NULL; total, note, customerId not set

c.isSet(QOrderPatch.TOTAL);                     // false
c.unset(QOrderPatch.STATUS);                    // drop a field again

long n = executor.update(QOrderPatch.update(c).whereKey(id).build());
// UPDATE orders SET delivered_at = NULL, version = version + 1 WHERE id = ?
```

Because the change set also has JavaBean setters and a no-arg constructor, it binds straight from a PATCH request body.
Jackson (and other binders) only call the setter of a property that is present, so `{"note": null}` clears `note`
and a body without `note` leaves it alone. `core` needs no Jackson dependency for this.

```java
@PatchMapping("/orders/{id}")
void patch(@PathVariable long id, @RequestBody OrderPatchChanges changes) {
    orders.update(QOrderPatch.update(changes).whereKey(id).build());
}
```

A query model can get a change set too, with `@QueryModel(generateChanges = true)`. It covers the model's root,
non-key columns; joined and filter-only columns are left out. `OrderViewChanges.from(view, QOrderView.EDITABLE)` copies
the named columns from a model instance, nulls included. There is deliberately no "ignore nulls" copy, because that is
how PATCH endpoints lose the ability to clear a field.

**Update definition.**

```java
ModelUpdate<OrderEntity, OrderPatch> u = QOrderPatch.update(changes)   // or ModelUpdate.builder(QOrderPatch.ROOT)
        .set(QOrderPatch.UPDATED_AT, now)                  // extra assignment; a column set twice throws at build()
        .setNull(QOrderPatch.NOTE)                         // set(col, null) throws: NULL must be explicit
        .setExpression(QOrderPatch.TOTAL, (path, cb) -> cb.prod(path, rate))   // escape hatch
        .where(f -> f.lt(QOrderPatch.CREATED_AT, cutoff)
                     .eq(QOrderPatch.CUSTOMER_COUNTRY, country))   // @FilterColumn: rendered as EXISTS (R16)
        .all()                                             // required only when no filter is left (R18)
        .expectVersion(version)                            // AND version = ?; needs whereKey, 0 rows → OptimisticLockException
        .keepVersion()                                     // opt out of the default version increment (R22)
        .chunked(ChunkOptions.size(1_000))                 // optional: key-first chunks (R23)
        .build();
```

- `set` only accepts `ColumnField<M, E, C>`, a column whose table is the root entity type `E`, so most joined columns
  fail to compile. A join back to the same entity type (a self-reference) passes the type check and throws at
  `build()`.
- `set(Changes<M>)` adds every column the change set marked. An empty change set makes the whole update a no-op:
  `update` returns 0 without running SQL.
- `whereKey(key)` and `whereKeys(keys)` filter by the model's `@PrimaryKey` (single or composite). `whereKeys` splits
  long lists to the vendor's limits (R12).

**Delete definition.**

```java
long d = executor.delete(QOrderView.delete()
        .where(f -> f.eq(QOrderView.STATUS, "DRAFT").lt(QOrderView.CREATED_AT, cutoff))
        .chunked(ChunkOptions.size(5_000))
        .build());
```

`ModelDelete` has the same `where`, `whereKey(s)`, `all()` and `chunked(...)` options as `ModelUpdate`. Soft deletes
are updates (`set(DELETED, true)`); entities mapped with Hibernate's `@SoftDelete` get that from Hibernate itself.

**Transactions.** Bulk writes need an active transaction. The executor checks with `EntityManager#isJoinedToTransaction`
and fails fast with a message naming the operation, instead of the provider's `TransactionRequiredException` at the
end. The Spring module runs them in a transaction automatically, like `SimpleJpaRepository`'s modifying methods.
Chunked writes run every chunk in the caller's transaction. In the Spring module, `ChunkOptions.commitEachChunk()`
commits after each chunk instead, which keeps locks and undo logs short at the cost of atomicity.

---

## 5. Correctness requirements for the engine

Earlier in-house versions of this pattern hit each of R1–R15. R16–R23 cover bulk writes (§4.6). Every one of them is a
release requirement with a TCK test (§9.2).

| # | Requirement | Design |
|---|---|---|
| R1 | **Offset export visits every row exactly once.** | Always order by a unique key: the engine appends the primary-key columns as a tie-breaker to whatever order is given. Without a stable order, `LIMIT/OFFSET` pages overlap or skip rows. |
| R2 | **Export memory is bounded.** | No global "seen IDs" set. With a stable order, duplicates can only appear at a page boundary, so dedupe only against the previous page's keys. |
| R3 | **Export fails loudly when the primary key isn't selected.** | If `primaryKey` is set and a row's key is `null`, throw with a message naming the model. The primary-key columns are added to the selection automatically, and keys are read from the `Row`, so this works for classes and records alike. |
| R4 | **Keyset paging doesn't skip ties.** | The primary key is appended as the last keyset column, with the direction of the last order column. Keyset without a primary key is a build-time error. Every order column is added to the selection, so the cursor can read its value from the `Row` even when the column is filter-only or not in the chosen `ColumnSet`. |
| R5 | **Keyset paging doesn't truncate on NULL.** | By default, a NULL in a keyset column throws. With explicit `nullsFirst()`/`nullsLast()`, the keyset predicate adds the matching `IS NULL` branches, and the `ORDER BY` renders explicit null precedence so every vendor sorts the same way. |
| R6 | **`count` is right for grouped queries.** | Count groups, not rows: `select count(*) from (<grouped query>)` via Hibernate's `SelectionQuery#getResultCount()`. The portable fallback counts rows client-side and logs a warning. |
| R7 | **`count` isn't inflated by collection joins.** | After building predicates, inspect `root.getJoins()` recursively. Use `count(distinct root)` only when a collection (one-to-many) join exists. |
| R8 | **`NO_COUNT` pages are well-formed.** | Fetch `limit + 1` rows to compute `hasNext`. Keep the page number and size. Totals are reported as unknown. |
| R9 | **Page size and limits are validated.** | `limit == 0` returns an empty result without querying. `pageSize <= 0` throws. |
| R10 | **Streams never leak connections.** | The public API is `stream(q, limit, Function<Stream<M>, R>)`, and the engine closes the stream. There is no method that returns an open `Stream`. |
| R11 | **Column types match entity attributes.** | Checked at compile time by the processor (§6.4). Hand-written columns are checked at first path resolution (`path.getJavaType()` against `ColumnField.type`, with a converter allow-list). |
| R12 | **Primary-key-first paging respects database limits.** | For deep offsets, step 1 selects keys and step 2 selects models `WHERE pk IN (...)`. The batch size is clamped to the vendor's IN-list and bind-parameter limits (§7.3). |
| R13 | **One path, one join.** | Joins are cached by join key (parent key, attribute, type, alias), never by object identity. Selecting, filtering and ordering on the same path uses one join; a second join needs `as(...)` (§4.1). |
| R14 | **`or` and `not` don't lose rows through joins.** | Joins first created inside `or`/`not` are LEFT (§4.4). |
| R15 | **Filter values never change the query's meaning by accident.** | Empty `Optional` skips; an empty collection means "none"; negation includes NULLs; `like` input is escaped; long IN lists are split to the vendor's limit (§4.4). |
| R16 | **Bulk writes never join.** | `CriteriaUpdate` and `CriteriaDelete` have a root and no joins. Filters on root columns render directly. Filters that need a join are built as a correlated sub-query over the root with the normal `JoinContext` and rendered as `EXISTS (SELECT 1 FROM orders o2 JOIN … WHERE o2.pk = o.pk AND …)`, which works for composite keys too. `exists(...)` filters stay correlated sub-queries. |
| R17 | **Bulk writes work where the database can't read the target table in a sub-query.** | MySQL rejects `UPDATE`/`DELETE` whose sub-query reads the target table (error 1093). When `VendorProfile.targetTableInSubquery()` is false and R16 needs a sub-query, the engine runs key-first: select matching keys with the query engine, then write `WHERE pk IN (…)` in chunks sized by R12. The Javadoc notes that rows committed by others between the two steps aren't touched. |
| R18 | **No accidental full-table writes.** | If no predicate is left after skipping empty `Optional`s, `build()` throws unless `all()` was called. A forgotten filter or an all-empty request can't update or delete every row. `in(col, List.of())` still renders `FALSE` and affects nothing (R15). |
| R19 | **Only what was set is written.** | The `SET` clause contains exactly the columns marked in the change set plus explicit `set`/`setNull`/`setExpression` calls, in declaration order. "Set to NULL" writes NULL; "not set" writes nothing. An empty assignment list returns 0 without SQL. Primary-key columns and the `@Version` attribute are never assignable. A column assigned twice throws at `build()`. |
| R20 | **Assigned values are typed and bound.** | Values pass through the column's converter and are always bind parameters, never inlined. A to-one attribute set by id binds `EntityManager#getReference(target, id)`, so no row is loaded. Types are checked like R11. |
| R21 | **The persistence context isn't left stale.** | Before the statement: `flush()`, so pending entity changes are written first and not overwritten afterwards. After it: `clear()` by default (`PersistenceContextMode.CLEAR`), or nothing with `KEEP`. Hibernate invalidates the second-level cache region of the entity for bulk statements. |
| R22 | **Bulk updates respect optimistic locking.** | When the root has a `@Version` attribute, every update renders `version = version + 1` (or the current timestamp for timestamp versions) unless `keepVersion()` is set. `expectVersion(v)` with `whereKey` adds `AND version = ?`; 0 affected rows throws `OptimisticLockException`. |
| R23 | **Chunked writes visit every matched row once and terminate.** | Keyset over the primary key: select the next `n` matching keys `WHERE <filters> AND pk > :last ORDER BY pk`, write `WHERE pk IN (…)`, repeat until a chunk is short. Because the cursor moves forward on the key, an update that leaves rows still matching can't loop forever, and a delete never re-reads what it removed. |

Export algorithm (one loop for both modes):

```
cursor = start (offset 0, or empty keyset)
loop:
    rows = fetch(query + stable order + cursor predicate/offset, pageSize)
    fresh = rows minus keys seen on previous page      # offset mode only
    for item in pageTransformer(fresh): sink(item) until limit reached
    if rows.size < pageSize or limit reached: stop
    cursor = next(cursor, rows)                        # keyset: last row's key; offset: += rows.size
```

---

## 6. Annotation processor

### 6.1 Annotations

| Annotation | Target | Purpose |
|---|---|---|
| `@QueryModel(root = X.class, generateColumnSets = true, generateChanges = false, prefix = "Q")` | model class or record | Enables generation. `generateChanges` also generates a change set over the root, non-key columns (§6.5). |
| `@UpdateModel(root = X.class, prefix = "Q")` | class or record | Declares the attributes a bulk update may write, and generates columns and a change set for them (§6.5) |
| `@PrimaryKey` | field or record component | Primary-key column(s). Composite keys are supported. |
| `@Column(attribute = "...", converter = Foo.class)` | field or component | Rename the attribute or convert the value (`ColumnConverter<C, F>`) |
| `@Join(attribute = "...", type = LEFT, prefix = "CUSTOMER", alias = "")` | `Optional<NestedModel>` field (initialised to `Optional.empty()`) or component | Join the association and reuse the nested model's QModel columns (§6.3, nested models). Two `@Join`s on the same attribute get the field name as their alias automatically. |
| `@FilterColumn(name = "...", path = "...", joinType = LEFT, alias = "", converter = Foo.class)` | model class or record (repeatable) | Generate a filter-only column: a `ColumnField` constant with no model field, left out of every generated `ColumnSet` and of `map(Row)`. Filter columns with the same `alias` share one separate join. |
| `@ExcludeFromDefaults` | field or component | Leave the column out of `DEFAULT` (heavy BLOB/TEXT columns) |
| `@Transient` | field or component | Not a column |

### 6.2 Generated `QOrderView`

```java
@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderView {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    public static final TableField<OrderEntity, CustomerEntity> CUSTOMER_TABLE =
            TableField.join(ROOT, "customer", JoinType.LEFT);

    public static final ColumnField<OrderView, OrderEntity, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    public static final ColumnField<OrderView, OrderEntity, String> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", String.class);
    public static final ColumnField<OrderView, OrderEntity, BigDecimal> TOTAL = …;
    public static final ColumnField<OrderView, OrderEntity, Instant> CREATED_AT = …;

    public static final ColumnField<OrderView, CustomerEntity, Long> CUSTOMER_ID =
            QCustomerView.ID.withTable(OrderView.class, CUSTOMER_TABLE);
    public static final ColumnField<OrderView, CustomerEntity, String> CUSTOMER_NAME = …;

    // @FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country"): reuses CUSTOMER_TABLE, not mapped
    public static final ColumnField<OrderView, CustomerEntity, String> CUSTOMER_COUNTRY =
            ColumnField.of(OrderView.class, CUSTOMER_TABLE, "country", String.class);

    public static final ColumnSet<OrderView> ALL         = ColumnSet.of(ID, STATUS, TOTAL, CREATED_AT);
    public static final ColumnSet<OrderView> DEFAULT     = ALL.without(/* @ExcludeFromDefaults */);
    public static final ColumnSet<OrderView> CUSTOMER    = ColumnSet.of(CUSTOMER_ID, CUSTOMER_NAME);
    // filter-only columns are in no generated set

    public static final RowMapper<OrderView> MAPPER = QOrderView::map;

    public static ModelQuery.Builder<OrderEntity, Long, OrderView> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(PrimaryKey.of(ID));
    }

    // Record model: one canonical-constructor call
    static OrderView map(Row row) {
        return new OrderView(
                row.get(ID),
                OrderStatusConverter.INSTANCE.toModel(row.get(STATUS)),
                row.get(TOTAL),
                row.get(CREATED_AT),
                row.get(CUSTOMER_ID) == null
                        ? Optional.empty()                                   // not selected, or LEFT-join miss
                        : Optional.of(QCustomerView.map(row.scoped(CUSTOMER_TABLE))));
    }

    // Class model: the same method, generated with setters instead
    //   OrderView m = new OrderView();
    //   if (row.isSelected(ID)) m.setId(row.get(ID));
    //   ...
    //   m.setCustomer(row.get(CUSTOMER_ID) == null ? Optional.empty() : Optional.of(QCustomerView.map(row.scoped(CUSTOMER_TABLE))));
    //   return m;

    private QOrderView() {}
}
```

Joined column sets come from the nested model's QModel. A column added to `CustomerView` appears in
`QOrderView.CUSTOMER` without any manual list to update.

**Naming.** `Q` + model name, in the same package. The prefix and suffix are configurable (`-Amodelquery.prefix=`,
`-Amodelquery.suffix=`). If a project also uses Querydsl on the same classes, it should set a different prefix.
Collisions are unlikely anyway, because Querydsl generates for entities and this processor generates for models.

### 6.3 Processing model

- Reads the `root` entity's `TypeElement`, from source in the same compilation or from the classpath. It doesn't
  depend on `hibernate-jpamodelgen` output, so the order in which processors run doesn't matter.
- Understands `@Id`, `@EmbeddedId`, `@IdClass`, `@Column`, `@Embedded`, `@ManyToOne`, `@OneToOne`, `@OneToMany`,
  `@ManyToMany`, `@Access(FIELD|PROPERTY)` and `@MappedSuperclass` inheritance.
- Attribute names are emitted as string literals that the processor validated.
- **Records:** mapped through the canonical constructor, with components passed in declaration order. Components that
  weren't selected get `null`, and `Optional` components get `Optional.empty()`. Compact constructors that reject
  `null` will fail on partial column sets; the user guide says to keep validation out of query models.
- **Classes:** need a no-arg constructor (it may be non-public if it's visible from the QModel's package). Setters are
  only called for selected columns, so field initialisers survive for unselected ones.
- **Setters:** generated code calls setters by name (`m.setId(...)`). These calls are resolved when
  javac attributes the code, which happens after all annotation processors (including Lombok) have run. So
  Lombok-generated setters work, as long as the processor follows Lombok's naming rules (for example, a `Boolean isX`
  field gets `setIsX`, while a primitive `boolean isX` gets `setX`).
- Registered as an **isolating** incremental processor for Gradle. Each model produces exactly one file.
- JavaPoet is shaded into the processor jar.

**Filter-only columns.** Each `@FilterColumn` on the model type generates one `ColumnField` constant:

- `path` is a dotted attribute path from `root` (`"deleted"`, `"customer.country"`, `"customer.address.city"`). It is
  validated like any other attribute, and the constant's type `C` is the entity attribute's type (or the converter's
  model type when `converter` is set).
- Joins are shared. If a path prefix matches a `@Join` (here `customer` → `CUSTOMER_TABLE`), that `TableField` is
  reused, so filtering and selecting the customer never joins it twice. Any other association on the path gets its own
  generated `TableField` (`<PREFIX>_TABLE`) with `joinType` (default `LEFT`).
- `alias` puts the whole path on a separate join: `@FilterColumn(name = "SKU_B", path = "items.sku", alias = "itemB")`
  generates `ITEMS_ITEMB_TABLE = TableField.join(ROOT, "items", LEFT).as("itemB")`. Filter columns with the same alias
  share it. Join `ON` conditions can't be expressed in an annotation (they are lambdas); declare that `TableField` by
  hand and build the column with `ColumnField.of(...)`, or use `Filters.exists` with an inner group.
- Every collection association on the root also gets a generated `TableField` constant (`ITEMS_TABLE`), so
  `Filters.exists(QOrderView.ITEMS_TABLE, ...)` works without hand-written joins.
- The constant isn't in `ALL`, `DEFAULT` or any join set, and `map(Row)` never reads it.
- Filter-only columns can be used in `orderBy` too. With keyset paging the engine selects them automatically (R4).

**Nested models.** A `@Join` field or record component is always declared as `Optional<NestedModel>` (fields are
initialised to `Optional.empty()`):

```java
@Join(attribute = "customer")
private Optional<CustomerView> customer = Optional.empty();
```

- **Empty means "no data".** The field is `Optional.empty()` when none of the nested model's columns were selected,
  or when a LEFT join found no matching row. It is never `null` and never a nested model with all fields `null`.
- **Presence follows the joined primary key.** When any column of a join is selected, the engine also selects that
  join's `@PrimaryKey` columns (like R3 does for the root). A LEFT-join miss makes every joined column `NULL`, so the
  nested model stays empty. A matched row has a non-null key, so the nested model is present even if all its other
  selected columns are `NULL`.
- **Deeper nesting works the same way.** `OrderView.customer → CustomerView.address` yields
  `Optional<CustomerView>` containing `Optional<AddressView>`. Class and record models can be nested in each other
  freely, since each QModel's `map(Row)` builds its own model.
- **Always assigned.** The generated mapper always sets the `@Join` field or component, to `Optional.empty()` or
  `Optional.of(...)`, so even a class field without an initialiser is never `null` after mapping.
- **Serialization.** `Optional` fields need Jackson's `jdk8` module (registered by default in Spring Boot). This is in
  the user guide.

### 6.4 Compile-time diagnostics

| Check | Example |
|---|---|
| Unknown attribute | `OrderView.totl: no attribute 'totl' on OrderEntity` |
| Model type isn't assignable from the entity type and has no converter | `OrderView.id: model type Integer, entity attribute type Long` |
| `@Join` attribute isn't an association, or the nested model's `root` doesn't match the association target | `OrderView.customer: CustomerView.root is AccountEntity, association targets CustomerEntity` |
| Missing `@PrimaryKey` | `OrderView: no @PrimaryKey; paging, export and @Join presence need one` |
| `@Join` field isn't `Optional<X>`, or `X` isn't a `@QueryModel` | `OrderView.customer: @Join field must be Optional<CustomerView>, found CustomerView` |
| Nested model has no `@PrimaryKey` (presence can't be decided) | `OrderView.customer: CustomerView needs a @PrimaryKey to be used in @Join` |
| Join cycle between nested models | `OrderView.customer → CustomerView.lastOrder → OrderView` |
| Class model has no no-arg constructor visible from its package | `OrderView: needs a no-arg constructor for setter mapping` |
| Record component is primitive (`int`, `boolean`, …) and not `@PrimaryKey` | `OrderView.count: primitive components can't be null when not selected; use Integer` |
| Record has a non-canonical constructor only, or is generic | … |
| `@FilterColumn` path doesn't resolve, or passes through a collection without an explicit `joinType` | `OrderView @FilterColumn(CUSTOMER_COUNTRY): no attribute 'contry' on CustomerEntity` |
| Two `@FilterColumn`s with the same `alias` and path prefix but different `joinType` | `OrderView @FilterColumn(SKU_B): alias 'itemB' is INNER here, LEFT on SKU_B_QTY` |
| `@FilterColumn` name clashes with a generated constant | `OrderView @FilterColumn(STATUS): name already used by field 'status'` |
| Update-model field maps through a join or a collection | `OrderPatch.customerName: update models can only write attributes of OrderEntity; 'customer.name' needs a join` |
| `@Join` on an update model | `OrderPatch.customer: @Join isn't allowed on @UpdateModel; write the foreign key with @Column(attribute = "customer") Long customerId` |
| Update-model field maps to the primary key without `@PrimaryKey`, or to the `@Version` attribute | `OrderPatch.version: the @Version attribute is managed by the engine (keepVersion, expectVersion)` |
| Update-model field maps to an attribute that can't be written | `OrderPatch.createdAt: OrderEntity.createdAt is @Column(updatable = false)` |
| To-one attribute written by id with the wrong id type | `OrderPatch.customerId: CustomerEntity's id is Long, found String` |

The processor is tested with `com.google.testing.compile:compile-testing`: one case per diagnostic, Lombok on and off,
classes and records (and records nested in classes and vice versa), composite and embedded keys, nested joins,
`@FilterColumn` paths sharing and not sharing a `@Join`, and golden files for the generated sources.

The same compile-testing suite covers update models: records and classes, converters, to-one by id, composite keys in
`whereKey`, and golden files for `QOrderPatch` and `OrderPatchChanges`.

A missing setter on a class model isn't a processor diagnostic, because Lombok-generated setters aren't reliably
visible to other processors. javac reports it when compiling the generated `QOrderView.map`, pointing at the call.

### 6.5 Generated update models

For `@UpdateModel OrderPatch` (§4.6) the processor generates two files, both in the model's package.

```java
@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderPatch {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    public static final ColumnField<OrderPatch, OrderEntity, Long> ID = …;              // @PrimaryKey: whereKey only
    public static final ColumnField<OrderPatch, OrderEntity, String> STATUS = …;
    public static final ColumnField<OrderPatch, OrderEntity, Long> CUSTOMER_ID = …;     // to-one by id
    public static final ColumnField<OrderPatch, OrderEntity, Instant> CREATED_AT = …;   // @FilterColumn
    public static final ColumnField<OrderPatch, CustomerEntity, String> CUSTOMER_COUNTRY = …;
    // …

    public static OrderPatchChanges changes() { return new OrderPatchChanges(); }

    public static ModelUpdate.Builder<OrderEntity, OrderPatch> update(Changes<OrderPatch> changes) {
        return ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(changes);
    }

    public static ModelDelete.Builder<OrderEntity, OrderPatch> delete() {
        return ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID));
    }

    private QOrderPatch() {}
}

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class OrderPatchChanges implements Changes<OrderPatch> {
    private final BitSet set = new BitSet();          // one bit per writable column, in declaration order
    private OrderStatus status;
    // …

    public OrderPatchChanges() {}                     // for Jackson and other binders

    public OrderPatchChanges status(OrderStatus value) { this.status = value; set.set(0); return this; }
    public void setStatus(OrderStatus value) { status(value); }     // JavaBean form: binders call it only when present
    public OrderStatus getStatus() { return status; }
    // … one fluent setter, JavaBean setter and getter per writable field

    @Override public boolean isSet(ColumnField<OrderPatch, ?, ?> column) { … }
    @Override public OrderPatchChanges unset(ColumnField<OrderPatch, ?, ?> column) { … }
    @Override public boolean isEmpty() { return set.isEmpty(); }
    @Override public List<Assignment<OrderPatch, ?>> assignments() {
        // set columns only, in declaration order; converters applied, e.g. STATUS ← converter.toEntity(status)
    }
}
```

- The update model itself is only a declaration. It is never instantiated, so a record is the shortest way to write
  one.
- Only root attributes (including embedded ones, as a dotted `@Column(attribute = "address.city")`) and to-one
  associations written by id are writable. `@PrimaryKey` fields get a column constant but no setter.
- `@FilterColumn` works as on query models. Joined filter columns are rendered through R16.
- For `@QueryModel(generateChanges = true)`, `QOrderView` gains `changes()` and `update(changes)`, and
  `OrderViewChanges` gains `static OrderViewChanges from(OrderView model, ColumnSet<OrderView> columns)`, which reads the
  model's getters or record accessors. Columns in `columns` that aren't writable throw.
- `QOrderView.delete()` is generated for every query model with a `@PrimaryKey`, since a delete writes no columns.

---

## 7. Multi-vendor support

### 7.1 Support tiers

| Tier | Database | Versions | CI |
|---|---|---|---|
| 1 | H2 | 2.2+, native mode | every PR |
| 1 | PostgreSQL | 14, 15, 16, 17 | every PR (Testcontainers) |
| 1 | MySQL | 8.0, 8.4 | every PR (Testcontainers) |
| 2 (next) | MariaDB | 10.11, 11.x | nightly, after Tier 1 is stable |
| 3 (community) | Oracle, SQL Server, others | — | profile contributions welcome, run nightly when present |

A Tier-1 release requires the whole TCK to pass on every Tier-1 version.

### 7.2 `VendorProfile` SPI

```java
public interface VendorProfile {

    DatabaseVendor vendor();                                   // H2, POSTGRESQL, MYSQL, MARIADB, ORACLE, SQLSERVER, OTHER

    int maxInListSize();                                       // soft limit for IN (...)

    int maxBindParameters();                                   // hard limit per statement

    void applyStreaming(Query query, int fetchSize);           // forward-only streaming of large results

    default void checkStreamingPreconditions(EntityManager em) {}

    void applyTimeout(Query query, Duration timeout);

    NullOrdering defaultAscendingNullOrdering();               // NULLS_FIRST, NULLS_LAST, UNKNOWN

    boolean targetTableInSubquery();                           // may UPDATE/DELETE read their own table in a sub-query (R17)
}
```

Profiles are discovered with `ServiceLoader`. Spring users can also register them as beans.

### 7.3 Tier-1 profiles

| Concern | H2 | PostgreSQL | MySQL |
|---|---|---|---|
| Streaming | positive `fetchSize` (hint `org.hibernate.fetchSize`) | positive `fetchSize`. The driver only uses a cursor when **autocommit is off**, so the query must run inside a transaction. `checkStreamingPreconditions` fails fast outside one. | `fetchSize = Integer.MIN_VALUE` (row-by-row streaming). Alternative mode: `useCursorFetch=true` + positive fetch size, chosen via config. |
| Timeout | `jakarta.persistence.query.timeout` | `jakarta.persistence.query.timeout` | `jakarta.persistence.query.timeout` |
| Max IN list (soft) | 10 000 | 10 000 | 10 000 |
| Max bind parameters | 100 000 | 65 535 | 65 535 |
| NULLs in ASC order | first | **last** | first |
| Explicit `NULLS FIRST/LAST` | native | native | emulated by Hibernate (`ISNULL(col)` sort) |
| Keyset row-value `(a,b) > (?,?)` | yes | yes | yes. A possible later optimisation; the default is the portable OR-expansion. |
| Target table in an `UPDATE`/`DELETE` sub-query | yes | yes | **no** (error 1093), so joined filters run key-first (R17) |

Notes:

- `jakarta.persistence.query.timeout` becomes `Statement.setQueryTimeout`, which has **one-second granularity**.
- MySQL Connector/J implements `setQueryTimeout` by opening a second connection to run `KILL QUERY`. That works, but
  it costs an extra connection per cancelled query. Keep pool sizing in mind when timeouts are short.
- The `OTHER` profile is conservative: fetch size 500, JPA timeout, IN list 1 000, bind parameters 2 000, NULL
  ordering `UNKNOWN`, and no target table in sub-queries (bulk writes with joined filters run key-first). Keyset
  paging on nullable columns without explicit null precedence is refused.

### 7.4 Vendor detection

1. An explicit `ModelQueryConfig.vendor(...)` or the `modelquery.vendor` property.
2. If `model-query-hibernate` is present: `SessionFactoryImplementor#getJdbcServices().getDialect()`, mapped by dialect
   class. This needs no connection and is resolved per `EntityManagerFactory`, so apps with several datasources on
   different databases work.
3. Otherwise: `DatabaseMetaData#getDatabaseProductName()`, read once per `EntityManagerFactory` and cached.

### 7.5 Keyset predicate generation

For order `(a ASC, b DESC, id ASC)` and last key `(ka, kb, kid)`:

```
(a > :ka)
OR (a = :ka AND b < :kb)
OR (a = :ka AND b = :kb AND id > :kid)
```

With `a.nullsLast()` and `ka = NULL`, the branches that compare `a` become `a IS NULL AND …`. With `ka` not null, a
branch `OR a IS NULL` is added, because under NULLS LAST all NULLs come after every non-null value. The TCK checks each
combination (ASC/DESC × NULLS FIRST/LAST × null/non-null key) on every Tier-1 database.

### 7.6 What the library doesn't abstract

Documented in the user guide:

- String comparison and case sensitivity follow column collation (MySQL `*_ci` is case-insensitive, PostgreSQL is
  case-sensitive).
- JSON, array and enum column types follow the entity mapping.
- Boolean storage and date/time precision follow Hibernate and the JDBC driver. Time zones follow
  `hibernate.jdbc.time_zone`.

---

## 8. Integrations

### 8.1 Spring Data

```java
@NoRepositoryBean
public interface ModelQueryRepository<E, ID> extends JpaRepository<E, ID> {
    <M> Page<M> findPage(ModelQuery<E, ?, M> q, Pageable pageable, CountMode mode);
    <M> List<M> findAll(ModelQuery<E, ?, M> q, Limit limit);
    long count(ModelQuery<E, ?, ?> q);
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options, Function<List<M>, List<S>> t, Consumer<S> sink);
    long update(ModelUpdate<E, ?> u);                // @Transactional
    long delete(ModelDelete<E, ?> d);                // @Transactional
}
```

- `ModelQueryRepositoryFactoryBean` is set on `@EnableJpaRepositories(repositoryFactoryBeanClass = …)`.
  The starter configures it for the default `@EnableJpaRepositories` automatically.
- `Pageable`/`Sort` are converted to `PageSpec`/`SortSpec`. Sort properties resolve against the root entity's
  attribute paths, or against `ColumnField`s by name when the sort names a column.
- `stream(...)` opens a read-only transaction when none is active, which PostgreSQL needs for cursor streaming.
- `update(...)` and `delete(...)` join the current transaction or open one, like the modifying methods of
  `SimpleJpaRepository`. `ChunkOptions.commitEachChunk()` runs each chunk in its own `REQUIRES_NEW` transaction.
- Change sets bind from request bodies with no extra configuration (§4.6).

### 8.2 Configuration (`modelquery.*` in the starter)

| Property | Default | Meaning |
|---|---|---|
| `modelquery.vendor` | auto | Override detection |
| `modelquery.export.page-size` | 1000 | Default export page size |
| `modelquery.primary-key-first.batch-size` | 1000 | Step-2 batch size, clamped per vendor |
| `modelquery.stream.fetch-size` | 500 | Ignored by MySQL in streaming mode |
| `modelquery.mysql.streaming-mode` | `row-by-row` | or `cursor-fetch` |
| `modelquery.query-timeout` | none | Default per-query timeout |
| `modelquery.keyset.null-keys` | `fail` | `fail` or `honour-null-precedence` |
| `modelquery.mutation.persistence-context` | `clear` | `clear` or `keep` after a bulk write (R21) |
| `modelquery.mutation.chunk-size` | 1000 | Default size for `chunked(...)`, clamped per vendor |

---

## 9. Testing

### 9.1 Unit tests (per module)

- `core`: join resolution, column sets, keyset predicate trees, filters. Uses Hibernate's `CriteriaBuilder` against an
  in-memory H2 metamodel. No mocks of JPA internals.
- `processor`: compile-testing suite (§6.4), including update models (§6.5).
- Rendered-SQL snapshots: a test-only JDBC proxy (`datasource-proxy`) captures the SQL for about 30 reference queries on each vendor
  into `src/test/resources/sql/<vendor>/*.sql`. A PR that changes generated SQL shows the change as a diff.

### 9.2 TCK (`model-query-tck`)

A JUnit 5 suite parameterized by vendor, with a shared fixture schema: `customers`, `orders`, `order_items`, a table
with a composite key, a table with nullable sort columns, and about 20 000 seeded rows.

| Group | Tests |
|---|---|
| Mapping | every `ColumnField` type, converters, nested joins, LEFT vs INNER, `withTable`, nested `Optional` empty on LEFT-join miss and present on match with all-NULL columns; every case runs for a class model and an equivalent record model; filter-only columns filter correctly, add no join when their filter is skipped, and never reach the model |
| Paging | page / `NO_COUNT` / `ONLY_COUNT` (R8, R9) |
| Export, offset | every row exactly once with duplicated sort keys, bounded dedupe, missing primary key throws (R1–R3) |
| Filters | every operator in both value and `Optional` forms on every vendor; skipping inside `or`/`not`/`exists`; empty `in` matches nothing; `ne`/`notIn` include NULLs; `like` with `%`, `_` and `\` in the value; IN lists above the vendor limit; `or` branch over a LEFT-joined column keeps rows without the join (R14, R15) |
| Joins | select + filter + order on one path render one join; `as(...)` renders two; children of an aliased join stay separate; `on(...)` keeps LEFT semantics; same key with different `on` throws; `exists` over an aliased path (R13) |
| Export, keyset | ties (R4), ordering by a filter-only / unselected column, NULL keys fail by default, NULL keys with explicit precedence across all combinations (R5) |
| Count | plain, grouped (R6), collection join (R7) |
| Primary-key first | single and composite keys, batches crossing the vendor's limits (R12) |
| Streaming | 20 000 rows with a bounded heap assertion, early exit releases the connection (checked with the pool's active count), PostgreSQL without a transaction fails fast (R10) |
| Timeout | a slow query is cancelled (`SLEEP()` / `pg_sleep()` / H2 user function) |
| Detection | each container resolves to the expected profile |
| Mutations | change sets write only set columns, NULL vs not set, converters and to-one by id (R19, R20); joined filters render as `EXISTS` and key-first on MySQL (R16, R17); a write with every filter skipped throws unless `all()` (R18); empty change set runs no SQL; version increment, `keepVersion`, `expectVersion` mismatch throws (R22); persistence context cleared or kept, pending changes flushed first (R21); chunked update that leaves rows matching terminates and touches each row once, chunked delete across the vendor's IN limits (R23); no transaction fails fast; a delete blocked by a foreign key surfaces the provider's constraint exception |

### 9.3 CI

GitHub Actions:

- **PR:** build, unit tests, TCK on H2 + PostgreSQL 17 + MySQL 8.4, on JDK 17 and 21.
- **Nightly:** full version matrix (PostgreSQL 14–17, MySQL 8.0/8.4, Hibernate 6.6/7.x, JDK 17/21/25) plus any Tier-2/3 profiles.
- Code coverage (JaCoCo), mutation testing on `core` keyset and paging code (PIT), dependency and CVE scanning.

---

## 10. Open-source project setup

| Item | Choice |
|---|---|
| License | Apache License 2.0 |
| Coordinates | `groupId` `io.github.rey5137`, artifacts `model-query-*` (e.g. `io.github.rey5137:model-query-core`). The namespace is verified on Maven Central through the `rey5137` GitHub account. The Java package (`com.rey.modelquery`) intentionally differs from the `groupId`. |
| Base package | `com.rey.modelquery` (sub-packages per module: `.core`, `.jpa`, `.hibernate`, `.processor`, `.spring.data`, `.spring.boot`) |
| Hosting | GitHub repository, with `main` protected |
| Publishing | Maven Central through the Central Portal (`central-publishing-maven-plugin`), GPG-signed, `-sources` and `-javadoc` jars |
| Versioning | SemVer. `0.x` until the API is frozen, then `1.0.0`. |
| Release automation | Tag → GitHub Actions → Central. Changelog from Conventional Commits. |
| Docs | `README` (quick start), a docs site (MkDocs or Antora) with a user guide, a vendor notes page, and a migration guide from hand-written columns |
| Community | `CONTRIBUTING.md` (including "how to add a VendorProfile"), `CODE_OF_CONDUCT.md`, issue/PR templates, `SECURITY.md` |
| API stability | `@Incubating` annotation for APIs that may change. `japicmp` in CI checks binary compatibility from `1.0` on. |
| Samples | `samples/plain-jpa` and `samples/spring-boot-multi-datasource` (H2 + PostgreSQL + MySQL via Docker Compose) |

---

## 11. Milestones

| Milestone | Contents | Exit criteria |
|---|---|---|
| **M0: Bootstrap** | Repo, license, Maven multi-module build, CI skeleton, ArchUnit rules, TCK harness with H2/PostgreSQL/MySQL containers | CI green on an empty TCK |
| **M1: Core** | `TableField`, `ColumnField`, `OrderField`, `ColumnSet`, `Row`, `RowMapper` (setter and constructor mapping), `JoinContext` (join keys, `as`, `on`), `ModelQuery` builder, full `Filters` DSL including `or`/`not`/`exists`, `QueryCustomizer` | Unit tests. Rendered-SQL snapshots for H2. |
| **M2: Engine** | `ModelQueryExecutor`: list, page, count, stream, export (offset + keyset + primary-key first). Requirements R1–R12. | TCK green on the Tier-1 databases |
| **M3: Vendors** | Tier-1 `VendorProfile`s, detection, `model-query-hibernate` (grouped count, null precedence) | TCK green on the full nightly matrix |
| **M4: Processor** | Annotations + processor + diagnostics, for class and record models | compile-testing suite green. Samples use only generated QModels. |
| **M5: Spring** | `spring-data` module + starter + properties | Spring Boot sample with several datasources on H2, PostgreSQL and MySQL |
| **M6: 0.1.0 release** | Docs site, samples, Maven Central publishing | First public release |
| **M7: Hardening → 1.0.0** | Early-adopter feedback, API review, `japicmp` baseline, MariaDB Tier 2 | API frozen |
| **M8: Bulk writes** | `ModelUpdate`, `ModelDelete`, `Changes`, `@UpdateModel` and `generateChanges` in the processor, executor and repository methods, chunked mode, `VendorProfile.targetTableInSubquery`. Requirements R16–R23. | TCK mutation group green on the Tier-1 databases. compile-testing cases for §6.5. The Spring Boot sample has a PATCH endpoint. |

M3 and M4 can run in parallel after M2. M8 starts after M6, so the first release ships the read API, and runs alongside
M7. Its API is `@Incubating` until the M7 API review, which covers it before 1.0.0.

### 11.1 Milestone status

Milestone status is public through git, not through a tracker file:

- **In progress:** a `mN-<name>` branch exists (`m1-core`, `m2-engine`, ...). All of the milestone's work happens there.
- **Done:** the branch has been merged into `main` after its exit criteria were met, and the merge commit is tagged
  `mN-verified` (`m1-verified`). The branch is then deleted.
- **Not started:** neither a branch nor a tag exists.

`git tag -l 'm*-verified'` lists the finished milestones. Tagging is part of merging the milestone, so there is
nothing else to update.

---

## 12. Adopting from hand-written column definitions

For codebases that already define `ColumnField` constants by hand:

1. **Rename packages.** Ship an OpenRewrite recipe (`com.rey.modelquery.rewrite.MigrateToModelQuery`) that changes the
   imports to the library packages and maps old names (`BaseColumns` → `ColumnSet`, `BaseQuery` → `QuerySpec`,
   subclass hooks → `QueryCustomizer`) where it can do so mechanically.
2. **Keep hand-written constants.** They have the same types as generated ones, so hand-written and generated models
   can live in one codebase.
3. **Convert models one at a time.** Annotate the model with `@QueryModel`, delete its hand-written constants, and
   update references from `Model.X` to `QModel.X`. An OpenRewrite recipe for this reference update is part of M7.
4. **Watch for new failures.** The engine's correctness checks (R3 missing primary key, R5 NULL keysets) turn silent
   data loss into exceptions. Run exports in a test environment first. A temporary
   `modelquery.keyset.null-keys=honour-null-precedence` plus explicit `nullsFirst/Last` fixes most NULL-key cases.

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| Criteria API differences between Hibernate 6 and 7 | CI matrix on both. Version-specific code only in `model-query-hibernate`, behind small adapters. |
| Lombok naming rules drift | compile-testing cases per rule. A mismatch breaks compilation of the generated code loudly. |
| PostgreSQL streaming misuse (no transaction) | Fail fast in `checkStreamingPreconditions`. The Spring module opens a transaction automatically. |
| MySQL row-by-row streaming holds the connection for the whole export | Documented. Keyset `export` (short queries per page) is the recommended default for large exports. `stream` is for single-pass pipelines. |
| `Optional` fields on models are unusual (not `Serializable`, need Jackson `jdk8`) | Documented. Only `@Join` fields use `Optional`, plain columns stay plain types. |
| `CASE WHEN … IS NULL` null-precedence fallback defeats index use | Used only without `model-query-hibernate` and only when the requested precedence differs from the vendor default. Documented in §4.1. |
| API churn before 1.0 | `@Incubating`, `0.x` versions, and an explicit API review at M7 |
| Scope creep toward a general SQL builder | Non-goals in §1. `QueryCustomizer` is the escape hatch for everything else. |
| Bulk writes surprise users who expect entity semantics (listeners, cascades, Envers, Bean Validation don't run) | Stated in the Javadoc of every write method and in the user guide. Flush and clear by default (R21), version increment by default (R22). The TCK pins down what the provider does for join tables and element collections. |
| A change set bound from a request lets clients write fields they shouldn't (mass assignment) | An update model lists exactly the writable fields, so the user guide recommends one per endpoint. `generateChanges` on a query model exposes all its root columns and is documented as for internal use. |

---

## 14. Open questions

1. Project name (the GitHub repository will be `github.com/rey5137/<name>`).
2. Should the MySQL default be row-by-row streaming or `useCursorFetch`? Row-by-row is faster, but it blocks other
   statements on the same connection until the result has been read.
3. Is Hibernate 6.6 the right minimum, or should the library target Hibernate 7 only, since Spring Boot 4 uses it?
4. Should generated change sets copy Bean Validation annotations from the update model's fields, so
   `@Valid @RequestBody OrderPatchChanges` validates the fields that were set?
