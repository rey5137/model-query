# RFC 0011 — Declared target vendors and an offline portability check

- **Status:** accepted (2026-10-13; recorded as D-133, Q-17 and Q-18)
- **Affects:** `SPEC.md` (INV-7 names a new test-scope module); `vendor/40` (new R-VND-19 declared targets, R-VND-20
  `ProviderSupport#offlineProperties`); `integration/50` (new R-SPR-17 `modelquery.target-vendors`, §3 table);
  `api/16` (new R-INS-10 to R-INS-12, the portability check); `delivery/60` (an INV-5 conformance run in the TCK);
  `delivery/61` (module list, incubating list); `reference/90` (new `MQ4012`, `MQ4013`); `reference/92` (D-133,
  Q-17, Q-18); `docs/site` (a "portability" page)
- **Discussion:** to open

## Summary

An application can say which vendors it targets: `ModelQueryConfig.targetVendors(POSTGRESQL, H2)`, or the property
`modelquery.target-vendors=postgresql,h2`. Startup then fails when a factory resolves to a vendor outside that list.
A new test-scope module, `model-query-portability`, runs the application's calls on each target vendor with no
database. It boots the persistence unit for the target's dialect over a `DataSource` that stops at the first
statement, runs the real executor, and reports every refusal the call meets before that statement. Because of INV-5,
those are all of the library's refusals for that call. A vendor-specific feature that a target cannot serve
(`updateReturning` on MySQL, `Expr.nextval` without sequences, a skip-locked lock on H2 under Hibernate 6.6) fails
`mvn test` instead of the first production call. All new API is `@Incubating`. This is M23, shipped in 0.9.0 with M17 to M22 (user decision 2026-10-13).

## Motivation

