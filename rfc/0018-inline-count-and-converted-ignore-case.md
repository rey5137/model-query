# RFC 0018 — Inline count, case-insensitive filters over converted attributes, and values under inline value handling

- **Status:** draft. M30, 0.10.0 (D-176); the freeze moves to M31.
- **Affects:**
  - **`core`:** `CountMode.INLINE_COUNT` (Part A); `OperandBinder#converts(Expression)`, a default method (Part B);
    one `JoinContext` binding helper every engine value path goes through under inline value handling (Part C).
  - **`engine/20`:** R-EXE-02's table gains `INLINE_COUNT`; new R-EXE-15 (the inline total, its fallbacks); new
    AC-EXE-18 to AC-EXE-21.
  - **`api/12`:** R-FLT-07 amended (`likeIgnoreCase` and `eqIgnoreCase` over a converted attribute convert the value,
    then fold it in SQL); R-FLT-06's `@Convert` sentence amended to match; new AC-FLT-33 and AC-FLT-34. `Having`'s
    `likeIgnoreCase` follows `Filters`'.
  - **`api/12`, Part C:** R-FLT-08 amended (it holds under `hibernate.criteria.value_handling_mode=inline`); new
    AC-FLT-35 and AC-FLT-36.
  - **`vendor/40`:** R-VND-24 amended (`converts`); new R-VND-27: `ProviderSupport#inlineCounts(EntityManagerFactory)`,
    an `InlineCountRenderer` in the shape of `ConstantRenderer` (R-VND-16).
  - **`vendor/40`, Part C:** new R-VND-28: `ProviderSupport#inlinesValues(EntityManagerFactory)`.
  - **`model-query-hibernate`:** an `InlineCountRenderer` through a registered `mq_count_over` function, answered by
    the dialect's window-function support; `converts` on `HibernateOperandBinder`.
  - **`reference/92`:** D-176 (release, decided before acceptance), the acceptance `D-n`; Q-28, Q-29 and Q-30 resolved.
  - **`docs/site`:** the paging page's count modes, the filters page's case-insensitive section (both tabs,
    CC-DOC-01).
- **Discussion:** to open
- **Target:** 0.10.0 (M30, D-176), `@Incubating`. Parts A and C are additive; Part B changes the rows of a shipped filter
  (§Compatibility).

## Summary

Three independent parts, accepted or rejected separately, shipped together before the 1.0 freeze.

**Part A (Q-29).** `CountMode.INLINE_COUNT` takes a page's total from the page statement itself, as
`count(*) over()`, instead of a separate count statement. One round trip, and the total and the rows come from one
snapshot without a transaction. The total stays exact in every shape: where the window can't give `COUNT`'s answer,
the executor runs `COUNT`'s statement instead. Opt-in, never the default.

**Part B (Q-30).** `likeIgnoreCase` and `eqIgnoreCase` over a JPA `@Convert` attribute today bind a value the
converter never sees, so they return no rows and no error (INV-5). With `model-query-hibernate`, they will bind the
value typed by the column's mapping, so Hibernate converts it, and fold both sides in SQL:
`lower(col) = lower(?)`. They then match as `like` and `ilike` already do over a converted column (D-171). Columns
without a converter render as today.

**Part C (Q-28).** Under `hibernate.criteria.value_handling_mode=inline`, Hibernate writes a value the engine passed
as `cb.equal(path, value)` into the SQL text, which breaks R-FLT-08 ("every value is a bind parameter") on most of the
engine's value paths. When the provider reports that setting, every engine value path binds through the rule the
operator path already follows (R-FLT-21): the binder's parameter, typed by the column, or a parameter typed by class for
a value whose binding doesn't depend on the mapping, or `MQ1502` before any statement. Under the default setting the
rendering is unchanged.

## Motivation

### Part A

`COUNT` runs two statements per page. Outside a transaction they read two snapshots, so a row inserted between them
gives a total that disagrees with the page; `warnOfContradictedTotal` logs that but can't prevent it. Inside a
transaction, the second statement still costs a round trip. Blaze-Persistence's answer is `COUNT(*) OVER()` in the
page query. JPA Criteria has no window functions, so the engine can't render it portably; Hibernate 6.6 and 7.4 can.

