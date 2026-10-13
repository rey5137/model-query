# RFC 0010 — Returning writes and conflict extensions

- **Status:** accepted (after the 2026-10-13 `architect-review` design study; recorded as D-129)
- **Affects:** `api/14` (new R-WRT-50 returning writes, R-WRT-51 conflict `setExpression`, R-WRT-52 arbiter
  predicate and `onConstraint`; R-WRT-34 amended; R-WRT-48's `MQ1809` widened; AC-WRT-07 gains a returning variant);
  `api/10` (new R-COL-28 `Expr.nextval`); `engine/20` (the returning branch at the statement seam); `vendor/40` (new
  R-VND-17 `ProviderSupport#returning()` and the profile facts); `vendor/41` (the Tier-1 returning and conflict
  table); `reference/90` (new `MQ4011`, `MQ1509`; `MQ1804`, `MQ1809` widened); `reference/92` (D-129);
  `delivery/61` (incubating list); `integration/50` (the repository's returning writes); `docs/site` (recipes)
- **Discussion:** to open

## Summary

A bulk update, a bulk delete and a values insert (with or without a conflict clause) can return the rows they wrote,
mapped through a query model: `updateReturning(update, q)`, `deleteReturning(delete, q)`, `insertReturning(insert,
q)`. The statement is still built by the engine as today; the provider takes its own translation of that statement
and adds the vendor's returning form (a `RETURNING` suffix on PostgreSQL and MariaDB, a `FINAL TABLE`/`OLD TABLE`
delta table on H2). Where the vendor or provider has none (MySQL, MariaDB update), the call is refused before any
statement; nothing is emulated. The conflict clause gains `setExpression` over the stored and incoming rows, an arbiter
predicate for partial unique indexes and `onConstraint` (PostgreSQL), and expressions gain `Expr.nextval`. All new
API is `@Incubating`. Ships in 0.9.0.

## Motivation

LitePOS has 13 call sites that write and read back in one statement (`litepos-gaps.md` row 3, part of 4): claiming a
job (`UPDATE … SET status = 'RUNNING' … RETURNING *`), deleting expired sessions and logging what went, and a counter
upsert (`INSERT … ON CONFLICT (key) DO UPDATE SET next_value = t.next_value + 1 RETURNING next_value`). Rows 5 and 8
add a partial-unique-index upsert (`ON CONFLICT (sku) WHERE status <> 'CLOSED'`) and a sequence-backed default. Each is
hand-written native SQL today, a second definition of the columns, converters and version handling the models already
own. The library already returns columns from `persist(…, returning)` (R-WRT-48), but only for entities it holds in
memory; nothing returns from a set-based write.

## Design

### 1. Returning writes (R-WRT-50)

```java
// ModelQueryExecutor<E>, ModelQueryRepository<E>; @Incubating
<R> List<R> updateReturning(ModelUpdate<E, ?> update, ModelQuery<E, ?, R> returning);
<R> List<R> deleteReturning(ModelDelete<E, ?> delete, ModelQuery<E, ?, R> returning);
<K, R> List<R> insertReturning(ValuesInsert<E, K, ?> insert, ModelQuery<E, ?, R> returning);
```

- The returned shape is a `ModelQuery`, not a `SelectSet`: R-WRT-48 already decided that a `SelectSet` alone carries
  neither the root nor the mapper. `E` fixes the root at compile time; a `Row`-typed query covers the untyped case.
- Columns are checked and mapped by `ReturningColumns` as `persist(…, returning)` does. `MQ1809` widens to the
  returning writes and also refuses a column on a table other than the written one (secondary or joined supertable,
  reported by the provider), `throughEntities()`, and `commitEachChunk()` (committed chunks would lose their rows).
  Plain chunking is allowed: R-WRT-17 never writes a row twice, so the chunks' rows are concatenated.
- Row order is unspecified; the result is held in memory (not an export, INV-4 does not apply). `expectVersion` with
  zero rows still throws `OptimisticLockException` (R-WRT-16). An upsert returns inserted and updated rows, not
  skipped ones (`DO NOTHING`).
- A returning write takes no lock and needs no paging rule. The claim-a-job pattern stays a locked read (M18) followed
  by a `whereKeys` write, or, where the vendor supports it, one `updateReturning` over a `whereKeys` sub-select.

**Rendering.** Every write already ends at one seam, `execute(Query, int)` in `DefaultModelQueryExecutor`, holding the
statement with its parameters bound (M19 `Expr.value` included), the timeout set, and write assignments, version
increment and EXISTS-wrapped filters already in it. The returning branch hands that `Query` to the provider instead of
calling `executeUpdate()`. The Hibernate implementation translates it through Hibernate's own mutation translator
(`SqmTranslatorFactory.createMutationTranslator` → `JdbcOperationQueryMutation`), applies the vendor's shape, prepares
it through the session's `JdbcCoordinator` (SQL logging and resource tracking unchanged), binds with Hibernate's
binders (converters and enums unchanged) and reads each column with its attribute's JDBC mapping. It calls the
`StatementInspector` and invalidates query spaces as Hibernate's own bulk path does. A mutation Hibernate runs as more
than one statement (JOINED inheritance, `@SecondaryTable`) is refused.

**SPI** (`jpa`, jakarta types only, INV-7; R-VND-17):

```java
// ProviderSupport
default Optional<ReturningSupport> returning() { return Optional.empty(); }

// spi
interface ReturningSupport {
    boolean rendered(EntityManagerFactory emf, Class<?> entity, ReturningShape shape);
    List<Object[]> rows(Query statement, Class<?> entity, List<String> attributes, ReturningShape shape);
}

// VendorProfile: a database fact, no SQL (R-VND-11)
default Optional<ReturningShape> returning(WriteStatement kind) { return Optional.empty(); } // SUFFIX | DELTA_TABLE
```

An empty profile fact, no `ReturningSupport`, or `rendered == false` is the new **`MQ4011`**, naming provider, vendor
and statement kind, at method entry before any statement. A third-party profile defaults to empty and fails safe.

| Vendor | update | delete | insert / upsert |
|---|---|---|---|
| PostgreSQL | suffix | suffix | suffix |
| H2 (Tier 1) | `FINAL TABLE` | `OLD TABLE` | `FINAL TABLE` (over Hibernate's `MERGE`: spike) |
| MariaDB (Tier 2) | `MQ4011` | suffix | suffix |
| MySQL | `MQ4011` | `MQ4011` | `MQ4011` |

### 2. Conflict `setExpression` (R-WRT-51)

```java
<C> Assigned<E, M> setExpression(ColumnField<?, E, C> column, ConflictExpression<C> expression);

@FunctionalInterface
interface ConflictExpression<C> {
    Expression<? extends C> apply(Path<C> stored, Path<C> incoming, CriteriaBuilder cb);
}
```

Mirrors `ModelUpdate.setExpression` with the incoming (`excluded`) row added. It maps onto Hibernate's public
`JpaConflictUpdateAction.set(Path, Expression)` and `getExcludedRoot()` on both lines, through the existing
`Assignments.expression` SPI. R-WRT-13 ordering, `MQ1609` on converted columns and R-WRT-34's column checks apply.

### 3. Arbiter predicate and `onConstraint` (R-WRT-52, R-WRT-34 amended)

```java
insert.onConflict(SKU).where(f -> f.ne(STATUS, Status.CLOSED)).doUpdate(…);   // ON CONFLICT (sku) WHERE status <> 'CLOSED'
insert.onConstraint("uq_item_sku").doNothing();                               // stage has no where(...)
```

- R-WRT-34 says "constraint names are not offered"; this RFC amends it. `onConstraint` uses Hibernate's public
  `conflictOnConstraint`; a profile fact (true on PostgreSQL only) refuses it elsewhere with `MQ1804`. The returned
  stage has no `where`, so `onConstraint(…).where` does not compile (PostgreSQL forbids it).
- The arbiter predicate is not in Hibernate's conflict model (its predicate is the `DO UPDATE` `WHERE`). It uses the
  translated-SQL path of §1: the provider inserts `WHERE <pred>` after `on conflict (…)`, and refuses with `MQ4011`
  unless it finds that anchor exactly once (INV-5). Values render as **literals** through M19's `ConstantRenderer`
  (D-128): a bind parameter keeps PostgreSQL from inferring the partial index. Root columns and plain comparisons
  only; `Expr.value`, `exists` and sub-selects are `MQ1804` at `build()`, an unrenderable value `MQ1508`. Refused on
  every vendor but PostgreSQL (`MQ1804`, profile fact).

### 4. `Expr.nextval` (R-COL-28)

```java
static <M> ExpressionField<M, Long> nextval(String sequence);
```

Not a `VendorProfile.sequenceNextValue(…)` rendering method (the plan's sketch): a profile renders no SQL (R-VND-11).
Instead a profile fact `sequences()` (false on MySQL) plus a provider renderer reached through a `JoinContext` hook, as
D-128 did for constants; the Hibernate one renders `dialect.getSequenceSupport().getSelectSequenceNextValString(seq)`
through `HibernateCriteriaBuilder.sql`. Usable in a select, an update assignment, an insert value and a conflict
assignment; refused in a filter, order or group, for a name that is not an identifier, and on a vendor or provider
without sequences (new **`MQ1509`**). The Javadoc states that a value is consumed even when a conflict skips the row,
and twice by `one(q)`'s `LIMIT 2`.

### New ids

R-WRT-50, R-WRT-51, R-WRT-52, R-COL-28, R-VND-17; AC-WRT-42 (update/delete returning per Tier-1 vendor, refusals),
AC-WRT-43 (insert and counter upsert returning), AC-WRT-44 (conflict `setExpression`), AC-WRT-45 (arbiter predicate
and `onConstraint`), AC-COL-33 (`nextval`), AC-VND-14 (`ReturningSupport.rendered` per dialect and Hibernate line);
`MQ4011`, `MQ1509`.

## Compatibility

Additive and `@Incubating`. `ProviderSupport#returning()` and the profile facts are default methods returning empty
or false, so third-party profiles and providers compile and refuse.

### Hibernate internals

The Hibernate implementation of §1 and §3's arbiter predicate uses internal translator types
(`org.hibernate.query.sqm.internal` and friends, which moved between 6.6 and 7.x). A future Hibernate may change them
again; these rules make such a change fail early, loudly and safely, never as wrong SQL:

1. **One adapter per Hibernate line.** The internal calls live in one small class per line (e.g.
   `Hibernate6Mutations`, `Hibernate7Mutations`) behind a package-private interface: given a `Query`, return the SQL
   text and its binder. An ArchUnit rule forbids `org.hibernate..internal..` imports anywhere else in
   `model-query-hibernate`.
2. **Chosen by probing, not by version string.** At bootstrap the module checks that the adapter's classes and
   methods exist and selects the first that matches. If none does, `ReturningSupport.rendered` is `false` and every
   returning write and arbiter predicate is `MQ4011`; plain writes, inserts and upserts are unaffected.
3. **A declared tested range.** Each adapter names the Hibernate versions it was tested on. Outside that range it
   still runs if the probe passes, and logs one `WARN` naming the version (as R-VND-07 logs the resolved profile).
4. **Checked on first use of each statement.** The translated SQL must start with the expected `update`, `delete`,
   `insert` or `merge` keyword, the number of placeholders must equal the number of binders, and the arbiter anchor
   must occur exactly once. Any mismatch is `MQ4011` before execution (INV-5).
5. **Canary builds.** A non-blocking nightly CI job runs the TCK against Hibernate's newest 7.x release and its next
   snapshot, so a break shows in the Tier-1 SQL snapshots before users meet it. Added in M20.1.

If a line cannot be translated cleanly, it reports `rendered == false` rather than falling back. Whether the adapters
stay or are replaced (for example by a supported Hibernate API, should one appear) is reviewed at M16, before 1.0;
the feature stays `@Incubating` until then.

## Alternatives

- **A library SQL renderer for writes.** Re-implements every filter, `Expr`, constant, bind, naming strategy,
  converter and version rule, and cannot render the user's Criteria lambdas (`setExpression`, `customize`) at all. A
  second SQL engine that drifts from the read path (P-5, AC-WRT-07). Rejected.
- **Emulation: locked key select, `UPDATE … WHERE pk IN`, re-read by key.** Exact only for update and only under a
  lock; delete would need a read before the write; an upsert cannot tell inserted rows from skipped ones; and it
  changes statement count and locking silently (R-VND-10). Shipped instead as a recipe built from M18's lock,
  `whereKeys` and `byKeys`, which is also the MySQL claim-a-job answer.
- **A `SelectSet` parameter.** R-WRT-48.
- **Do nothing.** LitePOS keeps 13 native statements.

## Unresolved questions

- The M20.1 spike decides whether the translation works cleanly on Hibernate 6.6.58 and 7.4.11 (bindings,
  `StatementInspector`, query-space invalidation, multi-table detection), and confirms H2's delta-table forms
  (including over `MERGE`). If a snapshot disagrees with §1's table, the slice stops and the RFC is amended.
- Q-15 (not blocking): whether an explicit opt-in MySQL emulation is ever wanted.
- Decided at acceptance: `Expr.nextval` stays in M20 (LitePOS row 8 needs it with the counter upsert), and
  `onConstraint` amends R-WRT-34.
