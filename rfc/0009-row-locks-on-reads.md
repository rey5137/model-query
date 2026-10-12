# RFC 0009 — Row locks on reads

- **Status:** accepted (after the 2026-10-12 `architect-review`; recorded as D-127)
- **Affects:** `SPEC.md` (INV-1 gains one sentence); `api/11` (new R-QRY-17, `ModelQuery.Builder#lock`,
  `ModelQuery#withLock`; R-QRY-11's guard covers `distinct`/`having` on a locked query); `engine/20` (new R-EXE-14,
  which reads take a lock); `engine/21` (a locked query is refused by paging and export); `vendor/40` (new R-VND-15,
  `ProviderSupport#locks()`); `vendor/41` (the Tier-1 lock rendering table); `api/14` (`MQ1809` gains a lock);
  `reference/90` (new `MQ1209`, `MQ2102`, `MQ2211`, `MQ4010`); `reference/92` (D-127); `integration/50` (the
  repository reads honour the lock); `docs/site` (`queries.md` §Row locks, a recipe)
- **Discussion:** to open

## Summary

A query model can say that the rows it reads are locked: `lock(RowLock.exclusive())` or `RowLock.shared()`, each with
an optional `Wait` (`SKIP_LOCKED`, `NO_WAIT`). The lock is part of the immutable definition, so `list`, `first`,
`one`, `one(q, key)` and `byKeys` (and the repository's `findAll(q, Limit)`, `findOne`, `findFirst`, `findByKey`,
`findAllByKeys`) lock what they return. Paging, `count`, `stream`, export, grouped queries and queries with joins
refuse a lock, and a locked read refuses to run outside a transaction. The lock reaches the database through
`ProviderSupport.locks()`, a provider sub-SPI that reports what it renders, so a lock that would be silently dropped,
widened or deferred is refused instead. All new API is `@Incubating`. Ships in 0.9.0.

## Motivation

LitePOS takes row locks in 11 classes (`litepos-gaps.md` rows 1 and 2): claiming a job (`SKIP LOCKED`), reserving
stock before a sale, and reading a parent before inserting children without blocking their foreign-key checks. Each
of those reads is a query model everywhere else in the code base, but the lock forces it back to hand-written JPQL or
native SQL, a second definition of the same projection that drifts from the model. The library already takes a lock
itself (`ChunkOptions.lockKeys()`, R-WRT-11) but offers none to callers, and `QueryCustomizer` sees only the
`CriteriaQuery`, where JPA has no lock.

## Design

### 1. `RowLock` and the builder (new R-QRY-17)

```java
static final ModelQuery<JobModel, Long> NEXT_JOB = ModelQuery.builder(JOB, JobModel.class)
        .where(f -> f.eq(JOB.STATUS, "PENDING"))
        .orderBy(JOB.CREATED_AT.asc())
        .lock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED))
        .build();

Optional<JobModel> job = executor.first(em, NEXT_JOB);   // PostgreSQL: FOR NO KEY UPDATE SKIP LOCKED, LIMIT 1
```

`RowLock` (core, `@Incubating`) is an immutable value with `equals`/`hashCode`: a strength (`Strength`: `EXCLUSIVE`,
`SHARED`) and a `Wait` (`WAIT`, `NO_WAIT`, `SKIP_LOCKED`), read by `strength()` and `waitPolicy()` (not `wait()`, which
`Object` declares). Factories: `exclusive()`, `exclusive(Wait)`, `shared()`, `shared(Wait)`; the no-argument forms wait.
An invalid combination cannot be written (P-2).

**Two strengths, named for what they guarantee, not for a keyword.** Hibernate offers no per-query way to render a
true `FOR UPDATE` on PostgreSQL: `PESSIMISTIC_WRITE` renders `FOR NO KEY UPDATE` in both 6.6 and 7.x. So `exclusive()`
means "no other transaction updates, deletes or exclusively locks this row until mine ends", and `shared()` means
"no other transaction updates or deletes it". `vendor/41` lists the rendering:

