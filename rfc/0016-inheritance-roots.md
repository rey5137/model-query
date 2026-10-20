# RFC 0016 — Inheritance roots

- **Status:** accepted 2026-10-19 as D-154 (after two `architect-review`s and the acceptance spike, §11). M28, 0.10.0
  (D-145).
- **Affects:**
  - **`api/10`:** new R-COL-34 to R-COL-36 and AC-COL-44 to AC-COL-52: a treated `TableField`, what a subtype column
    reads on a row of another subtype and the renderings that keep that rule, and the row's entity type. R-COL-08's
    `MQ1003` message names `treat` when the column's root is a subtype of the query's. R-COL-03's `MQ1104` covers a
    treated root.
  - **`api/12`:** new R-FLT-28 and AC-FLT-30, AC-FLT-31: `ofType` and `notOfType`.
  - **`api/17`:** new R-SRC-20 and AC-SRC-39 to AC-SRC-41: subtype roots and treated fields in a source, and a union of
    subtype models. R-SRC-06 is unchanged.
  - **`api/14`:** R-WRT-11 unchanged: a treated `ModelUpdate` or `ModelDelete` `where` is `MQ4016` before it picks a
    path (R-VND-26).
  - **`api/15`:** a treated node in a fetch plan's child statement, a join plan or an enricher is `MQ4016` (`CHILD`).
  - **`engine/20`:** R-EXE-05 amended: a count over a statement with a treated node is built fresh and counts the root
    id (R-COL-35); a grouped one keeps R-AGG's group count.
  - **`engine/21`:** R-PAG-19 amended: the fingerprint carries each key's treat chain. New R-PAG-26: a duplicate key on
    a read that relies on a unique key (keyset, export, `byKeys`, `one`, fetch-plan parents) is `MQ2213`. New
    R-PAG-27: an offset page over a treated statement whose rows contradict its total logs one `WARNING` (`MQ2214`)
    per `ModelQuery`. New AC-PAG-29 to AC-PAG-32.
  - **`processor/30`:** new R-PROC-41 to R-PROC-43: `treat` on `@Column`, `@FilterColumn` and `@Join`; a `@Join` to a
    nested model rooted at a subtype or supertype of the target; `@Subtype`. R-PROC-08 points at R-PROC-42.
  - **`processor/31`:** new R-GEN-41 and R-GEN-42, AC-GEN-34 and AC-GEN-35: treated tables and the columns over them,
    and the `@Subtype` constant.
  - **`processor/32`:** new `MQ3042` and `MQ3043`, with AC-PROC-42 to AC-PROC-45; `MQ3003` narrowed for nested roots
    and extended to a nested model's `treat` targets.
  - **`vendor/40`:** new R-VND-26 and AC-VND-16 to AC-VND-21: `ProviderSupport#treats(emf)`, `TreatRenderer`,
    `TreatSite` and its `StatementShape`, the Hibernate line set, the statement preflight, and `PortabilityCheck`'s
    treat report.
  - **`core` SPI:** new `@EngineFacing @Incubating` `TreatRenderer`, `TreatSite`, `StatementShape` and `TreatUse`;
    `RenderOptions` carries the renderer.
  - **`reference/90`:** new `MQ1106`, `MQ2213`, `MQ2214` (logged as a `WARNING`, never thrown) and `MQ4016`;
    `MQ4014` widened to "a treated path"; `MQ2209`'s message names a treat.
  - **`reference/92`:** D-154 (below); Q-25, Q-26 and Q-27. D-95 unchanged: `MQ2214`'s warning carries no
    bound values.
  - **`delivery/60`:** a TCK case per admitted allow-list cell, and the Hibernate renderer pinned to the matrix's minor
    lines.
  - **`delivery/61`:** the incubating list.
  - **`docs/site`:** a new "Inheritance roots" page; the comparison page's Reach row; Diagnostics §Logging lists
    `MQ2214`.
- **Discussion:** to open

## Summary

A query model over a polymorphic entity root reads its subtypes' attributes, in three ways:

- **Subtype fields on a base model.** `@Column(treat = CardPayment.class)` reads an attribute only `CardPayment`
  declares through `cb.treat`, and on a row of another subtype it reads NULL: in the select list, in every predicate,
  in an order and in a keyset's NULL branch. `@Subtype` maps the row's entity type, and `Filters#ofType` filters on it.
- **One model per subtype.** `@QueryModel(root = CardPayment.class)` already works. This RFC pins it in the TCK, for
  each strategy, and lets a `@Join` nest a model rooted at a subtype (a treated join) or a supertype of the target.
- **A union of subtype models.** A `shape` whose branches are rooted at subtypes, kept apart by `@Discriminator`. This
  is M25.4's set operation unchanged, with treated fields allowed in a branch body.

Treat is standard JPA, but JPA defines it only in `FROM` and `WHERE`, and Hibernate's rendering depends on the
statement: one that reads the root only through the treat narrows it to the subtype (§11). So the engine anchors every
statement with a treated node (a `treat` node or a downcast join) on the root's id. Content and source bodies select
it, and a count counts it. Every
treated use goes through a provider renderer whose allow-list is keyed by statement shape, and the Hibernate renderer
also checks each statement's translation before it runs. A use outside the table is refused before any statement
(`MQ4016`).

```java
@Entity @Inheritance(strategy = InheritanceType.SINGLE_TABLE) @DiscriminatorColumn(name = "kind")
public abstract class Payment { @Id Long id; BigDecimal amount; Instant createdAt; }

@Entity @DiscriminatorValue("CARD")
public class CardPayment extends Payment { String last4; @ManyToOne Issuer issuer; }

@Entity @DiscriminatorValue("BANK")
public class BankPayment extends Payment { String iban; }

@QueryModel(root = Payment.class)
public record PaymentRow(@PrimaryKey Long id, BigDecimal amount,
                         @Subtype Class<? extends Payment> type,
                         @Column(treat = CardPayment.class) String last4,
                         @Column(treat = BankPayment.class) String iban,
                         @Join(attribute = "issuer", treat = CardPayment.class) Optional<IssuerView> issuer) {}

// every payment, newest id first; card rows with a matching last4 when one is given
var q = QPaymentRow.query().select(QPaymentRow.DEFAULT)
        .where(f -> f.eq(QPaymentRow.LAST4, last4))           // Optional<String>; NULL on bank rows, so they drop
        .orderBy(QPaymentRow.ID.desc())
        .keyset()
        .build();
```

```sql
-- PostgreSQL, SINGLE_TABLE, in the shape the spike records on both lines (§11): the predicate needs no restriction
select p.id, p.amount, p.kind, p.last4, p.iban, i.id, i.name
from payment p left join issuer i on p.kind in ('CARD') and i.id = p.issuer_id
where p.last4 = ?
order by p.id desc fetch first ? rows only
```

## Motivation

- **A polymorphic root is half-readable today.** A model over `Payment` selects, filters and pages the base
  attributes over every subtype. A subtype attribute is `MQ3001` ("no attribute 'last4' on Payment"), and a
  hand-written column on `TableField.root(CardPayment.class)` in a query rooted at `Payment` is `MQ1003`. The choices
  are one model and one statement per subtype, merged and paged by hand, or native SQL.
- **One model per subtype works, but nothing checks it.** The processor reads the root's entity superclasses
  (`EntityMetamodel#hierarchy`), the engine roots at the subtype, and Hibernate restricts the rows to it. No TCK
  fixture reads an inheritance hierarchy. The one there, `InsJoinedEntity`, checks that inserts refuse a `JOINED` root.
  A `@Join` to a nested model rooted at a subtype or a supertype of the association's target is `MQ3003`, since the
  processor compares the roots for equality.
- **Treat is where row loss hides.** A treated path that the provider turns into a type restriction for the whole
  statement removes the other subtypes' rows. Hibernate 5 did this to a treat inside an `or`. On a keyset page or an
  export, that removes rows without an error, which INV-4 and INV-5 forbid. The library defines what a treated path
  means and refuses what a line can't render that way.

Blaze-Persistence covers this with entity-view inheritance (`@EntityViewInheritance` and a mapping per subtype view).
This RFC keeps one flat model with typed, compile-checked columns, plus M25.4's set operations.

## Design

### 1. A treated table (R-COL-34)

```java
public final class TableField<P, T> {
    @Incubating
    public <S extends T> TableField<T, S> treat(Class<S> subtype);   // this node, read as subtype S
}
```

- **A node, not a column flag.** `ROOT.treat(CardPayment.class)` and `CUSTOMER_TABLE.treat(VipCustomer.class)` are
  `TableField`s. Columns, joins and `exists` paths hang off them like any other node, so a subtype's to-one
  (`TableField.join(CARD_TREAT, "issuer", LEFT)`) needs nothing new. The treated node's join key is its parent's key
  and the subtype. One `JoinContext` resolves it once per statement through `TreatRenderer#treat` (§10), so two
  columns of one subtype share one subtype-table join on `JOINED`. It is immutable and may be `static final` (INV-9).
- **Where it may sit.** A treat may sit on an entity root, or on a to-one or to-many join. A to-many join keeps its own
  rules: it is filter-only (R-PAG-13) and feeds `exists`. `presentBy(...)` on a treated join is allowed, and is how a
  downcast nested model reads its presence (§5). On a treated root, `as(...)`, `on(...)` and `presentBy(...)` stay
  `MQ1104`: a treated root is still a root.
- **`MQ1106` is treat-only misuse.** At definition: `treat(T.class)` on a root (an improper subtype), `treat` of a
  treated node,
  `treat` on a source root (no entity metamodel), and `as(...)` or `on(...)` on a treated join, which belong on the
  join before it is treated. At first resolution:
  `treat(T.class)` on a join (its target type is known only from the metamodel), a subtype the metamodel
  does not map as an entity, and a
  treated node that resolves over a sibling type (below). The generic bound rejects a type outside the hierarchy at
  compile time (P-2).
