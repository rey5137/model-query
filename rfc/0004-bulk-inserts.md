# RFC 0004 — Inserts

- **Status:** draft (revision 3, after the second `architect-review`)
- **Affects:** `P-5`, `INV-1`, `INV-9` (wording); `api/14` (retitled "Writes"; §1 R-WRT-01; new §10 with `R-WRT-24`…`R-WRT-40`
  and `AC-WRT-21`…; R-WRT-15, R-WRT-18, R-WRT-19, R-WRT-20 extended to inserts); `processor/30` (new `R-PROC-23`,
  `@InsertModel`, and `R-PROC-24`); `processor/31` (new §7, generated insert models); `processor/32` (codes
  `MQ35xx`); `vendor/40` (new `R-VND-14`, the insert SPI; `VendorProfile` gains `maxValuesRows()` and
  `conflictTargetHonoured()`); `integration/50` (`ModelQueryRepository` gains `insert`, `insertReturningKeys` and `persist`; R-SPR-10 and
  AC-SPR-09 extended to them); `reference/90` (new `MQ18xx`
  sub-range and codes); `reference/92` (new D-116, amended D-14, D-85 and P-5); `core` `ChunkedWriteException` (new
  accessors). `docs/plan/mvp-plan.md`: a new milestone before the M10 freeze.
- **Discussion:** [#28](https://github.com/rey5137/model-query/discussions/28)
- **Target:** 0.3.0. Every new public type and method is `@Incubating`; D-85 lists them as exempt from the 1.0 freeze.

## Summary

Three ways to write new rows, all from models, so the caller never handles the entity:

- **Insert-select** copies the rows a filter matches into another entity's table,
  `INSERT INTO target (…) SELECT … FROM source … WHERE <filters>`, reusing the `Filters` DSL, joins and the vendor's
  limits exactly as bulk update and delete do.
- **Insert-values** writes rows given as instances of an insert model (a record) in multi-row `INSERT … VALUES`
  statements, chunked per call instead of by the provider's global JDBC batch setting. It can carry a **conflict
  clause** (`DO NOTHING` / `DO UPDATE`), which is how an insert becomes conditional, and can **return the generated
  keys** for every generator the provider runs before the statement (sequence, table, UUID).
- **`persist(model)`** writes one row through JPA's own `persist`, so it returns the key for every generator,
  `IDENTITY` included, and runs the entity's lifecycle callbacks, Bean Validation and Envers.

The first two are bulk writes: they load no entity and run no callback, cascade, Bean Validation or Envers audit, and
are Hibernate-only (JPA has no insert criteria). `persist` is an entity write and works on any provider.

## Motivation

D-14 accepted filter-driven bulk writes and scoped them to update and delete. Four cases keep coming back that force
the caller back to hand-written JPQL, native SQL, or exposing the entity:

1. **Copy what a filter matches.** Archiving closed orders into `order_archive`, seeding a child table from a parent's
   matching rows. The read already exists as a query model and its filters; the write is a hand-written JPQL
   `INSERT … SELECT` that re-derives the joins and drops the filter semantics (D-5, D-6) and the vendor limits.
2. **Write many rows without exposing the entity.** The adopter's layering keeps entities inside the persistence
   layer. Inserting 50,000 imported rows today means mapping each model to an entity and calling `persist`, which
   exposes the entity or a mapper to the layer above, makes batching a global setting that cannot differ per call (and
   is disabled for `IDENTITY`), and keeps every inserted entity managed until a manual `flush()`/`clear()` loop.
3. **Insert unless it is already there.** Idempotent imports and event consumers need "insert, or skip / update when a
   row with this unique key exists"; with `persist` that is a read per row and a race between the read and the write.
4. **Create one row from a request and return its id**, the everyday `POST` endpoint, still without the entity leaving
   the persistence layer, on any id generator.

Hibernate's criteria API has the first three since 6.5 (`HibernateCriteriaBuilder.createCriteriaInsertSelect` /
`createCriteriaInsertValues`, `JpaCriteriaInsert.onConflict()` with `getExcludedRoot()`), with the same signatures on
6.6.58 and 7.4.11. Its bulk insert returns a count only (`Query#executeUpdate`); the criteria API has no `RETURNING`.
Keys come back from the provider's entity-insert path only, which is why case 4 goes through `persist`.

