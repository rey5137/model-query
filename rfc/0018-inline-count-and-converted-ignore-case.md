# RFC 0018 — Inline count, converted ignore-case filters, inline value handling, and filter-only to-many joins

- **Status:** accepted as D-177 (2026-10-21, user decision), with the acceptance spike's amendments after three
  `architect-review` passes. M30, 0.10.0 (D-176); the freeze moves to M31.
- **Affects:**
  - **`core`:** `CountMode.INLINE_COUNT` (Part A); `OperandBinder#converts(Expression)`, a default method (Part B);
    the operator path's value rule (`ConditionGroup`'s `operand` and `requireMappingIndependent`) extracted into one
    `@EngineFacing` helper every engine value path calls under inline value handling, `jpa`'s `Keyset` included
    (Part C).
  - **`engine/20`:** R-EXE-02's table gains `INLINE_COUNT`; new R-EXE-15 (when the page statement carries the total,
    its fallback, its preflight); new AC-EXE-18 to AC-EXE-21. R-EXE-03 to R-EXE-05 unchanged.
  - **`api/12`:** R-FLT-07 amended (`likeIgnoreCase` and `eqIgnoreCase` over a converted attribute with a binder; the
    no-binder case kept as a named limitation; the folding property); R-FLT-06's `@Convert` sentence amended to match,
    and its "inlined under `value_handling_mode=inline` too" made true only of user values; R-FLT-08 amended (it holds
    under `hibernate.criteria.value_handling_mode=inline`); new AC-FLT-33 to AC-FLT-36. `Having`'s `likeIgnoreCase`
    follows `Filters`'.
  - **`engine/20`, Part D:** R-EXE-04 amended (a filter-only to-many join renders as a semi-join), R-EXE-14 amended
    (a locked read over the shape runs); new R-EXE-16, R-WRT-10 named as the shared mechanism;
    AC-EXE-22 to AC-EXE-24; AC-EXE-11, AC-EXE-13 and `integration/50` AC-SPR-19's `MQ2003` cases move to a
    customizer's join or a §D.1-excluded shape.
  - **`engine/21`:** AC-PAG-28's cursor-binding claim (and `Keyset`'s javadoc for it) amended for the setting;
    R-PAG-02's within-page drop and R-PAG-21's short-page note scoped to a customizer's join or a §D.1-excluded
    shape (Part D).
  - **`vendor/40`:** R-VND-24 amended (`converts`); new R-VND-27: `ProviderSupport#inlineCounts(EntityManagerFactory)`,
    a provider fact; the window itself renders through `SourceRenderer#window` (R-SRC-04); new R-VND-28:
    `ProviderSupport#inlinesValues(EntityManagerFactory)`.
  - **`model-query-hibernate`:** `inlineCounts` from the dialect's `supportsWindowFunctions()`; `converts` on
    `HibernateOperandBinder`; `inlinesValues` from the factory's criteria value handling mode.
  - **`reference/92`:** D-176 (release, decided before acceptance), D-177 (acceptance); Q-28, Q-29 and Q-30
    resolved; Q-31 (`COUNT` against a page over a filter-only to-many join) resolved by Part D.
  - **`docs/site`:** the paging page's count modes, the filters page's case-insensitive section, a note on inline
    value handling, and the semi-join performance caveat (both tabs, CC-DOC-01).
- **Discussion:** to open
- **Target:** 0.10.0 (M30, D-176), `@Incubating`. Parts A and C are additive; Parts B and D change shipped filters'
  rows (§Compatibility).

## Summary

Four independent parts, all accepted as D-177, shipped together before the 1.0 freeze.

**Part A (Q-29).** `CountMode.INLINE_COUNT` takes a page's total from the page statement itself, as a
`count(...) over ()` window, instead of a separate count statement: one round trip, and the total and the rows from
one snapshot without a transaction. The total is `COUNT`'s, always: the window is used only where the page statement
returns exactly the rows `COUNT` counts, and `COUNT`'s statements run otherwise. Opt-in, never the default.

**Part B (Q-30).** `likeIgnoreCase` and `eqIgnoreCase` over a JPA `@Convert` attribute today bind a value the
converter never sees. Over the `SKU:` converter, equality, `EXACT` and `STARTS_WITH` miss silently (INV-5);
`CONTAINS` and `ENDS_WITH` succeed only because their leading `%` tolerates the prefix. Where the binder serves the
line, they will bind the value typed by the column's mapping, so Hibernate converts it, and fold both sides in SQL:
`lower(col) = lower(?)`. They then match as `like` and `ilike` already do over a converted column (D-171). Columns
without a converter render as today; without a binder the limitation stays, named in R-FLT-07.

**Part C (Q-28).** Under `hibernate.criteria.value_handling_mode=inline`, Hibernate writes a value the engine passed
as `cb.equal(path, value)` into the SQL text, which breaks R-FLT-08 ("every value is a bind parameter") on most of the
engine's value paths. When the provider reports that setting, every engine value path binds through the rule the
operator path already follows (R-FLT-21): the binder's parameter, typed by the column, or a parameter typed by class for
a value whose binding doesn't depend on the mapping, or `MQ1502` for that filter before any statement. Under the
default setting the rendering is unchanged.

**Part D (Q-31).** A filter through a to-many join that no selected column reads repeats its root once per matching
child: `list`, `stream` and offset pages return the repeats, `one` fails with `MQ2003`, keyset and export pages drop
them client-side into short pages, and `count` reports distinct roots, so a page's total and its rows disagree. The
engine will render such a join as a correlated semi-join instead: the statement's predicate moves into
`exists (select 1 from E e2 join e2.items i … where e2 = root and <predicate over e2>)`, so each root is one row, and
`count`, `list`, pages and aggregates agree, where nothing else in the outer statement keeps a to-many join and the
root isn't treated (D.1). It is the rendering bulk writes already use (R-WRT-10). Only that shape's SQL changes.

## Motivation

### Part A

