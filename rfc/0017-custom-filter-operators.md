# RFC 0017 — Custom filter operators

- **Status:** accepted 2026-10-21 as D-167 (after two `architect-review`s and the acceptance spike, §7). M29, 0.10.0
  (D-148).
- **Affects:**
  - **`api/12`:** new R-FLT-20 (`FilterOperator`, `Filters.op`, `Having.op`, `MQ1313`), R-FLT-21 (how an operator's
    value is prepared, converted, typed and bound) and R-FLT-22 (NULLs and negation), AC-FLT-21 to AC-FLT-27 and
    AC-FLT-32 (full text). R-FLT-08 covers the operand the engine binds; R-FLT-13's skip rule covers the `Optional`
    form; AC-FLT-01's "every operator on every Tier-1 vendor" excludes gated operators. The escape hatch (`add`, D-24)
    is unchanged.
  - **`api/10`:** R-COL-18's binding bans are lifted for an operator's value only, when the provider's `OperandBinder`
    types it by the column's mapping (R-FLT-21).
  - **`api/16`:** new `Condition.Kind.OPERATOR` recording the operator, the column and the value or right operand
    (R-INS-13); R-INS-04 amended (operators compare by identity); R-INS-07 gains `FilterMatchers.op(...)`.
  - **`vendor/40`:** new R-VND-23: `OperatorFeature` (a `core` enum), `VendorProfile#operators(OperatorFeature)` and
    `ProviderSupport#operators(EntityManagerFactory, OperatorFeature)`, the two checks behind the shipped catalogue
    (§4), `FULL_TEXT` among them; new R-VND-24: `ProviderSupport#operands(EntityManagerFactory)`, an `OperandBinder`
    that types an operator's parameter by the column's mapping (§2), in the shape of `ConstantRenderer` (R-VND-16).
  - **`api/10`:** R-COL-17's CASE conditions refuse `OPERATOR` (`MQ1505`), as they refuse `CUSTOM` (§5).
  - **`model-query-hibernate`:** a catalogue, `HibernateOperators` (§4), `fullTextMatch(configuration)` included, a
    `FunctionContributor` that registers the `mq_*` pattern functions it renders through for PostgreSQL's dialect
    only, and an `OperandBinder` per served line (§2).
  - **`reference/90`:** new `MQ1313` (an operator renderer returned `null` or threw) and `MQ1315` (a full-text
    configuration that is not a plain SQL identifier); `MQ4014` and `MQ4015` widened to an operator feature the
    provider or the target lacks; `MQ1502`'s message names an operator's value that can't be bound (§2); `MQ1804`'s
    message names an operator in a conflict update's `where` (§2).
  - **`reference/92`:** D-148 (release and name, decided before acceptance), D-167 (acceptance), Q-28.
  - **`delivery/61`:** the incubating list.
  - **`docs/site`:** the filters page gains "Custom operators" (both tabs, CC-DOC-01), with the index advice (§6).
- **Discussion:** to open
- **Target:** 0.10.0 (M29, D-148), `@Incubating`, additive.

## Summary

A filter can use an operator the library doesn't ship, such as PostgreSQL's `ILIKE`, `~`, `@>` or `&&`, as a typed,
named, reusable definition rather than through the opaque `add((ctx, cb) -> …)` escape hatch. A `FilterOperator<C, V>`
is defined once (`static final`, INV-9) and applied with `Filters.op(operator, column, value)`. It gets what built-in
operators get: compile-time column and value types, a value the engine binds and types by the column's attribute, an
`Optional` form that skips, a converted column's value converted, a loud refusal on a bad renderer, and a recorded
condition that query inspection and `FilterMatchers` can see. `model-query-hibernate` ships a small catalogue (`ilike`,
regex match, array and `jsonb` containment, PostgreSQL full text). Its reach is two facts, the provider's and the
vendor's, so a PostgreSQL-only operator on an H2 target is refused before any statement and reported by
`PortabilityCheck`.

```java
// Ships in model-query-hibernate
Filters.of(QOrderView.class)
    .op(HibernateOperators.ilike(LikeMode.CONTAINS), QOrderView.CUSTOMER_NAME, Optional.ofNullable(q))
    .op(HibernateOperators.arrayOverlaps(), QOrderView.TAGS, wantedTags);       // String[] column, String[] value

// User-defined, once
public static final FilterOperator<String, String> SIMILAR =
    FilterOperator.of("similar",
        (ctx, left, right) -> ctx.cb().isTrue(ctx.cb().function("similar_to_ci", Boolean.class, left, right)));
```

## Motivation

The concrete case: a client searching a PostgreSQL column with `ILIKE`. Today they have two choices.

- **`likeIgnoreCase`** renders `lower(col) LIKE ?` on every vendor (R-FLT-07, D-22). It is portable, but it is not
  `ILIKE`: under a C collation `lower()` folds only ASCII, and an existing `gin (col gin_trgm_ops)` index on the bare
  column can't serve `lower(col)`.
