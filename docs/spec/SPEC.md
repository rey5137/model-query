# Model Query — Specification Index

**Model Query** is a Java library for **typed, projection-first queries on top of JPA**. A use case declares the
columns it needs as typed constants, the library selects exactly those columns and maps them into a plain model class —
no entity loading, no persistence context, no N+1 — and runs the query as a page, a count, a stream or an export of
millions of rows with bounded memory, on H2, PostgreSQL and MySQL alike.

> **Model Query is a read path built on JPA Criteria, not a replacement for JPA and not a general SQL builder.** Its
> only writes are filter-driven bulk updates and deletes (M6), which load no entity.

- **Implementation:** Java 17, Maven multi-module, `jakarta.persistence` 3.1+, Hibernate 6.6+ optional extras.
- **Surface (0.1):** `TableField`, `ColumnField`, `ExpressionField`, `AggregateField`, `SubSelect`, `SelectSet`, `ModelQuery`, `Filters`, `Row`,
  `RowMapper`, `ModelQueryExecutor`, `VendorProfile`, an annotation processor generating `QModel` classes.
- **Works without Spring:** the core needs only an `EntityManager`. Spring Data and Boot are separate modules.
- **Open source:** Apache-2.0, published to Maven Central as `io.github.rey5137:model-query-*`.

---

## 1. How to use this spec

`SPEC.md` is the only file meant to be read every time. Everything else is loaded on demand.
Each child file states what it is for in its first five lines, so you can route without reading the body.

### Routing table

| If you are working on… | Read |
|---|---|
| Anything at all | this file (invariants, principles, decisions index) |
| `TableField`, `ColumnField`, `SelectField`, `SelectSet`, `Row`, `RowMapper`, join sharing, null precedence | `api/10` |
| `ModelQuery` builder, primary keys, `afterMap`, `QueryCustomizer`, the executor's API surface | `api/11` |
| The `Filters` DSL: operators, `Optional` skipping, `or`/`not`/`exists`, escaping | `api/12` |
| `Agg`, `AggregateField`, `groupBy`, `having`, grouped-query rules | `api/13` |
| Bulk `update`/`delete`, `ModelUpdate`, `ModelDelete`, change sets | `api/14` |
| `FetchPlan`, `@Child` children, plans through `@Join`, per-page enrichers | `api/15` |
| Reading a query's conditions back, `model-query-test` assertions | `api/16` |
| `list` / `page` / `count` / `stream`: counting, limits, slices, connection lifetime | `engine/20` |
| Offset paging, keyset paging, primary-key-first, the export loop, grouped export | `engine/21` |
| `@QueryModel` and the other annotations, what each one means | `processor/30` |
| What the processor generates and how it reads the entity metamodel | `processor/31` |
| Compile-time diagnostics and their `MQ3xxx` codes | `processor/32` |
| The `VendorProfile` SPI, detection, what the library refuses to abstract | `vendor/40` |
| H2 / PostgreSQL / MySQL behaviour, limits, support tiers | `vendor/41` |
| `ModelQueryRepository`, `Pageable`/`Sort` adapters, starter properties | `integration/50` |
| Test layers, the TCK, SQL snapshots, CI gates | `delivery/60` |
| Module layout, dependency rules, releases, publishing, license, RFCs | `delivery/61` |
| Milestones, 0.1 scope, exclusions, success criteria | `delivery/62` |
| `MQnnnn` codes, exception catalog, glossary | `reference/90` |
| Where a section of the original plan document went | `reference/91` |
| Why a design choice was made; open questions for the owner | `reference/92` |

### Identifier scheme

| Prefix | Meaning | Defined in |
|---|---|---|
| `INV-n` | architectural invariant | this file |
| `P-n` | design principle | this file |
| `D-n` | design decision (resolves a gap or a choice in the original plan) | `reference/92` |
| `Q-n` | open question for the project owner | `reference/92` |
| `R-<AREA>-nn` | binding rule | the owning child file |
| `AC-<AREA>-nn` | testable acceptance criterion | the owning child file |
| `MQnnnn` | stable diagnostic / exception code | `reference/90` |
| `CC-*` | code convention | `docs/code-conventions.md` |
| `§n` | section of the original plan document (moved here; `reference/91` maps it) | `reference/91` |

