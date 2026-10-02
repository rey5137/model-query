# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html); the public API may change in any `0.x`
release (`docs/spec/delivery/61-repo-release-governance.md` R-REL-07).

## [Unreleased]

## [0.2.0] - Unreleased

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
- `DEBUG` logging of each query definition built and each executor call, and `TRACE` logging of each statement's bind
  count, rows and time, through `System.Logger`; values are never logged (D-95). See Diagnostics §Logging.
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

### Fixed
- The starter's repository factory bean swap re-registers each definition, so a repository type-checked before the swap
  no longer starts without `findPage`.
- A model selecting one attribute through two columns, such as an `Instant` and a `Date` field over one `Timestamp`,
  or a plain column beside a converted one, no longer fails with a duplicate-alias error: the attribute is selected
  once and each column reads it through its own converter, on every read path and in a grouped count (R-COL-10).

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
