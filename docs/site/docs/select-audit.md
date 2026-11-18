# Select audit

!!! note "Incubating"
    The select audit (`model-query-select-audit`, `UnselectedColumnRead` and `SelectAuditReport`) is annotated
    `@Incubating` (see [API stability](stability.md)).

A model is only as complete as its select set: an unselected record component reads `null` and an unselected class
field keeps its initialiser. Code that reads a property the query did not select gets a silent wrong value, often
several method calls away from the query. The opposite mistake — a select set wider than any reader needs — costs
joins and bytes without failing anything.

`model-query-select-audit` is a test-scope Java agent that watches your existing test suite with no test code changes:

- **Fails an unselected read** with `UnselectedColumnRead` (`AssertionError`) at the line that reads the property,
  naming the query call site that built the model and its selection.
- **Writes a report** when the JUnit session closes, listing unselected reads, serializer warnings, and selected
  columns that no test ever read per query call site and child load.

## Setup

Add `model-query-select-audit` in test scope and attach its jar as a `-javaagent` at JVM startup. The module implements
a non-API hand-off observer in `model-query-jpa` and shades Byte Buddy under a relocated package, so it must resolve at
`model-query-jpa`'s exact version from `model-query-bom`.

=== "Maven"

    ```xml
    <dependencies>
        <dependency>
            <groupId>io.github.rey5137</groupId>
            <artifactId>model-query-select-audit</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Populates ${io.github.rey5137:model-query-select-audit:jar} -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>properties</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <argLine>@{argLine} -javaagent:${io.github.rey5137:model-query-select-audit:jar}</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>
    ```

=== "Gradle"

    ```kotlin
    val selectAuditAgent by configurations.creating {
        isTransitive = false
    }

    dependencies {
        testImplementation(platform("io.github.rey5137:model-query-bom:0.15.0"))
        testImplementation("io.github.rey5137:model-query-select-audit")
        selectAuditAgent("io.github.rey5137:model-query-select-audit:0.15.0")
    }

    tasks.test {
        systemProperty(
            "modelquery.selectAudit.dir",
            layout.buildDirectory.dir("model-query").get().asFile.absolutePath
        )
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-javaagent:${selectAuditAgent.singleFile.absolutePath}")
        })
    }
    ```

No Spring bean or test configuration is needed: `model-query-jpa` discovers the hand-off observer via `ServiceLoader`
and JUnit Platform discovers the session listener automatically, so plain JPA tests, Spring tests, and repositories are
audited alike.

- **Startup `-javaagent` required.** Model classes are recognised from their class-file annotations and hooked as they
  load (and JDK 21+ warns on dynamic agent loading under JEP 451). With the jar on the test classpath and no
  `-javaagent` attached, the JUnit `LauncherSessionListener` fails the test session immediately when it opens, naming
  the `argLine` to add, so a test suite never passes silently unaudited.
- **Test scope only.** Without `model-query-select-audit` on the classpath, `model-query-jpa` uses a no-op observer
  that changes neither SQL nor results. If the audit jar is accidentally placed on the runtime classpath outside JUnit,
  it logs one warning and does nothing.
- **Newer JDKs.** Because Byte Buddy is shaded and relocated inside the agent jar so it never clashes with Mockito's or
  your application's version, running on a newer JDK than the release was built with may require upgrading
  `model-query-select-audit`.

## Failing an unselected read

When application code reads a mapped model property (via a record accessor, a `getX`/`isX` getter, or a direct field
read inside a method on the model itself) that was not selected in the row that built the model, the hook throws
`UnselectedColumnRead` (a subclass of `AssertionError`, so `catch (Exception e)` in application code does not swallow
it):

```text
UnselectedColumnRead: OrderView.customerEmail was not selected
  read at   InvoiceMailer.compose(InvoiceMailer.java:57)
  model of  OrderService.search:142 list, select INVOICE_SET
  add QOrderView.CUSTOMER_EMAIL to the select set, or ignore this reader (modelquery.selectAudit.ignore)
```

- **`@Join` and `@Child` properties.** A `@Join` property is selected when its presence key is selected; a present
  `Optional` tags the nested model from the scoped row, and an empty `Optional` tags nothing. A `@Child` property is
  selected only when the query's fetch plan loads it; reading an unloaded `@Child` fails at the read, and loaded child
  models are tagged from the child query's rows and reported under the parent call site as `child <name> select <set>`.