- **`add((ctx, cb) -> ((HibernateCriteriaBuilder) cb).ilike(path, pattern))`** gives real `ILIKE`, but:
  - the column and value aren't typed or checked, and a converted column's value isn't converted;
  - the caller escapes `%`/`_` by hand, and nothing stops `cb.literal(value)` inlining the value (against R-FLT-08);
  - there is no `Optional` form, so skipping takes a `when(...)` around it;
  - inspection records an opaque `CUSTOM` with at most a label, so a unit test can't assert the column or value
    (`api/16` R-INS-03);
  - every call site repeats the lambda and the cast.

`Filters` and `Having` are sealed (D-85), so a user can't add an operator method. Without an operator type, every
operator the library doesn't ship stays opaque for good.

The same holds for the operators PostgreSQL indexes by: `@>` and `&&` on arrays and `jsonb` (GIN), `~` (trigram GIN),
`@@` (full text). Their function forms (`arraycontains`, `jsonb_contains`) are reachable through `Expr.function` today
(D-115), but a function call can't use the operator's index, so the operator form is the one that matters.

## Design

### 1. `FilterOperator<C, V>` (R-FLT-20)

```java
@Incubating
public final class FilterOperator<C, V> {
    /** Right side of the column's own type: a value is converted as eq's is and bound typed by the attribute. */
    public static <C> FilterOperator<C, C> of(String name, OperatorRenderer<C, C> renderer);

    /** Right side of its own type V, bound typed by valueType, unconverted. */
    public static <C, V> FilterOperator<C, V> of(String name, Class<V> valueType, OperatorRenderer<C, V> renderer);

    /** The same operator, refused (MQ1001, R-COL-14) on a column whose attribute isn't an attributeType. */
    public FilterOperator<C, V> requiringAttribute(Class<?> attributeType);

    /** The same operator, refused before any statement where the provider or the vendor lacks feature (§4). */
    public FilterOperator<C, V> requires(OperatorFeature feature);

    /** The same operator with its value prepared before binding (escaping, wildcards); recorded unprepared. */
    public FilterOperator<C, V> preparing(UnaryOperator<V> prepare);

    public String name();
}

@Incubating
@FunctionalInterface
public interface OperatorRenderer<C, V> {
    /** left is the column's path, of the attribute's type on a converted column (D-37), though typed C. */
    Predicate render(OperatorContext ctx, Expression<C> left, Expression<V> right);
}

@Incubating
public interface OperatorContext {
    CriteriaBuilder cb();
}
```

