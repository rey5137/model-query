# RFC 0022 — Portable entity joins, outer-column anchors, request-bound expression values, joined-entity row counts

- **Status:** draft. Follows RFC 0021 (D-193 to D-196), and closes what it left out: its Resolution 3 (no portable
  renderer), A.2 (no anchor on an outer entity's plain column) and Resolution 7 (no row-count form for a joined
  entity), plus Q-24 (request-time values in a `@Computed`, left by RFC 0015).
- **Affects:**
  - **Part A, `vendor/40`:** new R-VND-33, a portable `EntityJoinRenderer` on Jakarta Persistence 3.2, found when the
    factory's `ProviderSupport` has none; R-VND-32's "no portable default" sentence points to it; new `MQ4017`; new
    AC-VND-23, AC-VND-24. **`delivery/61`, `SPEC.md`:** the module tree and the module table gain
    `model-query-jpa32`; the BOM gains it.
  - **Part B, `api/17`:** new R-SRC-25, an entity join anchored on columns of the root or of its to-one paths, with no
    joined source; R-SRC-23's "a model that joins a source" opening and R-SRC-24's "created right after its anchor
    source join" sentence each name the second anchor kind. **`processor/30`, `processor/32`:** R-PROC-46 gains
    `@JoinEntity.On#column`, and `source` and `On#field` gain a `""` default. New AC-SRC-59 to AC-SRC-62, AC-PROC-49.
    `MQ1911`, `MQ1912`, `MQ3046` gain cases; `MQ2211` and `MQ4015` apply as stated in B.5.
  - **Part C, `api/10`:** new R-COL-40, `QueryParam` and `Expr.param`, bound per query with `bind`; R-COL-17's
    factory list and R-COL-18 name it. Q-24 is resolved. New `MQ1512`; new AC-COL-59 to AC-COL-62.
  - **Part D, `api/13`:** R-AGG-19's walk continues through an `INNER` joined entity to the root it is attached to,
    read from the statement's join record, not from `Join#getParent()`. **`processor/30`:** new R-PROC-47,
    `@Aggregate#entityJoin`. New AC-AGG-31, AC-AGG-32, AC-PROC-50.
  - **`reference/90`:** new `MQ1512`, `MQ4017`; widened rows for `MQ1911`, `MQ1912`, `MQ3046`.
  - **`docs/site`:** the joins page (outer-column anchors), the sources page (the entity join's provider note), the
    expressions page (`QueryParam`), the providers page (`model-query-jpa32`); each runnable snippet in both tabs
    (CC-DOC-01).
- **Discussion:** none yet.
- **Target:** 0.11.0, as M34; the 1.0 freeze moves to M35 and stays last. Every new type, member and annotation member
  is `@Incubating`. The four parts are separable (§Compatibility, Resolution 1). Part A on its own gives no user a new
  query: see A.1.

## Summary

Four parts, separately acceptable:

- **A.** A new artifact, `model-query-jpa32`, compiled against `jakarta.persistence-api` 3.2.0, supplies an
  `EntityJoinRenderer` that calls `From#join(Class, JoinType)` and `Join#on(Predicate...)` directly, with no
  reflection. It serves only when the factory's provider supplies no renderer of its own, so Hibernate 6.6 and 7.4
  render as today. On a classpath whose Jakarta Persistence API or provider is below 3.2, the executor refuses at
  creation with `MQ4017`, found by one probe call that catches a `LinkageError`.
- **B.** An entity join anchored on a column of the root or of its to-one paths, `@JoinEntity.On(column = ...)`, for
  an id-equal column that has no association mapping (an entity you can't change, a column with no foreign key and
  orphan values). It needs no joined source, so it is the first entity join a non-Hibernate provider can render.
- **C.** A `QueryParam<C>`, an immutable named and typed placeholder that may be `static final`, read in any
  expression through `Expr.param(...)` (a `@Computed`, a CASE, a correlated count's filter). The built query gets the
  value from `bind(param, value)`, the way `where` and `joinFilter` take values. Definitions stay immutable (INV-9).
- **D.** A count of an `INNER` joined entity, and of an `INNER` chain below it, renders the provider's row count
  (`count(*)`, R-AGG-19) on both lines, since such an entity can't be null on any row. A `LEFT` one, filtered or not,
  keeps `count(<first id attribute>)`. `@Aggregate` gains `entityJoin`, so a generated model can count or aggregate a
  joined entity's attributes.

## Motivation

**A.** RFC 0021 Resolution 3 left every provider but Hibernate at `MQ4014`: `core` compiles against 3.1.0
(`pom.xml:73`) and the 6.6 line runs it, so `core` can't call 3.2's `From#join(Class, JoinType)`. Jakarta Persistence
3.2 providers exist (Hibernate 7, EclipseLink 5.0). Without a 3.2 route, the only way for them is a `ProviderSupport` per
provider, written with reflection as `HibernateEntityJoinRenderer` is
(`model-query-hibernate/.../HibernateEntityJoinRenderer.java:36-46`).

**B.** A sales report over a legacy schema: `invoice(id, number, rep_code, region_code, …)`, `employee(code, name,
team_id)` with `code` as the id, `region(code, name)`. `invoice.rep_code` has no foreign key and holds codes of
employees who have since been deleted (orphans). The `InvoiceEntity` and `EmployeeEntity` classes come from a shared
`billing-model` jar that the reporting team can't change. Native SQL:

```sql
select i.id, i.number, e.name, t.name, r.name
from invoice i
left join employee e on e.code = i.rep_code
left join team t     on t.id = e.team_id
left join region r   on r.code = i.region_code
where i.issued_on >= ? and (e.team_id = ? or ? is null)
order by i.id
```

RFC 0021 A.2 says to map a read-only `@ManyToOne` instead. That route fails here three ways:

1. **The entity can't be changed.** The mapping belongs to another module, and adding an association changes that
   module's entity graph for every caller (its `equals`, its serialisation, its `JOIN FETCH` behaviour).
2. **Orphans.** With no foreign key, a read-only `@ManyToOne(fetch = LAZY)` over `rep_code` is a proxy that throws
   `EntityNotFoundException` when any other code path in the application touches an orphan. Hibernate's
   `@NotFound(IGNORE)` avoids that but forces an eager load and is provider-specific. Model-query never loads the
   entity, so a criteria join by id equality is the safe read.
3. **Portability.** An entity join today needs a joined source, and a source needs a `SourceRenderer`, which only
   Hibernate supplies. A column anchor needs neither, so Part A plus Part B is the first entity join that renders on a
   3.2 provider other than Hibernate.

**C.** Q-24 (`reference/92-decisions-questions.md:3158-3161`): a `@Computed` definition is built once into a
constant, so two common report columns can't be written:

- "open lines whose status is the one the request names", a correlated count (`docs/site/docs/subqueries-expressions.md:244`
  writes it with the status fixed in the definition);
- "overdue as of the date the caller passes", `case when i.due_on < :asOf and i.paid_on is null then true else false end`.

The workarounds are a column per status value, a native query, or rebuilding the model's column set per request,
which R-COL-17 and INV-9 rule out.

**D.** RFC 0021 Resolution 7 counts a joined entity by its first id attribute in both kinds. The reason was the line
split: on 6.6 Hibernate's entity join is not a `jakarta Join`, and on 7.4 it is (`JpaEntityJoin` extends `JpaJoin`).
A `Join#getParent()` walk would therefore render `count(*)` on one line and `count(p.id)` on the other. Under `INNER`
the identity can't be null, so `count(p.id)` is exactly the case RFC 0020 Part F changed everywhere else. That part
measured the cost on PostgreSQL: about 10% on a grouped read, since `count(col)` checks every value for null
(`rfc/0019-spike-results.md`). The joined entity is the one site left. Separately, `@Aggregate` has no `entityJoin`
member, so a grouped generated model can't count, sum or `max` a joined entity's attribute at all
(`model-query-annotations/.../Aggregate.java:23-73`).

## Design

### Part A — a portable entity-join renderer on Jakarta Persistence 3.2 (R-VND-33)

**A.1 What it buys, alone and with Part B.** Every entity join that RFC 0021 allows is anchored on a joined source.
A joined source needs `ProviderSupport#sources()`, and only `model-query-hibernate` supplies one. Hibernate also
supplies `entityJoins()` and ranks first (A.3). So **Part A alone changes no query for any user**: it serves only a
third-party `ProviderSupport` that has `sources()` and no `entityJoins()`. With Part B, a model with a column-anchored
entity join and no source runs on any 3.2 provider. Recommendation: accept A only with B, and build B first
(Resolution 1).

**A.2 The module.** `model-query-jpa32`, package `com.rey.modelquery.jpa32`:

```xml
<!-- model-query-jpa32/pom.xml -->
<dependency>
    <groupId>io.github.rey5137</groupId><artifactId>model-query-core</artifactId>
</dependency>
<dependency>
    <groupId>jakarta.persistence</groupId><artifactId>jakarta.persistence-api</artifactId>
    <version>3.2.0</version>
    <scope>provided</scope>  <!-- the application's own 3.2 API wins; never upgrades a 3.1 classpath -->
</dependency>
```

```java
// META-INF/services/com.rey.modelquery.core.EntityJoinRenderer -> com.rey.modelquery.jpa32.internal.Jpa32EntityJoinRenderer
@EngineFacing
@Incubating
public final class Jpa32EntityJoinRenderer implements EntityJoinRenderer {
    public Jpa32EntityJoinRenderer() {}

    @Override
    public <E> From<?, E> join(From<?, ?> anchor, Class<E> entity, JoinKind kind,
            Function<From<?, E>, Predicate> on) {
        if (!(anchor instanceof Root<?>)) {
            throw new ModelQueryConfigurationException(MqCode.MQ4014, "an entity join attaches to the statement root");
        }
        Join<?, E> joined = anchor.join(entity, kind == JoinKind.INNER ? JoinType.INNER : JoinType.LEFT); // 3.2
        joined.on(on.apply(joined));                                                                     // once
        return joined;
    }
}
```

- **No reflection.** Both calls are plain interface calls against the 3.2 API. They link at the first call, and A.4
  makes that first call at discovery. An ArchUnit rule in the TCK's `LayeringRules` holds the module to
  `jakarta.persistence..`, `com.rey.modelquery.core..` and the JDK. It bans `java.lang.reflect..`,
  `java.lang.invoke..` and `org.hibernate..` (AC-VND-24). The core-side `hasOn` check
  (`ModelQuery.java:747-761`) takes its `Join#getOn()` branch for this renderer, since a 3.2 entity join is a
  `jakarta Join`. Its reflective branch stays for Hibernate 6.6 only.
- **The one-`on` contract** (R-VND-32, AC-VND-22, D-196) holds by construction. The renderer calls `on` once with the
  engine's one predicate (key equality, a Part B static term, the `joinFilter`). On 3.2, a second `Join#on` would
  replace the first (spike B.1 of RFC 0021), so the engine's existing call-count and identity check
  (`ModelQuery.java:734-738`) guards this renderer as it guards Hibernate's.
- **Root only.** It attaches to the statement's `Root`, as R-SRC-24 requires on every line. A `rootedAt` context, whose
  root is a join, gets `MQ4014`, as with Hibernate (B.5 keeps child models out).
- **Only `core`.** The module implements `core.EntityJoinRenderer`, which is public and `@EngineFacing`. It needs no
  `jpa` internals, so it is not a second exception to `delivery/61`'s internal-API rule (`:250`), which only
  `model-query-portability` uses. `@EngineFacing` is not API: the BOM keeps the module's version in step with `core`'s.

**A.3 Selection and rank.** The `jpa` executor resolves the renderer once per factory, when it builds its render
options (`DefaultModelQueryExecutor.java:315-318`):

1. the factory's `ProviderSupport#entityJoins()`, as today;
2. else the first `EntityJoinRenderer` that `ServiceLoader` lists and that passes A.4's probe for this factory;
3. else none, and a query with an entity join is `MQ4014` before any statement, as today.

The provider's renderer always wins, so **on Hibernate 7.4 `HibernateEntityJoinRenderer` serves**, with or without the
module on the class path, and the 6.6 and 7.4 lines render byte for byte as at `m33-verified`. When the module is on
the class path and isn't used, the executor logs one DEBUG line naming it. Step 2 runs whether or not a `ProviderSupport`
serves the factory: EclipseLink has none, and vendor detection then falls back to `DatabaseMetaData` (R-VND-06).
`PortabilityCheck`'s offline factory (`OfflineFactory.java:93`) resolves the same way, so it reports `MQ4014` or
`MQ4017` per target. `ServiceLoader` iteration reuses `VendorResolver.loadable`'s guard: a renderer class that fails to
load is skipped with the existing log levels (`VendorResolver.java:196-230`).

**A.4 A 3.1 classpath: fail at discovery with `MQ4017`.** On API 3.1.0, `Jpa32EntityJoinRenderer` loads and verifies:
`From`, `Join` and `JoinType` all exist, and a method reference resolves only when it is called. The first call to
`From#join(Class, JoinType)` throws `NoSuchMethodError`. With the 3.2 API jar forced over a 3.1 provider (EclipseLink 4,
or Hibernate 6.6 under a Boot dependency override), it throws `AbstractMethodError` instead. Both are `LinkageError`s.
So step 2 probes each candidate once per factory, without a connection:

```java
// jpa, at render-option resolution; no statement runs, no connection opens
EntityType<?> any = emf.getMetamodel().getEntities().iterator().next();   // skipped when the unit maps no entity
CriteriaBuilder cb = emf.getCriteriaBuilder();
Root<?> root = cb.createTupleQuery().from(any);
try {
    candidate.join(root, any.getJavaType(), JoinKind.LEFT, joined -> cb.conjunction());
} catch (LinkageError belowThreePointTwo) {
    throw new ModelQueryConfigurationException(MqCode.MQ4017, candidate.getClass().getName()
            + " needs Jakarta Persistence 3.2, and this factory's API or provider is older: " + belowThreePointTwo);
}
```

`MQ4017` is thrown at executor creation, so the failure comes at startup. It is not deferred to the first query with
an entity join. Justification: a candidate reaches step 2 only when the provider has no renderer of its own. A 3.1
application that put the module there by mistake gets nothing from it, and a code at startup names the cause. A
Hibernate application with the module present never reaches the probe (A.3, step 1), so it is never refused. The
probe's result is cached with the factory's vendor resolution. A probe that throws anything other than a
`LinkageError` (a provider refusing a self-join on `conjunction()`) is `MQ4017` too, with the cause attached: the
renderer can't be trusted on that factory. Spike A.2 picks the probe predicate each Tier-1 vendor and provider accepts.

**A.5 Spring Boot starter.** No change. The starter depends on `model-query-hibernate` and Spring Boot 3.4.13, which
is Hibernate 6.6 on Jakarta Persistence 3.1. It doesn't depend on `model-query-jpa32`, because with `provided` scope
the module would add nothing, and the BOM must never pull 3.2 onto a Boot 3.4 classpath. A Boot application that runs
another 3.2 provider adds the module itself; the providers page says so. A later Boot 4 starter (Hibernate 7) still
gets Hibernate's renderer first.

**A.6 Later, not here.** Once the 6.6 line is dropped (after 1.0, with `core` on 3.2), `core` can call the 3.2 method
itself. The module and `HibernateEntityJoinRenderer`'s reflection then both go, and the module is relocated to `core`
under the same service name.

### Part B — an entity join anchored on an outer column (R-SRC-25)

**B.1 The model.** `@JoinEntity` takes `On(column = ...)` in place of `source` and `On(field = ...)`. `column` names a
model column, a record component or a `@FilterColumn` name, as `@JoinSource.On#column` does (R-SRC-21):

```java
@QueryModel(root = InvoiceEntity.class)
@JoinEntity(name = "rep", entity = EmployeeEntity.class, on = @JoinEntity.On(column = "repCode"))
@FilterColumn(name = "repCode", path = "repCode")                         // the anchor; not selected
@FilterColumn(name = "issuedOn", path = "issuedOn")
@FilterColumn(name = "repTeamId", entityJoin = "rep", path = "team.id")
public record InvoiceRow(
        @PrimaryKey Long id,
        String number,
        @Column(entityJoin = "rep", attribute = "name") String repName,
        @Column(entityJoin = "rep", attribute = "team.name") String repTeam,
        @Column(attribute = "region.name") String region) {}
```

Annotation changes, all `@Incubating`:

```java
public @interface JoinEntity {
    String name();
    Class<?> entity();
    String source() default "";            // was required; "" = the root. Required when any On names a field
    JoinKind type() default JoinKind.LEFT;
    On[] on();
    @interface On {
        String field() default "";         // was required: a field of the joined `source`
        String column() default "";        // new: a model column over the root or one of its to-one paths
        String id() default "";
    }
}
```

- **Each `On` names exactly one of `field` and `column`.** All pairs of one join use the same one: `field` with a
  `source` (RFC 0021, unchanged), or `column` with `source = ""`. Anything else is `MQ3046`: both or neither set, a mix
  across pairs, a `column` with a `source`, a `field` without one.
- **The id rule is RFC 0021's** (A.1 there): the pairs cover the entity's full id, each id attribute once. An extra or
  missing pair, a non-id attribute (a unique natural key included), an association id, a converted anchor, or a
  boxed-type mismatch is `MQ3046`. So the join still adds at most one row per outer row, and R-PAG-01 to R-PAG-08 hold
  unchanged.
- **What the anchor may be.** A column whose table is the root, or a to-one path from the root
  (`@Column(attribute = "customer.legacyRepCode")`) through the joins R-COL-25 makes. It may not be a column over a
  joined source (use `field`), over another entity join (a chain, Resolution 6), through a to-many path, a treated
  path (R-COL-35), an expression, a correlated column, or an id-form read (R-COL-29). Each is `MQ3046`.
- **Unchanged from RFC 0021:** names unique among `@JoinSource` and `@JoinEntity` names; no nested `@Join` on a joined
  entity; no `@PrimaryKey` on a joined column; not on a `@SourceModel`; columns via `@Column(entityJoin)` and
  `@FilterColumn(entityJoin)`; identity paths `entity(<join>).<attr...>` (D-196).

**B.2 Generated code and builder form.** No new runtime signature. `EntityJoin.on(ColumnField<M, ?, ?> anchor, String
idAttribute)` already takes a column (RFC 0021 Resolutions 1 and 2 kept room for this). Only the anchor rule widens:

```java
public static final ColumnField<InvoiceRow, InvoiceEntity, String> REP_CODE =
        ColumnField.of(InvoiceRow.class, ROOT, "repCode", String.class);
public static final EntityJoin<InvoiceRow, EmployeeEntity> REP =
        EntityJoin.of("rep", EmployeeEntity.class, JoinKind.LEFT, List.of(EntityJoin.on(REP_CODE, "code")));
public static final ColumnField<InvoiceRow, EmployeeEntity, String> REP_NAME =
        ColumnField.of(InvoiceRow.class, REP.root(), "name", String.class);

public static ModelQuery.Builder<InvoiceEntity, Long, InvoiceRow> query() {
    return ModelQuery.builder(ROOT, MAPPER).joinEntity(REP);           // no SourceQuery parameter
}

ModelQuery<InvoiceEntity, Long, InvoiceRow> q = QInvoiceRow.query()
        .joinFilter(QInvoiceRow.REP, f -> f.when(!includeInactive, g -> g.eq(QInvoiceRow.REP_ACTIVE, true)))
        .where(f -> f.ge(QInvoiceRow.ISSUED_ON, from).eq(QInvoiceRow.REP_TEAM_ID, teamId))  // Optional<Long>
        .build();
```

`MQ1911` splits as RFC 0021 Resolution 8 does. At `build()`: pairs mixing anchor kinds, an anchor over a joined
source without `field` semantics (pairs over two tables), an anchor over another entity join, a converted anchor.
At first resolution: the id rules, and an anchor path through a to-many association.

**B.3 Rendering.** The engine creates each column-anchored entity join in one step per statement. The step runs right
after the joined sources and their entity joins (`ModelQuery.java:650-704`), in registration order. Each anchor's own
to-one joins resolve first, as a joined source's outer columns do (`:681-683`), so the `ON` reads only tables that
come before it. It goes through the same `joinEntity(...)` helper, with the root as anchor and one `on` call, so
`MQ4014`'s checks apply unchanged. Example with `include_inactive = false`:

```sql
from invoice i
left join employee e on e.code = i.rep_code and e.active = ?
left join team t     on t.id = e.team_id
left join region r   on r.code = i.region_code
```

**B.4 Join kind, filters, counts.** As RFC 0021 A.4 says for a source-anchored join. Under `LEFT` every column of the
join is nullable. An orphan `rep_code` reads NULL and keeps the row, which a read-only association can't give
(§Motivation 2). `INNER` drops outer rows with no match. That includes rows whose anchor is NULL because the anchor's
own to-one path was `LEFT` and unmatched: the flat SQL's meaning. `joinFilter` (R-COL-39) applies to a `LEFT`
column-anchored join, with the D-194 timing. **D-195 extended:** an `INNER` entity join is `MQ1912` at statement build
when its anchor reads through a `joinFilter` join. If the filter blanked the anchor's row, the anchor would be NULL
and the `INNER` join would drop the row, which is the silent drop D-195 refuses. Keyset over a nullable joined column
needs `nullsFirst` or `nullsLast` (R-PAG-05); cursor identity paths are `entity(<join>).…` (D-196).

**B.5 Statements, vendors, locks, children, writes.**

- **Every statement carries the join**, even a `LEFT` one nothing reads, as R-SRC-24 chose: content, count, the
  root-id count of a treated path, both primary-key-first steps, keyset page, every `byKeys` chunk, `one(q, key)`,
  every export page and the stream, and a fetch plan's parent reads. The phases keep the same joins (R-QRY-09).
- **No CTE gate.** `DefaultModelQueryExecutor.java:2900` raises `MQ4015` for every entity join on a vendor without
  CTEs. It narrows to entity joins anchored on a joined source. A column-anchored join needs no database fact, so
  `OTHER` renders it.
- **The count over groups.** By default, a grouped model with a column-anchored entity join counts its groups
  client-side, as D-192 does for a joined source. Spike B.3 shows whether `ProviderSupport#countQuery`'s copy keeps the
  join on both lines and every vendor. If it does, the copy path is kept for this case, and D-n records which one ships.
- **Locks.** A locked query with a join is `MQ2211` (R-QRY-17, R-EXE-14). Every statement has the entity join, so
  `withLock(...)` on such a query is `MQ2211`, raised where R-EXE-14 raises it today. No new code.
- **Child models.** A `@Child` whose child model has an entity join is refused with the codes a child model that
  joins a source gets today (`MQ3039`, R-PROC-44, and its runtime form). A fetch plan's `through` load uses a
  `rootedAt` context whose root is a join (`RowCounts.java:71-72`), and the renderer attaches to a `Root` only.
- **Writes.** Columns over an entity join are read-only, as columns over a `@Join` path are. `ModelUpdate`,
  `ModelDelete` and `InsertColumns` refuse a model with an entity join with the codes a model with a `@Join` column
  gets. The slice lists each write entry point and its code.

**B.6 A static outer term (polymorphic references).** A polymorphic column (`attachment(owner_type, owner_id)`) is
the other case with no association mapping. An id-equality join on `owner_id` alone also matches invoice 5 for an
attachment owned by ticket 5, and returns another table's row without any error. B.6 adds one static term:

```java
@JoinEntity(name = "invoice", entity = InvoiceEntity.class, on = @JoinEntity.On(column = "ownerId"),
        when = @JoinEntity.When(column = "ownerType", value = "INVOICE"))
// builder:
EntityJoin.of("invoice", InvoiceEntity.class, JoinKind.LEFT, List.of(EntityJoin.on(OWNER_ID, "id")))
        .when(OWNER_TYPE, "INVOICE")                     // a definition constant, ANDed into the one ON
```

`when` takes an anchor-kind column (B.1's rules) and a value of its boxed type (R-COL-18's bindable types; `MQ3046`
or `MQ1911` otherwise). It is a definition constant, so INV-9 holds, and it binds as a statement parameter. Its column
is on the root or a to-one path from it, which come before the join (B.3). Resolution 7 recommends including it. A
polymorphic join is invalid without it, and the alternative is a docs warning about a wrong-row case the library
can't detect.

### Part C — request-bound values in expressions (R-COL-40, resolves Q-24)

**C.1 The placeholder.** The shape of RFC 0019 P-A.1, whose names D-184 released unused, without the compiled cache:

```java
@Incubating
public final class QueryParam<C> {
    public static <C> QueryParam<C> of(String name, Class<C> type);   // a primitive class maps to its wrapper
    public String name();
    public Class<C> type();
}
// Expr, @Incubating:
public static <M, C> ExpressionField<M, C> param(QueryParam<C> param);
```

- **Equal by identity.** Two constants with one name are two parameters. A query that reads two different
  `QueryParam`s with one name is `MQ1512` at `build()`, so inspection and messages stay unambiguous.
- **Typed by declaration**, not by a value. R-COL-18's list decides bindability on `type()` when `param` is called:
  arrays, `Collection`, `Map`, `Optional`, `Date`, `Calendar`, enums and entities are `MQ1502`. It renders the way an
  `Expr.value` does: a bind parameter typed by its operand (R-COL-18), one Criteria `ParameterExpression` per
  `QueryParam` per statement (R-COL-19). A param in a select item, a group key and an order key is then one node, and
  PostgreSQL matches the group key.
- **Where it goes.** Anywhere an `ExpressionField` goes: a `@Computed` or `@Aggregate(expression)` definition, an
  operand of `coalesce`, `nullIf`, arithmetic, `concat`, a CASE result or a `function` argument, and a filter
  through the existing `compare(left, Op, right)` (`FilterGroup.java:453`). That includes a CASE condition and a
  `Correlated` correlation. No new filter overload: `compare` already takes two scalars. It is never SQL text, so
  it can't stand where `Expr.constant` is required (a function's mode argument, R-COL-18).

**C.2 The two Q-24 columns.**

```java
public final class OpenLines implements ExpressionDefinition<OrderRow, Long> {
    public static final QueryParam<String> STATUS = QueryParam.of("status", String.class);
    @Override
    public ExpressionField<OrderRow, Long> expression() {
        return Correlated.count(OrderRow.class, SubSelect.of(LINE_ID),
                (f, outer) -> f.compare(LINE_ORDER_ID, Op.EQ, outer.column(QOrderRow.ID))
                               .compare(LINE_STATUS, Op.EQ, Expr.param(STATUS)));
    }
}

public final class Overdue implements ExpressionDefinition<InvoiceRow, Boolean> {
    public static final QueryParam<LocalDate> AS_OF = QueryParam.of("asOf", LocalDate.class);
    @Override
    public ExpressionField<InvoiceRow, Boolean> expression() {
        return Expr.<InvoiceRow, Boolean>cases()
                .when(f -> f.compare(QInvoiceRow.DUE_ON, Op.LT, Expr.param(AS_OF)).isNull(QInvoiceRow.PAID_ON),
                        Expr.value(true))
                .otherwise(Expr.value(false));
    }
}

@QueryModel(root = OrderEntity.class)
public record OrderRow(@PrimaryKey Long id, @Computed(OpenLines.class) Long openLines) {}   // unchanged annotation
```

**C.3 Binding per query.**

```java
ModelQuery.Builder<E, K, M> bind(QueryParam<C> param, C value);     // also SourceQuery.Builder#bind

ModelQuery<OrderEntity, Long, OrderRow> q = QOrderRow.query()
        .bind(OpenLines.STATUS, request.status())
        .where(f -> f.eq(QOrderRow.CUSTOMER_ID, customerId))
        .build();
```

- **Like `where` and `joinFilter`.** The value is held on the built `ModelQuery`, an immutable value built per request.
  It is never held on the `QueryParam`, the `ExpressionField` or the model's constants (INV-9). Every builder copy
  carries the bindings: `withLock`, `withFetch`, `orderedBy`. A second `bind` of one param replaces the first, as a
  second `where` does (RFC 0021 Resolution 4).
- **Checked at `build()`.** The query's expressions are known when it is built: the model's select set, its filter
  columns, its order and group keys, and every `@Computed` and `@Aggregate(expression)`. So a param that some
  expression reads and the query doesn't bind is `MQ1512` at `build()`, naming the param and the column, before any
  statement. A `null` value is `MQ1502` at `bind`, as `Expr.value(null)` is. A value that is not
  `type().isInstance(value)` is also `MQ1502`. A bound param that no expression reads is allowed: the param is a typed
  constant, so it can't be misspelled, and an order-only expression reads it on some calls and not others.
- **Never skipped.** There is no `Optional` bind. An expression has no "absent" form, and a CASE skipped per call
  would change the select set. A caller with an optional value binds a default.
- **Scopes.** A source body binds on its `SourceQuery`, and the outer query binds on its `ModelQuery`. One binding
  never reaches into the other's statement part, since source bodies are built in their own `JoinContext`
  (`ModelQuery.java:672-676`). A `Correlated` sub-select is part of the outer expression, so it reads the outer
  query's bindings.
- **Engine-built queries.** Wherever the engine builds a `ModelQuery` itself rather than running one the caller built,
  it either copies the caller's bindings or refuses with `MQ1512` at that build. The M34.4 slice lists every such
  entry point (the fetch-plan child loads, the treated root-id count, `orderedBy` copies) and the outcome for each.
- **Writes.** A write that reads a caller's `ModelQuery` (`ModelInsert.select`) uses that query's bindings. Any other
  write expression that reads a `QueryParam` is `MQ1512` at the write's build. Binding in writes is a later, additive
  change.

**C.4 Phases, caching, cursors, inspection.**

- **Every statement binds what it renders, and only that.** The per-statement `ParameterSink` (`ParameterSink.java:15-21`)
  binds each `QueryParam` a statement's tree renders, once: content, count, both primary-key-first steps, keyset page,
  each export page and the stream, each `byKeys` chunk, `one(q, key)`, the inline-count window (R-EXE-03), a fetch
  plan's parent reads, and a joined source's CTE body (its own bindings). A param read only by a select column is
  absent from the count statement and isn't bound there, so no provider reports an unknown parameter.
- **Caching and compiled queries.** D-184 adopted no compiled queries or build cache. Criteria are built per
  statement, so a param adds no cache key. The SQL text is the same across calls with different values, which is
  better for the driver's statement cache than a per-call constant. Under Hibernate's
  `value_handling_mode=inline` (R-VND-28, D-177) a param inlines as `Expr.value` does.
- **Cursors.** R-PAG-19's fingerprint covers the root and the order keys. A keyset order key can't be an expression
  (R-QRY-16), so a param never reaches a cursor and no fingerprint changes. A cursor reused under different bound
  values continues with the new values, as with different `where` values today; the docs say so.
- **Determinism.** A param-bearing expression is deterministic for one binding, which R-PAG-25 requires of an offset
  order. The export pages of one call share its bindings.
- **Inspection** lists each bound param by name and type, never its value. `toString` names the params (RFC 0019
  P-A.2's rule; values may be personal data).

### Part D — a row count for a joined entity (R-AGG-19 amended)

**D.1 The rule.** At registration, the engine records each entity join's kind and its root with the join's statement
record (`JoinContext.java:548-550` stores `inner = false` for every entity join today). R-AGG-19's walk then treats a
registered entity join as one hop, by that record and never by `Join#getParent()`:

- **`INNER`**: a hop from the root it is attached to (R-SRC-24, B.3). The walk continues there, so the counted
  identity is eligible exactly when the root is. A to-one `INNER` join below it (`TableField.join(REP.root(), "team",
  INNER)`) is eligible through it. Its `getParent()` is the entity join on both lines, and the walk recognises that
  node through the context (`RowCounts.java:89-99`).
- **`LEFT`** stops the walk, as today: `count(<first id attribute>)`, which counts 0 for an unmatched or
  `joinFilter`-blanked row (R-COL-39). `count(*)` would count those rows, so `LEFT` must keep the identity count.
- **`joinFilter`** applies only to `LEFT` joins, and D-195 refuses an `INNER` join below a filtered one, so no
  eligible chain contains a filtered join.
- **Unchanged:** a customizer, a treated node, `countDistinct`, a filtered count's `1`-under-condition form
  (R-AGG-17), and a composite id, which counts as one identity (`count(*)` replaces `count(p.<first id>)`).

```java
Agg.count(QNodeDetail.MANAGER.root())   // INNER manager:   count(*)      — was count(p.id)
Agg.count(QNodeDetail.MANAGER.root())   // LEFT manager:    count(p.id)   — unchanged
```

The record is the same on 6.6, where Hibernate's entity join is no `jakarta Join`, and on 7.4, where it is. So the
rendering is the same on both lines, which Resolution 7 of RFC 0021 required.

**D.2 `@Aggregate#entityJoin`.** The annotation form, additive:

```java
@QueryModel(root = InvoiceEntity.class)
@JoinEntity(name = "rep", entity = EmployeeEntity.class, type = JoinKind.INNER, on = @JoinEntity.On(column = "repCode"))
public record RepTotals(
        @GroupBy @Column(entityJoin = "rep", attribute = "team.id") Long teamId,
        @Aggregate(fn = AggregateFunction.COUNT, entityJoin = "rep", attribute = "code") Long reps,  // count(*)
        @Aggregate(fn = AggregateFunction.MAX, entityJoin = "rep", attribute = "hiredOn") LocalDate lastHire) {}
```

`entityJoin` defaults to `""`, the root, so every existing model generates the same code. The path is checked as
`@Column(entityJoin)` checks it, with `MQ3046` for an unknown join and the existing aggregate codes otherwise.
Generated: `Agg.count(REP_CODE_COLUMN).named("reps")` over a column of `REP.root()`, which D.1 makes eligible.

## Vendor and provider SPI

- **No `VendorProfile` change** (INV-6). Part B removes a database fact from a case (the CTE gate). It adds none.
- **No `ProviderSupport` change.** Part A adds a `ServiceLoader` service of the existing `core.EntityJoinRenderer` and
  the probe in `jpa`. Resolution 3 weighs a new `jpa.spi` type instead.
- **`model-query-hibernate`** is unchanged by A, B and C. D changes only `core`.

## Spike plan

A throwaway branch `spike/rfc-0022` with one test-source module `spike-rfc0022/`, on Hibernate 6.6.58.Final and
7.4.11.Final, against H2, PostgreSQL 16 (`postgres:16-alpine`) and MySQL 8.0.31. Part A also uses EclipseLink 5.0
(Jakarta Persistence 3.2; pin the newest 5.0.x) and EclipseLink 4.0 (3.1). Results go to `rfc/0022-spike-results.md`
in `rfc/0021-spike-results.md`'s shape (a verdict per part, **Matrix**, **Findings**, **Line parity**, **Consequences
for the RFC**), raw data to `rfc/0022-spike-data/` with a README of reproduction commands. Production `src/main` and
committed SQL snapshots stay unchanged. Dataset: RFC 0021's closure-table shape plus B's invoice schema (about 50,000
invoices, 2% orphan `rep_code`s, 300 employees, composite-id and `@EmbeddedId` variants of `employee`, and an
`attachment(owner_type, owner_id)` table over invoices and tickets with colliding ids).

1. **A.1 Signatures.** `javap` on `jakarta.persistence-api` 3.2.0: `From#join(Class, JoinType)`, `Join#on(Predicate...)`
   and their return types. Whether Hibernate 7.4.11's and EclipseLink 5.0's `From` implement them, and whether the
   result is a `jakarta Join` whose `getOn()` is non-null after one `on`.
2. **A.2 The probe.** Record which `LinkageError` each of these raises and when (class load, verification, first
   call), and confirm that no connection opens:
   (i) API 3.1.0 with Hibernate 6.6 and its renderer withheld;
   (ii) API 3.2.0 forced over Hibernate 6.6;
   (iii) EclipseLink 4.0;
   (iv) EclipseLink 5.0 (passes).
   Also: which probe predicate (`conjunction()`, an id self-equality) each provider accepts, and the probe's cost (µs).
3. **A.3 Parity on 7.4.** The portable renderer against `HibernateEntityJoinRenderer` on Hibernate 7.4 (Hibernate's
   withheld by a delegating test `ProviderSupport`), over every statement kind in R-SRC-22's list, `LEFT` and `INNER`,
   composite and embedded ids, with a `joinFilter`. Expect identical SQL and rows on all three vendors.
4. **A.4 EclipseLink 5.0.** Part B's model (no source): `LEFT`, `INNER`, a to-one path below, a filter, a count, a
   keyset page with `nullsLast`, an export, against the native SQL, on all three vendors. This records whether the rest
   of the engine (vendor detection by `DatabaseMetaData`, paging, `Tuple` aliases) runs on EclipseLink at all. That
   result decides Resolution 4.
5. **B.1 Rendering.** Column-anchored joins on both Hibernate lines and all three vendors. An anchor on a root basic
   column and on a `LEFT` and an `INNER` to-one path. Creation order against the anchor's path joins. An `INNER` entity
   join over a `LEFT`-unmatched anchor path (rows drop). Orphan anchors read NULL with no `EntityNotFoundException`, set
   beside a read-only `@ManyToOne` over the same column loaded through `em.find`. Every statement kind of B.5.
6. **B.2 Cost.** The invoice page of 20, its count and a full export, with the rep join unreferenced in the count:
   `EXPLAIN (ANALYZE, BUFFERS)` on PostgreSQL (is the LEFT join on `employee`'s primary key removed?), `EXPLAIN
   ANALYZE` on MySQL, `EXPLAIN` on H2; medians of 7 alternating runs, against the native SQL.
7. **B.3 The group count.** A grouped model with a column-anchored entity join. Does `ProviderSupport#countQuery`'s
   copy keep the join on 6.6 and 7.4, per vendor? This decides B.5's count rule.
8. **B.4 The static term.** The attachment table: rows with and without `when`, showing the colliding-id wrong rows
   that `when` removes; SQL and parameter binding on each line.
9. **C.1 Rendering.** `Expr.param` as a bare select item, in arithmetic with a column, as a CASE condition operand and
   a CASE result, in `coalesce`, as a `function` argument, inside a `Correlated` count's correlation, in a joined
   source's body bound on its `SourceQuery`, and as a group key and an order key on PostgreSQL (one node, R-COL-19).
   On both lines and all three vendors, and under `value_handling_mode=inline`. Note any "could not determine data
   type of parameter" on PostgreSQL, and the cast each line renders.
10. **C.2 Phases.** Every statement kind of C.4. Record which statement binds which param (no unknown-parameter
    error, no unbound one), SQL text stable across calls with different values, and the values bound once per
    statement.
11. **D.1 Counts.** `INNER` and `LEFT` entity joins, source-anchored and column-anchored, with single, `@IdClass` and
    `@EmbeddedId` ids, a to-one `INNER` join below, a `LEFT` join with a `joinFilter`, grouped and ungrouped, and a
    filtered count (R-AGG-17). `count(*)` and `count(p.id)` return equal numbers wherever D.1 makes the count eligible,
    and differ (0 against n) for an unmatched or blanked `LEFT` row, on both lines. PostgreSQL timing of both forms on
    the grouped read, for information.
12. **Line parity.** The spike module compiles and behaves the same on 6.6 and 7.4.

## New ids (placeholders)

Checked against the spec and RFCs 0019 to 0021. The highest in use are R-SRC-24, R-COL-39, R-AGG-19, R-VND-32,
R-PROC-46, AC-SRC-58, AC-COL-58, AC-AGG-30, AC-VND-22, AC-PROC-48, D-196, `MQ1511`, `MQ1912`, `MQ3046` and `MQ4016`.
RFC 0019's released ids (R-COL-38, R-VND-31, `MQ1210`, `MQ2215`) are not reused, following RFC 0021's practice.

| Id | Part | What |
|---|---|---|
| R-VND-33 | A | `model-query-jpa32`'s `EntityJoinRenderer`; resolution order (provider's first, then `ServiceLoader`, probed once per factory); `MQ4017` at executor creation on a 3.1 API or provider; no reflection |
| R-SRC-25 | B | An entity join anchored on columns of the root or its to-one paths; no joined source or CTE needed; the creation step; the static `when` term; locks, children and writes |
| R-PROC-46 (amended) | B | `@JoinEntity.On#column`, `@JoinEntity.When`, defaults on `source` and `On#field` |
| R-COL-40 | C | `QueryParam`, `Expr.param`, `Builder#bind` on `ModelQuery` and `SourceQuery`; checked at `build()`; bound per statement |
| R-AGG-19 (amended) | D | An `INNER` joined entity is a hop to its root, read from the join record; `LEFT` stops the walk |
| R-PROC-47 | D | `@Aggregate#entityJoin` |

| AC | Part | Asserts |
|---|---|---|
| AC-VND-23 | A | With the provider's renderer withheld, the portable renderer on Hibernate 7.4 renders the same SQL and rows as Hibernate's on every R-SRC-22 statement kind and Tier-1 vendor; one `on` call; on EclipseLink 5.0, B's model returns the native SQL's rows (if Resolution 4 keeps the lane) |
| AC-VND-24 | A | The provider's renderer wins when present (Hibernate 6.6 and 7.4 unchanged, module present or not); the probe raises `MQ4017` at executor creation on API 3.1 and on a 3.2 API over a 3.1 provider; `PortabilityCheck` reports `MQ4014` and `MQ4017` per target; the ArchUnit rule bans `java.lang.reflect`, `java.lang.invoke` and `org.hibernate` in the module |
| AC-SRC-59 | B | A `LEFT` column-anchored join matches the native SQL on Tier 1 and both lines; orphan anchors read NULL; an anchor on a to-one path; two joins of one entity stay apart; no CTE gate on `OTHER` |
| AC-SRC-60 | B | `INNER` drops unmatched rows, including rows with a `LEFT`-unmatched anchor path, in content and count; the join is in every B.5 statement; keyset over a nullable joined column with `nullsLast` loses no row |
| AC-SRC-61 | B | `MQ1911` cases (B.2) at build and at first resolution; `MQ1912` for an `INNER` entity join whose anchor reads through a `joinFilter` join; `MQ2211` for a lock; the child-model and write refusals |
| AC-SRC-62 | B | `when` removes the colliding-id rows; it binds as a parameter; its type and anchor rules refuse with `MQ1911` |
| AC-PROC-49 | B | `MQ3046`: both or neither of `field` and `column`, mixed pairs, `column` with `source`, an anchor over a source, another entity join, a to-many or treated path, an expression or id-form column, a bad `when`; a generated model runs AC-SRC-59's query |
| AC-COL-59 | C | `Expr.param` in each C.1 position returns the expected values on Tier 1 and both lines; a param as group and order key on PostgreSQL renders one node |
| AC-COL-60 | C | A param binds in every C.4 statement that renders it and in no other; values differ per call over one shared definition; the static constants are unchanged after a bound build (INV-9) |
| AC-COL-61 | C | `MQ1512`: an unbound param at `build()`, two params with one name, a param in a non-query write; `MQ1502`: a `null` value, a non-instance value, an unbindable `type()`; a second `bind` replaces |
| AC-COL-62 | C | A source body's param binds on its `SourceQuery` only; a `Correlated` correlation reads the outer binding; each engine-built query listed in C.3 copies or refuses; inspection and `toString` name params and never values |
| AC-AGG-31 | D | `Agg.count` over an `INNER` joined entity and over an `INNER` to-one below it render the row count on both lines, with equal results; `LEFT`, filtered or not, renders `count(<first id>)`, 0 for unmatched; SQL snapshots for those cases change |
| AC-AGG-32 | D | Composite and embedded ids under `INNER` render the row count; a customizer or a filtered count keeps today's forms |
| AC-PROC-50 | D | `@Aggregate(entityJoin)` generates over the joined entity; an unknown join is `MQ3046` |

| Code | Part | Meaning |
|---|---|---|
| `MQ4017` (new) | A | A discovered `EntityJoinRenderer` fails its probe on this factory: Jakarta Persistence API or provider below 3.2, or the provider refuses the probe join |
| `MQ1512` (new) | C | A query reads a `QueryParam` it doesn't bind, or reads two `QueryParam`s with one name; a non-query write reads a `QueryParam`; an engine-built query can't carry the caller's bindings |
| `MQ1911` (widened) | B | B.2's anchor and `when` cases |
| `MQ1912` (widened) | B | An `INNER` entity join whose anchor reads through a `joinFilter` join (D-195 extended) |
| `MQ3046` (widened) | B, D | B.1's and B.6's annotation cases; `@Aggregate#entityJoin` naming an unknown join |
| `MQ4014`, `MQ2211`, `MQ1502`, `MQ4015` | A, B, C | Meanings unchanged; new cases: none for `MQ4014`/`MQ2211`/`MQ1502` beyond B.5 and C.3; `MQ4015` narrowed to source-anchored entity joins |

## Compatibility

All of it is additive or unreleased. 0.11.0 isn't tagged, so M33's surface can still change without a shim.

- **A.** A new artifact in the BOM. `core`'s 3.1.0 baseline, `model-query-hibernate` and the starter are unchanged.
  Hibernate 6.6 and 7.4 render byte for byte as at `m33-verified` (AC-VND-24). One new failure, only for new setups:
  the module on a 3.1 factory whose provider has no renderer refuses at executor creation (`MQ4017`).
- **B.** `@JoinEntity#source` and `@JoinEntity.On#field` gain `""` defaults. Adding a default to an annotation member
  is source and binary compatible, and every RFC 0021 model compiles and generates the same code. `EntityJoin.on`'s
  signature is unchanged. Its anchor rule widens, so a hand-written column anchor that was `MQ1911` at build is now
  valid. The `MQ4015` gate narrows. No committed SQL snapshot changes.
- **C.** New `QueryParam`, `Expr.param`, `bind` on both builders. `ExpressionField`'s package-private sealed `Node`
  gains a permitted record. No shipped signature or snapshot changes.
- **D.** A rendering change with equal results. Snapshots of `INNER` entity-join counts change, from `count(p.id)` to
  `count(*)`; M33's TCK has a few such cases and the slice lists them. `@Aggregate` gains a defaulted member.

Everything new is `@Incubating`; the M35 freeze review decides each piece.

## Alternatives

- **A. Reflection in `core`** (RFC 0021 Resolution 3's option b): look up `From#join(Class, JoinType)` reflectively
  when present. Rejected: the user asked for none, `core` would carry a provider-version branch, and a 3.2 API over a
  3.1 provider fails only at the first query.
- **A. A `ProviderSupport` in the module** whose `supports(emf)` is true for any non-Hibernate factory. Rejected: one
  `ProviderSupport` serves a factory (`ProviderSupport.java:29`), so it would compete with a real provider's support
  in `ServiceLoader` order and take over its other hooks.
- **A. Prefer the portable renderer on 7.4**, to drop Hibernate's reflection there. Rejected for now: the Hibernate
  module is one artifact for both lines and must keep the reflective path for 6.6, so preferring the portable one would
  add a per-line choice with no saving. A.6 drops both after the 6.6 line goes.
- **A. A documented requirement only** (no probe, `NoSuchMethodError` at the first entity-join query). Rejected: the
  error surfaces in production, deep in a statement build, without a code.
- **B. Do nothing:** map a read-only association. That fails for entities you can't change and for orphan values
  (§Motivation), and it keeps every entity join Hibernate-only.
- **B. A unique natural key as the target** (`employee.badge` with a unique index). Rejected as in RFC 0021: the
  metamodel can't show uniqueness, and a non-unique key multiplies rows past every R-PAG-* guarantee.
- **B. Chaining off another entity join** (`p2.id = p1.manager_code`). Left out (Resolution 6): it is additive later
  under the same `column` member.
- **C. `ModelQuery#with(param, value)` on a built query** (RFC 0019 P-A.2). It would let a `static final ModelQuery`
  be bound per call, but bindings would then be an outer copy layer over `withLock`, `orderedBy` and `withFetch`,
  which RFC 0019 needed only for its cache. Rejected for `Builder#bind`, which matches `where` and `joinFilter`;
  `with` is additive later.
- **C. Processor-declared params** (`@QueryModel(params = …)` generating `query(LocalDate asOf)`). Rejected: the
  processor can't see which params a definition class reads (R-PROC-01; the definition is runtime code), and a
  generated param constant read from a definition is D-115's class-initialisation cycle.
- **C. Filter overloads taking `QueryParam`** (`eq(column, param)`, as RFC 0019 had). Not needed: `compare(left, Op,
  Expr.param(p))` already covers every comparison, and `where` already takes request values.
- **D. Do nothing.** Results are already correct, and only PostgreSQL's per-value null check is saved. Rejected for
  the line-independent record, which costs one field and closes the one site R-AGG-19 still skips. D.2 is separable.

## Resolutions to record

No question stays open. Each resolution below is recorded in D-197 (or one D-n per part, D-197 to D-200, if the
parts are accepted separately), with the spike's evidence (`rfc/0022-spike-results.md`). Q-24 is resolved by Part C's
D-n.

Review of the draft (2026-10-24): every resolution below takes its recommended option, Resolution 10 included
(`Builder#bind` only). A spike result that contradicts a recommendation reopens only that resolution.

1. **Part order and coupling.** Options: (a) four independent parts; (b) A only with B, B before A; (c) defer A.
   Recommended: (b). A alone serves no user (A.1). C and D are independent of both.
2. **Module name and BOM.** Options: `model-query-jpa32`, `model-query-jakarta-persistence-32`, a classifier of `core`.
   Recommended: `model-query-jpa32` in the BOM, package `com.rey.modelquery.jpa32`. A classifier can't carry its own
   `provided` API version, and the short name matches `model-query-jpa`.
3. **How it's found.** Options: (a) a `ServiceLoader` service of the existing `@EngineFacing core.EntityJoinRenderer`,
   probed generically in `jpa`; (b) a new public `jpa.spi.EntityJoinSupport { Optional<EntityJoinRenderer>
   renderer(EntityManagerFactory) }`; (c) a `ProviderSupport` in the module. Recommended: (a). No new public SPI type,
   no `jpa` dependency, and the BOM keeps the module's version in step with `core`'s `@EngineFacing` contract.
   (b) is the fallback if a third-party renderer needs `supports(emf)`.
4. **Which providers it is verified on.** Options: (a) Hibernate 7.4 with Hibernate's renderer withheld, in the
   module's tests; (b) plus an EclipseLink 5.0 lane on H2 in the module's tests; (c) EclipseLink in the full TCK.
   Recommended: (b) if spike A.4 shows the engine runs on EclipseLink, else (a). The providers page then says the module
   is verified for entity-join statements on those providers, and model-query's supported providers stay Hibernate 6.6
   and 7.4.
5. **A 3.1 classpath.** Options: `MQ4017` at executor creation from a `LinkageError` probe; `MQ4014` (with the cause)
   at the first entity-join query; a documented requirement only. Recommended: `MQ4017` at creation, probed only when
   the provider has no renderer, so a Hibernate application with the module present is never refused.
6. **The anchor's scope (B).** Options: (a) root and to-one paths from it; (b) also another entity join's columns
   (chains); (c) root basic columns only. Recommended: (a). (b) is additive under `On#column` once creation order across
   entity joins is specified; (c) would leave out legacy codes reached through a to-one path.
7. **The static `when` term (B.6).** Options: include; defer with a docs warning. Recommended: include. A polymorphic
   id join without it returns another table's row with no error (INV-5), and it is one definition constant ANDed into
   the one `ON`.
8. **The group count with a column-anchored join (B.5).** Recommended: client-side (D-192's path) unless spike B.3
   shows `countQuery`'s copy keeps the join on both lines and all three vendors; the D-n records which.
9. **Locks, children, writes (B.5).** Recommended: `MQ2211` for locks (existing rule), the source-join child-model codes
   for `@Child`, and read-only joined columns in writes. No new code.
10. **`QueryParam` binding point (C).** Options: `Builder#bind` (per built query); `ModelQuery#with` (per call on a
    static query); both. Recommended: `Builder#bind` only; `with` is additive.
11. **Unbound and unread params (C).** Recommended: an unbound read param is `MQ1512` at `build()`; a bound param no
    expression reads is allowed. Identity equality, and `MQ1512` for two params with one name.
12. **Param scope (C).** Recommended: `ModelQuery` and `SourceQuery` bind their own; a `Correlated` reads the outer
    query's; non-query writes refuse (`MQ1512`).
13. **The row-count rule (D).** Recommended: an `INNER` joined entity is a hop to its root by the join record, on both
    lines; `LEFT` stops, filtered or not.
14. **`@Aggregate#entityJoin` (D.2).** Options: include; leave aggregates over a joined entity to the builder.
    Recommended: include, defaulted to `""`.

## Milestone sketch (M34, 0.11.0; the freeze moves to M35)

Model line: `architect-review` at the gate (the `EntityJoinRenderer` discovery surface and module boundary, INV-7 and
INV-9, paging correctness of a column-anchored `INNER` join in every phase, the `QueryParam` public API).

| Slice | Content | Tier | Done when |
|---|---|---|---|
| M34.0 | RFC review and acceptance spike (`rfc/0022-spike-results.md`) | `opus` / `high` (review) | Resolutions recorded as D-197 (to D-200) |
| M34.1 | Part B engine: anchor rule widened in `EntityJoin`, the creation step, the static `when`, `MQ4015` gate narrowed, `MQ1911`/`MQ1912` cases, locks, children, writes, the group count | `opus` / `high` (paging correctness, INV-5, public API) | AC-SRC-59 to AC-SRC-62 green on both lines and Tier 1; SQL snapshots unchanged |
| M34.2 | Part B processor: `On#column`, `When`, defaults, `MQ3046` cases (R-PROC-46) | `sonnet` / `medium` | AC-PROC-49 green |
| M34.3 | Part A: `model-query-jpa32`, BOM, services file, resolution order and probe in `jpa`, `MQ4017`, `PortabilityCheck`, ArchUnit rule, the 7.4-withheld and (Resolution 4) EclipseLink lanes | `opus` / `high` (`ProviderSupport`/SPI surface, module boundary) | AC-VND-23, AC-VND-24 green; 6.6 and 7.4 snapshots unchanged |
| M34.4 | Part C: `QueryParam`, `Expr.param`, `bind` on both builders, `MQ1512`, every phase, the engine-built query list | `opus` / `high` (public API, INV-9) | AC-COL-59 to AC-COL-62 green on both lines and Tier 1 |
| M34.5 | Part D: the join record and R-AGG-19's hop, `@Aggregate#entityJoin` (R-PROC-47) | `opus` / `high` (a wrong eligibility is a silent over-count) | AC-AGG-31, AC-AGG-32, AC-PROC-50 green; changed snapshots listed |
| M34.6 | Docs: joins page (outer-column anchors, `when`), expressions page (`QueryParam`), providers page (`model-query-jpa32`, 3.2 requirement, `MQ4017`), both tabs (CC-DOC-01); `CHANGELOG.md` 0.11.0 extended | `sonnet` / `medium` | `mkdocs build --strict` green |

M34.1 lands before M34.3 (Resolution 1). M34.4 and M34.5 depend on neither and may run in either order after M34.1
(M34.5's tests use column-anchored `INNER` joins).
