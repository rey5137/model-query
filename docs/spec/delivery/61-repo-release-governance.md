# 61 — Repository, Release and Governance

**Covers:** module layout, dependency rules, branches and reviews, versions, publishing, licensing, and how the API
evolves.
**Read when:** adding a module or a dependency, cutting a release, or proposing an API change.
**Owns:** `R-REL-*`, `AC-REL-*`.

---

## 1. Repository layout

```
model-query/
├── pom.xml  mvnw  mvnw.cmd  .mvn/  .editorconfig  .gitattributes
├── README.md  LICENSE  CONTRIBUTING.md  CODE_OF_CONDUCT.md  SECURITY.md  CHANGELOG.md
├── model-query-bom/                 version alignment
├── model-query-annotations/         @QueryModel and friends                      (no deps)
├── model-query-core/                TableField, SelectField, ColumnField, AggregateField, SelectSet,
│                                    Row, RowMapper, ModelQuery, Filters, JoinContext, RenderOptions,
│                                    SPI interfaces                               (jakarta.persistence-api)
├── model-query-test/                assertThatQuery and FilterMatchers: assert on a built query's filters,
│                                    order and selection with no database (D-98)  (core, AssertJ)
├── model-query-jpa/                 executor, ModelQueryConfig, paging and export engine;          (core)
│                                    jpa.spi: VendorProfile, ProviderSupport; jpa.vendor: built-in profiles (not API)
├── model-query-hibernate/           Hibernate 6.x extras: dialect detection, grouped count,
│                                    null precedence                              (jpa + hibernate-core, optional)
├── model-query-portability/         portability dry run: offline factory per target vendor, recording stub
│                                    DataSource; add in test scope (the consumer's POM decides) (jpa only)
├── model-query-processor/           annotation processor                          (annotations, JavaPoet shaded)
├── model-query-spring-data/         ModelQueryRepository + factory, Page/Pageable/Sort adapters
├── model-query-spring-boot-starter/ auto-configuration, properties, SPI beans
├── model-query-tck/                 Testcontainers suite per vendor
├── samples/{plain-jpa,spring-boot}/
├── rfc/                             README.md, 0000-template.md, accepted RFCs
├── docs/{spec,plan,site}/  docs/code-conventions.md
└── .github/{workflows,ISSUE_TEMPLATE,pull_request_template.md,dependabot.yml}
```

**R-REL-01** A new module needs a real API or dependency boundary and an update to SPEC.md §5 and INV-7's order. A
directory alone is never a reason.

**R-REL-02** Grows later, not in 0.1: an `editors/` module and a `model-query-quarkus` extension. The docs site
source tree ships in 0.1 and CI builds it (D-76); it is published to GitHub Pages from main since 0.1.0 (D-79).

## 2. Dependency rules (INV-7)

**R-REL-03** Enforced with ArchUnit in the build:

- `core` imports only `jakarta.persistence.*`, `com.rey.modelquery.annotations` and the JDK.
- `jpa` does not import `org.hibernate.*`. Hibernate features are found with `ServiceLoader` and have a portable
  fallback.
- Only `spring-*` modules import `org.springframework.*`.
- `jakarta.validation` is an optional dependency of `jpa` only, used by `@ValidChanges` (`Future`, M6; `api/14`
  R-WRT-22). Generated change sets reference it only when it is on the model module's classpath.
- `processor` depends only on `annotations` and shaded JavaPoet.
- `test` depends only on `core` (and the `annotations` it exposes), the JDK and AssertJ (D-98, `api/16` R-INS-06).
- `portability` depends on `jpa` only: jakarta types, the JDK and the public types of `jpa`, `core` and
  `annotations`; never `org.hibernate`, `org.springframework`, `org.junit` or `org.assertj`, nor the `hibernate`,
  `spring`, `test` or `processor` modules; and no module depends on it (D-133). It also uses the not-API
  `jpa.vendor` members `VendorResolver.productNameOf`, `resolve` and `withSupplied` and `ResolvedVendor.profile()`
  (allow-listed in the INV-7 rule, D-139), so it is released and resolved at `model-query-jpa`'s exact version, through
  the BOM. It is meant for test scope, which only
  the consumer's POM can set: INV-7 cannot enforce it.
- No Lombok in anything the library ships. The processor's tests alone take it, in `test` scope, to compile models
  beside it (D-40). Consumers may use Lombok on their models (`processor/31` R-GEN-10).

