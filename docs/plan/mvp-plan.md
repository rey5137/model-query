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
| M13.1 | Design review: `architect-review` of RFC 0006 rev 2; decisions folded into the RFC (rev 3, accepted) and recorded as D-121. Review only, no code | done: RFC 0006 accepted; D-121 in `reference/92` |
| M13.2 | Core: `FieldIndex<M>` and its public `@Incubating` builder (`select`, `filterOnly`, `set`, `joinSet`, `child`; varargs `final` `@SafeVarargs`), keys derived by kind (R-GEN-33 table) through one helper shared with `orderedBy`'s tier 1 (property path, else attribute path), `AggregateField.named` outside `equals` and matched first by the sort's aggregate tier, the duplicate-key rule (equal field kept once, different field `IllegalStateException`), `select`, `filter`, `set`, `child`, `resolve` (R-COL-23: kinds in order, input order, sets expanded in place, distinct children and unknowns, `Resolution` copies), `only` (R-COL-24: narrows sets, `MQ1105` on an unknown name or an emptied set), `names()`, `filterNames()`; R-COL-23/24, `MQ1105` | done: `FieldIndex`, `AggregateField.named`, `MQ1105`; AC-COL-23..26 green in `core` |
| M13.3 | Processor: `fields()` and its lazy holder on every `@QueryModel` (not on update or insert models), constants passed by kind in declaration order, join sets by their `TableField`, nested `@Join` columns, `@Child`, `@FilterColumn`, `@Computed`, `@Aggregate` with `.named(field)`; a shadowed filter-only column left out with warning `MQ3022`; R-GEN-32/33 | done: generated `fields()`, `.named(field)` on every `@Aggregate`, `MQ3022`; AC-GEN-21, AC-GEN-22, AC-GEN-24, AC-GEN-25 and AC-DIAG-11 green in `processor`; AC-GEN-23 green in `tck` (the generated models live there, not in `jpa`) |
| M13.4 | Integration and docs: a `jpa` test resolving `?fields=` names into a query whose joins follow the selection, with `@Selected` leaving unselected fields out; user-guide recipe "Client-chosen fields" (whitelist with `only`, `resolve`, a response writer, the `eqValue` capture helper, children matched by identity, aliases kept in the app); stability page lists `FieldIndex` and `fields()` as incubating; `[Unreleased]` folded into the `0.6.0` CHANGELOG section (noting that `MQ3022` now warns on a 0.5 model with two filter columns on one path, aliased joins included), README, docs site and stability page say 0.6.0, `M13` added to the audit's started scope | done: AC-COL-27 green in `tck` (`tck/sel/ClientChosenFieldsTest`: the generated models live there, not in `jpa`); recipe "Client-chosen fields", stability page, `0.6.0` CHANGELOG section and version, `M13` in the audit's started scope |

**Exit:** the M13 rows' criteria green on Tier 1, nothing new frozen, and the `0.6.0` CHANGELOG section ready, so the
user can push the `v0.6.0` tag.

## 15. M14 — Fewer models → 0.7.0

Spec: RFC 0007 and D-123, D-122 (already on `main`, ships in this release), `engine/20` (new R-EXE-12, `AC-EXE`
rows), `api/11` R-QRY-03 (the new `MQ2203` triggers), `processor/30` (annotation table, new R-PROC-26, `AC-PROC`
row), `processor/31` (new R-GEN-34, `AC-GEN` rows), `processor/32` and `reference/90` (new `MQ2003` and `MQ3505`,
`MQ2203`, `MQ3501`, `MQ3504` and `MQ3015` extended), `integration/50` (new R-SPR-15, AC-SPR-18), `delivery/61`
R-REL-07 (abstract methods on the `@Incubating` interfaces). Each build slice writes its rules, `AC-*` rows and `MQ`
codes into the owning spec file first, then builds; new rows continue from AC-EXE-11, AC-PROC-19, AC-GEN-26 and
AC-DIAG-12. Model: the design was reviewed by `architect-review` before D-123; none at a slice. Nothing new is
frozen: `generateInserts`, its generated members and the six single-row methods are `@Incubating`. Tagging `v0.7.0`
is the user's step after the gate, never a slice's.

| Slice | Contents | Done when |
|---|---|---|
| M14.1 | Engine: `one(q)`, `first(q)` and `one(q, key)` abstract and `@Incubating` on `ModelQueryExecutor`, built in `DefaultModelQueryExecutor` on `list`'s statement. `one`: limit 2, `MQ2003` on a second row before the fetch plan runs, the message naming a predicate-only to-many join and pointing at `Filters.exists`. `first`: limit 1, `orderBy` then the key (group keys for a grouped query) where the `orderBy` doesn't cover it, implicit columns `nullsLast` explicitly; neither is `MQ2203`. `one(q, key)`: key converted as `whereKey`, ANDed with `q`'s filter; `null` key or component, or a wrong component count, `IllegalArgumentException`; keyless or grouped `MQ2203`. Fetch plans and customizers as `list`; any other `ModelQueryExecutor` implementor in the repo (test doubles) gains the three methods; R-EXE-12, R-QRY-03, `MQ2003`, `MQ2203`, R-REL-07 | `jpa` unit tests and the TCK single-row group green on Tier 1: an enricher never sees the extra row, `first` on a nullable non-id `@PrimaryKey` gives the same row on every vendor, grouped `first` by group keys, contradictory filters give empty; `JapicmpExclusionsTest` green |
| M14.2 | Processor: `@QueryModel.generateInserts` (default `false`); on `true` the query model gets `INSERT_COLUMNS`, `insert`, `insertFrom` and `persist` as an `@InsertModel` does (R-GEN-28), each `@Incubating`, over root columns only, leaving out the R-PROC-26 list without a diagnostic and naming each left-out field and why in the `INSERT_COLUMNS` Javadoc; `addKey` and the `insert`/`persist` key type follow the root's id, a non-id `@PrimaryKey` written with `add`; `MQ3505` (grouped, or no writable root column), `MQ3501` only when the model's `@PrimaryKey` fields don't cover an assigned id, `MQ3504` as on an insert model, `INSERT_COLUMNS` reserved under `MQ3015`; `generateChanges` combines; R-PROC-26, R-GEN-34 | compile-testing green for every new and extended diagnostic, the left-out list, a composite id and a non-id `@PrimaryKey`; a TCK case inserting, insert-selecting and persisting through a `generateInserts` query model green on Tier 1 (the generated models live in `tck`) |
| M14.2b | Processor (D-124): `generateChanges` on a query model leaves a to-one column out, whole-entity or converted, sharing `InsertedColumns`' to-one rule, and the change set's Javadoc names it with the reason; `@UpdateModel` unchanged; engine: a whole-entity to-one column reads through a LEFT join of the association, not `root.get`'s inner join; R-PROC-19, R-GEN-19, R-GEN-21, AC-PROC-20, AC-GEN-28 | compile-testing green for AC-PROC-20; TCK AC-GEN-28 green on Tier 1 (`update(Changes.from(view, columns))` over the root non-key columns of a model with a to-one column, and a `NULL`-foreign-key row read through a whole-entity column with `null`); existing change-set golden files unchanged or updated with the reason |
| M14.3 | Spring: `findOne`, `findFirst` and `findByKey` on `ModelQueryRepository`, abstract and `@Incubating`, delegate-only (R-SPR-01); R-SPR-15, AC-SPR-18 (a repository extending `JpaRepository`, `JpaSpecificationExecutor`, `QueryByExampleExecutor` and `ModelQueryRepository` routes each `findOne` to the right one), AC-SPR-09 for the three methods | `spring` tests green, AC-SPR-18 and AC-SPR-09 for the new methods |
| M14.4 | Docs and release: user-guide sections "One model for reading and creating" (`generateInserts`, the left-out list, `MQ3503` points here, a public create endpoint keeps its own `@InsertModel`, foreign keys need an `@InsertModel`, `one(q, persist(...))` doesn't compile for a composite key) and "Reading one row" (`one`, `first`'s tie-breaker, `one(q, key)`, `MQ2003` and `Filters.exists`); stability page lists the D-123 incubating members; `[Unreleased]` folded into the `0.7.0` CHANGELOG section with D-122's fix and the note that a class implementing `ModelQueryExecutor` must add the three methods (`AbstractMethodError` if compiled before 0.7.0); README, docs site and stability page say 0.7.0; `M14` added to the audit's started scope | AC rows green on Tier 1; `mkdocs build --strict` green; `JapicmpExclusionsTest` green; AC audit green with M14 started |

