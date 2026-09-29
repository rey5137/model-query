# 20 — Execution: list, page, count, stream

**Covers:** what each executor method does, how counting works for grouped and collection-join queries, limit and page
validation, and the connection lifetime of a stream.
**Read when:** changing `list`/`page`/`count`/`stream`, or explaining a wrong total or a leaked connection.
**Owns:** `R-EXE-*`, `AC-EXE-*`. Paging, cursors and export are `engine/21`.

---

## 1. `list`

**R-EXE-01** `list(q, limit)` runs one query and returns the mapped rows in order. `Limit.of(0)` returns an empty list
without touching the database. `Limit.unlimited()` applies no `maxResults`.

## 2. `page`

**R-EXE-02** *(was R8)* `page(q, pageSpec, mode)` returns a `Slice<M>` that is well-formed in every mode:

| `CountMode` | Behaviour |
|---|---|
| `COUNT` | Runs the count query (R-EXE-03, R-EXE-04) and reports an exact total. |
| `NO_COUNT` | Fetches `pageSize + 1` rows to compute `hasNext`, returns `pageSize` of them, and reports the total as unknown. Page number and size are preserved. |
| `ONLY_COUNT` | Runs the count query only; the content list is empty. |

A `Slice` never reports a total of `0` when it holds rows, and never reports `hasNext == false` because the count was
skipped.

## 3. `count`

**R-EXE-03** *(was R6)* `count` over a **grouped** query counts groups, not rows:
`select count(*) from (<grouped query>)`, run through Hibernate's `SelectionQuery#getResultCount()` when
`model-query-hibernate` is present. The portable fallback counts rows client-side and logs a warning, because a
correct-but-slow total is better than a wrong one (INV-5).

**R-EXE-04** *(was R7)* `count` is not inflated by collection joins. After predicates are built, the engine inspects
`root.getJoins()` recursively and uses `count(distinct root)` only when a to-many join exists. A query using
`Filters.exists` instead of a join needs neither (`api/12` R-FLT-12). When a selected column is read through a to-many
join, each joined row is a result, so `count` counts rows and agrees with `list`; key-based paging refuses that shape
(`engine/21` R-PAG-13).

**R-EXE-05** The count query drops `orderBy` and any selection the count does not need, and keeps every predicate and
join that can change the number of matching rows.

## 4. Limits and validation

**R-EXE-06** *(was R9)* `limit == 0` returns an empty result without querying. `pageSize <= 0` throws `MQ2001`. A
negative offset throws `MQ2002`. A negative `Limit` throws `MQ2001`. `ExportOptions.pageSize` follows the same rule.

## 5. `stream`

**R-EXE-07** *(was R10)* The only streaming API is `stream(q, limit, Function<Stream<M>, R> body)`. The engine opens the
stream, passes it to `body`, and closes it in a `finally` block. No method returns an open `Stream`, so a caller cannot
leak a JDBC result set (D-8).

**R-EXE-08** The engine calls `VendorProfile.applyStreaming(query, fetchSize)` before execution and
`checkStreamingPreconditions(em)` before that. On PostgreSQL, streaming outside a transaction fails fast with `MQ2101`
rather than silently buffering the whole result in the driver (`vendor/41`).

**R-EXE-09** An early exit from `body` — `findFirst`, a `break`, an exception — still closes the stream and releases the
connection.

**R-EXE-10** `stream` is for a single pass over a large result inside one transaction. For exporting millions of rows,
keyset `export` is the documented default, because it runs short queries per page instead of holding one result set open
(`engine/21`).

## 6. Timeouts

**R-EXE-11** A per-query timeout comes from `ModelQueryConfig` or `modelquery.query-timeout` and is applied through
`VendorProfile.applyTimeout`. The profile documents its granularity: `jakarta.persistence.query.timeout` becomes
`Statement.setQueryTimeout`, which has one-second granularity (`vendor/41`).

## 7. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-EXE-01 | `Limit.of(0)` and `pageSize + 1` probing are observable in the SQL snapshot; no query runs for a zero limit (R-EXE-01, R-EXE-06). |
| AC-EXE-02 | `NO_COUNT` reports `hasNext` correctly on an exact multiple of the page size (R-EXE-02). |
| AC-EXE-03 | `count` over a grouped query equals the number of groups, with and without `model-query-hibernate` (R-EXE-03). |
| AC-EXE-04 | `count` over a query with a to-many join equals the number of distinct roots (R-EXE-04). |
| AC-EXE-10 | `count` over a query selecting a column through a to-many join equals the number of rows `list` returns (R-EXE-04). |
| AC-EXE-05 | `pageSize <= 0` throws `MQ2001`; a negative offset throws `MQ2002` (R-EXE-06). |
| AC-EXE-06 | Streaming 20 000 rows keeps heap bounded; the assertion fails if the engine buffers (R-EXE-07). |
| AC-EXE-07 | An early exit from `body` releases the connection, checked against the pool's active count (R-EXE-09). |
| AC-EXE-08 | PostgreSQL streaming without a transaction throws `MQ2101` before executing (R-EXE-08). |
| AC-EXE-09 | A slow query is cancelled by the configured timeout on every Tier-1 vendor (R-EXE-11). |
