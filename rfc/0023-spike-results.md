# RFC 0023 acceptance spike: results

**1 Rendering and binds: keep.** Keys over three groups render one statement text per `IN` length, with one round per
group, on H2, PostgreSQL and MySQL and on both Hibernate lines. A group over the limit splits by the limit.
**2 Pruning: keep for the two-sided range; keep with a changed expectation for the lower-bound-only form.** Bound values
prune on every vendor, on the real JDBC path, including PostgreSQL's generic plans and MySQL's server-side prepare. The
lower bound alone prunes only the partitions before it: 19 of 36 for a mid-range group.
**3 Cost: problem as written, keep with guidance.** Grouping wins only when a group holds hundreds of keys. A 50-row page
spread over 36 months is 36 statements instead of 1, about 20 times slower through Hibernate. The lower-bound-only child
load is slower than ungrouped at every size measured, except a few JDBC-only cells.

Spike on `rfc-0023-spike`: test-only module `spike-rfc0023`, data in [`0023-spike-data/`](0023-spike-data/README.md).
`KeyGroups` doesn't exist, so the engine's rounds are emulated: keys grouped by month, each round the real
`ModelQueryExecutor#byKeys` (or a `list` read for a child) over `IN (...)` with `compare(CREATED_AT, GTE,
Expr.param(FROM))` and, for the two-sided form, `compare(CREATED_AT, LT, Expr.param(TO))`, values bound per round with
`ModelQuery.Builder#bind`. Ungrouped is today's: no range condition, the same chunking.

**Versions.** PostgreSQL 16.15 (`postgres:16-alpine`, pgjdbc 42.7.14, `shared_buffers=512MB`); MySQL 8.0.34 as the server
reports it (image tag `mysql:8.0.31`; Connector/J 26.7.0, `connectionTimeZone=UTC`, `innodb_buffer_pool_size=1G`); H2
2.5.252; Hibernate 6.6.58.Final (API 3.1.0) and 7.4.14.Final (API 3.2.0); the JVM runs in UTC. Hibernate 6.6 and 7.4 gave
the same statements, binds, partition counts and, within noise, the same timings in every cell below.

**Data.** `event_log`, 36 monthly partitions, 20 000 rows per month (720 000 rows) on PostgreSQL and MySQL, native DDL
(PostgreSQL `PARTITION BY RANGE (created_at)`, MySQL `PARTITION BY RANGE COLUMNS(created_at)`, plus a
`to_days(created_at)` copy for `EXPLAIN`). `id` is a snowflake (millis since 2020-01-01 shifted left 22 bits plus a
sequence), `created_at` is `timestamptz` / `DATETIME(6)`, bound as an `Instant`. The JPA `@Id` is `id` alone; the DDL
primary key is `(id, created_at)`. `order_id` is a non-unique indexed parent key (a to-many child). H2: an unpartitioned
table, 100 rows per month, correctness only (grouped results equal ungrouped ones in every cell).

## Findings

### 1 Rendering and binds

| Cell (all vendors, both lines) | Statements | Distinct texts | Binds per statement |
|---|---|---|---|
| `byKeys`, 3 groups of 3, 5, 7 keys, two-sided | 3 | 3 | 5, 7, 9 |
| the same, lower bound only | 3 | 3 | 4, 6, 8 |
| groups of 3, 5, 3 keys | 3 | 2 | 5, 7, 5 (equal lengths share a text) |
| child, 5 parents over 3 groups, lower bound | 3 | 2 | 2, 3, 3 |
| `byKeys`, one group of 10 240 keys plus 5 keys | 3 | 3 | 8194, 2050, 7 |
| child, one group of 10 240 keys plus 5 | 5 | 4 | 8193, 2049, 2, 4, 2 |
| `byKeys`, the same 15 keys ungrouped | 1 | 1 | 15 |