**Exit:** the M14 rows' criteria green on Tier 1, nothing new frozen, and the `0.7.0` CHANGELOG section ready, so the
user can push the `v0.7.0` tag.

## 16. M15 — Insert columns and many keys → 0.8.0

Spec: RFC 0008, D-125 and D-126, `processor/30` (R-PROC-26 extended, new R-PROC-27, `AC-PROC` rows), `processor/31`
R-GEN-34 (the `INSERT_COLUMNS` Javadoc reasons), `processor/32` (new `MQ3506`, `MQ3501` and `MQ3021` extended),
`api/14` (AC-WRT-40), `engine/20` (new R-EXE-13, R-EXE-05 and R-EXE-12 amended, `AC-EXE` rows), `api/11` R-QRY-03
and R-QRY-10, `api/12` R-FLT-09, `api/15` R-FCH-05, `reference/90` (new `MQ2005`, `MQ2003` and `MQ2203` extended),
`integration/50` (R-SPR-15, AC-SPR-19), `delivery/61` (the behaviour changes). Each build slice writes its rules,
`AC-*` rows and `MQ` codes into the owning spec file first, then builds; new rows are the ids RFC 0008 §New ids
names (AC-PROC-21/22, AC-GEN-29, AC-DIAG-13, AC-WRT-40, AC-EXE-14/15, AC-SPR-19). Model: the design was reviewed by
`architect-review` before D-125 and D-126; none at a slice. Nothing new is frozen: `@ExcludeFromInserts`, `byKeys`
and `findAllByKeys` are `@Incubating`. Tagging `v0.8.0` is the user's step after the gate, never a slice's.

| Slice | Contents | Done when |
|---|---|---|
| M15.1 | Processor (D-125): R-PROC-26 also leaves out a non-id root column whose attribute carries `org.hibernate.annotations.Generated` with `writable = false`, an empty `sql` and `INSERT` among its events (`value` when present and not `INSERT`, Hibernate 6, else `event`), read by full name with `getElementValuesWithDefaults`, an unresolved type left written, every timestamp annotation written; `@ExcludeFromInserts` in `model-query-annotations` (`@Incubating`, field and record-component target, `CLASS` retention, no elements), honoured by `INSERT_COLUMNS`, `insert`, `insertFrom` and `persist` while the query members keep the field; the `INSERT_COLUMNS` Javadoc names each left-out field and its reason; `MQ3506` (no `generateInserts`, an `@UpdateModel` or `@InsertModel`, a field R-PROC-26 already leaves out), `MQ3021` on a `@Selected` field, only `MQ3505` on a grouped model or when every column is excluded, `MQ3501`'s excluded-assigned-id message; R-PROC-26, R-PROC-27, R-GEN-34 | compile-testing green for AC-PROC-21, AC-PROC-22 (each `@Generated` shape of Hibernate 6.6 and 7.x, written and left out), AC-GEN-29 and AC-DIAG-13; TCK on Tier 1: AC-WRT-40 (`insert(rows).set(CREATED_AT, now)`) and an excluded `DEFAULT` column through `insert` (default read back) and `persist` (constructor's value); existing golden files unchanged or updated with the reason |
| M15.2 | Engine (D-126): `byKeys(q, keys)` abstract and `@Incubating` on `ModelQueryExecutor`, built in `DefaultModelQueryExecutor`: checks `NullPointerException`, `MQ2203`, key conversion as `whereKey`, then the empty-keys shortcut (no SQL); chunks as R-PAG-07 step 2 without `primaryKeyFirstBatchSize` (R-FLT-09's third key list), each statement in `Phase.MODEL`, no `ORDER BY`; every chunk read and checked (`MQ2003` on a key matching two rows, `MQ2005` on a row matching no requested value) before mapping; rows matched back by converted attribute value, an unmodifiable `Map<K, M>` in first-occurrence key order, keys converting to one value sharing one model with `afterMap` once, the fetch plan run once; an `orderBy` ignored with one `WARNING` per `ModelQuery`, which `count`, `one(q)` and `one(q, key)` now log too when called directly (not `page`'s count, not `first`), both `one` statements rendering no `ORDER BY`; test-double executors gain the method; R-EXE-13, R-EXE-05, R-EXE-12, R-QRY-03, R-QRY-10, R-FLT-09, R-FCH-05, `MQ2003`, `MQ2005`, `MQ2203`, R-REL-07 | `jpa` unit tests and the TCK group green on Tier 1 for AC-EXE-14 (incl. `MQ2005` on a case-insensitive string key on MySQL and SQL Server, and a configured `primaryKeyFirstBatchSize` not changing the chunks) and AC-EXE-15; `JapicmpExclusionsTest` green |
| M15.3 | Spring: `findAllByKeys(q, keys)` on `ModelQueryRepository`, abstract and `@Incubating`, delegate-only (R-SPR-01); R-SPR-15, AC-SPR-19, AC-SPR-09 for the new method | `spring` tests green, AC-SPR-19 and AC-SPR-09 for `findAllByKeys` |
| M15.4 | Docs and release: `models.md` §One model for reading and creating gains the `@Generated` left-out rule, `@ExcludeFromInserts`, the server-time recipe (`insert(rows).set(CREATED_AT, now)`) and `persist`'s constructor value; `queries.md` "Reading many keys" (`byKeys`, key order, missing keys absent, `MQ2005`, the ignored `orderBy` and its `WARNING`); `spring.md` `findAllByKeys` and a user-side `PersistenceExceptionTranslator` on `code()` for `MQ2003`; stability page lists the D-125/D-126 incubating members; `delivery/61` and `[Unreleased]` folded into the `0.8.0` CHANGELOG section naming the behaviour changes (a 0.7.0 model stops writing a non-writable `@Generated` column, a value set on it under `insert` dropped; a `GENERATED ALWAYS AS` column now works; `count` and `one` warn on an `orderBy` and the `one` statements lose their `ORDER BY`); README, docs site and stability page say 0.8.0; `M15` added to the audit's started scope | AC rows green on Tier 1; `mkdocs build --strict` green; `JapicmpExclusionsTest` green; AC audit green with M15 started |

**Exit:** the M15 rows' criteria green on Tier 1, nothing new frozen, and the `0.8.0` CHANGELOG section ready, so the
user can push the `v0.8.0` tag.

## 17. M17 — Docs for what already works

Spec: `docs/plan/litepos-gaps.md` §1 and §3 (M17), `api/10` (new `AC-COL` rows from AC-COL-29), `api/13` (new
`AC-AGG` rows from AC-AGG-16), `api/14` (new `AC-WRT` rows from AC-WRT-41), CC-DOC-01 (every runnable snippet gets the
Plain JPA and Spring tabs). Runs before the freeze, M29, which stays last. No API change; ships in 0.9.0 with M18–M22 (the
docs site still publishes from `main` as it merges). Model: none; the M17.1 cases may each fail, and a failing case is recorded as a gap
in `litepos-gaps.md` and left out of M17.2's docs rather than fixed here.