`COUNT` runs two statements per page. Outside a transaction they read two snapshots, so a row inserted between them
gives a total that disagrees with the page; `warnOfContradictedTotal` logs that but can't prevent it. Inside a
transaction, the second statement still costs a round trip. Blaze-Persistence's answer is `COUNT(*) OVER()` in the
page query. JPA Criteria has no window functions, so the engine can't render it portably; Hibernate 6.6 and 7.4 can,
and the engine already renders windows through them for sources (`SourceRenderer#window`, R-SRC-04).

The price is real and is why this is opt-in: the window counts the whole result before `LIMIT`, so the database
can't stop early at the top N rows. On a large, unselective result that is often slower than a separate count.
The spike (§A.6) met the user's tie rule for selective flat first pages (§Resolutions, 1); its slow shapes set the
count-mode advice in §A.1. There is no automatic performance fallback: the user chooses the mode.

### Part B

`ConvertedLikeTest` pins it: over a converter adding `SKU:`, `likeIgnoreCase(EXACT, "A12")` binds `a12`, the database
compares it with `lower('SKU:a12')`, and no row matches. The spike (§B.4) confirms that `eqIgnoreCase`, `EXACT` and
`STARTS_WITH` miss on both lines and Tier 1. `CONTAINS` and `ENDS_WITH` return the right rows only by accident:
their leading `%` tolerates the stored prefix, not because Hibernate converted the operand.