- **Immutable and shareable** (INV-9). An operator holds a name, its types, an optional attribute check, an optional
  feature, an optional `prepare` and a renderer; it captures no `CriteriaBuilder` (D-24's reason). Each `requiring…`,
  `requires` and `preparing` returns a new instance, so they belong where the constant is defined, never inline at a
  call site (§3).
- **Equality is identity.** Two definitions with one name are two operators. The name is for logs, inspection and
  messages only: it is a constant, never a request value, as R-INS-03 requires of a label, and a null or blank one is
  `MQ1301`.
- **No class token for the column.** A `Class<C>` can't express `String[]` for every element type, `List<String>` or
  `Map<String, Object>`, so the factories take none: `C` is inferred from the call, and `requiringAttribute` adds the
  runtime check where the operator needs one (`ilike` requires `String`).
- **The renderer sees two expressions, never the operand's value.** The engine binds that value itself (§2), so the
  operand can't be inlined by mistake. That holds because `right` is an explicit parameter, which Hibernate never
  inlines, even under `hibernate.criteria.value_handling_mode=inline`; `cb.equal(left, someValue)` lets Hibernate
  turn the value into a parameter itself, and under that setting it inlines it (spike finding 8). So R-FLT-08 is
  guaranteed for the operand only, and the docs say a renderer must use the `right` it is given and never capture a
  value, through `cb.equal(left, someValue)`, `cb.literal` or otherwise. Whether the engine's own value paths have the
  same exposure is Q-28.
- **Layering.** `FilterOperator`, `OperatorRenderer`, `OperatorContext` and `OperatorFeature` are `core` types that see
  `jakarta.persistence.criteria` and the JDK only (INV-7). `OperatorFeature` names features and holds no SQL; the facts
  about them live in `jpa`'s `VendorProfile` and `ProviderSupport` (§4).

### 2. `Filters.op` and `Having.op` (R-FLT-20, R-FLT-21, R-FLT-22)

```java
<C, V> Filters<M> op(FilterOperator<C, V> operator, ScalarField<M, C> column, V value);
<C, V> Filters<M> op(FilterOperator<C, V> operator, ScalarField<M, C> column, Optional<? extends V> value);
<C, V> Filters<M> op(FilterOperator<C, V> operator, ScalarField<M, C> left, ScalarField<M, V> right);
```

- **Value form, R-FLT-21.** In order:
  1. `null` is `MQ1301` naming the column, as for every value form. A literal `null` argument doesn't compile (the
     `Optional` and field overloads are ambiguous), so `MQ1301` is reached through a typed variable (P-2).
  2. `prepare` runs.
  3. For a `FilterOperator<C, C>` the value goes through a converted column's converter to the attribute's type
     (`ConditionGroup.bound`, D-37). For a `FilterOperator<C, V>` it binds unconverted.
  4. The engine binds it as a statement parameter typed by the column's mapping (the `<C, C>` form) or by the
     declared `valueType` (the `<C, V>` form), never by `value.getClass()` alone. A `core` parameter can only be
     typed by a Java class (`cb.parameter(Class)`, `JoinContext.parameter`), and for an enum the attribute's class is
     the value's class: the parameter knows nothing of `@Enumerated(STRING)` or a JPA `@Convert`. Inside
     `cb.function(...)` Hibernate then binds a `STRING` enum by ordinal (`setByte(0)`), and a `@Convert` value fails
     to bind (spike finding 1). PostgreSQL and H2 refuse the ordinal, but MySQL is silently wrong both ways (INV-5):
     over a `varchar` column `status_col = 0` is true for every non-numeric string, so every row matches, and over
     Hibernate's default native `enum(...)` column no row does. So the parameter comes from a provider hook,
     R-VND-24, in the shape of `ConstantRenderer`:

     ```java
     @Incubating
     public interface OperandBinder {   // core
         <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type);
     }
     ```

     `ProviderSupport#operands(EntityManagerFactory)` supplies it, resolved once per executor and carried by
     `RenderOptions.withOperandBinder(...)`. For the `<C, C>` form `typedBy` is the column's path; for the `<C, V>`
     form it is absent and the declared `valueType` types the parameter.

     `model-query-hibernate` implements it as a `JpaCriteriaParameter` whose anticipated type is `typedBy`'s node
     type (the spike's `ANTICIPATED_PATH`), the route that typed all eight operand types by the column's mapping on
     every Tier-1 vendor and both lines, MySQL's `STRING` enum bound as `setString('ACTIVE')` (spike finding 7). It is
     a `ParameterExpression` whose value `JoinContext` sets per statement (step 6), and it stays `?` under
     `value_handling_mode=inline` (finding 8). `SqmExpression#applyInferableType` types nothing by the mapping on
     either line, so it is not used.
     - **One adapter, two line differences**, found by reflection as `model-query-hibernate`'s other per-line
       adapters are: the constructor (its `BindableType` parameter is `org.hibernate.query.BindableType` on 6.6 and
       `org.hibernate.type.BindableType` on 7.4), and the unwrap, on 6.6 only (6.6's node type is a
       `BasicSqmPathSource` whose `getSqmPathType()` is the attribute's type; 7.4's node type already is that type
       and has no `getSqmPathType()`).
     - **Served lines only.** `operands(emf)` returns the binder on Hibernate 6.6.x and 7.4.x only
       (`org.hibernate.Version`), as `treats(emf)` does (R-VND-26), and is empty elsewhere, so step 5's rules apply.
     - **Loud, never a fallback.** The adapter knows per line which node type it expects. A node type it doesn't
       expect, a missing unwrap on 6.6 or a missing constructor is `MQ1502` naming the operator and the column,
       before any statement; it never falls back to the raw node type or to `cb.parameter(class)`. Both types it
       relies on are outside Jakarta and Hibernate's public API (`org.hibernate.query.sqm.tree.expression`, and
       `getSqmPathType()`), so a later line may move them; the TCK binds every operand type on each line.
     - **Not `SqmCriteriaNodeBuilder#value(value, path)`**, the other route that typed by the mapping: it lives in an
       `internal` package, carries the value in the expression rather than setting it per statement, inlines it
       under `value_handling_mode=inline`, and breaks there for enums, `@Convert` and arrays.
  5. With the binder, the value may be an enum, an array or a converted value, which `Expr.value` refuses
     (R-COL-18). Without one (no `ProviderSupport`, or `RenderOptions.portable()`), only a value whose binding doesn't
     depend on the mapping binds: `String`, a number, `Boolean`, a `java.time` value or a `UUID`. Anything else is
     `MQ1502`, never bound by its class. `Optional`, `Collection` and `Map` values are always `MQ1502`: a
     `Collection` operand is an `IN`, and a `Map` has no portable binding (a `jsonb` value is passed as its text).
     The spike bore the split out: `String`, `LocalDate` and `ORDINAL` enums bound correctly by class, `STRING` enums
     and `@Convert` didn't. Arrays bound correctly by class on PostgreSQL's fixture, so refusing them without a
     binder is conservative there; it stays, because an array's element mapping can still differ. `UUID`, numbers and
     `Boolean` were not probed, and bind by class as `Expr.value` binds them today.
  6. The bind is per statement through `JoinContext`'s parameters, so every statement that renders the filter binds
     it: the content query, the count, each export page, a keyset page, and a sub-select's parameters merged into its
     outer query (`JoinContext#addSubqueryBinds`). It counts toward R-FLT-09's bind limit.
- **`Optional` form.** Empty skips the filter, with no join (R-FLT-01, R-FLT-03).
- **Field form.** Both sides are fields of the same model, as `compare` is (R-FLT-18). Two converted columns whose
  attribute types differ are `MQ1001`, as `compare` refuses them. An expression on either side is an operand
  (R-INS-09). No `Optional` twin.
