# RFC 0002 — Database functions in filters

- **Status:** draft
- **Affects:** INV-9 (adds `FunctionCall`); SPEC.md §4; `api/10` §2–3 and R-COL-06; `api/11` (`JoinContext` gains
  a builder and a bind method); `api/12` §1, R-FLT-03, R-FLT-08, R-FLT-09, R-FLT-11, AC-FLT-02; `api/13`
  R-AGG-06 (wording); `api/14` R-WRT-17 and D-17 (bulk writes reuse `Filters`); `reference/90` (new codes, MQ1301
  widened, glossary); `reference/91` (coverage); `reference/92` (new D-19, Q-5 narrowed, risk table). Adds `api/12` §9
  with `R-FLT-15..26`; the acceptance criteria move to §10 and gain `AC-FLT-12..24`. Depends on the resolution of
  issue #7 (R-PROC-07).
- **Discussion:** TBD
- **Target:** 0.2, `@Incubating`. It widens `Filters` parameter types, which is source-compatible for every existing
  call (see Compatibility).

## Summary

A filter can compare the result of a database function instead of a bare column: `unaccent(name) = ?`,
`jsonb_exists(tags, ?)`, `reporting.is_active(status, valid_to)`. A new `FunctionCall<M, C>` is a typed expression
over a model's columns, built with `Fn`. It is accepted on the left side of every `Filters` operator. It keeps the
`Optional`-skip contract, the join rules, `exists` re-rooting and bind-only values. Nothing about it is raw Criteria,
and nothing is inlined into SQL.

## Motivation

`Filters` compares columns only. The one way to call a function is the escape hatch:

```java
.where(f -> f.add(ctx -> {
    CriteriaBuilder cb = …;                     // api/11 gives JoinContext no builder accessor; callers improvise
    return cb.equal(cb.function("unaccent", String.class, QCustomerView.NAME.path(ctx)),
                    cb.function("unaccent", String.class, cb.literal(q)));   // literal: inlined on some providers
}))
```

`add(...)` is a raw `Predicate`, so every guarantee `api/12` makes stops at its boundary:

| With `add(...)` | Failure |
|---|---|
| No `Optional` form | The caller writes `when(q.isPresent(), …)` by hand, and a forgotten one filters on `unaccent(NULL)` and matches nothing. |
| The column path is resolved eagerly inside the lambda | The join is created even when the caller's own condition skips it, against R-FLT-03. |
| No re-rooting | Inside `exists(...)` the path resolves against the outer query, not the sub-query (R-FLT-11), and the result is silently a cross-correlation. |
| `cb.literal(value)` is the obvious way to pass a value | Hibernate inlines some literals into SQL: no bind, a new plan per value, and against R-FLT-08. |
| The function name is any string | Built from request input, it is SQL injection through the one API that has no bind. |
| Join type is whatever the lambda asked for | An INNER join created inside `or(...)` removes rows the other branch should match (R-FLT-10). |

The common cases are all real reporting needs: accent- and case-insensitive search, JSON containment, a stored
function that encodes a business rule, and `coalesce` over a nullable column. Each is one function around a column the
model already has.

## Design

### 1. `Operand`: what a filter compares

All four types below are top-level types in `com.rey.modelquery.core`. The library has no `module-info`, so a sealed
type and its permitted subtypes must share a package (JLS 8.1.6).

```java
public sealed interface FunctionArg<M> permits Operand, FunctionValue {}

public sealed interface Operand<M, C> extends FunctionArg<M> permits ColumnField, FunctionCall {
    Class<C> type();
    String name();                              // used in MQ messages and logs
    Expression<C> expression(JoinContext ctx);
}

public final class FunctionValue implements FunctionArg<Object> { /* a value to bind; read by the engine */ }
public final class FunctionCall<M, C> implements Operand<M, C> { /* immutable; equal by name, type and args */ }
```

`ColumnField` implements both `SelectField` and `Operand`. `AggregateField` implements only `SelectField`, so an
aggregate in `where` stays a compile error, and so does a function over an aggregate (R-COL-06, R-AGG-06).

Every `Filters` method that took `ColumnField<M, ?, C>` now takes `Operand<M, C>`. Nothing else changes:

```java
<C> Filters<M> eq(Operand<M, C> left, C value);
<C> Filters<M> eq(Operand<M, C> left, Optional<? extends C> value);
Filters<M> likeIgnoreCase(Operand<M, String> left, String value, LikeMode mode);
<C> Filters<M> compare(Operand<M, C> left, Op op, Operand<M, C> right);
Filters<M> isTrue(Operand<M, Boolean> condition);                         // new: a boolean function as a predicate
// … every other operator likewise
```

