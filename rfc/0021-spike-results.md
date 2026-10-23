# RFC 0021 acceptance spike: results

**A: keep the join and the root anchor, and amend six statements: the link claim holds only through the reflective
`on`, two successive `on` calls are ANDed on 6.6 and replace each other on 7.4, the join order is creation order
(manager profile before the node's), PostgreSQL removes the unreferenced join, MySQL and H2 pay a per-row lookup, and the
count-over-groups claim does not hold on 6.6 or on H2 for any joined source. B: keep, with the refusal of `exists`
reworded (it renders the same on all three vendors) and the "ON dropped off a correlated root" claim confirmed.**
Every entity-join statement kind of A.5's list returns the native SQL's rows on H2, PostgreSQL and MySQL, on both
lines. All 288 functional cells have the same SQL on both lines (the joined source's alias aside); six differ in
status, all in the count over groups, and 18 in value, all from the signatures, the `on` semantics and that count.

Throwaway research on `spike/rfc-0021`, one test-source module `spike-rfc0021/` (Maven profile `spike-rfc0021`), on
Hibernate 6.6.58.Final and 7.4.11.Final (Jakarta Persistence 3.2.0 on 7.4), against H2, PostgreSQL 16
(`postgres:16-alpine`) and MySQL 8.0.31. Production `model-query-*/src/main` and committed SQL snapshots are
unchanged. The library has no entity-join or join-filter API, so every probe calls Hibernate's Criteria API directly
(`Q.java`): the CTE join as `SourceRenderer#join` does it, and the entity join as the proposed
`EntityJoinRenderer#join` (reflective `JpaFrom#join(Class, SqmJoinType)` and a reflective `on`, called once). **What
was emulated:** every A.5 statement kind is the Details statement with that kind's extra predicate, order and limit (the
engine's own statement of that kind could not be built); the `through` child load and the fetch-plan parent read are
only `node_id in (...)` and `id in (...)` over the same statement; the count over groups is `createCountQuery()` on a
hand-built grouped query, not the executor's call. The dataset is not scaled down (15,000 nodes, 88,371 edges; seeds
in 0.4 to 1.2 s).

The [raw-data inventory and reproduction commands](0021-spike-data/README.md) hold the SQL, binds, values, timings and
plans. References use **F**: `rfc0021-<line>.tsv`, keyed `vendor | probe | case`; **T**: `rfc0021-timings-<line>.tsv`,
keyed `vendor | cell | case | scope`, medians of 7 alternating runs in ms (plans under `plans/<line>/<vendor>.txt`);
**L**: `link-probe-<line>.txt`; **J**: `javap-<line>.txt`.

## Matrix

PASS/FAIL/NOT_RUN per probe group (`summarize.py matrix`). A FAIL is a cell whose expectation did not hold or that
threw; the 3 FAILs on 6.6 and on H2 7.4 are all the count over groups (below), nothing else fails.

## matrix

| Probe | H2 6.6 | H2 7.4 | POSTGRESQL 6.6 | POSTGRESQL 7.4 | MYSQL 6.6 | MYSQL 7.4 |
|---|---|---|---|---|---|---|
| A.1 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 |
| A.2 | 22/2/0 | 22/2/0 | 22/2/0 | 24/0/0 | 22/2/0 | 24/0/0 |
| A.3 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 |
| A.4 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 | 6/0/0 |
| A.5 | 38/0/0 | 38/0/0 | 38/0/0 | 38/0/0 | 38/0/0 | 38/0/0 |
| B.1 | 15/1/0 | 15/1/0 | 15/1/0 | 16/0/0 | 15/1/0 | 16/0/0 |
| total | 93/3/0 | 93/3/0 | 93/3/0 | 96/0/0 | 93/3/0 | 96/0/0 |

## Findings

### A.1 Signatures: `join` links across lines, `on` does not, and `@IdClass` needs no `EntityDomainType` form

- `JpaFrom#join(Class, SqmJoinType)` has the descriptor `(Ljava/lang/Class;Lorg/hibernate/query/sqm/tree/SqmJoinType;)
  Lorg/hibernate/query/criteria/JpaEntityJoin;` on both lines: abstract on 6.6, a default on 7.4 (J). A call compiled
  against 6.6 and run on 7.4 links and returns an `SqmEntityJoin` (L, "direct: join"). The direct call of `on` does not:
  it compiled to `JpaEntityJoin.on(JpaExpression)JpaEntityJoin`, and 7.4 throws `NoSuchMethodError` (L). The reflective
  form (look `on(Predicate...)` up on the returned type) runs on both: it resolves to `JpaEntityJoin` on 6.6 and to
  `JpaJoin` on 7.4 (L; F `A.1/signatures`).
- Supertypes: 6.6 `JpaEntityJoin<T> extends JpaJoinedFrom<T, T>`, declaring its own `on(...)` (4 overloads); 7.4
  `JpaEntityJoin<L, R> extends JpaJoin<L, R>`, declaring none (J). At run time the class is `SqmEntityJoin` on both;
  `instanceof jakarta.persistence.criteria.Join` is `false` on 6.6 and `true` on 7.4, and a to-one join below it is a
  `Join` with `getParent()` equal to the entity join on both (F `A.1/signatures | runtime entity join`).
- `join(Class, SqmJoinType)` works for an `@IdClass` entity (`member_ic`, on `hierarchyId` and `profileId`) and an
  `@EmbeddedId` entity (`key.hierarchyId`, `key.profileId`), rows equal to the native join (F `A.1/idclass`,
  `A.1/embedded`). `join(EntityDomainType, SqmJoinType)` is also there and also works, and is not needed.
- 7.4 with Jakarta Persistence 3.2.0 also has `From#join(Class, JoinType)` returning `Join`; 6.6 on 3.1.0 does not.

### A.2 Rendering: creation order is rendering order, the root anchor is the same SQL as the CTE anchor

- **Order.** On all vendors and both lines the joins render in creation order: `hierarchy_node`, the CTE join, the
  entity join (manager's `profile`), then the node's `profile` and the two `profile_type` joins; creating the node's
  association join first puts it, and its type, before the CTE join (F `A.2/order`). The library creates the source and
  the entity join first (A.5), so the manager's profile renders before the node's, not after it as A.7's SQL shows.
- **INNER under a LEFT source.** Count with an entity join: LEFT/LEFT 15,000 (every node), LEFT source with INNER entity
  14,988 (the unmatched source rows are dropped), INNER source with either kind 14,988, equal on all vendors and both
  lines. Resolution 5's rule holds.
- **Comparison only, the CTE anchor.** `cteJoin.join(Profile.class, ...)` renders the **same statement**, character
  for character after the source alias, as the root anchor on all three vendors and both lines (12 of 12 pairs); the
  nested join group Resolution 5 feared did not appear. INNER under a LEFT source drops the same 12 rows.
- **Statement kinds** (emulated, F `A.2/kinds`, all vendors and lines): content page, count, root-id count
  (`count(distinct hn.id)`), both primary-key-first steps, keyset page, export page and stream (fetch size 100, 4,734
  rows), `byKeys` chunk, `one(q, key)`, `through` load and fetch-plan parent read each carry exactly one `join profile`
  for the entity join, and return the rows the native SQL returns (checked column by column).
- **Count over groups.** `JpaCriteriaQuery#createCountQuery()` over a grouped query that carries a CTE fails on **6.6
  on all three vendors** (`Could not find CTE for name 'cte0'`) and on **7.4 H2** (`Can't emulate lateral join for query
  spec with group by clause`), **with or without the entity join** (control cell). On 7.4 PostgreSQL and MySQL it runs,
  keeps the entity join (and its ON filter) inside the derived table, and counts 1,671 groups. With an entity join off a
  plain column and no CTE (no source involved) it works on every cell: `select count(*) from (select … left join profile
  … group by …)`, 15,000 groups. So the entity join survives the copy, and the failure is the CTE's, not the join's.

### A.3 Paths and keys

On every vendor and both lines, rows equal the native SQL's: `type.name` below the entity join beside the node's own
type (two distinct `profile_type` joins, 4,712 rows), two entity joins of `Profile` (manager off the source, node off the
root; distinct aliases `p1_0`, `p2_0`), an `@IdClass` and an `@EmbeddedId` target (above), a filter on a joined column,
an order on the nullable `firstName` with nulls last (265 of 4,734 rows have a NULL manager name and sort last), a keyset
walk over `(firstName nulls last, id)` in 5 pages of 1,000 with no row lost or repeated, and a grouped model grouped by
the joined column (557 groups). F `A.3/*`.

### A.4 `count` and cost

- **`count`.** `count(m)` renders `count(p1_0.id)` and `count(mt)` below it `count(t1_0.id)` on both lines and all vendors
  (same SQL, 0 statement differences); an unmatched LEFT row counts 0 (15,000 nodes, 14,988 managers) and a per-row
  `count(m.id)` grouped by node is 0 for the 4 unmatched nodes and 1 for the rest (F `A.4/count`). For the `@IdClass`
  entity, `count(ic.hierarchyId)` is right (12,855); `count(ic)` renders `count((a, b))` on PostgreSQL and H2 and counts
  all 15,000 rows including the unmatched ones, and a `case` form on MySQL (12,855). So the first-id-attribute rule is
  required, not a convenience.
- **Join removal (PostgreSQL).** An unreferenced LEFT entity join to `profile` is removed in the count, the page and
  the export, with its ON filter present, on both lines: the plan has 0 scans of `profile` and `profile_type`
  against 2 and 2 when the columns are read (T digests; `plans/*/postgresql.txt`).
- **Cost of attaching it (T, 6.6 and 7.4 agree within 2 points), entity join attached and unreferenced against no
  entity join:**

  | Statement (scope) | PostgreSQL | MySQL | H2 |
  |---|---|---|---|
  | page of 20 (hierarchy 1) | 2.83 vs 2.84 ms | 8.07 vs 8.01 ms | 6.17 vs 5.51 ms (+12%) |
  | count (hierarchy 1) | 2.53 vs 2.42 ms (+5 to 8%, planning) | 12.5 vs 11.8 ms (+5%) | 6.09 vs 5.42 ms (+12%) |
  | full export, 4,734 rows | 4.77 vs 4.90 ms | 14.4 vs 13.9 ms (+4%) | 6.27 vs 5.59 ms (+12%) |
  | count (all hierarchies) | 0.90 vs 0.88 ms | 52.1 vs 49.6 ms (+5%) | 16.5 vs 14.3 ms (+15%) |
  | full export, 14,207 rows (all) | 13.0 vs 14.1 ms | 61.5 vs 59.9 ms (+3%) | 17.9 vs 15.8 ms (+13%) |

  MySQL runs the join as a single-row primary-key lookup per outer row (`loops=4734`, about 0.1 µs each); H2 does
  the same; PostgreSQL does nothing. The A.5 cost text ("one index lookup per row on MySQL and H2") is right and small.
- **What dominates is the source, not the entity join.** The library's page of 20 reads the whole grouped CTE:
  Details with the manager 5.2 ms on PostgreSQL and 8.2 ms on MySQL against the native SQL's 1.1 and 0.55 ms (hierarchy
  1); without the hierarchy filter MySQL takes 42 ms against 1.1 ms (the CTE is materialized for every statement,
  RFC 0020's documented cost). H2 is faster than the native SQL on the page (8.3 vs 14.3 ms). A count over all
  hierarchies on MySQL is 52 ms against 27 ms native.

### A.5 Parity against the native SQL

Details, Parents (INNER source, 300 node ids) and Simple (no source) return **exactly the native SQL's rows, in the
same order, with the same count and the same page of 20**, for `include_deleted` 0 and 1 and six filter combinations
each (none, hierarchy, hierarchy + manager, node, first name, all four), on all three vendors and both lines: 38 of 38
cells (F `A.5/*`). Row counts: Details 14,207 (0) and 15,000 (1); Parents 834 and 888; Simple 14,207 and 15,000, equal to
the native `count(distinct)` because `(hierarchy_id, node_id)` is unique in this data. Without Part B the deleted checks
cannot be placed in the ON: with no check 391 of the 4,734 rows of hierarchy 1 differ (a deleted profile is read, not
blanked), and as `where` terms 391 nodes are dropped (4,343 against 4,734), as A.7 item 2 says.

### B.1 Join filters (the ON of Part B, emulated as one predicate)

- **Rows.** A deleted-profile filter in the entity join's ON alone, in an association join's ON alone, and in both, and
  with a static `on` (`last_name like 'L00%'`) in one `on` call, return the native rows on every vendor and line.
  Deleted profiles are blanked, not dropped: 4,734 rows = the live nodes of hierarchy 1; the manager's name is blanked
  on 180 rows (id kept) and the node's on 222 (F `B.1/*`).
- **Bound once.** The value is written once per place and bound once per statement: content page 4 `is_deleted`
  placeholders (edge CTE, manager ON, node ON, root where), count 3 (no node join), ids step 3, rows step 4, count over
  groups 2 (7.4 PostgreSQL and MySQL) (F `B.1/bind-once`).
- **Two `on` calls differ by line.** On **6.6 the second `on(...)` is ANDed** with the first, for the association join and
  for the entity join; on **7.4 it replaces** the first. For the entity join that replaces the key predicate: `left join
  profile p1_0 on p1_0.is_deleted<>?`, a cross join with the live profiles (23,825,000 rows for hierarchy 1; the first
  version of the cell ran out of heap on 7.4). One `on` call is therefore required, not stylistic (F `B.1/two-on-calls`).
- **`exists` in an ON** (record only): in the entity join's ON, correlated to the join (`exists (select 1 from
  profile_type t where t.id = p.type_id and t.name <> 'Type 3')`), and in an association join's ON, correlated to the
  root (`exists (select 1 from hierarchy_edge …)`), the SQL is the same on H2, PostgreSQL and MySQL and on both lines,
  and the rows equal the native SQL's (F `B.1/exists-in-on`).
- **ON of a join off a correlated root is dropped.** In `exists (select 1 from <correlated hn> join node n on … and
  n.is_deleted <> true where n.first_name = ?)`, the sub-query renders `from profile n1_0 where n1_0.first_name=? and
  n1_0.id=hn1_0.node_id`: the ON filter is gone, with no error, on all three vendors and both lines (F
  `B.1/exists-correlated-join`). B.2's refusal is right.

## Line parity

All 288 functional cells (`summarize.py parity`): the SQL is identical on 6.6 and 7.4 once the joined source's alias is
normalized (`cte01_0` on 6.6, `var_1_11_0` on 7.4), 0 statements differ. Status differs in 6 cells, all the count over
groups through `createCountQuery` (6.6 fails on all vendors, 7.4 passes on PostgreSQL and MySQL, 7.4 H2 fails with another
error). Values differ in 18: the signatures (A.1), the two-`on` semantics (B.1) and that count's message. The 72 timed
statements are the same SQL with the same row counts and plan shapes, medians within a few percent. The probe module
compiles from one source on both lines (it reaches `join(Class, SqmJoinType)` and `on` reflectively; `Q.java`), and one
`LinkProbe` class file compiled against 6.6 runs on 7.4 for `join` but not for a direct `on` (L).

## Timings

Medians in ms (T); `lib:` rows are Criteria statements carrying the proposed entity join, `native` is the §Motivation
SQL. 6.6 and 7.4 columns agree.

## timings

## Consequences for the RFC

Each item names the RFC line and the wording to put there; every open point is a decision to close now.

1. **A.6 "Hibernate", line 260-262 ("should link on 7.4 (to confirm in the spike)").** Replace with: "…with the same erased
   descriptor, so a call compiled against 6.6 links and runs on 7.4 (spike A.1: a direct `JpaEntityJoin#on` compiled against
   6.6 throws `NoSuchMethodError` on 7.4, so the `on` is looked up reflectively on the returned type)."