- **What a treat resolves over.** At resolution the treated node's subtype is compared with the entity the node
  actually reaches: the query's root, or the target of the join it is re-rooted under (`TableField#under`, §5). A
  strict subtype of it renders the treat. The entity itself or a supertype of it renders the node untreated, since
  every row already is one. Anything else is `MQ1106`, naming both types. Two paths reach that case today with no
  check: a column on `PAYMENT.treat(CardPayment.class)` in a query rooted at `BankPayment` passes R-COL-08's check,
  because `Payment` is a supertype of `BankPayment`; and `under(join)` re-roots a nested model's treated node onto a
  join to `BankPayment` unchecked.
- **`MQ1003` points at it.** A column whose root is a strict subtype of the query's root stays `MQ1003`, and the
  message names `ROOT.treat(<Subtype>.class)` (R-COL-08 amended). A column on a supertype root is accepted, as today.
- **A subtype mapped only in `orm.xml`** is an entity to the provider but carries no `@Entity`. The processor's `treat`
  member needs one (`MQ3042`, §4), so such a subtype is reachable only hand-written: `TableField#treat` and `ofType`.

### 2. A subtype column reads NULL on other subtypes (R-COL-35)

**The rule.** A column over a treated node reads the attribute on rows of the subtype and its own subtypes. On every
other row it reads NULL, wherever it is admitted:

- **In the select list,** the row is kept and the value is NULL. A treat in a select never removes a row. The same
  holds for an operand of a `@Computed` or `Expr` function (a `coalesce` of two treated columns).
- **In a predicate,** the column compares as NULL: `eq`, `lt`, `like`, `in` and `isNotNull` are false; `isNull` is
  true. `ne` and `notIn` include the row, through their D-5 `IS NULL` branch. `not(eq)` excludes it: SQL `NOT` over a
  NULL comparison is NULL (D-5), where JPA's "the predicate is false" reading would make `not(eq)` include it. A treat
  inside `or` restricts only its own operand.
- **In an order,** the row sorts as NULL, under the column's null precedence (R-COL-12).
- **In a keyset,** the column is a nullable key: NULL is `MQ2202` by default, and explicit `nullsFirst()`/`nullsLast()`
  add the `IS NULL` branches of R-PAG-05 and R-PAG-22. Those branches are true on other subtypes' rows, so a keyset
  over a subtype key visits every row once. After a NULL cursor value the branch is a top-level `IS NULL AND id > ?`,
  with no `or` around it (§10).
- **In a count,** the column appears only where it can change the row count: in a predicate, or under an INNER
  treated join. The count itself counts the root id (below).
- **For every nullability rule,** the column is nullable whatever the attribute's own mapping says. That covers a
  source's group key (R-SRC-02: a treated group key is `MQ3031`), a shape field's nullability (R-SRC-16) and a
  primitive record component (`MQ3009`).
- **Not as a group key or an aggregate's argument.** A treated column in `GROUP BY` or under an aggregate, directly or
  inside an `Expr`, is `MQ4016` in M28, in a query and in a source body. Hibernate narrows a grouped statement to the
  subtype on every line and strategy: the NULL group holds one sibling row of three, and a root count over five rows
  reads three (§11). A LEFT entity join on the id keeps the rule there (spike S2.4), and is Q-27. A `@Subtype` group
  key (§3) and a correlated count whose own root is treated (R-COL-31, §10) are unaffected.

This is what a LEFT join to the subtype would give. It is also the reading JPA's treat semantics give for the
null-rejecting predicates. For `isNull`, `ne`, `notIn` and `not(...)`, JPA's "the predicate is false" differs from it.
That is why the engine classifies every site (§10) instead of assuming the provider's rendering.

**Where the rule holds, and what enforces it.** Hibernate keeps the root's other rows only while the statement also
reads the root untreated. A statement whose every read of the root goes through the treat narrows the root to the
subtype, whatever its predicates say (§11). The rule holds, on both lines, every strategy and every Tier-1 vendor, in
three renderings, and the engine builds only these:

- **An anchored content statement.** Every content statement and every source body (CTE) with a treated node selects the
  root's id untreated. A keyless treated-only select and a keyless CTE body read three rows of five; the selected id
  restores five (S2.3). When the statement selects no untreated `root.<id>` itself (a source key need not be the id),
  the engine adds it as a hidden column, `mq_0id`: selected last, and never in `fields()`, `DEFAULT`, `ALL`, a mapper or
  the generated `Rows`. The name sits outside D-146's `mq_<property>` space, since a property can't start with a digit.
  A hidden column changes the rows of a `distinct()` query (`api/10`) and of a `union`, `intersect` or `except` (equal
  visible rows stop collapsing, and branches would differ in arity), so such a query or set operation that would need
  the hidden anchor is `MQ4016`, unless the model selects the root id itself. A grouped source body (`@GroupBy
  @Subtype`) is `GROUPED` and gets no anchor.
- **A LEFT treated join,** whose type restriction sits in its `ON` (below).
- **A count of the root id.** A statement with a treated node is one with a `treat` node or a downcast join; `typeOf`
  and `ofType` alone don't make one. A count over such a statement is built fresh from the definition, with
  only its predicates and the joins that change the row count (R-EXE-05), and selects `count(root.<id>)`. Today's path,
  the content statement with its selection replaced and its order cleared, undercounts a select-only or order-only
  treat (three of five, S2.1). A fresh `count(root)` or `count(*)` undercounts `isNull`, `ne`, `notIn` and
  `not(isNotNull)` (S2.2). `count(root.<id>)` passes all four, so totals and `count(q)` over them are admitted. A
  treated column that is only selected or ordered is absent from that count. A grouped `@Subtype` query's total keeps
  R-AGG's count of groups, since `count(root.<id>)` would count rows.

A statement in any other form, a grouped one among them, is refused before the first statement (§10). The Hibernate
renderer also checks each statement's translation before it runs (§10).

**What each strategy renders**, recorded on both lines and every vendor:

| Strategy | An anchored statement | A statement that reads the root only through the treat |
|---|---|---|
| `SINGLE_TABLE` | no join and no discriminator restriction, even in a predicate: an unshared column is NULL on other rows; a shared one is read under a `CASE` on the discriminator | the root becomes a derived table restricted to the subtype's discriminator values |
| `JOINED` | a LEFT join to the subtype's table, and to its supertables below the root | an INNER join to it |
| `TABLE_PER_CLASS` | the union of the concrete tables, padded with NULL | the union pruned to the subtype's tables |

**A column two subtypes share.** On `SINGLE_TABLE`, two subtypes may map one physical column. Both lines read it under
a `CASE` on the discriminator, in the select list, a predicate and an order, for same-named and for differently named
attributes, so a sibling's value never shows (§11; the predicate probes match only the sibling's value and find no
row). That is evidence for those two mappings, not for every shared column. The Hibernate renderer reads each site's
column from the entity persister and keys the allow-list on it per attribute: a shared-column site in a shape or use
set the table lacks is `MQ4016`, the fail-closed default for what the spike didn't run.

**A treated join** (`@Join` to a subtype, §5) follows the same rule for its LEFT form. Its type restriction sits in the
join's `ON`, so on a target of another subtype the joined row is absent, the nested model is `Optional.empty()`, and
the parent row stays. On `SINGLE_TABLE` and `JOINED`, the target's id sits in the base table. A restriction rendered
anywhere but `ON` would leave the presence key (R-GEN-13) non-NULL and give `Optional.of` a view of NULLs instead. The
spike finds it in `ON` on every strategy, line and vendor, and tells an absent target from a present one whose values
are all NULL; the TCK keeps both (AC-COL-47). An INNER treated join drops the parent row, as an INNER join drops a
missing target.

### 3. The row's entity type, and filtering by type (R-COL-36, R-FLT-28)

```java
public final class Expr {
    @Incubating
    public static <M, T> ExpressionField<M, Class<? extends T>> typeOf(Class<M> model, TableField<?, T> table);
}

public sealed interface Filters<M> permits FilterGroup {
    @Incubating @SafeVarargs
    <T> Filters<M> ofType(TableField<?, T> table, Class<? extends T> type, Class<? extends T>... more);
    @Incubating
    <T> Filters<M> ofType(TableField<?, T> table,
                          Optional<? extends Collection<? extends Class<? extends T>>> types);
    @Incubating @SafeVarargs
    <T> Filters<M> notOfType(TableField<?, T> table, Class<? extends T> type, Class<? extends T>... more);
    @Incubating
    <T> Filters<M> notOfType(TableField<?, T> table,
                             Optional<? extends Collection<? extends Class<? extends T>>> types);
}
```

- **`typeOf` is `TYPE(t)`**, the row's exact entity class, through `From#type()`. It is an `ExpressionField`. It
  selects, groups, filters with `eq` and `in` by exact class, and orders offset pages, offset export and
  `primaryKeyFirst` (R-PAG-25). `keyset()` refuses it with `MQ1208` (R-QRY-16). Its order is unspecified: the
  provider's type rendering, which is the discriminator value on `SINGLE_TABLE` (and on `JOINED` with a discriminator)
  and the union's synthetic type column on `TABLE_PER_CLASS`. With R-PAG-01's id tie-breaker the order is
  deterministic per deployment, which is all an offset page needs (§11 records it and its repeat). The docs say so and
  point at a `@Computed` CASE for a named order.
