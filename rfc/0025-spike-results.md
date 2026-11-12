# RFC 0025 acceptance spike: results

**1 Part C reach: keep with change; Part C ships.** Every child load's statement is reached on two placeholder keys with
no parent row, at depth 2 and 3, under a `@Join` nested model, under a parent rooted at a source, and key-grouped with
a catch-all (`verifyMissing`, `groupWhen`) and without one (`assumeInRange()`), on four targets, both value-handling
modes and both lines. The changes: a key-grouped load runs both placeholders in **one** run of the chosen values
(production's own grouping splits `0` and `1` into two groups of one key each), and the group is asked with the child
load as its owner, so the refusal message names the child instead of `null`.
**2 Placeholders: keep with change.** `Long`, `Integer`, `String`, `UUID`, `LocalDate` and an enum each render
`in (?,?)`; the stub's `prepareStatement` is the only JDBC call, so no value binds, and a value of the wrong Java type
is refused by the provider before the stub, so the attribute type is what matters. Under `value_handling_mode=inline`
no key renders as a literal: each is an explicit parameter (D-177), and every child statement's text equals the bound
one. The changes: drop the composite and tuple-`IN`-versus-`OR` wording (no child key is composite: `MQ3404`); an enum
of one constant takes its one constant; the failing line stays for an empty enum, a converted value object and
`byte[]`.
**3 Refusals: keep with change.** `MQ2003` is not a child-stage refusal at all (only `one` and `byKeys` raise it). A key
group's `MQ1502` and `MQ2607` reach the child's line only when the placeholder's group is the failing one, so they are
data-dependent; `MQ2601`, `MQ2603` and `MQ2604` need rows. R-INS-12's limits list already names all five, and Part C
takes none of them off it; the child refusals that don't depend on data already fire before the parent statement.
**4 R-INS-16 naming item: keep with change.** `emf.getProperties()` returns both of Boot's naming defaults as the
class-name strings it was given, on both lines, through the container contract and through spring-orm's factory bean;
an instance comes back as the same instance. The change: the same map always carries five instance-valued keys a Boot
unit can't avoid (the data source three times, a `Class`, a `CfgXmlAccessService` key), so the report head should list
only the dropped keys outside the connection keys R-VND-20 blanks anyway.

Spike on `spike/rfc-0025`: test-only module `spike-rfc0025`, data in [`0025-spike-data/`](0025-spike-data/README.md).
No database: every Part C cell runs on a factory booted offline on the stub and records the statement the stub stopped.

**Versions.** Hibernate 6.6.58.Final (API 3.1.0) and 7.4.14.Final (API 3.2.0). Offline targets H2 2.3.232, PostgreSQL
16.4, MySQL 8.0.36, MariaDB 11.4 (dialect versions only; no server). R-INS-16 item: H2 2.5.252 in memory, spring-orm
6.2.15, Spring Boot 3.4.13's `SpringImplicitNamingStrategy`.

**What is real and what is emulated.** Real: the production executor's child stage (`childrenOf`, `ChildRounds`,
`ChildLoad#build`/`keyIn`, key groups, `checkFirstRun`), the processor-generated query models, `model-query-hibernate`,
and `model-query-portability`'s `OfflineFactory`, `StubDataSource`, `JdbcRecording` and `DryRun.classify`. Emulated, in
the spike module only:

- **The not-API engine entry** (Part C, last bullet): `Engine.childrenOf` calls production's private
  `DefaultModelQueryExecutor#childrenOf(load, load.query(), keys)` by reflection. No engine class is shadowed.
- **The key-group rule** (Part C, third bullet): `Engine.oneRun` calls production's `checkFirstRun(q)`, then runs one
  private `KeyRun(keys, values)` through a fresh `ChildRounds` (`size`, then `read`), with `values` the catch-all's
  where one exists, else `values(groupOf(placeholder, null), null)`.
- **The harness walk:** `Probes.walk` visits `FetchPlan.childLoads()` depth first, then each `joinPlans()` plan, and
  runs every load on its own recording on two placeholders of `load.field().key().attributeType()` (`Placeholders`).
  `portability.internal.SpikeDry` is `DryRun.run(emf, ...)` keeping the whole recording.
- **Children the processor refuses** (`MQ3404`): a `byte[]` key and a whole `@EmbeddedId`, hand-written as generated
  `ChildField`s (`HandChildren`), to see what the engine would render.

