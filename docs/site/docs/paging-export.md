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

## Stable order

For `page` and `export`, the engine appends the primary key (or, for a grouped query, the group keys) to your
`orderBy` as a tie-breaker. Without a stable order, `LIMIT/OFFSET` pages overlap or skip rows. The primary-key columns
are added to the selection for you; if a row's key still comes back `null`, the export fails with `MQ2201` rather than
silently going wrong. An operation that needs a key on a query without one fails with `MQ2203`.

## Keyset paging

Offset paging gets slower the deeper you go. Call `keyset()` on the query to page by "everything after the last row
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

```java
long written = executor.export(
        query,
        ExportOptions.of(2_000),                 // or ExportOptions.defaults()
        page -> page,                            // a page transformer; may batch lookups for the whole page
        item -> writer.write(item));             // receives one item at a time
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