- **Two `typeOf` uses are refused** (`MQ4016`), from the persister, before any statement. An order by `typeOf` on a
  `JOINED` root whose persister has no discriminator: both lines render `order by case end`, which no vendor accepts.
  An explicit `@DiscriminatorColumn`, or `hibernate.discriminator.implicit_for_joined`, gives it one, unless
  `ignore_explicit_for_joined` removes it. And `typeOf` over a LEFT-joined node: on a row whose join is absent, both
  lines fail to resolve the discriminator instead of reading NULL. `notOfType` over such a join is admitted.
- **`ofType` means instance-of.** `ofType(ROOT, CardPayment.class)` keeps rows whose type is `CardPayment` or one of its
  entity subtypes. It renders `TYPE(t) IN (...)` over the subtypes the engine reads from `Metamodel#getEntities()`:
  every entity type whose Java type is assignable to a listed type, so a subtype mapped in `orm.xml` counts. `notOfType`
  is its `NOT`, and over a LEFT join it includes rows where the join is absent, as `notIn` includes NULL (D-5). An empty
  `Optional` skips the filter (P-3). An empty collection is "none of these types": `ofType` keeps no row and
  `notOfType` keeps every row, as `in` and `notIn` do with an empty set. A type that isn't a strict or improper subtype
  of the table's entity can't compile. A type the metamodel doesn't map is `MQ1106` at first resolution.
- **Exact against instance-of.** The javadoc of `typeOf` says `eq(TYPE, CardPayment.class)` keeps `CardPayment` rows
  only, and the javadoc of `ofType` says it keeps its entity subtypes too.
- **A discriminator value no entity maps.** A row whose discriminator value no subtype maps is kept by `notOfType`,
  whose `NOT IN` doesn't name it, and dropped by `ofType`. Reading `typeOf` over it fails with the provider's error, so
  the row is never skipped silently. The docs say so, and the spike pins all three on every line and vendor (§11).

### 4. Declaring it on a model (R-PROC-41, R-PROC-43)

```java
public @interface Column      { /* … */ @Incubating Class<?> treat() default void.class; }
public @interface FilterColumn { /* … */ @Incubating Class<?> treat() default void.class; }
public @interface Join        { /* … */ @Incubating Class<?> treat() default void.class; }

@Incubating @Target({FIELD, RECORD_COMPONENT}) @Retention(CLASS)
public @interface Subtype {}
```

- **`treat` names the subtype of the model's root that declares the attribute.** On `@Column`, the attribute (or its
  dotted embedded path, R-PROC-06) is read from that subtype. On `@FilterColumn`, the path's first segment is read from
  it, and the rest of the path joins as usual (R-PROC-10). On `@Join`, the association is read from it. The member is
  `Class<?>` with `void.class` as "none", as `converter` is, because the annotations module depends on nothing
  (R-PROC-01).
- **Validation.** The processor reads the subtype's `TypeElement` as it reads a root (R-GEN-01), so the subtype may be
  in the same compilation or on the classpath. It checks:
  - The subtype is an `@Entity` and a strict subtype of the model's root: `MQ3042`. A `@MappedSuperclass`, an
    embeddable, an unrelated class and the root itself are all `MQ3042`. A subtype mapped only in `orm.xml` is
    therefore `MQ3042` too, and is read hand-written (§1).
  - The attribute exists on the subtype's hierarchy: `MQ3001`, naming the subtype.
  - The root's own hierarchy doesn't already declare the attribute: `MQ3042`, "drop `treat`".
  - The model and attribute types match, under the usual checks (`MQ3002`, `MQ3014`).
  - The field is not a `@PrimaryKey`, a `@Child`'s parent-side key, a `@Discriminator`, or a field of a model rooted at
    a source, a `@UpdateModel` or an `@InsertModel`: all `MQ3042`. A key reads the root's id, and a write has no treat
    in JPA's `SET` clause.
  - The model doesn't set `generateChanges` or `generateInserts` while declaring a treated or `@Subtype` field:
    `MQ3042`. The field isn't silently dropped from the write (the Q-23 precedent).
- **`@Subtype`** marks one field of type `Class<? extends R>`, where `R` is the model's root, which maps `typeOf(ROOT)`.
  `Class` is the only value type (Q-25): `JOINED` has no discriminator value to map a `String` from, a `String` or enum
  form can be added later without breaking this one, and a `@Computed` CASE over `typeOf` maps either today. Each of
  these is `MQ3043`:
  - a second `@Subtype` field;
  - another field type;
  - `@Subtype` combined with `@Column`, `@Computed`, `@Aggregate` or `@PrimaryKey`;
  - `@Subtype` on a model rooted at a source, or on a write model.

  A `@GroupBy @Subtype` field is allowed: it groups by type, which keeps every row (§11).
- **No `treat` on `@Aggregate`.** An aggregate over a subtype attribute is refused in M28 in every form (§2, Q-27): a
  `@Computed` or `Agg.*` over a column on the generated treated table (R-GEN-41) is `MQ4016` before any statement.
  This leaves `@Aggregate` unchanged.

### 5. Joins to a subtype or a supertype (R-PROC-42, `MQ3003` narrowed)

A `@Join`'s nested model may be rooted at the association's target, at a subtype of it, or at a supertype of it:

- **A subtype (downcast).** The generated join is
  `TableField.join(ROOT, "customer", LEFT).treat(VipCustomer.class).presentBy(QVipCustomerView.KEY)`, carrying the
  nested model's presence key (R-GEN-13) on the treated join (§1). Section 2's join rule applies: LEFT gives
  `Optional.empty()` for another subtype, and INNER drops the row.
