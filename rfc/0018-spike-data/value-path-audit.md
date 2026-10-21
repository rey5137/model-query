# Part C: actual inline TCK value-path audit

The AC-FLT-35 suite families actually ran with `hibernate.criteria.value_handling_mode=inline` and
`Rfc0018ValueAudit` installed as the statement inspector on both Hibernate lines and all three Tier 1 vendors.
The opt-in `rfc0018-value-audit` Maven profile sets both options; `Rfc0018ValueAuditTest` asserts the effective
SessionFactory options on H2, PostgreSQL 16 and MySQL 8.0.31. All six profile self-checks passed.
Production code and committed SQL snapshots are unchanged. This is a current-engine acceptance finding,
not an implementation of `valueOperand` or `ProviderSupport.inlinesValues`.

## Run and preserve the evidence

Run the commands sequentially from the repository root. `TCK_TESTS` expands to the actual filter, paging,
fetch, write and source suites plus byKeys and the Spring paging/fetch entry points; it is not the full TCK.

```sh
TCK_TESTS='com.rey.modelquery.tck.flt.*Test,com.rey.modelquery.tck.pag.*Test'
TCK_TESTS="$TCK_TESTS,com.rey.modelquery.tck.exe.ByKeysTest,com.rey.modelquery.tck.fch.*Test"
TCK_TESTS="$TCK_TESTS,com.rey.modelquery.tck.wrt.*Test,com.rey.modelquery.tck.src.*Test"
TCK_TESTS="$TCK_TESTS,com.rey.modelquery.tck.spr.FindKeysetPageTest"
TCK_TESTS="$TCK_TESTS,com.rey.modelquery.tck.spr.FetchPlanRepositoryTest"
TCK_TESTS="$TCK_TESTS,com.rey.modelquery.tck.harness.Rfc0018ValueAuditTest"
./mvnw -q verify -pl model-query-tck -am -Dtest="$TCK_TESTS" \
  -Dsurefire.failIfNoSpecifiedTests=false -Drfc0018.audit=true
python3 spike-rfc0018/capture-tck-results.py 6.6.58.Final
./mvnw -q verify -pl model-query-tck -am -Dtest="$TCK_TESTS" \
  -Dsurefire.failIfNoSpecifiedTests=false -Drfc0018.audit=true \
  -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
python3 spike-rfc0018/capture-tck-results.py 7.4.11.Final
```

Both Maven runs exit 1 today, as expected. Run the capture command after each completed run, before changing
Hibernate lines, to preserve that run's Surefire outcomes. The helper stores compact outcomes and compresses
the inspector TSV; it does not copy Surefire XML containing system properties or environment information.

| Hibernate | Tests | PASS | FAIL | ERROR | SKIP | Marker SQL | Literal-free SQL |
|---|---:|---:|---:|---:|---:|---:|---:|
| 6.6.58.Final | 1,481 | 551 | 912 | 0 | 18 | 20,739 | 4,703 |
| 7.4.11.Final | 1,481 | 551 | 912 | 0 | 18 | 20,739 | 4,705 |

Each line records 491 vendor test callbacks per vendor and eight unparameterized callbacks. Eighteen tests
are aborted/skipped by the existing suites; `tck-test-results-<version>.tsv.gz` preserves the authoritative JUnit
outcome, including its reason. The extension's TEST callback labels an execution exception FAIL, including
an aborted test, so those callback statuses must not be substituted for the JUnit totals above.

## What the inspector observes

Existing fixture values serve as markers; fixture contents and expected rows were not rewritten. The inspector
records SQL issued through a `com.rey.modelquery.jpa` execution stack. Fixture setup and baseline SQL outside
that execution stack pass through. Quoted strings and literal numeric/Boolean comparison or membership operands
are candidates. Recording happens before execution, and the inspector defers its assertion until the test's end,
allowing statements, row assertions and cleanup to proceed. An earlier existing assertion, including a SQL snapshot
mismatch under inline handling, can still stop a test before later assertions or statements.

SQL FAIL means a literal candidate was observed; SQL PASS means no candidate was found. These are SQL
observations, not row-correctness claims. The broad detector also matches legitimate structural literals.
The marker regex misses literals in `between a and b`, function arguments and later `IN` elements. SQL PASS
cells are therefore weaker evidence: they establish only that this detector found no candidate, not that every
value was bound. The regex is unchanged and the audit was not rerun; confirmed literal observations remain valid.
Consequently neither all 20,739 SQL candidates nor all 912 failing tests are asserted to be binding defects.
The confirmed input paths below are identified from the actual SQL and the named test's supplied values.

Raw evidence was `tck-value-audit-<version>.tsv.gz` (complete test unique ids, vendor, marker and SQL), kept outside the
repo for size.
`value-path-observations.tsv` selects 138 representative records: each of these 23 paths on both lines and all
three vendors. Its `audit_row` is the row in the decompressed audit TSV, including the header. Its `test` field
names the exact test that observed the path; abbreviated names below are searchable in that field.

