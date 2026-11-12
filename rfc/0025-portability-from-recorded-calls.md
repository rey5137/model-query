# RFC 0025 — Portability from recorded calls, and the statements after the first

- **Status:** draft (2026-11-12), revised after its architect review
- **Affects:**
  - **`api/16`:** R-INS-10 (a Boot unit's properties and the offline overrides), R-INS-11 (a call's passes), R-INS-12
    (the limits list: the content statement of `page(..., COUNT)` and, after Part C, a fetch plan's child statements
    leave it; replay fidelity joins it; the report's head lines); new R-INS-14 (recording), R-INS-15 (replay),
    R-INS-16 (a check from a `PersistenceUnitInfo`), R-INS-17 (child statements on placeholder keys, Part C).
  - **`integration/50`:** new R-SPR-18 (`ModelQueryExecutorDecorator`).
  - **`vendor/40`:** R-VND-20 (`offlineProperties` also blanks the provider's connection keys).
  - **`INV-7`**, **D-139:** the not-API `jpa` entries the harness may call gain two (Part A, Part C).
  - **`rfc/0011`:** §4 "Statements after the first" narrowed. **D-138** amended (the `COUNT` content statement).
  - **`delivery/62`:** the deferred starter helper for a Boot unit is superseded by R-INS-16.
  - **`reference/92`:** D-221 onward.
  - **`docs/site`:** the portability page (recording, replay, the log lines a dry run produces), the logging page (JUL
    reset by MockServer).
- **Discussion:** TBD

## Summary

`PortabilityCheck` (RFC 0011, D-133, D-138) checks the first statement of each call on each target with no
database. An adopter running it on a Spring Boot service hit two limits. First, hand-written `call(...)` lambdas must
rebuild queries the application builds per request, which forced visibility changes in production code and can drift
from what the application runs. Second, a call stops at its first statement, so a `COUNT` page checks only its
`count(*)`, and a fetch plan's child statements are never rendered. This RFC proposes:

- **Part A — the content statement of a `COUNT` page.** The harness runs such a call a second time with `NO_COUNT`
  and reports both.
- **Part B — record and replay.** A recorder wraps the application's executors while its tests run;
  `PortabilityCheck.replay(...)` runs every recorded call on the targets. `PortabilityCheck.of(PersistenceUnitInfo,
  config)` takes the unit the application booted.
- **Part C — child statements on placeholder keys.** The harness renders each child load of a fetch plan, at every
  depth, on placeholder parent keys, without running the parent. It needs a spike.
- **Part D — smaller items:** a report note on unversioned targets, and two docs notes.

Rejected here: a dry run that answers each select with a synthetic row, and replaying the application's own
functions (Alternatives).

## Motivation

An adopter's report service (Spring Boot 3.5, Hibernate 6.6, MySQL in production, H2 `MODE=MYSQL` in integration
tests) wrote a `PortabilityCheck` by hand, then prototyped a replay of the calls its integration tests made. Both
worked. What they hit:

1. **Hand-written calls don't scale.** A service's query-building method and a controller's fetch plan had to be made
   visible to the test, only for the check. `sweep` reads `static final` definitions, and most real queries are built
   per request from request parameters.
2. **Replaying a repository call needs package-private code.** `findPage` translates `Pageable` and `Sort` through
   `SpringPaging` and `ModelQueryRepositoryFragment`, both package-private, so the prototype put a helper in
   `com.rey.modelquery.spring.data` (a split package).
3. **The interesting SQL is after the first statement.** `page(..., COUNT)` reaches `count(*)` and stops; the content
   select (`order by`, `limit ?, ?`) is unchecked. The prototype replayed each `COUNT` page again with `NO_COUNT` by
   hand. A four-level fetch plan's child selects are never rendered. Both are on R-INS-12's limits list, so the check
   behaves as specified; the spec is what is narrow.
4. **Smaller points.** `Target.of(MYSQL)` checks MySQL 8.0, the dialect minimum, unless a version is named (R-INS-10
   says so; the report does not). Every dry run logs Hibernate's `SqlExceptionHelper` ERROR lines for the stub's stops,
   which read as real failures. MockServer 5.x resets JUL, which silently drops model-query's `System.Logger` output.
   A Boot application without `persistence.xml` rebuilds its entity list from `PersistenceManagedTypesScanner`.

## Design

### Part A — the content statement of a `COUNT` page

A call runs on an executor the harness creates, so the harness hands the call a wrapping executor (a
`java.lang.reflect.Proxy` over `ModelQueryExecutor`, an interface with no default methods today). The proxy calls a
default method through `InvocationHandler.invokeDefault`, so a future default method's inner `page` call is seen too;
it never records `Object` methods, and it rethrows the delegate's throwable unchanged (unwrapping
`InvocationTargetException`).

