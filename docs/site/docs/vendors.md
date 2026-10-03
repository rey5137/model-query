# Vendor notes

The library is vendor-aware for the databases it is tested on and conservative for the rest. A **vendor profile**
holds the database-specific facts the engine needs: how to stream, which limits to respect, how NULLs sort. The profile
is resolved once per `EntityManagerFactory` and logged once at `INFO` with how it was resolved, because a wrong profile
gives correct-looking results with the wrong limits.

## Support tiers

| Tier | Database | Versions | Tested |
|---|---|---|---|
| 1 | H2 | 2.2+, native mode | every pull request |
| 1 | PostgreSQL | 14, 15, 16, 17 | every pull request (Testcontainers) |
| 1 | MySQL | 8.0, 8.4 | every pull request (Testcontainers) |
| 3 (community) | Oracle, SQL Server, others | | profile contributions welcome |

A Tier-1 release needs the whole compatibility suite to pass on every Tier-1 version. A Tier-3 profile is
community-supported and never gates a release.

## What a profile decides

| Concern | H2 | PostgreSQL | MySQL |
|---|---|---|---|
| Streaming | positive fetch size | positive fetch size; the driver uses a cursor only when **autocommit is off**, so run in a transaction | `row-by-row` by default, or cursor fetch by configuration |
| Query timeout | `jakarta.persistence.query.timeout` | the same | the same |
| `IN` list size (soft limit) | 10 000 | 10 000 | 10 000 |
| Bind parameters (max) | 100 000 | 65 535 | 65 535 |
| NULLs in ascending order | first | **last** | first |
| Explicit `NULLS FIRST/LAST` | native | native | emulated by Hibernate |
| Target table in an `UPDATE`/`DELETE` sub-query | yes | yes | **no** (error 1093); such writes run key-first |

The streaming fetch size reaches the driver through the provider's `ProviderSupport`, which opens the stream itself:
`model-query-hibernate` for Hibernate. Without one, the library sets none, so the driver's default applies, which on
PostgreSQL and MySQL reads the whole result into memory, and the first `stream` on each `EntityManagerFactory` logs a
warning saying so.

Longer `IN` lists are split into chunks automatically. One filter with more values than the bind-parameter limit fails
with `MQ1306` when the query is built, and a statement whose values only together pass it fails with `MQ1307` before it
runs. Only the key lists the library builds itself, for primary-key-first paging and bulk writes, are spread over
several statements, each holding the largest power of two of keys within the limits, so Hibernate's
`hibernate.query.in_clause_parameter_padding` cannot push one over.

## How the vendor is detected

1. `ModelQueryConfig.vendor(...)` or `modelquery.vendor`, if set. Nothing is detected.
2. With `model-query-hibernate` on the classpath, the Hibernate dialect. This needs no connection and is correct per
   `EntityManagerFactory`, so an application with several datasources on different databases resolves each one.
3. Otherwise `DatabaseMetaData#getDatabaseProductName()`, read once per factory from the factory's
   `jakarta.persistence.nonJtaDataSource` property.

An unrecognised database, or a recognised one without a profile (MariaDB, Oracle, SQL Server), resolves to the
`OTHER` profile. It is deliberately conservative: fetch size 500, `IN` lists of 1 000, 2 000 bind parameters, unknown
null ordering and no target table in sub-queries. Under `OTHER`, keyset paging over a nullable column without explicit
null precedence is refused. If your database is one of the Tier-1 vendors but resolves to `OTHER`, add
`model-query-hibernate` or set the vendor explicitly.

## H2

- **Keep H2's default null ordering.** With `DEFAULT_NULL_ORDERING` set to anything else, Hibernate's H2 dialect
  assumes NULLs sort smallest and leaves an explicit `nullsFirst()`/`nullsLast()` bare where it is needed. NULLs are
  then misplaced, and keyset paging may skip them even with explicit precedence. The library cannot detect this without
  a connection.

## PostgreSQL

- **Streaming needs a transaction.** The PostgreSQL driver only uses a cursor when autocommit is off. Outside a
  transaction, `stream` fails fast with `MQ2101` instead of buffering the whole result. With Spring Data, the
  repository opens a read-only transaction for you, so this mostly guards plain-JPA code.
- Text comparison is **case-sensitive** and follows column collation. `likeIgnoreCase` lower-cases the column and the
  value; a database whose `lower()` folds only ASCII, such as PostgreSQL with `C` collation, can disagree on non-ASCII
  text, and the filter needs a functional index to be fast.
- NULLs sort **last** in ascending order. Keyset paging over a nullable column therefore wants an explicit
  `nullsFirst()` or `nullsLast()`.

## MySQL

- **Streaming default (`row-by-row`).** MySQL streams with `fetchSize = Integer.MIN_VALUE` by default. It is the
  fastest mode, but it **holds the connection until the whole result has been read and blocks other statements on
  that connection** until then. This is why keyset `export`, which runs a short query per page, is the recommended way
  to read large results. If blocking the connection does not suit you, switch to cursor fetch.
- **Cursor fetch.** Set `modelquery.mysql.streaming-mode=cursor-fetch` (or
  `ModelQueryConfig.mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH)`) and add **`useCursorFetch=true` to the JDBC
  URL**. The library cannot set the URL for you: without it, Connector/J ignores the fetch size and buffers the whole
  result. Cursor fetch uses `modelquery.stream.fetch-size` and does not block the connection. The mode is read once when
  the profile is created; it is never decided per query.
- **Timeouts.** `jakarta.persistence.query.timeout` becomes `Statement.setQueryTimeout`, which has one-second
  granularity, so a sub-second timeout is rounded up. Connector/J cancels a query by opening a second connection to run
  `KILL QUERY`, which costs one extra connection per cancelled query. Count this in your pool size if your timeouts are
  short.
- **`*_ci` collations are case-insensitive**, so `eq` matches `'a'` and `'A'`.
- NULLs sort **first** in ascending order, and explicit `NULLS FIRST/LAST` is emulated by Hibernate with an `ISNULL`
  sort key.
- **Bulk writes and error 1093.** MySQL cannot read the table being updated in a sub-query. When a bulk write's filter
  needs a join, goes through inheritance, or has an `exists(...)` reading the root's table, even through another entity
  mapped to it, the library selects the matching keys first and writes them in chunks. A change to a joined row
  between the two steps is not re-checked; `ChunkOptions.lockKeys()` selects the keys with a pessimistic write lock,
  which on MySQL also reads current rows instead of the transaction's snapshot. Telling two entities on one table apart
  needs `model-query-hibernate`, which reports the tables each entity reads, joined supertables and secondary tables
  included; without it, such a write fails with error 1093.

## What the library leaves to the database

These are not abstracted, and the library never silently emulates them:

- **String comparison and case sensitivity** follow the column's collation: MySQL `*_ci` is case-insensitive, PostgreSQL
  is case-sensitive.
- **JSON, array and enum column types** follow your entity mapping.
- **Boolean storage and date/time precision** follow Hibernate and the JDBC driver. Time zones follow
  `hibernate.jdbc.time_zone`.
- **A default null ordering configured on the provider.** Hibernate's `hibernate.order_by.default_null_ordering` is only
  honoured with `model-query-hibernate`. Without it, the library logs a warning once at startup when it sees the
  setting; add the module, or give your nullable keyset columns explicit null precedence.

If a use case needs uniform behaviour across databases, ask for it explicitly: for example use `eqIgnoreCase`
and an index, instead of relying on a collation.
