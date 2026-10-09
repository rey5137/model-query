# 20 — Execution: list, page, count, stream, single-row reads

**Covers:** what each executor method does, how counting works for grouped and collection-join queries, limit and page
validation, the connection lifetime of a stream, and the single-row reads `one` and `first`.
**Read when:** changing `list`/`page`/`count`/`stream`/`one`/`first`, or explaining a wrong total or a leaked
connection.
**Owns:** `R-EXE-*`, `AC-EXE-*`. Paging, cursors and export are `engine/21`.

---

## 1. `list`

**R-EXE-01** `list(q, limit)` runs one query and returns the mapped rows in order. `Limit.of(0)` returns an empty list
without touching the database. `Limit.unlimited()`, or `Limit.of(null)`, applies no `maxResults`. A fetch plan runs
once on the returned rows (`api/15` R-FCH-09): its children, then its join plans, each over the present nested models
read from their rows under the join, then its enrichers in order, so a nested plan's enrichers run first (`api/15`
R-FCH-08).

## 2. `page`

**R-EXE-02** *(was R8)* `page(q, pageSpec, mode)` returns a `Slice<M>` that is well-formed in every mode. A `PageSpec`
is built by `PageSpec.of(pageNumber, pageSize)` or `PageSpec.ofOffset(offset, pageSize)` and has no public
constructor, so a page number cannot be read as an offset (D-88).

| `CountMode` | Behaviour |
|---|---|
| `COUNT` | Runs the count query (R-EXE-03, R-EXE-04) and reports an exact total. |
| `NO_COUNT` | Fetches `pageSize + 1` rows to compute `hasNext`, returns `pageSize` of them, and reports the total as unknown. Page number and size are preserved. |
| `ONLY_COUNT` | Runs the count query only; the content list is empty. |

A `Slice` never reports a total of `0` when it holds rows, and never reports `hasNext == false` because the count was
skipped. A fetch plan runs on the content only, after the `NO_COUNT` probe row is dropped, and `ONLY_COUNT` runs none
(`api/15` R-FCH-09).

## 3. `count`

**R-EXE-03** *(was R6)* `count` over a **grouped** query counts groups, not rows:
`select count(*) from (<grouped query>)`, built with Hibernate's `JpaCriteriaQuery#createCountQuery()` when
`model-query-hibernate` is present, as its `ProviderSupport#countQuery` (D-34), and run by the executor like any
other statement, so the configured timeout applies (R-EXE-11). The portable fallback counts rows
client-side and logs a warning, because a correct-but-slow total is better than a wrong one (INV-5).

**R-EXE-04** *(was R7)* `count` is not inflated by collection joins. After predicates are built, the engine inspects
`root.getJoins()` recursively and uses `count(distinct root)` only when a to-many join exists. A query using
`Filters.exists` instead of a join needs neither (`api/12` R-FLT-12). When a selected column is read through a to-many
join, each joined row is a result, so `count` counts rows and agrees with `list`; key-based paging refuses that shape
(`engine/21` R-PAG-13).

**R-EXE-05** The count query drops `orderBy` and any selection the count does not need, and keeps every predicate and
join that can change the number of matching rows. A direct `count(q)` on a query with an `orderBy` logs one `WARNING`
per `ModelQuery`, `<Model>: count(query) ignores the query's orderBy`; the count `page` runs for its total logs
nothing, since `page` uses the order (D-126).

## 4. Limits and validation

**R-EXE-06** *(was R9)* `limit == 0` returns an empty result without querying. `pageSize <= 0` throws `MQ2001`. A
negative offset throws `MQ2002`. A negative `Limit` throws `MQ2001`. `ExportOptions.pageSize` follows the same rule.

## 5. `stream`

**R-EXE-07** *(was R10)* The only streaming API is `stream(q, limit, Function<Stream<M>, R> body)`. The engine opens the
stream, passes it to `body`, and closes it in a `finally` block. No method returns an open `Stream`, so a caller cannot
leak a JDBC result set (D-8). A fetch plan with a child, join plan or enricher runs once per page, and a stream has
none, so `stream` with one throws `MQ2605` before any statement (`api/15` R-FCH-09).

