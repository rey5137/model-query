# RFC 0019 — Compiled queries with late-bound values, and a faster row path

- **Status:** draft
- **Affects:**
  - **`core`:** new `QueryParam<C>`, a typed, named placeholder for a value bound per call (Part A); `Filters` and
    `Having` overloads taking a `QueryParam` for the comparison, `between` and `like` operators; `Expr.param`, the
    late-bound counterpart of `Expr.value` (R-COL-26); `ModelQuery#with(QueryParam, value)`, a bound copy whose
    `definition()` is the unbound query (as `orderedBy` copies are, R-QRY-14); `Builder#compiled()`, the opt-in.
    Part B changes `RowSelection`'s `Row` view internally (`@EngineFacing`, no public signature).
  - **`api/10`:** new R-COL-38 for `QueryParam` and `Expr.param`; R-COL-10's wrapper reads by a position resolved
    once per build (Part B, P-B.1), and, only if P-B.3 ships, "a `Tuple` or an `Object[]`".
  - **`api/11`:** new R-QRY-18 for `compiled()` and `with`; R-QRY-07 gains "a compiled definition refuses a
    customizer"; R-QRY-14's `definition()` text becomes "differs in its ordering and bindings alone".
  - **`api/12`:** new R-FLT-30: a `QueryParam` filter is never skipped (R-FLT-01's skip contract is for `Optional`
    values), and binding `null` is refused; R-FLT-08 unchanged (a parameter is a bind, always).
  - **`engine/20`:** new R-EXE-17: the entry cache, its scope and key, which calls use it, what runs per call; new
    AC-EXE-25 and up.
  - **`engine/21`:** no rule changes. Every R-PAG-* path takes today's build route with the bound values (P-A.4).
  - **`vendor/40`:** new R-VND-31: `ProviderSupport#statements(EntityManagerFactory)`, an optional provider mechanism
    (`copiesOnCreate(EntityManager)`, `prepare`); the portable fallback rebuilds per call. The ids are reserved against
    RFC 0020's R-COL-37, R-FLT-29, R-VND-29 and R-VND-30.
  - **`model-query-hibernate`:** the `statements` implementation; its route is chosen by the spike (§Spike).
  - **`model-query-spring-boot-starter`:** `modelquery.statement-cache-size`.
  - **`docs/spec/SPEC.md`:** at acceptance, INV-9's "Per-query state lives only in `JoinContext`, created per build"
    gains "or a sealed entry derived from one (R-EXE-17)", and `QueryParam` joins the list of immutable definitions.
  - **`docs/site`:** a "hot paths" page (compiled queries, the row path's cost, when `JdbcClient` is the better tool,
    and a paragraph on `hibernate.criteria.plan_cache_enabled` for 7.4 users), both tabs (CC-DOC-01).
- **Discussion:** to open
- **Target:** after 0.10.0, `@Incubating`. Both parts are additive; Part B is behaviour-identical.

## Summary

Two costs that LitePOS measured on Spring Boot 3, Hibernate 7 and PostgreSQL 16. Part A lets a fixed-shape query be
built and translated once and bound per call: a definition marked `compiled()` declares its per-request values as
`QueryParam`s, a call binds them with `with(param, value)`, and the executor reuses one cached entry per
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
- **Databases:** PostgreSQL 16 (`postgres:16-alpine`), the reporting user's setup, for every timing and every rule;
  MySQL 8.0.31 for RA.1 and the chosen route on the "today" read only, as information (no rule reads it); H2 for
  functional cells only.
- **Dataset:** a LitePOS-shaped schema (`store`, `terminal`, `cashier`, `sale`, `sale_line`) seeded deterministically:
  365 days, about 86k `sale` rows, 3–6 lines per sale. Fixed queries: a "today" read (about 50 rows, one join, two
  filters), a by-key `one`, an offset `page` with `COUNT`, the 365-day grouped count (the "variances" read), and the
  flat 86k projection (12 columns: `Long`, `String`, `Instant`, `BigDecimal`, one enum through a converter) read by
  `list`, `stream` and `export`.
