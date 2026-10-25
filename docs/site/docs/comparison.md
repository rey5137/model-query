# Compared with plain SQL, JPA, Querydsl and Blaze-Persistence

Model Query doesn't replace your JPA entities. It sits on top of them, next to the tools you may already use. This
page explains why it exists and where it differs from hand-written SQL, plain JPA, Querydsl and Blaze-Persistence, so
you can decide which one fits your read path, and which of your writes it can take over.

## Why another query library

The library grew out of three steps that many JPA projects go through.

1. **Plain JPA.** Entities model the schema well, but reading through them pulls in their relationships. Each
   `@ManyToOne` and `@OneToMany` has a fetch type, and a screen that needs three columns gets a managed entity graph,
   a persistence context and lazy proxies. Controlling what gets loaded means entity graphs, `join fetch` per query and
   watching for N+1 selects. DTO projections avoid all that, but JPQL constructor expressions are strings, and Criteria
   multiselects are verbose and positional.
2. **Querydsl.** Typed queries remove the strings, but the Q classes are generated from entities, so every query still
   writes its own joins and its own `Projections.constructor(...)`. The projection's shape lives in each query, not in
   one place.
3. **Blaze-Persistence.** Entity Views solve the projection problem properly, with keyset paging, CTEs and fetch
   strategies for collections. The cost is adoption: an extra runtime and integration layer, and one annotated
   interface or abstract class for each shape you read. A list screen, a detail screen and an export of the same table
   each tend to get their own view, plus subviews for their joins.

Model Query keeps what worked in each step: the entity mapping from JPA, generated typed constants from Querydsl, and
declared projections from Blaze-Persistence. The projection is a plain record or class. One model serves several
shapes, because a query selects column sets of it rather than declaring a new view.

## At a glance

