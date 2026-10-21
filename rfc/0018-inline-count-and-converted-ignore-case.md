# RFC 0018 — Inline count, case-insensitive filters over converted attributes, values under inline value handling, and filter-only to-many joins

- **Status:** draft, revised after the first `architect-review` (2026-10-21). M30, 0.10.0 (D-176); the freeze moves
  to M31.
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
  - **`engine/20`, Part D:** R-EXE-04 amended (a filter-only to-many join renders as a semi-join); new R-EXE-16,
    AC-EXE-22 to AC-EXE-24; AC-EXE-11, AC-EXE-13 and `integration/50` AC-SPR-19's `MQ2003` cases move to a customizer's join.
  - **`engine/21`:** AC-PAG-28's cursor-binding claim (and `Keyset`'s javadoc for it) amended for the setting;
    R-PAG-02's within-page drop and R-PAG-21's short-page note scoped to a customizer's join (Part D).
  - **`vendor/40`:** R-VND-24 amended (`converts`); new R-VND-27: `ProviderSupport#inlineCounts(EntityManagerFactory)`,
    a provider fact; the window itself renders through `SourceRenderer#window` (R-SRC-04); new R-VND-28:
    `ProviderSupport#inlinesValues(EntityManagerFactory)`.
  - **`model-query-hibernate`:** `inlineCounts` from the dialect's `supportsWindowFunctions()`; `converts` on
    `HibernateOperandBinder`; `inlinesValues` from the factory's criteria value handling mode.
  - **`reference/92`:** D-176 (release, decided before acceptance), the acceptance `D-n`; Q-28, Q-29 and Q-30
    resolved; Q-31 (`COUNT` against a page over a filter-only to-many join) resolved by Part D.
  - **`docs/site`:** the paging page's count modes, the filters page's case-insensitive section, a note on inline
    value handling (both tabs, CC-DOC-01).
- **Discussion:** to open
- **Target:** 0.10.0 (M30, D-176), `@Incubating`. Parts A and C are additive; Parts B and D change the rows of shipped filters
  (§Compatibility).

## Summary

Four independent parts, accepted or rejected separately, shipped together before the 1.0 freeze.

**Part A (Q-29).** `CountMode.INLINE_COUNT` takes a page's total from the page statement itself, as a
`count(...) over ()` window, instead of a separate count statement: one round trip, and the total and the rows from
one snapshot without a transaction. The total is `COUNT`'s, always: the window is used only where the page statement
returns exactly the rows `COUNT` counts, and `COUNT`'s statements run otherwise. Opt-in, never the default.

**Part B (Q-30).** `likeIgnoreCase` and `eqIgnoreCase` over a JPA `@Convert` attribute today bind a value the
converter never sees, so they return no rows and no error (INV-5). Where `model-query-hibernate`'s binder serves the
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
`count`, `list`, pages and aggregates agree. Only that shape's SQL changes.

## Motivation

### Part A

`COUNT` runs two statements per page. Outside a transaction they read two snapshots, so a row inserted between them
gives a total that disagrees with the page; `warnOfContradictedTotal` logs that but can't prevent it. Inside a
transaction, the second statement still costs a round trip. Blaze-Persistence's answer is `COUNT(*) OVER()` in the
page query. JPA Criteria has no window functions, so the engine can't render it portably; Hibernate 6.6 and 7.4 can,
and the engine already renders windows through them for sources (`SourceRenderer#window`, R-SRC-04).