| Observed path / observation id | Test evidence | Actual SQL observation |
|---|---|---|
| `comparison-eq` | WRT-07 `every_filters_operator_fixture` | `status='PAID'` |
| `comparison-ne` | WRT-07 `every_filters_operator_fixture` | `status<>'PAID' or status is null` |
| `comparison-gt` | WRT-07 `every_filters_operator_fixture` | `total>500.00` |
| `comparison-ge` | WRT-07 `every_filters_operator_fixture` | `total>=500.00` |
| `comparison-lt` | WRT-07 `every_filters_operator_fixture` | `total<100.00` |
| `comparison-le` | WRT-07 `every_filters_operator_fixture` | `total<=100.00` |
| `comparison-range` | WRT-07 `every_filters_operator_fixture` | `total between 100.00 and 200.00` |
| `filter-membership` | WRT-07 `every_filters_operator_fixture` | `status in ('NEW','SHIPPED')` |
| `eq-ignore-case` | WRT-07 `every_filters_operator_fixture` | `lower(status)='paid'` |
| `like` | FCH-02 `repeated_child_rows_from_a_to_many_child_filter` | `product_code like 'P0%'` |
| `ignore-case` | WRT-07 `every_filters_operator_fixture` | `lower(name) like 'customer 012%'` |
| `subquery-filter` | FLT-14 `exists_and_not_exists_correlate` | Correlated filter `product_code='P007'` |
| `byKeys-chunk` | EXE-14 `by_keys_chunks_over_the_vendor_limit` | Chunk membership `id in (9,8,7,6)` |
| `fetch-key` | FCH-03 `converted_key_column_matches` | Child-round parent key list `in (1,2,3,4,5)` |
| `keyset-UUID` | PAG-18 `every_cursor_type_round_trips` | UUID and equality-prefix cursor values inline |
| `converted-enum` | COL-32 `an_enum_over_an_attribute_converter` | Converted enum stored code `state='C'` |
| `converted-Boolean` | COL-32 `a_constant_filters_and_compares_through_each_mapping` | Stored code `active='Y'` |
| `update-assignment` | WRT-19 `set_expression_reading_a_column` | Assignment `name='Renamed'`, key `id=7` |
| `delete-filter` | WRT-13 `a_delete_blocked_by_a_foreign_key` | Delete predicate/key-selection input `id=1` |
| `insert-select-filter` | WRT-39 `an_insert_select_selects_its_insert_assignments_as_bound_columns` | `id in (1,2)` |
| `conflict-condition` | WRT-24 `rows_per_statement_count_the_conflict_clause_binds` | Conflict input `name<>'x'` |
| `source-keyset` | SRC-08 `a_discriminated_feed_is_exported_by_keyset` | Cursor `mq_branch>'live'`, `mq_id>500` |
| `typed-insert-binds` | WRT-22 `insert_values_binds_jpa_converted_and_jdbc_typed` | Actual typed INSERT uses `?` |

The first 22 paths contain confirmed inline input values. The last is an observed binding success: the
converted/JDBC typed insert-values path already uses parameters. Insert-select's additional assignment columns
also use `?`; its source predicate is the observed leak. A FAIL SQL record containing a bound assignment does
not imply that assignment was inlined. The converted-Boolean NULL assignment test writes SQL `null`, while
its key predicate inlines `id=1`; SQL NULL is not classified here as a converted-value binding defect.

The PAG-18 cursor SQL also contains decimal, timestamp, binary and primary-key prefix operands. The actual
WRT-07 operator matrix exercises read and bulk-write paths, including equality, inequality, ordered comparison,
BETWEEN, membership and pattern predicates. Full SQL remains in the compressed logs, beyond the representative
rows. Isolated C.1 cells separately check every primitive and mapped `sku/state/enabled/token/autoCode` value
under bind and inline handling, with exact ids or DML outcomes and SQL. Proposed explicit-parameter routes
are PASS/FAIL engine observations. The absent no-binder refusal decision is SIMULATED.

## Structural candidates, not confirmed input leaks

- Source discriminator tags such as `select 'live'` and `select 'shelved'` are structural constants. The same
  spelling used as a keyset cursor comparison is an input value and is listed as `source-keyset` above.
- Conflict-arbiter predicates, such as `status <> 'CLOSED'`, may need literal SQL to match a partial index.
  Their presence in the audit is not treated as proof that engine-bound values took an inlining route.
- LIKE escape characters, user-authored expression constants, discriminator restrictions and SQL NULL can
  trigger the broad detector. They are not counted as separate input-binding defects without a supplied-value path.

## By reading only / limits

These are not presented as observed engine paths:

- The absent `valueOperand`, `OperandBinder.converts` and provider inlining/count capability APIs are RFC
  routing proposals. Explicit parameters demonstrate underlying engine behavior, not those APIs.
- An unexercised custom provider/no-binder refusal path is known only from the RFC and current binder contract.
  `C.1 no-binder/reject-enum` evaluates the proposed refusal rule and remains SIMULATED.
- Arbitrary application-authored source literals, custom operators and conflict-arbiter expressions are not
  exhaustively classified by these suites. Reading their construction cannot establish an observed input leak.
- A plain-field binding fix cannot be inferred to fix converted mappings: `ConditionGroup`, `PrimaryKey`,
  keyset operands, executor fetch/assignment paths and Hibernate insert support must preserve mapping types.
  The records above establish execution-family coverage, not every possible model mapping.

Both lines have identical test outcomes and confirmed-path coverage. Hibernate 7 emits two additional literal-free
H2 statements in WRT-43's returning upsert and WRT-25's do-nothing conflict test. All 1,170 functional spike cell
identities/statuses match. Part C's provisional verdict is **keep** the binding requirement: current-engine FAILs
are expected and give concrete implementation targets. Final acceptance and the broader RFC verdicts await sub-step 3.
