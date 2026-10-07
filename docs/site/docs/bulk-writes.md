# Bulk writes

!!! warning "Incubating"
    Bulk updates and deletes are annotated `@Incubating`, as is the rest of the API in 0.x (see
    [API stability](stability.md)). They are complete and tested, but their API may still change in a minor release;
    at 1.0 the rest of the API freezes, and bulk writes freeze in a 1.x minor once one minor ships with no change to
    them.

A bulk write is "change the rows these filters match". It is rendered as one JPA `CriteriaUpdate` or
`CriteriaDelete`, or as a series of them in chunks. It reuses the [`Filters`](queries.md) DSL, the same join
resolution and converters as reads, and the vendor's limits.

!!! important "What a bulk write does not do"
    It never loads an entity, and runs no lifecycle callback, cascade, Bean Validation (other than on a change set
    you validate yourself) or audit listener. Those stay with ordinary JPA entity writes.

## Update models and change sets

An **update model** lists the attributes of an entity that may be written. The processor generates a **change set**
from it that tells "set to NULL" from "not set", which is what a PATCH endpoint needs.

```java
@UpdateModel(root = BookEntity.class)
public record BookPatch(
        @PrimaryKey Long id,
        @NotNull @Size(min = 1, max = 20) String title,
        @Max(2100) Integer released) {}
```

The processor generates `BookPatchChanges` and `QBookPatch`:

```java
BookPatchChanges c = QBookPatch.changes()
        .title("New title")
        .released(null);                // explicitly NULL; fields not called are not written

c.isSet(QBookPatch.TITLE);              // true
c.unset(QBookPatch.RELEASED);           // drop a field again
c.isEmpty();

long written = executor.update(QBookPatch.update(c).whereKey(id).build());
```

A change set also has a no-argument constructor and JavaBean setters, so it binds from a request body: with Jackson,
`{"released": null}` clears `released` and a body without `released` leaves it alone. This is the PATCH endpoint of the
Spring Boot sample:

```java
@PatchMapping("/books/{id}")
ResponseEntity<Void> patch(@PathVariable long id, @Valid @RequestBody BookPatchChanges changes) {
    if (changes.isEmpty()) {
        return books.existsById(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
    long written = books.update(QBookPatch.update(changes).whereKey(id).build());
    return written == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
}
```

Declare one update model per endpoint: an endpoint that binds a change set can write every attribute the model lists.
A column the server sets, such as an audit timestamp, stays out of that model and is assigned with a hand-written
`ColumnField` and `set(...)` instead; otherwise a client that sends it causes `MQ1602`.

### Validation

A field that was set is checked against the Bean Validation constraints on the update model; a field that was not set
is not checked. So `@NotNull` means "may not be cleared", not "must be sent". The generated change set carries
`@ValidChanges`, so `@Valid @RequestBody BookPatchChanges` works with no configuration, and each violation is reported
on its own property. This needs `jakarta.validation` on the model module's classpath. The engine itself never
validates: `update` writes what it is given.

## Update definitions

`QBookPatch.update(changes)` starts a `ModelUpdate` builder. Each stage returns a new immutable builder, in the order
assignments, then the row choice, then options.

```java
ModelUpdate<OrderEntity, OrderPatch> u = QOrderPatch.update(changes)
        .set(UPDATED_AT, now)                                   // an extra assignment
        .setNull(QOrderPatch.NOTE)                              // NULL must be explicit
        .setExpression(QOrderPatch.TOTAL, (path, cb) -> cb.prod(path, rate))
        .where(f -> f.lt(QOrderPatch.CREATED_AT, cutoff)
                     .eq(QOrderPatch.CUSTOMER_COUNTRY, country))
        .keepVersion()                                          // opt out of the version increment
        .chunked(ChunkOptions.size(1_000))                      // optional: write in key-ordered chunks
        .build();

long rows = executor.update(u);
```

- `set(column, null)` fails with `MQ1603`: write NULL with `setNull` or a change set. On an attribute with a JPA
  `AttributeConverter`, `setNull` writes what the converter gives for `null`. Test:
  `UpdateNullConvertedTest.ac_wrt_01_null_on_a_jpa_converted_attribute_writes_what_the_converter_gives_null`.
- Primary-key and `@Version` columns cannot be assigned (`MQ1605`), and a column assigned twice fails with `MQ1602`.
- An update with nothing to write returns 0 without running SQL.
- Values are bound parameters and pass through the column's converter. A to-one association set by id binds a
  reference, so no row is loaded.