Areas: `COL` 10 · `QRY` 11 · `FLT` 12 · `AGG` 13 · `WRT` 14 · `FCH` 15 · `EXE` 20 · `PAG` 21 · `PROC` 30 · `GEN` 31 · `DIAG` 32 ·
`VND` 40 · `PRF` 41 · `SPR` 50 · `QA` 60 · `REL` 61 · `RDM` 62 · `ERR` 90.

### Status tags

`0.1` — build now · `0.1-reserved` — the name, signature slot or property exists in 0.1 but does nothing yet ·
`Future` — do not build, do not contradict.

---

## 2. Architectural invariants

These hold for the life of the library. A change that breaks one is an architecture decision, not a patch.

| ID | Invariant |
|---|---|
| **INV-1** | **Queries are read-only.** A query never writes or deletes and never calls `flush()` (the provider's own auto-flush before a query still applies), and nothing the library returns is a managed entity. The only writes are explicit bulk `update`, `delete` and `insert` calls and `persist` (`api/14`, D-14, D-116). A bulk write loads no entity; every write leaves the persistence context flushed and, after a bulk write, by default cleared; `persist` leaves the entity it created detached. |
| **INV-2** | **Projection-first.** A query selects the columns a use case declared and nothing else. Results are plain models built from a result row; no lazy proxy, no entity graph, no N+1. |
| **INV-3** | **A column's type is checked, not trusted.** A `ColumnField`'s Java type matches the entity attribute it reads and the model field it fills — at compile time for generated columns, at first path resolution for hand-written ones (`api/10`). |
| **INV-4** | **An export visits every row, or every group, exactly once,** with memory bounded by one page, on every supported database (`engine/21`). |
| **INV-5** | **Loud over silently wrong.** When a configuration cannot be executed correctly — a keyset over a NULL key, a grouped keyset, a missing primary key, a selected column outside the group-by — the library throws with a message naming the model and column. It never returns a partial or approximate result instead. |
| **INV-6** | **Vendor differences live only behind `VendorProfile`.** No `if (vendor == MYSQL)` outside a profile, and no vendor name in `core` (`vendor/40`). |
| **INV-7** | **Dependencies flow one way:** `annotations` ← `core` ← `jpa` ← (`hibernate`, `spring-data`) ← `spring-boot-starter`, with `processor` depending only on `annotations` and `test` only on `core` (D-98). `core` imports only `jakarta.persistence` and the JDK; `jpa` never imports `org.hibernate` (`delivery/61`). |
| **INV-8** | **Framework-optional.** Every feature is reachable with a plain `EntityManager`. Spring is a convenience layer, never a requirement. |
| **INV-9** | **Definitions are immutable and thread-safe.** `TableField`, `ColumnField`, `AggregateField`, `SelectSet`, `OrderField`, `ModelQuery`, from M6 `ModelUpdate` and `ModelDelete`, and from M10 `ModelInsert`, `ValuesInsert`, `ModelPersist` and `InsertColumns`, can be `static final`; an insert's copy of its rows is shallow, so a mutable value inside a row is the caller's. Per-query state lives only in `JoinContext`, created per build. |
| **INV-10** | **Diagnostic codes are stable:** once released, an `MQnnnn` code is never reused for a different meaning. |

## 3. Design principles

| ID | Principle |
|---|---|
| **P-1** | **Spec first** — this tree defines the library; the implementation never silently becomes the definition. |
| **P-2** | **Make the mistake not compile** — prefer a signature that rejects the wrong call over a runtime check, and a build-time check over a wrong result. |
| **P-3** | **Explicit over inferred** — "no filter" is `Optional.empty()`, never a `null` that happens to be skipped; an empty selection means "none", not "all". |
| **P-4** | **Portable by default** — the default rendering works on every Tier-1 vendor; vendor-specific optimisations are opt-in and behind a profile. |
| **P-5** | **Small surface** — stay on JPA Criteria and the entity mapping. Not a SQL builder, not an ORM, not an entity write path beyond `persist(model)`, which hides the entity (D-116). |
| **P-6** | **One escape hatch, not a fork** — anything out of scope goes through `QueryCustomizer` rather than a parallel API. |
| **P-7** | **Boring dependencies** — no Lombok, no Spring, no Hibernate in `core`; new third-party dependencies need a reason. |

---

## 4. Scope

| Area | 0.1 | 0.1-reserved | Future |
|---|---|---|---|
| Columns | `TableField`, `ColumnField`, `SelectField`, `SelectSet`, converters, filter-only columns | — | computed/SQL-function columns beyond `Agg.of` |
| Models | classes with setters, records, nested `@Join` models as `Optional<T>` | — | interface projections, Kotlin data classes as a first-class case |
| Filters | full DSL: comparison, sets, strings, nulls, column/column, `or`/`not`/`when`/`apply`, `exists` | — | full-text search, JSON path predicates |
| Aggregates | `count`, `countDistinct`, `sum`, `avg`, `min`, `max`, `Agg.of`, `groupBy`, `having` | `window(...)` name | window functions, `ROLLUP`/`CUBE`, sub-query selections |
| Execution | `list`, `page`, `count`, `stream`, `export`; fetch plans with children and per-page enrichers (`api/15`) | — | reactive / `Publisher` results |
| Paging | offset, keyset, keyset page (cursor format not API), primary-key-first, grouped offset | — | keyset over grouped queries, row-value keyset per vendor |
| Vendors | H2, PostgreSQL, MySQL (Tier 1) | `DatabaseVendor` entries for MariaDB, Oracle, SQL Server | Tier-2 MariaDB profile, community profiles |
| Processor | `@QueryModel`, `@PrimaryKey`, `@Column`, `@Join`, `@FilterColumn`, `@Aggregate`, `@GroupBy`, `@ExcludeFromDefaults`, `@Transient`, `@Child` | — | generating from an existing JPA metamodel, IDE plugin |
| Integration | Spring Data repository, Boot starter, `modelquery.*` properties | remote-store properties | Quarkus / Micronaut extensions |
| Writes | — | — | bulk `update`/`delete`, change sets, `@UpdateModel` (M6, `api/14`) |

Full milestone list and exclusions: `delivery/62`.

## 5. Components

| Component | Module | Owner spec | Status |
|---|---|---|---|
| Annotations | `model-query-annotations` | `processor/30` | 0.1 |
| Columns, joins, filters, query definition | `model-query-core` | `api/10..13` | 0.1 |
| Executor, paging, export engine, built-in profiles | `model-query-jpa` | `engine/20..21`, `vendor/41` | 0.1 |
| Hibernate extras (dialect detection, grouped count, null precedence) | `model-query-hibernate` | `vendor/40`, `engine/20` | 0.1 |
| Annotation processor | `model-query-processor` | `processor/31..32` | 0.1 |
| Spring Data repository and adapters | `model-query-spring-data` | `integration/50` | 0.1 |
| Auto-configuration and properties | `model-query-spring-boot-starter` | `integration/50` | 0.1 |
| Vendor conformance suite | `model-query-tck` | `delivery/60` | 0.1 |
| Version alignment | `model-query-bom` | `delivery/61` | 0.1 |
| Bulk writes: `ModelUpdate`, `ModelDelete`, `Changes`, `ChunkTransactions`; `@ValidChanges` | `model-query-core`; `model-query-jpa` | `api/14` | Future (M6) |
| Samples | `samples/plain-jpa`, `samples/spring-boot` | `delivery/62` | 0.1 |

## 6. Architecture decisions (summary)

| Decision | Choice | Ref |
|---|---|---|
| Query foundation | JPA Criteria API over the entity mapping, not generated SQL | P-5, INV-2 |
| Result shape | JPA `Tuple` wrapped as a `Row`, keyed by `SelectField`, mapped by a `RowMapper` | `api/10`, D-2 |
| Selectable abstraction | one sealed `SelectField`; `ColumnField` and `AggregateField` implement it | D-3, `api/13` |
| Nested models | `Optional<NestedModel>`, presence decided by the join's primary key | D-4, `processor/31` |
| "No filter" | `Optional.empty()` skips locally; `null` throws | P-3, `api/12` |
| Negation | `ne`/`notIn` include NULL rows; `not(group)` is plain SQL `NOT` | D-5, `api/12` |
| Aggregates | typed `AggregateField` selections plus `having`, never string expressions | D-6, `api/13` |
| Grouped paging | offset only; keyset refused, because a group has no unique row key | D-7, `engine/21` |
| Deep paging | primary-key-first two-step, clamped to the vendor's bind limits | `engine/21` |
| Streaming safety | the API owns the `Stream` and closes it; no method returns an open one | D-8, `engine/20` |
| Vendor behaviour | one `VendorProfile` SPI, `ServiceLoader`-discovered, Hibernate dialect detection when present | INV-6, `vendor/40` |
| Null precedence | explicit `nullsFirst`/`nullsLast` mean the same on every vendor; portable `CASE WHEN` fallback | D-9, `api/10` |
| Code generation | annotation processor + JavaPoet, isolating and incremental; one file per model | `processor/31` |
| Error codes | `MQ` + 4 digits, grouped by phase | D-10, `reference/90` |
| Writes | filter-driven bulk `CriteriaUpdate`/`CriteriaDelete` with generated change sets; never entity writes | D-14, `api/14` |
| Write parity | a bulk write affects exactly the rows the equivalent read returns | D-17, `api/14` |
| License | Apache-2.0 for code and docs | `delivery/61` |

## 7. Source of truth

| Artifact | Owner |
|---|---|
| API semantics | this spec tree |
| What a query actually selects | the `SelectSet` the caller passed, plus the columns the engine adds for R-PAG-03/04 |
| Whether a row maps to a model | the query's `RowMapper` (`api/10`) |
| Vendor behaviour | the `VendorProfile` for that vendor (`vendor/41`), verified by the TCK |
| Diagnostic codes | `reference/90` |

---

## 8. File tree

```
docs/spec/
  SPEC.md                         this file
  api/
    10-columns-joins.md           TableField, SelectField, ColumnField, SelectSet, Row/RowMapper, joins, null precedence
    11-query-definition.md        ModelQuery builder, primary keys, afterMap, QueryCustomizer, executor surface
    12-filters.md                 Filters DSL, skip semantics, or/not/exists, escaping, IN splitting
    13-aggregates-grouping.md     Agg, AggregateField, groupBy, having, grouped-query rules
    14-bulk-writes.md             ModelUpdate, ModelDelete, change sets, bulk-write correctness (Future, M6)
  engine/
    20-execution.md               list, page, count, stream: counting rules, limits, connection lifetime
    21-paging-export.md           offset, keyset, primary-key-first, the export loop, grouped export
  processor/
    30-annotations.md             every annotation and what it means
    31-generation.md              generated QModel, metamodel reading, records vs classes, nested models
    32-diagnostics.md             compile-time checks and MQ3xxx codes
  vendor/
    40-vendor-spi.md              VendorProfile, detection, what isn't abstracted
    41-tier1-profiles.md          support tiers, H2/PostgreSQL/MySQL behaviour and limits
  integration/
    50-spring.md                  ModelQueryRepository, Pageable/Sort, starter properties
  delivery/
    60-testing-tck.md             unit tests, TCK groups, SQL snapshots, CI gates
    61-repo-release-governance.md modules, dependency rules, branches, releases, publishing, RFCs
    62-roadmap.md                 milestones, 0.1 scope, exclusions, success criteria
  reference/
    90-errors-glossary.md         MQ codes, exception catalog, glossary
    91-coverage-map.md            original plan § → owning file, old R1..R18 → new ids, later plan additions
    92-decisions-questions.md     decisions D-n, open questions Q-n, risks
```
