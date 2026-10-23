# RFC 0020 spike data

Raw output of the acceptance spike in `spike-rfc0020/` (branch `spike/rfc-0020`), read by
[`../0020-spike-results.md`](../0020-spike-results.md).

| File | What it holds |
|---|---|
| `rfc0020-<line>.tsv` | Functional cells (E.1, D.1, D.2, C.1, C.2, A.2, B.1, B.2, F.1): `vendor`, `probe`, `case`, `status` (`PASS`, `FAIL`, `NOT_RUN`), `value` (rows, types, the error for a `FAIL`), and every statement the cell sent with its binds (`sql [binds]`, statements joined by ` ;; `). A `FAIL` is a cell whose expectation did not hold or that threw; a planned refusal or a vendor limit is data, not a harness error. |
| `rfc0020-timings-<line>.tsv` | Timed and plan cells (A.1, E.1 plans, B.2 against B.1, F.1): `vendor`, `cell`, `case`, `index` (the indexes present during the run, and `enable_seqscan=off` where set), `status`, `median_ms`, `runs_ms` (the timed runs in order), `rows`, `plan` (a one-line digest: whether each named index appears, PostgreSQL CTE scan or not, top-node buffers and execution time, node kinds in order; H2's whole `EXPLAIN` on one line), `sql`. |
| `plans/<line>/<vendor>.txt` | The full plan text of every timed statement, headed `### vendor \| cell \| case \| index`, then the statement and binds: `EXPLAIN (ANALYZE, BUFFERS)` on PostgreSQL, `EXPLAIN ANALYZE` on MySQL, `EXPLAIN` on H2. |
| `f1-snapshots.txt` | Output of `snapshot_counts.py`: the committed TCK SQL snapshots whose `count(<alias>.id)` (and, separately, `count(1)`) R-AGG-19 would turn into `count(*)`. A list only; no snapshot was changed. |
| `snapshot_counts.py` | Standard-library classifier behind `f1-snapshots.txt` (root, inner-joined or LEFT-joined alias per statement). |
| `summarize.py` | Standard-library reducer: the PASS/FAIL/NOT_RUN matrix, the median table per timed cell with the plan flags, and the line-parity diff. |

`<line>` is the Hibernate version (`6.6.58.Final`, `7.4.11.Final`); `<vendor>` is `h2`, `postgresql` or `mysql`
(PostgreSQL 16 `postgres:16-alpine`, MySQL 8.0.31, H2 2.x in memory).

## How the timed cells run

Each statement is built through Hibernate's Criteria API, executed once through the session (which also checks it
runs), and captured with its binds by datasource-proxy. It is then re-run on one unproxied JDBC connection: 2 warm-up
rounds and 7 timed rounds, each round running every variant of the cell with the order rotated by one, so the variants
alternate; the time covers execute plus reading every column of every row; the median of the 7 is kept. A variant
whose first warm-up exceeds 5 s gets one warm-up and 3 timed runs (only H2's B.2 day query). After the timed runs one
plan is taken. On H2 `set optimize_reuse_results 0` is applied first: otherwise H2 hands a re-run of a cached command
its previous result without executing it. Statistics are refreshed (`analyze`, `analyze table` on MySQL) after the
seed and after each index change.

Dataset (`Rfc0020Dataset`): 365 business days, sales on the last 30 at 1,000 sales and 3,000 lines a day (30,000
sales, 90,000 lines), 200 inventory movements a day for the year, 200 items, 500 customers, 80 promos (codes
`P1`..`P50`, 10 NULL, and `X1`..`X20`, which no sale has; sales draw `P1`..`P55` or NULL). A.1 runs without and then with
`ix_sale_business_date` on `sale(business_date)`; its page, export and count keep the days whose `counted_cash` is
distinct from the cash sum (the RFC's two-valued `distinctFromColumn` form), and `count unfiltered` drops that filter
(join removal). E.1's `filter` cell runs with `ix_sale_promo_code` on `sale(promo_code)`; its `filter-2op` and `exists`
cells add `ix_promo_code` on `promo(code)`, and on PostgreSQL run a second time under `set enable_seqscan = off` (the
`index` column says so). B and F.1 run with no extra index.

## Reproducing

From the repository root, once per line, one after the other (Docker must be running; a line takes about 10 minutes,
most of it H2's B.2 day query):

```shell
./mvnw -q test -Pspike-rfc0020 -pl spike-rfc0020 -am -Dtest='Rfc0020*Test' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhibernate.version=6.6.58.Final
./mvnw -q test -Pspike-rfc0020 -pl spike-rfc0020 -am -Dtest='Rfc0020*Test' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
```

`-Drfc0020.plans=false` skips the timed and plan cells (functional cells only). Each run rewrites that line's two
TSVs and its `plans/<line>/` files; running one vendor class alone (`-Dtest='Rfc0020H2*Test'`) leaves only that
vendor in the line's TSVs. Then:

```shell
cd rfc/0020-spike-data
python3 -I summarize.py
cd ../..
python3 -I rfc/0020-spike-data/snapshot_counts.py > rfc/0020-spike-data/f1-snapshots.txt
```
