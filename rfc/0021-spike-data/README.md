# RFC 0021 spike data

Raw output of the acceptance spike in `spike-rfc0021/` (branch `spike/rfc-0021`), read by
[`../0021-spike-results.md`](../0021-spike-results.md).

| File | What it holds |
|---|---|
| `rfc0021-<line>.tsv` | Functional cells (A.1 signatures, A.2 rendering and statement kinds, A.3 paths and keys, A.4 `count`, A.5 parity, B.1 join filters): `vendor`, `probe`, `case`, `status` (`PASS`, `FAIL`, `NOT_RUN`), `value` (rows, counts, the join order, the error for a `FAIL`), and every statement the cell sent with its binds (`sql [binds]`, statements joined by ` ;; `). A `FAIL` is a cell whose expectation did not hold or that threw; a refusal or a vendor limit is data, not a harness error. |
| `rfc0021-timings-<line>.tsv` | Timed and plan cells (A.4): `vendor`, `cell`, `case`, `index` (here the scope: `hierarchy 1` or `all hierarchies`), `status`, `median_ms`, `runs_ms` (the 7 timed runs in order), `rows`, `plan` (a one-line digest: scans of `profile` and `profile_type`, whether the CTE is materialized, top-node buffers and execution time on PostgreSQL, node kinds in order; H2's whole `EXPLAIN` on one line), `sql`. |
| `plans/<line>/<vendor>.txt` | The full plan of every timed statement, headed `### vendor \| cell \| case \| scope`, then the statement and binds: `EXPLAIN (ANALYZE, BUFFERS)` on PostgreSQL, `EXPLAIN ANALYZE` on MySQL, `EXPLAIN` on H2. |
| `javap-<line>.txt`, `javap.sh` | The `JpaFrom#join(Class, ...)`, `JpaEntityJoin`, `JpaJoin` and `JpaJoinedFrom` signatures with their descriptors on each line (A.1). |
| `link-probe-<line>.txt`, `LinkProbe.java` | A.1's link test: one class file compiled against Hibernate 6.6.58 and run against 6.6 and 7.4, with direct and reflective calls. |
| `summarize.py` | Standard-library reducer: the PASS/FAIL/NOT_RUN matrix, the median table per timed cell with the plan flags, the line-parity diff and the list of non-PASS cells. |

`<line>` is the Hibernate version (`6.6.58.Final`, `7.4.11.Final`); `<vendor>` is `h2`, `postgresql` or `mysql`
(PostgreSQL 16 `postgres:16-alpine`, MySQL 8.0.31, H2 2.x in memory).

## How the cells run

The library has no entity-join or join-filter API yet, so every statement is built on Hibernate's Criteria API
(`Q.java`): the root `hierarchy_node`, the grouped edge CTE joined through reflective `JpaFrom#join(JpaCteCriteria,
SqmJoinType)` and `on` (what `SourceRenderer#join` does), and the proposed `EntityJoinRenderer#join` as a reflective
`JpaFrom#join(Class, SqmJoinType)` plus a reflective `on(Predicate...)` called once with the whole predicate. The A.5
statement kinds are emulated as the same statement with the extra predicate, order and limit the library's statement of
that kind has; the rows are checked against the native SQL's, and the SQL against `join profile` counts.

Each timed statement is built through Hibernate, executed once through the session (which also checks it runs), and
captured with its binds by datasource-proxy. It is then re-run on one unproxied JDBC connection: 2 warm-up rounds and
7 timed rounds, each round running every variant of the cell with the order rotated by one, so the variants alternate;
the time covers execute plus reading every column of every row; the median of the 7 is kept. After the timed runs one
plan is taken. On H2 `set optimize_reuse_results 0` is applied first. Statistics are refreshed (`analyze`,
`analyze table` on MySQL) before the timed cells.

Dataset (`Rfc0021Dataset`, seeded `Random(21)`): 3 hierarchies of 5,000 nodes (15,000 `hierarchy_node` rows; node `k`'s
parent is `(k - 2) / 3 + 1`), 5,000 profiles (node `k` is profile `k`), 10 profile types, closure edges for 1 to 6 hops
(88,371 `hierarchy_edge` rows; the root has none and a node closer than `hops` levels to it has none at that `hops`),
unique index `(hierarchy_id, end_vertex, hops)`, 5% deleted profiles, 5% deleted nodes, 2% deleted edges; two
composite-id tables (`member_ic`, an `@IdClass`; `member_emb`, an `@EmbeddedId`) of 4,286 rows per hierarchy. The seed
takes 0.4 to 0.6 s on H2, 0.6 s on PostgreSQL and 1.2 s on MySQL, so nothing was scaled down.

## Reproducing

From the repository root, once per line, one after the other (Docker must be running; a line takes a few minutes):

```shell
./mvnw -q test -Pspike-rfc0021 -pl spike-rfc0021 -am -Dtest='Rfc0021*Test' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhibernate.version=6.6.58.Final
./mvnw -q test -Pspike-rfc0021 -pl spike-rfc0021 -am -Dtest='Rfc0021*Test' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
```

`-Drfc0021.plans=false` skips the timed and plan cells. Each run rewrites that line's two TSVs and its `plans/<line>/`
files; running one vendor class alone (`-Dtest='Rfc0021H2*Test'`) leaves only that vendor in the line's TSVs. Then:

```shell
cd rfc/0021-spike-data
./javap.sh
python3 -I summarize.py
```

The link probe (A.1) is compiled once against 6.6 and run on both lines; the classpaths come from
`dependency:build-classpath` of `spike-rfc0021` on each line:

```shell
./mvnw -q dependency:build-classpath -Pspike-rfc0021 -pl spike-rfc0021 -Dmdep.outputFile=/tmp/cp-6.6.txt \
    -Dhibernate.version=6.6.58.Final
./mvnw -q dependency:build-classpath -Pspike-rfc0021 -pl spike-rfc0021 -Dmdep.outputFile=/tmp/cp-7.4.txt \
    -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
mkdir -p /tmp/link && javac -cp "$(cat /tmp/cp-6.6.txt)" -d /tmp/link rfc/0021-spike-data/LinkProbe.java
java -cp "/tmp/link:$(cat /tmp/cp-6.6.txt)" LinkProbe    > rfc/0021-spike-data/link-probe-6.6.58.Final.txt
java -cp "/tmp/link:$(cat /tmp/cp-7.4.txt)" LinkProbe    > rfc/0021-spike-data/link-probe-7.4.11.Final.txt
```
