# 14 — Writes

**Covers:** `ModelUpdate`, `ModelDelete`, change sets (`Changes<M>`), how a bulk write renders and runs, and the
executor's `update`/`delete`; from M10 the inserts (`ModelInsert`, `ValuesInsert`, `ModelPersist`, §10); from M11
entity mode, `persist` returning a model and write assignments (§11).
**Read when:** working on M6, M10 or M11, or deciding whether a write belongs in this library at all.
**Owns:** `R-WRT-*`, `AC-WRT-*`. **Status: M6, before 0.1.0; §10 M10, 0.3.0; §11 M11, 0.4.0.** `@Incubating` until
the M8 API review; the insert types and §11's surface stay `@Incubating` (D-85, D-116, D-118). Update models are
generated as in `processor/31` §6; the reasoning is D-14 and D-17, for inserts D-116 and D-117, and for entity writes
D-118.

---

## 1. Scope

**R-WRT-01** A bulk update or delete is "change the rows these filters match", rendered as one JPA `CriteriaUpdate` or
`CriteriaDelete`, or a chunked series of them (R-WRT-17); a bulk insert is detailed in §10. It reuses the `Filters` DSL
(`api/12`), join resolution (`api/10`), converters and the vendor's limits. It never loads an entity, and runs no
lifecycle callback, cascade, Bean Validation or Envers audit: those stay with JPA entity writes (INV-1, D-14). Every
write method's Javadoc says so. The exception is an update or delete built with `throughEntities()` (§11, D-118): it
reuses the same filters and chunking, but loads the matched entities chunk by chunk and writes them through the
`EntityManager`, so callbacks, listeners and audits run.

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

// QOrderPatch.update(changes) is ModelUpdate.builder(QOrderPatch.ROOT).primaryKey(QOrderPatch.PK).set(changes).
// Each stage returns a new immutable builder; the order is assignments, one row choice, options (D-60).
ModelUpdate<OrderEntity, OrderPatch> u = QOrderPatch.update(changes)
        .set(UPDATED_AT, now)                              // extra assignment
        .setNull(QOrderPatch.NOTE)                         // NULL must be explicit
        .setExpression(QOrderPatch.TOTAL, (path, cb) -> cb.prod(path, rate))   // escape hatch
        .where(f -> f.lt(QOrderPatch.CREATED_AT, cutoff)
                     .eq(QOrderPatch.CUSTOMER_COUNTRY, country))   // joined: the tree renders in one EXISTS (R-WRT-10);
                                                                   // a second where, or all(), doesn't compile
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
`@PrimaryKey` must name exactly the id attributes of the JPA metamodel, else the definition's first execution throws
`MQ1608` before any statement, the flush included (`build()` sees no metamodel, INV-7; the processor reports `MQ3306`
earlier when it can see the entity; D-61): a key that is not the id could match several rows. `whereKeys` drops
duplicate keys, so each distinct key is written once, then splits the list across statements to the vendor's limits
(`engine/21` R-PAG-07), counting one bind parameter per key component plus the statement's own binds, `SET` values
included, at most the largest power of two of keys within them, and returns the summed count (D-63, D-80).

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
the target table in a sub-query when R-WRT-10 builds one, when an `exists(...)` path or a sub-select (`api/12`
R-FLT-15) leads back to the root entity type, or when the root uses `SINGLE_TABLE` or `JOINED` inheritance. If it does and
`VendorProfile.targetTableInSubquery()` is false (MySQL, error 1093; `vendor/40` R-VND-11), the engine runs key-first:
select the matching keys with the query engine, then write `WHERE pk IN (…) AND <root predicates>` in chunks sized by
R-WRT-08. The root predicates are the top-level `AND` terms that need no join and no sub-query; re-applying them means
a row that stopped matching on its own columns between the two steps is not written. A change to a joined row in
between is not re-checked; `ChunkOptions.lockKeys()` selects the keys with `LockModeType.PESSIMISTIC_WRITE`, which on
MySQL also makes the select read current rows rather than the transaction's snapshot. The Javadoc states both. A
sub-query also reads the target table when it reads a second entity that shares one of the root's tables: `jpa`
intersects, ignoring case, the tables `ProviderSupport.tablesOf` reports for the root and for each entity an
`exists(...)` sub-query or sub-select reads, and where either set is empty, as without `model-query-hibernate`, compares the entities
alone, so two entities on one table then go undetected and MySQL fails loudly with error 1093 (`vendor/40` R-VND-13,
D-109). `core` reports the entities a sub-query reads, never a table (INV-7).

**R-WRT-12** **No accidental full-table writes.** A builder chooses its rows with `where(...)` and `whereKey(s)(...)`,
which combine with `AND`, or with `all()`, which excludes both: `all()` returns a stage with no `where`, and neither
`where` nor `whereKey(s)` returns one with `all()`, so `where(...).all()` does not compile (P-2). If no predicate is
left after skipping empty `Optional`s (`api/12` R-FLT-01), `build()` throws `MQ1601`; `all()` is the only way to write
every row. `whereKeys` with an empty collection affects nothing and runs no SQL; it never counts as "no predicate".
`in(col, List.of())` still renders `FALSE` and affects nothing (`api/12` R-FLT-02); `notIn(col, List.of())` is an
explicit predicate that matches every row and never throws `MQ1601` (D-64). The builders take a root `TableField`;
a join throws `MQ1203`.

**R-WRT-13** **Only what was set is written.** The `SET` clause holds exactly the columns marked in the change set plus
the explicit `set`/`setNull`/`setExpression` calls. "Set to NULL" writes NULL; "not set" writes nothing. A column
assigned twice throws `MQ1602` at `build()`. Primary-key columns and the `@Version` attribute are never assignable; a
hand-written column naming one throws `MQ1605`, at `build()` when it is a column of the definition's `@PrimaryKey`,
otherwise on the definition's first execution, before any statement, from the JPA metamodel (D-61). A column the server sets, such as an audit timestamp, stays out of any
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

**R-WRT-15** **The persistence context is not left stale.** Once per write, before its first statement, the engine calls
`flush()` when the `EntityManager` is joined to a transaction, so pending entity changes are written first and not
overwritten afterwards; outside one (only possible with `commitEachChunk()`, R-WRT-19) there is nothing it could flush.
After the write's last statement, `clear()` by default (`PersistenceContextMode.CLEAR`), or nothing with `KEEP`. `CLEAR`
detaches every managed entity, not only the root type's, so a later change to any of them is silently not written; the
Javadoc and the user guide say so. With `KEEP` the root's entities stay managed but stale. Flushing a stale versioned
one fails loudly because its version moved (R-WRT-16); with `keepVersion()`, or on a root with no `@Version` attribute,
nothing moved, so the flush silently writes the stale values back over the bulk write, and the Javadoc of `KEEP` says
so. Either way the engine evicts the root entity from the second-level cache with
`EntityManagerFactory#getCache().evict(root)`, which is portable. An entity-mode write keeps the flush before its
first chunk but clears per chunk and skips the eviction (R-WRT-43, R-WRT-45).

