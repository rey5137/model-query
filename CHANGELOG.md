# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html); the public API may change in any `0.x`
release (`docs/spec/delivery/61-repo-release-governance.md` R-REL-07).

## [Unreleased]

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
  a composite-key user-profile enricher (D-111).
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
  four-actor payment order, a lookup splitting its keys by a key part, a cache shared with a child's enricher and
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
