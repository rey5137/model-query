# RFC 0003 — Debug and trace logging

- **Status:** draft
- **Affects:** `code-conventions.md` (new §7, `CC-LOG-*`; CC-ERR-04 and CC-IMM-02 wording); `vendor/40` R-VND-07;
  `api/11` R-QRY-09; `api/13` R-AGG-09; `engine/20` R-EXE-03; `engine/21` (export exceptions carry their page);
  `integration/50` §3 and R-SPR-09; `processor/31` (a processor option); `reference/90` (exception accessors);
  `delivery/60` (a capturing logger for tests); `delivery/61` R-REL-03 (banned logging imports); `reference/92`
  (new D-20). Adds `engine/22-logging.md` with `R-LOG-*`
  and `AC-LOG-*`.
- **Discussion:** TBD
- **Target:** 0.1 if it lands before the M2 engine work starts, since every engine class would otherwise be revisited;
  0.2 otherwise. One public addition: `ModelQueryConfig.logValues(boolean)`, `@Incubating`.

## Summary

The library logs what it decided and what it ran, so a user can answer "why this SQL, why these rows, why this slow"
without a debugger. `DEBUG` gives one line per statement and per decision, with the model, the kind of statement, the
paging strategy, the row count and the time. `TRACE` adds page-level progress and, behind a second explicit switch,
the bound values, keys and cursors. Logging goes through `java.lang.System.Logger`, so no module gains a dependency,
and disabled logging costs one level check. Logger names and level meanings are documented and stable. Message text
is not.

## Motivation

The spec already logs in five places (R-VND-07, R-QRY-09, R-EXE-03, R-SPR-09, R-AGG-09) with no rule for how. Worse,
most of what the engine decides is invisible:

| Question a user asks | Where the answer is today |
|---|---|
| Why did my filter not narrow the query? | Nowhere. `Optional.empty()` skipped it (R-FLT-01), which is by design and silent. |
| Why is there a `LEFT JOIN` I didn't ask for? | Nowhere. R-FLT-10 turned it LEFT inside an `or`. |
| Why does the export run two statements per page after page 10? | Nowhere. `primaryKeyFirst(whenOffsetAbove(…))` switched it to key-then-model paging (R-PAG-07). |
| Why is my `IN` list `IN (…) OR IN (…)`? | Nowhere. R-FLT-09 split it. |
| Which statement is slow: the count or the page? | `org.hibernate.SQL` shows both, with nothing linking them to the call that issued them. |
| Why did the export stop at page 412? | The exception, without the page it was on. |
| Which profile and limits am I running with? | One `INFO` line (R-VND-07), without the limits. |

`org.hibernate.SQL` and `org.hibernate.orm.jdbc.bind` answer "what SQL ran", and this RFC does not repeat them. What
they cannot say is *why*: which executor call, which step of which paging strategy, which filters were dropped. That
is what the library knows and nobody else does.

## Design

### 1. Facade: `System.Logger`

`core` may import only `jakarta.persistence` and the JDK (INV-7, R-REL-03). `java.lang.System.Logger` is in the JDK.
Where it goes depends on the `System.LoggerFinder` the JVM finds:

| Setup | Where `System.Logger` goes |
|---|---|
| Spring Boot (`spring-boot-starter-logging`) | `java.util.logging`, which Boot already bridges to Logback: it ships `jul-to-slf4j` and installs a `LevelChangePropagator`, so `logging.level.*` applies with no extra dependency |
| SLF4J outside Boot | `org.slf4j:slf4j-jdk-platform-logging` on the class path, or JUL plus `jul-to-slf4j` |
| Log4j 2 | `org.apache.logging.log4j:log4j-jpl` |
| none | `java.util.logging`, the JDK default |

The JDK finds a `LoggerFinder` through the **system** class loader, so a provider packaged inside a Boot fat jar or a
WAR is not found. The user guide says so, and recommends the JUL bridge in those cases.

**R-LOG-01** Library code logs only through `System.Logger`, obtained from one internal `Log` helper per module that
uses only `java.lang`. No module, including the starter, adds a logging dependency. R-REL-03's ArchUnit list
gains one bullet: no library module imports `java.util.logging`, `org.slf4j`, `org.apache.logging` or
`org.jboss.logging`, with no exemption.

### 2. Logger names and levels

The logger names and what each level means on them are API: stable, documented in the user guide, and changed only
through an RFC. Message text is not API. Package-based names such as `com.rey.modelquery.jpa` are never used, and the
user guide says that enabling them does nothing.

