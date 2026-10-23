# RFC 0019 acceptance spike: results

**Part A: no route ships, so it becomes a docs paragraph (rule 2, "Neither"). Part B: ship P-B.2 (flat-row
`mapAll`), drop P-B.1 and P-B.3.**
On PostgreSQL, today's executor adds about 0.03 ms (median) to the "today" read over `JdbcClient` running the same
SQL, so there is no per-call 1–3 ms to save on this dataset. The build cache and the HQL route save nothing on
the small reads. The only large saving, RA.5 on the grouped read, is faster than the JDBC baseline itself, so it
comes from different SQL (`count(*)` against `count(s1_0.id)`), not from cutting overhead. On the 86k-row flat read, model-query's
own mapping is 78–90% of the gap to JDBC. The flat-row `mapAll` closes almost all of it: 194 → 92 ms on 6.6 and
203 → 108 ms on 7.4.

This is throwaway research on `spike/rfc-0019`. It uses one test-source module, `spike-rfc0019/`, on Hibernate
6.6.58.Final and 7.4.11.Final, against H2, PostgreSQL 16 and MySQL 8.0.31 (MySQL is informational only). Production
`model-query-*/src/main` and the committed SQL snapshots are unchanged. Raw data is in `0019-spike-data/`:

- `performance-<version>.tsv` and `.json` hold the JMH timings: 2 forks × 10 measured iterations per cell,
  `-prof gc`.
- `performance-<version>-fill.tsv` and `-ra6.tsv` rerun the three cells the main run lost (RA.0 `one` and
  `page`, RA.6), after fixing their harness bugs.
- `rfc0019-<version>.tsv` holds the functional cells.
- `summarize.py` is the reducer and `test_summarize.py` its test; the README says how to rerun everything.

The bounds below are the reducer's one-sided 99.9% Welch bounds (`python3 summarize.py` in `0019-spike-data/`).

## Part A: where the per-call time goes

The baseline (RA.0) on the "today" read is 0.71 ms on both lines, so `T = 0.3 ms`. Times are medians in ms on
PostgreSQL, 6.6 / 7.4.

| Read | RA.0 JDBC | RA.1 executor | RA.2 build | RA.3 copy | RA.3 no copy | RA.4 (7.4) | RA.4b (7.4) | RA.5 HQL |
|---|---|---|---|---|---|---|---|---|
| today | 0.71 / 0.71 | 0.74 / 0.75 | 0.003 / 0.003 | 0.76 / 0.73 | 0.71 / 0.79 | 0.78 | 0.74 | 1.05 / 1.21 |
| one | 0.51 / 0.56 | 0.62 / 0.61 | 0.0005 / 0.0006 | 0.61 / 0.63 | 0.63 / 0.65 | 0.64 | 0.62 | 0.65 / 0.65 |
| page | 4.48 / 4.30 | 4.75 / 4.62 | 0.0006 / 0.0006 | 4.54 / 4.53 | 4.63 / 4.69 | 4.94 | 4.61 | 4.47 / 4.56 |
| grouped | 8.32 / 8.39 | 9.04 / 9.25 | 0.0004 / 0.0005 | 8.85 / 8.96 | 8.91 / 9.13 | 9.34 | 8.69 | 7.73 / 7.55 |
| orderedBy | 0.73 / 0.73 | 0.78 / 0.79 | 0.003 / 0.003 | 0.73 / 0.75 | 0.74 / 0.80 | 0.82 | 0.78 | 0.91 / 1.33 |

RA.6 (native SQL through the row path) on the "today" read: 0.79 / 1.01 ms. The `page` read, two statements, is
where the executor's overhead is largest: about 0.3 ms over JDBC on both lines.

Gating cell, 16 threads, no execution: a build-cache lookup plus `createQuery` takes 0.006 ms, against 0.015 ms
for `buildQuery` plus `createQuery`. MySQL's informational "today" read shows the same picture: RA.0 1.55 / 1.50,
RA.1 1.49 / 1.52, RA.3 1.49 / 1.53, RA.5 1.65 / 1.59.

### Applying the rules

