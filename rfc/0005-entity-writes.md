# RFC 0005 — Entity writes, persist returning a model, write assignments

- **Status:** accepted (merged in [#30](https://github.com/rey5137/model-query/pull/30); open questions resolved by
  the M11.1 `architect-review`, see D-118)
- **Affects:** `INV-1`, `P-5` (wording); `api/14` §1 R-WRT-01, R-WRT-15, R-WRT-16, R-WRT-17, R-WRT-39 (extended), new
  §11 with `R-WRT-41`…`R-WRT-49` and `AC-WRT-34`…; `integration/50` (R-SPR-10 and AC-SPR-09 extended to the new
  repository methods; the starter hands `WriteAssignment` beans to the config, as it does `VendorProfile` beans);
  `reference/90` (new `MQ1610`…`MQ1612`, `MQ1809`); `reference/92` (new D-118, amends D-14, D-85, D-116 and P-5).
  `docs/plan/mvp-plan.md`: a new milestone M11 — Entity writes → 0.4.0, the freeze renumbered to M12.
- **Discussion:** [#30](https://github.com/rey5137/model-query/pull/30)
- **Target:** 0.4.0. Every new public type and method is `@Incubating`; D-85 lists them as exempt from the 1.0 freeze.

## Summary

Three additions on the write side, all opt-in:

- **Entity mode** (`throughEntities()`) on bulk update and delete. The matched rows are loaded as entities, chunk by
  chunk, changed or removed, and flushed, so `@PreUpdate`/`@PostUpdate`/`@PreRemove`, Hibernate `Post*EventListener`s,
  Envers and Bean Validation run with the real old state. The definition, its filters, change sets and chunking stay
  the same; only how each chunk is written changes.
- **`persist` returning a model.** `persist(persist, returning)` returns the written row as a query model, built from
  the managed entity after the flush: the generated id, `@PrePersist` values and constructor defaults included, with no
  second statement.
- **Write assignments.** A column the server sets on every write, such as `last_updated_timestamp`, is declared once
  per entity on `ModelQueryConfig` instead of at every call site.

## Motivation

The D-111 adopter wants every write to go through model-query, so no JPA entity leaves its repository layer, and found
three gaps in 0.3.0 (feedback on the 0.4.0 write-side feature requests, Hibernate 6.6,
MySQL in production, H2 in tests).

1. **Audit (blocker).** Its audit library registers Hibernate `PostInsert`/`PostUpdate`/`PostDelete` listeners that
   publish a before/after snapshot of every audited entity. Six audited entities are written today with a load, a
   setter loop and `saveAll`. A bulk update or delete loads no entity (R-WRT-01), so migrating any of them would
   silently drop audit records. Only `persist` goes through JPA.

   ```java
   // today: PostUpdate fires per row, so the audit log sees each change
   List<PaymentReportServiceEntity> entities = repository
           .findAllByReportTypeIdAndServiceIdInAndIsDeletedIsFalse(reportTypeId, serviceIds);
   entities.forEach(e -> { e.setIsDeleted(true); e.setLastUpdatedTimestamp(now); });
   repository.saveAll(entities);
   ```

2. **The created row.** `persist` returns only the key. Create methods return the full row to ten or more callers,
   which use the id, a status defaulted by the constructor and timestamps set by `@PrePersist`. Today that costs a
   second select by key, or a service that rebuilds the row from its input and duplicates the defaults.
3. **Server-set columns.** Bulk writes skip `@PrePersist`/`@PreUpdate`, so every migrated call site must add
   `set(LAST_UPDATED_TIMESTAMP, now)`, and a forgotten one is a silent data bug.

The feedback also asked for `IDENTITY` keys from a bulk insert and for saving a parent with its children; both are
out of scope (see Alternatives).

## Design

### 1. Scope, INV-1 and D-14

D-14 rejected entity-level writes because they duplicate JPA, and D-116 made `persist` the one exception. Entity mode
is the second, accepted for the same reason: it hides the entity, and it reuses what the library adds over a JPA loop
(the filter DSL, change sets with their PATCH semantics, key-first chunking, resumable failures and transaction
handling). It is opt-in per definition; without `throughEntities()` nothing changes.

INV-1 gains "an update or delete `throughEntities()` loads and writes the root's entities chunk by chunk, leaving them
as `PersistenceContextMode` says" in its list of explicit writes. P-5 reads "...beyond `persist(model)` and
`throughEntities()`, which hide the entity (D-116, D-118)". Queries stay read-only, and nothing the library returns is a
managed entity.

### 2. Entity mode for update and delete

```java
repository.update(QPaymentReportServiceDelete.update(changes)
        .where(f -> f.eq(REPORT_TYPE_ID, reportTypeId).in(SERVICE_ID, serviceIds).eq(IS_DELETED, false))
        .throughEntities()
        .chunked(ChunkOptions.size(500))
        .build());

repository.delete(QExportRoleConfiguration.delete().whereKey(id).throughEntities().build());
```

**R-WRT-41** `throughEntities()` is offered on the options stage of `ModelUpdate` and `ModelDelete`, beside
`chunked` and `persistenceContext`, and on each `Resumable` stage (`@Override public Resumable<E,K,M>
throughEntities()`); `entityMode()` is `@EngineFacing`. `build()` throws `MQ1610` for an update that combines
`throughEntities()` with `keepVersion()`, `expectVersion(v)` or any `setExpression`, whatever the call order; a delete
has no incompatible option. The Javadoc of `throughEntities()` says the error comes when the definition is built (for a
`static final` constant, at class initialization), not at compile time. An entity-mode write is always chunked:
without `chunked(...)` it uses `ModelQueryConfig.bulkWriteChunkSize()`, so memory is bounded by one chunk of entities.

**R-WRT-42** Each chunk selects its keys exactly as R-WRT-17 does (`WHERE <tree> AND pk > :last ORDER BY pk`), then
loads the chunk's entities with one `CriteriaQuery<E>` over the R-WRT-17 predicate (`pk IN (…) AND <tree>`), never
one `find` per key; a composite id uses the `vendor/41` §5 expansion, and for `whereKey(s)` the chunk is the given
keys. A row that stops matching the
filter between the key select and the load is not written. An update applies each assignment to the managed entity
through the same metamodel member R-WRT-39 uses (field, or the setter paired with the getter), after the column's
converter; a to-one by id binds `EntityManager#getReference`, so its target is not loaded. A delete calls
`EntityManager#remove`. The chunk is then flushed. The provider writes only the entities that changed, so an
assignment that leaves a row as it was writes nothing and fires no update listener.

**R-WRT-43** It is an entity write: `@PreUpdate`/`@PostUpdate`, `@PreRemove`/`@PostRemove`, the provider's event
listeners, Envers, Bean Validation, cascades (`REMOVE`, `orphanRemoval`) and the mapping's `@SQLDelete` run, and the
provider maintains the second-level cache, so R-WRT-15's eviction does not apply. A delete may therefore remove more
rows than it counts, and the Javadoc says so.

**R-WRT-44** The return value is the number of distinct entities loaded, which is the number of rows matched, not the
number the provider wrote.

**R-WRT-45** The persistence context follows `PersistenceContextMode`. R-WRT-15's flush before the first chunk stays.
With `CLEAR` (the default) the engine clears after each chunk's flush, which is what keeps memory to one chunk. With
`KEEP` nothing is detached: the loaded entities stay managed and current, since they were written through the context,
and the Javadoc of `KEEP` says the context then grows with every matched row. The engine never detaches only "what it
loaded": a row the caller already had managed is the same instance, and detaching it would surprise the caller.

**R-WRT-46** Optimistic locking is the provider's: a `@Version` attribute is checked and incremented by the flush.
`keepVersion()` and `expectVersion(v)` cannot be honoured, and `setExpression` cannot be computed in Java, so
`build()` throws `MQ1610` for a definition that combines any of them with `throughEntities()`. An
`OptimisticLockException` from a chunk's flush is a failed chunk like any other: it reaches the caller as R-WRT-20
says, wrapped in `ChunkedWriteException` with the key to resume after.

**R-WRT-47** An entity-mode write needs an active transaction (`MQ2501`); with `commitEachChunk()` each chunk loads,
writes and clears inside its own transaction (R-WRT-19). Each resume after a `ChunkedWriteException` is a new execution,
so it calls every write assignment's supplier again (R-WRT-49). It needs no provider SPI and runs on every provider.
Inserts have no entity mode: an insert that must fire listeners is `persist`, in a loop for many rows, which costs the
same as `saveAll` with an `IDENTITY` id.

### 3. `persist` returning a model

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
beyond a to-one id, a join with `on(...)`, an expression, an aggregate), is `MQ1809` on its first execution per factory,
before any statement; there is no silent fallback to a select. The Javadoc says the model holds what JPA knows after the
flush: a value the database fills (a column default, a trigger) is present only where the mapping has the provider read
it back (`@Generated`). The overload is separate rather than a `returning(...)` stage, which would change the builder's
key type `K` into `M`.

### 4. Write assignments

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
the attribute's type and a supplier of its value; `entity()`, `attribute()`, `type()` and `kind()` read them back. An
assignment applies to the named class and its subclasses (`isAssignableFrom`), so one on a `@MappedSuperclass` covers
every entity extending it. `ModelQueryConfig.writeAssignments(Collection<? extends WriteAssignment>)` holds them and
`writeAssignments()` returns them, empty by default; under Spring Boot the starter hands every `WriteAssignment` bean to
the config, as it does `VendorProfile` beans. The path names a basic singular attribute of the root, possibly through
embeddables. It is checked on the first write per root per `EntityManagerFactory` (D-61's `checkWriteOnce`), before
any statement, not when the config is built, since one config can serve several factories: an unknown path, an id, a
`@Version`, a collection, a to-one, a whole embeddable, or two assignments of overlapping kinds for one root and path,
is `MQ1611`; a type the attribute cannot take (after boxing) is `MQ1612`. A supplier that returns `null` or a value of
the wrong type is `MQ1612` at execution, before any statement. The supplier is called once per write execution, so
every chunk and every row of one write gets the same value; it may be called from several threads at once and must be
thread-safe, and a `Clock` keeps tests deterministic.

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
callback that sets the same attribute runs after it and wins, and in entity mode an `UPDATE` assignment makes every
matched row dirty.

### 5. New and changed ids

- **`api/14`:** R-WRT-01 names entity mode as the exception for update and delete; R-WRT-15 and R-WRT-16 point to
  R-WRT-45 and R-WRT-46; R-WRT-39 points to R-WRT-48 and R-WRT-49. New §11 with R-WRT-41…R-WRT-49 and:
  - **AC-WRT-34** an entity-mode update fires `@PreUpdate`, `@PostUpdate` and a registered Hibernate
    `PostUpdateEventListener` once per changed row, with the old state, and, for a root with no `UPDATE` assignment,
    none for an unchanged row; the count is the rows matched. The Hibernate listener test lives in
    `model-query-hibernate` or the integration tests, not in `model-query-jpa` (INV-7).
  - **AC-WRT-35** an entity-mode delete fires `@PreRemove` and `PostDeleteEventListener` per row and cascades `REMOVE`
    and `orphanRemoval`.
  - **AC-WRT-36** an entity-mode write loads one select per chunk, holds at most one chunk of entities under `CLEAR`,
    and resumes from `ChunkedWriteException` after an `OptimisticLockException`.
  - **AC-WRT-37** `throughEntities()` with `setExpression`, `keepVersion()` or `expectVersion(v)` is `MQ1610` at
    `build()`.
  - **AC-WRT-38** `persist(persist, returning)` returns the generated id, `@PrePersist` values and constructor
    defaults with one insert and no select; a `returning` query with a `where` (or another R-WRT-48 clause) or an
    unfillable column is `MQ1809` before any statement.
  - **AC-WRT-39** a write assignment is applied by each write in the table above, skipped where the definition sets the
    attribute, called once per execution (again on resume), and overridden by an entity callback under `persist`; an
    empty update stays a no-op; an assignment on a superclass applies to a subclass root; `MQ1611` (including two
    overlapping kinds for one path), `MQ1612` (including a supplier returning `null`).
- **`integration/50`:** R-SPR-10 and AC-SPR-09 cover `persist(persist, returning)`; the starter collects
  `WriteAssignment` beans.
- **`reference/90`:** `MQ1610`, `MQ1611`, `MQ1612` (bulk writes) and `MQ1809` (inserts).

### 6. D-118 (draft)

**D-118 — Entity writes (amends D-14, D-85, D-116 and P-5).** The D-111 adopter's audit runs as Hibernate event
listeners, which a bulk statement never reaches, so a bulk update or delete on an audited table silently loses audit
records. Entity mode is the second exception to D-14 after `persist`, accepted because it keeps the entity hidden and
reuses the filter DSL, change sets and chunking, not to duplicate JPA: rows are loaded by key per chunk, changed
through the metamodel and flushed, so every listener, callback and audit library runs unchanged. The persistence
context follows `PersistenceContextMode` rather than detaching only what was loaded, which would detach a caller's
own managed instance. `persist` may return a query model built from the flushed entity, with no second select, and
refuses what it cannot fill rather than falling back to one. Server-set columns are `WriteAssignment`s per entity on
the config, since a per-model annotation must be repeated on every model and a forgotten one is the bug it fixes; an
explicit `set` wins. Rejected: a write-event SPI (every listener library would need an adapter, and a bulk statement
has no old state without an extra select), an entity mode for inserts (`persist` in a loop is the same cost),
`IDENTITY` keys from a multi-row insert (outside JPA, and MySQL's key arithmetic depends on `innodb_autoinc_lock_mode`),
saving a parent with its children (cascade through an insert model, deferred past 1.0), and a `returning(...)` builder
stage (it would retype the builder). D-85: `throughEntities()`, the `persist` overload, `WriteAssignment`, `WriteKind`
and `ModelQueryConfig.writeAssignments` join the incubating bulk-write types. Placement: `throughEntities()` is an
options-stage method and incompatible options are `MQ1610` at `build()`. `setExpression` precedes the row choice and
`update(changes)` returns the assignment stage, so a separate stage could not make them unexpressible without doubling
the update stage types. Assignments name a string attribute path and a `Class<A>`, checked per root on the first write
per factory (a `ColumnField` needs a model the server-set column is kept out of; a static `SingularAttribute` is null
before bootstrap; a config can serve several factories). They live in `jpa` and match the named class and its
subclasses. `persist` returns a model through a `ModelQuery<E, ?, R>`, not a `SelectSet`, which carries neither the
root nor the mapper.

## Compatibility

Additive for callers. `ModelQueryExecutor` and `ModelQueryRepository` gain an abstract `persist` overload, which is
incompatible for a class implementing them (japicmp `METHOD_ADDED_TO_INTERFACE`); both are `@Incubating` and D-85
keeps their write methods incubating at 1.0, so this is allowed. The builder stages gain `throughEntities()`.
`ModelQueryConfig` gains `writeAssignments`. No released behaviour changes: a definition without `throughEntities()`
and a config without assignments run exactly as in 0.3.0. `INV-1` and `P-5` widen the list of explicit writes without
changing what a query may do.

## Alternatives

- **Do nothing.** Audited tables keep their load, setter loop and `saveAll`, and the entity stays in the service layer.
- **A write-event SPI on `ModelQueryConfig`**, called per affected row with the key, the assignments and an optional
  before-snapshot. It avoids loading entities, but every listener library needs an adapter, the snapshot is an extra
  select anyway, and cascades and Envers still don't run.
- **Detach only what entity mode loaded.** See D-118.
- **Fall back to a select by key in `persist(persist, returning)`.** Predictable cost wins; the caller can run the
  query.
- **A per-model `@Assign` annotation.** See D-118.
- **`persistAll(rows) → List<K>`.** A thin loop over `persist`; left out with the `IDENTITY` request, and addable
  later.

## Resolved questions

Decided by the M11.1 `architect-review` and recorded in D-118:

1. **Stage placement.** The options stage, checked at `build()` with `MQ1610` (R-WRT-41). A separate stage cannot make
   the combinations a compile error, since `setExpression` precedes the row choice, and would double the update stage
   types.
2. **`WriteAssignment` paths.** A string path plus `Class<A>`, checked on the first write per root per factory
   (R-WRT-49).
3. **`persist` returning a model.** Takes a `ModelQuery<E, ?, R>`, not a `SelectSet<M>`, which carries neither the root
   nor the mapper (R-WRT-48).
4. **Milestone:** "M11 — Entity writes → 0.4.0"; the freeze is renumbered to M12 and waits for the adopter to run
   0.4.0 in production.