- **NULLs and negation, R-FLT-22.** An operator renders as its renderer builds it, with no `OR col IS NULL` added: a
  NULL column or operand makes it unknown, so the row doesn't match. `not(g -> g.op(...))` is plain `NOT (…)`
  (R-FLT-05) and also drops the NULL rows. R-FLT-04's NULL rule for `ne` and `notIn` doesn't apply, and the docs page
  says so.
- **Groups and sub-queries.** Inside `or`, `not`, `exists` and a sub-select it behaves like any operator. A join it
  first needs inside `or` is LEFT (R-FLT-10). A column outside an `exists` path is `MQ1302`, as for every operator.
- **An upsert's `where`.** The two differ, and an `op` follows each (`api/14` R-WRT-34, R-WRT-52):
  - an arbiter's `onConflict(...).where(...)` accepts only plain comparisons, so any `op`, value or field form, is
    `MQ1804` at `build()`, naming the operator;
  - a conflict update's `doUpdate(...).where(...)` refuses only a bound parameter, on first execution, so a value-form
    `op` is `MQ1804` then, naming the operator rather than `Expr.value`, and a field-form `op` runs.
- **The renderer's result.** If `render` returns `null` or throws a `RuntimeException`, that is `MQ1313` naming the
  operator and the column, at build time and before any statement, with the thrown exception as the cause. A
  `ModelQueryException` thrown from `render` is rethrown unwrapped, so an engine error keeps its own code. `add` has no
  equivalent for a throw (`MQ1305` covers only `null`), hence a separate code.
- **`having`.** `Having` gains the same three methods over an `AggregateField`, for an operator whose `C` is the
  aggregate's type (`api/13`). The parameter is typed by the aggregate's type; only `min` and `max` over a converted
  column take the column's mapping (R-AGG-04).

### 3. Inspection and test matchers (R-INS-13)

- **What is recorded.** `op(...)` records `Condition.Kind.OPERATOR` with:
  - `column()`;
  - `operator()`, an `Optional<FilterOperator<?, ?>>` that is empty for every other kind;
  - either one value in `values()`, or the right operand in `right()`.
- **Values.** A value is recorded as passed, before `prepare` and any converter, as R-INS-01 records other values.
  `toString` prints the operator's name with values as `?` (R-INS-05).
- **Equality.** R-INS-04 is amended so that two `OPERATOR` conditions are equal when their operators are the same
  instance and their columns and values are equal.
  - Two queries built from equal calls therefore stay equal when they share the operator constant.
  - An operator built inline per call (`preparing(...)` at the call site) makes each query unequal; the docs say to
    keep operators in constants.
- **Matchers.** `FilterMatchers.op(operator, column, value)` and `op(operator, left, right)` match the condition, so

  ```java
  assertThatQuery(q).containsFilter(op(HibernateOperators.ilike(CONTAINS), CUSTOMER_NAME, "acme"));
  ```

  passes in a unit test with no database. A catalogue factory (`ilike(mode)`, `arrayOverlaps()`) returns one cached
  instance per argument, so the test's operator is the query's.

### 4. The catalogue and its two checks (R-VND-23)

`HibernateOperators` ships operators whose SQL is a database's own (R-VND-09, as `Expr.function` is). Each is a
cached factory, generic where the column type is:

| Factory | Type | Renders | Feature |
|---|---|---|---|
| `ilike(LikeMode)` | `FilterOperator<String, String>` | `col ILIKE ? ESCAPE '\'` | `ILIKE` |
| `regexMatch()` | `FilterOperator<String, String>` | `col ~ ?` | `REGEX_MATCH` |
| `<E> arrayContains()`, `<E> arrayOverlaps()` | `FilterOperator<E[], E[]>` | `col @> ?`, `col && ?` | `ARRAY_CONTAINMENT` |
| `<C> jsonbContains()` | `FilterOperator<C, String>` | `col @> cast(? as jsonb)` | `JSONB_CONTAINMENT` |
| `fullTextMatch(String config)` | `FilterOperator<String, String>` | `to_tsvector('c', col) @@ …('c', ?)` | `FULL_TEXT` |

