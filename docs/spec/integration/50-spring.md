# 50 — Spring Data and Boot Integration

**Covers:** `ModelQueryRepository`, the repository factory, `Pageable`/`Sort` adapters, and every `modelquery.*`
property.
**Read when:** changing the Spring surface or adding a property.
**Owns:** `R-SPR-*`, `AC-SPR-*`. Core behaviour is `engine/20..21`; nothing here may add behaviour the plain-JPA path
lacks (INV-8).

---

## 1. Repository

```java
public interface ModelQueryRepository<E> {
    <M> ModelPage<M> findPage(ModelQuery<E, ?, M> q, Pageable pageable, CountMode mode);   // total null under NO_COUNT
    <M> KeysetSlice<M> findKeysetPage(ModelQuery<E, ?, M> q, KeysetSpec keyset, Sort sort);   // sort unsorted: definition's order
    <M> List<M> findAll(ModelQuery<E, ?, M> q, Limit limit);
    long count(ModelQuery<E, ?, ?> q);
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options, Function<List<M>, List<S>> t, Consumer<S> sink);
    long update(ModelUpdate<E, ?> u);                // Future (M6), transactional per R-SPR-10
    long delete(ModelDelete<E, ?> d);                // Future (M6), transactional per R-SPR-10
}

interface OrderRepository extends JpaRepository<OrderEntity, Long>, ModelQueryRepository<OrderEntity> {}
```

**R-SPR-12** `ModelQueryRepository<E>` is a repository fragment, not a base interface: a repository extends it next to
`JpaRepository` or any other Spring Data interface, and keeps its own `repositoryBaseClass` (D-50). Its `E` is the
repository's domain type: a repository of `Book` declaring `ModelQueryRepository<Author>` fails startup with `MQ4007`. `Slice` and `Pageable`
in this file are Spring Data's types; `Limit` and `ExportOptions` are `core`'s.

**R-SPR-01** Every method delegates to `ModelQueryExecutor` and adds no semantics of its own (INV-8). A behaviour that
only works under Spring is a bug.

**R-SPR-02** `ModelQueryRepositoryFactoryBean` is set through
`@EnableJpaRepositories(repositoryFactoryBeanClass = …)`. The starter swaps it in for every repository registered
with exactly `JpaRepositoryFactoryBean`, Boot's own and any `@EnableJpaRepositories` that names no factory bean class,
and leaves a repository with a factory bean class of its own alone (D-50). It re-registers each swapped definition
rather than mutating it, so a repository type-checked before the swap is still built with it (D-83). A repository
extending `ModelQueryRepository` without the factory bean fails at startup, as any repository with an unimplemented
method does.

**R-SPR-03** `stream(...)` opens a read-only transaction when none is active, which PostgreSQL needs for cursor
streaming (`vendor/41` R-PRF-03), and joins an active one. The transaction ends when `stream` returns, and `body`
runs inside it. It comes from the transaction manager of the repository's own `@EnableJpaRepositories` (D-54).

**R-SPR-10** `update(...)` and `delete(...)` (`Future`, M6) join the current transaction or open one, like the modifying
methods of `SimpleJpaRepository`, except for a `commitEachChunk()` write, which opens none, because each chunk commits
on its own (`api/14` R-WRT-19). Because that depends on the argument, the methods are not annotated `@Transactional`;
they use a `TransactionTemplate` (`PROPAGATION_REQUIRED`) unless the write is `commitEachChunk()`. Change sets bind from
request bodies with no extra configuration (`api/14` R-WRT-03).

**R-SPR-11** The starter registers a `ChunkTransactions` that finds, for the `EntityManagerFactory` it is given, the
`JpaTransactionManager` bound to that factory (once per factory, then cached), and runs the chunk in a
`TransactionTemplate` with `REQUIRES_NEW` on it, on that factory's transactional `EntityManager`. So
`ChunkOptions.commitEachChunk()` works with no configuration and with several datasources (`api/14` R-WRT-19). No
matching transaction manager, or more than one, throws `MQ4004`. A user-defined `ChunkTransactions` bean replaces it. An application's own `ModelQueryConfig` does not receive it
implicitly; it injects the `ChunkTransactions` bean and passes it to `chunkTransactions(...)`.
This is the plain-JPA callback with a Spring default, not a Spring-only feature (R-SPR-01).

