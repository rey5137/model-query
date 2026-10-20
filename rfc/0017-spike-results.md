# RFC 0017 acceptance spike: results

Throwaway research on `spike/rfc-0017`; no product engine, processor or public SPI changes. The same test source
(module `spike-rfc0017/`, test sources only) runs on Hibernate 6.6.58.Final and 7.4.11.Final (Jakarta Persistence
3.2.0), sequentially against H2, PostgreSQL 16 (`postgres:16-alpine`, `pg_trgm`) and MySQL 8.0.31 (`mysql:8.0.31`).
Line differences (`applyInferableType`'s argument type, `JpaCriteriaParameter`'s constructor, the
`getSqmPathType()` unwrap) sit behind one reflection adapter, `InferableTypes`; no probe has two copies. Every PASS in
probes 1-4, 6-8 and FT that runs a query asserts its row ids, and where the probe says so the `?`-only statement with
a JDBC bind, or the index in the plan. The PASS cells that run no query of their own assert something else: `0` and
`8` bootstrap (schema and seed), `2` `registerPattern_boolean` (registration), `3` `setup` and `filler` (DDL and
`ANALYZE`), `3` `*/eq_true_seqscan_off` (the plan of a hand-rewritten statement), `5` `provider_check` and
`6` `registry` (function-registry lookups), `7` `*/unwrap` (the criteria path's node type). Probe 5's two gate cells
are SIMULATED, not PASS (finding 5). Provider and database failures are recorded as FAIL observations, never as
JUnit failures. Probe 8's inline cells run twice: in the test JVM (`-ea`) and again in a second surefire execution
without assertions (`*/no_ea`), as production runs.

The complete [raw data](0017-spike-data/README.md) keeps every cell's SQL, binds and error. Probe ids are RFC 0017
§7's (`1`..`7`), plus `FT` (full text, RFC 0017 Unresolved) and `8` (the binder routes under
`hibernate.criteria.value_handling_mode`).

## Matrix

PASS / FAIL / NOT_RUN cells per probe, vendor and line, plus SIMULATED where any (`summarize.py`). A FAIL count
includes the routes and forms the spike tried in order to rule them out, and probe 7's `*/unwrap` cells on 7.4
(finding 7); the findings say which cells answer each question.

| Probe | H2 6.6 | H2 7.4 | PG 6.6 | PG 7.4 | MySQL 6.6 | MySQL 7.4 |
|---|---|---|---|---|---|---|
| 0 bootstrap | 1 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 |
| 1 binding | 20 / 16 / 4 | 24 / 12 / 4 | 24 / 16 / 0 | 28 / 12 / 0 | 20 / 16 / 4 | 24 / 12 / 4 |
| 2 pattern functions | 4 / 0 / 1 | 4 / 0 / 1 | 5 / 0 / 0 | 5 / 0 / 0 | 4 / 0 / 1 | 4 / 0 / 1 |
| 3 index use | 0 / 0 / 1 | 0 / 0 / 1 | 32 / 6 / 0 | 32 / 6 / 0 | 0 / 0 / 1 | 0 / 0 / 1 |
| 4 `ilike` | 6 / 0 / 0 | 6 / 0 / 0 | 6 / 0 / 0 | 6 / 0 / 0 | 6 / 0 / 0 | 6 / 0 / 0 |
| 5 the two checks | 2 / 0 / 0 / 2 sim | 2 / 0 / 0 / 2 sim | 1 / 0 / 1 / 2 sim | 1 / 0 / 1 / 2 sim | 2 / 0 / 0 / 2 sim | 2 / 0 / 0 / 2 sim |
| 6 arrays | 5 / 6 / 0 | 5 / 6 / 0 | 11 / 0 / 0 | 11 / 0 / 0 | 1 / 10 / 0 | 1 / 10 / 0 |
| 7 the binder | 104 / 24 / 0 | 102 / 26 / 0 | 104 / 24 / 0 | 102 / 26 / 0 | 104 / 24 / 0 | 102 / 26 / 0 |
| 8 value handling mode | 44 / 38 / 0 | 44 / 38 / 0 | 44 / 38 / 0 | 44 / 38 / 0 | 44 / 38 / 0 | 52 / 30 / 0 |
| FT full text | 0 / 0 / 3 | 0 / 0 / 3 | 3 / 0 / 0 | 3 / 0 / 0 | 0 / 0 / 3 | 0 / 0 / 3 |