- **`ilike(LikeMode)`** prepares the value as `like` does (R-FLT-06: escaped, wrapped for `CONTAINS`, `STARTS_WITH`
  and `ENDS_WITH`), renders the escape character, and requires a `String` attribute. It goes through
  `HibernateCriteriaBuilder#ilike`.
  - PostgreSQL and H2 have `ILIKE` natively and match `%`, `_` and `\` literally once escaped (spike finding 4).
    MySQL has none, and Hibernate emulates it silently as `lower(col) like lower(?)`, which R-VND-10 forbids. So
    `ILIKE` is a feature, false on MySQL on both checks.
  - It doesn't replace `likeIgnoreCase`, whose one rendering everywhere D-22 and R-VND-10 keep.
- **Pattern functions.** `regexMatch`, the array operators and `jsonbContains` render through boolean-valued pattern
  functions named `mq_*` (`mq_regex_match` as `(?1 ~ ?2)`, `mq_array_contains` as `(?1 @> ?2)`, `mq_array_overlaps` as
  `(?1 && ?2)`, `mq_jsonb_contains`), registered by a `FunctionContributor` in `model-query-hibernate`
  (`META-INF/services/org.hibernate.boot.model.FunctionContributor`) for PostgreSQL's dialect only. The renderer calls
  `cb.isTrue(cb.function("mq_…", Boolean.class, left, right))`, which renders the bare pattern, never `= true`, on
  both lines (finding 2).
  - **Not Hibernate's array functions.** `array_includes` and `array_intersects` / `array_overlaps` render the same
    `@>` and `&&` on PostgreSQL and use the same index, but Hibernate registers them on H2 too, where
    `array_intersects` fails to prepare and `array_includes` is an emulation; MySQL has none, and
    `cb.arrayIncludes` throws there (finding 6). A provider check that looked them up would pass on H2 and reach a
    database error. The `mq_*` functions are one mechanism for every pattern feature, gated before rendering.
- **`fullTextMatch(String configuration)`** is PostgreSQL's full text, a value-form operator: the value is the
  request's text, bound unconverted as a `String` (the `<C, V>` form), and the column is a `String` attribute.
  - **The configuration** is required, a constant of the definition, and rendered as a literal:
    `to_tsvector('english', col)`. It must be a plain, optionally schema-qualified SQL identifier (`english`,
    `public.my_config`); null, blank or anything else is `MQ1315` when the factory is called. It is rendered rather
    than bound because an expression index matches only `to_tsvector('<config>', col)`: a one-argument
    `to_tsvector(col)` (the server's `default_text_search_config`) returns the right rows but never uses the index,
    even with `enable_seqscan = off` (finding 9). The factory is cached per configuration.
  - **The text** goes through `plainto_tsquery` or `websearch_to_tsquery`, chosen by a probe that opens the slice
    building it and recorded there as a D-n; `to_tsquery` is not offered, because it raises on free text such as
    `acme corp`. Either takes request text without a database error.
  - It renders through `mq_ts_match` (`(?1 @@ ?2)`), registered for PostgreSQL's dialect only; whether the
    `to_tsvector` and tsquery calls sit in the pattern or are passed to it is the slice's, since the spike measured
    both shapes rendering and planning alike.
  - Not a field form with `Expr.function` operands: that works in raw Criteria, but every caller would build both
    calls and pass the configuration as a literal by hand.
- **The two checks**, mirroring R-SRC-11's for sources, run when the filter is rendered, before any statement. The
  vendor check runs first, because the vendor is the real reason an operator can't run:
  1. **The vendor.** `VendorProfile#operators(OperatorFeature)`, `@Incubating`, false by default. PostgreSQL answers
     all five true, H2 only `ILIKE`, and MySQL none. False is `MQ4015`, widened to "the target's vendor lacks a source
     or operator feature".
  2. **The provider.** `ProviderSupport#operators(EntityManagerFactory, OperatorFeature)`, `@Incubating`, false by
     default. It takes the factory, as `constants(emf)` and `sequences(emf)` do, because a `ProviderSupport` is a
     stateless ServiceLoader service. Hibernate's answers a pattern-function feature (`REGEX_MATCH`,
     `ARRAY_CONTAINMENT`, `JSONB_CONTAINMENT`, `FULL_TEXT`) by looking its `mq_*` function up in that factory's
     function registry, so it is true exactly where the contributor registered it, whatever a custom profile says
     (finding 5). It answers `ILIKE` by the factory's dialect: true for PostgreSQL's and H2's (subclasses included),
     false elsewhere, because `ilike` is an HQL predicate Hibernate emulates on other dialects rather than a registry
     function. False is `MQ4014`, widened to "the persistence provider can't render operator feature F".
- **How the checks reach the render.** The executor resolves both facts once, as it resolves the provider's other
  per-factory facts, into an operator gate that `RenderOptions.withOperatorGate(...)` carries to every render path:
  queries, counts, writes, child loads and conflict clauses. `core` sees only the gate, never a profile (INV-6,
  INV-7).
  - **No gate** (`RenderOptions.portable()`, or `JoinContext.of(root, cb)`): an operator that requires a feature is
    `MQ4014`. This is the same fail-safe sources have.
  - **`checkPhases`' scratch build** gets a stand-in gate that answers true (D-132's pattern). Without it, every
    query with an `op` and a customizer would fail there.
  - **Hibernate's pass-through rendering** of an unknown function is never reached: a profile answering true on a
    dialect the contributor skipped stops at check 2. Without the gate Hibernate renders `mq_regex_match(...)`
    through and the database refuses it (`SQLGrammarException`, finding 5), so check 2 is the only thing between a
    wrong profile and that error.
- **`PortabilityCheck`** (`rfc/0011`) reaches both checks because it renders the calls it runs. It sees an `op` only
  when the value is present in that run: an empty `Optional` records and renders nothing (R-INS-02), so a definition
  checked with an empty value is not gated. The docs say to give the check a present value, and an AC covers it.