- **Harness:** JMH (`jmh-core`, test scope) started from a JUnit entry point, `@Fork(2)`, 5 warm-up and 10 measured
  iterations (20 samples per route and line), `Mode.AverageTime`, one transaction per invocation (the `JdbcClient`
  baselines run in the same transaction template), the connection pool warm. **Iterations last 1 s in Part A** (about
  180 benchmarks, about 1 h; JMH's default 10 s would take about 15 h) **and 10 s in Part B**, whose operations take
  about 600 ms. JMH writes the timing TSV; the reducer takes min/median/max per route and the ratio to the baseline, and
  computes **every compared difference from the raw iterations as a one-sided Welch bound at 99.9%, per line, on the
  side that makes a criterion harder to pass** (the upper bound of an overhead, the lower bound of a saving). With
  n = 20 (t about 3.88) the bound resolves about ±0.2 ms on a 5 ms PostgreSQL read: enough for the 0.2 and 0.3 ms
  thresholds, not for 0.1 ms, so a rule on that threshold defaults to the side with less code (rule 4). An interval that
  straddles a threshold is rerun once at `@Fork(5)`.
  Attribution comes from JFR (`jdk.ExecutionSample`, 10 ms) and async-profiler wall-clock flame graphs per route, kept
  as collapsed stacks.
- **Plan-cache counting** is measured, not inferred: a separate run per Part A route with `hibernate.generate_statistics`
  records `getQueryPlanCacheHitCount()` and `getQueryPlanCacheMissCount()` over the measured iterations. A route
  counts as translating once only if its hit ratio is at least 99%.

### Spike A: where the per-call 1–3 ms goes

Routes for the "today" read, the `one`, the offset page and the grouped read, each compared with `JdbcClient`
running the SQL Hibernate logged. Let `O(r)` be the overhead of route `r` over that baseline on the "today" read,
`T = max(0.3 ms, 10% of the baseline)`, and `B = time(RA.2)`, an absolute time with no baseline.

| Route | What it isolates |
|---|---|
| RA.1 today's executor | the total |
| RA.2 `ModelQuery#buildQuery` alone | our build: `assemble`, `JoinContext`, `ConditionGroup`, `ValueOperands#valueOperand` |
| RA.3 one built `CriteriaQuery` reused, `em.createQuery` per call; `hibernate.criteria.copy_tree` true and false | Hibernate's criteria copy and `SqmSelectionQueryImpl` construction, i.e. the build cache |
| RA.4 RA.1 plus `hibernate.criteria.plan_cache_enabled=true`, **7.4 only** (default false; the key does not exist on 6.6) | Hibernate's criteria plan cache with no model-query change (a fresh but equal tree can hit) |
| RA.4b RA.3 plus the same setting, 7.4; **informational**, since the starter does not set it | the build cache plus the plan cache |
| RA.4h RA.3 plus the hint `hibernate.query.plan.cacheable`, 6.6; **one statistics cell**, no timing | confirms the identity key never hits on a copied tree (a shared uncopied tree is forbidden, P-A.3) |
| RA.5 the SQM rendered once to HQL with named parameters, including the constants `cb.equal(expression, v)` binds implicitly, `em.createQuery(hql)` per call | the HQL string plan cache, keyed by text, plus the parameter slots; no `CriteriaQuery`, so no copy dependence. The only provider candidate |
| RA.6 a native SQL string through `createNativeQuery`, **"today" read only** | the floor inside JPA; never ships |

`addNamedQuery` is not a candidate: it keeps the query's max results, lock and hints, and its registry never evicts.

Cells: each route on each of the four read queries and on a compiled `orderedBy` call (RA.6 as above); `copy_tree`
true and false for RA.3, true only for RA.4b and RA.5 (no `copy_tree=false` timing is taken for them: RA.5 does not
depend on it, and a build cache is never used with copying off, P-A.3). It is on under JPA and Spring bootstrap, off
under native bootstrap, and a user can override it, per session too. Profiled classes: `ModelQuery#assemble`,
`JoinContext`, `ValueOperands`, `DefaultModelQueryExecutor#create`
and `#checked`; Hibernate's `SqmCriteriaNodeBuilder`, `QuerySqmImpl`/`SqmSelectionQueryImpl`,
`QueryInterpretationCacheStandardImpl`, `ConcreteSqmSelectQueryPlan`, `StandardSqmTranslator` and
`JdbcSelectExecutorStandardImpl`.

**Functional cells** (H2 and PostgreSQL, both lines): RA.3, RA.4b and RA.5 return the rows and the SQL of RA.1,
every value a bind, under `hibernate.criteria.value_handling_mode` `bind` and `inline` (R-FLT-08, D-177 Part C);
16 threads binding different values to one stored `CriteriaQuery` each read only their own rows (in the Spike section,
not deferred to implementation); two sessions with different enabled `@Filter`s or `@TenantId` values on one
definition each see their own rows; and a session with `hibernate.criteria.copy_tree` set false through
`setProperty` reports `copiesOnCreate` false while its factory's other sessions report true (P-A.3).

**Throughput and leak cells.** The gating cell, `@Threads(16)` on 8 or more cores, with no execution (at 16 threads
the database and Hikari's default pool of 10 dominate and hide lock contention): lookup in the build cache (RA.3
behind a bounded access-ordered LRU, each get locking) + `createQuery` + bind + `getParameters()`, against
`buildQuery` + `createQuery`. A heap check: boot and close 50 `EntityManagerFactory`s through the cache, force GC, and
count live factories (a cached `CriteriaQuery` reaches its factory through the provider's node builder); expect at
most 1, the last one closed.

**Decision rules for Part A**, evaluated per line and applied in this order; the first that holds wins. Each
difference is the reducer's Welch bound. `S_b = O(RA.1) − O(RA.3)` is the saving of the build cache (the baseline
cancels); `S_p = O(RA.1) − O(RA.5)` is the saving of the provider route, the same code on both lines. RA.5 passes when
its functional cells hold and its plan-cache hit ratio is at least 99%.

1. **Drop** (Part A becomes a docs paragraph) if RA.4 passes on 7.4 (its overhead's upper bound is at most `T` and its
   rows and SQL are identical) **and** RA.1 on 6.6 is already within `T`. Otherwise Part A ships on both lines.
2. **Neither** (also a docs paragraph, `QueryParam` does not ship) if no route has a lower-bound saving of at least
   0.2 ms while passing its functional cells.
3. **Provider only** if the upper bound of `B` is under 0.2 ms on both lines (the build is cheap) and RA.5 passes:
   `compiled()` entries hold only the provider's `Prepared` form and the row selection, both taken
   once from one build of the definition; no call rebuilds (RA.5 measures exactly this).
4. **Both** if the build cache passes its functional and throughput cells, RA.5 passes, and the lower bound of
   `S_p − S_b` reaches `max(0.1 ms, 10% of O(RA.1))` on both lines: the entry holds the sealed snapshot and the
   provider's `Prepared` form.
5. **Build only** otherwise, when the build cache passes its functional and throughput cells (it is the default when
   noise leaves rule 4 undecided, or RA.5 fails): the entry holds the sealed snapshot only.

Any shipped route must also have a saving over RA.1 whose lower bound is at least 0 on every read cell, the grouped
read and the compiled `orderedBy` call included. If the build cache fails its gating cell (no saving over `buildQuery`
+ `createQuery`) or any functional cell, it is struck from rules 4 and 5, and rule 3 applies if RA.5 passes, otherwise
rule 2.

### Spike B: where row-mapping time goes

Routes for the flat 86k projection, each against `JdbcClient` + `RowMapper` (RB.0):

| Route | What it isolates |
|---|---|
| RB.1 raw JPA `CriteriaQuery<Tuple>`, `Tuple.get(String)` | Hibernate's tuple path, no model-query code |
| RB.2 RB.1 with `Tuple.get(int)` | the alias lookup inside `TupleImpl`/`TupleMetadata`; the floor P-B.1 adopts |
| RB.3 raw JPA `CriteriaQuery<Object[]>` (`cb.array`) | `Tuple` construction |
| RB.4 today's `list` | the total |
| RB.5 today's `list` with an index-resolved `ValueRow` (spike copy) | `RowSelection`'s alias map and converter dispatch (P-B.1) |
| RB.6 RB.5, or RB.4 if P-B.1 fails, with the flat-row `mapAll` (spike copy) | per-row bookkeeping and list sizing (P-B.2) |
| RB.7 `stream` and `export` | the result-list materialization and the export loop, reported separately, not gating |

Profiled classes: `RowSelection.ValueRow#get`/`#raw`, `SelectField#hashCode` (an expression's structural hash,
`RowSelection.java:126-128`), `ColumnField#toModel`, `ColumnField.valueColumn`,
`DefaultModelQueryExecutor#mapAll`/`#loaded`, the generated `map(Row)`; Hibernate's `TupleImpl`, `TupleMetadata`,
`JdbcValuesSourceProcessingStateStandardImpl` and the row reader (`StandardRowReader`). Objects per row come from JMH
`-prof gc` (`gc.alloc.rate.norm`), not from throttled JFR allocation samples. A functional cell runs a one-column projection
(a `PRIMARY_KEY` phase) through RB.3, since `cb.array` with a single item must still yield an array.

**Decision rules for Part B**, per line, each step shipping only if its rule holds on **both** lines:

| Step | Ships if |
|---|---|
| gate | `RB.4 − RB.2` (model-query's own share) is at least 25% of `RB.4 − RB.0` on at least one line; otherwise Part B is dropped unless P-B.3 passes, and the docs page says when `JdbcClient` is the better tool for a large flat read |
| P-B.1 | RB.5 is at least 10% faster than RB.4 |
| P-B.2 | RB.6 is at least 5% faster than its base (RB.5 if P-B.1 ships, else RB.4) and objects per row do not rise |
| P-B.3 | RB.3 is at least 15% faster than RB.2 (the `Tuple.get(int)` route P-B.1 already adopts) and the single-item cell passes |

## Design

### Part A: compiled queries

**P-A.1 Placeholders.**

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

- `QueryParam` is immutable and **equal by identity**, and `build()` refuses two different parameters with one name
  (`MQ1210`). Equality by name would let two unrelated constants bind each other. Overloads take it in `eq`, `ne`,
  `lt`, `le`, `gt`, `ge`, `between`, `like`, `likeIgnoreCase` and `eqIgnoreCase`, on `Filters` and `Having`;
  `Expr.param(QueryParam<C>)` takes it where `Expr.value` takes a value (R-COL-26), which also covers LitePOS gap 8 (a
  per-request time zone). `op`, `fullTextMatch` and `range` take no `QueryParam` in v1 (lifting that later is
  compatible).
- `of` maps a primitive class to its wrapper, so `QueryParam.of("n", long.class)` is a `QueryParam<Long>` with
  `type()` `Long.class`; otherwise `isInstance` would fail on every `with`.
- **`in` takes no `QueryParam`.** Its list's size is part of the statement text (R-FLT-02 renders an empty list as
  `FALSE`), so a late-bound collection would change the shape the cache keys on. Reason: the cache must never key on
  a value.
- **A `QueryParam` filter is never skipped**, and `with(param, null)` throws `NullPointerException`; a value that is
  not `type().isInstance(value)` throws `IllegalArgumentException`. Skipping changes the joins (R-FLT-03), so it
  cannot be decided per call on a cached statement. A query whose filters are optional per request uses `Optional`
  values on a non-compiled definition, as today. Reason: one definition, one shape.
- R-FLT-08's bindability is decided at `build()` on `type()` (`MQ1502`), not at `with`.
- Java-side value transforms (`likeIgnoreCase`'s `Locale.ROOT` lowering, `LikeMode` escaping, a converter's
  `toAttribute`) run at bind, per call, through the same helper the build uses today (`ValueOperands`), so a bound
  value renders exactly as a built one (R-FLT-07, R-FLT-08).
- A `FilterOperator`'s renderer runs once per entry, not per call; R-QRY-18 states it must not read request state. It
  is not refused.

**P-A.2 Binding.** `ModelQuery#with(QueryParam<C>, C)` returns a copy holding one more binding, an immutable map.
**Bindings are the outermost copy layer:** `withLock`, `orderedBy` and `withFetch` on a bound query apply to its
unbound query and re-attach the bindings, so call order does not matter (a locked copy is its own definition, as
today). `definition()` returns the unbound, unsorted query, so `checkPhases` runs once per definition (D-21) and its
scratch builds accept unbound parameters. Sorted copies are cached by (definition, `SortSpec`), as `LockedCopies`
caches lock copies, in an LRU of 64 per definition (`LockedCopies` keeps its keys strongly, so an unbounded cache would
grow with every distinct request sort); `q.with(..).orderedBy(..)` per call does not build a new definition each time.
The `ORDER_IGNORED` and `TOTAL_CONTRADICTED` warnings key on `definition()`, so they warn once, not once per call. A
bound copy's `toString` names its parameters, never the values (the executor logs the query at DEBUG).

Running a query with an unbound `QueryParam`, or binding one the definition does not declare, fails with `MQ2215`
naming the parameter, **before the lock refusals and R-EXE-06's zero-limit short cut**, so no statement or lock is
reached. Every executor method accepts a bound query, compiled or not: on a non-compiled definition a `QueryParam` is
a value supplied late, built per call.

**P-A.3 The entry cache.** `compiled()` asks the executor to keep one entry per definition and call variant, in a
bounded LRU (`modelquery.statement-cache-size`, default 256). The engine owns the LRU and the key in `jpa`; the
provider supplies only a mechanism.

- **Scope:** one cache per (`EntityManagerFactory`, executor config), created once; it holds the `RenderOptions`. The
  executor config is a value-equal record of exactly the fields that change the tree, not `RenderOptions` (no
  `equals`, built per executor, so it would miss for every executor made per `EntityManager`); two executors with
  equal configuration on one factory share entries. A field held by identity (a user `VendorProfile`) would add a
  scope per executor, so scopes are **capped per factory in an LRU of 8**: an unequal configuration misses instead of
  leaking. Scopes live in a weak map by factory; because a cached `CriteriaQuery` reaches its factory through the
  provider's node builder, every scope creation first sweeps scopes whose factory is `!isOpen()`.
- **Key:** (`definition()` identity, call variant, `SortSpec`, `CountMode`). The call variant is an enum `LIST | STREAM
  | ONE | FIRST | COUNT | PAGE | PAGE_INLINE_COUNT`: the variants are different trees (`one` clears the order, `count`
  has no select list). `SortSpec` is a value-equal record. Values never enter the key. The route of an offset page
  depends on the offset (`primaryKeyFirst`), so it is decided per call **before** the lookup.
- **Entry:** sealed when stored, never handed to a customizer or mutated. It is a `BuiltQuery` with an immutable
  snapshot in place of its `JoinContext`: the parameter slots, the `Expr.value` parameter map,
  `repeatedExpressionBinds`, and the parameters each variant keeps (`bindValues` needs them, as a count clears the
  order and replaces the select). The lock checks, preflights and `mapAll` read the `BuiltQuery`. Variant mutations
  (`one`'s cleared order, the count's select) happen before sealing. The definition stays immutable and
  `static final`-safe (INV-9); `JoinContext` is created per build, and the build runs once per entry.
- **Locking:** an access-ordered LRU locks on every get, so the Spike A throughput cell gates it; a lock-striped or
  read-mostly structure replaces it only if that cell fails and the replacement passes.

What an entry holds follows the Spike A rule: the snapshot (build cache), the provider's `Prepared` form, or both.

- **Build cache** (portable, in `jpa`): the sealed `CriteriaQuery` with each `QueryParam`'s `ParameterExpression`.
  Each call creates a `TypedQuery` on the caller's `EntityManager` and binds. Sharing one tree across sessions and
  threads is safe only because Hibernate copies it on `createQuery` (`hibernate.criteria.copy_tree`: on under JPA or
  Spring bootstrap, off under native bootstrap, overridable, and switchable for one session through `setProperty`).
  **`copiesOnCreate(EntityManager)` is checked per call**; when it returns false, or there is no `ProviderSupport`,
  that call takes the build route.
- **Provider mechanism**, shaped like `constants(emf)` and `sequences(emf)`, with `jakarta.persistence` types only
  (INV-7), added to `ProviderSupport` (`model-query-jpa/src/main/java/com/rey/modelquery/jpa/spi/ProviderSupport.java:33`):

  ```java
  @Incubating default Optional<StatementSupport> statements(EntityManagerFactory emf) { return Optional.empty(); }
  // jpa.spi
  boolean copiesOnCreate(EntityManager em);
  <T> Optional<Prepared<T>> prepare(CriteriaQuery<T> query);   // empty: fall back to the build cache
  // Prepared<T>: TypedQuery<T> create(EntityManager em, Map<ParameterExpression<?>, Object> values)
  ```

  `create` binds everything itself, the parameters and the constants `cb.equal(expression, v)` binds implicitly
  (a `Parameter<?>` of one query cannot be bound on another). `HibernateProviderSupport` reads
  `SharedSessionContractImplementor#isCriteriaCopyTreeEnabled()` for `copiesOnCreate`, and implements `prepare` by
  RA.5 (render to HQL once, the parameter slots mapped by name) if Spike A selects it. The starter does not set
  `hibernate.criteria.plan_cache_enabled`, a boot-time setting an SPI cannot apply; the docs page describes it
  (changing it alters how the user's own criteria queries are cached).

**P-A.4 What uses the cache, and what runs per call.** The cache replaces `buildQuery` and the provider's
translation. Every check still runs per call against the entry's statement: lock refusals (R-EXE-14), preflights,
R-EXE-06's zero-limit short cut, the timeout (R-EXE-11), and `setMaxResults`/`setFirstResult`, which are not in the
statement. **General rule: an entry is sealed when stored, so a call whose executor addition takes a call value (a
key, a cursor, a key batch, an offset-chosen route) takes the build route.** Cached in v1: `list`, `stream`,
`one(query)`, `first`, `count`, and offset `page` (content and count, with R-EXE-15's inline count as its own
variant). **`one(query, key)`, keyset pages, primary-key-first steps, both export loops, `byKeys` and `findAllByKeys`
take today's build route with the bound values.** `one(query, key)` appends `primaryKey.equal(key, ...)` after
`buildQuery` and records the key's parameter in the build's `JoinContext` (`DefaultModelQueryExecutor.java:550-556`);
a stored entry would bind the first call's key on every later call, which returns the wrong row without an error
(INV-5), and concurrent calls would race on one `JoinContext`. Every R-PAG-* check stays exactly where it is.
Reason: the measured cost is the polled small read, and caching the paging paths would move checks that turn data
loss into exceptions.

**P-A.5 Refusals.** `compiled()` refuses, at `build()` with `MQ1210` (a definition code):

- **a `QueryCustomizer`** (R-QRY-07). A customizer runs during the build with the `CriteriaBuilder` and can read
  request state, say a tenant id from a thread-local. On a cached statement it would run once and freeze that state
  for every later call, which leaks rows across requests. Reason: the hazard is silent, and lifting the refusal later
  is compatible while adding one is not;
- **a fetch plan**, since its child queries are built per parent batch (`api/15`);
- **a `QueryParam` inside a `SourceQuery` body or a fetch plan's child query**, which nothing binds (lifting this later
  is compatible), and two parameters with one name; both are refused at **every** `build()`, compiled or not, since a
  non-compiled definition cannot bind them either;
- a `type()` not bindable under R-FLT-08 (`MQ1502`, as above).

**P-A.6 Provider state.** The R-VND-31 text allows a provider route to use only the provider's own keyed caches (the
HQL plan cache). A route that keeps anything below Hibernate's public query API (a
`SelectQueryPlan`, a JDBC operation) would carry per-session state (enabled `@Filter`s, `@TenantId`, fetch profiles)
across sessions and leak rows across tenants.

