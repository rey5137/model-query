# 32 — Compile-time Diagnostics

**Covers:** every check the annotation processor performs, its `MQ3xxx` code and its message.
**Read when:** adding a check, or wording a message. A wrong message here is the first thing a new user sees.
**Owns:** `R-DIAG-*`, `AC-DIAG-*`. The codes are catalogued in `reference/90`.

---

## 1. Checks

| Code | Check | Example message |
|---|---|---|
| `MQ3001` | Unknown attribute, or a dotted `@Column(attribute)` that leaves embedded values (D-44) | `OrderView.totl: no attribute 'totl' on OrderEntity` |
| `MQ3002` | Model type is not the entity attribute's type, a primitive counting as its wrapper, and no converter; or the attribute is a collection (D-44) | `OrderView.id: model type Integer, entity attribute type Long` |
| `MQ3003` | `@Join` attribute is not a to-one association (a collection cannot be selected, `engine/21` R-PAG-13), or the nested model's `root` does not match the target | `OrderView.customer: CustomerView.root is AccountEntity, association targets CustomerEntity` |
| `MQ3004` | Missing `@PrimaryKey`, on a model with no `@Aggregate` field | `OrderView: no @PrimaryKey; paging, export and @Join presence need one` |
| `MQ3005` | `@Join` field is not `Optional<X>`, or `X` is not a `@QueryModel` (D-45) | `OrderView.customer: @Join field must be Optional<CustomerView>, found CustomerView` |
| `MQ3006` | Nested model has no `@PrimaryKey`, so presence cannot be decided | `OrderView.customer: CustomerView needs a @PrimaryKey to be used in @Join` |
| `MQ3007` | Join cycle between nested models | `OrderView.customer → CustomerView.lastOrder → OrderView` |
| `MQ3008` | Class model has no no-arg constructor visible from its package | `OrderView: needs a no-arg constructor for setter mapping` |
| `MQ3009` | Record component is primitive and not `@PrimaryKey` | `OrderView.count: primitive components can't be null when not selected; use Integer` |
| `MQ3010` | Record has only a non-canonical constructor, or is generic | `OrderView: a generic record can't be mapped through its canonical constructor; remove the type parameters` |
| `MQ3011` | `@FilterColumn` path does not resolve, or crosses a collection with no explicit `joinType` | `OrderView @FilterColumn(CUSTOMER_COUNTRY): no attribute 'contry' on CustomerEntity` |
| `MQ3012` | Two `@FilterColumn`s with the same `alias` and path prefix but different `joinType` | `OrderView @FilterColumn(SKU_B): alias 'itemB' is INNER here, LEFT on SKU_B_QTY` |
| `MQ3013` | `@FilterColumn` name clashes with a generated constant | `OrderView @FilterColumn(STATUS): name already used by field 'status'` |
| `MQ3014` | `converter` is not a `ColumnConverter` between the field type and the attribute type, or has neither a public static `INSTANCE` nor a visible no-arg constructor | `OrderView.status: OrderStatusConverter converts OrderStatus to Integer, entity attribute type String` |
| `MQ3015` | Two generated constants would have the same name, or a field's constant clashes with a reserved one (`ROOT`, `ALL`, `DEFAULT`, `KEY`, `MAPPER`, `GROUP_KEYS`), or a `@Join(prefix)` is not a Java identifier (D-45) | `OrderView.customerId: constant CUSTOMER_ID is also generated for customer.id; rename the field or set @Join(prefix)` |
| `MQ3016` | **Warning.** A column on a to-one association selects the whole entity and has no converter (D-44, D-45) | `OrderView.customer: selects the whole CustomerEntity entity; use @Join with a query model of CustomerEntity to select only its columns` |
| `MQ3201` | `@Aggregate` field is primitive | `ProductSales.revenue: SUM is NULL over zero rows; use BigDecimal, not a primitive` |
| `MQ3202` | `@Aggregate` field type does not match the function's result type | `ProductSales.lines: COUNT returns Long, field is Integer` |
| `MQ3203` | `@Aggregate` model has no `@GroupBy` field and is not `singleGroup` | `ProductSales: has @Aggregate fields but no @GroupBy; add one or set @QueryModel(singleGroup = true)` |
| `MQ3204` | `@GroupBy` combined with `@Aggregate` or `@Join` | `ProductSales.revenue: @GroupBy can't be combined with @Aggregate` |
| `MQ3205` | `@Aggregate(fn = SUM)` over a 32-bit attribute | `ProductSales.units: SUM over Integer returns Long; declare the field as Long` |
| `MQ3301` | Update-model field maps through a join or a collection (`Future`, M8) | `OrderPatch.customerName: update models can only write attributes of OrderEntity; 'customer.name' needs a join` |
| `MQ3302` | `@Join`, `@Aggregate` or `@GroupBy` on an update model (`Future`, M8) | `OrderPatch.customer: @Join isn't allowed on @UpdateModel; write the foreign key with @Column(attribute = "customer") Long customerId` |
| `MQ3303` | Update-model field maps to the primary key without `@PrimaryKey`, or to the `@Version` attribute (`Future`, M8) | `OrderPatch.version: the @Version attribute is managed by the engine (keepVersion, expectVersion)` |
| `MQ3304` | Update-model field maps to an attribute that can't be written: `updatable = false`, or the inverse (`mappedBy`) side of a to-one (`Future`, M8) | `OrderPatch.createdAt: OrderEntity.createdAt is @Column(updatable = false)` |
| `MQ3305` | To-one attribute written by id with the wrong id type (`Future`, M8) | `OrderPatch.customerId: CustomerEntity's id is Long, found String` |
| `MQ3306` | `@PrimaryKey` on an update model, or a query model with `generateChanges = true`, is not the root entity's id (`Future`, M8) | `OrderPatch.orderNo: @PrimaryKey must be OrderEntity's id 'id'; bulk writes key on the entity id` |
| `MQ3307` | Update-model field generates a change-set member that clashes with `Changes<M>` (`Future`, M8) | `OrderPatch.empty: generates isEmpty(), which clashes with Changes.isEmpty(); rename the field` |

**R-DIAG-01** A message names the model, the field or annotation, and both sides of a mismatch. It never asks the user
to read the spec to understand what happened.

**R-DIAG-02** Every check reports as an `ERROR` on the annotated element, so the IDE underlines the field rather than
the generated file. `MQ3016` alone is a `WARNING`: the model is still generated.

**R-DIAG-03** The processor reports **every** independent problem in one pass. A model that failed one check still
produces the remaining diagnostics for its other fields; it does not produce a QModel.

**R-DIAG-04** A code's meaning is fixed once released (INV-10). A check that is later split keeps the original code for
the original case and takes a new code for the new one.

## 2. Testing

**R-DIAG-05** Every row of §1 has a `compile-testing` case asserting the code and the message, with Lombok on and off,
for a class and a record where both are possible.

## 3. What is deliberately not a diagnostic

**R-DIAG-06** A missing setter on a class model, because Lombok-generated setters are not reliably visible to another
processor (`processor/31` R-GEN-11). javac reports it against the generated `map` call.

**R-DIAG-07** A compact record constructor that rejects `null` (`processor/31` R-GEN-07), and a non-bijective
`ColumnConverter` (`processor/30` R-PROC-07). Both are documented, not checked.

## 4. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-DIAG-01 | Each code in §1 has a compile-testing case asserting code and message text (R-DIAG-05). |
| AC-DIAG-02 | A model with three independent errors reports three diagnostics in one compilation (R-DIAG-03). |
| AC-DIAG-03 | Diagnostics are attached to the annotated element, verified through the diagnostic's element (R-DIAG-02). |
| AC-DIAG-04 | A model with any error produces no QModel file (R-DIAG-03). |
| AC-DIAG-05 | `reference/90` lists exactly the codes §1 uses, with no gaps or duplicates (INV-10). |
