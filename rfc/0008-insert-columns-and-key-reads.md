# RFC 0008 — Insert columns and multi-key reads on a query model

- **Status:** accepted (after the 2026-10-09 `architect-review`; recorded as D-125 and D-126)
- **Affects:** `processor/30` (R-PROC-26 extended, new R-PROC-27 for `@ExcludeFromInserts`); `processor/31` (R-GEN-34:
  the `INSERT_COLUMNS` Javadoc reasons); `processor/32` (new `MQ3506`; `MQ3501` gains a message variant; `MQ3021`
  gains `@ExcludeFromInserts`); `model-query-annotations` (new `@ExcludeFromInserts`, `@Incubating`); `delivery/61`
  and the changelog (behaviour change); `engine/20` (new R-EXE-13, `byKeys`); `integration/50` (R-SPR-15 gains
  `findAllByKeys`); `api/12` R-FLT-09 (a third library-built key list); `api/11` (R-QRY-03 needs a primary key,
  R-QRY-10 and §6 list `byKeys` among the reads a fetch plan loads on); `api/15` R-FCH-05 ("per page" includes a
  `byKeys` result); `engine/20` R-EXE-05 and R-EXE-12 (`count`, `one(q)` and `one(q, key)` warn on an ignored `orderBy`); `reference/90` (new `MQ2005`; `MQ2003` and `MQ2203` gain `byKeys`); `docs/site` (`models.md` §One model for reading
  and creating, `queries.md`, `spring.md`)
- **Discussion:** to open

## Summary

`@QueryModel(generateInserts = true)` (D-123) writes every root column the model reads. This RFC narrows that set two
ways: the processor leaves out a column whose Hibernate `@Generated` says the **database** fills it on insert and
Hibernate itself never writes, and a new field annotation, `@ExcludeFromInserts`, leaves out any other column the
caller wants the database default for. Both are processor-only. It also adds the read side of `whereKeys`: the
executor's `byKeys(q, keys)` and the repository's `findAllByKeys(q, keys)` read the models of many keys at once,
keyed by key.

## Motivation

A query model's fields are chosen for reading. A screen that shows `createdAt` and `status` reads them, and with
`generateInserts` its `insert` also writes them: a field the caller left unset goes in as `NULL`, overriding
`created_at DEFAULT now()` or `status DEFAULT 'NEW'`, or failing a `NOT NULL` column that has a default. An
`@InsertModel` avoids this by not naming the column, which is the second model D-123 set out to remove. Today the only
way out is to drop `generateInserts` and keep both models. A downstream user reported exactly this.

Two cases differ:

- **The mapping says the database fills it and Hibernate never writes it**: `@Generated` on an insert event, neither
  `writable` nor with `sql`. Writing the column is wrong: under `persist` Hibernate leaves it out of its own INSERT,
  and a computed (`GENERATED ALWAYS AS`) column refuses a value. No annotation should be needed.
- **Only the DDL knows**: a plain `DEFAULT` clause (or `@ColumnDefault`, which affects DDL only). The processor cannot
  tell whether the caller means "default" or "write what I set", so the model must say.

## Design

### 1. Database-generated columns are left out (R-PROC-26)

R-PROC-26's list of columns left out without a diagnostic gains:

- a non-id root column whose attribute carries `org.hibernate.annotations.Generated` (not `jakarta.annotation.Generated`
  or `javax.annotation.processing.Generated`) with `writable = false`, an empty `sql`, and `INSERT` among its events:
  its `value` when present and not `INSERT` (Hibernate 6), otherwise its `event`.

The processor reads the annotation by its full name and its values with `Elements.getElementValuesWithDefaults`
(the annotation type is on the compile classpath, since the entity compiles against it), reading enum constants by
simple name; a missing `value` (Hibernate 7) counts as `INSERT`. When the annotation type doesn't resolve, the column
stays written. `orm.xml` is not seen.