**R-WRT-16** **Bulk updates respect optimistic locking.** When the root has a `@Version` attribute, every update renders
`version = version + 1` (or the current timestamp for a timestamp version) unless `keepVersion()` is set.
`expectVersion(v)` adds `AND version = ?`. It is offered only after `whereKey`, so without one it doesn't compile
(D-60); on a root with no `@Version` attribute, or with a value of the wrong type, or (without `keepVersion()`) with a
`@Version` of a type an update cannot increment, the definition's first execution throws `MQ1606` before any statement
(D-61).
Zero affected rows then throws JPA's `OptimisticLockException`. In entity mode the provider checks and increments the
version, and `keepVersion()` and `expectVersion(v)` are `MQ1610` at `build()` (R-WRT-46).

**R-WRT-17** **Chunked writes write no row twice and terminate.** Keyset over the root id: select the next `n` matching
keys `WHERE <tree> AND pk > :last ORDER BY pk` (a composite id uses the OR-expansion of `vendor/41` §5), write
`WHERE pk IN (…) AND <tree>` (the root predicates only where R-WRT-11 applies), and repeat until a key select returns fewer than `n` keys, counted from the select, never from the rows the write
affected. A key select that returns a key the round before it wrote throws, as R-PAG-14 does (`MQ2205`). The
cursor only moves forward, so an update that leaves rows matching cannot loop, and a delete never re-reads what it
removed. A row that matches for the whole call is written exactly once; a row whose match changes during the call, by
another transaction, may or may not be written. The chunk size is clamped to the vendor's limits as in R-WRT-08. A key-first write without `chunked` (R-WRT-11) runs the
same loop with `n` at that clamp (D-63). An entity-mode write (R-WRT-42) runs the same key select, then loads the
chunk's entities over `pk IN (…) AND <tree>` instead of writing with it; it is always chunked.

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

    // throw if this callback cannot serve emf; called before the flush (D-62)
    default void checkServes(EntityManagerFactory emf) {}
}
```

passing the executor's own `EntityManagerFactory`, so one callback serves every datasource. A chunk's key select and its
write both run on the callback's `EntityManager`. `commitEachChunk()` with no callback, or with one that cannot serve
that factory (`checkServes` throws), throws `MQ4004` before any statement runs, the flush included. The Spring starter provides one (`integration/50` R-SPR-11); a
plain-JPA caller writes their own, usually `begin`/`commit` on a resource-local `EntityManager` (INV-8, D-16). The
caller's `EntityManager` is flushed first when it is in a transaction (R-WRT-15); there, the row locks that flush takes
are held until that transaction ends, and a chunk writing one of those rows waits on them until the lock timeout.
`commitEachChunk()` is therefore meant to run outside a transaction, and the Javadoc says so.

**R-WRT-20** A per-chunk write that fails throws `ChunkedWriteException` (`MQ2502`) with the provider's exception as the
cause, even when the first chunk fails. It carries `committedRows()` and `lastCommittedKey()` (empty when nothing
committed), and `inDoubtKeys()`: when the commit of a chunk itself failed, that chunk's outcome is unknown, its rows are
not counted, and this lists its keys; otherwise it is empty. Committed chunks stay committed,
and the message says so, because a caller who assumed atomicity would otherwise misread the table's state (INV-5).
`lastCommittedKey()`, `inDoubtKeys()` and the `startAfter` key use the model key, the type `whereKey` takes
(D-63). `chunked(options, startAfter)`, offered after `where` or `all()`, resumes after `lastCommittedKey()` (D-68). For
a `whereKey` or `whereKeys` write, `lastCommittedKey()` is the last key of the last committed run in the order given,
after deduplication: every key up to and including it, in that order, was written or matched no row (D-73).
Re-running the whole write is safe only when it is
idempotent: `total * 1.1` would apply again to rows already committed. The same holds for resuming after an in-doubt
chunk, which may in fact have committed: a non-idempotent caller checks `inDoubtKeys()` against the table first, and
resumes after the last of them if the chunk did commit. The Javadoc says both.

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
| AC-WRT-06 | `whereKeys` with more keys than the vendor's limits, single and composite, updates every key once; `whereKeys` with no keys runs no SQL; duplicate keys are written once; a `@PrimaryKey` that is not the entity id throws `MQ1608` on first execution, before the flush (R-WRT-08). |
| AC-WRT-07 | For every fixture of the TCK Filters group, including `not` and `or` over a LEFT-joined column whose association is missing, the keys a write affects equal the keys the matching read returns: as one statement on H2 and PostgreSQL, key-first on MySQL, and chunked on all three (R-WRT-10, R-WRT-11, R-WRT-17). |
| AC-WRT-08 | A write with every filter skipped throws `MQ1601`; `all()` writes every row; `where(...).all()` does not compile (R-WRT-12). |
| AC-WRT-09 | Converters apply to assigned values, and a to-one set by id loads no row; `setExpression` on a converted column throws `MQ1609` (R-WRT-14). |
| AC-WRT-10 | Pending entity changes are flushed first inside a transaction, and a `commitEachChunk()` write outside one runs without flushing; the persistence context is cleared by default and kept with `KEEP`; the root's second-level cache entries are evicted (R-WRT-15). |
| AC-WRT-11 | Updates increment the version; `keepVersion` does not; an `expectVersion` mismatch throws `OptimisticLockException`; `expectVersion` on a root with no `@Version` throws `MQ1606` on first execution, before any statement; `expectVersion` without `whereKey` doesn't compile (R-WRT-16, D-60, D-61). |
| AC-WRT-12 | A chunked update that leaves rows matching terminates and touches each row once; a chunked delete crosses the vendor's IN limits, single and composite key (R-WRT-17). |
| AC-WRT-13 | A write with no transaction throws `MQ2501`; a delete blocked by a foreign key surfaces the provider's constraint exception (R-WRT-18). |
| AC-WRT-14 | `commitEachChunk()` with no `ChunkTransactions` throws `MQ4004` and runs no SQL; with a plain-JPA resource-local callback each chunk commits separately (R-WRT-19). |
| AC-WRT-15 | A per-chunk write whose third chunk fails throws `ChunkedWriteException` (`MQ2502`) reporting the first two chunks' rows and last key, and those rows stay written; a first-chunk failure also throws it, with zero rows; `startAfter` resumes after the last key; a failed chunk commit lists that chunk's keys in `inDoubtKeys()` (R-WRT-20). |
| AC-WRT-16 | With `@NotNull` and `@Size` on update-model fields: an unset field passes, a field set to NULL fails, a set field over the size fails; `@Valid @RequestBody` reports each as a field error in the Spring sample; a value containing `${…}` is not evaluated (R-WRT-21). |
| AC-WRT-17 | Without `jakarta.validation` on the classpath, generated change sets compile, carry no `@ValidChanges`, and nothing is validated (R-WRT-22). |
| AC-WRT-18 | On MySQL, a row that stops matching on a root column between the key select and the write is not written; with `lockKeys()` a concurrent change to a matched row waits for the write (R-WRT-11). |
| AC-WRT-19 | `setExpression` reading a column that a plain assignment writes gives the same result on every Tier-1 vendor (R-WRT-13). |
| AC-WRT-20 | On MySQL with `model-query-hibernate`, a write whose `exists(...)` sub-query reads a second entity that shares the root's table runs key-first and writes the rows the read returns; on H2 and PostgreSQL it runs as one statement (R-WRT-11, D-109). |
| AC-WRT-21 | On every Tier-1 vendor, an insert-select writes exactly the rows the equivalent `list` returns, with joined and to-many source columns; chunked over a non-overlapping target it writes each source row once; chunked over an overlapping one, or one whose `tablesOf` is empty, it throws `MQ1806` before the flush (R-WRT-27, R-WRT-28). |
| AC-WRT-22 | Insert-values with an assigned id, a pooled sequence, a converter, a to-one by id and a `set` constant round-trips; `IDENTITY` round-trips with `insert` and throws `MQ1807` with `insertReturningKeys` (R-WRT-26, R-WRT-29, R-WRT-30, R-WRT-33). |
| AC-WRT-23 | `insertReturningKeys` returns keys that read back each row by index; with `commitEachChunk()` it throws `MQ1801` before the flush (R-WRT-33). |
| AC-WRT-24 | Rows per statement never exceed the bind or `VALUES` row limit, the version seed counted (SQL snapshots per vendor); an empty list runs no SQL (R-WRT-29). |
| AC-WRT-25 | `doNothing` skips a conflicting row; `doUpdate` with `setFromRow` and `where` updates only the matching rows and increments `@Version`; MySQL without `anyUniqueKey()` throws `MQ1804`, and with it counts as R-WRT-35 states; `doNothing` where the provider does not render it throws `MQ1804` on first execution; a `where` reading two or more assigned columns, the version increment included unless `keepVersion()`, throws `MQ1804` on every vendor by default, and with `conflictUpdateWhereOnAssignedColumns(true)` updates as the stored row matches where `conflictWhereSeesEarlierAssignments()` is false and throws `MQ1804` where it is true, while one such column runs everywhere (R-WRT-34, R-WRT-35, R-WRT-36). |
| AC-WRT-26 | A `commitEachChunk` insert-values failure reports `committedRows()`, `nextRowIndex()` and `inDoubtRowCount()`; a failed insert-select reports source keys (R-WRT-32). |
| AC-WRT-27 | After any bulk insert the persistence context is cleared by default and kept with `KEEP`, and the root is evicted from the second-level cache (R-WRT-38). |
| AC-WRT-28 | `persist` with `IDENTITY` returns the key, runs `@PrePersist` and leaves the created entity detached, on Hibernate and on a second provider if the TCK has one; with a `CascadeType.ALL` to-one it detaches the caller's managed target, as documented; an unnamed nullable attribute with a database default is written as NULL (R-WRT-39, R-WRT-40). |
| AC-WRT-29 | Insert-select with a pooled sequence or a `JOINED` root throws `MQ1805`; insert-values or `persist` with a `K` that is not the id's type throws `MQ1807`; on first execution, before the flush, a model naming a generated id, lacking an assigned one or writing part of a composite one throws `MQ1802`, and an insert-select `map` between attributes of different types, or a `map` or `where` column off the source root, `MQ1801` (R-WRT-26, R-WRT-27, §10.1). |
| AC-WRT-30 | `ModelQueryRepository.insert`, `insertReturningKeys` and `persist` succeed without an ambient transaction (§10.1, `integration/50`). |
| AC-WRT-31 | A bulk insert on a provider with no insert support throws `MQ4009` and runs no flush; `persist` runs on it (R-WRT-39, `vendor/40`). |
| AC-WRT-32 | `build()` and `ModelPersist.of` check the definition before any statement: a `where` whose every filter is skipped throws `MQ1601`; a column unmapped, mapped twice or with another converter class, a `set` on a model column, a column set twice, a column not on the root, or `lockKeys()` on insert-values throws `MQ1801`; a `null` row throws `MQ1803`; a `null` assigned id `MQ1802`; two rows sharing a conflict-key tuple with no `null` in it `MQ1808`; a conflict column or assignment that R-WRT-34 refuses, or `exists` in the update's `where`, `MQ1804`. Changing a row or the list after `build()` leaves the definition unchanged (R-WRT-27, R-WRT-29, R-WRT-30, R-WRT-32, R-WRT-34, R-WRT-37, INV-9). |
| AC-WRT-33 | The insert stages compile in the documented orders and reject the rest: a source column of another model than the first `map`'s, a join as `insertFrom`'s source, `onConflict` on an insert-select or after `chunked`, keys from an insert-select or a conflict-clause insert, `keepVersion()` after `doNothing()`, a `doUpdate` assigning nothing, and `set` or chunking on `persist` do not compile (R-WRT-27, R-WRT-29, R-WRT-33, R-WRT-34, R-WRT-40, D-60). |
| AC-WRT-34 | An entity-mode update fires `@PreUpdate`, `@PostUpdate` and a registered Hibernate `PostUpdateEventListener` once per changed row, with the old state, and, for a root with no `UPDATE` assignment, none for an unchanged row; the count is the rows matched. The Hibernate listener test lives in `model-query-hibernate` or the integration tests, not in `model-query-jpa` (INV-7) (R-WRT-41, R-WRT-42, R-WRT-43, R-WRT-44). |
| AC-WRT-35 | An entity-mode delete fires `@PreRemove` and `PostDeleteEventListener` per row and cascades `REMOVE` and `orphanRemoval` (R-WRT-42, R-WRT-43). |
| AC-WRT-36 | An entity-mode write loads one select per chunk, holds at most one chunk of entities under `CLEAR`, and resumes from `ChunkedWriteException` after an `OptimisticLockException` (R-WRT-42, R-WRT-45, R-WRT-46, R-WRT-47). |
| AC-WRT-37 | `throughEntities()` with `setExpression`, `keepVersion()` or `expectVersion(v)` is `MQ1610` at `build()`, whatever the call order; a delete accepts `throughEntities()` with any option (R-WRT-41, R-WRT-46). |
| AC-WRT-38 | `persist(persist, returning)` returns the generated id, `@PrePersist` values and constructor defaults with one insert and no select; a `returning` query with a `where` (or another R-WRT-48 clause) or an unfillable column is `MQ1809` before any statement (R-WRT-48). |
| AC-WRT-39 | A write assignment is applied by each write in the R-WRT-49 table, skipped where the definition sets the attribute, called once per execution (again on resume), and overridden by an entity callback under `persist`; an empty update stays a no-op; an assignment on a superclass applies to a subclass root; `MQ1611` (including two overlapping kinds for one path), `MQ1612` (including a supplier returning `null`); `WriteAssignment.of` rejects a `null` argument, a primitive or array entity class and a blank or malformed path (R-WRT-49). |

## 10. Inserts

Added by RFC 0004 (D-116); the API shape is D-117. Three ways to write new rows, all from models, so the caller never
handles the entity: insert-select (§10.3) and insert-values (§10.4), which are bulk writes, and `persist` (§10.6),
which is an entity write. Every type and method here is `@Incubating` (D-85).

### 10.1 Scope and types

**R-WRT-24** A bulk insert writes either the rows a filter over a source model matches (insert-select) or rows given as
insert-model instances (insert-values), as one provider insert statement or a chunked series of them. It loads no
entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit (R-WRT-01). Every bulk `insert`
Javadoc says so, and says that `persist` (§10.6) is the method that runs them.

The definitions are immutable (INV-9). `ModelInsert<E, M>` is a sealed class permitting only `ValuesInsert`; an
insert-select, and an insert-values with a conflict clause, build to it. An insert-values without a conflict clause
builds to the final `ValuesInsert<E, K, M>`, and `persist` takes the final `ModelPersist<E, K, M>` (type parameters
in `ModelQuery`'s order). `E` is the written root, `K` its id type, `M` the insert model; an insert-select's source is
unconstrained. The executor offers, beside `update` and `delete`:

```java
long insert(ModelInsert<E, ?> i);                              // rows affected (R-WRT-35)
<K> List<K> insertReturningKeys(ValuesInsert<E, K, ?> i);      // generated keys, in row order (R-WRT-33)
<K> K persist(ModelPersist<E, K, ?> p);                        // the created row's key (R-WRT-39)
```

so keys from an insert-select or a conflict-clause insert, and `set`, a conflict clause or chunking on `persist`, do
not compile (P-2). `ModelQueryRepository` gains the same three (`integration/50`).

`K` is fixed by the generated `insert(rows)` and `persist(row)` from the root's id as the processor sees it (the boxed
`@Id`, the `@EmbeddedId`, the `@IdClass`, or a `@MappedSuperclass` type variable resolved on the root), which passes
`Class<K>` into the definition. A composite `K` is the `@IdClass` or the embeddable, what
`PersistenceUnitUtil#getIdentifier` returns. On the definition's first execution (D-61) `K` must equal the boxed Java
type of `IdentifiableType#getIdType()`, else `MQ1807` naming `orm.xml`, which the processor cannot see; with no id
visible to the processor `K` is `Object`, which passes, and it warns `MQ3504` (`processor/32`). Where the metamodel
reports no id type, as Hibernate 6 does for an `@IdClass`, nothing is compared. A per-call `Class<K>` is not offered:
a wrong class would compile (D-117).