- **Engine and `Object` reads do not count.** Reads made by the mapper, generated `with` methods, fetch plans,
  enrichers, `@Selected` accessors, or inside a model's own `equals`, `hashCode` and `toString` (including Lombok's)
  neither fail nor count as application reads.
- **Mapping callbacks.** `Row.get` and `Row.raw` reads inside `afterMap` or a finisher count as reads of those columns
  (so they are not reported unread) and never fail.
- **Report-only mode.** Set `-Dmodelquery.selectAudit.failOn=none` (default `unselected`) to record unselected reads in
  the report without throwing `UnselectedColumnRead`.

### Serializers and ignore rules

- **Serializers warn, never fail.** The audit classifies a read by the first frame outside the model, skipping JDK,
  hidden and generated accessor frames (including Jackson Blackbird and Afterburner). Reads from Jackson 2
  (`com.fasterxml.jackson.`), Jackson 3 (`tools.jackson.`) and JSON-B (`jakarta.json.`, `javax.json.`) getter paths log
  a warning and are listed in the report without failing the test: a partial `?fields=` response (`FieldIndex`) reads
  unselected getters by design when `@JsonInclude(NON_NULL)` drops `null` values. Serializer reads of selected columns
  still count as reads.
- **Ignoring a reader (`modelquery.selectAudit.ignore`).** Pass comma-separated package prefixes or `Class#method`
  entries in `-Dmodelquery.selectAudit.ignore=...` for code that intentionally reads an unselected property and handles
  `null` (or a test asserting that an unselected field is `null`). An ignored read neither fails nor counts toward
  read columns.
    - A rule matches **only the immediate reading frame** (after skipping JDK, hidden, generated and audit frames),
      never a caller further up the stack.
    - A `Class#method` rule (`com.example.OrderTest#shouldLeaveNotesNull`) also matches synthetic lambda frames
      declared in that method (`lambda$shouldLeaveNotesNull$0`).
    - A method reference invoked by a library (for example, `assertThat(order).extracting(OrderView::notes)`) has the
      library as its reading frame and therefore requires a package rule for that library (`org.assertj.`).
    - A read guarded by `order.selected().contains(QOrderView.NOTES)` needs no ignore entry, because it runs only when
      the column was selected.

## The report

When the JUnit session closes, the listener writes a report to `target/model-query/select-audit-<fork>.txt` (override
the directory with `-Dmodelquery.selectAudit.dir=...`) and logs a one-line summary:

```text
UNSELECTED READS (2)
  OrderView.customerEmail  read at InvoiceMailer.compose:57  model of OrderService.search:142 list x4
  OrderView.total          read at ReportJob.run:88 (may not have failed a test)  model of ReportService.load:31 stream
SERIALIZER READS OF UNSELECTED COLUMNS (warning)
  OrderView.internalNote   read by Jackson  model of OrderController.list:40 page x12
UNREAD COLUMNS (warning)
  OrderService.search:142 list, select INVOICE_SET: OrderView.createdBy, OrderView.updatedAt
    never read in 37 calls across 12 tests
  OrderService.search:142 child lines select LINE_SET: LineView.sku
READS OF UNMAPPED INHERITED FIELDS (warning)
  OrderView.auditedBy (BaseView.getAuditedBy)  read at AuditLog.write:19  model of OrderService.search:142 list
MODELS NOT OBSERVED (hook missing)
  LegacyView  model of LegacyService.load:12 list x3: loaded before the agent; reads not recorded
```

- **Other-thread reads (`may not have failed a test`).** Each tag records the thread that handed the model off. An
  unselected read on a different thread (such as an `@Async` task or a worker pool whose future was not awaited) is
  labelled `(may not have failed a test)` because the audit cannot know whether the test joined that thread. Because
  the check compares against the tag's creation thread, the label is conservative and also appears when a test spawns
  and joins a worker thread.
- **Unread columns.** Counted per query call site and `SelectSet` (and per child load): any column in the query's own
  `select()` that no test read on any model returned from that call site and select set, and no mapping callback read
  through `Row`. Primary keys or foreign keys added internally by the engine for paging or child matching, and queries
  replayed by `PortabilityCheck`, are excluded. Unread columns are always a warning, never a failure, because they are
  bounded by what your tests exercise.
- **Unmapped inherited fields.** Only a model's own `@Column` fields are mapped by the processor; a getter inherited
  from a superclass reading an unmapped superclass field is reported as a warning rather than an unselected failure.
