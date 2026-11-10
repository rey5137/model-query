# RFC 0023 spike data

Raw data of the RFC 0023 (key groups) acceptance spike, read by [`../0023-spike-results.md`](../0023-spike-results.md).
This is research on the `rfc-0023-spike` branch: one test-source module `spike-rfc0023/` behind the root `pom.xml`
profile `spike-rfc0023`. Production sources, SQL snapshots and `docs/spec/` are unchanged. `KeyGroups` does not exist
yet, so `Emulator` runs what the engine would: the keys grouped by `group(key)`, each group split into rounds, each
round the key `IN` filter plus `created_at >= :createdFrom [and created_at < :createdTo]` with the group's values bound
per round through the existing `QueryParam` / `bind` API. `byKeys` rounds go through the real
`ModelQueryExecutor#byKeys`; child rounds are `ModelQueryExecutor#list` reads chunked at 8192 keys (the chunk `byKeys`
uses on every vendor here). "Ungrouped" is today's behaviour: no range condition, the keys chunked by the same limits.

## Data set

`event_log`, 36 monthly partitions (2022-01 to 2024-12): 20 000 rows per month, 720 000 rows, on PostgreSQL and MySQL;
100 rows per month in an unpartitioned table on H2 (correctness only). `id` is a snowflake-style key (millis since
2020-01-01 UTC, shifted left 22 bits, plus a sequence); `created_at` is the partition column and always agrees with
the id's instant, so a "wrong group" is injected by the probe (the group function names the next month), which is what a
row whose id month disagrees with its `created_at` looks like to the engine. `order_id` (indexed) is the id of an
"order" created 0 to 40 days before the row, truncated to the hour: a to-many child key whose rows can live in the
order's month or later. The JPA `@Id` is `id` alone; the DDL primary key is the composite `(id, created_at)` (MySQL
needs the partition column in every unique key). The DDL is native (`Events.seed`), not Hibernate schema generation.
MySQL has a second copy, `event_log_td`, partitioned by `to_days(created_at)`, used only for `EXPLAIN`.

## Files

- `rfc0023-<line>.tsv`: functional cells per Hibernate line: `vendor`, `probe` (`0` setup, `1` rendering and binds,
  `2` pruning), `case`, `status`, `value`.
- `rfc0023-cost-<line>.tsv`: item 3. `*_ms` is wall time through Hibernate and the engine path (median of 7 runs after
  3 warm-up runs; 14 for the 50-key page cells); `*_jdbc_ms` replays that run's captured statements on a bare JDBC
  connection (round trips and the database, no Hibernate), same median rule.
- `plans/<line>/pg-<form>-<plan_cache_mode>.txt`: PostgreSQL `EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF)` of
  `PREPARE` + `EXECUTE` of the captured SQL. `plans/<line>/mysql-<form>-<table>.txt`: MySQL `EXPLAIN` partitions and
  `EXPLAIN ANALYZE` with the binds inlined as literals.

`<line>` is `6.6.58.Final` or `7.4.14.Final`.

## Reproduce

From the repository root, with Docker running (Testcontainers: `postgres:16-alpine`, `mysql:8.0.31`). Each run
appends to its line's TSVs, so delete `rfc/0023-spike-data/*.tsv` and `plans/` first. One Hibernate line at a time:

```sh
rm -f rfc/0023-spike-data/*.tsv; rm -rf rfc/0023-spike-data/plans
./mvnw -q -P spike-rfc0023 -pl spike-rfc0023 -am test -Dtest='Rfc0023*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dhibernate.version=6.6.58.Final

./mvnw -q -P spike-rfc0023 -pl spike-rfc0023 -am test -Dtest='Rfc0023*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dhibernate.version=7.4.14.Final -Djakarta-persistence.version=3.2.0
```

(Each line writes its own `<line>`-suffixed files, so delete once, before the first command.)
`-Drfc0023.cost=false` skips item 3; `-Drfc0023.runs=N` sets the measured runs (default 7). A full run takes about
10 minutes per line.

Pruning, how it is measured:

- PostgreSQL: `plan_cache_mode=force_generic_plan` (and `force_custom_plan`) `EXPLAIN (ANALYZE) EXECUTE` of the captured
  SQL; then the real JDBC path through Hibernate, eight executions on one connection with `prepareThreshold=1` and with
  `plan_cache_mode=force_generic_plan` in the connection options, reading `pg_prepared_statements` (generic and custom
  plan counts) and the per-partition scan deltas of `pg_stat_user_tables`.
- MySQL: `EXPLAIN` `partitions` with literals; on the JDBC path (Connector/J default, then `useServerPrepStmts=true`,
  checked with `Com_stmt_execute`) the session's `Handler_read_key` delta per execution, and the partitions holding
  record locks in `performance_schema.data_locks` after a `FOR SHARE` read (each partition touched shows up).
