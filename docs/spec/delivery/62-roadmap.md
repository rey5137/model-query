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
| **M1: Core** | `TableField`, `SelectField`, `ColumnField`, `OrderField`, `ColumnSet`, `Row`, `RowMapper` (setter and constructor mapping), `JoinContext` (join keys, `as`, `on`), `ModelQuery` builder, full `Filters` DSL including `or`/`not`/`exists`, `Agg`/`AggregateField`, `groupBy`/`having`/`afterMap`, `QueryCustomizer` | Unit tests; H2 SQL snapshots. `api/10..13` acceptance criteria covered |
| **M2: Engine** | `ModelQueryExecutor`: list, page, count, stream, export (offset + keyset + primary-key-first), grouped queries and grouped export | TCK green on Tier-1 databases. `engine/20..21` covered, AC-QA-02..04 |
| **M3: Vendors** | Tier-1 `VendorProfile`s, detection, `model-query-hibernate` (grouped count, null precedence) | TCK green on the full nightly matrix. `vendor/40..41` covered, except `Future` criteria and AC-VND-06, which needs Spring and is owed by M5 |
| **M4: Processor** | Annotations, processor, diagnostics, for class and record models, including `@Aggregate`/`@GroupBy` | compile-testing suite green; samples use only generated QModels. `processor/30..32` covered, except `Future` criteria |
| **M5: Spring** | `spring-data` module, starter, properties | Boot sample with several datasources on H2, PostgreSQL and MySQL. `integration/50` and AC-VND-06 covered, except `Future` criteria |
| **M6: 0.1.0** | Docs site, samples, Maven Central publishing | First public release; AC-REL-04, AC-REL-05 |
| **M7: Hardening → 1.0.0** | Early-adopter feedback, API review, `japicmp` baseline, MariaDB Tier 2 | API frozen; AC-REL-06 |
| **M8: Bulk writes** | `ModelUpdate`, `ModelDelete`, `Changes`, `@UpdateModel` and `generateChanges`, executor and repository methods, chunked mode, `VendorProfile.targetTableInSubquery` | `api/14` covered, TCK bulk-write group green on Tier-1 databases, AC-VND-07, AC-SPR-09, AC-GEN-10..12 and the `MQ33xx` compile-testing cases; the Spring Boot sample has a PATCH endpoint |

**R-RDM-01** M3 and M4 may run in parallel after M2. M8 starts after M6, so the first release ships the read API, and
runs alongside M7; its API is `@Incubating` until the M7 API review, which covers it before 1.0.0. Nothing else runs in
parallel.

**R-RDM-02** A milestone is done when every acceptance criterion its "Exit criteria" column names has a passing test
(`delivery/60` R-QA-11) — not when the code exists. A criterion tagged `Future` belongs to the milestone that
builds it, which names it in its exit criteria, and does not hold up an earlier milestone whose file it sits in. A
criterion no milestone names (today the AC-RDM rows) is reported by the AC audit but not enforced until a
milestone claims it.

## 2. What 0.1 excludes

**R-RDM-03** Deliberately out of scope for 0.1, and not to be half-built:

- Writes, entity loading, anything that touches the persistence context (INV-1). Bulk writes come in M8 (`api/14`).
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
