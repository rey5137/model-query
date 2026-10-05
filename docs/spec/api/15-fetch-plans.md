# 15 — Fetch Plans

**Covers:** `FetchPlan`, child models loaded by the library (`@Child`, `ChildField`), plans through a `@Join`
(`JoinField`), and per-page enrichers for fields the caller fills.
**Read when:** a model carries data its own row cannot select: a to-many child, a to-one child by key, or a value
looked up outside the query.
**Owns:** `R-FCH-*`, `AC-FCH-*`. The executor's page loops are `engine/20..21`; the generated constants are
`processor/31`; the codes are `reference/90`.

---

## 1. Shape

```java
@QueryModel(root = LenderEntity.class)
public record Lender(
        @PrimaryKey Integer id,
        String name,
        @Child(foreignKey = "lender.id") List<LenderSpiUrlConfig> spiUrlConfigs) {}   // to-many, on the child's FK

@QueryModel(root = LoanEntity.class)
public record Loan(
        @PrimaryKey Integer id,
        Integer userTypeId,
        Integer userId,
        @Join Optional<Lender> lender,
        @Transient Optional<UserProfile> userProfile) {}                               // filled by the caller

static final FetchPlan<LenderSpiUrlConfig> SPI = FetchPlan.of(QLenderSpiUrlConfig.ALL);
static final FetchPlan<Lender> LENDER = FetchPlan.of(QLender.ALL)
        .child(QLender.SPI_URL_CONFIGS, SPI, c -> c.orderBy(QLenderSpiUrlConfig.NAME.asc()));

FetchPlan<Loan> loan = FetchPlan.of(QLoan.DEFAULT)
        .join(QLoan.LENDER_JOIN, LENDER)               // the same plan, re-rooted under the join
        .enrich(Enricher.byKey(
                l -> new UserKey(l.userTypeId(), l.userId()),
                keys -> profiles.find(keys, fields),   // caller code: one lookup per page
                (l, p) -> l.withUserProfile(Optional.of(p)),
                QLoan.USER_TYPE_ID, QLoan.USER_ID));

ModelQuery<LoanEntity, Integer, Loan> q = QLoan.query().fetch(loan).where(f -> ...).build();
ModelPage<Loan> page = loanRepository.findPage(q, pageable, CountMode.COUNT);   // children and profiles filled
ModelPage<Loan> other = loanRepository.findPage(q.withFetch(exportPlan), pageable, CountMode.NO_COUNT);
```

**R-FCH-01** A `FetchPlan<M>` is immutable (INV-9). It holds a `SelectSet<M>`, the children to load, the plans of
`@Join`ed models and the enrichers, each in the order added. `child`, `join` and `enrich` return copies. A plan nests to
any depth: a child's or join's plan has its own children, joins and enrichers. It is built bottom-up from immutable
parts, so it cannot contain itself; a self-referencing model (a tree) nests as deep as its plans are written. Naming
the same child or join twice in one plan throws `MQ1703`.

**R-FCH-02** `ModelQuery.Builder.fetch(FetchPlan<M>)` selects the plan's `SelectSet`, with its join plans' re-rooted
under their joins (R-FCH-07), and attaches the plan. `fetch` and `select` replace each other, last call wins as a
whole: `select` after `fetch` drops the plan, and logs a `WARNING` naming the model that it did. `build()` with
neither throws `MQ1202`. The query also selects every column the plan needs: each child's parent-side key and each
enricher's declared columns, recursively through joins (R-FCH-07). Those columns count as selected for every rule:
presence keys (R-QRY-04, `MQ1409`), phase consistency (R-QRY-09), to-many joins (R-PAG-13), grouping (R-AGG-08,
`MQ1401`) and counting (R-EXE-04). One that is read through a to-many join throws `MQ1702` on first execution, checked
once per `ModelQuery` as the phases are (D-21), since `build()` has no metamodel to tell a to-many join from a to-one.
`count`, which loads no children and runs no enricher, logs that check's failure as a `WARNING` and counts; the failure
is not remembered, so the next call that returns models throws `MQ1702`.
`select()` returns the selection without them, as it does without the primary key, so `orderedBy` cannot sort on
them; a join plan's re-rooted selection is part of `select()`. They stay filled on the returned models.