**Data.** None: no row is read. The schema: customer → order → line (orders and lines reach their parent through a
`@ManyToOne`); a parent and a child with one column per key type (`Integer`, `String`, `UUID`, `LocalDate`, enums of
two, one and no constants, a `Code` record behind an `autoApply` converter, `byte[]`); an `@EmbeddedId` parent and an
`@IdClass` parent with their children.

## Findings

Each item ran on H2, PostgreSQL, MySQL and MariaDB, each under bound values and under
`hibernate.criteria.value_handling_mode=inline`, on both lines: 48 cells per target and mode (49 inline, with the
text comparison), plus 8 target-free cells; 396 per line, all `PASS`
([`rfc0025-6.6.58.Final.tsv`](0025-spike-data/rfc0025-6.6.58.Final.tsv),
[`rfc0025-7.4.14.Final.tsv`](0025-spike-data/rfc0025-7.4.14.Final.tsv)). The two lines reached the same statement
text in every cell; they differ only in the provider's exception text (cells `wrong-java-type` and
`hand-made.byEmbId` under inline) and in `getProperties()` holding 71 keys on 7.4 against 69. Child statements are the
same on the four targets (a child round has no `LIMIT`); a statement below is the same everywhere unless a cell says
otherwise.

### 1 Every child load reached on placeholder keys: keep with change

| Cell | Result |
|---|---|
| `three-level-parent` | the parent call, as the dry run reaches it today: `select sc1_0.id,sc1_0.name from sp_customer sc1_0 fetch first ? rows only` |
| `three-level.orders` (depth 2) | `select so1_0.id,so1_0.status,c1_0.id from sp_order so1_0 left join sp_customer c1_0 on c1_0.id=so1_0.customer_id where c1_0.id in (?,?) order by 1` |
| `three-level.orders.lines` (depth 3) | `select sl1_0.id,sl1_0.product,o1_0.id from sp_line sl1_0 left join sp_order o1_0 on o1_0.id=sl1_0.order_id where o1_0.id in (?,?) order by 1` |
| `three-level.orders.customer` (to-one by key) | `select sc1_0.id,sc1_0.name from sp_customer sc1_0 where sc1_0.id in (?,?) order by 1` |
| `three-level.orders.buyer` (to-one `through`) | `select c1_0.id,c1_0.name,so1_0.id from sp_order so1_0 join sp_customer c1_0 on c1_0.id=so1_0.customer_id where so1_0.id in (?,?) order by 1` |
| `join-nested.customer.orders`, `.lines`, `.customer`, `.buyer` | the same four statements, walked through `joinPlans()` of `OrderWithBuyer`'s `@Join` customer |
| `source-root-parent` | `select cte01_0.mq_id,cte01_0.mq_status from (select so1_0.id,so1_0.status from sp_order so1_0) cte01_0(mq_id,mq_status) fetch first ? rows only`; MariaDB: `REFUSED MQ4015` (its profile has no CTE support) |
| `source-root.lines` | the `lines` statement above: a child round reads the child entity, never the source |
| `key-groups-verifyMissing-rfc-rule` | the catch-all's values, both keys: `... where c1_0.name>=? and so1_0.id in (?,?) order by 1` |
| `key-groups-verifyMissing-production` | production's grouping on the same keys: `... so1_0.id in (?) ...`, one key per group |
| `key-groups-groupWhen-rfc-rule` / `-production` | `... where so1_0.status>=? and c1_0.id in (?,?) ...` / `... c1_0.id in (?) ...` |
| `key-groups-assumeInRange-rfc-rule` / `-production` | no catch-all; group of placeholder `0` asked: `in (?,?)` / `in (?)` |
| `key-groups-lookup-no-catch-all-rfc-rule` | a group function over known keys (`Map::get`): `REFUSED MQ2607: null: the key-group function returned null for the key 0` |
| `key-groups-lookup-with-catch-all-rfc-rule` / `-production` | the same function with `groupWhen(..., "all")`: `REACHED ... in (?,?)` / production `REFUSED MQ2607` |

Each `REACHED` cell's recording holds one access, `Connection.prepareStatement`, and the stub's stop is in the thrown
chain (no cell reached a statement any other way). No parent `Loaded` row is needed: `childrenOf` takes only the keys,
and the child plan's own children are not reached from it (the stub stops the first round), so the walk reaches them
by running each child load on its own, depth first, as the RFC says.