| Logger | `DEBUG` | `TRACE` |
|---|---|---|
| `com.rey.modelquery.exec` | one line per executor call, and one per statement it runs (R-LOG-04) | page-level progress of `export` and `stream` (R-LOG-06) |
| `com.rey.modelquery.plan` | decisions that change a call's SQL or result (R-LOG-05) | join keys, selection and filter tree (R-LOG-05) |
| `com.rey.modelquery.values` | — | bound values, keys, cursors and the query's HQL, behind a second switch (R-LOG-07) |
| `com.rey.modelquery.vendor` | detection steps and the resolved limits (R-LOG-08) | — |
| `com.rey.modelquery.write` | (`Future`, M8) one line per bulk write and per chunk | — (chunk keys go to `values`) |
| `com.rey.modelquery.config` | every effective `ModelQueryConfig` value, once per factory | — |

`WARN` and `INFO` lines use the logger of the area that emits them.

**R-LOG-02** Levels mean one thing everywhere:

- `ERROR` is never used. A failure is an exception, and the caller decides whether to log it.
- `WARN` is used only where a rule says so: a correct but degraded path (CC-ERR-04, R-EXE-03), a customizer that
  narrows one phase (R-QRY-09), a correctness property relaxed by configuration (R-SPR-09). Each fires **once per
  cause per `EntityManagerFactory`**. The cause is the model and the rule. The registry of causes already warned is
  held per factory, next to the resolved profile (R-VND-04), not on any definition type (INV-9). A `WARN` for a
  swallowed vendor exception includes the throwable (CC-ERR-04).
- `INFO` is used only once per `EntityManagerFactory`: the resolved profile (R-VND-07). In plain JPA that may be on
  the first query, since resolution is lazy there. Apart from that line, normal queries log nothing at `INFO` or above.
- `DEBUG` is for what a developer turns on to understand one request.
- `TRACE` is for what they turn on to understand one page.

**R-LOG-03** The library never logs an exception it throws, and never logs and rethrows. Its message is complete on its
own (CC-ERR-02) and contains no bound value, key or cursor. An exception thrown during `export` carries the page
number, and its offset under `OFFSET` or `PRIMARY_KEY_FIRST`, in its message and through accessors (`page()`,
`offset()`, empty under `KEYSET`). That holds for the library's own `ModelQueryExecutionException` only. A provider
exception, such as a `QueryTimeoutException`, and an exception from the sink or `pageTransformer` propagate unwrapped,
as R-ERR-04 keeps JPA exception types; their page is on the `failed` end line below. A call that fails still writes
its end line on `exec` at `DEBUG`, from a `finally` block, with the code or exception class and no message:
`mq-42 failed page=412 cause=MQ2201`.

### 3. What is logged

**R-LOG-04** `exec` at `DEBUG`: every executor call logs a start line and an end line, and every statement in between
logs one line **after it completes**, so it follows the `org.hibernate.SQL` line for the same statement. Each line
starts with the call's id, `mq-<n>`, a per-JVM counter, so a call's lines can be picked out of an interleaved log and
matched with the SQL lines just above them on the same thread. Paging switches strategy within one call, so each
statement line carries its own `strategy`:

```
mq-42 export OrderView pageSize=1000 primaryKeyFirst=whenOffsetAbove(10000)
mq-42   stmt MODEL strategy=OFFSET page=1 offset=0 rows=1000 fetch=12ms map=4ms
…
mq-42   stmt PRIMARY_KEY strategy=PRIMARY_KEY_FIRST page=12 offset=11000 rows=312 fetch=9ms
mq-42   stmt MODEL_BY_KEYS strategy=PRIMARY_KEY_FIRST page=12 keys=312 rows=312 fetch=11ms map=1ms
mq-42 done rows=11312 pages=12 statements=13 total=240ms
```

Statement kinds reuse `Phase` (`MODEL`, `PRIMARY_KEY`, `MODEL_BY_KEYS`) and add `COUNT`, `COUNT_CLIENT` (the
client-side fallback of R-EXE-03) and `STREAM`. They are a documented, open set, not a public enum; later RFCs add to
it (`CHILDREN` in RFC 0001, `UPDATE`, `DELETE` and `CHUNK` in M8). Strategies are `OFFSET`, `KEYSET` and
`PRIMARY_KEY_FIRST`. A `page` in `NO_COUNT` mode logs `rows=21 lookAhead=1`: the `pageSize + 1` fetch is one statement
(R-EXE-02). `fetch` is the time the provider took to return the result, and `map` is the time spent in `RowMapper` and
`afterMap`. A call that runs nothing says why: `mq-43 list OrderView limit=0 → no statement` (R-EXE-01).