**R-FCH-13** `ModelQuery.withFetch(FetchPlan<M>)` returns a copy with the plan's selection and plan: a new definition
(D-21), checked as `build()` checks one. A (query, plan) pair that passed is remembered, by identity, in a static set
weak on both, not on the query (CC-IMM-01): a later `withFetch` of the pair returns the copy
already built, without the checks and the `DEBUG` build log, so a constant plan is checked once per query. The copy for a repeated (query, plan) pair is the same instance, so
D-21's first-run check runs once. A pair that threw is checked again. An
`orderedBy` copy keeps the plan. Use it when the plan depends on the call, such as an export's chosen columns,
or holds a bean the query definition must not.

## 2. Children

**R-FCH-03** `@Child` marks a field of a `@QueryModel` filled from another `@QueryModel`'s rows. `key`, an attribute
path on the parent's root (default: its `@PrimaryKey` attribute), equals `foreignKey`, an attribute path on the child's
root (default: its `@PrimaryKey` attribute); both are validated against the entities, need not be model fields, and are
declared `String[]` but take one path in 0.2.0 (composite keys are `MQ3404`). The two attribute types are equal and not
arrays (INV-3, R-QRY-12). `foreignKey` may cross a collection, a many-to-many seen from the child's side (one child
row per parent it belongs to); `key` may not, since the parent would get a row per element (`MQ3402`, D-99). A
many-to-many mapped only on the parent's side uses `through` instead (R-FCH-14). The field is `List<C>` (to-many: `foreignKey` required, and `C` has a `@PrimaryKey`) or
`Optional<C>` (to-one). The processor generates a `ChildField<M, C>` per `@Child` that returns a copy of the parent with
the field set for a record (its canonical constructor), and the same instance with the field set for a class (its
setter), and refers to the child's generated class lazily,
so a `@Child` and a back-`@Join` between two models initialise in either order. A mapped row holds `List.of()` or
`Optional.empty()` until its child is loaded.

**R-FCH-04** `child(ChildField<M, C>, FetchPlan<C>)` loads a child; one not in the plan stays empty. An optional
`ChildQuery` adds child filters (`where`), an order (`orderBy`, closed by the child's primary key; the primary key alone
by default) and `maxPerParent` (R-FCH-11; below 1 it throws `MQ2001`). Child rows are deduplicated on the child's
primary key per parent key, since a child filter through a to-many join can repeat them, while a many-to-many child
belongs to several parents (D-99). A to-one child that finds two distinct rows
for one key throws `MQ2601` naming the child field. A to-one child without a `@PrimaryKey` is not deduplicated, so one
reached through a to-many path (a child filter or `foreignKey` crossing a collection) throws `MQ2601` on the repeated
rows unless it has a `@PrimaryKey`.

**R-FCH-05** Keys match on attribute values before any converter, as primary keys do (R-COL-11): the parent's key is
read from its row when it is mapped, the child's `foreignKey` from the child's row, and the `IN` binds those values.
Per page, the distinct non-null parent keys are split into rounds of at most the largest power of two within the
vendor's limits (R-PAG-07, D-32; `primaryKeyFirstBatchSize` does not apply), each round one child query
`where foreignKey IN keys` with the `ChildQuery` filters and order. A child row whose key equals none of its round's keys
(a case-insensitive or padding collation) throws `MQ2604` naming the field and the value. The child plan then runs once
over all the page's child rows (its children, joins and enrichers), and the children are grouped by key and set on each
parent in child order. A page with no keys runs no child query.

**R-FCH-06** A child query runs on the parent's `EntityManager`, inside the caller's transaction if any, through an
executor for the child's root entity (rooted at the parent's for `through`) with the parent executor's
`ModelQueryConfig`. It reads no entity (INV-2). Without a transaction (a Spring repository call outside one), a page and
its children are separate reads, as R-PAG-07's two steps are; run in a read-only transaction for one snapshot.

