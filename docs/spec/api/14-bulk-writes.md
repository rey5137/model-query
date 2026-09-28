# 14 — Bulk Updates and Deletes

**Covers:** `ModelUpdate`, `ModelDelete`, change sets (`Changes<M>`), how a bulk write renders and runs, and the
executor's `update`/`delete`.
**Read when:** working on M8, or deciding whether a write belongs in this library at all.
**Owns:** `R-WRT-*`, `AC-WRT-*`. **Status: `Future` (M8, after 0.1.0).** `@Incubating` until the M7 API review. Update
models are generated as in `processor/31` §6; the reasoning is D-14 and D-17.

---

## 1. Scope

**R-WRT-01** A bulk write is "change the rows these filters match", rendered as one JPA `CriteriaUpdate` or
`CriteriaDelete`, or a chunked series of them (R-WRT-17). It reuses the `Filters` DSL (`api/12`), join resolution
(`api/10`), converters and the vendor's limits. It never loads an entity, and runs no lifecycle callback, cascade, Bean
Validation or Envers audit: those stay with JPA entity writes (INV-1, D-14). Every write method's Javadoc says so.

## 2. Change sets

Writing `set(...)` once per field is tedious for a wide entity, and cannot express "only the fields the client sent".
An update model lists the writable attributes once:

```java
@UpdateModel(root = OrderEntity.class)
@FilterColumn(name = "CREATED_AT", path = "createdAt")                 // filter-only, as on query models
@FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country")
public record OrderPatch(                       // a class works too; it is only read by the processor
        @PrimaryKey Long id,                    // the entity id; never written; used by whereKey(...)
        OrderStatus status,                     // converter from @Column or the entity mapping, as for queries
        BigDecimal total,
        Instant deliveredAt,
        String note,
        @Column(attribute = "customer") Long customerId) {}   // to-one by id: writes the foreign key

OrderPatchChanges c = QOrderPatch.changes()
        .status(OrderStatus.SHIPPED)
        .deliveredAt(null);                     // explicitly NULL; total, note, customerId not set

c.isSet(QOrderPatch.TOTAL);                     // false
c.unset(QOrderPatch.STATUS);                    // drop a field again

long n = executor.update(QOrderPatch.update(c).whereKey(id).build());
// UPDATE orders SET delivered_at = NULL, version = version + 1 WHERE id = ?
```

**R-WRT-02** A change set records which setters were called. "Set to NULL" and "not set" are different states and
stay different through `assignments()` (R-WRT-13).

**R-WRT-03** A change set also has a no-arg constructor and JavaBean setters, so it binds straight from a PATCH request
body. Binders such as Jackson call only the setters of properties that are present, so `{"note": null}` clears `note`
and a body without `note` leaves it alone. `core` needs no Jackson dependency for this.

```java
@PatchMapping("/orders/{id}")
void patch(@PathVariable long id, @RequestBody OrderPatchChanges changes) {
    orders.update(QOrderPatch.update(changes).whereKey(id).build());
}
```

**R-WRT-04** `OrderViewChanges.from(view, QOrderView.EDITABLE)` (a query model with `generateChanges = true`,
`processor/30` R-PROC-19) copies the named columns from a model instance, NULLs included. There is deliberately no
"ignore nulls" copy, because that is how a PATCH endpoint loses the ability to clear a field.

## 3. Update definition