The text is the keys' `IN` length plus the range conditions, never a value. The round size `byKeys` uses is **8192** keys
on all three vendors (the largest power of two within `maxInListSize()`, not within the 32 767 / 65 535 bind limits), so a
group over 8192 splits there, and R-FCH-05's rounds are expected to match: the guide shouldn't quote the vendor bind limit.
The bound range adds 2 binds (1 for lower-bound only) per round. A child over the 3 row-months groups into 3 statements
only when the parent keys all lie in the same months as their children: with real order dates 0 to 40 days earlier
than their lines (the data set's), the same lines fall in up to 3 parent months, so groups multiply (see 3).

### 2 Pruning

Captured binds: an `Instant` reaches the driver as a `java.sql.Timestamp` on both lines (the JVM and the session ran in
UTC; other time zones weren't tried), the keys as `Long`.

**PostgreSQL**, `PREPARE` + `EXPLAIN (ANALYZE) EXECUTE`, with `plan_cache_mode=force_generic_plan`, keys in month 17:

| Form | Subplans Removed | Partitions scanned |
|---|---|---|
| two-sided (`>= from and < to`) | 35 | 1 |
| lower bound only (`>= from`) | 17 | 19 (this month and every later one) |
| ungrouped (no range) | none | 36 |

The pruning is initial pruning at executor start (no "never executed" nodes). `force_custom_plan` scans the same
partitions, planned out. On the JDBC path (Hibernate, 8 executions on one connection; the scan deltas of
`pg_stat_user_tables` and `pg_prepared_statements`): default settings (custom plans after the unnamed statement),
`prepareThreshold=1` (1 generic plan after 8 custom) and `prepareThreshold=1` with `plan_cache_mode=force_generic_plan`
(9 generic plans, 0 custom) each touched 1 partition (two-sided), 19 (lower bound only) and 36 (ungrouped), 3 index
scans per execution per partition (one per key). The child cases (`order_id IN`) are identical.

**MySQL**, `EXPLAIN` with literal values, `partitions` column: two-sided `p202306` (1), lower bound only 19, ungrouped
36. `EXPLAIN` can't take a server-prepared statement, so the real path is measured with `Handler_read_key` per
execution, which is 3 (two-sided), 57 (lower bound only), 108 (ungrouped): the keys times the partitions probed. And
with the partitions holding record locks in `performance_schema.data_locks` after the same statement read `FOR SHARE`
(1, 19, 36). Both agree with `EXPLAIN` on Connector/J's default (client-side prepare, values inlined,
`Com_stmt_execute` 0) and on `useServerPrepStmts=true` (`Com_stmt_execute` 1): MySQL prunes at each execution on both.
`RANGE (TO_DAYS(created_at))` isn't as clean as `RANGE COLUMNS(created_at)`: two partitions for the two-sided form, the
target and the first one (`p202201`, where `to_days(NULL)` goes), and 20 for the lower bound. Worth a line in the guide.

### 3 Cost

Wall time is the median of 7 runs after 3 warm-ups (14 for the 50-key cells), 20 000 rows per month. "H" is wall time
through Hibernate and the executor path; "J" replays that run's captured statements on a bare JDBC connection (round
trips and the database only). Each statement through Hibernate costs about 5 ms more than the same statement on JDBC
(36 statements: 185 to 205 ms against 11 to 16 ms on JDBC), and that includes the emulator's own `build()` per round.
The engine does the same criteria translation per round, so its overhead is of that order, but it is not measured here.
Ratios are grouped over ungrouped, below 1 means grouping wins; the ranges span the two vendors and the two lines;
H is the Hibernate figure.

**`byKeys`, two-sided range, `assumeInRange`**, keys spread evenly over the groups (months):

