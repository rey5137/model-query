# 32 — Compile-time Diagnostics

**Covers:** every check the annotation processor performs, its `MQ3xxx` code and its message.
**Read when:** adding a check, or wording a message. A wrong message here is the first thing a new user sees.
**Owns:** `R-DIAG-*`, `AC-DIAG-*`. The codes are catalogued in `reference/90`.

---

## 1. Checks

| Code | Check | Example message |
|---|---|---|
| `MQ3001` | Unknown attribute, or a dotted `@Column(attribute)` that leaves embedded values (D-44) | `OrderView.totl: no attribute 'totl' on OrderEntity` |
| `MQ3002` | Model type is not the entity attribute's type, a primitive counting as its wrapper, and no converter, named or built-in (D-84); or the attribute is a collection (D-44) | `OrderView.id: model type Integer, entity attribute type Long` |
| `MQ3003` | `@Join` attribute is not a to-one association (a collection cannot be selected, `engine/21` R-PAG-13), or the nested model's `root` does not match the target | `OrderView.customer: CustomerView.root is AccountEntity, association targets CustomerEntity` |
| `MQ3004` | Missing `@PrimaryKey`, on a model with no `@Aggregate` field | `OrderView: no @PrimaryKey; paging, export and @Join presence need one` |
| `MQ3005` | `@Join` field is not `Optional<X>`, or `X` is not a `@QueryModel`, or `X` has an `@Aggregate` or `@Computed` field (D-45, D-47, D-115) | `OrderView.customer: @Join field must be Optional<CustomerView>, found CustomerView` |
| `MQ3006` | Nested model has no `@PrimaryKey`, so presence cannot be decided | `OrderView.customer: CustomerView needs a @PrimaryKey to be used in @Join` |
| `MQ3007` | Join cycle between nested models | `OrderView.customer → CustomerView.lastOrder → OrderView` |
| `MQ3008` | Class model has no no-arg constructor visible from its package | `OrderView: needs a no-arg constructor for setter mapping` |
| `MQ3009` | Record component is primitive and not `@PrimaryKey`; on a model with `@Aggregate` or `@GroupBy` fields, a primitive `@PrimaryKey` too (D-49) | `OrderView.count: primitive components can't be null when not selected; use Integer` |
| `MQ3010` | Record has only a non-canonical constructor, or is generic | `OrderView: a generic record can't be mapped through its canonical constructor; remove the type parameters` |
| `MQ3011` | `@FilterColumn` path does not resolve, or crosses a collection with no explicit `joinType` | `OrderView @FilterColumn(CUSTOMER_COUNTRY): no attribute 'contry' on CustomerEntity` |
| `MQ3012` | Two `@FilterColumn`s with the same `alias` and path prefix but different `joinType`, or a `joinType` written on a `@FilterColumn` that differs from the type of the `@Join` its path reuses (D-46) | `OrderView @FilterColumn(SKU_B): alias 'itemB' is INNER here, LEFT on SKU_B_QTY` |
| `MQ3013` | `@FilterColumn` name clashes with a generated constant, is reserved, or is not a legal Java name (a keyword included, D-46) | `OrderView @FilterColumn(STATUS): name already used by field 'status'` |
| `MQ3014` | `converter` is not a `ColumnConverter` between the field type and the attribute type, or has neither a public static `INSTANCE` nor a visible no-arg constructor | `OrderView.status: OrderStatusConverter converts OrderStatus to Integer, entity attribute type String` |
| `MQ3015` | Two generated constants would have the same name, or a field's constant clashes with a reserved one (`ROOT`, `ALL`, `DEFAULT`, `KEY`, `MAPPER`, `GROUP_KEYS`), or a `@Join(prefix)` or a `@FilterColumn` `alias` is not a legal Java name (a keyword included; D-45, D-46) | `OrderView.customerId: constant CUSTOMER_ID is also generated for customer.id; rename the field or set @Join(prefix)` |
| `MQ3016` | **Warning.** A column on a to-one association selects the whole entity and has no converter (D-44, D-45) | `OrderView.customer: selects the whole CustomerEntity entity; use @Join with a query model of CustomerEntity to select only its columns` |
| `MQ3017` | A model's `root`, or the `root` of a model it nests through `@Join` or `@Child`, still names no class in the last round of annotation processing; until then the model is deferred to the next round (D-107) | `ShipmentView: root does not name a class, and no annotation processor generated one` |
| `MQ3018` | `@Computed(value)` is not an `ExpressionDefinition<Model, FieldType>`, or has neither a public static `INSTANCE` nor a visible no-arg constructor (D-115) | `OrderView.net: NetAmount is not an ExpressionDefinition<OrderView, BigDecimal>, or has neither INSTANCE nor a no-arg constructor` |
| `MQ3019` | `@Computed` combined with `@PrimaryKey`, `@Column`, `@Join`, `@Child`, `@Aggregate` or `@Transient`, or on a primitive field, since an expression may be NULL (D-115) | `OrderView.net: @Computed can't be combined with @Column` or `OrderView.net: @Computed field is primitive; an expression may be NULL` |
| `MQ3201` | `@Aggregate` field is primitive | `ProductSales.revenue: SUM is NULL over zero rows; use BigDecimal, not a primitive` |
| `MQ3202` | `@Aggregate` field type does not match the function's result type | `ProductSales.lines: COUNT returns Long, field is Integer` |
| `MQ3203` | `@Aggregate` model has no `@GroupBy` field and is not `singleGroup` | `ProductSales: has @Aggregate fields but no @GroupBy; add one or set @QueryModel(singleGroup = true)` |
| `MQ3204` | `@GroupBy` combined with `@Aggregate` or `@Join`, or `@Aggregate` combined with `@PrimaryKey`, `@Column`, `@Join` or `@Transient` (D-47) | `ProductSales.revenue: @GroupBy can't be combined with @Aggregate` |
| `MQ3205` | `@Aggregate(fn = SUM)` over a 32-bit attribute | `ProductSales.units: SUM over Integer returns Long; declare the field as Long` |
| `MQ3206` | `@Aggregate(distinct = true)` on `SUM`, `AVG`, `MIN` or `MAX` (D-47) | `ProductSales.revenue: distinct only applies to COUNT, found SUM` |
| `MQ3207` | `@QueryModel(singleGroup = true)` on a model that has `@GroupBy` fields (D-47) | `ProductSales: singleGroup = true can't be combined with @GroupBy fields; remove one` |
| `MQ3208` | `@Aggregate` with both `attribute` and `expression` (D-115) | `ProductSales.paid: @Aggregate takes attribute or expression, not both` |
| `MQ3301` | Update-model field maps through a join or a collection | `OrderPatch.customerName: update models can only write attributes of OrderEntity; 'customer.name' needs a join` |
| `MQ3302` | `@Join`, `@Aggregate`, `@GroupBy` or `@Computed` on an update model | `OrderPatch.customer: @Join isn't allowed on @UpdateModel; write the foreign key with @Column(attribute = "customer") Long customerId` |
| `MQ3303` | Update-model field maps to the primary key without `@PrimaryKey`, or to the `@Version` attribute | `OrderPatch.version: the @Version attribute is managed by the engine (keepVersion, expectVersion)` |
| `MQ3304` | Update-model field maps to an attribute that can't be written: `updatable = false`, or the inverse (`mappedBy`) side of a to-one | `OrderPatch.createdAt: OrderEntity.createdAt is @Column(updatable = false)` |
| `MQ3305` | To-one attribute written by id with the wrong id type | `OrderPatch.customerId: CustomerEntity's id is Long, found String` |
| `MQ3306` | `@PrimaryKey` on an update model, or a query model with `generateChanges = true`, is not the root entity's id | `OrderPatch.orderNo: @PrimaryKey must be OrderEntity's id 'id'; bulk writes key on the entity id` |
| `MQ3307` | Update-model field generates a change-set member that clashes with `Changes<M>` | `OrderPatch.empty: generates getEmpty() and setEmpty(...), which clash with Changes.isEmpty() as property 'empty'; rename the field` |
| `MQ3401` | `@Child` on a field that is not a `List` or `Optional` of a `@QueryModel`, combined with `@Join`, `@Transient`, `@Aggregate` or `@GroupBy`, on an update model, or with both `through` and `foreignKey` | `CustomerView.note: @Child needs a List or Optional of a @QueryModel, found String` |
| `MQ3402` | `@Child` `key` or `foreignKey` names no attribute of its root, names an association rather than one of its attributes, crosses an association inside an embedded value, or crosses a collection on the `key` side (a `foreignKey` may, D-99); or is left empty on a model with no `@PrimaryKey` | `CustomerView.orders: OrderEntity has no attribute 'customer.idx'` |
| `MQ3403` | `@Child` key and foreign-key attribute types differ | `CustomerView.orders: key String name and foreignKey Long customer.id differ` |
| `MQ3404` | `@Child` with a composite key: several paths, a path to an embedded value, or a default `@PrimaryKey` of several columns; or an array-typed key | `CustomerView.orders: @Child takes one key attribute each side; foreignKey names 2` |
| `MQ3405` | A `List` `@Child` without `foreignKey` (unless `through`), or whose model has no `@PrimaryKey`; an `Optional` `@Child` whose `through` crosses a collection, whose model has no `@PrimaryKey` | `CustomerView.orders: a List @Child needs foreignKey, the attribute of OrderEntity that holds the parent's key` |
| `MQ3406` | `@Child` `through` that is blank, crosses an attribute that is not an association or an embedded value, or ends at another type than the child's root (a subclass included); a `key` that is not the parent root's single `@Id`; or a grouped child model (R-FCH-14) | `LabelView.orders: through 'customer' ends at CustomerEntity, not at OrderEntity, the root of OrderRef` |