```java
// A column the server sets stays out of the update model bound from requests (R-WRT-13).
static final ColumnField<OrderPatch, OrderEntity, Instant> UPDATED_AT =
        ColumnField.of(OrderPatch.class, QOrderPatch.ROOT, "updatedAt", Instant.class);

ModelUpdate<OrderEntity, OrderPatch> u = QOrderPatch.update(changes)   // or ModelUpdate.builder(QOrderPatch.ROOT)
        .set(UPDATED_AT, now)                              // extra assignment
        .setNull(QOrderPatch.NOTE)                         // NULL must be explicit
        .setExpression(QOrderPatch.TOTAL, (path, cb) -> cb.prod(path, rate))   // escape hatch
        .where(f -> f.lt(QOrderPatch.CREATED_AT, cutoff)
                     .eq(QOrderPatch.CUSTOMER_COUNTRY, country))   // joined: the tree renders in one EXISTS (R-WRT-10)
        .keepVersion()                                     // opt out of the version increment (R-WRT-16)
        .chunked(ChunkOptions.size(1_000))                 // optional: key-first chunks (R-WRT-17)
        .build();

ModelUpdate<OrderEntity, OrderPatch> v = QOrderPatch.update(changes)
        .whereKey(id)
        .expectVersion(version)                            // AND version = ? (R-WRT-16)
        .build();

ModelDelete<OrderEntity, OrderPatch> purge = QOrderPatch.delete()
        .all()                                             // every row; no where after this (R-WRT-12)
        .build();
```

**R-WRT-05** `build()` returns an immutable `ModelUpdate` (INV-9), as `ModelQuery` does. `set(Changes)` copies the
change set's `assignments()` when it is called, so changing the change set afterwards changes neither the builder nor
an update already built.

**R-WRT-06** `set` accepts only `ColumnField<M, E, C>` whose table is the root entity type `E`, so most joined columns
do not compile (P-2). A join back to the same entity type (a self-reference) passes the type check and throws `MQ1604`
at `build()`. `set(col, null)` throws `MQ1603`: NULL is written only through `setNull` or a change set.

**R-WRT-07** `set(Changes<M>)` adds every column the change set marked. An update whose assignment list is empty —
typically an empty change set — is a no-op: `update` returns 0 without running SQL. With `expectVersion` it still runs
and increments the version, so a stale version still throws; with `keepVersion` as well there is nothing to write and
`build()` throws `MQ1606`. A caller that must tell "nothing to write" from "no row matched" checks
`Changes#isEmpty()` first.

**R-WRT-08** `whereKey(key)` and `whereKeys(keys)` filter by the root entity's id, single or composite. A definition's
`@PrimaryKey` must name exactly the id attributes of the JPA metamodel, else `build()` throws `MQ1608` (the processor
reports `MQ3306` earlier when it can see the entity): a key that is not the id could match several rows. `whereKeys`
splits a long list across statements to the vendor's limits (`engine/21` R-PAG-07), counting one bind parameter per
key component, and returns the summed count.

## 4. Delete definition

```java
long d = executor.delete(QOrderView.delete()
        .where(f -> f.eq(QOrderView.STATUS, "DRAFT").lt(QOrderView.CREATED_AT, cutoff))
        .chunked(ChunkOptions.size(5_000))
        .build());
```

**R-WRT-09** `ModelDelete` has the same `where`, `whereKey(s)`, `all()` and `chunked(...)` options as `ModelUpdate`,
and is immutable. A soft delete is an update (`set(DELETED, true)`); an entity mapped with Hibernate's `@SoftDelete`
gets that from Hibernate itself.

## 5. Correctness

A bulk write affects exactly the rows the equivalent read returns (D-17). The rules below are how.

**R-WRT-10** **Bulk writes never join.** `CriteriaUpdate` and `CriteriaDelete` have a root and no joins. A filter tree
that needs no join renders directly. If any predicate in the tree needs a join, the **whole tree** renders inside one
correlated sub-query over the root, built with the same `JoinContext` as the read path:
`EXISTS (SELECT 1 FROM orders o2 LEFT JOIN … WHERE o2.pk = o.pk AND <tree>)`, which works for composite keys too. The
tree is never split per predicate, because that changes what `not` and `or` mean: `not(eq(CUSTOMER_COUNTRY, "X"))`
excludes an order with no customer on the read path, while `NOT EXISTS (…)` would include it. `exists(...)` filters
stay correlated sub-queries inside the tree.

