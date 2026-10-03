# 21 — Paging and Export

**Covers:** offset paging, keyset paging, primary-key-first deep paging, the export loop, and grouped export.
**Read when:** changing anything that decides which rows a page contains. Every rule here exists because a naive paging
loop loses or duplicates rows without raising an error.
**Owns:** `R-PAG-*`, `AC-PAG-*`. Keyset predicate rendering per vendor is `vendor/41` §5.

---

## 1. Stable order

**R-PAG-01** *(was R1)* **Offset export visits every row exactly once.** The engine always orders by a unique key: it
appends the primary-key columns as a tie-breaker to whatever order the caller gave. Without a stable order,
`LIMIT/OFFSET` pages overlap or skip rows, and no test that checks a single page catches it.

**R-PAG-02** *(was R2)* **Export memory is bounded.** There is no global "seen ids" set. With a stable order,
duplicates can only appear at a page boundary, so the engine dedupes only against the previous page's keys (INV-4).
It also drops a key repeated within one page: a to-many join used only by predicates repeats its root's rows, and
exporting each root once matches `count` (`engine/20` R-EXE-04) whatever the page size.

**R-PAG-03** *(was R3)* **Export fails loudly when the primary key isn't selected.** If `primaryKey` is set and a row's
key is `null`, the engine throws `MQ2201` naming the model. Primary-key columns are added to the selection
automatically, and keys are read from the `Row`, so this holds for classes and records alike (INV-5).

