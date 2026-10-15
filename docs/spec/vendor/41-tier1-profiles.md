# 41 — Support Tiers and Tier-1 Profiles

**Covers:** which databases are supported at which level, the concrete values in the H2, PostgreSQL and MySQL profiles,
and the keyset predicate shape they all share.
**Read when:** changing a profile value, adding a database, or explaining a vendor-specific behaviour.
**Owns:** `R-PRF-*`, `AC-PRF-*`. The SPI is `vendor/40`.

---

## 1. Support tiers

| Tier | Database | Versions | CI |
|---|---|---|---|
| 1 | H2 | 2.2+, native mode | every PR |
| 1 | PostgreSQL | 14, 15, 16, 17 | every PR (Testcontainers) |
| 1 | MySQL | 8.0.31, 8.4 | every PR (Testcontainers) |
| 2 (after 1.0, D-81) | MariaDB | 10.11, 11.x | nightly, after Tier 1 is stable |
| 3 (community) | Oracle, SQL Server, others | — | profile contributions welcome, run nightly when present |

The portability check (D-133) treats H2, PostgreSQL and MySQL as Tier 1; its tests also boot MariaDB, and any other target is
best effort.

**R-PRF-01** A Tier-1 release requires the whole TCK to pass on every Tier-1 version listed here (`delivery/60`).

**R-PRF-02** A Tier-3 profile may be merged with its TCK results from the contributor's environment, and is documented
as community-supported. It never gates a release.

## 2. Tier-1 values

| Concern | H2 | PostgreSQL | MySQL |
|---|---|---|---|
| Streaming fetch size | the configured size | the configured size; the driver only uses a cursor when **autocommit is off**, so the query must run in a transaction | `Integer.MIN_VALUE` (row-by-row). Alternative: `useCursorFetch=true` + the configured size, chosen by config |
| Timeout | `jakarta.persistence.query.timeout` | `jakarta.persistence.query.timeout` | `jakarta.persistence.query.timeout` |
| Max IN list (soft) | 10 000 | 10 000 | 10 000 |
| Max bind parameters | 100 000 | 65 535 | 65 535 |
| NULLs in ASC order | first | **last** | first |
| Explicit `NULLS FIRST/LAST` | native | native | emulated by Hibernate (`ISNULL(col)` sort) |
| Target table in an `UPDATE`/`DELETE` sub-query (`Future`, M6) | yes | yes | **no** (error 1093): joined filters run key-first (`api/14` R-WRT-11) |
| Row-value keyset `(a,b) > (?,?)` | supported | supported | supported — a possible later optimisation; the default stays the portable OR-expansion |
| Rows per multi-row `VALUES` insert (`maxValuesRows()`) | no limit beyond the bind limit | no limit beyond the bind limit | no limit beyond the bind limit |
| Conflict detected only on the named key (`conflictTargetHonoured()`) | yes | yes | **no**: any unique key, so a conflict clause needs `anyUniqueKey()` (`api/14` R-WRT-36) |
| Conflict `where` reads earlier assignments (`conflictWhereSeesEarlierAssignments()`) | no | no | **yes**: a `CASE` per assignment, so a `where` reading two or more assigned columns is refused (`api/14` R-WRT-34) |
| Conflict target names a constraint (`conflictOnConstraint()`) | **no** | yes | **no** (`api/14` R-WRT-52) |
| Conflict target takes an arbiter `WHERE` (`conflictArbiterPredicate()`) | **no** | yes | **no** (`api/14` R-WRT-52) |
| Sequences (`sequences()`) | yes | yes | **no**: `Expr.nextval` is `MQ1509` (`api/10` R-COL-28) |
| Source features (`sources(SourceFeature)`) | all six | all six | all six (`api/17` R-SRC-11, R-VND-21) |
| Recursion ceiling (`maxRecursionDepth()`) | none known | none known | **1 000**: `cte_max_recursion_depth`'s default, so `maxDepth + 2 > 1000` is `MQ4015` (`api/17` R-SRC-08, R-VND-22) |

