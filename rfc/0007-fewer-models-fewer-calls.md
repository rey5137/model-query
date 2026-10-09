# RFC 0007 — Fewer models, fewer calls

- **Status:** accepted (after the 2026-10-09 `architect-review`; recorded as D-123)
- **Affects:** `processor/30` (`@QueryModel.generateInserts`, new R-PROC-26); `processor/31` (new R-GEN-34, AC-GEN-26..);
  `processor/32` (new `MQ3505`; `MQ3501` and `MQ3504` extended to `generateInserts`); `api/11` (R-QRY-03: `one(q, key)`
  and an unordered `first` need a key); `engine/20` (new R-EXE-12 for `one`, `first` and `one(q, key)`, new `MQ2003`);
  `reference/90` (`MQ2003`, the `MQ2203` row, the `MQ3501`–`MQ3505` range); `reference/91`; `integration/50` (new
  R-SPR-15 and AC-SPR-18: `findOne`, `findFirst`, `findByKey`); `delivery/61` (R-REL-07); `reference/92` (new D-123).
- **Discussion:** to open
- **Target:** 0.7.0. One annotation member and six methods, all `@Incubating`. It ships with the `Date` column fix
  (D-122), which stays under `[Unreleased]` rather than going out as 0.6.1.

## Summary

Two small additions that each remove boilerplate an application writes today:

- **A.** `@QueryModel(generateInserts = true)` generates the members an `@InsertModel` would (`INSERT_COLUMNS`,
  `insert`, `insertFrom`, `persist`) on the query model itself, the way `generateChanges = true` already generates an
  update model's. A screen that reads and creates the same row needs one model, not two near-identical records.
- **B.** The executor gains `one(q)`, `first(q)` and `one(q, key)`, and the Spring repository `findOne`, `findFirst`
  and `findByKey`, so reading a single row is not `list(q, Limit.of(1))` plus an emptiness check.

## Motivation

### A. A second model that repeats the first

A type carries at most one of `@QueryModel`, `@UpdateModel` and `@InsertModel` (R-PROC-24, `MQ3503`), since each
generates `Q<Model>`. An application whose create endpoint takes the same shape its read endpoint returns keeps two
records:

```java
@QueryModel(root = TagEntity.class)
public record TagView(@PrimaryKey String code, String label, int rank) {}

@InsertModel(root = TagEntity.class)
public record NewTag(@PrimaryKey String code, String label, int rank) {}   // the same three fields
```

They drift: a column added to the view is forgotten on the insert, and nothing fails until a row is written without
it. Updates already avoid this through `generateChanges = true` (R-PROC-19); inserts have no equivalent.

### B. Reading one row

`list` is the only read that returns rows without paging, so every single-row read is written by hand:

```java
List<OrderView> rows = executor.list(QOrderView.query().where(f -> f.eq(QOrderView.ID, id)).build(), Limit.of(2));
if (rows.size() > 1) throw new IllegalStateException("two orders with id " + id);
Optional<OrderView> order = rows.stream().findFirst();
```

Each copy chooses its own limit (1 hides a duplicate, 2 catches it), its own exception, and whether "first" means
anything without an `orderBy`. The query already knows its primary key (R-QRY-03), so a lookup by key need not
restate the filter.

## Design

### A. `@QueryModel(generateInserts = true)`

```java
@QueryModel(root = TagEntity.class, generateInserts = true)
public record TagView(@PrimaryKey String code, String label, int rank) {}

// generated on QTagView, as for an @InsertModel (R-GEN-28, `processor/31` §7), each member @Incubating:
public static final InsertColumns<TagView, TagEntity> INSERT_COLUMNS = …;   // code, label, rank
public static ValuesInsert.Rows<TagEntity, String, TagView> insert(List<? extends TagView> rows) { … }
public static <S> ModelInsert.SelectStart<TagEntity, TagView> insertFrom(TableField<S, S> sourceRoot) { … }
public static ModelPersist<TagEntity, String, TagView> persist(TagView row) { … }
```

**R-PROC-26** (new) `generateInserts = true` writes the query model's **root columns**: fields whose attribute path is
a root attribute or a dotted embedded path in it. It leaves out, without a diagnostic:

- `@Join` fields (another table), `@Child`, `@Computed`, `@Selected` and `@Transient` fields, and filter-only columns;
- a to-one column (the whole entity, `MQ3016`): a query model cannot name a foreign key as a scalar (`MQ3002`, D-44),
  so `generateInserts` writes no foreign key, and a model that must set one keeps its own `@InsertModel`;
- the id when it carries `@GeneratedValue` or a generator annotation, as an insert model must (R-PROC-23);
- a `@Version` column, and one an insert cannot write (`insertable = false`), which an `@InsertModel` would refuse
  (`MQ3303`, `MQ3304`). This is unlike `generateChanges`, which keeps them and relies on `MQ1605` (D-70).

The generated `INSERT_COLUMNS` Javadoc lists every field left out and why, so a missing column is visible in the IDE.

**Keys.** `addKey` marks the fields that map to the root's id; `insert` and `persist` are typed by the root's id type
(the `@IdClass` for a composite id), as on an insert model. A `@PrimaryKey` on a non-id unique column (R-GEN-22) is
written with `add`, and `MQ3501`'s "`@PrimaryKey` on another attribute" clause does not apply. `MQ3501` fires only
when the root's id is assigned and the model's `@PrimaryKey` fields do not cover all of it. `MQ3504` warns when the
root shows no id type. Because `KEY` follows `@PrimaryKey` (`List<Object>` for a composite key) and `persist` follows
the id, `one(q, executor.persist(...))` does not compile for a composite key; the user guide says so.

`INSERT_COLUMNS` joins `ROOT` as a reserved constant name under this flag (`MQ3015`). `ROOT` and the root column
constants are the same on both roles, so nothing else clashes.

**`MQ3505`** (new, error) `generateInserts = true` on a model with `@Aggregate` or `@GroupBy` fields or
`singleGroup = true` (a grouped row is not an entity row), or on one with no writable root column:
`TagTotals: generateInserts needs an ungrouped model with at least one root column it can write`.

`MQ3503` is unchanged: `@QueryModel` and `@InsertModel` on one type stay an error, and the user guide points at the flag.
`generateChanges` and `generateInserts` combine freely. As with `generateChanges`, the user guide says an endpoint that
binds the model from a request can write every listed column, so a public create endpoint keeps its own
`@InsertModel`.

### B. Single-row reads

```java
public interface ModelQueryExecutor<E> {
    @Incubating <M> Optional<M> one(ModelQuery<E, ?, M> q);              // MQ2003 on a second row
    @Incubating <M> Optional<M> first(ModelQuery<E, ?, M> q);            // stable order: orderBy, then the key
    @Incubating <K, M> Optional<M> one(ModelQuery<E, K, M> q, K key);    // q's filter AND primary key = key
}
```

**R-EXE-12** (new, `engine/20`)

- `one(q)` renders the statement `list` would, with a limit of 2. A second row throws `MQ2003`
  (`OrderView: one(query) found more than one row`) before the fetch plan runs; the fetch plan then runs once, on the
  single row left, so an enricher never sees the extra row. `one` counts rows as `list` returns them: `list` does not
  deduplicate, so a predicate-only to-many join (R-EXE-04, R-PAG-02) can repeat the root row and give `MQ2003`. The
  message then names the join and points at `Filters.exists`. A distinct-key read may come later; turning that
  exception into a result is a compatible change.
- `first(q)` renders with a limit of 1 and a stable order, as R-PAG-01 does for paging: `q`'s `orderBy`, then the
  primary key (or, for a grouped query, its group keys) wherever the `orderBy` does not already cover it. With no
  `orderBy`, that is the key ascending. The implicit key columns sort `nullsLast` explicitly (R-COL-12), since a
  non-id `@PrimaryKey` may be NULL and vendors place NULL differently (INV-6). A query with neither an `orderBy` nor
  a key throws `MQ2203`, as an offset export does.
