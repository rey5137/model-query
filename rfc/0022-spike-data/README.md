# RFC 0022 spike data

Raw data of the RFC 0022 acceptance spike (A.1 to A.4, B.1 to B.4, C.1, C.2, D.1, line parity), read by
[`../0022-spike-results.md`](../0022-spike-results.md). This is research on `spike/rfc-0022`: one test-source module
`spike-rfc0022/` behind the root `pom.xml` profile `spike-rfc0022`. Production sources, committed SQL snapshots and
`docs/spec/` are unchanged.

## Files

- `rfc0022-<line>.tsv`: functional cells per Hibernate line, columns `vendor`, `probe`, `case`, `status`, `value`,
  `sql` (SQL `||` JDBC binds, `;;` between statements).
- `cost-cells-<line>.tsv`: the accepted sub-step 2 B.2 cells. `summarize.py` uses them only where the line's TSV
  has the `-Drfc0022.plans=false` skip.
- `rfc0022-timings-<line>.tsv`: B.2 medians of 7 alternating runs (renderer SQL and native SQL), with row counts.
- `joined-count-timings-<line>.tsv`: D.1 PostgreSQL medians of `count(*)` and `count(id)` on the grouped read.
- `plans/<line>/<vendor>.txt`: B.2 plans, PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`, MySQL `EXPLAIN ANALYZE`,
  H2 `EXPLAIN`.
- `rfc0022-eclipselink-5.0.2.tsv`: A.4 and A.4-repro cells on EclipseLink 5.0.2 / API 3.2.0, all three vendors.
- `eclipselink-5.0.2-engine.txt`: the A.4 child process's stderr, with the full stack of every failed cell and the
  vendor log lines.
- `link-probe-<lane>.tsv` and `.txt`: A.1/A.2 cells per offline lane, and that lane's complete stdout/stderr.
- `javap-<lane>.txt`: A.1 signatures with descriptors (API and implementation) and the probe's bytecode, for the
  Hibernate 7.4 and EclipseLink 5.0.2 lanes.
- `probe-pom.xml`, `LinkProbe.java`, `link-probe.sh`, `javap.sh`: the isolated A.1/A.2 harness.
- `PlainEntityJoinRenderer.java.template`: A.3's portable renderer, compiled at test time against 7.4/API 3.2.
- `PlainJoinReproduction.java.template`: A.4-repro, plain Criteria and JPQL Invoice -> Employee joins, no engine.
- `summarize.py`, `test_summarize.py`: tables, parity and validation over the TSVs (standard library only), and
  its unit tests. `parity.txt` is `summarize.py parity`'s output.
- `checks/`: run records. `substep2-checks.md` (sub-step 2 commands and counts), `substep3-*.log` (sub-step 3
  runs), `eclipselink-reproduction.log` (A.4-repro), `validate.log`.

`<line>` is `6.6.58.Final` or `7.4.11.Final`. `<lane>` is `hibernate-6.6-api3.1`, `hibernate-6.6-api3.2`,
`hibernate-7.4-api3.2`, `eclipselink-4.0.8-api3.2` or `eclipselink-5.0.2-api3.2`. Functional vendors are
H2 2.5.252, PostgreSQL 16 (`postgres:16-alpine`) and MySQL 8.0.31.

A failed expectation or a provider refusal is a `FAIL` cell, and a cell that can't run is `NOT_RUN` with its reason:
a green JUnit run does **not** mean every cell passed. Container startup, schema or seed failures fail the JUnit
class. Containers and the H2 keeper close at teardown; Hibernate drops the schema; Testcontainers' reaper handles an
abruptly terminated JVM.

## Reproduce

From the repository root, with Docker running, one Hibernate line at a time (never both at once). Each functional
run replaces that line's `rfc0022-<line>.tsv`; a run without `-Drfc0022.plans=false` also replaces its timings,
`joined-count-timings` and `plans/<line>/`, and records the B.2 cells directly (then `cost-cells-<line>.tsv` is unused).

```sh
./mvnw -q test -Pspike-rfc0022 -pl spike-rfc0022 -am \
  '-Dtest=Rfc0022*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dhibernate.version=6.6.58.Final

./mvnw -q test -Pspike-rfc0022 -pl spike-rfc0022 -am \
  '-Dtest=Rfc0022*Test' -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
```

Add `-Drfc0022.plans=false` to skip the timed B.2 cells while iterating (the line's TSV then has one B.2 `NOT_RUN`).

The offline A.1/A.2 probe, then its signatures. `link-probe.sh` also resolves the isolated classpaths A.4 uses, so
run it first:

```sh
sh rfc/0022-spike-data/link-probe.sh
sh rfc/0022-spike-data/javap.sh
```

A.4 on EclipseLink 5.0.2, opt-in (writes `rfc0022-eclipselink-5.0.2.tsv` and `eclipselink-5.0.2-engine.txt`):

```sh
./mvnw -q test -Pspike-rfc0022 -pl spike-rfc0022 -am \
  -Dtest=Rfc0022EclipseLinkTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0 \
  -Drfc0022.eclipselink=true -Drfc0022.plans=false