- **User-defined operators** declare no feature and get no gating, as `Expr.function` gets none: their SQL is the
  user's. `requires(OperatorFeature)` lets a user operator opt into a catalogue feature's gate; users can't add
  features, because the enum is the library's vendor knowledge.

### 5. What doesn't change

- `add(...)` stays the escape hatch for a predicate that isn't an operator over a column (D-24).
- `Expr.function` stays the way to call a function.
- `likeIgnoreCase`, `eqIgnoreCase` and every built-in operator keep their rendering, and none is rewritten through
  `FilterOperator`.
- Paging, keyset and export are unaffected: an operator is a `WHERE` or `HAVING` predicate and never an order key.
  `Expr.cases().when(...)` refuses an `OPERATOR` condition with `MQ1505`, as it refuses `CUSTOM`, `EXISTS` and
  sub-selects (R-COL-17). Otherwise a CASE could carry an operator into a select column, a sort key or a keyset key.
- Spring and the processor are unaffected.

### 6. Indexes

The docs page says plainly that an operator is only as fast as the index that matches its form, and that the index
use is the planner's, per plan, not something the operator guarantees. On PostgreSQL 16 (spike finding 3):

- `@>` and `&&` on an array column use `gin (col)`, under a custom and a generic plan alike;
- `fullTextMatch(config)` uses `gin (to_tsvector('<config>', col))` under both plans, only when the index's
  configuration is the operator's;
- `ILIKE '%x%'` uses `gin (col gin_trgm_ops)` and `jsonb @>` uses `gin (col)` under a custom plan, where the planner
  sees the value. Under a generic plan (`plan_cache_mode = force_generic_plan`, the plan a reused server-side
  prepared statement can get) both were a Seq Scan at 20 000 rows. Whether `plan_cache_mode = auto` picks that plan
  once pgjdbc server-prepares the statement was not measured;
- `~` can use `gin (col gin_trgm_ops)` too, as `pg_trgm` documents; the spike did not measure it;
- a `lower(col)` index serves `likeIgnoreCase`, not `ILIKE`.

### 7. Spike plan and results

On Hibernate 6.6.58 and 7.4.11, against H2, PostgreSQL 16 and MySQL 8.0.31, results in `rfc/0017-spike-results.md`:

1. **Binding.** A parameter typed by the attribute or the declared type reaches `ilike` and a pattern function on each
   vendor, H2 included (D-22's reason). Cover:
   - a `String`, an enum (`@Enumerated(STRING)` and `ORDINAL`), a `LocalDate`, a `String[]`, an `Integer[]` and a
     converted value, each inside a pattern function;
   - the statement shows `?`, never the value.
2. **Pattern functions.**
   - `registerPattern` with a boolean return type compiles on both lines.
   - Record whether `cb.isTrue(cb.function(...))` renders as the bare operator or as `(…) = true`, per line.
3. **Index use (PostgreSQL).** `EXPLAIN` uses the index for each form from probe 2:
   - `ILIKE '%x%'` against a trigram GIN index;
   - `@>` and `&&` against a GIN index;
   - `jsonb @>`.

   PostgreSQL's planner probably simplifies `x = true` to `x`; this probe confirms it.
4. **`ilike`.** `HibernateCriteriaBuilder#ilike` takes a bound parameter and an escape character.
   - On PostgreSQL and H2 it matches `%`, `_` and `\` literally.
   - Record MySQL's emulated rendering, for the record only (it is gated).
5. **The two checks.** A contributor that registers `mq_regex_match` only for PostgreSQL's dialect makes Hibernate's
   `ProviderSupport#operators` false on H2 and MySQL. A custom profile answering true there still gets `MQ4014`, never
   an unknown-function error.
7. **The binder.** `applyInferableType` through each line's adapter types a parameter by the column's mapping:
   - inside a pattern function, for `@Enumerated(STRING)`, `@Enumerated(ORDINAL)`, a JPA `@Convert` attribute and an
     array;
   - on MySQL, an enum `STRING` column compared to the bound value never degrades to `'ACTIVE' = 0`.
6. **Arrays.** Record Hibernate's array function names on each line (`array_contains`, `array_overlaps`, or their
   renamed forms), and whether they render `@>` and `&&` on PostgreSQL or a function form that skips the index.

The spike added probe 8 (the binder routes under `hibernate.criteria.value_handling_mode=inline`) and `FT` (full
text). What it changed here:

- **Probe 7 and 8:** `applyInferableType` types nothing by the mapping; the binder is `ANTICIPATED_PATH`, loud on
  failure, one adapter with two line differences, and not `SqmCriteriaNodeBuilder#value` (§2 step 4).
- **Probe 1:** MySQL's two silent shapes for a `STRING` enum bound by ordinal (§2 step 4, AC-FLT-22); the
  no-binder split stands (§2 step 5).
- **Probes 4 to 6:** `ILIKE` answered by dialect on the provider side, the `mq_*` array functions for PostgreSQL's
  dialect only rather than Hibernate's, and check 2 as the only guard against Hibernate's pass-through (§4).