The parameter meets `lower(col)`, not `col`, so Hibernate has no mapping to type it by and never runs the converter.
Converting it in Java wouldn't help: the engine can't see a `@Convert` converter (`core` reads the JPA metamodel,
which doesn't expose it, INV-7), and lowering before conversion is wrong for any converter that cares about case.

### Part C

RFC 0017's spike found it (finding 8): `cb.equal(path, value)`-style calls, where Hibernate makes the parameter
itself, inline the value under `value_handling_mode=inline`; an explicit `ParameterExpression` never does. M29's
operators bind explicitly (D-167, D-170), and AC-FLT-22 runs under the setting. The rest of the engine passes raw
values: filters (`ConditionGroup`: `eq`/`ne`, ranges, `like`, the ignore-case pair), keyset cursors (`jpa`'s
`Keyset`), primary keys (`PrimaryKey`, so `one(q, key)`, `byKeys` and primary-key-first pages), the optimistic-lock
version check (`ModelUpdate`), fetch-plan child rounds (`load.key(built).in(roundKeys)`, raw values through
`Expression.in(Object...)`), the executor's own `CriteriaUpdate` assignments (`setValue`), `PersistedEntity`,
insert-select predicates, conflict conditions and source cursors. The observed inventory in §C.2 separates leaks
from already-bound insert assignments and structural literals.

An inlined value still returns the right rows (D-171 (7) measured `like`'s), so this is not INV-5. What breaks is
R-FLT-08's promise itself, the plan cache (every distinct value is a new statement), and R-FLT-09's bind limit, which
no longer counts what it was written to count. An application may set the mode for its own reasons, and nothing tells
it the engine's promise has gone.

### Part D

`ExecutionTest`'s AC-EXE-04 and `SingleRowTest`'s AC-EXE-11 pin both halves. `ORDER_ROWS.where(f ->
f.eq(ITEM_PRODUCT_FILTER, "P001"))` joins `OrderEntity.items` only to filter, so order 1, with four `P001` items, is
four identical rows: `count` says one order (`count(distinct o)`, R-EXE-04), `list` returns four, `one` throws
`MQ2003` with a hint to use `Filters.exists`, and the engine already admits the repeats where it has to: export and
keyset pages drop a key repeated within a page (R-PAG-02, R-PAG-21: "a look-ahead row that repeats a key within the page
can give a short page"). An offset page under `COUNT` reports a total of distinct roots over pages of repeated rows,
so roots whose rows fall after row position `total` are never returned: `COUNT` returns nothing once `offset >= total`
and sets `hasNext = offset + rows < total`, and `warnOfContradictedTotal` fires only for treated queries, so the loss
is silent (INV-5). On a grouped query the join inflates every aggregate over a root column (`sum(o.total)` counted
once per item), certain by SQL semantics, with no error and no test today; AC-EXE-24 pins the fix.

The model never sees a child's column, so a repeat is an identical row: no caller wants it. `Filters.exists` gives the
right rows today, but the filter column that joins is the natural way to write the filter, generated QModels expose
it, and nothing refuses it.

## Design

### Part A — `CountMode.INLINE_COUNT`

#### A.1 API

```java
public enum CountMode {
    COUNT,
    NO_COUNT,
    ONLY_COUNT,
    /**
     * COUNT's exact total, read from the page statement as a window where that statement returns exactly the rows
     * COUNT counts, and from COUNT's statements otherwise (R-EXE-15). The window counts the whole result before the
     * page's LIMIT, so the database can't stop early: on a large, unselective result COUNT can be faster.
     */
    @Incubating INLINE_COUNT
}
```

```java
Slice<OrderRow> page = executor.page(q, PageSpec.of(3, 50), CountMode.INLINE_COUNT);
page.total(); // exact, as under COUNT
```

`INLINE_COUNT` is a request about *how* to count, not *whether*: a `Slice` under it reports the same total and
`hasNext` as under `COUNT`, always (R-EXE-02's guarantees unchanged). Spring Data's `ModelPage` reads `Slice.total`
and needs no change; the repository default stays `COUNT`.

**Choosing the mode.** The [acceptance spike](0018-spike-results.md) favors selective filters: flat selective
first pages tie with `COUNT` on PostgreSQL and MySQL within run-to-run spread, and grouped middle/deep pages improve
to roughly 0.53–0.71x `COUNT`'s time. Unselective flat pages cost roughly 8–20x on first pages and 2–8x on
middle/deep pages (deep PostgreSQL was closer to 1.3–1.4x): the window counts every matching row before the page is
cut, while a separate count can use a cheaper index-only plan. Grouped selective first pages cost 14–32x in this
fixture (grouped unselective first pages ~1.04–1.20x); an offset-0 planner choice is the inferred cause, not an
EXPLAIN result. Check the application's real plan.
These are laptop fixture measurements, not latency promises. The user chooses the count mode; there is no
automatic performance fallback. R-EXE-15's correctness fallbacks stay as designed.

#### A.2 Rendering (R-VND-27)

```java
// ProviderSupport
/**
 * Whether the factory's database renders a window function over the page statement (R-EXE-15). True only where
 * sources() is present and its window renders COUNT.
 */
@Incubating default boolean inlineCounts(EntityManagerFactory emf) { return false; }
```

The window renders through the `core` SPI that already renders windows, `SourceRenderer#window(cb, WindowSpec)`
(R-SRC-04; Hibernate's renders `COUNT` as `windowFunction("count", Long.class, window, argument)` and adds no frame for
`NONE`): `WindowSpec(COUNT, cb.literal(1), [], [], NONE)`, so `count(1) over ()` in every shape. Not `root.<id>`:
on a grouped page `count(o.id) over ()` is refused by PostgreSQL ("must appear in the GROUP BY clause"), by MySQL
under `ONLY_FULL_GROUP_BY` and by H2, and on an `@EmbeddedId` or `@IdClass` root it counts a tuple; `count(1)` is what
`COUNT` already uses on a source. No new renderer type, no new `mq_*` function, and nothing in `RenderOptions` that
`core` can't see (INV-7): `RenderOptions` carries `core` renderers only. `model-query-hibernate` answers
`inlineCounts(emf)` from the dialect's `supportsWindowFunctions()`, as `operators(emf, f)` answers from the dialect
(D-171, D-175); its `sources()` is unconditional, so the two facts agree. A third-party `ProviderSupport` answering
`inlineCounts` true with no `sources()` would reach `SourceRenderer#window`'s `MQ4014` default where `COUNT` works, so
the executor gates on both (fallback 1). Nothing is refused, so no `VendorProfile` fact and no `PortabilityCheck`
report are needed (INV-6: no vendor branch at a call site).

#### A.3 Execution (R-EXE-15)

**The rule.** The page statement carries the window only when it returns exactly the rows `COUNT` counts, and the
rule follows the code rather than a reading of the definition: the window is used only when the `Counting` that
`preflightedCounting` already builds is the content statement itself with its selection replaced by `count(root)` or
`count(1)`, or is the database-side count of its groups (R-EXE-03). That `Counting` is built from the statement after
the customizer runs, so a customizer's `distinct()` (`ModelQuery` R-QRY-11; D-154 and AC-COL-52 treat a `distinct()`
query as supported) and a customizer's own to-many join are seen. Any other shape falls back, so a shape added after
M30 falls back until a slice admits it, rather than miscounting until someone lists it.

**The fallback** is `COUNT`'s statements, for the total only. It applies:

1. with `inlineCounts(emf)` false or `sources()` empty (no `model-query-hibernate`, a dialect without window
   functions, or a provider without a window renderer);
2. on a `DISTINCT` statement;
3. where `COUNT` counts client-side (R-EXE-03: no grouped count support, a source carrying checks, a refused
   `GROUPED_COUNT` treat site). Some of these grouped cases would count correctly on the content statement; falling
   back is conservative, never wrong;
4. where `COUNT`'s statement is not the content statement with its selection replaced: `count(distinct root)` over a
   to-many join that no selected column reads (R-EXE-04; after Part D, from a customizer's join or a §D.1-excluded
   shape), and a treated, ungrouped root, which `COUNT` counts on a freshly built statement (`buildRootIdCount`,
   R-EXE-05, D-154). The spike's probe 4 found equal treated-root totals as an HQL observation only; treated shapes
   still fall back in M30, and admitting them is future work;
5. on a page past the end: no row, offset above 0.

| Shape | Behaviour |
|---|---|
| Window used, rows returned | total = the window's value; `hasNext = offset + rows < total` |
| Window used, no row, offset 0 | total = 0 |
| Window used, no row, offset > 0 | `COUNT`'s statement runs for the total (fallback 5) |
| Grouped, `COUNT` in SQL | the window runs after `GROUP BY` and `HAVING`, so it counts groups (R-EXE-03's meaning) |
| Primary-key-first page | Window on step 1's key query; step 2 unchanged (R-PAG-13 keeps key rows as root rows) |
| A fallback of 1–4 | `COUNT`'s two statements, as under `COUNT` |

**Preflight (R-VND-26).** `INLINE_COUNT` builds and preflights `COUNT`'s statements exactly as `COUNT` does, before the
first statement runs, whether or not the window is used, since fallback 5 can only be known after the content
statement. `checkFirstRun` and `preflightedCounting` take `INLINE_COUNT` as they take `COUNT`. A refused treat site is
`MQ4016` before any statement (AC-EXE-19).

**The hidden column.** The window is selected as `mq_0total`, following `mq_0id` (D-154), outside D-146's
`mq_<property>` space. It is added inside `offsetPage` and `keyQuery`, after `buildQuery` (so after the customizer,
whose `multiselect` can't drop it, and after `core` adds `mq_0id`) and before `preflight`, so under treated nodes the
statement R-VND-26 checks and runs is the one carrying the window; fallbacks 1–4 are decided there. It is appended the
way `selecting(...)` already appends a selection to a compound `Tuple` selection. It is read by alias, never by
position: `loaded(built, tuple)` ignores the trailing alias, step 1's `readKeys` reads the total off a
primary-key-first page's keys, and it never reaches `fields()`, `Rows`, the mapper or a fetch plan.

**Logging.** Each fallback logs its reason at `DEBUG`, once per executor and `ModelQuery`, with the state held in the
executor, as `count`'s ignored-`orderBy` warning does: fallback 1 depends on the factory, and a `static final` query
is shared between executors (INV-9). The executor's existing weak warn-once sets hold it.

Keyset pages carry no total (D-105) and take no `CountMode`, so they are untouched. `page` already refuses row locks
(R-EXE-14), so `FOR UPDATE` with a window never arises. `ONLY_COUNT`, `count(q)` and exports are unchanged. The
reviews checked, and found nothing to add for: grouped with `HAVING`, correlated columns (R-EXE-05 keeps their joins
in both statements), set-operation and CTE sources (the window sits on the outer select), `NO_COUNT`'s probe row (not
used), fetch plans (separate statements). Treated roots fall back (fallback 4).

#### A.4 Why fall back rather than refuse

A refusal would make `INLINE_COUNT` unusable as a repository-wide setting: every to-many filter would throw. The
fallback costs only the statement the caller would have run under `COUNT`, the total stays exact (INV-5), and the
`DEBUG` line says why. An application that wants to know can inspect the query (`api/16`).

#### A.5 New ids

R-EXE-15; R-VND-27; AC-EXE-18 (the inline total equals `COUNT`'s on flat, grouped, `HAVING` and primary-key-first
pages, both lines, Tier 1, one statement each), AC-EXE-19 (past the end: `COUNT` runs, total exact; a refused treat
site `MQ4016` before any statement), AC-EXE-20 (fallbacks 1–4, a customizer's `distinct()` included, run `COUNT`'s
statements and log their reason once per executor and query), AC-EXE-21 (no `model-query-hibernate`: `COUNT`'s
statements, the same `Slice`).

#### A.6 Spike (M30.0)

The [acceptance results](0018-spike-results.md#part-a--correct-window-rendering-accepted-under-the-users-relaxed-gate)
cover these probes on both Hibernate lines and Tier 1; performance is PostgreSQL and MySQL only. Rendering and
totals agree; fallback routing remains simulated until implementation. Part A's EXPLAIN was not run.

1. `SourceRenderer#window` with `COUNT` over `cb.literal(1)` and an empty window on an entity root's page statement,
   under `setFirstResult` and `setMaxResults`: `count(1) over ()` in its SQL, and `mq_0total` read by alias;
2. grouped with `HAVING`: `count(1) over ()` is accepted and counts groups;
3. a customizer's `distinct()` and a customizer's to-many join: confirm the miscounts fallbacks 2 and 4 avoid;
4. sources (CTEs), set-operation and recursive sources, treated roots and correlated columns: the window's total
   against `COUNT`'s, as a check of the rule, not the place fallbacks are found;
5. performance: PostgreSQL and MySQL, 1M rows, first, middle and deep pages, selective and unselective filters, flat
   and grouped, `INLINE_COUNT` against `COUNT`'s two statements in one transaction. The user's accepted gate is
   no loss beyond run-to-run spread on selective first pages on both databases; a tie qualifies (§Resolutions, 1).
   Flat selective first pages tie on both lines and databases. The slow shapes set §A.1's advice, with no automatic
   performance fallback.

### Part B — case-insensitive filters over a converted attribute

#### B.1 The binder says what it converts (R-VND-24)

```java
public interface OperandBinder {
    <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type);
    default boolean typesBy(Expression<?> typedBy) { return true; }
    /**
     * Whether a value typed by typedBy goes through a converter of the mapping (a JPA AttributeConverter) when
     * bound. Asked only where typesBy(typedBy) is true.
     */
    @Incubating default boolean converts(Expression<?> typedBy) { return false; }
}
```

`HibernateOperandBinder` answers from the node type it already resolves, behind the same line check (D-170), so it
can't drift from what the binder actually binds: true when its `JdbcMapping` carries a JPA `AttributeConverter` (a
`@Convert` or an auto-applied converter). An enum's built-in conversion isn't a JPA converter and answers false;
`likeIgnoreCase` takes `String` columns only (R-FLT-07). `OperandBinder` is new in 0.10.0 and unreleased, so the
default method is free.

#### B.2 Rendering (R-FLT-07 amended)

`typedBy` is the column's own expression, `column.expression(ctx)`, never `cb.lower(...)`: D-170 (4) declines to type
by a function.

| Column | `eqIgnoreCase(col, v)` | `likeIgnoreCase(col, v, mode)` |
|---|---|---|
| no binder, `typesBy` false, or `converts` false | `lower(col) = ?` | `lower(col) like ?` |
| `typesBy` and `converts` true | `lower(col) = lower(?)` | `lower(col) like lower(?) escape '\'` |

In the first row, `v` or its pattern is lowered in Java, as today. In the second, the binder types `?` by the
column's mapping and binds `v` for equality or `mode`'s pattern of `v` for LIKE.

On a converted column, Hibernate runs the converter on the bound value, then the database folds both sides with the
same `lower`. A model-query `ColumnConverter` runs first, as R-FLT-06 and R-COL-14 order it. The pattern's wildcards
and escapes go through the converter, exactly as for `like` and `ilike` (D-171). Over the `SKU:` converter,
`EXACT "A12"` binds `SKU:A12`, both sides fold to `sku:a12`, and the row matches.

**The folding property.** The rendering is right exactly when `lower(conv(x)) = lower(conv(y))` holds if and only if
`lower(x) = lower(y)`: true of a converter that adds a prefix or suffix, upper- or lower-cases, or trims. It is not
true of one that encrypts or encodes: a deterministic encryption matches only the exact same case (a silent
case-sensitive match), and an encoding such as base64 can collide under `lower()` and return extra rows. R-FLT-07 and
the user guide state the property; `converts` can't tell converter kinds apart, so no converter is refused
(§Resolutions, 3).

**Without a binder** (no `model-query-hibernate`, or a Hibernate line outside the binder's, where `operands(emf)` is
empty, D-170 (1)), `converts` is never asked and the silent miss stays. R-FLT-07 keeps it as a named limitation;
Part B narrows INV-5's exposure, it doesn't close it.

SQL for columns without a converter is unchanged, so no existing snapshot moves. `Having.likeIgnoreCase` over an
aggregate follows the same table, with `typesBy` and `converts` asked of the aggregate expression (§B.4, probe 3).

#### B.3 New ids

AC-FLT-33 (`eqIgnoreCase` and `likeIgnoreCase` in every `LikeMode` over the `SKU:` converter match the rows `like`
matches with the same case, both lines, Tier 1; `ConvertedLikeTest`'s pinned misses become matches), AC-FLT-34
(without a binder, and on a column without a converter, the SQL is today's).

#### B.4 Spike (M30.0)

The [acceptance results](0018-spike-results.md#part-b--converted-comparison-works-qualify-the-original-failure-claim)
support the design on both lines and Tier 1. Converter inspection stands in for the absent `converts` API.
MySQL's default case-folding collation also matches both cases in the non-folded equality controls; H2 and
PostgreSQL match only the exact case. That is a database observation, not a Hibernate-line difference.

1. `eqIgnoreCase`, `EXACT` and `STARTS_WITH` miss today over `SKU:`; `CONTAINS` and `ENDS_WITH` succeed accidentally.
2. `cb.lower(param)` with the binder's parameter on both lines: Hibernate accepts a converted `String` type as
   `lower`'s argument, runs the converter, and PostgreSQL types the parameter (no "could not determine data type").
3. `typesBy` and `converts` over `min`/`max` of a converted column, and over a path through a join and an embeddable.
4. Under `value_handling_mode=inline`, the parameter stays `?` (as AC-FLT-22's binder parameter does, D-170).

### Part C — values under inline value handling

#### C.1 Knowing the setting (R-VND-28)

```java
// ProviderSupport
/** Whether the factory renders a criteria value given without a parameter as a literal. */
@Incubating default boolean inlinesValues(EntityManagerFactory emf) { return false; }
```

`model-query-hibernate` answers from the session factory's options (`getCriteriaValueHandlingMode() == INLINE`), on
any line, whether or not its binder serves that line. The executor asks once and carries the answer through
`RenderOptions`, as it does the binder. Without `model-query-hibernate` the answer is `false`, documented: reading a
Hibernate key in `jpa` would put provider knowledge outside `ProviderSupport`, and would turn correct, inlined rows into
`MQ1502` (§Resolutions, 4).

#### C.2 Binding (R-FLT-08 amended)

The operator path's value rule (`ConditionGroup`'s `operand` and `requireMappingIndependent`) moves into one
`@EngineFacing` helper, callable from `core`'s value paths and from `jpa`:

```java
<T> Expression<T> valueOperand(RenderOptions options, CriteriaBuilder cb, Expression<?> typedBy, Class<T> type,
        Object value, String label, ParameterSink sink)
```

Today's `operand` needs its `JoinContext` for three things: making the parameter, binding it, and checking that the
parameter isn't already one of the statement's (`ctx.statementParameters()`); it also names the operator and the column
in `MQ1502`. The helper takes those as `label` and a `sink` that binds and checks reuse, so `Keyset` (whose
`after(cursor, joins, cb)` reaches the binder and `inlinesValues` through `joins.renderOptions()`, but collects its
parameters in `Beyond.parameters`, not `ctx.bind`) and the executor's fetch-plan and update paths can call it. The
operator path's `valueType() == null` gate stays with its caller. `Keyset`'s placeholders are already explicit
parameters and are unaffected. The name stays distinct from `JoinContext`'s existing
`bind(ParameterExpression, Object)`.

| `inlinesValues` | Binder types `typedBy` | Value | Rendering |
|---|---|---|---|
| false | — | any | today's call, unchanged (`cb.equal(path, value)`, …) |
| true | yes | any | the binder's parameter, typed by the column's mapping (D-170) |
| true | no, or no binder | mapping-independent (R-FLT-21) | `cb.parameter(type)` |
| true | no, or no binder | anything else | `MQ1502` before any statement |

Mapping-independent values are `String`, `java.lang`/`java.math` numbers and `java.time` values (R-FLT-21).
Enums, arrays, `Boolean`, `UUID` and `@Convert` values are not; `MQ1502` names the path and column.

**Why only under the setting.** Under the default setting `cb.equal(path, v)` and an explicit parameter both render
`?`, so snapshots wouldn't move either way. Binding explicitly always would cost elsewhere: without a binder (another
provider, or a Hibernate line the binder doesn't serve) every enum, `Boolean` and `UUID` filter would become `MQ1502`,
and every query would go through D-170's reflective constructor. Under the setting, the operator path's tested rule
takes over. `MQ1502` is the operator path's code already; its message widens to name a filter, cursor, key or
assignment. No new code, and a path the audit can't type gets `MQ1502` for that path, not a refusal of the whole
setting. `MQ1502` is a `ModelQueryDefinitionException`; for a call-time key or cursor value it reports input the
installation can't bind rather than a bad definition, which R-FLT-08 states and D-177 records.

**Required path inventory (R-FLT-08 / AC-FLT-35).** The
[execution-family audit](0018-spike-data/value-path-audit.md) supplies 23 observed paths on both lines and Tier 1.
Implementation covers every path below, preserving mapping-aware binding and exact rows or DML round trips:

- Filter input leaks: `comparison-eq`, `comparison-ne`, `comparison-gt`, `comparison-ge`, `comparison-lt`,
  `comparison-le`, `comparison-range`, `filter-membership`, `eq-ignore-case`, `like`, `ignore-case`,
  `subquery-filter`.
- Key and cursor input leaks: `byKeys-chunk`, `fetch-key`, `keyset-UUID`; PAG-18 also observes decimal, timestamp,
  binary and primary-key equality-prefix cursor inputs.
- Mapped input leaks: `converted-enum`, `converted-Boolean`.
- Write and source input leaks: `update-assignment`, `delete-filter`, `insert-select-filter`,
  `conflict-condition`, `source-keyset`.
- Binding success to retain: `typed-insert-binds`, whose typed insert-values operands already use `?`.
  Insert-select assignment columns also bind already; its source predicate leaks.

The first 22 paths are confirmed input leaks; the last is a regression control. Structural source tags,
conflict-arbiter predicates needed to match a partial index, LIKE escapes, user-authored expression constants,
discriminator restrictions and SQL NULL are classified separately, not treated as confirmed input leaks.
The same source tag used as a cursor comparison is input (`source-keyset`). The audit's broad marker detector
has false positives and misses some literal forms; neither its 20,739 SQL candidates nor its 912 failing tests
per line counts independent defects. AC-FLT-35's inspector must classify structural literals and cover the
confirmed inputs, including BETWEEN, function arguments and later IN elements the spike's regex can miss.

Two texts that say the engine's values are never inlined are amended to hold under the setting: R-FLT-06's
"(inlined under `value_handling_mode=inline` too)", which then describes a user's own `like` value only, and AC-PAG-28's
cursor claim with `Keyset`'s javadoc for it.

#### C.3 New ids

R-VND-28; AC-FLT-35 (a TCK profile with the setting on, run by the two-line verify on both lines and Tier 1: the
filter, paging, keyset, `byKeys`, fetch-plan, write, insert, conflict and source suites with a `StatementInspector`
failing on a marker value in SQL text, each returning the rows it returns under the default setting, enum,
`@Convert`, `Boolean` and `UUID` columns included; all 23 observed paths in §C.2 covered, with structural literals
classified separately);
AC-FLT-36 (under the setting with a test provider and no binder: a `String` filter binds,
an enum filter is `MQ1502` before any statement; under the default setting both run as today). A profile rather than a
one-off test, because the setting is a second rendering of every value path and would rot if only the spike ran it.

#### C.4 Spike (M30.0)

The [acceptance results](0018-spike-results.md#part-c--real-execution-family-audit-establishes-the-binding-work)
support the binding design. The real TCK audit exposed the §C.2 inventory; explicit-parameter probes preserve
mapped types, IN padding and computed cursors. The no-binder refusal is simulated, and the default control is
targeted SQL equality, not a whole-project verification. Production routing and the ongoing profile remain to build.

1. The audit: the AC-FLT-35 suites with the setting on and the marker inspector; the list of inlining paths goes into
   R-FLT-08 and AC-FLT-35.
2. `cb.in(path)` with many parameters under the setting (Hibernate's in-list padding).
3. A keyset cursor over a computed expression, which the binder doesn't type: confirm `cb.parameter(type)` compares
   as today.
4. The default setting's SQL snapshots don't move.

### Part D — a filter-only to-many join as a semi-join

#### D.1 When it applies (R-EXE-04 amended, new R-EXE-16)

Part D applies to a statement whose `WHERE` would make a to-many join that **nothing else in the outer statement
keeps**: no selected column, order term, group key, aggregate, `HAVING` term or fetch-plan key reads a to-many join,
and the root is not treated. If the outer statement keeps any to-many join, the statement renders as today, `MQ2003`'s
hint and R-PAG-02's within-page drop included: moving the whole `WHERE` into a sub-query would then test terms on that
kept join against some child of the sub-query's root instead of the outer row's child, and return rows filtered today
(a model selecting `items.productCode`, filtered on `items.qty > 5` and `tags.name = 'x'`, would get every item of an
order having one such item). Treated roots are excluded too: a `WHERE` treat moved into a second-root sub-query is a
treat site no `TreatRenderer` has been measured for (R-COL-35, `ExpectedNarrowing`); `buildRootIdCount` stays as it
is. Sources have no to-many join, so they never qualify. Fetch-plan child loads through a `through` path, rooted at a
join, are out of scope and keep today's rendering.

The decision is made in `core`, inside `ModelQuery`'s `assemble`, before the `WHERE` renders, since Criteria can't
remove a join once made; the executor's `filterOnlyToManyJoin` (used for `MQ2003`'s hint) runs after the build and the
customizer, excludes only selected columns, and can't tell a customizer's join from the engine's, so it can't decide
this. Filters are lambdas, so the only way to see which joins they make is to render them (D-65). The shipped precedent
is R-WRT-10's `WriteRendering.rows`/`probe`: render the predicate into a scratch sub-query with a fresh `JoinContext`,
so no orphan parameter reaches `statementParameters()`, compare the to-many joins it made with the outer context's
`toManyJoins()` **by join path** (not by `JoinKey`, which carries the join type: an `or` makes a LEFT join in the probe
where today it reuses the outer INNER one), then render for real and add the sub-query's binds.

#### D.2 Rendering

The statement's `WHERE` predicate, the whole of it, renders inside a correlated sub-query over a second root of the same
entity, and the outer statement keeps only the semi-join, as R-WRT-10 already renders a bulk write's filter tree in one
`EXISTS` over a second root (AC-WRT-07 pins reads and writes agreeing):

```sql
-- today
select o.id, o.number from orders o join order_items i on i.order_id = o.id
 where i.product_code = ? and o.status = ?
-- Part D
select o.id, o.number from orders o
 where exists (select 1 from orders o2 join order_items i on i.order_id = o2.id
                where o2.id = o.id and i.product_code = ? and o2.status = ?)
```

The whole predicate moves, not only its terms on the to-many join, because a term on the child and a term on the root
can sit under one `or`, and two terms on the same join must hold for the same child (`i.product_code = ? and
i.qty > ?` is not two independent `exists`). The predicate is evaluated row by row with the same join types rebuilt
inside, so `or`, `not(...)` and the null-extended rows of a LEFT join keep their meaning. Correlation is entity
equality (`o2 = o`), not key columns: a read need not declare a `primaryKey`. To-one joins only the predicate uses are
made only in the sub-query, so they never reach the outer statement. `WHERE` and `HAVING` render together today (D-26);
Part D splits them, `WHERE` into the sub-query and `HAVING` on the outer statement. What the executor adds after
`buildQuery` against `built.joins()` (keyset `Beyond` predicates, `byKeys`, `one(q, key)`, `MODEL_BY_KEYS`, fetch
rounds) is ANDed onto the outer statement and stays outside the `exists`, as it must. Each value still binds once
(R-FLT-09).

#### D.3 What it changes

- `list`, `stream` and offset pages return each root once; `count(q)` is unchanged and now equals `list(q).size()`.
- `count` on that shape takes `count(root)`: the outer statement has no to-many join. R-EXE-04's distinct count stays
  for a customizer's join and for the shapes D.1 excludes.
- `one(q)`, `one(q, key)` and `byKeys` no longer throw `MQ2003` for that shape; `checkPageKeys`, `byKeys` and
  `one(q, key)` take their strict paths, where a duplicate key would be `duplicateKey`, not `MQ2003`.
- Keyset and export pages stop coming back short from the engine's own joins; `stream` emits fewer elements. R-PAG-02's
  drop stays as the guard for the excluded shapes and a customizer's join.
- A grouped query's aggregates count each root once.
- Locked reads (`list`, `first`, `one`, `byKeys`, R-EXE-14) over that shape run where they threw `MQ2211`: the outer
  statement has no join; the sub-query's tables are not locked on PostgreSQL or MySQL. A filter-only to-one join no
  longer reaches the outer statement either, so it no longer refuses. R-EXE-14 is amended.
- Part A's fallback 4 remains for a customizer's join or a §D.1-excluded shape.
- Writes are unchanged: R-WRT-10 already renders them this way, and Part D brings reads in step with them.

**Performance.** In the [acceptance spike](0018-spike-results.md), PostgreSQL's semi-join ran roughly 2–3.5x faster
than the join or `select distinct`, but MySQL 8.0 ran 1.4–3.6x slower, with a second root lookup and duplicate weedout
on three of four MySQL plans. MySQL's embedded-id plan differed between the two Hibernate lines despite identical
SQL (nested-loop semijoin on 7.4). These are single executed EXPLAINs on a laptop, not repeated timing medians or
stable performance bounds. Semi-join conversion does not guarantee speed: check the application's real plans and
indexes. The correctness rewrite stays, with no unique-per-root escape hatch
(§Resolutions, 7).

#### D.4 New ids

R-EXE-16; AC-EXE-22 (over a filter-only to-many join, on both lines and Tier 1: `list` and offset pages return each
root once, `count` equals `list`'s size, a page's total matches its rows, `one` returns the single root, a locked
`list` runs; the SQL holds one `exists` and no outer to-many join), AC-EXE-23 (an `or` mixing a child term and a root
term, two terms on the same child, and a `not(...)` around a child term return the rows `Filters.exists` with the same
predicate returns; a statement that also selects through a to-many join renders and returns as today), AC-EXE-24 (a
grouped query's `sum` over a root column with a filter-only to-many join equals the sum over distinct roots).
AC-EXE-11, AC-EXE-13 and AC-SPR-19's `MQ2003` cases move to a customizer's join or an excluded shape.

#### D.5 Spike (M30.0)

The [acceptance results](0018-spike-results.md#part-d--correct-semi-join-semantics-qualified-optimizer-results)
confirm the wrong-result counterexamples and the semi-join's rows, aggregate, boolean, entity-equality and locking
behavior on both lines and Tier 1. Scratch decision and excluded-shape routing remain implementation work.
The PostgreSQL and MySQL plan observations set §D.3's caveat; they do not justify restoring the duplicating join.

1. Today's wrong results: an offset page's total against its rows and the last page under `COUNT`; a grouped `sum`
   over a root column (AC-EXE-24 then pins the fix).
2. The probe of D.1 on both lines: the scratch render's to-many joins by path, under `or` (LEFT) and `and` (INNER).
3. The semi-join on both lines and Tier 1, `o2 = o` with a single and an embedded id; its plan on PostgreSQL and MySQL
   8.0 (semi-join conversion) against the join and `select distinct`.
4. Every boolean shape of AC-EXE-23, a predicate holding a correlated sub-query, and a locked read.

## Compatibility

**Part A** is additive. `CountMode.INLINE_COUNT` and `ProviderSupport#inlineCounts` (a default method) are new and
`@Incubating`. A `switch` over `CountMode` without a default gains a case. The user chooses the mode (§A.1): selective
flat first pages tie, grouped middle/deep pages improve to ~0.53–0.71x, while unselective flat first pages cost
8–20x and middle/deep pages roughly 2–8x (deep PostgreSQL ~1.3–1.4x). Grouped selective first pages cost 14–32x
in the fixture (grouped unselective first pages ~1.04–1.20x), with an inferred offset-0 planner cause; check real
plans. There is no automatic performance fallback; R-EXE-15's correctness fallbacks remain.

**Part B** adds `OperandBinder#converts` (a default method on an unreleased type) and changes results: where the binder
serves the line, `eqIgnoreCase`, `EXACT` and `STARTS_WITH` over `SKU:` return rows they missed; `CONTAINS` and
`ENDS_WITH` already returned the right rows by accident of the leading `%`. The rendering supports converters with
the folding property (§B.2); without it, a case-sensitive match or extra rows remain possible. Without a binder
the old limitation remains. MySQL's default case-folding collation is an observation from the controls, not a
promise for all collations. It fixes a silent wrong result (INV-5) where it can, so it lands before the freeze;
the `CHANGELOG.md` entry names it under *Changed*.

**Part C** adds `ProviderSupport#inlinesValues` (a default method, `@Incubating`). Under the default setting nothing
changes. Under `value_handling_mode=inline`, statements carry `?` instead of literals, and where no binder types a
column, every value of an enum, `Boolean`, `UUID` or converted column becomes `MQ1502` where it ran with an inlined
value before: not only a filter, but with such a primary key `one(q, key)`, `byKeys`, primary-key-first pages, keyset
cursors and every fetch-plan child load. On a Hibernate line the binder doesn't serve, that is a large share of an
application's queries. That is R-FLT-08 being kept, not narrowed. The message names the case it is in: without
`model-query-hibernate`, add it; with it on a Hibernate line its binder doesn't serve (7.5, say), `inlinesValues`
is true and the binder empty, so the fix is a served line or the default setting.

**Part D** adds no API and changes results for one shape, a filter through a to-many join that nothing else in the
outer statement keeps (D.1):
- `list`, `stream` and offset pages return each root once instead of once per matching child; export and keyset
  pages stop coming back short;
- `one`, `one(q, key)` and `byKeys` return the root where they threw `MQ2003`;
- grouped aggregates stop counting a root once per child;
- locked reads over the shape run where they threw `MQ2211`, the sub-query's tables unlocked;
- a customizer that resolves the filter's child path through `built.joins()` used to reuse the filter's join; it now
  makes a fresh one, multiplying rows by all children, not only the matching ones, and `query.getRestriction()` is the
  `exists`.

Each change but the last replaces a result `count` already disagreed with, so it fixes INV-5 rather than narrowing
anything; the `CHANGELOG.md` entry names all five under *Changed*, with `Filters.exists`, a customizer's own join and
the excluded shapes as before. That shape's SQL changes; every other snapshot stays. `PortabilityCheck` has no
read-join rule, so it reports nothing new (INV-6 unchanged).

The correctness change also changes plan cost: PostgreSQL's semi-join was roughly 2–3.5x faster than join or DISTINCT
in the spike, MySQL 8.0's 1.4–3.6x slower (duplicate weedout / second root lookup). These were single laptop
EXPLAINs; check real plans and indexes (§D.3). There is no unique-per-root escape hatch (§Resolutions, 7).

## Alternatives

**Part A**

- **Do nothing.** `COUNT` stays; a caller wanting one snapshot wraps the page in a read-only transaction. Cheaper to
  maintain; D-177 accepts the opt-in mode under the tie rule, with the costs in §A.1.
- **A new `InlineCountRenderer` in `jpa.spi` with an `mq_count_over` function** (the first draft). `RenderOptions`
  is a `core` type and carries only `core` renderers, so `core` couldn't see it (INV-7); and `SourceRenderer#window`
  already renders the window.
- **Refuse the shapes the window can't count** (a new `MQ` code). Exact too, but makes the mode unusable as a default
  (§A.4).
- **A deny-list of fallbacks.** A shape added later would miscount until listed; the rule (§A.3) falls back by default.
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
- **Always render `lower(?)`.** Moves folding from Java's `Locale.ROOT` to the database for unconverted columns too,
  for no gain.

**Part C**

- **Refuse the setting** at startup with a new `MQ4017`. The smallest change and honest, but it rejects a supported
  Hibernate setting the application may need, and an inlined value returns the right rows: the cost of today's
  behaviour is R-FLT-08, the plan cache and R-FLT-09, not INV-5, which per-path `MQ1502` answers more narrowly.
- **Always bind explicitly**, whatever the setting. Turns every enum, `Boolean` and `UUID` filter into `MQ1502` where no
  binder serves the line, for no gain where nothing inlines (§C.2).
- **Read `hibernate.criteria.value_handling_mode` from `emf.getProperties()` in `jpa`.** Provider knowledge outside
  `ProviderSupport`, and correct rows turned into `MQ1502` without `model-query-hibernate`.
- **Document only.** R-FLT-08 would stay false under a setting the engine can detect.

**Part D**

- **`select distinct` on the content statement.** Merges two roots whose selected values are equal, and PostgreSQL
  refuses `ORDER BY` a column the distinct selection lacks; adding the root id to fix both changes the selection.
- **Count rows instead of distinct roots.** Makes the total agree with the pages by making both wrong: the repeats
  stay, and aggregates stay inflated.
- **Refuse the shape** with an `MQ` code pointing at `Filters.exists`. Honest, but breaks every query written the
  natural way, generated filter columns included, for a rewrite the engine can do itself.
- **Move only the terms on the to-many join into the `exists`.** Wrong under `or` and for two terms on one child
  (§D.2).
- **Correlate the outer root itself**, as `Filters.exists` does. Hibernate 6 drops the ON clause of a join off a
  correlated root, which breaks the LEFT joins an `or` or a `not` makes; a second root with an equality is R-WRT-10's
  shape, already shipped for writes.
- **Decide from the executor's `filterOnlyToManyJoin`.** It runs after the build, sees only selected columns and
  can't tell a customizer's join; the decision has to come before the `WHERE` renders (D.1).
- **Move the whole `WHERE` whenever any filter-only to-many join exists.** Wrong when another to-many join is kept
  (D.1), a silent wrong result.
- **`root.id in (select o2.id …)`.** Same rows where the id is single; fails on composite ids, which entity equality
  in a correlated `exists` handles.
- **Do nothing; keep Q-31 open.** Leaves silently wrong totals and aggregates past the freeze.

## Resolutions (D-177)

The former unresolved items 1–7 are resolved by the user's acceptance on 2026-10-21, using the
[spike results](0018-spike-results.md):

1. **Part A, worth it? Resolved: keep.** The user's gate is no loss beyond run-to-run spread against `COUNT` on
   first pages with selective filters on PostgreSQL and MySQL; a tie qualifies. Flat selective first pages tie on
   both lines and databases, meeting the gate. The user chooses the mode, with §A.1's measured advice and no
   automatic performance fallback. R-EXE-15's correctness fallbacks remain. Q-29 is resolved as accepted.
2. **Part A, the name. Resolved: `INLINE_COUNT`.** Its correctness fallbacks mean it isn't always a window;
   its javadoc states the cost.
3. **Part B, converters without the folding property. Resolved: document, don't refuse.** `converts` can't
   distinguish converter kinds; §B.2's folding property and the no-binder limitation stay. Q-30 is resolved.
4. **Part C, without `model-query-hibernate`. Resolved: `inlinesValues` stays false**, documented in §C.1.
5. **Part C, bind or refuse. Resolved: bind.** Under the setting, a path the binder can't type uses a class-typed
   parameter only for mapping-independent values; otherwise it is `MQ1502` for that path before any statement,
   including call-time key/cursor inputs. Keep the ongoing audit profile and §C.2's inventory. Q-28 is resolved.
6. **Part D, scope. Resolved: keep §D.1's exclusions.** A customizer's own to-many join stays as it is; it can't
   be told apart from one the customizer reads. Filter-only to-one joins rendered in the sub-query never reach
   the outer statement. The whole-predicate semi-join resolves Q-31.
7. **Part D, performance. Resolved: document the cost, keep the rewrite.** PostgreSQL and MySQL both convert the
   semi-join, but MySQL's measured common shapes are slower (§D.3). Check real plans and indexes; do not restore
   the duplicating join. No unique-per-root escape hatch: no metamodel fact establishes it, and such a relation
   should be mapped to-one.
