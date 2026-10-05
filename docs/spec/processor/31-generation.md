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
            TableField.<OrderEntity, CustomerEntity>join(ROOT, "customer", JoinType.LEFT).presentBy(QCustomerView.KEY);

    public static final OrderedColumnField<OrderView, OrderEntity, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    // @Column(converter = OrderStatusConverter.class): the entity attribute is a String; a converter that is not an
    // OrderedColumnConverter gives a plain ColumnField, so Agg.min/max/countDistinct do not take it (D-93)
    public static final ColumnField<OrderView, OrderEntity, OrderStatus> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", OrderStatus.class, String.class, OrderStatusConverter.INSTANCE);
    public static final OrderedColumnField<OrderView, OrderEntity, BigDecimal> TOTAL = …;

    public static final OrderedColumnField<OrderView, CustomerEntity, Long> CUSTOMER_ID =
            QCustomerView.ID.withTable(OrderView.class, CUSTOMER_TABLE);
    public static final OrderedColumnField<OrderView, CustomerEntity, String> CUSTOMER_NAME = …;

    // @FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country"): reuses CUSTOMER_TABLE, not mapped
    public static final OrderedColumnField<OrderView, CustomerEntity, String> CUSTOMER_COUNTRY =
            ColumnField.of(OrderView.class, CUSTOMER_TABLE, "country", String.class);

    public static final SelectSet<OrderView> ALL      = SelectSet.of(ID, STATUS, TOTAL, CREATED_AT);
    public static final SelectSet<OrderView> DEFAULT  = ALL.without(/* @ExcludeFromDefaults */);
    public static final SelectSet<OrderView> CUSTOMER = SelectSet.of(CUSTOMER_ID, CUSTOMER_NAME);

    public static final PrimaryKey<OrderView, Long> KEY = PrimaryKey.of(ID);
    public static final RowMapper<OrderView> MAPPER = QOrderView::map;

    public static ModelQuery.Builder<OrderEntity, Long, OrderView> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static OrderView map(Row row) {
        Row customer = row.scoped(CUSTOMER_TABLE);
        return new OrderView(
                row.get(ID),
                row.get(STATUS),                       // converted by the column
                row.get(TOTAL),
                row.get(CREATED_AT),
                customer.get(QCustomerView.ID) == null
                        ? Optional.empty()
                        : Optional.of(QCustomerView.MAPPER.map(customer)));
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

**R-GEN-04** A joined `SelectSet` is derived from the nested model (`CustomerView`), so a column added to
`CustomerView` appears in `QOrderView.CUSTOMER` with no list to update by hand. The processor reads the nested model's
own fields and refers to its QModel by name only, so the order in which models are processed does not matter (D-39).
A nested model is mapped through its `MAPPER`, which is public, so it may live in another package, and it may be a
source of the compilation or a compiled class on its classpath, provided its QModel is there too (D-45).

**R-GEN-05** One model produces exactly one file, whose only originating element is that model's type, and the
processor is registered as an **isolating** incremental processor for Gradle (D-39). JavaPoet is shaded into the
processor jar (INV-7).

**R-GEN-24** Every QModel with a `@PrimaryKey` declares `KEY`, a `PrimaryKey` over its key columns
(`PrimaryKey.composite` for several), which `query()` and an outer model's `presentBy` both use.

## 2. Records

**R-GEN-06** A record model is mapped through its canonical constructor, with components passed in declaration order.
Unselected components get `null`, and `Optional` components get `Optional.empty()`.

**R-GEN-07** A compact constructor that rejects `null` will fail on a partial `SelectSet`. The user guide says to keep
validation out of query models; this is not something the processor can check.

## 3. Classes

**R-GEN-08** A class model needs a no-arg constructor, which may be non-public as long as it is visible from the
QModel's package.

**R-GEN-09** Setters are called only for selected columns, so field initialisers survive for unselected ones:

```java
OrderView m = new OrderView();
if (row.isSelected(ID)) m.setId(row.get(ID));
…
Row customer = row.scoped(CUSTOMER_TABLE);
m.setCustomer(customer.get(QCustomerView.ID) == null ? Optional.empty() : Optional.of(QCustomerView.MAPPER.map(customer)));
return m;
```

**R-GEN-10** Generated code calls setters by name, and the getter of a `@Join` field (R-GEN-25). Those calls are
resolved when javac attributes the code, after every annotation processor — Lombok included — has run, so
Lombok-generated accessors work provided the processor follows Lombok's naming rules: a `Boolean isX` field gets
`setIsX`, a primitive `boolean isX` gets `setX`, and an `Optional<X> customer` gets `getCustomer`.

**R-GEN-11** A missing setter or getter is therefore not a processor diagnostic. javac reports it when compiling the
generated class, pointing at the call (`processor/32` §3).

## 4. Nested models

**R-GEN-12** **Empty means "no data".** A `@Join` field is `Optional.empty()` when none of the nested model's columns
were selected, or when a LEFT join found no matching row. It is never `null`, and never a nested model whose every field
is `null`.

**R-GEN-13** **Presence follows the joined primary key.** The generated join carries the nested model's `KEY`
(`presentBy`, `api/10` R-COL-15), so when any column of a join is selected, the engine also selects
that join's `@PrimaryKey` columns — as `engine/21` R-PAG-03 does for the root. A composite key is present when any
of its components is non-null. A LEFT-join miss makes every joined column
`NULL`, so the nested model stays empty; a matched row has a non-null key, so the nested model is present even when all
its other selected columns are `NULL`.

**R-GEN-14** Nesting composes. `OrderView.customer → CustomerView.address` yields `Optional<CustomerView>` containing
`Optional<AddressView>`; classes and records may be nested in each other freely, because each QModel's `map(Row)` builds
its own model. The outer QModel declares the joins below its `@Join` too, each with its columns and
`SelectSet` (`CUSTOMER_ADDRESS_TABLE`, `CUSTOMER_ADDRESS_CITY`, `CUSTOMER_ADDRESS`), read from the nested QModel (D-45).

**R-GEN-15** The generated mapper always assigns a `@Join` field, to `Optional.empty()` or `Optional.of(...)`, so even a
class field with no initialiser is never `null` after mapping.

**R-GEN-16** `Optional` fields need Jackson's `jdk8` module, registered by default in Spring Boot. Stated in the user
guide, not enforced.

**R-GEN-25** Each `@Join` of the model itself also generates `CUSTOMER_JOIN`, a `JoinField<OrderView, CustomerView>`
named after the join's prefix, which a fetch plan names (`api/15` R-FCH-07). Its `table()` is `CUSTOMER_TABLE`; `get`
reads the field, through a record's accessor or a class's getter; `with` returns the parent with the field set to
`Optional.of(nested)`: a record rebuilt through its canonical constructor, every other component read through its
accessor, and a class through its setter, called on the parent itself, which is returned. A join below the `@Join`
has its `JoinField` in its own nested QModel.

**R-GEN-26** Each `@Child` generates a `ChildField<OrderView, LineView>` named as a column of the field would be
(`LINES`), which a fetch plan names (`api/15` R-FCH-03). Its `key()` and `foreignKey()` are columns read as the
attribute's type, without converter (R-FCH-05), on `ROOT` and on the child root's `TableField.root`, or on the
`LEFT` joins their paths cross from there. Only `query()` names the child's QModel, so neither QModel's static
initialiser reads the other's: a `@Child` and a back-`@Join` initialise in either order. `with` sets the field to
`List.copyOf(children)`, or to the first child or `Optional.empty()`, as R-GEN-25's `with` does. The mapper sets a
`@Child` field to `List.of()` or `Optional.empty()`, a record's in its constructor and a class's through its setter.

## 5. Aggregate models

**R-GEN-17** An `@Aggregate` field generates an `AggregateField` constant and is mapped like any other column. `@GroupBy`
fields form `GROUP_KEYS` in declaration order, and `query()` is emitted with `.groupBy(GROUP_KEYS)` already applied
(`processor/30` R-PROC-16).

**R-GEN-18** For a `singleGroup` model, `query()` is emitted without a `groupBy` and without a `primaryKey`, since a
whole-table aggregate has neither (`api/13` R-AGG-07, R-AGG-09).

**R-GEN-27** *(D-115)* A `@Computed` field generates `<NAME> = Def.INSTANCE.expression().named("name")` (or
`new Def().expression()...`), emitted after every column constant, so a definition reading `Q<Model>.*` columns runs
during the initialiser with those fields already set. It is mapped like a column (`processor/30` R-PROC-21).

## 6. Generated update models

For `@UpdateModel OrderPatch` (`api/14` §2) the processor generates two files, both in the model's package.

```java
@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderPatch {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    public static final OrderedColumnField<OrderPatch, OrderEntity, Long> ID = …;              // @PrimaryKey: whereKey only
    public static final OrderedColumnField<OrderPatch, OrderEntity, String> STATUS = …;
    public static final OrderedColumnField<OrderPatch, OrderEntity, Long> CUSTOMER_ID = …;     // to-one by id
    public static final OrderedColumnField<OrderPatch, OrderEntity, Instant> CREATED_AT = …;   // @FilterColumn
    public static final OrderedColumnField<OrderPatch, CustomerEntity, String> CUSTOMER_COUNTRY = …;
    // …

    public static OrderPatchChanges changes() { return new OrderPatchChanges(); }

    public static ModelUpdate.Builder<OrderEntity, Long, OrderPatch> update(Changes<OrderPatch> changes) {
        return ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(changes);
    }

    public static ModelDelete.Builder<OrderEntity, Long, OrderPatch> delete() {
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
        // set columns only, in declaration order, as model values: the engine converts once (D-37, D-60)
    }
}
```

**R-GEN-19** Writable are root attributes, including embedded ones as a dotted `@Column(attribute = "address.city")`,
and to-one associations written by id. `@PrimaryKey` fields get a column constant but no setter. `@FilterColumn` works
as on query models; a joined filter column renders through `api/14` R-WRT-10.

**R-GEN-20** A change set is mutable by design and is never a `static final` constant; INV-9 covers definitions, not
change sets.

**R-GEN-21** For `@QueryModel(generateChanges = true)`, `QOrderView` gains `changes()` and `update(changes)`, and
`OrderViewChanges` gains `static OrderViewChanges from(OrderView model, SelectSet<OrderView> columns)`, which reads the
model's getters or record accessors (`api/14` R-WRT-04).

**R-GEN-22** `QOrderView.delete()` is generated for every query model whose `@PrimaryKey` is the root entity's id,
since a delete writes no columns. A query model whose key is not the id (a unique column on a view, say) gets no
`delete()`; on an `@UpdateModel`, or with `generateChanges = true`, such a key is `MQ3306` (`api/14` R-WRT-08).
The generated `changes()`, `update(changes)` and `delete()` carry `@Incubating`, as the bulk-write API does (D-85).

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
| AC-GEN-03 | Adding a column to a nested model changes the outer model's joined `SelectSet` with no edit to the outer model (R-GEN-04). |
| AC-GEN-04 | A partial `SelectSet` leaves unselected class fields at their initialiser and unselected record components `null` (R-GEN-06, R-GEN-09). |
| AC-GEN-05 | Lombok on and off both compile, including `Boolean isX` and `boolean isX` (R-GEN-10). |
| AC-GEN-06 | A LEFT-join miss yields `Optional.empty()`; a match whose non-key columns are all NULL yields a present model (R-GEN-12, R-GEN-13). |
| AC-GEN-07 | Two-level nesting maps correctly with a class nested in a record and vice versa (R-GEN-14). |
| AC-GEN-08 | Each generated file has exactly one originating element, its model's type, and the processor's registration names it isolating, so a changed model regenerates its own file and those of models nesting it (R-GEN-05). |
| AC-GEN-09 | The processor jar contains no unshaded JavaPoet package (R-GEN-05). |
| AC-GEN-10 | Golden files pin `QOrderPatch` and `OrderPatchChanges` for a record and a class update model, with a converter, a to-one by id and a composite key (R-GEN-19). |
| AC-GEN-11 | `generateChanges = true` adds `changes()`, `update(...)` and `from(...)` covering root non-key columns only; a query model gets `delete()` exactly when its `@PrimaryKey` is the root entity's id (R-GEN-21, R-GEN-22). |
| AC-GEN-12 | The generated change set carries `@ValidChanges` naming its model when Bean Validation is on the classpath, no annotation when it is not, and never the model's field constraints (R-GEN-23). |