### Part B: a faster row path

Part B adds no public API. Each step is behaviour-identical and ships only if its rule in §Spike B holds.

- **P-B.1 Index-resolved rows.** The tuple position of each selected field and its value column (the converter,
  `ColumnField.valueColumn`) are resolved once per build, into arrays held by `BuiltQuery`, not on `RowSelection`
  (which is definition state, INV-9) and after the customizer runs (R-QRY-08). `ValueRow` reads `Tuple.get(int)`
  through them, instead of the alias `HashMap` and `Tuple.get(String)`. R-COL-10's guarantee is kept: no layer above
  the wrapper reads by index, so inserting a selection still cannot shift a mapping. Scoped rows (`Row#scoped`)
  resolve their re-rooted paths once per scope, not per cell.
- **P-B.2 Flat rows.** For a query with no fetch plan and no to-many join in its statement, `mapAll` skips per-row
  bookkeeping that only the dedupe and child-load paths read, and presizes the result list to
  `min(limit, 1,024)`; `Limit.of(Integer.MAX_VALUE)` must not allocate that array. R-COL-11 is unchanged: keys and
  cursors are still read from the `Row`, through `Row.raw`, before mapping.
- **P-B.3 `Object[]` selection** (conditional). The engine selects `cb.array(...)` instead of a `Tuple` and wraps the
  array; standard JPA, so no SPI. R-COL-10's first sentence becomes "Queries select a JPA `Tuple` or an `Object[]`". A
  customizer's added selections (R-QRY-08) need an alias-addressable tuple, so a query with a customizer keeps
  `Tuple`. A one-column projection (a single-item `cb.array`) has a functional cell.