**R-PRF-11** The built-in H2, PostgreSQL and MySQL profiles carry the values in this table (`vendor/40` R-VND-03);
"no limit" is `maxValuesRows()` returning `Integer.MAX_VALUE` (`vendor/40` R-VND-14, D-117).
A profile's streaming value is the size its `streamingFetchSize` returns; it names no provider hint, and the factory's
`ProviderSupport` opens the stream with it, `resultStream(query, size)` (`vendor/40` R-VND-12, D-108).

The clamp counts `Query.getParameters()`, which holds one parameter for an embeddable-valued key or a composite-FK to-one
SET value though each takes several JDBC binds, so it can under-count; on a limit-bound vendor that fails loudly at the
database, and reads under `engine/21` R-PAG-07 share it. The clamp is the largest power of two within the limits, so a
provider that pads IN lists to the next power of two stays within them, and the same count refuses a query's own
statement over `maxBindParameters()` with `MQ1307` (`api/12` R-FLT-09, D-80).

**R-PRF-03** `checkStreamingPreconditions` on PostgreSQL fails fast outside a transaction with `MQ2101`
(`engine/20` R-EXE-08). The Spring module opens a read-only transaction automatically, so this is mainly a plain-JPA
guard.

**R-PRF-04** MySQL row-by-row streaming holds the connection until the whole result has been read and blocks other
statements on it. Documented, and the reason keyset `export` is the recommended default for large exports
(`engine/20` R-EXE-10). Every streaming value, `Integer.MIN_VALUE` included, reaches the driver only through a
`ProviderSupport`: without one for the provider (`model-query-hibernate` for Hibernate) the driver's default applies,
which on MySQL and PostgreSQL buffers the whole result, and the engine warns once per factory (`vendor/40` R-VND-12,
D-108).

**R-PRF-05** `jakarta.persistence.query.timeout` becomes `Statement.setQueryTimeout`, which has **one-second
granularity**; a sub-second timeout is rounded up, not honoured exactly.

**R-PRF-06** MySQL Connector/J implements `setQueryTimeout` by opening a second connection to run `KILL QUERY`. It works,
but it costs an extra connection per cancelled query, which matters for pool sizing when timeouts are short. Documented in
the vendor notes page.

## 3. `modelquery.mysql.streaming-mode`

**R-PRF-07** `row-by-row` (default) or `cursor-fetch`. The profile reads it once at construction; it is never decided per
query. `ModelQueryConfig.mysqlStreamingMode(...)` picks the built-in MySQL profile, and the resolver caches by it as well
as by the vendor, so two configurations on one factory get their own profile (`reference/92` D-34). `cursor-fetch` needs
`useCursorFetch=true` on the JDBC URL, which the library cannot set; without it Connector/J ignores the fetch size and
buffers. With the MariaDB Connector/J driver, which rejects a negative fetch size and so fails `row-by-row`, use
`cursor-fetch` (`MysqlStreamingMode.CURSOR_FETCH`). A `ServiceLoader` profile decides its own streaming and ignores the
mode.

## 4. NULL ordering defaults

**R-PRF-08** `defaultAscendingNullOrdering()` returns `NULLS_FIRST` for H2 and MySQL and `NULLS_LAST` for PostgreSQL.
The engine uses it to decide whether an explicit precedence needs rendering at all (`api/10` R-COL-12) and whether a
`DEFAULT`-precedence keyset over a nullable column is safe (`engine/21` R-PAG-05).

## 5. Keyset predicate shape

For order `(a ASC, b DESC, id ASC)` and last key `(ka, kb, kid)`:

```
(a > :ka)
OR (a = :ka AND b < :kb)
OR (a = :ka AND b = :kb AND id > :kid)
```

**R-PRF-09** With `a.nullsLast()` and `ka = NULL`, the branches comparing `a` become `a IS NULL AND …`. With `ka` not
null, an `OR a IS NULL` branch is added, because under NULLS LAST every NULL sorts after every non-null value. The
mirror image applies to `nullsFirst()`. A non-key `DEFAULT`-precedence column under `keyset.null-keys=fail` gets the
same `OR a IS NULL` branch whatever the null ordering, so its NULLs are read and refused with `MQ2202` (D-30, D-35).

