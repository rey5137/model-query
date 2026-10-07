# Bulk writes

!!! warning "Incubating"
    Bulk updates and deletes, entity mode and write assignments (new in 0.4.0) are annotated `@Incubating`, as is the
    rest of the API in 0.x (see [API stability](stability.md)). They are complete and tested, but their API may still
    change in a minor release; at 1.0 the rest of the API freezes, and bulk writes freeze in a 1.x minor once one minor
    ships with no change to them.

A bulk write is "change the rows these filters match". It is rendered as one JPA `CriteriaUpdate` or
`CriteriaDelete`, or as a series of them in chunks. It reuses the [`Filters`](queries.md) DSL, the same join
resolution and converters as reads, and the vendor's limits.

!!! important "What a bulk write does not do"
    It never loads an entity, and runs no lifecycle callback, cascade, Bean Validation (other than on a change set
    you validate yourself) or audit listener. Those stay with ordinary JPA entity writes, or with
    [entity mode](#entity-mode), which runs them for an update or a delete.

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

## Entity mode

`throughEntities()` on an update or a delete writes through the entities instead of one bulk statement, so the things a
bulk write skips run: `@PreUpdate`/`@PostUpdate`, `@PreRemove`/`@PostRemove`, the provider's event listeners (an audit
library such as Envers), Bean Validation, cascades (`REMOVE`, `orphanRemoval`), the mapping's `@SQLDelete`, and the
second-level cache, which the provider maintains, so nothing is evicted. The entity stays hidden: you still write a
model and filters, never the entity.

An entity-mode write is always chunked, with `ModelQueryConfig.bulkWriteChunkSize()` when you give no `chunked(...)`.
Each chunk selects its keys as a chunked write does, loads the chunk's entities with one query, sets the assignments (an
update) or calls `remove` (a delete), and flushes. The provider writes only the entities that changed. Test:
`EntityUpdateTest.ac_wrt_34_an_entity_mode_update_fires_callbacks_and_the_hibernate_listener_once_per_changed_row_only`:

```java
var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID")
        .where(f -> f.lte(ID, 4L)).throughEntities().build();
```

Row 2 of that test is already `PAID`, so it fires no callback and no statement, but it counts. Test:
`EntityDeleteTest.ac_wrt_35_an_entity_mode_delete_fires_remove_callbacks_and_the_hibernate_listener_once_per_row`:

```java
var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).where(f -> f.lte(ID, 3L))
        .throughEntities().build();
```

What differs from a bulk write:

- The returned count is the entities matched, not the rows the provider wrote; a delete may remove more rows than it
  counts, through a cascade.
- `keepVersion()`, `expectVersion(v)` and `setExpression` cannot be honoured on entities: an update combining one with
  `throughEntities()`, in any order, throws `MQ1610` at `build()`; a delete has no such option. The provider checks
  and increments a `@Version`, and an `OptimisticLockException` reaches you as it is, or inside a
  `ChunkedWriteException` under `commitEachChunk()`.
- It needs a transaction (`MQ2501` without one) unless it commits each chunk. A resume after a `ChunkedWriteException`
  works as for a chunked write. Test:
  `EntityUpdateTest.ac_wrt_36_commit_each_chunk_resumes_from_chunked_write_exception_after_an_optimistic_lock_failure`.
- Under `PersistenceContextMode.CLEAR` the context is cleared right after your pending changes are flushed and after
  each chunk, so it holds at most one chunk of entities; test:
  `EntityUpdateTest.ac_wrt_36_each_chunk_loads_with_one_select_and_clear_holds_at_most_one_chunk_of_entities`.
  Under `KEEP` an update leaves every entity it loaded managed and current, and the context grows with every matched
  row; a delete leaves none of its removed entities managed, since the flush detaches them.
- A row your context held as an uninitialised proxy, from `em.getReference` or a lazy to-one, is written like any
  other: under `CLEAR` the clear before the first chunk drops the proxy, and under `KEEP` the engine unwraps it through
  the provider. A provider with no `EntityWriteSupport` cannot unwrap it, and the update throws `MQ2503` before it
  changes the chunk. Test:
  `EntityUpdateTest.ac_wrt_34_an_entity_mode_update_writes_a_row_the_context_held_as_a_proxy_under_clear_and_keep`.