| Slice | Contents | Done when |
|---|---|---|
| M17.1 | TCK cases, each with its `AC-*` row in the owning spec: a SQL-view root (an `@Immutable` entity over a view created in the TCK schema, and an `@Immutable @Subselect` entity) read, filtered, paged and grouped through a query model; `Expr.function("date_trunc", …, Expr.constant("day"), col)` on PostgreSQL and a per-vendor date-truncation equivalent elsewhere, plus `timezone` with a fixed zone on PostgreSQL; runtime `groupBy(ScalarField...)` on a model whose other `@GroupBy` fields are left unselected (they read `null`) and `groupBy(SelectSet)`; `ModelUpdate.setExpression` incrementing a counter column | the new AC rows green on Tier 1, or each failing case written up as a gap with its vendor and error |
| M17.2 | Docs: `getting-started/plain-jpa.md` and `spring-boot.md` gain a Gradle tab beside Maven (`annotationProcessor`, the BOM through `platform(...)`, the processor being `isolating`); `recipes.md` gains SQL views as roots, functions with literal arguments (`date_trunc`, `timezone`, `jsonb_exists`, and that a bound argument is not supported yet), and a counter update through `setExpression`; `grouped-queries.md` shows runtime `groupBy` as the way to serve several groupings from one model. Every snippet comes from an M17.1 case and has both tabs | `mkdocs build --strict` green; every snippet traceable to a green M17.1 test |

**Exit:** the M17 rows' criteria green on Tier 1 and the docs published from `main`.

## 18. M18 — Row locks on reads

Spec: RFC 0009, D-127, `SPEC.md` INV-1 (one sentence), `api/11` (new R-QRY-17, R-QRY-11's guard), `engine/20` (new
R-EXE-14), `engine/21` (paging and export refuse a lock), `vendor/40` (new R-VND-15), `vendor/41` (the Tier-1
rendering table), `api/14` (`MQ1809`), `reference/90` (new `MQ1209`, `MQ2102`, `MQ2211`, `MQ4010`), `integration/50`
(the repository reads). Each slice writes its rules, `AC-*` rows and `MQ` codes into the owning spec file first, then
builds; new rows are the ids RFC 0009 §New ids names (AC-QRY-16, AC-EXE-16/17, AC-VND-13, AC-PRF-10). Tier 1 only;
MariaDB stays Tier 2. Model: the design was reviewed by `architect-review` before D-127; none at a slice. Everything
new is `@Incubating`. Ships in 0.9.0 with M17 and M19–M22.

| Slice | Contents | Done when |
|---|---|---|
| M18.1 | Core and rendering spike: `RowLock` (`exclusive()`, `exclusive(Wait)`, `shared()`, `shared(Wait)`, `Wait { WAIT, NO_WAIT, SKIP_LOCKED }`, `equals`/`hashCode`), `ModelQuery.Builder#lock`, `ModelQuery#lock()`, `ModelQuery#withLock` (no `null`, `this` for an equal lock, keeps the fetch plan); `MQ1209` for a grouped query at `build()` and in `withLock`; `MQ1205` for a customizer's `distinct` or `having` on a locked query; TCK SQL snapshots of what Hibernate 6.6 and 7.x render for `PESSIMISTIC_WRITE/READ` × wait × joined/unjoined on H2, PostgreSQL 17 and MySQL 8.4; R-QRY-17, R-QRY-11 | `core` unit tests green for AC-QRY-16; the spike snapshots match RFC 0009 §1, §3 and §4, or the slice stops and the RFC is amended before M18.2 |
| M18.2 | Executor and provider: `ProviderSupport#locks()`, `LockSupport` (`rendered`, `apply`) and its Hibernate implementation (`followOnLocking=false`); the R-EXE-14 table (lock applied after `create` in `list`, `list(Limit)`, `first`, `one`, `one(q, key)`, `byKeys` with each chunk ordered by key; refusals at method entry, before any SQL; fetch-plan statements unlocked); `MQ2102`, `MQ2211`, `MQ4010`, `MQ1809`; INV-1's sentence; R-EXE-14, R-VND-15, `engine/21`, `vendor/41` | `jpa` and `hibernate` unit tests and the TCK group green on Tier 1 for AC-EXE-16, AC-EXE-17 (two connections on PostgreSQL and MySQL: blocked, skipped, no-wait error), AC-VND-13 and AC-PRF-10 on both Hibernate lines; `JapicmpExclusionsTest` green |
| M18.3 | Spring and docs: `findAll(q, Limit)`, `findOne`, `findFirst`, `findByKey`, `findAllByKeys` honour the lock, `findPage` and `findKeysetPage` refuse it (`integration/50`); `queries.md` §Row locks; a "claim the next job" recipe with `SKIP_LOCKED` (the out-of-order caveat for `first`, the PostgreSQL transaction-abort and Spring read-only notes, `Filters.exists` instead of a join); stability page lists the D-127 incubating members; `[Unreleased]` CHANGELOG entry. Both tabs (CC-DOC-01) | `spring` tests green; `mkdocs build --strict` green; every snippet traceable to a green test |

**Exit:** the M18 rows' criteria green on Tier 1 and nothing new frozen.

## 19. M19 — Literal filters and bound function arguments