**R-PAG-13** **Key-based paging refuses a to-many selection.** When a selected column is read through a to-many join
(the join's attribute is a `PluralAttribute` in `jakarta.persistence.metamodel`), one primary key spans several rows:
the R-PAG-01 tie-breaker is no longer unique, R-PAG-02's boundary dedupe drops real rows, and a keyset cursor skips the
rest of the key's rows. Offset export of an ungrouped query, keyset paging and the primary-key-first phase therefore
throw `MQ2204` naming the model and the join, before the first query runs. A grouped export is exempt: its tie-breaker
and dedupe are the group-key tuple, which is unique per result row even through a to-many join (R-PAG-11). Ordering
keys count as selected (D-29), so ordering through a to-many join is refused too: its repeated rows need not be adjacent. A to-many join used only by predicates is unaffected; select from the
child side, or filter with `Filters.exists` (`api/12` R-FLT-12). `list`, `page` and `stream` still accept the shape, and
`count` counts its rows (`engine/20` R-EXE-04).

## 2. Keyset paging

**R-PAG-04** *(was R4)* **Keyset paging doesn't skip ties.** The primary key is appended as the last keyset column, with
the direction of the last order column. Keyset without a primary key is a build-time error (`api/11` R-QRY-03). Every
order column is added to the selection, so the cursor can read its value from the `Row` even when the column is
filter-only or absent from the chosen `SelectSet`.

**R-PAG-05** *(was R5)* **Keyset paging doesn't truncate on NULL.** A NULL in a keyset column throws `MQ2202` by
default. With explicit `nullsFirst()`/`nullsLast()` (`api/10` R-COL-12), the keyset predicate adds the matching
`IS NULL` branches and the `ORDER BY` renders explicit null precedence, so every vendor sorts the same way.
`modelquery.keyset.null-keys=honour-null-precedence` (`ModelQueryConfig.keysetNullKeys`) makes that the default for a
migrating codebase (`integration/50`): a column without explicit precedence pages its NULLs where the profile's
`defaultAscendingNullOrdering()` puts them, and still throws `MQ2202` under `OTHER`, whose ordering is `UNKNOWN`
(`api/10` R-COL-13, D-35). Under the default `fail`, the predicate of such a column keeps its `OR a IS NULL` branch
whatever ordering is reported, so a NULL is read and refused even where that ordering is wrong (D-30, D-35). Where the persistence provider is configured with a default null ordering
(`ProviderSupport.defaultNullPrecedence`), that ordering replaces the profile's in both directions (D-36). Nothing reports it without the provider's module
(`model-query-hibernate` for Hibernate's `hibernate.order_by.default_null_ordering`), so such an application adds that
module or gives its nullable keyset columns explicit precedence, which always renders its own sort key there
(`api/10` R-COL-12); the user guide says so, and resolution warns when it sees the setting without the module
(`vendor/40` R-VND-07).

**R-PAG-06** Keyset predicates are generated as an OR-expansion over the order columns; the shape and the NULL branches
are specified in `vendor/41` §5. Row-value comparison `(a,b) > (?,?)` is a possible later optimisation, not the default
(P-4).

**R-PAG-14** **Keyset export refuses a repeated page.** Each keyset page starts strictly after the last row of the page
before, so no key of that page can come back, unless the cursor did not survive being bound (a value that does not
compare equal to the stored one; `Float` and `Double` keysets are refused at build for this, `api/11` R-QRY-13) or a
row's keyset value changed between pages so that it now sorts after the cursor. The engine keeps the previous page's
keys, bounded by one page (INV-4), and throws `MQ2205` naming the model when a row of the new page has one of them,
before that page reaches `pageTransformer`. It throws rather than dedupes: the same causes can skip rows as well as
repeat them, and a repeated tie group that fills a page would loop forever. A key repeated within one page is still
dropped as in R-PAG-02 (D-31). A row that moves further than the next page is not caught; keyset export guarantees
exactly-once only over rows whose keyset values do not change while it runs.

**R-PAG-16 — The keyset page's signatures.** `ModelQueryExecutor` gains
`<M> KeysetSlice<M> page(ModelQuery<E, ?, M> q, KeysetSpec keyset)`, `@Incubating`, whose arity keeps it apart from
`page(q, PageSpec, CountMode)`. `KeysetSpec` is a `core` final class built by `first(size)`, `after(cursor, size)` or
`before(cursor, size)`; a null, blank or malformed cursor throws `MQ2208` when the spec is built, so there is no
nullable `of(...)`. `KeysetSlice<M>` is a final class, not a record, so accessors can be added later; it has `content()`, `size()`,
`hasNext()`, `hasPrevious()`, `nextCursor()` and `previousCursor()`, and the two cursor accessors return an
`Optional<String>`, never `null`, with `hasNext() == nextCursor().isPresent()` and the same for previous, and
`of(content, size, previous, next)` is public for tests. The size range is `1..Integer.MAX_VALUE - 1`, because the
engine reads size + 1 rows; anything else reuses `MQ2001` (R-EXE-06). `page(q, KeysetSpec)` on a query without
`keyset()` (`api/11` R-QRY-03) throws `MQ2207` before any query runs.

**R-PAG-17 — A cursor is position only.** It carries the raw values (`Row.raw`, R-COL-11) of every keyset key — the
order columns plus the primary key appended as the tie-breaker (R-PAG-04) — of the boundary row, with no direction:
`nextCursor()` is built from the last content row and `previousCursor()` from the first, and `before` turns one
around itself (R-PAG-20). Each value is read through the column's `attributeType`, over a closed codec set: String,
Character, Boolean, the integral types and `BigInteger`; `BigDecimal` through `toString`/`new BigDecimal(String)`,
which keeps its scale; `UUID`; an enum by `name()`; `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`,
`OffsetDateTime`, `OffsetTime` and `ZonedDateTime` at nanosecond precision, keeping their offset or zone;
`java.util.Date`, `java.sql.Date`, `Time` and `Timestamp` by runtime class, `Timestamp` keeping its nanos; and
`byte[]`. Any other type — an `@Convert` value class or `Calendar` — throws `MQ2210` naming the column before any
query runs; Java serialization is never used. The cursor's values are readable by anyone who decodes it, filter-only
order columns and internal primary keys included; that is documented, not hidden, and non-breaking to change later
because the format is not API (R-PAG-18).

**R-PAG-18 — A cursor's encoding.** A cursor is URL-safe base64 without padding over
`[version=1][fingerprint 8 bytes][key count][per value: kind byte (NULL or codec kind) + length-prefixed
bytes][CRC32C 4 bytes over all preceding bytes]`. Its length is capped at 8192 characters: decoding a longer input is
`MQ2208`, encoding a longer one is `MQ2210` naming the column. A decoder accepts every version it knows, and a format
change keeps the previous version decodable for one minor release. The format is not API; only the round trip is.
`MQ2208` also covers a null or blank cursor, one that is not base64, an unknown version, a bad checksum, a truncated
one, a value that fails to decode, and a NULL in a refusing or primary-key column; the message names the reason, never
echoes the whole cursor, and sends the caller back to `KeysetSpec.first`.

**R-PAG-19 — A cursor's fingerprint.** The fingerprint is the first 8 bytes of SHA-256 over a canonical UTF-8 form of
the root entity class name and, per keyset key in order, its attribute path, `attributeType` name, direction and
resolved null precedence (FIRST, LAST or refusing). It excludes the filter, the model class and the selection: under
another filter the cursor gives a different window, never wrong rows, so the same order under another filter is
accepted. A mismatch — another sort, direction, precedence, entity or deployment — throws `MQ2209` before any query
runs. The fingerprint catches another order and the CRC32C catches truncation and typos; a deliberately rebuilt
cursor is accepted and only moves within rows the query allows, and nothing is signed.

**R-PAG-20 — `before` reads the page before the cursor.** It flips each key's direction and resolved null precedence
(FIRST↔LAST; a key that refuses NULL keeps its `OR IS NULL` branch, R-PAG-22), reads size + 1 rows nearest the cursor
in the flipped order, drops the furthest (the last row read) and reverses the rest, so the slice keeps the query's
order. It then drops keys repeated within the page (R-PAG-02, D-31) and runs the fetch plan on the final content in
query order (R-PAG-23); the look-ahead row's children are never loaded. A short `before` page is not topped up,
because that would be a second statement.

**R-PAG-21 — The keyset page's flags need no extra query.** `first` gives `hasPrevious() == false` and
`hasNext() == read > size`; `after` gives `hasPrevious() == true` and `hasNext() == read > size`; `before` gives
`hasPrevious() == read > size` and `hasNext() == true`. An empty page has both flags false and both cursors empty,
and the client restarts with `first`. A true flag reached through a cursor can lead to an empty page after deletes,
and a look-ahead row that repeats a key within the page can give a short page; it never skips a row. An `EXISTS`
probe for an exact `hasPrevious`, or a top-up of a short `before` page, is rejected: each is a second statement.

**R-PAG-22 — Null rules under `before`.** The default is R-PAG-05 unchanged: a NULL keyset column throws `MQ2202`,
checked on every row read, the look-ahead included. Under `before`, a key whose precedence is the database default
may render bare reversed, because the database flips it (`api/10` R-COL-13, D-35). Explicit precedence renders the
flipped precedence explicitly. A precedence resolved from `ProviderSupport.defaultNullPrecedence` (D-36) must render
reversed and explicit too, because the provider applies its default the same way in both directions, so `Keyset.Key`
records where its precedence came from. Always rendering explicit precedence is rejected: it loses the index on
MySQL.

**R-PAG-23 — The keyset page with grouped queries and fetch plans.** A grouped query is refused for keyset paging at
build with `MQ1402` (`api/13` R-AGG-10) and stays `Future`; a keyset page asked of one past that point hits `MQ2207`
(R-PAG-16). A fetch plan is allowed and runs on the page's content (`api/15` R-FCH-09). R-PAG-13 still holds: a
to-many selection is `MQ2204` before querying, while a to-many join used only in predicates is accepted and its
repeated roots are dropped within the page (R-PAG-02). A keyset page always runs in one step, so R-PAG-15's `MQ2206`
applies and `MQ1307` covers its binds as it does for any keyset statement (R-PAG-07).

**R-PAG-24 — `MQ2205` covers the stateless page, and R-PAG-14's guarantee is restated.** A keyset page reached
through a cursor whose rows hold that cursor's own primary key throws `MQ2205` (INV-5): the cursor did not round-trip
or the boundary row moved. A stateless page cannot detect other rows moving between requests, so R-PAG-14's
exactly-once guarantee is restated for pages: a page repeats or skips no row only while its cursor round-trips and no
row's keyset value changes while it is used.

## 3. Primary-key-first deep paging

**R-PAG-07** *(was R12)* For a deep offset, step 1 selects only the primary keys in the query's order, and step 2
selects models `WHERE pk IN (...)`. The step-2 batch is `ModelQueryConfig.primaryKeyFirstBatchSize(...)`, or the
whole page when that is unset, clamped to `VendorProfile.maxInListSize()` keys and to `maxBindParameters()` less the
statement's own binds, rounded down to a power of two so a provider's IN-list padding stays within it (`vendor/41`,
D-32, D-80); a configured batch below the clamp is kept, and a batch over it is read in several statements. A keyset page or
export page is refused up front with `MQ1307` when its own binds plus the worst cursor, k(k+1)/2 binds for k keyset keys,
exceed `maxBindParameters()` (`api/12` R-FLT-09, D-82). A fetch plan's child keys go in rounds of the same
clamp, without `primaryKeyFirstBatchSize` (`api/15` R-FCH-05).

**R-PAG-08** Step 2 re-applies the query's order, because `IN` does not preserve the key order. Predicates must be
identical in both steps; a `QueryCustomizer` that narrows only one phase is what `api/11` R-QRY-09 warns about, and
what R-PAG-15 refuses.

**R-PAG-15** **Primary-key-first refuses phases that disagree.** When a customizer narrows some phases but not others
(`api/11` R-QRY-09), step 1 pages rows step 2 does not return, or the reverse, and the offset an export shares between
one-step and two-step pages shifts at the switch, skipping rows. On a query with `primaryKeyFirst(...)` the phase check
of the first execution therefore throws `MQ2206` naming the model and the phases, before any query runs, whatever the
method and the offset, since the check belongs to the definition, not to a request's values. It passes only once the
phases agree, so every later execution throws too. Other queries keep the R-QRY-09 warning.

## 4. The export loop

One loop serves both modes:

```
cursor = start (offset 0, or empty keyset)
loop:
    rows = fetch(query + stable order + cursor predicate/offset, pageSize)
    fresh = rows minus keys repeated within the page   # both modes (R-PAG-02)
    offset: fresh -= keys of the previous page         # rows shifted across the boundary
    keyset: a key of the previous page -> MQ2205       # the cursor did not round-trip (R-PAG-14)
    fresh = fetch plan run on fresh                    # children, join plans, enrichers (api/15 R-FCH-09)
    for item in pageTransformer(fresh): sink(item) until limit reached
    if rows.size < pageSize or limit reached: stop
    cursor = next(cursor, rows)                        # keyset: last row's key; offset: += rows.size
```

**R-PAG-09** `pageTransformer` receives whole pages so a caller can batch its own lookups; `sink` receives one item at
a time. Neither is called for an empty page. The loop's options are an `ExportOptions`, a final class built by
`defaults()` or `of(pageSize)` and narrowed by `withLimit`, so a new option is additive (D-88).

**R-PAG-10** The export returns the number of items passed to `sink`, which may differ from the number of rows read when
`pageTransformer` expands or filters.

## 5. Grouped export

**R-PAG-11** *(was R18)* A grouped query has no per-row key, so R-PAG-01's primary-key tie-breaker is replaced by every
group-by column not already in the order — unique per group — and R-PAG-02's page-boundary dedupe compares group-key
tuples. R-PAG-03's primary-key check is skipped (`api/13` R-AGG-09).

**R-PAG-12** Grouped export is offset-mode only, because keyset paging is refused for grouped queries
(`api/13` R-AGG-10). An export whose order is not a prefix of "group keys plus appended remainder" is the only shape
that could overlap pages, and R-PAG-11 makes it unreachable.

## 6. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-PAG-01 | Offset export over a table with duplicated sort keys yields every row exactly once (R-PAG-01). |
| AC-PAG-02 | Export heap stays bounded at 20 000 rows; no structure grows with the row count (R-PAG-02). |
| AC-PAG-03 | An export whose `SelectSet` omits the primary key still succeeds, because the engine added it (R-PAG-03). |
| AC-PAG-04 | A model whose primary key maps to `null` throws `MQ2201` naming the model (R-PAG-03). |
| AC-PAG-05 | Keyset export over ties yields every row exactly once (R-PAG-04). |
| AC-PAG-06 | Keyset ordering by a filter-only or unselected column works, because the engine selected it (R-PAG-04). |
| AC-PAG-07 | A NULL keyset column throws `MQ2202` by default; with explicit precedence, every ASC/DESC × FIRST/LAST × null/non-null combination is correct on every Tier-1 vendor (R-PAG-05). |
| AC-PAG-08 | Primary-key-first paging returns the same rows as plain offset paging, for single and composite keys (R-PAG-07). |
| AC-PAG-09 | A step-2 batch larger than the vendor's IN limit is split and stays correctly ordered (R-PAG-07, R-PAG-08). |
| AC-PAG-10 | Grouped offset export over 20 000 rows visits every group exactly once, with duplicated order keys (R-PAG-11). |
| AC-PAG-11 | `export` returns the count passed to `sink`, not the count read, when `pageTransformer` filters (R-PAG-10). |
| AC-PAG-12 | Offset export of an ungrouped query, keyset paging and primary-key-first paging over a selection read through a to-many join throw `MQ2204` naming the join, without querying; the same export with the to-many join used only in a predicate succeeds, and so does a grouped export over it (R-PAG-13). |
| AC-PAG-13 | A keyset export whose next page repeats a key of the page before, because a row's keyset value moved after the cursor between pages, throws `MQ2205` naming the model before that page reaches `pageTransformer`, on every Tier-1 vendor; a key repeated within one page is still dropped (R-PAG-14). |
| AC-PAG-14 | On a query with `primaryKeyFirst(...)` whose customizer narrows only some phases, `page` and `export` throw `MQ2206` before any query runs; the same customizer without `primaryKeyFirst(...)` only warns (R-PAG-15). |
| AC-PAG-15 | A first/after walk over ties spanning page boundaries, for a single and a composite primary key, visits every row once, in the offset page's order; an exact-size remainder gives `hasNext()` false (R-PAG-04, R-PAG-16, R-PAG-21). |
| AC-PAG-16 | `before` returns exactly the page before, in query order; the furthest look-ahead row is dropped; walking back reverses the forward walk; the first page has `hasPrevious()` false (R-PAG-20, R-PAG-21). |
| AC-PAG-17 | Every ASC/DESC × FIRST/LAST combination with NULLs at page edges pages once each way on every Tier-1 vendor; a default-precedence NULL throws `MQ2202`; a Hibernate `default_null_ordering` key reverses correctly under `before` (R-PAG-05, R-PAG-22). |
| AC-PAG-18 | Every supported type round-trips with no repeat or skip on ties (BigDecimal scale, Timestamp nanos, enum, UUID, `byte[]`, a `ColumnConverter` column); an unsupported type throws `MQ2210` without querying (R-PAG-17). |
| AC-PAG-19 | A changed character, a truncation, non-base64 input, an empty or null cursor, an unknown version or an oversized cursor throws `MQ2208` at `KeysetSpec`, and no other exception type (R-PAG-18). |
| AC-PAG-20 | A cursor from another sort, direction, precedence or entity throws `MQ2209` before querying; the same order under another filter is accepted (R-PAG-19). |
| AC-PAG-21 | `first` on an empty result returns empty content, both flags false and both cursors empty; so does `after` once its rows are deleted (R-PAG-21). |
| AC-PAG-22 | Before querying, a non-`keyset()` query throws `MQ2207`, a to-many selection `MQ2204`, a narrowing customizer with `primaryKeyFirst` `MQ2206`; a fetch plan loads children for the content only (R-PAG-16, R-PAG-23). |
| AC-PAG-23 | A page holding the cursor's own primary key throws `MQ2205`; repeats from a predicate-only to-many join are dropped and no root is skipped (R-PAG-02, R-PAG-24). |
| AC-PAG-24 | A String primary key works on the keyset page, keyset export and primary-key-first paging: a first/after walk and its backward walk over a non-key order with tied keys visit every row once in the key-closed order, and export and primary-key-first hold the same rows as `list`, in that order, under a filter and an explicit non-key sort too (R-PAG-04, R-PAG-07, R-PAG-16, D-111). |
| AC-PAG-25 | An `@EmbeddedId` primary key works the same on the keyset page, keyset export and primary-key-first paging: a first/after walk and its backward walk over a non-key order with tied keys visit every row once in the order the composite key closes, and export and primary-key-first hold the same rows as `list`, in that order, filtered and explicitly sorted too (R-PAG-04, R-PAG-07, R-PAG-16, D-111). |
