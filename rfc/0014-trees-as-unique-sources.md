# RFC 0014 — Trees as unique sources

- **Status:** accepted 2026-10-16 as D-147 (revised after two architect reviews; the acceptance spike passed
  with three findings decided at acceptance, see [0014-spike-results.md](0014-spike-results.md)). M26, 0.10.0.
- **Affects:**
  - **`api/17`:** new R-SRC-13 to R-SRC-15 and AC-SRC-17 to AC-SRC-25. R-SRC-08 is amended: a recursive source built
    by `tree(...)` is unique, and the checks it says "the content statement and the count" carry are read from every
    statement kind (R-SRC-15), the grouped count included. R-SRC-09 is amended the same way. R-SRC-11 gains `MQ4014`'s
    new reason, which `PortabilityCheck` reports per target (§7).
  - **`engine/20`:** R-EXE-05's count keeps a source's check sub-selects; a count over groups over a source that
    carries checks takes R-EXE-03's client-side path, checked on the group rows and unchecked on zero groups (§3).
  - **`engine/21`:** R-PAG-01's non-unique bullet points at R-SRC-13 for trees; a known limit on ordering by `@Depth`
    under READ COMMITTED (§5).
  - **`core` SPI:** `RecursiveSpec` gains the tree flag and `RecursiveCte` the duplicate-key check (both
    `@Incubating`), each keeping a constructor with M25's components; `SourceRenderer#recursive`'s signature is
    unchanged.
  - **`jpa` SPI:** `SourceFeature` gains `RECURSIVE_COMPOSITE_KEY`, which H2's `VendorProfile` answers false (§7).
    `ProviderSupport` is unchanged (§3).
  - **`vendor/40`:** R-VND-21's source-feature table gains the composite-key row per Tier-1 vendor.
  - **`reference/90`:** new `MQ2703` beside `MQ2701` and `MQ2702`. `MQ1901` and `MQ2212` messages name `tree(...)` on
    a recursive source, `MQ1902`'s "built without" reason names `recursive(...)` and `tree(...)`, and `MQ4014` gains a
    reason (§7).
  - **`reference/92`:** D-147, resolving Q-21.
  - **`delivery/61`:** the incubating list, with the two records' added components and kept constructors.
  - **`docs/site`:** the "Source models" page gains a "Trees" section, with the cost per vendor, H2's as a
    test-database limit (§6), and the snapshot advice (§5).
- **Discussion:** to open

## Summary

