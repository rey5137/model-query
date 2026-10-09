# 62 — Roadmap

**Covers:** the milestones, what 0.1 includes, what it deliberately excludes, and what "done" means for each stage.
**Read when:** planning work or deciding whether something belongs in this release.
**Owns:** `R-RDM-*`, `AC-RDM-*`. Slice breakdowns are in `docs/plan/mvp-plan.md`; status is read from git
(`delivery/61` R-REL-06).

---

## 1. Milestones

| Milestone | Contents | Exit criteria |
|---|---|---|
| **M0: Bootstrap** | Repo, license, Maven multi-module build, CI skeleton, ArchUnit rules, TCK harness with H2/PostgreSQL/MySQL containers | CI green on an empty TCK; AC-REL-01..03, AC-REL-07, AC-QA-01, AC-QA-05 |
| **M1: Core** | `TableField`, `SelectField`, `ColumnField`, `OrderField`, `SelectSet`, `Row`, `RowMapper` (setter and constructor mapping), `JoinContext` (join keys, `as`, `on`), `ModelQuery` builder, full `Filters` DSL including `or`/`not`/`exists`, `Agg`/`AggregateField`, `groupBy`/`having`/`afterMap`, `QueryCustomizer` | Unit tests; H2 SQL snapshots. `api/10..13` acceptance criteria covered |
| **M2: Engine** | `ModelQueryExecutor`: list, page, count, stream, export (offset + keyset + primary-key-first), grouped queries and grouped export | TCK green on Tier-1 databases. `engine/20..21` covered, AC-QA-02..04 |
| **M3: Vendors** | Tier-1 `VendorProfile`s, detection, `model-query-hibernate` (grouped count, null precedence) | TCK green on the full nightly matrix. `vendor/40..41` covered, except `Future` criteria and AC-VND-06, which needs Spring and is owed by M5 |
| **M4: Processor** | Annotations, processor, diagnostics, for class and record models, including `@Aggregate`/`@GroupBy` | compile-testing suite green; samples use only generated QModels. `processor/30..32` covered, except `Future` criteria |
| **M5: Spring** | `spring-data` module, starter, properties | Boot sample with several datasources on H2, PostgreSQL and MySQL. `integration/50` and AC-VND-06 covered, except `Future` criteria |
| **M6: Bulk writes** | `ModelUpdate`, `ModelDelete`, `Changes`, `@UpdateModel` and `generateChanges`, executor and repository methods, chunked mode, `VendorProfile.targetTableInSubquery` | `api/14` covered, TCK bulk-write group green on Tier-1 databases, AC-VND-07, AC-SPR-09, AC-GEN-10..12 and the `MQ33xx` compile-testing cases; the Spring Boot sample has a PATCH endpoint |
| **M7: 0.1.0** | Docs site, samples, Maven Central publishing | First public release; AC-REL-04, AC-REL-05 |
| **M8: Hardening → 0.2.0** | Early-adopter feedback, API review, `japicmp` check, fetch plans (`api/15`, D-96), query inspection and `model-query-test` (`api/16`, D-98) (MariaDB Tier 2 moved after 1.0, D-81) | API reviewed, nothing frozen (D-90); AC-REL-06; `api/15..16` covered |
| **M9: Adoption → 0.2.0** | SPI split before new vendors (D-108, D-109), `ModelQueryException` (D-106), the two-way keyset page with an opaque cursor (D-105), child-query assertions (D-104), processor round deferral (D-107), and the adoption features of D-111: sub-queries and correlated `exists`, expressions and ordering by them, custom repository factory beans, chunked enricher lookups, String and `@EmbeddedId` keyset keys, non-key joins | Nothing frozen (D-111); the new `AC-*` of D-104–D-111 covered; the adoption features green on Tier 1 |
| **M10: Inserts → 0.3.0** | RFC 0004 / D-116: insert-select, insert-values with conflict clauses and returned keys, `persist(model)`, `@InsertModel`, `InsertSupport`, repository methods | Nothing frozen (D-116); `api/14` §10 covered, the insert `AC-WRT` rows green on Tier-1 databases, AC-SPR-09 for the insert methods, the `MQ35xx` compile-testing cases |
| **M11: Entity writes → 0.4.0** | RFC 0005 / D-118: entity mode (`throughEntities()`) for update and delete, `persist` returning a query model, `WriteAssignment`s on the config, repository and starter support | Nothing frozen (D-118); `api/14` §11 covered, AC-WRT-34..39 green on Tier-1 databases, AC-SPR-09 for the `persist` overload |
| **M12: Selected fields → 0.5.0** | D-120: an opt-in `@Selected SelectSet<M>` field the mapper fills with the selected columns, `SelectSet.contains`, `selectedIn` and value equality | Nothing new frozen (D-120); AC rows for R-COL-21/22, R-PROC-25 and R-GEN-29…31 green on Tier-1 databases |
| **M13: Field index → 0.6.0** | RFC 0006 / D-121: a generated `Q<M>.fields()` returning a `FieldIndex<M>` that resolves property paths to select fields, filter fields, column sets and children | Nothing new frozen (D-121); AC rows for R-COL-23/24 and R-GEN-32/33 green on Tier-1 databases |
| **M14: Fewer models → 0.7.0** | RFC 0007 / D-123: `@QueryModel(generateInserts = true)`, executor `one`, `first` and `one(q, key)`, repository `findOne`, `findFirst` and `findByKey`; ships D-122 | Nothing new frozen (D-123); AC rows for R-EXE-12, R-PROC-26, R-GEN-34 and R-SPR-15 green on Tier-1 databases |
| **M15: Insert columns and many keys → 0.8.0** | RFC 0008 / D-125, D-126: `generateInserts` leaves out non-writable `@Generated` columns, `@ExcludeFromInserts`, executor `byKeys` and repository `findAllByKeys`, an ignored `orderBy` warned on | Nothing new frozen (D-125, D-126); AC rows for R-PROC-26/27, R-GEN-34, R-EXE-13 and R-SPR-15 green on Tier-1 databases |
| **M16: Freeze → 1.0.0** | Starts once the D-111 adopter has migrated onto 0.5.0 or later and run it in production: the freeze review of every public type and the D-85 freeze applied | API frozen per D-85 and the M16.1 review; AC-REL-06 against the frozen types |