**Every timestamp annotation stays written**, whatever its `source`. `@CreationTimestamp`, `@UpdateTimestamp` and
`@CurrentTimestamp` with `source = DB` (the default of `@CurrentTimestamp`) make Hibernate write the dialect's
`current_timestamp` in its own INSERT, so the DDL usually has no default; with `source = VM` Hibernate sets the value
in memory. A Hibernate SQM insert applies only the id generator and the version seed, so a column left out of
`insert` gets only the DDL default: leaving a timestamp out would write `NULL` or fail `NOT NULL`. A model that wants
the server's time excludes the field and sets it on the write (§2, AC-WRT-40).

The `INSERT_COLUMNS` Javadoc names each column left out with its reason, "filled by the database: `@Generated`".

An `@InsertModel` is unchanged: it names its columns, and naming a database-generated one stays the caller's choice.

### 2. `@ExcludeFromInserts` (new R-PROC-27)

```java
@QueryModel(root = OrderEntity.class, generateInserts = true)
public record OrderView(
        @PrimaryKey Long id,
        String customer,
        @ExcludeFromInserts String status,      // DEFAULT 'NEW'
        @ExcludeFromInserts Instant createdAt) { // DEFAULT now()
}
```

```java
/**
 * Leaves the column out of the inserts a {@code generateInserts} query model generates, so the database default
 * applies under {@code insert} and the no-arg constructor's value under {@code persist}. The model still reads it.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface ExcludeFromInserts {
}
```

It follows `@ExcludeFromDefaults` (one exclusion per generated set, field-level, `CLASS` retention, no elements). The
field stays a column of the query model and keeps its constant; it only leaves `INSERT_COLUMNS`, `insertFrom`,
`insert` and `persist`. No constant is added, so no name becomes reserved under `MQ3015`. The Javadoc reason is
"`@ExcludeFromInserts`".

An excluded field's constant is not a model column for AC-WRT-32, so the server-time recipe works:
`insert(rows).set(QOrderView.CREATED_AT, now)`.

**Under `persist`** an excluded column is written as the no-arg constructor leaves it (R-WRT-25), so
`status DEFAULT 'NEW'` gets `NULL` unless the entity has a field initializer or `@DynamicInsert`. The user guide says
so beside the example.

**`MQ3506`** (error):

- on a field of a `@QueryModel` without `generateInserts = true`, or of an `@UpdateModel` or `@InsertModel`: every
  insert-model field is a column of every row (R-WRT-25, as `@Transient` there is `MQ3502`), and an accepted no-op
  would read as "excluded" while `NULL` is written;
- on a field R-PROC-26 already leaves out (`@Join`, `@Child`, `@Computed`, `@Transient`, a to-one, the `@Version`,
  `insertable = false`, a generated id, or a `@Generated` column from §1): the annotation would do nothing, and a
  reader would think it does something.

A `@Selected` field carrying it is `MQ3021` (every other annotation of the library), not `MQ3506`; the `MQ3021` row and
AC-DIAG-10 add `@ExcludeFromInserts`. On a grouped model `MQ3505` stays the only insert check (R-PROC-26), so
`MQ3506`'s "already left out" case does not fire there.

Excluding an **assigned id** is `MQ3501` (the columns written don't cover the id), with its own message:
`TagView.code is @ExcludeFromInserts, but TagEntity's id 'code' is assigned, so generateInserts must write it`.
Excluding every writable column is the existing `MQ3505`.

### 3. Multi-key reads (new R-EXE-13)

Reads have `one(q, key)` and `findByKey(q, key)` for one key (R-EXE-12, R-SPR-15); writes have `whereKeys` for many
(R-WRT-08). A caller with many keys, typically ids collected from another result, writes an IN filter by hand and
reads with `Limit.unlimited()`, which neither chunks the list to the vendor's limits (`api/12` R-FLT-09 spreads only
library-built lists) nor says which keys were missing. The downstream user's `getSystemUserByIds` does exactly that.

