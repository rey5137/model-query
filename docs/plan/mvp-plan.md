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
| M1.2 | `SelectField`, `ColumnField`, type checking, `withTable`, `SelectSet` | AC-COL-04, AC-COL-05 |
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
| M4.3 | `@Column(converter)`; `@Join` and nested models: joined constants and `SelectSet`, automatic aliases, `Optional` mapping by the joined key, two-level nesting; `MQ3003`, `MQ3005`..`MQ3007`, `MQ3014`; the database criteria run in `model-query-tck` | AC-PROC-04, AC-PROC-05, AC-GEN-01 (nested pair), AC-GEN-03, AC-GEN-06, AC-GEN-07 |
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

## 7. M6 — Bulk writes

Spec: `api/14`, `processor/30` R-PROC-18, R-PROC-19, `processor/31` §6, `processor/32` `MQ3301`–`MQ3307`, `vendor/40`
R-VND-11, `integration/50` R-SPR-10, R-SPR-11 and the `bulk-write` properties, `reference/90` `MQ1601`–`MQ1609`,
`MQ2501`, `MQ2502`, `MQ4004`. Every new public type is `@Incubating` (D-59). Model: `architect-review` required before
M6.1. It covers the public shapes, the `VendorProfile` method and bulk-write correctness. Questions:
(a) the staged builder types that make `where(...).all()` fail to compile (R-WRT-12), and the generics of `Changes<M>`
and `Assignment`;
(b) where `MQ1608` is checked, since `build()` in `core` sees no metamodel;
(c) where the `PersistenceContextMode` and default chunk-size settings live, since the spec names only the starter
properties;
(d) how the key-first path and the chunk loop reuse the export keyset, the R-PAG-07 clamp and composite OR-expansion
(R-WRT-11, R-WRT-17).
Record each answer as a `D-n`.

| Slice | Contents | Done when |
|---|---|---|
| M6.1 | `model-query-core`: `Changes<M>`, `Assignment` (sealed: value, null, expression), `ModelUpdate`, `ModelDelete` and their staged builders (D-60: `Start` → `primaryKey` → assignments → one row choice → `Options`), `set`/`setNull`/`setExpression`, `keepVersion`/`expectVersion`, `PersistenceContextMode`, `ChunkOptions` (D-62); the build-time checks `MQ1601`–`MQ1604`, the key part of `MQ1605`, `MQ1606`'s `keepVersion` case, `MQ1609`, `MQ1203` for a join root | AC-WRT-04, AC-WRT-08, the `build()` part of AC-WRT-05 |
| M6.2 | `model-query-jpa`: `ModelQueryExecutor.update`/`delete`; `core` write rendering (`buildWrite`, D-63) to one `CriteriaUpdate`/`CriteriaDelete`: no-join tree direct, joined tree in one correlated `EXISTS` over the root (R-WRT-10); first-execution metamodel checks per factory (D-61): `MQ1608`, metamodel `MQ1605`, `MQ1606`; converters and to-one by `getReference` (R-WRT-14); version increment and `expectVersion` (R-WRT-16); `setExpression` ordering (R-WRT-13). Tests on H2 and PostgreSQL only until M6.4 | AC-WRT-01, AC-WRT-05, AC-WRT-09, AC-WRT-11, AC-WRT-19 |
| M6.3 | Refactor first, no behaviour change: `Keyset.ofKey(PrimaryKey)`, `Keys` helper with the pure clamp (D-63). Then `whereKey(s)` with composite keys, dedup and splitting to the vendor's limits counting the write's own binds (R-WRT-08); `MQ2501` (R-WRT-18); flush before, `CLEAR`/`KEEP` after in a `finally` with `ModelQueryConfig.persistenceContextMode`, second-level cache eviction (R-WRT-15, D-62) | AC-WRT-06, AC-WRT-10, AC-WRT-13 |
| M6.4 | The shared keyset write loop (D-63): `buildKeySelect`, stop on the selected-key count, repeated key throws; `VendorProfile.targetTableInSubquery()` (default `false`; H2/PostgreSQL `true`); key-first path with root predicates re-applied, `lockKeys()` (R-WRT-11); M6.2's tests on MySQL | AC-VND-07, AC-WRT-18 |
| M6.5 | Chunked mode on the M6.4 loop: `chunked(ChunkOptions)`, `ModelQueryConfig.bulkWriteChunkSize`, composite OR-expansion, clamp (R-WRT-17); `commitEachChunk`, `ChunkTransactions` on `ModelQueryConfig` with `checkServes`, `MQ4004` before the flush (R-WRT-19, D-62); `ChunkedWriteException` `MQ2502`, `startAfter`, `inDoubtKeys` as model keys (R-WRT-20) | AC-WRT-12, AC-WRT-14, AC-WRT-15 |
| M6.6 | `model-query-annotations` and processor: `@UpdateModel`, `generateChanges`; generated `QOrderPatch` (returning `Builder<E,K,M>`, D-60), `OrderPatchChanges` (model values in `assignments()`) (fluent and JavaBean setters, `isSet`/`unset`/`assignments`), `changes()`, `update(...)`, `from(...)`, `delete()` (R-GEN-19..22); `MQ1607` | AC-GEN-10, AC-GEN-11, AC-WRT-02, AC-WRT-03 |
| M6.7 | Processor diagnostics `MQ3301`–`MQ3307` with compile-testing cases; the AC-DIAG-05 matrix extended | `processor/32` `MQ33xx` cases |
| M6.8 | `@ValidChanges` and its validator in `model-query-jpa` behind optional `jakarta.validation`; processor emits it only when both resolve (R-WRT-21, R-WRT-22, R-GEN-23) | AC-WRT-17, AC-GEN-12, AC-WRT-16 except the Spring part |
| M6.9 | TCK bulk-write group: AC-WRT-01..19 on every Tier-1 vendor, and write/read parity over every Filters fixture as one statement, key-first and chunked | AC-WRT-07, `delivery/60` bulk-write group green |
| M6.10 | `model-query-spring-data`: `update`/`delete` on the repository via `TransactionTemplate` except `commitEachChunk` (R-SPR-10); starter `ChunkTransactions` per factory with `checkServes`, `MQ4004` (R-SPR-11); the `MQ4006` startup check (D-54) covers the `ChunkTransactions` bean | AC-SPR-09 |
| M6.11 | Starter `modelquery.bulk-write.*` properties, checked at startup like the others (`MQ4006`, D-54); Spring Boot sample PATCH endpoint with `@Valid @RequestBody` field errors; `M6` added to the audit's started scope | AC-WRT-16 Spring part, `delivery/62` M6 sample criterion |

