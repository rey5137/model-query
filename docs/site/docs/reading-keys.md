# Reading many keys

!!! note "Incubating"
    Reading many keys is annotated `@Incubating` (see [API stability](stability.md)).

`byKeys(q, keys)` reads the models of many primary keys at once and returns them as an unmodifiable `Map<K, M>`
(D-126, `@Incubating`):

=== "Plain JPA"

    ```java
    Map<Long, OrderView> byId = executor.byKeys(q, List.of(1L, 2L, 3L));
    ```

=== "Spring repository"

    ```java
    Map<Long, OrderView> byId = orders.findAllByKeys(q, List.of(1L, 2L, 3L));
    ```

The map iterates in the order of the keys' first occurrence, and a key with no row — or one `q`'s filter
excludes — is absent rather than a `null` entry. Each key is converted as `whereKey` converts it, so two keys
a converter maps to one value share one model, read once, and rows are matched back by the converted attribute
value. A duplicate key is `MQ2213`, as `one(q, key)` gives, including a non-id model key matching two rows.
If a customizer's join, or a shape the semi-join excludes, repeats the row, both calls give `MQ2003` with a
`Filters.exists` hint. A row whose key equals none of the requested values is `MQ2005`, naming the value, before
any row is mapped. That happens when the column's collation is case-insensitive or ignores trailing spaces, or a
`BigDecimal` key has a different scale, so the row the database matched is not one the caller asked for. `keys` may
be larger than the vendor's limits: the library spreads them over as many statements as the bind limits allow, and
each key is read in exactly one. No keys return an empty map and run no SQL.

`byKeys` ignores the query's `orderBy`, since the map is in key order; a call on an ordered query logs one `WARNING`,
`<Model>: byKeys(query, keys) ignores the query's orderBy; the map is in key order`. `count(q)`, `one(q)` and
`one(q, key)` ignore an `orderBy` the same way and log one `WARNING` each; the two `one` statements render no
`ORDER BY`. `first` uses the order and is unchanged. On a Spring repository `byKeys` is `findAllByKeys`; see
[Spring Data](spring.md#the-repository).

## Key groups on a partitioned table

!!! note "Incubating"
    Key groups are annotated `@Incubating` (see [API stability](stability.md)).

On a table partitioned by a column the key does not carry, `byKeys` reads every partition. When the key encodes the
partition (a time-ordered id and a table partitioned by month), declare how a key maps to a range with
`ModelQuery.Builder#keyGroups`. The call then reads its keys in rounds per group, each round binding its group's range
into the query's own filter, so a round reads one partition:

```java
record Month(Instant from, Instant to) {}

static final QueryParam<Instant> CREATED_FROM = QueryParam.of("createdFrom", Instant.class);
static final QueryParam<Instant> CREATED_TO = QueryParam.of("createdTo", Instant.class);

static final KeyGroups<Long, Month> BY_MONTH = KeyGroups.by(Long.class, (Long id) -> monthOf(id))
        .bind(CREATED_FROM, Month::from)
        .bind(CREATED_TO, Month::to)
        .verifyMissing(new Month(Instant.parse("1000-01-01T00:00:00Z"), Instant.parse("9999-12-31T00:00:00Z")));

var q = QEvent.query().select(QEvent.ALL)
        .where(f -> f.compare(QEvent.CREATED_AT, Op.GTE, Expr.param(CREATED_FROM))
                .compare(QEvent.CREATED_AT, Op.LT, Expr.param(CREATED_TO)))
        .keyGroups(BY_MONTH)
        .build();
```

=== "Plain JPA"

    ```java
    Map<Long, Event> byId = executor.byKeys(q, ids);
    Optional<Event> one = executor.one(q, id);
    ```

=== "Spring repository"

    ```java
    Map<Long, Event> byId = events.findAllByKeys(q, ids);
    Optional<Event> one = events.findByKey(q, id);
    ```

The map is the one an ungrouped call returns, in the order of the keys' first occurrence. The groups are built from the
distinct keys after the dedupe, in the order they first appear, and each key is read in exactly one round.

**Every declaration ends in `verifyMissing(all)` or `assumeInRange()`:**

- `assumeInRange()` is for data where the key always agrees with the row. A row outside its key's range is silently not
  read, and its key looks absent: no error, no log.
- `verifyMissing(all)` re-reads the keys that found no row with the values of `all` (above, a range that covers every
  partition) and returns the rows it finds, logging `MQ2608` once per definition. While the keys agree with the data it
  adds no statement; for the keys that do not, one statement over all partitions. Bind the exact range: a margin widens
  every round's scan (a day's margin on a month overlaps three partitions).

A query with key groups is read only by `byKeys` and `one(q, key)`; `list`, `page`, `count`, `one(q)`, `first(q)`,
`export`, `stream` and the `returning` query of a write are `MQ2215` before any statement, naming the call and the
params the groups bind. A query that other reads need is built without `keyGroups`, with `bind` for the params. A copy
from `withLock`, `withFetch` or `orderedBy` keeps the groups. `MQ1708` is a definition that does not fit the query
(`keyType` not the primary key's type, a bound param nothing reads, `verifyMissing` with no `bind`), at `build()`;
`MQ2607` is a group function that returned `null`, before any statement.

**With a row lock,** the rounds of a grouped call run in sorted key order so that two calls lock rows in the same
order (see [Row locks](row-locks.md)): with `Comparable` keys a round is a run of keys that are next to each other in
sorted order and share a group. Keys that interleave across groups (odd and even months, say) then form more rounds,
up to one statement per key, where an unlocked call runs one per group. Group a locked call only when its keys
cluster. The `verifyMissing` re-read is the one exception to the order: it can lock rows after higher keys, so two
overlapping locked calls that both re-read can deadlock, and the database aborts one.

**When to group.** Grouping pays when many keys share a group and costs when they do not:

- Group when each group holds **about 300 keys or more**. Fifty keys spread over 36 months are 36 statements, about
  twenty times slower than one; small spread pages do not pay, and an export pays it on every page.
- `groupWhen(rule, all)` lets each call decide from its distinct keys and groups:
  `.groupWhen(GroupingRule.minKeysPerGroup(300), all)`. When the rule says no, the call runs its ordinary chunks with
  `all`'s values bound and no re-read. Without `groupWhen`, every call is grouped. Under `groupWhen` a group param may
  be read only in a `>`, `>=`, `<` or `<=` comparison with a column, and `verifyMissing(all)` must be given the same
  `all`.
- Bound values are the attribute's type, an `Instant` for `timestamptz` or `DATETIME`. They reach the driver as a
  `Timestamp`, which prunes on PostgreSQL and MySQL; the JVM and the database session must agree on the time zone.
- On MySQL use `PARTITION BY RANGE COLUMNS (created_at)`, which prunes cleaner than `RANGE (TO_DAYS(created_at))`, and
  put the partition column in every unique key; the entity's `@Id` can stay `id`. The same applies to
  [child loads](fetch-plans.md#child-loads-over-partitioned-tables).