| | `exclusive()` | `shared()` |
|---|---|---|
| PostgreSQL | `FOR NO KEY UPDATE` | `FOR SHARE` |
| MySQL | `FOR UPDATE` | `FOR SHARE` |
| H2 | `FOR UPDATE` | `FOR UPDATE` (H2 has no `FOR SHARE`) |

On PostgreSQL `exclusive()` does not block a child row's foreign-key check, which is what the parent-read case
wants. A key-level or true-`FOR UPDATE` strength can be added later if a provider can render it.

`ModelQuery.Builder#lock(RowLock)` stores it; `ModelQuery#lock()` returns `Optional<RowLock>`;
`ModelQuery#withLock(RowLock)` returns a locking copy (as `orderedBy`, R-QRY-14): it takes no `null`, returns `this`
for an equal lock, and keeps the fetch plan (R-FCH-13). `ModelQuery` stays identity-equal.

**`MQ1209`**, at `build()` and in `withLock`: a lock on a grouped query (`isGrouped()`, which includes
aggregate-only models). PostgreSQL rejects a locking clause with `GROUP BY`, and a group is not a row. R-QRY-11's
guard extends: a customizer that sets `distinct` or `having` on a locked query is `MQ1205`.

The name is `RowLock`, not `LockMode`: JPA (`LockModeType`) and Hibernate (`LockMode`) already have types by that name.

### 2. Which reads lock (new R-EXE-14)

| Read | With a lock |
|---|---|
| `list(q)`, `list(q, Limit)`, `first(q)` | locks every row returned |
| `one(q)` | locks the row returned; with `SKIP_LOCKED`, `MQ2211` |
| `one(q, key)`, `byKeys(q, keys)` | locks every row returned |
| `page(q, PageSpec, …)`, `page(q, KeysetSpec)`, `count`, `stream`, `export` | `MQ2211` |
| `persist(…, returning)` | `MQ1809` (it maps from the entity in memory, so a lock would be ignored) |

**Refused reads.**
- **`one(q)` with `SKIP_LOCKED`.** `one(q)` reads `LIMIT 2` to check uniqueness. A locked second match would be
  invisible, so the `MQ2003` check would pass when it should fail (INV-5).
- **Paging.** PostgreSQL also locks the rows that `OFFSET` skips over. Under READ COMMITTED, `ORDER BY` with a locking
  clause can return rows out of order, which breaks a keyset cursor's monotonicity. With `SKIP_LOCKED`, a keyset
  cursor moves past locked rows for good, which is a silent skip.
- **`stream` and export.** They hold a cursor across user code, and export also spans transactions.

Relaxing a refusal later is compatible; the reverse is not.

**`SKIP_LOCKED` on `one(q, key)` and `byKeys`:** a key that is absent from the result means absent, filtered out, or
locked. `byKeys` orders each chunk's statement by primary key, so concurrent callers lock in the same order.

**Only the statement that returns the model's rows is locked.** The executor applies the lock after creating the
statement, and only in `list`, `first`, `one` and `byKeys`. It does not apply it in the shared `create`, which also
serves paging, `count`, primary-key-first and fetch plans. Fetch-plan statements (R-FCH-*) are not locked. Every
refusal runs at method entry, before a zero-limit short cut and before any statement.

**A locked read needs an active transaction** (`em.isJoinedToTransaction()`), else `MQ2102`. The provider would throw
`TransactionRequiredException` at the locked statement anyway. `MQ2102` gives one library code, checked before any
statement runs. The M18.2 TCK records, rather than asserts, what a read-only transaction does on each Tier-1 database.

**Database refusals pass through unchanged**: `PessimisticLockException` (a `NO_WAIT` conflict or a deadlock),
`LockTimeoutException`, and `QueryTimeoutException` (the factory's `queryTimeout`, R-EXE-11, still bounds the
statement, lock wait included). On PostgreSQL any of these aborts the whole transaction; the docs say so.

INV-1 gains: "a locking read takes row locks and changes no data."

### 3. Lock scope: the root table only

`createTupleQuery()` plus `multiselect` gives Hibernate no lock roots. Hibernate then renders a locking clause with no
`OF` list, and on PostgreSQL that locks every table in the statement. Worse, an outer join switches Hibernate to
follow-on locking, which locks only loaded entities. A projection loads none, so the read would run unlocked and
return normally. Therefore:

- **A locked query whose built statement has any join** (R-COL-*, including a filter on a joined column) is
  `MQ2211`; filter through `Filters.exists(...)` instead. The check runs on `JoinContext`, so it is pure JPA (INV-7).
- **A root entity mapped to more than one table** (`tablesOf`, R-VND-13: `JOINED` inheritance, secondary tables) is
  `MQ4010`.
- The Hibernate implementation always sets `hibernate.query.followOnLocking=false`, so any follow-on case left over
  fails loudly instead of reading unlocked.

Allowing joins later, where a provider renders `OF <root>`, stays compatible.

### 4. The provider surface (new R-VND-15)

```java
// com.rey.modelquery.jpa.spi.ProviderSupport
default Optional<LockSupport> locks() { return Optional.empty(); }

// com.rey.modelquery.jpa.spi.LockSupport — jakarta.persistence types only
boolean rendered(EntityManagerFactory emf, RowLock lock);   // would apply(...) render exactly this lock?
void apply(TypedQuery<?> query, RowLock lock);
```

This follows `inserts()` and `entityWrites()`, and the reporting idea of `doNothingRendered` (R-VND-14, D-117). Whether a
lock renders is a fact about the provider and its version, not the vendor:

- Hibernate 6.6 drops `NOWAIT` and `SKIP LOCKED` on H2, rendering a plain `FOR UPDATE` that blocks.
- Hibernate 7.x renders both for H2 2.2.220 and later, but only for `exclusive()`. `shared()` with `NO_WAIT` or
  `SKIP_LOCKED` renders a plain `FOR UPDATE` on H2 on both lines, so `rendered` is false and the read is refused with
  `MQ4010`. `rendered` therefore decides per strength × wait, not per wait alone (M18.1 spike).
- For an entity query Hibernate 7.x adds `OF <root alias>` on PostgreSQL and MySQL (not on H2); 6.6 renders no `OF`.
  The executor's column projections render no `OF` on either line (M18.2 snapshots, as §3 expects). Either way only
  the root table is locked, since a locked query reads its root alone (§3).

The Hibernate implementation answers `rendered` from the `Dialect`. Its `apply` sets `setLockMode` and the
lock-timeout hint (`0` or `-2`, Hibernate's values). It also sets `hibernate.query.followOnLocking=false` (§3).
`VendorProfile` gains nothing. The `-2` convention is Hibernate's (R-VND-12: no profile names a provider's hint), and
an overridable raw-`Query` hook on a profile would be the `TypedQuery` escape hatch left out of M18.

**`MQ4010`**, at execution, before any statement: no `locks()` is available (every lock refused, the same fail-safe as
D-117 and `MQ4009`), or `rendered` is false for this lock, or the root is on several tables. `MQ4010` names the
provider, the vendor and the lock.

### 5. Spring

No new methods. `findAll(q, Limit)`, `findOne`, `findFirst`, `findByKey` and `findAllByKeys` take the query and so take
its lock. `findPage` and `findKeysetPage` are `MQ2211`. Library exceptions pass through unchanged (D-50).

### 6. Delivery (slices, for `mvp-plan.md` once accepted)

1. **M18.1 core + spike:** `RowLock`, builder, `withLock`, `MQ1209`, the `MQ1205` extension. Also TCK SQL snapshots
   of what Hibernate 6.6 and 7.x render for `PESSIMISTIC_WRITE/READ` × wait × joined/unjoined on H2, PostgreSQL 17
   and MySQL 8.4, to confirm §1, §3 and §4 before executor code.
2. **M18.2 executor + provider:** `LockSupport` and its Hibernate implementation, the R-EXE-14 table, `MQ2102`,
   `MQ2211`, `MQ4010`, `MQ1809`. Two-connection TCK cases on PostgreSQL and MySQL: blocked, skipped, and a no-wait
   error. SQL snapshots on all three Tier-1 databases; the read-only-transaction cases.
3. **M18.3 Spring + docs:** the repository cases; `queries.md` §Row locks; a "claim the next job" recipe with
   `SKIP_LOCKED`, including the out-of-order caveat for `first`; the PostgreSQL transaction-abort and Spring
   read-only notes. Both tabs (CC-DOC-01).

