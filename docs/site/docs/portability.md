# Portability

!!! note "Incubating"
    Portability checking and target vendors are annotated `@Incubating` (see [API stability](stability.md)).

A query or write that runs on your development database can still be refused on the one you deploy: a row lock the
provider would drop, a returning write on MySQL, a conflict arbiter outside PostgreSQL, `Expr.nextval` without
sequences. The library refuses these before any statement, so they can be found without a database. Two tools do it:
declared target vendors, checked at startup, and the portability check, a dry run of your calls on every target in a
unit test.

## Declare your target vendors

Declare the databases the application runs on, and a factory on any other one fails before its first query with
`MQ4012`. See [Vendor notes](vendors.md#declaring-target-vendors) for the setting, `modelquery.target-vendors` in the
starter. The portability check runs on the same list unless you name targets yourself.

## The dry run

Add the module in test scope; the version comes from the BOM. The module uses members of `model-query-jpa` that are not
API, so it must resolve at `model-query-jpa`'s exact version: take it from the BOM rather than pinning it.

```xml
<dependency>
    <groupId>io.github.rey5137</groupId>
    <artifactId>model-query-portability</artifactId>
    <scope>test</scope>
</dependency>
```

`PortabilityCheck` boots your persistence unit once per target with no database (`model-query-hibernate` provides
the offline boot for Hibernate 6.6 and 7.4): Hibernate gets the target's dialect, and its connections come from a
stub that records the first statement a call prepares and runs nothing. Each call runs on the real executor, so every
refusal the library makes before a statement is made here too, on the Hibernate line on your test class path.

=== "Plain JPA"

    ```java
    PortabilityCheck.of("shop", ModelQueryConfig.defaults())
            .targets(Target.of(DatabaseVendor.H2), Target.of(DatabaseVendor.POSTGRESQL, "16.4"),
                    Target.of(DatabaseVendor.MYSQL, "8.4.0"))
            .sweep(CustomerQueries.class)
            .skip(CustomerQueries.class, "RENAME_NOBODY", "whereKeys of no key runs no statement")
            .call("claim next", CustomerEntity.class,
                    x -> x.first(CustomerQueries.NEXT_PENDING.withLock(RowLock.exclusive())))
            .call("rename returning", CustomerEntity.class,
                    x -> x.updateReturning(CustomerQueries.RENAME, CustomerQueries.RENAMED))
            .assertPortable();
    ```

=== "Spring repository"

    ```java
    // Spring Boot has no persistence.xml: pass its booted PersistenceUnitInfo and properties directly from the
    // EntityManagerFactory. getPersistenceUnitInfo() provides the managed classes, converters and mapping files;
    // emf.getProperties() provides naming strategies and JPA settings.
    PersistenceUnitInfo unit = ((EntityManagerFactoryInfo) emf).getPersistenceUnitInfo();
    PortabilityCheck.of(unit, ModelQueryConfig.defaults().targetVendors("postgresql", "h2"))
            .properties(emf.getProperties())
            .sweep(CustomerQueries.class)
            .skip(CustomerQueries.class, "RENAME_NOBODY", "whereKeys of no key runs no statement")
            .call("claim next", CustomerEntity.class,
                    x -> x.first(CustomerQueries.NEXT_PENDING.withLock(RowLock.exclusive())))
            .call("rename returning", CustomerEntity.class,
                    x -> x.updateReturning(CustomerQueries.RENAME, CustomerQueries.RENAMED))
            .assertPortable();
    ```

    In a test without a running Spring context, pass entity classes explicitly using
    `PortabilityCheck.of(entities, config)` with Spring's `PersistenceManagedTypesScanner`, along with
    `properties(...)` for naming strategies and `mappingFiles(...)`.

- `sweep(holder)` runs each `static final` definition of the class once: `list` for a query, the write for an update,
  delete or insert, `persist` for a `ModelPersist`. A returning write, a page, an export, a stream or a call that needs
  arguments is not inferred: add it with `call`. `skip` leaves a definition out, and its reason is printed.
- `callOutsideTransaction` runs a call with no transaction, for a `commitEachChunk()` write.
- An explicit `vendor(...)` in the config is ignored, so each target is detected. A target outside the declared
  `targetVendors` is `MQ4012`: production would refuse that factory.
- A target without a version uses the dialect's minimum version, which is not the same on every Hibernate line, so
  its results can differ between lines. The report head warns once per unversioned target:
  ```text
  MYSQL checked at the dialect minimum 8.0; name the deployed version with Target.of(MYSQL, "8.4.3")
  ```
  Name the version you deploy with `Target.of(vendor, version)` to ensure reproducible checks.
- Non-comparable instance values in properties (such as bean instances) cannot be cached and are dropped. The report
  head lists each dropped key outside connection keys:
  ```text
  dropped property: hibernate.physical_naming_strategy
  ```
  Connection properties (data sources, JDBC URLs, connection providers, multi-tenant connection providers) are blanked
  (R-VND-20) so that only the offline stub is reached, and data source properties are excluded from the dropped list.

`assertPortable()` fails unless every call reached a statement with no refusal on every target. It prints one line
per call and target, the named calls first, then the swept definitions labelled `Holder.FIELD operation`, then each
skipped definition with its reason:

```text
Not portable: 1 of 15 results failed
claim next on H2 (dialect minimum): REACHED select ce1_0.id,ce1_0.name from customers ce1_0 order by 1 ... for update
claim next on POSTGRESQL 16.4: REACHED select ce1_0.id,ce1_0.name from customers ce1_0 order by 1 ... for no key update
claim next on MYSQL 8.4.0: REACHED select ce1_0.id,ce1_0.name from customers ce1_0 order by 1 limit ? for update
rename returning on H2 (dialect minimum): REACHED select id, name from final table (update customers ce1_0 set ...)
rename returning on POSTGRESQL 16.4: REACHED update customers ce1_0 set name=? where ... returning id,name
rename returning on MYSQL 8.4.0: REFUSED MQ4011: CustomerEntity: UPDATE returning cannot run on MYSQL, ...
CustomerQueries.NEXT_PENDING list on H2 (dialect minimum): REACHED select ce1_0.id,ce1_0.name from customers ce1_0 ...
...
CustomerQueries.RENAME_NOBODY skipped: whereKeys of no key runs no statement
```

| Outcome | Meaning |
|---|---|
| `REACHED` | The call reached its first statement with no refusal; the line shows it. "First statement only" marks a call whose later statements (a fetch plan's children, a primary-key-first second step, a chunk) could still be refused. |
| `REFUSED` | The library, or the provider translating the call, refused it before any statement: target cannot serve it. |
| `REFUSED_AFTER_STATEMENT` | A refusal came after a statement, which the library's own refusals never do: lambda caught the stop and went on. |
| `NO_STATEMENT` | The call returned without reaching a statement, so nothing was checked. |
| `HARNESS_ERROR` | Anything else, such as your lambda throwing, or a call reaching JDBC other than through a statement. |

A unit that cannot boot offline for a target is `MQ4013`: no provider support for the target's vendor, a vendor with
no offline boot (Oracle, SQL Server, `OTHER`), or a version that is not numeric.

A query over a [source model](source-models.md) is checked per target too, and both of its codes are raised before any
statement: `MQ4014` is the target's provider having no `SourceRenderer` (`ProviderSupport#sources()` empty, or no
`ProviderSupport` at all), and `MQ4015` is the target's `VendorProfile` answering `false` for a `SourceFeature` the
source uses (`CTE`, `RECURSIVE`, `WINDOW`, `UNION`, `INTERSECT`, `EXCEPT`) or a `maxDepth + 2` above its
`maxRecursionDepth()`. The dry run therefore reports `REFUSED` for a target that cannot serve the source and `REACHED`
for one that can, with no database attached.

### Muting dry-run log noise

During the dry run, Hibernate prepares statements against the offline stub connection and halts execution before JDBC.
Hibernate's `SqlExceptionHelper` logs each stopped statement at `ERROR` level as an unexpected JDBC exception. To mute
this noise in test runs without hiding genuine application errors, mute `SqlExceptionHelper` in your test logging:

=== "Logback (`logback-test.xml`)"

    ```xml
    <logger name="org.hibernate.engine.jdbc.spi.SqlExceptionHelper" level="OFF"/>
    ```

=== "Log4j2 (`log4j2-test.xml`)"

    ```xml
    <Logger name="org.hibernate.engine.jdbc.spi.SqlExceptionHelper" level="off"/>
    ```

## Record and replay calls

Writing every call by hand with `call(...)` or relying on `sweep(...)` can miss query arguments, dynamic filters, or
repository calls made by application services. Instead, `CallRecorder` records calls executed during integration tests
and replays them across all declared targets.

`CallRecorder.create()` bounds the recording (default 10,000 calls). Identical calls (same method, arguments, and
transaction state) collapse into a single line in the report with a count (e.g.
`OrderController.list:28 page x2 [count]`). If a recorded call touches an entity outside the persistence unit, exclude
it with `.leaveOut(UnmanagedEntity.class, "reason")`.

=== "Plain JPA"

    ```java
    CallRecorder recorder = CallRecorder.create();
    ModelQueryExecutor<CustomerEntity> executor = recorder.wrap(CustomerEntity.class, plainExecutor, em);

    // Calls executed by application or test code are recorded:
    executor.first(CustomerQueries.NEXT_PENDING.withLock(RowLock.exclusive()));

    // Replay the recorded calls against all targets:
    PortabilityCheck.of("shop", ModelQueryConfig.defaults())
            .targets(Target.of(DatabaseVendor.H2), Target.of(DatabaseVendor.POSTGRESQL, "16.4"))
            .replay(recorder.calls())
            .assertPortable();
    ```

=== "Spring repository"

    ```java
    // Register a ModelQueryExecutorDecorator bean to record calls from every repository:
    @Configuration(proxyBeanMethods = false)
    static class TestRecordingConfig {
        @Bean
        CallRecorder recorder() {
            return CallRecorder.create();
        }

        @Bean
        @Order(1)
        ModelQueryExecutorDecorator recording(CallRecorder recorder) {
            return new ModelQueryExecutorDecorator() {
                @Override
                public <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> ctx) {
                    return recorder.wrap(ctx.rootEntity(), executor, ctx.entityManager());
                }
            };
        }
    }

    // Repository calls made during test execution are recorded:
    customerRepository.findFirst(CustomerQueries.NEXT_PENDING.withLock(RowLock.exclusive()));

    // Replay recorded calls on the declared targets using the booted unit:
    PersistenceUnitInfo unit = ((EntityManagerFactoryInfo) emf).getPersistenceUnitInfo();
    PortabilityCheck.of(unit, ModelQueryConfig.defaults().targetVendors("postgresql", "h2"))
            .properties(emf.getProperties())
            .replay(recorder.calls())
            .assertPortable();
    ```

## A fast net, not a database

The dry run is a fast check in the unit-test phase. It does not replace running your tests on the databases that
matter, with Testcontainers, for example. It cannot decide:

- **what the database refuses:** an unknown function or sequence, a missing partial index or named constraint for a
  conflict arbiter, SQL the server version rejects, a driver URL setting such as MySQL's `useCursorFetch`;
- **what depends on data:** `NULL` keyset keys (`MQ2201`, `MQ2202`), a nullable column mapped as non-null, `in` lists
  longer than the values the call was given, and an `Optional.empty()` branch the call did not take (add a second call
  for it);
- **statements after the first:** export pages after the first, fetch-plan child loads, the second primary-key-first
  step, the content query of a counted page, and each later round of a chunked write. The checks the library runs up
  front for these are covered; the SQL itself is not;
- **other Hibernate lines and providers** than the one on the test class path.

The library's own suite runs the dry run on H2, PostgreSQL and MySQL on both Hibernate lines with no container, and
checks that it reproduces every vendor-dependent refusal the live suite asserts, and that the statements it reaches
equal the SQL the live suite captured on a real database.