The `MQ3304` check on `updatable = false` is best-effort: it reads `@Column` and `@JoinColumn`, not `@AttributeOverride` or
orm.xml (D-70).

**R-DIAG-01** A message names the model, the field or annotation, and both sides of a mismatch. It never asks the user
to read the spec to understand what happened.

**R-DIAG-02** Every check reports as an `ERROR` on the annotated element, so the IDE underlines the field rather than
the generated file. `MQ3016` alone is a `WARNING`: the model is still generated. The user guide shows the `@Join` form its message
points to, which selects the nested model's columns instead of the whole entity (D-44, D-45).

**R-DIAG-03** The processor reports **every** independent problem in one pass. A model that failed one check still
produces the remaining diagnostics for its other fields; it does not produce a QModel. Two diagnostics wait for
another, since each check needs the result the first error denies: an `MQ3015` clash with a `@Join`'s constants is
reported once that `@Join`'s own error is fixed, and an `MQ3014` once its column's path resolves (D-107). A model whose
`root`, or a nested model's `root`, names no class yet is not checked: another processor may generate that class, so
the model is retried each round and reports `MQ3017` in the last one if the class never appears (D-107).

**R-DIAG-04** A code's meaning is fixed once released (INV-10). A check that is later split keeps the original code for
the original case and takes a new code for the new one.

