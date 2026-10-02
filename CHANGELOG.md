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
- A statement whose binds pass the vendor's limit throws `MQ1307` before it runs (D-80).

Behaviour to know when moving from hand-written Criteria code:

- `LikeMode.CONTAINS`, `STARTS_WITH` and `ENDS_WITH` escape `%`, `_` and the escape character in the value, so a `%`
  in user input is no longer a wildcard. `EXACT` passes the pattern through as given.
- Offset paging and export append the primary key to the order you give as a tie-breaker, so rows with equal sort
  values come back in a stable order; keyset paging appends it as the last keyset column.
- An `Optional<Date>` filter on a `Timestamp` column compiles when the model field is a `Date`, through the built-in
  converter (D-84).

### Added
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
- The processor gives an `Instant` or `Date` field over a `Timestamp` attribute the built-in converter when no
  `converter` is named, so the column filters with values of the field's type; `@Aggregate` `MIN` and `MAX` into such
  a field read through it.

### Changed
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
- `DateTimestampConverter` documents that the `Date` it reads is a `Timestamp`, whose `equals` is asymmetric with a
  plain `Date` (D-89).
- Primary-key-first step-2 batches and bulk-write key chunks hold at most the largest power of two of keys within the
  vendor's limits, so IN-list padding cannot pass them (#6).

### Fixed
- The starter's repository factory bean swap re-registers each definition, so a repository type-checked before the swap
  no longer starts without `findPage`.

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
