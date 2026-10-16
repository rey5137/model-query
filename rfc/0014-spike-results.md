# RFC 0014 acceptance spike: results

Throwaway research code, kept on the local branch `spike/rfc-0014` (worktree `../model-query-spike-0014`) and not
published. Probe class: `model-query-tck/src/test/java/com/rey/modelquery/tck/spike/Rfc14SpikeTest.java` (+
`SpikeEntities`, which adds a two-column `@IdClass` node `SpikeCNode` to RFC 0013's entities). Like RFC 0013's spike it
drives Hibernate's criteria API directly (`JpaCriteriaQuery#withRecursiveUnionAll`, `JpaCteCriteria#cycle`,
`JpaSubQuery` with `groupBy`, `having`, `orderBy`, `fetch`) and records PASS or FAIL per probe. Every probe asserts the
check values it expects (row counts, the named key, the error the §2 read order picks), so a PASS is not vacuous.

Raw output, including the SQL Hibernate sent for every probe, is in `rfc/0014-spike-data/full-run-*.tsv` (columns:
SPIKE, vendor, Hibernate version, probe, case, status, time, detail, SQL). `plans-*.txt` holds the `EXPLAIN ANALYZE`
output of probe 6 (H2, PostgreSQL, MySQL) with the inlined SQL it explains.

Targets: H2 (embedded, 2.x bundled with the TCK), PostgreSQL 16 (`postgres:16-alpine`), MySQL `mysql:8.0.31`
(default `sql_mode`, `ONLY_FULL_GROUP_BY` on). Hibernate 6.6.58.Final (default build) and 7.4.11.Final with Jakarta
Persistence 3.2.0. The probe source is identical on both lines.

## Matrix

P = PASS, F = FAIL, P* = PASS with a finding below. No cell differs between Hibernate 6.6 and 7.4, so each cell is
both lines.

| #  | Probe                                                                | H2  | PG 16 | MySQL 8.0.31 |
|----|----------------------------------------------------------------------|-----|-------|--------------|
| 1  | check form `group by … having count(*) > 1 order by … fetch 1` (6 cases) | P | P   | P            |
| 2  | fallback: `case when exists(… having …) then 1 end`, naming statement | P  | P     | P            |
| 3  | composite node key (two columns), cycle clause on both               | F   | P     | P            |
| 3' | composite key, check form without the cycle clause (isolation)       | P   | n/a   | n/a          |
| 4  | placement on every statement kind (8 kinds + count over groups ×3)   | P   | P     | P            |
| 4' | count over groups via `createCountQuery()` + checks re-selected      | F   | F     | F            |
| 5  | empty result: empty list, count still carries the check              | P   | P     | P            |
| 6  | re-walk per reference: timings and plans                             | P*  | P     | P            |
| 7  | keyset export cost, with and without the checks                      | P*  | P     | P            |
| 8  | concurrent break between pages (RC raises, RR completes)             | –   | P     | –            |
| 9  | concurrent subtree move, keyset by `(level, id)`                     | –   | P     | –            |
| 10 | line parity                                                          | P   | P     | P            |

## Notes per probe