| Rule | Evidence | Holds? |
|---|---|---|
| 1 Drop | On 7.4, RA.4 and RA.4b return the same rows and SQL as RA.1, but the plan cache **never hits**: 0 hits in 100 calls for RA.4 (today's executor, which builds a new tree per call) and also for RA.4b (one stored tree). Why RA.4b misses too is not established. O(RA.4)'s upper bound is 0.38 ms, above `T`. On 6.6, O(RA.1)'s upper bound is 0.33 ms, also above `T`, though its mean is 0.15 and its median 0.03. | no |
| 2 Neither | Only RA.5 on the grouped read has a lower-bound saving of at least 0.2 ms: 1.14 / 1.55 ms. But RA.5 there (7.73 / 7.55) beats RA.0, plain JDBC on RA.1's SQL (8.32 / 8.39), because it sends other SQL: the HQL route renders `count(*) ... group by s1_0.status`, model-query renders `count(s1_0.id) ... group by 1` (cell `A.1/grouped`, same rows). PostgreSQL's `count(col)` checks each value for null, so the saving is a rendering difference, not overhead removed. RA.5 also fails the ship condition (rule 3), so it cannot be the route that defeats this rule. Every other saving's lower bound is below 0.2 ms. | **yes** |
| 3 Provider only | `B`'s upper bound is 0.003 ms on both lines, and RA.5's 99% hit-ratio cell passes (200/200). But RA.5 fails the ship condition: its saving's lower bound is negative on the "today" read (−0.41 / −0.50) and on `orderedBy` (−0.32 / −0.56). | not reached |
| 4, 5 Both / Build only | The build cache passes its functional cells and its gating cell. But its saving's lower bound is negative on the "today" read (−0.26 / −0.34) and on `orderedBy`, and its mean saving is about 0 on every small read. | not reached |

So `QueryParam` and `compiled()` do not ship. The docs paragraph should say:

- our build is about 3 µs;
- the executor's per-call overhead over `JdbcClient` is tens of microseconds on PostgreSQL;
- on 7.4, `hibernate.criteria.plan_cache_enabled` gets no hits for model-query's queries.

A separate, small follow-up outside this RFC: render `count(*)` for a count of a non-null primary key, which is
about 1 ms (10%) faster on this grouped read.

### Late-fork drift (unexplained)

Every Hibernate route slows down late in each fork: 0.70–0.80 ms through measured iteration 5–7, then 1.0–1.9
ms. RA.5 drifts earliest, from about iteration 4. RA.0 (JDBC) and RA.2 (no statement) never drift. GC isn't the
cause: `-prof gc` shows 1–3 ms of GC per 1 s iteration, before and after the step. Nor is the SQL-capture
inspector, because the routes on factories without it drift too. The drift is why the upper bounds above sit near
`T` while the medians sit near zero. It doesn't change the verdict: before the step, RA.1, RA.3, RA.4 and RA.5
are within about 0.05 ms of each other.

## Part B: where row-mapping time goes

These are medians on PostgreSQL for the flat 86k-row, 12-column projection, 6.6 / 7.4. Allocation is
`gc.alloc.rate.norm` per call.

| Route | ms | MB allocated per call |
|---|---|---|
| RB.0 `JdbcClient` + `RowMapper` | 76.2 / 77.8 | 101 / 101 |
| RB.1 JPA `Tuple.get(String)` | 92.7 / 108.1 | 106 / 152 |
| RB.2 JPA `Tuple.get(int)` | 87.6 / 105.9 | 106 / 152 |
| RB.3 JPA `Object[]` | 94.4 / 103.9 | 104 / 150 |
| RB.4 today's `list` | 193.5 / 203.2 | 393 / 429 |
| RB.5 index-resolved `ValueRow` | 194.0 / 208.1 | 395 / 436 |
| RB.6 flat-row `mapAll` (on RB.4, since P-B.1 fails) | 92.2 / 107.9 | 109 / 155 |
| RB.7 `stream` (reported, not gating) | 353 / 384 | 108 / 154 |
| RB.7 `export` (reported, not gating) | 2745 / 2774 | 127 / 174 |

| Step | Measure | Result |
|---|---|---|
| gate | (RB.4 − RB.2) / (RB.4 − RB.0) = 106/117 = **90%** / 97/125 = **78%**, need ≥ 25% | passes |
| P-B.1 | RB.5 against RB.4: −0.3% / −2.4% (slower), need ≥ 10% faster | **drop** |
| P-B.2 | RB.6 against RB.4: **52% / 47% faster**, need ≥ 5%; allocation falls from 393 to 109 MB (6.6) and 429 to 155 MB (7.4) | **ship** |
| P-B.3 | RB.3 against RB.2: 7.8% slower / 1.9% faster, need ≥ 15% faster; the single-item cell passes | **drop** |

Today's `list` allocates about 3.5 KB per row beyond Hibernate's tuple path: 287 MB for 86k rows. The flat-row
`mapAll` removes nearly all of it, leaving model-query 2–5 ms above RB.2. Since P-B.1 and P-B.3 are dropped,
R-COL-10 keeps `Tuple`, and `RowSelection` keeps its alias map.

### Why export is 14× slower than `list`

`FLAT_QUERY` has a primary key but no `keyset()`, so `export` takes the offset loop
(`DefaultModelQueryExecutor#exportByOffset`). It sends about 87 statements of `order by id offset k fetch first
1000 rows`, the default `exportPageSize` being 1,000. PostgreSQL can't seek to an offset: for each page it walks
the primary-key index and fetches every row before `k`, then discards them. Across the export that is
1000 × (1 + 2 + … + 87), about 3.8 million rows read to return 86k, roughly 44 times the rows. The cost is
quadratic in the result size. `stream` runs the same SQL once, with no offset, and takes 353 / 384 ms. The extra
2.4 s is the skipped rows plus 87 round trips. Per-page bookkeeping is small: the allocation is close to
`stream`'s (127 against 108 MB), so the time is spent in the database.

This is the documented trade-off, not a defect in the loop. `keyset()` sends each page as `where id > ? ... fetch
first 1000 rows`, which is linear. MQ2002's message already points large results there. Two follow-ups are worth
considering, outside this RFC:

- a keyset-export RB.7 cell, to confirm the linear cost;
- making the export docs lead with `keyset()` for any result above a few thousand rows.

## Known limits

- **The SQL-capture inspector stays attached during timing** on the RA.1, RA.3-copy, RA.5 and RA.6 factory,
  adding one `CopyOnWriteArrayList` append per statement. The routes on factories without it drift the same way,
  so it is not what moves the verdict.
- **The late-fork drift is unexplained** (see above).
- **RA.6's timing comes from a separate rerun** (`-ra6`), and RA.0 `one` and `page` from another (`-fill`), each in
  its own JVM after the main run.
