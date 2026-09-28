# 31 — Code Generation

**Covers:** the shape of a generated `QModel`, how the processor reads the entity metamodel, records versus classes,
nested models, and incremental-build behaviour.
**Read when:** changing what the processor emits, or debugging why generated code does not compile.
**Owns:** `R-GEN-*`, `AC-GEN-*`. The annotations are `processor/30`; the diagnostics are `processor/32`.

---

## 1. Generated `QOrderView`

```java
@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderView {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    public static final TableField<OrderEntity, CustomerEntity> CUSTOMER_TABLE =
            TableField.join(ROOT, "customer", JoinType.LEFT);

    public static final ColumnField<OrderView, OrderEntity, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    public static final ColumnField<OrderView, OrderEntity, String> STATUS = …;
    public static final ColumnField<OrderView, OrderEntity, BigDecimal> TOTAL = …;

    public static final ColumnField<OrderView, CustomerEntity, Long> CUSTOMER_ID =
            QCustomerView.ID.withTable(OrderView.class, CUSTOMER_TABLE);
    public static final ColumnField<OrderView, CustomerEntity, String> CUSTOMER_NAME = …;

    // @FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country"): reuses CUSTOMER_TABLE, not mapped
    public static final ColumnField<OrderView, CustomerEntity, String> CUSTOMER_COUNTRY =
            ColumnField.of(OrderView.class, CUSTOMER_TABLE, "country", String.class);

    public static final ColumnSet<OrderView> ALL      = ColumnSet.of(ID, STATUS, TOTAL, CREATED_AT);
    public static final ColumnSet<OrderView> DEFAULT  = ALL.without(/* @ExcludeFromDefaults */);
    public static final ColumnSet<OrderView> CUSTOMER = ColumnSet.of(CUSTOMER_ID, CUSTOMER_NAME);

    public static final RowMapper<OrderView> MAPPER = QOrderView::map;

    public static ModelQuery.Builder<OrderEntity, Long, OrderView> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(PrimaryKey.of(ID));
    }

    static OrderView map(Row row) {
        return new OrderView(
                row.get(ID),
                OrderStatusConverter.INSTANCE.toModel(row.get(STATUS)),
                row.get(TOTAL),
                row.get(CREATED_AT),
                row.get(CUSTOMER_ID) == null
                        ? Optional.empty()
                        : Optional.of(QCustomerView.map(row.scoped(CUSTOMER_TABLE))));
    }

    private QOrderView() {}
}
```

**R-GEN-01** The processor reads the `root` entity's `TypeElement`, from source in the same compilation or from the
classpath. It does not consume `hibernate-jpamodelgen` output, so processor ordering does not matter.

**R-GEN-02** It understands `@Id`, `@EmbeddedId`, `@IdClass`, `@Column`, `@Embedded`, `@ManyToOne`, `@OneToOne`,
`@OneToMany`, `@ManyToMany`, `@Access(FIELD|PROPERTY)` and `@MappedSuperclass` inheritance.

**R-GEN-03** Attribute names are emitted as string literals that the processor validated against the metamodel. A
literal the processor could not validate is a diagnostic, never a guess (INV-3).

**R-GEN-04** A joined `ColumnSet` is derived from the nested model's QModel (`QCustomerView`), so a column added to
`CustomerView` appears in `QOrderView.CUSTOMER` with no list to update by hand.

**R-GEN-05** One model produces exactly one file, and the processor is registered as an **isolating** incremental
processor for Gradle. JavaPoet is shaded into the processor jar (INV-7).

## 2. Records

**R-GEN-06** A record model is mapped through its canonical constructor, with components passed in declaration order.
Unselected components get `null`, and `Optional` components get `Optional.empty()`.

**R-GEN-07** A compact constructor that rejects `null` will fail on a partial `ColumnSet`. The user guide says to keep
validation out of query models; this is not something the processor can check.

## 3. Classes

**R-GEN-08** A class model needs a no-arg constructor, which may be non-public as long as it is visible from the
QModel's package.

**R-GEN-09** Setters are called only for selected columns, so field initialisers survive for unselected ones:

```java
OrderView m = new OrderView();
if (row.isSelected(ID)) m.setId(row.get(ID));
…
m.setCustomer(row.get(CUSTOMER_ID) == null ? Optional.empty() : Optional.of(QCustomerView.map(row.scoped(CUSTOMER_TABLE))));
return m;
```

**R-GEN-10** Generated code calls setters by name. Those calls are resolved when javac attributes the code, after every
annotation processor — Lombok included — has run, so Lombok-generated setters work provided the processor follows
Lombok's naming rules: a `Boolean isX` field gets `setIsX`, a primitive `boolean isX` gets `setX`.

**R-GEN-11** A missing setter is therefore not a processor diagnostic. javac reports it when compiling the generated
`map`, pointing at the call (`processor/32` §3).

## 4. Nested models

**R-GEN-12** **Empty means "no data".** A `@Join` field is `Optional.empty()` when none of the nested model's columns
were selected, or when a LEFT join found no matching row. It is never `null`, and never a nested model whose every field
is `null`.