### Spec changes

New ids are reserved now (R-COL-38, R-QRY-18, R-FLT-30, R-EXE-17, R-VND-31; `MQ1210`, `MQ2215`; unused in the spec and
in RFC 0020). AC ids, AC-QRY-17 and up for the surface, AC-EXE-25 and up for the cache:

- a compiled `list`, `one`, `count` and offset `page` return the rows and SQL of the built route, on each Tier-1
  vendor and both lines, under `value_handling_mode` `bind` and `inline`, with each value a `?` in the statement log;
- 16 threads binding different values to one compiled definition each read only their own rows;
- `one(q, k1)` then `one(q, k2)` on a compiled query return different rows;
- keyset, primary-key-first, export and `byKeys` over a bound compiled query match the non-compiled route, and their
  R-PAG-* refusals still throw;
- the unbound, undeclared, duplicate-name and customizer refusals throw their `MQ` codes before any statement or lock;
  `null` throws `NullPointerException` and a wrong type `IllegalArgumentException` (neither carries a code);
  `QueryParam.of("n", long.class)` binds a `Long`;
- the cache holds at most its size; a definition built per call does not grow it unless it is `compiled()`;
  `q.with(..).withLock(l)` and `q.with(..).orderedBy(s)` per call hit one entry (hit rate above 0); two executors on one
  factory share entries; executors with unequal configurations stop at 8 scopes per factory; a closed factory's scope
  is swept; a session with `copy_tree` off builds per call and returns correct rows;
