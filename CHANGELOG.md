# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html); the public API may change in any `0.x`
release (`docs/spec/delivery/61-repo-release-governance.md` R-REL-07).

## [Unreleased]

Nothing is frozen yet: every public type stays `@Incubating`, and the additions below are all `@Incubating`.
0.10.0 covers milestones M25–M30 (D-176).

### Added (incubating)
- Custom filter operators (RFC 0017, D-167, R-FLT-20..22): immutable `FilterOperator` definitions with
  `OperatorRenderer` and `OperatorContext`, attribute checks, feature requirements and value preparation;
  `Filters.op` and `Having.op` accept values, optional values or fields. Values bind for every statement;
  both an operator and its negation drop NULL-column rows. Renderer or preparation failures are `MQ1313`.
  Reuse constants for identity equality; inspection records `Condition.Kind.OPERATOR` and `FilterMatchers.op`
  matches the definition (AC-FLT-21, AC-FLT-23, AC-FLT-25..27).
  See [Custom operators](docs/site/docs/queries.md#custom-operators).
- Mapping-aware operator operands (D-168..D-170, R-FLT-21, R-VND-24): `OperandBinder` and
  `ProviderSupport#operands` let Hibernate 6.6.x and 7.4.x bind enums, converted attributes and arrays by the column's
  mapping. Without a mapping-aware binder, only strings, `java.lang` or `java.math` numbers and `java.time` values
  bind; other operator values, including `Boolean` and `UUID`, are `MQ1502`. A binder returning null, throwing or
  reusing a bound parameter is also `MQ1502` (AC-FLT-22).
- Operator feature gates (R-VND-23): `OperatorFeature`, `VendorProfile#operators`, `ProviderSupport#operators` and
  `RenderOptions#withOperatorGate` check the vendor first (`MQ4015`), then the provider (`MQ4014`), before any
  statement. `RenderOptions#withOperandBinder` supplies the mapping-aware binder. `PortabilityCheck` reports missing
  features per target when an operator's value is present; an empty `Optional` is skipped (AC-FLT-24, AC-FLT-32).
- Hibernate pattern operators (D-171, R-VND-23): `HibernateOperators.ilike(LikeMode)` serves PostgreSQL and H2,
  and `regexMatch()` serves PostgreSQL, taking its pattern unconverted (D-174). `ilike` prepares patterns like `like`,
  and Hibernate answers it by the dialect's `supportsCaseInsensitiveLike()` (D-175), so CockroachDB's dialect is served
  by the provider (still `MQ4015` from the vendor check) and 7.4's Spanner PostgreSQL dialect is `MQ4014`; MySQL is
  refused rather than emulated (AC-FLT-21, AC-FLT-24). Case-insensitive string filters over JPA `@Convert` attributes
  now convert through the operand binder (D-177, Q-30 resolved; see below).
- Hibernate containment operators (D-172, R-VND-23): `arrayContains()`, `arrayOverlaps()` and `jsonbContains()`
  serve PostgreSQL's array `@>`, array `&&` and jsonb `@>` predicates (the array operators take boxed arrays, D-172); H2 and MySQL are refused before execution
  (AC-FLT-24).
- Hibernate full-text operator (D-173, R-VND-23): `fullTextMatch(configuration)` serves PostgreSQL with
  `plainto_tsquery`, binding request text and rendering the validated configuration as a literal to match expression
  indexes. An invalid configuration is `MQ1315`; blank or stop-word-only text matches nothing (AC-FLT-32).
- Inline count mode (RFC 0018 Part A, D-177, R-EXE-15): `CountMode.INLINE_COUNT` reads a page's exact total and
  `hasNext` from a `count(1) over ()` window on the page statement (or step-1 key query) when that statement returns
  exactly the rows `COUNT` counts; falls back to `COUNT`'s statements otherwise, producing the same total and
  `Slice` (`AC-EXE-21`). Gated by `ProviderSupport#inlineCounts(EntityManagerFactory)` (R-VND-27, default false),
  answered from Hibernate's dialect window function support. See [Paging and export](docs/site/docs/paging-export.md).
- Converted ignore-case binding (RFC 0018 Part B, D-177, R-FLT-07, R-VND-24): `OperandBinder#converts` (default false)
  reports whether an expression's mapping applies a JPA `AttributeConverter`. Where true, `likeIgnoreCase` and
  `Having.likeIgnoreCase` render `lower(col) LIKE lower(?)` and `eqIgnoreCase` and `Having.eqIgnoreCase` render
  `lower(col) = lower(?)`, binding the converted pattern or value through the binder and folding both sides in SQL.
- Explicit binding under inline value handling (RFC 0018 Part C, D-177, R-FLT-08, R-VND-28):
  `ProviderSupport#inlinesValues(EntityManagerFactory)` (default false) and `RenderOptions#withInlinesValues` and
  `#inlinesValues` detect Hibernate's `hibernate.criteria.value_handling_mode=inline`. Under this setting, every filter
  comparison, membership chunk, range, ignore-case parameter, call-time key (`one(q, key)`, `byKeys`,
  primary-key-first pages), keyset cursor, fetch-plan round key, and update assignment binds explicitly as a statement
  parameter via `@EngineFacing` `ValueOperands` and `ParameterSink`. Where no binder types the column, a value of a
  mapping-dependent type (an enum, `Boolean`, `UUID`, array, or a converter's non-JDK attribute type) fails with
  `MQ1502` before any statement runs.
- Source models (RFC 0013, D-143): `@SourceModel(root = OrderItemEntity.class)` declares a model read as a common
  table expression rather than a select, so a read the Criteria API cannot express becomes one generated model with
  the compile-time checks an entity root gets. `QQuantitySource.source()` builds a `SourceQuery`, which carries the
  `where` and `having` of the CTE body, and `QQuantityFeed.query(source)` roots an outer model at it, generated with
  `TableField.source(...)` and `ColumnField.over(...)` columns: the CTE is rendered before the outer select, so an
  outer query filters, sorts, pages, keysets or exports the rows a plain, grouped, windowed, combined or recursive
  source produces. `QQuantitySource` has `source()` in place of `query()`, `ALL`, `KEY` and `GROUP_KEYS` where the
  source groups, and no `MAPPER` and no write method; `ModelQuery#source()` and the three-argument
  `ModelQuery.builder(root, mapper, source)` root a hand-written query at one. A source is one statement, so a `@Child` on
  it is `MQ3030`, a `@Join` on a model rooted at one is `MQ3035`, a `@Child` whose child model is rooted at one is
  `MQ3039`, and a field naming no source field is `MQ3001`; a source key that is neither the root entity's id, for an
  ungrouped source, nor the full `@GroupBy` set, for a grouped one, and a group key nullable in its mapping, are
  `MQ3031`; a `root` that is neither an `@Entity` nor a `@SourceModel`, or both, is `MQ3038`, and an outer
  `@PrimaryKey` that is not exactly the source's key is `MQ3036` (R-PROC-35, R-PROC-39). `generateChanges` on a model
  rooted at a source is the new `MQ3040` rather than ignored: a write needs an entity root, and neither a source nor
  an outer model over one generates a change set (R-GEN-37, D-145). See [Source models](source-models.md).
- Window fields (RFC 0013, R-SRC-04): `@Window(function = ..., argument = ..., partitionBy = ..., orderBy = ...,
  frame = ...)` on a `@SourceModel` field selects a `WindowFunction` over the CTE — `ROW_NUMBER`, `RANK`,
  `DENSE_RANK`, `LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE`, `SUM`, `AVG`, `MIN`, `MAX` or `COUNT` — with
  `WindowFrame` for the framed aggregates, and `WindowField` as the generated constant the field is read through, so
  an outer query filters and sorts on the result like on any column. `@Window` outside a source model is `MQ3033`,
  and a term naming no attribute, an argument where the function takes none or none where it needs one, a result type
  that is not the function's, or an order-sensitive function without `orderBy` is `MQ3032` (R-PROC-36).
- Set operations and `@Discriminator` (RFC 0013, R-SRC-06, R-PROC-38): `SourceQuery#union`, `#unionAll`, `#intersect`
  and `#except` combine two sources that read the same shape, and a branch — a `@SourceModel(shape = ...)` — fixes its
  key column to a constant with `@Discriminator(value = "...")`, which is what makes the combined rows unique by key.
  Two branches of one `union` or `unionAll` sharing a value are `MQ1904`, and a branch whose fields differ from its
  shape's by name, position, Java type, converter, `@Enumerated` or temporal mapping, or in number, is `MQ3034`. A
  source with no unique key — one whose `@PrimaryKey` is not unique in the source, or a combination whose keys
  collide — is refused where a read identifies a row by its key, with `MQ1901` for a `keyset()` or
  `primaryKeyFirst(...)` over one and `MQ2212` for an `export`, `byKeys`, `one(q, key)` or fetch plan over one; an
  offset page over a non-unique source orders by every selected column as a tie-breaker.
- Recursive sources (RFC 0013, R-SRC-07, D-144): a `@Depth Integer` field makes a source recursive, and its
  `source()` requires the step and the limit before it is built: `recursive(step -> step.childKey(...).parentKey(...),
  maxDepth)` names the two key columns, while `where(...)` selects the seed rows. A `maxDepth` below 1 or above
  `Integer.MAX_VALUE - 2`, a `recursive(...)` on a model with no `@Depth`, a `@Depth` model built without
  `recursive(...)`, and a `@Depth` that is not exactly one `Integer` field are `MQ1902` or `MQ3037`; a recursive
  source carrying `@Window`, `@Aggregate` or `@GroupBy` is `MQ3037`, which every database forbids in the recursive
  term. A tree deeper than `maxDepth` is refused with `MQ2701`, naming the model, the limit and the key of the first
  row past it, and a graph that closes a cycle with `MQ2702`, naming the model and the first key the cycle clause
  marked; both are read from the statement's own first row, so a too-deep tree is refused before any page is
  returned, with no extra query.
- Running a source (RFC 0013, R-SRC-10, D-143): a query over a source is a CTE, so the provider needs the new
  `@Incubating SourceRenderer` of `core`, which `ProviderSupport#sources()` returns (empty by default; `window`, `combine` and `recursive` default to `MQ4015`), and the
  target's `VendorProfile` has to answer `true` for every `SourceFeature` the source uses — `CTE`, `RECURSIVE`,
  `WINDOW`, `UNION`, `INTERSECT` or `EXCEPT` — and to leave room above its `maxRecursionDepth()` for a recursive
  one's `maxDepth + 2`. A factory whose provider has no `SourceRenderer` is `MQ4014`, a target that lacks a feature
  or the depth is `MQ4015`, and both are raised at build time, before any statement, so the portability dry run
  refuses the target with no database attached. H2 2.2+, PostgreSQL 14+ and MySQL 8.0.31+ have all six (`vendor/41`).
  `MQ1903` refuses a row lock on a query that reads a source, because a locking clause cannot follow the CTE;
  `MQ1905` refuses a hand-written `ColumnField.of` or `TableField.join` over a source root, which the processor's
  metamodel cannot check, in favour of the generated `ColumnField.over(...)`; and `MQ1906` refuses a source `where`
  predicate whose path crosses a to-many join, which no join inside the CTE can express, and points at
  `Filters.exists(path, inner)`.
- A [Source models](source-models.md) docs page covers the source, its key, the outer model and set, window and
  recursive sources, with the provider and vendor facts they need, the vendors each feature runs on, and what does
  not work yet: a window reading rows another transaction wrote, MySQL's export cost, an emulated `cycle`, the
  empty-result race under `READ COMMITTED`, and a CTE column name that is a reserved word.

### Changed
- The Tier-1 MySQL floor rises from 8.0 to 8.0.31 (`vendor/41`, RFC 0013, D-143): `intersect` and `except` arrive in
  8.0.31, so the version the Tier-1 matrix runs drops to the first release with the whole of the source feature set
  listed above. `VendorProfile` carries no server version, so an older 8.0.x is unsupported rather than refused;
  8.0.0 to 8.0.30 are past end of life.

- A source CTE's columns are named `mq_<property>` by the engine (D-146), so a field named `year` or `order` cannot
  collide with a vendor reserved word; properties, sort matching and generated code are unchanged. `ProviderSupport#sources()`
  returns the one hook, `core.SourceRenderer`. `Builder#discriminator` takes a key column and `Builder#depth` a column of
  the fields, a recursive step's `childKey` and `parentKey` share one column type, and `combine` over sources whose
  fields differ in order, type or converter is `MQ1904`.
- `WindowField` joins `SelectField`'s sealed permits (RFC 0013), so an exhaustive `switch` over `SelectField` needs a
  new case and already-compiled code meets a `MatchException`.
- `MQ3031` checks an ungrouped source's `@PrimaryKey` against the root entity's id as a whole (D-149, RFC 0014): a
  source over an `@IdClass` or `@EmbeddedId` entity now compiles with every id column as its key, where it was refused
  for keying more than one column, and the message no longer calls the key "one row's key".
- Filter-only to-many joins render as correlated `exists` semi-joins (RFC 0018 Part D, D-177, D-180, D-181, R-EXE-16):
  where a statement's `where` makes a to-many join that nothing else in the outer statement keeps, its terms that join
  or nest a sub-query render inside one correlated sub-query over a second root of the same entity:
  `exists (select 1 from <entity> r2 <joins> where r2 = r and <terms>)`. Root terms (those that make no join and no
  sub-query) stay on the outer statement (`D-181`). Result rows and aggregates change to match:
  1. `list`, `stream` and offset pages return each root entity once rather than repeating it per matching child; export
     and keyset pages stop ending prematurely with short pages;
  2. `one(q)`, `one(q, key)` and `byKeys` return the root entity where they previously threw `MQ2003`;
  3. Grouped aggregates count each root entity once instead of multiplying by matched children;
  4. Locked reads (`LockModeType`) run where they previously threw `MQ2211`, leaving sub-query tables unlocked;
  5. A customizer that resolves the filter's child path through `built.joins()` creates a separate outer join rather
     than reusing the filter's join, multiplying rows by all children (not only matching), and `query.getRestriction()`
     is now the `exists`.
  A customizer's join, or a shape the semi-join excludes (a treated node, a having, or a selected column, order or
  group key that keeps the to-many join, or a fetch-plan through load), retains the outer join as before and continues
  to duplicate rows.
  PostgreSQL semi-joins ran ~2–3.5x faster in benchmarks, whereas MySQL 8.0 was 1.4–3.6x slower due to duplicate
  weedout and a second root lookup; check real plans, as no unique-per-root bypass is provided.
- Unsupported mapping-dependent inputs fail under inline value handling (RFC 0018 Part C, D-177, R-FLT-08): under
  `hibernate.criteria.value_handling_mode=inline`, where no binder types a column, every value of an enum, `Boolean`,
  `UUID` or converted column that previously ran with an inlined literal now fails with `MQ1502` before any statement
  runs: not only filters, but primary keys in `one(q, key)`, `byKeys` and primary-key-first pages, keyset cursors and
  fetch-plan child loads.
- Case-insensitive filters over converted attributes return matching rows (RFC 0018 Part B, D-177, R-FLT-07):
  `eqIgnoreCase`, `EXACT` and `STARTS_WITH` over JPA `@Convert` attributes (such as `SKU:`) now return matching rows
  where they previously missed; `CONTAINS` and `ENDS_WITH` previously succeeded only by accident of their leading `%`.
  Matches require converters with the folding property (`lower(conv(x)) = lower(conv(y))` iff `lower(x) = lower(y)`).
  Without a mapping-aware binder, values remain compared unconverted.
- Primitive basic attribute binding under Hibernate 6.6 (D-179): Hibernate 6.6 reports primitive node types (`int`,
  `boolean`) as JPA basic types; the operand binder accepts them so typed inserts and filters bind rather than failing
  with `MQ1502`.
- Source `where` parameter binding (D-179): an `Expr.value` in a source's `where` now binds under the default setting,
  where before its parameter was never bound and the statement could not run.

## [0.9.0] - 2026-10-14

Nothing is frozen yet: every public type stays `@Incubating`. The additions below are all `@Incubating`. Four
behaviour changes (see the upgrade notes) can change the SQL or the diagnostics of a 0.8.0 model.

### Added
- `@BindQueryModel(model = OrderView.class, root = OrderEntity.class)` (D-134, R-PROC-28, R-GEN-35), repeatable and
  allowed on a type or a `package-info`, binds a query model compiled without its entity, in a module that depends on
  `model-query-annotations` only. The processor reads the model from the classpath, runs every check against the bound
  root and generates its QModel in the binding's package. `@BindUpdateModel` and `@BindInsertModel` bind update and
  insert models the same way, and `@BindQueryModel` takes `generateChanges` and `generateInserts` (D-141). A model bound
  twice for one kind, or bound and annotated, is `MQ3023`, and so is a binding that would generate one class name with
  another binding, of any model, or with an annotated model in the compilation (which still generates its own); a model,
  no-arg constructor, setter, `@Join` getter or column getter the generated classes can't reach from that package is
  `MQ3024`.
- A bound model may nest, through `@Join` or `@Child`, a model that is annotated or bound in the same compilation, and
  its QModel names the nested QModel in that binding's package (R-PROC-31, D-142); any model may nest a bound one. On a
  bound model, a nested model that is neither is the new `MQ3026`, and one that is not public is `MQ3024`. A binding
  whose root, or a nested model's, another processor generates is retried in a later round as an annotated model is
  (D-107), and reports `MQ3017` on the binding if the class never appears. The processor option
  `-Amodelquery.aggregating=true` (R-PROC-34, D-140) makes Gradle treat it as aggregating, which a module needs when a
  model nests a bound model whose binding is in another source file; it is isolating otherwise.
- `@Computed.value` defaults to `void.class` (source and binary compatible), and `@BindQueryModel(expressions =
  @BindExpression(field = "net", value = RowNet.class))` supplies the `ExpressionDefinition` of a bound model's
  valueless `@Computed`, or turns an `@Aggregate` into an expression aggregate, since the model's module cannot compile
  a definition over the entity (R-PROC-29). A valueless `@Computed` with no `@BindExpression`, or on an annotated model,
  and a `@BindExpression` that matches no such field, are the new `MQ3025`.
- The "Models in an API module" docs section covers the module layout, a binding, `@BindExpression` and the Maven and
  Gradle setup, with the Gradle `compileJava` input that an annotation-only edit to a bound model needs (D-140).
- The new `model-query-portability` artifact, on `model-query-jpa` only (resolve it at `model-query-jpa`'s exact
  version, through the BOM: D-139) and meant for test scope, boots a factory per
  target vendor with no database (D-133, R-INS-10): `Target.of(vendor)` or `Target.of(vendor, "15.4")`, a recording
  deny-by-default stub `DataSource`, and `ProviderSupport#offlineProperties(DatabaseVendor)`, which
  `model-query-hibernate` implements for Hibernate 6.6 and 7.4; a provider without it, or a boot that reaches JDBC,
  is the new `MQ4013`. `PortabilityCheck.of(unit or classes, config)` runs the real executor on it per target
  (R-INS-11, R-INS-12): `call(...)` and `callOutsideTransaction(...)` with the real signatures, `sweep(holders...)`
  over `static final` definitions with `skip(holder, field, reason)`, and `assertPortable()`, which reports one line per
  call and target, `REACHED` with the SQL (marked "first statement only" where a later statement is not checked),
  `REFUSED`, `REFUSED_AFTER_STATEMENT`, `NO_STATEMENT` or `HARNESS_ERROR`, then one line per skipped definition with
  its reason, and fails unless every result is `REACHED`.
  The targets default to `config.targetVendors()`, and one outside them is `MQ4012`.
  The TCK runs the dry run on H2, PostgreSQL and MySQL on both Hibernate lines with no container (AC-INS-11): it
  reproduces every vendor-dependent refusal of the row-lock, returning, conflict, `nextval` and to-one-id criteria,
  and a first statement it reaches equals the live run's SQL snapshot wherever the live run committed one. A new
  "Portability" docs page covers declared targets, the dry run beside a Testcontainers matrix, and what it cannot
  decide.
- Declared target vendors (D-133, R-VND-19, R-SPR-17): `ModelQueryConfig.targetVendors(DatabaseVendor first,
  DatabaseVendor... more)`, `targetVendors(Collection)` and `targetVendors(String...)`, read by `targetVendors()`, and
  the property `modelquery.target-vendors`. A factory whose configured or detected vendor (not its profile's, so
  MariaDB is a valid target) is not listed is the new `MQ4012`, naming the factory, the vendor, how it was found and
  the list: from `ModelQueryExecutor.create`, from the new `ModelQueryConfig.checkTargets(EntityManagerFactory)`, and
  in Spring at startup, where a `BeanPostProcessor` enabled by `modelquery.target-vendors` alone checks every eager
  `EntityManagerFactory` bean against that list and reports every failing one in one `MQ4012` (a lazy factory is
  checked when its first executor is built; targets declared by a configurer or config bean are checked when a
  repository is created; a factory bootstrapped in the background, `bootstrap-mode=deferred` or `lazy`, is checked once
  the singletons exist, so the check never waits on its build). An empty list is `MQ4003`,
  an unknown name `MQ4001`, whose message now names `modelquery.target-vendors` too; an application `ModelQueryConfig`
  bean with the property set is `MQ4006`, reported before any `MQ4012`. The startup check never calls a `ModelQueryConfigurer`. The factory's name in `MQ4012` comes from the new `@Incubating`
  `ProviderSupport.factoryName(EntityManagerFactory)`, empty by default, which Hibernate answers with the session
  factory name; with no name, the factory's class and identity.
- A to-one read by its id (D-136, R-COL-29): a column of the target's single basic id type over a to-one
  (`ColumnField.of(Order.class, ORDERS, "referrer", Long.class)`), or with a converter from it, reads the foreign key
  instead of `MQ1001`, whose message now names the id type:
  - Every clause of a read reads it through a `LEFT` join and the target's id, so a `NULL` foreign key reads back
    `null` and no row is dropped; a locked read of it is `MQ2211`.
  - A write's filter renders the foreign-key column with no join. The new code `MQ1004` refuses it before any
    statement unless the provider answers `LOCAL_COLUMN` (a column of the entity's own table, no join or formula,
    referencing the target's primary key) through the new `ProviderSupport.foreignKeyColumn` and its `ForeignKeyColumn`
    enum, which `model-query-hibernate` answers; core receives the answer as `ForeignKeyLookup`. It checks the
    `where`, the set clause and an upsert's `doUpdate(...).where(...)`, and the message says whether the provider
    reports it is not or cannot tell. A write of a `@JoinFormula` or `@SecondaryTable` to-one's id is therefore
    `MQ1004`; a `@JoinFormula` write was accepted before.
  - A sub-query of a read (`exists`, `in(subSelect)`, `exists(subSelect)`) reads it through the same join.
  - A returning write and `persist(..., returning)` fill it from the written row (R-WRT-48).
- A to-one read by its id on a generated model (D-136, R-PROC-33, R-GEN-19): `@Column(attribute = "referrer") Long
  referrerId`, or a converter from the id type, is accepted on a `@QueryModel`; another type is `MQ3002` naming the
  target's id type, and `MQ3002` with a reason refuses the inverse side, a `referencedColumnName`, `@JoinColumns`,
  `@JoinFormula`, `@JoinColumnOrFormula`, `@NotFound`, a `@MapsId`, `@IdClass` or `@EmbeddedId` target and a to-one in an
  embedded value. `generateChanges` and `generateInserts` write the id form and the generated `from` copies it; the
  whole-entity form stays read-only (D-124). `MQ3016` names the id form. See "Foreign key by id" in the user guide.
  - `@JoinTable` and `@JoinColumn(table = "...")` are also `MQ3002` reasons.
  - The new code `MQ3029` refuses the id form over a target with `@SQLRestriction` or `@Where`, whose restriction
    would make a read return `null` for a foreign key that holds a value (until Q-20 resolves); `MQ3016` does not
    suggest the id form there. A runtime `@Filter` on the target is a documented caveat.
  - The new code `MQ1410` refuses a runtime `groupBy` or group key over a whole-entity to-one column before any
    statement (D-137), as `MQ3028` refuses `@GroupBy`; the id form stays allowed.
- Generated-code nullness (D-135, R-PROC-32, R-GEN-36), described in "Processor options":
  - `-Amodelquery.jspecify=true` puts JSpecify `@NullMarked` on generated `Q` classes and change sets, type-use
    `@Nullable` on a change set's fields, setter and fluent parameters, getters and assignment values, and
    `@NullUnmarked` on the mapper and the `JoinField`/`ChildField` `with` methods. The annotations are named by string,
    so no artifact depends on JSpecify; output with the option off is unchanged.
  - The new code `MQ3027`, reported once per compilation when the option is set and JSpecify is not on the classpath.
  - A NullAway setup for Maven and Gradle, checked by a compile test of the shop, update and insert fixtures.
- Row locks on reads (D-127, RFC 0009), described in "Row locks" and the "Claim the next job" recipe:
  - `RowLock` names a strength (`exclusive` or `shared`) and a wait policy (`Wait.WAIT`, `NO_WAIT` or `SKIP_LOCKED`).
    `ModelQuery.Builder#lock` sets it, `ModelQuery#lock()` reads it back, and `ModelQuery#withLock` returns a locking
    copy as `orderedBy` does, keeping the fetch plan.
  - `list`, `list(q, Limit)`, `first`, `one`, `one(q, key)` and `byKeys` take the lock; `byKeys` locks each chunk's
    statement ordered by primary key. `page`, keyset `page`, `count`, `stream` and `export` refuse a locked query with
    `MQ2211`, as does a joined query and `one(q)` with `SKIP_LOCKED`; `persist(..., returning)` refuses one with
    `MQ1809`. A fetch plan's own statements stay unlocked.
  - A locked read needs an active transaction, else `MQ2102`. A lock on a grouped query is `MQ1209`, and `MQ1205` now
    covers a customizer that adds `distinct` or `having` to a locked query.
  - `ModelQueryRepository.findAll(q, Limit)`, `findOne`, `findFirst`, `findByKey` and `findAllByKeys` take the lock;
    `findPage` and `findKeysetPage` throw `MQ2211`, and a locked read with no ambient transaction is `MQ2102`.
  - The new codes `MQ1209`, `MQ2102`, `MQ2211` and `MQ4010`; `MQ1809` gained the lock case and `MQ1205` the two
    customizer clauses. A lock the provider cannot render — a wait the running Hibernate's dialect drops, or a root
    mapped to more than one table — is `MQ4010`. Rendering is verified on the Tier-1 vendors (H2, PostgreSQL and
    MySQL) on both Hibernate lines.
- Literal filters and bound function arguments (D-128, RFC 0002), described in "Literal filters" and "Bound function
  arguments" and the "Filter by a fixed set of enum states" and "Group by a time zone the caller picks" recipes:
  - `Filters#in` and `#notIn` take a fixed set of operand fields — `in(col, a, b)`, `in(col, a, b, c)` and the same
    for `notIn` — with the value forms' semantics, so `notIn` also keeps rows whose column is NULL. Each operand is a
    field of the column's own model and type, and `Expr.constant` inlines its literal, e.g. `in ('PENDING','FAILED')`.
    They record as the new `Condition.Kind` values `IN_FIELDS` and `NOT_IN_FIELDS`, with `Condition#operands()`.
  - `Expr.constant(ColumnField typedBy, Object value)` types a constant by a column: the value goes through the
    column's `ColumnConverter` (`MQ1308` on failure) and then the attribute's JPA mapping — an `@Enumerated(STRING)`
    enum's name, an `ORDINAL` one's ordinal, an `AttributeConverter`'s stored value — inlined as the provider's
    literal text. The stored value must be a `String`, `Number` or `Boolean`, else `MQ1508`. `typedBy` is a type
    witness, part of the expression's equality but no column of it and no join. It is operand-only (a comparison or
    `in`/`notIn` operand); as a model column, an order or a group key it is `MQ1508`.
  - `Expr.value(Object)` renders a value as a statement parameter, bound by the executor on every statement that
    renders it: `list`, `count`, an offset page, primary-key-first (both steps), a keyset page, a grouped or offset
    export, an `exists` or sub-select body, a CASE condition, and a chunked bulk update and delete. A `null`, an
    `Optional`, a `Collection` or a `Map` value is `MQ1502`.
  - The new code `MQ1508`; `MQ1502` gained a case. R-FLT-09's statement check counts an `Expr.value` expression's
    binds, as it counts a filter's. Every change is additive and `@Incubating`.
- Returning writes and conflict extensions (D-129, RFC 0010), described in "Returning the changed rows", "Conflict
  clauses", "Sequence values" and the "Claim the next job" and "Number rows from a sequence" recipes:
  - `updateReturning`, `deleteReturning` and `insertReturning` on `ModelQueryExecutor` and `ModelQueryRepository` run a
    write and return the changed or written rows as the models of a `ModelQuery`, in one statement: H2 through
    `FINAL TABLE` and `OLD TABLE`, PostgreSQL through `RETURNING`; MySQL is `MQ4011`. `MQ1809` covers the new refusals
    (a column on another table, `throughEntities()`, `commitEachChunk()`). The Hibernate mutation translation sits
    behind one adapter per line, chosen by probe, with a `WARN` outside its tested range; the SPI is
    `ReturningSupport`, `ReturningShape` and `WriteStatement`, with `ProviderSupport#returning()`.
  - `ConflictUpdate#setExpression(column, ConflictExpression)` computes a conflict assignment from the stored and the
    incoming row; `ValuesInsert#onConstraint(name)` and an arbiter `where` on `onConflict(columns)` name a constraint or
    a partial unique index (PostgreSQL only, `MQ1804` elsewhere); `MQ1601` for an arbiter that every filter skipped.
  - `Expr.nextval(sequence)` reads a sequence's next value as a `Long` select column through
    `ProviderSupport#sequences(EntityManagerFactory)`, behind the profile fact `VendorProfile#sequences()` (true on H2
    and PostgreSQL). The new code `MQ1509` refuses it in a filter, a `having`, an order or a group key, on a name
    that is not an identifier, and on MySQL, before any statement. New `VendorProfile` facts: `conflictOnConstraint()`,
    `conflictArbiterPredicate()` and `sequences()`.
  - Write expressions (D-130), described in "Sequence values and other write expressions" and "Sequence values" of
    Inserts: `setExpression(column, ExpressionField)` on `ModelUpdate.Builder`, `ConflictUpdate`,
    `ConflictUpdate.Assigned`, `ValuesInsert.Rows` and `ModelInsert.Mapping` writes an expression that reads no column
    and binds nothing, such as `Expr.nextval`, evaluated per written row: a chunked update's chunks take increasing
    values, each insert-values row its own value with no bind for it, an insert-select one per source row, a conflict
    update one per row it writes. The new `Assignment.Computed` permit means an exhaustive `switch` over `Assignment`
    needs another case. The new code `MQ1613` refuses an expression that reads a column or binds a value; a
    sequence on a vendor without sequences is `MQ1509` before a write's flush or any statement; `MQ1802` refuses one
    on an insert's id. `InsertSupport` gains a six-argument `insertValues` default taking per-row expression
    factories, which `model-query-hibernate` implements; where a provider leaves it to the default, the insert is
    `MQ1613` before the flush.
  - The "Claim the next job" recipe claims rows with a locked key read, then `updateReturning` over `whereKeys`, or
    `update` and `byKeys` on MySQL, in one transaction; two concurrent workers claim disjoint rows (AC-WRT-47). No
    locked sub-select write is offered (D-131).
  - Every change is additive and `@Incubating`.

### Changed
- A fetch plan's child statements are now built and checked for a one-key round before any statement of a call that
  returns models, so a child refused with `MQ1306`, `MQ1307` or an `MQ15xx` code fails on an empty page, a zero limit
  or `byKeys` with no keys too, where it used to fail only once a page found a parent (D-138, R-FCH-02). A
  primary-key-first `page` or `export` likewise sizes step 2 and refuses it with `MQ1307` before step 1 and the
  count, not after step 1 read keys (R-PAG-07).
- `MQ1502` now also refuses an `Optional`, a `Collection` and a `Map` value from every `Expr` factory, including the
  new `Expr.value`: a collection is never a single bound value.
- `@GroupBy` on a column that selects a whole to-one entity is now the error `MQ3028` (D-136): the database groups by
  the foreign key but the select lists every column of the entity, which PostgreSQL refuses. This can break an
  existing `@GroupBy CustomerEntity customer`; group by the id form, `@Column(attribute = "customer") Long customerId`,
  instead. `MQ3016`'s message also names the id form.

### Upgrade notes
- A model with `@GroupBy` on a column that selects a whole to-one entity now fails with `MQ3028` at compile time. Group
  by the id form, `@Column(attribute = "customer") Long customerId`, instead.
- A fetch plan's child statements are built and checked before any statement of a call that returns models, so a
  child refused with `MQ1306`, `MQ1307` or an `MQ15xx` code now fails on an empty page, a zero limit or `byKeys` with
  no keys too, and a primary-key-first `page` or `export` refuses `MQ1307` before step 1 and the count.
- `MQ1502` now also refuses an `Optional`, a `Collection` and a `Map` passed to an `Expr` factory.
- `@Computed.value` defaults to `void.class`: source and binary compatible, but a `@Computed` without a value now needs
  a `@BindExpression`, else `MQ3025`.

## [0.8.0] - 2026-10-09

Nothing is frozen yet: every public type stays `@Incubating`, and the freeze review moves to a later milestone. The
additions below are all `@Incubating`. Two behaviour changes (see the upgrade notes) change the SQL a 0.7.0 model
writes.

### Added
- Insert columns a database fills (D-125, RFC 0008), described in "One model for reading and creating":
  - `@QueryModel(generateInserts = true)` leaves out a non-id root column whose attribute carries Hibernate's
    `@Generated` with `writable = false`, an empty `sql` and `INSERT` among its events: the database fills it, and a
    computed `GENERATED ALWAYS AS` column refuses a value. `@Generated(writable = true)`, `@Generated(sql = …)`,
    `@Generated(event = UPDATE)` and every timestamp annotation stay written.
  - The new `@ExcludeFromInserts` annotation leaves any other column — a plain `DEFAULT` clause — out of
    `INSERT_COLUMNS`, `insert`, `insertFrom` and `persist` while the model still reads it, so a server-set column uses
    `insert(rows).set(CREATED_AT, now)`. `MQ3506` refuses it where it does nothing.
- Reading many keys (D-126, RFC 0008), described in "Reading many keys":
  - `ModelQueryExecutor.byKeys(q, keys)` reads many primary keys into an unmodifiable `Map<K, M>` in the keys'
    first-occurrence order, with a missing key absent. Keys are chunked as `whereKeys` spreads its keys; a key matching
    two rows is `MQ2003`, and the new `MQ2005` names a row that matches no requested value.
  - `ModelQueryRepository.findAllByKeys(q, keys)` delegates to it.
- `MQ2005` and `MQ3506`, and `MQ3501`'s message for an `@ExcludeFromInserts` field that is an assigned id.

### Changed
- A 0.7.0 `generateInserts` model whose entity has a non-writable `@Generated` insert column stops writing that column:
  under `insert` a value the caller set on it is dropped, as `persist` already dropped it; a `GENERATED ALWAYS AS`
  column that failed loudly under 0.7.0 now works.
- `count(q)`, `one(q)` and `one(q, key)` on an ordered query log one `WARNING` per `ModelQuery`, which they did not
  before, and both `one` statements render no `ORDER BY`.

### Upgrade notes
- `byKeys(q, keys)` is abstract on `ModelQueryExecutor`, like `one` and `first`. A class that implements
  `ModelQueryExecutor` itself (a decorator or test double) must add it; one compiled before 0.8.0 throws
  `AbstractMethodError` only when `byKeys` is called.
- A model whose entity maps a non-writable `@Generated` insert column now drops that column from its inserts, so a
  value set on it under `insert` no longer reaches the database. Name the column in an `@InsertModel` if it must be
  written.

## [0.7.0] - 2026-10-09

Nothing is frozen yet: every public type stays `@Incubating`. No existing API changes incompatibly except for classes
that implement `ModelQueryExecutor` themselves (see the upgrade notes); the additions below are all `@Incubating`.

### Added
- Fewer models, fewer calls (D-123, RFC 0007), described in "One model for reading and creating" and "Reading one row":
  - `@QueryModel(generateInserts = true)` generates `INSERT_COLUMNS`, `insert`, `insertFrom` and `persist` on a query
    model, over its root columns. It leaves out joins, children, computed, selected and transient fields, filter-only
    columns, to-one columns, version columns, columns an insert cannot write and generated ids, and the `INSERT_COLUMNS`
    Javadoc names each. `addKey` follows the root's id. `MQ3505` for a grouped model or one with no writable root
    column.
  - `ModelQueryExecutor.one(q)`, `first(q)` and `one(q, key)`, each an `Optional`. `one` reads at a limit of 2 and
    throws `MQ2003` on a second row before the fetch plan runs; through a predicate-only to-many join the message
    points at `Filters.exists`. `first` orders by the `orderBy`, then the key (group keys for a grouped query) with
    `nullsLast` where the key can be `NULL`, so every database returns the same row. `one(q, key)` ANDs the key with the filter.
  - `ModelQueryRepository.findOne`, `findFirst` and `findByKey`, delegating to the three executor methods.

### Changed
- A query model's generated change set and inserts leave a to-one column out (D-124): a query model cannot name a
  foreign key as a scalar, so the setter could never succeed. The change set's Javadoc names the column and the reason.
  `@UpdateModel` and `@InsertModel` still write a to-one by id.
- A whole-entity or converted to-one column on the root reads through a `LEFT` join of the association, so a row
  with a `NULL` foreign key comes back with `null` instead of being dropped. A filter on a to-one column now adds that
  `LEFT` join, and a keyset export ordered by a to-one column now sees the `NULL`-foreign-key rows, so it throws
  `MQ2202` unless the order sets `nullsFirst` or `nullsLast` (they used to be silently dropped).

### Fixed

- A model field declared `java.util.Date` over an entity attribute declared `java.util.Date` compiled and then failed
  `MQ1001` at first use on Hibernate, which reports such an attribute as `java.sql.Date`, `Time` or `Timestamp`. A
  `Date` column now matches all three (D-122), as do a `Date` `Expr.coalesce` (`MQ1507`) and `Agg.of` (`MQ1405`), and
  each of the three codes names both types in full when their simple names are equal.
  An insert-select `set` of a `Date` on a `DATE` or `TIME` attribute now binds as that attribute's type rather than a
  timestamp, which Hibernate refused.

### Upgrade notes
- `one`, `first` and `one(q, key)` are abstract on `ModelQueryExecutor`, as a default built on `list` would run the
  fetch plan on the extra row. A class that implements `ModelQueryExecutor` itself (a decorator or test double) must add
  the three methods; one compiled before 0.7.0 throws `AbstractMethodError` when a new method is called.
- A query model with `generateChanges = true` and a to-one column no longer gets a setter for it, so a call to that
  setter stops compiling. Write the foreign key through an `@UpdateModel`.

## [0.6.0] - 2026-10-08

Nothing is frozen yet: every public type stays `@Incubating`, and the freeze review stays after this release (M14).
No existing API changes incompatibly; the additions below are all `@Incubating`.

### Added
- Field lookup by name (D-121, RFC 0006), described in the "Client-chosen fields" recipe:
  - `FieldIndex<M>`, an immutable index of a model's fields by the names a sort already uses (`customer.name`):
    `select`, `filter`, `set` and `child` look a name up by kind, `resolve` turns a list of names into a `SelectSet`,
    the children named and the unknown names without throwing, `only` narrows the index to an application's whitelist
    (`MQ1105` for an unknown name or an emptied set), and `names()` and `filterNames()` list the keys. Its public
    `Builder` (`select`, `filterOnly`, `set`, `joinSet`, `child`) is what generated code calls.
  - A generated `Q<Model>.fields()` on every query model, built on first use.
  - `AggregateField.named`, the aggregate's key in the index, outside `equals` and `hashCode`.
  - `MQ1105` (core) and the warning `MQ3022` (processor).

### Changed
- Generated `@Aggregate` constants now carry `.named(field)`, and a sort property name matches a `named` aggregate
  first (R-QRY-14).

### Upgrade notes
- `MQ3022` can now warn on a model that compiled cleanly on 0.5: two `@FilterColumn`s on one path (aliased joins
  included, such as a buyer's and a payer's email), or a filter column on a path a mapped column or a `@Computed` field
  already holds. Only the first is in `fields()`; the others stay constants and keep working. A build with `-Werror`
  fails until one of them is renamed.

## [0.5.0] - 2026-10-07

Nothing is frozen yet: every public type stays `@Incubating`, and the freeze review moves to the next milestone (D-120).
No existing API changes incompatibly; the additions below are all `@Incubating` except `SelectSet`'s equality.

### Added
- Selected fields (D-120), described in the "Selected fields" section of the "Models and QModels" page:
  - `@Selected` on one `SelectSet<Model>` field or record component: the generated mapper fills it on every row, never
    with `null`, with the columns, expressions and aggregates of `Q<Model>` the row selected, joined columns of any depth
    and the keys the engine adds included, so a selected `NULL` column can be told from an unselected one. A nested
    `@Join` or `@Child` model fills its own. `MQ3020` for a wrong type or a second field, `MQ3021` for another field
    annotation on it, and `MQ3302` and `MQ3502` for an update or insert model that declares one.
  - `SelectSet.contains(SelectField)`, exact as `Row.isSelected` is, and `SelectSet.selectedIn(Row)`, the subset the row
    selected, in set order, sharing one instance across the rows of a query.
  - A record `finisher` passes `selected()` along like any component (R-GEN-31).

### Changed
- `SelectSet` has set-semantics `equals` and `hashCode` (same fields, any order) and a `toString` like
  `[OrderView.id, OrderView.status]`, so a record holding one stays comparable.
- A release publishes only after the full nightly matrix passes on the tag commit, and every push also builds against
  Hibernate 7.x.

### Fixed
- A values `insert` on Hibernate 7 failed with `QueryArgumentException` ("int is not assignable to
  java.lang.Integer") when a column targets a primitive attribute.

## [0.4.0] - 2026-10-07

Nothing is frozen yet: every public type stays `@Incubating`, and the freeze review moves to the next milestone, after an
adopter runs 0.4.0 in production (D-111, D-118). No existing API changes incompatibly; the additions below are all
`@Incubating`.

### Added
- Entity writes (RFC 0005, D-118), described in the "Bulk writes" and "Inserts" pages of the user guide:
  - `throughEntities()` on a `ModelUpdate` or `ModelDelete`: each chunk loads its entities by key and writes them
    through the metamodel, so entity callbacks, the provider's listeners, audit libraries, Bean Validation, cascades
    and `@SQLDelete` run. Always chunked; the count is the entities matched. `MQ1610` for `keepVersion()`,
    `expectVersion(...)` or `setExpression` combined with it, whatever the call order. An update writes attributes
    through the provider's `EntityWriteSupport` (`ProviderSupport#entityWrites()`, Hibernate's included), so a proxy
    already in the persistence context and a bytecode-enhanced entity are written; without one, a proxy is `MQ2503`
    (D-119).
  - `persist(persist, returning)` on `ModelQueryExecutor` and `ModelQueryRepository`: returns a query model built from
    the flushed entity with no second select; `MQ1809` for a query clause or column it cannot fill.
  - `WriteAssignment`, `WriteKind` and `ModelQueryConfig.writeAssignments`: a server-set column named once per
    entity and applied by bulk update (chunked and entity mode), insert-values, insert-select, `doUpdate` and
    `persist`, skipped where the write sets the attribute itself. `MQ1611` and `MQ1612` for a path or value that
    does not fit, or on a strict subclass of the write's root. In an entity-mode update an assignment dirties a
    row only where its value differs (R-WRT-49).
  - The Spring Boot starter hands every `WriteAssignment` bean to the config of each datasource; an application's own
    `ModelQueryConfig` bean that drops one fails startup with `MQ4006`.
  - The Spring Boot sample's `POST /books` returns the persisted `SavedBook`, and its updated-at column is a
    `WriteAssignment` bean.
- `MQ1610` to `MQ1612`, `MQ1809` and `MQ2503`; see the diagnostics reference.

### Changed
- `ModelQueryExecutor`, `ModelQueryRepository`, `ModelUpdate`, `ModelDelete` and `ModelQueryConfig` gain the methods
  above. They are `@Incubating` or `@EngineFacing`, so `japicmp` ignores them.

## [0.3.0] - 2026-10-07

Nothing is frozen yet: every public type stays `@Incubating`, and the freeze review moves to the next milestone (D-111,
D-116). No existing API changes incompatibly; the additions below are all `@Incubating`.

### Added
- Inserts (RFC 0004, D-116, D-117), described in the "Inserts" page of the user guide:
  - `@InsertModel`, and generated `QModel.insert(rows)`, `insertFrom(...)` and `persist(row)` builders, with
    `MQ3501` to `MQ3504` for definition errors.
  - Insert-select, unchunked and chunked key-first over the source's distinct ids (`ModelInsert.select`), which writes
    exactly the rows the equivalent read returns; `MQ1806` when a chunked copy overlaps its target or joins
    through a link or collection table.
  - Insert-values (`ValuesInsert`) as multi-row `VALUES` statements within the vendor's bind and row limits, with
    converters, to-one columns by id and `commitEachChunk` with `nextRowIndex()` to resume.
  - `insertReturningKeys`, returning keys drawn before the insert, in row order; `MQ1807` for an `IDENTITY` id.
  - Conflict clauses on insert-values: `onConflict(...).doNothing()` and `doUpdate(...)` with `setFromRow`, `set`,
    `setNull`, `where` and `keepVersion()`, `anyUniqueKey()` for MySQL, and the
    `ModelQueryConfig.conflictUpdateWhereOnAssignedColumns` option; `MQ1804` when a vendor cannot honour the clause.
  - `persist(ModelPersist)`, one row through JPA with its lifecycle callbacks, the portable way to get an `IDENTITY`
    key; `MQ1802` for a model writing part of a composite id or, where the provider reports the generator, a generated
    id, and `MQ1308` for a `null` set on a primitive attribute, both before any statement.
  - `ModelQueryExecutor.insert`, `insertReturningKeys` and `persist`, and the same three on `ModelQueryRepository`,
    which opens a transaction when none is active (R-SPR-10).
  - Provider SPI: `ProviderSupport.inserts()` with `InsertSupport`, `InsertTarget`, `IdGeneration` and
    `ConflictClause`, implemented by `HibernateProviderSupport`; `VendorProfile.maxValuesRows()` and
    `conflictTargetHonoured()`. A provider without it throws `MQ4009` from a bulk insert; `persist` still runs.
  - The Spring Boot sample gains a `POST /books` endpoint over `persist` and a `POST /films/import` endpoint over
    insert-values with `doNothing`.
- `MQ1801` to `MQ1808`, `MQ3501` to `MQ3504` and `MQ4009` for insert definitions and execution; `MQ2501` now also
  covers `persist` without a transaction. See the diagnostics reference.

### Changed
- `ModelQueryExecutor`, `ModelQueryRepository`, `VendorProfile` and `ProviderSupport` gain the methods above. They are
  `@Incubating`, and the new `VendorProfile` and `ProviderSupport` methods have defaults, so existing implementations
  compile unchanged.

### Fixed
- An update setting `null` on an attribute with a JPA `AttributeConverter` now writes what the converter gives for
  `null`, instead of a SQL `NULL` that bypassed it (R-WRT-01).

## [0.2.0] - 2026-10-06

Nothing is frozen yet: every public type stays `@Incubating` until the 1.0 freeze (D-90).

### Upgrading from 0.1

Breaking changes, each under Changed below:

- `Filters` and `Having` are sealed (D-85).
- `Filters.or` and `Having.or` take two or three branches or a `List`, not varargs (D-87).
- `PageSpec` and `ExportOptions` have no public constructor; use `PageSpec.of` or `PageSpec.ofOffset` (D-88).
- `SetterMapper.bind` takes a column of the mapper's own model (D-88).
- `ColumnSet` is `SelectSet`, with `fields()` for `columns()`; the builder's `columns(...)` and the query's `columns()`
  are `select(...)` and `select()`, and `@QueryModel(generateColumnSets)` is `generateSelectSets` (D-94).
- A statement whose binds pass the vendor's limit throws `MQ1307` before it runs (D-80).
- A class model with a `@Join` field needs that field's getter, named as Lombok names it (D-96).
- `VendorProfile.applyStreaming` is replaced by `streamingFetchSize`, and `ProviderSupport` gains
  `resultStream`; a plain `model-query-jpa` application streams by cursor only with `model-query-hibernate` (D-108).
- The `Filters` operators, `FilterMatchers`, `groupBy` and `QuerySpec.groupBy()` take a `ScalarField`: source compiles
  as before, but code compiled against 0.1 must be recompiled (D-115).

Behaviour to know when moving from hand-written Criteria code:

- `LikeMode.CONTAINS`, `STARTS_WITH` and `ENDS_WITH` escape `%`, `_` and the escape character in the value, so a `%`
  in user input is no longer a wildcard. `EXACT` passes the pattern through as given.
- Offset paging and export append the primary key to the order you give as a tie-breaker, so rows with equal sort
  values come back in a stable order; keyset paging appends it as the last keyset column.
- An `Optional<Date>` filter on a `Timestamp` column compiles when the model field is a `Date`, through the built-in
  converter (D-84).

### Added
- `ModelQuery.conditions()` (incubating, D-98, D-101): a read-only view of the filters a built query records in `where`
  and `having`, as `QueryConditions` and `Condition` (kind, column, values as passed, children, label). A filter skipped
  by an empty `Optional` is absent. `conditions().toString()` and the build log list each condition's kind and column
  with every value shown as `?`; only the accessors expose values.
- `Filters.add(String label, ...)` names a custom filter, so it shows in logs and can be matched in a test; a null or
  blank label is `MQ1301`.
- `model-query-test` (incubating, D-98), in the BOM: depends on `model-query-core` and AssertJ only.
  `assertThatQuery(query)` asserts on filters, `having`, order, selection and a fetch plan's selection with no database,
  and `FilterMatchers` has one matcher per `Filters` operator, plus `and(...)` and `custom(label)`. See "Testing queries
  without a database" in the user guide.
  `QueryAssert<M>` is typed by the query's model, so `isOrderedBy` and the selection methods refuse a column of another
  model, and a filter on a join matches only the same join path (D-103).
- `japicmp` runs in `verify` against the baseline release named by `japicmp.baseline`, and fails the build on a
  binary- or source-incompatible change to API; it is skipped while no baseline is set, and ignores `@Incubating`,
  `@EngineFacing` and `jpa.vendor`.
- The user guide is published to GitHub Pages from main.
- `MQ1307`: a statement whose binds only together pass the vendor's `maxBindParameters()` is refused before it runs,
  rather than failing in the database (#6). On a keyset export page or key-first round after a cursor, the message
  names how many of its binds are the cursor's.
- `OrderedColumnConverter`, a `ColumnConverter` that preserves order both ways, and the built-in
  `InstantTimestampConverter` and `DateTimestampConverter` over a `Timestamp` attribute. `Agg.min`, `Agg.max` and
  `Agg.countDistinct` take a column with an ordered converter, and `min` and `max` return the model type; other
  aggregates over a converted column still throw `MQ1408`.
- `OrderedColumnField`, a `ColumnField` subclass for a column with no converter or an `OrderedColumnConverter`:
  `Agg.min`, `max` and `countDistinct` take it, so they no longer compile over a column with any other converter instead
  of throwing `MQ1408` (D-93). `ColumnField` is `sealed`. A variable typed `ColumnField` passed to those three must
  become `OrderedColumnField`; the processor declares generated columns with the narrower type.
- `Filters.or(List)` and `Having.or(List)` with an empty list render `FALSE`, "none of these", as an empty `in`; a
  non-empty list whose branches were all skipped stays skipped (D-92).
- The `MQ1307` bind check runs up front for a keyset page, export page or key-first write round, first one and
  `startAfter` round included: it counts the worst cursor, k(k+1)/2 binds for k keyset keys, so a run never fails after
  rows reached a sink or a round committed (D-82).
- `MQ1308`: a value that its column's converter cannot convert, such as an `Instant` beyond the range of `Timestamp`,
  is refused with a message naming the column; `InstantTimestampConverter` now round-trips the converted value, since
  `Timestamp.from(Instant.MAX)` returned a wrong instant on JDK 21 (D-84).
- `Limit.of(Integer)` takes `null` for "unlimited".
- `SelectSet.isEmpty()`, true when the set selects nothing.
- `DEBUG` logging of each query definition built and each executor call, naming the filters of a query, an update or
  a delete, and `TRACE` logging of each statement's bind count, rows and time, through `System.Logger`; values and
  keys are never logged (D-95, R-INS-05). See Diagnostics §Logging.
- Fetch plans (D-96, D-99, D-100): `FetchPlan` loads, once per page, the children of each model (`@Child`, with a
  generated `ChildField` and an optional `ChildQuery` for filters, order and `maxPerParent`), nested plans through a
  `@Join` (`JoinField`), and caller-supplied `Enricher`s. A query takes one with `fetch(plan)` or `withFetch(plan)`, and
  `list`, `page` (each `CountMode`), `export` and the Spring repository's `findAll`, `findPage` and `export` run it;
  `stream` refuses it. Codes `MQ1701`-`MQ1705`, `MQ2601`-`MQ2605` and `MQ3401`-`MQ3406`. See the user guide's Fetch
  plans page.
- `@Child(through = "path")` loads a many-to-many child that only the parent's entity maps (unidirectional, the inverse
  side, or through a join entity), by joining along an association path from the parent's `@Id` (D-100).
- The processor gives an `Instant` or `Date` field over a `Timestamp` attribute the built-in converter when no
  `converter` is named, so the column filters with values of the field's type; `@Aggregate` `MIN` and `MAX` into such
  a field read through it.
- `ModelQueryException`, the sealed abstract superclass of `ModelQueryDefinitionException`,
  `ModelQueryExecutionException` and `ModelQueryConfigurationException` (the three are `non-sealed`): one catch clause
  handles every library failure and reads its `code()`, and a foreign exception cannot carry an `MqCode` (D-106).
- `assertThatQuery(q).child(field)` in `model-query-test` asserts a fetch plan's child query: its filters, order,
  selection and `maxPerParent`, with the same matchers; a child the plan doesn't load fails, naming the ones it does
  (D-104).
- `MQ3017`: a model whose `root` is generated by another annotation processor is now generated in a later round
  instead of being skipped with no diagnostic; a root that is never generated reports `MQ3017` on the model (D-107).
- `ProviderSupport.tablesOf(emf, entity)`, implemented by `model-query-hibernate` with every table reading the entity
  touches (joined supertables, secondary tables, subclass tables): on MySQL, a bulk write whose `exists(...)` reads a
  second entity that shares one of the root's tables now runs key-first instead of failing with error 1093 (D-109).
- `ModelQueryExecutor.page(q, KeysetSpec)` returns a `KeysetSlice` — one keyset page with the cursors that reach its
  neighbours and no total — built with `KeysetSpec.first/after/before`; its checks report `MQ2207`–`MQ2210` (D-110).
- `ModelQueryRepository.findKeysetPage(q, KeysetSpec, Sort)` exposes that keyset page through Spring Data, passing the
  order and the cursor fingerprint it decides straight through (R-SPR-14).
- String and `@EmbeddedId` primary keys are covered by the TCK on the keyset page, keyset export and primary-key-first
  paging, and a generated `@Join` through a non-key `referencedColumnName` or a Hibernate `@JoinFormula`; the
  hand-written `TableField.as(...).on(...)` join is documented as the replacement for the declined `@Join(on = ...)`
  (D-111). `docs/site/docs/recipes.md` starts the migration recipes with the keyset-key and join cases.
- An application keeps its own `repositoryFactoryBeanClass` and `repositoryBaseClass` by extending
  `ModelQueryRepositoryFactoryBean`: the subclass adds the model-query fragment only to the repositories that declare
  `ModelQueryRepository`, and the custom base class keeps working for all of them, so a repository migrates one at a
  time (R-SPR-02, R-SPR-12, D-50, D-83). The migration recipes add the custom factory bean, a nested-join enricher and
  a composite-key artist-profile enricher (D-111).
- `Filters.in` and `notIn` take a `SubSelect` — one column of another root with its own filters — and `exists` and
  `notExists` take one with an explicit correlation: `Outer.column(...)` lifts a column of the enclosing query's root
  into the sub-select, where `or` and `not` may mix inner and lifted conditions, and a `notIn` never lets a `NULL`
  value of the sub-select empty the result (R-FLT-15, R-FLT-16, R-FLT-17, D-112). A bulk delete whose sub-select reads
  its target table now runs key-first on MySQL (R-WRT-11), and the migration recipes add the sub-select and
  correlated-`exists` cases.
- A `JpaRepositoryFactoryBean` subclass that does not extend `ModelQueryRepositoryFactoryBean` keeps its class, its
  overrides and its `repositoryBaseClass`, and the starter re-registers each of its repositories that declares
  `ModelQueryRepository` with an inner `ModelQueryRepositoryFragmentFactoryBean` as its `customImplementation`,
  through the new `ModelQueryRepositoryFragmentFactoryBean` in `model-query-spring-data`; a definition that already
  sets `customImplementation` fails with `MQ4008`, and `MQ4007` holds on the new route (R-SPR-02, D-113). The
  migration recipes and the Spring guide cover both routes.
- `Enricher.byKeys(lookup).key(key, with)….batchSize(n).reading(columns)` fills several fields of one model from one
  lookup over the distinct non-null keys across models and keys, in first-seen order, in one call per run or one per
  chunk of at most `n`; a null key is skipped, an absent key or a null map value leaves the model as is, a lookup
  returning `null` fails with `MQ2606`, no `key(...)` with `MQ1706` and `batchSize(0)` with `MQ1707`; `byKey` is its
  one-key case, and a `null` lookup result there now throws `MQ2606` too, where it was a `NullPointerException`
  (R-FCH-15, R-FCH-16, D-114). The fetch-plan guide and the migration recipes add the
  four-party delivery, a lookup splitting its keys by a key part, a cache shared with a child's enricher and
  request-time parameters applied with `withFetch`.
- `Expr` builds an `ExpressionField`, a typed, immutable value over one vocabulary's columns that is equal by
  structure: `coalesce`, `nullIf`, `cases`, `plus`/`minus`/`times`/`dividedBy`, `negate`, `concat`, `function` and
  `constant` (SQL definition text). A `ScalarField` is a `ColumnField` or an expression, so every `Filters` operator,
  `Filters.compare` and `groupBy` take either, and `Agg` gains overloads over an expression (`Agg.count` over a
  column included), while an aggregate as a filter operand still does not compile. A converted column (`MQ1501`), a
  null, array, enum, `Date`, `Calendar` or entity value (`MQ1502`), an integral `dividedBy` (`MQ1503`), a CASE
  condition left with no filter or one using `add`/`exists`/a sub-select (`MQ1504`, `MQ1505`), and a non-identifier or
  aggregate function name (`MQ1506`) are refused at the factory; `cases` exposes only `when`, so a CASE with no WHEN
  does not compile; a declared type the provider does not resolve (`MQ1507`) is refused at first resolution, when the
  query is built against a provider. An expression also stands as a selected column, a group key or an order key: it
  is read back by `Row.get`, reused across `select`, `group by` and `order by`, and accepted by offset paging, offset
  export and `primaryKeyFirst`; `keyset()` refuses one with `MQ1208` (D-115, R-COL-20, R-AGG-14, R-PAG-25). A join plan
  selecting one is refused with `MQ1705` (R-FCH-07), and an expression reading a column through a to-many join is
  refused for offset export, keyset paging and `primaryKeyFirst` with `MQ2204` (R-PAG-13). On a grouped query, an
  expression order key with an explicit null precedence renders the portable null key inside `MIN(...)`, with and
  without `model-query-hibernate` (R-COL-12, D-115).
- `FilterMatchers` take a `ScalarField` wherever `Filters` does, and `ModelQuery.conditions()` records a filter over
  an expression with the expression as its `column()`; `toString` and the build log show its values as `?` (R-INS-09).
- The `byte[]` keyset cursor value binds as a parameter instead of being inlined as a hex literal, so R-FLT-08 holds
  for every cursor value (AC-PAG-28).
- `@Computed` and `@Aggregate(expression = ...)` map a model field to an `ExpressionDefinition<Model, FieldType>`: the
  generated constant is an `ExpressionField` named after the field, emitted after every column constant so a definition
  reading `Q<Model>`'s own constants finds them set, and selected by `ALL` and `DEFAULT` unless `@ExcludeFromDefaults`;
  a `@GroupBy` on a `@Computed` field joins `GROUP_KEYS`, and the definition is built from a public `INSTANCE` or a
  visible no-arg constructor (`MQ3018`, `MQ3019`, `MQ3208`, and `MQ3005` when a `@Join` model has a `@Computed` field,
  D-115, R-PROC-21, R-PROC-22, R-GEN-27).
- `docs/site/docs/subqueries-expressions.md` is the reference for `SubSelect`, `in`/`notIn`/`exists`/`notExists` and
  `Outer.column` correlation, the `Expr` vocabulary and where an expression fits, the expression paging rule
  (`MQ1208`, `MQ2204`), `@Computed`/`@Aggregate(expression)` (`MQ3018`, `MQ3019`, `MQ3208`) and the `MQ15xx` factory
  codes. The Spring Boot sample runs the adoption recipes 1, 2, 3, 4 and 8 end to end, one tested HTTP endpoint per
  recipe across its H2, PostgreSQL and MySQL datasources (D-111).

### Changed
- `model-query-spring-boot-starter` is the one dependency of a Spring Boot application: it pulls
  `spring-boot-starter-data-jpa` and `model-query-hibernate`, where Spring was `provided` and an application declared
  four or more artifacts. `model-query-annotations` is needed only in a module without the starter, and the processor
  still goes in `annotationProcessorPaths`. A `ProviderSupport` whose provider library is missing is skipped (D-97).
- Every public annotation, `@EngineFacing` and `ModelQueryProcessor` carries `@Incubating`, as every other public
  type does (D-90).
- The starter's repository factory bean swap copies a `RootBeanDefinition` with `cloneBeanDefinition()` and keeps its
  target type over `ModelQueryRepositoryFactoryBean`; a swapped repository moves to the end of the registration order,
  which changes singleton creation order and `List<Repository>` injection order (D-83).
- The API review for 1.0 (D-85 to D-89) changed the signatures listed under Upgrading from 0.1. No type is frozen:
  every public type stays `@Incubating` and may still break in a minor release (D-90).
- `@EngineFacing` may mark a type: `BuiltQuery`, `RowSelection` and `RenderOptions` carry it, as do `JoinContext.of`
  and `OrderField.toOrders`. They are not API and may change in any release (D-86).
- **Breaking:** `Filters` and `Having` are `sealed`, so they cannot be implemented outside `core` (D-85).
- **Breaking:** `Filters.or` and `Having.or` take two or three branches, or a `List` of them, in place of varargs, so a
  call no longer warns `unchecked generic array creation` and an `or` of one branch does not compile. An `or` whose
  branches are built at run time, or a one-branch `or` kept as is, takes the list (D-87).
- **Breaking:** `PageSpec` is a final class with no public constructor: `PageSpec.of(pageNumber, pageSize)` as before,
  and `PageSpec.ofOffset(offset, pageSize)` in place of `new PageSpec(offset, pageSize)` (D-88).
- **Breaking:** `ExportOptions` is a final class with no public constructor; `defaults()`, `of(int)`, `withLimit` and
  the `pageSize()` and `limit()` accessors are unchanged (D-88).
- **Breaking:** `SetterMapper.bind` takes a `SelectField<M, C>` of the mapper's own model; another model's column,
  which set `null` on every row, no longer compiles (D-88).
- **Breaking:** `ColumnSet` is renamed `SelectSet`, after the `SelectField`s it holds, aggregates included; its
  `columns()` is `fields()`. `ModelQuery.Builder.columns(...)` is `select(...)`, `ModelQuery.columns()` is `select()`,
  `@QueryModel(generateColumnSets)` is `generateSelectSets`, and `MQ1202` names `select(...)` or `fetch(...)`. The
  generated `ALL`, `DEFAULT`, join and `GROUP_KEYS` constants keep their names (D-94).
- `DateTimestampConverter` documents that the `Date` it reads is a `Timestamp`, whose `equals` is asymmetric with a
  plain `Date` (D-89).
- Primary-key-first step-2 batches and bulk-write key chunks hold at most the largest power of two of keys within the
  vendor's limits, so IN-list padding cannot pass them (#6).
- **Breaking:** the processor generates a `JoinField` constant per `@Join` (`CUSTOMER_JOIN`) and a `ChildField` per
  `@Child`, which fetch plans name. A class model's `JoinField` reads the field through its getter (`getCustomer()`,
  as Lombok names it), so a class model with a `@Join` field and no getter no longer compiles; javac reports the call
  in the generated class (D-96).
