# 40 — VendorProfile SPI

**Covers:** the `VendorProfile` interface, how a profile is discovered and selected, and what the library deliberately
does not abstract.
**Read when:** adding a database, changing detection, or deciding whether a difference belongs in a profile.
**Owns:** `R-VND-*`, `AC-VND-*`. Tier-1 values are `vendor/41`.

---

## 1. The interface

```java
public interface VendorProfile {

    DatabaseVendor vendor();                                   // H2, POSTGRESQL, MYSQL, MARIADB, ORACLE, SQLSERVER, OTHER

    int maxInListSize();                                       // soft limit for IN (...)

    int maxBindParameters();                                   // hard limit per statement

    default int streamingFetchSize(int requested) { return requested; } // the fetch size `stream` runs with

    default void checkStreamingPreconditions(EntityManager em) {}

    void applyTimeout(Query query, Duration timeout);

    NullOrdering defaultAscendingNullOrdering();               // NULLS_FIRST, NULLS_LAST, UNKNOWN

    default boolean targetTableInSubquery() { return false; }  // Future (M6): may UPDATE/DELETE read their own table
}

public interface ProviderSupport {                             // what a persistence provider does, not a database

    boolean supports(EntityManagerFactory emf);

    default Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) { return Optional.empty(); }

    default Optional<CriteriaQuery<Long>> countQuery(CriteriaQuery<?> groupedQuery) { return Optional.empty(); }

    default Optional<NullPrecedenceRenderer> nullPrecedence() { return Optional.empty(); }

    default Optional<NullPrecedence> defaultNullPrecedence(EntityManagerFactory emf) { return Optional.empty(); }

    <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize); // streams by cursor at a profile's fetch size

    default Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity) { return Set.of(); }

    default ForeignKeyColumn foreignKeyColumn(EntityManagerFactory emf, Class<?> entity, String toOne) {
        return ForeignKeyColumn.UNKNOWN;                       // R-VND-13, D-136
    }
}
```

`VendorProfile` and `DatabaseVendor` are in `com.rey.modelquery.jpa.spi`; `NullOrdering` is in `core`, because it names
no vendor. The built-in profiles and the resolver are in `com.rey.modelquery.jpa.vendor`. `core` sees a profile only as
the vendor-neutral `RenderOptions` the executor passes to each query build. What varies by persistence provider rather
than by database (dialect detection, the grouped count, native null precedence, a configured default null ordering)
is the separate `ProviderSupport` SPI in `jpa.spi`, which `model-query-hibernate` implements (D-34, D-36). How
streaming fetches and which tables an entity reads are provider mechanics too (D-108, D-109).

**R-VND-01** Every vendor-specific behaviour the engine needs is a method here. No vendor name and no
`if (vendor == …)` exists anywhere else (INV-6). A new behaviour is a new method with a default, never a cast to a
concrete profile.

**R-VND-02** A profile is stateless and thread-safe, and is resolved once per `EntityManagerFactory`.

**R-VND-03** Profiles are discovered with `ServiceLoader`. The built-in H2, PostgreSQL, MySQL and `OTHER` profiles are
a fixed table in `jpa`, not service registrations, and a discovered profile takes precedence over the built-in one for
its vendor. Two discovered profiles for one vendor throw `MQ4002`. A caller may also supply profiles on
`ModelQueryConfig.vendorProfiles(...)`, which take precedence over a `ServiceLoader`-provided profile for the same
vendor; two supplied for one vendor throw `MQ4002`. The Spring starter passes every `VendorProfile` bean there
(`integration/50`, D-53).

**R-VND-11** `targetTableInSubquery()` (`Future`, M6) says whether an `UPDATE` or `DELETE` may read its own table in
a sub-query. When it is false, a bulk write whose rendering needs such a sub-query runs key-first (`api/14` R-WRT-11).
It defaults to `false`, which is always correct and only slower, so a profile written before M6 keeps compiling and
stays safe (R-VND-01). It is a capability, not a rendering hook, because the engine renders every predicate itself
(R-VND-08, P-5). Tier-1 values are in `vendor/41` §2; otherwise `true` for Oracle, SQL Server and MariaDB 10.3.1+, and
`false` for `OTHER`.