- two sessions with different enabled filters or tenants on one compiled definition each see their own rows.

At acceptance, `docs/spec/SPEC.md` INV-9 changes as listed under Affects.

Part B: AC-COL-06, AC-COL-11, AC-COL-14 and AC-COL-20 run unchanged on the new row path, plus AC-COL-53: a scoped row
over a re-rooted join (and, if P-B.3 ships, a single-item array).

### Decision to record at acceptance

**D-184 — Compiled queries: entry scope, key and layering.**

- An entry is sealed when stored. Calls whose additions take a value (`one(key)`, keyset, primary-key-first, exports,
  `byKeys`) build per call.
- The key is (definition identity, call variant, `SortSpec`, `CountMode`), within a scope per (factory, executor-config
  record); scopes are capped at 8 per factory.
- Bindings are the outermost copy layer.
- The build cache needs the provider's copy-on-create fact, checked per `EntityManager` through the SPI and never
  `emf.unwrap` in `jpa`; when false, that call is built per call.
- RA.5 (HQL with named parameters, `Optional<Prepared>`) is the only provider route; it may use only the provider's
  own session-aware caches.
- Rejected: keying by `RenderOptions` (identity, so no hits); a provider-owned LRU (duplicates the key logic); named
  queries (the registry never evicts); a criteria-plan-cache provider route (needs a shared uncopied tree, forbidden).

