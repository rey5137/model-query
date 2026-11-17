# RFC 0026 — Select audit: unselected reads and unread columns, recorded while the tests run

- **Status:** draft (architect review applied)
- **Affects:**
  - **`api/16`:** new R-INS-18 (the hand-off observer and tagging), R-INS-19 (recording reads), R-INS-20 (failing an
    unselected read), R-INS-21 (the report), R-INS-22 (setup and the missing agent); new AC-INS-18 to AC-INS-24.
  - **`api/11`**, **`core`:** one `@EngineFacing` read of the `Row` behind a returned row of `mapReturning`
    (`ModelQuery.mapReturning` never exposes it today).
  - **`SPEC.md` INV-7:** a new clause: `model-query-select-audit` on `jpa` only, implementing one not-API `jpa.vendor`
    service type at `jpa`'s exact version; no module depends on it. Not an entry in D-139's allow-list, which lists
    what `model-query-portability` may call.
  - **`delivery/61`:** the module tree and the BOM gain `model-query-select-audit`.
  - **`docs/plan/mvp-plan.md`:** this is M38; the freeze moves to M39.
  - **`reference/92`:** D-228 to D-232 at acceptance.
  - **`docs/site`:** a "Select audit" page under testing, with Maven and Gradle tabs for the agent line.
- **Discussion:** TBD

## Summary

A model is only as complete as its select set. An unselected record component reads `null` and an unselected class
field keeps its initialiser (R-GEN-06, R-GEN-09), so code that reads a column the query did not select gets a silent
wrong value, often several method calls away from the query. The opposite mistake, a select set wider than any reader
needs, costs joins and bytes without failing anything. This RFC proposes a test-scope **select audit**, recorded the
way RFC 0025 records portability calls: the application's existing test suite runs unchanged, and the audit

- **fails the read** of an unselected column, at the line that reads it, naming the query call that built the model;
- **reports** each query call site's selected columns that no test ever read.

Setup is one test dependency and one `-javaagent` line. No test code changes.

## Motivation

An adopter asked for a way to enforce select sets. What they hit:

1. **The read is far from the query.** A service queries `OrderView` with a narrow set, passes the list to a mapper,
   which passes one row to a mail composer, which reads `customerEmail()`. The query was right for the screen it was
   written for; the composer was added later. Nothing fails: the mail goes out with an empty address line.
2. **Declaring requirements at the query does not reach the reader.** A `requires(SelectSet)` on the query (considered
   in Alternatives) checks what the query's author remembered, not what the downstream code reads.
3. **`@Selected` (D-120) needs every reader to check.** It tells an unselected column from a `NULL` one, but only for
   code that asks, and the bug is precisely the code that does not.
4. **Over-wide sets are invisible.** `DEFAULT` is the easy choice, and a report query selecting thirty columns to
   print five is found only by reading the SQL.

## Design

### Part A — tagging at hand-off

**The observer (R-INS-18).** `jpa.vendor` gains a not-API service type, loaded once per class loader in a static lazy
holder through `ServiceLoader` (at most one provider, guarded as `VendorResolver.loadable` is), with a no-op default.
The lookup touches no `EntityManagerFactory` and never throws while an executor is built, so lazy and deferred
bootstraps stay lazy (D-133, D-139).

**Where it is called.** The model a caller receives is not always the one `BuiltQuery.map` built: a fetch plan's
`runJoin`/`loadChild` replace a record through the generated `with` (R-GEN-25, R-GEN-26), and enrichers return new
instances by position (R-FCH-08). So the executor calls the observer from one private hand-off helper, **after** the
fetch plan and enrichers have run, with `rows[i]` for `result[i]` (enrichers keep positions). Every path that returns
models goes through it: `models()` (`list`, `page`, `one`, `first`, `byKeys`, `export`), the flat-row path, `stream`'s
per-element mapping, and the `*Returning` writes and `persist(…, returning)`, whose `Row` the executor reads through
the new `@EngineFacing` `mapReturning` read. Reads made by the engine before hand-off (the mapper, `with`, enrichers) are therefore never
attributed to a tagged model.

