# Compared with JPA, Querydsl and Blaze-Persistence

Model Query doesn't replace your JPA entities. It sits on top of them, next to the tools you may already use. This
page explains why it exists and where it differs from plain JPA, Querydsl and Blaze-Persistence, so you can decide
which one fits your read path.

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

| | Plain JPA | Querydsl | Blaze-Persistence | Model Query |
|---|---|---|---|---|
| Built on | — | JPQL (JPA module) | JPQL plus its own extensions | JPA Criteria |
| Result type | Managed entities, or DTOs via constructor expressions | Entities, or DTOs via `Projections` / `@QueryProjection` | Entity View interfaces or abstract classes | Plain records or classes (`@QueryModel`) |
| Where the result's shape is declared | In each query | In each query | One view per shape | Once per model; a query picks column sets |
| Typed columns | JPA static metamodel | Q classes per entity | Metamodel, plus `@Mapping` strings in views | Q classes per model |
| Joins | Written per query, or mapped relationships | Written per query | `@Mapping` paths in the view | `@Join` on the model field; added when selected |
| Children (to-many) | Lazy loading, `join fetch` or entity graphs | Written per query | Subviews with a fetch strategy | Fetch plans, loaded once per page in batches |
| Optional filters | Build the predicate list yourself | `BooleanBuilder` | Build it yourself | An empty `Optional` skips the filter |
| Paging | Offset (Hibernate 6.5+ adds keyed pages) | Offset | Offset and keyset | Offset, keyset and primary-key-first, plus `export` |
| Writes | Entities | Bulk update and delete | Updatable entity views, CTE DML | Filter-driven bulk writes and inserts (`@Incubating`) |
| What you add to the project | Nothing | An annotation processor and a runtime | A runtime, integrations and view definitions | An annotation processor and a runtime |
| Reach | Any JPA query | Most of JPQL | Beyond JPQL: CTEs, window functions, set operations | What Criteria expresses; `QueryCustomizer` for the rest |

## Plain JPA

Use plain JPA for writes through the persistence context and for the cases where you want managed entities.

Model Query reads through the same entities, so converters, naming strategies and dialects behave exactly as they do
for JPA. A query loads no entities: it selects the model's columns into a `Tuple` and maps them. Relationships
matter only when a model names them with `@Join` or `@Child`, so the fetch type on the entity no longer decides what a
read loads.

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

Blaze-Persistence is the closest in intent and is more powerful. Its criteria builder goes beyond JPQL with CTEs,
window functions and set operations, and its Entity Views can be updatable. If you need those, it is the better
choice.

Model Query trades that reach for a smaller surface:

- **Records instead of view interfaces.** A model is a plain record or class with a few annotations. Your service
  and API layers use it directly, with no generated implementation behind an interface.
- **One model for several shapes.** A list screen, a detail screen and an export can share one model and select
  different column sets. With [selected fields](models.md#selected-fields), a row also tells an unselected column from a selected
  `NULL`.
- **Plain JPA underneath.** Queries are standard Criteria on your provider. There is no criteria builder to learn,
  and anything Criteria can't express goes through `QueryCustomizer`.

## When to choose something else

- You need CTEs, window functions or recursive queries everywhere: use Blaze-Persistence, or jOOQ if you can drop
  JPA on the read path.
- Your queries are mostly ad hoc and each returns a different shape: Querydsl's per-query projections fit better.
- You read only through managed entities and edit them in place: plain JPA is enough.
- You need a stable API today: Model Query is 0.x and every public type is `@Incubating` until 1.0 (see
  [API stability](stability.md)).