- `TableField` has `equals` and `hashCode` by its join key, and its `toString` is path-qualified, as in
  `Order.customer.address (INNER)` (D-103). `Enricher.of` is documented as positional, and a `null` element in its
  result is `MQ2602` like a wrong size (D-102).
- **Breaking (vendor SPI):** `VendorProfile.applyStreaming(Query, int)` is replaced by
  `int streamingFetchSize(int requested)`, which decides the size without touching the query (by default, the
  configured size), and `ProviderSupport` gains `<T> Stream<T> resultStream(TypedQuery<T>, int)`, which opens the
  stream with that size and has no default; `model-query-hibernate` does so with `org.hibernate.fetchSize`, and the
  built-in profiles no longer name that hint. Without a `ProviderSupport`, `stream` sets no fetch size and warns once
  per factory that the driver may buffer the whole result (D-108). A plain `model-query-jpa` application on Hibernate
  therefore loses cursor streaming, and gets that warning, until it adds `model-query-hibernate`, which the Spring Boot
  starter already pulls in.
- **Breaking (binary):** the `Filters` operators and `Filters.compare`, the `FilterMatchers` factories, `ModelQuery`'s
  `groupBy` and `QuerySpec.groupBy()` take a `ScalarField` where they took a `ColumnField`. A `ColumnField` is one, so
  source stays compatible, but a caller or a binary compiled against the old parameter descriptors must recompile
  (D-115).

