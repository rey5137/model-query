# RFC 0013 acceptance spike: results

Throwaway research code, kept on a local branch and not published. Probe class:
`model-query-tck/src/test/java/com/rey/modelquery/tck/spike/Rfc13SpikeTest.java` (+ `SpikeEntities`). It drives
Hibernate's criteria API directly (`HibernateCriteriaBuilder`, `JpaCriteriaQuery`, `JpaCteCriteria`, `JpaWindow`) and
records PASS or FAIL per probe. Raw output, including the SQL Hibernate sent for every probe, is in
`rfc/0013-spike-data/*.tsv` (columns: SPIKE, vendor, Hibernate version, item, probe, status, time, detail, SQL).

Targets: H2 (embedded, 2.x bundled with the TCK), PostgreSQL 16 (`postgres:16-alpine`), MySQL `mysql:8.0.31`.
Hibernate lines: 6.6.58.Final (default build) and 7.4.11.Final with Jakarta Persistence 3.2.0. The probe source is
identical on both lines (it compiles and behaves the same without change).

- `full-run-*.tsv`: the full run of 11 items on all three vendors, per Hibernate line.
- `mysql-rerun-*.tsv`: a MySQL-only rerun that added the depth-997/998 boundary probes and the path-overflow probes.

## Matrix

P = PASS, F = FAIL, W = WORKAROUND (the item works with a changed shape; see the notes). Each cell is the same for
Hibernate 6.6 and 7.4 (no cell differs between the lines), so the two columns of a vendor are shown as one value.

| # | Item                                              | H2  | PG 16 | MySQL 8.0.31 |
|---|---------------------------------------------------|-----|-------|--------------|
| 1 | `with(tupleQuery)`, `from(cte).get(alias)`        | P   | P     | P            |
| 2 | `(boolean, CriteriaQuery...)` set op as CTE body  | P   | P     | P            |
| 3 | `withRecursiveUnionAll` + `cycle`/`cycleUsing` + level, probe sub-selects in content and count | P | P | P |
| 4 | `JpaWindow` ROWS/RANGE; rowNumber, rank, sum, firstValue, `windowFunction` lag/lead/min/max | W | P | P |
| 5 | window value in outer where / order by / keyset   | P   | P     | P            |
| 6 | `select count(*)` over a CTE-rooted query         | P   | P     | P            |
| 7 | `exists` on a to-many path inside a CTE body      | P   | P     | P            |
| 8 | discriminator constant (literal, bound, cast)     | P   | P     | P            |
| 9 | nested parenthesized set operations               | P   | P     | P            |
| 10 | emulated cycle path, depth 1000                  | W   | P (native) | W (limit) |
| 11 | Q-21 probe                                       | P   | P     | P            |

## Notes per item

1. `outer.with(body)` returns a `JpaCteCriteria<Tuple>`; the aliases of the body's `multiselect` become the CTE
   column list (`with cte0 (id,category,amt) as (...)`), and `outer.from(cte)` then `r.get("amt")` resolves them.
2. `cb.union(true|false, a, b)`, `cb.intersect(false, a, b)`, `cb.except(false, a, b)` return `JpaCriteriaQuery<T>` on
   both lines and work as a CTE body. On 7.4 the two-argument `union(CriteriaQuery, CriteriaQuery)` forms also exist
   and return `JpaCriteriaQuery`, but `union(CriteriaSelect, CriteriaSelect)` returns `CriteriaSelect`, so overload
   resolution on raw `CriteriaQuery` arguments is easy to get wrong. The `(boolean, ...)` forms are unambiguous.