- **Merging multiple forks.** Each JVM writes a unique fork file (`select-audit-fork-<n>-<pid>.txt` under Surefire or
  Failsafe, or `select-audit-<pid>.txt` otherwise) so `reuseForks=false` and Surefire + Failsafe never overwrite each
  other. Keep the report directory under the build output (`target/model-query` for Maven, or `build/model-query` via
  `-Dmodelquery.selectAudit.dir` for Gradle) so `clean` removes stale fork files from earlier runs. When a build runs
  more than one test JVM, merge all fork files into `select-audit.txt` after the test phase (a column is reported
  unread only if no fork read it):

    === "Maven"

        ```bash
        ./mvnw exec:java -Dexec.classpathScope=test \
            -Dexec.mainClass=com.rey.modelquery.audit.SelectAuditReport
        ```

    === "Gradle"

        ```kotlin
        tasks.register<JavaExec>("mergeSelectAuditReport") {
            classpath = sourceSets.test.get().runtimeClasspath
            mainClass.set("com.rey.modelquery.audit.SelectAuditReport")
            args(layout.buildDirectory.dir("model-query").get().asFile.absolutePath)
        }
        ```

## Mockito, JaCoCo, and JDK 17/21 records

### Mockito agent ordering and the JDK 17/21 record `ClassFormatError`

Either `-javaagent` may come first on the command line when combining `model-query-select-audit` with Mockito's agent:

```xml
<argLine>
    -javaagent:${io.github.rey5137:model-query-select-audit:jar}
    -javaagent:${org.mockito:mockito-core:jar}
</argLine>
```

On JDK 17 and 21, HotSpot has a defect when retransforming a `record` whose record component carries a
`CLASS`-retention annotation (such as `@PrimaryKey`, `@Column`, `@Join` or `@Child`): the class file rebuilt by the JVM
for retransformation has a malformed `Record` attribute and fails with `ClassFormatError`. Furthermore, Mockito 5.23
marks a class as instrumented before retransforming it and clears that mark only on an `Exception`, not an `Error`;
after the first `ClassFormatError` on a spy or mock of an annotated record, a subsequent `mock(...)` of the same record
silently returns an uninstrumented instance that is not a mock. (JDK 25 fixes the rebuilt `Record` attribute.)

With `model-query-select-audit` attached at startup, the audit agent transforms model records as they load using a
retransform-capable transformer (`RedefinitionStrategy.RETRANSFORMATION`, `TypeStrategy.DECORATE`,
`disableClassFormatChanges()`). Because the class was modified at load time by a retransform-capable agent, HotSpot
caches the original class file bytes and hands them to later retransforms (such as Mockito's inline mock maker) instead
of rebuilding the class file from memory. As a result, Mockito spies and mocks of annotated model records work on JDK
17, 21 and 25 when the audit agent is attached, and the audit hooks survive Mockito re-instrumentation.

### JaCoCo agent ordering

The audit agent works whether JaCoCo's agent is placed before or after `model-query-select-audit` on the `argLine`. In
Maven, let `jacoco-maven-plugin`'s `prepare-agent` goal populate `@{argLine}` and append the audit agent after it (or
pass both `-javaagent` flags explicitly in `maven-surefire-plugin` / `maven-failsafe-plugin`):

```xml
<argLine>
    @{argLine}
    -javaagent:${io.github.rey5137:model-query-select-audit:jar}
    -javaagent:${org.mockito:mockito-core:jar}
</argLine>
```

Because the audit agent recognises models from the class file's constant pool and annotations rather than reflection,
and restricts its changes to method bodies (`disableClassFormatChanges()`), JaCoCo coverage probes and audit hooks
coexist in either order.

## What the audit does not see

- **Branches no test runs**, just like any dynamic coverage tool.
- **Models built outside an executor hand-off:** direct `new` calls, copies built by a custom user wither, direct calls
  to `BuiltQuery.map(Tuple)`, or a custom `ModelQueryExecutor` implementation.
- **Direct reads of a `public` field from another class**, which would require transforming every caller class. Field
  reads inside the model's own methods are hooked and attributed to the caller of that model method.
- **Reflective field reads**, including Gson and Jackson or JSON-B configured for field visibility rather than getters.
  (JSON-B and Jackson getter reads are seen and classified as serializer reads; generated bean mappers such as
  MapStruct call ordinary accessors and are seen as application reads.)
- **Reads on a model whose hook a later agent overwrote** by completely replacing accessor bytecode without preserving
  prior instructions.
- **Columns read and then discarded:** if application code calls an accessor and ignores the return value, the column
  counts as read.
