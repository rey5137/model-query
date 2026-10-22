# RFC 0019 — Compiled queries with late-bound values, and a faster row path

- **Status:** draft
- **Affects:**
  - **`core`:** new `QueryParam<C>`, a typed, named placeholder for a value bound per call (Part A); `Filters` and
    `Having` overloads taking a `QueryParam` for the comparison, `between` and `like` operators; `Expr.param`, the
    late-bound counterpart of `Expr.value` (R-COL-26); `ModelQuery#with(QueryParam, value)`, a bound copy whose
    `definition()` is the unbound query (as `orderedBy` copies are, R-QRY-14); `Builder#compiled()`, the opt-in.
    Part B changes `RowSelection`'s `Row` view internally (`@EngineFacing`, no public signature).
  - **`api/10`:** new R-COL rule for `QueryParam` and `Expr.param`; R-COL-10's wrapper reads by a position resolved
    once per build (Part B, B.1), and, only if B.3 ships, "a `Tuple` or an `Object[]`".
  - **`api/11`:** new R-QRY rule for `compiled()` and `with`; R-QRY-07 gains "a compiled definition refuses a
    customizer".
  - **`api/12`:** new R-FLT rule: a `QueryParam` filter is never skipped (R-FLT-01's skip contract is for
    `Optional` values), and binding `null` is refused; R-FLT-08 unchanged (a parameter is a bind, always).
  - **`engine/20`:** new R-EXE rule: the statement cache, the statements it covers, what runs per call; new AC-EXE ids.
  - **`engine/21`:** no rule changes. Every R-PAG-* path takes today's build route with the bound values (§A.4).
  - **`vendor/40`:** new R-VND rule: `ProviderSupport#statements(EntityManagerFactory)`, an optional provider
    statement cache; the portable fallback rebuilds per call.
  - **`model-query-hibernate`:** the `statements` implementation; its route is chosen by the spike (§Spike).
  - **`model-query-spring-boot-starter`:** `modelquery.statement-cache-size`.
  - **`docs/site`:** a "hot paths" page (compiled queries, the row path's cost, when `JdbcClient` is the better tool),
    both tabs (CC-DOC-01).
- **Discussion:** to open
- **Target:** after 0.10.0, `@Incubating`. Both parts are additive; Part B is behaviour-identical.

## Summary

Two costs that LitePOS measured on Spring Boot 3, Hibernate 7 and PostgreSQL 16. Part A lets a fixed-shape query be
built and translated once and bound per call: a definition marked `compiled()` declares its per-request values as
`QueryParam`s, a call binds them with `with(param, value)`, and the executor reuses one prepared statement per
definition and statement variant. Part B makes the path from a JDBC row to a model cheaper for every query, flat
projections first. Both parts start with a spike that measures where the time goes before anything is designed further,
and each has kill criteria that shrink or drop it.

## Motivation

LitePOS (`model-query-feedback.md`, "Measurements" and gaps 1 and 2), medians of 7 alternating runs, repeated once:

| Query shape | SQL ms | model-query ms | Note |
|---|---|---|---|
| Small reads, 0–6 ms (5 methods) | — | +1–2 | per-call cost of building and translating the query |
| "Today" read on a polled dashboard | 4–6 | 7–10 | +75%: translation cost dominates a cheap query |
| 86k-row export | 383–462 | 605–633 | same plan; the extra time is row mapping |
| 365-day count variances | 40–44 | 59–65 | same plan; row mapping |

1. **Per-call cost.** Every `list`, `one` or `page` calls `ModelQuery#buildQuery`
   (`model-query-core/src/main/java/com/rey/modelquery/core/ModelQuery.java:442`), which assembles a fresh Criteria
   tree in a new `JoinContext` (INV-9). `DefaultModelQueryExecutor#list`
   (`model-query-jpa/src/main/java/com/rey/modelquery/jpa/DefaultModelQueryExecutor.java:455`) then creates and binds
   a `TypedQuery` (`create`/`checked`, `:1066`, `:1099`), and Hibernate translates the criteria's SQM to SQL. Only the
   values change between calls; the shape never does. A definition is already a `static final` constant, but its
   values are fixed at build, so a request-scoped value forces a new definition per call.
2. **Row mapping.** 86k flat rows map about 40% slower than `JdbcClient` with a `RowMapper` on the same plan. The
   feedback suggests a constructor resolved once as a `MethodHandle`. Today's row path has no reflection: the
   processor's `MAPPER` is a method reference to a generated `map(Row)` that calls the constructor directly
   (`model-query-processor/src/main/java/com/rey/modelquery/processor/QModelWriter.java:258`), and a hand-written
   `RowMapper` is a lambda. The cost is elsewhere: candidates are Hibernate's `Tuple` materialization, the
   `Tuple.get(String)` alias lookup and the alias map lookup per cell in `RowSelection`'s `ValueRow`
   (`model-query-core/src/main/java/com/rey/modelquery/core/RowSelection.java:109`, `:128`), the converter dispatch in
   `ColumnField.valueColumn`, and the per-row `Loaded` wrapper (`DefaultModelQueryExecutor#mapAll`/`loaded`, `:3302`,
   `:3613`). Which of these matters is what the spike measures (§Spike B).

The 86k-row case was an export, whose offset or keyset loop (engine/21 §4) runs several statements and dedupes by key
(R-PAG-11). The spike separates the loop's cost from the row path's; the loop's checks are not in scope.

## Spike

Throwaway research on `spike/rfc-0019`, one test-source module `spike-rfc0019/` behind `-Pspike-rfc0019`, excluded
from the default reactor and from deployment, following `rfc/0018-spike-results.md` and `rfc/0018-spike-data/README.md`:
raw TSV cells per Hibernate line, a standard-library `summarize.py` reducer with `test_summarize.py`, and a README with
Files, Cell conventions and Reproduction. Production `src/main` and committed SQL snapshots stay unchanged. The spike
module may import `org.hibernate` (it is not `jpa`).

- **Lines:** Hibernate 6.6.58.Final and 7.4.11.Final (Jakarta Persistence 3.2.0 on 7.4).
- **Databases:** PostgreSQL 16 (`postgres:16-alpine`), the reporting user's setup, for every timing; MySQL 8.0.31 for
  line and vendor parity of the Part A timings; H2 for functional cells only.
- **Dataset:** a LitePOS-shaped schema (`store`, `terminal`, `cashier`, `sale`, `sale_line`) seeded deterministically:
  365 days, about 86k `sale` rows, 3–6 lines per sale. Four fixed queries: a "today" read (about 50 rows, one join, two
  filters), a by-key `one`, an offset `page` with `COUNT`, and the flat 86k projection (12 columns: `Long`, `String`,
  `Instant`, `BigDecimal`, one enum through a converter) read by `list`, `stream` and `export`.
- **Harness:** JMH (`jmh-core`, test scope) started from a JUnit entry point, `@Fork(2)`, 5 warm-up and 10 measured
  iterations, `Mode.AverageTime`, one transaction per invocation, the connection pool warm. JMH writes the timing TSV;
  the reducer takes min/median/max per route and the ratio to the `JdbcClient` baseline. Attribution comes from JFR
  (`jdk.ExecutionSample`, 10 ms) and async-profiler wall-clock flame graphs per route, kept as collapsed stacks.

### Spike A: where the per-call 1–3 ms goes

Routes for the "today" read, the `one` and the offset page, each compared with `JdbcClient` running the SQL
Hibernate logged:

| Route | What it isolates |
|---|---|
| A.1 today's executor | the total |
| A.2 `ModelQuery#buildQuery` alone | our build: `assemble`, `JoinContext`, `ConditionGroup`, `ValueOperands#valueOperand` |
| A.3 one built `CriteriaQuery` reused, `em.createQuery` per call | Hibernate's criteria copy (`SqmCriteriaNodeBuilder`, `SqmSelectStatement#copy`) and `SqmSelectionQueryImpl` construction |
| A.4 A.3 plus `hibernate.criteria.plan_cache_enabled=true` | Hibernate's own criteria plan cache (`QueryInterpretationCacheStandardImpl`) |
| A.5 the SQM rendered once to HQL, `em.createQuery(hql)` per call | the HQL string plan cache, keyed by text |
| A.6 `EntityManagerFactory#addNamedQuery` once, `createNamedQuery` per call | the portable JPA 2.1 route |
| A.7 a native SQL string through `createNativeQuery` | the floor inside JPA |

Profiled classes: `ModelQuery#assemble`, `JoinContext`, `ValueOperands`, `DefaultModelQueryExecutor#create` and
`#checked`; Hibernate's `SqmCriteriaNodeBuilder`, `QuerySqmImpl`/`SqmSelectionQueryImpl`,
`QueryInterpretationCacheStandardImpl`, `ConcreteSqmSelectQueryPlan`, `StandardSqmTranslator` and
`JdbcSelectExecutorStandardImpl`. Functional cells for A.4–A.6 assert the same rows and the same SQL as A.1, every
value as a bind, under `hibernate.criteria.value_handling_mode` `bind` and `inline` (R-FLT-08, D-177 Part C).

**Kill criteria for Part A.**

- **Dropped** if A.4 (a Hibernate setting, no model-query change) brings the median overhead over `JdbcClient` to
  0.3 ms or 10% of the "today" read, whichever is larger, on both lines. Part A becomes a docs paragraph and, if A.4 is
  row- and SQL-identical, a starter default.
- **Shrinks to the build cache only** if Hibernate's translation is already cached on the A.3 route and A.2 accounts
  for at least 70% of the remaining overhead: the provider SPI of §A.3 is not added, and the cache holds our build.
- **Shrinks to the provider route only** if A.2 is under 0.2 ms: `QueryParam` and the cache ship, but `compiled()`
  caches only the provider's statement.

### Spike B: where row-mapping time goes

Routes for the flat 86k projection, each against `JdbcClient` + `RowMapper` (B.0):

| Route | What it isolates |
|---|---|
| B.1 raw JPA `CriteriaQuery<Tuple>`, `Tuple.get(String)` | Hibernate's tuple path, no model-query code |
| B.2 B.1 with `Tuple.get(int)` | the alias lookup inside `TupleImpl`/`TupleMetadata` |
| B.3 raw JPA `CriteriaQuery<Object[]>` (`cb.array`) | `Tuple` construction |
| B.4 today's `list` | the total |
| B.5 today's `list` with an index-resolved `ValueRow` (spike copy) | `RowSelection`'s alias map and converter dispatch |
| B.6 `stream` and `export` | the result-list materialization and the export loop, reported separately |

Profiled classes: `RowSelection.ValueRow#get`/`#raw`, `ColumnField#toModel`, `ColumnField.valueColumn`,
`DefaultModelQueryExecutor#mapAll`/`#loaded`, the generated `map(Row)`; Hibernate's `TupleImpl`, `TupleMetadata`,
`JdbcValuesSourceProcessingStateStandardImpl` and the row reader (`StandardRowReader`). JFR allocation samples
(`jdk.ObjectAllocationSample`) count objects per row on each route.

**Kill criteria for Part B.**

- **Dropped** if B.4 − B.1 (model-query's own share) is under 25% of B.4 − B.0: the gap is Hibernate's result
  processing, and the docs page says when `JdbcClient` is the better tool for a large flat read.
- B.1 ships only if B.5 is at least 10% faster than B.4 on the 86k read.
- B.3 ships only if B.3 is at least 15% faster than B.1 on both lines; otherwise R-COL-10 keeps `Tuple`.

## Design

### Part A: compiled queries

**A.1 Placeholders.**

```java
@Incubating
public final class QueryParam<C> {
    public static <C> QueryParam<C> of(String name, Class<C> type);
    public String name();
    public Class<C> type();
}

static final QueryParam<Instant> FROM = QueryParam.of("from", Instant.class);
static final QueryParam<Long> STORE = QueryParam.of("store", Long.class);

static final ModelQuery<Sale, Long, SaleRow> TODAY = SaleRow_.query()
        .where(f -> f.eq(SaleRow_.STORE_ID, STORE).ge(SaleRow_.CREATED_AT, FROM))
        .compiled()
        .build();

List<SaleRow> rows = executor.list(TODAY.with(STORE, storeId).with(FROM, startOfDay), Limit.none());
```

- `QueryParam` is immutable and equal by name and type (INV-9). Overloads take it in `eq`, `ne`, `lt`, `le`, `gt`, `ge`,
  `between`, `like`, `likeIgnoreCase` and `eqIgnoreCase`, on `Filters` and `Having`; `Expr.param(QueryParam<C>)` takes
  it where `Expr.value` takes a value (R-COL-26), which also covers LitePOS gap 8 (a per-request time zone).
- **`in` takes no `QueryParam`.** Its list's size is part of the statement text (R-FLT-02 renders an empty list as
  `FALSE`), so a late-bound collection would change the shape the cache keys on. A request-sized `in` stays an
  ordinary value. Reason: the cache must never key on a value.
- **A `QueryParam` filter is never skipped**, and `with(param, null)` throws `NullPointerException`. Skipping changes
  the joins (R-FLT-03), so it cannot be decided per call on a cached statement. A query whose filters are optional per
  request uses `Optional` values on a non-compiled definition, as today. Reason: one definition, one shape.
- Java-side value transforms (`likeIgnoreCase`'s `Locale.ROOT` lowering, `LikeMode` escaping, a converter's
  `toAttribute`) run at bind, per call, through the same helper the build uses today (`ValueOperands`), so a bound
  value renders exactly as a built one (R-FLT-07, R-FLT-08).

**A.2 Binding.** `ModelQuery#with(QueryParam<C>, C)` returns a copy holding one more binding, an immutable map;
`definition()` returns the unbound query, so `checkPhases` runs once per definition (D-21), and `orderedBy` and
`withLock` keep the bindings. Running a query with an unbound `QueryParam` fails before any statement with a new
`MQ2xxx` execution code (allocated at acceptance), naming the parameter. Binding a parameter the definition does not
declare is refused the same way. Every executor method accepts a bound query, compiled or not: on a non-compiled
definition a `QueryParam` is just a value supplied late, built per call.

**A.3 The statement cache.** `compiled()` asks the executor to keep one prepared form per definition and statement
variant, in a bounded LRU per `EntityManagerFactory` (`modelquery.statement-cache-size`, default 256), keyed by
`(definition(), phase, StatementShape, sort, count mode, RenderOptions)`. The cache is executor state, never the
definition's: the definition stays immutable and `static final`-safe, and `JoinContext` is still created per build;
the build simply runs once per cache entry (INV-9). Values never enter the key.

What an entry holds depends on the spike:

- **Build cache** (portable, in `jpa`): the finished `CriteriaQuery` after every executor addition for that variant,
  frozen, plus the `RowSelection` and each `QueryParam`'s `ParameterExpression`. Each call creates a `TypedQuery` from
  it on the caller's `EntityManager` and binds. Hibernate's criteria copy on `createQuery` (Spike A.3) is what makes
  sharing one frozen tree across threads safe; the spike's functional cells check that under concurrency, and an
  entry is never handed to a customizer or mutated after it is stored.
- **Provider statement** (behind SPI): `ProviderSupport#statements(EntityManagerFactory)` returns an optional
  `StatementCache` (`model-query-jpa/src/main/java/com/rey/modelquery/jpa/spi/ProviderSupport.java:33`), implemented
  in `HibernateProviderSupport` by the fastest route Spike A finds row- and SQL-identical (A.4, A.5 or A.6). `jpa`
  names no Hibernate type (INV-7); without the SPI the build cache is the fallback, and without either the query is
  built per call, which is today's behaviour.

**A.4 What uses the cache, and what runs per call.** The cache replaces only `buildQuery` and the provider's
translation. Every check still runs per call against the entry's frozen statement: lock refusals (R-EXE-14),
preflights, R-EXE-06's zero-limit short cut, the timeout (R-EXE-11), and `setMaxResults`/`setFirstResult`, which are
not in the statement. v1 covers the statements whose executor additions do not depend on a value: `list`, `one`,
`first`, `one(key)`, `stream`, `count`, and offset `page` (content and count, with R-EXE-15's inline count as its own
variant). **Keyset pages, primary-key-first steps, both export loops and `byKeys` take today's build route with the
bound values.** Their statements depend on the cursor, the key batch or the page (R-PAG-02, R-PAG-07, R-PAG-13), and
every R-PAG-* check stays exactly where it is. Reason: the measured cost is the polled small read; the paging paths
are not where it was found, and caching them would move checks that turn data loss into exceptions.

**A.5 Refusals.** `compiled()` refuses, at `build()` with a new `MQ12xx` definition code (allocated at acceptance):

- **a `QueryCustomizer`** (R-QRY-07). A customizer runs during the build with the `CriteriaBuilder` and can read
  request state, say a tenant id from a thread-local. On a cached statement it would run once and freeze that state
  for every later call, which leaks rows across requests. Reason: the hazard is silent, and lifting the refusal later
  is compatible while adding one is not;
- **a fetch plan**, since its child queries are built per parent batch (`api/15`). Its parent query can be compiled
  once child queries take parameters, which is outside this RFC.

### Part B: a faster row path

Part B adds no public API. Each step is behaviour-identical and ships only if its kill criterion passes.

- **B.1 Index-resolved rows.** `RowSelection` resolves each selected field's tuple position and its value column (the
  converter, `ColumnField.valueColumn`) once per build into arrays, and `ValueRow` reads `Tuple.get(int)` through
  them, keyed by an identity lookup instead of the alias `HashMap` and `Tuple.get(String)`. R-COL-10's guarantee is
  kept: no layer above the wrapper reads by index, so inserting a selection still cannot shift a mapping. Scoped rows
  (`Row#scoped`) resolve their re-rooted paths once per scope, not per cell.