## Design

### 1. Scope, INV-1 and INV-9

**R-WRT-24** A bulk insert writes either the rows a filter over a source model matches (insert-select, §3) or rows
given as insert-model instances (insert-values, §4), as one provider insert statement or a chunked series of them. It
loads no entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit (R-WRT-01). Every bulk
`insert` Javadoc says so, and says that `persist` (§6) is the method that runs them.

`INV-1`'s last sentence becomes: "The only writes are explicit bulk `update`, `delete` and `insert` calls and
`persist` (`api/14`, D-14, D-116). A bulk write loads no entity; every write leaves the persistence context flushed
and, after a bulk write, by default cleared; `persist` leaves the entity it created detached." `INV-9` lists
`ModelInsert`, `ValuesInsert` and
`ModelPersist`; the copy of the rows is shallow (a mutable value inside a row is the caller's). `api/14` is retitled
"Writes". `P-5` becomes "… Not a SQL builder, not an ORM, not an entity write path beyond `persist(model)`, which hides
the entity (D-116)."

### 2. Insert models

```java
@InsertModel(root = OrderArchiveEntity.class)
public record OrderArchiveRow(
        Long orderId, OrderStatus status, BigDecimal total,
        @Column(attribute = "customer") Long customerId) {}   // to-one by id: writes the foreign key

@InsertModel(root = OrderEntity.class)
public record NewOrder(                         // no id: OrderEntity's id is generated (R-WRT-26)
        @NotBlank String externalRef,           // the caller's @Valid sees every field (R-WRT-31)
        OrderStatus status,
        BigDecimal total,
        @Column(attribute = "customer") Long customerId) {}
```

**R-PROC-23** `@InsertModel(root = …)` declares the root-entity attributes an insert writes. It accepts `@PrimaryKey`
and `@Column` (including a to-one association written by id, as on update models) and rejects `@Join`,
`@FilterColumn`, `@Aggregate`, `@GroupBy` and `@Computed` (`MQ3502`): an insert reads no row of its root. Unlike an
update model it is instantiated: it is the row. The processor generates `Q<Model>` with one `ColumnField` per
component, `INSERT_COLUMNS` in declaration order, and the `insert`, `insertFrom` and `persist` builders.

**R-PROC-24** One type carries at most one of `@QueryModel`, `@UpdateModel` and `@InsertModel`, since each generates
`Q<Model>` (`MQ3503`).

**R-WRT-25** Every component of an insert model is a column of every row it writes, and `null` writes NULL. A column
the database or the server fills (a default, an audit timestamp) stays out of the model; a server value goes in with
`set(column, value)`, one bind per row. One column list per definition is what lets rows share a statement (R-WRT-29).
An attribute the model does not name takes the database default under `insert`, but under `persist` it is written as
the no-arg constructor leaves it (R-WRT-39): the same model can write different rows through the two.

**R-WRT-26** The id. When the root's `@Id` has a generator, the model leaves it out; when it has none, the model names
it with `@PrimaryKey` and a `null` id in a row throws `MQ1802` before any statement. Naming a generated id is
`MQ1802` too: whether a vendor accepts an explicit `IDENTITY` value differs (SQL Server and PostgreSQL reject it,
MySQL accepts it). The processor checks what the annotations show (`MQ3501`); the generator itself is reported by the
provider (R-VND-14) on the definition's first execution (D-61), since the JPA metamodel does not expose it. A root
`@Version` is never in the model; the provider writes its seed value.

Supported generators are an allowlist; any other generator, or an unsupported root, throws `MQ1805` on first execution
naming the generator class or the mapping:

- **Insert-values:** assigned, `IDENTITY`, sequence (pooled included), table and UUID. For all but `IDENTITY` and
  assigned the engine generates the keys through the provider before the statement and writes them as values
  (Hibernate accepts an explicit id and then skips its own generator), so the keys are known (R-WRT-33).
- **Insert-select:** assigned (mapped from the source), `IDENTITY`, and a sequence with increment 1. Rows of a select
  cannot be pre-generated, and Hibernate runs a pooled or table generator through a temporary-table plan and rejects a
  generator it cannot render inline; Q3 may widen this.
- **Either:** a `JOINED` root, a `@SecondaryTable`, a composite id with generated parts, and `@MapsId` (pending Q3)
  are `MQ1805`.

### 3. Insert-select

```java
long archived = executor.insert(QOrderArchiveRow.insertFrom(QOrderView.ROOT)
        .map(QOrderArchiveRow.ORDER_ID, QOrderView.ID)
        .map(QOrderArchiveRow.STATUS, QOrderView.STATUS)
        .map(QOrderArchiveRow.TOTAL, QOrderView.TOTAL)
        .map(QOrderArchiveRow.CUSTOMER_ID, QOrderView.CUSTOMER_ID)
        .set(ARCHIVED_AT, now)
        .where(f -> f.eq(QOrderView.STATUS, OrderStatus.CLOSED).lt(QOrderView.CLOSED_AT, cutoff))
        .chunked(ChunkOptions.size(5_000))
        .build());
```

**R-WRT-27** `insertFrom(sourceRoot)` takes the source query model's root `TableField`. Stages follow D-60: `map`/`set`,
then `where` or `all()` (R-WRT-12, `MQ1601`), then options. `<C> map(ColumnField<M, E, C> target,
ColumnField<?, ?, C> source)` takes `ColumnField` only, so a mismatched model type, an aggregate or a grouped source
does not compile (P-2). No converter runs inside the statement (the database copies attribute values), so `build()`
requires both columns to have the same converter class or none, and the first execution (D-61) requires equal entity
attribute types; either failure is `MQ1801`, as is a target column unmapped or mapped twice. A source column may be
joined: the select is built with the same `JoinContext` as the read path.

**R-WRT-28** **An insert-select writes exactly the rows the equivalent read returns** (D-17), duplicates from a
to-many join included: seeding child rows from a parent's matches is that multiplication. `chunked(...)` pages
key-first over the distinct source-root ids with R-WRT-17's guarantees, so each source row is read once. When the
source and the target overlap (the same entity, the same hierarchy, or intersecting `tablesOf`, R-VND-13),
`chunked` throws `MQ1806` on first execution, before the flush: rows a chunk writes could match the next chunk's key
select, and the engine only gets a count back. The unchunked statement stays allowed, since the database reads the
whole select before it inserts. A guard against rows already in the target is a correlated `notExists` filter over the
target (R-FLT-17): portable, not atomic.

### 4. Insert-values

```java
long n = executor.insert(QNewOrder.insert(rows)          // List<NewOrder>, copied when called (INV-9)
        .set(CREATED_AT, now)
        .chunked(ChunkOptions.size(500))
        .build());

List<Long> ids = executor.insertReturningKeys(QNewOrder.insert(rows).build(), Long.class);   // ids.get(i) is rows.get(i)'s
```

**R-WRT-29** `insert(rows)` writes every row in list order, as multi-row `VALUES` statements. Rows per statement are
the largest count whose statement stays within the vendor's bind-parameter limit, counted from the parameters the
provider reports for one row (columns, `set` constants, the version seed, a conflict clause's binds, as D-80 counts),
and within `VendorProfile.maxValuesRows()` (1,000 on SQL Server), capped by the `chunked` size when given. An empty
list runs no SQL and returns 0 (or an empty list). `set` on a column the model already has throws `MQ1801`. Stages
follow D-60: `set`, then `onConflict` (§5), then options. `maxValuesRows()` defaults to `Integer.MAX_VALUE`; where the
provider cannot render a `VALUES` list (Oracle before 23) it writes one statement per row inside the chunk (Q3).

**R-WRT-30** Values pass through the column's converter and are always bind parameters (R-WRT-14). A to-one column
binds `EntityManager#getReference(target, id)`. A `null` row throws `MQ1803` at `build()`.

**R-WRT-31** The library does not validate rows (R-WRT-24). Because every field of an insert-model row is present, a
plain `@Valid List<NewOrder>` on the caller's method checks them with the model's own constraints, `@NotNull` included.
D-15's field-by-field rule is for change sets and does not apply.

**R-WRT-32** By default the statements run in the caller's transaction (R-WRT-18). `ChunkOptions.commitEachChunk()`
commits each statement (R-WRT-19); `lockKeys()` on insert-values throws `MQ1801` at `build()`. A failed chunk throws
`ChunkedWriteException` (`MQ2502`). For insert-select it carries source keys, as R-WRT-20. For insert-values it gains
`OptionalInt nextRowIndex()` (the first row not committed) and the in-doubt row range; `committedRows()` stays the
rows affected, which with `doNothing` is not the rows processed. Resume with `rows.subList(nextRowIndex, size)`.

**R-WRT-33** `insertReturningKeys(insert, keyType)` returns the generated keys in row order. It accepts an insert-values
definition with no conflict clause (a definition with one, or an insert-select, does not compile: a skipped row would
leave a key with no row). The keys come from R-WRT-26's pre-generation: `IDENTITY` and an assigned id throw `MQ1807` on
first execution, the message pointing to `persist` (§6) and to the rows themselves respectively. With
`commitEachChunk()` it throws `MQ1801` at `build()`, since a failure would lose the committed rows' keys. A `keyType`
other than the id's type (the `@IdClass` or embeddable for a composite id) is `MQ1807` as well.

### 5. Conflict clauses: the conditional insert

```java
executor.insert(QNewOrder.insert(rows).onConflict(QNewOrder.EXTERNAL_REF).doNothing().build());

executor.insert(QNewOrder.insert(rows)
        .onConflict(QNewOrder.EXTERNAL_REF)
        .doUpdate(u -> u.setFromRow(QNewOrder.STATUS, QNewOrder.TOTAL)   // = the incoming row's values
                        .set(UPDATED_AT, now)
                        .where(f -> f.ne(QNewOrder.STATUS, OrderStatus.CLOSED)))
        .build());
```

**R-WRT-34** `onConflict(ColumnField<M, E, ?> first, ColumnField<M, E, ?>... rest)` names the unique key the conflict is
detected on; zero columns does not compile. The columns must be the root's id, its natural id, or a declared unique
constraint, checked on first execution (`MQ1804`) from the mapping: `@Id`, `@NaturalId`, `@Column(unique = true)`
and `@Table(uniqueConstraints)`. A unique index that exists only in a migration is not seen and is rejected; the
Javadoc says to declare it in the mapping. The check stays because on a `MERGE` vendor a non-unique match updates
several rows. `onConflict` returns a stage offering only `doNothing()` and
`doUpdate(...)`, so a clause without an action does not compile; `keepVersion()` is offered only after `doUpdate`.
`doUpdate` assigns with `setFromRow` (the incoming row's value), `set` and `setNull`, under R-WRT-13 (no key column,
no `@Version`, no column twice: `MQ1804`); its `where` filters the stored row on root columns only, with no join and no
`exists`, since the conflict action has no join context. A root `@Version` is incremented unless `keepVersion()`;
`MQ1606` applies to a version type it cannot increment. Constraint names are not offered: Hibernate rejects them on
every `MERGE` vendor and for `DO UPDATE` on MySQL. Both actions work on insert-select as well.

**R-WRT-35** Rendering is the provider's: `ON CONFLICT` on PostgreSQL; `ON DUPLICATE KEY UPDATE` on MySQL and MariaDB;
`MERGE` on H2, Oracle and SQL Server. The count is the provider's and differs by vendor for `doUpdate` (MySQL counts
an updated row as 2); the Javadoc says so and the TCK pins it per vendor. Under concurrency a `MERGE` vendor raises a
unique violation for a key another transaction inserts at the same time, rather than skipping it; the Javadoc says so.

**R-WRT-36** **The named key is honoured or the call fails.** MySQL and MariaDB detect a conflict on any unique key,
so `doUpdate` could update, and `doNothing` skip, a row that matched a different key. `VendorProfile` gains
`conflictTargetHonoured()`, false by default so a third-party profile fails safe, and overridden to true by the
built-in PostgreSQL, H2, Oracle and SQL Server profiles; where it is false, `onConflict` throws
`MQ1804` on first execution unless the builder says `anyUniqueKey()`, which accepts the vendor's behaviour explicitly.

**R-WRT-37** **Duplicate conflict keys within one call.** Vendors disagree (PostgreSQL skips them for `doNothing` and
fails for `doUpdate`; `MERGE` vendors insert both and fail), and the outcome would depend on the chunk size. Insert-values
with a conflict clause throws `MQ1808` before any statement when two rows share a conflict-key tuple. For insert-select
the behaviour is documented per vendor and pinned in the TCK.

**R-WRT-38** Persistence context: every bulk insert applies R-WRT-15 unchanged (flush before; `CLEAR` by default,
`KEEP` available; the root evicted from the second-level cache). An insert without `doUpdate` still leaves managed
state stale, for example an initialized `Order.lines` of a managed parent, or a `@Formula`.

### 6. `persist`: one row, any generator

```java
Long id = executor.persist(QNewOrder.persist(newOrder), Long.class);
```

**R-WRT-39** `persist` writes one insert-model row through JPA. The engine instantiates the root entity with its no-arg
constructor and sets each model column through the attribute's metamodel member: the field, or for property access
the setter paired with the getter `Attribute#getJavaMember` returns, after the column's converter. An embeddable path
instantiates the embeddable with its no-arg constructor; a record or constructor-only embeddable is `MQ1805`. A to-one
column binds `EntityManager#getReference`. It then calls `persist` and `flush`, reads `PersistenceUnitUtil#getIdentifier`,
and calls `detach` on the entity, in that order. Constructors and fields are reached with `setAccessible`, so a modular
application `opens` its entity package to the library; the Javadoc says so. `persist` needs no provider SPI and works
with every generator, `IDENTITY` included, and every provider. It needs an active transaction (`MQ2501`).

It is an entity write: `@PrePersist`/`@PostPersist`, Bean Validation, Envers and the provider's insert run, and the
provider maintains the second-level cache, so R-WRT-15's eviction does not apply. The first paragraph of its Javadoc
says that entity attributes the model does not name are written as the no-arg constructor leaves them, often NULL,
not as the database default (unless the mapping is `insertable = false`, generated, or the provider's dynamic insert),
and that a domain constructor's invariants do not run. The `flush` writes the caller's pending changes too, as
R-WRT-15's flush does. `detach` cascades as the mapping says: a to-one with `CascadeType.ALL` or `DETACH` detaches the
instance `getReference` returned, which is the caller's own managed entity when there is one, and an entity persisted
by a callback stays managed; the Javadoc documents this as R-WRT-15 documents `CLEAR`. A `keyType` other than the id's
type (the `@IdClass` or embeddable for a composite id) is `MQ1807`.

**R-WRT-40** `persist` takes no `set`, conflict clause or chunking: server values belong in the entity's callbacks or
in the model. Many rows with `IDENTITY` keys are a loop over `persist`, one statement each, which is what the provider
does for `IDENTITY` anyway; the Javadoc points to `insert`/`insertReturningKeys` for anything else.

### 7. Provider SPI and executor

**R-VND-14** `ProviderSupport` gains `Optional<InsertSupport> inserts()`, empty by default. `InsertSupport` uses only
`jakarta.persistence` types (INV-7): it takes the source as a `CriteriaQuery<Tuple>` (which Hibernate's
`JpaCriteriaInsertSelect.select` accepts) or the row values, callbacks receiving the target and excluded `Root<E>` for
the conflict action, and returns a `jakarta.persistence.Query`, so timeouts and hints apply as for update and delete.
It also reports the root's generator kind and generates `n` keys for the insert-values generators of R-WRT-26.
`HibernateProviderSupport` implements it. A bulk `insert` on a provider without it throws `MQ4009` before the flush;
`persist` needs no SPI.

```java
long insert(ModelInsert<E, ?> i);                                      // ModelQueryExecutor, beside update and delete
<K> List<K> insertReturningKeys(ValuesInsert<E, ?> i, Class<K> keyType);
<K> K persist(ModelPersist<E, ?> p, Class<K> keyType);
```

`ModelQueryRepository` gains the same three beside its `update` and `delete`; R-SPR-10 extends to them, so each joins
the current transaction or opens one, and AC-SPR-09 covers them (`integration/50`).

### 8. New and changed ids

| Id | Change |
|---|---|
| `P-5`, `INV-1`, `INV-9` | Wording, §1. |
| `R-WRT-01` | "update or delete" becomes "update, delete or insert"; §10 is the insert detail. |
| `R-WRT-15`, `-18`, `-19`, `-20` | Apply to inserts as amended by R-WRT-32 and R-WRT-38. |
| `R-WRT-24`…`R-WRT-40` | New, §1–§6. |
| `R-PROC-23`, `R-PROC-24` | New: `@InsertModel`; one model annotation per type. |
| `R-VND-14` | New: `ProviderSupport#inserts()`; `VendorProfile.maxValuesRows()`, `conflictTargetHonoured()` (INV-6). |
| `R-SPR-10`, `AC-SPR-09` | Extended to `insert`, `insertReturningKeys` and `persist` on `ModelQueryRepository`. |
| `D-116` | New (§9); amends D-14 and D-85. |

Codes (provisional; `reference/90` gains the `MQ18xx` sub-range "inserts", processor codes go in `processor/32`):

| Code | Meaning |
|---|---|
| `MQ1801` | An insert column unmapped, mapped or set twice, mapped with another converter or attribute type; `lockKeys()` on insert-values; `insertReturningKeys` with `commitEachChunk()` |
| `MQ1802` | An insert model naming a generated id, lacking an id that has no generator, or a row with a `null` assigned id |
| `MQ1803` | A `null` row |
| `MQ1804` | Conflict columns that are not a unique key, a `doUpdate` assigning a key or `@Version`, or a conflict target the vendor does not honour without `anyUniqueKey()` |
| `MQ1805` | A generator outside R-WRT-26's allowlist, a `JOINED` or `@SecondaryTable` root, a composite id with generated parts, `@MapsId`, or a constructor-only embeddable under `persist` |
| `MQ1806` | `chunked` insert-select whose source and target overlap |
| `MQ1807` | Keys requested for an `IDENTITY` or assigned id; a `keyType` that is not the id's |
| `MQ1808` | Two rows of one insert-values call share a conflict-key tuple |
| `MQ4009` | A bulk insert on a provider with no `InsertSupport` |
| `MQ3501`…`MQ3503` | Processor: id mismatch it can see; a rejected annotation; two model annotations on one type |

Acceptance criteria `AC-WRT-21`…, at minimum, on every Tier 1 vendor:

- insert-select writes exactly the rows the equivalent `list` returns, with joined and to-many source columns;
  chunked over a non-overlapping target writes each source row once; chunked over an overlapping one throws `MQ1806`;
- insert-values with an assigned id, a pooled sequence, a converter, a to-one by id and a `set` constant round-trips;
  `IDENTITY` round-trips with `insert` and throws `MQ1807` with `insertReturningKeys`;
- `insertReturningKeys` returns keys that read back each row by index;
- rows per statement never exceed the bind or `VALUES` row limit (SQL snapshots per vendor, with the version seed);
- `doNothing` skips a conflicting row; `doUpdate` with `setFromRow` and `where` updates only the matching rows and
  increments `@Version`; MySQL without `anyUniqueKey()` throws `MQ1804`; duplicate conflict keys throw `MQ1808`;
- a `commitEachChunk` failure reports `nextRowIndex()` and the in-doubt range;
- after any bulk insert the context is cleared by default and kept with `KEEP`;
- `persist` with `IDENTITY` returns the key, runs `@PrePersist` and leaves the created entity detached, on Hibernate
  and on a second provider if the TCK has one; with a `CascadeType.ALL` to-one it detaches the caller's managed target,
  as documented; an unnamed nullable attribute with a database default is written as NULL;
- insert-select with a pooled sequence or `JOINED` root throws `MQ1805`;
- `ModelQueryRepository.insert`, `insertReturningKeys` and `persist` succeed without an ambient transaction;
- a bulk insert on a non-Hibernate provider throws `MQ4009` and runs no flush.

### 9. D-116 (draft)

**D-116 — Inserts (amends D-14, D-85 and P-5).** Insert-select is the read path's rows written elsewhere, so it reuses
filters, joins and vendor limits exactly as D-14's update and delete do. Insert-values is accepted for a different
reason: it keeps entities inside the persistence layer and makes batching a per-call option, and it is still not an
entity write. Keys come back only where the engine generated them first, because the provider's bulk insert returns a
count. `persist(model)` is the one entity write, accepted because it hides the entity, not to duplicate JPA: it is the
only portable way to get an `IDENTITY` key back, and a single created row usually wants its callbacks; D-14's "never entity writes" and P-5 gain that one exception. `persist` detaches only
the entity it created; cascade-detach is the mapping's. Insert-select generators are limited to those Hibernate renders
inline. Conditional
insert-values is the conflict clause: one statement per chunk, and a concurrent duplicate surfaces as a constraint
violation rather than a silent skip; insert-select can already guard with `notExists` over the target. Rejected: a
per-row guard statement (its cost), partial rows taking column defaults (rows with different column sets cannot share
a statement), constraint-named conflict targets (unsupported on most vendors), `StatelessSession` for keys
(Hibernate-only, and it silently skips Envers and validation), reading a "last insert id" (not available after a
prepared statement on SQL Server, absent on Oracle), and reusing `@UpdateModel` (opposite id rule, never instantiated,
"unset" means "keep"). D-85: `@InsertModel` joins the annotation freeze exemptions, and `ModelInsert`, `ValuesInsert`,
`ModelPersist`, `InsertSupport`, the three executor methods, the generated builders and the repository methods join
the incubating bulk-write types; the generated `insert`, `insertFrom` and `persist` carry `@Incubating`, as
`update(...)` does, so "every type generated code links against is frozen" still holds.

## Compatibility

Additive for callers. `ModelQueryExecutor` and `ModelQueryRepository` gain abstract methods, which is source- and
binary-incompatible for a class implementing them (japicmp reports `METHOD_ADDED_TO_INTERFACE`); both are
`@Incubating` today, and D-85 keeps their write methods incubating at 1.0, so this is allowed. `ProviderSupport#inserts()` is a default method; `VendorProfile`'s two
new methods have defaults. `ChunkedWriteException` gains accessors and keeps its existing ones. No rule changes meaning
except `INV-1` and `P-5`, which widen the list of explicit writes without changing what a query may do.

## Alternatives

- **Do nothing.** Callers keep `persist` loops and hand-written JPQL inserts, and the entity stays in the service layer.
- **Native SQL from the metamodel.** Portable and could read keys with `getGeneratedKeys`, but it re-implements column
  naming, converters, inheritance and id generation the provider already owns.
- **`StatelessSession.insert` for keys.** See D-116.
- **Reuse `@UpdateModel` and change sets as rows.** See D-116.
- **A guard filter.** See D-116; addable later.

## Unresolved questions

1. **Key typing.** `Class<K>` checked at run time (proposed), or a key type the processor generates from the entity's
   id when it can see the entity. `architect-review` decides.
2. **`ValuesInsert` as a separate type** so `insertReturningKeys` rejects conflict clauses at compile time (proposed),
   or one `ModelInsert` with an `MQ1807` check.
3. **Vendor spike before the slices**, a generator × vendor × conflict matrix: `IDENTITY`, assigned, pooled sequence,
   table, UUID; pre-generated ids in insert-values on every vendor (Hibernate's source accepts them); the temporary-table plan for
   insert-select with pooled and table generators; `@MapsId`; multi-row `VALUES` on Oracle before and after 23; the
   update count and the conflict rendering per vendor; `persist` on bytecode-enhanced entities written by field.
   Results fix R-WRT-26/29/35 and the `MQ1805` allowlist.
4. **Milestone.** A new milestone before the freeze ("M10 — Inserts → 0.3.0", the freeze renumbered to M11), or
   "M9b"; decided when the RFC is accepted.