- **Probe 3:** the index list, with the generic-plan caveat and `~` unmeasured (§6); `= true` is never rendered and
  is moot.
- **`FT`:** `fullTextMatch(configuration)` ships behind `FULL_TEXT` (§4, AC-FLT-32).
- **Probe 8:** a renderer must use `right` (§1); the engine's own value paths are Q-28.

### New ids

| Id | Where | What |
|---|---|---|
| R-FLT-20 | `api/12` | `FilterOperator`, `OperatorRenderer`, `Filters.op`, `Having.op`, `MQ1313` |
| R-FLT-21 | `api/12` | An operator's value: prepared, converted, typed by attribute or declared type, bound per statement |
| R-FLT-22 | `api/12` | An operator over NULL, and under `not` |
| R-INS-13 | `api/16` | `Kind.OPERATOR`, `Condition#operator()`, identity equality, `FilterMatchers.op` |
| R-VND-23 | `vendor/40` | `OperatorFeature`, `operators` on `VendorProfile` and `ProviderSupport`, `MQ4015`/`MQ4014` |
| R-VND-24 | `vendor/40` | `OperandBinder`, `ProviderSupport#operands(emf)`, `withOperandBinder`, `MQ1502` widened |
| MQ1313 | `reference/90` | An operator renderer returned `null` or threw |
| MQ1315 | `reference/90` | A `fullTextMatch` configuration that is not a plain SQL identifier |
| AC-FLT-21..27, 32 | `api/12` | below (AC-FLT-28..31 are D-150's and D-154's) |

- **AC-FLT-21:** a user operator and `ilike(mode)` filter on PostgreSQL and H2 on both lines:
  - in value, `Optional` and field form, and inside `or`, `not`, `exists` and a sub-select;
  - an empty `Optional` creates no join;
  - the value is a bind parameter (SQL snapshot).
- **AC-FLT-22:** an operator's value is typed and converted correctly:
  - with Hibernate's binder, on each served line, an enum (`STRING` and `ORDINAL`), a `@Convert` attribute, an array
    and a converted value bind typed by the column's mapping, the statement shows `?` with the default value handling
    and under `hibernate.criteria.value_handling_mode=inline`, and on MySQL a `STRING` enum over a `varchar` column
    and over a native `enum(...)` column each returns exactly its matching rows, never every row or none;
  - the binder refuses a node type its line doesn't expect with `MQ1502` (a test double for the node type), and
    `operands(emf)` is empty outside 6.6.x and 7.4.x;
  - with no binder, an enum or an array value is `MQ1502`, and a `String`, number or `java.time` value binds;
  - a `FilterOperator<C, V>` binds `V` unconverted, and one with `requiringAttribute` over the wrong attribute is
    `MQ1001` (R-COL-14);
  - a field form over two converted columns of different attribute types is `MQ1001`;
  - a `null` through a typed variable is `MQ1301`, and a `Collection` value is `MQ1502`.
- **AC-FLT-23:** a renderer returning `null` and one throwing are `MQ1313` naming the operator and column, before any
  statement; a `ModelQueryException` thrown from a renderer keeps its own code.
- **AC-FLT-24:** the catalogue's `regexMatch`, `arrayContains`, `arrayOverlaps` and `jsonbContains` return the right
  rows on PostgreSQL. On H2 and MySQL each is refused before any statement:
  - `MQ4015` with H2's and MySQL's profiles (the vendor check runs first);
  - `MQ4014` with a custom profile that answers true and Hibernate's provider, which has no `mq_*` function there, and
    for `arrayOverlaps` on H2 never `Unknown data type`;
  - an operator that requires a feature is `MQ4014` with no gate (`RenderOptions.portable()`), and a query with an
    `op` and a customizer passes `checkPhases`;
  - `ilike` is `MQ4015` on MySQL with its profile, and `MQ4014` with a custom profile answering true there (the
    provider answers `ILIKE` by dialect), never the `lower(col) like lower(?)` emulation;
  - `PortabilityCheck` reports each per target when the value is present, and an `op` with an empty `Optional` is not
    reported.
- **AC-FLT-32:** `fullTextMatch(config)` on PostgreSQL, both lines:
  - returns the right rows for request text with spaces and punctuation (`acme corp`, a quote), with no database
    error;
  - the statement renders the configuration as a literal and the text as `?` (SQL snapshot);
  - a null, blank or non-identifier configuration (`english'`, `a b`) is `MQ1315` at the factory call, and two calls
    with one configuration return the same instance;
  - it is `MQ4015` on H2 and MySQL with their profiles, and `MQ4014` with a custom profile answering `FULL_TEXT` true
    there;
  - `PortabilityCheck` reports it per target.
- **AC-FLT-25:** inspection and matching:
  - inspection records `OPERATOR` with the operator, column and unprepared value;
  - `FilterMatchers.op` matches it, and a different operator with the same name doesn't;
  - two queries sharing the constant are equal;
  - `toString` prints no value.