| Keys | 1 group | 3 groups | 12 groups | 36 groups |
|---|---|---|---|---|
| 50 | H 0.56 to 0.78, J 0.2 to 0.4 | H 1.8 to 2.1, J 0.4 to 0.7 | H 7 to 8, J 1.3 to 2.8 | **H 19 to 25, J 3.4 to 10** |
| 200 | H 0.2 to 0.8, J 0.03 to 0.3 | H 0.7 to 1.8, J 0.07 to 0.4 | H 2.4 to 6.7, J 0.2 to 1.1 | H 7 to 19, J 0.65 to 3.1 |
| 1000 | H 0.2 to 0.4, J 0.05 to 0.2 | H 0.5 to 0.8, J 0.15 to 0.2 | H 1.6 to 3.8, J 0.15 to 0.5 | H 4.4 to 6.6, J 0.4 to 0.7 |
| 10 000 | H 0.26 to 0.33, J 0.07 to 0.2 | H 0.3, J 0.1 to 0.2 | H 0.5 to 0.8, J 0.2 | **H 1.0 to 1.7, J 0.26 to 0.5** |

Statements: 1 group is 1 (2 above 8192 keys, as ungrouped); 3, 12 and 36 groups are 3, 12 and 36. The absolute times for
10 000 keys: ungrouped 150 to 185 ms (PostgreSQL), 212 to 250 ms (MySQL), almost all of it the database probing 36
partitions per key (J 108 to 125 ms and 177 to 212 ms); grouped into 1 to 3 months 39 to 70 ms.

**Where grouping stops paying off.** Through Hibernate, a group needs **about 300 keys or more**: 1000 keys over 3 groups
(333 each) win at 0.5 to 0.8, over 12 (83 each) lose at 1.6 to 3.8; 10 000 over 12 (833 each) win at 0.5 to 0.8, over 36
(278 each) break even at 1.0 to 1.7. On bare JDBC the database's saving covers a round trip much sooner, at about 5 keys
per group on PostgreSQL (200 keys over 36 groups: 0.65; 50 over 12: 2.5 to 2.8) and 10 to 20 on MySQL (200 over 12: 0.96 to
1.05; 1000 over 36: 0.65 to 0.73). PostgreSQL's ungrouped cost is largely planning an `IN` over 36 partitions
(J 1.5 ms for 50 keys but 18 ms for 200); MySQL's is the probes (`Handler_read_key` 108 against 3).

**A 50-row page whose keys spread over 36 months**: 36 statements instead of 1. Ungrouped 8 to 15 ms (H), 1.6 to 4.7 ms
(J); grouped 184 to 204 ms (H), 11 to 16 ms (J): **19 to 25 times slower through Hibernate, 3.4 to 10 times on JDBC**. A
child load of 50 parents: 31 statements, 16 to 19 times slower (H). Every page of an export pays it again.

