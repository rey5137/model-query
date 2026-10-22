# Inheritance roots

!!! warning "Incubating"
    Inheritance roots, and every type and member on this page — `TableField#treat`, `Expr.typeOf`, `Filters.ofType`,
    `notOfType`, `@Subtype`, the `treat` member on `@Column`, `@FilterColumn` and `@Join`, and the generated
    `<SUBTYPE>_TREAT` constants — are new in 0.10.0 and annotated `@Incubating`, as the rest of the API in 0.x is
    (see [API stability](stability.md)). They are complete and tested, but their API may still change in a minor
    release.

When entity hierarchies use JPA inheritance (`SINGLE_TABLE`, `JOINED`, or `TABLE_PER_CLASS`), a query can read rows
polymorphically from the hierarchy root. You can project subtype attributes through treated fields, filter or sort by
concrete subtype, or query a subtype entity directly.

This page explains how treated nodes work, how nulls and counts behave on polymorphic hierarchies, how to filter by
entity type, and the safety guards the library enforces before running queries.

## Treated nodes and projections

A **treated node** downcasts an entity root or join to a specific entity subtype in your hierarchy. When a query model
is rooted at a base entity, fields declared on concrete subtypes are read by specifying `treat = <Subtype>.class`.

Consider a payment hierarchy where `Payment` is the base entity, extended by `CardPayment` and `BankPayment`:

```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class Payment {
    @Id private Long id;
    private BigDecimal amount;
    private String status;
    // getters and setters
}

@Entity
public class CardPayment extends Payment {
    private String last4;
    @ManyToOne(fetch = FetchType.LAZY)
    private Issuer issuer;
    // getters and setters
}

@Entity
public class BankPayment extends Payment {
    private String iban;
    // getters and setters
}
```

A `@QueryModel` over `Payment` reads subtype attributes and joins using the `treat` parameter:

```java
@QueryModel(root = Payment.class)
public record PaymentView(
        @PrimaryKey Long id,
        BigDecimal amount,
        String status,
        @Column(treat = CardPayment.class) String last4,
        @Column(treat = BankPayment.class) String iban,
        @Join(treat = CardPayment.class) Optional<IssuerView> issuer,
        @Subtype Class<? extends Payment> kind) {}

@QueryModel(root = Issuer.class)
public record IssuerView(@PrimaryKey Long id, String name) {}
```

The processor generates downcast constants and table fields on the generated `QPaymentView`:

```java
public static final TableField<Payment, Payment> ROOT = TableField.root(Payment.class);
public static final TableField<Payment, CardPayment> CARD_PAYMENT_TREAT = ROOT.treat(CardPayment.class);
public static final TableField<Payment, BankPayment> BANK_PAYMENT_TREAT = ROOT.treat(BankPayment.class);
public static final TableField<CardPayment, Issuer> ISSUER_TABLE =
        TableField.join(CARD_PAYMENT_TREAT, "issuer", JoinType.LEFT).presentBy(QIssuerView.KEY);
public static final OrderedColumnField<PaymentView, CardPayment, String> LAST4 =
        ColumnField.of(PaymentView.class, CARD_PAYMENT_TREAT, "last4", String.class).named("last4");
public static final OrderedColumnField<PaymentView, BankPayment, String> IBAN =
        ColumnField.of(PaymentView.class, BANK_PAYMENT_TREAT, "iban", String.class).named("iban");
public static final ExpressionField<PaymentView, Class<? extends Payment>> KIND =
        Expr.typeOf(PaymentView.class, ROOT).named("kind");
```

In programmatic query definitions, `TableField#treat(Class<S> subtype)` returns a `TableField<T, S>` downcast to the
subtype. Columns, joins, and `exists` paths hang off this treated table.

A treated root rejects `as(...)`, `on(...)`, and `presentBy(...)` with `MQ1104`. On a treated join, `as` and `on` are
rejected with `MQ1106` because join modifiers must be applied before downcasting; `presentBy` on a treated join is
allowed. Calling `treat(...)` with an improper subtype (such as a sibling class, an unmapped class, a source root, or
an already treated node) is rejected with `MQ1106`.

## NULL on other subtypes

When a query model reads an attribute of a specific subtype, rows representing other subtypes or the base entity read
`null` for that field. A treat in the projection never drops rows:

