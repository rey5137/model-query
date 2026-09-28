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

**R-PAG-03** *(was R3)* **Export fails loudly when the primary key isn't selected.** If `primaryKey` is set and a row's
key is `null`, the engine throws `MQ2201` naming the model. Primary-key columns are added to the selection
automatically, and keys are read from the `Row`, so this holds for classes and records alike (INV-5).

## 2. Keyset paging

**R-PAG-04** *(was R4)* **Keyset paging doesn't skip ties.** The primary key is appended as the last keyset column, with
the direction of the last order column. Keyset without a primary key is a build-time error (`api/11` R-QRY-03). Every
order column is added to the selection, so the cursor can read its value from the `Row` even when the column is
filter-only or absent from the chosen `ColumnSet`.

**R-PAG-05** *(was R5)* **Keyset paging doesn't truncate on NULL.** A NULL in a keyset column throws `MQ2202` by
default. With explicit `nullsFirst()`/`nullsLast()` (`api/10` R-COL-12), the keyset predicate adds the matching
`IS NULL` branches and the `ORDER BY` renders explicit null precedence, so every vendor sorts the same way.
`modelquery.keyset.null-keys=honour-null-precedence` makes that the default for a migrating codebase
(`integration/50`).

**R-PAG-06** Keyset predicates are generated as an OR-expansion over the order columns; the shape and the NULL branches
are specified in `vendor/41` §5. Row-value comparison `(a,b) > (?,?)` is a possible later optimisation, not the default
(P-4).

## 3. Primary-key-first deep paging

**R-PAG-07** *(was R12)* For a deep offset, step 1 selects only the primary keys in the query's order, and step 2
selects models `WHERE pk IN (...)`. The step-2 batch size is clamped to `VendorProfile.maxInListSize()` and
`maxBindParameters()` (`vendor/41`).

**R-PAG-08** Step 2 re-applies the query's order, because `IN` does not preserve the key order. Predicates must be
identical in both steps; a `QueryCustomizer` that narrows only one phase is what `api/11` R-QRY-09 warns about.

## 4. The export loop

One loop serves both modes:

```
cursor = start (offset 0, or empty keyset)
loop:
    rows = fetch(query + stable order + cursor predicate/offset, pageSize)
    fresh = rows minus keys seen on previous page      # offset mode only
    for item in pageTransformer(fresh): sink(item) until limit reached
    if rows.size < pageSize or limit reached: stop
    cursor = next(cursor, rows)                        # keyset: last row's key; offset: += rows.size
```

**R-PAG-09** `pageTransformer` receives whole pages so a caller can batch its own lookups; `sink` receives one item at
a time. Neither is called for an empty page.

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
| AC-PAG-03 | An export whose `ColumnSet` omits the primary key still succeeds, because the engine added it (R-PAG-03). |
| AC-PAG-04 | A model whose primary key maps to `null` throws `MQ2201` naming the model (R-PAG-03). |
| AC-PAG-05 | Keyset export over ties yields every row exactly once (R-PAG-04). |
| AC-PAG-06 | Keyset ordering by a filter-only or unselected column works, because the engine selected it (R-PAG-04). |
| AC-PAG-07 | A NULL keyset column throws `MQ2202` by default; with explicit precedence, every ASC/DESC × FIRST/LAST × null/non-null combination is correct on every Tier-1 vendor (R-PAG-05). |
| AC-PAG-08 | Primary-key-first paging returns the same rows as plain offset paging, for single and composite keys (R-PAG-07). |
| AC-PAG-09 | A step-2 batch larger than the vendor's IN limit is split and stays correctly ordered (R-PAG-07, R-PAG-08). |
| AC-PAG-10 | Grouped offset export over 20 000 rows visits every group exactly once, with duplicated order keys (R-PAG-11). |
| AC-PAG-11 | `export` returns the count passed to `sink`, not the count read, when `pageTransformer` filters (R-PAG-10). |
