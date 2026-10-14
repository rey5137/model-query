# 16 — Query Inspection and Test Support

**Covers:** the read-only view of what a built `ModelQuery` filters on, in `where` and `having` (`ModelQuery.conditions()`),
labelled custom filters, and the `model-query-test` module that asserts on that view in a unit test with no database.
**Read when:** a test checks which filters a request turned into, or a log needs to say more than "2 filters".
**Owns:** `R-INS-*`, `AC-INS-*`. The `Filters` DSL itself is `api/12`; `having` is `api/13`; the module rules are
`delivery/61`.

---

## 1. Shape

```java
// Service under test: turns a request into a query and hands it to a mocked repository.
ArgumentCaptor<ModelQuery<OrderEntity, ?, OrderView>> query = ArgumentCaptor.forClass(ModelQuery.class);
service.search(new OrderSearch(Optional.of(OPEN), Optional.empty()));
verify(repository).findPage(query.capture(), any(), any());

assertThatQuery(query.getValue())
        .hasFilters(eq(QOrderView.STATUS, OPEN))              // exactly these, in any order; the skipped one is absent
        .isOrderedBy(QOrderView.CREATED_AT.desc());

assertThatQuery(q).hasNoFilters();
assertThatQuery(q).containsFilter(or(eq(QOrderView.STATUS, OPEN), isNull(QOrderView.CLOSED_AT)));
assertThatQuery(q).containsFilter(exists(QOrderView.ITEMS_TABLE, gt(QOrderView.ITEM_QTY, 0)));
assertThatQuery(q).containsFilter(custom("region visible to user"));      // a labelled add(...)
```

```java
// core, @Incubating
QueryConditions conditions = q.conditions();     // where and having; immutable. Order stays q.orderBy().
List<Condition> where = conditions.where();      // top-level AND, in the order the filters were added
Condition c = where.get(0);
c.kind();      // Condition.Kind.EQ
c.column();    // Optional<SelectField<?, ?>>: QOrderView.STATUS
c.values();    // List<Object>: [OPEN], as passed, before any converter
c.children();  // List<Condition>: the operands of AND, OR, NOT, EXISTS, NOT_EXISTS
c.right();     // Optional<SelectField<?, ?>>: the other column of a compare(...)
c.op();        // Optional<Op>: the operator of a compare(...)
c.likeMode();  // Optional<LikeMode>: LIKE and LIKE_IGNORE_CASE
c.path();      // Optional<TableField<?, ?>>: the join path of EXISTS and NOT_EXISTS
c.label();     // Optional<String>: a labelled add(...)
c.subSelect(); // Optional<SubSelect<?, ?>>: the sub-select of the four *_SUBSELECT kinds (R-INS-08)
c.operands();  // List<ScalarField<?, ?>>: the operand fields of IN_FIELDS and NOT_IN_FIELDS (R-INS-09)
```

`Condition` and `QueryConditions` are final classes, not records, with no public constructor or factory: only the DSL
builds them (D-101). `Condition.Kind` is an enum that may gain constants in a later release, so a `switch` on it needs
a `default`:

| Kind | Recorded by | Fields set |
|---|---|---|
| `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE` | the comparison of that name, and a one-sided `range` or `between` | `column`, one value |
| `RANGE`, `BETWEEN` | `range` and `between` with both bounds | `column`, `[from, to]` |
| `IN`, `NOT_IN` | `in`, `notIn` | `column`, the values (none for an empty collection) |
| `LIKE`, `LIKE_IGNORE_CASE` | `like`, `likeIgnoreCase` | `column`, one value, `likeMode` |
| `EQ_IGNORE_CASE` | `eqIgnoreCase` | `column`, one value |
| `IS_NULL`, `IS_NOT_NULL` | `isNull`, `isNotNull` | `column` |
| `COMPARE` | `compare(left, op, right)` | `column`, `op`, `right` |
| `AND` | an `or` branch of two or more filters | `children` |
| `OR` | `or` | `children`, one per branch |
| `NOT` | `not` | `children` (an implicit AND) |
| `EXISTS`, `NOT_EXISTS` | `exists`, `notExists` | `path`, `children` (an implicit AND) |
| `CUSTOM` | `add` | `label` when given |
| `IN_SUBSELECT`, `NOT_IN_SUBSELECT` | `in`, `notIn` over a sub-select | `column`, `subSelect` |
| `IN_FIELDS`, `NOT_IN_FIELDS` | `in`, `notIn` against operand fields (`api/12` R-FLT-19) | `column`, `operands()` |
| `EXISTS_SUBSELECT`, `NOT_EXISTS_SUBSELECT` | `exists`, `notExists` over a sub-select | `subSelect`, `children` (the correlation, an implicit AND) |