- **In the select list**: Subtype columns read `null` on rows of different subtypes. The row is retained in the result.
- **In predicates**: A treated column evaluates against `null` for other subtypes. Conditions like `eq`, `lt`, `like`,
  `in`, and `isNotNull` evaluate to false; `isNull` evaluates to true. Consistent with SQL ternary logic, `ne` and
  `notIn` include other subtypes through their `IS NULL` branch (see [Filters](queries.md#filters)),
  whereas `not(eq(...))` excludes them (`NOT(UNKNOWN)` remains false).
- **In ordering**: A treated column is nullable. Nulls sort according to the column's null precedence (`nullsFirst()`
  or `nullsLast()`). Keyset paging over a treated column hits `null` on other subtypes, failing with `MQ2202` unless
  explicit null precedence is configured with `nullsFirst()` or `nullsLast()`.
- **In counts**: A treated column only influences counts when present in an active filter predicate or an `INNER`
  treated join.

```java
var query = QPaymentView.query()
        .where(f -> f.or(
                f.eq(QPaymentView.STATUS, "COMPLETED"),
                f.isNotNull(QPaymentView.LAST4)))
        .orderBy(QPaymentView.ID.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<PaymentView> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<PaymentView> rows = payments.findAll(query, Limit.unlimited());
    ```

## Counts over the root ID

When counting rows or requesting total page counts (`CountMode.TOTAL` or `executor.count(query)`), only an ungrouped
statement with a treated node is counted fresh as `count(root.id)`. Grouped totals keep their group count, and plain
polymorphic queries without treated nodes keep the normal count.

Counting the untreated root entity's primary key guarantees that every matching root entity is counted exactly once:
raw JPA queries that count treated columns or reproduce treated joins in a count query can silently drop rows or
produce incorrect totals when outer joins are pruned.

## Entity type: `typeOf`, `ofType`, and `notOfType`

You can inspect and filter by the exact concrete entity type of each row:

- **`Expr.typeOf(model, table)`**: Reads JPA's `TYPE(table)` expression, returning `Class<? extends T>`. On a query
  model, declaring `@Subtype Class<? extends Root> kind` generates an expression field named after the property. It
  can be selected, grouped, and used in offset page ordering. Keyset paging on `typeOf` is rejected with `MQ1208`
  because expression values cannot serve as keyset cursor keys. Reading `typeOf` over a LEFT-joined node (in a
  select, order, or group key), ordering by `typeOf` on a `JOINED` root without a discriminator column, or reading
  `typeOf` over a treated node is refused before any statement with `MQ4016`.
- **`Filters.ofType(table, Subtype.class, ...)`**: Generates `TYPE(table) IN (...)` matching the specified entity
  class and all concrete entity subclasses mapped in the persistence unit.
- **`Filters.notOfType(table, Subtype.class, ...)`**: Generates `TYPE(table) NOT IN (...)`. Over a node reached
  through a `LEFT` join, it also preserves rows where the join target is absent (`OR TYPE(table) IS NULL`).

Both `ofType` and `notOfType` provide varargs, `Collection`, and `Optional<Collection>` overloads:

- An empty `Optional` skips the filter.
- An empty collection represents an empty set: `ofType` matches no rows, while `notOfType` matches all rows. Neither
  requires a provider treat renderer.

```java
var query = QPaymentView.query()
        .select(SelectSet.of(QPaymentView.ID, QPaymentView.STATUS))
        .where(f -> f.ofType(QPaymentView.ROOT, CardPayment.class))
        .orderBy(QPaymentView.ID.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<PaymentView> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<PaymentView> rows = payments.findAll(query, Limit.unlimited());
    ```

Selecting treated columns (such as `LAST4` or `IBAN`) alongside an `ofType` filter on a `JOINED` hierarchy is
refused with `MQ4016` on Hibernate 6.6, where the provider cannot attribute the resulting narrowing. To project
subtype-specific fields, either root the model directly at the subtype entity or select only untreated root attributes
when filtering by type.

## One model per subtype

Instead of reading all subtypes through a polymorphic model with nullable treated columns, you can root a
`@QueryModel` directly at a concrete subtype:

```java
@QueryModel(root = CardPayment.class)
public record CardPaymentView(
        @PrimaryKey Long id,
        BigDecimal amount,
        String status,
        String last4,
        @Join Optional<IssuerView> issuer) {}
```

The processor inherits all persistent attributes from `Payment`. The engine roots the statement at `CardPayment`, and
the JPA provider automatically restricts the query to `CardPayment` and its entity subtypes.

Nested models can also navigate relationships between subtypes and supertypes. Upcast and downcast joins (R-PROC-42)
come from the nested model's root, not `treat`. When an entity has a `@ManyToOne Payment payment` association,
reading it with a nested model rooted at a subtype downcasts the target:

```java
@QueryModel(root = Holder.class)
public record HolderDetail(
        @PrimaryKey Long id,
        // Downcast: entity attribute is Payment, nested model is CardPaymentView
        @Join(attribute = "payment") Optional<CardPaymentView> card,
        // Upcast: entity attribute is CardPayment, nested model is PaymentView
        @Join(attribute = "card") Optional<PaymentView> payment) {}
```

A downcast `LEFT` join returns empty when the parent references a sibling or base payment, while an `INNER` join drops
those parents. Conversely, reading a `@ManyToOne CardPayment card` association as
`@Join(attribute = "card") Optional<PaymentView> payment` upcasts the association to the base model.

## Union of subtype models through a source shape

When each subtype has distinct fields and you want to avoid wide polymorphic tables containing sparse nulls, combine
subtype models into a uniform shape using [source models](source-models.md):

```java
@SourceModel(root = CardPayment.class)
public record PaymentItem(
        @Discriminator("CARD") @PrimaryKey String kind,
        @PrimaryKey Long id,
        @Column(attribute = "last4") String reference,
        BigDecimal amount) {}

@SourceModel(root = BankPayment.class, shape = PaymentItem.class)
public record BankPaymentItem(
        @Discriminator("BANK") @PrimaryKey String kind,
        @PrimaryKey Long id,
        @Column(attribute = "iban") String reference,
        BigDecimal amount) {}

@QueryModel(root = PaymentItem.class)
public record PaymentFeed(@PrimaryKey String kind, @PrimaryKey Long id, String reference, BigDecimal amount) {}
```

Combine the subtype sources with `unionAll`:

```java
SourceQuery<PaymentItem> items = QPaymentItem.source().build()
        .unionAll(QBankPaymentItem.source().build());
var feed = QPaymentFeed.query(items).select(QPaymentFeed.ALL)
        .orderBy(QPaymentFeed.KIND.asc(), QPaymentFeed.ID.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<PaymentFeed> rows = executor.list(feed, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    ModelQueryExecutor<PaymentItem> sources = ModelQueryExecutor.create(em, PaymentItem.class, config);
    List<PaymentFeed> rows = sources.list(feed, Limit.unlimited());
    ```

Each branch reads only its subtype's table. Including `@Discriminator` ensures compound primary keys `(kind, id)`
remain unique across the combined result set.

Treated and `@Subtype` fields can also appear in a source model's body. If the source body does not project the
untreated root entity ID, the engine automatically selects a hidden anchor column (`mq_0id`) so polymorphic rows are
not pruned. Grouping or aggregating over a treated column inside a source body is rejected with `MQ4016`.

## Paging and export over treated keys

Paging and export over polymorphic roots enforce strict key and cursor validation:

- **Keyset fingerprint**: Keyset pagination incorporates the full treat chain of each key column into the cursor's
  canonical fingerprint. A cursor generated for `QPaymentView.LAST4.asc().nullsFirst()` cannot be applied to
  `QPaymentView.IBAN.asc().nullsFirst()`; a mismatch is rejected with `MQ2209`.
- **Duplicate key detection (`MQ2213`)**: Keyset pages, export pages, `byKeys`, `one(q, key)`, and fetch-plan parent
  rows check for duplicate primary keys on every root (for example, in `TABLE_PER_CLASS` hierarchies, views, or
  assigned IDs where distinct rows can share a key). When duplicate keys are encountered, execution fails with
  `MQ2213`. A filter-only to-many join renders as a semi-join (`R-EXE-16`), so each root is returned once; only a
  customizer's join or a shape the semi-join excludes can duplicate rows, raising `MQ2003` with a `Filters.exists` hint
  for `byKeys` and `one(q, key)`.
- **Offset page contradiction warning (`MQ2214`)**: If an offset page with a total (`CountMode.TOTAL`) returns at
  least one row and more rows than `total - offset`, the executor logs one `WARNING` `MQ2214` per `ModelQuery`. It
  never throws: under `READ COMMITTED`, concurrent transactions committing rows between the count and content queries
  can produce this mismatch. The warning recommends running under `REPEATABLE READ` or taking a snapshot.

```java
var pageQuery = QPaymentView.query()
        .orderBy(QPaymentView.ID.asc())
        .build();
```

=== "Plain JPA"

    ```java
    Slice<PaymentView> page = executor.page(pageQuery, PageSpec.of(0, 25), CountMode.TOTAL);
    ```

=== "Spring repository"

    ```java
    ModelPage<PaymentView> page = payments.findPage(pageQuery, PageRequest.of(0, 25), CountMode.TOTAL);
    ```

## Refusals and statement preflight

JPA leaves the semantics of `TREAT` in selections and joins implementation-dependent. To prevent silent data loss or
engine errors, the library checks treat operations against an allow-list before executing any statement.

### Provider refusal (`MQ4014`)

Execution fails with `MQ4014` before any query runs when:

- The JPA provider does not provide a `TreatRenderer` (via `ProviderSupport#treats(emf)`).
- The Hibernate version is outside the supported minor lines: only **Hibernate 6.6.x** and **7.4.x** are supported.
- `hibernate.jpa.compliance.query=true` is enabled, which disables proprietary treat handling.

### Capability refusal (`MQ4016`)

Execution fails with `MQ4016` when a query asks for a shape or use set that cannot be reliably rendered:

- **Writes**: Bulk updates (`ModelUpdate`) or deletes (`ModelDelete`) with treated conditions in `where`, or returning
  models with treated attributes.
- **Grouping and aggregation**: Group keys or aggregate function arguments over treated columns (`GROUP_BY` or
  `AGGREGATE`), except for correlated counts over their own treated root.
- **Child statements**: Treated attributes in fetch-plan child queries (`CHILD`).
- **Set operations**: `distinct()` or non-ALL set operations that require the hidden `mq_0id` anchor.
- **Mapping restrictions**: Hierarchies mapped with `@SQLRestriction` on a subtype in Hibernate 6.6, hierarchies with
  `@DiscriminatorValue("null")` or `("not null")`, or hierarchies using composite IDs (`@IdClass` or `@EmbeddedId`).

### Statement preflight

On supported Hibernate lines, before the first statement executes, the library runs a statement preflight. It translates
the criteria query into Hibernate's SQL AST without executing JDBC calls and inspects table references and joins.

If the AST reveals that Hibernate has narrowed the query unintentionally (for example, pruning outer joins or
restricting base rows), or if an unknown AST structure is encountered, the preflight fails closed with `MQ4016`. Keyset
and export page shapes are also preflighted before page 1 executes.

## What to check before deploying

Before deploying queries with inheritance roots:

1. **Verify your Hibernate version**: Ensure you are running a supported minor release: **Hibernate 6.6.x** or
   **7.4.x**. A newer or unmeasured minor line refuses treated paths by design with `MQ4014`.
2. **Run offline portability checks**: Use `model-query-portability` in unit tests:

=== "Plain JPA"

    ```java
    PortabilityCheck.of("shop", ModelQueryConfig.defaults())
            .sweep(PaymentQueries.class)
            .assertPortable();
    ```

=== "Spring repository"

    ```java
    PortabilityCheck.of(entities, ModelQueryConfig.defaults())
            .sweep(PaymentQueries.class)
            .assertPortable();
    ```

   `PortabilityCheck` verifies all treat sites against the allow-list offline without requiring a live database,
   detecting `MQ4014` and `MQ4016` configuration issues before deployment.
3. **Use single-column IDs**: Polymorphic hierarchies should use a single `@Id` attribute rather than `@IdClass` or
   `@EmbeddedId`.
4. **Check `TABLE_PER_CLASS` downcast joins**: An `INNER` join that downcasts a `TABLE_PER_CLASS` base entity and
   reads an inherited attribute is refused with `MQ4016` by the statement preflight, because the union keeps a branch
   for base rows the join cannot exclude (D-161). Make the base entity abstract, declare the join `LEFT` (base rows
   then read `Optional.empty()`), or read only subtype-own columns.
