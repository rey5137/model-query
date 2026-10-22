# Row locks

!!! note "Incubating"
    Row locks are annotated `@Incubating` (see [API stability](stability.md)).

A read can take a row lock, so a worker holds the rows it is about to change until it commits (`RowLock`, D-127,
`@Incubating`). A lock is part of the query definition: a strength (`exclusive` or `shared`) and a wait policy
(`RowLock.Wait.WAIT`, `NO_WAIT` or `SKIP_LOCKED`). The no-argument factories wait.

```java
ModelQuery<OrderEntity, Long, OrderView> nextJob = QOrderView.query()
        .select(QOrderView.DEFAULT)
        .orderBy(QOrderView.ID.asc())
        .lock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED))
        .build();

// Or add the lock to a definition you already built, as orderedBy does.
ModelQuery<OrderEntity, Long, OrderView> locked = q.withLock(RowLock.shared());
```

`ModelQuery.Builder#lock(RowLock)` stores it and `ModelQuery#lock()` reads it back; `withLock(RowLock)` returns a
locking copy and keeps the fetch plan, as `orderedBy` does. A locked read needs an active transaction: without one the
executor throws `MQ2102` before any statement, and a Spring read-only transaction does not count (see below).

Only the statement that returns the model's rows is locked — never the fetch plan's own statements. `byKeys` locks
each chunk's statement, ordered by primary key, so concurrent callers lock in the same order.

| Read | With a lock |
|---|---|
| `list(q, Limit)`, `first(q)` | locks every row returned |
| `one(q)` | locks the row returned; `SKIP_LOCKED` is `MQ2211` |
| `one(q, key)`, `byKeys(q, keys)` | locks every row returned |
| `page`, keyset `page`, `count`, `stream`, `export` | `MQ2211` |
| `persist(…, returning)` | `MQ1809` |

`page` is refused because PostgreSQL also locks the rows `OFFSET` skips over, which breaks a keyset cursor; `count`,
`stream` and `export` hold a cursor or span transactions. A read that refuses a lock fails before any statement runs,
whatever its limit or keys. A locked query whose built statement has a join is `MQ2211` too, because a projection
leaves the lock no root table to name: filter the related rows with [`Filters.exists`](filters.md#exists) instead of
joining. A customizer that navigates a path past the root (`root.get("customer").get("name")`) joins implicitly, which
is not refused but locks the joined rows too, so keep a locked query's customizer to the root's own columns. A lock
on a grouped query is `MQ1209`, and a customizer that adds `distinct` or `having` to a locked query is `MQ1205`. A
provider that cannot render the lock — a wait H2 drops, or a root mapped to more than one table — is `MQ4010`, both
before any statement.

=== "Plain JPA"

    ```java
    // Holds the returned rows until the transaction ends.
    List<OrderView> locked = executor.list(q, Limit.of(10));
    ```

=== "Spring repository"

    ```java
    List<OrderView> locked = orders.findAll(q, Limit.of(10));
    ```

Through a Spring repository the reads that lock are `findAll`, `findOne`, `findFirst`, `findByKey` and
`findAllByKeys`; `findPage` and `findKeysetPage` refuse a lock as the executor's `page` does. A locked read with no
ambient transaction is `MQ2102`, because the repository opens none for it (unlike `stream`); see
[Spring Data](spring.md#the-repository).

Database exceptions pass through unchanged: `PessimisticLockException`, `LockTimeoutException` and
`QueryTimeoutException`. On PostgreSQL any of them aborts the whole transaction, so retry at the transaction, not the
statement. Do not lock inside `@Transactional(readOnly = true)`: H2 takes the lock anyway, but PostgreSQL and MySQL
reject the locking clause and fail the statement.

On PostgreSQL under READ COMMITTED, after a `WAIT` a locked row is re-checked and dropped if it no longer matches,
after `LIMIT` was applied. So `list(q, Limit)` can return fewer rows, `first` can return empty while another matching
row exists, and `one(q)` can miss a second match (no `MQ2003`). MySQL keeps scanning. For queue claims use
`SKIP_LOCKED`.

Tested by `RowLockExecutionTest` and `ModelQueryRepositoryTest` in the TCK.
