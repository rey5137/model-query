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