**R-EXE-08** Before execution the engine calls `checkStreamingPreconditions(em)`, then asks the profile for the fetch
size with `VendorProfile.streamingFetchSize(requested)`, `requested` being `ModelQueryConfig.streamFetchSize()`
(default 500, `api/11` R-QRY-15), and has the factory's `ProviderSupport` open the stream with it,
`resultStream(query, size)` (`vendor/40` R-VND-12, D-108). On PostgreSQL, streaming outside a transaction fails fast
with `MQ2101` rather than silently buffering the whole result in the driver (`vendor/41`). With no `ProviderSupport`
the engine streams with `getResultStream()` and sets no fetch size, and the first `stream` on the factory logs a
`WARN` that the driver may buffer the whole result.

**R-EXE-09** An early exit from `body` — `findFirst`, a `break`, an exception — still closes the stream and releases the
connection.

**R-EXE-10** `stream` is for a single pass over a large result inside one transaction. For exporting millions of rows,
keyset `export` is the documented default, because it runs short queries per page instead of holding one result set open
(`engine/21`).

## 6. Timeouts

**R-EXE-11** A per-query timeout comes from `ModelQueryConfig` or `modelquery.query-timeout` and is applied through
`VendorProfile.applyTimeout`. The profile documents its granularity: `jakarta.persistence.query.timeout` becomes
`Statement.setQueryTimeout`, which has one-second granularity (`vendor/41`). `ModelQueryConfig.queryTimeout(Duration)`
must be positive (`MQ4003`) and applies to every statement the executor runs; unset means none. The executor reports a
statement its timeout cancelled as `QueryTimeoutException` on every supported Hibernate version, translating a
cancellation (SQLState `57014`) a provider reports as a plain `PersistenceException`.

## 7. Single-row reads

**R-EXE-12** `one(q)`, `first(q)` and `one(q, key)` read at most one model and return it as an `Optional`; each is
abstract and `@Incubating` on `ModelQueryExecutor` (D-123). A default built on `list` would run the fetch plan on the
extra row, so none is one (`delivery/61` R-REL-07).

- `one(q)` renders the statement `list` would, with a limit of 2. A second row throws `MQ2003`
  (`OrderView: one(query) found more than one row`) before any row is mapped or the fetch plan runs; the fetch plan
  then runs once, on the single row left, so an enricher never sees the extra row. `one` counts rows as `list`
  returns them: `list` does not deduplicate, so a predicate-only to-many join (R-EXE-04, `engine/21` R-PAG-02) can
  repeat the root row and give `MQ2003`. The message then names the join and points at `Filters.exists`.
- `first(q)` renders with a limit of 1 and a stable order: `q`'s `orderBy`, then the primary key (or, for a grouped
  query, its group keys) wherever the `orderBy` does not already cover it. With no `orderBy`, that is the key
  ascending. An implicit column that can be NULL sorts `nullsLast` explicitly (`api/10` R-COL-12), since vendors place
  NULL differently (INV-6); a column that cannot (an `@Id`, a non-optional or a primitive attribute, not read through
  a LEFT join) sorts plain ascending, so an index can satisfy `ORDER BY ... LIMIT 1` where the vendor renders
  `nullsLast` as a `CASE`. When the selection reads through a to-many join the key spans several rows, so the chosen
  row is not deterministic, as with offset paging (`engine/21` R-PAG-13 refuses only key-based paging). A query with neither an
  `orderBy` nor a key throws `MQ2203` (`api/11` R-QRY-03) before any statement; a grouped query's key is its group
  keys, and one that only aggregates (no `groupBy`) has exactly one row, so it needs neither.
- `one(q, key)` adds `primary key = key` to `q`'s filter, converting each component as `whereKey` does
  (`api/14` R-WRT-08), and behaves as `one(q)`. A `q` that already filters on its key is accepted: the filters AND
  together, and contradictory ones give empty. A `null` key, a `null` component, or a composite key with the wrong
  number of components throws `IllegalArgumentException`. A query without a primary key throws `MQ2203`, before any
  statement: a keyless query has `K = Object`, and a grouped query keeps the builder's `K` but drops its key
  (`api/13` R-AGG-09), so in both any key compiles. A second row is possible only for a `@PrimaryKey` that is not the
  id (a unique column on a view), and is `MQ2003` there too.
- `one(q)` and `one(q, key)` read at most one row, so neither renders an `ORDER BY`; each logs one `WARNING` per
  `ModelQuery` and call when `q` has an `orderBy`, `<Model>: one(query) ignores the query's orderBy` (`one(query,
  key)` for the keyed one). `first` uses the order and is unchanged (D-126).