## 3. Branches, reviews, commits

**R-REL-04** Never commit to `main`, which is protected. Work on a branch (`m2-engine`, `spec/<topic>`, `fix/<topic>`).
Each commit is green on its own. Messages are Conventional Commits with the module as scope and the ids covered:
`feat(core): aggregate selections (AC-AGG-01..05)`; spec edits use `docs(spec)`.

**R-REL-05** PRs are rebase-merged after one approving review with CI green (`delivery/60` R-QA-08). SQL snapshots are
committed with the code that changed them.

**R-REL-06** Milestone status is read from git, never from a tracker file. A milestone is **in progress** while an
`mN-<name>` branch exists (`m1-core`) and all its work happens there. It is **done** once that branch is merged into
`main` after its exit criteria are met (`delivery/62` R-RDM-02); the last commit it brings onto `main` is tagged
`mN-verified` and the branch is deleted. With neither branch nor tag it is **not started**.
`git tag -l 'm*-verified'` lists finished milestones.

## 4. Versions and releases

**R-REL-07** SemVer. `0.x` while the API can still change; `1.0.0` freezes it. Anything that may still change after
1.0 is annotated `@Incubating`, on a type or on a member of an otherwise frozen type (the bulk-write members of
`ModelQueryExecutor`, say). Incubating API may break in a minor release; the commit is still marked breaking with `!`
(R-REL-08). The planned freeze, type by type, is D-85; 0.2 keeps every public top-level type `@Incubating` (D-90),
the annotations, `@EngineFacing` and `ModelQueryProcessor` among them; only `@Incubating` itself is not marked.
Adding an abstract method to an interface is safe while it has a single implementation:
`ModelQueryExecutor.page(query, KeysetSpec)` joins the interface before the freeze (D-110), and
`DefaultModelQueryExecutor` is its only implementation. Likewise 0.2 adds the sub-select methods to `Filters` and four
constants to `Condition.Kind` (D-112): `Filters` is implemented only by core, and `Kind` already warns that it gains
constants, so a binary-compatibility report flags both while no caller breaks. Expressions (D-115) widen every `Filters` operand and
`groupBy` key from `ColumnField` to `ScalarField` (source-compatible, binary-incompatible against 0.1), change
`ModelQuery.groupBy()`'s element type, and add `ScalarField` to `SelectField`'s permits, so an exhaustive `switch` over
`SelectField` needs a third case and already-compiled code meets a `MatchException`; the changelog says so.
`@Selected`, `SelectSet.contains` and `SelectSet.selectedIn` (D-120, 0.5.0) are `@Incubating` through 1.0; `SelectSet`'s
set-semantics `equals`, `hashCode` and `toString` are not marked and freeze with `SelectSet`.
`FieldIndex`, its `Builder` and `Resolution`, `AggregateField.named` and the generated `fields()` (D-121, 0.6.0) are
`@Incubating` through 1.0.
`ModelQueryExecutor.one(q)`, `first(q)` and `one(q, key)` (D-123, 0.7.0) are abstract and `@Incubating` through 1.0,
as D-110 allows: `DefaultModelQueryExecutor` is the only library implementation. They are not default methods, since a
default built on `list` would run the fetch plan on the extra row (`engine/20` R-EXE-12). A class that implements
`ModelQueryExecutor` itself (a decorator or test double) must add them; one compiled before 0.7.0 throws
`AbstractMethodError` only when a new method is called, and the changelog says so.
The `modelquery.jspecify` processor option and the JSpecify annotations it puts on generated code (D-135, 0.9.0) are
incubating through 1.0: the option name, the annotations and their placement may change in a minor release.
`ProviderSupport.foreignKeyColumn` and the `ForeignKeyColumn` enum in `jpa.spi`, and core's `@EngineFacing`
`ForeignKeyLookup` (D-136, 0.9.0), are incubating the same way: the answers `LOCAL_COLUMN`, `JOINS` and `UNKNOWN` may
change in a minor release. A `ProviderSupport` that does not override the method answers `UNKNOWN`, so a write of a
to-one id is `MQ1004` until the provider supports it.
`ModelQueryExecutor.byKeys(q, keys)` (D-126, 0.8.0) is abstract and `@Incubating` the same way, for the same reason:
a default built on `list` would need the IN filter and the chunking the executor moves in (`engine/20` R-EXE-13). The
only implementations are `DefaultModelQueryExecutor` and `ModelQueryRepositoryFragment`. A class that implements
`ModelQueryExecutor` itself must add it; one compiled before 0.8.0 throws `AbstractMethodError` only when `byKeys` is
called.
The row-lock members (D-127, 0.9.0) are `@Incubating` through 1.0: `RowLock` and its `Wait`, `ModelQuery.Builder#lock`,
`ModelQuery#lock()` and `ModelQuery#withLock`, and the `jpa.spi` capability `ProviderSupport#locks()` returns, an
`Optional<LockSupport>`, which a provider implements as an extension point and is not `@EngineFacing`.
The literal-filter and bound-argument members (D-128, 0.9.0) are `@Incubating` through 1.0: the fixed-arity
`Filters#in` and `#notIn` field forms, `Condition#operands()` with `Condition.Kind#IN_FIELDS` and `#NOT_IN_FIELDS`, the two- and three-operand
`FilterMatchers#in` and `#notIn` overloads of `model-query-test`, `Expr.constant(ColumnField, Object)` and
`Expr.value(Object)`, and the `ConstantRenderer` capability `ProviderSupport#constants` returns. `ModelUpdate.Rendered`,
`ModelDelete.Rendered`, `EntityLoad` (what `ModelUpdate#buildEntityLoad` and `ModelDelete#buildEntityLoad` return, the
load and the `JoinContext` that rendered it) and `JoinContext#statementParameters()` are `@EngineFacing`, so they are
not API.
The returning-write and conflict-extension members (D-129, 0.9.0) are `@Incubating` through 1.0: the three returning
writes, `updateReturning`, `deleteReturning` and `insertReturning`, on `ModelQueryExecutor` and on
`ModelQueryRepository`; `ConflictUpdate#setExpression` with `ConflictExpression` and `Assignment.Conflict`; the
`KeyedConflict` stage with its arbiter `where`, and `ValuesInsert#onConstraint`; `Expr.nextval` with the
`SequenceRenderer` capability `ProviderSupport#sequences(EntityManagerFactory)` returns; `ReturningSupport`,
`ReturningShape` and `WriteStatement` in `jpa.spi` with `ProviderSupport#returning()` and
`VendorProfile#returning(WriteStatement)`; and the `VendorProfile` facts `conflictOnConstraint()`,
`conflictArbiterPredicate()` and `sequences()`. The `jpa.spi` capabilities are extension points of a provider and a
vendor profile, not `@EngineFacing`.
The write-expression members (D-130, 0.9.0) are `@Incubating` through 1.0: the `setExpression(column, ExpressionField)`
overloads on `ModelUpdate.Builder`, `ConflictUpdate`, `ConflictUpdate.Assigned`, `ValuesInsert.Rows` and
`ModelInsert.Mapping`, the `Assignment.Computed` permit, and `InsertSupport`'s six-argument `insertValues`. The M20
gate (D-132) adds three `@Incubating` default methods the executor asks before the flush:
`InsertSupport.arbiterRendered`, `InsertSupport.computedValuesRendered` and `ReturningSupport.check`. The new
permit means an exhaustive `switch` over `Assignment` needs another case and already-compiled code meets a
`MatchException`, which `@Incubating` allows and the changelog says. The `InsertSupport` method is a default, so an
implementation written before it still compiles and refuses a computed value with `MQ1613` rather than dropping it
(`vendor/40` R-VND-14).
`ModelUpdate#checkSequences` and `ModelInsert#checkSequences` and `#computedValues` are `@EngineFacing`.
The portability members (D-133, D-138, 0.9.0) are `@Incubating` through 1.0:
`ProviderSupport#offlineProperties(DatabaseVendor)`, a default returning empty so a provider written before it
compiles and is refused with `MQ4013`; `ProviderSupport#factoryName(EntityManagerFactory)`, a default naming the
factory in `MQ4012`; `ModelQueryConfig#targetVendors`, its three setters and its getter;
`ModelQueryConfig#checkTargets(EntityManagerFactory)`; and the
`model-query-portability` artifact's `Target` and `PortabilityCheck`, whose report lines and outcomes may change in a
minor release. Its `portability.internal` package, the dry run's outcomes and call results among it, is not API
(R-REL-10).