`having` records the same kinds over aggregate columns, except `EXISTS`, `NOT_EXISTS`, `CUSTOM` and the four
`*_SUBSELECT` kinds, which it has no operator for.

## 2. Rules

**R-INS-01** **Every filter records what it is.** Each `Filters` operator (`api/12` §1) and each `having` operator
(`api/13`) records a `Condition` at the one point where it records its predicate, so no predicate can be added without
its condition. The view is the logical tree of the `where` and `having` calls: the kind, the column or path, the bound
values as passed (before any converter, and not lower-cased for the `IgnoreCase` operators), and the children. A
one-sided `range` or `between` records the comparison it renders (`GTE`, `LT` or `LTE`). Rendering may simplify the
tree (`ne` adds `OR IS NULL`, a long `IN` is chunked, an `or` left with one branch renders that branch); predicates
from a `QueryCustomizer` and those the executor adds (keyset, child keys) are not in it.

**R-INS-02** **Skipping is visible as absence.** A filter skipped by an empty `Optional` (R-FLT-01, R-FLT-03) records
nothing; an `or`, `not` or `exists` whose inner filters were all skipped records nothing, exactly as it renders
nothing. `in(col, List.of())` records `IN` with no values and `or(List.of())` records an `OR` with no children, since
both render `FALSE` (R-FLT-02, D-92); `notIn(col, List.of())` records `NOT_IN` with no values, though it renders
nothing.

**R-INS-03** **Custom filters are opaque but nameable.** `add(custom)` records `Condition.Kind.CUSTOM` with no label;
the new overload `add(String label, custom)` records the label, and a null or blank label is `MQ1301`. Labels are
printed in logs and failure messages, so a label is a constant, never a value. Nothing else about a custom predicate
is inspected.

**R-INS-04** **The view is a value.** `conditions()` returns an immutable, thread-safe tree (INV-9), equal for two
queries built from equal calls; `withFetch` and `orderedBy` copies share it. Columns compare as `ColumnField` does,
expressions structurally (`api/10` R-COL-20), paths by their key, `CUSTOM` conditions by label alone. Values are held by reference: a mutable value changed after
the call (a `java.util.Date`) changes the view.

**R-INS-05** **Logs name conditions, never values.** `QueryConditions.toString()`, `Condition.toString()`, the D-95
build log and the D-95 `update` and `delete` logs list each condition's kind and column, with values shown as `?`, since
bound values can be personal data; only the accessors expose the values. `ModelQuery.toString()` stays the model's name.
The text format is not API.

**R-INS-06** **The test module reads the public view and a plan's child loads.** `model-query-test` depends on `core`
and AssertJ and nothing else (INV-7); its assertions use `conditions()`, `select()`, `orderBy()` and
`fetch().map(FetchPlan::select)`, never an internal type. It needs no `EntityManager`, metamodel or database.
`conditions()` covers the query's own `where` and `having` only; a fetch plan's child queries are asserted through
`assertThatQuery(q).child(field)`, which reads `FetchPlan.childLoads()` and each `ChildLoad`'s field, query and
`maxPerParent`, an `@EngineFacing` read (D-104); the other is `Outer.reference`, through which `outer(col)` builds the
lifted column it matches (R-INS-08, D-112). It asserts the child query's filters and order
(the `ChildQuery`'s, without the key match or the primary-key tie-breaker), its selection (the child plan's own, then
the child's `foreignKey` unless already selected, then its join plans', R-FCH-05; a `through` child adds no
`foreignKey`, R-FCH-14) and its `maxPerParent`, with the same matchers; a field the plan doesn't load fails, naming the
children it loads.