**R-WRT-11** **Bulk writes work where the database cannot read the target table in a sub-query.** The rendering reads
the target table in a sub-query when R-WRT-10 builds one, when an `exists(...)` path leads back to the root entity
type, or when the root uses `SINGLE_TABLE` or `JOINED` inheritance. If it does and
`VendorProfile.targetTableInSubquery()` is false (MySQL, error 1093; `vendor/40` R-VND-11), the engine runs key-first:
select the matching keys with the query engine, then write `WHERE pk IN (…) AND <root predicates>` in chunks sized by
R-WRT-08. The root predicates are the top-level `AND` terms that need no join and no sub-query; re-applying them means
a row that stopped matching on its own columns between the two steps is not written. A change to a joined row in
between is not re-checked; `ChunkOptions.lockKeys()` selects the keys with `LockModeType.PESSIMISTIC_WRITE`, which on
MySQL also makes the select read current rows rather than the transaction's snapshot. The Javadoc states both.

**R-WRT-12** **No accidental full-table writes.** A builder chooses its rows with `where(...)` and `whereKey(s)(...)`,
which combine with `AND`, or with `all()`, which excludes both: `all()` returns a stage with no `where`, and neither
`where` nor `whereKey(s)` returns one with `all()`, so `where(...).all()` does not compile (P-2). If no predicate is
left after skipping empty `Optional`s (`api/12` R-FLT-01), `build()` throws `MQ1601`; `all()` is the only way to write
every row. `whereKeys` with an empty collection affects nothing and runs no SQL; it never counts as "no predicate".
`in(col, List.of())` still renders `FALSE` and affects nothing (`api/12` R-FLT-02).

**R-WRT-13** **Only what was set is written.** The `SET` clause holds exactly the columns marked in the change set plus
the explicit `set`/`setNull`/`setExpression` calls. "Set to NULL" writes NULL; "not set" writes nothing. A column
assigned twice throws `MQ1602` at `build()`. Primary-key columns and the `@Version` attribute are never assignable; a
hand-written column naming one throws `MQ1605`. A column the server sets, such as an audit timestamp, stays out of any
update model bound from a request and is assigned with a hand-written `ColumnField` (`api/10` §3); otherwise a client
that sends it turns a normal request into `MQ1602`. `setExpression` assignments render first, then the others in
declaration order, so an expression reading a column that a plain assignment writes sees the old value on every
vendor. An expression reading a column that another `setExpression` writes is vendor-dependent (MySQL reads the new
value, PostgreSQL and H2 the old one) and the Javadoc says so. `setExpression` is an escape hatch like `Agg.of`
(`reference/92` §3).

**R-WRT-14** **Assigned values are typed and bound.** Values pass through the column's converter and are always bind
parameters, never inlined. A to-one attribute set by id binds `EntityManager#getReference(target, id)`, so no row is
loaded. Types are checked as for queries (INV-3, `api/10` R-COL-08). `setExpression` on a column with a converter
throws `MQ1609` at `build()`, because the expression's path has the entity attribute's type, not the model's.

**R-WRT-15** **The persistence context is not left stale.** Before the statement the engine calls `flush()`, so pending
entity changes are written first and not overwritten afterwards. After it, `clear()` by default
(`PersistenceContextMode.CLEAR`), or nothing with `KEEP`. `CLEAR` detaches every managed entity, not only the root
type's, so a later change to any of them is silently not written; the Javadoc and the user guide say so. With `KEEP`
the root's entities stay managed but stale, and flushing a stale versioned one fails loudly because its version moved
(R-WRT-16). Either way the engine evicts the root entity from the second-level cache with
`EntityManagerFactory#getCache().evict(root)`, which is portable.

**R-WRT-16** **Bulk updates respect optimistic locking.** When the root has a `@Version` attribute, every update renders
`version = version + 1` (or the current timestamp for a timestamp version) unless `keepVersion()` is set.
`expectVersion(v)` adds `AND version = ?`; it needs `whereKey` and a root with a `@Version` attribute, else `MQ1606`.
Zero affected rows then throws JPA's `OptimisticLockException`.