- `one(q, key)` adds `primary key = key` to `q`'s filter, converting each component as `whereKey` does, and behaves
  as `one(q)`. A `q` that already filters on its key is accepted: the filters AND together, and contradictory ones
  give empty. A query without a primary key throws `MQ2203`. A `null` key, a `null` component, or a composite key
  with the wrong number of components throws `IllegalArgumentException`. A second row is possible only for a
  `@PrimaryKey` that is not the id (a unique column on a view), and is `MQ2003` there too. A keyless query has
  `K = Object`, and a grouped query keeps the builder's `K` but drops its key, so in both any key compiles and fails
  at run time with `MQ2203`; the Javadoc says so.
- All three accept fetch plans and customizers as `list` does. `primaryKeyFirst(...)` applies only past its offset
  threshold, so at offset 0 it has no effect. The SQL is `list`'s with a limit (and, for `first`, the order above), so
  `one` and `first` add no vendor surface.

`Optional` rather than a nullable return: the caller has to decide what absence means, and Spring Data's `findById`
already returns one.

**R-SPR-15** (new; adds to the `ModelQueryRepository` methods in `integration/50` §1)

```java
@Incubating <M> Optional<M> findOne(ModelQuery<E, ?, M> q);
@Incubating <M> Optional<M> findFirst(ModelQuery<E, ?, M> q);
@Incubating <K, M> Optional<M> findByKey(ModelQuery<E, K, M> q, K key);
```

Each delegates to the executor method and adds nothing (R-SPR-01). `findOne(ModelQuery)` overloads Spring Data's
`findOne(Example)` and `findOne(Specification)`, which a repository may also extend. The erasures differ and
`ModelQuery` is a final class, so each call resolves to one method (a lambda still goes to `Specification`); only a
bare `null` is ambiguous. Fragment methods are not derived queries, so `findFirst` and `findByKey` are not parsed as
query names. AC-SPR-18 pins a repository that extends `JpaRepository`, `JpaSpecificationExecutor`,
`QueryByExampleExecutor` and `ModelQueryRepository` and routes each `findOne` to the right one.

## Compatibility

Additive for callers. `@QueryModel` gains a member with a default, so existing models compile unchanged and generate
the same code. `ModelQueryExecutor` and `ModelQueryRepository` gain abstract methods, as R-REL-07 and D-110 allow for
`@Incubating` interfaces with one library implementation. They are not default methods: a default built on `list`
would run the fetch plan on the extra row, and a repository default cannot reach the executor. A class that
implements `ModelQueryExecutor` itself (a decorator or test double) is source-incompatible and must add the three
methods; one compiled before 0.7.0 throws `AbstractMethodError` only when a new method is called. New codes `MQ2003`
and `MQ3505`; `MQ2203` gains triggers. Since 0.7.0.

## Alternatives

- **Allow `@QueryModel` and `@InsertModel` together.** Both generate `Q<Model>`; merging two annotations' generated
  members would need a rule for each clash (`ROOT`, column constants typed for two roles). A flag on the one annotation
  that owns the class has no clash to resolve, and copies `generateChanges`.
- **Write foreign keys from a query model.** A query model cannot name one as a scalar (`MQ3002`, D-44), and its
  to-one column is the whole entity. Lifting that would change how query models read, so a model that sets a foreign
  key keeps an `@InsertModel`.
- **`first(q)` in the database's natural order when there is no `orderBy`.** It returns a different row across vendors
  and plans, which is the bug this method exists to remove. Rejected by the user (2026-10-09).
- **`first(q)` with an `orderBy` and no key tie-breaker, as `list`.** Ties then pick a different row per vendor.
  Adding the tie-breaker later would change results; dropping a documented one would be breaking. It costs one
  `ORDER BY` column.
- **`one(q)` with a limit of 1.** Cheaper by one row, but it hides the duplicate the caller asked to be told about.
- **`one(q)` deduplicating rows by key.** At a limit of 2, `[A, A]` would hide a real second row B: a silently wrong
  answer (INV-5).
- **A nullable return, or `getOne` that throws on no row.** `Optional` makes absence explicit; a throwing variant is
  `one(q).orElseThrow()`.
- **Do nothing.** Each application keeps two models per writable shape and its own single-row helpers.

## Unresolved questions

None. The draft's three were settled in the architect review (2026-10-09): `first` appends the key as a
tie-breaker; `one(q, key)` accepts a `q` that already filters on its key; a dotted `@Column` across an association is
already `MQ3001` (R-PROC-06), so an association-path root column cannot arise.