Per binder route, operands × vendors passing (8 operands × 3 vendors = 24), inside a plain pattern function
`mq_eq` = `(?1 = ?2)` unless named:

| Route | 6.6 default | 7.4 default | 6.6 inline | 7.4 inline |
|---|---|---|---|---|
| `cb.equal(path, value)` (Hibernate's own, control) | 24 | 24 | 0 (inlined) | 2 |
| `DECLARED`: `cb.parameter(attributeClass)` | 15 | 15 | 15 | 15 |
| `INFER`: `cb.parameter(type)` + `applyInferableType(path)` (RFC §2) | 15 | 15 | | |
| `OBJECT_INFER`: `cb.parameter(Object.class)` + `applyInferableType` | 9 | 9 | | |
| `ANTICIPATED`: `JpaCriteriaParameter`, anticipated type `path.getNodeType()` | 12 | 24 | | |
| `ANTICIPATED_PATH`: as above, `getSqmPathType()` on 6.6 | **24** | **24** | **24** | **24** |
| `RELATIONAL`: value converted through the mapping model, bound by JDBC type | 18 | 18 | | |
| `VALUE_INFERRED`: `SqmCriteriaNodeBuilder#value(value, path)` | 24 | 24 | 0 | 2 |
| `DECLARED` inside `mq_eq_typed` (`ARGUMENT_OR_IMPLIED_RESULT_TYPE`) | 21 | 21 | | |
| `ANTICIPATED` inside `mq_eq_typed` | 24 | 24 | | |

The 7.4 inline "2" are MySQL's two array operands, which Hibernate maps to JSON there and binds as
`cast(? as json)` even under `inline`. The `cb.equal` and `VALUE_INFERRED` inline counts are the same with and
without JVM assertions (`*/no_ea`).

## Findings

1. **Binding (§7 probe 1): yes for mapping-independent types, no for the RFC's binder.** A `String`, a `LocalDate`,
   an `ORDINAL` enum, a `String[]` and an `Integer[]` bound through `cb.parameter(attributeClass)` reach `mq_eq`,
   `ilike` and (PostgreSQL) `mq_regex_match` on every vendor and both lines with the right rows, and every passing
   statement shows `?` with a JDBC bind for it (the bind check is what would catch an inlined array or ordinal `0`,
   which no text marker names). MySQL's array columns are a line difference behind the same PASS: 6.6 maps them to
   Java-serialized `varbinary` (`setBytes(1,byte[57])`), 7.4 to JSON (`= cast(? as json)`,
   `setString(1,["red","blue"])`); line parity compares status only, so it does not list them. An `@Enumerated(STRING)`
   enum binds `setByte(1,0)`: PostgreSQL refuses
   (`operator does not exist: character varying = smallint`), H2 refuses (`ENUM(...)` and `TINYINT` not comparable;
   a varchar column fails to convert), and MySQL is silently wrong both ways: a varchar column returns all 8 rows
   (`'ACTIVE' = 0`, flagged `INV-5 degrade`), and Hibernate's default native `enum(...)` column returns 0 rows. A JPA
   `@Convert` value fails at `cb.parameter(Sku.class)` with `JdbcTypeRecommendationException`. Adding
   `applyInferableType(path)` changes none of these on either line.
2. **Pattern functions (probe 2): yes.** `registerPattern(name, pattern, BasicType<Boolean>)` compiles and registers
   on both lines. `cb.isTrue(cb.function(...))`, the function passed directly as a predicate, and `not(...)` render
   the bare pattern (`where (sr1_0.name = ?)`, `where not((sr1_0.name = ?))`) on every vendor and both lines; there
   is never an `= true`.
3. **Index use (probe 3): yes for every RFC form under a custom plan; not for `ilike` or `jsonb` under a generic
   one.** On PostgreSQL, both lines, each form's `EXPLAIN` with the captured binds replayed as JDBC binds (so the
   planner sees the values: a custom plan) is a Bitmap Heap Scan on the named GIN index, with the right rows, checked
   twice: on the 8-row fixture with `SET enable_seqscan = off`, and with seqscan on after 20 000 non-matching rows per
   table and `ANALYZE`. Forms: `ilike` against
   `gin (name gin_trgm_ops)`; `mq_array_contains` (`@>`) and `mq_array_overlaps` (`&&`) and Hibernate's
   `arrayIncludes` / `arrayOverlaps` against `gin (tags)` / `gin (nums)`; `mq_jsonb_contains` against `gin (doc)`;
   both full-text forms against `gin (to_tsvector('english', name))`. The statement rewritten to `where (P) = true`
   uses the same index, so the planner does simplify it (moot: Hibernate never renders it, finding 2).
   - Generic plan, at scale (`*/at_scale_generic`): the statement `PREPARE`d with `$n` placeholders and
     `EXPLAIN EXECUTE`d under `plan_cache_mode = force_generic_plan`, the plan a reused server-side prepared statement
     can get, built without the values (the plan shows `$1`). The four array forms and both configured full-text forms
     keep their GIN index. `ilike` against `gin_trgm_ops` (`~~* like_escape($1, '\')`) and `mq_jsonb_contains`
     (`doc @> $1`) become a Seq Scan (cost 548 and 498 on 20 008 rows): without the pattern or document the planner
     doesn't cost the index below the scan. Not probed: whether `plan_cache_mode = auto` ever picks that plan for a
     statement pgjdbc has server-prepared (after `prepareThreshold`, 5 executions).
   - `~` against the trigram index (RFC §6) was not in §7's list and was not probed.
