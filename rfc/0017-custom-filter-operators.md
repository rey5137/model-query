# RFC 0017 — Custom filter operators

- **Status:** draft (revised after two `architect-review` passes, each "accept with changes", all folded in)
- **Affects:**
  - **`api/12`:** new R-FLT-20 (`FilterOperator`, `Filters.op`, `Having.op`, `MQ1313`), R-FLT-21 (how an operator's
    value is prepared, converted, typed and bound) and R-FLT-22 (NULLs and negation), AC-FLT-21 to AC-FLT-27.
    R-FLT-08 covers the operand the engine binds; R-FLT-13's skip rule covers the `Optional` form; AC-FLT-01's "every
    operator on every Tier-1 vendor" excludes gated operators. The escape hatch (`add`, D-24) is unchanged.
  - **`api/10`:** R-COL-18's binding bans are lifted for an operator's value only, when the provider's `OperandBinder`
    types it by the column's mapping (R-FLT-21).
  - **`api/16`:** new `Condition.Kind.OPERATOR` recording the operator, the column and the value or right operand
    (R-INS-13); R-INS-04 amended (operators compare by identity); R-INS-07 gains `FilterMatchers.op(...)`.
  - **`vendor/40`:** new R-VND-23: `OperatorFeature` (a `core` enum), `VendorProfile#operators(OperatorFeature)` and
    `ProviderSupport#operators(EntityManagerFactory, OperatorFeature)`, the two checks behind the shipped catalogue
    (§4); new R-VND-24: `ProviderSupport#operands(EntityManagerFactory)`, an `OperandBinder` that types an operator's
    parameter by the column's mapping (§2), in the shape of `ConstantRenderer` (R-VND-16).
  - **`api/10`:** R-COL-17's CASE conditions refuse `OPERATOR` (`MQ1505`), as they refuse `CUSTOM` (§5).
  - **`model-query-hibernate`:** a catalogue, `HibernateOperators` (§4), and a `FunctionContributor` that registers the
    pattern functions it renders through.
  - **`reference/90`:** new `MQ1313` (an operator renderer returned `null` or threw); `MQ4014` and `MQ4015` widened to
    an operator feature the provider or the target lacks; `MQ1804`'s message names an operator in a conflict update's
    `where` (§2).
  - **`reference/92`:** D-148 (release and name, decided before acceptance); a D-n at acceptance.
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
regex match, array and `jsonb` containment). Its reach is two facts, the provider's and the vendor's, so a
PostgreSQL-only operator on an H2 target is refused before any statement and reported by `PortabilityCheck`.

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
  operand can't be inlined by mistake. A renderer that captures some other value and passes it to `cb.literal` still
  inlines it; R-FLT-08 is guaranteed for the operand, and the docs say not to capture values.
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
     `cb.function(...)` Hibernate then binds an enum by ordinal, and on MySQL `status_col = 0` is true for every
     non-numeric string, so rows come back silently wrong (INV-5). So the parameter comes from a provider hook,
     R-VND-24, in the shape of `ConstantRenderer`:

     ```java
     @Incubating
     public interface OperandBinder {   // core
         <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type);
     }
     ```

     `ProviderSupport#operands(EntityManagerFactory)` supplies it, resolved once per executor and carried by
     `RenderOptions.withOperandBinder(...)`. `model-query-hibernate` implements it with
     `SqmExpression#applyInferableType`, whose argument type differs by line (`SqmExpressible` on 6.6,
     `SqmBindableType` on 7.4), so it has one adapter per line. For the `<C, C>` form `typedBy` is the column's path;
     for the `<C, V>` form it is absent and the declared `valueType` types the parameter.
  5. With the binder, the value may be an enum, an array or a converted value, which `Expr.value` refuses
     (R-COL-18). Without one (no `ProviderSupport`, or `RenderOptions.portable()`), only a value whose binding doesn't
     depend on the mapping binds: `String`, a number, `Boolean`, a `java.time` value or a `UUID`. Anything else is
     `MQ1502`, never bound by its class. `Optional`, `Collection` and `Map` values are always `MQ1502`: a
     `Collection` operand is an `IN`, and a `Map` has no portable binding (a `jsonb` value is passed as its text).
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

- **`ilike(LikeMode)`** prepares the value as `like` does (R-FLT-06: escaped, wrapped for `CONTAINS`, `STARTS_WITH`
  and `ENDS_WITH`), renders the escape character, and requires a `String` attribute. It goes through
  `HibernateCriteriaBuilder#ilike`.
  - PostgreSQL and H2 have `ILIKE` natively. MySQL has none, and Hibernate would emulate it silently, which R-VND-10
    forbids. So `ILIKE` is a feature, false on MySQL.
  - It doesn't replace `likeIgnoreCase`, whose one rendering everywhere D-22 and R-VND-10 keep.
- **Pattern functions.** The other three render through boolean-valued pattern functions named `mq_*`
  (`mq_regex_match` as `(?1 ~ ?2)`), registered by a `FunctionContributor` in `model-query-hibernate`
  (`META-INF/services/org.hibernate.boot.model.FunctionContributor`) for the dialects that have the operator. The
  renderer calls `cb.isTrue(cb.function("mq_…", Boolean.class, left, right))`. Where Hibernate's own array functions
  render `@>` and `&&` on both lines, the catalogue uses them instead (spike probe 6).