**Child load, lower bound only** (to-many `order_id`, `assumeInRange`): never faster through Hibernate at 50 to 1000 keys
(1.0 to 21 times slower), and 1.2 to 2.0 times slower at 10 000. On JDBC it wins only at 1000 keys in few groups on
PostgreSQL (0.53 to 0.61) and at 10 000 keys over 36 months on MySQL (0.77 to 0.97). It prunes correctly, but only the
partitions before each parent's month (about half of the 36, 19 for the example), while an order created before its
line puts the same lines in up to 3 groups: 10 000 orders were 38 statements against 1 or 2. Grouping that child by a coarser unit (a quarter or a year of the parent's date) would keep the groups few.

**`verifyMissing`**. With no key outside its range the cost equals `assumeInRange` (same rounds, no extra statement: the
two lines of the `+verify(no skew)` rows differ by noise). With a share of keys whose id month disagrees with
`created_at` (injected by the group function naming the next month), the rounds miss them and one unpruned re-read
follows, 37 statements instead of 36 in every cell, as the design says (one chunk up to 8192 keys). Without the re-read
`assumeInRange` loses exactly those keys (asserted: 1 % and 10 % of the keys were missing from the result):

| Keys | Skewed | Ungrouped H / J ms | Grouped + re-read H / J ms | Ratio H, J |
|---|---|---|---|---|
| 1000 | 1 % | PG 44 / 33, MySQL 27 to 29 / 21 | PG 184 to 254 / 14, MySQL 187 to 257 / 17 to 21 | 4 to 9, 0.45 to 1.0 |
| 1000 | 10 % | PG 44 to 49 / 33, MySQL 27 to 32 / 22 | PG 216 to 240 / 16 to 19, MySQL 195 to 279 / 24 | 4.9 to 8.8, 0.5 to 1.1 |
| 10 000 | 1 % | PG 166 to 186 / 120 to 129, MySQL 249 to 269 / 211 to 235 | PG 305 to 337 / 62 to 66, MySQL 284 to 294 / 55 to 57 | 1.1 to 1.9, 0.24 to 0.51 |
| 10 000 | 10 % | PG 167 to 195 / 122 to 143, MySQL 235 to 250 / 204 to 209 | PG 334 to 357 / 91 to 94, MySQL 333 to 345 / 131 to 202 | 1.4 to 2.0, 0.63 to 0.99 |

The re-read is a full 36-partition probe of the missing keys, so at 10 % skew it costs as much on MySQL as the whole ungrouped
read (J 202 ms against 204 ms ungrouped). Up to a few percent it is small.

## What would change in the RFC

Nothing breaks the design: pruning works with bound values on all three vendors, both Hibernate lines and the real JDBC
paths. Three things the spike argues for:

1. **A per-run guard against many small groups.** Rounds equal to groups is correct and pruned, but at 5 ms per
   statement a run with few keys per group (a page, an export page, a polymorphic spread) is slower than not grouping.
   The RFC says "no cap on the number of groups" (§2) and rightly refuses to error, but it could offer a way to avoid the
   loss: e.g. a minimum group size below which the engine falls back to one ungrouped round for the whole run, or merges
   small groups into one (their `from` / `to` unioned), as a declared option of `KeyGroups`. Design decision for the author.
2. **§2's lower-bound-only example** (`BY_ORDER_MONTH`) is correct but poor value on this data: report the 19-of-36 and
   the group multiplication, or pick a coarser group in the example.
3. **The round size** is 8192 on every vendor, so the user guide shouldn't derive group sizes from the bind limits.

## Guidance for the user guide

- Group when many keys share a partition: **about 300 or more keys per group** on a Hibernate-backed executor, as measured
  here; fewer wins only on the database side. Keys that cluster (a time-ordered batch, one tenant's page) group well;
  keys spread thin over every partition do not.
- Don't group a small page of keys spread over all the partitions (50 keys over 36 months is 36 statements, about 20 times
  slower); key groups apply per `byKeys` call or per child round, so every page of an export pays this.
- Choose `verifyMissing` when ids may disagree with the partition column: it costs nothing while the ids agree, and one
  unpruned statement for the missing ones. Beyond roughly 10 % misses the re-read is a large share of the ungrouped cost.
  `assumeInRange` silently drops keys outside the range.
- Bind the exact range with `verifyMissing`; a margin widens the scan (a one-day margin on a month overlaps 3
  partitions).
- A lower bound alone only drops the earlier partitions (19 of 36 for a mid-range parent; roughly half on average) and its
  groups multiply when parents precede children. Prefer a two-sided range, or a coarse group.
- Bound values are the attribute type, an `Instant` for `timestamptz` / `DATETIME`. They reached the driver as
  `Timestamp` on both lines, which prune on PostgreSQL and MySQL; the guide should say the JVM and session time zone must
  agree (not tested here).
- PostgreSQL prunes at run time with generic plans and at plan time with custom ones; MySQL prunes at each execution,
  with client-side or server-side prepare. `RANGE COLUMNS(created_at)` is cleaner than `RANGE (TO_DAYS(created_at))`, which
  keeps the first partition in every scan.
- The primary key must contain the partition column on MySQL; the JPA `@Id` can still be `id` alone (the spike mapped it
  so), since the statements filter by both `id` and `created_at`.