**R-LOG-05** `plan` at `DEBUG`: the decisions that change a call's SQL or its result. Filters are evaluated per
execution (R-QRY-01), so these lines are logged **once per executor call**, tagged with its `mq-<n>` id and
deduplicated in the call's context: not per build, and not per page. Each line cites a spec id:

| Decision | Example line |
|---|---|
| A filter skipped by `Optional.empty()` (R-FLT-01) | `skip eq(STATUS) empty (R-FLT-01)` |
| A whole `or`, `not` or `exists` dropped because every branch was skipped | `skip or[2 branches] all empty (R-FLT-01)` |
| A join resolved LEFT because it was first needed inside `or`/`not` | `join customer LEFT inside or (R-FLT-10)` |
| An `IN` list split | `split in(SKU) 2400 values → 3 chunks, maxInListSize=1000 (R-FLT-09)` |
| Columns the engine added to the selection | `add ID primary key (R-QRY-04)`, `add ID keyset tie-breaker (R-PAG-04)` |
| The paging strategy and its switch point | `strategy KEYSET, keyset() set`, `strategy PRIMARY_KEY_FIRST above offset 10000 (R-PAG-07)` |
| `count(distinct root)` because of a to-many join | `count distinct, to-many join items (R-EXE-04)` |
| Null precedence rendering | `nullsLast(CLOSED_AT) via CASE, no model-query-hibernate (R-COL-12)` |
| A batch size clamped by the profile | `primaryKeyFirst batch 5000 → 1000, maxInListSize (R-PAG-07)` |

At `TRACE`, `plan` also logs every join key with its alias and type, the final selection list, and the filter tree as
rendered, with every value shown as `?`.

**R-LOG-06** `exec` at `TRACE`: `export` logs each page's number, offset, row count and dedupe count, and `stream`
logs every 10 000 rows. That shows where an export was when it failed or stalled, without one line per row. The
page's first and last keys are values and go to `values` (R-LOG-07).

**R-LOG-07** **Values reach a log line only through one renderer with two gates.** A value is any bound filter value, a
key, a keyset cursor, a primary-key-first key list, a bulk-write chunk key, or the query's HQL, which with Hibernate
has the bound values inlined. It is printed only if **both** the `values` logger is at `TRACE` **and**
`ModelQueryConfig.logValues(true)` is set (`modelquery.logging.values`, default `false`). With either missing, a value
is shown as its type and size (`<String len=21>`, `<List size=312>`), and the HQL is not logged. The renderer:

- cuts `String.valueOf` output to 64 characters;
- shows a `byte[]` as its length, and a collection as its size plus its first 10 elements;
- never throws, catching any exception from `toString` and printing the class name instead;
- never touches an entity, which could trigger lazy loading; it prints the class name.

With `model-query-hibernate` present, and both gates open, `values` also logs the query's HQL (`toHqlString()` on
the SQM tree), which the JPA API cannot provide.

**R-LOG-08** `vendor` at `DEBUG` logs each detection step it tried and its outcome (R-VND-04), and the resolved
profile's limits as `VendorProfile` exposes them: `vendor`, `maxInListSize`, `maxBindParameters` and the null
ordering, plus the effective `ModelQueryConfig` values that feed a profile (fetch size, MySQL streaming mode, query
timeout). It never casts to a concrete profile (R-VND-01). R-VND-07's `INFO` line, once per factory, gains the limits.

**R-LOG-09** Every line names the model by its simple name, never the entity (R-ERR-03), and names each column by its
`ColumnField` name. Lines use `key=value` pairs, so they can be grepped. A test asserts what a line contains, never its
exact text.

### 4. Cost

**R-LOG-10** Every log call is `if (log.isLoggable(level)) log.log(level, message)`, using the `(Level, String)`
overload, or `(Level, String, Throwable)` for a `WARN` that carries a cause (CC-ERR-04). The `Supplier`
overloads allocate a capturing lambda before the level is checked. The
`(Level, String, Object...)` overloads format through `MessageFormat`, which groups digits by locale (`rows=1,312`) and
drops apostrophes. ArchUnit bans both. A call reads its `exec` `DEBUG` flag once at the start, so timings are measured
with `System.nanoTime()` for the whole call or not at all.