- **First pass:** as today. The proxy notes whether the call's first statement was issued inside a `page(q, spec,
  COUNT)` or `page(q, spec, INLINE_COUNT)` invocation. A call whose first statement came from elsewhere (a lambda
  that swallowed an earlier stop) gets no second pass, so another invocation's SQL is never labelled as the page's.
- **Which statement came first.** For `COUNT` it is `count(*)`. For `INLINE_COUNT` it is the content select with the
  window count, unless a fallback to `COUNT` was decided; fallbacks 1–4 are decided before any statement
  (`inlineCountFallback`, R-EXE-15), and the harness asks through a not-API `jpa` entry beside D-139's (INV-7
  amended). With `primaryKeyFirst(...)` past its threshold the first statement is step 1's key query.
- **Second pass**, when the first was `REACHED` from the page and counted with `count(*)`: the same call again on a
  fresh `EntityManager` and recording, with the proxy rewriting that `page` to `NO_COUNT`. Its first statement is the
  content select, or step 1's keys under `primaryKeyFirst`.
- **Report:** that call's line becomes two, `label [count] on TARGET: ...` and `label [content] on TARGET: ...` (or
  `[keys]`), each classified on its own (R-INS-11). An `INLINE_COUNT` page that kept the window reports one line,
  `[content+count]`. A Spring `findPage(q, pageable, COUNT)` reaches the executor's `page` and is covered the same way.
- **What stays on the limits list:** an `INLINE_COUNT` page past the end, which runs `COUNT`'s statement after the
  content select (fallback 5); step 2 of primary-key-first paging. "The content statement of `page(..., COUNT)`"
  leaves it, amending D-138, which left that statement where it was.

### Part B — record and replay

**Recording (R-INS-14).** `model-query-portability` gains:

```java
@Incubating
public final class CallRecorder {
    public static CallRecorder create();                 // at most 10,000 calls
    public static CallRecorder create(int maxCalls);
    public <E> ModelQueryExecutor<E> wrap(Class<E> root, ModelQueryExecutor<E> executor);
    public <E> ModelQueryExecutor<E> wrap(Class<E> root, ModelQueryExecutor<E> executor, EntityManager em);
    public List<RecordedCall> calls();                   // a snapshot, in first-seen order
    public void clear();
}

@Incubating
public final class RecordedCall {                        // created only by CallRecorder
    public Class<?> root();
    public Method method();                              // the ModelQueryExecutor method invoked
    public List<Object> arguments();                     // unmodifiable; nulls kept; functions as markers
    public String callSite();                            // Class.method:line
    public int times();
    public Optional<Boolean> inTransaction();            // with the three-argument wrap only
    public String label();
}
```

A final class, not a record: no public constructor to forge a call, and room to add components before 1.0. `Method`
is a JDK type and tells overloads apart (`page`, `one`, `persist` have two each).

- `wrap` returns a proxy that records each invocation, then delegates. A `Collection` or `Map` argument is copied
  (`new ArrayList<>(c)` and friends, unmodifiable, nulls kept), so the application changing it later does not change
  the record. Definitions are immutable (INV-9) and are kept as they are; a `ValuesInsert` or `ModelPersist` holds its
  row models by a shallow copy (INV-9), so a row the application mutates after the call is recorded as mutated.
- **Function arguments** (`stream`'s body, `export`'s `pageTransformer` and sink, and any other functional parameter)
  are recorded as a marker of their parameter, not as the object. Replay substitutes the harness's own: a body that
  draws one element, an identity transformer, a no-op sink. The stub stops at the first statement, so no function
  argument can change what is rendered, and replaying an application closure would rerun its side effects, re-enter
  the recorder and keep request objects alive.
- **Call site:** the first stack frame, read with `StackWalker`, outside `com.rey.modelquery`, the JDK's reflection
  and proxy frames, and `org.springframework`. It labels the replay (`OrderService.search:142 page`), so a failure
  points at the application's code. The recorder is shared, not thread-local, so calls on server threads (a
  `RANDOM_PORT` test) are recorded too.
- **Transaction state:** the three-argument `wrap` records `em.isJoinedToTransaction()` per invocation, and replay
  runs the call in or outside a transaction to match (R-INS-11's `callOutsideTransaction`). With the two-argument
  `wrap`, a definition with `commitEachChunk()` replays outside one and every other call inside one.
- **Deduplication at record time:** the same root, method, call site and equal arguments are one record with `times`
  incremented. Arguments compare with `equals`; `ModelQuery` and `KeysetSpec` have no value equality, so a query built
  per request is a record per invocation. No structural key is invented: one too coarse would silently skip a distinct
  SQL shape. Instead the report collapses identical result lines (call site, method, target, outcome, SQL) after
  replay, with a count.
- **Loud failures.** Past `maxCalls`, invocations are counted, not kept. A recording that fails (an argument it cannot
  copy, a call site it cannot read) never fails the application's call, and is counted. Either count is a report line
  that fails the check, so a truncated or partial recording never passes.

**Spring (R-SPR-18).** `model-query-spring-data` gains a decorator SPI:

```java
@Incubating
public interface ModelQueryExecutorDecorator {
    <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context);
}