The model-binding annotations (D-134, D-140, D-141, D-142, 0.9.0) are `@Incubating` through 1.0: `@BindQueryModel`,
`@BindUpdateModel`, `@BindInsertModel` and `@BindExpression`, their containers `@BindQueryModels`,
`@BindUpdateModels` and `@BindInsertModels`, and the default of `@Computed.value`. Their members and
the names they generate may change in a minor release.

The source-model members (D-143, D-144, 0.10.0) are `@Incubating` through 1.0: the `@SourceModel` annotation with its
`root` and its `shape`, `SourceQuery` with its nested `Builder`, `TableField.source`,
`ColumnField.over`, the three-argument `ModelQuery.builder` and `ModelQuery.source()`, and the
`source()`/`query(SourceQuery<S>)` members the processor generates. The window members (`api/17` R-SRC-04, D-143) are
`@Incubating` the same way: the `@Window` annotation with `WindowFunction` and `WindowFrame`, core's `@EngineFacing`
`WindowSpec` and `WindowField`, and `SourceRenderer#window` beside `#cte`. The set-operation members (`api/17` §4,
R-SRC-05, R-SRC-06, 0.10.0) are `@Incubating` too: the `@Discriminator` annotation, core's `@EngineFacing` `SetOperation` and the
`union`/`unionAll`/`intersect`/`except` methods with the `Builder#shape`/`#discriminator` they are built from, core's
`@EngineFacing` `SourceQuery#unique()`, `SourceRenderer#combine` beside `#cte` and `#window`, and `SourceFeature`'s
`UNION`, `INTERSECT` and `EXCEPT`. The recursion members (D-144, 0.10.0) are `@Incubating` the same way: the `@Depth`
annotation, `SourceQuery.Builder#recursive` and its `maxDepth` with the `RecursiveStep`, `StepBuilder` and
`RecursiveKeys` a caller writes the step with, core's `@EngineFacing` `RecursiveCte`, `SourceRenderer#recursive` beside `#cte`,
`#window` and `#combine`, and `SourceFeature`'s `RECURSIVE` with `VendorProfile#maxRecursionDepth()`. `SourceRenderer`
is the one source hook (`jpa.spi.SourceSupport` is gone, D-146), and `WindowField` joins `SelectField`'s permits as
`ScalarField` did, so an exhaustive `switch` over `SelectField` needs a new case. `WindowSpec` and `RecursiveCte` are
`@Incubating` records a provider constructs, so adding a component to either breaks providers that build them.
The tree-source members (`api/17` R-SRC-13, R-SRC-14, D-147) are `@Incubating` through 1.0 too:
`SourceQuery.Builder#tree`, `RecursiveSpec.tree`, `RecursiveCte.duplicateKey`, `BuiltQuery.RECURSION_DUPLICATE_KEY`,
`BuiltQuery#sourceKeyColumns()`. The records have only their canonical
constructors (D-147). `SourceFeature.RECURSIVE_COMPOSITE_KEY` (D-149) is
`@Incubating` the same way.