**R-VND-12** Streaming splits the database fact from the provider mechanism (D-108). The profile decides the fetch size
with `streamingFetchSize(requested)`, given the configured `modelquery.stream.fetch-size` (by default, that size), and
never touches the `Query`; the factory's `ProviderSupport` opens the stream with that size,
`resultStream(query, size)`, since a provider may stream only through its own API (EclipseLink's `getResultStream()`
reads the whole list first). `model-query-hibernate` sets the `org.hibernate.fetchSize` hint and calls
`getResultStream()`. No profile names a provider's hint. With no `ProviderSupport` serving the factory, the engine calls
`getResultStream()` and sets no fetch size, so the driver's default applies and may buffer the whole result, and the
first `stream` on the factory logs a `WARN` saying so, which advises adding a `ProviderSupport` for its provider
(`model-query-hibernate` for Hibernate); later ones on that factory log nothing.

**R-VND-13** `ProviderSupport.tablesOf(emf, entity)` reports every table reading `entity` touches, its joined
supertables, secondary tables and subclass tables included, each unquoted and qualified with the configured default
catalog and schema when it names none, to be compared ignoring case; empty when it cannot tell, the default (D-109).
`model-query-hibernate` reads them from the query spaces of the entity's persister and its subclasses'. The engine uses
them in `jpa` only, to tell that a bulk write's sub-query reads a second entity sharing one of the root's tables
(`api/14` R-WRT-11); `core` never sees a table name (INV-7).
`@Incubating ProviderSupport.foreignKeyColumn(emf, entity, toOne)` is a second mapping fact of the same kind (D-136). It
returns the `@Incubating` enum `ForeignKeyColumn`: `LOCAL_COLUMN` when `toOne.id` renders as a column of the entity's own
table, with no join and no formula, referencing the target's primary key; `JOINS` when it renders a join (a key on
another table, a formula, a reference to another column); `UNKNOWN` when the provider cannot tell, the default.
`model-query-hibernate` answers `LOCAL_COLUMN` only for the key side, a reference to the primary key, no not-found
action (checked on the mapping, so `orm.xml` counts), no formula part and a key on the entity's own table. The executor
refuses with `MQ1004` a write's to-one id the provider does not answer `LOCAL_COLUMN` for (`api/10` R-COL-29); `core`
receives the answer as the `@EngineFacing` `ForeignKeyLookup` and never sees the provider.

**R-VND-14** Bulk inserts split the database facts from the provider mechanism as R-VND-12 does (D-117).
`ProviderSupport#inserts()` returns an `Optional<InsertSupport>`, empty by default; `InsertSupport` lives in `jpa.spi`
and uses `jakarta.persistence` types only (INV-7):

```java
InsertTarget target(EntityManagerFactory emf, Class<?> entity);   // record InsertTarget(IdGeneration id,
                                                                   //     List<String> unsupportedMappings)
List<Object> generateKeys(EntityManager em, Class<?> entity, int count);
boolean doNothingRendered(EntityManagerFactory emf);
default boolean computedValuesRendered(EntityManagerFactory emf);  // D-132: false -> MQ1613
default boolean arbiterRendered(EntityManagerFactory emf, Class<?> entity,
        List<Condition> arbiter);                                  // D-132: false -> MQ4011
int providerBindsPerRow(EntityManagerFactory emf, Class<?> entity);
List<Set<String>> uniqueKeys(EntityManagerFactory emf, Class<?> entity);
<E> Query insertSelect(EntityManager em, Class<E> entity, List<String> attributes, CriteriaQuery<Tuple> source,
        Map<String, Object> constants);
<E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes, List<List<Object>> rows,
        Optional<ConflictClause<E>> conflict);
default <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes, List<List<Object>> rows,
        Map<String, Function<CriteriaBuilder, Expression<?>>> computed, Optional<ConflictClause<E>> conflict);
```