- **The two checks**, mirroring R-SRC-11's for sources, run when the filter is rendered, before any statement. The
  vendor check runs first, because the vendor is the real reason an operator can't run:
  1. **The vendor.** `VendorProfile#operators(OperatorFeature)`, `@Incubating`, false by default. PostgreSQL answers
     all four true, H2 only `ILIKE`, and MySQL none. False is `MQ4015`, widened to "the target's vendor lacks a source
     or operator feature".
  2. **The provider.** `ProviderSupport#operators(EntityManagerFactory, OperatorFeature)`, `@Incubating`, false by
     default. It takes the factory, as `constants(emf)` and `sequences(emf)` do, because a `ProviderSupport` is a
     stateless ServiceLoader service. Hibernate's answers a pattern-function feature by looking the `mq_*` function up
     in that factory's function registry, so it is true exactly where the contributor registered it, whatever a
     custom profile says. It answers `ILIKE` true always, because `ilike` is an HQL predicate rather than a registry
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
    dialect the contributor skipped stops at check 2.
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

The docs page says plainly that an operator is only as fast as the index that matches its form:

- `ILIKE '%x%'` and `~` need `gin (col gin_trgm_ops)`;
- `@>` and `&&` need a GIN index on the column;
- a `lower(col)` index serves `likeIgnoreCase`, not `ILIKE`.

Spike probe 3 records the plans.

### 7. Spike plan

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

### New ids

| Id | Where | What |
|---|---|---|
| R-FLT-20 | `api/12` | `FilterOperator`, `OperatorRenderer`, `Filters.op`, `Having.op`, `MQ1313` |
| R-FLT-21 | `api/12` | An operator's value: prepared, converted, typed by attribute or declared type, bound per statement |
| R-FLT-22 | `api/12` | An operator over NULL, and under `not` |
| R-INS-13 | `api/16` | `Kind.OPERATOR`, `Condition#operator()`, identity equality, `FilterMatchers.op` |
| R-VND-23 | `vendor/40` | `OperatorFeature`, `VendorProfile#operators`, `ProviderSupport#operators`, `MQ4015`/`MQ4014` widened |
| R-VND-24 | `vendor/40` | `OperandBinder`, `ProviderSupport#operands(emf)`, `RenderOptions.withOperandBinder` |
| MQ1313 | `reference/90` | An operator renderer returned `null` or threw |
| AC-FLT-21..27 | `api/12` | below |

- **AC-FLT-21:** a user operator and `ilike(mode)` filter on PostgreSQL and H2 on both lines:
  - in value, `Optional` and field form, and inside `or`, `not`, `exists` and a sub-select;
  - an empty `Optional` creates no join;
  - the value is a bind parameter (SQL snapshot).
- **AC-FLT-22:** an operator's value is typed and converted correctly:
  - with Hibernate's binder, an enum (`STRING` and `ORDINAL`), a `@Convert` attribute, an array and a converted value
    bind typed by the column's mapping, and a MySQL enum column never matches through `= 0`;
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
  - `MQ4014` with a custom profile that answers true and Hibernate's provider, which has no `mq_*` function there;
  - an operator that requires a feature is `MQ4014` with no gate (`RenderOptions.portable()`), and a query with an
    `op` and a customizer passes `checkPhases`;
  - `ilike` is `MQ4015` on MySQL;
  - `PortabilityCheck` reports each per target when the value is present, and an `op` with an empty `Optional` is not
    reported.
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
`OperatorFeature`, `Filters.op`, `Having.op`, `Condition.Kind.OPERATOR`, `Condition#operator()`,
`OperandBinder`, `ProviderSupport#operators`, `ProviderSupport#operands` and `VendorProfile#operators` (default
methods), `RenderOptions.withOperatorGate` and `withOperandBinder`, and `HibernateOperators`.

- `Filters` and `Having` are already sealed, so the new methods break no implementation.
- A `switch` over `Condition.Kind` without a default gains a case, as each earlier kind did.
- The messages of `MQ4014`, `MQ4015` and `MQ1804` widen; their codes don't change.

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

## Unresolved questions

- **Release (decided 2026-10-16, D-148):** M29, in 0.10.0 after M28. The freeze moves to M30 and stays last. Every
  new public type and member is `@Incubating`, so the M30.1 freeze review decides each one.
- **The method's name (decided 2026-10-16, D-148):** `op`, which doesn't clash with `apply(UnaryOperator)`; `test`,
  `matches` and `where` were rejected.
- **Full text (`@@`).** `to_tsvector(...) @@ to_tsquery(...)` needs expressions on both sides and a language argument.
  It fits the field form with `Expr.function` operands; whether the catalogue ships it is left to the spike.
- **Regex on MySQL and H2.** Both have a regex operator, with different syntax and case rules. This RFC gates
  `regexMatch` to PostgreSQL rather than claim equal semantics; a per-vendor regex is a later RFC.
- **Ordering by an operator.** Out of scope: an operator is a predicate. A computed sort key stays `Expr`.
- **Acceptance D-n**, to record:
  - an operator's value binds typed by the column's mapping through the provider's `OperandBinder`, or by the
    declared value type; without a binder only mapping-independent types bind, others are `MQ1502`;
  - `OperatorFeature` is a `core` enum;
  - the gate is two checks at render time, the vendor's (`MQ4015`) and then the provider's (`MQ4014`), carried by
    `RenderOptions`; no gate is `MQ4014`; `checkPhases` uses a stand-in;
  - an `OPERATOR` condition is refused inside `Expr.cases()` (`MQ1505`);
  - operators compare by identity (R-INS-04 amended).