- **B.2 Flat rows.** For a query with no fetch plan and no to-many join in its statement, `mapAll` skips per-row
  bookkeeping that only the dedupe and child-load paths read, and sizes the result list from the statement's limit.
  R-COL-11 is unchanged: keys and cursors are still read from the `Row`, through `Row.raw`, before mapping.
- **B.3 `Object[]` selection** (conditional, Spike B.3). The engine selects `cb.array(...)` instead of a `Tuple` and
  wraps the array; standard JPA, so no SPI. R-COL-10's first sentence becomes "Queries select a JPA `Tuple` or an
  `Object[]`". A customizer's added selections (R-QRY-08) need an alias-addressable tuple, so a query with a customizer
  keeps `Tuple`.

### Spec changes

New ids are allocated at acceptance. Part A: one R-COL (`QueryParam`, `Expr.param`), one R-QRY (`compiled`, `with`,
refusals), one R-FLT (no skip, no `null`, no `in`), one R-EXE (§A.3–A.4), one R-VND (`statements`), with AC ids:

- a compiled `list`, `one`, `count` and offset `page` return the rows and SQL of the built route, on each Tier-1
  vendor and both lines, under `value_handling_mode` `bind` and `inline`, with each value a `?` in the statement log;
- 16 threads binding different values to one compiled definition each read only their own rows;
- keyset, primary-key-first, export and `byKeys` over a bound compiled query match the non-compiled route, and their
  R-PAG-* refusals still throw;