## Compatibility

Additive and `@Incubating`. No existing signature changes and no `MQnnnn` code changes meaning; `MQ1210` and `MQ2215`
are new. `Filters` and `Having` are sealed, so the new overloads are compatible; a `null` literal is already ambiguous
because of the `Optional` overload. A definition without `compiled()` builds per call exactly as today, so caching is
never a silent change. Part B is behaviour-identical by construction: the same TCK and SQL snapshots pass, and no
snapshot changes unless P-B.3 ships, in which case every snapshot's select clause is unchanged (the array and the
tuple render the same SQL) and the spike confirms it on both lines.

## Alternatives

- **Do nothing; document Hibernate's criteria plan cache.** This is Spike A's rule 1, so it wins if the numbers say so.
  On its own it leaves our build cost and the "a request value needs a new definition" problem, and 6.6 has no such
  setting.
- **Cache transparently, without `compiled()`.** Rejected: a definition built per request would fill the cache, a
  customizer's request state would be frozen without the author asking for a cache, and the refusals would have no
  place at `build()`.
- **A separate `CompiledQuery<E, K, M>` type with its own executor methods.** Rejected: it doubles the executor surface
  (`list`, `page`, `stream`, `one`, ...) and the paging paths would need a second copy of every R-PAG check. A bound
  `ModelQuery` reuses every path as is.