2. **A.6 line 265-267 ("a second `on` call, which would replace the first").** Replace the clause with: "…rather than set by
   a second `on` call: Hibernate 6.6 ANDs successive `on` calls and 7.4 replaces the first (spike B.1), so the engine
   calls `on` exactly once on every line." Add the same sentence to B.1's "Rendering" (line 375-377).
3. **A.6 line 268-276 and Resolution 5 (line 548-551), "The anchor".** The spike renders the CTE anchor and the root anchor
   identically on both lines and all vendors. Decision to close now: keep the root as the anchor (the contract does not
   depend on a provider's grouping of nested joins); delete "which could render a nested join group that keeps those rows
   with NULL entity columns" and "A Hibernate line that cannot render the join off the root refuses an `INNER` entity join
   with `MQ4014`" from A.6, keeping the refusal for a provider whose hook cannot attach to the root; replace "The spike
   confirms that creation order is rendering order" with "Spike A.2 confirms it on all vendors and both lines".
4. **A.7 SQL (line 305-323) and its lead-in "order and the profile join's place to confirm".** The rendered order is the
   creation order: the CTE join, the manager's `profile` (and its filter), then the node's `profile`, `profile_type`,
   then the manager's `profile_type`. Reorder the SQL to `left join edge x … left join profile p on p.id = x.mq_startVertex
   and p.is_deleted <> ? left join profile c … left join profile_type ct … left join profile_type pt …` and say "the order is
   creation order (spike A.2)".
5. **A.4 "Agg.count over a joined entity", line 213-218.** Keep the rule, add its evidence: "`count(entity)` renders
   `count(<id>)` on both lines for a single id, but `count((a, b))` for a composite id on PostgreSQL and H2, which counts
   an unmatched LEFT row; the first-id-attribute rule is required (spike A.4)." The line-215 argument stands as written:
   the entity join is a jakarta `Join` on 7.4 only (spike A.1, runtime check).
6. **A.5 line 232-234 ("to confirm in the spike for a table").** Replace with: "PostgreSQL removes an unreferenced LEFT join
   to `profile` on its primary key, with an ON filter, in the count, the page and the export (plan: 0 scans, spike A.4);
   MySQL and H2 do a primary-key lookup per outer row: 0 to 1% of a page of 20, 4 to 5% of a count and 3 to 4% of an export
   on MySQL, and 12 to 15% on H2." The rest of the sentence stays.
7. **A.5 line 229-231, "the count over groups (through `ProviderSupport#countQuery`)".** Decision to close now: the entity
   join adds no new rule here and the list item stays, but A.5 gains: "A grouped query that joins a source counts its groups
   with `createCountQuery()` only where Hibernate can: it fails on 6.6 on every vendor and on 7.4 with H2 for any joined
   CTE, entity join or not (spike A.2), and the entity join survives the copy where it runs (7.4 PostgreSQL and MySQL).
   Where it fails the executor counts the groups client-side (R-EXE-03)." (This is a **finding about R-SRC-22 today**, not
   caused by this RFC: the executor's own path was not driven here and must be checked before the sentence is final.)
8. **B.1 "Skipping/Rendering", line 375-377, and B.2 line 387-388 ("an `exists` in an `ON` is not rendered by every Tier-1
   vendor the same way (to confirm in the spike before it is ever allowed)").** Replace with: "An `exists` in an `ON`
   renders the same SQL on H2, PostgreSQL and MySQL on both lines (spike B.1), so the refusal is a scope decision, not a
   vendor gap: Part B takes values and constants only, and lifting it is a later RFC." The line-391-392 claim (Hibernate drops
   the ON of a join off a correlated root) is confirmed on both lines and all three vendors; add "(spike B.1)".
9. **Spike plan, line 433-466.** Mark every item done; nothing is deferred. `AC` placeholders:
   add one for "a second `on` call is not made" (an engine unit test that the predicate reaches `on` once) and one for
   "count over a composite-id joined entity counts its first id attribute" (the PostgreSQL/H2 `count((a, b))` case).
10. **Resolution 3 (line 543-545), a portable default.** Unchanged, with a new reason: the 3.2 `From#join(Class, JoinType)`
    default would also need the same one-`on` rule, since a `Join#on` on 3.2 replaces. Recommended stays `MQ4014` now.

No change is proposed to A.1 to A.3 (the annotation shape, the model, `EntityJoin`), to A.5's other statements, to
Resolutions 1, 2, 4, 6, 7, 8, or to the id-form refusal of B.2.