- All three take fetch plans and customizers as `list` does. `primaryKeyFirst(...)` applies only past its offset
  threshold, so at offset 0 it has no effect. The SQL is `list`'s with a limit (and, for `first`, the order above),
  so `one` and `first` add no vendor surface.

**R-EXE-13** `byKeys(q, keys)` reads the models of many keys into an unmodifiable `Map<K, M>`; it is abstract and
`@Incubating` on `ModelQueryExecutor` (D-126, `delivery/61` R-REL-07).

- Each key is converted as `whereKey` converts it (`api/14` R-WRT-08) and ANDed with `q`'s filter, as `one(q, key)`
  does; a filter that excludes a key's row leaves that key out. The map iterates in the order of the keys' first
  occurrence, and a key with no row is absent.
- Checks run in this order, all before the empty-keys shortcut: `q` and `keys` non-null (`NullPointerException`);
  `MQ2203` for a query without a primary key, a grouped one included; then every key converted
  (`IllegalArgumentException` for a `null` key or component, a converter returning `null`, or a composite key with the
  wrong number of components). No keys gives an empty map and runs no SQL.
- The converted keys are spread over statements as `engine/21` R-PAG-07 step 2 spreads its keys (each chunk at most
  the largest power of two within `maxInListSize()` and `maxBindParameters()` less the statement's own binds, one
  bind per key component), without `primaryKeyFirstBatchSize`. Every statement is built in `Phase.MODEL` and renders no
  `ORDER BY`; `q`'s `orderBy` is ignored, with one `WARNING` per `ModelQuery`, `<Model>: byKeys(query, keys) ignores
  the query's orderBy; the map is in key order`.
- Every chunk is read, then checked, then mapped. A key matching two rows is `MQ2003` (`<Model>: byKeys(query, keys)
  found more than one row for key <k>`, naming the filter-only to-many join and `Filters.exists` as `one` does). A row
  whose key equals none of the requested values (a case-insensitive or padding collation, a `BigDecimal` scale) is
  `MQ2005`, naming the value. Neither lets `afterMap` or the fetch plan see a row.
- Rows are matched to keys by the converted attribute value (`api/10` R-COL-11). Every caller key whose value matched
  gets an entry, so keys converting to one value share one model; `afterMap` runs once per row (`api/11` R-QRY-05).
  The fetch plan runs once over every row, in key order (`api/15` R-FCH-05). Memory is the whole result.

## 8. Acceptance criteria

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
| AC-EXE-11 | `one` reads at a limit of 2 and returns the single row or empty; a second row throws `MQ2003` before an enricher or `afterMap` sees it, and through a predicate-only to-many join the message names the join and `Filters.exists` (R-EXE-12). |
| AC-EXE-12 | `first` orders by `orderBy`, then the key with explicit `nullsLast`: on a nullable non-id `@PrimaryKey` it returns the same row on every Tier-1 vendor; a grouped `first` orders by its group keys; a query with neither an `orderBy` nor a key throws `MQ2203`, an aggregate-only query excepted (R-EXE-12). |
| AC-EXE-13 | `one(q, key)` ANDs the converted key with `q`'s filter, and contradictory filters give empty; a `null` key or component, or a wrong component count, throws `IllegalArgumentException`; a keyless or grouped query throws `MQ2203` (R-EXE-12). |
| AC-EXE-14 | `byKeys` returns the map in first-occurrence key order with missing keys absent and duplicates read once, for single and composite keys; more keys than the vendor's limits give several statements and one fetch-plan run, and a configured `primaryKeyFirstBatchSize` does not change the chunks; no keys run no SQL; the `orderBy` is ignored with one `WARNING` and no `ORDER BY`; a key matching two rows is `MQ2003`; a keyless query is `MQ2203`, with empty keys too; two keys converting to one value are both present with `afterMap` run once; a `MODEL`-phase customizer applies; a case-insensitive string key on MySQL and SQL Server whose row matches no requested value is `MQ2005` (R-EXE-13). |
| AC-EXE-15 | `count(q)`, `one(q)` and `one(q, key)` on an ordered query each log one `WARNING` per `ModelQuery`; the `one` statements render no `ORDER BY`; `page`'s count and `first` log nothing (R-EXE-05, R-EXE-12). |
