# Model Query — MVP Plan

The working plan: how each milestone breaks into slices. `docs/spec/delivery/62-roadmap.md` owns the milestone
definitions and exit criteria. This file holds what to build, never progress: status is read from git
(`delivery/61` R-REL-06).

---

## 1. M0 — Bootstrap

**Goal:** a build that can host M1 with every gate already wired, so no later milestone has to add CI plumbing.

| Slice | Contents | Done when |
|---|---|---|
| M0.1 | Maven multi-module skeleton, BOM, wrapper, `.editorconfig` | `./mvnw verify` green from a clean checkout |
| M0.2 | ArchUnit layering rules for INV-7, with a deliberately bad fixture proving each fails | AC-REL-02 |
| M0.3 | TCK harness: Testcontainers for H2/PostgreSQL/MySQL, fixture schema, 20 000-row seed, vendor parameterization | CI green on an empty TCK |
| M0.4 | CI workflows: PR gate, nightly matrix, AC audit skeleton | `delivery/60` R-QA-08, R-QA-09 |
| M0.5 | SQL-snapshot harness (`datasource-proxy`) with one reference query | `delivery/60` R-QA-03 |

**Exit:** CI green on an empty TCK; every gate a later milestone needs already runs.

## 2. M1 — Core

Spec: `api/10`, `api/11`, `api/12`, `api/13`. Model: `architect-review` required for the `SelectField` hierarchy and the
`Filters`/`Having` split, since both are public generic signatures (D-3).

| Slice | Contents | Done when |
|---|---|---|
| M1.1 | `TableField`, `JoinContext`, join keys, `as`, `on`, `withParent` | AC-COL-01..03 |
| M1.2 | `SelectField`, `ColumnField`, type checking, `withTable`, `ColumnSet` | AC-COL-04, AC-COL-05 |
| M1.3 | `Row`, `RowMapper`, `SetterMapper`, `OrderField`, null precedence | AC-COL-06, AC-COL-08 without `model-query-hibernate` |
| M1.4 | `ModelQuery` builder, `PrimaryKey`, `afterMap`, `QueryCustomizer` | AC-QRY-01..09 at definition level (see note), AC-COL-09 |
| M1.5 | `Filters`: comparison, sets, strings, nulls, `compare` | AC-FLT-01, 02, 05..07, AC-COL-07, AC-QRY-07 `where` row |
| M1.6 | `Filters`: `or`/`not`/`when`/`apply`, `exists`, join resolution inside `or`, `add` | AC-FLT-03, 04, 09..11 |
| M1.7 | `Agg`, `AggregateField`, `groupBy`, `having`, grouped build-time checks | AC-AGG-01..12, AC-QRY-07 `groupBy` row |

M1.4 tests AC-QRY-03 and AC-QRY-04 on the built selection and mapping. Their paging and `export` halves need the
executor and are owed by M2.3, which also calls the R-QRY-09 phase check on first execution. Likewise M1.7 tests
AC-AGG-09 at build time; that a grouped query with no `primaryKey` exports is owed by M2.6.

**Exit:** every `AC-COL/QRY/FLT/AGG-*` covered by a named test; H2 SQL snapshots committed.

## 3. M2 — Engine

Spec: `engine/20`, `engine/21`. Model: `architect-review` required for the export loop and the keyset predicate builder
(the two places where a bug loses rows silently).

| Slice | Contents | Done when |
|---|---|---|
| M2.1 | `ModelQueryExecutor.create`, `list`, `page`, `count` incl. grouped and collection-join counting | AC-EXE-01..05, AC-EXE-10 |
| M2.2 | `stream` with the closing contract | AC-EXE-06, AC-EXE-07 |
| M2.3 | Offset export: stable order, boundary dedupe, primary-key check, to-many refusal | AC-PAG-01..04, AC-PAG-12, AC-QRY-03/04 export halves |
| M2.4 | Keyset paging: cursors, ties, NULL handling | AC-PAG-05..07 |
| M2.5 | Primary-key-first paging with vendor clamping | AC-PAG-08, AC-PAG-09 |
| M2.6 | Grouped export | AC-PAG-10, AC-PAG-11, AC-AGG-09 export half |

A column selected through a to-many join repeats the primary key on several rows. Decided: key-based paging refuses it
with `MQ2204` (`engine/21` R-PAG-13, AC-PAG-12, owed by M2.3), and `count` counts its rows (`engine/20` R-EXE-04,
AC-EXE-10, owed by M2.1).