**R-FCH-14** `@Child(through = "path")` loads a child the parent's root reaches through an association path ending at
the child's root entity, typically a unidirectional `@ManyToMany` whose target has no way back. The child query is
rooted at the parent's root entity and joined INNER along `through`; the child model's selection, `@Join`s, filters
(`exists` and `add` included), order and customizer resolve against a `JoinContext` whose root is that join, so the
child's generated columns serve unchanged, and the parent's `key`, which must be its root's `@Id`, is read on the root
as the raw key (D-100). A customizer must resolve paths through its `JoinContext`; `query.getRoots()` holds the
parent's entity. `through` excludes `foreignKey` (`MQ3401`). A `through` path that crosses a non-association or an
embedded value, or ends at another type than the child's root, a `key` that is not the parent root's single `@Id`, or a
grouped child model, is `MQ3406`. Grouping, dedupe, bounds and rounds are as R-FCH-04/-05/-11.

## 3. Joins

**R-FCH-07** `join(JoinField<M, N>, FetchPlan<N>)` applies a nested plan to the models a `@Join` produced. The nested
plan's selection and needed columns are re-rooted under the join, as the generated join constants are, and added to the
parent's selection, so one plan serves on its own and nested. A join plan whose join ends up with no column of the
query's selection at or below it, at any depth, throws `MQ1701`; one whose nested plan selects an `AggregateField` or an
`ExpressionField`, which cannot be re-rooted under a join, throws `MQ1705`. Both are checked at `build()` and `withFetch`. The processor
generates a `JoinField<M, N>` per `@Join`, carrying the join's `TableField` and returning a parent copy with the nested
model replaced; an empty `Optional` (a LEFT join that found nothing) is skipped. An INNER `@Join` narrows the rows
exactly as selecting it does.

## 4. Enrichers

**R-FCH-08** An `Enricher<M>` is caller code run once per page, declaring the columns it reads (selected per
R-FCH-02). `Enricher.byKey(key, lookup, with, columns...)` reads a key per model, looks the distinct keys up in one call
and copies each model with its value (a model whose key is absent or `null` is left as is), so size and order cannot
change; it is the one-key case of `byKeys` (R-FCH-15), and a lookup returning `null` throws `MQ2606`. `Enricher.of(page -> ..., columns...)` takes the page and returns it filled, one model per position, in the page's
order: position `i` of the result replaces position `i` of the page (a plan serves a query alone and nested, and a
nested model is put back by position, D-102). A result of another size, or with a `null` element, throws `MQ2602`. Exceptions propagate unwrapped. Within a plan,
children, joins and enrichers run in R-FCH-17's order; a nested plan's enrichers run before the outer plan's, and a
join plan's enricher gets one entry per present nested model, duplicates included. The library never
fills a `@Transient` field. `afterMap` and the mapper's finisher run per row before any plan, so they cannot see
children (R-QRY-05).

**R-FCH-15** *(D-114)* **An enricher over several keys per model.** `byKeys` routes each key's value to its own
field:

```java
public static <M, K, V> Enricher.Keys<M, K, V> byKeys(Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup);

public static final class Keys<M, K, V> {                  // @Incubating; immutable, each call returns a copy
    public Keys<M, K, V> key(Function<? super M, ? extends K> key, BiFunction<? super M, ? super V, ? extends M> with);
    public Keys<M, K, V> batchSize(int maxKeysPerLookup);  // at least 1, else MQ1707; unset = one lookup per run
    @SafeVarargs public final Enricher<M> reading(ColumnField<M, ?, ?>... columns);   // MQ1706 with no key
}
```

```java
Enricher<PaymentOrderView> actors = Enricher.<PaymentOrderView, ActorKey, Profile>byKeys(
                keys -> profiles.find(keys, fields))
        .key(PaymentOrderView::payerKey, PaymentOrderView::withPayer)
        .key(PaymentOrderView::payeeKey, PaymentOrderView::withPayee)
        .key(PaymentOrderView::initiatorKey, PaymentOrderView::withInitiator)
        .key(PaymentOrderView::requestorKey, PaymentOrderView::withRequestor)   // null when absent: skipped
        .batchSize(1000)
        .reading(QPaymentOrderView.PAYER_USER_TYPE, QPaymentOrderView.PAYER_USER_ID, /* … all 8 */);
```

