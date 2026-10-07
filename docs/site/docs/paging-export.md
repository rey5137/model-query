# Paging and export

## `page` and `CountMode`

`executor.page(query, pageSpec, mode)` returns a `Slice<M>` with `content()`, `pageNumber()`, `pageSize()`,
`hasNext()` and an optional `total()`. Build the page spec with `PageSpec.of(pageNumber, pageSize)`, or with
`PageSpec.ofOffset(offset, pageSize)` for an offset that is not a multiple of the page size.

| `CountMode` | Behaviour |
|---|---|
| `COUNT` | Runs a count query and reports the exact total. |
| `NO_COUNT` | Fetches one extra row to compute `hasNext`; the total is unknown. Cheapest for infinite scroll. |
| `ONLY_COUNT` | Runs the count only; the content is empty. |

A slice never reports a total of zero when it holds rows, and never reports `hasNext == false` just because the
count was skipped. A `PageSpec` with a non-positive size fails with `MQ2001`, and a negative offset with `MQ2002`.

=== "Plain JPA"

    ```java
    Slice<OrderView> page = executor.page(query, PageSpec.of(2, 50), CountMode.NO_COUNT);
    ```

=== "Spring repository"

    ```java
    // A sorted Pageable replaces the query's orderBy; an unsorted one keeps it.
    ModelPage<OrderView> page = orders.findPage(query, PageRequest.of(2, 50), CountMode.NO_COUNT);
    ```