`exists(TableField, …)` is unchanged, because its first argument is a path, not an operand.

To render an operand, `JoinContext` gains `criteriaBuilder()` and `<T> ParameterExpression<T> bind(Class<T>, T)`.
`bind` registers a value for the engine to bind after the query is built. Both are public, so `add(...)` gets the same
tools, and the Motivation's `cb = …` is no longer improvised.

### 2. `Fn` and `FunctionCall`

```java
@Incubating
public final class Fn {

    /** Portable, rendered through the standard CriteriaBuilder method. */
    public static <M> FunctionCall<M, String>  lower(Operand<M, String> s);
    public static <M> FunctionCall<M, String>  upper(Operand<M, String> s);
    public static <M> FunctionCall<M, String>  trim(Operand<M, String> s);
    public static <M> FunctionCall<M, Integer> length(Operand<M, String> s);
    public static <M, C extends Number> FunctionCall<M, C> abs(Operand<M, C> n);
    public static <M, C> FunctionCall<M, C>    coalesce(Operand<M, C> first, C fallback);   // fallback is bound

    /** Any database function, rendered through CriteriaBuilder#function. Not portable by nature. */
    @SafeVarargs
    public static <M, C> FunctionCall<M, C> call(String function, Class<C> type, FunctionArg<? super M>... args);

    /** A bind-parameter argument (R-FLT-16). */
    public static FunctionValue value(Object value);
}
```

`call` takes `FunctionArg<? super M>`, so a `FunctionValue` can sit beside the model's columns, because it is a
`FunctionArg<Object>`. `var v = Fn.value(x)` still passes for the same reason.

A model's `Q` class is not touched. A shared call is a hand-written constant, and a call that takes a request value is
a method:

```java
public static final FunctionCall<CustomerView, String> NAME_FOLDED =
        Fn.call("unaccent", String.class, Fn.lower(QCustomerView.NAME));

static FunctionCall<OrderView, Boolean> hasTag(String tag) {
    return Fn.call("jsonb_exists", Boolean.class, QOrderView.TAGS, Fn.value(tag));
}

.where(f -> f
        .eq(NAME_FOLDED, search.map(Text::fold))                           // Optional<String>: skipped when empty
        .eq(Fn.coalesce(QOrderView.PRIORITY, 0), priority)
        .when(tag != null, g -> g.isTrue(hasTag(tag))))
```

### 3. Rules

These rules go into a new `api/12` §9, and the current §9, Acceptance criteria, becomes §10.

**R-FLT-15** A `FunctionCall` is a definition, like a `ColumnField`: immutable and thread-safe (INV-9), with no
per-query state. It resolves its column arguments through the query's `JoinContext` only when its filter renders. A
skipped filter over a function therefore creates no join (R-FLT-03), and a join first needed inside `or` or `not` is
LEFT (R-FLT-10).

**R-FLT-16** Every `Fn.value(...)` argument, and the `coalesce` fallback, is a bind parameter, never a literal. The
engine binds it through `JoinContext.bind`, for every provider (R-FLT-08). `Fn.value(null)` and a `null` `coalesce`
fallback throw `MQ1301` at construction. A NULL argument is written as a column that holds one, or with `coalesce`. An
enum value is bound by its declaring class.

A function that needs a literal or keyword argument is out of scope for `call`. Examples are the unit of
`date_trunc('month', …)` and the field of `extract(YEAR FROM …)`. The database cannot plan them with a bind parameter,
and Hibernate's function registry types them as `TEMPORAL_UNIT` and refuses a parameter. Such a function goes through
`add(...)`, or waits for a profile-backed `Fn` method (Unresolved question 2).

**R-FLT-17** `Fn.call`'s function name must match `[A-Za-z_][A-Za-z0-9_]*`, optionally schema-qualified with one
`.` (`reporting.is_active`). Anything else throws `MQ1303` at construction, naming the string. The name is rendered
into SQL, so the check is what keeps `call` from being an injection point. The Javadoc says the name must never come
from request input, even when it passes the check.

**R-FLT-18** `type` is the Java type the caller asserts the function returns. The engine cannot check it before
execution, because a user function has no metamodel. It decides how a compared value is bound. A wrong type fails in
the driver, not silently, and the Javadoc says so. `Fn`'s portable functions have fixed types and need no assertion.

