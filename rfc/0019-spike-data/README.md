# RFC 0019 spike data

Raw output of the acceptance spike in `spike-rfc0019/` (branch `spike/rfc-0019`), read by
[`../0019-spike-results.md`](../0019-spike-results.md).

| File | What it holds |
|---|---|
| `performance-<line>.tsv` | JMH timings, one row per measured iteration: `vendor`, `hibernate`, `part`, `query`, `route`, `fork`, `iteration`, `score_ms`, `alloc_rate_norm` (bytes per call), `status`. A benchmark that threw gets one `FAIL` row. |
| `performance-<line>-<suffix>.tsv` | A rerun (`-fill`, `-ra6`) of cells the main run lost or failed; the reducer uses it only for cells missing or failed in the main file. |
| `performance-<line>.json` | JMH's own JSON result for the same run. |
| `rfc0019-<line>.tsv` | Functional cells: `vendor`, `probe`, `case`, `status` (`PASS`, `FAIL`, `NOT_RUN`), `value`, and the SQL each route sent. |
| `summarize.py` | Standard-library reducer: min/median/max per route, the one-sided 99.9% Welch bounds and the Part A rule and Part B steps that hold. |
| `test_summarize.py` | Its tests, including one that checks the recorded data still give the verdicts in the results doc. |

`<line>` is the Hibernate version (`6.6.58.Final`, `7.4.11.Final`). Timings are PostgreSQL 16 unless `vendor` says
`MYSQL` (informational only).

## Reproducing

From the repository root, once per line (Docker must be running; a full run takes about an hour per line):

```shell
./mvnw test -Pspike-rfc0019 -pl spike-rfc0019 -am -Dtest='Rfc0019*Test' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhibernate.version=6.6.58.Final
./mvnw test -Pspike-rfc0019 -pl spike-rfc0019 -am -Dtest='Rfc0019*Test' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
```

`-Dspike.include=<JMH regex>` runs only matching benchmarks and `-Dspike.out.suffix=-fill` writes them to a `-fill`
file. Then:

```shell
cd rfc/0019-spike-data
python3 summarize.py
python3 -m unittest test_summarize
```