On a repository, `findPage` takes the page from a `Pageable` and returns a `ModelPage`; see
[Spring Data and the starter](spring.md#pageable-and-sort).

## Stable order

For `page` and `export`, the engine appends the primary key (or, for a grouped query, the group keys) to your
`orderBy` as a tie-breaker. Without a stable order, `LIMIT/OFFSET` pages overlap or skip rows. The primary-key columns
are added to the selection for you; if a row's key still comes back `null`, the export fails with `MQ2201` rather than
silently going wrong. An operation that needs a key on a query without one fails with `MQ2203`.

## Primary keys

Keyset paging and primary-key-first paging append the primary key to your order, whatever the key is: a numeric key, a
`String` key, an `@IdClass` or an `@EmbeddedId`. The key's columns are read from the row itself, so a String key such
as `"P-0001"` and a composite `@EmbeddedId` page and export exactly as a numeric one does, with the key's own columns
closing the order. [Migration recipes](recipes.md) has a worked String and embedded example.

## Keyset page

`executor.page(query, keysetSpec)` returns a `KeysetSlice<M>` for infinite scroll or next/previous links: the rows in
the query's order, `hasNext()`/`hasPrevious()`, and an optional cursor for each neighbouring page. It carries no total
and never runs a count.

- Build the spec with `KeysetSpec.first(size)`, `KeysetSpec.after(cursor, size)` or `KeysetSpec.before(cursor, size)`.
  `before` returns the page before the cursor's row in the query's order, with both neighbour cursors when a page sits
  on each side.
- `KeysetSlice.content()` is the page, `size()` the size asked for, and `nextCursor()`/`previousCursor()` the cursors
  that reach the neighbouring pages; a cursor is present exactly when the matching `has…()` is true.
- A cursor is opaque and only understood by the order that issued it: the order decides its fingerprint, so reusing a
  cursor under a different `orderBy` fails with `MQ2209`. Start again with `KeysetSpec.first`.

=== "Plain JPA"

    ```java
    KeysetSlice<OrderView> first = executor.page(query, KeysetSpec.first(50));
    KeysetSlice<OrderView> next = executor.page(query, KeysetSpec.after(first.nextCursor().orElseThrow(), 50));
    ```

=== "Spring repository"

    ```java
    // Sort.unsorted() keeps the query's order; pass the same Sort with every cursor it issued.
    KeysetSlice<OrderView> first = orders.findKeysetPage(query, KeysetSpec.first(50), Sort.unsorted());
    KeysetSlice<OrderView> next = orders.findKeysetPage(query,
            KeysetSpec.after(first.nextCursor().orElseThrow(), 50), Sort.unsorted());
    ```

Through Spring Data, `findKeysetPage(q, keyset, sort)` passes straight through to the executor: `Sort.unsorted()`
keeps the query's order, a sorted `Sort` replaces it, and the cursor's fingerprint follows whichever applies.

```java
@GetMapping("/books/pages")
KeysetPage page(@RequestParam(required = false) String after, @RequestParam(defaultValue = "20") int size) {
    var q = QBookView.query()
            .select(QBookView.ALL)
            .orderBy(QBookView.RELEASED.asc(), QBookView.ID.asc())   // the order the cursor is bound to
            .keyset()
            .build();
    KeysetSpec spec = after == null ? KeysetSpec.first(size) : KeysetSpec.after(after, size);
    KeysetSlice<BookView> slice = books.findKeysetPage(q, spec, Sort.unsorted());
    return new KeysetPage(slice.content(), slice.nextCursor().orElse(null), slice.previousCursor().orElse(null));
}
```

## Keyset export

Offset export gets slower the deeper you go. Call `keyset()` on the query to export by "everything after the last row
seen" instead, which stays fast at any depth:

```java
var q = QOrderView.query()
        .select(QOrderView.ALL)
        .orderBy(QOrderView.CREATED_AT.desc())
        .keyset()
        .build();
```

- The primary key is appended as the last keyset column, so ties are never skipped. Keyset paging needs a primary key
  (`MQ1201` otherwise).
- A `NULL` in a keyset column fails with `MQ2202` unless the column has an explicit `nullsFirst()` or `nullsLast()`;
  with one, the engine adds the matching `IS NULL` branches and renders the same ordering on every database.
  Dropping a row silently because of a NULL is the failure it prevents.
- `Float` and `Double` order or key columns are refused (`MQ1207`), because a floating-point cursor may not compare
  equal after being bound.
- A keyset page that holds a key from the page before, because a cursor value did not survive being bound or a row's
  sort value changed while the export ran, fails with `MQ2205` instead of looping or skipping.

Keyset paging, primary-key-first paging and offset export of an ungrouped query refuse a selection read through a
to-many join (`MQ2204`), because one primary key then spans several rows. Select from the child side, or filter with
`Filters.exists`.

## Deep offsets: primary-key-first

When you need offset paging over a large table, `primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10_000))` makes
deep pages run in two steps: first select only the primary keys in order, then load the models for those keys with
`WHERE pk IN (...)`. Use it with care: a `QueryCustomizer` that narrows only one of the two steps makes them disagree,
which the engine refuses with `MQ2206`.

## Export

`export` visits every matching row exactly once, one page at a time, with bounded memory:

=== "Plain JPA"

    ```java
    long written = executor.export(
            query,
            ExportOptions.of(2_000),                 // or ExportOptions.defaults()
            page -> page,                            // a page transformer; may batch lookups for the whole page
            item -> writer.write(item));             // receives one item at a time
    ```

=== "Spring repository"

    ```java
    long written = orders.export(
            query,
            ExportOptions.of(2_000),
            page -> page,
            item -> writer.write(item));
    ```

- The page size defaults to `ModelQueryConfig.exportPageSize()` (`modelquery.export.page-size`, default 1000).
  `ExportOptions.withLimit(Limit)` caps the number of rows.
- The transformer receives each page as a list, so you can batch your own lookups. The sink receives one item at a
  time. Neither is called for an empty page.
- The return value is the number of items passed to the sink, which can differ from the rows read when the
  transformer expands or filters.
- A query without `keyset()` exports with offset paging; a keyset query uses keyset paging. **Keyset export is the
  recommended default for large exports**: it runs short queries per page instead of holding one result set open.
- Keyset export guarantees exactly-once only over rows whose sort values do not change while it runs.
- A grouped query exports with offset paging only.

## `stream`

`stream(query, limit, stream -> ...)` hands the body a `java.util.stream.Stream` and closes it for you, even if the
body exits early or throws; the library never returns an open stream. It is for a single pass inside one transaction.

- On PostgreSQL the driver only uses a cursor when autocommit is off, so streaming outside a transaction fails with
  `MQ2101` instead of buffering the whole result. The Spring integration opens a read-only transaction for you.
- On MySQL the default is row-by-row streaming, which holds the connection until the whole result is read. See
  [Vendor notes](vendors.md) before you use it for long-running work.
- The fetch size is `modelquery.stream.fetch-size` (default 500).

For millions of rows, prefer keyset `export`.

## Timeouts

A per-query timeout from `ModelQueryConfig.queryTimeout(Duration)` or `modelquery.query-timeout` is applied to every
statement. JDBC timeouts have one-second granularity, so a sub-second value is rounded up.