### 10.2 Insert models and `InsertColumns`

**R-WRT-25** Every component of an insert model (`@InsertModel`, `processor/30` R-PROC-23) is a column of every row it
writes, and `null` writes NULL. A column the database or the server fills (a default, an audit timestamp) stays out of
the model; a server value goes in with `set(column, value)`, one bind per row. One column list per definition is what
lets rows share a statement (R-WRT-29). An attribute the model does not name takes the database default under
`insert`, but under `persist` it is written as the no-arg constructor leaves it (R-WRT-39): the same model can write
different rows through the two.

`InsertColumns<M, E>` is the column list, the type of the generated `INSERT_COLUMNS` and the processor-free entry point
(INV-8): `InsertColumns.of(root)` takes the root `TableField<E, E>`, and each `<C> add(ColumnField<M, E, C>,
Function<? super M, ? extends C>)` (or `addKey`, for a column of the model's `@PrimaryKey`) appends one column, in
declaration order, with the function that reads its value from a row. Each call returns a new immutable list. A column
added twice, or not on the root (a self-referencing join), throws `MQ1801`. The definitions start from it:
`ModelInsert.select(columns, sourceRoot)`, `ValuesInsert.builder(columns, keyType, rows)` and
`ModelPersist.of(columns, keyType, row)`; the generated `insertFrom`, `insert` and `persist` call them.

**R-WRT-26** The id. When the root's `@Id` has a generator, the model leaves it out; when it has none, the model names
it with `@PrimaryKey`, and a row with a `null` id throws `MQ1802` at `build()`. Naming a generated id is `MQ1802` too:
H2, PostgreSQL and Oracle accept an explicit `IDENTITY` value without advancing the identity, so a later generated key
collides. The processor checks what the annotations show (`MQ3501`); the generator itself is reported by the provider
(`vendor/40` R-VND-14) on the definition's first execution (D-61), since the JPA metamodel does not expose it. A root
`@Version` is never in the model; the provider writes its seed value, one bind per row.

Supported generators are an allowlist, checked on first execution against the generator the provider reports; any
other generator, or an unsupported root, throws `MQ1805` naming the generator class or the mapping:

- **Insert-values:** assigned, `IDENTITY`, a sequence (`SequenceStyleGenerator` with any optimizer, MySQL's
  table-backed sequence included), a table generator and UUID. For all but `IDENTITY` and assigned the engine draws the
  keys from the provider's generator before the statement and writes them as values (Hibernate accepts an explicit id
  and then skips its own generator), so the keys are known (R-WRT-33).
