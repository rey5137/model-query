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
| 1 | MySQL | 8.0, 8.4 | every PR (Testcontainers) |
| 2 (next) | MariaDB | 10.11, 11.x | nightly, after Tier 1 is stable |
| 3 (community) | Oracle, SQL Server, others | — | profile contributions welcome, run nightly when present |

**R-PRF-01** A Tier-1 release requires the whole TCK to pass on every Tier-1 version listed here (`delivery/60`).

**R-PRF-02** A Tier-3 profile may be merged with its TCK results from the contributor's environment, and is documented
as community-supported. It never gates a release.

## 2. Tier-1 values

| Concern | H2 | PostgreSQL | MySQL |
|---|---|---|---|
| Streaming | positive `fetchSize` (hint `org.hibernate.fetchSize`) | positive `fetchSize`; the driver only uses a cursor when **autocommit is off**, so the query must run in a transaction | `fetchSize = Integer.MIN_VALUE` (row-by-row). Alternative: `useCursorFetch=true` + positive fetch size, chosen by config |
| Timeout | `jakarta.persistence.query.timeout` | `jakarta.persistence.query.timeout` | `jakarta.persistence.query.timeout` |
| Max IN list (soft) | 10 000 | 10 000 | 10 000 |
| Max bind parameters | 100 000 | 65 535 | 65 535 |
| NULLs in ASC order | first | **last** | first |
| Explicit `NULLS FIRST/LAST` | native | native | emulated by Hibernate (`ISNULL(col)` sort) |
| Target table in an `UPDATE`/`DELETE` sub-query (`Future`, M8) | yes | yes | **no** (error 1093): joined filters run key-first (`api/14` R-WRT-11) |
| Row-value keyset `(a,b) > (?,?)` | supported | supported | supported — a possible later optimisation; the default stays the portable OR-expansion |

**R-PRF-11** The built-in H2, PostgreSQL and MySQL profiles carry the values in this table (`vendor/40` R-VND-03).

**R-PRF-03** `checkStreamingPreconditions` on PostgreSQL fails fast outside a transaction with `MQ2101`
(`engine/20` R-EXE-08). The Spring module opens a read-only transaction automatically, so this is mainly a plain-JPA
guard.

**R-PRF-04** MySQL row-by-row streaming holds the connection until the whole result has been read and blocks other
statements on it. Documented, and the reason keyset `export` is the recommended default for large exports
(`engine/20` R-EXE-10).

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
buffers. A `ServiceLoader` profile decides its own streaming and ignores the mode.

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

## 6. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-PRF-01 | Every value in §2 is asserted against the running database, not just against the profile constant (R-PRF-11). |
| AC-PRF-02 | An `IN` list at, just below and just above `maxInListSize()` returns identical rows (R-PRF-11, `api/12` R-FLT-09). |
| AC-PRF-03 | A primary-key-first step-2 batch needing more binds than `maxBindParameters()` is split into several statements, in order, rather than failing; one filter's list that alone needs more throws `MQ1306` (R-PRF-11, `api/12` R-FLT-09, `engine/21` R-PAG-07, R-PAG-08). |
| AC-PRF-04 | PostgreSQL streaming without a transaction throws `MQ2101`; inside one it streams with bounded heap (R-PRF-03). |
| AC-PRF-05 | Both MySQL streaming modes stream 20 000 rows with bounded heap (R-PRF-07). |
| AC-PRF-06 | `defaultAscendingNullOrdering()` matches the database's observed ordering on every Tier-1 version (R-PRF-08). |
| AC-PRF-07 | Every keyset combination in R-PRF-10 pages through the whole table exactly once (R-PRF-09). |
| AC-PRF-08 | A query exceeding the configured timeout is cancelled on every Tier-1 vendor (R-PRF-05). |
