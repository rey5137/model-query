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

**R-FCH-02** `ModelQuery.Builder.fetch(FetchPlan<M>)` selects the plan's `SelectSet` and attaches the plan; `fetch` and
`select` replace each other's selection, last call wins, and `build()` with neither throws `MQ1202`. The query also
selects every column the plan needs: each child's parent-side key and each enricher's declared columns, recursively
through joins (R-FCH-07). Those columns count as selected for every rule: presence keys (R-QRY-04), phase consistency
(R-QRY-09), to-many joins (R-PAG-13), grouping (R-AGG-08, `MQ1401`) and counting (R-EXE-04). One that is read through a
to-many join throws `MQ1702` at `build()`. `select()` returns the selection without them, as it does without the
primary key, so `orderedBy` cannot sort on them. They stay filled on the returned models.

**R-FCH-13** `ModelQuery.withFetch(FetchPlan<M>)` returns a copy with the plan's selection and plan: a new definition
(D-21), checked as `build()` checks one, with the check cached per (definition, plan), so a constant plan is checked
once. An `orderedBy` copy keeps the plan. Use it when the plan depends on the call, such as an export's chosen columns,
or holds a bean the query definition must not.

## 2. Children

**R-FCH-03** `@Child` marks a field of a `@QueryModel` filled from another `@QueryModel`'s rows. `key`, an attribute
path on the parent's root (default: its `@PrimaryKey` attribute), equals `foreignKey`, an attribute path on the child's
root (default: its `@PrimaryKey` attribute); both are validated against the entities, need not be model fields, and are
declared `String[]` but take one path in 0.2.0 (composite keys are `MQ3404`). The two attribute types are equal and not
arrays (INV-3, R-QRY-12). The field is `List<C>` (to-many: `foreignKey` required, and `C` has a `@PrimaryKey`) or
`Optional<C>` (to-one). The processor generates a `ChildField<M, C>` per `@Child` that returns a copy of the parent with
the field set (a record's canonical constructor, a class's setter), and refers to the child's generated class lazily,
so a `@Child` and a back-`@Join` between two models initialise in either order. A mapped row holds `List.of()` or
`Optional.empty()` until its child is loaded.

**R-FCH-04** `child(ChildField<M, C>, FetchPlan<C>)` loads a child; one not in the plan stays empty. An optional
`ChildQuery` adds child filters (`where`), an order (`orderBy`, closed by the child's primary key; the primary key alone
by default) and `maxPerParent` (R-FCH-11). Child rows are deduplicated on the child's primary key, since a child filter
through a to-many join can repeat them. A to-one child that finds two distinct rows for one key throws `MQ2601` naming
the child field.

**R-FCH-05** Keys match on attribute values before any converter, as primary keys do (R-COL-11): the parent's key is
read from its row when it is mapped, the child's `foreignKey` from the child's row, and the `IN` binds those values.
Per page, the distinct non-null parent keys are split into rounds of at most the largest power of two within the
vendor's limits (R-PAG-07, D-32; `primaryKeyFirstBatchSize` does not apply), each round one child query
`where foreignKey IN keys` with the `ChildQuery` filters and order. A child row whose key equals none of its round's keys
(a case-insensitive or padding collation) throws `MQ2604` naming the field and the value. The child plan then runs once
over all the page's child rows (its children, joins and enrichers), and the children are grouped by key and set on each
parent in child order. A page with no keys runs no child query.

**R-FCH-06** A child query runs on the parent's `EntityManager`, inside the caller's transaction if any, through an
executor for the child's root entity with the parent executor's `ModelQueryConfig`. It reads no entity (INV-2). Without
a transaction (a Spring repository call outside one), a page and its children are separate reads, as R-PAG-07's two
steps are; run in a read-only transaction for one snapshot.

## 3. Joins

**R-FCH-07** `join(JoinField<M, N>, FetchPlan<N>)` applies a nested plan to the models a `@Join` produced. The nested
plan's selection and needed columns are re-rooted under the join, as the generated join constants are, and added to the
parent's selection, so one plan serves on its own and nested. A join plan whose join ends up with no selected column
throws `MQ1701`. The processor generates a `JoinField<M, N>` per `@Join`, carrying the join's `TableField` and returning
a parent copy with the nested model replaced; an empty `Optional` (a LEFT join that found nothing) is skipped. An INNER
`@Join` narrows the rows exactly as selecting it does.

## 4. Enrichers

**R-FCH-08** An `Enricher<M>` is caller code run once per page, declaring the columns it reads (selected per
R-FCH-02). `Enricher.byKey(key, lookup, with, columns...)` reads a key per model, looks the distinct keys up in one call
and copies each model with its value (a model whose key is absent or `null` is left as is), so size and order cannot
change. `Enricher.of(page -> ..., columns...)` takes the page and returns it filled: a result of another size, or
`null`, throws `MQ2602`; the order is the caller's responsibility. Exceptions propagate unwrapped. Within a plan,
children and joins run first, then its enrichers in the order added; a nested plan's enrichers run before the outer
plan's, and a join plan's enricher gets one entry per present nested model, duplicates included. The library never
fills a `@Transient` field. `afterMap` and the mapper's finisher run per row before any plan, so they cannot see
children (R-QRY-05).

## 5. Where a plan runs

**R-FCH-09** A plan runs on exactly the models a call returns or passes on, once per page: `list` (one page), `page`
after the `hasNext` probe row is dropped (offset, keyset and primary-key-first), `export` on each page after grouped
dedupe and before `pageTransformer`, and their Spring forms (`findAll`, `findPage`, `export`). It never runs for
`count` or under `ONLY_COUNT`. Paging state (cursor, keys, dedupe) is read from rows, never from what a plan returns.
`stream` with a plan that has a child, join plan or enricher throws `MQ2605` before any statement, naming `export`.

**R-FCH-10** A child on a grouped query throws `MQ1704` at `build()`: a grouped row has no single key. An enricher's
columns on a grouped query must be group keys (`MQ1401`).

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
| AC-FCH-05 | One plan fills a model on its own and through a `@Join`, including two aliased joins to one entity and a LEFT join that found nothing; a join plan with nothing selected throws `MQ1701` (R-FCH-07). |
| AC-FCH-06 | `byKey` and `of` enrichers see their columns selected and run once per page after children, nested first; `of` returning another size or `null` throws `MQ2602` (R-FCH-08). |
| AC-FCH-07 | `stream` with a plan throws `MQ2605`; a child on a grouped query throws `MQ1704`; a plan column through a to-many join throws `MQ1702`; a duplicate child throws `MQ1703`; `count` and `ONLY_COUNT` run no child query; the probe row's children are never loaded (R-FCH-01, R-FCH-02, R-FCH-09, R-FCH-10). |
| AC-FCH-08 | `maxPerParent` throws `MQ2603` within one round's cap (R-FCH-11). |
| AC-FCH-09 | `withFetch` returns a definition with the plan's selection, `orderedBy` keeps it, and the original is unchanged (R-FCH-13). |
| AC-FCH-10 | The processor generates `ChildField` and `JoinField` for records and setter classes, a mutual `@Child`/back-`@Join` pair initialises in either order, a nested model from another module works (D-45), and each of `MQ3401`–`MQ3405` has a compile-failure case (R-FCH-03). |