- **Insert-select:** assigned (mapped from the source), `IDENTITY`, and a sequence over a physical sequence with
  increment 1. Rows of a select cannot be pre-generated: a pooled sequence runs a statement per row (except one CTE on
  PostgreSQL), Hibernate rejects a table or UUID generator, and any sequence on MySQL writes wrong keys on 6.6.
- **Either:** a `JOINED` root, a `@SecondaryTable`, a composite id with generated parts, and `@MapsId` in either form
  (D-116) are `MQ1805`, as is a subclass of `SequenceStyleGenerator`: an insert-select reads the sequence inside the
  statement, so the subclass's own key code would never run (D-117).

### 10.3 Insert-select

```java
long archived = executor.insert(QOrderArchiveRow.insertFrom(QOrderView.ROOT)
        .map(QOrderArchiveRow.ORDER_ID, QOrderView.ID)              // the first map fixes the source model
        .map(QOrderArchiveRow.STATUS, QOrderView.STATUS)
        .map(QOrderArchiveRow.TOTAL, QOrderView.TOTAL)
        .map(QOrderArchiveRow.CUSTOMER_ID, QOrderView.CUSTOMER_ID)
        .set(ARCHIVED_AT, now)
        .where(f -> f.eq(QOrderView.STATUS, OrderStatus.CLOSED).lt(QOrderView.CLOSED_AT, cutoff))
        .chunked(ChunkOptions.size(5_000))
        .build());
```

