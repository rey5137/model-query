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
M5. AC-VND-07 is `Future` (M8).

**Exit:** TCK green on the full nightly matrix.

## 5. M4–M8

Contents and exit criteria are in `delivery/62` §1. Slice breakdowns are written when the milestone starts, not before —
a slice plan written three milestones early is guesswork.