- Each attribute is set through the provider's own attribute access, `model-query-hibernate`'s with Hibernate, so a
  bytecode-enhanced entity's dirty tracking sees the change. Without one, the engine sets the field by reflection, which
  enhanced or woven change tracking does not see, so the flush may write nothing. Test:
  `EntityUpdateTest.ac_wrt_34_an_entity_mode_update_writes_a_bytecode_enhanced_entity_through_its_dirty_tracking`.
- The mapping's own write rules hold, as for any entity change: an attribute mapped `@Column(updatable = false)`, or
  any attribute of a Hibernate `@Immutable` entity, a write assignment's included, is written by a bulk update but not
  by entity mode, and the count still counts the row.
- The query timeout applies to the key select and the load, not to the flush.
- It costs a key select and a load per chunk, then one `UPDATE` or `DELETE` statement per changed or removed entity at
  the flush, so use it where the callbacks or audit matter, and a bulk write where they do not. There is no entity mode
  for inserts: `persist` in a loop fires the same listeners.

## Write assignments

A server-set column, such as an updated-at stamp or an audit user, is easy to forget on one write among many. A
`WriteAssignment` names the attribute once per entity, on the `ModelQueryConfig`, and every write of that entity applies
it. Test: `WriteAssignmentTest.ac_wrt_39_a_bulk_update_sets_its_update_assignments_after_its_own_values_and_no_insert_one`:

```java
ModelQueryConfig.defaults().writeAssignments(List.of(
        WriteAssignment.of(InsAuditedBase.class, "createdBy", String.class, WriteKind.INSERT, created),
        WriteAssignment.of(InsAuditedBase.class, "updatedBy", String.class, WriteKind.INSERT_AND_UPDATE,
                updated),
        WriteAssignment.of(InsAuditedEntity.class, "stamp.touchedBy", String.class, WriteKind.UPDATE,
                touched),
        WriteAssignment.of(InsAuditedEntity.class, "callbackBy", String.class,
                WriteKind.INSERT_AND_UPDATE, written),
        WriteAssignment.of(InsSourceEntity.class, "code", String.class, WriteKind.INSERT_AND_UPDATE,
                () -> "never")));
```

`WriteAssignment.of(entity, attribute, type, kind, supplier)` names an entity class, a dot-separated path to a basic
attribute (through embeddables), the attribute's type and a supplier. It applies to the class and its subclasses, so one
on a `@MappedSuperclass` covers every entity extending it. One naming a subclass of a write's root, such as
`CardPayment` under a `Payment` root, is `MQ1611`: that write reaches every subclass's rows, so name the root or a
superclass. Under Spring Boot, declare each as a bean and the starter
hands them to the config of every datasource.

| `WriteKind` | Applied by |
|---|---|
| `INSERT` | insert-values, insert-select, `persist` |
| `UPDATE` | bulk update (chunked and entity mode), the `doUpdate` branch of a conflict clause |
| `INSERT_AND_UPDATE` | both lists above |

Deletes and `doNothing` apply none.

- The supplier is called once per write execution, so every chunk and row of one write gets the same value, and again
  on a resume. It may be called from several threads, so keep it thread-safe; a `Clock` keeps tests deterministic.
- An explicit `set`, `setNull` or change-set field for the same attribute wins, and the assignment is skipped.
- In a bulk update an assignment is one more bound `set` after your own; an update with nothing else to write stays a
  no-op.
- Under `persist` and entity mode the value is set on the entity before the flush, so an entity callback that sets the
  same attribute runs after it and wins. In an entity-mode update an `UPDATE` assignment dirties a matched row only
  where its value differs from the loaded one: a row already holding the value is not written and fires no update
  callback, and the count still counts it.
- The path is checked on the first write per entity and factory, before any statement: an unknown path, an id, a
  `@Version`, a collection, a to-one, a whole embeddable, overlapping kinds for one path, or a strict subclass of the
  root is `MQ1611`; a type the
  attribute cannot take, or a supplier returning `null` or the wrong type, is `MQ1612`.
