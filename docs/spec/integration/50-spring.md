# 50 — Spring Data and Boot Integration

**Covers:** `ModelQueryRepository`, the repository factory, `Pageable`/`Sort` adapters, and every `modelquery.*`
property.
**Read when:** changing the Spring surface or adding a property.
**Owns:** `R-SPR-*`, `AC-SPR-*`. Core behaviour is `engine/20..21`; nothing here may add behaviour the plain-JPA path
lacks (INV-8).

---

## 1. Repository

```java
@NoRepositoryBean
public interface ModelQueryRepository<E, ID> extends JpaRepository<E, ID> {
    <M> Page<M> findPage(ModelQuery<E, ?, M> q, Pageable pageable, CountMode mode);
    <M> List<M> findAll(ModelQuery<E, ?, M> q, Limit limit);
    long count(ModelQuery<E, ?, ?> q);
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options, Function<List<M>, List<S>> t, Consumer<S> sink);
    long update(ModelUpdate<E, ?> u);                // Future (M8), @Transactional
    long delete(ModelDelete<E, ?> d);                // Future (M8), @Transactional
}
```

**R-SPR-01** Every method delegates to `ModelQueryExecutor` and adds no semantics of its own (INV-8). A behaviour that
only works under Spring is a bug.

**R-SPR-02** `ModelQueryRepositoryFactoryBean` is set through
`@EnableJpaRepositories(repositoryFactoryBeanClass = …)`. The starter configures it for the default
`@EnableJpaRepositories` automatically.

**R-SPR-03** `stream(...)` opens a read-only transaction when none is active, which PostgreSQL needs for cursor
streaming (`vendor/41` R-PRF-03).

**R-SPR-10** `update(...)` and `delete(...)` (`Future`, M8) join the current transaction or open one, like the
modifying methods of `SimpleJpaRepository`. Change sets bind from request bodies with no extra configuration
(`api/14` R-WRT-03).

**R-SPR-11** The starter registers a `ChunkTransactions` backed by a `TransactionTemplate` with `REQUIRES_NEW`, so
`ChunkOptions.commitEachChunk()` works with no configuration (`api/14` R-WRT-19). A user-defined `ChunkTransactions`
bean replaces it. This is the plain-JPA callback with a Spring default, not a Spring-only feature (R-SPR-01).

## 2. `Pageable` and `Sort`

**R-SPR-04** `Pageable` and `Sort` convert to `PageSpec` and `SortSpec`. A sort property resolves against the root
entity's attribute paths, or against a `ColumnField` by name when the sort names a column.

**R-SPR-05** `Sort.Order.nullsFirst()`/`nullsLast()`/`nullsNative()` map to `NullPrecedence.FIRST`/`LAST`/`DEFAULT`
(`api/10` R-COL-12).

**R-SPR-06** A sort property that resolves to neither an attribute path nor a known column throws `MQ2301` naming the
property, rather than being silently dropped (INV-5).

**R-SPR-07** `Page.getTotalElements()` follows `CountMode`. With `NO_COUNT` the method returns a `Page` implementation
whose total is documented as unknown; it never fabricates a total from the current page.

## 3. Properties

| Property | Default | Meaning |
|---|---|---|
| `modelquery.vendor` | auto | Override detection (`vendor/40` R-VND-04) |
| `modelquery.export.page-size` | 1000 | Default export page size |
| `modelquery.primary-key-first.batch-size` | 1000 | Step-2 batch size, clamped per vendor |
| `modelquery.stream.fetch-size` | 500 | Ignored by MySQL in row-by-row mode |
| `modelquery.mysql.streaming-mode` | `row-by-row` | or `cursor-fetch` (`vendor/41` R-PRF-07) |
| `modelquery.query-timeout` | none | Default per-query timeout |
| `modelquery.keyset.null-keys` | `fail` | `fail` or `honour-null-precedence` (`engine/21` R-PAG-05) |
| `modelquery.mutation.persistence-context` | `clear` | `Future` (M8): `clear` or `keep` after a bulk write (`api/14` R-WRT-15) |
| `modelquery.mutation.chunk-size` | 1000 | `Future` (M8): default size for `chunked(...)`, clamped per vendor (`api/14` R-WRT-17) |

**R-SPR-08** Every property has a plain-JPA equivalent on `ModelQueryConfig`; the starter only reads properties into it
(INV-8).

**R-SPR-09** A property that would weaken a correctness rule — currently only `keyset.null-keys` — is documented as a
migration aid with the failure it re-enables, and logged at `WARN` once on startup when set.

## 4. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-SPR-01 | Each repository method produces the same SQL and results as the plain-JPA executor (R-SPR-01). |
| AC-SPR-02 | A Boot application with three datasources (H2, PostgreSQL, MySQL) resolves one profile per factory (R-SPR-02, `vendor/40` AC-VND-03). |
| AC-SPR-03 | `stream` without an ambient transaction succeeds on PostgreSQL through the repository (R-SPR-03). |
| AC-SPR-04 | `Sort` by entity attribute and by column name both work; `nullsFirst`/`nullsLast`/`nullsNative` map correctly (R-SPR-04, R-SPR-05). |
| AC-SPR-05 | An unresolvable sort property throws `MQ2301` naming it (R-SPR-06). |
| AC-SPR-06 | `NO_COUNT` returns a `Page` that does not claim a total (R-SPR-07). |
| AC-SPR-07 | Every property in §3 is settable on `ModelQueryConfig` without Spring (R-SPR-08). |
| AC-SPR-08 | `modelquery.keyset.null-keys=honour-null-precedence` logs one startup warning (R-SPR-09). |
| AC-SPR-09 | `update`/`delete` without an ambient transaction succeed through the repository; `commitEachChunk` commits each chunk separately (R-SPR-10, R-SPR-11). |