`IdGeneration` is sealed: `Assigned`, `Identity`, `Sequence(boolean physical, int increment)`, `Table`, `Uuid` and
`Other(String generatorClass)`. `unsupportedMappings` names each mapping of the root no bulk insert writes (`JOINED`
inheritance, a `@SecondaryTable`, a composite id with generated parts, `@MapsId`). `ConflictClause<E>` carries
`keyAttributes()`, `doNothing()` and `Optional<Predicate> update(Root<E> target, Root<E> excluded, CriteriaBuilder,
ConflictClause.Assignments assign)`, where `Assignments` takes `value(Path<?>, Object)`, a value the implementation
binds as the path's type, and `expression(Path<?>, Expression<?>)`. `insertSelect` binds each of `constants`, the
source select's named parameters written to the last attributes, as its attribute's type, as `insertValues` binds its
values. The six-argument `insertValues` (D-130) also writes, in each row after `attributes`, each of `computed`'s
attributes the expression its factory returns: the implementation calls each factory once per row, so no Criteria node
is shared between rows, and the expression binds nothing (`api/14` R-WRT-53). The default delegates to the five-argument
form when `computed` is empty and otherwise throws `MQ1613` instead of building the statement, so an `InsertSupport`
written before it refuses a computed value rather than dropping it; the engine refuses such an insert with `MQ1613`
itself on each execution, before the flush, where the explicit `@Incubating` default `computedValuesRendered(emf)`
(false unless overridden) answers false, rather than detecting an override by reflection, which delegation defeats
(D-132). `arbiterRendered(emf, entity, conditions)` likewise reports on each execution, before the flush, whether the
provider can splice a conflict arbiter predicate (false is `MQ4011`; the Hibernate one renders the arbiter's literals
too, so a value with no literal rendering is `MQ1508` there). `uniqueKeys` names the attribute sets the mapping
declares unique, which a conflict clause's columns must equal one of (`MQ1804`). The engine holds the generator
against `api/14` R-WRT-26's allowlist in `jpa`, on the definition's first execution (`MQ1805`, `MQ1802`, `MQ1807`),
draws keys with `generateKeys` for a sequence, table or UUID generator, refuses `doNothing` where `doNothingRendered` is
false (`MQ1804`), and counts `providerBindsPerRow` (the `@Version` seed) when it sizes a `VALUES` statement (R-WRT-29,
D-80). It runs the returned `Query` itself, so the timeout applies as for an update or delete. A bulk insert on a
factory with no `InsertSupport` throws `MQ4009` before the flush; `persist` needs none. `model-query-hibernate` ships
one `InsertSupport`, compiled against Hibernate 6.6 and run on 7.x by the TCK: it reads the generator from the entity's
persister, compares a generator class that moved between the two by name, and reports `doNothingRendered` true on 7 and
on 6.x only for the PostgreSQL and MySQL dialect hierarchies (D-116).

`VendorProfile` gains six database facts (INV-6): `default int maxValuesRows()`, the most rows one multi-row
`VALUES` insert may hold besides the bind limit, `1_000` by default (a lower limit is only slower); `default boolean
conflictTargetHonoured()`, whether a conflict clause detects a conflict only on the key it names, `false` by default so
a third-party profile fails safe (`api/14` R-WRT-36); `default boolean conflictWhereSeesEarlierAssignments()`,
whether a conflict update's `where` reads the values the update's earlier assignments wrote rather than the stored row,
as MySQL's `CASE` per assignment does, `true` by default so a third-party profile fails safe: where it is true, a
`where` reading two or more assigned columns is refused even when the executor allows it (`api/14` R-WRT-34); and two
`false`-by-default facts for R-WRT-52's conflict targets, `default boolean conflictOnConstraint()`, whether the clause
can name a constraint (`ON CONFLICT ON CONSTRAINT`), and `default boolean conflictArbiterPredicate()`, whether the
conflict target takes a `WHERE` naming a partial unique index; both refuse with `MQ1804` on first execution where they
are false (`api/14` R-WRT-52). `default boolean sequences()` is the sixth, for `Expr.nextval` (R-VND-18). Tier-1
values are in `vendor/41` §2.

Entity-mode updates split the same way (D-119). `ProviderSupport#entityWrites()` returns an
`Optional<EntityWriteSupport>`, empty by default; `EntityWriteSupport` lives in `jpa.spi` and uses `jakarta.persistence`
types only (INV-7). `Object unproxy(Object entity)` returns the initialised instance behind a proxy the persistence
context holds, and `void set(EntityManager em, Object entity, String attribute, Object value)` sets an attribute of a
managed entity, dotted through embeddables, as the provider's own attribute access does, so its change tracking sees the
change (`api/14` R-WRT-42). `model-query-hibernate` ships one: `Hibernate.unproxy`, and the persister's property access,
which also marks a changed attribute dirty on an entity that tracks its own dirtiness, since the property access alone
does not.

**R-VND-15** Row locks split the database fact from the provider mechanism as R-VND-12 and R-VND-14 do (D-127).
`ProviderSupport#locks()` returns an `Optional<LockSupport>`, empty by default; `LockSupport` lives in `jpa.spi` and
uses `jakarta.persistence` types only (INV-7):