**R-INS-07** **Matchers mirror `Filters`.** `FilterMatchers` has one factory per `Filters` operator, with the same
name and the value form of its parameters (`eq`, `ne`, `in`, `like`, `isNull`, `between`, `compare`, `or`, `not`,
`exists`, …), plus `and(...)` for an `or` branch and `custom(label)`; no `Optional` overloads, and no `when` or `apply`,
which record nothing of their own. A matcher is a test-module type that matches a `Condition`; core exposes no way to
build one. `IN` and `NOT_IN` values match as multisets. `hasFilters` matches the top-level conditions exactly, as a
multiset, in any order; `containsFilter` matches one of them; a failure message prints the whole condition tree,
values included.

**R-INS-08** *(D-112)* **Sub-selects are recorded apart from value sets.** `in`/`notIn` over a sub-select record
`IN_SUBSELECT`/`NOT_IN_SUBSELECT` with the column and `subSelect()`; `exists`/`notExists` over one record
`EXISTS_SUBSELECT`/`NOT_EXISTS_SUBSELECT` with `subSelect()` and the correlation as `children`. They are not `IN` or
`EXISTS`, so code reading `values()` of an `IN` never mistakes a sub-select for an empty set. A sub-select equals one
built from equal calls (its column, root and conditions). A lifted column equals another lift of the same outer column,
whatever `S` or `Outer` made it; `Outer.referenced(column)` returns the outer column it lifts, and `toString` marks it
`outer.` with values shown as `?` (R-INS-05). `FilterMatchers` gains `in(col, sub)`, `notIn(col, sub)`,
`exists(sub, matchers…)`, `notExists(sub, matchers…)` and `outer(col)`.

**R-INS-09** *(D-115)* **Expressions are recorded as operands.** A filter over an expression records the operator's kind
with the expression as `column()` (or `right()`); no kind is added. An expression's own CASE conditions are not query
conditions. `toString` prints its structure with values as `?` (R-INS-05). `FilterMatchers` take a `ScalarField`
wherever `Filters` does.

*(D-128)* The field forms of `in`/`notIn` (`api/12` R-FLT-19) record `IN_FIELDS`/`NOT_IN_FIELDS` with the column and
`operands()`, and `values()` is empty for them, as for `IN_SUBSELECT` (R-INS-08). Both are `@Incubating`. They bind
nothing, so `toString` prints no `? x n` placeholder.

**R-INS-10** *(D-133, `@Incubating`)* **The portability dry run's factory.** `model-query-portability` (on `jpa` only,
INV-7; test scope is the consumer POM's choice) boots one factory per persistence unit and `Target`, with jakarta
types only and no database. `Target.of(vendor)` or `Target.of(vendor, "2.3.232")`: the full version string sets the
product version, major and minor; with none, the provider line's minimum dialect version applies (Hibernate 6.6 and
7.4: H2 2.1.214; PostgreSQL 12.0 and 14.0; MySQL 8.0; MariaDB 10.4 and 10.6), so name the version deployed. The product
name is the reverse of detection's (`vendor/40` R-VND-04 step 3), so a target is H2, PostgreSQL, MySQL or MariaDB;
any other vendor, or a version not starting with a major number, is `MQ4013`.

- **Unit fidelity.** A named unit boots through `Persistence.createEntityManagerFactory(name, overrides)`; a class list
  through the provider's `createContainerEntityManagerFactory`, with the mapping files and properties (a naming
  strategy, converters, `orm.xml`) a class list does not carry. Mapping files belong to the class-list form alone: a
  named unit lists its own. The class list's provider is the one its `jakarta.persistence.provider` property names,
  else the single `PersistenceProvider` on the class path; none or several is `MQ4013`. The class-list unit is
  `RESOURCE_LOCAL`, excludes unlisted classes, and has shared cache and validation mode `NONE`. A Spring Boot
  application supplies the class list from Boot's `PersistenceManagedTypes`, its `spring.jpa.properties` as properties
  and `spring.jpa.mapping-resources` as mapping files.