The price is real and is why this is opt-in: the window counts the whole result before `LIMIT`, so the database
can't stop early at the top N rows. On a large, unselective result that is often slower than a separate count. The
spike (§A.6) measures where the line falls.

### Part B

`ConvertedLikeTest` pins it: over a converter adding `SKU:`, `likeIgnoreCase(EXACT, "A12")` binds `a12`, the database
compares it with `lower('SKU:a12')`, and no row matches. `STARTS_WITH` misses the same way; `CONTAINS` matches only
because the stored prefix doesn't matter to `%a12%`. `eqIgnoreCase` renders `lower(col) = ?` the same way and very
likely fails the same way; the spike confirms it first (§B.4).

The parameter meets `lower(col)`, not `col`, so Hibernate has no mapping to type it by and never runs the converter.
Converting it in Java wouldn't help: the engine can't see a `@Convert` converter (`core` reads the JPA metamodel,
which doesn't expose it, INV-7), and lowering before conversion is wrong for any converter that cares about case.

### Part C

RFC 0017's spike found it (finding 8): `cb.equal(path, value)`-style calls, where Hibernate makes the parameter
itself, inline the value under `value_handling_mode=inline`; an explicit `ParameterExpression` never does. M29's
operators bind explicitly (D-167, D-170), and AC-FLT-22 runs under the setting. The rest of the engine passes raw
values: filters (`ConditionGroup`: `eq`/`ne`, ranges, `like`, the ignore-case pair), keyset cursors (`Keyset`), primary
keys (`PrimaryKey`, so `one(q, key)`, `byKeys` and primary-key-first pages), the optimistic-lock version check
(`ModelUpdate`), and very likely write assignments, conflict clauses and source parameters. Under the setting each of
those statements carries its values as literals: R-FLT-08 is broken, every distinct value is a new statement for the
plan cache, and the bind limit (R-FLT-09) no longer counts what it was written to count. An application may set it
for its own reasons (Hibernate's docs suggest it for some plan-cache trade-offs), and nothing tells it the engine's
promise has gone.

## Design

### Part A — `CountMode.INLINE_COUNT`

#### A.1 API

```java
public enum CountMode {
    COUNT,
    NO_COUNT,
    ONLY_COUNT,
    /** COUNT's exact total, read from the page statement where the provider can render it (R-EXE-15). */
    @Incubating INLINE_COUNT
}
```

```java
Slice<OrderRow> page = executor.page(q, PageSpec.of(3, 50), CountMode.INLINE_COUNT);
page.total(); // exact, as under COUNT
```

`INLINE_COUNT` is a request about *how* to count, not *whether*: a `Slice` under it reports the same total and
`hasNext` as under `COUNT`, always (R-EXE-02's guarantees unchanged). Spring Data's `ModelPage` reports it as
`COUNT`'s total; the repository default stays `COUNT`.

#### A.2 Rendering (R-VND-27)

```java
// model-query-jpa, jpa.spi
@Incubating
public interface InlineCountRenderer {
    /** count(*) over () as an expression the page statement selects beside its columns. */
    Expression<Long> total(CriteriaBuilder cb);
}

// ProviderSupport
default Optional<InlineCountRenderer> inlineCounts(EntityManagerFactory emf) { return Optional.empty(); }
```

The executor resolves it once, as it does the operand binder, and passes it to the page's statement through
`RenderOptions`. `model-query-hibernate` registers `mq_count_over` as the pattern `count(*) over ()` in its
`FunctionContributor` for every dialect whose `supportsWindowFunctions()` is true, and answers `inlineCounts(emf)`
from that registration, as `operators(emf, f)` does (D-171, D-175). The window is a provider fact: Hibernate's
dialect already knows the database version, and nothing is refused, so no `VendorProfile` fact and no
`PortabilityCheck` report are needed (INV-6 holds: no vendor branch at a call site).

#### A.3 Execution (R-EXE-15)

Under `INLINE_COUNT`, the page statement selects the total as one more tuple element, read off the first row and
never seen by the model:

| Shape | Behaviour |
|---|---|
| Rows returned | total = the window's value; `hasNext = offset + rows < total` |
| No row, offset 0 | total = 0 |
| No row, offset > 0 (past the end) | `COUNT`'s statement runs for the total (one extra statement, R-EXE-02 unchanged) |
| Grouped query | the window runs after `GROUP BY` and `HAVING`, so it counts groups (R-EXE-03's meaning, one statement) |
| Primary-key-first page | the window goes on step 1, the key query; step 2 is unchanged |

It falls back to `COUNT`'s two statements, logged once per `ModelQuery` at `DEBUG` with the reason, where the window
would not give `COUNT`'s total:

1. no `InlineCountRenderer` (no `model-query-hibernate`, or a dialect without window functions);
2. a count `COUNT` itself takes client-side (R-EXE-03: no grouped count support, a source carrying checks, a refused
   `GROUPED_COUNT` treat site);
3. a predicate's to-many join that `COUNT` counts as `count(distinct root)` (R-EXE-04): the window would count the
   joined rows before `DISTINCT`;
4. a statement the spike finds the window miscounts (§A.6, probe 4), listed in R-EXE-15 by the slice that finds it.

Keyset pages carry no total (D-105) and take no `CountMode`, so they are untouched. `page` already refuses row locks
(R-EXE-14), so `FOR UPDATE` with a window never arises. `ONLY_COUNT`, `count(q)` and exports are unchanged.

#### A.4 Why fall back rather than refuse

A refusal would make `INLINE_COUNT` unusable as a repository-wide setting: every to-many filter would throw. The
fallback costs only the statement the caller would have run under `COUNT`, the total stays exact (INV-5), and the
`DEBUG` line says why. An application that wants to know can inspect the query (`api/16`).

#### A.5 New ids

R-EXE-15; R-VND-27; AC-EXE-18 (the inline total equals `COUNT`'s on flat, grouped, `HAVING` and primary-key-first
pages, both lines, Tier 1, one statement each), AC-EXE-19 (past the end: `COUNT` runs, total exact), AC-EXE-20 (each
fallback of §A.3 runs two statements and logs its reason once), AC-EXE-21 (no `model-query-hibernate`: `COUNT`'s
statements, same `Slice`).

#### A.6 Spike (M30.0)

On both Hibernate lines and Tier 1:

1. `mq_count_over` as a pattern function against `HibernateCriteriaBuilder`'s window API: which renders
   `count(*) over ()` with `setFirstResult` and `setMaxResults` on both lines, and its SQL;
2. grouped with `HAVING`: the window counts groups;
3. a to-many predicate join with `DISTINCT`: confirm the miscount that fallback 3 avoids;
4. sources (CTEs), set-operation sources, recursive sources, treated roots and correlated columns: the window's total
   against `COUNT`'s;
5. performance: PostgreSQL and MySQL, 1M rows, first, middle and deep pages, selective and unselective filters, flat
   and grouped, `INLINE_COUNT` against `COUNT`'s two statements in one transaction; the result decides the docs'
   advice on when to choose it, or, if it never wins, that Part A is withdrawn.

### Part B — case-insensitive filters over a converted attribute

#### B.1 The binder says what it converts (R-VND-24)

```java
public interface OperandBinder {
    <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type);
    default boolean typesBy(Expression<?> typedBy) { return true; }
    /** Whether a value typed by typedBy goes through a converter of the mapping (JPA @Convert) when bound. */
    @Incubating default boolean converts(Expression<?> typedBy) { return false; }
}
```

`HibernateOperandBinder` answers from the node type it already resolves (D-170): true when its `JdbcMapping` carries a
JPA `AttributeConverter` (a `@Convert` or an auto-applied converter). An enum's built-in conversion isn't a JPA
converter and answers false; `likeIgnoreCase` takes `String` columns only (R-FLT-07). `OperandBinder` is new in 0.10.0
and unreleased, so the default method is free.

#### B.2 Rendering (R-FLT-07 amended)

| Column | `eqIgnoreCase(col, v)` | `likeIgnoreCase(col, v, mode)` |
|---|---|---|
| no binder, or `converts` false | `lower(col) = ?`, `v` lowered in Java (today) | `lower(col) like ?`, pattern lowered in Java (today) |
| `converts` true | `lower(col) = lower(?)`, `?` typed by the binder, bound with `v` | `lower(col) like lower(?) escape '\'`, `?` typed by the binder, bound with `mode`'s pattern of `v` |

On a converted column, Hibernate runs the converter on the bound value, then the database folds both sides with the
same `lower`. A model-query `ColumnConverter` runs first, as R-FLT-06 and R-COL-14 order it. The pattern's wildcards
and escapes go through the converter, exactly as for `like` and `ilike` (D-171), so `likeIgnoreCase` then matches a
model value through the same converters `like` does: those that turn a pattern into the stored form's pattern, such as
one adding a prefix. Over the `SKU:` converter, `EXACT "A12"` binds `SKU:A12`, both sides fold to `sku:a12`, and the
row matches.

SQL for columns without a converter is unchanged, so no existing snapshot moves. `Having.likeIgnoreCase` over an
aggregate follows the same table, with `converts` asked of the aggregate expression (§B.4, probe 3).

#### B.3 New ids

AC-FLT-33 (`eqIgnoreCase` and `likeIgnoreCase` in every `LikeMode` over the `SKU:` converter match the rows `like`
matches with the same case, both lines, Tier 1; `ConvertedLikeTest`'s pinned misses become matches), AC-FLT-34
(without `model-query-hibernate`, and on a column without a converter, the SQL is today's).

#### B.4 Spike (M30.0)

1. Confirm `eqIgnoreCase` misses today over the `SKU:` converter (Q-30's open half).
2. `cb.lower(param)` with the binder's parameter on both lines: Hibernate accepts a converted `String` type as
   `lower`'s argument, runs the converter, and PostgreSQL types the parameter (no "could not determine data type").
3. `converts` over `min`/`max` of a converted column, and over a path through a join and an embeddable.
4. Under `value_handling_mode=inline`, the parameter stays `?` (as AC-FLT-22's binder parameter does, D-170).

### Part C — values under inline value handling

#### C.1 Knowing the setting (R-VND-28)

```java
// ProviderSupport
/** Whether the factory renders a criteria value given without a parameter as a literal. */
@Incubating default boolean inlinesValues(EntityManagerFactory emf) { return false; }
```

`model-query-hibernate` answers from the session factory's options (`getCriteriaValueHandlingMode() == INLINE`), on
both lines. The executor asks once and carries the answer through `RenderOptions`, as it does the binder. Without
`model-query-hibernate` the answer is `false`; whether the executor should instead read
`hibernate.criteria.value_handling_mode` from `emf.getProperties()` (a standard JPA map, no `org.hibernate` import,
INV-7) is open (§Unresolved, 4).

#### C.2 Binding (R-FLT-08 amended)

Every place the engine hands Criteria a value goes through one `JoinContext` helper, `bind(typedBy, value, type)`:

| `inlinesValues` | Binder types `typedBy` | Value | Rendering |
|---|---|---|---|
| false | — | any | today's call, unchanged (`cb.equal(path, value)`, …) |
| true | yes | any | the binder's parameter, typed by the column's mapping (D-170) |
| true | no, or no binder | mapping-independent (R-FLT-21: `String`, `java.lang`/`java.math` number, `java.time`) | `cb.parameter(type)` |
| true | no, or no binder | anything else (enum, array, `Boolean`, `UUID`, a `@Convert` value) | `MQ1502` naming the path and the column, before any statement |

The default setting keeps today's calls on purpose: they are what every snapshot and every converted, enum and
`Boolean` column has been tested against, and nothing is gained by changing them. Under the setting, the operator
path's tested rule takes over. `MQ1502` is the operator path's code already; its message widens to name a filter,
cursor, key or assignment. No new code.

#### C.3 New ids

R-VND-28; AC-FLT-35 (with `model-query-hibernate` under the setting, on both lines and Tier 1: every value path of
§C.4's list renders `?` and returns the rows it returns under the default setting, enum, `@Convert`, `Boolean` and
`UUID` columns included); AC-FLT-36 (under the setting with a test provider and no binder: a `String` filter binds,
an enum filter is `MQ1502` before any statement; under the default setting both run as today).

#### C.4 Spike (M30.0)

1. The audit: run the TCK's filter, paging, keyset, `byKeys`, write, insert, conflict and source suites with the
   setting on and a `StatementInspector` that fails on a marker value appearing in SQL text; the list of inlining
   paths goes into R-FLT-08 and AC-FLT-35.
2. `cb.in(path)` with many parameters under the setting (Hibernate's in-list padding).
3. A keyset cursor over a computed expression, which the binder doesn't type: confirm `cb.parameter(type)` compares
   as today.
4. The default setting's SQL snapshots don't move.

## Compatibility

**Part A** is additive. `CountMode.INLINE_COUNT`, `InlineCountRenderer` and `ProviderSupport#inlineCounts` (a default
method) are new and `@Incubating`. A `switch` over `CountMode` without a default gains a case.

**Part B** adds `OperandBinder#converts` (a default method on an unreleased type) and changes results: with
`model-query-hibernate`, `likeIgnoreCase` and `eqIgnoreCase` over a converted attribute return the rows they missed.
No query that matched before stops matching through a prefix-style converter; through other converters the behaviour
moves from "silently no rows" to `like`'s semantics, which R-FLT-06 already documents. It is a fix of a silent wrong
result (INV-5), so it lands before the freeze rather than waiting; the `CHANGELOG.md` entry names it under *Changed*.

**Part C** adds `ProviderSupport#inlinesValues` (a default method, `@Incubating`). Under the default setting nothing
changes. Under `value_handling_mode=inline`, statements carry `?` instead of literals, and, without a binder, a filter
on an enum, `Boolean`, `UUID` or converted column becomes `MQ1502` where it ran with an inlined value before: that is
R-FLT-08 being kept, not narrowed, and it names the fix (add `model-query-hibernate`).

## Alternatives

**Part A**

- **Do nothing.** `COUNT` stays; a caller wanting one snapshot wraps the page in a read-only transaction. Cheaper to
  maintain, and the spike's numbers may say this is right (§A.6, probe 5).
- **Refuse the shapes the window can't count** (a new `MQ` code). Exact too, but makes the mode unusable as a default
  (§A.4).
- **Make it the default.** Loses the early stop on large results; a performance regression for every caller who
  didn't ask.
- **A `VendorProfile` fact and `PortabilityCheck` report.** Nothing is ever refused, so there is nothing to report.

**Part B**

- **Refuse with `MQ1001`** when `converts` is true (Q-30's first proposal). Honest, but narrows shipped operators for
  every converter, prefix-style ones included, where Part B's rendering is right; a caller must rewrite to `ilike`
  (PostgreSQL and H2 only) or to a function.
- **Document only.** Leaves a silent wrong result past the freeze (INV-5).
- **Convert in Java.** `core` can't see a `@Convert` converter (INV-7), and the provider would have to re-run the
  converter by hand, which `lower(?)` with a typed parameter gets for free.
- **Always render `lower(?)`.** Changes every snapshot and moves folding from Java's `Locale.ROOT` to the database for
  unconverted columns too, for no gain.

## Unresolved questions

For the RFC's `architect-review`s and the spike:

1. **Part A, worth it?** If probe 5 shows `INLINE_COUNT` rarely beats `COUNT`, withdraw Part A and close Q-29 as
   declined; the single-snapshot benefit alone may not justify a mode.
2. **Part A, the name.** `INLINE_COUNT`, or `WINDOW_COUNT` for what it renders.
3. **Part B, non-text converters.** A converter that encrypts or encodes makes `lower` on the stored form
   meaningless; Part B gives it `like`'s semantics (no rows), as today. Whether such a column should instead be
   refused (`MQ1001`) for the ignore-case operators only, where `converts` can't tell the kinds apart, is open.

4. **Part C, without `model-query-hibernate`.** Read the setting from `emf.getProperties()` in `jpa` (portable map,
   provider-specific key), or leave `inlinesValues` false and document that only `model-query-hibernate` keeps
   R-FLT-08 under the setting.
5. **Part C, bind or refuse.** §C.2 binds; refusing the setting (§Alternatives) is the fallback if the audit finds a
   path the helper can't type, such as a write assignment Hibernate types only by inference.