```java
boolean rendered(EntityManagerFactory emf, RowLock lock);   // would apply(...) render exactly this lock?
default boolean hasUnreportedJoins(CriteriaQuery<?> query) { return false; }   // joins JPA's getJoins() misses
void apply(TypedQuery<?> query, RowLock lock);
```

`rendered` says whether `apply` would render exactly `lock`, so a lock whose wait the provider would drop, or that it would defer (follow-on locking), is
refused instead; a shared lock widened to exclusive keeps its guarantee and is accepted (`MQ4010`, `engine/20` R-EXE-14). `apply` sets the target statement's lock mode and any hint the
provider needs. `hasUnreportedJoins` reports a join the standard criteria API does not expose, such as Hibernate's
entity join (`JpaRoot.join(Class)`) added by a customizer, so a locked query refuses it with `MQ2211` before any SQL
(added at the M18 gate). Whether a lock renders is a fact about the provider and its version, not the vendor (as with D-117), so
it is decided per strength × wait: Hibernate 6.6 drops `NOWAIT` and `SKIP LOCKED` on H2, and 7.x renders both for H2
only for `exclusive()`, so `shared()` with `NO_WAIT` or `SKIP_LOCKED` is refused on H2 on both lines; PostgreSQL and
MySQL render every combination (`vendor/41` §6). `model-query-hibernate` reads the answer from the JDBC `Dialect` and
always sets `hibernate.query.followOnLocking=false`, so a follow-on case left over fails loudly instead of reading
unlocked. `VendorProfile` gains nothing: the lock-timeout hint values are Hibernate's (R-VND-12), and an overridable
raw-`Query` hook on a profile would be the `TypedQuery` escape hatch left out of M18. The engine calls `locks()` in
`jpa` only; `core` never sees a provider or a hint (INV-7).

**R-VND-16** A column-typed constant's stored value is a provider fact, as a lock's rendering is (D-128).
`ProviderSupport#constants(emf)` returns an `Optional<ConstantRenderer>`, empty by default; a provider without it
leaves `Expr.constant(typedBy, value)` to throw `MQ1508` (`api/10` R-COL-27). `ConstantRenderer` lives in `core`,
because it hands back a plain stored value and no provider type:

```java
Optional<Object> storedValue(Class<?> entity, String attribute, Object attributeValue);   // String, Number or Boolean
```

`storedValue` maps the attribute value of `entity.attribute` to the value its column stores — an
`@Enumerated(STRING)` constant's name, an `ORDINAL` one's ordinal, an `AttributeConverter` one's converted database
value, else the attribute value itself — and is empty when the provider cannot tell. `model-query-hibernate` reads it
from the attribute's JPA mapping in its `MappingMetamodel`, so a mapped conversion decides it and not a re-read of the
annotations. The executor resolves it once per factory and passes it into the build as `RenderOptions` carries the
native null-precedence renderer (R-VND-01), reaching `core` only through `JoinContext` (INV-7).

**R-VND-17** Returning the rows a write changed splits the database's shape from the provider's mechanism, as R-VND-14
and R-VND-15 do (D-129). `VendorProfile#returning(WriteStatement)` returns an `Optional<ReturningShape>`, empty by
default, and states the database fact alone, rendering no SQL (R-VND-11): `SUFFIX`, the statement's own `RETURNING`
clause (PostgreSQL), and `DELTA_TABLE`, a select over the write's delta table (H2's `FINAL TABLE` on an update and
`OLD TABLE` on a delete, `FINAL TABLE` on an insert). `ProviderSupport#returning()` returns an
`Optional<ReturningSupport>`, empty by default. A returning write on a factory with no shape for the statement kind, no
`ReturningSupport`, or one whose `rendered` is false is `MQ4011` at method entry, before any statement (`api/14`
R-WRT-50); so is a `LinkageError` from a moved Hibernate type outside `..internal..` (D-132); a third-party profile or
provider compiles and fails safe. The SPI lives in `jpa` and takes jakarta types only (INV-7):

```java
// ProviderSupport
default Optional<ReturningSupport> returning();                        // empty: MQ4011

// spi
interface ReturningSupport {
    boolean rendered(EntityManagerFactory emf, Class<?> entity, ReturningShape shape);
    List<Object[]> rows(Query statement, Class<?> entity, List<String> attributes, ReturningShape shape);
    default void check(EntityManagerFactory emf, Class<?> entity, List<String> attributes);   // D-132: no-op
}

enum ReturningShape { SUFFIX, DELTA_TABLE }         // the database's fact
enum WriteStatement { UPDATE, DELETE, INSERT }      // the statement kind a fact is asked for
```