`Object`, `Optional`, any `Collection` and any array other than `byte[]` are refused, both as `type` and as the class
of an `Fn.value`, with `MQ1305` at construction. Hibernate expands a bound collection into several parameters, which
would silently change the function's number of arguments.

**R-FLT-19** A provider may reject a call's arguments when the query is built, with an exception that carries no code
and no model. Hibernate does this for a name in its function registry, which it checks against the registry's
signature rather than the database's. The engine wraps that exception in `MQ1306`, naming the model, the function and
the provider's message, and keeps the provider exception as the cause (R-ERR-01, INV-5).

**R-FLT-20** Converted columns follow issue #7: a column's type is the entity attribute's, and a `ColumnConverter`
applies only in the mapper, so it never matters to a function. A JPA `AttributeConverter`, `@Enumerated` or `@Lob`
mapping does matter, because the function sees the stored value:

- **Typed portable functions refuse such a column.** A column argument of any `Fn` portable function, present or
  added later, that carries an explicit `@Convert` or `@Enumerated` throws `MQ1304` at first resolution, naming the
  model, the column and the function. The result would otherwise be computed on a value whose type is not `C`. An
  `@Enumerated` column is typed as its enum, so only `coalesce` can reach it; the string and number functions reject
  it at compile time.
- **`call` allows it.** The caller is writing SQL against stored values on purpose. `jsonb_exists(tags, ?)` over a
  JSON column mapped with an `AttributeConverter` is exactly what `call` is for.

Detection reads the attribute's annotations through `Attribute.getJavaMember()`. JPA 3.1 exposes no converter in the
metamodel, so an `autoApply` converter or one declared in `orm.xml` is not detected. The Javadoc and the user guide
say so.

**R-FLT-21** Inside `exists(path, inner)`, every column argument of every function, at any nesting depth, must sit on
`path` or below it. It is re-rooted into the sub-query like a bare column, and anything else throws `MQ1302` naming the
column and the function (R-FLT-11).

**R-FLT-22** `isTrue(condition)` renders `cb.isTrue(expression)`. It has no `Optional` twin, because it has no value;
use `when(...)` to make it conditional (P-3). The Javadoc covers three cases:

- **What the function must return:** a SQL boolean or 0/1. On MySQL, a truthy integer above 1, such as the position
  `FIND_IN_SET` returns, may be compared as `= 1` and drop rows. Declare such a call with `Integer.class` and use
  `gt(call, 0)`; a `Boolean`-typed call does not accept `0`.
- **`not(isTrue(c))`:** plain SQL `NOT` (R-FLT-05), which excludes rows where `c` is NULL.
- **`ne(c, true)`:** includes those rows (R-FLT-23).

**R-FLT-23** `ne` and `notIn` over a `FunctionCall` include rows where the call returns NULL, exactly as over a
nullable column (R-FLT-04). A function's nullability is unknown, so it is treated as nullable. The exception is
`coalesce` with its non-null fallback, which never returns NULL and renders no `IS NULL` branch.