Java cannot infer `M` through a builder chain of implicit lambdas, so `byKeys` takes explicit type witnesses.

- For each model in page order and each key in the order declared, a `null` key is skipped and never looked up.
- The distinct non-null keys across models and keys go to **one** lookup call per run (a page, or an export batch,
  R-FCH-09), as an unmodifiable set in first-seen order. With `batchSize(n)`, they go in consecutive chunks of at most
  `n` keys in that order, one call per chunk, one after another on the calling thread; a key is in exactly one chunk,
  and only the values for a chunk's own keys are used.
- Each value found is set through its key's `with`, in key order; a value shared by several keys or models is passed to
  each. An absent key, or a `null` map value, leaves the model as is. Size and order cannot change.
- A lookup returning `null` throws `MQ2606`; `reading` with no `key` throws `MQ1706`, and `batchSize` below 1
  throws `MQ1707`, both at definition. Exceptions from the lookup propagate unwrapped.

**R-FCH-16** *(D-114)* **The library chunks keys only by `batchSize`, and never partitions them.** A lookup over
several sources (one datasource per user type, say) splits the keys of its call itself. With `batchSize(n)` set to the
smallest source's limit, each source gets at most one call per chunk, never with more than `n` keys; a source with a
smaller limit, or one called in parallel, is the lookup's own concern.