The source-over-source members (D-150, M27.4) are `@Incubating` through 1.0: both
`SourceQuery.builder(...)` overloads taking an inner `SourceQuery`, generated `source(SourceQuery<X> inner)`
factories, and `MqCode.MQ1907`/`MQ1908`. The retained inner definition is immutable and safe in `static final`
constants (INV-9); it exposes no new public accessor.

The correlated-column members (D-150, M27.1) are `@Incubating` through 1.0: `Correlated` and its terminals `count`,
`exists`, `min`, `max` and `sum`, and `MqCode.MQ1314`, `MqCode.MQ1510` and `MqCode.MQ1511`.

The correlated-first member (D-150, M27.3) is `@Incubating` through 1.0: `Correlated.first`, `SubqueryRenderer` and
`ProviderSupport#subqueries()`.

The source-chain members (D-150, M27.5) are `@Incubating` through 1.0: `SourceRenderer.CteHandle`,
`SourceRenderer.RecursiveHandle`, the `SourceRenderer` methods `register`, `registerRecursive` and `from`, and
`SourceQuery#tree()`.

The source-read members (D-150, M27.6) are `@Incubating` through 1.0: the generated `Rows` type and
`query(SourceQuery<S>)` on a source QModel.

The inheritance-roots members (D-154..D-163, M28) are `@Incubating` through 1.0: `TableField#treat` (R-COL-34),
`Expr.typeOf` (R-COL-36), `Filters.ofType` and `notOfType` with their `Optional` and collection overloads (R-FLT-28),
`Condition.Kind#OF_TYPE` and `#NOT_OF_TYPE`, the `treat` member on `@Column`, `@FilterColumn` and `@Join`, the
`@Subtype` annotation (R-PROC-41..43), and the generated `<SUBTYPE>_TREAT` constants (R-GEN-41). The SPI types and
methods (`vendor/40` R-VND-26) are `@Incubating` the same way: `TreatRenderer`, `TreatSite`, `StatementShape`,
`TreatUse`, `ExpectedNarrowing` and its `Kind` (D-157), `RenderOptions#withTreatRenderer`, `#withMetamodel` and
`#treatRenderer()`, `ProviderSupport#treats(EntityManagerFactory)`, and `TreatChecks`.
`ColumnField.identityPath()` (D-160), `TreatChecks.reachesTreat(ModelQuery)`, `ModelQuery#buildRootIdCount`,
`#checkFetch(CriteriaBuilder, RenderOptions)`, `#checkReturning(CriteriaBuilder, RenderOptions)` and
`BuiltQuery#expectedNarrowings()` are `@EngineFacing`, so they are not API.