**The call site** is captured **once**, when the outermost `ModelQueryExecutor` method is entered, under R-INS-14's
rule in full: the first frame outside `com.rey.modelquery`, the JDK's reflection, proxy and `java.util.stream` frames,
`org.springframework`, classes implementing `ModelQueryExecutor` or `InvocationHandler` (an application's own
decorator, D-225), and generated Q classes. Each model of that call shares one tag holding the call site, the method,
the query's `select()`, and a selection bit set shared per (query selection, model class). A tag never references its
model or its `Row`. Calls whose stack contains `com.rey.modelquery.portability` (a `PortabilityCheck` replay, R-INS-15)
are not tagged.

**Which properties are selected.** The audit reads `Q<M>.fields()` (D-121, R-GEN-32), which holds every field the
mapper reads keyed by property path (`join.field` at any depth) and every child, derived from the constants
themselves, so scoped join constants resolve by path (AC-GEN-42). It finds `Q<M>` through `MAPPER`'s nest host (the
`Q::map` method reference is a hidden nestmate of `Q<M>`), which also covers bound models whose Q class lives in the
binding's package (R-GEN-35). A generated model whose Q class cannot be found fails loudly, never skips. No processor
change.

- A **column property** is selected when `Row.isSelected` accepts its constant; engine-added keys count, since the
  model holds them.
- A **`@Join` property** is selected when its presence key is (R-GEN-15). The nested model is tagged from the scoped
  row; an empty `Optional` tags nothing.
- A **`@Child` property** is selected only when the query's fetch plan loads it; reading an unloaded `@Child` is an
  unselected read. Child models are tagged inside the child load, after the child plan runs, from the child query's
  own rows, and reported under the parent's call site as `child LINES select …`.

### Part B — reads

**The read hooks (R-INS-19).** The agent hooks each model class as it loads, never later (D-232):