3. All of these worked, including on a cyclic graph: the cycle-closing row is emitted with the mark set, the recursive
   term's `not(isCycle)` stops further recursion from it, and the outer filter `level <= maxDepth and not isCycle`
   hides it. Reading the mark in the outer query works with `r.get("isCycle")` once `cte.cycle("isCycle", attr)` has
   been called (`attr = cte.getType().getAttribute("id")`). `cycleUsing(mark, path, attr)` works too.
   - Probe in the content statement: `select id, name, level, (select max(level) from cte), case when exists(select 1
     from cte where isCycle) then 1 else 0 end from cte where ... order by level, id`. On a depth-3 tree with
     `maxDepth` 2 the `max(level)` column is 3 (= maxDepth+1) on every row; on the cyclic graph the exists column is 1.
   - Probe in the count statement: `select count(*), (select max(level) from cte), case when exists(...) ... from cte
     where level <= ? and not isCycle` works (an aggregate beside uncorrelated scalar sub-selects is legal on all three).
   - The scalar sub-selects over the CTE are built with `q.subquery(Integer.class)` cast to `JpaSubQuery` and
     `sub.from(cte)`; `JpaSubQuery.from(JpaCteCriteria)` is needed (it is not on the JPA `Subquery` interface).
4. FAIL on H2 only: `windowFunction("lag"|"lead", ..., w)` with a frame (`rows unbounded preceding`,
   `rows between current row and unbounded following`): `Syntax error in SQL statement "LAG(AMOUNT) OVER (ORDER BY ID
   ROWS UNBOUNDED PRECEDING[*])"`. H2 rejects a frame on `lag`/`lead` (the standard says these ignore it; PostgreSQL and
   MySQL accept and ignore it). Workaround: never emit a frame for `LAG`/`LEAD` (the no-frame form with an offset
   argument, `lag(x, 2) over (order by id)`, passes on all three). Everything else passed on all vendors: `rowNumber`,
   `rank`, `denseRank`, `sum` with `frameRows`/`frameRange`, `firstValue` with `ROWS UNBOUNDED..UNBOUNDED`, `min` with
   `ROWS 1 PRECEDING..1 FOLLOWING`, `max` with `RANGE`, `last_value` with `ROWS UNBOUNDED..UNBOUNDED`. Hibernate renders
   `rows unbounded preceding` (short form) for `UNBOUNDED PRECEDING..CURRENT ROW`.
   `cb.sum(expr, JpaWindow)` returns `JpaExpression<Number>` (cast when aliasing).
5. A CTE body with `row_number() over (order by amount, id)` and a ROWS-framed `sum` is read by the outer query, which
   filters (`rn > 7`), orders (`order by rn desc`) and runs the keyset
   `rn > ? or (rn = ? and id > ?) order by rn, id` correctly; `setMaxResults/setFirstResult` also work on top
   (rendered with `limit`/`offset` or `fetch first`).
6. `cb.createQuery(Long.class)`, `q.with(body)`, `q.from(cte)`, `q.select(cb.count())` renders
   `with cte0 (...) as (...) select count(*) from cte0 where ...`. Works with a windowed body and a plain body.
7. `sub = body.subquery(Integer.class)`, `ci = sub.correlate(root)`, `ci.join("lines")`, `cb.exists(sub)` inside the
   CTE body renders `where exists(select 1 from spike_line l1_0 where l1_0.qty>? and si1_0.id=l1_0.item_id)`.
   `cb.isNotEmpty(root.get("lines"))` also works.
8. The RFC's fear does not materialize. All of these passed on PostgreSQL 16 on both lines:
   `cb.literal("ITEM")` (rendered as an inline literal `'ITEM'`), `cb.value("ITEM")` (a bound parameter, which
   Hibernate renders as `cast(? as varchar)` in a select-list position, so PostgreSQL gets a type), `cb.cast(...)` of
   either, a named `cb.parameter(String)` bound with `setParameter`, the same with
   `hibernate.criteria.value_handling_mode=INLINE`, and an integer constant (`1` inline, `cast(? as integer)` bound).
   Hibernate adds the cast itself for select-list parameters. So no typed-literal or cast workaround is needed in the
   source renderer; `cb.literal(v)` is the simplest and inline, which also keeps the SQL stable across calls.