**Exit:** TCK green on Tier-1 databases.

## 4. M3 — Vendors

Spec: `vendor/40`, `vendor/41`, plus the vendor halves carried from M1 and M2 (`api/10` R-COL-12/13, `api/12` R-FLT-09,
`engine/20` R-EXE-08/11, `engine/21` R-PAG-05/07). Model: `architect-review` required for the `VendorProfile` surface
and for how vendor limits and null ordering reach `core` without a vendor name there (INV-6, INV-7), decided in M3.1
before any profile is written.

| Slice | Contents | Done when |
|---|---|---|
| M3.1 | `VendorProfile`, `DatabaseVendor`, `NullOrdering`; the H2, PostgreSQL, MySQL and `OTHER` profiles with the `vendor/41` §2 values; `ServiceLoader` discovery; resolution once per `EntityManagerFactory` (explicit config, Hibernate dialect, `DatabaseMetaData`) with the `INFO` log; the ArchUnit rule; the executor holds the resolved profile. Decides the mechanism by which limits and null ordering reach `core`, and whether `GroupedCountStrategy` folds into `VendorProfile` | AC-VND-01..04 |
| M3.2 | Each Tier-1 profile value asserted against the running database, on every Tier-1 version under the full matrix | AC-PRF-01, AC-PRF-06 |
| M3.3 | Vendor limits: `IN`/`NOT IN` chunking in `core` by `maxInListSize()` and `maxBindParameters()`; primary-key-first's step-2 clamp read from the profile in place of D-32's constants; `primary-key-first.batch-size` on `ModelQueryConfig` | AC-FLT-08, AC-PRF-02, AC-PRF-03 |
| M3.4 | Streaming and timeouts: `checkStreamingPreconditions` (`MQ2101`) and `applyStreaming` in `stream`; `mysql.streaming-mode`; `query-timeout` on `ModelQueryConfig` through `applyTimeout` | AC-EXE-08, AC-EXE-09, AC-PRF-04, AC-PRF-05, AC-PRF-08 |
| M3.5 | Null precedence through profiles: `HibernateCriteriaBuilder#sort` in `model-query-hibernate`; no null sort key when the vendor default already matches; `keyset.null-keys=honour-null-precedence` on `ModelQueryConfig` and its engine half, refused under `OTHER` | AC-COL-08, AC-PRF-07, AC-VND-05 |

AC-VND-06 (a Spring-registered profile bean overrides the `ServiceLoader` one) needs the Spring module and is owed by
M5. AC-VND-07 is `Future` (M6).

**Exit:** TCK green on the full nightly matrix.

## 5. M4 — Processor