## 2. Testing

**R-DIAG-05** Every row of §1 not tagged `Future` has a `compile-testing` case asserting the code and the message,
with Lombok on and off, for a class and a record where both are possible (D-48). A `Future` row gets its case when
its milestone builds the check.

## 3. What is deliberately not a diagnostic

**R-DIAG-06** A missing setter, or a `@Join` field's missing getter, on a class model, because Lombok-generated
accessors are not reliably visible to another processor (`processor/31` R-GEN-11). javac reports it against the call in
the generated class.

**R-DIAG-07** A compact record constructor that rejects `null` (`processor/31` R-GEN-07), and a non-bijective
`ColumnConverter` (`processor/30` R-PROC-07). Both are documented, not checked.

## 4. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-DIAG-01 | Each code in §1 not tagged `Future` has a compile-testing case asserting code and message text (R-DIAG-05, D-48). |
| AC-DIAG-02 | A model with three independent errors reports three diagnostics in one compilation (R-DIAG-03). |
| AC-DIAG-03 | Diagnostics are attached to the annotated element, verified through the diagnostic's element (R-DIAG-02). |
| AC-DIAG-04 | A model with any error produces no QModel file (R-DIAG-03). |
| AC-DIAG-05 | `reference/90` lists exactly the codes §1 uses, with no gaps or duplicates (INV-10). |
| AC-DIAG-06 | A model whose `root` another processor generates in the same compilation, and a model that nests it through `@Join`, are generated with no diagnostic (R-DIAG-03, D-107). |
| AC-DIAG-07 | A model whose `root` is never generated, and a model that nests it, each report `MQ3017` on their own element and produce no QModel (R-DIAG-03, D-107). |
| AC-DIAG-08 | `MQ3018`, `MQ3019`, `MQ3208` and the widened `MQ3005` each have a compile-testing case, and `MQ3202`/`MQ3205` over an expression's type (R-PROC-21, R-PROC-22). |