**R-WRT-27** `<S> insertFrom(TableField<S, S> sourceRoot)` takes the source query model's root, so a join `TableField`
does not compile (a self-referencing join of the root's type throws `MQ1203`). Stages follow D-60: `<Q, C> map(
ColumnField<M, E, C> target, ColumnField<Q, ?, C> source)`, whose first call fixes the source model `Q`, so a source
column of another model, an aggregate or a grouped source does not compile (P-2); then more `map`s and
`set(ColumnField<?, E, C>, C)`; then `where(UnaryOperator<Filters<Q>>)` or `all()` (R-WRT-12, `MQ1601`); then the
options `chunked(...)` and `persistenceContext(...)`, and `build()`. There is no conflict clause (D-116), no
`keepVersion` and no `startAfter`: a resumed copy narrows the `where` on the source key. No converter runs inside the
statement (the database copies attribute values), so `build()` requires both columns of a `map` to have the same
converter class or none, and the first execution (D-61) requires equal entity attribute types. Either failure is
`MQ1801`, as is, at `build()`, a column of `columns` not mapped, a column mapped twice or not in `columns`, a `set` on a
column the model has, a column set twice, or a `set` column not on the root. A source column may be joined: the select
is built with the same `JoinContext` as the read path.

**R-WRT-28** **An insert-select writes exactly the rows the equivalent read returns** (D-17), duplicates from a to-many
join included: seeding child rows from a parent's matches is that multiplication. `chunked(...)` pages key-first over
the distinct source-root ids with R-WRT-17's guarantees, so each source row is read once. When the target overlaps
the source root or an entity the select joins (the same entity, the same hierarchy, or intersecting `tablesOf`,
R-VND-13), `chunked` throws `MQ1806` on first execution, before the flush: rows a chunk writes could match the next
chunk's key select or change what its joins return, and the engine only gets a count back. A source or target whose
`tablesOf` is empty fails closed with `MQ1806` as well, since rows written with generated ids above the cursor would
otherwise be re-read silently (INV-5). A join through a link or collection table (`@ManyToMany`, `@ElementCollection`, or an `@OneToMany` not shown by
`mappedBy` or a join column to be a foreign key on its target) is not checked for overlap, since `tablesOf` names
entity tables only; `chunked` fails closed with `MQ1806` on it (M10 gate). A foreign-key `@OneToMany` and a to-one join
are named by their entity's table, so they stay allowed. The unchunked statement
stays allowed, since the database reads the whole select before it inserts. A guard against rows already in the target is a correlated `notExists` filter over the
target (R-FLT-17): portable, not atomic. A sub-query is not part of the select's FROM, so such a guard does not make
`chunked` throw `MQ1806`. Chunked, it gives the same rows as the unchunked statement only when it is correlated on a
value unique among the source rows, such as the source id, whose rows a chunk takes together; correlated on a value
several source rows share, a later chunk skips rows an earlier chunk inserted, which a single statement would not.

### 10.4 Insert-values

```java
long n = executor.insert(QNewOrder.insert(rows)          // List<NewOrder>, read once at build() (INV-9)
        .set(CREATED_AT, now)
        .chunked(ChunkOptions.size(500))
        .build());

List<Long> ids = executor.insertReturningKeys(QNewOrder.insert(rows).build());   // ids.get(i) is rows.get(i)'s
```

**R-WRT-29** `insert(rows)` writes every row in list order, as multi-row `VALUES` statements. Every row of a chunk goes
in one statement on every built-in vendor (D-116). Rows per statement are the largest count whose statement stays
within the vendor's bind-parameter limit, counted from the parameters one row takes (columns, `set` constants, the
binds the provider adds per row such as the version seed, and a conflict clause's binds as an upper bound, as D-80
counts), and within `VendorProfile.maxValuesRows()`, capped by the `chunked` size when given. `maxValuesRows()`
defaults to 1,000 and the built-in profiles raise it (`vendor/40`). An empty list runs no SQL and returns 0 (or an
empty list). Stages follow D-60: `insert(rows)` returns `ValuesInsert.Rows`, which offers `set` (a `null` value is
`MQ1603`; there is no `setNull`, since a model column writes NULL), `onConflict` (§10.5), `chunked`,
`persistenceContext` and `build()`; after `chunked` or `persistenceContext` the stage is `ValuesInsert.Options`, where
`onConflict` is gone. A `set` on a column the model has, a column set twice, or a `set` column not on the root throws
`MQ1801` at `build()`.

**R-WRT-30** Values pass through the column's converter and are always bind parameters (R-WRT-14). A to-one column
binds its target's id to the target's id attribute (`customer.id`), as an insert-select copies it (R-WRT-27), so no
reference is loaded or created. `build()` reads each row once, through `InsertColumns`, into an
immutable array of its column values, and never reads the model instance again; the copy is shallow, so a mutable
value inside a row is the caller's (INV-9). A `null` row therefore throws `MQ1803` at `build()`, as do `MQ1802`
(R-WRT-26) and `MQ1808` (R-WRT-37).

**R-WRT-31** The library does not validate rows (R-WRT-24). Because every field of an insert-model row is present, a
plain `@Valid List<NewOrder>` on the caller's method checks them with the model's own constraints, `@NotNull`
included. D-15's field-by-field rule is for change sets and does not apply.

**R-WRT-32** By default the statements run in the caller's transaction (R-WRT-18). `ChunkOptions.commitEachChunk()`
commits each statement (R-WRT-19); `lockKeys()` on insert-values throws `MQ1801` at `build()`, since no key is
selected. A failed chunk throws `ChunkedWriteException` (`MQ2502`, R-WRT-20). For insert-select it carries source keys:
`lastCommittedKey()` and `inDoubtKeys()` hold source-root ids, a composite id as the list of its component values
ordered by attribute name. For insert-values it carries `OptionalInt nextRowIndex()`, the first row not committed, and
`int inDoubtRowCount()`, the rows of the chunk whose commit failed, contiguous from `nextRowIndex` (0 when the chunk
rolled back); `nextRowIndex()` is empty and `inDoubtRowCount()` 0 for every other write. `committedRows()` stays the
rows affected (R-WRT-35), which with a conflict clause is not the rows processed. Resume with
`rows.subList(nextRowIndex, size)`, after checking the in-doubt rows against the table.

**R-WRT-33** `insertReturningKeys(insert)` returns the generated keys in row order. It takes a `ValuesInsert`, so a
definition with a conflict clause or an insert-select does not compile: a skipped row would leave a key with no row.
The keys come from R-WRT-26's pre-generation: `IDENTITY` and an assigned id throw `MQ1807` on first execution, the
message pointing to `persist` (§10.6) and to the rows themselves respectively. With `commitEachChunk()` it throws
`MQ1801` at that call, before the flush, since a failure would lose the committed rows' keys; `build()` cannot know
which method the definition is given to. A `K` that is not the id's type is `MQ1807` (§10.1).

### 10.5 Conflict clauses: the conditional insert

```java
executor.insert(QNewOrder.insert(rows).onConflict(QNewOrder.EXTERNAL_REF).doNothing().build());