### Fixed
- The starter's repository factory bean swap re-registers each definition, so a repository type-checked before the swap
  no longer starts without `findPage`.
- A model selecting one attribute through two columns, such as an `Instant` and a `Date` field over one `Timestamp`,
  or a plain column beside a converted one, no longer fails with a duplicate-alias error: the attribute is selected
  once and each column reads it through its own converter, on every read path and in a grouped count (R-COL-10).
- Hibernate 7: the default null precedence is read on both 6.6 and 7, and a statement cancelled by the query timeout
  is reported as `QueryTimeoutException` on both.

## [0.1.0] - 2026-10-01

First release.

### Added
- Typed query definitions: `ColumnField`, `TableField`, column sets, immutable `ModelQuery` builders with primary keys
  and customizers, and portable null precedence.
- A filters DSL: comparison, set, string and null filters, `or`, `not`, `when`, `apply`, `exists` and custom filters;
  IN lists are chunked to the vendor's limit.
- Aggregates, `groupBy` and `having`, with checks at build time.
- Column converters, join presence keys, and per-call or by-property-path sorting (`orderedBy`).
- Query engine on JPA: `list`, `page`, `count`, `stream` with a closing contract, and configurable page, fetch and
  query-timeout settings.
- Offset and keyset export, primary-key-first paging and grouped export deduplicated by group key, all with a stable
  order and NULL-key handling.