**Change: one run of both placeholders.** Production's grouping puts `0` and `1` in different groups for any group
function that tells them apart, so it prepares `in (?)`, which is not production's multi-key shape. The RFC's rule
(catch-all values, else the first placeholder's group) runs both keys in one `KeyRun`; the RFC should say so, since
"asks the placeholder's group" leaves it open.

**Change: the owner.** Asked with no owner, `groupOf` names `null` in `MQ2607` (`null: the key-group function returned
null for the key 0`). Pass the child load, as `childrenOf` does (`CustomerTree.orders: ...`). The line already names the
child, but the message is what a user reads.

**Confirmed: a catch-all avoids a false failure.** A group function that only knows real keys throws on any placeholder
(`lookup-*`). With a catch-all the rule never asks it and the line is `REACHED`; production's own grouping would fail
it. Without one, the line fails as the RFC words it; that is a harness limit, not an application error, so the line
should say which: "no catch-all, and the group function refused the placeholder key 0".

A parent the target refuses (`source-root-parent` on MariaDB) has no `REACHED` call, so the RFC's harness walks none of
its children; the spike walked them anyway and they reached.

### 2 Placeholder key types: keep with change

| Cell | Result |
|---|---|
| `placeholder.byInt`, `.byStr`, `.byUuid`, `.byDate`, `.byColor` | `select kc1_0.id,kc1_0.bytesKey,kc1_0.<key> from key_child kc1_0 where kc1_0.<key> in (?,?) order by 1` |
| `placeholder.byMono` (enum of one constant) | its one constant: `... monoKey in (?) ...` |
| `placeholder.byNothing`, `.byCode`, `.byBytes` | no placeholder: the failing line naming `Nothing`, `Code`, `byte[]` |
| `hand-made.byCode` | given `new Code("a")`, `new Code("b")`: `REACHED ... codeKey in (?,?)`, both modes |
| `hand-made.byBytes` | given two arrays: `REACHED ... bytesKey in (?,?)`, both modes |
| `embedded-id-component` | key `id.region` → `parent.region`: `... where ec1_0.parent_region in (?,?)` |
| `embedded-id-whole` / `hand-made.byEmbId` | no placeholder; given two `EmbKey`s: `where (ec1_0.parent_no,ec1_0.parent_region) in ((?,?),(?,?))`, tuple `IN` on all four targets; inline: `REFUSED MQ1502` (the provider types an operand by a basic node only) |
| `id-class-component` | key `no` → `parentNo`: `... where ic1_0.parentNo in (?,?)` |
| `same-text-other-values` | `(0, 1)` and `(41, 97)` give the same text |
| `wrong-java-type` | `"a"`, `"b"` for a `Long` key: refused before the stub, 6.6 `CoercionException`, 7.4 inline `QueryArgumentException ... not assignable to java.lang.Long` |
| `one-placeholder-shape` | one key: `in (?)` |
| `inline-text-equals-bound` | 37 child statements reached in both modes per target, 0 differ |

**Only the Java type matters, and it must be the attribute type.** The stub stops at `prepareStatement`, the only JDBC
access, so no placeholder value binds; but the provider coerces the criteria `IN` values when the query is created,
before the stub, so a value of another type is a false refusal. The type is `ChildLoad`'s key attribute type before
any converter (`Code`, not `String`), which `ChildField#key().attributeType()` gives.

**Inline renders no literal.** The RFC's premise (D-177, "where the key renders as a literal") doesn't hold:
`ChildLoad#keyIn` makes each key its own explicit parameter under inline handling, as it does a group's bound value
(`status>=?`), so the text equals the bound one in every compared cell and every placeholder type renders. The one
inline difference is the hand-written whole `@EmbeddedId`, refused `MQ1502` on the child's line: a shape the processor
refuses anyway. A converted key is not refused under inline (`hand-made.byCode`, and `key-types-parent` reached, so the
pre-statement `requireBindableKeys` check passed).

**Change: no composite child key.** A child is matched on one attribute each side: two key paths, a default to a
two-attribute `@PrimaryKey`, an embedded value or an array is `MQ3404` at compile time (`ChildKey`, `ChildChecks`), and
`ChildField#key()` holds one column. An `@EmbeddedId` or `@IdClass` parent keys its child on one component, a basic
placeholder. So the RFC's "a composite key's components each so ... tuple `IN` versus its expanded `OR` form" has no
case to cover; drop it, and drop "an `@IdClass` the harness can't build" from the failing list. Only a hand-written
`ChildField` reaches a whole embeddable; it renders tuple `IN` on the four targets, so the line would be honest if one
were built.

**Change: an enum of one constant.** Production can only ever send one distinct key for it, so its one constant is the
whole shape; only an empty enum has no placeholder. `byte[]` keeps its failing line (`MQ3404` from the processor, so
only a hand-written child has one). A converted value object keeps its failing line too: its statement is reachable
(`hand-made.byCode`), but the harness can't build an instance of an arbitrary type.

### 3 Refusals the child stage raises: keep with change

| Cell | Result |
|---|---|
| `mq1502-placeholder-group` | value `null` for group 0, the placeholder's: `REFUSED MQ1502: null, bind(statusFrom: String, ...): a param binds a non-null value` |
| `mq1502-other-group` | value `null` for group 2 only: `REACHED ... in (?,?)`, not seen |
| `mq2607-other-key` | group `null` for key 5 only: `REACHED`, not seen |
| `to-one-child-mq2601-unseen` | a to-one child by key reaches its statement; two rows for one key (`MQ2601`) needs rows |
| `key-groups-lookup-with-catch-all-rfc-rule` (item 1) | with a catch-all, the group function is never asked: `MQ2607` on a real key unseen |

**`MQ2003` is not the child stage's.** It is raised only by `one(q)` and `byKeys` (`DefaultModelQueryExecutor` lines
955 and 979), for a single-row read that found two. The
child stage's own refusals are:

- before any child round, per key: `MQ2607` (a group function returned `null`) and `MQ1502` (a group's value `null` or
  mistyped), computed from each key's group. On placeholders they show only when the placeholder's group is the failing
  one, and never under a catch-all, so they are data-dependent: they stay on the limits list by name.
- after rows: `MQ2601` (a to-one child found two rows), `MQ2603` (`maxPerParent`), `MQ2604` (a key matched by
  collation): data-dependent, on the limits list.
- data-independent: the child build, its bind limit and, under inline, `requireBindableKeys`' `MQ1502` run in
  `checkChildBuild` before the **parent** statement, so they are already on the parent call's line today; a
  catch-all's own `MQ1502` is raised when the key groups are defined.

**Change:** Part C's question 3 and its closing paragraph should name `MQ1502` and `MQ2607` of key groups and `MQ2601`,
`MQ2603`, `MQ2604` as the child stage's data-dependent refusals, not `MQ2003`. R-INS-12's list (api/16, line 231)
already holds all of them, and `MQ2003` for `one` and `byKeys`; it needs no edit for Part C.

### 4 R-INS-16: naming strategies from a booted unit: keep with change

| Cell | Result |
|---|---|
| `container-strings` | `createContainerEntityManagerFactory(unit, map)` with Boot's two names as strings: `getProperties()` returns `String org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy` and `String org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy` (69 properties on 6.6, 71 on 7.4) |
| `container-instances` | instances in the map: the same instances come back (`==`) |
| `container-no-naming-keys` | neither key given: neither in `getProperties()`; the provider's default isn't reported |
| `factory-bean-managed-classes` | `LocalContainerEntityManagerFactoryBean` over the package: 11 classes, every entity, the `@Converter(autoApply = true)` class and the `@Embeddable` |
| `factory-bean-mapping-files` | `[META-INF/spike-orm.xml]`; `excludeUnlistedClasses` true; the unit's own `getProperties()` is empty |
| `factory-bean-emf-properties` | through the bean's proxy factory: both strings, as above |
| `factory-bean-instance-valued-keys` | 5 of the map's values aren't strings, numbers, booleans or enums: `hibernate.connection.datasource`, `jakarta.persistence.nonJtaDataSource`, `javax.persistence.nonJtaDataSource` (the application's `DataSource`), `hibernate.transaction.coordinator_class` (a `Class`), `hibernate.boot.CfgXmlAccessService.key` |

**Confirmed:** `emf.getProperties()` is the place to read Boot's naming defaults, on both lines, and
`getPersistenceUnitInfo()` is the place to read the class list, converters included, and the mapping files. The unit's
own properties are empty because spring-orm passes its property map to the provider beside the unit, so the RFC's two
sources are both needed.

**Change: the dropped-key list.** Under the RFC's rule (an instance is dropped and listed) every Boot unit lists the
same five keys, three of them the application's data source, which R-VND-20's blanked connection keys replace with the
stub anyway. List only the dropped keys outside the connection keys, so the head shows a naming-strategy bean when
there is one and nothing when there isn't.