executor.insert(QNewOrder.insert(rows)
        .onConflict(QNewOrder.EXTERNAL_REF)
        .doUpdate(u -> u.setFromRow(QNewOrder.STATUS, QNewOrder.TOTAL)   // = the incoming row's values
                        .set(UPDATED_AT, now)
                        .where(f -> f.ne(QNewOrder.STATUS, OrderStatus.CLOSED)))
        .build());
```

**R-WRT-34** A conflict clause is offered on insert-values only (D-116: Hibernate cannot render one on an insert-select,
which guards with `notExists`, R-WRT-28). `onConflict(ColumnField<M, E, ?> first, ColumnField<M, E, ?>... rest)` names
the unique key the conflict is detected on; zero columns does not compile, and a column named twice or not in the
model's `InsertColumns` throws `MQ1804` at `build()`. The columns must be exactly the root's id, its natural id, or a
declared unique constraint, checked on first execution (`MQ1804`) from the mapping: `@Id`, `@NaturalId`, `@Column(unique
= true)`, `@JoinColumn(unique = true)` and `@Table(uniqueConstraints)` on the entity or a superclass entity, the
constraint's columns matched to the attributes' columns. A unique index that exists only in a migration, or only in
`orm.xml`, is not seen and is rejected; the Javadoc says to declare it in the mapping. The check stays because on a
`MERGE` vendor a non-unique match updates several rows. The key is trusted from the mapping: the database must enforce
it, since `MERGE` vendors (H2, Oracle, SQL Server) and MySQL with `anyUniqueKey()` do not detect a key the schema
does not enforce, and would update every matching row or insert duplicates.

`onConflict` returns `Conflict`, offering only `doNothing()`, which returns `ConflictOptions`, and
`doUpdate(Function<ConflictUpdate<E, M>, ConflictUpdate.Action<E, M>>)`, which returns `Upserting`; so a clause without
an action does not compile. `ConflictUpdate` offers `setFromRow(first, rest...)` (the incoming row's value), `set`
(`null` is `MQ1603`) and `setNull`, each returning `Assigned`, an `Action` offering more assignments and one
`where(UnaryOperator<Filters<M>>)` that returns a bare `Action`; so an update assigning nothing does not compile.
`ConflictOptions` offers `anyUniqueKey()` (R-WRT-36), `chunked`, `persistenceContext` and `build()`, which returns a
`ModelInsert`; `Upserting` adds `keepVersion()`. The assignments follow R-WRT-13: a key column (the conflict columns or
the model's `@PrimaryKey`), a column assigned twice or a column not on the root is `MQ1804` at `build()`, as is a
`setFromRow` column not in the model's `InsertColumns`; an id or `@Version` attribute is `MQ1804` on first execution.
The `where` filters the stored row on root columns only: the conflict action has no join context, so a joined column,
`exists` or a sub-select is `MQ1804` at `build()`. If every filter in it is skipped, the update applies to every
conflicting row (not `MQ1601`). A `where` that reads two or more of the columns the update assigns, the version
increment counted unless `keepVersion()`, is `MQ1804` on execution, before any statement, on every vendor, unless the
executor is configured with `ModelQueryConfig.conflictUpdateWhereOnAssignedColumns(true)`
(`modelquery.bulk-write.conflict-update-where-on-assigned-columns`, default false); with it, it is still `MQ1804`
where the profile's `conflictWhereSeesEarlierAssignments()` is true (`vendor/40` R-VND-14), since there the `where`
would filter on the values the earlier assignments wrote (R-WRT-35). The check runs on each execution, after the
first-execution checks, because the option is the executor's; so does R-WRT-36's, because the profile is. A
`where` reading at most one assigned column runs on every vendor. A root `@Version` is incremented unless
`keepVersion()`; `MQ1606` applies to a version type it cannot increment. Constraint names are not offered: Hibernate rejects them on every `MERGE` vendor and
for `DO UPDATE` on MySQL. `doNothing()` throws `MQ1804` on first execution where the provider reports that it does not
render it for the dialect (Hibernate 6 on a `MERGE` vendor writes a plain insert), rather than failing per row (D-116).

**R-WRT-35** Rendering is the provider's: `ON CONFLICT` on PostgreSQL; `ON DUPLICATE KEY UPDATE` with a row alias on
MySQL and MariaDB, `doNothing` rendered as a self-assignment and a `doUpdate`'s `where` as a `CASE` per assignment;
`MERGE` on H2, Oracle and SQL Server, with the `where` on `WHEN MATCHED`. MySQL assigns in order and each `CASE`
reads the columns earlier assignments wrote, so an assignment to a column the `where` reads renders after the others,
the version increment included; two or more such columns cannot all come last, which R-WRT-34 refuses there.
`insert` returns the provider's count, rows inserted plus rows updated, where `conflictTargetHonoured()`. Elsewhere a
conflict clause already needs
`anyUniqueKey()`, whose Javadoc states that it accepts the vendor's count: on MySQL every conflicting row counts 1 when
skipped, filtered out or left unchanged and 2 when changed (Connector/J's default found rows), so `doNothing` counts
the rows it skipped. `ChunkedWriteException.committedRows()` follows the same count. The Javadoc says so and the TCK
pins it per vendor. Under concurrency a `MERGE` vendor raises a unique violation for a key another transaction inserts
at the same time, rather than skipping it; the Javadoc says so.

**R-WRT-36** **The named key is honoured or the call fails.** MySQL and MariaDB detect a conflict on any unique key, so
`doUpdate` could update, and `doNothing` skip, a row that matched a different key. `VendorProfile` gains
`conflictTargetHonoured()`, false by default so a third-party profile fails safe, true in the built-in `H2` and
`POSTGRESQL` profiles and false in `MYSQL` and `MYSQL_CURSOR_FETCH` (`vendor/41`). Where it is false, a conflict clause
throws `MQ1804` on first execution unless the builder says `anyUniqueKey()`, whose Javadoc states that it accepts
any-unique-key detection, the vendor's count (R-WRT-35) and the vendor's key collation.

**R-WRT-37** **Duplicate conflict keys within one call.** Vendors disagree (PostgreSQL skips them for `doNothing` and
fails for `doUpdate`; MySQL writes the first or the last; `MERGE` vendors fail), and the outcome would depend on the
chunk size. An insert-values with a conflict clause throws `MQ1808` at `build()`, before any statement, when two rows
share a conflict-key tuple, compared by `equals` on the model values. A tuple holding a `null` is left out of the
check: SQL `NULL`s never conflict, and a unique index that treats them as equal (PostgreSQL's `NULLS NOT DISTINCT`)
reports its own constraint error.

**R-WRT-38** Persistence context: every bulk insert applies R-WRT-15 unchanged (flush before; `CLEAR` by default,
`KEEP` available; the root evicted from the second-level cache). An insert without `doUpdate` still leaves managed
state stale, for example an initialized `Order.lines` of a managed parent, or a `@Formula`.

### 10.6 `persist`: one row, any generator

```java
Long id = executor.persist(QNewOrder.persist(newOrder));
```

**R-WRT-39** `persist` writes one insert-model row through JPA. `ModelPersist.of` reads the row once, as `build()`
does (R-WRT-30: `MQ1803`, `MQ1802`). The engine instantiates the root entity with its no-arg constructor and sets each
model column through the attribute's metamodel member: the field, or for property access the setter paired with the
getter `Attribute#getJavaMember` returns, after the column's converter. An embeddable path instantiates the embeddable
with its no-arg constructor where the root's constructor left it `null`; a record or constructor-only embeddable is
`MQ1805`, on first execution (D-117). A to-one column binds `EntityManager#getReference`. For a `@MapsId` id the model names the to-one association (and
`@PrimaryKey` the id) for `persist` to work. It then calls `persist`
and `flush`, reads `PersistenceUnitUtil#getIdentifier`, and calls `detach` on the entity, in that order.
Constructors and fields are reached with `setAccessible`, so a modular application `opens` its entity package to the
library; the Javadoc says so. `persist` needs no provider SPI and works with every generator, `IDENTITY` included,
`@MapsId` included, and every provider. It needs an active transaction (`MQ2501`). A model that writes part of a
composite id is `MQ1802`, and, where the provider reports the root's generator (`InsertSupport`), so is one that names
an id the generator generates, which `persist` would otherwise refuse as a detached entity; an assigned id the model
does not name is not checked, since a constructor or `@PrePersist` may set it. A `null` set on a primitive attribute is
`MQ1308`, before the statement.

