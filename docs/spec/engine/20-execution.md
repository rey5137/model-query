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
| `INLINE_COUNT` | `COUNT`'s exact total and `hasNext`, read from the page statement as a window where that statement returns exactly the rows `COUNT` counts, and from `COUNT`'s statements otherwise (R-EXE-15). |

A `Slice` never reports a total of `0` when it holds rows, and never reports `hasNext == false` because the count was
skipped. A fetch plan runs on the content only, after the `NO_COUNT` probe row is dropped, and `ONLY_COUNT` runs none
(`api/15` R-FCH-09).

**R-EXE-15** *(D-177, `@Incubating`)* `CountMode.INLINE_COUNT` is a request about *how* to count, not *whether*: its
`Slice` reports the same total and `hasNext` as `COUNT`'s, always (R-EXE-02's guarantees unchanged). It is opt-in and
never a default; the Spring Data repository default stays `COUNT`, and `ModelPage` reads `Slice.total` unchanged. The
window counts the whole result before the page's `LIMIT`, so the database can't stop early: on a large, unselective
result `COUNT` can be faster. The user chooses the mode; there is no automatic performance fallback.

The window is `count(1) over ()`, rendered through `SourceRenderer#window(cb, WindowSpec(COUNT, cb.literal(1), [], [],
NONE))` (`api/17` R-SRC-04) and gated by `ProviderSupport#inlineCounts(emf)` and a source renderer (`vendor/40`
R-VND-27). The page statement carries it only when it returns exactly the rows `COUNT` counts: when the `Counting` that
`page` builds for `COUNT` (after the customizer, so a customizer's `distinct()` or to-many join is seen) is the content
statement itself with its selection replaced by `count(root)` or `count(1)`, or is the database-side count of its
groups (R-EXE-03). Any other shape falls back, so a shape added later falls back until it is admitted.

The fallback reads the total from `COUNT`'s statements. It applies:

1. with `inlineCounts(emf)` false or `sources()` empty (no `model-query-hibernate`, a dialect without window
   functions, or a provider without a window renderer);
2. on a `DISTINCT` statement;
3. where `COUNT` counts client-side (R-EXE-03: no grouped count support, a source carrying checks, a refused
   `GROUPED_COUNT` treat site), conservatively, even where the content statement would count correctly; an ungrouped
   query over a source carrying checks falls back too, since `COUNT`'s one row reads the checks where an empty page
   leaves them unread (R-SRC-08);
4. where `COUNT`'s statement is not the content statement with its selection replaced: `count(distinct root)` over a
   to-many join that no selected column reads, a customizer's or one a shape R-EXE-16 excludes keeps (a filter's
   own renders as a semi-join, which `COUNT` counts by `count(root)`) (R-EXE-04), a treated, ungrouped root, which `COUNT` counts on a
   freshly built statement (R-EXE-05, D-154), and a primary-key-first page whose step-1 key query carries a to-many
   join (a customizer's `LEFT` join in the `PRIMARY_KEY` phase alone, which R-PAG-15 admits), which repeats keys
   `COUNT` counts once;
5. on a page past the end: no row, offset above 0.

| Shape | Behaviour |
|---|---|
| Window used, rows returned | total = the window's value; `hasNext = offset + rows < total` |
| Window used, no row, offset 0 | total = 0 |
| Window used, no row, offset > 0 | `COUNT`'s statement runs for the total (fallback 5) |
| Grouped, `COUNT` in SQL | the window runs after `GROUP BY` and `HAVING`, so it counts groups (R-EXE-03's meaning) |
| Primary-key-first page | the window is on step 1's key query; step 2 is unchanged (R-PAG-13 keeps key rows as root rows) |
| A fallback of 1–4 | `COUNT`'s two statements, as under `COUNT` |

*Preflight.* `INLINE_COUNT` builds, checks and preflights `COUNT`'s statements exactly as `COUNT` does, before the first
statement runs, whether or not the window is used, since fallback 5 can only be known after the content statement
(`vendor/40` R-VND-26): a refused treat site is `MQ4016` before any statement.

*The hidden column.* The window is selected as `mq_0total`, following `mq_0id` (D-154), outside D-146's
`mq_<property>` space. It is added to the page statement (or step 1's key query) after `buildQuery`, so after the
customizer, whose `multiselect` can't drop it, and after `core` adds `mq_0id`, and before the preflight, so under
treated nodes the statement R-VND-26 checks and runs is the one carrying the window; fallbacks 1–4 are decided there.
It is read by alias, never by position, and never reaches `fields()`, `Rows`, the mapper or a fetch plan.

*Logging.* Each fallback logs its reason at `DEBUG`, once per executor and `ModelQuery`, with the state held by the
executor: fallback 1 depends on the factory, and a `static final` query is shared between executors (INV-9). A query re-sorted per call (`orderedBy`, as a Spring repository's `Sort` does) is a new `ModelQuery` and logs again.

Keyset pages carry no total (D-105) and take no `CountMode`. `page` already refuses row locks (R-EXE-14), so a window
never meets `FOR UPDATE`. `ONLY_COUNT`, `count(q)` and exports are unchanged.

## 3. `count`

**R-EXE-03** *(was R6)* `count` over a **grouped** query counts groups, not rows:
`select count(*) from (<grouped query>)`, built with Hibernate's `JpaCriteriaQuery#createCountQuery()` when
`model-query-hibernate` is present, as its `ProviderSupport#countQuery` (D-34), and run by the executor like any
other statement, so the configured timeout applies (R-EXE-11). The portable fallback counts rows
client-side and logs a warning, because a correct-but-slow total is better than a wrong one (INV-5). A grouped query
over a **source that carries checks** takes the client-side path even when `model-query-hibernate` is present, with the
source's check columns selected beside the group keys: `createCountQuery()` moves the source's CTE into its derived
table, out of the checks' reach (RFC 0014 §3). It counts the group rows it reads, logs no warning — no provider
support could count these groups — and reads the checks off the first of them, so a too-deep tree is `MQ2701` and a
cyclic graph `MQ2702` (R-SRC-08). Zero groups leave the checks unread, as `list` is on an empty result. A grouped
query whose count of groups the `TreatRenderer` refuses as a `GROUPED_COUNT` statement (`vendor/40` R-VND-26) takes
the client-side path too, even when `model-query-hibernate` is present: its sites are checked as `GROUPED` instead
(`MQ4016` when refused there), its statement is preflighted as `GROUPED`, and each such count logs a warning with the
renderer's reason and no bound value (D-95, D-159). Hibernate's renderer refuses it over a `typeOf` group key on a
`JOINED` root without a physical discriminator, where `createCountQuery()` drops the subtype joins and reads one type
for every row, so the total would be silently wrong.

**R-EXE-04** *(was R7; amended by D-177)* `count` is not inflated by collection joins. After predicates are built, the
engine inspects `root.getJoins()` recursively and uses `count(distinct root)` only when a to-many join exists. A query
using `Filters.exists` instead of a join needs neither (`api/12` R-FLT-12), and neither does a filter through a to-many
join that nothing else in the statement keeps, which renders as a semi-join (R-EXE-16), so its `count` is
`count(root)` and equals `list`'s size. The distinct count stays for a customizer's to-many join and for the shapes
R-EXE-16 excludes. When a selected column is read through a to-many join, each joined row is a result, so `count`
counts rows and agrees with `list`; key-based paging refuses that shape (`engine/21` R-PAG-13).

**R-EXE-16** *(D-177, D-180, D-181)* **A filter-only to-many join renders as a semi-join.** When a statement's `where`
makes a to-many join that nothing else in the statement keeps, its terms that join or nest an `exists(...)` render inside
one correlated sub-query over a second root of the same entity, and the statement keeps its root terms and
`exists (select 1 from <entity> r2 <joins> where r2 = r and <terms>)`, so each root comes once however many of its
children match. It is R-WRT-10's rendering (`api/14`), which bulk writes already use:

```sql
select o.id, o.number from orders o
 where o.status = ?
   and exists (select 1 from orders o2 join order_items i on i.order_id = o2.id
                where o2.id = o.id and i.product_code = ?)
```

- **When.** The statement reads an entity root, not a source (sources have no to-many join) and not a fetch plan's
  `through` child load, and has no treated node anywhere (`api/10` R-COL-35): a treat moved into a second-root
  sub-query is a site no `TreatRenderer` has been measured for. No selected column, order term, group key or fetch-plan
  key has made a to-many join, and the `having` makes none. If anything else keeps a to-many join, the statement renders
  as before: moving the `where`'s terms would test them on that join against some child of the second root instead of
  the row's own. A customizer's join is made after the decision and stays outside it.
- **Decision.** A filter is a lambda, so which joins the `where` makes is learnt by rendering each top-level term
  alone into a scratch sub-query over a second root with a fresh `JoinContext`, of a throwaway statement, so nothing
  it binds reaches the statement (D-65); the eligibility is read off those sub-queries' joins by attribute, whatever
  the join type, since an `or` joins `LEFT` in the scratch where the statement would reuse an `INNER` join. The `having` is checked the same
  way. The decision is made in `core` before the `where` renders, since a Criteria join cannot be removed once made.
- **Rendering.** The `where`'s top-level terms split as R-WRT-11 splits a key-first write's (D-181): a root term,
  one whose own scratch render makes no join and no `exists(...)` sub-query, stays on the statement, where an index on
  the root's table can narrow the outer scan; every other top-level term moves, not only those on the to-many join.
  A top-level `or` or `not(...)` is one term and moves whole, since a child term and a root term can sit under it, and
  two terms on the same join move together, so they must hold for the same child. `C(r) and exists (r2 = r and rest)`
  chooses the rows `exists (r2 = r and C and rest)` does, so the split changes no row. The sub-query rebuilds the joins
  with their types, so `or`, `not(...)` and a `LEFT` join's null-extended rows keep their meaning; a nested
  `exists(...)` correlates to the second root. Correlation is entity equality, not key columns, so a read needs no
  `primaryKey` and an embedded id compares whole. A to-one join only the `where` uses is made only in the sub-query.
  `having` renders on the statement with the root terms (D-26), and the `exists` follows the root terms. What the
  executor adds after the build (keyset bounds, `byKeys`, `one(q, key)`, `MODEL_BY_KEYS`, fetch rounds) is ANDed on
  the statement, outside the `exists`.
  Each value binds once (`api/12` R-FLT-09): the scratch render's parameters are dropped with it.
- **Effects.** `list`, `stream` and pages return each root once; `count` is `count(root)` (R-EXE-04); `one(q)`,
  `one(q, key)` and `byKeys` take their strict paths; a grouped query's aggregates count each root once; a locked read
  runs (R-EXE-14). Every phase of a query renders the same way, so primary-key-first paging pages the rows `MODEL`
  returns.
- **Performance.** PostgreSQL ran the semi-join roughly 2–3.5x faster than the join or `select distinct`, MySQL 8.0
  1.4–3.6x slower, with duplicate weedout or a second root lookup, in single EXPLAINs of the RFC 0018 spike. Check the
  application's real plans and indexes; there is no switch back to the duplicating join (D-177).

**R-EXE-05** The count query drops `orderBy` and any selection the count does not need, and keeps every predicate and
join that can change the number of matching rows. A correlated scalar value used only in the select list adds
nothing to the count statement except row-changing joins its lifted columns need; an INNER lifted path stays.
A correlated value used in WHERE remains in the count, and lifted joins needed only inside OR/NOT are LEFT
(R-FLT-10, R-COL-31). A source's check sub-selects are never dropped: a count over a
source that carries checks selects them beside what it counts, on its own statement or on the group rows it counts
(R-SRC-08, RFC 0014 §3). *(D-154)* A count over an ungrouped entity-rooted statement with a treated node
(`api/10` R-COL-35) is not the content statement with its selection replaced, which loses the rows a select-only or
order-only treat narrows away: it is built fresh from the definition, with the `where` predicates and the path of
each column a model or order field reads (inside an `Expr`, a `@Computed` value or a window too, and a correlated
value's lifted outer columns), whole when it holds a join other than `LEFT` and otherwise up to its first treated node,
and it selects `count(root.<id>)` (`count(distinct root.<id>)` when a predicate's to-many join repeats a root, and
rows when a selected column is read through one, as R-EXE-04). A treated column that is only selected or ordered is
absent from it. The customizer runs on it as on `MODEL`. A grouped query keeps its count of groups. A direct `count(q)` on a query with an `orderBy` logs one `WARNING`
per `ModelQuery`, `<Model>: count(query) ignores the query's orderBy`; the count `page` runs for its total logs
nothing, since `page` uses the order (D-126). `count` refuses a locked query with `MQ2211` (R-EXE-14): a count returns
no row to lock.

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
connection. `stream` refuses a locked query with `MQ2211` (R-EXE-14): it holds a cursor across `body`.

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
  returns them: `list` does not deduplicate, so a to-many join no selected column reads can repeat the root row and
  give `MQ2003`, which after R-EXE-16 is a customizer's join or a shape R-EXE-16 excludes (R-EXE-04, `engine/21`
  R-PAG-02). The message then names the join and points at `Filters.exists`.
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
  (`api/13` R-AGG-09), so in both any key compiles. A duplicate key is `MQ2213` (R-PAG-26), including a non-id model
  key matching two rows. A to-many join no selected column reads, which R-EXE-16 left in the statement, instead
  gives `MQ2003` with the join and `Filters.exists` hint above (D-160).
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
- Every chunk is read, then checked, then mapped. A duplicate key is `MQ2213` (R-PAG-26), including a non-id model
  key matching two rows. A to-many join no selected column reads, which R-EXE-16 left in the statement, instead
  gives `MQ2003` (`<Model>: byKeys(query, keys) found more
  than one row for key <k>`, naming the join and `Filters.exists` as `one` does; D-160). A row
  whose key equals none of the requested values (a case-insensitive or padding collation, a `BigDecimal` scale) is
  `MQ2005`, naming the value. Neither lets `afterMap` or the fetch plan see a row.
- Rows are matched to keys by the converted attribute value (`api/10` R-COL-11). Every caller key whose value matched
  gets an entry, so keys converting to one value share one model; `afterMap` runs once per row (`api/11` R-QRY-05).
  The fetch plan runs once over every row, in key order (`api/15` R-FCH-05). Memory is the whole result.

## 8. Locking reads

**R-EXE-14** A query can take a row lock (`api/11` R-QRY-17, D-127). The executor applies it only to the statement that
returns the model's rows, after creating it: never in the shared `create`, which also serves paging, `count`,
primary-key-first and the fetch plan. Every refusal in the table below runs before any statement executes, and before a
zero-limit short cut or `byKeys`' no-keys one, so a read that refuses a lock fails the same way whatever the limit or
keys are.

| Read | With a lock |
|---|---|
| `list(q, Limit)`, `first(q)` | locks every row returned |
| `one(q)` | locks the row returned; with `SKIP_LOCKED`, `MQ2211` |
| `one(q, key)`, `byKeys(q, keys)` | locks every row returned |
| `page(q, PageSpec, …)`, `page(q, KeysetSpec)`, `count`, `stream`, `export` | `MQ2211` |
| `persist(…, returning)` | `MQ1809` (it maps from the entity in memory, so a lock would be ignored) |

- **`one(q)` with `SKIP_LOCKED`** is refused: `one(q)` reads `LIMIT 2` to check uniqueness, and a locked second match
  would be invisible, so the `MQ2003` check would pass when it should fail (INV-5).
- **Paging** is refused: PostgreSQL also locks the rows `OFFSET` skips over, and under READ COMMITTED `ORDER BY` with a
  locking clause can return rows out of order, which breaks a keyset cursor's monotonicity; with `SKIP_LOCKED` a keyset
  cursor moves past locked rows for good, a silent skip (`engine/21`). **`stream` and export** hold a cursor across
  user code, and export also spans transactions.
- **A locked query whose built statement has any join** is `MQ2211`, because a projection loads no entities, so
  Hibernate renders a locking clause with no `OF` list (locking every table on PostgreSQL) or follows on to locking only
  loaded entities, which is nobody (`vendor/41`). The message suggests `Filters.exists(...)` instead; the check reads
  the built criteria statement's roots and their joins, a customizer's included, so it is pure JPA (INV-7).
  *(D-177)* A filter through a to-many join that renders as a semi-join (R-EXE-16) leaves the statement no join, and
  neither does a filter-only to-one join rendered beside it in the sub-query, so such a locked read runs; the
  sub-query's tables are not locked on PostgreSQL or MySQL.
  A path a customizer navigates past the root (`root.get("customer").get("name")`) is an implicit join that no join
  list reports, so it is not refused, and the joined rows are locked too: on a locked query a customizer should name
  only the root's own columns (known limitation, M18 gate).
- **A root entity mapped to more than one table** (`tablesOf`, `vendor/40` R-VND-13: `JOINED` inheritance, a secondary
  table) is `MQ4010`, because only the root table would be locked.
- **A locked read needs an active transaction** (`em.isJoinedToTransaction()`), else `MQ2102`: the provider would throw
  `TransactionRequiredException` at the locked statement anyway, and `MQ2102` gives one library code, checked before any
  statement runs. **`SKIP_LOCKED` on `one(q, key)` and `byKeys`** means a key absent from the result is absent,
  filtered out, or locked; `byKeys` orders each chunk's statement by primary key and, when the query is locked and the keys
  are `Comparable`, sorts the distinct keys before chunking, so concurrent callers lock in the same order across
  chunks; non-`Comparable` keys lock in caller order per chunk.
- **`LIMIT` with a lock on PostgreSQL** (READ COMMITTED): after a `WAIT`, a locked row is re-checked and dropped if it
  no longer matches, after `LIMIT` was applied. So `list(q, Limit)` can return fewer rows, `first` can return empty
  while another matching row exists, and `one(q)`'s `LIMIT 2` can miss a second match (`MQ2003` not raised). MySQL
  keeps scanning. Queue claims should use `SKIP_LOCKED`.
- **Database refusals pass through unchanged**: `PessimisticLockException` (a `NO_WAIT` conflict or a deadlock),
  `LockTimeoutException`, and `QueryTimeoutException` (the factory's `queryTimeout`, R-EXE-11, still bounds the
  statement, lock wait included). On PostgreSQL any of these aborts the whole transaction.
- **Fetch-plan statements carry no lock** (R-FCH-*): the plan's children, join plans and enrichers run their own
  statements, which lock nothing.

## 9. Returning writes

This section adds no `R-EXE-*` of its own: it places `api/14` R-WRT-50 in this file's seam, so the engine's rules are
read with it. `updateReturning`, `deleteReturning` and `insertReturning` run the write through the same seam every
other write uses — the
one holding the finished statement with its parameters bound, its timeout set (`R-EXE-11`) and its assignments, version
clause and filters in it — and hand that statement to the provider instead of calling `executeUpdate()`
(`vendor/40` R-VND-17). So `Expr.value` binds, converters, enums and the key-first decision of `api/14` R-WRT-11 reach a
returning write unchanged, and a chunked write concatenates its chunks' rows. The engine reads no rows and holds none
of its own: the provider returns one `Object[]` per changed row, which the executor maps into the `ModelQuery`'s model.

Before that seam, at method entry and before any statement, the executor refuses a factory that cannot return the rows:
`VendorProfile#returning(WriteStatement)` empty for the statement kind, no `ProviderSupport#returning()`, or
`ReturningSupport.rendered` false (a multi-table root, a Hibernate line whose calls moved). It is `MQ4011`, naming the
provider, the vendor and the statement kind, as `MQ4010` refuses a lock (`R-EXE-14`). An entity-mode write and
`commitEachChunk()` are `MQ1809` (`api/14` R-WRT-50); an insert has no entity-mode form, so only `commitEachChunk()`
arises there.

An arbiter predicate or an `onConstraint(name)` (`api/14` R-WRT-52) reaches the provider through the same seam. At
method entry, before any statement, the executor checks the profile fact the clause stands on —
`conflictArbiterPredicate()` or `conflictOnConstraint()` (`vendor/40` R-VND-14) — and refuses with `MQ1804` where it is
false. The provider then splices the rendered predicate after the translated upsert's conflict target, refusing with
`MQ4011` before execution unless it finds that anchor exactly once (INV-5); the two rewrites, the arbiter and a
returning clause, compose into one statement.

## 10. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-EXE-01 | `Limit.of(0)` and `pageSize + 1` probing are observable in the SQL snapshot; no query runs for a zero limit (R-EXE-01, R-EXE-06). |
| AC-EXE-02 | `NO_COUNT` reports `hasNext` correctly on an exact multiple of the page size (R-EXE-02). |
| AC-EXE-03 | `count` over a grouped query equals the number of groups, with and without `model-query-hibernate`, and client-side when the treat renderer refuses its `GROUPED_COUNT` site, as Hibernate's does for a `typeOf` group key on `JOINED` without a discriminator, whose sites are then checked as `GROUPED` (`MQ4016` when refused there) (R-EXE-03, D-159). |
| AC-EXE-04 | `count` over a query with a to-many join equals the number of distinct roots: by `count(distinct root)` for a customizer's join, and by the semi-join's `count(root)` for a filter-only one (R-EXE-04, R-EXE-16). |
| AC-EXE-10 | `count` over a query selecting a column through a to-many join equals the number of rows `list` returns (R-EXE-04). |
| AC-EXE-05 | `pageSize <= 0` throws `MQ2001`; a negative offset throws `MQ2002` (R-EXE-06). |
| AC-EXE-06 | Streaming 20 000 rows keeps heap bounded; the assertion fails if the engine buffers (R-EXE-07). |
| AC-EXE-07 | An early exit from `body` releases the connection, checked against the pool's active count (R-EXE-09). |
| AC-EXE-08 | PostgreSQL streaming without a transaction throws `MQ2101` before executing (R-EXE-08). |
| AC-EXE-09 | A slow query is cancelled by the configured timeout on every Tier-1 vendor (R-EXE-11). |
| AC-EXE-11 | `one` reads at a limit of 2 and returns the single row or empty; a second row throws `MQ2003` before an enricher or `afterMap` sees it, and through a to-many join a customizer made, which no selected column reads, the message names the join and `Filters.exists` (R-EXE-12). |
| AC-EXE-12 | `first` orders by `orderBy`, then the key with explicit `nullsLast`: on a nullable non-id `@PrimaryKey` it returns the same row on every Tier-1 vendor; a grouped `first` orders by its group keys; a query with neither an `orderBy` nor a key throws `MQ2203`, an aggregate-only query excepted (R-EXE-12). |
| AC-EXE-13 | `one(q, key)` ANDs the converted key with `q`'s filter, and contradictory filters give empty; a `null` key or component, or a wrong component count, throws `IllegalArgumentException`; a keyless or grouped query throws `MQ2203`; a second row through a to-many join a customizer made is `MQ2003` naming the join and `Filters.exists` (R-EXE-12). |
| AC-EXE-14 | `byKeys` returns the map in first-occurrence key order with missing keys absent and duplicates read once, for single and composite keys; more keys than the vendor's limits give several statements and one fetch-plan run, and a configured `primaryKeyFirstBatchSize` does not change the chunks; no keys run no SQL; the `orderBy` is ignored with one `WARNING` and no `ORDER BY`; a duplicate key is `MQ2213`, or `MQ2003` with the `Filters.exists` hint for a to-many join a customizer made; a keyless query is `MQ2203`, with empty keys too; two keys converting to one value are both present with `afterMap` run once; a `MODEL`-phase customizer applies; a case-insensitive string key on MySQL and SQL Server whose row matches no requested value is `MQ2005` (R-EXE-13). |
| AC-EXE-15 | `count(q)`, `one(q)` and `one(q, key)` on an ordered query each log one `WARNING` per `ModelQuery`; the `one` statements render no `ORDER BY`; `page`'s count and `first` log nothing (R-EXE-05, R-EXE-12). |
| AC-EXE-16 | Each read in R-EXE-14's table locks or refuses as stated, before any SQL: `list`, `list(q, Limit)`, `first`, `one(q)`, `one(q, key)` and `byKeys` lock; `page`, `count`, `stream` and `export` refuse with `MQ2211`, as does a joined query and `one(q)` with `SKIP_LOCKED`; `MQ2102` outside a transaction; fetch-plan statements carry no lock and `byKeys` chunks are ordered by key (R-EXE-14). |
| AC-EXE-17 | (TCK, PostgreSQL and MySQL, two connections) a second `first` with `WAIT` blocks until the first commits; with `SKIP_LOCKED` it returns the next row; with `NO_WAIT` it throws `PessimisticLockException` or `LockTimeoutException`, as recorded per vendor (R-EXE-14). |
| AC-EXE-18 | The inline total equals `COUNT`'s on flat, grouped, `HAVING` and primary-key-first pages, both lines, Tier 1, one statement each (R-EXE-15). |
| AC-EXE-19 | Past the end, `COUNT`'s statement runs and the total is exact; a refused treat site is `MQ4016` before any statement (R-EXE-15). |
| AC-EXE-20 | Fallbacks 1–4, a customizer's `distinct()` included, run `COUNT`'s statements and log their reason once per executor and query (R-EXE-15). |
| AC-EXE-21 | Without `model-query-hibernate`, `INLINE_COUNT` runs `COUNT`'s statements and returns the same `Slice` (R-EXE-15, R-VND-27). |
| AC-EXE-22 | Over a filter-only to-many join, on both lines and Tier 1, with a single and an embedded id: `list` and offset pages return each root once, `count` equals `list`'s size, a page's total matches its rows, `one` returns the single root, and a locked `list` and `one` run; the SQL holds one `exists` and no join outside it (R-EXE-16, R-EXE-04, R-EXE-14). |
| AC-EXE-23 | An `or` mixing a child term and a root term, two terms on the same child, a `not(...)` around a child term and a nested `exists(...)` return the rows `Filters.exists` with the same predicate returns, with no outer join; a statement that also selects through the to-many join renders the join and returns as before (R-EXE-16). |
| AC-EXE-24 | A grouped query's `sum` over a root column with a filter-only to-many join equals the sum over distinct roots (R-EXE-16, R-EXE-04). |