## 2. `Pageable` and `Sort`

**R-SPR-04** A `Pageable` converts to a `PageSpec` and its `Sort` to a `SortSpec`, applied with
`ModelQuery.orderedBy` (`api/11` R-QRY-14), so the same sort is available without Spring (INV-8). A sorted `Sort`
replaces the definition's `orderBy`; an unsorted one keeps it, and the result's `getSort()` then reports unsorted
although the definition's own `orderBy` applies. `Pageable.unpaged()` is `MQ2001`.

**R-SPR-05** `Sort.Order.nullsFirst()`/`nullsLast()`/`nullsNative()` map to `NullPrecedence.FIRST`/`LAST`/`DEFAULT`
(`api/10` R-COL-12).

**R-SPR-06** A sort property that does not resolve to exactly one selected column, on every matching tier, throws
`MQ2301` naming the property, rather than being silently dropped (INV-5). So does `Sort.Order.ignoreCase()`, which the
engine can't honour, a sort on an ungrouped definition without a primary key, which has no tie-breaker, and a sorted
copy that fails the checks of `build()`, with that failure as the cause (`api/11` R-QRY-14, D-58).

**R-SPR-07** `findPage` returns a `ModelPage<M>`, a Spring Data `Slice` that adds `Long getTotalElements()` and
`Integer getTotalPages()`. Both are the exact values when `CountMode` counted (`COUNT`, `ONLY_COUNT`) and `null` under
`NO_COUNT`; a total is never fabricated from the current page. A caller passes the mode through and returns the
result as is. It is not a Spring Data `Page`, whose total is a primitive `long` and can't be unknown (D-51). Because
the totals are `null` under `NO_COUNT`, unboxing them throws, so a caller serializes a DTO of its own rather than the
page. A counted page's COUNT and content run as separate statements and are one snapshot only when the caller holds a
transaction.

**R-SPR-14** `findKeysetPage(q, KeysetSpec, Sort)` returns the executor's `KeysetSlice<M>` and adds no semantics of
its own (R-SPR-01). `Sort.unsorted()` keeps the definition's order, and a sorted `Sort` applies through
`orderedBy` (R-SPR-04); either way the order decides the cursor's fingerprint (`engine/21` R-PAG-19). Its name keeps
it apart from the `Pageable` `findPage`, so a mock matching `any()` is unambiguous. The query still needs `keyset()`,
or the call is `MQ2207` (`engine/21` R-PAG-16).

## 3. Properties

| Property | Default | Meaning |
|---|---|---|
| `modelquery.vendor` | auto | Override detection (`vendor/40` R-VND-04); R-SPR-13 with several factories |
| `modelquery.export.page-size` | 1000 | Default export page size |
| `modelquery.primary-key-first.batch-size` | the whole page | Step-2 batch size, clamped per vendor (`engine/21` R-PAG-07) |
| `modelquery.stream.fetch-size` | 500 | Ignored by MySQL in row-by-row mode |
| `modelquery.mysql.streaming-mode` | `row-by-row` | or `cursor-fetch` (`vendor/41` R-PRF-07) |
| `modelquery.query-timeout` | none | Default per-query timeout |
| `modelquery.keyset.null-keys` | `fail` | `fail` or `honour-null-precedence` (`engine/21` R-PAG-05) |
| `modelquery.bulk-write.persistence-context` | `clear` | `clear` or `keep` after a bulk write (`api/14` R-WRT-15) |
| `modelquery.bulk-write.chunk-size` | 1000 | default size for `chunked(...)`, clamped per vendor (`api/14` R-WRT-17) |