It is an entity write: `@PrePersist`/`@PostPersist`, Bean Validation, Envers and the provider's insert run, and the
provider maintains the second-level cache, so R-WRT-15's eviction does not apply. The first paragraph of its Javadoc
says that entity attributes the model does not name are written as the no-arg constructor leaves them, often NULL, not
as the database default (unless the mapping is `insertable = false`, generated, or the provider's dynamic insert), and
that a domain constructor's invariants do not run. The `flush` writes the caller's pending changes too, as R-WRT-15's
flush does. `detach` cascades as the mapping says: a to-one with `CascadeType.ALL` or `DETACH` detaches the instance
`getReference` returned, which is the caller's own managed entity when there is one, and an entity persisted by a
callback stays managed; the Javadoc documents this as R-WRT-15 documents `CLEAR`. A `K` that is not the id's type is
`MQ1807` (§10.1). `persist(persist, returning)` returns a query model built from the flushed entity instead of the key
(R-WRT-48); write assignments with an `INSERT` kind are set on the entity before the flush (R-WRT-49).

**R-WRT-40** `persist` takes no `set`, conflict clause or chunking: server values belong in the entity's callbacks or
in the model. Many rows with `IDENTITY` keys are a loop over `persist`, one statement each, which is what the provider
does for `IDENTITY` anyway; the Javadoc points to `insert`/`insertReturningKeys` for anything else.

## 11. Entity writes

Added by RFC 0005 (D-118). Entity mode lets an update or delete reach the entity listeners, callbacks and audits that
a bulk statement skips, `persist` can return a query model, and write assignments fill server-set columns on every
write. Every type and method here is `@Incubating` (D-85, D-118). The acceptance criteria are in §9 (AC-WRT-34 to
AC-WRT-39).

### 11.1 Entity mode for update and delete

```java
repository.update(QPaymentReportServiceDelete.update(changes)
        .where(f -> f.eq(REPORT_TYPE_ID, reportTypeId).in(SERVICE_ID, serviceIds).eq(IS_DELETED, false))
        .throughEntities()
        .chunked(ChunkOptions.size(500))
        .build());

repository.delete(QExportRoleConfiguration.delete().whereKey(id).throughEntities().build());
```

**R-WRT-41** `throughEntities()` is offered on the options stage of `ModelUpdate` and `ModelDelete`, beside `chunked`
and `persistenceContext`, and on each `Resumable` stage; `entityMode()` is `@EngineFacing`. `build()` throws `MQ1610`
for an update that combines `throughEntities()` with `keepVersion()`, `expectVersion(v)` or any `setExpression`,
whatever the call order; a delete has no incompatible option. The Javadoc of `throughEntities()` says the error comes
when the definition is built (for a `static final` constant, at class initialization), not at compile time. An
entity-mode write is always chunked: without `chunked(...)` it uses `ModelQueryConfig.bulkWriteChunkSize()`, so memory
is bounded by one chunk of entities.

**R-WRT-42** Each chunk selects its keys exactly as R-WRT-17 does (`WHERE <tree> AND pk > :last ORDER BY pk`), then
loads the chunk's entities with one `CriteriaQuery<E>` over the R-WRT-17 predicate (`pk IN (…) AND <tree>`), never one
`find` per key; a composite id uses the `vendor/41` §5 expansion, and for `whereKey(s)` the chunk is the given keys. A
row that stops matching the filter between the key select and the load is not written. An update applies each
assignment to the managed entity through the same metamodel member R-WRT-39 uses (field, or the setter paired with the
getter), after the column's converter; a to-one by id binds `EntityManager#getReference`, so its target is not loaded.
A delete calls `EntityManager#remove`. The chunk is then flushed. The provider writes only the entities that changed,
so an assignment that leaves a row as it was writes nothing and fires no update listener.

**R-WRT-43** It is an entity write: `@PreUpdate`/`@PostUpdate`, `@PreRemove`/`@PostRemove`, the provider's event
listeners, Envers, Bean Validation, cascades (`REMOVE`, `orphanRemoval`) and the mapping's `@SQLDelete` run, and the
provider maintains the second-level cache, so R-WRT-15's eviction does not apply. A delete may therefore remove more
rows than it counts, and the Javadoc says so.

**R-WRT-44** The return value is the number of distinct entities loaded, which is the number of rows matched, not the
number the provider wrote.

**R-WRT-45** The persistence context follows `PersistenceContextMode`. R-WRT-15's flush before the first chunk stays.
With `CLEAR` (the default) the engine clears after each chunk's flush, which is what keeps memory to one chunk; with
`commitEachChunk()` each chunk clears its own `EntityManager`, and the caller's is also cleared once after the last
chunk, whether or not it failed, as a bulk write clears it after its last statement, since its managed copies of the
written rows are stale. With `KEEP` nothing is detached: the loaded entities stay managed and current, since they were
written through the context, and the Javadoc of `KEEP` says the context then grows with every matched row. The engine
never detaches only "what it loaded": a row the caller already had managed is the same instance, and detaching it
would surprise the caller. The configured query timeout (R-EXE-11) applies to the key select and the load; the
flush's statements get none, since JPA has no portable per-statement hint for them.

**R-WRT-46** Optimistic locking is the provider's: a `@Version` attribute is checked and incremented by the flush.
`keepVersion()` and `expectVersion(v)` cannot be honoured, and `setExpression` cannot be computed in Java, so `build()`
throws `MQ1610` for a definition that combines any of them with `throughEntities()`. An `OptimisticLockException` from
a chunk's flush is a failed chunk like any other. In the caller's transaction (no `commitEachChunk()`) it reaches the
caller unwrapped, as any failure of a write that does not commit per chunk does, and the provider marks the caller's
transaction for rollback; only a `commitEachChunk()` write wraps it, as R-WRT-20 says, in `ChunkedWriteException` with
the key to resume after.

**R-WRT-47** An entity-mode write needs an active transaction (`MQ2501`); with `commitEachChunk()` each chunk loads,
writes and clears inside its own transaction (R-WRT-19). Each resume after a `ChunkedWriteException` is a new
execution, so it calls every write assignment's supplier again (R-WRT-49). It needs no provider SPI and runs on every
provider. Inserts have no entity mode: an insert that must fire listeners is `persist`, in a loop for many rows, which
costs the same as `saveAll` with an `IDENTITY` id.

### 11.2 `persist` returning a model

```java
static final ModelQuery<ExportJobEntity, Long, ExportJob> JOB = QExportJob.query().select(QExportJob.ALL).build();

ExportJob job = repository.persist(QNewExportJob.persist(row), JOB);
```

**R-WRT-48** `@Incubating <R> R persist(ModelPersist<E, ?, ?> persist, ModelQuery<E, ?, R> returning)` on
`ModelQueryExecutor<E>` and `ModelQueryRepository<E>` runs R-WRT-39 up to the flush, then builds the model from the
managed entity before it is detached, with no further statement. The query supplies only the root, the selection, the
mapper, `afterMap` and the finisher; a `SelectSet` alone carries neither the root nor the mapper. Each selected column
is read through its attribute's metamodel member and the column's converter, and the model is instantiated as the read
path does. It can fill root attributes, embeddable paths and the id of a to-one association (read without initializing
the target; through an `INNER` join field whose foreign key is null it fills `null`, and the Javadoc says so). The root
is fixed by `E`, so a query on another entity does not compile. A query with `where`, `having`, `groupBy`, a fetch plan,
`customize`, `orderBy`, `keyset` or `primaryKeyFirst`, or a selected column it cannot fill from the entity alone (a join
beyond a to-one id, a join with `on(...)`, an expression, an aggregate, a collection attribute, or an association
attribute itself, whose message says to select the target's id), is `MQ1809` on its first execution per factory, before
any statement; there is no silent fallback to a select. A `where` or `having` counts only when it recorded a filter, so
one whose filters were all skipped is accepted (D-118). The Javadoc says the model holds what JPA knows after the flush:
a value the database fills (a column default, a trigger) is present only where the mapping has the provider read it back
(`@Generated`). The overload is separate rather than a `returning(...)` stage, which would change the builder's key type
`K` into `M`.

### 11.3 Write assignments

```java
ModelQueryConfig.defaults()
        .writeAssignments(List.of(
                WriteAssignment.of(AuditedEntity.class, "lastUpdatedTimestamp", Timestamp.class,
                        WriteKind.INSERT_AND_UPDATE, () -> Timestamp.from(clock.instant())),
                WriteAssignment.of(AuditedEntity.class, "createdTimestamp", Timestamp.class,
                        WriteKind.INSERT, () -> Timestamp.from(clock.instant()))));
```

**R-WRT-49** `WriteAssignment` (`@Incubating` final class) and `WriteKind` (`@Incubating enum { INSERT, UPDATE,
INSERT_AND_UPDATE }`) live in `com.rey.modelquery.jpa`. `static <A> WriteAssignment of(Class<?> entity, String
attribute, Class<A> type, WriteKind kind, Supplier<? extends A> value)` names an entity class, an attribute path of it,
the attribute's type and a supplier of its value; `entity()`, `attribute()`, `type()` and `kind()` read them back. `of`
throws `NullPointerException` for a `null` argument and `IllegalArgumentException` for a primitive or array entity class
or an attribute that is not dot-separated Java identifiers. An assignment applies to the named class and its subclasses
(`isAssignableFrom`), so one on a `@MappedSuperclass` covers every entity extending it.
`ModelQueryConfig.writeAssignments(Collection<? extends WriteAssignment>)` holds them and `writeAssignments()` returns
them, empty by default; under Spring Boot the starter hands every `WriteAssignment` bean to the config, as it does
`VendorProfile` beans. The path names a basic singular attribute of the root, possibly through embeddables. It is
checked on the first write per root per `EntityManagerFactory` (D-61's `checkWriteOnce`), before any statement, not when
the config is built, since one config can serve several factories: an unknown path, an id, a `@Version`, a collection, a
to-one, a whole embeddable, or two assignments of overlapping kinds for one root and path, is `MQ1611`; a type the
attribute cannot take (after boxing) is `MQ1612`. A supplier that returns `null` or a value of the wrong type is
`MQ1612` at execution, before any statement. The supplier is called once per write execution, so every chunk and every
row of one write gets the same value; it may be called from several threads at once and must be thread-safe, and a
`Clock` keeps tests deterministic.

They apply to:

| Write | Kind applied |
|---|---|
| bulk update, chunked or not, and entity mode | `UPDATE` |
| insert-values and insert-select | `INSERT` |
| the `doUpdate` branch of a conflict clause | `UPDATE` |
| `persist` | `INSERT` |
| delete, `doNothing` | none |

In a bulk update an assignment is rendered as a plain `set` of a bound value, after the definition's own assignments;
insert-values counts its extra bind per row in `rowsPerStatement` (R-WRT-29). An explicit `set`, `setNull` or
change-set field for the same attribute wins and the assignment is skipped, which is not `MQ1602`, so a backfill can
still set the column on purpose; keeping the column out of a change set bound from a request stays the model's job
(R-WRT-13). An update with nothing to write stays a no-op: assignments never turn an empty update into a write. Under
`persist` and entity mode the value is set on the entity through the metamodel member before the flush, so an entity
callback that sets the same attribute runs after it and wins. In entity mode an `UPDATE` assignment dirties a matched
row only where its value differs from the loaded one: the provider compares by value, so a row already holding the
supplied value is not written and fires no update callback, and the count stays the distinct entities loaded.