1. **The check form works on every vendor and line.** Rendered (6.6; 7.4 differs only in the CTE's name, `var_1`):
   - H2: `(select c.id c0 from cte0 c group by c0 having count(*)>? order by 1 fetch first ? rows only)`
   - PostgreSQL: `(select c.id from cte0 c group by 1 having count(*)>? order by 1 fetch first ? rows only)`
   - MySQL: `(select c.id from cte0 c group by 1 having count(*)>? order by 1 limit ?)`

   Hibernate renders the `group by` by alias or position; all three accept it. Cases, each asserted: a tree, `dup`
   null; overlapping seeds `{1, 2}`, `dup = 2`; an edge diamond, `dup = 203`; a cycle, `cycle = 100` and `dup = 100`,
   the read order picks `MQ2702`; a walk reaching `maxDepth + 1`, `depth = 5` and `dup` null (`MQ2701`); the diamond
   under an outer `where` that drops both copies of 203 and 204, three rows returned and `dup = 203` (whole-CTE scope).
2. **The detect form and the naming statement work too**, so the fallback is available but not needed. The naming
   statement (`select id, depth, cycle … group by id having count(*) > 1 order by id`, limit 1) returns `[203, null,
   null]` on the diamond and a non-null cycle check on the cycle. For comparison, a row-value `IN` against a CTE
   (`where (a, b) in (select … from cte)`, HQL) renders and runs on all three. The `count(*)`-against-grouped-count
   form was not tried, as the detect form passed everywhere.
3. **H2 fails on a two-column cycle clause, before any check.** Hibernate emulates `cycle` on H2 with an array of
   `row(a, b)` values (`array[row(a,b)]`, `array_contains(path, row(a,b))`), and H2 rejects it: `Column count does not
   match`. The isolating case with the cycle clause and no checks fails the same way; without the cycle clause, the
   check form groups on both columns and is right on H2 (overlapping seeds: `dup = 1`, `detect = 1`; seeds `(2,1)`,
   `(2,2)`, which share `a` only: `dup` null). PostgreSQL (native `cycle a, b`) and MySQL pass every composite case,
   including the composite cycle (`MQ2702` wins). This is a limit of M25's recursive rendering on H2, not of the check.
   `count(distinct …)` for comparison: PostgreSQL renders `count(distinct (a,b))`, MySQL `count(distinct a,b)`, H2
   accepts the row value; all return 6, confirming that form differs per dialect.
4. **Placement.** All three checks are present and right (`dup = 203`, the other two null) on the first row of: the
   content statement, the count, primary-key-first step 1 (`offset 1 limit 3`), step 2 (`id in (…)`), a keyset page
   (`(level, id) > (1, 201)`, limit 3), a grouped outer statement (`group by level`, scalar sub-selects in the select
   list, MySQL with `ONLY_FULL_GROUP_BY` in `sql_mode`), a count-free page (`offset 2 limit 2`), and `one(q, 203)` at
   limit 2 (two rows, `MQ2703` picked before the size test). **Count over groups:** the form that works builds the
   CTE and the checks on the outer count statement and the grouped query as a derived table over that CTE:
   `with recursive cte … select count(*), (check), (check), (check) from (select level from cte … group by 1)
   derived1_0(lv)`. It returns the checks with one group, four groups and zero groups (count `0`, `dup = 203`).
   `createCountQuery()` on a finished grouped query and then re-selecting the checks at its outer level fails on every
   vendor and line at interpretation: `Could not find CTE for name 'cte0'` (the copy moves the CTE into the derived
   table, out of the outer level's scope).
5. **Empty result.** Over the diamond with `id = -1`: the list is empty and no check is read; the count of the same
   query returns one row, count `0`, `dup = 203`; the zero-group count over groups of probe 4 likewise.
6. **Re-walk.** PostgreSQL and MySQL compute the recursive CTE once per statement; H2 does not, and worse, it
   re-evaluates each check sub-select (re-walking the CTE) once per outer row.
   - PostgreSQL plan: one `Recursive Union` (`loops=1`); each check is an `InitPlan` over a `CTE Scan` (`loops=1`).
   - MySQL plan: `Materialize recursive CTE cte0 … loops=1` on the outer reference, and `never executed` on the three
     check references (the materialization is shared).
   - H2 plan: each sub-select carries its own expansion of the recursive query, and the content statement's time grows
     with rows × tree size × checks, while the count statement (one outer row) stays fast.

   Content statement, minimum of 3 runs, ms (Hibernate 6.6; 7.4 within ±15 %):

   | Tree               | PG 0 / 3 checks | MySQL 0 / 3 checks | H2 0 / 1 / 2 / 3 checks   |
   |--------------------|-----------------|--------------------|---------------------------|
   | balanced 250       | –               | –                  | 2 / 41 / 82 / 123         |
   | balanced 500       | –               | –                  | 1 / 147 / 284 / 497       |
   | balanced 1 000     | 3 / 3           | 5 / 4              | 2 / 571 / 1 073 / 1 802   |
   | balanced 2 000     | –               | –                  | 1 / 2 199 / 4 380 / 8 040 |
   | balanced 10 000    | 20 / 22         | 26 / 28            | not run (extrapolated ~3 min) |
   | balanced 100 000   | 209 / 253       | 315 / 376          | not run                   |
   | chain 900          | 28 / 39         | 40 / 42            | 6 / 4 403 / 8 839 / 13 864 |

   The detect form costs the same as the `dup` form on every vendor (within noise). On PostgreSQL and MySQL the third
   check adds 5-20 % at 100k nodes. On H2 each check costs the same, so `dup` adds half of what M25.5's two checks
   already cost; doubling the tree quadruples the time. Count statement with 3 checks on H2: 1-5 ms at 250-2 000 nodes,
   20 ms on the chain.
7. **Export.** Keyset by id, pages of 100 and 1 000, whole tree, total ms (6.6 / 7.4):

   | Tree, page              | PG none / 3 checks | MySQL none / 3 checks | H2 none / 3 checks |
   |-------------------------|--------------------|-----------------------|--------------------|
   | balanced 10 000, 100    | 1 512 / 1 884      | 2 144 / 2 428         | –                  |
   | balanced 10 000, 1 000  | 178 / 203          | 250 / 272             | –                  |
   | balanced 1 000, 100     | –                  | –                     | 29 / 10 555        |
   | balanced 1 000, 1 000   | –                  | –                     | 2 / 1 870          |

   PostgreSQL and MySQL: the export is quadratic through the walk itself, as RFC 0013 §3 already states; the checks add
   10-25 %. H2: a page's limit doesn't bound the check's per-row evaluation, so each page costs the whole tree squared.
8. **Concurrent break (PostgreSQL).** Seeds 300 and 310, keyset by id, page 4. Page 1 `300, 301, 302, 310`; another
   connection moves 310 under 301 and commits. READ COMMITTED: page 2 (`311…`, non-empty) carries `dup = 310`, read
   order `MQ2703`. REPEATABLE READ: page 2 carries no check and the walk completes over its snapshot.
9. **Concurrent move (PostgreSQL), evidence for §5.** Seeds 400 and 410, keyset by `(level, id)`, page 4. After page 1
   (`400, 410, 401, 411`), another connection moves 402 from level 2 to level 1 (behind the cursor) and 401 from level
   1 to level 3 (ahead of it). READ COMMITTED: `seen = 400, 410, 401, 411, 412, 413, 401`: 401 twice, 402 never, and no
   check fires (the data is still a tree). REPEATABLE READ: all seven once.
10. **Line parity.** The probe source compiles unchanged on 6.6 and 7.4 and every cell matches. `JpaSubQuery#fetch`,
    `groupBy`, `having` and `orderBy` over a `JpaCteCriteria` root, and `JpaCriteriaQuery#from(Subquery)` over the
    same CTE, behave the same on both lines.