**R-FCH-17** *(D-114)* **The order within a run, and per-call state.** Within a run, the plan's children run in the
order added, each child plan whole, its enrichers included; then its join plans in the order added; then its enrichers
in the order added. Each step completes, its effects visible, before the next starts; the thread is not promised.
Per-call inputs (the profile fields a request asks for, a cache shared with a child's enricher) are captured by
building the enricher and its plan per call and applying it with `withFetch` (R-FCH-13), which runs no statement of its
own. Such a plan holds caller state and must not be shared across calls (INV-9); a cache it captures spans export
batches and is the caller's memory, outside INV-4.

## 5. Where a plan runs

**R-FCH-09** A plan runs on the models a call returns or passes on, once per page: `list` (one page), `page`
after the `hasNext` probe row is dropped (offset, keyset and primary-key-first), `export` on each page after grouped
dedupe and before `pageTransformer`, and their Spring forms (`findAll`, `findPage`, `export`). It never runs for
`count` or under `ONLY_COUNT`. Paging state (cursor, keys, dedupe) is read from rows, never from what a plan returns. The last `export` page is the
exception to "exactly the returned models": the plan runs on the whole page and `limit` cuts after, since
`pageTransformer` can change the item count, so a plan may load children for models the sink never sees.
`stream` with a plan that has a child, join plan or enricher throws `MQ2605` before any statement, naming `export`.

**R-FCH-10** A child on a grouped query, at any join depth, throws `MQ1704` at `build()`, before `MQ1401`: a grouped
row has no single key. An enricher's columns on a grouped query must be group keys (`MQ1401`).

**R-FCH-11** INV-4's page includes its children. `maxPerParent(n)` bounds them: each round reads at most
`keys × n + 1` rows (`setMaxResults`), and reaching that, or a parent with more than `n` distinct children, throws
`MQ2603` naming the key with the most rows read. Without it, a page's children are unbounded, and the user guide says
so.

**R-FCH-12** At `DEBUG`, each child load logs the child field, the number of keys and of rounds; at `TRACE`, each
round's statement logs as any statement does (D-95).

## 6. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-FCH-01 | A three-level plan (parent → to-many child → its to-many child) loads every level on `list`, offset `page`, keyset `page`, primary-key-first `page` and `export`, with the parent page's rows, order and totals unchanged (R-FCH-01, R-FCH-05, R-FCH-09). |
| AC-FCH-02 | A to-one child by key loads; two distinct rows for one key throw `MQ2601`; repeated child rows from a to-many child filter are deduplicated (R-FCH-03, R-FCH-04). |
| AC-FCH-03 | Child keys above the vendor's limit split into rounds; a page with no keys runs no child statement; a converted key column matches on its attribute value; a case-insensitive collation's unmatched child row throws `MQ2604` (R-FCH-05). |
| AC-FCH-04 | Child filters and order apply, closed by the child's primary key; the default order is the primary key (R-FCH-04). |
| AC-FCH-05 | One plan fills a model on its own and through a `@Join`, including two aliased joins to one entity and a LEFT join that found nothing; the re-rooted columns equal the generated join constants; a join plan with nothing selected throws `MQ1701`, and one selecting an aggregate throws `MQ1705` (R-FCH-07). |
| AC-FCH-06 | `byKey` and `of` enrichers see their columns selected and run once per page after children, nested first; `of` returning another size or `null` throws `MQ2602` (R-FCH-08). |
| AC-FCH-07 | `stream` with a plan throws `MQ2605`; a child on a grouped query throws `MQ1704`; a plan column through a to-many join throws `MQ1702` on first execution, except on `count`, which logs a `WARNING`; a duplicate child throws `MQ1703`; `count` and `ONLY_COUNT` run no child query; the probe row's children are never loaded (R-FCH-01, R-FCH-02, R-FCH-09, R-FCH-10). |
| AC-FCH-08 | `maxPerParent` throws `MQ2603` within one round's cap, and `MQ2001` below 1 (R-FCH-04, R-FCH-11). |
| AC-FCH-09 | `withFetch` returns a definition with the plan's selection, `orderedBy` keeps it, the original is unchanged, and a (query, plan) pair is checked once; `select` after `fetch` drops the plan with a warning (R-FCH-02, R-FCH-13). |
| AC-FCH-10 | The processor generates `ChildField` and `JoinField` for records and setter classes, a mutual `@Child`/back-`@Join` pair initialises in either order, a nested model from another module works (D-45), and each of `MQ3401`–`MQ3405` has a compile-failure case (R-FCH-03). |
| AC-FCH-11 | A many-to-many child loads both ways on Tier 1: through a `foreignKey` crossing the child's collection, and through `through` on a unidirectional `@ManyToMany`; a child shared by two parents appears under both; a child filter and order apply through `through`; `MQ3406` has a compile-failure case (R-FCH-03, R-FCH-04, R-FCH-14). |
| AC-FCH-12 | A nested plan's enricher, applied through `join`, fills a `@Transient` field of the joined model: each joined model is enriched and the lookup runs once per page (R-FCH-07, R-FCH-08, D-111). |
| AC-FCH-13 | An `Enricher.byKey` with a composite record key looks the page's distinct keys up in one call, leaves a model whose key is absent unchanged, and is reused across a root query and a nested plan (R-FCH-08, D-111). |
| AC-FCH-14 | A `byKeys` with payer, payee, initiator and a nullable requestor of a `(userType, userId)` record: one lookup per page with the distinct non-null keys across models and keys; a user in two roles or two models is looked up once and its value lands in every role's field; a null requestor is never in the set and leaves `requestor` as mapped; the 8 declared key columns are selected while the model's selection omits them (R-FCH-02, R-FCH-15, D-114). |
| AC-FCH-15 | `byKeys` runs on `list`, offset, keyset and primary-key-first pages and once per `export` batch, through a join plan and a child plan; a `null` lookup result throws `MQ2606` for `byKey` and `byKeys`; no key throws `MQ1706` and `batchSize(0)` `MQ1707` (R-FCH-09, R-FCH-15). |
| AC-FCH-16 | `batchSize(n)` over `d` distinct keys makes `ceil(d / n)` lookup calls of at most `n` keys each, in first-seen order, no key in two calls; with recipe 8's lookup split by user type, each type's source is called at most once per chunk (R-FCH-15, R-FCH-16). |
| AC-FCH-17 | In a plan built per call, a user the movements `@Child` enricher loaded is not looked up again by the order's enricher; applying the per-call plan with `withFetch` adds no statement (R-FCH-13, R-FCH-17). |
| AC-FCH-18 | A join plan selecting an expression throws `MQ1705` (R-FCH-07). |
