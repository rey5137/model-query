# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html); the public API may change in any `0.x`
release (`docs/spec/delivery/61-repo-release-governance.md` R-REL-07).

## [Unreleased]

### Added
- The user guide is published to GitHub Pages from main.
- `MQ1307`: a statement whose binds only together pass the vendor's `maxBindParameters()` is refused before it runs,
  rather than failing in the database (#6). On a keyset export page or key-first round after a cursor, the message
  names how many of its binds are the cursor's.

### Changed
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