```java
// ModelQueryExecutor<E>
@Incubating
<K, M> Map<K, M> byKeys(ModelQuery<E, K, M> q, Collection<? extends K> keys);

// ModelQueryRepository<E>
@Incubating
<K, M> Map<K, M> findAllByKeys(ModelQuery<E, K, M> q, Collection<? extends K> keys);
```

- Each key is converted as `whereKey` converts it and ANDed with `q`'s filter, as `one(q, key)` does; a filter that
  excludes a key's row leaves that key out of the map. `primaryKeyFirst` and `keyset()` are accepted as by
  `one(q, key)`.
- The map is unmodifiable and iterates in the order of the keys' first occurrence (stated in the Javadoc: Java 17 has
  no `SequencedMap`); a key with no row is absent.
- Rows are matched to keys by the converted attribute value (R-COL-11). Every distinct caller key whose value matched
  gets an entry, so keys a converter maps to one value share one model, read once (as `whereKeys` writes it once,
  R-WRT-08). A row whose key equals none of the requested values (a case-insensitive or padding collation, a
  `BigDecimal` scale) is `MQ2005`, naming the value, before any row is mapped. A composite component that is not an
  instance of its column's type is `IllegalArgumentException`.
- The keys are spread over statements as R-PAG-07 step 2 spreads its keys: each chunk at most the largest power of two
  within `maxInListSize()` and `maxBindParameters()` less the statement's own binds, one bind per key component;
  `primaryKeyFirstBatchSize` does not apply, as for fetch-plan rounds (R-FCH-05). `api/12` R-FLT-09 lists it as a
  third library-built key list. Every statement is built in `Phase.MODEL`, as `one(q, key)` is, so a customizer
  filters it as it filters `one`; `MODEL_BY_KEYS` would let a customizer that filters only `MODEL` pass rows it should
  not, since these keys come from the caller rather than a filtered step 1. Each key is read in exactly one statement,
  so chunking never repeats or skips a row; the statements see one snapshot only within one transaction at an
  isolation that gives one (`api/15`'s wording).
- Checks run in this order, all before the empty-keys shortcut: `q` and `keys` non-null (`NullPointerException`);
  `MQ2203` for a query without a primary key, a grouped one included; then every key
  converted (`IllegalArgumentException` for a `null` key or component, or a composite key with the wrong number of
  components). No keys then gives an empty map and runs no SQL. Otherwise every chunk is read, then checked for
  `MQ2003` and `MQ2005`, then mapped (`afterMap` once per row, R-QRY-05, also for a row two keys share); the fetch
  plan runs once over every row, in key order, so a child query batches across chunks. Memory is the whole result, as
  with `list(Limit.unlimited())`.
- `q`'s `orderBy` is ignored: no statement renders an `ORDER BY`, since chunks cannot be ordered across statements
  and the map is in key order anyway. So one definition serves `list` and `byKeys`. The call logs a `WARNING`,
  `<Model>: byKeys(query, keys) ignores the query's orderBy; the map is in key order`, once per `ModelQuery`.
  The signature takes no limit, offset or keyset page, so paging cannot be asked for.
- **`count`, `one(q)` and `one(q, key)` warn the same way** (amends R-EXE-05 and R-EXE-12). Each already ignores an
  `orderBy` without saying so: `count` drops it (R-EXE-05), and `one` keeps it in the SQL although it reads at most
  one row. Both `one` methods now drop it from the statement too. Each of the three logs `<Model>: <call> ignores the
  query's orderBy`, once per `ModelQuery` and call. Only a direct call logs: the count `page` runs for its total logs
  nothing, since `page` uses the order. `first` uses the order and is unchanged.
- A key matching two rows, through a non-id `@PrimaryKey` or a filter-only to-many join (the message then names the
  join and `Filters.exists`, as `one`'s does), is `MQ2003`, `<Model>: byKeys(query, keys) found more than one row for
  key <k>`.
- The key is the model's primary key, as for `one(q, key)`: the id, or a `@PrimaryKey` on a non-id unique column. A
  composite key is a `List<Object>` (R-GEN-34).
- Both methods are abstract and `@Incubating`, not defaults (R-REL-07): a default built on `list` would need the IN
  filter and the chunking this RFC moves into the executor. The only implementations are `DefaultModelQueryExecutor`
  and `ModelQueryRepositoryFragment`. `findAllByKeys` returns the executor's `byKeys`, as R-SPR-15's methods return
  theirs.
- Primary-key-first step 2's `readByKeys` skips an unmatched row silently, which is safe only because its keys come
  from the database; `byKeys` does not reuse it as it stands.

### New ids

- R-PROC-27; D-125: "`generateInserts` leaves out non-writable `@Generated` insert columns; `@ExcludeFromInserts`
  leaves out the rest; a misplaced or redundant one is `MQ3506`; no `@ExcludeFromChanges` (R-PROC-19)."
- D-126: "`byKeys`/`findAllByKeys` read many keys into an unmodifiable `Map<K, M>` in first-occurrence key order,
  matched by attribute value (an unmatched row `MQ2005`), chunked as R-PAG-07 step 2 without
  `primaryKeyFirstBatchSize`, in `Phase.MODEL`; an `orderBy` is ignored with a `WARNING`, as `count`, `one(q)` and
  `one(q, key)` now also warn."
- AC-PROC-21: `@ExcludeFromInserts` leaves the field out of the four insert members and keeps it in the query members.
- AC-PROC-22: left out, a plain `@Generated`; written, `@Generated(writable = true)`, `@Generated(sql = …)`,
  `@Generated(event = UPDATE)`, 6.x `@Generated(GenerationTime.UPDATE)` and `NEVER`, every timestamp annotation, and a
  `@Generated` whose type doesn't resolve; run on both the Hibernate 6.6 and 7.x classpaths.
- AC-GEN-29: the Javadoc reasons.
- AC-DIAG-13: the `MQ3506` cases, `MQ3501`'s new variant on an excluded assigned id, `MQ3505` when all are excluded,
  `MQ3021` on a `@Selected` field.
- AC-WRT-40: `@ExcludeFromInserts` plus `insert(rows).set(CREATED_AT, now)` writes the set value.
- R-EXE-13; AC-EXE-14: `byKeys` returns the map in first-occurrence key order with missing keys absent and duplicates
  read once, single and composite keys, more keys than the vendor's limits (several statements, one fetch-plan run),
  no keys (no SQL), an `orderBy` ignored with one `WARNING` and no `ORDER BY` rendered, `MQ2003` for a key matching
  two rows, `MQ2203` for a keyless query, with empty keys too, two keys converting to one value both present with `afterMap` run once,
  a `MODEL`-phase customizer applied, a configured `primaryKeyFirstBatchSize` not changing the chunks; a TCK case on
  MySQL and SQL Server where a case-insensitive string key gives `MQ2005`;
  AC-SPR-19: `findAllByKeys` returns the same.
- AC-EXE-15: `count`, `one(q)` and `one(q, key)` on an ordered query each log one `WARNING` per `ModelQuery`, the
  `one` statements render no `ORDER BY`, and `page`'s count and `first` log nothing.
- A TCK case on the Tier-1 databases inserting through a model with an excluded `DEFAULT` column, through both
  `insert` (the default read back) and `persist` (the constructor's value).

## Compatibility

Source and binary compatible for callers: one new annotation and two new methods. The methods are abstract on
interfaces a user does not implement (R-REL-07; the same as `one`, `first` and `findByKey` in D-123). Behaviour change, named in `delivery/61` and
the changelog: a 0.7.0 model whose entity has a non-writable `@Generated` insert column stops writing it, so under
`insert` a value the caller set on that column is dropped, as `persist` already dropped it; a computed
(`GENERATED ALWAYS AS`) column that failed loudly under 0.7.0 now works; `count`, `one(q)` and `one(q, key)` on an
ordered query log a `WARNING` they did not, and the `one` statements lose their `ORDER BY`. `generateInserts` and its generated members are
`@Incubating` (R-GEN-34). Ships as **0.8.0** (a new public annotation and new methods).

## Alternatives

- **Do nothing; document it.** Leaves the `NULL`-over-default trap in the one place D-123 promoted, and the only fix is
  the second model D-123 removed. Documenting it is part of this RFC anyway.
- **`skipNulls`: write `DEFAULT` for a `null` value, or skip `null` columns, at run time.** A `null` never means "skip"
  (P-3); the statement shape would vary per row, up to 2^n shapes for n nullable columns, breaking the one batched
  statement; `insertReturningKeys` returns keys in row order (R-WRT-33), which per-shape statements would scramble;
  SQLite takes no `DEFAULT` in a multi-row `VALUES`; and an intended `NULL` could no longer be written.
- **A run-time `InsertColumns#without(...)`.** The trap is in the generated default, so a run-time escape leaves it in
  place; INV-8 is already met through `InsertColumns.of`. It can be added later.
- **A list on the annotation, `@QueryModel(insertExcludes = {"status"})`.** Strings the compiler can't check, and a
  rename leaves a stale entry; a field annotation moves with its field.
- **Skip every `@ValueGenerationType` column, or DB-sourced timestamps.** Hibernate writes `current_timestamp` itself
  for a DB-sourced timestamp and sets a VM-sourced one in memory; an SQM insert does neither, so the column would be
  `NULL` (§1).
- **Honour `@ColumnDefault`.** It affects DDL only and says nothing about whether a caller wants to write the column;
  skipping it would make the column impossible to set.

- **`findAllByKeys` returning `List<M>`.** Missing keys would be silent and the order unclear; `Map<K, M>` states
  both, and `map.values()` is the list.
- **Accepting `q`'s `orderBy` and ordering within chunks.** Correct only below one chunk, so a result would change
  order once the key count crossed the vendor's limit.
- **Refusing `q`'s `orderBy` (`MQ2004`).** Safe to relax later, but a definition built for `list` would need an
  unordered twin, and nothing removes an order (`orderedBy(empty)` returns the definition unchanged, R-QRY-14).
  Decided with the user: ignore it and warn, as `count` and `one` now do.
- **Ignoring the `orderBy` silently**, as `count` and `one` did. A caller who expects the order would never learn it
  was dropped.
- **Overloading `one(q, Collection<K>)`.** A composite `K` is a `List<Object>`, itself a `Collection`, and a keyless
  query's `K` is `Object`, so the overload would pick a multi-key read nobody asked for; `Optional` and `Map` under
  one name also read badly. `map(q, keys)` reads as a transformation.
- **Translating `MQ2003` into Spring's `IncorrectResultSizeDataAccessException`** (asked for with this feature).
  Declined: `MQnnnn` exceptions pass through the repository unchanged (D-50, INV-8), and translating only there
  would make the same call throw different types through the executor and the repository. The Spring guide shows a
  user-side `PersistenceExceptionTranslator` keyed on `code()`.

## Decisions

- **No `@ExcludeFromChanges`, declined rather than deferred.** A change set writes only the fields set on it
  (R-PROC-19, D-124), so there is no default to protect. "Read but never patch" is authorization, which R-PROC-19 gives
  to one `@UpdateModel` per endpoint; an exclusion annotation would make `generateChanges` look safe to bind from a
  request. `updatable = false` is already `MQ1605` (D-70).
- **`@ExcludeFromInserts` on an `@InsertModel` or `@UpdateModel` field is `MQ3506`, not a no-op.** An accepted no-op
  reads as "excluded" while `NULL` is written, the trap this RFC removes (INV-5, P-2). Relaxing an error to a no-op
  later is compatible; the reverse breaks builds.