- **Overrides**, last wins: the unit's properties, then the provider's `offlineProperties(vendor)` (`vendor/40`
  R-VND-20), then the dry run's own: `jakarta.persistence.transactionType` `RESOURCE_LOCAL`, the stub `DataSource` as
  `jakarta.persistence.nonJtaDataSource`, shared cache `NONE`, `schema-generation.database.action` and `scripts.action`
  `none`, and `database-product-name` with, for a versioned target, `database-product-version`, `-major-version` and
  `-minor-version`. The first `ProviderSupport` on `ServiceLoader` with offline properties for the vendor is used; none
  is `MQ4013`, naming those found, and so is one that does not serve the factory booted.
- **The stub `DataSource`** denies by default and writes to a per-call recording, a `ThreadLocal` the boot or call
  opens. It hands out a connection only to a call. `createStatement`, `prepareStatement` and `prepareCall`, every
  overload, record their SQL and throw an `SQLException`; transaction calls do nothing; `DatabaseMetaData` answers the
  product name and the target's version alone; any other call, `unwrap` to a vendor type among them, is recorded and
  throws. The recording, never the exception, decides: a provider may swallow the stub's failure, and Hibernate wraps a
  statement's in its own exception.
- **A boot that reaches JDBC is `MQ4013`**, read from the boot's recording, naming the unit, the target, the provider
  support and the calls recorded; the factory is closed.
- **Factory cache.** Keyed on the unit name or sorted class list, the mapping files, the properties, the provider
  support, the target's vendor and full version, and the thread's context `ClassLoader`. A factory boots once, on the
  first requesting thread, through a memoised `FutureTask`, never inside `computeIfAbsent`; a failed boot is not kept.
  No per-call state lives on the factory or the `DataSource`. Factories close in a JVM shutdown hook.

**R-INS-11** *(D-133, D-138, `@Incubating`)* **A portability check call.** `PortabilityCheck.of(unit, config)` or
`PortabilityCheck.of(List<Class<?>>, config)`, with `properties(Map<String, ?>)` and, for a class list only,
`mappingFiles(String...)` (R-INS-10), runs calls on every target: `targets(Target...)`, by default each of
`config.targetVendors()` at the line's minimum dialect version. A target outside a declared `targetVendors()` is
`MQ4012` when `targets` is called, since production would refuse that factory. `call(Class<E>,
Function<ModelQueryExecutor<E>, ?>)` and `call(String label, Class<E>, Function<...>)` take the real executor
signature; `callOutsideTransaction(...)`, in the same two forms, runs the call with no transaction, as the application
runs a `commitEachChunk()` write or a call R-QRY-17 checks. Calls collect, and `assertPortable()` runs each on each
target with one `EntityManager` and one recording per call and target, in a stub transaction unless outside one. After
the function returns, a lazy result (`Stream`, `Iterator`) is drawn by one element, an `AutoCloseable` one is closed,
and a call in a transaction is flushed, all as part of the call; the transaction then rolls back.

- **Harness configuration.** An explicit `config.vendor(...)` is left out, so each target's vendor is detected rather
  than every target running one vendor's profile; every other setting is kept. A configured `ChunkTransactions` is
  replaced by the harness's own, which runs each chunk on its own `EntityManager` of the offline factory, flushed and
  rolled back, since the application's cannot serve that factory (a false `MQ4004`); with none configured,
  `commitEachChunk()` stays the application's `MQ4004`.
- **Classification** reads the call's recording first and the cause chain second, never the top-level exception type,
  since the library wraps a chunk's failure in `ChunkedWriteException` and the provider wraps the stub's stop. A JDBC
  call other than a statement before the first statement is `HARNESS_ERROR`. With no statement recorded: a return is
  `NO_STATEMENT`; a `ModelQueryException` in the chain is `REFUSED`, with the innermost one's code and message; else a
  throwable of the provider's package (`org.hibernate`), a translation error, is `REFUSED` with its class name; else
  `HARNESS_ERROR`. With a statement recorded: a return, or the stub's stop in the chain, is `REACHED` with the first
  statement's SQL; else a `ModelQueryException` in the chain is `REFUSED_AFTER_STATEMENT`, an INV-5 or spec bug or a
  call that swallowed the stop then refused; else `HARNESS_ERROR`.
- **Portable** means `REACHED` (D-133): `assertPortable()` throws the JDK's `AssertionError` when any result is not,
  `REFUSED` reporting the target unserved. `REACHED(sql)` certifies the refusals placed before the first statement and
  that statement's rendering only, not a statement after it (R-INS-12's limits list).

