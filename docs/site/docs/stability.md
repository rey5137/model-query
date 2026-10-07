# API stability

model-query is at 0.4.0. Until 1.0.0 the public API may change in any minor release, and the commit that does it is
marked breaking. From 1.0.0 the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Today: everything is `@Incubating`

In 0.x every public top-level type is marked `@Incubating`, the annotations, `@EngineFacing`, the processor class and
the `jpa.vendor` types included; only `@Incubating` itself is not. The API is complete and tested, but it may still
change.
`japicmp` is configured in the build, but it is skipped until a 1.0.0 baseline exists. The API review for 1.0 already
changed some signatures in 0.2.0; see "Upgrading from 0.1" in the
[changelog](https://github.com/rey5137/model-query/blob/main/CHANGELOG.md).

## Planned to freeze at 1.0

At 1.0 the following is planned to freeze, so that it changes incompatibly only in a new major release and `japicmp`
fails the build on such a change:

- every annotation except `@UpdateModel`, `@InsertModel` and `QueryModel.generateChanges`;
- every public type of `model-query-core` except the bulk-write and insert types and `NullPrecedenceRenderer`;
- in `model-query-jpa`, `ModelQueryExecutor`, `ModelQueryConfig`, `KeysetNullKeys`, `MysqlStreamingMode` and
  `DatabaseVendor`, apart from their bulk-write and insert members;
- the Spring modules, including the starter's property keys, apart from `ModelQueryRepository.update`, `delete`,
  `insert`, `insertReturningKeys` and `persist`, and the
  `modelquery.bulk-write.*` properties;
- `OrderedColumnConverter` and the two built-in timestamp converters, because generated code links them;
- the shape of the generated code, except `changes()`, `update(...)`, `delete()`, `insert(...)`, `insertFrom(...)`
  and `persist(...)`.

`Filters` and `Having` are already `sealed`, so a new operator can be added without breaking implementers.

## Still incubating after 1.0

These stay `@Incubating` at 1.0, and freeze in a later 1.x minor once one minor ships with no change to them:

- the bulk-write types in `core`: `ModelUpdate`, `ModelDelete`, `Changes`, `Assignment`, `ChunkOptions`,
  `ChunkedWriteException` and `PersistenceContextMode`, and the bulk-write members of the types above;
- `@UpdateModel`, `@InsertModel`, `QueryModel.generateChanges` and the generated `changes()`, `update(...)`, `delete()`,
  `insert(...)`, `insertFrom(...)` and `persist(...)`;
- the vendor SPI in `jpa.spi`: `VendorProfile`, `ProviderSupport` and `ChunkTransactions`;
- `HibernateProviderSupport`, `NullPrecedenceRenderer`, and the bean-validation constraint `ValidChanges` with its
  `ValidChangesValidator`.

Bulk writes are described in [Bulk writes](bulk-writes.md).

The insert types are new in 0.3.0 and `@Incubating` (D-116), and join the bulk-write types in that list. The freeze
review moves to the milestone after 0.4.0, which waits for the adopter to run 0.4.0 in production (D-111, D-118). The
incubating insert types are:

- in `core`: `ModelInsert`, `ValuesInsert`, `ModelPersist`, `InsertColumns` and `ConflictUpdate`;
- the `@InsertModel` annotation, and the generated `insert`, `insertFrom` and `persist` builders;
- in `model-query-jpa`: the executor's `insert`, `insertReturningKeys` and `persist` methods, the
  `conflictUpdateWhereOnAssignedColumns` option of `ModelQueryConfig`, and in `jpa.spi` `InsertSupport`,
  `InsertTarget`, `IdGeneration` and `ConflictClause`, with `VendorProfile.maxValuesRows()` and
  `conflictTargetHonoured()`;
- in the Spring modules: `ModelQueryRepository.insert`, `insertReturningKeys` and `persist`.

Inserts are described in [Inserts](inserts.md).

The entity-write types are new in 0.4.0 and `@Incubating` (D-118), and join the bulk-write types in that list. They are:

- in `core`: `throughEntities()` on the options stage and each resumable stage of `ModelUpdate` and `ModelDelete`;
- in `model-query-jpa`: `ModelQueryExecutor.persist(persist, returning)`, `WriteAssignment`, `WriteKind` and
  `ModelQueryConfig.writeAssignments`;
- in the Spring modules: `ModelQueryRepository.persist(persist, returning)`, and the starter's hand-over of every
  `WriteAssignment` bean to the config.

`ModelUpdate.entityMode`, `buildEntityLoad`, `assignedAttributes` and `assignedValues`, `ModelDelete.entityMode` and
`buildEntityLoad`, `ModelQuery.checkReturning` and `mapReturning`, and `ModelInsert.conflictUpdateAdds` are
`@EngineFacing`, so not API. Entity writes are described in
[Bulk writes](bulk-writes.md#entity-mode).

The fetch-plan types are new in 0.2.0, `@Incubating`, and not yet placed in the 1.0 freeze list above:
`FetchPlan`, `ChildField`, `JoinField`, `ChildQuery`, `Enricher` and the `@Child` annotation. The executor-facing
`ChildLoad` and `JoinPlan` are `@EngineFacing`. See [Fetch plans](fetch-plans.md).

Also new in 0.2.0, `@Incubating` and not yet in the freeze list: `ModelQueryException`, the two-way keyset
page (`KeysetSpec`, `KeysetSlice`), sub-selects (`SubSelect`, `Outer`), expressions (`Expr`, `ExpressionField`,
`ScalarField`, `ExpressionDefinition` and the `@Computed` annotation), `ModelQueryRepositoryFragmentFactoryBean` and
`ChildQueryAssert`. The keyset cursor's `KeysetCursorCodec` is `@EngineFacing`. See
[Sub-queries and expressions](subqueries-expressions.md).

## Not API

Two things are not API, whatever their visibility, and may change in any release:

- Everything in `com.rey.modelquery.jpa.vendor`: the built-in vendor profiles, `VendorResolver` and `ResolvedVendor`.
  To support another database, implement the `jpa.spi` extension points instead.
- Types and methods marked `@EngineFacing`, which only an executor calls: `BuiltQuery`, `RowSelection`,
  `RenderOptions`, `ChildLoad`, `JoinPlan`, `KeysetCursorCodec`, `JoinContext.of` and `OrderField.toOrders`.

`japicmp` ignores `@Incubating`, `@EngineFacing` and `jpa.vendor`.
