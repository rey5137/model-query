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

## 10. M9 — Freeze → 1.0.0

Spec: D-104–D-109 (Q-4, Q-9, Q-11, Q-12, Q-13 resolved), D-85 (the freeze split, amended by D-106), D-86, D-90;
`vendor/40` §2, `vendor/41` §2, `api/14` R-WRT-11, `engine/21` §2, `api/11`, `integration/50`, `api/16` R-INS-06,
`processor/32` R-DIAG-03, `reference/90`. Each slice writes its rules, `AC-*` rows and `MQ` codes into the owning spec
file first, then builds. Model: `architect-review` required before M9.3 (public API, paging correctness), for M9.1
(`VendorProfile` and `ProviderSupport` surface) and at M9.7 (the freeze). New vendor profiles (MariaDB, D-81) come
after M9, on the M9.1 SPI. Tagging `v1.0.0` is the user's step after the gate, never a slice's.

Slices run in table order, not id order: M9.2, M9.5 and M9.6 are decided and independent, so they go first and may
run unattended; M9.1 and M9.3a are attended (their reviews may need the user's decision); M9.3–M9.4 may run
unattended once M9.3a is recorded; M9.7–M9.8 are attended.

| Slice | Contents | Done when |
|---|---|---|
| M9.2 | `ModelQueryException` (D-106): abstract, protected constructors, `code()`; the three exceptions extend it; `reference/90` §1, `diagnostics.md`, CHANGELOG | a test catching each kind as `ModelQueryException` and reading its code |
| M9.5 | Child-query assertions (D-104): `QueryAssert.child(ChildField)` returning an assertion over its conditions, order, selection and `maxPerParent`, reading `childLoads()` inside `model-query-test`; `api/16` R-INS-06 and its `AC-INS` row; `testing.md` example | the new `AC-INS` row green; a missing child names the plan's children |
| M9.6 | Processor round deferral (D-107): a model whose root type is not yet generated is retried in a later round and reported with a new `MQ30xx` in the last; R-DIAG-03 documents the `MQ3015`/`MQ3014` order | compile-testing cases: a root generated by a second processor in the same round, and one never generated |
| M9.1 | SPI split (D-108, D-109): `VendorProfile.streamingFetchSize(int)` replaces `applyStreaming`'s hint, the built-in profiles stop naming `org.hibernate.fetchSize`; `ProviderSupport.applyFetchSize(Query, int)` and `default Optional<String> tableOf(Class<?>)`, both implemented in `model-query-hibernate`; the once-per-factory `WARN` when no provider support applies the size; key-first chosen by table for a sub-query over a second entity on the root's table; `vendor/40`, `vendor/41` §2, `api/14` R-WRT-11 updated; `vendors.md` drops the 1093 gap | a TCK case on MySQL with two entities on one table runs key-first; the fetch size reaches the statement on PostgreSQL; a unit test of the warning |
| M9.3a | Keyset page design: `architect-review` of D-105 (signatures, the cursor's content and fingerprint, `before` with null rules, grouped and fetch-plan queries, the new `MQ` codes); the outcome recorded as `D-n` and written into `engine/21` §2, `api/11`, `integration/50`, `reference/90` and SPEC.md's paging row. Review only, no code | every open point of D-105 has a recorded decision and the new `AC-PAG` rows exist |
| M9.3 | Keyset page in `core` and `jpa`: `KeysetSpec`, `KeysetSlice`, the cursor encoder and decoder (fingerprint check, typed values through converters), `ModelQueryExecutor.page(query, KeysetSpec)` with `after` and `before`, fetch plans run on the slice | the new `AC-PAG` rows green on Tier 1, ties, null precedences and a tampered cursor among them |
| M9.4 | Keyset page in Spring and docs: `ModelQueryRepository` pass-through; `paging-export.md` gets a "Keyset page" section (a REST endpoint passing the cursor) and its current "Keyset paging" heading renamed to keyset export; the Spring Boot sample uses it; CHANGELOG | the repository case green on Tier 1; `mkdocs build --strict` green |
| M9.7 | Freeze review: `architect-review` of every public type against D-85, placing the fetch-plan (`api/15`) and inspection (`api/16`) types and the M9 types frozen or `@Incubating`; recorded as `D-n`. Review only, no code | every public type has a recorded placement |
| M9.8 | Apply the freeze: `@Incubating` removed from the frozen types, `Filters` and `Having` `sealed` (D-85), `CHANGELOG.md` `1.0.0` section, docs site and stability page say 1.0.0, `M9` added to the audit's started scope | build and TCK green; `JapicmpExclusionsTest` green; AC audit green with M9 started |

**Exit:** the M9 rows' criteria green on Tier 1, the freeze applied per D-85 and the M9.7 decision, and the `1.0.0`
CHANGELOG section ready, so the user can push the `v1.0.0` tag.