The price is real and is why this is opt-in: the window counts the whole result before `LIMIT`, so the database
can't stop early at the top N rows. On a large, unselective result that is often slower than a separate count. The
spike (§A.6) measures where the line falls, against a bar set now (§Unresolved, 1).

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
values: filters (`ConditionGroup`: `eq`/`ne`, ranges, `like`, the ignore-case pair), keyset cursors (`jpa`'s
`Keyset`), primary keys (`PrimaryKey`, so `one(q, key)`, `byKeys` and primary-key-first pages), the optimistic-lock
version check (`ModelUpdate`), fetch-plan child rounds (`load.key(built).in(roundKeys)`, raw values through
`Expression.in(Object...)`), the executor's own `CriteriaUpdate` assignments (`setValue`), `PersistedEntity`, and very
likely insert assignments, conflict clauses and source parameters.

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
so the last page number it implies is wrong and some roots fall off the end (INV-5). On a grouped query the join very
likely inflates every aggregate over a root column (`sum(o.total)` counted once per item), with no error; the spike
confirms it (§D.5).

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
   to-many join that no selected column reads (R-EXE-04; after Part D only a customizer's own join produces it), and a
   treated, ungrouped root, which `COUNT` counts on a freshly built statement (`buildRootIdCount`, R-EXE-05, D-154). A
   later slice may admit the treated shape once §A.6's probe 4 proves the totals equal;
5. on a page past the end: no row, offset above 0.

| Shape | Behaviour |
|---|---|
| Window used, rows returned | total = the window's value; `hasNext = offset + rows < total` |
| Window used, no row, offset 0 | total = 0 |
| Window used, no row, offset > 0 | `COUNT`'s statement runs for the total (fallback 5) |
| Grouped, `COUNT` in SQL | the window runs after `GROUP BY` and `HAVING`, so it counts groups (R-EXE-03's meaning) |
| Primary-key-first page | the window goes on step 1, the key query (R-PAG-13 refuses a to-many selection, so key rows are root rows); step 2 is unchanged |
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

On both Hibernate lines and Tier 1:

1. `SourceRenderer#window` with `COUNT` over `cb.literal(1)` and an empty window on an entity root's page statement,
   under `setFirstResult` and `setMaxResults`: `count(1) over ()` in its SQL, and `mq_0total` read by alias;
2. grouped with `HAVING`: `count(1) over ()` is accepted and counts groups;
3. a customizer's `distinct()` and a customizer's to-many join: confirm the miscounts fallbacks 2 and 4 avoid;
4. sources (CTEs), set-operation and recursive sources, treated roots and correlated columns: the window's total
   against `COUNT`'s, as a check of the rule, not the place fallbacks are found;
5. performance: PostgreSQL and MySQL, 1M rows, first, middle and deep pages, selective and unselective filters, flat
   and grouped, `INLINE_COUNT` against `COUNT`'s two statements in one transaction. Part A stays only if
   `INLINE_COUNT` beats `COUNT` on first pages with selective filters on both databases (§Unresolved, 1); the result
   also sets the docs' advice.

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
| no binder, `typesBy` false, or `converts` false | `lower(col) = ?`, `v` lowered in Java (today) | `lower(col) like ?`, pattern lowered in Java (today) |
| `typesBy` and `converts` true | `lower(col) = lower(?)`, `?` typed by the binder, bound with `v` | `lower(col) like lower(?) escape '\'`, `?` typed by the binder, bound with `mode`'s pattern of `v` |

On a converted column, Hibernate runs the converter on the bound value, then the database folds both sides with the
same `lower`. A model-query `ColumnConverter` runs first, as R-FLT-06 and R-COL-14 order it. The pattern's wildcards
and escapes go through the converter, exactly as for `like` and `ilike` (D-171). Over the `SKU:` converter,
`EXACT "A12"` binds `SKU:A12`, both sides fold to `sku:a12`, and the row matches.

**The folding property.** The rendering is right exactly when `lower(conv(x)) = lower(conv(y))` holds if and only if
`lower(x) = lower(y)`: true of a converter that adds a prefix or suffix, upper- or lower-cases, or trims. It is not
true of one that encrypts or encodes: a deterministic encryption matches only the exact same case (a silent
case-sensitive match), and an encoding such as base64 can collide under `lower()` and return extra rows. R-FLT-07 and
the user guide state the property; `converts` can't tell converter kinds apart, so no converter is refused
(§Unresolved, 3).

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

1. Confirm `eqIgnoreCase` misses today over the `SKU:` converter (Q-30's open half).
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
`MQ1502` (§Unresolved, 4).

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
| true | no, or no binder | mapping-independent (R-FLT-21: `String`, `java.lang`/`java.math` number, `java.time`) | `cb.parameter(type)` |
| true | no, or no binder | anything else (enum, array, `Boolean`, `UUID`, a `@Convert` value) | `MQ1502` naming the path and the column, before any statement |

**Why only under the setting.** Under the default setting `cb.equal(path, v)` and an explicit parameter both render
`?`, so snapshots wouldn't move either way. Binding explicitly always would cost elsewhere: without a binder (another
provider, or a Hibernate line the binder doesn't serve) every enum, `Boolean` and `UUID` filter would become `MQ1502`,
and every query would go through D-170's reflective constructor. Under the setting, the operator path's tested rule
takes over. `MQ1502` is the operator path's code already; its message widens to name a filter, cursor, key or
assignment. No new code, and a path the audit can't type gets `MQ1502` for that path, not a refusal of the whole
setting. `MQ1502` is a `ModelQueryDefinitionException`; for a call-time key or cursor value it reports input the
installation can't bind rather than a bad definition, which R-FLT-08 states and the acceptance `D-n` records.

Two texts that say the engine's values are never inlined are amended to hold under the setting: R-FLT-06's
"(inlined under `value_handling_mode=inline` too)", which then describes a user's own `like` value only, and AC-PAG-28's
cursor claim with `Keyset`'s javadoc for it.

#### C.3 New ids

R-VND-28; AC-FLT-35 (a TCK profile with the setting on, run by the two-line verify on both lines and Tier 1: the
filter, paging, keyset, `byKeys`, fetch-plan, write, insert, conflict and source suites with a `StatementInspector` failing on a
marker value in SQL text, each returning the rows it returns under the default setting, enum, `@Convert`, `Boolean` and
`UUID` columns included); AC-FLT-36 (under the setting with a test provider and no binder: a `String` filter binds,
an enum filter is `MQ1502` before any statement; under the default setting both run as today). A profile rather than a
one-off test, because the setting is a second rendering of every value path and would rot if only the spike ran it.

#### C.4 Spike (M30.0)

1. The audit: the AC-FLT-35 suites with the setting on and the marker inspector; the list of inlining paths goes into
   R-FLT-08 and AC-FLT-35.
2. `cb.in(path)` with many parameters under the setting (Hibernate's in-list padding).
3. A keyset cursor over a computed expression, which the binder doesn't type: confirm `cb.parameter(type)` compares
   as today.
4. The default setting's SQL snapshots don't move.

### Part D — a filter-only to-many join as a semi-join

#### D.1 Which joins (R-EXE-04 amended, new R-EXE-16)

A **filter-only to-many join** is a to-many join the engine creates for a filter column's path (`Filters`, a
`@FilterColumn`, a field's filter form) that no selected column, group key, aggregate, order term or fetch-plan key
reads: the join `DefaultModelQueryExecutor` already finds for `MQ2003`'s hint ("a to-many join of `built` that no
selected column is read through"). A to-many join a selected column reads is unchanged: each joined row is a result
(R-EXE-04, AC-EXE-10), and key-based paging refuses it (R-PAG-13). A join a customizer adds is the customizer's and is
unchanged.

#### D.2 Rendering

When a statement has one or more filter-only to-many joins, the engine builds the statement's `WHERE` predicate, the
whole of it, inside a correlated sub-query over a second root of the same entity, and the outer statement keeps only
the semi-join:

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
i.qty > ?` is not two independent `exists`). Moving the whole predicate keeps every boolean shape's meaning exactly.
Correlation is entity equality (`o2 = o`), so composite and embedded ids work. To-one joins the predicate needs are
rebuilt inside the sub-query; the outer statement keeps the joins its selection, order and grouping read. `HAVING`
stays outside. The bind count is unchanged: each value still binds once (R-FLT-09). The sub-query is built through
`JoinContext`'s existing sub-query support, as `Filters.exists` and correlated columns are (R-FLT-12, R-COL-35).

#### D.3 What it changes

- `list`, `stream` and offset pages return each root once; `count(q)` is unchanged, and now equals `list(q).size()`.
- `count` on that shape no longer needs `count(distinct root)`: the outer statement has no to-many join. R-EXE-04's
  distinct count stays for a customizer's join.
- `one(q)`, `one(q, key)` and `byKeys` no longer throw `MQ2003` for that shape; the hint stays for a customizer's join.
- Keyset and export pages no longer meet within-page repeats from the engine's joins; R-PAG-02's drop stays as the
  guard for a customizer's.
- A grouped query's aggregates count each root once (§D.5, probe 2).
- Part A's fallback 4 no longer arises from the engine's own joins.
- Writes (`ModelUpdate`, deletes) are checked by the spike: a JPA `CriteriaUpdate` can't join, so they should already
  filter through a sub-query (§D.5, probe 5).

#### D.4 New ids

R-EXE-16; AC-EXE-22 (over a filter-only to-many join, on both lines and Tier 1: `list` and offset pages return each
root once, `count` equals `list`'s size, a page's total matches its rows, `one` returns the single root; and the SQL
holds one `exists` and no outer to-many join), AC-EXE-23 (an `or` mixing a child term and a root term, and two terms on
the same child, return the rows `Filters.exists` with the same predicate returns), AC-EXE-24 (a grouped query's `sum`
over a root column with a filter-only to-many join equals the sum over distinct roots). AC-EXE-11, AC-EXE-13 and
AC-SPR-19's `MQ2003` cases move to a customizer's join.

#### D.5 Spike (M30.0)

1. Today's wrong results: an offset page's total against its rows, and the last page under `COUNT`.
2. A grouped query's `sum` over a root column with a filter-only to-many join, today and under Part D.
3. The semi-join on both lines and Tier 1: `exists` with `o2 = o` (entity equality), with an embedded id, through a
   treated root and on a source's to-many join, if any can arise; its plan on PostgreSQL and MySQL against the join
   and against `select distinct`.
4. Every boolean shape of AC-EXE-23, and a predicate holding a correlated sub-query, under both.
5. Writes: a bulk update and delete whose filter column crosses a to-many join, today.

## Compatibility

**Part A** is additive. `CountMode.INLINE_COUNT` and `ProviderSupport#inlineCounts` (a default method) are new and
`@Incubating`. A `switch` over `CountMode` without a default gains a case.

**Part B** adds `OperandBinder#converts` (a default method on an unreleased type) and changes results: where the binder
serves the line, `likeIgnoreCase` and `eqIgnoreCase` over a converted attribute return the rows they missed, for every
converter with the folding property (§B.2). For one without it, the result moves from "silently no rows" to a
case-sensitive match or extra rows, as documented. It fixes a silent wrong result (INV-5) where it can, so it lands
before the freeze rather than waiting; the `CHANGELOG.md` entry names it under *Changed*.

**Part C** adds `ProviderSupport#inlinesValues` (a default method, `@Incubating`). Under the default setting nothing
changes. Under `value_handling_mode=inline`, statements carry `?` instead of literals, and where no binder types a
column, every value of an enum, `Boolean`, `UUID` or converted column becomes `MQ1502` where it ran with an inlined
value before: not only a filter, but with such a primary key `one(q, key)`, `byKeys`, primary-key-first pages, keyset
cursors and every fetch-plan child load. On a Hibernate line the binder doesn't serve, that is a large share of an
application's queries. That is R-FLT-08 being kept, not narrowed. The message names the case it is in: without `model-query-hibernate`,
add it; with it on a Hibernate line its binder doesn't serve (7.5, say), `inlinesValues` is true and the binder empty,
so the fix is a served line or the default setting.

**Part D** adds no API and changes results for one shape, a filter through a to-many join no selected column reads:
`list`, `stream` and offset pages return each root once instead of once per matching child; `one`, `one(q, key)` and
`byKeys` return the root where they threw `MQ2003`; grouped aggregates stop counting a root once per child. Each change
replaces a result `count` already disagreed with, so it fixes INV-5 rather than narrowing anything; the `CHANGELOG.md`
entry names it under *Changed*, with `Filters.exists` unchanged and a customizer's own join as before. The SQL of that
shape changes; every other snapshot stays.

## Alternatives

**Part A**

- **Do nothing.** `COUNT` stays; a caller wanting one snapshot wraps the page in a read-only transaction. Cheaper to
  maintain, and the spike's numbers may say this is right (§A.6, probe 5).
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
- **`root.id in (select o2.id …)`.** Same rows where the id is single; fails on composite ids, which entity equality
  in a correlated `exists` handles.
- **Do nothing; keep Q-31 open.** Leaves silently wrong totals and aggregates past the freeze.

## Unresolved questions

The first review's recommendations, for the second review and the spike to confirm and the acceptance `D-n` to record:

1. **Part A, worth it?** Withdraw Part A, and close Q-29 as declined, unless probe 5 shows `INLINE_COUNT` beating
   `COUNT` inside one transaction on first pages with selective filters, on PostgreSQL and MySQL. One snapshot alone
   isn't worth a mode, since a read-only transaction gives it.
2. **Part A, the name.** Keep `INLINE_COUNT`: with its fallbacks it isn't always a window, and Q-29 and D-176 use the
   name. Its javadoc states the cost.
3. **Part B, converters without the folding property.** Don't refuse: `converts` can't tell converter kinds apart.
   Document the property (§B.2).
4. **Part C, without `model-query-hibernate`.** `inlinesValues` stays false, documented (§C.1).
5. **Part C, bind or refuse.** Bind; a path the audit can't type is `MQ1502` for that path under the setting.
6. **Part D, scope.** Whether a customizer's own to-many join should get the same rendering (it can't be told apart
   from one the customizer reads), and whether the outer statement should drop to-one joins only the predicate used.
7. **Part D, performance.** If probe 3 shows the semi-join markedly slower than the join on a common shape, whether
   to keep the join where the to-many relation is known unique per root (none is today).