The custom-operator members (D-167, M29) are `@Incubating` through 1.0: `FilterOperator`, `OperatorRenderer`,
`OperatorContext` and `OperatorFeature`, `Filters.op` and `Having.op` in their three forms (R-FLT-20),
`Condition.Kind#OPERATOR` and `Condition#operator()`, `FilterMatchers.op` (R-INS-13), `MqCode.MQ1313` and `MQ1315`,
and `model-query-hibernate`'s `HibernateOperators` (R-VND-23). The SPI surface is `@Incubating` the same way:
`OperandBinder` with `parameter`, `typesBy` and `converts` (R-VND-24, D-177),
`ProviderSupport#operands(EntityManagerFactory)` and `#operators(EntityManagerFactory, OperatorFeature)`,
`VendorProfile#operators(OperatorFeature)`, and `RenderOptions#withOperatorGate`, `#withOperandBinder`,
`#operatorGate()` and `#operandBinder()` (R-VND-23).
`OperatorGate`, `LikeMode#pattern` and `LikeMode.escapeCharacter()`, which `like` and `HibernateOperators.ilike`
share, are `@EngineFacing`; the `FunctionContributor` `HibernateOperatorFunctions` is loaded by Hibernate.
They are not API (D-169, D-171).

The RFC 0018 members (D-177, M30) are `@Incubating` through 1.0: `CountMode.INLINE_COUNT` (R-EXE-15),
`ProviderSupport#inlineCounts(EntityManagerFactory)` (R-VND-27, D-182) and `#inlinesValues(EntityManagerFactory)`
(R-VND-28), and `RenderOptions#withInlinesValues` and `#inlinesValues()`. `ValueOperands`, `ParameterSink`,
`PrimaryKey#requireBindable`, `JoinContext#renderOptions()`, `ChildLoad#keyIn` and `#requireBindableKeys`, and
`ModelInsert#buildConflictUpdate`'s parameter sink are `@EngineFacing`, so they are not API; `ValueOperands`,
`ParameterSink` and `ChildLoad` also carry `@Incubating`, which marks a change to them breaking (R-REL-08).

The RFC 0020 Part A members (D-185, D-187, M32.6) are `@Incubating` through 1.0: `SourceJoin` with its nested `On`,
`ModelQuery.Builder#joinSource`, `SourceRenderer#join` and `MqCode.MQ1910` (`api/17` R-SRC-21, R-SRC-22).
`ModelQuery#joinsSource()` and `BuiltQuery#recursionJoin()`, which names a joined walk in its check messages, are
`@EngineFacing`, so they are not API; they also carry `@Incubating`, which marks a change to them breaking (R-REL-08).

**R-REL-08** Tag → GitHub Actions → Maven Central through the Central Portal
(`central-publishing-maven-plugin`), GPG-signed, with `-sources` and `-javadoc` jars. The changelog is generated from
Conventional Commits. A commit that breaks an `@Incubating` type is still marked breaking (`!` and a
`BREAKING CHANGE:` footer), so the generated changelog lists it. A break that reached `main` unmarked is entered in
`CHANGELOG.md` by hand: `ExportOptions.pageSize` became an `OptionalInt` in `9ce0c10` (D-53).