Spec: `processor/30`, `processor/31`, `processor/32`. Model: the M4.1 `architect-review` is done; its outcome is D-37
(a column carries its `ColumnConverter`), D-38 (a join carries its presence key), D-39 (isolating processor, `CLASS`
retention), D-40 (Lombok in the processor's tests only), D-41 (dotted paths through embedded values) and Q-10.

| Slice | Contents | Done when |
|---|---|---|
| M4.1a | Every non-`Future` annotation of `processor/30` §1 at `CLASS` retention, with `@FilterColumns` and `suffix`; Lombok as a test-only dependency of the processor, with the ban kept elsewhere (D-40); dotted attribute paths in `ColumnField` (D-41) | AC-PROC-01, AC-COL-10 |
| M4.1b | `ColumnConverter`, the converted `ColumnField`, `Row.raw` and the executor's key and cursor reads through it, `MQ1408` (D-37); `TableField.presentBy`, the presence key in the model phases, `MQ1409` (D-38); their criteria added to `api/10` §7 | the new `AC-COL` rows for D-37 and D-38 |
| M4.2 | Processor base: the entity metamodel reader (R-GEN-01..03), flat class and record models (`@PrimaryKey`, `@Column(attribute)` through `@Embedded`, `@Transient`, `@ExcludeFromDefaults`), `ALL`/`DEFAULT`, `MAPPER`, `query()`, `map(Row)` for both shapes, `KEY`, `prefix`/`suffix` and their `-A` options, the collect-every-error reporter (R-DIAG-02, R-DIAG-03) with `MQ3001`, `MQ3002`, `MQ3004`, `MQ3008`..`MQ3010`, `MQ3015`; one file and one originating element per model, and the isolating registration | AC-PROC-02, AC-PROC-03, AC-GEN-01 (class, record), AC-GEN-02, AC-GEN-04, AC-GEN-05, AC-GEN-08, AC-GEN-09, AC-DIAG-02..04 |
| M4.3 | `@Column(converter)`; `@Join` and nested models: joined constants and `ColumnSet`, automatic aliases, `Optional` mapping by the joined key, two-level nesting; `MQ3003`, `MQ3005`..`MQ3007`, `MQ3014`; the database criteria run in `model-query-tck` | AC-PROC-04, AC-PROC-05, AC-GEN-01 (nested pair), AC-GEN-03, AC-GEN-06, AC-GEN-07 |
| M4.4 | `@FilterColumn`: path resolution, join reuse, aliases; a `TableField` for every collection association on the root; `MQ3011`..`MQ3013` | AC-PROC-06..08 |
| M4.5 | `@Aggregate`, `@GroupBy`, `singleGroup`: `AggregateField` constants, `GROUP_KEYS`, the pre-configured `query()`; `MQ3201`..`MQ3205` | AC-PROC-09, AC-PROC-10, AC-GEN-01 (summary) |
| M4.6 | The diagnostic matrix completed (each code with Lombok on and off, class and record where both apply) and checked against `reference/90`; `samples/plain-jpa` moved to generated QModels only | AC-DIAG-01, AC-DIAG-05 |

The generated-code criteria run on H2 only: what a QModel renders is already covered per vendor by the TCK. AC-GEN-10..12
and `MQ3301`..`MQ3307` are `Future` (M6).

**Exit:** compile-testing suite green; `samples/plain-jpa` uses only generated QModels.

## 6. M5 — Spring

Spec: `integration/50`, `vendor/40` R-VND-03 (AC-VND-06), `api/11` R-QRY-14, R-QRY-15. Model: the `architect-review`
is done; its outcome is D-50 (fragment repository), D-51 (one paging method, total `null` when not counted), D-52 (per-call sort
in `core`), D-53 (profiles and defaults on `ModelQueryConfig`) and D-54 (per-factory binding, the `stream`
transaction).

| Slice | Contents | Done when |
|---|---|---|
| M5.0 | `model-query-core`: `SortSpec` and `ModelQuery.orderedBy`, resolving a property against the selected columns, `MQ2301` (matching rules now D-55 and D-58); `ExportOptions` with an optional page size (D-52, D-53) | AC-QRY-13 |
| M5.0b | Sort by model property path: a property on generated columns and `@Join` tables, `named(String)` on `ColumnField` and `TableField`, `orderedBy` matching property path then attribute path, no bare-name match for joined columns (D-55) | AC-QRY-13 |
| M5.1 | `model-query-jpa`: `exportPageSize` and `streamFetchSize` on `ModelQueryConfig`, applied by the executor; `vendorProfiles(...)` ahead of the `ServiceLoader` profiles (R-VND-03, D-53) | AC-QRY-14, AC-SPR-07 |
| M5.2 | `model-query-spring-data`: the `ModelQueryRepository` fragment without `findPage`, its implementation over `ModelQueryExecutor`, `ModelQueryRepositoryFactoryBean`; `stream` in a read-only transaction (R-SPR-01..03, R-SPR-12) | AC-SPR-01, AC-SPR-03 |
| M5.3 | `findPage`: `Pageable` and `Sort` adapters to `PageSpec` and `SortSpec`, null handling, `MQ2301`, `ModelPage` with a `null` total under `NO_COUNT` (R-SPR-04..07) | AC-SPR-04..06 |
| M5.4 | `model-query-spring-boot-starter`: `modelquery.*` properties read into `ModelQueryConfig`, the factory bean set for the default `@EnableJpaRepositories`, `VendorProfile` beans passed to the config, `ModelQueryConfigurer` and `MQ4005`, the startup `WARN` of R-SPR-09 | AC-SPR-08, AC-SPR-10, AC-VND-06 |
| M5.5 | `samples/spring-boot`: one application with three datasources (H2, PostgreSQL, MySQL), one profile per factory, run against Testcontainers; `M5` added to the audit's started scope | AC-SPR-02 |

`update`, `delete`, `ChunkTransactions` (R-SPR-10, R-SPR-11), the two `bulk-write` properties and AC-SPR-09 are `Future`
(M6).

**Exit:** the Boot sample green on H2, PostgreSQL and MySQL; `integration/50` and AC-VND-06 covered, except `Future`
criteria.

## 7. M6–M8

Contents and exit criteria are in `delivery/62` §1. Slice breakdowns are written when the milestone starts, not before —
a slice plan written three milestones early is guesswork.
