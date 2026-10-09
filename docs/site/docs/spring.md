# Spring Data and the starter

The `model-query-spring-data` module adds `ModelQueryRepository`, and `model-query-spring-boot-starter` configures it.
Both only delegate to the plain-JPA executor: every behaviour here also works without Spring, and a behaviour that
only works under Spring is a bug.

The starter is the only dependency to declare: it pulls `spring-boot-starter-data-jpa`, `model-query-hibernate` and
`model-query-spring-data`. `model-query-annotations` is needed only in a module without the starter, and the processor
still goes in `annotationProcessorPaths`, which no dependency can fill.

## The repository

```java
public interface OrderRepository extends JpaRepository<OrderEntity, Long>, ModelQueryRepository<OrderEntity> {}
```

`ModelQueryRepository<E>` is a repository fragment, not a base interface, so you keep your own `repositoryBaseClass`.
Its `E` must be the repository's own domain type; a repository of `Book` declaring `ModelQueryRepository<Author>`
fails at startup with `MQ4007`.

| Method | Meaning |
|---|---|
| `findPage(query, pageable, CountMode)` | Returns a `ModelPage<M>` |
| `findAll(query, Limit)` | The rows as a list |
| `findOne(query)`, `findFirst(query)`, `findByKey(query, key)` | An `Optional` of at most one row (incubating); see [Reading one row](queries.md#reading-one-row) |
| `count(query)` | The number of rows (groups, for a grouped query) |
| `stream(query, Limit, body)` | Streams inside a transaction that the repository opens, read-only, when none is active |
| `export(query, ExportOptions, pageTransformer, sink)` | The [export](paging-export.md) loop |
| `update(modelUpdate)`, `delete(modelDelete)` | [Bulk writes](bulk-writes.md) (incubating); join the current transaction or open one |

A repository extending `ModelQueryRepository` without the starter's factory bean fails at startup, like any
repository with an unimplemented method. The starter swaps in its factory bean for every repository registered with
Spring Data's `JpaRepositoryFactoryBean`, including Boot's own and any `@EnableJpaRepositories` that names no factory
bean class.

## A custom repository factory bean

An application that sets its own `repositoryFactoryBeanClass` — usually to add a custom `repositoryBaseClass` — keeps
that class, and the starter adds the fragment to it per repository (R-SPR-02, D-113). Three cases:

- **The stock `JpaRepositoryFactoryBean`** is swapped for `ModelQueryRepositoryFactoryBean`, as above.
- **Your own `JpaRepositoryFactoryBean` subclass** whose repository extends `ModelQueryRepository` keeps its class,
  its override and its `repositoryBaseClass`; the starter re-registers the definition with a
  `ModelQueryRepositoryFragmentFactoryBean` as its `customImplementation`, so the fragment is composed in beside
  everything the class already builds. A repository that does not extend `ModelQueryRepository` is left untouched.
- **A `ModelQueryRepositoryFactoryBean` subclass** is left alone: it adds the fragment itself.

A definition that already sets `customImplementation` fails with `MQ4008`: the starter cannot compose the fragment
beside it, so leave the fragment to that implementation, or extend `ModelQueryRepositoryFactoryBean`. A repository
that declares `ModelQueryRepository` for an entity other than its own domain type fails with `MQ4007`.

Extending `ModelQueryRepositoryFactoryBean` rather than `JpaRepositoryFactoryBean` is still the simplest route, and
Spring requires the one-argument constructor:

```java
public class CustomJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
        extends ModelQueryRepositoryFactoryBean<T, S, I> {
    public CustomJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }
}
```

Set it as before, next to your own `repositoryBaseClass`: `@EnableJpaRepositories(repositoryFactoryBeanClass =
CustomJpaRepositoryFactoryBean.class, repositoryBaseClass = MyBaseRepository.class)`.

The subclass adds the fragment only to the repositories that extend `ModelQueryRepository`, so a repository without
it stays a plain Spring Data repository, and your `repositoryBaseClass` keeps working for all of them. Because the
fragment is added per repository, you migrate one repository at a time: add `ModelQueryRepository<MyEntity>` to one
interface, and leave the rest on your old route until you move them.

## `Pageable` and `Sort`

A `Pageable` converts to a page spec, and its `Sort` is applied to the query's selected columns, so a REST endpoint
can sort by `?sort=customer.name,desc`.

- A sort property names a column the query selects: first by its property path, the model field names from the root
  model (`customer.name` for field `name` of the nested model under the `@Join` field `customer`), then by its
  attribute path from the root. A bare attribute name never matches a joined column. An aggregate matches by name.
- A property that matches no column, or more than one, fails with `MQ2301` naming the property, rather than being
  silently dropped. So does `Sort.Order.ignoreCase()`, which the engine cannot honour.
- `nullsFirst()`, `nullsLast()` and `nullsNative()` map to explicit null precedence.
- A sorted `Sort` replaces the query's own `orderBy`; an unsorted one keeps it.
- `Pageable.unpaged()` fails with `MQ2001`.

`findPage` returns a `ModelPage<M>`, a Spring Data `Slice` that adds `getTotalElements()` and `getTotalPages()`. Both
are exact when the mode counted (`COUNT`, `ONLY_COUNT`) and `null` under `NO_COUNT`. It is not a Spring Data `Page`,
whose total is a primitive that cannot be unknown. Because the totals are `null` under `NO_COUNT`, serialize a DTO of
your own rather than the page. A counted page runs its count and its content as separate statements, so they are one
snapshot only when you hold a transaction.

## Properties

Every property has a plain-JPA equivalent on `ModelQueryConfig`; the starter only reads properties into it. The
property keys are planned to freeze at 1.0; the `modelquery.bulk-write.*` properties stay `@Incubating`, as bulk
writes do.

| Property | Default | `ModelQueryConfig` method | Meaning |
|---|---|---|---|
| `modelquery.vendor` | detected | `vendor(...)` | Overrides database detection; matched ignoring case, `-` and `_`. |
| `modelquery.export.page-size` | `1000` | `exportPageSize(int)` | Default export page size. |
| `modelquery.primary-key-first.batch-size` | the whole page | `primaryKeyFirstBatchSize(int)` | Step-2 batch size, clamped per vendor. |
| `modelquery.stream.fetch-size` | `500` | `streamFetchSize(int)` | Ignored by MySQL in `row-by-row` mode. |
| `modelquery.mysql.streaming-mode` | `row-by-row` | `mysqlStreamingMode(...)` | `row-by-row` or `cursor-fetch`; see [Vendor notes](vendors.md). |
| `modelquery.query-timeout` | none | `queryTimeout(Duration)` | Default per-query timeout. |
| `modelquery.keyset.null-keys` | `fail` | `keysetNullKeys(...)` | `fail` or `honour-null-precedence`; see below. |
| `modelquery.bulk-write.persistence-context` | `clear` | `persistenceContextMode(...)` | `clear` or `keep` after a bulk write. |
| `modelquery.bulk-write.chunk-size` | `1000` | `bulkWriteChunkSize(int)` | Default size for `chunked(...)`, clamped per vendor. |

A value outside its allowed range fails with `MQ4003`.

!!! warning "`modelquery.keyset.null-keys=honour-null-precedence` is a migration aid"
    By default, keyset paging over a nullable column without explicit `nullsFirst()`/`nullsLast()` fails with
    `MQ2202`, because a NULL there would silently truncate the result. Setting `honour-null-precedence` lets such a
    column page its NULLs where the database sorts them, which re-enables that failure on a database whose null
    ordering the library does not know (`OTHER`). The starter logs a warning once at startup when it is set. Prefer
    explicit null precedence on the column.

## Several datasources

One `ModelQueryConfig` bean serves every `EntityManagerFactory`; each repository's executor uses the `EntityManager` of
its own factory, so the vendor profile is still resolved per factory. A `ModelQueryConfigurer` bean may return a
different config for a given factory.

- `modelquery.vendor` set in a context with more than one `EntityManagerFactory` and no `ModelQueryConfigurer` fails
  at startup with `MQ4005`, because it would force one vendor on every database.
- A `ModelQueryConfig` bean of your own replaces the starter's. Startup then fails with `MQ4006`, naming what it
  drops, when a `VendorProfile` or `ChunkTransactions` bean is not the one it holds or a `modelquery.*` property is
  set. A `ModelQueryConfigurer` adjusts the starter's config instead.
- Committing each chunk of a bulk write works with no configuration, including with several datasources: the starter
  registers a `ChunkTransactions` that finds the `JpaTransactionManager` bound to each factory. With none or more than
  one matching manager it fails with `MQ4004`. Define a `ChunkTransactions` bean to replace it.

The `samples/spring-boot` application shows three datasources (H2, PostgreSQL, MySQL) in one application.
