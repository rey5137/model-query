# 14 — Bulk Updates and Deletes

**Covers:** `ModelUpdate`, `ModelDelete`, change sets (`Changes<M>`), how a bulk write renders and runs, and the
executor's `update`/`delete`.
**Read when:** working on M8, or deciding whether a write belongs in this library at all.
**Owns:** `R-WRT-*`, `AC-WRT-*`. **Status: `Future` (M8, after 0.1.0).** `@Incubating` until the M7 API review. Update
models are generated as in `processor/31` §6; the reasoning is D-14.

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
        @PrimaryKey Long id,                    // never written; used by whereKey(...)
        OrderStatus status,                     // converter from @Column or the entity mapping, as for queries
        BigDecimal total,
        Instant deliveredAt,
        Instant updatedAt,
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
ModelUpdate<OrderEntity, OrderPatch> u = QOrderPatch.update(changes)   // or ModelUpdate.builder(QOrderPatch.ROOT)
        .set(QOrderPatch.UPDATED_AT, now)                  // extra assignment
        .setNull(QOrderPatch.NOTE)                         // NULL must be explicit
        .setExpression(QOrderPatch.TOTAL, (path, cb) -> cb.prod(path, rate))   // escape hatch
        .where(f -> f.lt(QOrderPatch.CREATED_AT, cutoff)
                     .eq(QOrderPatch.CUSTOMER_COUNTRY, country))   // joined: rendered as EXISTS (R-WRT-10)
        .all()                                             // required only when no filter is left (R-WRT-12)
        .expectVersion(version)                            // AND version = ?; needs whereKey
        .keepVersion()                                     // opt out of the version increment (R-WRT-16)
        .chunked(ChunkOptions.size(1_000))                 // optional: key-first chunks (R-WRT-17)
        .build();
```

**R-WRT-05** `build()` returns an immutable `ModelUpdate` (INV-9), as `ModelQuery` does.

**R-WRT-06** `set` accepts only `ColumnField<M, E, C>` whose table is the root entity type `E`, so most joined columns
do not compile (P-2). A join back to the same entity type (a self-reference) passes the type check and throws `MQ1604`
at `build()`. `set(col, null)` throws `MQ1603`: NULL is written only through `setNull` or a change set.

**R-WRT-07** `set(Changes<M>)` adds every column the change set marked. An update whose assignment list is empty —
typically an empty change set — is a no-op: `update` returns 0 without running SQL.

**R-WRT-08** `whereKey(key)` and `whereKeys(keys)` filter by the definition's `@PrimaryKey`, single or composite.
`whereKeys` splits a long list across statements to the vendor's limits (`engine/21` R-PAG-07) and returns the summed
count.

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

**R-WRT-10** **Bulk writes never join.** `CriteriaUpdate` and `CriteriaDelete` have a root and no joins. A filter on a
root column renders directly. A filter that needs a join is built as a correlated sub-query over the root with a normal
`JoinContext`, rendered as `EXISTS (SELECT 1 FROM orders o2 JOIN … WHERE o2.pk = o.pk AND …)`, which works for
composite keys too. `exists(...)` filters stay correlated sub-queries.

**R-WRT-11** **Bulk writes work where the database cannot read the target table in a sub-query.** When
`VendorProfile.targetTableInSubquery()` is false (MySQL, error 1093; `vendor/40` R-VND-11) and R-WRT-10 needs a
sub-query, the engine runs key-first: select the matching keys with the query engine, then write `WHERE pk IN (…)` in
chunks sized by R-WRT-08. The Javadoc states that rows committed by others between the two steps are not touched.

**R-WRT-12** **No accidental full-table writes.** If no predicate is left after skipping empty `Optional`s
(`api/12` R-FLT-01), `build()` throws `MQ1601` unless `all()` was called. `in(col, List.of())` still renders `FALSE` and
affects nothing (`api/12` R-FLT-02).

**R-WRT-13** **Only what was set is written.** The `SET` clause holds exactly the columns marked in the change set plus
the explicit `set`/`setNull`/`setExpression` calls, in declaration order. "Set to NULL" writes NULL; "not set" writes
nothing. A column assigned twice throws `MQ1602` at `build()`. Primary-key columns and the `@Version` attribute are
never assignable; a hand-written column naming one throws `MQ1605`.

**R-WRT-14** **Assigned values are typed and bound.** Values pass through the column's converter and are always bind
parameters, never inlined. A to-one attribute set by id binds `EntityManager#getReference(target, id)`, so no row is
loaded. Types are checked as for queries (INV-3, `api/10` R-COL-08).