P-4 keeps the default rendering portable, and the library refuses semantics that differ by vendor on every vendor
(`MQ1503`'s integral `dividedBy`). So until M17, almost every refusal showed up in ordinary tests on any one database.
M18 to M20 added opt-in vendor features whose refusal depends on where the call runs:

| Refusal | Depends on |
|---|---|
| `MQ4010` lock (R-VND-15) | dialect × strength × wait × Hibernate line; also refused for a multi-table root |
| `MQ4011` returning (R-VND-17) | profile shape per statement kind, provider adapter per line, mapping |
| `MQ1804` conflict forms (R-WRT-34, R-WRT-52) | profile facts, `doNothingRendered` per dialect and line, unique keys |
| `MQ1509` `nextval` on a vendor (R-VND-18) | `sequences()` and the provider renderer |
| `MQ1306`/`MQ1307` bind limits, keyset null order (R-PAG-05), `MQ2101` streaming | profile limits, null ordering, transaction |

A team that tests on H2 and deploys to PostgreSQL and MySQL tenants learns about `MQ4011` from the first MySQL claim
in production. Today the only safeguard is a Testcontainers matrix in the user's build.

The annotation processor cannot do this check. It sees model and entity declarations, never the call sites
(`updateReturning`, `onConstraint`, `setExpression`, `Expr.nextval`). It cannot see call arguments either: a lock's
`Wait`, an arbiter's `where`, an `in` list's size. And the provider line and dialect are runtime facts that it cannot
know (P-5: the processor is not a second engine).

## Design

### 1. What a refusal depends on

A refusal is not a function of (definition, profile, line) alone. It also depends on:

- the **operation**: a `ModelQuery` is refused by paging and export but served by `list` once it is locked;
- the **call's values**: a lock's wait, an `in` list's size against `maxBindParameters()`;
- the **config**: `conflictUpdateWhereOnAssignedColumns`, `keyset.null-keys`, `WriteAssignment`s;
- the **entity mapping**: unique keys, nullable keys, tables per root, generators;
- the **provider and dialect version**: `LockSupport.rendered`, and `ReturningSupport.rendered`, which translates
  the statement.

Several checks need a `CriteriaBuilder` (`MQ1507`, `MQ1508`, `MQ1509`'s renderer lookup in `JoinContext`). Pulling
these into a pure function would be a second implementation of the checks, and it would drift (P-1, P-5). INV-5
gives a cheaper seam: every refusal happens before any statement. So the check needs no database. It runs the real
executor and stops at the first statement.

### 2. Declared targets (R-VND-19, R-SPR-17)

```java
ModelQueryConfig config = ModelQueryConfig.defaults().targetVendors(DatabaseVendor.POSTGRESQL, DatabaseVendor.H2);
Optional<Set<DatabaseVendor>> targets = config.targetVendors();      // empty: undeclared, nothing checked
```

- Unset means no check. `targetVendors()` with no arguments is `MQ4003`: an empty list means "none" (P-3).
- The vendor checked is the detected one (`ResolvedVendor#detectedVendor`, or the explicit `vendor(...)`), not the
  profile's. `MARIADB` therefore stays a valid target while it resolves to `OTHER` (R-VND-06, D-81). `OTHER`
  matches only when it is listed. An explicit `modelquery.vendor` outside the list is refused too.
- A vendor outside the list is the new **`MQ4012`**. The message names the factory, the vendor detected, how it was
  detected (R-VND-07) and the list.
- In plain JPA the check runs when the executor is built (`ModelQueryExecutor.create`, where the profile is
  resolved). `ModelQueryConfig.checkTargets(EntityManagerFactory)` lets a bootstrap run it on purpose (INV-8).
- In Spring the starter checks every `EntityManagerFactory` bean in `SmartInitializingSingleton`. Repository
  executors are built lazily (`SingletonSupplier`), so a check at executor build time would fire on first use.
  Without a `ProviderSupport` this moves detection's one metadata read to startup (R-VND-05).
- Several factories share one list. A `ModelQueryConfigurer` may give each factory its own list, so there is no
  `MQ4005` analogue: a list restricts each factory and forces nothing on it.
- An application `ModelQueryConfig` bean with the property set is `MQ4006`, as for any `modelquery.*` property.
- The profile still states facts only (INV-6, R-VND-11). The list is application config, not a profile fact.

### 3. The portability check (R-INS-10 to R-INS-12)

```java
// model-query-portability (test scope; depends on jpa)          @Incubating
PortabilityCheck check = PortabilityCheck.of("orders", config)            // a persistence unit, or a List<Class<?>>
        .targets(Target.of(POSTGRESQL, 15), Target.of(MYSQL, 8), Target.of(H2)); // default: config.targetVendors()
check.call(Job.class,  x -> x.updateReturning(CLAIM, JOB_ROW))
     .call(Item.class, x -> x.insertReturning(COUNTER_UPSERT, NEXT_VALUE))
     .call(Job.class,  x -> x.list(NEXT_JOB_LOCKED))
     .sweep(OrderQueries.class, JobWrites.class)
     .assertPortable();
// AssertionError: JobWrites.CLAIM updateReturning on MYSQL 8: MQ4011 … (one line per call × target)
```

**R-INS-10, the dry-run factory.** For each `Target`, the module boots the unit through
`PersistenceProvider.createContainerEntityManagerFactory`, using jakarta types only (INV-7). It passes the standard
`jakarta.persistence.database-product-name` and `-major-version` properties. The `nonJtaDataSource` is a stub:

- its metadata answers the target's product name and version;
- transaction calls do nothing;
- `prepareStatement` throws an internal `StatementReached` that carries the SQL;
- schema generation is off.

Provider settings that keep boot away from JDBC come from the new `ProviderSupport#offlineProperties()` (R-VND-20,
empty by default; Hibernate returns `hibernate.boot.allow_jdbc_metadata_access=false`). An empty result, or a boot
that still reaches JDBC, is the new **`MQ4013`** naming the provider. Profile resolution goes through R-VND-04 and
R-VND-03 as in production, so the profiles in `config` and on `ServiceLoader` are the ones checked. A third-party
profile takes part by being in that config, and its empty or false defaults are refused here just as in production.
A target whose detected vendor is not `target.vendor()` is `MQ4012`, the same check as §2.

A target with no version uses the provider's minimum dialect version. That is conservative: H2's skip-locked or
PostgreSQL's `MERGE` count as absent.

**R-INS-11, a call.** `call` takes a `Function<ModelQueryExecutor<E>, ?>`. It is the real executor signature with
the real arguments, so a call that does not compile here does not compile in production (P-2). For each target the
harness runs the call in a stub transaction:

- If the call reaches `StatementReached`, it is served up to its first statement.
- A lazy result (`Stream`, `Iterator`) is drained by one element.
- A `ModelQueryException` is a refusal on that target.
- A Hibernate translation error is reported too, since translation runs offline.
- A refusal thrown after a `StatementReached` breaks INV-5 and is reported as a library bug.
- `.outsideTransaction()` runs a call the way the application does (`MQ2101`, R-QRY-17's transaction check).
- The executor needs an `EntityManager`; the harness opens one per call.

Factories are cached per (unit, target), so the cost is one boot per target per test JVM.

The Hibernate line is the one on the classpath, so the user checks the line they deploy. The library's TCK runs the
check on both lines (AC-INS-11).

**R-INS-12, the sweep.** `sweep(Class<?>... holders)` reads each holder's `static final` definition fields
(definitions are immutable, INV-9) and runs one default operation per type:

- `ModelQuery`: `list`;
- `ModelUpdate`, `ModelDelete`: the plain write;
- `ModelInsert`, `ValuesInsert`: the insert;
- `ModelPersist`: `persist`.

Pairs (returning writes), paging, export, stream and argument-dependent calls are not inferred. They need `call`.
`skip(holder, "FIELD", reason)` leaves a definition out; the reason is required. Holders are explicit; no classpath
scan (P-7, Q-17).

### 4. What it cannot decide

- **Database refusals:** an unknown function or sequence name, a missing partial index or constraint (`onConstraint`,
  the arbiter), SQL the server version rejects, a driver URL setting (MySQL `useCursorFetch`).
- **Data:** `MQ2201`/`MQ2202` NULL keys, a nullable column mapped as non-null, runtime `in` sizes beyond the
  values the call was given, and an `Optional.empty()` branch the call skipped (P-3), which needs a second call.
- **Statements after the first:** export pages after the first, fetch-plan child loads (built only for parents
  found), the second key-first step, `throughEntities` chunk writes. They are covered only where their checks run up
  front, as INV-5 requires. Q-17 audits this.
- **Lines and providers not on the classpath.**

The docs present the check as a fast net, with a Testcontainers matrix for the vendors that matter.

### 5. Not proposed now: a `@RequiresFact` ArchUnit rule (Q-18)

Tagging public members with the profile fact they need (`Expr.nextval` → `sequences`) would let a shipped ArchUnit
rule flag untested call sites. What it misses:

- provider and dialect-line facts (`MQ4010` on H2 6.6, `MQ4011` per line);
- mapping-dependent refusals (multi-table, unique keys, nullable keys);
- argument values, though an enum constant like `Wait.SKIP_LOCKED` is visible;
- definitions built through helpers or reflection.

It also flags call sites that sit behind the application's own vendor branch, it adds ArchUnit to a shipped artifact
(P-7), and every future member must be tagged, while an untagged one passes silently. Revisit if adopters show that
untested call sites are the real gap.

### New ids

R-VND-19, R-VND-20, R-SPR-17, R-INS-10, R-INS-11, R-INS-12. AC-VND-15 (targets checked per factory, detected
vendor, `MQ4003` empty list, `MQ4012`). AC-SPR-21 (startup failure with two factories, `MQ4006` with an application
config). AC-INS-10 (a dry-run boot per Tier-1 target on both lines opens no connection; `MQ4013` without
`offlineProperties`). AC-INS-11 (every vendor-dependent refusal AC of M18 to M20, that is AC-VND-13, AC-VND-14,
AC-WRT-42 to AC-WRT-45 and AC-COL-33, reproduces under the dry run on both lines with no container; this doubles as
an INV-5 conformance run). AC-INS-12 (the sweep, `skip`, report format). `MQ4012`, `MQ4013`.

**D-133.** *Target vendors are checked at startup, and portability is checked by a dry run, not by the
processor.* A refusal depends on operation, values, config, mapping, provider line and dialect. INV-5 places every
refusal before the first statement, so the real executor on a dialect-booted factory over a stub `DataSource` decides
portability with no database and no second implementation. The declared list fails startup (`MQ4012`). The dry run
lives in a new test-scope module on `jpa` (INV-7 amended); `model-query-test` stays core-only (D-98). The processor
and a pure refusal function were rejected.

## Compatibility

Additive and `@Incubating`. `ModelQueryConfig#targetVendors` is new, and an unset list changes nothing.
`ProviderSupport#offlineProperties()` is a default method returning empty, so third-party providers compile and
are refused by the check (`MQ4013`). `model-query-portability` is a new artifact. INV-7 gains "`portability` on
`jpa` only, test scope", and `model-query-test` stays on core (D-98). `MQ4012` and `MQ4013` are unused as of
`29587b8` (INV-10); confirm again at acceptance.

## Alternatives

- **The annotation processor.** It cannot see call sites or values, and the line and dialect are runtime facts
  (Motivation). Rejected.
- **A pure refusal function** (`Refusals.check(definition, profile, line)`). It would be a second copy of checks that
  live in rendering, in `InsertChecks`, `ReturningSupport.rendered` and `JoinContext`, and it would still need the
  metamodel. It drifts by construction (P-1). Rejected for the real-executor dry run.
- **The check inside `model-query-test`.** That module is core-only and provider-free (INV-7, D-98, AC-INS-05).
  Rejected.
- **Vendor-typed executors** (`Executor<PostgreSql>` exposing only what PostgreSQL serves). This is the ideal under
  P-2, but it puts vendor names in the API (INV-6), multiplies the surface (P-5), and argument- or mapping-dependent
  refusals would still happen at runtime. Rejected.
- **A production startup preflight over all definitions.** It still fails in production, and it cannot know each
  definition's operations. Rejected; the dry-run factory could serve it later.
- **Targets alone, with no check.** That fails at deploy time rather than in the build. Kept only as the companion
  to §3.
- **Do nothing.** A Testcontainers matrix stays the only net, and most teams run one vendor in CI.

## Unresolved questions

- **Spike (M23.1):** boot Hibernate 6.6.58 and 7.4.11 per Tier-1 dialect with no connection (sequences, `@Formula`,
  `hbm2ddl` off), and confirm `StatementReached` survives Hibernate's exception wrapping. Spring Boot entity
  discovery (a `PersistenceUnitInfo` from Boot's scan, or a `List<Class<?>>`) is also decided here.
- **Q-17:** which refusals run only after a first statement (fetch-plan child loads, export page N, the second
  key-first step, chunk writes, `persist`'s key draw). Each found is either moved up front (INV-5) or listed in §4.
  The sweep's package scan is also deferred here.
- **Q-18:** the `@RequiresFact` ArchUnit rule (§5).
- **Release (decided 2026-10-13):** M23, after M22, shipped in 0.9.0 with M17 to M22, so the M16.1 freeze review
  sees it. AC-INS-11 is written after M22, once the M20.5 and M22 refusals exist.
