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
binds as the path's type, and `expression(Path<?>, Expression<?>)`. Under inline value handling (R-VND-28, D-179) the predicate `update` returns may hold criteria parameters the engine binds on the `Query` that `insertValues` returns, so the implementation renders it into that same statement, not into native SQL text, and calls `update` once per `insertValues` call, using that call's predicate. `insertSelect` binds each of `constants`, the
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
`core.SourceRenderer`, the one source hook `ProviderSupport#sources()` returns (`CteHandle` and `RecursiveHandle`
nested types; `register`, `registerRecursive`, `from`, `window` and `combine` default to `MQ4014`, a missing provider
renderer, rather than a vendor fact; `cte` and `recursive` default over `register`, `registerRecursive` and `from`, so a
provider needs only those; D-146, D-150, D-151).

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

**R-VND-26** *(D-154)* `ProviderSupport#treats(EntityManagerFactory emf)` is an `@Incubating` default method
returning `Optional.empty()`. The core SPI uses only JPA and JDK types (INV-7); every type and member is
`@Incubating`, and `TreatRenderer` is `@EngineFacing`:

```java
interface TreatRenderer {
    <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype);
    Optional<String> refusal(TreatSite site);
    Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape,
                               List<ExpectedNarrowing> expected);
}
record TreatSite(Class<?> entity, Class<?> subtype, Optional<String> attribute,
                 StatementShape shape, boolean anchored, Set<TreatUse> uses) {}
record ExpectedNarrowing(Kind kind, Class<?> entity, Set<Class<?>> types) {
    enum Kind { EXISTS_ROOT, TYPE_RESTRICTION }
}
enum StatementShape { CONTENT, COUNT, GROUPED, GROUPED_COUNT, SOURCE_BODY, CHILD, WRITE }
enum TreatUse {
    SELECT, ORDER, GROUP_BY, AGGREGATE, EXPRESSION, PREDICATE, NULL_TEST, NEGATED, DISJUNCTION,
    LEFT_JOIN, INNER_JOIN, SUBQUERY, CORRELATION, TYPE
}
```

`preflight` has no default implementation. `TreatSite.uses()` is an immutable copy with enum-set semantics; consume its
accessors rather than deconstructing the record, since components may be added. `ExpectedNarrowing` and its `Kind` are
`@EngineFacing`; its `types` are a non-empty, unmodifiable copy of subtypes of `entity`, and a renderer refuses a `Kind`
it doesn't know (D-157). `refusal` and `preflight` return an empty optional on acceptance, or the reason for refusal.
The executor passes the factory's renderer through `RenderOptions#withTreatRenderer`, and the persistence unit's
metamodel, from which `ofType` reads its subtypes, through `RenderOptions#withMetamodel` (D-158); scratch options retain
both. There is no `VendorProfile#treats` and no `MQ4015` treat fact (INV-6). With no renderer, every treated node,
including a redundant treat, is `MQ4014` at build time, before any statement, across content, count, export and writes
(R-COL-34, AC-VND-16).

**Sites before the first statement (the engine half).** With a renderer, the executor derives every `TreatSite` of
a call from the definition and the call, not from the statements built so far, and checks each against `refusal`
before the first statement; a refusal is `MQ4016`, naming the model, the attribute, the shape, the uses, the root's
strategy and the reason. Nothing is cached: each call checks again. A site is one treated read in one statement:

- `entity` is the entity the treated node treats and `subtype` its subtype; `attribute` is the attribute path read
  through it, empty for a treated join's own rendering;