**R-GEN-13** **Presence follows the joined primary key.** When any column of a join is selected, the engine also selects
that join's `@PrimaryKey` columns — as `engine/21` R-PAG-03 does for the root. A LEFT-join miss makes every joined column
`NULL`, so the nested model stays empty; a matched row has a non-null key, so the nested model is present even when all
its other selected columns are `NULL`.

**R-GEN-14** Nesting composes. `OrderView.customer → CustomerView.address` yields `Optional<CustomerView>` containing
`Optional<AddressView>`; classes and records may be nested in each other freely, because each QModel's `map(Row)` builds
its own model.

**R-GEN-15** The generated mapper always assigns a `@Join` field, to `Optional.empty()` or `Optional.of(...)`, so even a
class field with no initialiser is never `null` after mapping.

**R-GEN-16** `Optional` fields need Jackson's `jdk8` module, registered by default in Spring Boot. Stated in the user
guide, not enforced.

## 5. Aggregate models

**R-GEN-17** An `@Aggregate` field generates an `AggregateField` constant and is mapped like any other column. `@GroupBy`
fields form `GROUP_KEYS` in declaration order, and `query()` is emitted with `.groupBy(GROUP_KEYS)` already applied
(`processor/30` R-PROC-16).

**R-GEN-18** For a `singleGroup` model, `query()` is emitted without a `groupBy` and without a `primaryKey`, since a
whole-table aggregate has neither (`api/13` R-AGG-07, R-AGG-09).

## 6. Generated update models — `Future` (M8)

For `@UpdateModel OrderPatch` (`api/14` §2) the processor generates two files, both in the model's package.

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
        // set columns only, in declaration order; converters applied
    }
}
```

**R-GEN-19** Writable are root attributes, including embedded ones as a dotted `@Column(attribute = "address.city")`,
and to-one associations written by id. `@PrimaryKey` fields get a column constant but no setter. `@FilterColumn` works
as on query models; a joined filter column renders through `api/14` R-WRT-10.

**R-GEN-20** A change set is mutable by design and is never a `static final` constant; INV-9 covers definitions, not
change sets.

**R-GEN-21** For `@QueryModel(generateChanges = true)`, `QOrderView` gains `changes()` and `update(changes)`, and
`OrderViewChanges` gains `static OrderViewChanges from(OrderView model, ColumnSet<OrderView> columns)`, which reads the
model's getters or record accessors (`api/14` R-WRT-04).

**R-GEN-22** `QOrderView.delete()` is generated for every query model whose `@PrimaryKey` is the root entity's id,
since a delete writes no columns. A query model whose key is not the id (a unique column on a view, say) gets no
`delete()`; on an `@UpdateModel`, or with `generateChanges = true`, such a key is `MQ3306` (`api/14` R-WRT-08).

**R-GEN-23** A generated change set is annotated `@ValidChanges(OrderPatch.class)`, naming the model whose field
constraints apply (`api/14` R-WRT-21), when `@ValidChanges` and `jakarta.validation.Constraint` both resolve on the
compile classpath; otherwise it carries no annotation (`api/14` R-WRT-22). Constraint annotations on the model's fields
are never copied to the change set. A field whose generated members would clash with `Changes<M>`'s own (`isEmpty`,
`isSet`, `unset`, `assignments`) is `MQ3307`.

## 7. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-GEN-01 | Golden-file tests pin the generated source for a class model, a record model, a nested pair and a summary model (R-GEN-01). |
| AC-GEN-02 | Every metamodel feature in R-GEN-02 has a generating case, including a composite `@IdClass` and a `@MappedSuperclass` parent. |
| AC-GEN-03 | Adding a column to a nested model changes the outer model's joined `ColumnSet` with no edit to the outer model (R-GEN-04). |
| AC-GEN-04 | A partial `ColumnSet` leaves unselected class fields at their initialiser and unselected record components `null` (R-GEN-06, R-GEN-09). |
| AC-GEN-05 | Lombok on and off both compile, including `Boolean isX` and `boolean isX` (R-GEN-10). |
| AC-GEN-06 | A LEFT-join miss yields `Optional.empty()`; a match whose non-key columns are all NULL yields a present model (R-GEN-12, R-GEN-13). |
| AC-GEN-07 | Two-level nesting maps correctly with a class nested in a record and vice versa (R-GEN-14). |
| AC-GEN-08 | A second compilation with one model changed regenerates only that model's file (R-GEN-05). |
| AC-GEN-09 | The processor jar contains no unshaded JavaPoet package (R-GEN-05). |
| AC-GEN-10 | (`Future`, M8) Golden files pin `QOrderPatch` and `OrderPatchChanges` for a record and a class update model, with a converter, a to-one by id and a composite key (R-GEN-19). |
| AC-GEN-11 | (`Future`, M8) `generateChanges = true` adds `changes()`, `update(...)` and `from(...)` covering root non-key columns only; every query model with a `@PrimaryKey` gets `delete()` (R-GEN-21, R-GEN-22). |
| AC-GEN-12 | (`Future`, M8) The generated change set carries `@ValidChanges` naming its model when Bean Validation is on the classpath, no annotation when it is not, and never the model's field constraints (R-GEN-23). |