@Incubating
public interface ExecutorContext<E> {
    Class<E> rootEntity();
    EntityManager entityManager();               // the shared, transaction-bound EntityManager
    EntityManagerFactory entityManagerFactory();
    ModelQueryConfig config();                   // after every ModelQueryConfigurer
}
```

- `ModelQueryRepositoryFragmentFactoryBean` applies every decorator bean, in `@Order` order, inside the executor's
  `SingletonSupplier`, resolving them lazily through `ObjectProvider.orderedStream()`. A `LAZY` or `DEFERRED` bootstrap
  stays lazy (D-139), and fragment creation looks up no bean (D-133's cycle concern).
- A decorator must delegate every call and rethrow the delegate's throwable unchanged, so Spring's exception
  translation and the application see what they see without it.
- The method is generic, so a lambda cannot implement it (JLS 15.27.3); the interface is not `@FunctionalInterface`.
  The generic signature means a decorator can't return an executor for a different root (P-2).
- Only repository executors are decorated. An executor the application creates with `ModelQueryExecutor.create` is
  wrapped by the application itself; the docs and the report say so.
- The SPI has uses beyond recording (metrics, tracing). It records at the executor, after `SpringPaging` has
  translated the `Pageable`, so replay never touches the repository layer and no package-private Spring type becomes
  public.

There is no starter property and no dependency from the starter on `model-query-portability` (INV-7: no module
depends on it). A test registers the recorder itself:

```java
@TestConfiguration
class RecordCalls {
    @Bean CallRecorder recorder() { return CallRecorder.create(); }