- **A supertype (upcast).** A plain join. The nested model reads attributes the target inherits, so one `CustomerView`
  serves every association to a `Customer` subtype. The engine already accepts a column on a supertype root
  (`TableField#resolve`'s assignability check).
- **The nested model's own `treat` targets.** Each `treat` the nested model declares on its root, through `@Column`,
  `@FilterColumn` or `@Join`, must be a subtype or a supertype of the association's target. A supertype, or the target
  itself, renders as the plain join (§1). A `PaymentView` rooted at `Payment` with `@Column(treat = CardPayment.class)`
  nested under an association typed `BankPayment` is `MQ3003`, naming the field: `under(join)` would otherwise render
  treat-as-card over a bank join, on `JOINED` possibly as an inner subtype-table join. The check runs at every `@Join`
  level, a nested model's own nested joins included, each against its own target. A hand-written path that reaches
  the same case is `MQ1106` at resolution (§1).
- **Anything else** is `MQ3003`, whose text becomes "the nested model's `root` is not the target, a subtype or a
  supertype of it", or names the `treat` field. `@Join(treat = …)` (§4) and a nested subtype compose: `treat` reads
  the association from the owner's subtype, and the nested root downcasts the target.

### 6. Count, paging and export over a polymorphic root

The key is the root id. JPA declares one id per hierarchy, so a polymorphic statement returns each entity once and the
key stays unique: R-PAG-01's tie-breaker, keyset (R-PAG-05) and export (INV-4) apply. A treated order key is nullable
(§2). A `@Subtype` order key is an expression (§3). The count is §2's fresh count of the root id. Every site a call
needs, later keyset pages included, is checked before its first statement (§10). Three paging rules change:

- **R-PAG-19, the fingerprint.** Per keyset key, the canonical form adds the key's treat chain: the subtype entity
  names along its path, root to column. Without it, `CARD_TREAT.reference asc` and `BANK_TREAT.reference asc` share a
  fingerprint, and a cursor from one resumes the other at the wrong rows (INV-4). A mismatch is `MQ2209`, whose
  message adds "treat" to what changed. An untreated key's canonical form is unchanged, so cursors issued before stay
  valid.
- **R-PAG-26, a duplicate key where a read relies on one.** On every root, the reads that rely on the key being unique
  (R-SRC-09's `MQ2212` set: a keyset page, an export page, `byKeys`, `one(q, key)` and a fetch plan's parent rows)
  check it: a key-ordered page compares each row's key with the row before it, and a key-to-row map refuses a key seen
  twice. An equal key is `MQ2213`, naming the model and the key, before the page or the map is returned. `list()` and
  offset pages are unchecked: they return every row today, and a duplicate loses none there. It is a partial guard,
  one comparison per row: it misses two rows with one key that a page boundary splits, and any order with a leading
  non-key column. It applies to every root because a view or an assigned id duplicates a key as `TABLE_PER_CLASS`
  can, and the engine can't see a mapping strategy portably.
- **R-PAG-27, a total the page contradicts.** An offset page with a total (`page()`) over a statement with a treated
  node, that returns at least one row and more rows than `total - offset`, logs one `WARNING` `MQ2214` and returns the
  page. The warning names the model, the offset, the rows and the total, never a bound value (D-95), and fires once
  per `ModelQuery`, as R-EXE-05's does. It is logged, never thrown: under READ COMMITTED a row committed between the
  count and the content trips it on a correct query. It is a backstop for the fresh id count (§2), not a
  replacement: on the last page it catches each of the six undercounts today's count path gives, on every strategy,
  vendor and line (S3.4). It misses an undercount while `offset + rows <= total`, a call that fetches no page, keyset
  pages and exports (no total), `count(q)` alone, a content and a count that lose the same rows, and an overcount. The
  non-empty condition keeps a correct total from tripping it on a page past the end. The message names the concurrent
  commit as a cause, and REPEATABLE READ or a snapshot around the call rules it out.

AC-PAG-29 to AC-PAG-31 pin keyset pages, offset pages and a full export over a base root of each strategy, and the
duplicate-key guard; AC-PAG-32 pins the page fallback.

**`TABLE_PER_CLASS` ids are trusted (Q-26).** The concrete tables share no key, so `@Id` is unique by mapping, not by
a constraint, and the library trusts it as it does an entity over a view:

- **What makes it false.** Assigned ids with no `@GeneratedValue`, where each table's writer picks its own, and rows
  written outside JPA: SQL scripts, another application, a migration.
- **What happens then.** A keyset page cut after the first of two rows with id `X` continues at `id > X` and drops the
  second. An offset export's order between the two is unstable, so a page edge between them repeats or skips one.
  `one(q, key)` and a fetch plan's children see two parents for one key.
- **Why it is trusted.** Hibernate's own `find` breaks on such data too. Marking every `TABLE_PER_CLASS` root
  non-unique (`MQ1901`, `MQ2212`) would take keyset and export away from every such list. R-PAG-26 catches the
  duplicates that land side by side on a page, and the docs page states the rest.

Row locks need no new rule. A base root on `JOINED` or `TABLE_PER_CLASS` maps to more than one table, so
`tablesOf` already refuses it with `MQ4010`. A treated join is a join, so `MQ2211` refuses that too. A treated column
on a `SINGLE_TABLE` root reads one table, and the lock applies to it.

### 7. One model per subtype

`@QueryModel(root = CardPayment.class)` needs no new rule. The processor reads the inherited attributes and the id from
the entity superclasses, the engine roots the statement at the subtype, and the provider restricts it to the subtype
and its subtypes. New here: the upcast join (§5); AC-COL-50, which pins content, count, keyset, export and the
`MQ4010` and lock behaviour per strategy; and the docs page. A subtype model and a base model may share a nested model
through the upcast.

### 8. A union of subtype models (R-SRC-20)

```java
@SourceModel(root = CardPayment.class)
public record PaymentItem(@Discriminator("CARD") @PrimaryKey String kind, @PrimaryKey Long id,
                          @Column(attribute = "last4") String reference, BigDecimal amount) {}

@SourceModel(root = BankPayment.class, shape = PaymentItem.class)
public record BankPaymentItem(@Discriminator("BANK") @PrimaryKey String kind, @PrimaryKey Long id,
                              @Column(attribute = "iban") String reference, BigDecimal amount) {}

SourceQuery<PaymentItem> items = QPaymentItem.source().build().unionAll(QBankPaymentItem.source().build());
```

- **It is M25.4 unchanged.** Any entity of the hierarchy may root a branch, the base included, and the fields match by
  R-PROC-38. Uniqueness follows R-SRC-05 and R-SRC-06: `(kind, id)` is unique by discriminator. Without a
  `@Discriminator`, a union over two subtype roots is non-unique (R-SRC-09), even though their ids can't collide on
  `SINGLE_TABLE`. Uniqueness is decided when the operation is built, where no mapping strategy is known (Alternatives).
- **What R-SRC-20 adds.** A source over a polymorphic root may declare treated and `@Subtype` fields, rendered in the
  CTE body (`SOURCE_BODY`, §10). The body selects the root id (§2, hidden as `mq_0id` when no field reads it) and may
  not group or aggregate over a treated field. Branches rooted at a subtype and its own subtype (`CardPayment`,
  `PremiumCardPayment`) read a premium row twice under `unionAll`, with two `kind` values, so the keys stay unique. The
  docs say so. The spike runs such a union, by discriminator, on every strategy, `TABLE_PER_CLASS`'s own internal union
  included (§11).
- **When to use which.** A base model (§1–§4) reads one polymorphic statement, with NULL in fields that don't apply.
  A union maps each subtype's own attributes into one shape (`reference` is `last4` or `iban`), one table read per
  branch. A `@Computed` coalesce of two treated columns is the base-model form of the same field (`EXPRESSION`).

### 9. Generated code (R-GEN-41, R-GEN-42)

```java
public static final TableField<Payment, Payment> ROOT = TableField.root(Payment.class);
public static final TableField<Payment, CardPayment> CARD_PAYMENT_TREAT = ROOT.treat(CardPayment.class);
public static final TableField<Payment, BankPayment> BANK_PAYMENT_TREAT = ROOT.treat(BankPayment.class);
public static final OrderedColumnField<PaymentRow, CardPayment, String> LAST4 =
        ColumnField.of(PaymentRow.class, CARD_PAYMENT_TREAT, "last4", String.class);
public static final ExpressionField<PaymentRow, Class<? extends Payment>> TYPE =
        Expr.typeOf(PaymentRow.class, ROOT).named("type");
public static final TableField<CardPayment, Issuer> ISSUER_TABLE =
        TableField.join(CARD_PAYMENT_TREAT, "issuer", JoinType.LEFT).presentBy(QIssuerView.KEY);
```

- **R-GEN-41.** Each distinct `treat` target of the model gets one `<SUBTYPE>_TREAT` constant, named as R-PROC-13 names
  `ITEMS_TABLE`, so its columns, joins and filter paths share one node. A clash is `MQ3015`. A column over it is
  generated as any column is, `OrderedColumnField` by R-PROC-07's rule, and it is in `DEFAULT`, `ALL` and `fields()` as
  usual. A downcast `@Join` (§5) generates `TableField.join(...).treat(Sub.class).presentBy(...)`. The treated tables
  are emitted before the columns that read them.
- **R-GEN-42.** A `@Subtype` field generates an `ExpressionField` constant through `Expr.typeOf`, named as any field.
  It is in the default sets, and the mapper reads it as a `Class`. With `modelquery.jspecify` (R-GEN-36), nothing new
  is annotated.
- **Nothing else changes.** `KEY`, `MAPPER`, `query()` and `source()` are as today.

### 10. Provider, SPI and refusals (R-VND-26)

```java
@EngineFacing @Incubating
public interface TreatRenderer {                                   // core; jakarta.persistence types only (INV-7)
    <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype);
    Optional<String> refusal(TreatSite site);                      // empty: renders as R-COL-35 says
    // empty asserts the provider never narrows this statement; M28 passes select statements only
    Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape);
}

@EngineFacing @Incubating
public record TreatSite(Class<?> entity, Class<?> subtype, Optional<String> attribute, StatementShape shape,
                        boolean anchored, Set<TreatUse> uses) {}

@EngineFacing @Incubating
public enum StatementShape { CONTENT, COUNT, GROUPED, SOURCE_BODY, CHILD, WRITE }

@EngineFacing @Incubating
public enum TreatUse { SELECT, ORDER, GROUP_BY, AGGREGATE, EXPRESSION, PREDICATE, NULL_TEST, NEGATED, DISJUNCTION,
                       LEFT_JOIN, INNER_JOIN, SUBQUERY, CORRELATION, TYPE }
```

- **One hook.** `ProviderSupport#treats(EntityManagerFactory emf)` is an `@Incubating` default method returning
  `Optional<TreatRenderer>`, empty by default. `jpa` hands the renderer to `core` through
  `RenderOptions#withTreatRenderer`, as `SubqueryRenderer` reaches it (R-VND-25). Without a renderer, every treated
  node, `typeOf` and `ofType` is `MQ4014`. That includes providers whose JPA `treat` would run, because JPA leaves treat
  in `SELECT` undefined and §2's rule can't be assumed (INV-5). The default is strict, as R-VND-25 and R-SRC-11 are.
  The subtype list `ofType` needs is not on the SPI: the engine reads it from `Metamodel#getEntities()` (§3).
- **A site in a statement.** A `TreatSite` is one treated read in one statement:
  - `entity`, the entity the treated node treats; `subtype`, its subtype; for `typeOf` and `ofType`, both are the
    node's own entity;
  - `attribute`, the attribute path read through it, empty for a treated join's own rendering and for `TYPE`;
  - `shape`, the statement it sits in: `CONTENT` (a list, page, keyset page or export page), `COUNT` (a total or
    `count(q)`), `GROUPED` (a statement with `GROUP BY` or an aggregate select), `SOURCE_BODY` (a source's CTE body),
    `CHILD` (a fetch plan's children, a join plan, an enricher) or `WRITE` (a bulk update's or delete's `where`). A
    site inside a sub-select takes its enclosing statement's shape and adds `SUBQUERY`;
  - `anchored`, whether the statement, or the sub-select that holds the site, also reads the treated node's root
    untreated: the engine's id anchor, a counted id, or a correlation on the root's id (§2). The engine anchors every
    `CONTENT`, `COUNT` and ungrouped `SOURCE_BODY` statement, so `false` is a `GROUPED`, `CHILD` or `WRITE`
    statement, or a sub-select that reads its root only through the treat;
  - `uses`, the site's own use and every context enclosing it, as an unmodifiable copy of an `EnumSet`.

  `not(isNull(LAST4))` is `{NULL_TEST, NEGATED}`. `LAST4` inside an `or` inside `exists` is `{PREDICATE, DISJUNCTION,
  SUBQUERY}`. `coalesce(LAST4, IBAN)` in the select list gives one site per attribute, each `{SELECT, EXPRESSION}`.
  `Agg.max(LAST4)` is a `GROUPED` `{SELECT, AGGREGATE}`, and a `@GroupBy` treated field adds `GROUP_BY`. A sub-select
  correlated on a treated outer node gives that node `{SUBQUERY, CORRELATION}`. `typeOf` carries `TYPE` with its
  context: `SELECT`, `ORDER`, `GROUP_BY` or `PREDICATE`, plus `LEFT_JOIN` when it reads a LEFT-joined node. The
  records and enums are engine-built and read by the provider. Renderers read `TreatSite` through its accessors: a
  later component breaks a deconstruction pattern over the record, so the javadoc says not to use one, and a later
  constant breaks no implementor that refuses what it doesn't know.
- **Every site and statement before the first statement.** The executor derives the sites from the definition and the
  call, not from the statements built so far: the content statement, the count, every keyset page shape, each source
  body and each child statement. A treated keyset key adds a site per page shape. After a non-NULL cursor value it is
  `{PREDICATE, DISJUNCTION}`, from R-PAG-06's OR-expansion, plus `NULL_TEST` under explicit precedence. After a NULL
  cursor value it is a top-level `{NULL_TEST}` (`IS NULL AND id > ?`, no `or`), safe only because the page is anchored
  (§2). Both are derived before page 1, although page 1 renders no keyset predicate. The engine also builds each later
  page shape, per key and for a non-NULL and a NULL cursor value, with placeholder parameters, and preflights it before
  page 1 (below). An export is therefore refused before it writes a row, as R-SRC-11 checks source features. A refusal
  is `MQ4016`, naming the model, the column, the shape, the uses, the root's strategy and the reason. `PortabilityCheck`
  reports it per target without a database (below).
- **The allow-list is keyed by shape, anchoring and use set.** The Hibernate renderer answers `refusal` from the spike's
  table (§11, `rfc/0016-spike-data/renderer-allowlist.tsv`), keyed by line, strategy, mapping variant, whether the
  attribute's column is shared, shape, anchoring and use set. It admits the measured cells, on every strategy unless
  noted, and refuses anything else with `MQ4016`:

  | Shape, anchored | Admitted | Refused (`MQ4016`) |
  |---|---|---|
  | `CONTENT`, yes | `SELECT`, `EXPRESSION`, `ORDER`; `PREDICATE` alone or with `NEGATED`, `DISJUNCTION`, `SUBQUERY`; `NULL_TEST` alone or with those; `LEFT_JOIN` or `INNER_JOIN` with `SELECT`, `ORDER`, `PREDICATE` or `NULL_TEST`; `TYPE` with `SELECT` or `PREDICATE`, and with `ORDER` except `JOINED` without a discriminator; a correlated count's `{SELECT, AGGREGATE, SUBQUERY}` on its own treated root, with or without `LEFT_JOIN` (R-COL-31) | `GROUP_BY` and `AGGREGATE` otherwise (Q-27); `{TYPE, LEFT_JOIN}`; `{SUBQUERY, CORRELATION}` on a treated outer node |
  | `COUNT`, yes | `PREDICATE` alone or with `NEGATED`, `DISJUNCTION`, `SUBQUERY`; `NULL_TEST` alone or with those; the joined predicate shapes; `{TYPE, PREDICATE}` (provenance below the table) | `EXPRESSION` with `PREDICATE`: measured only on today's count path, where it fails |
  | `SOURCE_BODY`, yes | `SELECT`; `NULL_TEST` | `AGGREGATE`, `GROUP_BY`; any other use set |
  | `GROUPED`, no | `{TYPE, SELECT, GROUP_BY}`, one site | every treated column (Q-27) |
  | `WRITE`, no | nothing in M28 | `SINGLE_TABLE` by the table (both lines throw `ClassCastException` translating it); `JOINED` and `TABLE_PER_CLASS` until a mutation preflight is measured (D-154) |
  | `CHILD`, or not anchored otherwise | nothing measured | everything |

  A cell is admitted only where every case of it passed on both lines, all three vendors and the strategy. The table
  takes the TSV's empirical `decision` plus S2's anchored and root-id-count rows. The `COUNT` cells were measured on
  S1's count path, not as `count(root.id)`, except the four null-accepting sets (`isNull`, `ne`, `notIn`,
  `not(isNotNull)`), measured as an id count (`S2_FRESH_COUNT_ID`); the AC-VND TCK runs each admitted `COUNT` cell as
  `count(root.id)`. The TSV marks `COMBINED_TYPE_TREAT` `REFUSE` (composition unmodelled), but only its `COUNT`
  `SELECT` form failed (6 of 6); its `AND` and `OR` forms passed in content and count. A type site beside a treated
  site is therefore admitted per site, and the failing form can't occur, because a fresh count drops selections. Its
  `current_SPI_decision` column refused anchored select, order and `NULL_TEST` cells only because the previous
  `TreatSite` couldn't say whether a statement reads the root untreated or how its count is built. A `SINGLE_TABLE`
  shared-column site is admitted in the `CONTENT` select, order and predicate cells its fixtures pass (§2). In a
  `COUNT` it is admitted only in the cells that passed on the spike's count path (`or` and `exists`), so a standalone
  null-accepting predicate on it is refused until the root-id count is measured there. Each site is checked on its
  own, and the preflight (below) checks the statement they compose into.
- **`model-query-hibernate` implements it on both lines.** `treat` calls `cb.treat`. `refusal` reads the strategy, the
  mapping variant and each attribute's column from the entity persister and answers from the table. `preflight` inspects
  the translation (below). Its javadoc says that an empty answer asserts the provider never narrows the statement, so a
  renderer with no translation check must refuse rather than return empty. M28 passes only select statements; the
  parameter stays `CommonAbstractCriteria` so that a later mutation preflight fits. Line differences live only in this
  module (INV-6, INV-7). `jpa` imports no Hibernate type. No Tier-1 vendor differs from another on any measured cell, so
  the renderer has no vendor input and `VendorProfile` gains nothing.
- **Mapping variants**, read from the persister:
  - **`@SQLRestriction` on a subtype:** every treat, `typeOf`, `ofType` and subtype join over that hierarchy is
    `MQ4016` on 6.6, which ignores the restriction and returns the hidden row, through treat as well. 7.4 refuses the
    mapping at bootstrap (`AnnotationException`), so no query reaches the renderer. A root's own `@SQLRestriction` is
    admitted: treat, `isNull`, `ofType` and `typeOf` keep exactly the visible rows on every strategy (S2.5).
  - **`@DiscriminatorValue("null")` or `("not null")`** in the hierarchy: `MQ4016` for the whole hierarchy. An INNER
    treated join misclassifies a sibling target there, and `TYPE` reads fail.
  - **`@DiscriminatorOptions(force = true)`, `@DiscriminatorFormula`, a `JOINED` `@DiscriminatorColumn` (under each of
    `implicit_for_joined` and `ignore_explicit_for_joined`) and a `@SecondaryTable` on a subtype** are admitted under
    the same table, keyed per variant; a cell the spike didn't measure for that variant is refused. A secondary table
    LEFT-joins under `optional = true` and `optional = false`, and keeps a row whose secondary row is missing.
  - **An `@IdClass` or `@EmbeddedId` hierarchy:** `MQ4016`. The root-id count and anchor were measured over a single
    id attribute only.
  - **`hibernate.jpa.compliance.query=true`:** `treats(emf)` is empty (`MQ4014`), as that mode already makes `first`
    `MQ4014`; it also rejects the scalar sub-selects of a correlated count.
- **Bulk writes.** A treated predicate in a `ModelUpdate` or `ModelDelete` `where` is a `WRITE` site. On
  `SINGLE_TABLE` both lines throw `ClassCastException` translating it, for every operator. On `JOINED` and
  `TABLE_PER_CLASS` the scalar comparisons and `not(eq)` change exactly the matching rows, and the null-accepting sets
  change too few (one of three, two of four). The preflight covers select statements only (S3 measured no mutation
  translation), so M28 refuses every `WRITE` site with `MQ4016`, on every strategy; the measurements stay as evidence
  for a later mutation preflight. The refusal is decided before R-WRT-11 picks a path, so a key-first write and a
  single-statement write are refused alike on every vendor, whatever `targetTableInSubquery()` says (INV-6).

#### Relying on observed Hibernate behaviour

The table and §2's renderings rest on what Hibernate 6.6 and 7.4 do, not on a JPA or Hibernate contract: JPA leaves
treat outside `FROM` and `WHERE` to the provider, and narrowing a statement that reads the root only through a treat
is Hibernate's own choice. Five guards keep a change in that behaviour from losing rows silently:

1. **Only the lines it was measured on.** The Hibernate renderer serves only the minor lines of the CI matrix, 6.6.x
   and 7.4.x, read from `org.hibernate.Version` when `treats(emf)` is asked. On any other minor `treats(emf)` is empty
   and every treated use is `MQ4014`. Adopting a minor adds it to the set and to the matrix in one change.
2. **Every admitted cell is a TCK case.** Each runs on all three strategies and every Tier-1 vendor, asserting ids,
   values, the row count and the total (AC-VND-19). A Hibernate patch bump, Dependabot's included, runs them on its
   line, and a failing cell blocks the bump.
3. **A statement preflight.** Before the first statement of a call, `preflight` gets each select statement the call
   derived that holds a treated node, with its shape. The Hibernate renderer translates it to Hibernate's SQL AST
   with no JDBC call, through the path S3 measured (`createQuery(criteria)`, its SQM statement, the select translator,
   the `SelectStatement`), and walks it. On `JOINED` it reads the join type of each subtype table join under a node
   the statement reads untreated. On `SINGLE_TABLE` and `TABLE_PER_CLASS` both lines hide the narrowing in the SQL
   fragment of the root's table reference, a derived table or a pruned union, so the walk inspects those fragments
   too; a walk of joins and the outer `where` alone missed 19 narrowed shapes per `SINGLE_TABLE` target. CTE bodies
   and sub-selects are walked recursively. A restriction the statement asks for (a subtype root, an INNER treated
   join, a sub-select root the engine declares it narrowed, `ofType`) is expected; S3 never covered sub-selects, so
   the engine lists the ones it narrows on purpose, a path `exists`'s correlated root, with each statement (D-157). A
   narrowed node is `MQ4016`, and so is an AST shape the adapter doesn't recognise:
   it fails closed. S3 found no false positive or negative over 45 shapes on three vendors, 135 observations per line
   and strategy. Each page shape of a keyset page or export is built with placeholder parameters and preflighted
   before page 1, per key and for a non-NULL and a NULL cursor value. A first translation and walk took a median
   64 µs on 6.6 and 98 µs on 7.4, a repeat 26 µs and 36 µs. M28 keeps no verdict cache: the renderer gets a fresh
   criteria object on every call, with no key to cache it under, so each distinct statement is preflighted once per
   call. The adapter uses Hibernate SPI, internal and `@Incubating` types (the translator's creation context is
   reached differently on 6.6 and 7.4), so it is a per-line adapter inside `model-query-hibernate`, as RFC 0010's
   mutation adapters are. INV-7 holds, since only that module imports `org.hibernate`, and guard 1 keeps it to the
   lines it was built for.
4. **The page fallback, a WARN diagnostic.** R-PAG-27's `MQ2214` (§6) logs an undercount that shows on a last offset
   page, once per `ModelQuery`, and returns the page.
   Its blind spots are those S3.4 lists: a page short of the end, no page, keyset and export, `count(q)` alone, rows
   both statements lose, and an overcount.
5. **`PortabilityCheck` reports the treat sites.** Per declared target, without a database, it reports each model's
   treat sites as `MQ4014` or `MQ4016`, from the table's answer alone: the preflight was not run over R-VND-20's offline
   factory, so it runs only at execution. A user checks a mapping and a line before deploying, as it reports `MQ4014`
   for a source feature (R-VND-25).

### 11. Spike results

Ran 2026-10-19; results in `rfc/0016-spike-results.md`, raw data and the renderer table in `rfc/0016-spike-data/`.
Run on the RFC 0014 and RFC 0015 harness as a throwaway `Rfc16SpikeTest` on local branch `spike/rfc-0016`, on
Hibernate 6.6.58 and 7.4.11, each against H2, PostgreSQL 16 and MySQL 8.0.31: 17,658 observations on 6.6 and 17,028
on 7.4. One fixture hierarchy per strategy: an abstract base, sibling subtypes A and B, a grandchild C of A, NULL and
non-NULL subtype values, and on `SINGLE_TABLE` same-named and differently named sibling attributes on one physical
column. Every pass asserts ids, values, counts or affected rows; every failure keeps its SQL or translation error.

- **S1, the planned probes.** Line parity; treat in `SELECT`, `ORDER`, `GROUP BY`, expressions and aggregates; 18
  predicate shapes as content and count, alone, in `or` beside a base or a sibling predicate, and in `exists`; valid
  correlated counts (own root on the id and on a foreign key); treated LEFT and INNER joins, presence and real
  association models; `TYPE` select, predicate, group and order; counts derived from real content queries; keyset,
  offset and export; subtype roots, locks and tables; sources and unions; bulk update and delete with all 18
  operators; the mapping variants; unmapped discriminator values.
- **S2, the count and anchor follow-ups.** Today's count path; fresh `count(root)`, `count(*)` and `count(id)`, each
  with and without an untreated id predicate; a keyless select, a CTE body and a group with and without an id anchor;
  a LEFT entity join on the id; a root `@SQLRestriction`.
- **S3, the preflight follow-up.** A pre-execution translation to the SQL AST on both lines; a detector checked
  against executed fixture values; translation, walk and cache cost; the last-page fallback.

What the results changed in this RFC:

- **No shared-column leak** (§2): both lines read a shared `SINGLE_TABLE` column under a `CASE` guard. The refusal
  stays only as the fail-closed default; the opt-in guard alternative is dropped.
- **Narrowing follows the statement, not the use** (§2, §10): an anchored statement keeps every row; a count, a group,
  an aggregate or a keyless select or CTE body narrows. `SINGLE_TABLE` predicates render no discriminator
  restriction; `JOINED`'s INNER subtype join appears only in narrowed statements. Hence `StatementShape` and
  `anchored`, the id anchor and the fresh root-id count, which lifts the earlier refusal of totals over `isNull`,
  `not(isNotNull)`, `ne` and `notIn`.
- **A select-only treat changes today's count** (three of five, through `createCountQuery` and through today's path).
  The previous "adds nothing to the count" premise was unmeasured and is false; the fresh id count replaces it.
- **Groups and aggregates narrow** on every line and strategy: refused, with the LEFT entity join as Q-27.
- **`TYPE`**: an absent LEFT row's type throws; `JOINED` order without a discriminator is invalid SQL (§3). An
  unmapped discriminator value: `notOfType` keeps it, `ofType` drops it, `typeOf` throws.
- **No vendor split**: the correlated-count split was a broken probe, and the valid own-root counts pass everywhere.
  `VendorProfile#treats` and the `MQ4015` treat fact are dropped.
- **Mapping variants**: subtype `@SQLRestriction` leaks on 6.6 and fails bootstrap on 7.4; a root one works; an
  `optional = false` secondary table LEFT-joins. **Bulk writes**: `SINGLE_TABLE` fails translation, null-accepting
  sets change too few rows. **Line parity**: no other pass or fail differs between the lines.
- **The preflight is viable but not universal** (S3): structural on `JOINED`, lexical over SQL fragments on
  `SINGLE_TABLE` and `TABLE_PER_CLASS`, exact on its 45 shapes. It backs the table; it doesn't widen it.

### New ids

None of these exist in `docs/spec`, another RFC or a source file (checked 2026-10-19). The acceptance decision is the
next free `D-n`, D-154.

| Id | Where | What |
|---|---|---|
| R-COL-34 | `api/10` | `TableField#treat`: a treated node, its join key, where it sits, what it resolves over, `MQ1106` |
| R-COL-35 | `api/10` | A subtype column reads NULL on other subtypes in select, predicates, order, keyset and count; nullable for every rule; not a group key or aggregate argument (`MQ4016`, Q-27); where the rule holds (anchored content and source bodies, LEFT treated joins, root-id counts) and that the engine builds only those; the hidden `mq_0id` anchor and its `distinct()`/set-operation refusal; a shared `SINGLE_TABLE` column |
| R-COL-36 | `api/10` | `Expr.typeOf`: the exact entity class; an offset order key of unspecified order; keyset `MQ1208`; `MQ4016` for a `JOINED` order without a discriminator and over a LEFT-joined node |
| R-FLT-28 | `api/12` | `ofType`/`notOfType`: instance-of over the metamodel's subtypes; `Optional` and empty-set forms |
| R-SRC-20 | `api/17` | Treated and `@Subtype` fields in a source, its body anchored on the root id; a union of subtype branches under R-SRC-06 unchanged |
| R-PAG-26 | `engine/21` | A duplicate key on a keyset or export page, `byKeys`, `one(q, key)` or fetch-plan parents is `MQ2213`, on every root; `list()` and offset pages unchecked |
| R-PAG-27 | `engine/21` | An offset page with a total over a treated statement that returns rows and more than `total - offset` logs one `WARNING` `MQ2214` per `ModelQuery` and returns the page, logged, never thrown; its blind spots |
| R-PROC-41 | `processor/30` | `treat` on `@Column`, `@FilterColumn`, `@Join`; its checks (`MQ3042`, `MQ3001`) |
| R-PROC-42 | `processor/30` | A `@Join` to a nested model rooted at a subtype (treated join) or supertype of the target; its `treat` targets |
| R-PROC-43 | `processor/30` | `@Subtype` (`MQ3043`), `Class` only |
| R-GEN-41 | `processor/31` | `<SUBTYPE>_TREAT` constants, columns over them, downcast joins |
| R-GEN-42 | `processor/31` | The `@Subtype` constant and its mapping |
| R-VND-26 | `vendor/40` | `ProviderSupport#treats(emf)`, `TreatRenderer` with `preflight`, `TreatSite`, `StatementShape`, `TreatUse`; the allow-list by shape, anchoring and use set; the mapping gates; the 6.6.x/7.4.x line set; every site and statement before any statement; treated writes refused; `PortabilityCheck`'s treat report |
| `MQ1106` | `reference/90` | `treat` misused: an improper subtype, a treat of a treat, `as`/`on` on a treated join, a source root, an unmapped subtype, a treat over a sibling type; or `ofType` with an unmapped type |
| `MQ2213` | `reference/90` | A read that relies on a unique key met two rows with one key |
| `MQ2214` | `reference/90` | An offset page over a treated statement returned more rows than its total leaves after the offset: the count lost rows, or a row was committed between the count and the content. Logged, never thrown: an `MQ2xxx` code that is a `WARNING`, an exception to `reference/90` §1's exception mapping, as `MQ1702` is on `count` |
| `MQ3042` | `reference/90` | A `treat` member that isn't a strict `@Entity` subtype of the root, is redundant, or sits on a key, a `@Discriminator`, a source-rooted or write model, or a model generating writes |
| `MQ3043` | `reference/90` | A `@Subtype` field misused |
| `MQ4016` | `reference/90` | The provider's `TreatRenderer` refuses a treated site for its shape, anchoring and uses on this line and strategy (a `CHILD` statement and a treated write among them), a mapping variant or a composite id gates it, a `distinct()` query or non-ALL set operation would need the hidden id anchor, or the preflight finds the statement's translation narrowed or unrecognised |
| AC-COL-44 | `api/10` | Per strategy, a treated column in the select list keeps every row, reads the value on subtype and grandchild rows and NULL on sibling rows, alone and in a `coalesce`, in a keyed and in a keyless model |
| AC-COL-45 | `api/10` | Per strategy, `eq`/`in`/`like`/`isNotNull` keep only matching subtype rows; `isNull`, `ne`, `notIn` include sibling rows; `not(eq)` excludes them (SQL `NOT` over NULL, D-5, where JPA's "false" would include them); a treat in `or` keeps the base operand's sibling rows; each `page()` total equals the content's rows |
| AC-COL-46 | `api/10` | Per strategy, an offset order by a treated key places sibling rows by NULL under `nullsFirst`/`nullsLast`; a treated group key or aggregate argument, in a query or a source, is `MQ4016` before any statement |
| AC-COL-47 | `api/10` | Per strategy, a LEFT downcast join is empty for a sibling or absent target and present for the subtype, also when its values are all NULL; INNER drops those parents; an upcast nested model reads inherited attributes; a join from a treated root |
| AC-COL-48 | `api/10` | `MQ1106` for each misuse at definition and at first resolution, a sibling root and a sibling re-root included; `MQ1104` for `as`/`on`/`presentBy` on a treated root; `MQ1003`'s message names `ROOT.treat(...)` |
| AC-COL-49 | `api/10` | `typeOf` selects each row's exact class, `eq` matches exactly, a group by type counts per type, an offset order by it repeats across two runs on `SINGLE_TABLE`, `TABLE_PER_CLASS` and `JOINED` with a discriminator; `JOINED` without one and `typeOf` over a LEFT-joined node are `MQ4016`; keyset is `MQ1208` |
| AC-COL-50 | `api/10` | Per strategy, a model rooted at a mid-level subtype reads its and the grandchild's rows only in content, count, keyset and export; `MQ4010` on a `JOINED` or `TABLE_PER_CLASS` base root; a lock on a `SINGLE_TABLE` subtype root |
| AC-COL-51 | `api/10` | Per line, a treated column on a `SINGLE_TABLE` column a sibling maps, same-named and differently named, reads NULL on the sibling's rows in the select list, an order and a predicate that matches only the sibling's value; a shared-column site the table lacks is `MQ4016` before any statement |
| AC-COL-52 | `api/10` | Per strategy, `page()`'s total and `count(q)` with a select-only or order-only treat and with `isNull`, `ne`, `notIn` and `not(isNotNull)` equal the content's rows, rendered as `count(root.id)` with no selection or order (SQL snapshot); a grouped `@Subtype` total counts groups; a keyless model's content and a source body select the root id last as `mq_0id`, absent from `fields()`, the default sets, the mapper and `Rows`; a `distinct()` query or a `union`/`intersect`/`except` that would need it is `MQ4016`, and runs when the model selects the root id |
| AC-FLT-30 | `api/12` | `ofType` keeps a grandchild and an `orm.xml` subtype, accepts an abstract type, skips on an empty `Optional`, keeps no row for an empty collection |
| AC-FLT-31 | `api/12` | `notOfType` includes rows of an absent LEFT join and of an unmapped discriminator value, and keeps every row for an empty collection |
| AC-SRC-39 | `api/17` | Per strategy, treated and `@Subtype` fields in a source body read as AC-COL-44 and AC-COL-49, with the root id selected in the body |
| AC-SRC-40 | `api/17` | Per strategy, a `unionAll` of card and bank branches by `@Discriminator` returns each `(kind, id)` once with `reference` from `last4` or `iban`; without the discriminator it is non-unique (`MQ2212` on export) |
| AC-SRC-41 | `api/17` | Branches rooted at `CardPayment` and `PremiumCardPayment` return a premium row twice with two `kind` values and unique keys |
| AC-PAG-29 | `engine/21` | Per strategy, keyset pages both ways ordered by a treated key under explicit precedence, NULLs at page edges and a page resuming after a NULL cursor value, visit every id once and the count equals the rows; a cursor of `CARD_TREAT.reference asc` on `BANK_TREAT.reference asc` is `MQ2209`; an untreated key's cursor is unchanged |
| AC-PAG-30 | `engine/21` | Per strategy, offset pages and a full export ordered by id with a treated predicate visit every matching id once |
| AC-PAG-31 | `engine/21` | Two `TABLE_PER_CLASS` rows with one id, written by SQL, on one id-ordered export page or keyset page, in `byKeys` or as fetch-plan parents are `MQ2213` before the page or map returns; `list()` and an offset page over the same rows return both rows; a non-inheritance entity over a view with one duplicated `@Id` behaves the same |
| AC-PAG-32 | `engine/21` | A last offset page over a treated statement whose rows exceed `total - offset`, a row committed between the count and the content, logs one `WARNING` `MQ2214` and returns the page; a second call on the same `ModelQuery` logs nothing; an empty page past the end and an early page log nothing |
| AC-GEN-34 | `processor/31` | `<SUBTYPE>_TREAT` constants, columns over them and a downcast `.treat(...).presentBy(...)` join, as §9; a clash is `MQ3015` |
| AC-GEN-35 | `processor/31` | The `@Subtype` constant through `Expr.typeOf`, in the default sets, mapped as a `Class` |
| AC-PROC-42 | `processor/32` | `MQ3042` for a non-entity, `@MappedSuperclass`, unrelated or root `treat`, a redundant one, one on a key, a `@Discriminator`, a source-rooted or write model, or with `generateChanges`/`generateInserts` |
| AC-PROC-43 | `processor/32` | `MQ3043` for a second `@Subtype`, another type, a combined annotation, a source-rooted or write model; `@GroupBy @Subtype` compiles |
| AC-PROC-44 | `processor/32` | A nested root that is a subtype or supertype of the target compiles; an unrelated one is `MQ3003`; a nested `treat` target that is a sibling of the target, at any `@Join` level, is `MQ3003` naming the field |
| AC-PROC-45 | `processor/32` | `MQ3001` naming the subtype for an attribute it lacks; a primitive treated component is `MQ3009` |
| AC-VND-16 | `vendor/40` | No renderer, a Hibernate minor outside 6.6 and 7.4, or compliance mode: every treated node, `typeOf` and `ofType` is `MQ4014` |
| AC-VND-17 | `vendor/40` | A site outside the table is `MQ4016` before any statement, an export's later page shapes and its count included; a treated node in a `CHILD` statement is `MQ4016`; `PortabilityCheck` reports the same sites offline, from the table alone |
| AC-VND-18 | `vendor/40` | A subtype `@SQLRestriction` on 6.6, a null/not-null discriminator value and an `@IdClass` or `@EmbeddedId` hierarchy are `MQ4016`; a root `@SQLRestriction` runs; each admitted mapping variant runs under its own cells |
| AC-VND-19 | `vendor/40` | Every admitted cell runs as a TCK case per strategy on every Tier-1 vendor and both lines, asserting ids, values, the row count and the total; each admitted `COUNT` cell as `count(root.id)` |
| AC-VND-20 | `vendor/40` | A treated `ModelUpdate`/`ModelDelete` `where` is `MQ4016` before any statement, on every strategy, vendor and path, key-first included |
| AC-VND-21 | `vendor/40` | On both lines and every strategy, the preflight of an unanchored `count(root)` over `isNull` is `MQ4016` and of its anchored form empty, with no JDBC statement; each later keyset page shape, non-NULL and NULL cursor, is preflighted before page 1; an unrecognised AST shape and a narrowing it can't attribute are `MQ4016` |
| Q-25, Q-26, Q-27 | `reference/92` | `@Subtype`'s value type; `TABLE_PER_CLASS` id trust; group keys and aggregates over treated fields |

**D-154 — Inheritance roots (RFC 0016, user decisions and final architect check, 2026-10-19).**

- **A shared `SINGLE_TABLE` column** reads NULL on a sibling's rows: both lines render a `CASE` guard (§11). The
  per-line, per-attribute refusal stays only as the fail-closed default for a shape the table lacks (`MQ4016`).
- **`TABLE_PER_CLASS` ids are trusted as today**, documented as Q-26 with what makes the trust false and what it costs
  (§6). R-PAG-26 adds a partial guard on every root, `MQ2213`, on the reads that rely on a unique key (keyset and
  export pages, `byKeys`, `one(q, key)`, fetch-plan parents); `list()` and offset pages are unchecked.
- **`@Subtype` is `Class` only** (Q-25). `JOINED` has no discriminator value, a `String` or enum form is additive
  later, and a `@Computed` CASE covers it today.
- **`typeOf` is an offset order key** of unspecified order, deterministic per deployment with the id tie-breaker.
  Keyset refuses it with `MQ1208`. On `JOINED` an order by it is `MQ4016` unless the persister has a discriminator.
- **The default is strict.** A provider without a `TreatRenderer` gets `MQ4014` for every treated use, as R-VND-25 and
  R-SRC-11 are strict.
- **Sites carry their statement.** `TreatSite` carries `StatementShape` and `anchored`; the allow-list is keyed by
  shape, anchoring and use set, and anything else is `MQ4016` before the first statement.
- **Counts count the root id.** A statement with a treated node is one with a `treat` node or a downcast join, not
  `typeOf` or `ofType` alone. Every count over one is built fresh with only its predicates and counts `root.<id>`,
  which admits totals over null-accepting predicates; a grouped `@Subtype` query's total keeps R-AGG's group count.
  Content statements and ungrouped source bodies with a treated node select the untreated root id. R-COL-35 says where
  the NULL rule holds and that these renderings enforce it.
- **The hidden anchor.** When a statement selects no untreated `root.<id>`, the engine selects it last as `mq_0id`,
  outside D-146's `mq_<property>` space and never in `fields()`, `DEFAULT`, `ALL`, a mapper or the generated `Rows`. A
  `distinct()` query or a non-ALL set operation that would need it is `MQ4016`, since it would change the rows, unless
  the model selects the root id itself.
- **Group keys and aggregates over treated fields are refused** (`MQ4016`) in M28. The LEFT entity join on the id and
  the `min(root.id)` group anchor are Q-27, for a later release.
- **No vendor fact.** `VendorProfile#treats` and the `MQ4015` treat path are dropped: no Tier-1 vendor split was
  measured.
- **Mapping gates.** A subtype `@SQLRestriction` is `MQ4016` for the hierarchy's treated uses on 6.6 (7.4 fails at
  bootstrap); a root one is allowed. A `@DiscriminatorValue("null")`/`("not null")` hierarchy is `MQ4016`. Force,
  formula, `JOINED` explicit discriminator and secondary-table variants are keyed per variant, unmeasured cells
  refused. Compliance mode is `MQ4014`.
- **Composite ids refused.** The root-id count and anchor were measured over a single id attribute, so an `@IdClass`
  or `@EmbeddedId` hierarchy is `MQ4016` until one is measured.
- **`CHILD` refused.** No `CHILD` cell was measured, so a treated node in a fetch plan's child statement, a join plan
  or an enricher is `MQ4016` in M28.
- **Treated bulk writes refused.** A treated `ModelUpdate` or `ModelDelete` `where` is `MQ4016` on every strategy in
  M28: `SINGLE_TABLE` fails translation on both lines, and `JOINED` and `TABLE_PER_CLASS` wait for a measured mutation
  preflight. The refusal precedes R-WRT-11's path choice, so key-first and single-statement writes are refused alike
  on every vendor (INV-6).
- **The preflight fails closed and caches nothing.** A narrowing the adapter can't attribute to an intended
  restriction (several treated targets, a formula discriminator, quoting or schema variants, a treat beside a subtype
  root, an INNER treated join or `ofType`) is `MQ4016`; the TCK pins each admitted composition. Each distinct
  statement, every later keyset page shape included, is preflighted once per call before the first statement; M28
  has no verdict cache, since the renderer gets a fresh criteria object each call.
- **The offline `PortabilityCheck` gives the table's answer alone.** Translation issues no JDBC call (S3.2), but it
  was not run over R-VND-20's offline factory.
- **The page fallback is a WARN log, never thrown.** R-PAG-27 logs one `WARNING` `MQ2214` per `ModelQuery`, with no
  bound values (D-95), and returns the page: under READ COMMITTED a row committed between the count and the content
  trips it on a correct query. It applies to treated statements only, so no existing page changes. `MQ1702` on
  `count` is the precedent for a logged code.
- **Five guards for relying on observed Hibernate behaviour** (§10): only the 6.6.x and 7.4.x minors, every admitted
  cell a TCK case, the statement preflight in a per-line adapter of `model-query-hibernate`, the page fallback's
  `WARNING` (`MQ2214`), and `PortabilityCheck`'s treat report.

## Compatibility

Additive. `MQ3003` narrows: a nested model rooted at a subtype or supertype of the target compiled to an error and now
compiles. The `MQ1003` and `MQ2209` messages change, not their meaning. `@Column`, `@FilterColumn` and `@Join` gain a
defaulted member, so source and binary compatible. `TableField`, `Expr`, `Filters`, `RenderOptions` and
`ProviderSupport` gain members, and `ProviderSupport#treats` is a default method. `Filters` is sealed, so its new
methods break no implementor. Models without `treat` or `@Subtype` generate the same code. R-EXE-05's fresh root-id
count and R-PAG-27's warning apply only to a statement with a treated node, which no query has today, so no existing
count changes and no SQL snapshot moves. An untreated key's fingerprint is unchanged, so existing cursors decode.
R-PAG-26 applies to every root: a root whose rows share a key, which already broke INV-4, now gets `MQ2213` on a keyset
or export page, `byKeys`, `one(q, key)` or a fetch plan's parents instead of a lost or doubled row; `list()` and offset
pages are unchanged. Every new type and member is `@Incubating`.

## Alternatives

- **No treat; one model per subtype only.** It works today. But a list over every payment needs one statement per
  subtype and a merge, which the library can't page. A `unionAll` of subtype branches (§8) covers some of it, at one
  table read per branch.
- **Treat in a path string** (`@Column(attribute = "treat(this as CardPayment).last4")`), as JPQL writes it. The
  processor would parse a second syntax, and a subtype rename would surface as a string error. A `Class` member is
  checked by javac and refactors.
- **A portable default renderer over `cb.treat`.** JPA defines treat only in `FROM` and `WHERE`, with "the predicate is
  false" semantics that differ from §2's for `isNull`, `ne` and `not`, and leaves `SELECT` to the provider. A default
  that renders whatever the provider does breaks INV-5 on the first provider that restricts rows.
- **A flat `refusal(root, subtype, use)` per use.** It can't tell `not(isNull)` from `isNull`, an `or` inside `exists`
  from one outside, or a `coalesce` operand from a plain select.
- **A per-read `TreatSite` without its statement** (the previous draft). The same `NULL_TEST` set keeps every row in
  anchored content and loses rows in a count, and a count derived from content narrows on a treat it no longer reads,
  so no remaining site could report the hazard (§11). `StatementShape` and `anchored` name both.
- **Repairing the count after the fact:** Hibernate's `createCountQuery`, or today's path of replacing the content's
  selection. Both keep the content's treated root and narrow (S2.1). A fresh `count(root)` or `count(*)` still
  undercounts null-accepting predicates (S2.2); only the counted root id passes.
- **A LEFT entity join on the id instead of `cb.treat`** (`JpaFrom.join(Class, LEFT)`, on both lines). It passes every
  measured shape, groups and aggregates included (S2.4), but it is another rendering, a self-join on `SINGLE_TABLE`
  with unmeasured cost, and the group anchor `min(root.id)` is a second design. Q-27, later.
- **The allow-list alone, or the preflight alone.** The table checks each site, not the statement they compose into.
  The preflight is lexical over fixture-shaped SQL fragments on `SINGLE_TABLE` and `TABLE_PER_CLASS`, exact on 45
  known shapes but not a universal detector (S3). Together each covers the other's gap; neither widens what the
  spike measured.
- **The page fallback as the guard.** It sees an undercount only on a last page with a total (R-PAG-27), so it backs
  the count's correctness and can't replace it.
- **Throwing on the page fallback.** Rejected: a row committed between the count and the content under READ
  COMMITTED trips it on a correct query, so it logs a `WARNING` instead.
- **Admitting `JOINED` and `TABLE_PER_CLASS` writes on the table alone.** Rejected: the preflight has no translation
  check for mutations, so the table would be the only guard on a bulk write.
- **The subtype list on the SPI.** `Metamodel#getEntities()` gives it portably, `orm.xml` included, so the renderer
  keeps only what needs the provider.
- **A vendor fact `VendorProfile#treats(TreatUse)`.** The previous draft had one, for a per-vendor failure. The spike
  measured none (the earlier split was a broken probe), so it would be surface with no `false` answer. A later vendor
  split can add it as a default method.
- **An explicit guard everywhere** (`case when type(p) in (...) then treat(p).x end`). It would make §2's rule hold by
  construction, but the column becomes an expression: keyset refuses it (`MQ1208`), and a predicate on it can't use an
  index. Hibernate already renders that guard where a column is shared (§2), and only there.
- **Refusing a shared column in the processor.** It sees only entities in the compilation or on the classpath, not
  `orm.xml`. The entity persister sees every mapping, and the spike shows the provider guards it.
- **`TABLE_PER_CLASS` roots non-unique** (`MQ1901`, `MQ2212`). Sound, but it takes keyset and export away from every
  such list for data that also breaks Hibernate's `find` (Q-26).
- **"Disjoint by hierarchy"** for a union of subtype branches without a `@Discriminator`. Uniqueness is decided when
  `union` is built, from definitions, with no mapping strategy. Only `SINGLE_TABLE` makes sibling rows disjoint in the
  database. On `JOINED`, nothing stops one base row from having rows in two sibling tables, and on `TABLE_PER_CLASS`,
  nothing stops two tables from holding one id. Either would duplicate a key. `@Discriminator` costs one field.
- **Refusing overlapping branch roots** in a `unionAll`. The keys stay unique, and listing a premium card as both
  `CARD` and `PREMIUM` can be intended.
- **Blaze-style entity-view inheritance:** a view type per subtype, chosen per row. It fits interface-based views, not
  flat records mapped from one tuple, and it needs a polymorphic mapper and per-subtype `SelectSet`s. `@Subtype` plus
  NULL-reading columns gives the same data. A record built per subtype in `afterMap` covers the polymorphic object when
  a caller wants it.
- **`treat` on `@Aggregate`.** Aggregates over treated fields are refused in M28 (Q-27); widening the annotation waits
  for that rendering.

## Unresolved questions

The design questions of the first review, the spike and the final architect check were decided on 2026-10-19
(D-154). Three stay open in `reference/92`:

1. **Q-25, `@Subtype`'s value type.** `Class` only for now. A `String` (entity name or discriminator value) or an enum
   through a converter is additive, once a use shows which; `JOINED` has no discriminator value to give.
2. **Q-26, `TABLE_PER_CLASS` id uniqueness.** Trusted, with R-PAG-26 as a partial guard (§6). Revisited if a user
   needs keyset or export over such a root with ids written outside JPA.
3. **Q-27, group keys and aggregates over treated fields.** Refused in M28. The LEFT entity join on the id (S2.4) and
   a `min(root.id)` anchor beside the original group key (S2.3) keep the NULL rule in every measured shape; a later
   release designs the rendering, measures its cost and composition, and lifts the refusal.

Later, not in this RFC: a treat in the middle of a `@FilterColumn` path (`customer` read as `VipCustomer`, then `tier`),
which a hand-written treated join covers today; group keys and aggregates over treated fields (Q-27) and `treat` on
`@Aggregate`; `typeOf` over a LEFT join; treated columns in change sets and inserts; treated bulk-write predicates, once
a mutation preflight is measured; keyset over `typeOf`; treated child statements; a vendor fact, if a vendor split
appears.