**R-SPR-08** Every property has a plain-JPA equivalent on `ModelQueryConfig`; the starter only reads properties into it
(INV-8).

**R-SPR-09** A property that would weaken a correctness rule — currently only `keyset.null-keys` — is documented as a
migration aid with the failure it re-enables, and logged at `WARN` once on startup when set.

**R-SPR-13** One `ModelQueryConfig` bean serves every `EntityManagerFactory`; each repository's executor uses the
`EntityManager` of its own factory, so a profile is still resolved per factory (`vendor/40` R-VND-02). A
`ModelQueryConfigurer` bean may return a different config for a given factory. `modelquery.vendor` set in a context
with more than one `EntityManagerFactory` and no `ModelQueryConfigurer` fails startup with `MQ4005`: it would force
one vendor on every database (D-54). A `ModelQueryConfig` bean of the application's own replaces the starter's, so
startup fails with `MQ4006`, naming what it drops, when a `VendorProfile` or `ChunkTransactions` bean is not the one it
holds, or a `modelquery.*` property is set. The starter's own `ChunkTransactions` is not counted: such a config's
`commitEachChunk()` writes throw `MQ4004` (D-74). A `ModelQueryConfigurer` adjusts the starter's config instead.

## 4. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-SPR-01 | Each repository method produces the same SQL and results as the plain-JPA executor (R-SPR-01). |
| AC-SPR-02 | A Boot application with three datasources (H2, PostgreSQL, MySQL) resolves one profile per factory (R-SPR-02, `vendor/40` AC-VND-03). |
| AC-SPR-03 | `stream` without an ambient transaction succeeds on PostgreSQL through the repository (R-SPR-03). |
| AC-SPR-04 | `Sort` by a selected column's property path (`customer.name`) and by its attribute path both work (D-55); `nullsFirst`/`nullsLast`/`nullsNative` map correctly (R-SPR-04, R-SPR-05). |
| AC-SPR-05 | An unresolvable or ambiguous sort property, one naming different columns on two tiers, `ignoreCase`, and a sort on an ungrouped query without a primary key throw `MQ2301` naming the property or the reason (R-SPR-06). |
| AC-SPR-06 | Under `NO_COUNT` the result's `getTotalElements()` and `getTotalPages()` are `null` and `hasNext()` is right; under `COUNT` they are exact (R-SPR-07). |
| AC-SPR-07 | Every property in §3 is settable on `ModelQueryConfig` without Spring (R-SPR-08). |
| AC-SPR-08 | `modelquery.keyset.null-keys=honour-null-precedence` logs one startup warning (R-SPR-09). |
| AC-SPR-10 | `modelquery.vendor` with two factories and no `ModelQueryConfigurer` fails startup with `MQ4005` (R-SPR-13). |
| AC-SPR-11 | A `ModelQueryConfig` bean of the application with a `modelquery.*` property set, or without a `VendorProfile` bean among its profiles, fails startup with `MQ4006`; one holding every profile bean starts (R-SPR-13). |
| AC-SPR-13 | A post-processor that type-checks the repositories before the swap leaves them built with `ModelQueryRepositoryFactoryBean`: the context starts and `findPage` works; a `RootBeanDefinition` keeps its `targetType`, now over `ModelQueryRepositoryFactoryBean` with the old generics (R-SPR-02, D-83). |
| AC-SPR-12 | A repository declaring `ModelQueryRepository` of an entity other than its domain type fails startup with `MQ4007` (R-SPR-12). |
| AC-SPR-09 | (`Future`, M6) `update`/`delete` without an ambient transaction succeed through the repository; `commitEachChunk` commits each chunk separately on the primary and on a secondary datasource of the multi-datasource sample, and a failed third chunk leaves the first two committed (R-SPR-10, R-SPR-11). |
| AC-SPR-14 | `findKeysetPage(q, KeysetSpec, Sort)` returns the same rows as the executor; a sorted `Sort` changes the fingerprint (R-SPR-14). |