**R-FLT-24** `in`, `notIn` and `like` over a `FunctionCall` follow R-FLT-06 and R-FLT-09: values are escaped and split
in the same way. An operand can be rendered more than once: once per `IN` chunk, and twice for `ne`. Each rendering
binds its `Fn.value` arguments again, so R-FLT-09's chunk sizing counts `chunks × the operand's binds` against
`maxBindParameters()`.

**R-FLT-25** A function is evaluated by each statement that renders it. A volatile function (`now()`,
`current_date`, `random()`) inside a filter therefore gives each export page, each primary-key-first step and each
bulk-write chunk (R-WRT-17) a different cut-off. Rows can then move into or out of the filter between statements, and
be skipped or visited twice, which breaks INV-4 and D-17. The Javadoc of `call` and the user guide say to bind the
instant once instead: `Fn.value(clock.instant())`, or a plain `lt(CREATED_AT, now)`.

**R-FLT-26** A `FunctionCall` is filter-only in this RFC. It is not a `SelectField`, so it cannot be selected, ordered
or read from a `Row` (see Unresolved questions). `Filters` stays `WHERE` only (R-FLT-14): a function over an aggregate
is `Agg.of` in `having`.

### 4. Portability

`Fn`'s portable functions render the same on every Tier-1 vendor and are covered by the TCK. `Fn.call` is exactly as
portable as the function it names. That is the caller's choice about their own database, like `Agg.of` and
`setExpression`, so it does not break INV-6: the library itself still names no vendor. The user guide lists, per
Tier-1 vendor, the functions the TCK exercises through `call` (for example, `unaccent` needs a PostgreSQL extension).

A function around a column does not use a plain index on that column. The user guide says so and shows a functional
index for each Tier-1 vendor. The same note already exists for `likeIgnoreCase` (R-FLT-07).

With Hibernate, a name registered in the dialect's function registry is rendered and argument-checked by the dialect
(R-FLT-19). Any other name is passed through as `name(args)`. Other providers pass every name through. SQL snapshots
are pinned for Hibernate.

### 5. Changed and new ids

- **INV-9:** the list of immutable types gains `FunctionCall`.
- **SPEC.md §4:** the Filters row gains "database functions over columns (`Fn`)". Its Future items stay: JSON *path*
  predicates as a portable API, and computed columns (`Columns` row).
- **`api/10` §2–3 and R-COL-06:** the `ColumnField` declaration implements `Operand`. R-COL-06 reads "Everything that
  builds a `WHERE` predicate accepts an `Operand` — a `ColumnField` or a `FunctionCall` —", and aggregates stay out
  of `where` at compile time.
- **`api/11`:** `JoinContext` gains `criteriaBuilder()` and `bind(...)` (§1).
- **`api/12`:**
  - §1 signatures take `Operand<M, C>`, and `isTrue` is added.
  - R-FLT-03, R-FLT-08 and R-FLT-11 each gain "or a function's argument".
  - R-FLT-09 gains the bind count of R-FLT-24.
  - AC-FLT-02 names "the column or function".
- **`api/13` R-AGG-06:** the "because `Filters` takes `ColumnField`" reasoning becomes "`Operand`".
- **`api/14`:** a bulk write's filter may use functions. R-WRT-17 and D-17 cite R-FLT-25.
- **`reference/90`:**
  - new codes (below);
  - MQ1301 widened to "a filter value or function argument is `null`";
  - glossary entries: *operand* (a `ColumnField` or `FunctionCall`, what a filter compares) and *function call* (a
    typed database function over columns).
- **`reference/91`:** R-FLT-15..26.
- **`reference/92`:**
  - Q-5 is narrowed to selecting and ordering by a function.
  - The risk table's list of escape hatches gains `Fn.call`.
  - New **D-19**: "Functions in filters are typed `FunctionCall` operands built with `Fn`. Values are always bound;
    function names are validated identifiers; typed portable functions refuse detectable converted columns; functions
    that need literal arguments go through `add(...)`." It traces to P-2, P-3, INV-5, R-FLT-08 and issue #7.

**New codes.** Added to `reference/90` before the code that raises them (R-ERR-02).

| Code | Meaning |
|---|---|
| `MQ1303` | `Fn.call` was given a function name that is not an identifier, optionally schema-qualified |
| `MQ1304` | A typed portable function's column argument carries an `@Convert` or `@Enumerated` mapping |
| `MQ1305` | A function's declared type, or an `Fn.value`, is `Object`, `Optional`, a collection or a non-`byte[]` array |
| `MQ1306` | The persistence provider rejected a function call's arguments |

**Acceptance criteria.** `AC-FLT-12..24`, run on every Tier-1 vendor through the TCK unless marked.

1. Each `Fn` portable function, used with `eq`, returns the same rows as the equivalent hand-written query (R-FLT-15).
2. `eq(call, Optional.empty())` over a joined column renders no join and no function (R-FLT-03, R-FLT-15).
3. A function over a joined column inside an `or` branch keeps rows with no joined row (R-FLT-10, R-FLT-15).
4. The SQL snapshot of a `call` with an `Fn.value` argument has a bind marker and no literal. `Fn.value(null)` and a
   `null` `coalesce` fallback throw `MQ1301` (R-FLT-16).
5. `Fn.call("x); drop table t; --", …)` and `Fn.call("a.b.c", …)` throw `MQ1303`, and `Fn.call("reporting.fn", …)`
   does not (R-FLT-17).
6. `Fn.call(…, Object.class, …)` and `Fn.value(List.of(1))` throw `MQ1305`, and `Fn.value(new byte[0])` does not
   (R-FLT-18).
7. On Hibernate, `call("abs", Integer.class, col, Fn.value(1))`, which every Tier-1 dialect registers with one
   argument, throws `MQ1306` naming the model and the function, with Hibernate's exception as the cause. On PostgreSQL
   and H2, whose dialects register `date_trunc`, `call("date_trunc", …, Fn.value("month"), col)` does too
   (R-FLT-16, R-FLT-19).
8. `Fn.lower` over an `@Convert` `String` column and `Fn.coalesce` over an `@Enumerated(STRING)` column throw
   `MQ1304`, and `Fn.call` over the same columns does not (R-FLT-20).
9. Inside `exists(ITEMS_TABLE, …)`, a function over an item column is re-rooted and a function over an order column
   throws `MQ1302` (R-FLT-21).
10. `isTrue` filters correctly over a PostgreSQL boolean function and a MySQL function returning 0/1 (R-FLT-22):
    - the rendered SQL for a MySQL function returning 3 is pinned in the snapshot, and the Javadoc's `gt(call, 0)`
      form, over the call declared with `Integer.class`, returns that row;
    - `not(isTrue(…))` excludes rows where the function is NULL, and `ne(call, true)` includes them.
11. `ne(call, v)` includes rows where the call returns NULL, and `ne(coalesce(col, 0), v)` renders no `IS NULL` branch
    (R-FLT-23).
12. `in(call, values)` with a function carrying one bound argument, over a list that needs splitting, stays within
    `maxBindParameters()` and returns the same rows as an unsplit list (R-FLT-24).
13. (compile-testing) An `AggregateField` passed to `Fn.call` or `Fn.lower` does not compile, and neither does a
    `FunctionCall` passed to `orderBy` or `ColumnSet.of` (R-COL-06, R-FLT-26).

The volatile-function guidance (R-FLT-25) is a documentation rule, checked in review, with no test.

## Compatibility

Source-compatible. `Operand<M, C>` (Design §1) is a supertype of `ColumnField<M, ?, C>`, so every existing call
still compiles and resolves to the same method. `C` is invariant in `Operand` and
was already fixed by the column, so inference is unchanged. `ColumnField` gains a supertype, which is additive.

Binary compatibility is not a concern, because no `Filters` signature has been released: 0.1 is not yet published.
If this lands after 0.1 is, it is a binary break of `Filters`, which `@Incubating` covers and the release notes list.

Queries that use no function render the same SQL. Their snapshots do not change.

## Alternatives

- **Keep `add(...)` and document the recipe.** The table in Motivation is what that costs, at every call site.
  Rejected.
- **A second family of overloads taking `FunctionCall`.** It doubles `Filters` (about 50 methods) and every future
  operator. A shared supertype costs one type. Rejected.
- **A computed `ColumnField`**, a column whose path is an expression. `ColumnField` would then be selectable, sortable,
  keyset-able and mappable while its type and nullability are only asserted. That is Q-5's scope creep and breaks the
  guarantee that a column is an entity attribute (INV-3). Rejected for filters; selection is left open.
- **Strings (`Filters.sql("unaccent(?) = ?", …)`).** A SQL builder, which P-5 rules out, with no join tracking and no
  re-rooting. Rejected.
- **Literal arguments (`Fn.literal("month")`).** They would make `date_trunc` work, but they are the inlining R-FLT-08
  forbids, with a value the engine cannot validate for every type. Rejected; keyword arguments belong to
  profile-backed functions.
- **Per-vendor function names in the call** (`Fn.call(Map.of(POSTGRESQL, "…", MYSQL, "…"))`). It puts vendor names in
  user query definitions, and a call that is not portable should look that way. A multi-database application can
  choose the call from its own configuration. Rejected.
- **Do nothing.** Every adopter writes the `add(...)` recipe and meets its failures.

## Unresolved questions

1. **Selecting and ordering by a function** (the rest of Q-5). Making `FunctionCall` a `SelectField` would let a
   report select `date_trunc('month', created_at)` and group by it. It raises questions this RFC avoids: keyset over a
   function, the processor generating a field for it, and grouping by an expression that is not an attribute. A
   follow-up RFC decides.
2. **More portable functions.** `substring`, `concat` and `round` are candidates. Date truncation and date parts take
   a keyword argument (R-FLT-16), and JPA 3.1 has no portable form for them. They need a `VendorProfile` method (INV-6)
   and a typed unit enum (`Fn.dateTrunc(Unit.MONTH, col)`). They would be added one at a time, on request, each with a
   TCK case.
3. **A processor hook.** `@FilterColumn` could take a function (`@FilterColumn(name = "NAME_FOLDED", path = "name",
   function = "unaccent")`), so the constant is generated. It is cheap to add later, and is left out to keep this RFC
   to `core`.
