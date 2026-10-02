# API stability

model-query is at 0.2.0. Until 1.0.0 the public API may change in any minor release, and the commit that does it is
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

- every annotation except `@UpdateModel` and `QueryModel.generateChanges`;
- every public type of `model-query-core` except the bulk-write types and `NullPrecedenceRenderer`;
- in `model-query-jpa`, `ModelQueryExecutor`, `ModelQueryConfig`, `KeysetNullKeys`, `MysqlStreamingMode` and
  `DatabaseVendor`, apart from their bulk-write members;
- the Spring modules, including the starter's property keys, apart from `ModelQueryRepository.update`/`delete` and the
  `modelquery.bulk-write.*` properties;
- `OrderedColumnConverter` and the two built-in timestamp converters, because generated code links them;
- the shape of the generated code, except `changes()`, `update(...)` and `delete()`.

`Filters` and `Having` are already `sealed`, so a new operator can be added without breaking implementers.

## Still incubating after 1.0

These stay `@Incubating` at 1.0, and freeze in a later 1.x minor once one minor ships with no change to them:

- the bulk-write types in `core`: `ModelUpdate`, `ModelDelete`, `Changes`, `Assignment`, `ChunkOptions`,
  `ChunkedWriteException` and `PersistenceContextMode`, and the bulk-write members of the types above;
- `@UpdateModel`, `QueryModel.generateChanges` and the generated `changes()`, `update(...)` and `delete()`;
- the vendor SPI in `jpa.spi`: `VendorProfile`, `ProviderSupport` and `ChunkTransactions`;
- `HibernateProviderSupport`, `NullPrecedenceRenderer`, and the bean-validation constraint `ValidChanges` with its
  `ValidChangesValidator`.

Bulk writes are described in [Bulk writes](bulk-writes.md).

## Not API

Two things are not API, whatever their visibility, and may change in any release:

- Everything in `com.rey.modelquery.jpa.vendor`: the built-in vendor profiles, `VendorResolver` and `ResolvedVendor`.
  To support another database, implement the `jpa.spi` extension points instead.
- Types and methods marked `@EngineFacing`, which only an executor calls: `BuiltQuery`, `RowSelection`,
  `RenderOptions`, `JoinContext.of` and `OrderField.toOrders`.

`japicmp` ignores `@Incubating`, `@EngineFacing` and `jpa.vendor`.