- `shape` is the statement it sits in: `CONTENT` (a list, page, keyset or export page, `one`, `byKeys`), `COUNT` (a
  total or `count(q)`), `GROUPED` (a statement with `GROUP BY` or an aggregate select), `GROUPED_COUNT` (the count of
  a grouped statement's groups, which the engine counts client-side, its sites checked as `GROUPED`, when the
  renderer refuses it; `engine/20` R-EXE-03, D-159), `SOURCE_BODY`, `CHILD` (a
  fetch plan's child statement, its join plans' selections and its declared columns, enrichers' included) or
  `WRITE` (a bulk write's `where` and a returning model). A site in a sub-select takes its statement's shape and
  adds `SUBQUERY`;
- `anchored` is true in a `CONTENT` and a `COUNT` statement, which the engine anchors (`api/10` R-COL-35), and in
  an ungrouped `SOURCE_BODY` over an entity root, which selects its root id (`api/17` R-SRC-20); it is false in a
  `GROUPED`, `GROUPED_COUNT`, `CHILD` or `WRITE` statement, in a recursive body or one over another source, and in a
  sub-select, since no anchor covers the sub-select's own root. A correlated count's
  counted column over its own treated root (`SubSelect.of(ROOT.treat(Sub.class).id)`-style) is the one anchored site
  in a sub-select: the count reads the treated root itself, so its `{SELECT, AGGREGATE, SUBQUERY}` site stays anchored
  and is not refused as an aggregate (R-COL-31); a treated node elsewhere in a correlated value — a counted column,
  condition, correlation or order over a sub-select whose root is not treated — is unanchored;
- `uses` is the site's own use and every enclosing context: `not(isNull(x))` is `{NULL_TEST, NEGATED}`, an operand
  inside `or` adds `DISJUNCTION`, an order key selected as well (R-QRY-04) gives a `{SELECT}` and an `{ORDER}` site,
  and a primary-key column the order lacks, which the executor appends as a tie-breaker, is an order key too.

The sites cover the content statement, the count of a `page(..., COUNT)` or `count(q)` (only predicates and
row-changing joins: a select-only or order-only treat is absent, R-EXE-05), each source body and each child statement.
A keyset query's later pages, which page 1 renders none of, add per treated key `{PREDICATE, DISJUNCTION}` from
R-PAG-06's OR-expansion and, under an explicit null precedence, `{PREDICATE, DISJUNCTION, NULL_TEST}` and a top-level
`{NULL_TEST}` for a page after a NULL cursor value; a keyset `export` adds them too, so it is refused before it writes
a row. Whatever the renderer answers, the engine refuses with `MQ4016` in M28: every `CHILD` site, every `WRITE`
site (a treated `ModelUpdate`/`ModelDelete` `where`, decided before R-WRT-11 picks a path, and a treated returning
model of any returning write or `persist`), and a site whose uses hold `GROUP_BY` or `AGGREGATE` (Q-27), except a
correlated count's column over its own treated root (R-COL-31). The engine also refuses a non-join correlation target
with `MQ4016` rather than casting it. A `CORRELATION` site, which a path `exists` nested under an `exists` whose path
holds a treat carries and a lifted `Outer.column` read carries too (D-164), is left to the renderer: Hibernate's table
refuses it with `MQ4016`.

**The provider half (M28.3).** `model-query-hibernate`'s `treats(emf)` serves a `TreatRenderer` on the 6.6.x and 7.4.x
minor lines only, read from `org.hibernate.Version`; on any other line, an unreadable version, or under
`hibernate.jpa.compliance.query=true`, it is empty and every treated node is `MQ4014`, with one WARN per factory naming
the reason (D-155). `treat` calls `CriteriaBuilder#treat` for a `Root` and every join type. `refusal` answers from the
spike's measured cells (`rfc/0016-spike-data/renderer-allowlist.tsv` and `statement-matrix.tsv`): a use set is admitted
only where every recorded case passed on both lines, all three vendors and every strategy, and anything the table
doesn't name is `MQ4016` (D-156). Admitted anchored `CONTENT` sets: `SELECT`, `ORDER`, `EXPRESSION` with `SELECT`,
`ORDER` or `PREDICATE`; `PREDICATE` alone or with any of `NEGATED`, `DISJUNCTION`, `SUBQUERY`; `NULL_TEST` alone or
with any of `NEGATED`, `DISJUNCTION`;
`{PREDICATE, NULL_TEST, DISJUNCTION}` (the `IS NULL` branch of `ne`, `notIn`, R-FLT-04) with or without `SUBQUERY`;
`LEFT_JOIN` or `INNER_JOIN` with `SELECT`, `ORDER`, `PREDICATE`, `NULL_TEST`, `{NULL_TEST, NEGATED}` or
`{PREDICATE, NULL_TEST, DISJUNCTION}`; and a correlated count's `{SELECT, AGGREGATE, SUBQUERY}` on its own treated
root (R-COL-31), but not its `LEFT_JOIN` form, which treats twice when anchored (D-156). Admitted anchored `COUNT`
sets: the same predicate and `NULL_TEST` sets
(the three null-accepting ones as `count(root.id)`, `S2_FRESH_COUNT_ID`), their `LEFT_JOIN` and `INNER_JOIN` forms
of `PREDICATE`, `NULL_TEST`, `{NULL_TEST, NEGATED}` and `{PREDICATE, NULL_TEST, DISJUNCTION}`, and a treated join a
count keeps, `{INNER_JOIN}` or `{LEFT_JOIN}` with no attribute. A `SINGLE_TABLE` column more than one entity of the
hierarchy maps (AC-COL-51) is admitted in `CONTENT` `SELECT`, `ORDER`, `{SELECT, EXPRESSION}` and the unjoined
predicate and `NULL_TEST` sets without `SUBQUERY`, and in `COUNT` only in the `or` cells that passed on the spike's
count path: the predicate and `NULL_TEST` sets with `DISJUNCTION` and without `SUBQUERY`, less
`{PREDICATE, NULL_TEST, DISJUNCTION}`, which a standalone `ne` also reports. A dotted path's column is its first
segment's; a join or path through a shared column, or an attribute whose columns the renderer can't read, is refused on
`SINGLE_TABLE` (D-156). A standalone predicate on it in a `COUNT` is refused until the root-id count is
measured there (`rfc/0016` §10). A mapping variant (`@DiscriminatorOptions(force = true)`, `@DiscriminatorFormula` or a
`@SecondaryTable` on `SINGLE_TABLE`; a `JOINED` discriminator column, or either `JOINED` discriminator setting) keeps
the table less what its runs didn't measure, the three null-accepting `COUNT` sets; a shared column in a variant, and
a variant on another strategy, is refused. An anchored `SOURCE_BODY` admits `{SELECT}`, `{NULL_TEST}` and
`{SELECT, TYPE}` on the three core strategies over an unshared column, the type except on a `JOINED` root without a
physical discriminator, and nothing on a variant or a shared column (`api/17` R-SRC-20, D-162). A
correlated outer node, an unanchored site and every non-`TYPE` `GROUPED`, `GROUPED_COUNT`, `CHILD` and
`WRITE` site are refused; a `GROUPED_COUNT` type site is admitted as its `GROUPED` cell, except on a `JOINED` root
without a physical discriminator (D-159).
The mapping gates are `MQ4016` for the whole hierarchy: a subtype `@SQLRestriction` (ignored by 6.6, refused at
bootstrap by 7.4), a `@DiscriminatorValue("null")` or `("not null")`, an `@IdClass` or `@EmbeddedId` id, and other
`@DiscriminatorOptions`; a root `@SQLRestriction` runs. The mapping is read from the entity persisters once per
factory, and `treats(emf)` returns the same renderer for a factory (D-155).

**The statement preflight (M28.4, `rfc/0016` §11 guard 3).** With a renderer and a definition whose own statement, or a
source body it reads, holds a treated node, the executor builds every select statement the call will run and hands each
to `preflight`, with its shape, before the call's first statement: the content statement, the count, a primary-key-first
page's two steps, `byKeys`' first chunk and, when it is built again smaller, the build that runs, `byKeys` with no keys
too, and for a keyset page or a keyset `export` every later page shape, over every combination of NULLs at the keys
whose cursor value can be NULL (all non-NULL included), with placeholder parameters. The `mq_0id` anchor and `MQ2214`
still key on the top statement's own treat (D-165). A non-empty answer is `MQ4016` naming the model, the statement, its
shape and the reason; no JDBC statement has run. Without a treated node nothing is built for it and `preflight` is not
called. Nothing is cached: each call preflights again (INV-9). The site check runs first, so the preflight never widens
the table. Writes stay refused by the table (`WRITE`), so only select statements are preflighted. The preflight runs at
execution only: on R-VND-20's offline factory the renderer accepts every statement, so `PortabilityCheck` reports each
target's treat sites from the table alone, `MQ4014` without a renderer and `MQ4016` outside the table, with no database
(AC-VND-17, D-163).

*Engine-declared narrowings (D-157).* Each statement comes with the `ExpectedNarrowing`s its own build rendered on
purpose, in build order, compared as a multiset: an `EXISTS_ROOT` for the correlated root of a path `exists` read
through a treat, naming every subtype it was treated as, unless an `or` in that `exists` reads a column of the treated
root in some of its branches only, where Hibernate reads the root whole. Nothing else is declared: an `in` sub-select's
own treated root, a `CASE WHEN EXISTS` and a correlated count's own treated root (which neither line narrows) are not,
so a narrowing there is `MQ4016`. A `TYPE_RESTRICTION` names a node a non-empty `ofType` restricts to the rendered
classes, declared only for an `ofType` that is a top-level conjunct of a select statement's own `where`, where a
narrowing to them loses no row the filter keeps; none under `or`, `not`, a `CASE`, `add(...)` or in a sub-select
(`api/12` R-FLT-28, D-158).

*Hibernate's preflight.* `model-query-hibernate` translates the criteria statement to Hibernate's SQL AST with no
JDBC call, through a per-line adapter for 6.6 and 7.4, and walks every query spec, its CTE bodies, derived tables
and sub-selects included. Each `from` root is checked against the rows its own entity holds: a `SINGLE_TABLE` root
read from a derived table restricted to some discriminator values, a `TABLE_PER_CLASS` root read from a union that
lacks one of its entity's tables, a `JOINED` root `INNER` joined to a table that holds only some of its subtypes'
rows, and a discriminator restriction in the `where` that leaves out a concrete subtype are narrowings, the
discriminator a column, a formula, `TABLE_PER_CLASS`'s `clazz_` or `JOINED`'s `CASE`. A subtype
root is expected; a `LEFT` join and a `CASE` guard don't narrow a root. A declared `TABLE_PER_CLASS` treated join is
also checked against its translated target union (D-161). Every branch outside the declared subtype's tables must
have a NULL key in a top-level join equality, or an effective discriminator restriction to the subtype in the
`where`, the join's own `ON`, or an `INNER` join from its target. A `CASE` guarding selected inherited columns does
not exclude a matching branch, so an `INNER` target that retains its real key is `MQ4016`. A to-one `LEFT` target
may retain it only when every target column read in `select`, `where`, `group by`, `having` and `order by`, the
hidden presence key and later-page keyset predicates included, is NULL for that branch: the branch projects NULL,
or the value has a subtype discriminator `CASE` guard with a NULL `ELSE`. Columns read through joins nested under
the treated group count as target reads. Otherwise it is refused too. Collect declarations inside derived roots,
derived joins and FROM-function arguments; a TPC union join with no recorded treated declaration, including inside
a derived count's `QueryPartTableReference`, fails closed. An unknown join, union, key or guard shape fails closed.
A narrowed sub-select
root is expected only when it consumes a declaration: its sub-select is a direct operand of an `exists` in its
parent's `where` or `having` (under `AND`, `OR` and `NOT`), correlated to an untreated parent root by a top-level `=`
of their single id columns, and it reads what a treat as the declared subtype renders (on `TABLE_PER_CLASS` the union
may also keep the root entity's own table for a subtype with subtypes of its own, when that table's rows read a null id
or a `clazz_` restriction to exactly the subtype is a top-level conjunct of the `where` or of an `INNER` join's
condition; a discriminator restriction on it must admit the declared subtype). An
unattributed narrowing, a declaration left unconsumed, an unknown kind, a declaration of several subtypes, two
declarations of one kind and entity naming different subtypes, and an AST shape the walk doesn't recognise are refused:
it fails closed (AC-VND-21).

**R-VND-23** *(D-167, D-169, `@Incubating`)* Whether a filter operator's `OperatorFeature` (`ILIKE`, `REGEX_MATCH`,
`ARRAY_CONTAINMENT`, `JSONB_CONTAINMENT`, `FULL_TEXT`) can run is two facts, both checked when the filter is
rendered, before any statement (`api/12` R-FLT-20), mirroring R-SRC-11's checks for sources:
1. **The vendor.** `VendorProfile#operators(OperatorFeature)`, `false` by default. PostgreSQL answers all five `true`,
   H2 only `ILIKE`, MySQL none; `OTHER` keeps the default. False is `MQ4015`, naming the feature, the vendor, the
   operator and the column. It runs first, because the vendor is the real reason an operator can't run. A database
   fact, no SQL (R-VND-11, INV-6).
2. **The provider.** `ProviderSupport#operators(EntityManagerFactory emf, OperatorFeature feature)`, `false` by
   default, so with no `ProviderSupport` every feature is refused. False is `MQ4014`. It takes the factory, as
   `constants(emf)` does, because a `ProviderSupport` is a stateless `ServiceLoader` service; Hibernate's answer is
   `model-query-hibernate`'s (`rfc/0017` §4).

A feature means the catalogue's rendering: a vendor answers `true` only where that SQL runs, and a provider only where
its functions are served (D-171, D-175). A user operator that renders its own SQL should require no feature, since the
vendor and provider answers say nothing about its SQL (INV-6).

The executor asks both for every constant once, when it is built, into a `core.OperatorGate` (two immutable feature
sets and the vendor's name) that `RenderOptions.withOperatorGate(gate)` carries to every statement it renders: queries,
counts, exports, writes, child loads and conflict clauses. `core` sees only the gate, never a profile (INV-6, INV-7).
With no gate (`RenderOptions.portable()`, `JoinContext.of(root, cb)`) an operator that requires a feature is
`MQ4014`. `checkPhases`' scratch build falls back to a stand-in gate that serves every feature, as D-132's scratch
sequence renderer does, so a query with an `op` and a customizer is not refused there for a fact the scratch lacks; the
executor's own gate, when it passes one, still decides. An operator that requires no feature is never gated.

**R-VND-24** *(D-167, D-169, `@Incubating`)* An operator's value is a parameter typed by the column's mapping where the
provider can type one (`api/12` R-FLT-21). `core.OperandBinder` has one abstract method, `<T> ParameterExpression<T>
parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type)`: a parameter of `type` whose binding follows
`typedBy`'s mapping (an `@Enumerated(STRING)` enum as its name, a `@Convert` value through its converter), a new
parameter on every call. Its default `boolean typesBy(Expression<?> typedBy)`, `true` unless overridden, is asked first:
where it is `false`, an expression whose type the provider knows only from a Java class, the engine binds the value as
with no binder, by the no-binder rule (D-168, D-170). `ProviderSupport#operands(EntityManagerFactory emf)` supplies it,
empty by default; the executor resolves it once, when it is built, and `RenderOptions.withOperandBinder(binder)` carries
it with the gate. The engine sets the value on the parameter per statement, as for every `JoinContext` parameter. It
types the `<C, C>` form only, by the rendered column (or aggregate) expression; the `<C, V>` form is typed by its
declared `valueType` through `cb.parameter` and keeps the no-binder rule. A binder that throws a `RuntimeException`,
returns `null` or returns a parameter the statement already binds (one value per parameter, so the later would replace
the earlier, INV-5) is `MQ1502` naming the operator and the column, before any statement; a `ModelQueryException` it
throws keeps its own code. It never falls back to a parameter typed by the value's class. Hibernate's binder is
`model-query-hibernate`'s (`rfc/0017` §2 step 4, D-170): a `JpaCriteriaParameter` whose anticipated type is `typedBy`'s
basic type, so it stays `?` under `hibernate.criteria.value_handling_mode=inline`, on the 6.6.x and 7.4.x lines only; on
any other line `operands(emf)` is empty, with one WARN per factory. It types only a path or an aggregate over one
(`DISTINCT` included); `typesBy` is `false` for any other expression, a function, `coalesce`, `CASE`, arithmetic, a
literal, a sub-query or an aggregate over one of these, whose node type Hibernate may infer from a Java class alone. A
node type the line doesn't expect (on 6.6 anything but a basic type or a path source whose `getSqmPathType()` is one, on
7.4 anything but a basic type, an entity path's included) makes it throw, so `MQ1502`. On 6.6 a primitive attribute's
node type, the JPA basic type of an `int` or a `boolean`, which carries no converter or enum mapping, binds too (D-179).

Its `@Incubating` default `boolean converts(Expression<?> typedBy)`, `false` unless overridden and asked only where
`typesBy(typedBy)` is `true`, says whether a value typed by `typedBy` goes through a converter of the mapping when
bound, a JPA `AttributeConverter` (`@Convert` or auto-applied); where it is `true`, `eqIgnoreCase` and `likeIgnoreCase`
bind their value through `parameter` under `lower(?)` (`api/12` R-FLT-07, D-177). Hibernate's binder answers from the
basic type `parameter` binds by, behind the same `typesBy` check and the same 6.6 unwrapping, so it can't drift from
what it binds: `true` exactly when that type's value converter is a `JpaAttributeConverter`. An enum's built-in
conversion (`@Enumerated`, `@EnumeratedValue`), a converter Hibernate applies that isn't a JPA `AttributeConverter` (on
7.4 an `AttributeConverterInstance`, which `@Convert` and auto-applied mappings don't produce), and a node type the line
doesn't expect answer `false` rather than throw, so the column renders as with no conversion.

**R-VND-27** *(D-177, D-182, `@Incubating`)* `ProviderSupport#inlineCounts(EntityManagerFactory emf)` is an `@Incubating` default method
returning `false`: whether the factory's database renders a window function over the page statement (`engine/20`
R-EXE-15), true only where `sources()` is present and its window renders `COUNT`. The executor asks it once, when it is
built, and only where `sources()` is present, so a `ProviderSupport` answering true with no source renderer never
reaches `SourceRenderer#window`'s `MQ4014` default where `COUNT` works (fallback 1). The window renders through the
existing core `SourceRenderer#window(cb, WindowSpec)` (`api/17` R-SRC-04) as `count(1) over ()`: no new renderer type,
no new `mq_*` function and nothing in `RenderOptions` that `core` can't see (INV-7). `model-query-hibernate` answers
from the dialect's `supportsWindowFunctions()`, as `operators(emf, f)` answers from the dialect (D-171, D-175); its
`sources()` is unconditional, so the two facts agree. Nothing is refused, so no `VendorProfile` fact and no
`PortabilityCheck` report are needed (R-VND-11, INV-6).

**R-VND-28** *(D-177, `@Incubating`)* `ProviderSupport#inlinesValues(EntityManagerFactory emf)` is an `@Incubating`
default method returning `false`: whether the factory renders a criteria value given without a parameter as a literal.
The executor asks it once, when it is built, and carries the answer in `RenderOptions#inlinesValues()`, as it does the
operand binder; where it is `true` every filter value binds as an explicit parameter by the operator rule (`api/12`
R-FLT-08, R-FLT-21), and where it is `false` every filter renders as before, so no SQL changes. `model-query-hibernate`
answers from the session factory's options, `getCriteriaValueHandlingMode() == INLINE`
(`hibernate.criteria.value_handling_mode=inline`), on any line, whether or not its binder serves that line (R-VND-24).
Without `model-query-hibernate` the answer is `false`: `jpa` reads no Hibernate key, which would put provider
knowledge outside `ProviderSupport` (INV-7), so a factory inlining values with no `ProviderSupport` renders its
filter values as the provider does.

**R-VND-29** *(D-185, `@Incubating`)* `VendorProfile#nativeNullSafeEquality()` is an `@Incubating` default method
returning `false`: whether the database runs its native null-safe equality as an index lookup where the portable `OR`
form reads every NULL key (`api/12` R-FLT-29). `true` on MySQL and H2, `false` on PostgreSQL and `OTHER`. A database
fact, no SQL (R-VND-11, INV-6).

`VendorProfile#aggregates(AggregateFeature)` is a second `@Incubating` default method returning `false` (D-186): whether
the database serves an aggregate feature. `AggregateFeature` (`@Incubating`, in `core`, as `OperatorFeature`) has one
constant, `FIRST_BY` (`api/13` R-AGG-16), `true` on PostgreSQL and H2, `false` on MySQL and `OTHER`. The executor folds
the answers into an immutable `AggregateGate` carried by `RenderOptions`, as `OperatorGate` does for R-VND-23, so
`core` sees no profile; nothing consumes `FIRST_BY` until `firstBy` ships.

**R-VND-30** *(D-185, `@Incubating`)* `ProviderSupport#nullSafeEquality()` is an `@Incubating` default method returning
an empty `Optional<NullSafeEqualityRenderer>` (`@EngineFacing @Incubating core.NullSafeEqualityRenderer`, with
`notDistinctFrom(cb, a, b)` and `distinctFrom(cb, a, b)`). The executor folds it into `RenderOptions` only when the
profile answers `nativeNullSafeEquality()` `true`, as it does `sequences` (R-VND-18); a vendor answering `true` with no
hook, or a provider with the hook on a vendor answering `false`, renders the portable form, with no error.
`model-query-hibernate` implements it with `HibernateCriteriaBuilder#notDistinctFrom` and `distinctFrom` on both lines;
`jpa` imports no `org.hibernate` (INV-7).

`ProviderSupport#aggregates()` is a third `@Incubating` default method returning an empty
`Optional<AggregateRenderer>` (`@EngineFacing @Incubating core.AggregateRenderer`, D-186). Its `filtered(cb, kind,
argument, condition)` renders a filtered aggregate (`api/13` R-AGG-17); the default, and the engine's form with no
hook, is `fn(case when condition then argument end)`. Unlike R-VND-30's null-safe hook it needs no database fact: the
provider only improves the SQL shape. `model-query-hibernate` implements it with `HibernateCriteriaBuilder`'s
`count`, `sum` and `avg` overloads taking a `JpaPredicate` and `functionAggregate` for `min` and `max`, so PostgreSQL
and H2 render `FILTER (WHERE ...)` and MySQL Hibernate's CASE emulation; `countDistinct` keeps the CASE form. `jpa`
imports no `org.hibernate` (INV-7).

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
| AC-VND-16 | No renderer, a Hibernate minor outside 6.6 and 7.4, or compliance mode: every treated node, `typeOf` and `ofType` is `MQ4014` before any statement in content, count, export and writes; `model-query-hibernate` serves the renderer on the 6.6.x and 7.4.x lines only and logs one WARN naming the reason, once per factory, and builds one renderer per factory (R-VND-26, D-154, D-155). |
| AC-VND-17 | A site outside the table is `MQ4016` before any statement, an export's later page shapes and its count included; a treated node in a `CHILD` statement is `MQ4016`; the table's answer is per site, keyed by shape, anchoring, use set, strategy and shared column, on both Hibernate lines; `PortabilityCheck` reports the same sites offline, from the table alone (M28.8) (R-VND-26, D-154). |
| AC-VND-18 | A subtype `@SQLRestriction` on 6.6, a null/not-null discriminator value and an `@IdClass` or `@EmbeddedId` hierarchy are `MQ4016`; a root `@SQLRestriction` runs; each admitted mapping variant runs under its own cells (R-VND-26, D-154). |
| AC-VND-19 | Every admitted cell a definition reaches runs as a TCK case per strategy on every Tier-1 vendor and both lines, asserting ids, values, the row count and the total, on each mapping variant as well; each admitted `COUNT` cell as `count(root.id)`; probing the renderer with synthesized sites, per strategy, shape, anchoring, shared column and variant, admits exactly the use sets the cases run, so a set no definition reaches is refused (D-156) (R-VND-26, D-154). |
| AC-VND-20 | A treated `ModelUpdate`/`ModelDelete` `where` and a treated returning model (`updateReturning`, `deleteReturning`, `persist`) are `MQ4016` before any statement, on every strategy, vendor and path, key-first included (R-VND-26, D-154). |
| AC-VND-21 | On both lines and every strategy, the preflight of an unanchored `count(root)` over `isNull` is `MQ4016` and of its anchored form empty, with no JDBC statement; each later keyset page shape, over every combination of NULL cursor values, is preflighted before page 1, a keyset
`export`'s too, and `byKeys` with no keys and its first chunk built again; a `TABLE_PER_CLASS`, `JOINED` or formula
discriminator restriction leaving out a subtype is refused; an unrecognised AST shape and a narrowing it can't attribute are `MQ4016`; a declared path `exists` root, `not` and `or` forms included, is admitted and a wider, narrower, sibling, missing, left-over or two-subtype declaration refused; an `or` reading the treated root in some branches only declares nothing; a nested `TABLE_PER_CLASS` union keeping its root's table is admitted under a null id or an exact `clazz_` restriction and refused under a `CASE`-guarded inherited column; a correlated count over its own treated root passes with no declaration; a `TABLE_PER_CLASS` treated `INNER` target
retaining a concrete base or sibling branch with a real join key and no effective subtype restriction is `MQ4016`
before any statement, while a NULL join key still admits the subtype-only selection; a to-one `LEFT` target
keeps an empty Optional for a base, sibling or absent target and a present Optional for a subtype with NULL values,
and an unguarded presence key is refused; grouped derived counts, later keyset pages, streams and unguarded reads
through nested target joins are checked before any statement; admitted joins page through the entire fixture;
unknown target shapes fail closed; every admitted cell passes the
preflight (R-VND-26, D-157, D-161). |