**R-LOG-11** Loggers are resolved once, in static fields of the `Log` helper. No definition type (INV-9) holds a
logger. Nothing logs inside the `RowMapper` loop: per-row work is logged only as counts (R-LOG-06).

### 5. Integration

**R-LOG-12** The starter adds no logging dependency. In a Boot application, `logging.level.com.rey.modelquery=DEBUG`
works through Boot's JUL bridge (§1). An application that prefers SLF4J's `System.Logger` provider adds it itself, with
the version Boot manages, and runs from an exploded class path or accepts the fat-jar caveat in §1.

**R-LOG-13** New property, with a `ModelQueryConfig` equivalent (R-SPR-08):

| Property | Default | Meaning |
|---|---|---|
| `modelquery.logging.values` | `false` | Allow `values` at `TRACE` to print values (R-LOG-07) |

Setting it to `true` logs one `WARN` per factory, like R-SPR-09, because it can put personal data in logs.

**R-LOG-14** The annotation processor cannot use a logger. With `-Amodelquery.verbose=true`, declared in
`getSupportedOptions()`, it emits one javac `NOTE` per generated model. The note lists each field with the column it
became, or why it was skipped (`@Transient`, no matching attribute, excluded from defaults). This answers "why is my
field not in `QOrderView`" without reading generated code.

### 6. Changed and new ids

- **New spec file:** `engine/22-logging.md` owns `R-LOG-01..14` and the logger table. Area `LOG` is added to SPEC.md
  §1.
- **`delivery/61` R-REL-03:** gains the banned logging imports of R-LOG-01.
- **`code-conventions.md`:** a new §7 with three rules. CC-ERR-04 cites R-LOG-02.
  - `CC-LOG-01`: log through the module's `Log` helper, at the level R-LOG-02 gives.
  - `CC-LOG-02`: every log call is guarded and uses `(Level, String)` (R-LOG-10).
  - `CC-LOG-03`: a new decision that changes SQL or results gets a `plan` line with its spec id (R-LOG-05), and a new
    statement kind is added to R-LOG-04's set.
- **CC-IMM-02:** also allows the per-factory registry of causes already warned (R-LOG-02), next to the cached profile.
- **R-VND-07:** "once per `EntityManagerFactory`", and the line includes the limits.
- **R-QRY-09:** the warning is logged when the call runs, since phases exist only at execution, once per cause per
  factory.
- **R-EXE-03, R-SPR-09, R-AGG-09:** each names its logger. R-AGG-09's `DEBUG` line becomes once per cause per factory.
- **`engine/21`:** the library's export exceptions carry `page()` and `offset()` (R-LOG-03).
- **`reference/90`:** R-ERR-01 adds that no message contains a bound value, key or cursor.
- **`delivery/60`:** unit tests in `core` and `jpa` capture logs through a test `System.LoggerFinder` registered with
  `ServiceLoader`. Starter tests capture them through a Logback `ListAppender`, because the JVM-wide finder would
  shadow Boot's bridge there.
- **`integration/50` §3:** gains `modelquery.logging.values`.
- **New decision D-20:** "The library logs through `System.Logger` under documented logger names. `DEBUG` covers
  every statement and decision, and `TRACE` covers progress. Values reach logs only through one renderer gated by
  `TRACE` on `values` and an explicit opt-in, and never reach exception messages. The library never logs at `INFO` or
  above per query, and never logs an exception it throws." It traces to INV-7, P-7, CC-ERR-02, CC-ERR-04 and INV-5.
- **No new codes.**

**Acceptance criteria.** `AC-LOG-*`, run with the capturing finder in unit tests and on H2 in the TCK.

1. With every logger off, an export of 20 000 rows makes zero `log` calls, counted by the capturing finder, whose
   `isLoggable` returns false (R-LOG-10).
2. An export with `primaryKeyFirst(whenOffsetAbove(n))` logs, at `exec` `DEBUG`:
   - one start line, one line per statement with its kind and strategy, and one end line, all with the same
     `mq-<n>` id;
   - the switch from `OFFSET` to `PRIMARY_KEY_FIRST` at the right page;
   - statement lines that match the statements in the SQL snapshot one to one (R-LOG-04).
3. A call that throws mid-export logs a `failed` end line with the page and the code, and the library logs the
   exception nowhere (R-LOG-03).
4. Each decision in R-LOG-05's table logs its line with its spec id at `plan` `DEBUG`, once per call across a
   multi-page export (R-LOG-05).