`ReturningShape` and `WriteStatement` name facts only. `rendered(emf, entity, shape)` reports whether this factory,
entity and shape can return rows at all: false for a shape the provider cannot render, a root entity mapped to more
than one table (`tablesOf`, as R-VND-15 refuses a lock), and a provider line whose calls moved. `rows(statement, entity,
attributes, shape)` runs a write statement the executor already built — its parameters bound, its timeout set, its
assignments and version handling in it — and returns one `Object[]` per changed row, each holding the values of
`attributes`' columns in that order, as the entity holds them and before the column's converter, exactly as a tuple of a
read query holds them; a to-one's id is the written row's foreign key. An implementation refuses with `MQ1809` a column
it cannot express on the written table (R-WRT-50), in `check(emf, entity, attributes)`, which the executor calls on
each execution before the flush (INV-5, D-132). `model-query-hibernate` answers with Hibernate's own machinery: it
translates the statement through Hibernate's mutation translator, applies the vendor's shape, prepares it through the
session's `JdbcCoordinator` (SQL logging and resource tracking unchanged), binds it with Hibernate's binders, reads each
column through its attribute's JDBC mapping, calls the `StatementInspector` and invalidates query spaces as Hibernate's
own bulk path does. The `org.hibernate..internal..` types that takes live in one small adapter per Hibernate line behind
a package-private interface, chosen by probing rather than by a version string, with one `WARN` outside an adapter's
tested range and a first-use check of each translated statement, so a moved internal refuses with `MQ4011` rather than
running SQL that may be wrong (R-WRT-50, INV-5).

**R-VND-18** A sequence's next value is a database fact and a provider rendering, as a constant is (D-129).
`VendorProfile` gains `default boolean sequences()`, whether the database has sequences, `false` by default so a
third-party profile fails safe; it names no SQL (R-VND-11). `ProviderSupport#sequences(EntityManagerFactory emf)`
returns an `Optional<SequenceRenderer>`, empty by default; it takes the factory, as `constants(emf)` does, because the
renderer spells the call in the dialect that factory uses. `SequenceRenderer` lives in `core`, because it takes and
returns `jakarta.persistence.criteria` types only (INV-7):

```java
Expression<Long> nextValue(CriteriaBuilder cb, String sequence);   // the dialect's own sequence call
```

The executor puts the renderer in `RenderOptions` only where the profile's `sequences()` is true, and `Expr.nextval`
(`api/10` R-COL-28) reaches it through a `JoinContext` hook; without one it throws `MQ1509`. `model-query-hibernate`
renders `dialect.getSequenceSupport().getSelectSequenceNextValString(sequence)` through
`HibernateCriteriaBuilder.sql`, so the dialect decides the text and the sequence name is checked as an identifier first.

**R-VND-19** *(D-133)* An application may declare its target vendors, the databases it is meant to run on.
`ModelQueryConfig` gains `@Incubating` `targetVendors(DatabaseVendor first, DatabaseVendor... more)`,
`targetVendors(Collection<DatabaseVendor>)`, `targetVendors(String...)` (names matched as `vendor(String)` matches
them, D-57) and `Optional<Set<DatabaseVendor>> targetVendors()`, empty by default: undeclared, nothing is checked. An
empty `Collection` or `String...` list is `MQ4003` (an empty list would mean "none", P-3; the varargs form cannot be
empty), an unknown name `MQ4001`. The vendor checked is the one R-VND-04 resolves for the factory before any profile is
chosen, `ResolvedVendor#detectedVendor()`: an explicit `vendor(...)`, else the detected one. So `MARIADB` is a target
while its profile is `OTHER`'s (R-VND-06), `OTHER` matches only when it is listed, and an explicit vendor outside the
list is refused like a detected one. A factory on another vendor is `MQ4012`, naming the factory, the vendor, how it
was found (R-VND-07: configured, by the provider, from `DatabaseMetaData`, or nothing detected) and the list. The name
is the provider's: `ProviderSupport` gains `@Incubating default Optional<String> factoryName(EntityManagerFactory)`,
empty by default, which Hibernate answers with the session factory name from `SessionFactory`'s options (empty when
null or blank); with no answer the factory is named by its class and identity, as JPA 3.1 gives a factory no name and
its properties no portable key for one. `ModelQueryExecutor.create` checks it when it
resolves the factory's vendor, and `ModelQueryConfig.checkTargets(EntityManagerFactory)` (`@Incubating`) lets a
bootstrap check it on purpose (INV-8); without a `ProviderSupport` that call makes detection's one metadata read
(R-VND-05). The list is application config, not a profile fact (INV-6, R-VND-11). Spring checks every eager factory at
startup (`integration/50` R-SPR-17).