4. **`ilike` (probe 4): yes.** PostgreSQL and H2 render `col ilike ? escape '\'` with a bound value; `%`, `_` and `\`
   escaped by the caller match literally (contains, starts-with, ends-with). MySQL renders
   `lower(col) like lower(?) escape '\\'`, with the right rows on the fixture: an emulation, gated by the vendor check.
5. **The two checks (probe 5): the registry answer is observed; the gate is simulated.** The function-registry lookup
   that `ProviderSupport` would do answers `mq_regex_match` false on H2 and MySQL and true on PostgreSQL, both lines
   (PASS). The two gate cells are SIMULATED: a decision table over that real registry answer (`gate()`, vendor check
   first) yields `MQ4015` for a profile answering false and `MQ4014` for one answering true on H2 or MySQL, but it runs
   no engine code, so it shows only that the registry answer is enough to decide; the order of the checks and "before
   any statement" are AC-FLT-24's to test once `MQ4014` is built. Without the gate Hibernate does not refuse an
   unregistered function: it renders `mq_regex_match(...)` through and the database refuses it (`Function
   "MQ_REGEX_MATCH" not found`, `FUNCTION test.mq_regex_match does not exist`, a `SQLGrammarException`). The provider
   check is the only thing between a wrong profile and that error.
6. **Arrays (probe 6).** Both lines register the same names: `array_contains`, `array_includes`, `array_intersects`
   with its alias `array_overlaps`, and each `*_nullable` twin; there is no `array_contains_all`.
   - PostgreSQL: `array_includes` and `cb.arrayIncludes` render `tags@>?`, `array_intersects`, `array_overlaps` and
     `cb.arrayOverlaps` render `nums&&?`, right rows, index used (finding 3). `array_contains` is element
     containment: with a one-element value it renders `tags@>cast(array[?] as varchar array)`.
   - H2: its own forms. `array_contains(tags,?)` is native; `array_includes` is a `system_range` sub-select
     emulation (right rows); `array_intersects`, `array_overlaps` and `cb.arrayOverlaps` fail to prepare on both
     lines (`Unknown data type: "?3"`), with a class-typed `cb.parameter(Integer[].class)` and with the operand typed
     by the column (`ANTICIPATED_PATH`, `*/anticipated_path`) alike: the failure is the functions' H2 rendering, not
     the parameter. On PostgreSQL the `ANTICIPATED_PATH` calls render and match as the class-typed ones.
   - MySQL: none registered. `cb.arrayIncludes` / `cb.arrayOverlaps` throw `NullPointerException` (no descriptor);
     `cb.function("array_…")` reaches the database, which refuses it. The array columns themselves map differently
     per line (finding 1: Java-serialized bytes on 6.6, JSON on 7.4).
7. **The binder (probe 7): no for `applyInferableType`; yes for two other routes.** `applyInferableType` (RFC §2, on
   `cb.parameter(type)` or `cb.parameter(Object.class)`) types nothing by the mapping (table above). Two routes type
   the operand by the column's mapping for all 8 operand types on all 3 vendors and both lines, inside a plain
   pattern function, with the right rows, `?` in the statement, and MySQL's `STRING` enum bound `setString('ACTIVE')`:
   - `ANTICIPATED_PATH`: `new JpaCriteriaParameter(null, pathType, false, nodeBuilder)` where `pathType` is the
     path's `getNodeType()`, unwrapped through `getSqmPathType()` where the node type has that method. The value is
     set on the query like any parameter. Two line differences, both behind the adapter: the constructor's second
     parameter type (`org.hibernate.query.BindableType` on 6.6, `org.hibernate.type.BindableType` on 7.4), found by
     arity; and the unwrap, which is 6.6-only (`*/unwrap` cells). On 6.6 the node type is `BasicSqmPathSource` and
     `getSqmPathType()` yields `BasicTypeImpl` for an enum, `CustomMutabilityConvertedBasicTypeImpl` for `@Convert`,
     `BasicArrayType` for arrays. On 7.4 the node type already is that type and has no `getSqmPathType()`; the
     spike's adapter then silently passes the raw node type (`InferableTypes#pathType`), so 7.4's 24 rest on that
     fallback, which is the `ANTICIPATED` route. Without the unwrap (`ANTICIPATED`) 6.6 throws `ConversionException`
     for enums and `@Convert`; 7.4 passes.
   - `VALUE_INFERRED`: `SqmCriteriaNodeBuilder#value(value, path)`, what `cb.equal(path, value)` uses; the same
     signature on both lines, but in `org.hibernate.query.sqm.internal`, and the value is carried by the expression,
     not set per statement.

   Ruled out: `RELATIONAL` (converting through the mapping model's attribute) degenerates to `DECLARED` for enums:
   the enum attribute's JDBC mapping has no value converter, so the route records `relational=Status:ACTIVE` and
   binds `cb.parameter(Status.class)` with the enum itself, which is `setByte(0)` for a `STRING` enum, as `DECLARED`
   does. It converts `@Convert` correctly inside `mq_eq` (`setString('SKU:S3')`) but fails for it on 7.4 inside
   `mq_eq_typed`. Registering the function with `ARGUMENT_OR_IMPLIED_RESULT_TYPE` (`mq_eq_typed`) fixes `STRING` enums
   with a plain `cb.parameter` on both lines, but not `@Convert` (`JdbcTypeRecommendationException`), and only for
   the catalogue's own functions, never a user's (`similar_to_ci` in the RFC's summary).
8. **Value handling mode (added probe 8).** Under `hibernate.criteria.value_handling_mode=inline`:
   - `VALUE_INFERRED` is **not acceptable**: it inlines the value (`where (sr1_0.name = 'a_b')`, `date '2024-01-03'`,
     `'ACTIVE'`), so the "statement shows `?`" rule (§7 probe 1, R-FLT-08) depends on a factory-wide setting the
     library doesn't control. Where it doesn't inline, it breaks: on 6.6 enums, `@Convert` and arrays throw
     `NullPointerException` (`expressible is null`); on 7.4 `@Convert` trips a JVM `assert` inside Hibernate
     (`AssertionError: null`; the test JVM runs with `-ea`), and PostgreSQL's `String[]` literal fails
     (`character varying[] = text[]`). Only MySQL's JSON-mapped arrays on 7.4 stay bound. Rerun without assertions
     (`*/no_ea`, as production runs), the 7.4 `@Convert` cell fails loudly instead, before any statement:
     `HibernateException: Could not convert 'Sku' to 'java.lang.String' using StringJavaType`; neither inlined nor
     bound wrong. Every other `VALUE_INFERRED` and `cb.equal` inline cell is the same with and without `-ea`.
   - `ANTICIPATED_PATH` stays `?` and correct for all 24 operand-vendor pairs on both lines, inline or default, as does
     a plain `cb.parameter` wherever it was correct (an explicit parameter is never inlined).
   - `cb.equal(path, value)` itself inlines under that setting; any engine path that relies on Hibernate's
     value-to-parameter conversion rather than an explicit parameter inlines too.
9. **Full text (`@@`, RFC Unresolved).** PostgreSQL only, both lines (`mq_ts_match` = `(?1 @@ ?2)` and
   `mq_fulltext` are registered for its dialect only; NOT_RUN elsewhere).
   - The field form, `mq_ts_match(to_tsvector(cb.literal("english"), name), to_tsquery(cb.literal("english"), ?))`,
     renders `to_tsvector('english',sr1_0.name) @@ to_tsquery('english',?)`: the configuration inline as a literal,
     the query text bound, right rows, and it uses `gin (to_tsvector('english', name))` on the fixture, at scale and
     under a generic plan. The single-function form (`mq_fulltext`, configuration in the pattern) renders and plans
     the same.
   - Without a configuration (`to_tsvector(name) @@ to_tsquery(?)`, the server's `default_text_search_config`) the
     rows are right but the plan is a Seq Scan, even with `enable_seqscan = off` (cost 10^10), at scale and under a
     generic plan: the expression index can't match a one-argument `to_tsvector`.
   - Not probed: `to_tsquery` raises on free user text such as `acme corp` (it wants tsquery syntax);
     `plainto_tsquery` / `websearch_to_tsquery` would be the form for a request value.

**Line parity.** The two lines differ in 68 cells, all in probes 1, 7 and 8 (`line-parity.tsv`): `ANTICIPATED`
without the unwrap (6.6 fails, 7.4 passes), the unwrap itself (`*/unwrap`: found on 6.6, absent on 7.4),
`OBJECT_INFER` inside `mq_eq_typed`, `RELATIONAL` for `@Convert`, and MySQL's JSON arrays under `inline`, with and
without `-ea`. Every other probe has the same statuses on both lines. Parity compares status only: a cell that passes
on both lines with different SQL or binds is not listed, and MySQL's array mapping (Java-serialized bytes on 6.6, JSON
on 7.4, finding 1) is such a line difference.

## Consequences for the RFC

Line numbers are `rfc/0017-custom-filter-operators.md`'s at the spike's base.

1. **The binder route (§2 step 4, :143-162; probe 7, :312-315; AC-FLT-22, :337-338; acceptance D-n, :414-415).**
   `SqmExpression#applyInferableType` does not type an operand by the column's mapping on either line: :159-161 is
   wrong, and AC-FLT-22's `STRING` enum, `@Convert` and MySQL `= 0` cases would all fail with it. Adopt
   `ANTICIPATED_PATH`: `OperandBinder#parameter(cb, typedBy, type)` returns a `JpaCriteriaParameter` whose anticipated
   type is `typedBy`'s node type, unwrapped through `getSqmPathType()` on 6.6. It keeps the RFC's signature (:154),
   returns a `ParameterExpression` whose value `JoinContext` sets per statement (step 6, :168-170), and stays `?` under
   `value_handling_mode=inline`. Its line adapter has two differences, found by reflection in `model-query-hibernate`
   as today's per-line adapters are: the constructor (`BindableType` moved from `org.hibernate.query` to
   `org.hibernate.type`), and the unwrap, 6.6-only (7.4's node type already is the attribute's type and has no
   `getSqmPathType()`, finding 7). The spike's adapter silently fell back to the raw node type when the unwrap was
   missing; the product adapter must instead know per line which node type it expects and refuse loudly on anything
   else.
   Risk: `JpaCriteriaParameter` lives in `org.hibernate.query.sqm.tree.expression` and `getSqmPathType()` on the
   node type is not Jakarta or Hibernate public API, so a later Hibernate line may move or reshape them; the binder
   needs a TCK case per line, and its failure must be loud (`MQ1502`-style refusal), never a fallback to
   `cb.parameter(class)`. Do not adopt `SqmCriteriaNodeBuilder#value`: it is in an `internal` package, it inlines the
   value under `inline` (finding 8), and it breaks for enums, `@Convert` and arrays there.
   **Decided:** `ANTICIPATED_PATH` (a `JpaCriteriaParameter` typed by the column's node type), with a loud refusal on
   failure; not `SqmCriteriaNodeBuilder#value`.
2. **The MySQL degrade sentence (:146-148, :392).** "On MySQL `status_col = 0` is true for every non-numeric string"
   holds for a varchar column; for Hibernate's default `@Enumerated(STRING)` DDL on MySQL (native `enum(...)`) the
   ordinal bind matches no row instead. Both are silently wrong, and PostgreSQL and H2 refuse; the sentence and
   AC-FLT-22 should name both MySQL shapes.
3. **Hibernate's array functions in the catalogue (:236-238).** They render `@>` and `&&` on PostgreSQL on both lines
   (`array_includes`, `array_intersects` / `array_overlaps`, `cb.arrayIncludes`, `cb.arrayOverlaps`; not
   `array_contains`, which is element containment), and use the index. But the provider check (:246-250) looks up a
   registry function, and these are registered on H2 too, where `array_intersects` / `arrayOverlaps` fail with
   `Unknown data type: "?3"` and `array_includes` is an emulation. So with Hibernate's functions a custom profile
   answering `ARRAY_CONTAINMENT` true on H2 passes check 2 and reaches a database error, which contradicts :258-259
   and AC-FLT-24's `MQ4014` case. Keep the `mq_array_contains` / `mq_array_overlaps` pattern functions registered for
   PostgreSQL's dialect only (same SQL, same index use), or make the provider answer that feature by dialect rather
   than by registry lookup. Either way H2's overlaps and MySQL's arrays (no functions; `cb.arrayIncludes` NPEs) must
   be gated before rendering. With the operand typed by the column (`ANTICIPATED_PATH`) H2's overlaps fail the same
   way, so the failure is H2's rendering of the functions, not the class-typed parameter (finding 6). The spike's
   recommendation is the `mq_*` functions, one mechanism for every pattern feature. **Decided:** `mq_array_contains`
   / `mq_array_overlaps` pattern functions, registered for PostgreSQL's dialect only.
4. **`ILIKE` on the provider side (:248-249, :231-232).** The provider answers `ILIKE` true always, and on MySQL
   Hibernate renders the `lower(col) like lower(?)` emulation (finding 4). A custom profile answering `ILIKE` true on
   MySQL therefore gets exactly the silent emulation :231-232 says R-VND-10 forbids. If that matters, the provider
   should answer `ILIKE` by dialect (PostgreSQL and H2) rather than always. **Decided:** the provider answers `ILIKE`
   by dialect (PostgreSQL, H2); elsewhere `MQ4014`.
5. **Full text (Unresolved, :408-409).** Ship it, PostgreSQL-only, behind a new feature (for example `FULL_TEXT`),
   as a **value-form** catalogue operator rather than the field form: `fullTextMatch(String configuration)`, a
   `FilterOperator<String, String>` whose renderer wraps the column in `to_tsvector(config, col)` and the bound value
   in a tsquery function, through `mq_ts_match`. The configuration must be a required, validated constant of the
   definition, rendered as a literal: the expression index only matches `to_tsvector('<config>', col)`, and the
   default-configuration form never uses it (finding 9). The field form with `Expr.function` operands works in raw
   Criteria but would make every caller build both function calls and pass the configuration as a literal. Before
   building it, a follow-up probe must pick the tsquery function for request text (`plainto_tsquery` or
   `websearch_to_tsquery`; `to_tsquery` raises on plain words with spaces). §6 (:278-286) then gains the
   `gin (to_tsvector('<config>', col))` line. **Decided:** ship `fullTextMatch(config)`, the PostgreSQL-only value
   form, behind a new `FULL_TEXT` feature, the configuration a validated constant rendered as a literal; a follow-up
   probe picks `plainto_tsquery` or `websearch_to_tsquery` first.
6. **No-binder fallback (step 5, :163-167).** Consistent: `String`, `LocalDate` and `ORDINAL` enums bind correctly by
   class, `STRING` enums and `@Convert` don't (finding 1). On PostgreSQL, the vendor the array operators are
   registered for, arrays also bound correctly by class in the fixture, so refusing them without a binder is
   conservative, not required there; keep it, since an array's element mapping can still differ. MySQL's passing
   array cells say nothing about this: they compare Java-serialized bytes (6.6) or JSON (7.4), not arrays. `UUID`,
   numbers and `Boolean` were not probed.
7. **`= true` (§7 probe 3, :305).** Confirmed and moot: the planner keeps the index for `(P) = true`, and
   Hibernate never renders `= true` for `cb.isTrue(fn)` (finding 2).
8. **Index list (§6, :278-286).** `~` against `gin_trgm_ops` (:282) is asserted but was not in the probe list and is
   unprobed; either probe it or soften the sentence. The `ilike`-against-`gin_trgm_ops` and `jsonb`-containment lines
   hold for a custom plan only: under a generic plan (`plan_cache_mode = force_generic_plan`) both are a Seq Scan at
   20 000 rows, while the array and configured full-text forms keep their index (finding 3). §6 should say the index
   use is the planner's, per plan, not guaranteed by the operator.
9. **The renderer and inlining (:122-124).** The guarantee holds only because the operand is an explicit parameter:
   `cb.equal(path, value)`-style value binding inlines under `value_handling_mode=inline` (finding 8). The docs page
   should say a renderer must use the `right` expression it is given, never `cb.equal(left, someValue)` or
   `cb.literal`, and the engine's own value paths should be checked for the same exposure (outside this RFC).