**R-WRT-15** **The persistence context is not left stale.** Before the statement the engine calls `flush()`, so pending
entity changes are written first and not overwritten afterwards. After it, `clear()` by default
(`PersistenceContextMode.CLEAR`), or nothing with `KEEP`. Hibernate invalidates the entity's second-level cache region
for bulk statements.

**R-WRT-16** **Bulk updates respect optimistic locking.** When the root has a `@Version` attribute, every update renders
`version = version + 1` (or the current timestamp for a timestamp version) unless `keepVersion()` is set.
`expectVersion(v)` adds `AND version = ?` and needs `whereKey`, else `MQ1606`; zero affected rows then throws
JPA's `OptimisticLockException`.

**R-WRT-17** **Chunked writes visit every matched row once and terminate.** Keyset over the primary key: select the next
`n` matching keys `WHERE <filters> AND pk > :last ORDER BY pk`, write `WHERE pk IN (…)`, repeat until a chunk is short.
The cursor moves forward on the key, so an update that leaves rows still matching cannot loop forever, and a delete
never re-reads what it removed. The chunk size is clamped to the vendor's limits (R-WRT-08).

## 6. Transactions

**R-WRT-18** A bulk write needs an active transaction. The executor checks `EntityManager#isJoinedToTransaction` and
throws `MQ2501` naming the operation, instead of the provider's `TransactionRequiredException` at the end.

**R-WRT-19** A chunked write runs every chunk in the caller's transaction. The Spring module can commit per chunk
instead (`integration/50` R-SPR-11), trading atomicity for short locks and undo logs.

## 7. Executor surface

```java
public interface ModelQueryExecutor<E> {
    // … the read methods of api/11 §6
    long update(ModelUpdate<E, ?> u);               // rows affected
    long delete(ModelDelete<E, ?> d);               // rows affected
}
```

**R-WRT-20** Both methods return the rows affected, summed over chunks, and are reachable with a plain `EntityManager`
(INV-8).

## 8. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-WRT-01 | A change set writes only its set columns; a column set to NULL writes NULL and an unset one is absent from the `SET` clause (R-WRT-02, R-WRT-13). |
| AC-WRT-02 | A change set bound by Jackson from `{"note": null}` clears `note`; a body without `note` leaves it unchanged (R-WRT-03). |
| AC-WRT-03 | `from(model, columns)` copies NULLs; a non-writable column in `columns` throws `MQ1607` (R-WRT-04). |
| AC-WRT-04 | `set` on a self-referencing join throws `MQ1604`; `set(col, null)` throws `MQ1603`; a column assigned twice throws `MQ1602` (R-WRT-06, R-WRT-13). |
| AC-WRT-05 | An empty change set runs no SQL and returns 0 (R-WRT-07). |
| AC-WRT-06 | `whereKeys` with more keys than the vendor's limits, single and composite, updates every key once (R-WRT-08). |
| AC-WRT-07 | A joined filter renders as `EXISTS` on H2 and PostgreSQL and runs key-first on MySQL, affecting the same rows (R-WRT-10, R-WRT-11). |
| AC-WRT-08 | A write with every filter skipped throws `MQ1601` unless `all()` was called (R-WRT-12). |
| AC-WRT-09 | Converters apply to assigned values, and a to-one set by id loads no row (R-WRT-14). |
| AC-WRT-10 | Pending entity changes are flushed first; the persistence context is cleared by default and kept with `KEEP` (R-WRT-15). |
| AC-WRT-11 | Updates increment the version; `keepVersion` does not; an `expectVersion` mismatch throws `OptimisticLockException`; `expectVersion` without `whereKey` throws `MQ1606` (R-WRT-16). |
| AC-WRT-12 | A chunked update that leaves rows matching terminates and touches each row once; a chunked delete crosses the vendor's IN limits (R-WRT-17). |
| AC-WRT-13 | A write with no transaction throws `MQ2501`; a delete blocked by a foreign key surfaces the provider's constraint exception (R-WRT-18). |