```

The tables (`grid`, `matrix`, `timings` and `parity` by default; `fails` lists every non-PASS cell; `validate` fails
on a missing C.1/C.2/D.1 cell, a duplicate key, a missing B.2 measurement, plan or timing):

```sh
python3 -I -B rfc/0022-spike-data/summarize.py
python3 -I -B rfc/0022-spike-data/summarize.py fails validate
python3 -I -B rfc/0022-spike-data/summarize.py parity > rfc/0022-spike-data/parity.txt
python3 -I -B rfc/0022-spike-data/test_summarize.py
```

Timings and parity counts are info only: the spike defines no latency threshold, and line differences are findings,
not failures. Row counts and row equality of the timed replays are acceptance assertions.

## Harness

**A.1/A.2.** The isolated POM resolves each provider/API classpath without the library or its renderer. Java compiles
the probe once against API 3.2, then runs the same class files with `-Xverify:all` on the five lanes, each JVM with a
90-second deadline (generated classes and classpaths under `spike-rfc0022/target/link-probe/`). The factory, its
metamodel and criteria builder are initialized before the discovery boundary; the data source then denies and counts
every `getConnection`. No discovery probe executes a query. An older lane's A.2 PASS means the **exact expected**
first-call error with zero connections: `NoSuchMethodError` on API 3.1, `AbstractMethodError` with API 3.2 forced
over Hibernate 6.6 or EclipseLink 4. A.1's implementation check applies to Hibernate 7.4 and EclipseLink 5 only; the
other lanes carry an explicit `NOT_RUN`. Probe times (cold, and median of 7 on 7.4) are info only. EclipseLink 5.0.2
was the newest stable 5.0.x in [Maven Central metadata][central] on 2026-10-24 (`5.0.3-RC1` is a prerelease).

**Dataset.** The RFC 0021 closure-table seed is copied unchanged into `Rfc0022ClosureDataset`. The invoice seed has
50,000 invoices, exactly 1,000 (2%) orphan `rep_code`s, 300 employees in each of the simple, `@IdClass` and
`@EmbeddedId` tables, 10 regions and 10 teams; every 25th invoice has a null region. 100 tickets have ids that collide
with invoice ids; 200 attachments are split evenly between those owners, plus two with absent owners. JUnit checks
the invoice and orphan cardinalities before any cell runs.

**B.1, B.3, B.4, A.3, C, D.** `Q` emulates the proposed column-anchored creation step on Criteria and calls the
production `ProviderSupport#entityJoins()` renderer: production refuses column anchors today, so these are
statement-shape probes, not executor tests. Each statement kind (content, count, root-id count, both primary-key-first
steps, keyset, by-keys, one, first and next export page, stream, fetch-plan parent read) is the content statement with
that kind's predicates, order and limit. The root-id count has no treated mapping, so treated-path dispatch is
`NOT_RUN`. Every statement is captured with its JDBC binds. Native SQL is built independently and read over JDBC, and
rows must be equal in full (nulls and order included). A.3 compiles `PlainEntityJoinRenderer.java.template` into
`spike-rfc0022/target/portable-renderer/` and withholds only `entityJoins` through a delegating `ProviderSupport`; it
runs on 7.4 only (6.6 keeps API 3.1). C emulates `Expr.param` as one Criteria `ParameterExpression` per param, bound by
name; C runs once under the default and once under `hibernate.criteria.value_handling_mode=inline`. No SQL snapshots
are read or written.

**B.2.** Row equality first, then the captured renderer SQL and the native SQL replayed over raw JDBC, every selected
value consumed: two warmups and seven measured runs, alternating which variant runs first. Page size 20; the
unreferenced LEFT employee join stays in the generated count; the export reads all 50,000 rows. Timings cover
database/JDBC execution and consumption, not Criteria building or model mapping.

**A.4.** An isolated API 3.2 / EclipseLink 5.0.2 child process (eight-minute deadline, 60-second query timeouts, an
explicit environment allowlist) boots EclipseLink factories only, creates Part B's schema and seeds 50,000 invoices on
each vendor. It runs `A.4-repro` (`PlainJoinReproduction.java.template`: `root.join(Employee.class, LEFT)` with
`join.get("code")` untyped, typed before `on`, typed after a first `on`, and the JPQL form), then the production
executor on a page of 20 with no join, then the portable LEFT and INNER joins. The seven joined-engine cells per vendor
(LEFT, INNER, to-one below, filter, count, keyset with `nullsLast`, export) run only if those joins are created; a
test-only Criteria adapter would redirect the model's `rep` association to the column-anchored join, and the 7.4 lane
validates that adapter on Hibernate. Factories, connections and containers close on failed cells too.

The executor cell asserts rows only. Its log shows the vendor resolved to the conservative `OTHER` profile on all
three vendors (`VendorResolver` found no `jakarta.persistence.nonJtaDataSource` factory property), so `DatabaseMetaData`
vendor detection is **not** shown by this run, whatever the cell's value text says.

[central]: https://repo.maven.apache.org/maven2/org/eclipse/persistence/org.eclipse.persistence.jpa/maven-metadata.xml