- the unbound, undeclared, `null` and customizer refusals throw their codes before any statement;
- the cache holds at most its size, and a definition built per call does not grow it unless it is `compiled()`.

Part B: AC-COL-06, AC-COL-11, AC-COL-14 and AC-COL-20 run unchanged on the new row path, plus one AC for a scoped row
over a re-rooted join.

## Compatibility

Additive and `@Incubating`. No existing signature changes and no `MQnnnn` code changes meaning; two codes are new.
A definition without `compiled()` builds per call exactly as today, so caching is never a silent change. Part B is
behaviour-identical by construction: the same TCK and SQL snapshots pass, and no snapshot changes unless B.3 ships,
in which case every snapshot's select clause is unchanged (the array and the tuple render the same SQL) and the spike
confirms it on both lines.

## Alternatives

- **Do nothing; document Hibernate's criteria plan cache.** This is Spike A's first kill criterion, so it wins if the
  numbers say so. On its own it leaves our build cost and the "a request value needs a new definition" problem.
- **Cache transparently, without `compiled()`.** Rejected: a definition built per request would fill the cache, and a
  customizer's request state would be frozen without the author asking for a cache.
- **A separate `CompiledQuery<E, K, M>` type with its own executor methods.** Rejected: it doubles the executor surface
  (`list`, `page`, `stream`, `one`, ...) and the paging paths would need a second copy of every R-PAG check. A bound
  `ModelQuery` reuses every path as is.
