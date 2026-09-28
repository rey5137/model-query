# 60 — Testing and Quality

**Covers:** unit-test scope per module, the TCK's groups, SQL snapshots, and the CI gates a change must pass.
**Read when:** adding tests for a rule, or changing what CI runs.
**Owns:** `R-QA-*`, `AC-QA-*`. Every `AC-*` in this spec tree is realised by a test named here.

---

## 1. Unit tests per module

**R-QA-01** `core`: join resolution, column sets, keyset predicate trees, filter rendering. Tests run against
Hibernate's `CriteriaBuilder` over an in-memory H2 metamodel. JPA internals are never mocked — a mock cannot tell you
that a predicate tree is wrong.

**R-QA-02** `processor`: a `compile-testing` case per diagnostic (`processor/32` R-DIAG-05), plus golden files for the
generated sources (`processor/31` AC-GEN-01).

**R-QA-03** Rendered-SQL snapshots: a test-only JDBC proxy (`datasource-proxy`) captures the SQL of about 30 reference
queries per vendor into `src/test/resources/sql/<vendor>/*.sql`. A PR that changes generated SQL shows it as a diff.

**R-QA-04** A snapshot change is reviewed, never blessed blindly: the PR says which snapshots changed and why.

## 2. The TCK (`model-query-tck`)

A JUnit 5 suite parameterized by vendor, over a shared fixture schema: `customers`, `orders`, `order_items`, a table with
a composite key, a table with nullable sort columns, and about 20 000 seeded rows.

| Group | Tests |
|---|---|
| Mapping | every `ColumnField` type, converters, nested joins, LEFT vs INNER, `withTable`, nested `Optional` empty on a LEFT-join miss and present on a match with all-NULL columns; every case for a class model and an equivalent record model; filter-only columns filter, add no join when skipped, and never reach the model |
| Joins | select + filter + order on one path render one join; `as(...)` renders two; children of an aliased join stay separate; `on(...)` keeps LEFT semantics; same key with different `on` throws; `exists` over an aliased path |
| Filters | every operator in both value and `Optional` form on every vendor; skipping inside `or`/`not`/`exists`; empty `in` matches nothing; `ne`/`notIn` include NULLs; `like` with `%`, `_` and `\`; IN lists above the vendor limit; an `or` branch over a LEFT-joined column keeps rows without the join |
| Aggregates | every `Agg` function; result types; `sum` over zero rows is NULL; identical `Agg` constants render one selection; duplicate `Agg.of` names throw; aggregate-in-`where` and column-in-`having` do not compile; `having` skip semantics |
| Grouped queries | a selected column outside the group-by throws; `.keyset()` and `primaryKeyFirst` throw; grouped offset export visits every group once with duplicated order keys; a grouped query with no `primaryKey` exports; `afterMap` runs once per group |
| Paging | page / `NO_COUNT` / `ONLY_COUNT`, limit and page-size validation |
| Count | plain, grouped, collection join |
| Export, offset | every row exactly once with duplicated sort keys, bounded dedupe, missing primary key throws |
| Export, keyset | ties, ordering by a filter-only or unselected column, NULL keys fail by default, NULL keys with explicit precedence across all combinations |
| Primary-key first | single and composite keys, batches crossing the vendor's limits |
| Streaming | 20 000 rows with a bounded-heap assertion, early exit releases the connection (checked against the pool's active count), PostgreSQL without a transaction fails fast |
| Timeout | a slow query is cancelled (`SLEEP()` / `pg_sleep()` / an H2 user function) |
| Detection | each container resolves to the expected profile |
| Bulk writes (`Future`, M8) | the `api/14` acceptance criteria AC-WRT-01..17 on every Tier-1 vendor |

**R-QA-05** A test method name starts with the criterion id in snake case:
`ac_pag_07_null_keyset_requires_explicit_precedence`. A test covering several criteria names the main one and lists the
rest in a one-line comment (`CC-TEST-01`).

**R-QA-06** Every rule that rejects something has a test asserting the exact `MQnnnn` code, not merely that an exception
was thrown.

**R-QA-07** A correctness rule is never verified by a single-page assertion. Paging and export tests page through the
whole fixture and assert the multiset of visited keys.

## 3. CI

**R-QA-08** Gates on every PR: build, unit tests, TCK on H2 + PostgreSQL 17 + MySQL 8.4, on JDK 17 and 21, ArchUnit
layering (INV-7), and SQL-snapshot diff.

**R-QA-09** Nightly: the full matrix — PostgreSQL 14–17, MySQL 8.0/8.4, Hibernate 6.6 and latest 7.x, JDK 17/21/25 —
plus any Tier-2/3 profiles present.

**R-QA-10** Also in CI: JaCoCo coverage, mutation testing (PIT) on the `core` keyset and paging code, dependency and CVE
scanning, and from 1.0 `japicmp` binary-compatibility checks (`delivery/61`).

**R-QA-11** A PR that changes a rule id must change the tests naming it in the same commit; an `AC-*` with no test is a
CI failure (the AC audit).

## 4. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-QA-01 | The AC audit lists every `AC-*` in `docs/spec` and fails on one with no matching test name (R-QA-11). |
| AC-QA-02 | The TCK runs unchanged against every Tier-1 vendor, with vendor-specific expectations only where a rule allows a difference (R-PRF-01). |
| AC-QA-03 | Removing a keyset tie-breaker makes an export test fail, not merely a unit test (R-QA-07). |
| AC-QA-04 | Mutating a comparison operator in the keyset predicate builder is caught by PIT (R-QA-10). |
| AC-QA-05 | ArchUnit fails on a `core → org.hibernate` or `jpa → org.hibernate` edge (R-QA-08). |