**R-WRT-17** **Chunked writes write no row twice and terminate.** Keyset over the root id: select the next `n` matching
keys `WHERE <tree> AND pk > :last ORDER BY pk` (a composite id uses the OR-expansion of `vendor/41` §5), write
`WHERE pk IN (…) AND <tree>` (the root predicates only where R-WRT-11 applies), and repeat until a chunk is short. The
cursor only moves forward, so an update that leaves rows matching cannot loop, and a delete never re-reads what it
removed. A row that matches for the whole call is written exactly once; a row whose match changes during the call, by
another transaction, may or may not be written. The chunk size is clamped to the vendor's limits as in R-WRT-08.

## 6. Transactions

**R-WRT-18** A bulk write needs an active transaction, except with `commitEachChunk()` (R-WRT-19). The executor checks
`EntityManager#isJoinedToTransaction` and throws `MQ2501` naming the operation, instead of the provider's
`TransactionRequiredException` at the end.

**R-WRT-19** A chunked write runs every chunk in the caller's transaction by default. `ChunkOptions.commitEachChunk()`
runs each chunk in a new transaction instead, which keeps locks and undo logs short at the cost of atomicity. The
library never starts a transaction itself: it calls the `ChunkTransactions` callback on `ModelQueryConfig`,

```java
public interface ChunkTransactions {
    // open an EntityManager on emf in a new transaction, run the chunk, commit, close, return its result
    <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk);
}
```

passing the executor's own `EntityManagerFactory`, so one callback serves every datasource. A chunk's key select and
its write both run on the callback's `EntityManager`. `commitEachChunk()` with no callback, or with one that cannot
serve that factory, throws `MQ4004` before any statement runs. The Spring starter provides one (`integration/50`
R-SPR-11); a plain-JPA caller writes their own, usually `begin`/`commit` on a resource-local `EntityManager` (INV-8,
D-16). The caller's `EntityManager` is still flushed first (R-WRT-15); inside a transaction, the row locks that flush
takes are held until that transaction ends, and a chunk writing one of those rows waits on them until the lock
timeout. `commitEachChunk()` is therefore meant to run outside a transaction, and the Javadoc says so.

**R-WRT-20** A per-chunk write that fails throws `ChunkedWriteException` (`MQ2502`) with the provider's exception as
the cause, even when the first chunk fails. It carries `committedRows()` and `lastCommittedKey()` (empty when nothing
committed), and `inDoubt()`, true when the commit of a chunk itself failed, so that chunk's outcome is unknown and its
rows are not counted. Committed chunks stay committed, and the message says so, because a caller who assumed
atomicity would otherwise misread the table's state (INV-5). `ChunkOptions.startAfter(key)` resumes after
`lastCommittedKey()`. Re-running the whole write is safe only when it is idempotent: `total * 1.1` would apply again
to rows already committed, and the Javadoc says so.

## 7. Validation

