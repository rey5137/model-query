# API stability

From 1.0.0 the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). What the promise covers
depends on how a type or member is marked.

## Frozen

Every public type and member of `model-query-core`, `model-query-jpa`, `model-query-hibernate`,
`model-query-processor` (its annotations and the shape of the generated code) and the Spring modules is frozen unless
one of the markers below says otherwise. A frozen API changes incompatibly only in a new major release.
`japicmp` runs in the build against the previous release and fails on a binary-incompatible change to it.

## `@Incubating`

`@Incubating` marks API that is complete and tested but may still change. It may break in a minor release, and the
commit that does it is marked breaking. It sits on a type or on a member of an otherwise frozen type. At 1.0 it marks:

- the bulk-write types in `core`: `ModelUpdate`, `ModelDelete`, `Changes`, `Assignment`, `ChunkOptions`,
  `ChunkedWriteException` and `PersistenceContextMode`; and the bulk-write members of frozen types: `update` and
  `delete` on `ModelQueryExecutor` and `ModelQueryRepository`, the write settings on `ModelQueryConfig` and
  `ModelQueryProperties.getBulkWrite` (so the `modelquery.bulk-write.*` properties);
- the annotation `@UpdateModel`, `QueryModel.generateChanges`, and the generated `changes()`, `update(...)` and
  `delete()` methods;
- the vendor SPI in `jpa.spi`: `VendorProfile`, `ProviderSupport` and `ChunkTransactions`;
- `HibernateProviderSupport`, `NullPrecedenceRenderer`, and the bean-validation constraint `ValidChanges` with its
  `ValidChangesValidator`.

Bulk writes are described in [Bulk writes](bulk-writes.md). An incubating API freezes in a later 1.x minor, once a
minor release ships with no change to it.

## Not API

Two things are not API, whatever their visibility, and may change in any release:

- Everything in `com.rey.modelquery.jpa.vendor`: the built-in vendor profiles, `VendorResolver` and `ResolvedVendor`.
  To support another database, implement the `jpa.spi` extension points instead.
- Types and methods marked `@EngineFacing`, which only an executor calls: `BuiltQuery`, `RowSelection`,
  `RenderOptions`, `JoinContext.of` and `OrderField.toOrders`.

`japicmp` ignores `@Incubating`, `@EngineFacing` and `jpa.vendor`.

## What 1.0 changed from 0.1

See "Upgrading from 0.1" in the [changelog](https://github.com/rey5137/model-query/blob/main/CHANGELOG.md).
