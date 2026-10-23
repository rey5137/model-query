# Hot paths

## What a read costs

The measurements below are medians on PostgreSQL 16 with Hibernate 6.6 / 7.4, in that order. The dataset models
a year of point-of-sale activity: stores, terminals, cashiers, about 86,000 sales and 3–6 lines per sale. The small
"today" read returns about 50 rows with one join and two filters. These timings describe that dataset, rather than
a latency guarantee for other queries or databases.

Compared with Spring's `JdbcClient` running the same SQL, the executor adds tens of microseconds to the small read:
0.71 / 0.71 ms becomes 0.74 / 0.75 ms. A `page` with a separate count runs two statements and adds about 0.3 ms
over the equivalent JDBC calls.

`buildQuery` takes about 3 µs on the small read. Building a query per call is fine; an immutable `ModelQuery` can
also be shared when its definition is fixed. There is no compiled or cached query API, and these measurements show
no need for one.

On Hibernate 7.4, enabling `hibernate.criteria.plan_cache_enabled` produced zero cache hits in 100 calls for
model-query's queries, including a test that reused the same criteria tree. Enabling it bought no saving in these
measurements.

## Large flat reads

Since 0.11.0, `list` maps each tuple straight to its model when the query has no fetch plan. Queries with a fetch plan use the
general mapping path and do not get this saving. The choice is automatic; SQL, results and the public API are unchanged.

The large read projects all 86,000 sales into 12 columns: numbers, strings, timestamps, decimals and an enum read
through a converter.

| Read path | Hibernate 6.6 | Hibernate 7.4 |
|---|---|---|
| `JdbcClient` + `RowMapper` | 76.2 ms | 77.8 ms |
| Hibernate tuples read by index | 87.6 ms | 105.9 ms |
| model-query `list`, since 0.11.0 | 92.2 ms | 107.9 ms |
| model-query `list`, before 0.11.0 | 193.5 ms | 203.2 ms |

The direct mapping path is within a few milliseconds of Hibernate's tuple path. Allocation per call falls from
393 / 429 MB to 109 / 155 MB. A fetch plan needs the general path's extra row processing, so the flat
read figures do not describe that work.

Use `JdbcClient` for very large flat reads where saving the last roughly 15–30% of read time matters, or for SQL
model-query cannot express. For ordinary lists, choose a limit that fits the caller's needs; `list` holds the result
in memory.

=== "Plain JPA"

    ```java
    List<OrderView> rows = executor.list(query, Limit.of(100));
    ```

=== "Spring repository"

    ```java
    List<OrderView> rows = orders.findAll(query, Limit.of(100));
    ```

## Large exports

Use [`keyset()` for large exports](paging-export.md#keyset-export). An offset loop re-reads earlier rows as the
offset grows. Keyset export resumes after the last row seen and runs short queries with bounded memory. See the
[export examples and consistency requirements](paging-export.md#export).

[`stream`](paging-export.md#stream) is available for one pass inside a single transaction. It holds the connection
for that pass; prefer keyset export for millions of rows.