- **Which classes.** A class is a model when its class file carries a model-query annotation on the type, a field, a
  method or a record component. The annotations are `CLASS` retention, so the agent recognises a model from the class
  file it is given (Byte Buddy's pool-based type description), never by reflection, which cannot see them.
- **A later re-instrument keeps the hook.** One `AgentBuilder` with `RedefinitionStrategy.RETRANSFORMATION`,
  `TypeStrategy.DECORATE` and `disableClassFormatChanges()` (method body changes only). Its transformer is
  retransform-capable, so when another agent (Mockito's inline mock maker, a coverage agent) re-instruments a model,
  the hook is applied again. HotSpot then hands the chain its cached copy of the original class file, not a class file
  rebuilt from the loaded class. That is also what avoids a JDK 17 and 21 defect: the rebuilt class file of a record
  whose component carries a `CLASS`-retention annotation is malformed and rejected with `ClassFormatError`, with or
  without the audit. The cache is HotSpot behaviour, not a JVMTI guarantee, and covers only classes the agent changed
  at load. It costs one copy of each hooked model's class file in native memory.
- **Superclasses.** When a model is recognised, its superclasses are hooked too: one not yet loaded is hooked as it
  loads, one already loaded is retransformed (safe on JDK 17 and 21, since a superclass is never a record). The
  retransform runs on a helper thread the transformer waits on, since it cannot run inside a class load. The
  processor maps only a model's own `@Column` fields, so an inherited field is never selected. A read through an
  inherited getter of a superclass field is therefore a warning ("read of an unmapped inherited field"), never a
  failure, since the base class may set the field itself.
- **Always on, a no-op when untagged.** Every accessor of a model is hooked from the start; the advice returns at once
  for an instance the tag map does not know. The spike measured about 0.6 ns per call when nothing is tagged and about
  2 ns per untagged call once some instances are.

It hooks:

- record accessors and class getters (`getX`/`isX`) of a `fields()` property;
- field reads inside the model's own methods, attributed to the first frame outside the model class, so a computed
  `fullName()` reading an unselected `lastName` is reported at `fullName()`'s caller. Methods returning the model's own
  type (withers) are not hooked.

Not counted:

- reads while one of the model's own `equals`, `hashCode` or `toString` is on the stack (a thread-local depth
  counter, raised by entry advice and lowered by exit advice that also runs on a throw), so Lombok's getter-calling
  `equals` under `assertThat(list).containsExactly(...)` does not fail;
- a read of the `@Selected` field;
- reads on an untagged instance: one built by `new`, a user wither's copy, or a model from a user's own
  `BuiltQuery.map(Tuple)` or custom executor.

Records' `equals`, `hashCode` and `toString` read fields through `ObjectMethods` method handles, which the hooks never
see. The tags live in a weak identity map (records compare by value), written for the audit: the JDK has none.

**A missing hook is reported, not silent.** The agent records, per class (by `Class`, not by name), that a model was
hooked, and marks it lost when a later re-instrument of it is ignored or fails. At hand-off the observer checks each
tagged model's class; a model whose class was never hooked or lost its hook is listed in the report under "MODELS NOT
OBSERVED (hook missing)" instead of showing zero reads. That covers:

- a class loaded before the agent (an earlier agent's `premain`, or a dynamic attach): no cached class file, and the
  rebuilt one has no `CLASS`-retention annotations (malformed for an annotated record on JDK 17 and 21);
- an agent registered before this one that strips the annotations, so a re-instrument no longer recognises the model;
- an error in the audit's own transformer.

An agent registered after this one that rewrites an accessor and drops the hook cannot be detected; the docs say so.

**Mapping-time reads.** `Row.get` and `Row.raw` calls made by `afterMap` or a `finisher` (R-QRY-05) while a model is
mapped count as reads of those columns, so a column selected only to compute another is not reported unread. They
never fail.

### Part C — failing and reporting

**An unselected read (R-INS-20).** By default a read of an unselected property throws `UnselectedColumnRead`, an
`AssertionError`, at the read, so the test that ran it fails with the stack trace:

```
UnselectedColumnRead: OrderView.customerEmail was not selected
  read at   InvoiceMailer.compose(InvoiceMailer.java:57)
  model of  OrderService.search(OrderService.java:142) list, select INVOICE_SET [id, status, total, customerName]
  add OrderView.CUSTOMER_EMAIL to the select set, or ignore this reader (modelquery.selectAudit.ignore)
```

An `Error`, not an exception, so an application's `catch (Exception e)` does not swallow it. Every unselected read is
also in the report: one on a thread no test joins (an `@Async` method, a pool whose future is dropped) is labelled
"may not have failed a test", since nothing guarantees it did.

- **Serializers warn, never fail.** A read is classified by its first frame after skipping JDK, hidden and generated
  frames (Jackson Blackbird and Afterburner generate accessors). A read from a serializer package (Jackson 2 and 3,
  Gson, JSON-B by default) is a warning: a `?fields=` partial response (R-COL-23) reads unselected getters by design,
  and `@JsonInclude(NON_NULL)` drops them. A serializer read of a selected column counts as a read.
- **Ignoring a reader.** `modelquery.selectAudit.ignore` lists package prefixes or `Class#method` frames whose reads
  neither fail nor count: code that reads a column and handles its `null` on purpose, such as a test asserting that an
  unselected value is `null`. A read guarded by `selected().contains(...)` needs no entry, since it runs only when the
  column was selected.
- **`modelquery.selectAudit.failOn`**: `unselected` (default) or `none` (report only).

**The report (R-INS-21).** A JUnit `LauncherSessionListener`, registered through `ServiceLoader`, writes the report
when the session closes, to `target/model-query/select-audit-<fork>.txt` (the directory is
`modelquery.selectAudit.dir`), and logs its summary:

```
UNSELECTED READS (3)
  OrderView.customerEmail  read at InvoiceMailer.compose:57  model of OrderService.search:142 list x4
  OrderView.total          read at ReportJob.run:88 (may not have failed a test)  model of ReportService.load:31 stream
SERIALIZER READS OF UNSELECTED COLUMNS (warning)
  OrderView.internalNote   read by Jackson  model of OrderController.list:40 page x12
UNREAD COLUMNS (warning)
  OrderService.search:142 list, select INVOICE_SET: OrderView.createdBy, OrderView.updatedAt
    never read in 37 calls across 12 tests
  OrderService.search:142 child LINES select LINE_SET: LineView.sku
READS OF UNMAPPED INHERITED FIELDS (warning)
  OrderView.auditedBy (BaseView.getAuditedBy)  read at AuditLog.write:19  model of OrderService.search:142 list
MODELS NOT OBSERVED (hook missing)
  LegacyView  model of LegacyService.load:12 list x3: loaded before the agent; reads not recorded
```

- **Unread columns** are counted per query call site and select set (`SelectSet` has value equality, D-120): a column
  of the query's own `select()` that no read of any model from that call site, and no mapping-time read, touched.
  Engine-added keys are not listed; the caller did not ask for them.
- Unread is a warning, never a failure: "no test observed it" is only as good as the tests' coverage.
- **Forks.** Each fork writes its own file. `SelectAuditReport.main(dir)` merges a directory's files into one report,
  unioning reads per call site, for a build with `forkCount > 1`. Surefire's default (one reused fork) needs no merge.

### Part D — setup

**Setup (R-INS-22).** `model-query-select-audit` depends on `jpa` at its exact version and on Byte Buddy, shaded and
relocated so it never clashes with Mockito's (the docs note that a newer JDK may need a newer audit release, since the
shaded Byte Buddy cannot be bumped by the user). The jar is its own agent:

```xml
<dependency>
  <groupId>com.rey.modelquery</groupId>
  <artifactId>model-query-select-audit</artifactId>
  <scope>test</scope>
</dependency>

<!-- maven-dependency-plugin:properties sets the jar's path, as Mockito documents for its agent -->
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>@{argLine} -javaagent:${com.rey.modelquery:model-query-select-audit:jar}</argLine>
  </configuration>
</plugin>
```

- **A `-javaagent` at startup, not a self-attach.** JDK 21 warns on an agent loaded at run time (JEP 451) and a later
  JDK may refuse it. A later attach would also miss every model class already loaded, since models are hooked only as
  they load.
- **With Mockito.** Either agent line may come first. With the audit attached, a Mockito spy or mock of an annotated
  record works on JDK 17 and 21 (it re-instruments from the cached class file). Without it, Mockito 5.23 fails with
  `ClassFormatError` on the first such spy or mock, and a later mock of the same record silently returns an object
  that is not a mock, since Mockito keeps the class marked as instrumented after an `Error`.
- **A missing agent fails the session.** With the jar on the classpath and no agent attached, the
  `LauncherSessionListener` fails the JUnit session when it opens, naming the `argLine` to add. A silent no-op would
  pass a suite it never audited. Outside a JUnit session (the jar shipped at runtime scope by mistake) the observer
  logs one warning and does nothing, so production never throws. No `MQ` code: the failure is a test-session failure.
- **Tests only.** The `jpa` default is a no-op; the docs say not to ship the jar.
- No Spring bean and no test code: the observer is found by `ServiceLoader` and the listener by JUnit, so a Spring
  test, a plain JUnit test and an executor from `ModelQueryExecutor.create` are audited alike.

### What this does not see

- A read on a branch no test runs (as any coverage tool).
- Models built outside an executor hand-off: `new`, a user wither's copy, a user's own `BuiltQuery.map(Tuple)`, a
  custom `ModelQueryExecutor` implementation.
- A public field read directly from another class: that would need the reading class transformed.
- Reads of a model whose hook another agent registered after this one removed (Part B).
- A column read and then thrown away counts as read; whether its value mattered is out of scope (Alternatives:
  mutation runs).
- Reflection-based readers other than the listed serializers. A generated bean mapper such as MapStruct makes ordinary
  accessor calls and is seen.

## New ids

| Id | What |
|---|---|
| R-INS-18 | The hand-off observer, the call site captured once per outermost call, selection from `fields()`, `@Join` and `@Child` selection |
| R-INS-19 | Read hooks; own `Object` methods, withers and untagged instances not counted; mapping-time `Row` reads |
| R-INS-20 | `UnselectedColumnRead` at the read; serializer warnings; `ignore`; `failOn` |
| R-INS-21 | The report: unselected reads, serializer reads, unread columns per call site and child; fork files and the merge |
| R-INS-22 | The module, the shaded agent, the session failure on a missing agent, the runtime-scope no-op |
| AC-INS-18 | Against H2, on both Hibernate lines: a record and a class model queried with a partial set, passed through two methods, fail the test at the reading line with the call site; a selected `NULL` column read passes; Lombok `equals` in an AssertJ assertion does not fail |
| AC-INS-19 | A fetch plan's `with` copy and an enricher's instance are the tagged models, and the engine's own reads are not counted; a column read only by `afterMap` through the `Row` is not reported unread |
| AC-INS-20 | A `@Join` is selected by its presence key, an empty `Optional` tags nothing; a `@Child` loaded by the plan is tagged from the child query's rows and reported as `child`; reading an unloaded `@Child` fails |
| AC-INS-21 | A Jackson serialization (plain and with Blackbird) of a partial model warns and does not fail; an `ignore`d reader neither fails nor counts |
| AC-INS-22 | The report lists unread selected columns per call site and select set, excludes engine-added keys and portability replays, and two forks' files merge to the union |
| AC-INS-23 | Every `ModelQueryExecutor` method returning models, enumerated by reflection, tags its result; a `stream` call and a call through an application decorator get the application's call site; a read on an unjoined pool thread is labelled |
| AC-INS-24 | The jar without the agent fails the JUnit session when it opens; outside JUnit it logs once and changes no SQL and no result; the `jpa` default changes neither |

## Compatibility

Additive. The new module and its types are `@Incubating`. No processor change and no generated-source change. The
`jpa` observer and the `mapReturning` read are not API, and the observer is a no-op without the audit jar.

## Alternatives

- **Do nothing; `@Selected` and code review.** Leaves the bug class in the motivation silent.
- **`requires(SelectSet)` on the query.** Checks the query author's list, not the readers', so it misses exactly the
  read three calls away. Cheap, and could be added later on its own.
- **A generated property table per Q class.** Duplicates `fields()`, adds class-initialisation work in production for
  a test tool, reopens R-GEN-26/R-GEN-29's initialisation-order hazards and changes every golden file (AC-GEN-30).
- **Hooking `BuiltQuery.map` in `core`.** Tags a model a fetch plan or enricher then replaces, so the caller's instance
  goes unaudited and `with`'s own reads fail.
- **Strict accessors that throw on an unselected column, in production.** Records' accessors cannot be overridden by
  generated code, and a production throw on a partial `?fields=` response is the wrong default.
- **Mutation runs: rerun each path with one column dropped, and once with every column selected.** Finds a column
  read but unused, which the read audit cannot. Rejected as the default: 2 + N runs per path, a captured output per
  test, and repeatable paths. It can follow as an opt-in mode built on the same tagging.
- **Replay as RFC 0025 does.** Replaying a call reruns the query, not the downstream code that reads the model, which
  is where the bug is (RFC 0025 rejected replaying application functions for the same reason).
- **A static-analysis plugin (Error Prone).** Precise within a method, blind once a model crosses a method or service
  boundary, which is the motivating case.
- **Putting it in `model-query-test`.** That module depends on `core` only (R-INS-06, INV-7), and the hand-off is in
  `jpa`, so it would bring `jpa` and Byte Buddy onto every assertion user's classpath.
- **Self-attaching the agent.** Removes the `argLine`, but warns on JDK 21+ and is slated to be refused.
- **An `MQ` exception on a missing agent.** An application's `catch (Exception)` can swallow it, and a jar shipped at
  runtime scope by mistake would throw in production.

## Unresolved questions

None. The acceptance spike checks:

- hooking a record's accessors, and an inherited getter's superclass, on both supported JDKs, including after
  another agent (Mockito, JaCoCo) re-instruments the model;
- finding `Q<M>` through `MAPPER`'s nest host for a plain, a bound (R-GEN-35) and a source model; any `fields()` key
  that is not a Java property name is fixed in `FieldIndex`;
- the observer's cost on a 10,000-row `export` (target: under 2x the unaudited run);
- that every executor method returning models reaches the hand-off helper once per model.

## Spike results

Module `spike-rfc0026` (profile `spike-rfc0026`), test-only; nothing in a production module changed.

Each check is a JUnit class, run with `./mvnw -q verify -P spike-rfc0026 -pl spike-rfc0026 -am -Dtest='Check*'
-Dsurefire.failIfNoSpecifiedTests=false` on JDK 17.0.16, 21.0.8 and 25.0.1, all green (62 tests each; checks 6 to
10 run in their own surefire executions with the JVM options they need). Checks 1 to 4 tried hooking a model when it
is first tagged (retransforming it then); checks 6 to 10 led to D-232's load-time hooks instead.

- **Check 1, read hooks (pass on 17, 21 and 25, with one fallback; superseded by checks 6 to 10).** A `-javaagent`
  jar built in the same module (`maven-jar-plugin` at `process-test-classes`, surefire `argLine -javaagent:`; Byte
  Buddy 1.18.15, `AgentBuilder` with `RETRANSFORMATION`, `disableClassFormatChanges()` and `TypeStrategy.DECORATE`). A record's accessor is hooked by
  retransforming the loaded class, and a read on an instance created before the hook is reported; an inherited getter
  is matched only once its declaring superclass is retransformed, as the RFC says. A record's `equals`, `hashCode` and
  `toString` enter no hook (`ObjectMethods`); a Lombok-style `equals` calling getters reports reads until guarded and
  none once the depth counter guards it, which needs exit advice as well as entry advice. On JDK 17 and 21,
  retransforming a record whose component carries a `CLASS`-retention annotation (every model's `@PrimaryKey`,
  `@Join`, `@Child`, `@Column`, or a `TYPE_USE` one on the component type) fails with `ClassFormatError`: the JVM
  rejects the class file it rebuilds for retransformation, before Byte Buddy changes anything. JDK 25 retransforms it.
  A fallback (`redefineClasses` from the class file read through the model's loader) worked alone but not with JaCoCo
  (check 6), so D-232 drops it. Byte Buddy 1.18.15
  still prints the JDK 25 `sun.misc.Unsafe::objectFieldOffset` warning (`ClassInjector.UsingUnsafe`), as 1.17.8 did.
  Field reads inside a model's own methods (an ASM visitor inserting a call before each `GETFIELD` of a property, with
  `COMPUTE_MAXS`) work on all three JDKs: `fullName()` reading an unselected `last` is reported at `fullName()`'s
  caller, while a wither and the accessor's own `GETFIELD` are not hooked. The `@Join` trap is real: an observer that
  reads `order.customer()` after tagging `order` counts as an unselected read. Reading the nested model before
  tagging the parent, or reading it under the depth counter, avoids the trap; M38.1 reads before tagging.
- **Check 2, `Q<M>` through `MAPPER` (pass on 17, 21 and 25).** For a plain model with a nested `@Join`, a `@Child` and
  a `@FilterColumn`, a grouped model, a source-rooted model and a bound model (Q class in the binding's package),
  `MAPPER` is a `public static` field of `Q<M>`. Its value is a hidden, synthetic lambda class, and its
  `getNestHost()` is `Q<M>` on every JDK, so no fallback is needed. A `@SourceModel`'s own Q class has `fields()` but
  no `MAPPER`. That needs no lookup, because the models returned are rooted at the source. Every key in `names()` and
  `filterNames()` is a valid Java property path, so `FieldIndex` needs no fix. `names()` also holds keys that are not
  model properties: the set names `ALL` and `DEFAULT`, and the join-set keys (`customer`, `customer.region`), which
  coincide with the `@Join` property names. `filterNames()` adds filter-only paths (`customer.country`). So the audit
  takes a column property from `select(name)`, a `@Join` from its join-set key, and a `@Child` from `child(name)`. It
  never iterates `names()` as a property list.
- **Check 3, cost (pass, about 1.2-1.4x).** H2 in memory, 10,000-row offset `export` of an eight-column record, five
  warm-up runs each, then seven alternating runs, median. Audited means the record's eight accessors are hooked, and
  every model is tagged in a weak identity map from the page transformer, which receives exactly what `models()`
  returns. That is the emulated hand-off: one tag per call, a shared bit set, no call-site walk. The sink reads each
  accessor once, 80,000 checked reads. Medians, unaudited against audited: JDK 17, 7.7 ms against 10.4 ms (1.36x);
  JDK 21, 6.9 against 8.8 ms (1.28x); JDK 25, 7.4 against 9.1 ms (1.23x). This is about 0.2-0.3 µs per model.
  Against an in-memory database it is the worst case; a networked database shrinks the ratio. One later run on JDK 17
  measured 2.14x against the test's 2.0x limit; the timing test is noisy on a loaded machine, and M38's cost test
  should take more runs or a wider margin.
- **Check 4, one hand-off per model (pass on 17, 21 and 25).** Exit advice on the production classes, applied in the
  test JVM only, records what six private points return. In each call below, every returned model passed exactly one
  point exactly once:
  - `DefaultModelQueryExecutor.models`: `list` with a plan, `one(q)`, `first`, `one(q, key)`, `byKeys`, `page`
    with `COUNT` and `NO_COUNT`, and `export`.
  - `mapFlat`: `list` without a plan.
  - `BuiltQuery.map(Tuple)`: `stream`, through `tuples.map(built::map)`.
  - `ChildRounds.children`: the 15 child models.
  - `Returning.models`: `updateReturning` on H2.
  - `persist(…, returning)`: not run, found by reading only.

  With a child load or an enricher in the plan, none of the returned models is the mapper's own instance, so the
  hand-off must follow the plan, as D-228 says. `models()` already has `rows[i]` (`Loaded.row()`), and positions hold
  because `Enricher.enrich` refuses a resized page (MQ2602), so M38.1 unifies six points. Two of them have no `Row`
  today: `stream` maps through `built::map`, and the returning reads go through `mapReturning` over an `Object[]` or a
  managed entity.
- **Check 5, field reads in a model's own methods and the `@Join` trap (pass on 17, 21 and 25).** As described under
  check 1.
- **Check 6, hooks at load time (pass on 17, 21 and 25).** Model classes are recognised from their class file and
  hooked as they load, with no retransform; detection from the bytes agreed with a raw class-file scan. With JaCoCo's
  agent attached first, a load-time hook keeps JaCoCo's probes and coverage, while the check 1 fallback fails on JDK
  17 and 21 (`UnsupportedOperationException: attempted to delete a method`), which rules out hooking at tag time.
  Always-on hooks cost about 0.6 ns per call with nothing tagged and about 2 ns per untagged call once some
  instances are tagged; the 10,000-row export measured 1.0 to 1.35x.
- **Check 7, getters inherited from a superclass without model-query annotations (pass on 17, 21 and 25).** With no
  superclass hooking, the reads are missed. Hooking superclasses at load only catches a superclass that loads with the
  model. Hooking them with D-232's setup (a superclass already loaded is retransformed on a helper thread) catches
  every case: a superclass loaded with the model, one already loaded, and a second model sharing it, with no double
  hook and with JaCoCo attached. About 50 lines.
- **Check 8, another agent re-instruments a model later (pass on 17, 21 and 25).** Hooks from a transformer that is not
  retransform-capable survive but cannot hook an already-loaded superclass. A retransform-capable transformer that
  recognises models by reflection loses the hook on every JDK: reflection cannot see `CLASS`-retention annotations, so
  the model no longer matches. Recognising models from the class file keeps the hook on every JDK, also with JaCoCo.
- **Check 9, Mockito 5.23 spies and mocks of a model (pass on 17, 21 and 25).** With D-232's setup the hook survives,
  and a spy, a mock and stubbing work on a class model and on an annotated record. Without the audit, spying on the
  record fails on 17 and 21 with `ClassFormatError`.
- **Check 10, why the JDK 17 and 21 record defect goes away, and the lost hook (pass on 17, 21 and 25).**
  - Bytes captured before and after the retransform chain show that once a retransform-capable transformer changed a
    class at load, HotSpot hands a later retransform the cached original class file (identical to the `.class` file,
    asserted) instead of rebuilding it. Our output still carries the `@PrimaryKey` component annotation and the hook.
    The rebuilt class file on 17 and 21 is malformed: its `Record` attribute declares one attribute on component `id`
    and none follows. JDK 25 rebuilds it correctly but drops every `CLASS`-retention annotation, on records and
    classes alike.
  - Mockito 5.23 marks a type as instrumented before retransforming it and unmarks it only on an `Exception`; a
    `ClassFormatError` leaves it marked. So after a failed spy, a mock of the same record "passes" but is not a mock
    (stubbing fails with `MissingMethodInvocationException`); a mock alone, in a fresh JVM, fails on 17 and 21.
  - With pool-based description the hook is lost only when an earlier agent strips the annotations, when the class
    loaded before the agent, when the audit's transformer errors, or when a later agent rewrites the accessor. A
    prototype (about 20 lines) that records hooked and lost classes flagged every case but the last. Part B reports
    them as not observed.
  - The JDK bug id is not found yet; the docs page cites it once known.

## Decisions at acceptance

- **D-228:** the observer is called at the executor's hand-off, after the fetch plan and enrichers, with `rows[i]`;
  children are tagged in the child load; the call site is captured once per outermost call under R-INS-14's rule in
  full, generated Q classes counting as library frames.
- **D-229:** no generated property table; the audit reads `Q<M>.fields()`, found through `MAPPER`'s nest host, and
  fails loudly when it cannot; `@Join` selection follows the presence key, `@Child` selection the fetch plan.
- **D-230:** a separate INV-7 clause for `model-query-select-audit` and its one service type; a missing agent fails
  the JUnit session when it opens; outside JUnit the observer warns once and does nothing; no `MQ` code.
- **D-231:** this is M38; the freeze moves to M39 and stays last.
- **D-232:** models are hooked as they load, recognised from the class file, by a retransform-capable transformer
  (`DECORATE`, `disableClassFormatChanges()`, pool-based description), with their superclasses; the audit never
  retransforms a model class itself and has no redefine fallback; `-javaagent` at startup is required; a tagged model
  whose class has no hook is reported as not observed.

## Milestone

M38, in the next minor release, with an acceptance spike before its slices:

| Slice | Content |
|---|---|
| M38.1 | `jpa` observer type, its lazy lookup and the hand-off helper on every result path; the `mapReturning` `Row` read (AC-INS-24 no-op half) |
| M38.2 | Module, shaded agent, call-site capture, tagging from `fields()`, `@Join`/`@Child` selection, the session failure (AC-INS-20, AC-INS-23, AC-INS-24) |
| M38.3 | Load-time read hooks with superclasses and hook-missing tracking (D-232), own-`Object`-method suppression, mapping-time reads, `UnselectedColumnRead`, serializer and `ignore` rules (AC-INS-18, AC-INS-19, AC-INS-21) |
| M38.4 | Report, fork files and merge; docs page with Maven and Gradle tabs (AC-INS-22) |