- **Named parameters in a string template, or JPA `ParameterExpression`s exposed to users.** Rejected: untyped, and
  `core`'s users would handle Criteria objects that belong to one build.
- **A provider-owned cache, or `addNamedQuery`.** Rejected: the first duplicates the key logic, where correctness
  lives; the second keeps the query's max results, lock and hints, and its registry never evicts.
- **A `MethodHandle` constructor for records and classes.** Rejected: no reflection is on the row path today
  (§Motivation 2); the generated mapper already calls the constructor directly.
- **A JPA constructor expression (`cb.construct`).** Rejected: it bypasses `Row`, so keys, cursors and dedupe could no
  longer be read before mapping (R-COL-11), and converters and nested models would not apply.
- **A JDBC fast path (`ResultSet` read directly).** Rejected: it would sit outside JPA, against INV-7's layering, and
  duplicate converters and type handling the provider owns. For a large flat read, the docs point to `JdbcClient`.

## Unresolved questions

Only what the spike measures; each answer selects a branch already decided above.

1. **Part A's rule.** Which of rules 1–5 holds, from `O(RA.1)`, `B`, `S_b`, `S_p` and the 99% plan-cache hit
   ratio, per line.
2. **Provider route.** Whether RA.5 passes its functional and statistics cells, so that `prepare` ships.
3. **Build-cache safety and cost.** Whether the sealed `CriteriaQuery` passes the 16-thread, `copy_tree` and
   session-filter cells, whether its `@Threads(16)` throughput is at least RA.1's (else a lock-striped LRU is tried,
   then the cache is struck), and whether the factory-leak heap check is clean.
4. **Part B's steps.** The gate share and the gains of RB.5, RB.6 and RB.3, which decide whether P-B.1, P-B.2 and
   P-B.3 ship.