**R-INS-12** *(D-133, D-138, `@Incubating`)* **The sweep, the skip and the report.** `sweep(Class<?>... holders)` reads
each holder's `static final` fields that hold a definition, in field-name order, and runs one default operation per
definition, labelled `Holder.FIELD operation`: `list(query, Limit.unlimited())` for a `ModelQuery`, `update` or
`delete` for a `ModelUpdate` or `ModelDelete`, `insert` for a `ModelInsert` or `ValuesInsert`, and `persist` for a
`ModelPersist`, since every library refusal of a persist precedes `em.persist`. Paging, export, stream, returning
writes and argument-dependent calls need `call`. Holders are explicit; there is no package scan (P-7).
`skip(holder, "FIELD", reason)` leaves one definition out; the reason is required.

- **Limits list: first statement only.** The check sees the first statement a call reaches and the refusals before it.
  What follows a first statement is not checked: a fetch plan's child statements; the second step of
  primary-key-first paging; the content statement of `page(..., COUNT)`; each round of a chunked, key-first or
  `throughEntities()` write, and their returning or conflict-arbiter translation; a values insert or persist whose id
  is drawn from a sequence or table (the reached statement is the draw); export pages after the first. The library's
  own refusals that depend on the vendor run before the first statement (D-138); what still follows one depends on
  data (`MQ2201`, `MQ2202`, `MQ2003`, `MQ2005`, `MQ2205`, `MQ2210`, `MQ2601`, `MQ2603`, `MQ2604`, `MQ2002`, `MQ2503`)
  or on the provider translating a later statement.
- **Marks.** The sweep marks a `REACHED` definition "first statement only" from its public facts, per target, erring
  towards marking: a query with a `primaryKeyFirst(...)` or a fetch plan loading a child at any depth; an update or
  delete with chunk options, `throughEntities()`, or on a profile without `targetTableInSubquery()` (key-first,
  R-WRT-11); an insert with chunk options; a values insert, or a persist not writing its id, whose root's id has a
  `@GeneratedValue` of `SEQUENCE`, `TABLE` or `AUTO` (one declared in `orm.xml` is missed). A `call` prints its
  reached SQL with no mark: the caller chose the operation.
- **Report.** One line per call and target, call by call, each call's targets in the order given:
  `label on TARGET VERSION: OUTCOME[ (first statement only)] detail`, the detail being the SQL reached, the refusal's
  message (`... after SQL` for `REFUSED_AFTER_STATEMENT`), or the throwable. An unlabelled call is
  `call N (Entity)`. After the results, one line per skipped definition, in sweep order: `Holder.FIELD skipped:
  reason`; a skip is not a result and is not counted. A failing check's `AssertionError` starts with
  `Not portable: F of N results failed`; a passing report is logged at `INFO`.
- **Loud failures.** Before any call runs, `assertPortable()` throws an `AssertionError` listing every setup problem:
  no target, no call, a swept holder with no definition, a `skip` of an unknown field, of a field that is not a
  `static final` definition, with a blank reason or of a holder no sweep reads, mapping files for a named unit, and a
  class list with no class. A check never passes vacuously.