| | Plain SQL (JDBC) | Plain JPA | Querydsl | Blaze-Persistence | Model Query |
|---|---|---|---|---|---|
| Built on | — | — | JPQL (JPA module) | JPQL plus its own extensions | JPA Criteria |
| Result type | Whatever your row mapper builds | Managed entities, or DTOs via constructor expressions | Entities, or DTOs via `Projections` / `@QueryProjection` | Entity View interfaces or abstract classes | Plain records or classes (`@QueryModel`) |
| Where the result's shape is declared | In each statement and its row mapper | In each query | In each query | One view per shape | Once per model; a query picks column sets |
| Typed columns | None: column names are strings | JPA static metamodel | Q classes per entity | Metamodel, plus `@Mapping` strings in views | Q classes per model |
| Joins | Written per statement | Written per query, or mapped relationships | Written per query | `@Mapping` paths in the view | `@Join` on the model field; added when selected |
| Children (to-many) | A second query and your own grouping | Lazy loading, `join fetch` or entity graphs | Written per query | Subviews with a fetch strategy | Fetch plans, loaded once per page in batches |
| Optional filters | Concatenate the `where` yourself | Build the predicate list yourself | `BooleanBuilder` | Build it yourself | An empty `Optional` skips the filter |
| Paging | Whatever you write | Offset (Hibernate 6.5+ adds keyed pages) | Offset | Offset and keyset | Offset, keyset and primary-key-first, plus `export` |
| Writes | Any statement | Entities, or bulk JPQL | Bulk update and delete | Updatable entity views, CTE DML | Filter-driven bulk writes, inserts with conflict clauses, writes that return their rows (`@Incubating`) |
| Row locks | `for update`, in each vendor's syntax | `LockModeType`; skip-locked through a provider hint | `setLockMode` | `setLockMode` | `RowLock` with `WAIT`, `NO_WAIT` or `SKIP_LOCKED` (`@Incubating`) |
| Several databases | One statement per vendor, or the common subset | The provider's dialect | The provider's dialect | The provider's dialect | The provider's dialect; declare target databases (`targetVendors`), a feature a target lacks is refused before any statement, and offline unit tests check all targets ([Portability](portability.md), `@Incubating`) |
| Reading many keys | Manual `WHERE id IN (...)` and chunking | Manual loop or `IN` query | Manual `id.in(...)` | — | `byKeys`/`findAllByKeys` chunked automatically to bind limits, in key order ([Reading many keys](reading-keys.md), `@Incubating`) |
| Count mode / totals | `COUNT(*)` or `COUNT(*) OVER ()` | Separate count query | Separate count query | Separate count query or paginated count | `COUNT`, `NO_COUNT`, `ONLY_COUNT` and windowed `INLINE_COUNT` where the shape allows ([Paging and export](paging-export.md), `@Incubating`) |
| Filter-only to-many joins | Native `EXISTS` or manual subquery | Duplicates parent rows unless `DISTINCT` or manual subquery | Duplicates parent rows unless `DISTINCT` or manual subquery | Subviews or criteria subquery | Where the shape allows, renders automatically as correlated `EXISTS` semi-join: no duplicated roots, no `DISTINCT` needed ([Filters](filters.md#what-the-rules-mean-for-you)) |
| Custom operators | Native SQL functions / operators | Native queries or provider extensions | Template expressions | Custom JPQL functions | Typed `FilterOperator` with feature gates; unsupported vendors refused before execution ([Custom operators](custom-operators.md), `@Incubating`) |
| Correlated columns | Correlated scalar subquery in SELECT | Criteria / JPQL subquery | Expressions with subquery | Correlated expressions / subviews | `Correlated` scalar sub-queries as `ExpressionField`s you select, filter and order by ([Sub-queries and expressions](subqueries-expressions.md#correlated-columns), `@Incubating`) |
| Trees and recursion | Recursive CTEs (`WITH RECURSIVE`) | — | — | Recursive CTEs | Recursive source models with `tree(...)` and `@Depth` ([Source models](source-models.md#trees), `@Incubating`) |
| Joins beyond mapped relationships | Any `JOIN` on any condition | Entity joins on an arbitrary condition (JPA 3.2 or Hibernate); a subquery or derived table in `FROM` only through the provider | Entity joins with `on(...)`; no derived tables in JPQL | Entity joins and joins on subqueries or CTEs | Joined sources and entity joins, `LEFT` or `INNER`, anchored on a joined source's key or on the root's columns with a static `when`; request-bound `joinFilter` values ([Source models](source-models.md#joining-an-entity), `@Incubating`) |
| Insert generation | `INSERT INTO ...` statements | Entity `persist()` only | Querydsl SQL `insert(...)` | Updatable entity view / CTE DML | `generateInserts` on `@QueryModel` adds generated `QModel.insert(rows)` and `persist(row)` ([One model for reading and creating](models.md#one-model-for-reading-and-creating), `@Incubating`) |
| What you add to the project | Nothing, or a template helper | Nothing | An annotation processor and a runtime | A runtime, integrations and view definitions | An annotation processor and a runtime |
| Reach | Everything your database supports | Any JPA query | Most of JPQL | Beyond JPQL: CTEs, window functions, set operations | What Criteria expresses, plus CTEs, window functions, set operations and recursive queries through [source models](source-models.md), including sources over sources (`@Incubating`); [correlated columns](subqueries-expressions.md#correlated-columns) (`@Incubating`); inheritance roots via treat ([Inheritance roots](inheritance-roots.md), `@Incubating`); report aggregates (filtered, `firstBy`, exact averages, null-safe equality) in [grouped queries](grouped-queries.md) (`@Incubating`); `QueryCustomizer` for the rest |

## Plain SQL

Use plain SQL (JDBC, `JdbcTemplate`, or a SQL-first library such as jOOQ or MyBatis) when a statement needs what JPA
can't express, or when the read path doesn't use JPA at all.

Hand-written SQL reaches everything the database offers, but every statement repeats its column list, its joins and
its row mapping, and nothing checks them until the statement runs. A renamed column breaks at run time, and a feature
one vendor lacks breaks only on that vendor. Optional filters, keyset paging and loading children without N+1 selects
are code you write for each query.

Model Query covers much of what used to send a JPA project to native SQL, on the same entities: row locks with
`SKIP_LOCKED`, updates and deletes that return the changed rows, inserts with `doNothing` and `doUpdate` conflict
clauses, sequence values, and literal function arguments. The model's columns are checked against the entity at
compile time, and a feature the database lacks is refused before any statement, with an `MQnnnn` code that names it.
Claiming the next jobs from a queue shows the difference:

```java
// Plain SQL (PostgreSQL): the vendor's syntax, the column list and the row mapping live in each statement
List<Long> ids = jdbc.queryForList("""
        select id from orders where status = 'PENDING'
        order by id limit 2 for update skip locked""", Long.class);
List<OrderView> claimed = jdbc.query("""
        update orders set status = 'CLAIMED' where id = any(?)
        returning id, status, total""",
        (rs, n) -> new OrderView(rs.getLong("id"), rs.getString("status"), rs.getBigDecimal("total"), null),
        (Object) ids.toArray(Long[]::new));

// Model Query: the model declares the columns; the lock and the returning write are rendered for the vendor
var next = QOrderView.query().select(QOrderView.DEFAULT)
        .where(f -> f.eq(QOrderView.STATUS, Optional.of("PENDING")))
        .orderBy(QOrderView.ID.asc())
        .build()
        .withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));
List<Long> ids = executor.list(next, Limit.of(2)).stream().map(OrderView::id).toList();
var claim = ModelUpdate.builder(QOrderView.ROOT).primaryKey(QOrderView.KEY)
        .set(QOrderView.STATUS, "CLAIMED").whereKeys(ids).build();
List<OrderView> claimed = executor.updateReturning(claim, QOrderView.query().select(QOrderView.DEFAULT).build());
```

Both run in one transaction. MySQL returns no rows from a write, so there the SQL version needs a second statement,
and Model Query refuses `updateReturning` with `MQ4011`: write with `update`, then read the rows back with
`byKeys(q, ids)`. The [Claim the next job](recipes.md#claim-the-next-job) recipe has the full version, and
[Portability](portability.md) shows how a unit test finds such refusals for every target database without starting
one.

Source models and recursive queries ([tree(...)](source-models.md#trees), 0.10.0) cover common table
expressions, window functions, set operations and hierarchies on top of JPA entities. Keep SQL for what a source
model cannot express; [QueryCustomizer](queries.md#the-builder) covers the Criteria features the builder doesn't
expose.

## Plain JPA

Use plain JPA for writes through the persistence context and for the cases where you want managed entities.

Model Query reads through the same entities, so converters, naming strategies and dialects behave exactly as they do
for JPA. A query loads no entities: it selects the model's columns into a `Tuple` and maps them. Relationships
matter only when a model names them with `@Join` or `@Child`, so the fetch type on the entity no longer decides what a
read loads.

It also covers what plain JPA leaves to native queries or provider hints: `byKeys` reads many primary keys into a map
in chunks ([Reading many keys](reading-keys.md)), `RowLock` names `SKIP_LOCKED` on every vendor that renders it
([Row locks](row-locks.md)), inserts take conflict clauses and can return the rows they wrote ([Inserts](inserts.md)),
filter-only to-many joins render as correlated `EXISTS` semi-joins without duplicating root rows where the shape allows
([Filters](filters.md#what-the-rules-mean-for-you)), and `CountMode.INLINE_COUNT` calculates total rows in the same
statement using window functions where the shape allows ([Paging and export](paging-export.md)).

```java
// JPA: a DTO needs a constructor expression in a string, and the join is written by hand
List<OrderView> rows = em.createQuery("""
        select new com.acme.OrderView(o.id, o.status, o.total, c.name)
        from OrderEntity o left join o.customer c
        where o.status = :status""", OrderView.class)
        .setParameter("status", status)
        .setMaxResults(100)
        .getResultList();

// Model Query: the model declares the columns and the join
List<OrderView> rows = executor.list(
        QOrderView.query().select(QOrderView.DEFAULT, QOrderView.CUSTOMER)
                .where(f -> f.eq(QOrderView.STATUS, Optional.of(status)))
                .build(),
        Limit.of(100));
```

## Querydsl

Querydsl and Model Query both generate Q classes, from different sources: Querydsl from entities, Model Query from
read models. With Querydsl, the query is the projection, so two queries that return the same DTO repeat its columns
and joins. With Model Query, the model is the projection, and queries only pick which column sets to fill.

Querydsl reaches further into JPQL and also covers SQL, MongoDB and other back ends. Model Query covers only JPA, and
adds what Querydsl leaves to you: paging and export strategies, fetch plans for children, and vendor-aware behaviour.
The two can share a project: both name a generated class `Q` plus the class name, so the only clash is a model and an
entity with the same simple name in the same package.

## Blaze-Persistence

Blaze-Persistence is the closest in intent and is more powerful. It excels at criteria-level CTEs, complex query
transformations, and updatable Entity Views for mutating entire view hierarchies in place. If you need criteria-level
CTE manipulation or two-way updatable views, it is the better choice.

Model Query trades that reach for a smaller surface:

- **Records instead of view interfaces.** A model is a plain record or class with a few annotations. Your service
  and API layers use it directly, with no generated implementation behind an interface.
- **One model for several shapes.** A list screen, a detail screen and an export can share one model and select
  different column sets. With [selected fields](models.md#selected-fields), a row also tells an unselected column from
  a selected `NULL`.
- **Models outside the entity module.** With [`@BindQueryModel`](models.md#models-in-an-api-module), a model lives
  in an API module that depends only on `model-query-annotations`, and its QModel is generated beside the entities.
- **Plain JPA underneath.** Queries are standard Criteria on your provider. There is no criteria builder to learn,
  and anything Criteria can't express goes through `QueryCustomizer`.

## When to choose something else

- You need CTEs or recursive queries beyond what [source models](source-models.md) express, or a window function
  outside the [`@Window`](source-models.md#window-fields) set (such as `NTILE` or `PERCENT_RANK`): use
  Blaze-Persistence, or jOOQ or plain SQL if you can drop JPA on the read path.
- You target one database, want its whole dialect and have no entities: plain SQL or jOOQ is simpler.
- Your queries are mostly ad hoc and each returns a different shape: Querydsl's per-query projections fit better.
- You read only through managed entities and edit them in place: plain JPA is enough.
- You need a stable API today: Model Query is 0.x and every public type is `@Incubating` until 1.0 (see
  [API stability](stability.md)).