Spec: RFC 0002 (trimmed), D-128, `api/10` (new R-COL-26 `Expr.value`, R-COL-27 column-typed `Expr.constant`,
`MQ1502` widened), `api/12` (new R-FLT-19, field-form `in`/`notIn`; R-FLT-09 counts an expression's binds),
`vendor/40` (the optional `ProviderSupport` constant-rendering capability), `reference/90` (new `MQ1508`; `MQ1509`
only if the escaping spike needs it), `delivery/61`. Each slice writes its rules, `AC-*` rows and `MQ` codes into the
owning spec file first, then builds; new rows are AC-COL-31, AC-COL-32 and AC-FLT-20. Tier 1 only. Model: the design
was reviewed by `architect-review` before D-128; none at a slice. Everything new is `@Incubating`. Ships in 0.9.0
with M17, M18 and M20–M22.

| Slice | Contents | Done when |
|---|---|---|
| M19.1 | Literal filters: `Filters#in`/`notIn` field forms (two and three expressions, same model, no `Optional`, `notIn` includes `NULL` rows); `Expr.constant(ColumnField<M, ?, C> typedBy, C value)` (converter then attribute mapping; `typedBy` in equality, not in `columns()`); the optional `ProviderSupport` capability and its Hibernate implementation (the attribute's JDBC mapping from the metamodel), reached through a `JoinContext` hook; `MQ1508`; compile-testing cases for `var` and a mixed-model call; escaping spike: a `'` and `\` constant round-trips on every Tier-1 vendor (`MQ1509` if MySQL cannot); R-COL-27, R-FLT-19 | `core`, `jpa` and `hibernate` unit tests and the TCK group green on Tier 1 for AC-FLT-20 and AC-COL-32 (a STRING and an ORDINAL enum, an `AttributeConverter`, a `ColumnConverter`); SQL snapshots show the inlined `in ('PENDING','FAILED')` and `status <> 'CLOSED'`; `JapicmpExclusionsTest` green |
| M19.2 | Bound arguments: `Expr.value(C)` (typed by its class), a statement-level parameter registry on `JoinContext`, the executor binding it on every query it creates (list, `count`, offset page, primary-key-first, keyset, grouped and offset export, `exists` and sub-select bodies, CASE conditions, bulk update and delete); `MQ1502` widened (`null`, `Optional`, `Collection`, `Map`); R-FLT-09 chunk sizing counts the expression's binds; cast spike (`Expr.function` casts, e.g. `jsonb`); R-COL-26 | AC-COL-31 green on Tier 1 for each statement path; PostgreSQL groups by `timezone(?, created_at)` and filters by `jsonb_exists(tags, ?)`; snapshots show a bind marker where a constant would be text; the cast spike's outcome recorded in RFC 0002 §Outcome |
| M19.3 | Docs: `queries.md` (literal filters, `Expr.value`, the plan-cache note, the volatile-function note), recipes for an enum `in` filter and a `timezone(?, col)` group, stability page lists the D-128 incubating members, `delivery/61`, `[Unreleased]` CHANGELOG entry. Both tabs (CC-DOC-01) | `mkdocs build --strict` green; spec checks green; every snippet traceable to a green test |

**Exit:** the M19 rows' criteria green on Tier 1 and nothing new frozen.

## 20. M20 — Returning writes and conflict extensions

Spec: RFC 0010, D-129 (amended by D-130 and D-131), `api/14` (new R-WRT-50 returning writes, R-WRT-51 conflict
`setExpression`, R-WRT-52 arbiter predicate and `onConstraint`; R-WRT-34 amended; R-WRT-48's `MQ1809` widened), `api/10`
(new R-COL-28 `Expr.nextval`), `engine/20` (the returning branch at the statement seam), `vendor/40` (new R-VND-17),
`vendor/41` (the Tier-1 returning and conflict table), `reference/90` (new `MQ4011`, `MQ1509`; `MQ1804`, `MQ1809`
widened), `integration/50`, `delivery/61`. Each slice writes its rules, `AC-*` rows and `MQ` codes into the owning spec
file first, then builds; new rows are AC-WRT-42..47, AC-COL-33 and AC-VND-14. Tier 1 only; MariaDB stays Tier 2. Model:
the design was reviewed by `architect-review` before D-129; none at a slice. Everything new is `@Incubating`. Ships in
0.9.0 with M17–M19, M21 and M22.

| Slice | Content | Done when |
|---|---|---|
| M20.1 | Spike, then update and delete returning: the per-line Hibernate adapters (`Hibernate6Mutations`, `Hibernate7Mutations`) chosen by probing, with the declared tested range and its `WARN`; translation with Hibernate's binders, `StatementInspector` and query-space invalidation; multi-table refusal; H2 `FINAL TABLE`/`OLD TABLE`; the first-use SQL check; the ArchUnit rule confining `org.hibernate..internal..`; `ProviderSupport#returning()`, `ReturningSupport`, `VendorProfile#returning(WriteStatement)`; `updateReturning`/`deleteReturning` on the executor; `MQ1809` widened; `MQ4011`; the non-blocking nightly canary job (newest 7.x and next snapshot); R-WRT-50, R-VND-17 | spike snapshots match RFC 0010 §1's table on both Hibernate lines, or the slice stops and the RFC is amended before M20.2; `jpa` and `hibernate` unit tests and the TCK group green on Tier 1 for AC-WRT-42 and AC-VND-14; `JapicmpExclusionsTest` green |
| M20.2 | `insertReturning` (plain and upsert, including H2 over `MERGE`); `ConflictUpdate.setExpression` with `ConflictExpression`; the counter-upsert TCK case on PostgreSQL and H2; R-WRT-51 (MariaDB rows wait for its profile, D-81) | TCK green on Tier 1 for AC-WRT-43 and AC-WRT-44; SQL snapshots show `… RETURNING next_value` on PostgreSQL |
| M20.3 | `onConstraint(name)` (stage without `where`) and the arbiter predicate `onConflict(cols).where(…)` rendered with literals through the translated-SQL path; R-WRT-34 amended; `MQ1804` widened; R-WRT-52 | TCK green on PostgreSQL for AC-WRT-45 against a partial unique index; refusals on H2 and MySQL; snapshot shows `on conflict (sku) where status<>'CLOSED'` |
| M20.4 | `Expr.nextval` in a select only (profile fact `sequences()`, provider renderer through a `JoinContext` hook, `MQ1509`; write positions are M20.5, D-130); repository returning writes (`integration/50`); docs: returning writes, conflict extensions and `nextval` in `queries.md`/recipes, a "claim the next job" recipe (D-131): a locked key read, then `updateReturning` over `whereKeys` on PostgreSQL and H2 7.x, or `update` then `byKeys` on MySQL, in one transaction, stability page lists the D-129 incubating members, `delivery/61`, `[Unreleased]` CHANGELOG entry. Both tabs (CC-DOC-01); R-COL-28, AC-WRT-47 | AC-COL-33 green on Tier 1; AC-WRT-47 green on Tier 1 (two concurrent workers claim disjoint rows; H2 on 6.6 `MQ4010`); `spring` tests green; `mkdocs build --strict` green; every snippet traceable to a green test |
| M20.5 | `setExpression(column, ExpressionField)` at the update, conflict, insert-values and insert-select sites (D-130), restricted to column-free bind-free expressions (new `MQ1613`); per-row `InsertSupport.insertValues` default with computed factories; MQ1509 before any statement on a write; spike first: `HibernateCriteriaBuilder.sql` inside DML `VALUES`/`SET` on 6.6 and 7.4 and H2 `FINAL TABLE` over `MERGE` with `NEXT VALUE FOR`; R-WRT-53; LitePOS row 8 counter upsert with a sequence value | AC-WRT-46 green on Tier 1 on both Hibernate lines; refusals for MQ1509/MQ1613/MQ1609/MQ1610/MQ1802/MQ1804/MQ1809 tested; MySQL MQ1509 with zero statements executed |

**Exit:** the M20 rows' criteria green on Tier 1 and nothing new frozen.

## 21. M21 — Generated-code nullness

Spec: `docs/plan/litepos-gaps.md` (F4), D-135, `processor/30` (new R-PROC-32, the `modelquery.jspecify` option),
`processor/31` (new R-GEN-36, where the generated code carries JSpecify annotations), `processor/32` (new `MQ3027`),
`reference/90`, `docs/site` (`models.md` processor options, NullAway setup), `delivery/61`. New rows are AC-PROC-27,
AC-GEN-31 and AC-GEN-32; ids R-PROC-28..31, R-GEN-35, `MQ3023`..`MQ3026`, AC-PROC-23..26 and AC-GEN-30 stay reserved
for M24 (RFC 0012). Model: no `architect-review`; no public Java API changes, the option is `@Incubating` in the
stability list. Compile-testing only, no TCK. Ships in 0.9.0 with M17–M20 and M22–M24.

D-135 (the design): with `-Amodelquery.jspecify=true` every generated QModel and change-set class is `@NullMarked`;
change-set backing fields, setter and fluent parameters, getters and the private `assignment` value are `@Nullable`
(type-use, so `Outer.@Nullable Inner` and `java.lang.@Nullable String` on qualified names); the private mapper and the
`JoinField`/`ChildField` `with` methods are `@NullUnmarked`, because they pass unselected or NULL values into the
user's model by design (R-GEN-07) and the user's own components decide their nullness; `ColumnField` type arguments
stay unannotated while `core` is unannotated. JSpecify is not a dependency of any model-query artifact: the processor
names the annotations by string, and the user adds `org.jspecify:jspecify`; the option set without JSpecify on the
compile classpath is `MQ3027`. Default off: output with the option unset is byte-identical to today's golden files.

| Slice | Content | Done when |
|---|---|---|
| M21.1 | `modelquery.jspecify` in `@SupportedOptions` and read once into `QModelWriter`, `ChangesWriter` and `FetchFieldWriter`; class-level `@NullMarked`, `@Nullable` change-set positions and `@NullUnmarked` mapper/`with` methods per D-135; `MQ3027` when `org.jspecify.annotations.NullMarked` is not resolvable; an options overload on `ProcessorHarness.compile`; `org.jspecify:jspecify` 1.0.0 in `dependencyManagement` and processor test scope; R-PROC-32, R-GEN-36, `MQ3027` in `processor/32` and `reference/90` | golden files `QOrderView`, `QInvoiceView`, `QCustomerViewWithInvoices`, `OrderPatchChanges` and `QNewOrder` under `golden/jspecify/` equal; every existing golden unchanged; a nested-type and an import-clash case render type-use annotations correctly and compile; AC-PROC-27, AC-GEN-31 green |
| M21.2 | A NullAway compatibility test: the shop, update and insert fixtures compiled with the option on under Error Prone + NullAway (`AnnotatedPackages` covering the fixture and generated packages, JSpecify mode) report no NullAway error, and a deliberately unannotated change-set use in a fixture does (the test proves NullAway ran); Error Prone and NullAway test-scope only, with the surefire `--add-exports` they need; docs: `models.md` "Processor options" (prefix, suffix, jspecify) and a NullAway setup with Maven and Gradle tabs, each snippet traceable to the M21.2 test; stability page and `delivery/61` incubating list; `[Unreleased]` CHANGELOG entry | AC-GEN-32 green; `mkdocs build --strict` green |

**Exit:** the M21 rows' criteria green in the compile-testing suite; nothing new frozen.

## 22. M22 — Scalar foreign key

Spec: `docs/plan/litepos-gaps.md` (F2), Q-14, D-136, `api/10` (new R-COL-29, a to-one read by its id), `api/14`
(R-WRT-48 amended), `jpa` `ProviderSupport` (the foreign-key fact), `reference/90` (`MQ1004`), `processor/30`
(new R-PROC-33; R-PROC-26 amended), `processor/31` (R-GEN-19, R-GEN-21 amended), `processor/32` (new `MQ3028`; `MQ3002`
and `MQ3016` messages), `reference/90`, `docs/site`, `delivery/61`. New rows are AC-COL-34, AC-COL-35, AC-PROC-28 and
AC-GEN-33; the M24 reservations (R-PROC-28..31, R-GEN-35, `MQ3023`..`MQ3026`, AC-PROC-23..26, AC-GEN-30) stay
untouched. Model: an `architect-review` of D-136 before M22.1 and at the gate (processor attribute resolution,
`MQ3016`, a read path's join shape). Ships in 0.9.0 with M17–M21, M23 and M24.

D-136 (the design, amended by the design review): `@Column(attribute = "referrer") Long referrerId` on a query model
reads the to-one's foreign key; its type, or its converter's attribute type, must be the target's single basic id type
(else `MQ3002` naming it). `MQ3002` with the reason also refuses the inverse side, a non-primary-key
`referencedColumnName`, `@JoinColumns`, `@JoinFormula`/`@JoinColumnOrFormula`, `@NotFound`, a derived-identity target,
an `@IdClass`/`@EmbeddedId` target and an embedded-value to-one; `referrer.id` stays `MQ3001`. Every read clause reads
the id through a `LEFT` join and its id, keeping `NULL`-foreign-key rows (a locked read is `MQ2211`); a write's filter
renders the foreign-key column, refused as `MQ1004` before any statement unless the provider reports that the foreign
key references the target's primary key (else Hibernate joins); a returning write sends `referrer.id`, which the
Hibernate module maps to the foreign-key column (else `MQ1809`). A hand-written `ColumnField` of the id type reads the
same (core requires a `BASIC` single id; `MQ1001` names the id type). `generateChanges` and `generateInserts` write the
id form and `from` copies it; the whole-entity form keeps D-124. `MQ3016` names the id form; `@GroupBy` on a
whole-entity to-one is `MQ3028`. Q-20 keeps the no-join read open.

| Slice | Content | Done when |
|---|---|---|
| M22.1 | Spike first: SQL snapshots on Hibernate 6.6 and 7.4 of the `LEFT` join and its id over the TCK's nullable `referrer` (select, filter, order, group, keyset; select and group render the same column on PostgreSQL), and of `from.get(toOne).get(id)` in a bulk update/delete filter and a returning write over the owning, primary-key `referrer`, plus `SkuOrderLineEntity` (non-primary-key `referencedColumnName`), `FormulaLineEntity` (`@JoinFormula`) and `vnd/ins/InsMapsIdEntity` (`@OneToOne`) to record what Hibernate renders; stop and amend D-136 if a write form joins (it did: D-136 amended with `MQ1004`). Then `ColumnField.path` reads a to-one of the id type (or a converter from it) through `leftJoined(ctx).get(id)` in a read and the foreign-key column in a write, in place of `MQ1001` (message names the id type; `BASIC` single id required); a write filter's id form asks `ProviderSupport` whether the foreign key references the target's primary key (the Hibernate support answers from `isReferenceToPrimaryKey()`), else `MQ1004` before any statement; `ReturningColumns` accepts the id form on the root and sends `referrer.id` (R-WRT-48 amended); new inverse `@OneToOne` and `@NotFound` TCK fixtures read through hand-written columns with no row dropped; R-COL-29 in `api/10`, R-WRT-48 in `api/14`, `MQ1004` in `reference/90`, R-QRY-17 note (`MQ2211`); D-44/D-124/Q-14 cross-references | the spike's snapshots are in the slice on both Hibernate lines; AC-COL-34 (NULL-foreign-key rows kept in select, filter, order, offset paging, `count`, keyset with `nullsFirst`/`nullsLast` and `MQ2202` without, primary-key-first two phases with a collection filter, export visiting each row once, an `@Join`ed table, a child root, the inverse and `@NotFound` fixtures; a locked read `MQ2211`; a bulk-write filter and a returning write by the id; a write filter and a returning write on `SkuOrderLineEntity` `MQ1004` and `MQ1809` before any statement) and AC-COL-35 (runtime `groupBy` on the id with a `NULL` group, grouped export, PostgreSQL included) green on Tier 1 on both Hibernate lines |
| M22.2 | Processor: the id form accepted on a query model, converter from the id type included (`MQ3002` naming the id for another type, and with the reason for each excluded mapping, annotations named as strings), `MQ3016` message, `MQ3028` for `@GroupBy` on a whole-entity to-one, `generateChanges` and `generateInserts` include the id form (R-PROC-26's insertable rule) and `from` copies it (AC-GEN-28 amended); R-PROC-33, R-GEN-19/21, R-PROC-26 amended, `MQ3028` in `processor/32` and `reference/90`; a TCK query model reading `referrerId`, writing it through its change set and insert, and returning it from a write; docs: a "foreign key by id" section in `models.md` (locked read `MQ2211`, the naming beside a `@Join` that avoids `MQ3015`, the id form over the D-124 double mapping) and a recipe, JPA and Spring tabs (CC-DOC-01); `[Unreleased]` CHANGELOG entry noting `MQ3028` can break an existing `@GroupBy` | AC-PROC-28 (each exclusion separately, wrong type naming the id, `referrer.id` `MQ3001`, the `MQ3016` and `MQ3028` messages, converter parity with core) and AC-GEN-33 green; every existing golden unchanged; the TCK case green on Tier 1; `mkdocs build --strict` green |

**Exit:** the M22 rows' criteria green on Tier 1 on both Hibernate lines; nothing new frozen.

## 23. M23 — Target vendors and portability check

Spec: `rfc/0011`, D-133, Q-17, Q-18, `SPEC.md` (INV-7 names `model-query-portability`, test scope, on `jpa` only),
`vendor/40` (new R-VND-19 declared targets, R-VND-20 `ProviderSupport#offlineProperties`), `integration/50` (new
R-SPR-17 `modelquery.target-vendors`, §3 table), `api/16` (new R-INS-10..12), `delivery/60` (the INV-5 conformance
run), `delivery/61` (module list, incubating list), `reference/90` (new `MQ4012`, `MQ4013`; confirm both unused,
INV-10), `docs/site` (a "portability" page). New rows are AC-VND-15, AC-SPR-21 and AC-INS-10..12. Model: an
`architect-review` of D-133 before M23.1 (done 2026-10-14, applied to RFC 0011 and D-133; the new module's boundary, the `ProviderSupport` surface, the stub
`DataSource` contract) and at the gate. Ships in 0.9.0 with M17–M22 and M24.

| Slice | Content | Done when |
|---|---|---|
| M23.1 | Spike first, as its own stop point: boot Hibernate 6.6 and 7.4 per H2, PostgreSQL, MySQL and MariaDB offline over the TCK unit (`hibernate.boot.allow_jdbc_metadata_access=false` honoured on both lines, the `jakarta.persistence.database-product-name`/`-major-version` properties, sequences, `@Formula`, schema generation off), confirm the recorder sees a reached statement through Hibernate's exception wrapping, decide how a Spring Boot app supplies its unit; record the findings and amend D-133 if a line cannot boot. Then `@Incubating ProviderSupport#offlineProperties(DatabaseVendor)` (`Optional<Map<String,Object>>`, default empty = `MQ4013`; Hibernate: `allow_jdbc_metadata_access=false`, `hbm2ddl.auto=none`, `hibernate.connection.url` ignored, an explicit `hibernate.dialect` overridden), the vendor-to-product-name map in `jpa.vendor` (reverse of `VendorResolver.vendorOf`), `Target.of(vendor[, "2.3.232"])` with the full version (none: the line's minimum dialect version), the new `model-query-portability` module (on `jpa`; ArchUnit INV-7 rule for `..portability..`: no `org.hibernate`, `org.springframework`, `org.junit`, `org.assertj`, no jpa hibernate/spring/spring.data/test packages or non-public jpa types, no main module depends on it; the INV-6 rule in `LayeringRules` allows the package; BOM entry; `delivery/61` says "test scope" is the consumer POM's), the recording deny-by-default stub `DataSource` (per-call `ThreadLocal` recorder; records and throws at `createStatement`, `prepareStatement`, `prepareCall`, `unwrap` to a vendor type and `DatabaseMetaData` beyond product name/version; `getConnection` during boot `MQ4013`), the offline factory (name form through `Persistence.createEntityManagerFactory(name, overrides)`, class-list form through a container call; overrides `RESOURCE_LOCAL`, stub `DataSource`, shared cache `NONE`, schema generation none; cache keyed on unit or sorted class list, mapping files, overrides, provider, full Target version and `ClassLoader`, booted through a memoised `FutureTask`, closed in a shutdown hook) and `MQ4013`; R-VND-20 in `vendor/40`, R-INS-10 in `api/16`, `MQ4013` in `reference/90`, INV-7 in `SPEC.md`, module list in `delivery/61` | the spike's findings recorded (D-133 amended if a line cannot boot); AC-INS-10 green on both Hibernate lines with no container: zero JDBC calls recorded during boot for H2, PostgreSQL, MySQL and MariaDB, and negative cases (`hibernate.hbm2ddl.auto=create` and an explicit `hibernate.dialect` are overridden or refused with `MQ4013`/`MQ4012`, never booted silently; `MQ4013` for a `ProviderSupport` without `offlineProperties`) |
| M23.2 | Declared targets: `ModelQueryConfig.targetVendors(DatabaseVendor first, DatabaseVendor... more)`, `targetVendors(Collection)` and `targetVendors(String...)` and `Optional<Set<DatabaseVendor>> targetVendors()` (`@Incubating`; an empty `Collection`/`String...` list `MQ4003`, an unknown name `MQ4001` with its text extended), checked against the detected vendor (`ResolvedVendor#detectedVendor` or the explicit `vendor(...)`; `MARIADB` valid while it resolves to `OTHER`, `OTHER` only when listed) in `ModelQueryExecutor.create` and `ModelQueryConfig.checkTargets(EntityManagerFactory)`; `MQ4012` names the factory, the vendor, how it was detected and the list; Spring: `modelquery.target-vendors`, a `BeanPostProcessor#postProcessAfterInitialization` on `EntityManagerFactory` beans (collaborators through `ObjectProvider`; the per-factory config built as `ModelQueryRepositoryFragmentFactoryBean` does, `configurer.configure(shared, emf)`; lazy factories skipped with the `ModelQueryExecutor.create` check as backstop; several failing factories in one `MQ4012`; docs note that migrations may run first and the `@DataJpaTest`-on-embedded-H2 trap), `MQ4006` for an application config with the property set; R-VND-19, R-SPR-17 and the §3 table, `MQ4012`, `MQ4001` text | AC-VND-15 (per factory, detected vendor, explicit vendor outside the list, `MQ4003`, `MQ4001`, `MQ4012` message) and AC-SPR-21 (startup failure with two factories in one `MQ4012`, a lazy factory skipped, `MQ4006`) green on both Hibernate lines |
| M23.3 | Q-17 audit first, as a stop point: which refusals run only after a first statement; answer or narrow Q-17 in `reference/92`, update R-INS-11/12 and the sweep's `persist` default (operations on the limits list are reported "first statement only"). Then `PortabilityCheck.of(unit | List<Class<?>>, config)`, `.properties(Map<String,?>)`, `.mappingFiles(...)`, `.targets(...)` (default `config.targetVendors()`), `.call(Class<E>, Function<ModelQueryExecutor<E>, ?>)`, `.call(String label, Class<E>, Function<...>)` and `.callOutsideTransaction(...)` run per target in a stub transaction with one `EntityManager` per call (`em.flush()` and closing any `AutoCloseable` result before rollback; a lazy result drained by one element), classification by the recorder first then the cause chain, never the top-level type, into `REACHED(sql)`, `REFUSED(code)`, `REFUSED_AFTER_STATEMENT` (an INV-5/spec bug, fails), `NO_STATEMENT` (fails) and `HARNESS_ERROR` (fails); harness sanitising (an explicit `config.vendor(...)` stripped or refused, targets outside a declared `targetVendors()` refused up front, the harness's own `ChunkTransactions`); `.sweep(Class<?>...)` over `static final` definitions, `skip(holder, field, reason)` (reason required), loud failure for no targets, a sweep holder with no definitions and a skip of an unknown or non-definition field, `.assertPortable()` with one line per call x target; R-INS-11, R-INS-12 | AC-INS-12 (sweep, `skip`, report format, a lazy result, `REFUSED_AFTER_STATEMENT` where the lambda swallows the stop then throws, `NO_STATEMENT`, `HARNESS_ERROR`, a `commitEachChunk` call, the loud empty cases) green on both Hibernate lines with no container |
| M23.4 | The TCK conformance run: every vendor-dependent refusal AC of M18-M22 (AC-VND-13, AC-VND-14, AC-WRT-42..45, AC-COL-33, the M22 `MQ1004`/`MQ1809` rows) reproduced under the dry run on every Tier-1 target on both lines, and the converse: on each target that serves a call, the captured SQL equals the TCK's existing SQL snapshot for that vendor and line; `delivery/60` row; docs: a "Portability" page (declared targets, the dry run as a fast net beside a Testcontainers matrix, what it cannot decide, results depending on the line for a version-less target), JPA and Spring tabs (CC-DOC-01), `reference/90`, the incubating list in `delivery/61`; `[Unreleased]` CHANGELOG | AC-INS-11 green on both Hibernate lines with no container; `mkdocs build --strict` green |

**Exit:** the M23 rows' criteria green on both Hibernate lines with no container; nothing new frozen.

## 24. M24 — External model bindings

Spec: `rfc/0012`, D-134, Q-19, `processor/30` (new R-PROC-28..31), `processor/31` (new R-GEN-35), `processor/32` (new
`MQ3023`..`MQ3026`, AC-PROC-23..26), `reference/90` (the four codes; confirm all unused, INV-10), `delivery/61`
(incubating list), `docs/site` (`models.md`, "Models in an API module"). New rows are AC-PROC-23..26 and AC-GEN-30, the
ids M21 and M22 kept reserved. Model: an `architect-review` at the gate (the published incubating annotations, the
`@Computed.value` default and the processor's classpath-read and round contract); no runtime change, INV-7 holds.
Compile-testing only, no TCK. Ships in 0.9.0 with M17–M23.

| Slice | Content | Done when |
|---|---|---|
| M24.1 | Spike first, as its own stop point (RFC 0012 "Unresolved"): on JDK 17 and 21, javac exposes a classpath record's component annotations (`CLASS` retention, `RECORD_COMPONENT` and `FIELD` targets) and a classpath class's field annotations with their values through the `Element` API; whether the processor's Gradle `isolating` declaration still recompiles the persistence module when a bound model changes, or bindings need `aggregating`; recorded as D-140. Then `@BindQueryModel` (`@Incubating`, `CLASS`, repeatable with its container, on a type or `package-info`) mirroring `@QueryModel` plus `model`; the processor collects bindings, reads the model from the classpath and runs the existing reader and checks against the bound root; QModel in the binding's package (prefix/suffix per D-44); `MQ3023` (bound twice per kind, or bound and annotated), `MQ3024` (model, constructor or accessors not public); a `ProcessorHarness` step compiling the model to a class path first; R-PROC-28 (query part), R-PROC-30, R-GEN-35, `MQ3023`, `MQ3024` in `processor/30..32` and `reference/90` | golden `bound/QOrderView` equals `QOrderView` apart from the package line; AC-GEN-30, AC-PROC-23, AC-PROC-24 green; every existing golden unchanged |
| M24.2 | `@BindUpdateModel`, `@BindInsertModel`; `generateChanges`/`generateInserts` on `@BindQueryModel`; `@BindExpression` and `@Computed.value` defaulting to `void.class`; a `@BindExpression` on an `@Aggregate` makes it an expression aggregate; `MQ3025` (valueless `@Computed` unbound or on an annotated model, `@BindExpression` naming an unknown field, a non-computed/aggregate field, or one already naming a definition); R-PROC-28 (rest), R-PROC-29 | bound `OrderPatchChanges`, `QOrderPatch` and `QNewOrder` equal their annotated goldens apart from the package; a bound valueless `@Computed` and an expression `@Aggregate` generate what the annotated forms do; AC-PROC-25 green |
| M24.3 | Nested models of a bound model (D-45): annotated, or bound in the same compilation, the outer QModel naming the nested QModel in its binding's package; round deferral (D-107) across bindings; `MQ3026`; R-PROC-31; Q-19 left open | bound `QCustomerViewWithInvoices` with an annotated and with a bound nested model equals its golden apart from packages; AC-PROC-26 green |
| M24.4 | Docs: `models.md` "Models in an API module" (module layout, a binding, `@BindExpression`, Maven and Gradle tabs, each snippet traceable to an M24 test; the Gradle compile-avoidance limit and its `compileJava` classpath input, D-140), stability page and `delivery/61` incubating list, `reference/90` rows, `[Unreleased]` CHANGELOG entry | `mkdocs build --strict` green |

**Exit:** the M24 rows' criteria green in the compile-testing suite; nothing new frozen.

## 25. M25 — Source models → 0.10.0

Spec: `rfc/0013`, D-143, Q-21, Q-22; new `api/17-query-sources.md` (R-SRC-01..12, AC-SRC-01..16), `SPEC.md` (`SRC 17`
in §1, `SourceQuery` and `WindowField` in INV-9), `api/10` (the source root kind), `processor/30` (new R-PROC-35..39),
`processor/31` (new R-GEN-37, R-GEN-38), `processor/32` (new `MQ3030`..`MQ3039`, AC-PROC-30..39), `vendor/40` (new
R-VND-21, R-VND-22), `vendor/41` (the Tier-1 MySQL floor 8.0 → 8.0.31), the `jpa` SPI (`ProviderSupport#sources()`,
`SourceSupport`, `SourceFeature`), `reference/90` (the `MQ19xx` sub-range; `MQ1901`..`MQ1906`, `MQ2212`, `MQ2701`,
`MQ2702`, `MQ4014`, `MQ4015`; confirm all unused, INV-10), `delivery/61` (incubating list), `docs/site` (a "Source
models" page; the comparison page's Reach row). The acceptance spike is done (`rfc/0013-spike-results.md`). Model: an
`architect-review` at the gate (public API and generics of `SourceQuery`/`WindowField`, paging and export over sources,
the `VendorProfile` and `SourceSupport` surface, INV-6 and INV-7). Every new public type and member is `@Incubating`.
Ships in 0.10.0 with M26–M28 (D-145), before the freeze, M29, which stays last. Spec rows land with the slice that implements them; AC-SRC
and AC-PROC numbers are taken in slice order. The `spike/rfc-0013` branch (unpushed, `model-query-tck/.../tck/spike/`)
is the reference for the Hibernate hooks.

| Slice | Content | Done when |
|---|---|---|
| M25.1 | Processor and core shape, no runtime. New `api/17-query-sources.md` (R-SRC-01..03, R-SRC-10), `SRC 17` in `SPEC.md` §1, `SourceQuery` in INV-9, the source root kind in `api/10`. Core: `@SourceModel(root, shape)` (`shape` reserved for M25.4, default `void.class`), `@Incubating` immutable `SourceQuery<S>`, `TableField`'s source root kind, `MQ1905` for a hand-written `ColumnField` over a source root. Processor: a source model read and validated against its root with the `@QueryModel` checks; its QModel's `source()` builder (`where`, `having`, no `select`, `build()`) in place of `query()` (R-GEN-37); the root kind read from `@QueryModel.root`'s annotation (R-PROC-39, also through `@BindQueryModel.root`); outer column checks by field name and type, single-segment `@Column` paths; the outer `query(SourceQuery<S>)`; `MQ3030`, `MQ3031` (key is the root id or the full non-nullable group set, no to-many reads), `MQ3035`, `MQ3036`, `MQ3038`, `MQ3039`; R-PROC-35, R-PROC-39, R-GEN-37 and the codes in `processor/30..32` and `reference/90` (`MQ19xx` sub-range; every new code confirmed unused, INV-10) | goldens for an ungrouped and a grouped source model and an outer model over each; AC-PROC-30, -31, -35, -36, -38, -39 and the `@SourceModel` positive case green; every existing golden unchanged |
| M25.2 | Plain CTE sources at run time. `jpa.spi`: `@Incubating SourceSupport` (`cte` first; the other hooks land with their slice), `ProviderSupport#sources()`, `SourceFeature` (`CTE`, `RECURSIVE`, `WINDOW`, `UNION`, `INTERSECT`, `EXCEPT`; Javadoc: `true` only for known constants), `VendorProfile#sources(SourceFeature)` (default `false`) and `maxRecursionDepth()` (default `Integer.MAX_VALUE`) with the Tier-1 values; `jpa` builds the CTE body as a standard `CriteriaQuery<Tuple>` aliased by field name and re-registers it on every statement it builds over a source (R-SRC-12: content, count, both primary-key-first steps, each export page, `byKeys`, fetch-plan parent reads); the Hibernate `cte` hook on both lines; `MQ4014` (no `SourceSupport`) and `MQ4015` (missing feature) before any statement, and `PortabilityCheck` reporting both per target; `MQ1906` at the source's `build()` pointing at `Filters.exists`; `MQ1903` for a row lock over a source; the MySQL Tier-1 floor 8.0 → 8.0.31 (`vendor/41`, TCK image, `delivery/60`); R-SRC-03, R-SRC-11, R-SRC-12, R-VND-21, R-VND-22, `MQ1903`, `MQ1906`, `MQ4014`, `MQ4015` in `api/17`, `vendor/40`, `reference/90` | AC-SRC rows for a plain and a grouped CTE (SQL snapshots per vendor and line), a keyset export over a grouped source, and `MQ1906` with the explicit `exists` form returning the hand-written SQL's rows, green on Tier 1 on both lines; `MQ4014`/`MQ4015` unit and dry-run cases green; INV-7 ArchUnit rules unchanged and green |
| M25.3 | Window fields. `@Window(function, argument, partitionBy, orderBy, frame)`, `WindowFunction`, `WindowFrame` (five frames and `NONE`), `WindowField<S, T>` constants not accepted by the source's `where`/`having`/`orderBy` (R-GEN-38), read as plain `ColumnField`s by the outer model; `MQ3032` (paths, result type, order-sensitive without `orderBy`, missing `frame`, `RANGE` on `FIRST_VALUE`/`LAST_VALUE`, any frame on `LAG`/`LEAD`, grouped-source terms neither group key nor aggregate), `MQ3033`; the source key appended ascending to order-sensitive windows only (R-SRC-04; `RANK`, `DENSE_RANK`, `RANGE`/unordered aggregates unchanged); `SourceSupport#window` on Hibernate (`LAG`, `LEAD`, `MIN`, `MAX` through `windowFunction`; no frame ever rendered on `LAG`/`LEAD`); `WindowField` in INV-9; R-PROC-36, R-SRC-04 | AC-PROC-32, -33 and the `@Window` positive case green; AC-SRC rows for each function and frame (SQL snapshots) and the top-N example paged by keyset and by primary-key-first over tied timestamps with no row lost or repeated, green on Tier 1 on both lines |
| M25.4 | Set operations and non-unique sources. `shape`, `@Discriminator`, `MQ3034` (field, type, converter, `EnumType`, temporal type and key match in declaration order; no `@Window` or recursive branch or shape; discriminator placement); `SourceQuery#union`, `unionAll`, `intersect`, `except`, nesting in call order; discriminator rendered with `cb.literal`; `MQ1904` at the operation; uniqueness per R-SRC-05/06 (by discriminator, by one branch model under `union`); non-unique refusals (R-SRC-09): `MQ1901` at the outer `build()`, `MQ2212` at the executor call (`export`, `byKeys`, `one(q, key)`, a fetch plan with a child, join plan or enricher, an unorderable tie-breaker column), offset `page` appending every selected outer column as tie-breakers; `SourceSupport#combine` through the `(boolean, CriteriaQuery, CriteriaQuery…)` overloads; R-PROC-38, R-SRC-05, R-SRC-06, R-SRC-09, `MQ1901`, `MQ1904`, `MQ2212` in `api/17`, `engine/21`, `reference/90` | AC-PROC-34 and the `@Discriminator`/`shape` positive cases green; AC-SRC rows for each operation (SQL snapshots), a discriminated feed exported by keyset, an "owned `union` shared" list paged by keyset, offset pages over a non-unique `unionAll` with duplicate keys and tied order values losing no row, and each `MQ1901`/`MQ2212` refusal before any statement, green on Tier 1 on both lines |
| M25.5 | Recursive sources. `@Depth`; the builder's `recursive(step -> step.childKey(..).parentKey(..), maxDepth)` with the shared `T`; `MQ1902`; `MQ3037`; `SourceSupport#recursive` (`with recursive` to `maxDepth + 1`, Hibernate's `cycle` mark; `search` ordering type hidden per line) and the outer filter `level <= maxDepth and not mark`; the `max(level)` and mark sub-selects through `JpaSubQuery.from(cte)` on the content and count statements, read from the first row, `MQ2701`/`MQ2702` naming the model and first offending key; `MQ4015` at `maxDepth + 2 > maxRecursionDepth()`; a recursive source is non-unique (M25.4's refusals); R-PROC-37, R-SRC-07, R-SRC-08, `MQ1902`, `MQ2701`, `MQ2702`; Q-21 left open | AC-PROC-37 and the `@Depth` positive case green; AC-SRC rows for a tree (SQL snapshots), `MQ2701` on a deep tree and `MQ2702` on a cyclic graph with no marked row returned, and MySQL `maxDepth` 998 served / 999 `MQ4015`, green on Tier 1 on both lines |
| M25.6 | Q-23 resolved (D-145): `generateChanges` on a source model or a model rooted at one is a new `MQ30xx` (confirmed unused, INV-10) in place of being ignored, with an AC-PROC compile-testing case and `processor/31` R-GEN-37, `processor/32`, `reference/90` rows. Docs and release prep: a "Source models" page (each feature with JPA and Spring tabs per CC-DOC-01, each snippet traceable to an M25 test; the known limits: windows see other writes and REPEATABLE READ, MySQL's quadratic export cost, emulated `cycle` cost on H2/MySQL, the empty-result race under READ COMMITTED), the comparison page's Reach row, the "Portability" page's `MQ4014`/`MQ4015`, stability page and `delivery/61` incubating list, `reference/90` rows checked against the code, `[Unreleased]` CHANGELOG (with the MySQL 8.0.31 floor) | `mkdocs build --strict` green; AC audit green with M25 started |

**Exit:** the M25 rows' criteria green on Tier 1 on both Hibernate lines; nothing new frozen.

## 26. M26 — Trees as unique sources → 0.10.0

Spec: Q-21, D-143, D-145, a new `rfc/0014` (written in M26.0). A recursive source over a tree counts as unique, so
keyset paging, primary-key-first, `byKeys`, `one(q, key)` and fetch plans work on trees: a duplicate-key probe in the
same statement (the M25 spike's `count(*)` against `count(distinct key)` over the CTE, the cycle-marked row counted
too) raises a new `MQ27xx` where it finds one, instead of M25.4/M25.5's non-unique refusal. Export and keyset walks
across statements (Q-21's open half) are decided by the RFC. Model: an `architect-review` of the RFC and at the gate
(paging and export correctness over recursive sources, INV-4, INV-5). Every new public type and member is
`@Incubating`. Slices are written once the RFC is accepted (§4).

| Slice | Content | Done when |
|---|---|---|
| M26.0 | `rfc/0014` and its acceptance spike on both Hibernate lines and Tier 1 (`rfc/0014-spike-results.md`) | RFC accepted as a `D-n`; slice rows written here and in `AGENTS.md`'s model table |

## 27. M27 — Correlated columns and sources over sources → 0.10.0

Spec: D-112 (outer columns root-only, `MQ1311`), Q-22, D-145, a new `rfc/0015` (written in M27.0). Per-row correlated
sub-query columns on a query model (a count, a latest value or an `exists` per row), rendered as a scalar sub-query or
a left join to a grouped source, picked in the spike by plan cost per Tier-1 vendor; a source over sources (window and
aggregate fields over a set operation's or a recursive source's rows, chained CTEs); a source QModel also offering
`query()`, so a source reads without a trivial outer model (Q-22's second half). Model: an `architect-review` of the
RFC and at the gate (public generics of the new column shapes, INV-9, the `SourceSupport` surface). Every new public
type and member is `@Incubating`. Slices are written once the RFC is accepted (§4).

| Slice | Content | Done when |
|---|---|---|
| M27.0 | `rfc/0015` and its acceptance spike on both Hibernate lines and Tier 1 (`rfc/0015-spike-results.md`) | RFC accepted as a `D-n`; slice rows written here and in `AGENTS.md`'s model table |

## 28. M28 — Inheritance roots → 0.10.0

Spec: D-145, a new `rfc/0016` (written in M28.0). Query models over a polymorphic entity root: subtype fields read
through `cb.treat`, one model per subtype, and a discriminated union of subtype models through M25.4's `shape`;
refusals, before any statement, for what a Hibernate line can't render. Model: an `architect-review` of the RFC and at
the gate (processor contracts, the generated shape, INV-6, INV-7). Every new public type and member is `@Incubating`.
The last slice carries the 0.10.0 release prep (`CHANGELOG.md` section). Slices are written once the RFC is accepted
(§4).

| Slice | Content | Done when |
|---|---|---|
| M28.0 | `rfc/0016` and its acceptance spike of `cb.treat` on both Hibernate lines and Tier 1 (`rfc/0016-spike-results.md`) | RFC accepted as a `D-n`; slice rows written here and in `AGENTS.md`'s model table |

## 29. M29 — Freeze → 1.0.0

Spec: D-85 (amended by D-106, D-116 and D-118), D-86, D-111, D-116, D-118. Starts only when the user says the D-111 adopter has migrated onto 0.5.0 or later
and run it in production long enough. Model: `architect-review` at M29.1. Both slices are attended. Tagging `v1.0.0`
is the user's step after the gate, never a slice's.

| Slice | Contents | Done when |
|---|---|---|
| M29.1 | Freeze review: `architect-review` of every public type against D-85, placing the fetch-plan (`api/15`), inspection (`api/16`), M9, adoption, M10 insert and M11 entity-write and M12 selected-field, M13 field-index, M14 single-model and M15 insert-column and multi-key types frozen or `@Incubating`, with the adopter's production feedback; recorded as `D-n`. Review only, no code | every public type has a recorded placement |
| M29.2 | Apply the freeze: `@Incubating` removed from the frozen types, `Filters` and `Having` `sealed` (D-85), `CHANGELOG.md` `1.0.0` section, docs site and stability page say 1.0.0, `M29` added to the audit's started scope | build and TCK green; `JapicmpExclusionsTest` green; AC audit green with M29 started |

**Exit:** the M29 rows' criteria green on Tier 1 and the freeze applied per D-85 and the M29.1 decision, so the user can
push the `v1.0.0` tag.