5. **No value leaks.** Sentinel strings are placed in filter values, in keys and in order columns. With every
   `com.rey.modelquery.*` logger at `TRACE` and `logValues` off, no sentinel appears in any line on those loggers or in
   any library exception message. Provider loggers such as `org.hibernate.orm.jdbc.bind` are out of
   scope. With `logValues` on,
   the sentinels appear only on `values`, cut at 64 characters (R-LOG-03, R-LOG-06, R-LOG-07).
6. A value whose `toString` throws, and a lazy entity reference, render as their class name, with no exception and no
   extra statement (R-LOG-07).
7. `exec` at `TRACE` logs each export page's number, offset and dedupe count, and `stream` logs every 10 000 rows
   (R-LOG-06).
8. `vendor` at `DEBUG` logs each detection step and the resolved limits (R-LOG-08).
9. Every `exec` and `plan` line names the model by its simple name and each column by its `ColumnField` name
   (R-LOG-09).
10. A normal query logs nothing at `INFO` or above, on every logger, apart from the profile line once per factory
    (R-LOG-02).
11. A customizer that narrows one phase warns once across 100 calls on one factory, and again on a second factory
    (R-LOG-02, R-QRY-09).
12. ArchUnit fails on a `java.util.logging`, `org.slf4j` or `org.apache.logging` import in any library module, and on
    a `Supplier`, parameter-array or `ResourceBundle` `System.Logger.log` overload (R-LOG-01, R-LOG-10).
13. `modelquery.logging.values=true` logs one startup `WARN`, and is settable through `ModelQueryConfig` (R-LOG-13).
14. The Boot sample, run as the packaged fat jar with `logging.level.com.rey.modelquery=DEBUG`, shows `exec` lines
    through Logback (R-LOG-12).
15. `-Amodelquery.verbose=true` emits a `NOTE` naming a `@Transient` field as skipped (R-LOG-14).

## Compatibility

Additive: one new `ModelQueryConfig` setter, one new property, and two accessors on export exceptions, all
`@Incubating`. No module gains a dependency.

## Alternatives

- **SLF4J API in `core` and `jpa`.** SLF4J is the most common choice, but it breaks INV-7 and R-REL-03 ("the JDK
  only") for a facade the JDK already has. Rejected.
- **The starter shipping `slf4j-jdk-platform-logging`.** The JDK does not find it inside a fat jar or WAR. Boot's JUL
  bridge already covers the case, and the provider would install a JVM-wide finder that silently competes with
  `log4j-jpl`. Rejected.
- **JBoss Logging, which Hibernate uses.** It is always present with Hibernate, but not with other providers, and it
  is a third-party dependency in `core` (P-7). Rejected.
- **An event listener SPI (`QueryListener`) instead of logs.** It is more structured, and could feed metrics, but it is
  a public API to design and support. Logging answers the debugging need now. A listener can be added later, and the
  `exec` lines are its natural events. Left as a future RFC.
- **Micrometer observations.** They are metrics and tracing, not debugging, and they add a dependency. Left to the
  listener above.
- **Log the rendered SQL.** Hibernate already does, as `org.hibernate.SQL`. The library has no SQL text of its own
  without Hibernate internals (P-5). The `mq-<n>` id links the two instead. Rejected.
- **Values at `TRACE` with no opt-in flag.** Turning on `TRACE` for the root logger would then print personal data,
  the easiest way to leak it in production. Rejected.
- **Do nothing.** Users read Hibernate's SQL and guess which rule produced it.

## Unresolved questions

1. **Primary-key-first step 2 over the bind limit.** When step 2's keys exceed a statement's budget, R-PAG-07 and
   AC-PAG-09 do not say whether it becomes several statements or one split `IN`. R-LOG-04 logs one line per statement
   either way, but AC-LOG-2's count depends on the answer. This is related to issue #6 and is settled there.
2. **Slow-statement `WARN`.** `modelquery.logging.slow-threshold=2s` would log a `WARN` for any statement over the
   threshold, with the call id and model. It is useful in production, where `DEBUG` is off, but it is a policy, and
   R-LOG-02 keeps `WARN` for correctness. It is proposed for a follow-up once the `exec` timings exist.
3. **MDC.** `System.Logger` has no MDC, so the call id is in the message, not in a field. A Boot application could get
   it into the MDC through the starter, which needs the listener SPI above.
4. **Target release.** Landing in 0.1 means every M2+ engine class is written with its log lines. Landing in 0.2 means
   revisiting them. The maintainer decides against the M2 schedule.