- **Named parameters in a string template, or JPA `ParameterExpression`s exposed to users.** Rejected: untyped, and
  `core`'s users would handle Criteria objects that belong to one build.
- **A `MethodHandle` constructor for records and classes.** Rejected: no reflection is on the row path today
  (§Motivation 2); the generated mapper already calls the constructor directly.
- **A JPA constructor expression (`cb.construct`).** Rejected: it bypasses `Row`, so keys, cursors and dedupe could no
  longer be read before mapping (R-COL-11), and converters and nested models would not apply.
- **A JDBC fast path (`ResultSet` read directly).** Rejected: it would sit outside JPA, against INV-7's layering, and
  duplicate converters and type handling the provider owns. For a large flat read, the docs point to `JdbcClient`.

## Unresolved questions

Only what the spike measures; each answer selects a branch already decided above.

1. **Part A's route.** Which of A.4, A.5 or A.6 removes the most per-call time while returning the same rows and SQL on
   both lines, and whether A.4 alone meets the drop criterion.
2. **Part A's split.** The shares of our build (A.2) and Hibernate's copy and translation (A.3) in the overhead, which
   select the build cache, the provider route or both.
3. **Frozen-tree safety.** Whether one stored `CriteriaQuery` creates correct, independent `TypedQuery`s across threads
   on both lines (Spike A functional cells); if not, the build cache is dropped and only the provider route ships.
4. **Part B's steps.** Model-query's share of the row-mapping gap (B.4 − B.1), and the gains of B.5 and B.3, which
   decide whether B.1, B.2 and B.3 ship.