- **AC-FLT-26:** `not(op(...))` and `op(...)` both drop rows whose column is NULL (R-FLT-22); `Having.op` filters
  groups; any `op` in an arbiter's `where` is `MQ1804` naming the operator at `build()`, a value-form `op` in a conflict
  update's `where` is `MQ1804` naming the operator on first execution, and a field-form one runs there; an `op` in
  `Expr.cases().when(...)` is `MQ1505`.
- **AC-FLT-27:** compile failures (P-2, as AC-FLT-15 and AC-FLT-20 have):
  - a value of the wrong type;
  - a field of another model;
  - a `FilterOperator<String, String>` on an `Integer` column.

## Compatibility

Additive. Every new type and member is `@Incubating`: `FilterOperator`, `OperatorRenderer`, `OperatorContext`,
`OperatorFeature` (`ILIKE`, `REGEX_MATCH`, `ARRAY_CONTAINMENT`, `JSONB_CONTAINMENT`, `FULL_TEXT`), `Filters.op`,
`Having.op`, `Condition.Kind.OPERATOR`, `Condition#operator()`, `FilterMatchers.op`, `OperandBinder`,
`ProviderSupport#operators`, `ProviderSupport#operands` and `VendorProfile#operators` (default methods),
`RenderOptions.withOperatorGate` and `withOperandBinder`, and `HibernateOperators` with its factories,
`fullTextMatch(String)` included. The `mq_*` function names are internal to `model-query-hibernate`.

- `Filters` and `Having` are already sealed, so the new methods break no implementation.
- A `switch` over `Condition.Kind` without a default gains a case, as each earlier kind did.
- The messages of `MQ4014`, `MQ4015`, `MQ1502` and `MQ1804` widen; their codes don't change. `MQ1313` and `MQ1315`
  are new.

## Alternatives

- **Do nothing; document `add(...)`.** Works today, but leaves the value unbound by construction, the column untyped,
  no `Optional` form and an opaque inspection record, for good once `Filters` is sealed.
- **A built-in `ilike` rendered per vendor through `VendorProfile`.** It solves one operator, and it contradicts D-22
  and R-VND-10's one rendering everywhere for a uniformity the caller asked for.
- **SQL template strings in `core`** (`"?1 ilike ?2"`). They bypass Criteria, so they can't be typed or bound
  portably (D-22), and `core` would carry SQL text.
- **The renderer receives the raw value** (`render(ctx, left, V value)`). Simpler to write, but the renderer would
  then choose how to bind it, and `cb.literal(value)` would inline it silently (R-FLT-08).
- **Binding through `Expr.value`, or typing by the attribute's class in `core`.** Both type the parameter by a Java
  class. `Expr.value` refuses arrays, enums, `Date` and `Calendar` (R-COL-18), which rules out the catalogue's array
  operators. Typing by the attribute's class alone binds an enum by ordinal inside a function, silently wrong on MySQL
  (§2). Only the provider can type by the mapping.
- **Operators through `Expr.function` only.** Already possible for function forms, but a function can't use the
  operator's index (§Motivation).
- **One vendor check only.** The engine's profile and Hibernate's dialect registration could disagree, and a profile
  answering true would reach an unknown function at the first statement (INV-5). Two checks, as sources have, close
  that.
- **`OperatorFeature` in `jpa`, beside `SourceFeature`.** Then `core`'s `FilterOperator` couldn't name its feature
  (INV-7).

- **Binding through `applyInferableType`, `SqmCriteriaNodeBuilder#value` or a function's
  `ARGUMENT_OR_IMPLIED_RESULT_TYPE`.** The first types nothing by the mapping; the second is `internal`, carries the
  value and inlines it under `value_handling_mode=inline`; the third fixes `STRING` enums but not `@Convert`, and
  only for the catalogue's own functions, never a user's (spike finding 7, §2 step 4).
- **Hibernate's array functions in the catalogue.** Same SQL on PostgreSQL, but registered on H2, where they fail or
  emulate, so the provider check can't gate them (§4).
- **Full text as a field form over `Expr.function` operands.** Works, but every caller builds both calls and passes
  the configuration as a literal by hand (§4).

## Unresolved questions

The design questions of the two reviews and the acceptance spike were decided on 2026-10-21 (D-167), the release and
the method's name on 2026-10-16 (D-148): the binder (§2), the `mq_*` array functions and `ILIKE` by dialect (§4),
full text behind `FULL_TEXT` (§4), the index advice (§6). One stays open in `reference/92`:

1. **Q-28, the engine's own value paths under `value_handling_mode=inline`.** `cb.equal(path, value)`-style binding
   inlines under that setting (spike finding 8). Whether any engine path relies on it rather than on an explicit
   parameter is to be checked outside this RFC.

Left to a slice, not open: the tsquery function for `fullTextMatch` (`plainto_tsquery` or `websearch_to_tsquery`),
picked by the probe that opens its slice.

Later, not in this RFC:

- **Regex on MySQL and H2.** Both have a regex operator, with different syntax and case rules. This RFC gates
  `regexMatch` to PostgreSQL rather than claim equal semantics; a per-vendor regex is a later RFC.
- **Ordering by an operator.** Out of scope: an operator is a predicate. A computed sort key stays `Expr`.