9. `(A union B) except C`, `A union (B intersect C)`, `(A unionAll B) intersect (C unionAll D)`, a three-level
   `((A union B) except C) union D`, and a varargs `union(false, a, b, c)` all work, also on MySQL 8.0.31. Hibernate
   emits the parentheses itself: `with cte0 (...) as (((select ... union select ...) except select ...) union select
   ...) select ...`. MySQL 8.0.31 executes these and returns the same rows as H2 and PostgreSQL.
10. See "Item 10 numbers" below. PASS everywhere except on MySQL, where the recursion limit bites one level earlier
    than the RFC assumes.
11. See "Item 11 answer".

## Item 10 numbers

SQL Hibernate generates for the cycle clause (6.6 shape; 7.4 differs only in CTE alias names `var_1`, and `false`
instead of `0` for MySQL's seed mark):

- **PostgreSQL 16 (native):** `... ) cycle id set isCycle to true default false using path select ...`. No path column
  in the CTE column list, and the cost is the same as without `cycle`.
- **H2 (emulated):** CTE columns become `(id,name,level,isCycle,path)`; seed adds `false, array[sn1_0.id]`; the
  recursive term adds `case when array_contains(cte.path, sn2_0.id) then true else false end, cte.path||array[sn2_0.id]`
  and `and not(cte.isCycle)`. The path is a native H2 array holding the cycle column(s), one element per level, so
  its width is `depth x (number of cycle columns)` and each row copies and scans it: O(depth) per row, O(depth^2) per
  statement.
- **MySQL 8.0.31 (emulated):** the path is `cast(concat(char(0), coalesce(concat(id,''),char(0)), char(0), char(0)) as
  char(22000))` in the seed, extended by `concat(cte.path, char(0), coalesce(concat(id,''), char(0)), char(0), char(0))`;
  the mark is `case when cte.path like concat('%', escaped(char(0)+id+char(0)+char(0)), '%') escape '~' then 1 else 0 end`.
  So the path column is `char(22000)` (fixed), with each node costing `len(id) + 3` characters (a LIKE scan per row,
  O(depth) as well). Nothing was truncated or lost in the tests: with `cte_max_recursion_depth` raised to 100000, a
  4001-node chain of 5-digit ids (about 32000 path characters, wider than 22000) returned all 4001 rows in 0.6 s, and a
  3500-node cycle (about 28000 characters) was still detected (`count=3500`, `mark=1`). I did not find the width where
  detection breaks; at the default limit (1000 levels) a 19-digit key needs 22000 characters, which is the declared
  width.

Timings for a chain of single-parent nodes (seed at id 1000, `maxDepth` = N, recursion to level N+1; query time as
measured by the probe, Hibernate 6.6 / 7.4):

| maxDepth | H2 (emulated)          | PG 16 (native) | MySQL 8.0.31 (emulated)  |
|----------|------------------------|----------------|--------------------------|
| 50       | 53 ms / 37 ms          | 21 / 4 ms      | 34 / 3 ms                |
| 500      | 2.0 s / 2.1 s          | 35 / 32 ms     | 38 / 14 ms               |
| 997      | -                      | -              | 39 / 50 ms               |
| 998      | -                      | -              | 38 / 39 ms (PASS)        |
| 999      | 14.0 s / 12.6 s        | 85 / 87 ms     | FAIL, error 3636         |
| 1000     | 13.2 s / 12.4 s        | 80 / 86 ms     | FAIL, error 3636         |
| 1100 (data ends at level 1100) | 15.5 s / 16.3 s | 95 / 100 ms | FAIL, error 3636 |

(997 and 998 ran in the MySQL rerun only.) Baseline without any `cycle` clause on the same chain: H2 3-5 ms at 500 and
999 (so the emulation costs about 4000 times more on H2 at depth 1000); MySQL 0.3-1.1 s at 500/999 on the rerun (the
numbers are noisy because this ran after the other probes; the emulated-cycle runs were 15-50 ms, so the MySQL cycle
emulation is not the dominant cost there). An H2 run before adding an index on `spike_node.parentId` took 74 s at depth
999 (the recursive join scanned the table), so H2 emulation cost also depends on the join side being indexed.

**MySQL ceiling is one lower than the RFC states.** MySQL counts the final empty iteration. With the default
`cte_max_recursion_depth = 1000`, `maxDepth = 998` (rows up to level 999) passes and `maxDepth = 999` (level 1000) fails
with `Recursive query aborted after 1001 iterations` (SQLSTATE HY000, error 3636), even though the recursive term
carries `level < maxDepth + 1` and so stops producing rows at level 1000: the 1001st iteration still runs and finds
nothing. So the usable rule is `maxDepth + 2 <= cte_max_recursion_depth`, not the RFC's `maxDepth + 1 <=`. At
`maxDepth + 1 = 1001` the failure is the same error 3636 (also in the count statement). The failure is a plain
`GenericJDBCException` wrapping `SQLException`, not a Hibernate-specific type.

## Item 11 answer

Yes, it is possible in one statement. `select (select count(*) from cte), (select count(distinct id) from cte)` over a
recursive CTE works on all three vendors and both lines (the sub-selects are legal inside the same statement, as in
item 3). On a diamond graph (300 -> 301, 300 -> 302, 301 -> 303, 302 -> 303) the unmarked rows give `count = 4`,
`count(distinct id) = 3`. With a cycle (400 -> 401 -> 400) the unmarked rows give 2 / 2 but all rows including the
cycle-closing marked row give 3 / 2, so the comparison must run over all rows (marked ones included), not over the
`not isCycle` rows, to see a repeated node. This detects a repeated key reached by two paths (diamond) and by a cycle
alike, as long as the recursion is bounded (here by a level cap). The cost is another full scan of the CTE per scalar
sub-select, and it needs the whole recursion to finish, so it does not make a graph exportable on its own; it only turns
"unique key" from a processor-time claim into a runtime check (`MQ2702`-style) in the same snapshot.

## API differences between 6.6 and 7.4 that `SourceSupport` must hide

None of these were hit by the probe, which has one source for both lines; listed by inspecting the two jars:

- `HibernateCriteriaBuilder.search(JpaCteCriteriaAttribute, SortDirection, NullPrecedence)` (6.6) became
  `search(..., SortDirection, jakarta.persistence.criteria.Nulls)` (7.4). Only matters if `SourceSupport.recursive`
  ever uses SEARCH ordering (the RFC does not).
- `JpaWindow.frameExclude(FrameExclusion)`: the enum moved from `org.hibernate.query.sqm.FrameExclusion` (6.6) to
  `org.hibernate.query.common.FrameExclusion` (7.4). The RFC does not use frame exclusion.
- 7.4 adds two-argument `union/intersect/except` overloads: `(CriteriaSelect, CriteriaSelect)` returning
  `CriteriaSelect<T>` and `(CriteriaQuery, CriteriaQuery)` returning `JpaCriteriaQuery<T>`. The `(boolean, CriteriaQuery,
  CriteriaQuery...)` forms are identical on both lines and return `JpaCriteriaQuery`. Code written against them compiles
  and runs unchanged on both.
- 7.4 adds `from(JpaSetReturningFunction)`; unused.
- Everything the spike exercised (`with`, `withRecursiveUnionAll`, `cycle`, `cycleUsing`, `getCycle*`, `JpaCteCriteria.getType().getAttribute`,
  `createWindow`, `frameRows/frameRange`, `frameUnboundedPreceding/CurrentRow/UnboundedFollowing/BetweenPreceding/Following`,
  `rowNumber/rank/denseRank/firstValue/windowFunction`, `sum(expr, window)`, `JpaSubQuery.from(cte)`) has the same
  signature and behaviour on both lines. The SQL differs cosmetically only (CTE alias `cte0` vs `var_1`, MySQL `0` vs
  `false` for the seed mark).