**R-PRF-10** The TCK checks every combination — ASC/DESC × NULLS FIRST/LAST × null/non-null key — on every Tier-1
database (`delivery/60`).

## 6. Row-lock rendering

What Hibernate renders for a `RowLock` (`vendor/40` R-VND-15) on each Tier-1 database, confirmed by the M18.1 SQL
snapshots of an entity query (`model-query-tck` `sql/*/lock-render.sql` and `sql/*/hibernate7/lock-render.sql`). A cell
is the clause appended to the statement; **refused** means `LockSupport.rendered` is false and the read throws `MQ4010`
before any statement. The executor's reads render the same clause without the `of <root>` list (below).

| Strength × wait | H2, Hibernate 6.6 | H2, Hibernate 7.x | PostgreSQL 17, 6.6 | PostgreSQL 17, 7.x | MySQL 8.4, 6.6 | MySQL 8.4, 7.x |
|---|---|---|---|---|---|---|
| `exclusive()`, `WAIT` | `for update` | `for update` | `for no key update` | `for no key update of <root>` | `for update` | `for update of <root>` |
| `exclusive(NO_WAIT)` | refused | `for update nowait` | `for no key update nowait` | `for no key update of <root> nowait` | `for update nowait` | `for update of <root> nowait` |
| `exclusive(SKIP_LOCKED)` | refused | `for update skip locked` | `for no key update skip locked` | `for no key update of <root> skip locked` | `for update skip locked` | `for update of <root> skip locked` |
| `shared()`, `WAIT` | `for update` (H2 has no `FOR SHARE`) | `for update` | `for share` | `for share of <root>` | `for share` | `for share of <root>` |
| `shared(NO_WAIT)` | refused | refused (renders a plain `for update`) | `for share nowait` | `for share of <root> nowait` | `for share nowait` | `for share of <root> nowait` |
| `shared(SKIP_LOCKED)` | refused | refused (renders a plain `for update`) | `for share skip locked` | `for share of <root> skip locked` | `for share skip locked` | `for share of <root> skip locked` |

On H2, 6.6 supports neither `NOWAIT` nor `SKIP LOCKED`, and 7.x supports both but renders neither for `shared()`,
which it widens to `FOR UPDATE`; `rendered` (`vendor/40` R-VND-15) therefore decides per strength × wait, not per wait
alone. On PostgreSQL `exclusive()` renders `FOR NO KEY UPDATE`, which does not block a child row's foreign-key check —
the parent-read case `RowLock` exists for. For an entity query Hibernate 7.x adds `OF <root alias>` on PostgreSQL and
MySQL (not H2) and 6.6 renders none; the executor's reads are column projections, which give Hibernate no lock root, so
they render no `OF` list on either line (`sql/*/lock-exec-*.sql`). Either way only the root table is locked, because a
locked query reads its root alone (`engine/20` R-EXE-14). A query whose root is on several tables is refused with
`MQ4010`.

## 7. Returning rendering

What each Tier-1 database renders for a returning write (`vendor/40` R-VND-17, `api/14` R-WRT-50), confirmed by the
M20.1 spike for an update and a delete and by M20.2's insert cases, committed as the SQL snapshots
`sql/*/returning.sql` (`sql/*/hibernate7/returning.sql` only where a Hibernate line differs). `<cols>` is the returning
selection in the query's column order — the model's selected columns and its primary key, each once — and `<write>` the
statement the executor built, with its assignments, version clause and filters. **refused** means `MQ4011` at method
entry, before any statement.

| Statement | H2, Hibernate 6.6 | H2, Hibernate 7.x | PostgreSQL 17, both lines | MySQL 8.4, both lines |
|---|---|---|---|---|
| `updateReturning` | `select <cols> from final table (<write>)` | same | `<write> returning <cols>` | refused |
| `deleteReturning` | `select <cols> from old table (<write>)` | same | `<write> returning <cols>` | refused |
| `insertReturning`, plain | `select <cols> from final table (<write>)` | same | `<write> returning <cols>` | refused |
| `insertReturning`, upsert | `select <cols> from final table (<write>)`, Hibernate's `MERGE` | same | `<write> returning <cols>`, `ON CONFLICT` do update | refused |