**R-REL-09** Coordinates: `groupId` `io.github.rey5137`, artifacts `model-query-*`. The namespace is verified on Central
through the `rey5137` GitHub account. The Java package `com.rey.modelquery` intentionally differs from the `groupId`.

**R-REL-10** Sub-packages follow the modules: `.core`, `.test`, `.jpa`, `.hibernate`, `.processor`, `.spring.data`,
`.spring.boot`, `.portability`. `com.rey.modelquery.jpa.vendor` (the built-in profiles, `VendorResolver`,
`ResolvedVendor`) and `com.rey.modelquery.portability.internal` (the dry run's factory, stub and recording) are not
API, whatever the visibility of their types: they may change in any release, and extensions go through `jpa.spi`.
The one exception in use is `model-query-portability`, which calls `VendorResolver.productNameOf`, `resolve` and
`withSupplied` and `ResolvedVendor.profile()` and so must be resolved at `model-query-jpa`'s exact version (D-139). A
type or method marked `@EngineFacing` (`core`; only an executor uses it) is not API either, and may change in any
release: the types `BuiltQuery`, `RowSelection`, `RenderOptions`, `ChildLoad` and `JoinPlan`, the methods
`JoinContext.of`, `OrderField.toOrders`, `FetchPlan.childLoads`, `joinPlans`, `enrichers` and `isSelectionOnly`,
`Enricher.enrich`, `ModelQuery.checkFetch` and those D-72 lists (D-72, D-86).

**R-REL-11** From 1.0, `japicmp` fails the build on a binary-incompatible change to API: a type outside the non-API
packages that is neither `@Incubating` nor `@EngineFacing`, or a member of such a type that is neither (R-REL-10).
The root pom's `japicmp.baseline` names the release compared against; while it is unset the check is skipped, and
`-Djapicmp.baseline=<version>` runs it once. The processor is not compared: its surface is the annotations and the generated
code's shape, and its jar bundles a relocated JavaPoet.

## 5. Licensing

**R-REL-12** Apache-2.0 for code and documentation, one `LICENSE` file at the root. Contributions are accepted under the
same license; there is no CLA.

## 6. Documentation

**R-REL-13** `README` carries the quick start only. The docs site (MkDocs or Antora) carries the user guide and the
vendor notes page (`vendor/40` R-VND-09). `docs/spec` stays the source of truth and is not published as user
documentation.

Before a release is tagged, each docs-site snippet is checked against the test that mirrors it (the test names its
page in its Javadoc, for example `PortabilityDocsSnippetTest` for `portability.md`). The tests hold their own copy of
each snippet, and CI skips the Java jobs on a PR that touches only docs, so a page can drift from its test unnoticed.

## 7. API evolution: RFCs

**R-REL-14** A change to a public signature, to a rule's meaning, or to an `INV-*` needs an accepted RFC first:
Discussion → RFC PR (`rfc/NNNN-title.md`) → accepted → implementation, tests and spec update in one PR.

**R-REL-15** A new `MQnnnn` code is added to `reference/90` before the code that raises it. A code's meaning never
changes (INV-10).

**R-REL-16** A design choice made while implementing is recorded as a `D-n` in `reference/92`, or a `Q-n` while still
open. Rules are never renumbered; a retired rule is marked `Retired` and keeps its id.

## 8. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-REL-01 | The build produces exactly the modules in §1, and `./mvnw verify` is green from a clean checkout. |
| AC-REL-02 | ArchUnit fails on each forbidden edge in R-REL-03, verified by a deliberately bad test fixture. |
| AC-REL-03 | `core` and `jpa` resolve with no `org.hibernate` and no `org.springframework` artifact in their dependency tree. |
| AC-REL-04 | A release dry run publishes signed `-sources` and `-javadoc` jars for every module and a BOM that lists them all. |
| AC-REL-05 | Two builds of the same tag produce identical artifact checksums. |
| AC-REL-06 | `japicmp` fails on a removed public method of a non-`@Incubating` type (R-REL-11). |
| AC-REL-07 | Every `MQnnnn` raised in code appears in `reference/90` (R-REL-15), checked by the AC audit. |