### New ids

- R-QRY-17; AC-QRY-16: `RowLock` factories and `equals`; `withLock` (no `null`, `this` for an equal lock, keeps the
  fetch plan); `MQ1209` for a grouped lock at `build()` and in `withLock`; `MQ1205` for a customizer's `distinct` or
  `having` on a locked query.
- R-EXE-14; AC-EXE-16: each read in §2's table locks or refuses as stated, before any SQL. `MQ2102` outside a
  transaction. `MQ2211` for a joined query. Fetch-plan statements carry no lock. `byKeys` chunks are ordered by key.
- AC-EXE-17 (TCK, PostgreSQL and MySQL, two connections): a second `first` with `WAIT` blocks until the first
  commits; with `SKIP_LOCKED` it returns the next row; with `NO_WAIT` it throws `PessimisticLockException` or
  `LockTimeoutException`, as recorded per vendor.
- R-VND-15; AC-VND-13: `LockSupport.rendered` per dialect, on both Hibernate lines (H2 waits refused on 6.6 and
  allowed on 7.x); `MQ4010` without `locks()`, for an unrendered wait, and for a multi-table root;
  `followOnLocking=false` set.
- AC-PRF-10: the rendering table in §1, as SQL snapshots.
- D-127: "A row lock is part of the query definition (`RowLock`: exclusive or shared × wait). It is applied only to
  the statement that returns rows, of a query that reads its root table alone, through `ProviderSupport.locks()`.
  That SPI reports what it renders and disables follow-on locking; whether a lock renders is a provider fact, as in
  D-117. A lock that would be dropped, widened or deferred is refused: `MQ2211` for the read or the statement shape,
  `MQ4010` for the provider. Paging, `count`, `stream`, export, `persist` returning, grouped queries and `one(q)` with
  skip locked refuse one. A locked read needs a transaction (`MQ2102`)."
- `MQ1209` (definition), `MQ2102` (no transaction), `MQ2211` (read or shape), `MQ4010` (provider cannot render).

## Compatibility

Source and binary compatible: a new type, one builder method, two `ModelQuery` methods, and a `default` method on
`ProviderSupport`. A query without a lock behaves as before. Ships in **0.9.0**, `@Incubating`.

## Alternatives

- **Do nothing; hand-written JPQL for locked reads.** This is what LitePOS does today: a second definition per locked
  read.
- **A per-call argument (`executor.first(em, q, RowLock)`).** It doubles every read signature on the executor and the
  repository. The lock belongs to the read's intent as much as its filter does, and `withLock` gives the same
  flexibility from one definition.
- **`UPDATE` / `NO_KEY_UPDATE` / `SHARE` strengths** (as `litepos-gaps.md` sketched). On PostgreSQL, Hibernate renders
  `NO KEY UPDATE` for `PESSIMISTIC_WRITE` and offers no per-query true `FOR UPDATE`. `update()` would silently have
  been `NO KEY UPDATE`.
- **Builder `skipLocked()`/`noWait()` chaining.** `skipLocked().noWait()` would need a runtime error; `Wait` as an
  argument makes it unwritable.
- **`VendorProfile#applyLock(Query, RowLock)`.** It would name Hibernate's `-2` in a portable default (R-VND-12), and
  a user profile overriding it would be the raw-`Query` hook left out of M18.
- **Locking joined queries with Hibernate's rendering.** That locks every joined table on PostgreSQL, or reads
  unlocked through follow-on locking when there is an outer join.
- **A `TypedQueryCustomizer` hook for raw hints.** Left out (decided with the user, 2026-10-12): an escape hatch the
  1.0 freeze would have to keep, for no case M18 needs.
- **MariaDB TCK target.** Out of scope (D-81, Tier 2 after 1.0; decided with the user): MariaDB uses the `OTHER`
  profile, and `locks()` answers from Hibernate's MariaDB dialect.

## Unresolved questions

None blocking. The M18.1 spike confirms §1's rendering table and §3's follow-on behaviour as SQL snapshots on both
Hibernate lines; if a snapshot disagrees, the slice stops and the RFC is amended before M18.2.