**Exit:** `api/14` covered, TCK bulk-write group green on H2, PostgreSQL and MySQL, AC-VND-07, AC-SPR-09,
AC-GEN-10..12, the `MQ33xx` compile-testing cases, and a PATCH endpoint in the Spring Boot sample.

## 8. M7 — 0.1.0

Spec: `delivery/61` R-REL-02, R-REL-07..R-REL-09, R-REL-12, R-REL-13, AC-REL-04, AC-REL-05; `delivery/62` §1 M7, §3;
`vendor/40` R-VND-09 (the vendor notes page); D-76 (docs site built in CI, not deployed; samples keep their names on
disk). Already there from M0: `release.yml` (`v*` tag → Central), the `release` profile (sources, javadoc, GPG,
`central-publishing-maven-plugin`), the BOM, CI's `release-check` job, `CHANGELOG.md`. Model: no `architect-review`;
M7 changes no public API, `INV-*`, module boundary or `VendorProfile`. Publishing the `v0.1.0` tag is the user's step
after the gate, never a slice's.

| Slice | Contents | Done when |
|---|---|---|
| M7.1 | Reproducible build: `project.build.outputTimestamp` in the parent, set by `versions:set` in `release.yml` alongside the version; every build plugin's version pinned (enforcer `requirePluginVersions`); a script under `.github/scripts/` that builds the `release` profile twice into separate local repos and compares the published artifacts' SHA-256, run by a CI job | AC-REL-05 |
| M7.2 | Release dry run: `samples/spring-boot` added to `excludeArtifacts` next to the other sample; a dry-run path for the `release` profile (`-Dcentral.skipPublishing`, signing with a throwaway key generated in the job) and a check script that every published module has a signed main, `-sources` and `-javadoc` jar, that the BOM lists exactly the published modules, and that no sample or the TCK is published. CI's `release-check` job runs it | AC-REL-04 |
| M7.3 | Docs site source (D-76): MkDocs under `docs/site` with `mkdocs.yml`, a pinned `requirements.txt`, and a strict build in CI. Pages: getting started (no Spring, then Spring Boot), models and QModels, queries and `Filters`, paging and export, grouped queries, bulk writes (`@Incubating`), Spring Data and the starter's properties, vendor notes (R-VND-09: the Tier-1 table and each profile's caveats, the MySQL streaming default from Q-2), diagnostics (pointing at the `MQnnnn` codes). Content comes from `api/*`, `engine/*`, `vendor/*`, `integration/*`, written for users; `docs/spec` itself is not published (R-REL-13) | `mkdocs build --strict` green in CI; every page linked from the nav |
| M7.4 | Samples and README: both samples import the BOM and use only generated QModels; `samples/plain-jpa` shows the define-model, generate, page flow in under 20 lines without Spring; `README` cut to the quick start with links into the site (R-REL-13); `CHANGELOG.md` `0.1.0` section from the Conventional Commits since the start, with D-53's hand entry kept; `M7` added to the audit's started scope | `delivery/62` §1 M7 samples, R-REL-08 changelog, AC audit green with M7 started |

**Exit:** AC-REL-04 and AC-REL-05 pass in CI, the docs site builds strictly in CI, both samples build against the BOM,
and the `CHANGELOG.md` `0.1.0` section is ready, so the user can push the `v0.1.0` tag. Q-1 (artifact prefix) and Q-3
(minimum Hibernate) are raised at the gate: the coordinates and the Hibernate floor become public with this release.

## 9. M8 — Hardening → 0.2.0

Spec: `delivery/62` §1 M8; `delivery/61` R-REL-07, R-REL-10, R-REL-11, AC-REL-06; `vendor/41` AC-PRF-02, AC-PRF-03;
`api/12` R-FLT-09, AC-FLT-08; `engine/21` R-PAG-07; `integration/50` R-SPR-02, D-50; D-59 (bulk writes `@Incubating`
until this review); D-81 (MariaDB after 1.0); D-82. Fetch plans, M8.12–M8.16: `api/15`, D-96 (Q-8), reviewed by `architect-review` before the slices; many-to-many children (D-99), `through` in M8.15b after its own `architect-review`. Query inspection and `model-query-test`, M8.17–M8.18: `api/16`, D-98, its `conditions()` shape reviewed by `architect-review` before M8.17 and settled in D-101. Early-adopter feedback is issues #6 and #7 (#7 closed against
D-37) and a private trial (the starter swap, M8.2; `Date` values on `Timestamp` columns, M8.4–M8.5, D-84). RFCs 0001–0003 stay out of M8: they are additive `@Incubating` work after 1.0. Model: `architect-review` at the
gate (public API shape, paging correctness). M8 ships as 0.2.0, not 1.0.0 (D-90): the API changes stay, nothing is
frozen yet, and D-85–D-89 become the plan for the 1.0 freeze. Tagging `v0.2.0` is the user's step after the gate, never
a slice's.

