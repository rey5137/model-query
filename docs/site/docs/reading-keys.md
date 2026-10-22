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