- `setExpression` is an escape hatch for expressions over the entity's own columns. Expressions that read a column
  another `setExpression` writes behave differently on MySQL (new value) than on PostgreSQL and H2 (old value).

### Choosing rows

| Call | Meaning |
|---|---|
| `where(f -> ...)` | Rows matching the filters. If every filter is skipped, `build()` fails with `MQ1601`. |
| `whereKey(id)`, `whereKeys(ids)` | By the root entity's primary key. `whereKeys` drops duplicates and splits the list across statements to the vendor's limits. |
| `all()` | Every row. The only way to write the whole table; you cannot combine it with `where`. |

There is no accidental full-table write: a `where` that ends up empty is an error, never "everything".

### Joins in the filter

A bulk write has no joins, so when any filter needs one, the whole filter tree is rendered inside one correlated
`EXISTS` sub-query over the root. That keeps `not` and `or` meaning what they mean on a read. On MySQL, which cannot
read the target table of an `UPDATE` in a sub-query (error 1093), the engine selects the matching keys first and then
writes them in chunks; see [Vendor notes](vendors.md). `ChunkOptions.lockKeys()` selects those keys with a pessimistic
write lock.

### Optimistic locking

When the entity has a `@Version`, every update increments it unless you call `keepVersion()`. After `whereKey`, you
can call `expectVersion(version)` to add `AND version = ?`; zero affected rows then throws JPA's
`OptimisticLockException`. `expectVersion` on an entity without a `@Version` fails with `MQ1606`.

## Deletes

```java
long deleted = executor.delete(QOrderView.delete()
        .where(f -> f.eq(QOrderView.STATUS, "DRAFT").lt(QOrderView.CREATED_AT, cutoff))
        .chunked(ChunkOptions.size(5_000))
        .build());
```

`ModelDelete` has the same `where`, `whereKey(s)`, `all()` and `chunked(...)` options. A soft delete is an update
(`set(DELETED, true)`).

## Transactions

A bulk write needs an active transaction: without one, `update` or `delete` fails with `MQ2501` naming the operation.
The exception is a write that commits each chunk itself.

### Chunked writes

`chunked(ChunkOptions.size(n))` selects the next `n` matching keys in primary-key order, writes them, and repeats
until a select returns fewer than `n` keys. The cursor only moves forward, so an update that leaves rows matching
cannot loop, and no row is written twice. The size is clamped to the vendor's limits; the default comes from
`ModelQueryConfig.bulkWriteChunkSize(int)` (`modelquery.bulk-write.chunk-size`, default 1000).

By default every chunk runs in your transaction. `ChunkOptions.size(n).commitEachChunk()` runs each chunk in a new
transaction, which keeps locks and undo logs short at the cost of atomicity. It is meant to run outside a transaction:
the row locks taken by the initial flush in your transaction would otherwise be held until it ends.

Committing each chunk needs a `ChunkTransactions` callback on `ModelQueryConfig` (`chunkTransactions(...)`), because the
library never starts a transaction itself. The Spring Boot starter provides one for every datasource. A plain-JPA
application writes its own: open an `EntityManager` on the given factory in a new transaction, run the chunk, commit.
Without one, `commitEachChunk()` fails with `MQ4004` before any statement runs.

If a chunk fails, a `ChunkedWriteException` (`MQ2502`) reports `committedRows()`, `lastCommittedKey()` and
`inDoubtKeys()` (the keys of a chunk whose commit outcome is unknown). Committed chunks stay committed. You can resume
with `chunked(options, startAfter)` after `lastCommittedKey()`. Re-running or resuming is only safe when the write is
idempotent: `total * 1.1` would apply again to rows already committed.

## The persistence context

Before its first statement a bulk write flushes the `EntityManager` when it is joined to a transaction, so pending
entity changes are written first and not overwritten later. After the last statement it clears the persistence
context by default (`PersistenceContextMode.CLEAR`, `modelquery.bulk-write.persistence-context=clear`).

!!! warning
    `CLEAR` detaches **every** managed entity, not just the root type, so a later change to any of them is silently
    not written. `KEEP` leaves the root's entities managed but stale; on an entity without a `@Version`, or with
    `keepVersion()`, flushing a stale entity writes its old values back over the bulk write. The root entity is also
    evicted from the second-level cache either way.