    @Bean ModelQueryExecutorDecorator recording(CallRecorder recorder) {
        return new ModelQueryExecutorDecorator() {
            @Override
            public <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context) {
                return recorder.wrap(context.rootEntity(), executor, context.entityManager());
            }
        };
    }
}
```

**Replay (R-INS-15).** `PortabilityCheck.replay(Collection<RecordedCall> calls)` adds each recorded call as a `call`
that invokes the recorded method with the recorded arguments, function markers replaced, on the harness's executor
(Part A's proxy included), labelled with its call site, method and `xN` when `times > 1`.

- **Roots outside the unit fail** unless left out explicitly with `leaveOut(Class<?> root, String reason)`, whose
  reason is required, as `skip`'s is (R-INS-12). An application with two units writes two checks, each leaving out the
  other unit's roots. A root managed by two units is checked in both.
- **Replay fidelity** joins R-INS-12's limits list. Replay reruns the call, not the request, so it does not reproduce:
  a `QueryCustomizer` or config-level customizer reading thread-local state (a tenant, a security context) at build
  time; Hibernate session filters or a tenant id on the production `EntityManager`; a per-factory config from a
  `ModelQueryConfigurer` that differs from the check's; row models mutated after the call.

**A check from the booted unit (R-INS-16).** `PortabilityCheck.of(PersistenceUnitInfo unit, ModelQueryConfig
config)`, a `jakarta.persistence.spi` type (INV-7), takes the managed class names (converters included) and the
mapping files the application booted with. A Spring test gets it from `EntityManagerFactoryInfo
.getPersistenceUnitInfo()`, and the properties from `emf.getProperties()` through the existing `properties(Map)`.

- Values R-INS-10's cache can't compare (an instance, such as a naming-strategy bean) are dropped, and the report's
  head lists each dropped key, so a check that boots with a different naming strategy says so.
- **Connection keys.** Properties taken from a running factory may name a connection provider or a multi-tenant
  connection provider, which the D-133 spike found bypasses the stub. R-VND-20's `offlineProperties` gains the
  provider's connection keys, blanked, so the stub stays the only connection whatever the properties say.
- R-INS-10's Boot guidance changes: `spring.jpa.properties` does not carry Boot's naming defaults, so it names
  `emf.getProperties()` instead.
- No new `ProviderSupport` method: fidelity alone doesn't justify one (P-5, as D-138 rejected a translate SPI).
- A spike item: whether `emf.getProperties()` on both lines includes the naming strategies Boot sets by default.

### Part C — child statements on placeholder keys (spike first)

`childrenOf(load, q, keys)` in `DefaultModelQueryExecutor` runs one child load from a key list. The harness, for a
`REACHED` call whose query carries a fetch plan, runs each child load depth first, on its own recording, and reports
each reached statement under the call: `label child tiers on MYSQL 8.4.3: REACHED select ...`.

- **Two placeholder keys**, distinct and of the parent key's Java type (`0` and `1`, `"a"` and `"b"`, two dates, two
  UUIDs; a composite key's components each so), so the `IN` list has production's multi-key shape, including tuple
  `IN` versus its expanded `OR` form. A child's own children use placeholders of the child's key in turn.
- A key type with no placeholder (a value object behind a converter, an `@IdClass` the harness can't build, an empty
  enum, `byte[]`) gives a failing line naming the type, not a skip.
- **Key groups** can't be enumerated: `KeyGroups.by(keyType, function)` maps a key and its owner to a group at run
  time. The harness renders the catch-all's values where one exists; without one, it asks the placeholder's group with
  no owner, and a failure there is a failing line naming the child. Under bound values every group's statement has the
  same text, so one round is the shape.
- **The engine entry** is a not-API `jpa` method running one child load of a plan, at a path, on given keys, in the
  space D-139 allow-lists for the harness. INV-7's parenthetical, D-139's allow-list and the ArchUnit rule are
  amended. It is not `@EngineFacing`, which means "only an executor uses" (D-72, D-86). Per-call state stays in
  `ChildRounds` (INV-1, INV-9).

**Spike questions:** (1) whether every child load's statement can be reached on placeholder keys without the parent's
`Loaded` rows, including a child under a `@Join` nested model, a child of a source-rooted model, and a key-grouped
child with and without a catch-all; (2) the stub throws at `prepareStatement`, before any bind, so a placeholder needs
only the key's Java type; whether that holds under `value_handling_mode=inline` (D-177), where the key renders as a
literal; (3) whether a refusal the child stage raises today (`MQ2003`, a key group's `MQ1502`) is reported on the
child's line or must stay on the limits list as data-dependent. A shape that fails (1) stays on the limits list by
name.

What Part C still cannot see, and R-INS-12 keeps listing: data-dependent refusals (`MQ2201`, `MQ2202`, `MQ2003` on
real data, `MQ2005`, ...), step 2 of primary-key-first paging, export pages after the first, and every round of a
chunked or key-first write.

### Part D — smaller items

- **Unversioned targets.** The report's head prints, once, each target given without a version:
  `MYSQL checked at the dialect minimum 8.0; name the deployed version with Target.of(MYSQL, "8.4.3")`. No new
  property: a target version is the test's choice, not production configuration.
- **Log lines.** The portability docs page explains that Hibernate logs each stub stop through `SqlExceptionHelper` at
  `ERROR`, with a Logback and a Log4j2 snippet that mutes that logger in tests. The harness changes no logger level: it
  has no portable way to, and the provider's logger would need an `org.hibernate`-aware path.
- **JUL reset.** The logging page gets a troubleshooting note: MockServer 5.x resets the JUL configuration, so set
  `java.util.logging.config.file` before MockServer starts, or route `System.Logger` through SLF4J.

## New ids

| Id | What |
|---|---|
| R-INS-14 | `CallRecorder`, `RecordedCall`: recording, function markers, call site, transaction state, dedup, bound, loud failures |
| R-INS-15 | `PortabilityCheck.replay(...)`, `leaveOut(...)`, replay fidelity on the limits list |
| R-INS-16 | `PortabilityCheck.of(PersistenceUnitInfo, ModelQueryConfig)`, dropped keys, blanked connection keys |
| R-INS-17 | Child statements on placeholder keys (Part C, after its spike) |
| R-SPR-18 | `ModelQueryExecutorDecorator`, `ExecutorContext` |
| AC-INS-13 | Part A on both lines: a `COUNT` page reports `[count]` and `[content]`, the content SQL matching the TCK's snapshot of `COUNT`'s content statement per vendor and line; `[keys]` under `primaryKeyFirst`; `INLINE_COUNT` with the window one `[content+count]` line, with a fallback two; `ONLY_COUNT` one; a swallowed earlier stop gets no second pass |
| AC-INS-14 | Part B: recording through a Spring repository and a wrapped executor, overloads told apart, function markers replaced at replay, dedup, the bound's and a failed recording's failing lines, transaction state replayed, a root outside the unit failing unless left out |
| AC-INS-15 | `of(PersistenceUnitInfo)` from a Boot-configured unit renders as `of(classList)` with the same properties; a dropped instance value is listed |
| AC-INS-16 | A unit naming a multi-tenant connection provider reaches only the stub |
| AC-INS-17 | Part C: a three-level fetch plan and a key-grouped child report each child statement on each Tier-1 target; an unsupported key type fails by name |
| AC-SPR-22 | Decorators apply lazily, in order, to every repository's executor and rethrow unchanged; a `LAZY` bootstrap stays lazy |

No new `MQnnnn` code: the harness's own problems are `AssertionError`s (R-INS-12) or `HARNESS_ERROR`.

## Compatibility

Additive. Every new type and method is `@Incubating`, like the rest of `model-query-portability` (D-133).
`ModelQueryExecutorDecorator` and `ExecutorContext` are new public interfaces in `model-query-spring-data`, placed by
the freeze review with the rest. A check that passes today can fail after Part A or Part C, by design: it now sees
statements it did not. The report changes shape: a call can give several lines (`[count]`, `[content]`, `child
<name>`), so AC-INS-12's "one line per call and target" and R-INS-11's "one recording per call and target" are
amended; the head gains lines for unversioned targets, dropped properties and recording counts.

## Alternatives

- **Do nothing.** Adopters write `call`s by hand and widen production visibility for them, and the check keeps
  missing the content select of every `COUNT` page, the most common read in a report API.
- **A public `ModelQueryRepository` factory over an executor**, so a replay can go through the repository. It makes a
  test-only construction path part of the repository's frozen surface, and recording at the repository still needs
  the `Pageable` translation at replay. Recording at the executor needs neither.
- **A starter property (`modelquery.portability.record`)** that registers the recorder. It needs a starter dependency
  on `model-query-portability`, which INV-7 forbids, and couples production configuration to a test tool. A
  `@TestConfiguration` of a few lines does it.
- **An observe-only listener** instead of a decorator. It is narrower, and widening later is additive. It lost because
  recording without Spring needs a plain wrap anyway, and a listener needs its own wiring in `jpa`.
- **Replaying the application's functions.** They capture request state and rerun side effects on the test's
  database; they can't change what the stub renders.
- **A structural dedup key** for queries without value equality. One too coarse silently skips a SQL shape; collapsing
  identical report lines is safe.
- **`of(EntityManagerFactory)` from the metamodel.** `getManagedTypes()` misses auto-applied converters, package-level
  annotations and mapping files; `PersistenceUnitInfo` carries them.
- **A synthetic-row dry run:** the stub answers each select with one made-up row so the executor goes on to its child
  loads and later rounds. It follows one data path only; the stub would have to produce a value every column read
  accepts (converters, enums, embeddables, UUID and JSON types) on every line; export and key-first loops would need a
  round cap; and since D-138 the library's vendor-dependent refusals already run before the first statement, so it
  adds little over Part C.
- **`@EngineFacing` for the harness's entries.** That annotation means "only an executor uses"; D-139's allow-list is
  the place for the harness.
- **`modelquery.target-versions` in configuration.** It couples production configuration to a test tool; the report
  note gives the nudge without it.

## Unresolved questions

None left open: the spike answers Part C's three questions and the naming-strategy item in R-INS-16, and a shape that
fails stays on the limits list by name.

## Milestone

Proposed: M37 in 0.14.0, the freeze moving to M38 (as D-213 moved it to M37). Slices, roughly: M37.1 Part A; M37.2
`CallRecorder`, `RecordedCall` and replay; M37.3 the Spring decorator; M37.4 `of(PersistenceUnitInfo)` and Part D;
M37.5 Part C after its spike. Parts A, B and D can ship without Part C if the spike says no.