A recursive source built with `tree(step, maxDepth)` instead of `recursive(step, maxDepth)` claims that its walk
reaches every node once. The claim is checked in every statement over the source: a third scalar sub-select beside
M25.5's depth and cycle checks finds the smallest key the CTE holds twice, the engine reads it from every statement's
first row, and a duplicate is a new `MQ2703`. Because every statement proves its own rows unique, such a source counts
as unique (R-SRC-02's sense), so keyset paging, primary-key-first, `export`, `byKeys`, `one(q, key)` and fetch plans
work on it, and offset `page` drops R-SRC-09's every-column tie-breakers.

No state is carried between statements. The guarantee across the statements of an export or a keyset walk is the one
tables already have: INV-4 assumes the data does not change during the walk, a write that breaks the tree is refused on
the next statement, and REPEATABLE READ around the call is the full guarantee.

```java
SourceQuery<CategoryNode> tree = QCategoryNode.source()
        .where(f -> f.eq(QCategoryNode.ID, rootId))
        .tree(step -> step.childKey(QCategoryNode.PARENT_ID).parentKey(QCategoryNode.ID), 20)   // was recursive(...)
        .build();

var q = QCategoryRow.query(tree).orderBy(QCategoryRow.NAME.asc()).keyset().build();     // MQ1901 under recursive(...)
executor.export(q, ExportOptions.of(500), row -> write(row));                            // MQ2212 under recursive(...)
```

## Motivation

M25 made every recursive source non-unique (R-SRC-08), because the processor can't tell a tree from a graph that
reaches a node by two paths. So the most common recursive read, a category or org-chart tree, gets `list`, `count`,
`stream` and offset `page` only. It can't be exported, paged by keyset, read by key, or given a fetch plan, which are
exactly the features a tree screen needs: a lazy tree view pages children by keyset, and an admin export walks the
whole tree.

How a walk reaches a node twice, given R-SRC-07's single `childKey`/`parentKey` pair:

- **A cycle.** M25.5 already refuses it (`MQ2702`): the cycle clause marks the row that closes it.
- **Overlapping seeds.** `where(f -> f.in(ID, a, b))` where `b` is under `a`: `b`'s subtree is walked from both.
- **A graph over a link entity.** A source rooted at a `PartLink(parent, child)` entity walks a bill of materials. A
  part under two assemblies is a diamond, and every link below it is emitted once per path. Here the repeat is the
  point: a parts explosion lists a part once per path, at each path's level.

The RFC 0013 spike (item 11) showed one statement can detect a repeated key on all three vendors and both Hibernate
lines. Q-21 asked whether that check lets a tree count as unique, given that it sees only the statement it runs in.
This RFC answers yes, with the cross-statement guarantee stated as INV-4's, not a stronger one.

## Design

### 1. Opting in: `Builder#tree` (R-SRC-13)

```java
@Incubating
public Builder<E, M, S> tree(Function<RecursiveStep<M>, RecursiveKeys<M, ?>> step, int maxDepth)
```

- **The walk.** `tree(...)` builds exactly what `recursive(...)` builds (R-SRC-07): the same step, levels, depth
  limit, cycle clause, `MQ1902` rules and `MQ4015` ceiling. It adds one thing: the claim that each node is reached
  once, and the check of that claim (§2).
- **One step per source.** The builder is immutable and the last of `tree(...)` and `recursive(...)` wins, as `where`
  replaces an earlier `where`.
- **`unique()`.** `SourceQuery#unique()` answers `true` for a tree source and `false` for a `recursive(...)` one, as
  today. Everything that reads `unique()` follows without change: no `MQ1901` at the outer `build()`, no `MQ2212` at
  the executor call (R-SRC-09).
- **Never a branch.** A tree source given to `union`, `unionAll`, `intersect` or `except` is still `MQ1904`
  (R-SRC-07), so R-SRC-05 is never asked about one, and its rules are unchanged.
- **No discriminator.** A recursive source is never a branch and `@Depth` on a branch or shape is `MQ3034`, so a tree
  source has no `@Discriminator`. Its key is its root entity's id (R-SRC-02), and the outer model's `@PrimaryKey` is
  that id (`MQ3036`), as today.
- **Messages.** The `MQ1901` and `MQ2212` messages on a `recursive(...)` source name `tree(...)` as the way out when
  the data is a tree. `MQ1902`'s reason for a `@Depth` model built without its step names both calls: "built without
  `recursive(...)` or `tree(...)`".
- **The name.** This RFC recommends `tree`: it says what the data is, sits beside `recursive` in completion, and
  carries the step itself (§Alternatives rejects a modifier). The name stays the user's call at acceptance.

**Why opt in, rather than every recursive source.** Making every recursive source unique would turn a link-table walk
that M25 serves today (a parts explosion, one row per path) into `MQ2703` on `list` and `page`. Such a walk is right to
repeat a key, and R-SRC-09's tie-breakers page it correctly. The alternative, probing only in the statements of
key-based features, would make one source unique for `export` and non-unique for `page`, and would make a DAG `list`
work while its `export` fails. One declaration at the source says which kind of data the caller expects, and every
statement over it checks that claim.

### 2. The duplicate-key check (R-SRC-14)

**Rendering.** A tree source's CTE carries a third scalar sub-select beside M25.5's two, registered by the same
`SourceRenderer#recursive` call through `JpaSubQuery` (D-144). For node key columns `k1..kn`:

```sql
(select c.k1 from cte c group by c.k1, …, c.kn having count(*) > 1
 order by c.k1, …, c.kn fetch first 1 rows only)
```

- It is `null` on a tree and otherwise the first key column of the smallest duplicated key.
- The `group by` is over the whole key, so a composite key is checked whole.

**It names the first key column, not the whole key.** A scalar sub-select returns one value, so naming a composite key
whole would take one sub-select per key column, each repeating the grouping, and multiply §6's cost by the key's
width. One column is what `MQ2701` and `MQ2702` name today (`RecursiveCte`'s checks read `keyAliases().get(0)`), and it
is enough to find the doubled subtree. For a composite key the message says that it names the first column of the key,
so it never passes one column off as the whole key.

**One form on every vendor and line (INV-6).** The check renders the same on H2, PostgreSQL and MySQL, and on both
Hibernate lines: `model-query-hibernate` has no per-dialect form. The spike ran it on H2, PostgreSQL 16 and MySQL
8.0.31 under Hibernate 6.6 and 7.4, and it passed every case on all six (`rfc/0014-spike-results.md`, probe 1), so this
form is the design. The fallback below also passed everywhere (probe 2) and is kept only as the recorded alternative,
should a later vendor reject the form above; then it would replace it everywhere:

- **Detect.** A scalar sub-select that is non-null exactly when some key group holds more than one row, carried and
  read like the form above. Probe 2 tries
  `case when exists(select 1 from cte c group by c.k1, …, c.kn having count(*) > 1) then 1 end` first: it needs no
  derived table, `order by` or `fetch`, the parts most likely to be what failed in probe 1. Only if that fails too
  does it try `count(*)` against a grouped count.
- **Name, on the error path only.** When the detect check fires, the engine runs one more statement over the same
  source, `select k1, …, kn from cte group by k1, …, kn having count(*) > 1 order by k1, …, kn`, one row,
  and puts its first key column in the message. It is built through the same `SourceRenderer#recursive` call, so it
  walks with the same `maxDepth + 1` bound and cycle clause, and it selects the depth and cycle checks too, read in the
  order below. A cycle that appeared since the failed statement is then `MQ2702`, not `MQ2703`. It doesn't select
  the detect check and doesn't go through the check-site helper (§3), so the error path never re-enters itself. Under
  READ COMMITTED that statement can find no duplicate any more; `MQ2703` is raised anyway, without a key. A statement
  that passes pays only the detect check.

**Why not `count(*)` against `count(distinct key)`.** The spike's form detects a duplicate but can't name it, and the
message has to. It also needs `count(distinct (a, b))` for a composite key. PostgreSQL and H2 accept a row value there
and MySQL only `count(distinct a, b)`, and what Hibernate renders differs by dialect, which INV-6 rules out. The spike
records it only as a comparison (§8, probe 3). `group by … having` is portable for any number of key columns.

**Scope: the whole CTE.** Like M25.5's checks, the sub-select reads every row the recursion produced, so it also sees
the row past `maxDepth` and the marked row. It doesn't stop at the rows the outer query keeps, so a tree source whose
walk reaches a node twice is refused even when the outer `where` or the page would drop both copies. Uniqueness is a
property of the source. A page-scoped check would let page 1 pass and page 2 fail over the same data, and would let
`count` and `list` disagree about it.

**Read order.** The engine reads all three checks from the statement's first row, before any row is mapped, keyed,
passed to a fetch plan, or given to a sink or a stream consumer. A row past `maxDepth` raises `MQ2701`, a marked row
`MQ2702`, and a duplicated key `MQ2703`. A cycle's marked row also repeats a key, so checking `MQ2702` first gives a
cycle its more specific message.

- **Before `one`'s size test.** `one(query)` and `one(q, key)` read at a limit of 2 and throw `MQ2003` on a second row
  (`DefaultModelQueryExecutor.java:576`, in `atMostOne`) before `modelOf` reads the checks (`:587`). The checks move
  ahead of the size test: a node reached twice is two rows for one key, and `MQ2003` would hide the cause. The same
  order gives `MQ2701` and `MQ2702` precedence over `MQ2003` on a `recursive(...)` source.
- **Before a key is taken.** `byKeys` throws `MQ2003` on a repeated key (`:516`, `putIfAbsent`) and primary-key-first
  step 2 keeps the first row of a repeated key and drops the rest (`:2703`, `putIfAbsent`). On a tree source the
  checks are read before either sees a row, so a doubled node is `MQ2703` in both.

**`MQ2703`** names the model and the first key column of the smallest duplicated key, and suggests narrowing the seeds
or using `recursive(...)` when the data is a graph.

**An empty result.** A statement that returns no row carries no row to read the checks from, so it refuses nothing.
Nothing doubled is returned, so this is safe for uniqueness:

- **An empty page ends a walk on the same terms as on a table.** Each earlier page was checked in its own statement.
  An empty keyset or offset page means no row lies past the cursor in that statement's snapshot. That is the answer a
  table gives too, and INV-4's assumption covers the rows a concurrent write moved behind the cursor.
- **A missing key reads as missing.** `one(q, key)`, `first` and a `byKeys` chunk that find nothing report "not
  found", and that is right whatever the shape of the rest of the tree.

A count in the database returns its one row whatever it counts, so it is always checked: the plain count carries the
checks as M25.5 does, and the count over groups carries them at its outer level, so they are evaluated with zero
groups too (§3). The one count that reads rows is the client-side count over groups (R-EXE-03), used where the
`ProviderSupport` builds no count with checks. It reads the grouped rows through the check site, so it is checked
when there is a group and unchecked on zero groups, where it returns `0`. Over a tree source that reaches a node
twice, an outer filter that matches no row gives this, accepted as `MQ2701`'s empty-result note is (R-SRC-08):

- `list`, `stream` and `first` return nothing and raise nothing;
- `page` with a count raises `MQ2703` from its count statement, and so does `count`, a count over groups included;
- a count-free page (`CountMode.NO_COUNT`) returns an empty page;
- the client-side count over groups returns `0`.

The asymmetry follows the rows: a statement is refused only when it has rows to hand back, or a count that describes
them. Making every empty statement fail would take a second statement per empty result, for an answer that returns no
wrong row.

### 3. Every statement, no state between them (R-SRC-15)

**What carries the check today.** The check columns are selected on every statement built from a source:
`ModelQuery.assemble` adds them to every selection (`ModelQuery.java:593`). But the engine reads them in one place,
`checkRecursion` (`DefaultModelQueryExecutor.java:602`), reached only from `modelOf` (`:587`), `stream` (`:802`),
`mapAll` (`:2742`) and the count (`:3258`). These statements carry the checks and never read them:

| Statement | Where | What happens to a duplicate |
|---|---|---|
| a `byKeys` chunk | `:508` | `MQ2003` from `putIfAbsent` (`:516`), naming no cause |
| a keyset page | `:1153` | mapped and handed back |
| primary-key-first step 1, `readKeys` | `:2672` | the key read twice |
| primary-key-first step 2, `readByKeys` | `:2702` | dropped silently by `putIfAbsent` (`:2703`) |
| a deep offset page through `fetch` | `:2550` | as step 1 and step 2 |
| an offset export page | `:2805` | handed to the sink |
| a keyset export page | `:2862` | handed to the sink |
| a count over groups, `countGroups` | `:3275`, `:3278` | gone: see below |

On a `recursive(...)` source the first seven are refused before any statement (`MQ1901`, `MQ2212`), so only
`countGroups` is reachable today. There it is an M25.5 bug: a grouped outer query over a recursive source counts a
too-deep tree or a cycle without `MQ2701` or `MQ2702` (INV-5). M26's first slice fixes it and adds its AC row.

**Why the count over groups loses them.** `countGroups` replaces the selection with the group keys
(`multiselect(keys)`, `:3275`). Re-adding the checks there is not enough: `model-query-hibernate`'s
`ProviderSupport#countQuery` (`HibernateProviderSupport.java:138-142`) wraps the grouped query as a derived table
(`createCountQuery()`, a `CriteriaQuery<Long>`), so whatever the inner query selects is projected away, and with zero
groups the inner query has no row to carry them. The client-side path (`:3285`) reads the grouped rows, so it sees
the checks only when there is a group.

**The count over groups over a source counts in memory.** The spike (probe 4') could not put the checks on a count
built by `createCountQuery()`: the grouped query's CTE ends up inside the derived table, out of the outer select's
reach, on every vendor. A count statement that would work (the CTE and the checks at the outer level, the grouped part
a sub-query over the CTE) needs an SPI that takes the query's parts rather than a finished query, which is more surface
than one count path is worth. So a count over groups whose root is a source that carries checks (`recursive(...)` or
`tree(...)`) skips `ProviderSupport#countQuery` and takes the client-side path R-EXE-03 already has (`:3285`): it
selects the group keys and the check columns, reads the rows through the check site below, and counts them. It
transfers one row per group, for these counts only, and logs no R-EXE-03 warning, since no provider could do better.
With zero groups it reads no row and so no check, the same empty-result limit as `list` (§2). R-EXE-05's "drops any
selection the count does not need" keeps the checks: the count needs them. `ProviderSupport` is unchanged. The same
path is the M25.5 fix for a `recursive(...)` source's count over groups.

**The rule: one check site.** Every `Tuple` read over a `BuiltQuery` goes through one engine check site that reads
the checks from the statement's first row before it returns any row. Today the engine reads results through three
untyped helpers, `rows` (`:941`), `single` (`:954`) and `resultStream` (`:811`), and `stream` reads through
`resultStream` (`:798`), not `rows`. Each gains a `Tuple` overload taking the `BuiltQuery`, which reads the checks
before it returns (`resultStream`'s before it hands on the first row), so a read over a source has no unchecked
signature to call (P-2). An ArchUnit rule (AC-SRC-25) keeps `getResultList`, `getSingleResult` and `getResultStream`
on a `Tuple` query inside those overloads. Reading the checks inside the read puts them ahead of everything the
caller does with a row:

- the keyset cursor's null rule (`:1159`, `Keyset.java:340`), so a page holding both a doubled node and a NULL
  order value is `MQ2703`, not `MQ2202`;
- a keyset page's in-page dedupe and its `MQ2205` (`:1175`), and a keyset export page's `MQ2205` (`:2872`), so the
  page after a concurrent break is `MQ2703`, not `MQ2205`;
- `byKeys`'s `MQ2005` (`:512`) and `MQ2003` (`:516`), and primary-key-first step 2's `putIfAbsent` (`:2703`).

This widens R-SRC-08, whose text says only the content statement and the count carry the checks, to every statement
kind R-SRC-12 lists: the content query, the count and the count over groups, `one`, both primary-key-first steps,
each export page, every `byKeys` chunk and the parent reads of a fetch plan. Each statement checks the whole tree as
of its own snapshot. The engine carries nothing from one statement to the next.

**The guarantee** is the one tables have (INV-4, `engine/21`):

- **Stable data.** A tree that stays a tree, with stable order values, is visited exactly once by every export mode,
  keyset walk and primary-key-first deep page.
- **A concurrent write that breaks the tree.** One that makes the data a DAG for the walk (overlapping seeds, or a
  link-table diamond) or a cycle raises `MQ2703` or `MQ2702` on the next statement that reads the source (INV-5).
  Pages already delivered stay delivered, the same limit as R-SRC-08's depth check (`api/17` §6).
- **The full guarantee** is REPEATABLE READ (or a snapshot) around the call.

The fetch plan's child rounds read the child entity, not the source, so they carry no CTE and no check: a child
model rooted at a source is `MQ3039` at compile time (`api/17` §3), since the fetch plan has no `SourceQuery` to pass
it, so a child round never reads one. The parent
statement proved its keys unique, so children are attached by a key that is unique in the page they belong to
(`api/15` R-FCH-05). A write between the parent read and a child round is the same race a table-rooted fetch plan has.

### 4. What a tree source changes

| Feature | `recursive(...)` (M25) | `tree(...)` |
|---|---|---|
| `list`, `count`, `first`, `stream`, `one(query)` | work | work, checked |
| offset `page` | key plus every selected column as tie-breakers (R-SRC-09) | key only (R-PAG-01) |
| `keyset()`, `primaryKeyFirst(...)` | `MQ1901` | work (R-PAG-04, R-PAG-07) |
| `export` (both modes), `byKeys`, `one(q, key)` | `MQ2212` | work |
| fetch plan with a child, join plan or enricher | `MQ2212` | works |

- **Offset `page`.** R-SRC-09 adds the every-column tie-breakers because, with duplicate keys, two rows with equal
  order values could swap between statements. A tree source's statements each prove their keys unique, so R-PAG-01's
  key tie-breaker is total again and the extra columns go. That also removes the `MQ2212` on an unorderable selected
  column for tree sources. A `recursive(...)` source keeps the tie-breakers.
- **Keyset.** R-PAG-04 appends the node key as the last keyset column. R-PAG-05's NULL rule applies as on a table: a
  keyset ordered by the parent key meets the roots' NULL, and is `MQ2202` unless the order says `nullsFirst()` or
  `nullsLast()`.
- **Export dedupe.** R-PAG-02's previous-page dedupe and R-PAG-14's `MQ2205` work unchanged, keyed by the node key.
- **Primary-key-first.** Step 1 and step 2 each carry and read the CTE's checks. Step 2's `IN` list re-walks the tree,
  as every statement over a source does (§6).

### 5. Derived values under concurrent writes (known limit)

A tree source's `@Depth` is computed from the path to the seed. Moving one subtree (a single update of one
`parentId`) changes the level of every node under it, and so does any value the outer model orders by that the walk
derives. On a table, one update changes one row's order value. On a tree, it changes a whole subtree's.

- **What can go wrong.** Under READ COMMITTED, a keyset or offset walk ordered by `@Depth` can skip or repeat a whole
  subtree when it moves between two pages. The node key stays the last tie-breaker (R-PAG-04), and the per-statement
  check still passes, since each statement sees a tree. So `MQ2205` catches a repeat only when it lands on the very
  next page.
- **Moving across the seeds' reach.** A subtree moved into or out of the walk is, to an export, a bulk insert or
  delete, which INV-4's assumption already covers.
- **The guard.** A walk ordered by the node key alone is unaffected by moves within the walk. One ordered by derived
  values is stable only under a snapshot. This is the same advice R-SRC-04's known limit gives for window values. The
  docs page says so.

### 6. Cost

The check aggregates the whole CTE in every statement, so an export of `N` nodes in pages of `P` does `N/P` statements
of `O(N)` work each, which is quadratic in the tree's size. M25 already pays this for the walk itself on every page
(RFC 0013 §3's MySQL cost). The question is whether the check adds a full re-walk:

The spike measured it (`rfc/0014-spike-results.md`, probes 6 and 7):

- **PostgreSQL** computes a recursive CTE once per statement (one `Recursive Union`, each check an `InitPlan` over a
  `CTE Scan`). The check adds a scan, not a walk: 253 ms against 209 ms for a 100k-node content statement.
- **MySQL 8.0.31** materializes the recursive CTE once and shares it (the check references show `never executed`):
  376 ms against 315 ms at 100k nodes. A 10k-node keyset export at pages of 100 takes 2.4 s against 2.1 s.
- **H2** re-walks the recursion for each check sub-select, and does so once per outer row: a content statement costs
  rows × tree size × checks. A 1k-node tree reads in 2 ms with no check, 1.1 s with M25.5's two, and 1.8 s with the
  duplicate check too; doubling the tree quadruples the time. A count statement (one outer row) is unaffected. This is
  M25's cost already; the duplicate check adds half of it again. The design stays: H2 is a test database here, and
  its cost is stated as a test-database limit. The cycle sub-select stays too, though a tree source's duplicate check
  could stand in for it at a third off H2's cost: it is what attributes a cycle to `MQ2702` rather than `MQ2703`, and
  one check per cause keeps the read order of §2.

The docs page states the measured cost per vendor, as M25 states MySQL's export cost, H2's with the advice to keep
trees in H2 tests small.

### 7. Provider and SPI

- **`RecursiveSpec`** gains `boolean tree`, and keeps a constructor with M25's components that passes `false`.
- **`RecursiveCte`** gains `Expression<?> duplicateKey`, `null` when `tree` is false, so a `recursive(...)` statement
  carries exactly M25.5's two checks. It keeps a constructor with M25's three components that passes `null`. A
  renderer that returns a non-null `duplicateKey` for a `recursive(...)` source is not wrong, but the engine ignores
  it: it selects and reads the check only when `spec.tree()`, so a `recursive(...)` statement's SQL, and M25's SQL
  snapshots, don't change whatever the renderer returns.
- **A renderer that ignores the flag is refused.** A `SourceRenderer` built against M25's records still compiles and
  still returns a `RecursiveCte` without the check. For a tree source the engine refuses that before any statement:
  `spec.tree()` with `cte.duplicateKey() == null` is `MQ4014`, with a new reason ("the persistence provider's
  `SourceRenderer` returned no duplicate-key check for a `tree(...)` source"), so an old renderer can never make a
  tree source unique without its check. The reason is R-SRC-11's, so `PortabilityCheck` reports it per declared
  target with no database, as it reports `MQ4014` today (AC-SRC-24).
- **`ProviderSupport` is unchanged.** The spike (probe 4') found that `createCountQuery()` can't carry the checks:
  re-selecting them at its outer level fails on every vendor and line (`Could not find CTE for name 'cte0'`), because
  the copy moves the CTE into the derived table. The form that works registers the CTE and the checks on the outer
  count statement and the grouped query as a derived table over that CTE (`from(Subquery)`), and a finished grouped
  `CriteriaQuery` can't be turned into it. Rather than an SPI that takes the grouped query's parts, the count over
  groups over a source counts client-side (§3).
- **A composite node key is a vendor fact.** Hibernate's H2 emulation of a multi-column `cycle` clause
  (`array[row(a, b)]`) is rejected by H2 (`Column count does not match`), with or without any check (probe 3), so
  M25's `recursive(...)` over a composite key fails on H2 today. `SourceFeature` gains `RECURSIVE_COMPOSITE_KEY`:
  H2's `VendorProfile` answers false, PostgreSQL's and MySQL's true. A `recursive(...)` or `tree(...)` source whose
  node key has more than one column is `MQ4015` on a target that answers false, before any statement, and
  `PortabilityCheck` reports it per declared target as it reports every source feature (R-SRC-11). This is the M25
  fix too, so it ships in M26's first slice.
- **`SourceRenderer#recursive(outer, spec)`** keeps its signature. `model-query-hibernate` builds the third sub-select
  as it builds the other two, identically on 6.6 and 7.4 (spike probe 10).
- **Module boundaries.** `core` names the check and `jpa` reads it by alias from the first row, as M25.5 does, so no
  provider type reaches `jpa` (INV-7).
- **Vendor facts.** The one new fact is `RECURSIVE_COMPOSITE_KEY`, answered through `VendorProfile#sources` like the
  other source features; the check itself has one form on every vendor (§2) (INV-6).

### 8. Spike plan

Run; results in `rfc/0014-spike-results.md`, raw data in `rfc/0014-spike-data/`. Planned as: run on the
`spike/rfc-0013` branch's harness (`model-query-tck/.../tck/spike/`, `Rfc13SpikeTest`), as a new
`Rfc14SpikeTest`. Run it on Hibernate 6.6.58 and 7.4.11, each against H2, PostgreSQL 16 and MySQL 8.0.31, with the
same TSV output under `rfc/0014-spike-data/`. The probes prove what SQL renders and runs; that the engine reads the
check on every statement kind is an AC (AC-SRC-18), not a probe.

1. **The check's form.** A `JpaSubQuery` with `groupBy`, `having`, `orderBy` and `fetch(1)` over the CTE renders and
   runs as a scalar sub-select. It is `null` on a tree, and names the smallest duplicate in two cases: overlapping
   seeds and a link-table diamond. In a third, a cycle, both the cycle check and the duplicate check are non-null, and
   reading them in §2's order raises `MQ2702`. In a fourth, a tree whose walk reaches level `maxDepth + 1`, the depth
   check is non-null and the duplicate check stays `null`. In a fifth, the diamond under an outer `where` that drops
   both copies of the doubled node, the duplicate check is still non-null (whole-CTE scope).
2. **The fallback form.** The detect-only check, tried first as
   `case when exists(select 1 from cte c group by c.k1, …, c.kn having count(*) > 1) then 1 end` and, only if that
   fails, as `count(*)` against a grouped count, each as a scalar sub-select; and the error-path naming statement,
   built through `SourceRenderer#recursive` with the depth and cycle checks, raising `MQ2702` when a cycle is present.
   Needed only where probe 1 fails anywhere, and then on every vendor and line. It also records whether a row-value
   `IN` against a CTE renders on each vendor, for comparison.
3. **A composite node key.** Use an `@IdClass` entity and run probe 1 grouped on two columns, with the cycle clause on
   both. Record, for comparison only, what `count(distinct (a, b))` renders and whether it runs on each vendor and
   line.
4. **Placement.** Three sub-selects on each statement kind, all present in the first row: the content statement, the
   count, primary-key-first step 1, step 2 with an `IN` list, a keyset page with its cursor predicate, a grouped outer
   statement, a count-free page, and `one` at its limit of 2. On MySQL, with `ONLY_FULL_GROUP_BY` on (the default),
   the grouped statements run with the scalar sub-selects in the select list. Probe 4' builds the count over groups
   with the three checks beside `count(*)` at the outer level of `createCountQuery()`'s derived table, with one group,
   several, and zero (it failed everywhere; §7).
5. **An empty result.** An outer filter that matches no row over a DAG returns an empty list and raises nothing. The
   count of the same query, and its count over groups with zero groups, carry `MQ2703`'s condition on their one row.
   This is §2's asymmetry, observed.
6. **Re-walk per reference.** Time the content statement with 0, 1, 2 and 3 check sub-selects. Use trees of 1k, 10k and
   100k nodes, both balanced and as a chain under MySQL's ceiling. Record each vendor's plan: PostgreSQL's
   `EXPLAIN ANALYZE` CTE scans, MySQL's `EXPLAIN ANALYZE` (whether it materializes once or per reference), and H2's
   `EXPLAIN` (whether it inlines the CTE per reference). This answers §6's question.
7. **Export cost.** A full keyset export of a 10k-node tree at page sizes 100 and 1000, with and without the check,
   reporting total time per vendor.
8. **A concurrent break.** On PostgreSQL, from two connections under READ COMMITTED: read page 1 of a keyset walk over
   seeds `a` and `b`, with page 2 non-empty, then move `b` under `a` and commit. Page 2 raises the duplicate. Under
   REPEATABLE READ, the walk completes over its snapshot.
9. **A concurrent move (§5).** Same set-up, ordered by `@Depth`: moving a subtree between pages repeats or skips it
   under READ COMMITTED, and neither under REPEATABLE READ. This is evidence for the docs, not a pass/fail gate.
10. **Line parity.** The probe source compiles and behaves the same on 6.6 and 7.4 (`JpaSubQuery#fetch`, `groupBy`
    and `having` over a `JpaCteCriteria` root).

### New ids

| Id | Where | What |
|---|---|---|
| R-SRC-13 | `api/17` | `Builder#tree(step, maxDepth)`: a recursive source with one row per node, unique |
| R-SRC-14 | `api/17` | The duplicate-key check: one form on every vendor, whole-CTE scope, read order, empty results |
| R-SRC-15 | `api/17` | Every statement carries and reads the check, no state between; the guarantee; derived values |
| MQ2703 | `reference/90` | A tree source's walk reaches a key twice; names the model and the key's first column |
| `RECURSIVE_COMPOSITE_KEY` | `SourceFeature` | A recursive source over a multi-column node key; H2 answers false |
| AC-SRC-17..25 | `api/17` | TCK cases on every Tier-1 vendor and both Hibernate lines, an ArchUnit rule; below |
| D-147 | `reference/92` | Q-21 resolved: trees opt in with `tree(...)`, checked per statement, no state between |

D-147 also records: every row read over a tree source passes through one check site, the `Tuple` overloads of
`rows`, `single` and `resultStream`; the count over groups over a source counts client-side, checked on the group
rows and unchecked on zero groups, with `ProviderSupport` unchanged; a provider that returns no duplicate check for a
tree is refused before any statement; the empty-result asymmetry is accepted; a composite node key is
`RECURSIVE_COMPOSITE_KEY`, false on H2; H2's per-row check cost is a documented test-database limit; and the
cross-statement guarantee is the one tables have, with no state kept between statements.

The AC-SRC-17..25 cases are:
- **AC-SRC-17:** a tree source exported by keyset and by offset, paged by keyset and primary-key-first, read by
  `byKeys` and `one(q, key)`, and given a fetch-plan child, returns every node exactly once; the check is pinned in
  an SQL snapshot per vendor;
- **AC-SRC-18:** `MQ2703` on overlapping seeds and on a link-table diamond, on each statement kind of R-SRC-15 (the
  content query, the count, a count over groups, `one`, a `byKeys` chunk, a keyset page, both primary-key-first
  steps, a deep offset page, an offset and a keyset export page, a fetch plan's parent read), naming the model and the
  first key column of the smallest duplicated key, and raised before a keyset page's `MQ2202` or `MQ2205` and
  `byKeys`'s `MQ2005` or `MQ2003`; a cycle stays `MQ2702`; a count over groups over a `recursive(...)` source is
  `MQ2701` and `MQ2702` as the plain count is (the M25.5 fix), read from the group rows;
- **AC-SRC-19:** a duplicate made between two export pages (second connection, READ COMMITTED) is `MQ2703`, not
  `MQ2205`, on the next page; under REPEATABLE READ the export visits every node once;
- **AC-SRC-20:** offset `page` over a tree source orders by the definition's order plus the key only (SQL snapshot);
  a `recursive(...)` source keeps `MQ1901`, `MQ2212` and the every-column tie-breakers, and their messages name
  `tree(...)`; `MQ1902` for a `@Depth` model built without its step names both calls;
- **AC-SRC-21:** a tree over an entity with a composite id is checked on the whole key, and `MQ2703` says it names
  the key's first column, on PostgreSQL and MySQL; on H2 a `recursive(...)` or `tree(...)` source over a composite
  key is `MQ4015` naming `RECURSIVE_COMPOSITE_KEY` before any statement, and `PortabilityCheck` reports it for an H2
  target;
- **AC-SRC-22:** `one(query)` and `one(q, key)` over a tree that reaches the node twice are `MQ2703`, not `MQ2003`;
- **AC-SRC-23:** over a tree that reaches a node twice, an outer filter that matches no row gives an empty `list`, an
  `MQ2703` from `page`'s count, and an empty count-free page (`CountMode.NO_COUNT`); a count over groups that matches
  no group returns `0`, and one that matches a group is `MQ2703`;
- **AC-SRC-24:** a `SourceRenderer` that returns a `RecursiveCte` without a duplicate check for a tree source is
  `MQ4014` before any statement, and `PortabilityCheck` reports that reason per declared target with no database;
  the same renderer still serves a `recursive(...)` source; a renderer that returns a duplicate check for a
  `recursive(...)` source leaves its SQL snapshot unchanged;
- **AC-SRC-25:** an ArchUnit rule: in `model-query-jpa`, `getResultList`, `getSingleResult` and `getResultStream` on a
  `Tuple` query are called only from the check-site overloads of `rows`, `single` and `resultStream`.

`SourceQuery.Builder#tree`, the new components of `RecursiveSpec` and `RecursiveCte`, and
`SourceFeature.RECURSIVE_COMPOSITE_KEY` are `@Incubating`.

## Compatibility

- **Additive for callers.** `recursive(...)` keeps M25's behaviour, its refusals and its SQL. Only `tree(...)` sources
  change, apart from M25.5's grouped-count fix, which turns a silent count over a too-deep or cyclic walk into
  `MQ2701` or `MQ2702`.
- **The SPI change.** `RecursiveSpec` and `RecursiveCte` are `@Incubating` SPI records, and both gain a component.
  Each keeps a constructor with M25's components, so a third-party `SourceRenderer` still compiles and still serves
  `recursive(...)` sources; for a tree source it is `MQ4014` until it returns the check (§7). The only implementation
  is `model-query-hibernate`'s, released together with this change. A kept constructor doesn't cover deconstruction:
  source that matches either record with a record pattern (`case RecursiveCte(var a, var b, var c) -> …`) no longer
  compiles, and `delivery/61` says so. `delivery/61`'s incubating list records both.
- **H2 and composite keys.** A `recursive(...)` source over a composite key, which failed on H2 with a database error,
  is now `MQ4015` before any statement there.
- **Messages.** The texts of `MQ1901`, `MQ1902` and `MQ2212` change; their codes and conditions don't. `MQ4014` gains
  a condition that only a tree source can meet.

## Alternatives

- **Every recursive source unique, with no opt-in.** This turns M25's link-table walks, which repeat a key by design,
  into `MQ2703` on `list` and `page` (§1).
- **A modifier, `recursive(step, maxDepth).uniqueNodes()`.** Called without `recursive(...)` before it, or after a
  later `recursive(...)` replaced the step, it has nothing to apply to, and only a runtime check would say so. P-2
  prefers the signature that makes that mistake not compile: `tree(...)` takes the step itself. An enum argument to
  `recursive(...)` would compile, but reads worse at the call site and puts a behaviour switch in a flag.
- **A check only in key-based features' statements, with no declaration.** One source would be unique for `export` and
  not for `page`, and a DAG would `list` but fail to `export`. The caller's expectation would be nowhere in the code.
- **A per-dialect check form** in `model-query-hibernate` (a row-value `count(distinct …)` where the vendor takes
  one). It splits the check by vendor at a call site INV-6 keeps out, and two forms give two sets of results to keep
  equal. §2's form, or its fallback, runs everywhere.
- **Materialize the CTE into a temporary table** for the length of a walk, so every page reads one fixed tree. This
  needs vendor DDL and writes during a read. Temporary-table syntax and scope differ per vendor (INV-6), it needs a
  provider hook for DDL that `jpa` can't express portably (INV-7), and it fails on a read-only connection or replica.
- **Fingerprint the tree across pages.** Hash the whole tree in each statement and refuse when the hash changes. This
  costs a full-tree aggregate per page, just as the check does, and it is still racy under READ COMMITTED: two writes
  that cancel out, or a write after the last page, go unseen. It would also refuse harmless writes elsewhere in the
  tree.
- **Keep refusing export and keyset on recursive sources,** and allow only the per-statement features (`one`,
  `byKeys`, fetch plans on `list`). This drops M26's point: exporting a tree, and paging it by keyset, are the cases
  that prompted Q-21.
- **The spike's `count(*)` against `count(distinct key)`.** It can't name the key, and a composite key's
  `count(distinct …)` isn't portable (§2).
- **The SQL `SEARCH DEPTH FIRST` order as a stable walk order.** It is native only on PostgreSQL, its signature
  differs between Hibernate 6.6 and 7.4, and a path order is just as derived as `@Depth` (§5).

## Unresolved questions

- **Spike: run** (`rfc/0014-spike-results.md`). Settled: §2's form works on every vendor and line, and on a composite
  key on PostgreSQL and MySQL (probes 1, 3); the empty-result asymmetry and every statement kind but the count over
  groups behave as §2 and §3 say (probes 4, 5); a concurrent break raises under READ COMMITTED and a snapshot
  completes (probe 8); a subtree move under READ COMMITTED repeats and skips nodes with no check firing (probe 9, §5's
  hazard observed). The three findings that needed a decision were decided at acceptance (D-147): a composite node key
  is `RECURSIVE_COMPOSITE_KEY`, false on H2 (§7); the count over groups over a source counts client-side, with
  `ProviderSupport` unchanged (§3); H2's cost is a documented test-database limit and the cycle check stays (§6).
- **AC-SRC-25's exact rule.** ArchUnit sees erased types, so it can't tell a `TypedQuery<Tuple>` from a
  `TypedQuery<Long>` at a call site. If it can't express "on a `Tuple` query", the slice states the rule by method
  (the raw reads only in `rows`, `single` and `resultStream`, and the untyped ones called with no `BuiltQuery`'s
  query), and says which form it took in the AC row.
- **The opt-in's name.** `tree(step, maxDepth)`, a sibling of `recursive(...)` (§1), chosen at acceptance (D-147); a
  modifier is rejected (§Alternatives).
- **Release.** M26 in 0.10.0 (D-145), before the freeze (M29, then M30 by D-148, then M31 by D-176). Every new public member is `@Incubating`, so the M31.1
  freeze review decides each one.
