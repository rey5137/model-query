# RFC 0017 spike observations

The two `rfc0017-<version>.tsv.gz` files are complete fresh runs of the final spike source on Hibernate 6.6.58.Final
and 7.4.11.Final (Jakarta Persistence 3.2.0), each against H2, PostgreSQL 16 (`postgres:16-alpine`, `pg_trgm`) and
MySQL 8.0.31, 880 cells per line. They are gzipped because each is over 200 KB; `gunzip -k` them to read them.

Fields (one header row): vendor, Hibernate version, probe, case, status, asserted value or error, captured SQL.
Tabs and newlines inside a field are flattened. Captured SQL comes from a datasource-proxy listener; a bound
statement is followed by `||` and its `setXxx(index,value:Type)` calls, and the statements of one cell are joined by
` ;; `. A cell that failed before any statement reached JDBC has empty SQL.

* **PASS** of a cell that runs a query (probes 1-4, 6-8, FT) asserts the actual row ids (and, where the probe says
  so, the `?`-only statement with a JDBC bind, or the index name in the `EXPLAIN` plan), never just "no exception".
  The PASS cells that run no query of their own are bootstrap, registration, registry lookup, DDL, a hand-rewritten
  statement's plan (`3` `*/eq_true_seqscan_off`) and the node-type read (`7` `*/unwrap`); the results file lists them.
* **SIMULATED** (probe 5's two gate cells) evaluates the spike's stand-in decision table for a check not built yet,
  over the real registry answer: true by construction, not an observation of engine behaviour.
* **FAIL** is an observation, not a harness fault: the asserted value, Hibernate's or the database's exception, or
  `INV-5 degrade` when a mistyped bind matched every row. JUnit stays green when every vendor's harness completed
  (3 tests, 0 failures in each of the two surefire executions per line).
* **NOT_RUN** names why: a PostgreSQL-only function the contributor didn't register, or a PostgreSQL-only probe.

Probe 8's inline `cb.equal` and `VALUE_INFERRED` cells run twice: in the test JVM (`-ea`), and as `*/no_ea` in a
second surefire execution with assertions off (`spike.group=no_ea`), which appends to the same file. Probe 3's
`*/at_scale_generic` cells `PREPARE` the captured statement with `$n` placeholders and `EXPLAIN EXECUTE` it under
`plan_cache_mode = force_generic_plan`, the binds as untyped literals; the other probe-3 plans replay the binds as
JDBC binds (custom plans).

Probe ids follow RFC 0017 §7 (`1`..`7`); `0` is bootstrap (the per-line binder adapter it resolved), `FT` the
full-text (`@@`) question from RFC 0017's Unresolved section, `8` the value-handling-mode probe (the two binder
routes under `hibernate.criteria.value_handling_mode` default and `inline`). Case names are
`<operand>/<route>` in probe 1, `<operand>/<function>/<route>` in probe 7 and `<operand>/<function>/<route>/<setting>`
in probe 8; the routes are the `Mode` constants in `Rfc0017SpikeTest`.

* `line-parity.tsv`: every cell whose status differs between the lines (68, all in probes 1, 7 and 8). It compares
  status only: a cell that passes on both lines with different SQL or binds (MySQL's arrays: Java-serialized bytes
  on 6.6, JSON on 7.4) is not listed.
* `summarize.py`: regenerates the results file's matrix and `line-parity.tsv` from the raw files, stdlib only
  (`python3 -I summarize.py`).

The test source (module `spike-rfc0017/` on branch `spike/rfc-0017`, not merged) is the fixture and query definition:
eight `spike_row` rows and eight `spike_arr` rows, and for probe 3 20 000 non-matching filler rows per table. Query
timeout is 10 s. No product behaviour changed.