## 3. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-INS-01 | Each `Filters` and `having` operator records its kind, column and values; `or`, `not` and `exists` record their children, and an `or` branch of two filters records an `AND`; a one-sided `range` records `GTE` (R-INS-01). |
| AC-INS-02 | A request with one of two `Optional` filters empty yields one condition; an `or` with every branch skipped yields none; an empty `in` yields `IN` and an empty `notIn` yields `NOT_IN`, each with no values (R-INS-02). |
| AC-INS-03 | `add(label, …)` records its label and `add(…)` none; a blank label is `MQ1301` (R-INS-03). |
| AC-INS-04 | `conditions().toString()` and the build log of a query with an `eq` on a string column contain the column and `?`, not the value (R-INS-05). |
| AC-INS-05 | A unit test in `model-query-test` asserts a query captured from a mocked `ModelQueryExecutor`, with no persistence provider on its test classpath (R-INS-06, R-INS-07). |
| AC-INS-06 | A failing `hasFilters` names the missing and the unexpected conditions (R-INS-07). |
| AC-INS-07 | `assertThatQuery(q).child(field)` asserts that child query's filters, order, selection and `maxPerParent`, and a failure names the child; a field the plan doesn't load fails, naming the children the plan loads (R-INS-06, D-104). |
| AC-INS-08 | Each of the four `*_SUBSELECT` kinds records its column, `subSelect()` and correlation; `Outer.referenced` returns the lifted column; sub-selects built from equal calls are equal; the new matchers match them and a failure prints the tree (R-INS-08, D-112). |
| AC-INS-09 | A filter over an expression records its kind with the expression as `column()`. `toString` and the build log show `?` for its values. An `eq` matcher built from equal `Expr` calls matches, and a failing one prints the tree (R-INS-09, R-INS-04, R-INS-05). |
| AC-INS-10 | On both Hibernate lines with no container, the TCK unit boots offline for H2, PostgreSQL, MySQL and MariaDB, with no version and with a full one (the dialect then at that version), recording no JDBC call, booted once per unit and target however many threads ask, a failed boot booted again on the next request; a unit's `hibernate.hbm2ddl.auto=create` and `scripts.action=create` are overridden (no script written), an explicit `hibernate.dialect` is overridden by the target's, and a unit `hibernate.connection.provider_class` is overridden so a call reaches the stub; a reached statement is recorded once through Hibernate's exception wrapping; a `ProviderSupport` without `offlineProperties`, and a boot that reaches JDBC (the metadata flag or H2's temporary-table settings dropped), are `MQ4013`, and so is a class list naming a provider not on the class path (R-INS-10, `vendor/40` R-VND-20). |
| AC-INS-11 | On both Hibernate lines with no container, the dry run on H2, PostgreSQL and MySQL reproduces every vendor-dependent refusal of M18 to M22 before any statement: a lock the provider would drop is `MQ4010` per line (H2's shared waits on both lines, its exclusive ones on 6.6 only) and a root on two tables is `MQ4010` everywhere (AC-VND-13); a returning update, delete, insert and upsert is `MQ4011` on MySQL (AC-VND-14, AC-WRT-42, AC-WRT-43); an arbiter or a named constraint is `MQ1804` on H2 and MySQL (AC-WRT-45); `nextval` is `MQ1509` in a select and at every write site on MySQL and in a filter, a having, an order, a group key or a distinct query everywhere (AC-COL-33, AC-WRT-46); a write filtering on a non-key reference is `MQ1004` everywhere and a returning write reading it `MQ1809`, or `MQ4011` on MySQL (AC-COL-34). Conversely, on each target that serves a call (the returning writes on H2 and PostgreSQL, a conflict `setExpression` everywhere (AC-WRT-44), the arbiter and named constraint on PostgreSQL, `nextval` selected, and written by a chunked update, a values insert and an insert-select, on H2 and PostgreSQL, an exclusive lock, a returning write reading a to-one id), the first statement equals that call's statement in the TCK's committed SQL snapshot for that vendor and Hibernate line (R-INS-11, D-133). |
| AC-INS-12 | On both Hibernate lines with no container: a sweep runs each `static final` definition of a holder once per target and reports one line each, marking a primary-key-first query, a child-loading query, a drawn-id persist and, on MySQL, an update and a delete "first statement only", and an identity persist and a plain list not; `skip` leaves a definition out and the report prints it once, `Holder.FIELD skipped: reason`; the report has one line per call and target with each outcome: `REACHED` with the SQL, `REFUSED` with the code, `REFUSED_AFTER_STATEMENT` when the lambda swallows the stop then refuses, `NO_STATEMENT` and `HARNESS_ERROR`; a lazy `Stream` and `Iterator` are drawn by one element and the stream closed; a `commitEachChunk()` call outside a transaction runs on the harness's `ChunkTransactions` where the application's fails `checkServes`, and is `MQ4004` with none; an explicit `vendor(...)` is left out; the targets default to `targetVendors()` and one outside them is `MQ4012`; no target, no call, a holder with no definition and a bad `skip` fail loudly before any call runs; the harness keeps every configuration setting but the vendor and `ChunkTransactions` (R-INS-11, R-INS-12, D-138). |