**R-WRT-21** A change set is validated field by field: a field that was set is checked against the Bean Validation
constraints declared on the update model's field; a field that was not set is not checked, so `@NotNull` means "may not
be cleared", not "must be sent". The processor never copies constraint annotations onto the change set (D-15). The
generated class carries one class-level constraint, `@ValidChanges`, whose validator calls
`Validator.validateValue(OrderPatch.class, property, value)` for each set field, so `@Valid @RequestBody
OrderPatchChanges` works with no configuration (`processor/31` R-GEN-23). Each violation is re-reported on its own
property node (`addPropertyNode(property)`), so Spring maps it to a field error. The already-interpolated message is
escaped (`\`, `{`, `}`, `$`) before `buildConstraintViolationWithTemplate`, so a rejected value that appears in a
message is never evaluated as an expression.

**R-WRT-22** `@ValidChanges` and its validator live in `model-query-jpa` behind an optional `jakarta.validation`
dependency (`delivery/61` R-REL-03). The processor emits `@ValidChanges` only when both it and
`jakarta.validation.Constraint` resolve on the model module's compile classpath, so a model module without either
compiles and references neither. The engine itself never validates: `update` writes what it is given.

## 8. Executor surface

```java
public interface ModelQueryExecutor<E> {
    // … the read methods of api/11 §6
    long update(ModelUpdate<E, ?> u);               // rows affected
    long delete(ModelDelete<E, ?> d);               // rows affected
}
```

**R-WRT-23** Both methods return the rows affected, summed over chunks, and are reachable with a plain `EntityManager`
(INV-8).

## 9. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-WRT-01 | A change set writes only its set columns; a column set to NULL writes NULL and an unset one is absent from the `SET` clause (R-WRT-02, R-WRT-13). |
| AC-WRT-02 | A change set bound by Jackson from `{"note": null}` clears `note`; a body without `note` leaves it unchanged (R-WRT-03). |
| AC-WRT-03 | `from(model, columns)` copies NULLs; a non-writable column in `columns` throws `MQ1607` (R-WRT-04). |
| AC-WRT-04 | `set` on a self-referencing join throws `MQ1604`; `set(col, null)` throws `MQ1603`; a column assigned twice throws `MQ1602` (R-WRT-06, R-WRT-13). |
| AC-WRT-05 | An empty change set runs no SQL and returns 0; with `expectVersion` it runs and a stale version throws `OptimisticLockException`; with `keepVersion` as well `build()` throws `MQ1606`. Changing a change set after `set(...)` leaves the built update unchanged (R-WRT-05, R-WRT-07). |
| AC-WRT-06 | `whereKeys` with more keys than the vendor's limits, single and composite, updates every key once; `whereKeys` with no keys runs no SQL; a `@PrimaryKey` that is not the entity id throws `MQ1608` (R-WRT-08). |
| AC-WRT-07 | For every fixture of the TCK Filters group, including `not` and `or` over a LEFT-joined column whose association is missing, the keys a write affects equal the keys the matching read returns: as one statement on H2 and PostgreSQL, key-first on MySQL, and chunked on all three (R-WRT-10, R-WRT-11, R-WRT-17). |
| AC-WRT-08 | A write with every filter skipped throws `MQ1601`; `all()` writes every row; `where(...).all()` does not compile (R-WRT-12). |
| AC-WRT-09 | Converters apply to assigned values, and a to-one set by id loads no row; `setExpression` on a converted column throws `MQ1609` (R-WRT-14). |
| AC-WRT-10 | Pending entity changes are flushed first; the persistence context is cleared by default and kept with `KEEP`; the root's second-level cache entries are evicted (R-WRT-15). |
| AC-WRT-11 | Updates increment the version; `keepVersion` does not; an `expectVersion` mismatch throws `OptimisticLockException`; `expectVersion` without `whereKey`, or on a root with no `@Version`, throws `MQ1606` (R-WRT-16). |
| AC-WRT-12 | A chunked update that leaves rows matching terminates and touches each row once; a chunked delete crosses the vendor's IN limits, single and composite key (R-WRT-17). |
| AC-WRT-13 | A write with no transaction throws `MQ2501`; a delete blocked by a foreign key surfaces the provider's constraint exception (R-WRT-18). |
| AC-WRT-14 | `commitEachChunk()` with no `ChunkTransactions` throws `MQ4004` and runs no SQL; with a plain-JPA resource-local callback each chunk commits separately (R-WRT-19). |
| AC-WRT-15 | A per-chunk write whose third chunk fails throws `ChunkedWriteException` (`MQ2502`) reporting the first two chunks' rows and last key, and those rows stay written; a first-chunk failure also throws it, with zero rows; `startAfter` resumes after the last key (R-WRT-20). |
| AC-WRT-16 | With `@NotNull` and `@Size` on update-model fields: an unset field passes, a field set to NULL fails, a set field over the size fails; `@Valid @RequestBody` reports each as a field error in the Spring sample; a value containing `${…}` is not evaluated (R-WRT-21). |
| AC-WRT-17 | Without `jakarta.validation` on the classpath, generated change sets compile, carry no `@ValidChanges`, and nothing is validated (R-WRT-22). |
| AC-WRT-18 | On MySQL, a row that stops matching on a root column between the key select and the write is not written; with `lockKeys()` a concurrent change to a matched row waits for the write (R-WRT-11). |
| AC-WRT-19 | `setExpression` reading a column that a plain assignment writes gives the same result on every Tier-1 vendor (R-WRT-13). |