On H2 the delta-table form is what reads the rows back, because the dialect renders `FINAL TABLE` and `OLD TABLE` for
its own insert and update statements only: the adapter wraps the translated `update`, `delete` or `insert`, and the
returning selection is the columns' own expressions, so a converted column is returned as its converted value and
mapped back by the column's mapping. An upserting insert is Hibernate's `MERGE` on H2 and `ON CONFLICT ... DO UPDATE`
on PostgreSQL, wrapped or suffixed as its plain form is; the rows it returns are the ones the statement inserted or
updated, never those it left alone. On PostgreSQL Hibernate's own translator renders the ` returning ` list, which the
adapter requires to be there rather than appending a clause of its own (D-129). Both Hibernate lines render the same
SQL, so no `hibernate7/` variant exists; the two lines' difference is the translation call behind the SQL (R-WRT-50).
An arbiter predicate (`api/14` R-WRT-52) is spliced after the translated insert's conflict target — on PostgreSQL
`on conflict (<cols>) where <pred>`, its values as literals — and composes with the ` returning ` list of an arbiter
upsert; the adapter finds that anchor exactly once, else `MQ4011` before execution, as it does the returning anchor.
An insert, an update or a delete over a root entity mapped to more than one table is `MQ4011` on every vendor, because
Hibernate runs it as more than one statement.

## 8. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-PRF-01 | Every value in §2 is asserted against the running database, not just against the profile constant (R-PRF-11). |
| AC-PRF-02 | An `IN` list at, just below and just above `maxInListSize()` returns identical rows (R-PRF-11, `api/12` R-FLT-09). |
| AC-PRF-03 | A library-built key list needing more binds than `maxBindParameters()`, a primary-key-first step-2 batch, is split into several statements of at most a power-of-two size each and returns the same rows in order; a query's own statement over the limit is refused before it runs, with `MQ1306` when one filter's list alone passes it and `MQ1307` when several filters only together do, which on a keyset statement names its own binds and the worst cursor's; such a statement is refused before it runs when its own binds plus the worst cursor, k(k+1)/2 for k keys, pass the limit: an export is refused with an empty sink, one that just fits runs, and the first key-first round, a `startAfter` round and a `commitEachChunk` write are refused before any commit (R-PRF-11, D-82, `api/12` R-FLT-09, `engine/21` R-PAG-07, R-PAG-08, D-80). |
| AC-PRF-04 | PostgreSQL streaming without a transaction throws `MQ2101`; inside one it streams with bounded heap (R-PRF-03). |
| AC-PRF-05 | Both MySQL streaming modes stream 20 000 rows with bounded heap (R-PRF-07). |
| AC-PRF-06 | `defaultAscendingNullOrdering()` matches the database's observed ordering on every Tier-1 version (R-PRF-08). |
| AC-PRF-07 | Every keyset combination in R-PRF-10 pages through the whole table exactly once (R-PRF-09). |
| AC-PRF-08 | A query exceeding the configured timeout is cancelled on every Tier-1 vendor (R-PRF-05). |
| AC-PRF-09 | On every Tier-1 vendor a 1,001-row `VALUES` insert, past the default `maxValuesRows()`, runs as one statement, and a conflict clause naming the id meets a conflict on another unique key as a unique violation exactly where `conflictTargetHonoured()` is true, and a conflict update whose `where` reads both columns it assigns leaves the second unchanged exactly where `conflictWhereSeesEarlierAssignments()` is true (R-PRF-11, `vendor/40` R-VND-14). |
| AC-PRF-10 | The RFC 0009 §1 rendering table, strength × wait × joined/unjoined, matches what Hibernate 6.6 and 7.x render on H2, PostgreSQL 17 and MySQL 8.4, asserted as the SQL snapshots `sql/*/lock-render.sql` and `sql/*/hibernate7/lock-render.sql` (`tck/lock/RowLockRenderingTest#ac_prf_10_pessimistic_locks_render_the_rfc_table`; `engine/20` R-EXE-14, `vendor/40` R-VND-15). |