| Slice | Contents | Done when |
|---|---|---|
| M8.1 | Issue #6: R-FLT-09 keeps `IN … OR IN …` for `maxInListSize()` only; a user statement whose binds exceed `maxBindParameters()` is refused before execution with a new code (in `reference/90` first); only library-built key lists (R-PAG-07 step 2) are split across statements, chunked at the largest power of two within the budget; AC-PRF-02/03 reworded and re-cited to R-FLT-09 and the new rule | reworded AC-PRF-03 green on Tier 1 |
| M8.2 | Starter: the repository factory bean swap (R-SPR-02, D-50) re-registers each swapped definition (copy with `ModelQueryRepositoryFactoryBean`, then remove and register under the same name) instead of calling `setBeanClassName`, so a merged definition or an early `JpaRepositoryFactoryBean` instance cached by an earlier type check is dropped; recorded as D-83 | a context test where a post-processor type-checks the repositories before the swap fails before the fix and passes after, with `findPage` working |
| M8.3 | `MQ1307` names the keyset cursor's binds: a keyset page, an export page or a key-first round that carries a cursor (a page or round after the first, or a write's `startAfter`) and passes the bind limit says how many of its binds the cursor adds and that its own filters must drop that many; no bind reserve (D-82) | a test of each path at the limit asserts the message |
| M8.4 | Ordered converters in `core` (D-84): `OrderedColumnConverter<C, F> extends ColumnConverter<C, F>` (strictly order-preserving both ways, so injective); built-in ordered converters `Instant`↔`Timestamp` and `Date`↔`Timestamp` (the `Date` is the `Timestamp` itself, so no precision is lost); `Agg.min`/`max`/`countDistinct` accept a column whose converter is ordered and convert the result with `toModel`, others keep `MQ1408`; `sum`/`avg` keep `MQ1408`; R-COL-14, R-AGG-04 and the `MQ1408` row updated | a `Timestamp` attribute read, filtered (`eq`, `gt`, `lte`, `between`, with sub-millisecond stored values), sorted, keyset-paged and `min`/`max`/`countDistinct`-aggregated through each built-in, green on Tier 1 |
| M8.5 | Processor picks a built-in converter (D-84): a model field of `Instant` or `Date` over a `Timestamp` attribute, with no `converter` given, generates the column with the built-in instead of `MQ3xxx` type mismatch; `@Aggregate` `min`/`max`/`countDistinct` over an ordered-converter field is accepted; R-PROC-07 and `processor/32` updated | compile-testing cases for both pairs and an aggregate over each; a sample model filters a `Timestamp` column with a `Date` |
| M8.6 | API review: an `architect-review` of every public type, deciding per type frozen at 1.0 or still `@Incubating` (bulk writes per D-59, `jpa.spi`), and every signature to change before the freeze; recorded as `D-n`. Review only, no code | every public type has a recorded decision |
| M8.7 | Apply M8.6: signature changes (marked `!`), `@Incubating` removed from the frozen types, CHANGELOG entries | build and TCK green |
| M8.8 | `japicmp` in the parent: excludes `@Incubating`, `jpa.vendor` and `@EngineFacing` (R-REL-10); skipped while no baseline version is set; a fixture that removes a public method of a frozen type fails the check | AC-REL-06 |
| M8.9 | 1.0.0 prep: `CHANGELOG.md` `1.0.0` section, docs site updated for frozen vs `@Incubating` types, `M8` added to the audit's started scope | AC audit green with M8 started |
| M8.10 | 0.2.0 instead of 1.0.0 (D-90): `@Incubating` put back on every type and member M8.7 unmarked (the API changes of M8.7 stay); D-85–D-89 reworded as the planned 1.0 freeze; D-91 records Q-2 (row-by-row stays the MySQL default), D-78 (Hibernate 6.6+ for 0.2, a Hibernate 7 CI leg before deciding), `or(List)` below two branches (R-FLT-01) and `@Aggregate.converter` (deferred, additive); CHANGELOG section, README, docs site and stability page say 0.2.0; `JapicmpExclusionsTest` still proves a removed method of a non-`@Incubating` type fails | build and TCK green; AC audit green |
| M8.11 | Duplicate alias: a model selecting two `ColumnField`s over the same entity attribute (e.g. an `Instant` and a `Date` view of one `Timestamp`) runs on every path (`list`, `page`, keyset, `count`, `export`, aggregates) instead of failing in Hibernate | a TCK case selecting one attribute twice, read and keyset-paged, green on Tier 1 |
| M8.12 | Fetch plans, core (`api/15`, D-96): `FetchPlan`, `ChildField`, `JoinField`, `Enricher` (`of`, `byKey`), `ChildQuery`; `Builder.fetch` and `ModelQuery.withFetch`; the plan's needed columns joined to the selection, re-rooted under joins, counted as selected (R-FCH-02) and left out of `select()`; `MQ1202` reworded; `MQ1701`–`MQ1704` | unit tests of the R-FCH-02 columns, re-rooting equal to the generated join constants, `withFetch`/`orderedBy`, and each `MQ17xx` |
| M8.12b | One-dependency starter (D-97): `model-query-spring-boot-starter` depends on `spring-boot-starter-data-jpa` (compile, replacing the `provided` `spring-boot-autoconfigure` and `spring-data-jpa`) and on `model-query-hibernate`; `model-query-hibernate`'s provider support stays inert when Hibernate is absent; the Spring Boot sample declares only the starter (plus the processor in `annotationProcessorPaths`, which no dependency can add); getting-started, `spring.md` and README say so, and that `model-query-annotations` is needed only in a module without the starter; D-97 recorded; CHANGELOG entry | the sample builds and its tests pass with only the starter declared; a test that the hibernate module's provider support is not picked without Hibernate on the classpath |
| M8.13 | Processor: `@Child` (`key`/`foreignKey` attribute paths as `String[]`, one path each), generated `ChildField` and `JoinField` for records and setter classes, referring to other QModels lazily; `MQ3401`–`MQ3405`; `processor/30..31` updated | golden files for a record, a class, a mutual `@Child`/back-`@Join` pair and a nested model from another module (D-45); a compile-failure case per code |
| M8.14 | Executor child loading: raw keys carried with mapped rows, key rounds through the R-PAG-07 clamp, dedupe per (parent key, child primary key) (D-99), a `foreignKey` crossing the child's collection (many-to-many), `maxPerParent` round cap, plan run only on returned or passed-on rows (after the probe row and grouped dedupe), `stream` refused; `MQ2601`, `MQ2603`–`MQ2605`; `engine/20..21` back-references | AC-FCH-01–04, -07, -08 green on Tier 1, with a case-insensitive collation case for `MQ2604` and a probe-row case |
| M8.15 | Join plans and enrichers: nested plans applied through `JoinField`, enricher order (nested first), `MQ2602` | AC-FCH-05, -06, -09 green on Tier 1, with a LEFT join that found nothing and two aliased joins to one entity |
| M8.15b | `@Child(through)` (D-99, R-FCH-14), after an `architect-review` of re-rooting `ChildQuery` filters: the annotation attribute, processor checks (`MQ3406`, `through` with `foreignKey` is `MQ3401`), generated `ChildField` carrying the path, and the executor's child query rooted at the parent's entity with the child model re-rooted under the join | AC-FCH-11 green on Tier 1, with a unidirectional `@ManyToMany` and a bidirectional one; golden file and compile-failure case |
| M8.16 | Spring, docs, CHANGELOG: repository pass-through (`findAll`, `findPage` in each `CountMode`, `export`), a user-guide page on fetch plans (unloaded children read as empty, unbounded without `maxPerParent`, one snapshot only in a transaction), sample, `api/11` back-reference | AC-FCH-01 through the repository green on Tier 1; AC-FCH-10 covered by M8.13; `japicmp` report reviewed |
| M8.17 | Query inspection, core (`api/16`, D-98): each `Filters` and `having` operator records a `Condition` through the single point that records its predicate (skipped filters record nothing, R-INS-02); `Condition` with its `Kind` enum and `QueryConditions` (where, having) as final classes, `ModelQuery.conditions()` (`@Incubating`, D-101); `add(String label, …)`, `MQ1301` on a blank label; `QueryConditions.toString()` and the D-95 debug log list conditions with values as `?`; `api/12` and `api/13` back-references | AC-INS-01–04 as unit tests in `core` |
| M8.18 | `model-query-test` module (D-98): depends on `core` and AssertJ only, added to the parent and the BOM; `assertThatQuery` (filters, having, order, selection, the fetch plan's selection) and `FilterMatchers` mirroring each `Filters` operator in its value form, plus `and(...)`; its INV-7 rule added to the TCK's `LayeringRules`; `delivery/61` module list, a user-guide page on testing queries without a database (capturing the query from a mocked executor or repository), the Spring Boot sample's service test, CHANGELOG entry | AC-INS-05, -06 green; the sample's test runs with no datasource |

**Exit:** AC-REL-06 passes in CI (skipped until a 1.0 baseline exists), the API review's signature changes are applied,
and the `0.2.0` CHANGELOG section is ready, so the user can push the `v0.2.0` tag.

## 10. M9 — Adoption → 0.2.0

Spec: D-104–D-109 (Q-4, Q-9, Q-11, Q-12, Q-13 resolved), D-85 (the freeze split, amended by D-106), D-86, D-90;
`vendor/40` §2, `vendor/41` §2, `api/14` R-WRT-11, `engine/21` §2, `api/11`, `integration/50`, `api/16` R-INS-06,
`processor/32` R-DIAG-03, `reference/90`; D-111 (the adoption features, the freeze moved to M10, now M11 per D-116). Each slice writes its rules, `AC-*` rows and `MQ` codes into the owning spec
file first, then builds. Model: `architect-review` required before M9.3 (public API, paging correctness), for M9.1
(`VendorProfile` and `ProviderSupport` surface), before M9.11 (sub-queries and correlation, M9.11a) and before M9.13 (expressions, M9.13a). New vendor profiles (MariaDB, D-81) come
after M9, on the M9.1 SPI. Nothing is frozen in M9 (D-111): every new type is `@Incubating`. Tagging `v0.2.0` is the user's step after the gate, never a slice's.

**Examples for every adoption case (D-111).** Each slice that ships a D-111 item adds that item's recipe to a new
user-guide page, `docs/site/docs/recipes.md` ("Migration recipes", in the nav after "Fetch plans"): the adopter's
case stated in one line, the model or repository code, the query, and the SQL shape it renders. Every code block is
copied from a test that runs (the Spring Boot sample for items 1, 2, 3, 4 and 8; the TCK for the rest), and the recipe
names that test. The nine recipes: (1) a custom `JpaRepositoryFactoryBean` and base class next to `ModelQueryRepository`,
migrating one repository at a time; (2) `in`/`notIn` over a sub-query on an entity the model does not map; (3) an
expression in a filter, an aggregate argument, a group key and a selected column (`coalesce`, `CASE`, arithmetic, a
function call); (4) ordering a page by an expression, offset and keyset; (5) a correlated `exists` reading outer
columns, with `or` across inner and outer; (6) an enricher on a nested `@Join` model; (7) a `@Join` through a
non-key `referencedColumnName` and through `@JoinFormula`, and an ad-hoc `TableField.on(...)` join for the `@Join(on)`
case D-111 declines; (8) a cross-datasource artist-profile enricher keyed by a `(artistId, catalogId)` record, reused
across models, chunked; (9) keyset paging and `primaryKeyFirst` over a String and an `@EmbeddedId` key. A slice is not
done until its recipes are in.

Slices run in table order, not id order: M9.2, M9.5 and M9.6 are decided and independent, so they go first and may
run unattended; M9.1 and M9.3a are attended (their reviews may need the user's decision); M9.3–M9.4 may run
unattended once M9.3a is recorded; M9.9 and M9.10 are decided and may run unattended; M9.11a and M9.13a are attended, and the slices after each may run unattended once its decision is recorded.

| Slice | Contents | Done when |
|---|---|---|
| M9.2 | `ModelQueryException` (D-106): abstract, protected constructors, `code()`; the three exceptions extend it; `reference/90` §1, `diagnostics.md`, CHANGELOG | a test catching each kind as `ModelQueryException` and reading its code |
| M9.5 | Child-query assertions (D-104): `QueryAssert.child(ChildField)` returning an assertion over its conditions, order, selection and `maxPerParent`, reading `childLoads()` inside `model-query-test`; `api/16` R-INS-06 and its `AC-INS` row; `testing.md` example | the new `AC-INS` row green; a missing child names the plan's children |
| M9.6 | Processor round deferral (D-107): a model whose root type is not yet generated is retried in a later round and reported with a new `MQ30xx` in the last; R-DIAG-03 documents the `MQ3015`/`MQ3014` order | compile-testing cases: a root generated by a second processor in the same round, and one never generated |
| M9.1 | SPI split (D-108, D-109): `VendorProfile.streamingFetchSize(int)` replaces `applyStreaming`'s hint, the built-in profiles stop naming `org.hibernate.fetchSize`; `ProviderSupport.<T> Stream<T> resultStream(TypedQuery<T>, int)` and `default Set<String> tablesOf(EntityManagerFactory, Class<?>)`, both implemented in `model-query-hibernate`; the once-per-factory `WARN` when no provider support opens the stream; key-first chosen by intersecting tables for a sub-query over a second entity sharing one of the root's tables; `vendor/40`, `vendor/41` §2, `api/14` R-WRT-11 updated; `vendors.md` drops the 1093 gap | a TCK case on MySQL with two entities on one table runs key-first; the fetch size reaches the statement on PostgreSQL; a unit test of the warning |
| M9.3a | Keyset page design: `architect-review` of D-105 (signatures, the cursor's content and fingerprint, `before` with null rules, grouped and fetch-plan queries, the new `MQ` codes); the outcome recorded as `D-n` and written into `engine/21` §2, `api/11`, `integration/50`, `reference/90` and SPEC.md's paging row. Review only, no code | every open point of D-105 has a recorded decision and the new `AC-PAG` rows exist |
| M9.3 | Keyset page in `core` and `jpa`: `KeysetSpec`, `KeysetSlice`, the cursor encoder and decoder (fingerprint check, typed values through converters), `ModelQueryExecutor.page(query, KeysetSpec)` with `after` and `before`, fetch plans run on the slice | the new `AC-PAG` rows green on Tier 1, ties, null precedences and a tampered cursor among them |
| M9.4 | Keyset page in Spring and docs: `ModelQueryRepository` pass-through; `paging-export.md` gets a "Keyset page" section (a REST endpoint passing the cursor) and its current "Keyset paging" heading renamed to keyset export; the Spring Boot sample uses it; CHANGELOG | the repository case green on Tier 1; `mkdocs build --strict` green |
| M9.9 | Keys and joins (D-111 items 7, 9): TCK entities with a String `@Id`, an `@EmbeddedId`, a `@ManyToOne` through `@JoinColumn(referencedColumnName)` on a non-key column and a Hibernate `@JoinFormula`; `keyset()` page, keyset export and `primaryKeyFirst` over the String and `@EmbeddedId` keys; a generated `@Join` through each association, filtered, sorted and fetched; new `AC-PAG` and `AC-COL` rows; `paging-export.md` and the joins page say what is supported; recipes 7 and 9. A failure is fixed if the rule is clear, otherwise reported | the new rows green on Tier 1 |
| M9.10 | Spring and fetch recipes (D-111 items 1, 6): a context test with a `JpaRepositoryFactoryBean` subclass that extends `ModelQueryRepositoryFactoryBean` and a custom `repositoryBaseClass`, holding one repository that extends `ModelQueryRepository` and one that does not; `spring.md` says how to migrate one repository at a time this way; `fetch-plans.md` gets a recipe for an enricher on a nested join model (R-FCH-08); recipes 1 (today's subclass route) and 6, and 8 as it works today (an unchunked `byKey` with a record key) | the context test green; `mkdocs build --strict` green |
| M9.11a | Sub-query design: `architect-review` of D-111 items 1, 2, 5 and 8: a public sub-query type (root, one selected column, filters, correlation to the outer query), `in`/`notIn`/`exists`/`notExists` over it, the outer root in the `exists` lambda and `or` across inner and outer, conditions recorded for inspection (`api/16`) and mirrored in `model-query-test`, the starter adding the fragment to any `JpaRepositoryFactoryBean` subclass (amending R-SPR-02/D-50), the D-111 item 8 enrichers (several keys per row routed to role fields with one deduplicated lookup, chunking, a lookup split by a key part, the null-key skip, values shared across enrichers of one fetch including a child's, request-time parameters; for each: build, or document a workaround and defer), with the adopter's checks as `AC-FCH` rows (one lookup per user type per chunk, a user shared across roles or rows fetched once, a null approver never looked up, the key columns auto-selected); new `MQ` codes; recorded as `D-n` and written into `api/12`, `api/15`, `api/16`, `integration/50`, `reference/90`. Review only, no code | every point has a recorded decision and the new `AC-*` rows exist |
| M9.11 | Sub-queries in `core` and `jpa`: the M9.11a type and filter operators, correlation, the outer root in `exists`, inspection and `model-query-test` mirrors; recipes 2 and 5 | the new `AC-FLT` rows green on Tier 1 |
| M9.12 | Spring and enrichers per M9.11a: the starter's fragment registration for factory bean subclasses, the item 8 enrichers, repository and docs; recipe 1 updated and recipe 8 rewritten as the four-party delivery, on the final API | the new `AC-SPR` and `AC-FCH` rows green on Tier 1 |
| M9.13a | Expression design: `architect-review` of D-111 items 3 and 4, reversing D-27: one expression type (columns, literals, `coalesce`, `CASE`, arithmetic, `concat`, a named function call with a result type), where it plugs in (aggregate argument, group key, filter operand, selected column, order key), its place in the sealed `SelectField`, how `keyset()` and `primaryKeyFirst` treat an expression order (supported or a new `MQ` code), vendor rendering, the processor's annotation surface, inspection; recorded as `D-n` and written into `api/10..13`, `engine/21`, `processor/30`, `reference/90`. Review only, no code | every point has a recorded decision and the new `AC-*` rows exist |
| M9.13 | Expressions in `core` and `jpa`, part 1: the expression type, as a filter operand and an aggregate argument; the `byte[]` keyset cursor value bound (AC-PAG-28); recipe 3 for filters and aggregates | `AC-COL-18`, `AC-COL-19`, `AC-FLT-17` to `AC-FLT-19`, `AC-AGG-14`, `AC-INS-09` and `AC-PAG-28` green on Tier 1 |
| M9.14 | Expressions, part 2: selected columns, group keys and order keys, with the M9.13a paging rule on every paging path; recipe 3 completed, recipe 4 | `AC-COL-20`, `AC-QRY-15`, `AC-AGG-15`, `AC-PAG-26`, `AC-PAG-27` and `AC-FCH-18` green on Tier 1 |
| M9.15 | Processor: the M9.13a annotations for computed fields, expression aggregates and grouping by a computed field; diagnostics | `AC-PROC-13`, `AC-PROC-14` and `AC-DIAG-08`: compile-testing cases for each annotation and each new code |
| M9.16 | Adoption docs and sample: a user-guide page on sub-queries and expressions, the Spring Boot sample covering recipes 1, 2, 3, 4 and 8 end to end (an endpoint each, tested over HTTP), `recipes.md` checked against the nine cases with every code block matching its named test, CHANGELOG entries for M9.9–M9.15 | `mkdocs build --strict` green; the sample's tests pass; all nine recipes present |
| M9.17 | 0.2.0 release prep: `[Unreleased]` folded into the `0.2.0` CHANGELOG section, README, docs site and stability page say 0.2.0, `M9` added to the audit's started scope | AC audit green with M9 started |

**Exit:** the M9 rows' criteria green on Tier 1, nothing frozen, and the `0.2.0` CHANGELOG section ready, so the user
can push the `v0.2.0` tag.

## 11. M10 — Inserts → 0.3.0

Spec: RFC 0004 (accepted, `rfc/0004-bulk-inserts.md`), D-116 (its §9; amends D-14, D-85, P-5), `api/14` (retitled
"Writes", new §10 R-WRT-24…R-WRT-40, `AC-WRT-21`…; R-WRT-15, -18, -19, -20 extended), `processor/30` R-PROC-23,
R-PROC-24, `processor/31` §7, `processor/32` (`MQ35xx`), `vendor/40` R-VND-14, `integration/50` R-SPR-10 and
AC-SPR-09, `reference/90` (`MQ18xx`, `MQ4009`), `reference/92`. Each slice writes its rules, `AC-*` rows and `MQ` codes
into the owning spec file first, then builds. Model: `architect-review` at M10.2a (key typing, RFC 0004 "Resolved" 1;
the `ModelInsert`/`ValuesInsert`/`ModelPersist` generics and stages; the `InsertSupport` and `VendorProfile` surface),
before any public type is written. Nothing is frozen: every new public type and method is `@Incubating` and D-85
exempts it from the 1.0 freeze (D-116). The D-111 adopter migrates onto 0.3.0, not 0.2.0, before M11. Tagging
`v0.3.0` is the user's step after the gate, never a slice's.

M10.1 and M10.2a are attended (their results may need the user's decision); M10.2 onwards may run unattended once
M10.2a's `D-n` is recorded.

| Slice | Contents | Done when |
|---|---|---|
| M10.1 | Vendor spike (RFC 0004 "Resolved" 3): the generator × vendor × conflict matrix on every Tier 1 vendor, as TCK probes on Hibernate 6.6 and 7.x: `IDENTITY`, assigned, pooled sequence, table, UUID; pre-generated ids in insert-values; the temporary-table plan for insert-select with pooled and table generators; `@MapsId`; multi-row `VALUES` on Oracle before and after 23; the update count and the conflict rendering per vendor; `persist` on bytecode-enhanced entities written by field. Results fix R-WRT-26, R-WRT-29, R-WRT-35 and the `MQ1805` allowlist, recorded in D-116; probes that pin vendor behaviour stay as TCK cases | every matrix cell has a recorded result; D-116 final |
| M10.2a | Insert API design: `architect-review` of key typing (`Class<K>` checked at run time or a processor-generated key type), the `ModelInsert`/`ValuesInsert`/`ModelPersist` generics and D-60 stages (`map`/`set`, `where`/`all()`, `onConflict` → `doNothing`/`doUpdate` → `keepVersion`/`anyUniqueKey`, options), `InsertSupport` (INV-7) and the two `VendorProfile` methods (INV-6), against the M10.1 results; recorded as D-117. Review only, no code | every point has a recorded decision |
| M10.2 | Spec and `core` types: `api/14` §10, INV-1/INV-9/P-5 wording, D-116, `MQ18xx` in `reference/90`; `ModelInsert`, `ValuesInsert`, `ModelPersist` and their staged builders over `ColumnField`, with the `build()` checks (`MQ1601`, `MQ1801` set twice / converter mismatch / `lockKeys` / keys with `commitEachChunk`, `MQ1803`), the shallow row copy (INV-9); `ChunkedWriteException` `nextRowIndex()` and in-doubt range | `core` unit tests for every `build()` check green; ArchUnit green |
| M10.3 | Processor: `@InsertModel` (R-PROC-23), one model annotation per type (R-PROC-24), generated `Q<Model>` with `INSERT_COLUMNS` and the `@Incubating` `insert`, `insertFrom`, `persist` builders (`processor/31` §7), `MQ3501`–`MQ3504` | compile-testing cases for the generated shape and each new code green |
| M10.4 | Provider SPI and executor: `ProviderSupport#inserts()`/`InsertSupport` (R-VND-14) implemented by `HibernateProviderSupport` (generator kind, `n` pre-generated keys), `VendorProfile.maxValuesRows()` and `conflictTargetHonoured()` with the built-in overrides, the three `ModelQueryExecutor` methods, first-execution checks (D-61: `MQ1801` attribute types, `MQ1802`, `MQ1805`, `MQ1807`), `MQ4009` before the flush on a provider without the SPI | `AC-WRT` rows for `MQ1802`, `MQ1805`, `MQ1807`, `MQ4009` green on Tier 1 |
| M10.5 | Insert-select: the source select on the read path's `JoinContext`, unchunked and `chunked` key-first over distinct source ids (R-WRT-28), `MQ1806` on overlap, R-WRT-38 context handling, `ChunkedWriteException` with source keys | insert-select `AC-WRT` rows (rows = `list`, joined and to-many sources, chunked once each, `MQ1806`, `CLEAR`/`KEEP`) green on Tier 1 |
| M10.6 | Insert-values: rows per statement from the bind limit (D-80 counting), `maxValuesRows()` and the `chunked` cap, converters and to-one `getReference`, pre-generated keys, `insertReturningKeys` (R-WRT-33), `commitEachChunk` with `nextRowIndex()` | insert-values and keys `AC-WRT` rows green on Tier 1; SQL snapshots per vendor with the version seed |
| M10.7 | Conflict clauses: `onConflict` stages, `doNothing`, `doUpdate` with `setFromRow`/`set`/`setNull`/`where`/`keepVersion`, the mapping unique-key check and R-WRT-13 (`MQ1804`), `conflictTargetHonoured()`/`anyUniqueKey()` (R-WRT-36), duplicate keys (`MQ1808`), on insert-values only (D-116); `doNothing` `MQ1804` where `doNothingRendered()` is false (H2 on Hibernate 6.6); counts pinned per vendor | conflict `AC-WRT` rows green on Tier 1, MySQL `MQ1804` included |
| M10.8 | `persist` (R-WRT-39, R-WRT-40): instantiate, set through the metamodel member, `persist`, `flush`, `getIdentifier`, `detach`; `MQ2501` without a transaction; `MQ1805` for a constructor-only embeddable | `persist` `AC-WRT` rows green on Hibernate and on a second provider if the TCK has one |
| M10.9 | Spring: `ModelQueryRepository.insert`, `insertReturningKeys`, `persist` (R-SPR-10, AC-SPR-09); the Spring Boot sample gains a `POST` endpoint over `persist` and an import endpoint over insert-values with `doNothing`, tested over HTTP | AC-SPR-09 for the three methods green; the sample's tests pass |
| M10.10 | Docs and 0.3.0 release prep: a user-guide page "Inserts" (every code block copied from a test that runs, the test named), the stability page listing the D-116 incubating types, `japicmp` exclusions for the executor and repository methods added to the interface, `[Unreleased]` folded into the `0.3.0` CHANGELOG section, README, docs site and stability page say 0.3.0, `M10` added to the audit's started scope | `mkdocs build --strict` green; `JapicmpExclusionsTest` green; AC audit green with M10 started |

**Exit:** the M10 rows' criteria green on Tier 1, nothing frozen, and the `0.3.0` CHANGELOG section ready, so the user
can push the `v0.3.0` tag.

## 12. M11 — Entity writes → 0.4.0

Spec: RFC 0005 (accepted, `rfc/0005-entity-writes.md`), D-118 (its §6; amends D-14, D-85, D-116, P-5), `api/14`
(new §11 R-WRT-41…R-WRT-49, `AC-WRT-34`…`AC-WRT-39`; R-WRT-01, -15, -16, -17, -39 extended), INV-1 and P-5 wording,
`integration/50` R-SPR-10 and AC-SPR-09, `reference/90` (`MQ1610`…`MQ1612`, `MQ1809`), `reference/92`. Each slice
writes its rules, `AC-*` rows and `MQ` codes into the owning spec file first, then builds. Model: `architect-review` at
M11.1 (RFC 0005 "Unresolved" 1 and 2; the `persist` overload generics; the `WriteAssignment` surface), before any
public type is written. Nothing is frozen: every new public type and method is `@Incubating` and D-85 exempts it from
the 1.0 freeze (D-118). Tagging `v0.4.0` is the user's step after the gate, never a slice's.

M11.1 is attended (its result may need the user's decision); M11.2 onwards may run unattended once M11.1's decisions
are recorded in D-118.

| Slice | Contents | Done when |
|---|---|---|
| M11.1 | Entity-write API design: `architect-review` of `throughEntities()` placement (options stage checked at `build()` with `MQ1610`, or its own stage so `setExpression`, `keepVersion` and `expectVersion` don't compile after it), `WriteAssignment` paths (a string checked at first execution or a typed field), `WriteAssignment`/`WriteKind` and `ModelQueryConfig.writeAssignments` shape, and the `persist` overload returning a model on executor and repository; recorded in D-118 and folded into RFC 0005. Review only, no code. Decided: options stage with `MQ1610` at `build()`; a string path plus `Class<A>` checked on the first write per root per factory; `WriteAssignment`/`WriteKind` in `jpa`; `<R> R persist(ModelPersist<E, ?, ?>, ModelQuery<E, ?, R>)` (a `SelectSet` carries neither root nor mapper) | every point has a recorded decision |
| M11.2 | Spec and API types: `api/14` §11 and the R-WRT-01/-15/-16/-17/-39 extensions, INV-1/P-5 wording, D-118 (and the D-14, D-85, D-116, P-5 amendment notes), `MQ1610`–`MQ1612` and `MQ1809` in `reference/90`; `throughEntities()` and `@EngineFacing entityMode()` on `ModelUpdate` and `ModelDelete` (options and `Resumable` stages) in `core` with the `MQ1610` checks whatever the call order (AC-WRT-37); `WriteAssignment`, `WriteKind` and `ModelQueryConfig.writeAssignments` in `jpa`; the `<R> R persist(ModelPersist<E, ?, ?>, ModelQuery<E, ?, R>)` overload on `ModelQueryExecutor` | `core` unit tests for AC-WRT-37 and `jpa` unit tests for the `WriteAssignment` construction checks green; ArchUnit green |
| M11.3 | Entity-mode update (R-WRT-41, -42, -44, -45, -46, -47): always chunked, the R-WRT-17 key select, one `IN` load per chunk, assignments and change sets through the metamodel member after the converter, to-one `getReference`, flush, `CLEAR` per chunk or `KEEP`, count = rows matched, `OptimisticLockException` as a failed chunk in `ChunkedWriteException`, `commitEachChunk`, `MQ2501` | AC-WRT-34 and AC-WRT-36 green on Tier 1 |
| M11.4 | Entity-mode delete (R-WRT-42, -43): `remove` per loaded entity, cascades `REMOVE` and `orphanRemoval`, `@SQLDelete`, no R-WRT-15 eviction, Javadoc on rows removed beyond the count | AC-WRT-35 green on Tier 1 |
| M11.5 | `persist` returning a model (R-WRT-48): build the `ModelQuery`'s model from the managed entity after the flush and before the detach through the metamodel members and converters (root attributes, embeddable paths, to-one ids without initializing), the read path's mapper, `afterMap` and finisher, `MQ1809` on first execution per factory before any statement for a query clause other than the selection or an unfillable column | AC-WRT-38 green on Hibernate and on a second provider if the TCK has one |
| M11.6 | Write assignments (R-WRT-49): first-execution checks (`MQ1611`, `MQ1612`), the supplier called once per execution, applied per the RFC table to bulk update (chunked and entity mode), insert-values, insert-select, `doUpdate` and `persist`, skipped where the definition sets the attribute, set on the entity before the flush under `persist` and entity mode | AC-WRT-39 green on Tier 1; SQL snapshots per vendor for an update and an insert with an assignment |
| M11.7 | Spring: `ModelQueryRepository.persist(persist, returning)` with its fragment delegate (R-SPR-10, AC-SPR-09), the starter hands every `WriteAssignment` bean to the config per datasource; the Spring Boot sample's `POST` endpoint returns the persisted model and an updated-at column moves to a `WriteAssignment` | AC-SPR-09 for the overload green; a starter test for the beans green; the sample's tests pass |
| M11.8 | Docs and 0.4.0 release prep: "Bulk writes" gains entity mode and write assignments, "Inserts" gains `persist` returning a model (every code block copied from a test that runs, the test named), the stability page lists the D-118 incubating types, `japicmp` exclusions for the overload added to the interfaces, `[Unreleased]` folded into the `0.4.0` CHANGELOG section, README, docs site and stability page say 0.4.0, `M11` added to the audit's started scope | `mkdocs build --strict` green; `JapicmpExclusionsTest` green; AC audit green with M11 started |

**Exit:** the M11 rows' criteria green on Tier 1, nothing frozen, and the `0.4.0` CHANGELOG section ready, so the user
can push the `v0.4.0` tag.

## 13. M12 — Selected fields → 0.5.0

Spec: D-120 (amends D-85 and D-118), `api/10` (new R-COL-21 `contains` and value equality, R-COL-22 `selectedIn`,
`AC-COL` rows), `api/11` R-QRY-05, `processor/30` (annotation table, new R-PROC-25, `AC-PROC` rows), `processor/31`
(new R-GEN-29…R-GEN-31, `AC-GEN` rows), `processor/32` and `reference/90` (`MQ3020`, `MQ3021`, `MQ3302` and `MQ3502`
extended), `delivery/61` (`@Incubating` note). Each slice writes its rules, `AC-*` rows and `MQ` codes into the owning
spec file first, then builds. Model: the design was reviewed by `architect-review` before D-120; none at a slice.
Nothing new is frozen: `@Selected`, `contains` and `selectedIn` are `@Incubating` (D-120). Tagging `v0.5.0` is the
user's step after the gate, never a slice's.

| Slice | Contents | Done when |
|---|---|---|
| M12.1 | Core: `SelectSet.contains(SelectField<M, ?>)` (exact, no converter look-through), `selectedIn(Row)` (subset in set order, `this` when all selected, single-entry memo on a `long[]` mask in a `volatile` immutable holder), set-semantics `equals`/`hashCode` and a `toString` like `[OrderView.id, OrderView.status]`; R-COL-21/22 | `core` unit tests green: a scoped `RowSelection`, more than 64 fields, concurrent different selections, equality ignoring order |
| M12.2 | Processor and annotation: `@Selected` (`FIELD`, `RECORD_COMPONENT`, no members, `@Incubating`); the reader marks it `column=false` and checks the type by qualified name; `MQ3020`, `MQ3021`, `MQ3302` and `MQ3502` extended; the generated private set of every field the mapper reads, declared after all constants and referenced qualified; a record component gets `Q<M>.selected.selectedIn(row)`, a class setter is always called; R-PROC-25, R-GEN-29, R-GEN-30 | compile-testing for every new diagnostic and for record, class and nested emission green |
| M12.3 | Integration and docs: `jpa` tests for a partial select, an engine-added key and a presence key equal to the generated joined constant, a nested LEFT-join miss, a grouped query, keyset export ordered by a filter-only column, fetch-plan `with` keeping the set, and `persist` returning; user guide section (selected vs `NULL`, `@JsonIgnore`, a record `finisher` carries the set over; R-GEN-31), R-QRY-05 names `@Selected`; stability page lists the D-120 incubating members; `[Unreleased]` folded into the `0.5.0` CHANGELOG section, README, docs site and stability page say 0.5.0, `M12` added to the audit's started scope | AC rows green on Tier 1; `mkdocs build --strict` green; `JapicmpExclusionsTest` green; AC audit green with M12 started |

**Exit:** the M12 rows' criteria green on Tier 1, nothing new frozen, and the `0.5.0` CHANGELOG section ready, so the
user can push the `v0.5.0` tag.

## 14. M13 — Field index → 0.6.0

Spec: RFC 0006 (rev 2) and D-121, `api/10` (new R-COL-23 `resolve`, R-COL-24 `only`, `AC-COL` rows), `processor/31`
(new R-GEN-32 `fields()`, R-GEN-33 what the index holds, AC-GEN-21…AC-GEN-24), `reference/90` (`MQ1105`),
`delivery/61` (`@Incubating` note). Each build slice writes its rules, `AC-*` rows and `MQ` code into the owning spec
file first, then builds. Model: `architect-review` at M13.1 (public API shape and the generated-code contract); none
at a build slice. Nothing new is frozen: `FieldIndex` and `fields()` are `@Incubating`. Tagging `v0.6.0` is the user's
step after the gate, never a slice's.

| Slice | Contents | Done when |
|---|---|---|
| M13.1 | Design review: `architect-review` of RFC 0006 rev 2 (the builder's surface, the holder, wildcard generics in `select`/`filter`, key derivation from `ColumnField.propertyPath()` shared with `ModelQuery.resolve`); decisions folded into the RFC, which moves to accepted, and recorded as D-121. Review only, no code | RFC 0006 accepted; D-121 in `reference/92` |
| M13.2 | Core: `FieldIndex<M>` and its builder, keys derived by kind (R-GEN-33 table) through one helper shared with `orderedBy`'s property-path tier, `select`, `filter` (columns, filter-only columns, expressions), `set`, `child`, `resolve` returning `Resolution` (never throws, unknowns in input order, sets merged into `select`), `only` (`MQ1105` naming the key and the index's keys), `names()`; R-COL-23/24, `MQ1105` | `core` unit tests for every `AC-COL` row green: known, unknown, filter-only, set and child names, empty input, `only` narrowing and `MQ1105`, duplicate key from the builder |
| M13.3 | Processor: `fields()` and its holder on every `@QueryModel` (not on update or insert models), constants passed by kind in declaration order, nested `@Join` columns and join sets, `@Child`, `@FilterColumn`, `@Computed`, `@Aggregate`; R-GEN-32/33 | compile-testing for AC-GEN-21, AC-GEN-22 (a field named `fields`) and AC-GEN-24 green; AC-GEN-23 (`fields()` path equals sort property) green in `jpa` |
| M13.4 | Integration and docs: a `jpa` test resolving `?fields=` names into a query whose joins follow the selection, with `@Selected` leaving unselected fields out; user-guide recipe "Client-chosen fields" (whitelist with `only`, `resolve`, a response writer, aliases kept in the app); stability page lists `FieldIndex` and `fields()` as incubating; `[Unreleased]` folded into the `0.6.0` CHANGELOG section, README, docs site and stability page say 0.6.0, `M13` added to the audit's started scope | AC rows green on Tier 1; `mkdocs build --strict` green; `JapicmpExclusionsTest` green; AC audit green with M13 started |

**Exit:** the M13 rows' criteria green on Tier 1, nothing new frozen, and the `0.6.0` CHANGELOG section ready, so the
user can push the `v0.6.0` tag.

## 15. M14 — Freeze → 1.0.0

Spec: D-85 (amended by D-106, D-116 and D-118), D-86, D-111, D-116, D-118. Starts only when the user says the D-111 adopter has migrated onto 0.5.0 or later
and run it in production long enough. Model: `architect-review` at M14.1. Both slices are attended. Tagging `v1.0.0`
is the user's step after the gate, never a slice's.

| Slice | Contents | Done when |
|---|---|---|
| M14.1 | Freeze review: `architect-review` of every public type against D-85, placing the fetch-plan (`api/15`), inspection (`api/16`), M9, adoption, M10 insert and M11 entity-write and M12 selected-field and M13 field-index types frozen or `@Incubating`, with the adopter's production feedback; recorded as `D-n`. Review only, no code | every public type has a recorded placement |
| M14.2 | Apply the freeze: `@Incubating` removed from the frozen types, `Filters` and `Having` `sealed` (D-85), `CHANGELOG.md` `1.0.0` section, docs site and stability page say 1.0.0, `M14` added to the audit's started scope | build and TCK green; `JapicmpExclusionsTest` green; AC audit green with M13 started |

**Exit:** the M14 rows' criteria green on Tier 1 and the freeze applied per D-85 and the M14.1 decision, so the user can
push the `v1.0.0` tag.