- Vendor profiles resolved per `EntityManagerFactory` for H2, PostgreSQL and MySQL, with supplied profiles and a
  Hibernate module for dialect detection and null ordering.
- Annotation processor that generates `QModel` classes for class and record models: `@QueryModel`, `@PrimaryKey`,
  `@Join`, `@FilterColumn`, converters, `@Aggregate` and `@GroupBy`, with coded diagnostics (`MQ*`).
- Bulk update and delete (`@Incubating`) driven by the same filters, with generated update models and change sets,
  chunked writes with per-chunk transactions and resume, and `@ValidChanges` field-by-field validation.
- Spring Data `ModelQueryRepository` with `Pageable`/`Sort` adapters, and a Spring Boot starter with
  auto-configuration, per-factory configuration and `modelquery.*` properties.
- Samples: plain JPA on H2, and Spring Boot with H2, PostgreSQL and MySQL datasources.
- A TCK that runs the same cases against H2, PostgreSQL and MySQL.
- A user guide under `docs/site`, a BOM, signed release artifacts and a reproducible build.
- Specification tree under `docs/spec/`, with the routing index at `docs/spec/SPEC.md`.
- Working plan at `docs/plan/mvp-plan.md` and code conventions at `docs/code-conventions.md`.
- RFC process for public API changes (`rfc/`).

### Changed
- **Breaking:** `ExportOptions.pageSize` is an `OptionalInt`; `ExportOptions.defaults()` leaves the page size to
  `ModelQueryConfig.exportPageSize` (1000 by default). Callers of `ExportOptions.of(int)` are unaffected (D-53).