**R-RDM-01** M3 and M4 may run in parallel after M2. M6 follows M5, so the first release (M7) ships bulk writes
next to the read API; their API is `@Incubating` until the M8 API review, which covers it before the 1.0 freeze
(D-59, D-90).
Nothing else runs in parallel.

**R-RDM-02** A milestone is done when every acceptance criterion its "Exit criteria" column names has a passing test
(`delivery/60` R-QA-11) — not when the code exists. A criterion tagged `Future` belongs to the milestone that
builds it, which names it in its exit criteria, and does not hold up an earlier milestone whose file it sits in. A
criterion no milestone names (today the AC-RDM rows) is reported by the AC audit but not enforced until a
milestone claims it.

## 2. What 0.1 excludes

**R-RDM-03** Deliberately out of scope for 0.1, and not to be half-built:

- Entity loading and entity writes, anything that touches the persistence context (INV-1). Filter-driven bulk writes
  (`api/14`) are in 0.1 from M6 and load no entity.
- A general SQL builder, raw SQL fragments beyond `Agg.of` and `Filters.add` (P-5).
- Window functions, `ROLLUP`/`CUBE`, sub-query selections.
- Keyset paging over grouped queries (`api/13` R-AGG-10).
- Reactive or `Publisher`-based results.
- Interface projections and Kotlin-specific model support.
- Tier-2 and Tier-3 vendor profiles as release gates (`vendor/41` R-PRF-02).

**R-RDM-04** An excluded item may still have its name reserved when reserving costs nothing and prevents a breaking
change later (SPEC.md §4, `0.1-reserved`).

## 3. Success criteria for 0.1

| ID | Criterion |
|---|---|
| AC-RDM-01 | A new project can define a model, generate its QModel, and page a query in under 20 lines, with no Spring. |
| AC-RDM-02 | An export of 1 000 000 rows completes with bounded heap on every Tier-1 vendor, and visits every row exactly once. |
| AC-RDM-03 | A summary report — group-by plus aggregates, mapped into a model — needs no `QueryCustomizer`. |
| AC-RDM-04 | Every `INV-*` has at least one test that fails when the invariant is broken. |