**R-VND-20** Booting a factory with no database is a provider fact (D-133). `ProviderSupport` gains `@Incubating
default Optional<Map<String,Object>> offlineProperties(DatabaseVendor vendor)`: the provider's settings that boot a
factory for `vendor` over the portability dry run's stub `DataSource` (`api/16` R-INS-10) with no JDBC call. Empty, the
default, means the provider cannot, and the dry run refuses the target with `MQ4013` rather than reach a database, so a
third-party provider compiles and fails safe. The dry run applies them over the unit's properties and beneath its own
overrides, and still records the boot: a boot that reaches JDBC is `MQ4013` whatever the properties claimed.
`model-query-hibernate` returns, for every vendor but `OTHER`: `hibernate.boot.allow_jdbc_metadata_access=false`
(honoured on 6.6 and 7.4; without it both lines ask for a connection at boot), `hibernate.hbm2ddl.auto=none`,
`hibernate.dialect=""` (a unit's explicit dialect silently beats the product name; the empty override restores
resolution by product name, an application's `hibernate.dialect_resolvers` included, so a custom dialect subclass is
not used in the dry run), `hibernate.connection.provider_class=""` (a unit's connection provider bypasses the stub)
and `hibernate.query.mutation_strategy.global_temporary.create_tables` and `.drop_tables` `false` (H2's strategy asks
for a connection per multi-table entity and swallows the failure). A unit's `hibernate.connection.url`,
`jakarta.persistence.jdbc.url` and JNDI `hibernate.connection.datasource` need no override: the stub `DataSource`
beats them.

**R-VND-21** Which source features a database has is a profile fact (D-143). `VendorProfile` gains `@Incubating
boolean sources(SourceFeature feature)`, `false` by default: a query whose source uses a feature the target's vendor
answers `false` for is `MQ4015` before any statement (`api/17` R-SRC-11). A profile written before a `SourceFeature`
constant was added answers `false` for it — that is the point of the default — and a constant added later names a
database fact of its own. The Tier-1 profiles answer `true` for every constant in the shipped release but one: the
plain CTE, recursion, window functions and the three set operations (`vendor/41` §2), plus
`RECURSIVE_COMPOSITE_KEY` — a recursive source over a node key of more than one column, whose multi-column `cycle`
clause Hibernate emulates with `array[row(a, b)]`, a form H2 rejects — on PostgreSQL and MySQL. H2 answers `false` for
it, so a recursive source over a composite node key is `MQ4015` there before any statement (M26.1, D-149; `api/17`
AC-SRC-21). `OTHER` keeps the default. A database fact, no SQL (R-VND-11, INV-6). The SQL side is
`core.SourceRenderer`, the one source hook `ProviderSupport#sources()` returns (`cte` abstract; `CteHandle` and
`RecursiveHandle` nested types; `window`, `combine`, `recursive`, `register`, `registerRecursive` and `from` default to
`MQ4014`, a missing provider renderer, rather than a vendor fact; D-146, D-150).

**R-VND-22** A database's own recursion ceiling is a profile fact (D-143). `VendorProfile` gains `@Incubating
int maxRecursionDepth()`, `Integer.MAX_VALUE` by default, meaning the database sets no ceiling this library knows of.
MySQL's is 1000, its `cte_max_recursion_depth` default, so the Tier-1 MySQL profiles return it and a recursive source
whose `maxDepth + 2` exceeds it is `MQ4015` before any statement (the extra level and the final, empty iteration
count too; `api/17` R-SRC-08). A database fact, no SQL (R-VND-11, INV-6).

**R-VND-25** *(D-150)* `ProviderSupport#subqueries()` is an `@Incubating` default method returning an empty
`Optional<SubqueryRenderer>`. The core `SubqueryRenderer` SPI is `@EngineFacing` and `@Incubating`; its
`@Incubating firstRow(CriteriaBuilder, Subquery<C>, List<Order>)` returns the ordered, one-row scalar expression.
The engine supplies the inner orders, including the root id tie-breaker, through `RenderOptions`; the renderer
applies the order and a limit of one inside the sub-query. `model-query-hibernate` implements it through
`JpaSubQuery#orderBy` and `fetch(1)` on both Hibernate lines. An empty hook (including no `ProviderSupport`) is
`MQ4014` for `Correlated.first` before any statement; `PortabilityCheck` reports it per target without a database
(R-COL-32, AC-COL-40). Other correlated terminals need no renderer. This is a provider capability, with no
`VendorProfile` change (R-VND-11, INV-6).

## 2. Detection

**R-VND-04** Resolution order:

1. An explicit `ModelQueryConfig.vendor(DatabaseVendor)` or the `modelquery.vendor` property. Nothing is detected.
2. With a `ProviderSupport` serving the factory, as `model-query-hibernate` does:
   `SessionFactoryImplementor#getJdbcServices().getDialect()`, mapped by dialect class, most specific first (MariaDB's
   dialect extends MySQL's). This needs no connection and is per `EntityManagerFactory`, so an application with
   several datasources on different databases resolves each correctly.
3. Otherwise `DatabaseMetaData#getDatabaseProductName()`, read once per `EntityManagerFactory` on a connection from
   the factory's `jakarta.persistence.nonJtaDataSource` property: `H2`, `PostgreSQL`, `MySQL` and `MariaDB` name their
   vendor, any other name is `OTHER`. A factory without that property, or whose `DataSource` fails, resolves to
   `OTHER` and logs a `WARN`. A factory whose metadata read fails stays `OTHER` for the factory's lifetime: the
   result is cached, not retried.

The result is cached weakly per factory and configured vendor, so detection runs once per factory and an explicit
vendor always wins over an earlier detection.

**R-VND-05** Detection never runs per query, and never opens a connection when step 1 or 2 answered.

**R-VND-06** An unrecognised database resolves to the `OTHER` profile, which is deliberately conservative: fetch size
500, JPA timeout, IN list 1 000, bind parameters 2 000, null ordering `UNKNOWN`, no target table in sub-queries
(R-VND-11). A recognised vendor with no profile (MariaDB, Oracle, SQL Server until theirs exist) uses the `OTHER`
profile too, and the resolution log names the vendor detected. Keyset paging on a nullable column without explicit
null precedence is refused under `OTHER` (`engine/21` R-PAG-05, `api/10` R-COL-13).

**R-VND-07** The resolved profile is logged once at `INFO` with how it was resolved, because a wrong profile produces
correct-looking results with the wrong limits. When no `ProviderSupport` serves the factory and its properties carry
`hibernate.order_by.default_null_ordering` set to anything but `none`, resolution also logs a `WARN` once: the setting
is not honoured without `model-query-hibernate` (`engine/21` R-PAG-05, D-36). It reads that Hibernate property in
`jpa` by necessity: it fires only where no `ProviderSupport` exists (D-108).

## 3. Keyset predicate contract

**R-VND-08** The engine renders keyset predicates itself, portably; a profile only supplies
`defaultAscendingNullOrdering()`. The rendered shape and NULL branches are specified in `vendor/41` §5, so a new profile
inherits correct keyset behaviour without writing SQL.

## 4. What the library does not abstract

**R-VND-09** Documented in the user guide and left to the database and the entity mapping:

- String comparison and case sensitivity follow column collation — MySQL `*_ci` is case-insensitive, PostgreSQL is
  case-sensitive.
- JSON, array and enum column types follow the entity mapping.
- Boolean storage and date/time precision follow Hibernate and the JDBC driver; time zones follow
  `hibernate.jdbc.time_zone`.
- H2's default null ordering must be kept. With `DEFAULT_NULL_ORDERING` set to anything else, Hibernate's H2 dialect,
  which assumes NULLs sort smallest, leaves an explicit `nullsFirst()`/`nullsLast()` bare where it is needed, so NULLs
  are misplaced and keyset paging may skip them even with explicit precedence. It is not detectable without a
  connection (R-VND-05).
- The scale of a decimal quotient follows the database (`api/10` R-COL-17).
- `concat` with a NULL operand is NULL on Tier 1, and Oracle treats `''` as NULL.
- A named function (`Expr.function`) is the database's own SQL.
- Text an expression computes compares by the column collation rules.

**R-VND-10** A behaviour in this list is never silently emulated. If a use case needs uniformity, it asks for it
explicitly (`likeIgnoreCase`, `nullsFirst`), and the library renders it the same way everywhere (`api/12` R-FLT-07,
`api/10` R-COL-12).

## 5. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-VND-01 | ArchUnit fails when a `DatabaseVendor` reference appears outside a profile or the detection code (R-VND-01). |
| AC-VND-02 | Each Testcontainers database resolves to the expected profile, with and without `model-query-hibernate` (R-VND-04). |
| AC-VND-03 | Two `EntityManagerFactory` beans on different databases resolve to different profiles in one application (R-VND-04). |
| AC-VND-04 | Detection opens no connection when the vendor is configured explicitly or a dialect is available (R-VND-05). |
| AC-VND-05 | An unknown `DatabaseMetaData` product name yields `OTHER`, and a nullable-column keyset under `OTHER` is refused (R-VND-06). |
| AC-VND-06 | A Spring-registered profile bean overrides the `ServiceLoader` one (R-VND-03). |
| AC-VND-07 | (`Future`, M6) `targetTableInSubquery()` is true for H2 and PostgreSQL and false for MySQL and `OTHER`, verified against each container (R-VND-11). |
| AC-VND-08 | On PostgreSQL, the profile's fetch size reaches the streamed statement through `model-query-hibernate`'s `resultStream`, so the driver reads by cursor; without a `ProviderSupport` it reads the result at once (R-VND-12). |
| AC-VND-09 | With no `ProviderSupport`, `stream` logs one `WARN` per factory however many streams run; with one, none, and `resultStream` receives the size the profile chose: the configured size, or `Integer.MIN_VALUE` for MySQL row-by-row (R-VND-12). |
| AC-VND-10 | `model-query-hibernate`'s `tablesOf` reports every table reading an entity touches, unquoted and qualified with the default schema: a joined subclass's supertable, which a second entity on it shares, a secondary table and a table-per-class parent's subclass tables; the same for two entities on one table; and none for a type that is not an entity (R-VND-13). |
| AC-VND-11 | `model-query-hibernate`'s `InsertSupport` reports each D-116 probe root's generator (assigned, `IDENTITY`, a sequence with its increment and whether a database sequence backs it, table, UUID, an `@IdClass` with no generated part as assigned) and its unsupported mappings (`JOINED`, `@SecondaryTable`, `@MapsId`, a generated composite part); draws distinct keys from a pooled sequence, a table and a UUID generator and refuses `IDENTITY`; reports `doNothingRendered` true on Hibernate 7 and on 6.x except on H2; and counts the version seed as one bind per row, on every Tier-1 vendor (R-VND-14). |
| AC-VND-12 | `maxValuesRows()` defaults to 1,000, `conflictTargetHonoured()` to false and `conflictWhereSeesEarlierAssignments()` to true, `ProviderSupport#inserts()` to empty; the built-in profiles carry `vendor/41` §2's values (R-VND-14). |
| AC-VND-13 | `LockSupport.rendered` is per dialect on both Hibernate lines (H2's `NO_WAIT`/`SKIP_LOCKED` refused on 6.6, allowed only for `exclusive()` on 7.x); a lock with no `locks()`, an unrendered wait, or a multi-table root is `MQ4010`; `apply` sets the lock mode, the Hibernate timeout hint and `followOnLocking=false` (R-VND-15, `engine/20` R-EXE-14). |
| AC-VND-14 | `ProviderSupport#returning()` and `VendorProfile#returning(WriteStatement)` default to empty, so a write whose factory has neither is `MQ4011` at method entry; the built-in H2 profile reports `DELTA_TABLE` and PostgreSQL `SUFFIX` for update and delete, MySQL nothing; `model-query-hibernate`'s `ReturningSupport.rendered` is true on both Hibernate lines for a single-table entity and false for a `@SecondaryTable` root, and `rows` returns the columns of a changed row as a read tuple holds them; the translated SQL per line and dialect is `vendor/41` §7's (R-VND-17, R-WRT-50). |
| AC-VND-15 | Target vendors are undeclared by default and kept by the other setters; the three forms declare the same set in enum order; an empty `Collection` or `String...` list is `MQ4003` and an unknown name `MQ4001`; each factory is checked against its own vendor (an H2 factory detected from `DatabaseMetaData` passes `[H2]`, an undetected one is `OTHER` and passes only when `OTHER` is listed); an explicit `vendor(...)` outside the list is refused; an explicit `MARIADB` passes `[MARIADB]` though its profile is `OTHER`'s; `ModelQueryExecutor.create` refuses a factory off the list; and the `MQ4012` message names the factory (quoting `ProviderSupport#factoryName`'s answer, default empty, else its class and identity; Hibernate answers a named session factory's name and nothing for an unnamed, blank-named or non-Hibernate factory), the vendor, how it was found and the list (R-VND-19). |
