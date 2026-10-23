# RFC 0021 — Entity joins off joined sources, and request-bound join filters

- **Status:** accepted (D-193), after the acceptance spike (`rfc/0021-spike-results.md`). Prompted by a hierarchy report (a closure-table node search, native MySQL today) that RFC 0020
  Part A almost expresses: the source joins, but nothing joins *off* it.
- **Affects:**
  - **`api/17`:** new R-SRC-23 (a model that joins a source joins an entity on that entity's full id, equal to columns
    of the joined source) and R-SRC-24 (how it renders, which statements carry it, counts, locks, fetch plans); R-SRC-22's
    "every statement" sentence names the joined entity; new AC-SRC-53 to AC-SRC-58 (Part A). `MQ3035` (a `@Join` on a
    model rooted at a source) is unchanged.
  - **`api/10`:** new R-COL-39, a request-bound `ON` filter on a LEFT join (Part B); R-COL-03's `on(...)` bullet points
    to it; new AC-COL-54 to AC-COL-57.
  - **`processor/30`, `processor/32`:** new R-PROC-46 (`@JoinEntity`, and the `join` member of `@Column` and
    `@FilterColumn`); new `MQ3046`.
  - **`vendor/40`:** new R-VND-32, `ProviderSupport#entityJoins()` returning a `core.EntityJoinRenderer`, a provider
    fact; no `VendorProfile` change.
  - **`model-query-hibernate`:** `EntityJoinRenderer` over `JpaFrom#join(Class, SqmJoinType)` on both lines.
  - **`reference/90`:** new `MQ1911` (Part A), `MQ1912` (Part B), `MQ3046`; `MQ4014`'s row gains the entity join.
  - **`docs/site`:** the sources page (an entity joined off a joined source), the joins page (join filters), each
    runnable snippet in both tabs (CC-DOC-01).
- **Discussion:** none yet.
- **Target:** 0.11.0, as M33; the freeze moves to M34. Every new type, member and annotation member is
  `@Incubating`. Both parts are additive (§Compatibility). Part B can be accepted or deferred on its own.

## Summary

Two parts, separately acceptable, both extending RFC 0020 Part A (R-SRC-21, R-SRC-22):

- **A.** A model that joins a source may join an entity whose full id equals columns of that joined source
  (`left join profile p on p.id = x.mq_startVertex`), `LEFT` by default or `INNER`, and read the entity's columns and
  its to-one paths. The join is on the target's full id, so it adds at most one row per outer row and R-PAG-* hold
  unchanged.
- **B.** A request-bound `ON` filter on a LEFT join, the Part A entity join or an ordinary to-one join, so a
  per-request condition blanks the joined columns instead of dropping the row. It is bound on the built query, as a
  `where` is, never held in a static definition (INV-9).

Both render on every Tier-1 vendor and line, or are refused before any statement with a code `PortabilityCheck`
reports per declared target.

## Motivation

A hierarchy node search over a closure table. Tables: `hierarchy(id, name)`, `hierarchy_node(id, hierarchy_id,
node_id, is_deleted)`, `hierarchy_edge(hierarchy_id, start_vertex, end_vertex, hops, is_deleted, created_at)`
(`start_vertex` is an ancestor of `end_vertex`, `hops` levels up), `profile(id, first_name, last_name, type_id,
is_deleted)`, `profile_type(id, name)`. The Details variant, native MySQL:

```sql
SELECT r.hierarchy_id, r.node_id, c.first_name, ct.name, r.start_vertex, p.first_name, pt.name, r.created_at
FROM (SELECT hn.hierarchy_id, hn.node_id, e.start_vertex, e.created_at
      FROM hierarchy_node hn
      LEFT JOIN hierarchy h      ON h.id = hn.hierarchy_id
      LEFT JOIN hierarchy_edge e ON e.hierarchy_id = h.id AND e.end_vertex = hn.node_id
                                AND e.hops = :hops AND (:include_deleted = 1 OR e.is_deleted <> 1)
      WHERE (:hierarchy_id IS NULL OR h.id = :hierarchy_id) AND (:manager_id IS NULL OR e.start_vertex = :manager_id)
        AND (:node_id IS NULL OR hn.node_id = :node_id) AND (:include_deleted = 1 OR hn.is_deleted <> 1)) r
LEFT JOIN profile c       ON c.id = r.node_id      AND (:include_deleted = 1 OR c.is_deleted <> 1)
LEFT JOIN profile_type ct ON ct.id = c.type_id
LEFT JOIN profile p       ON p.id = r.start_vertex AND (:include_deleted = 1 OR p.is_deleted <> 1)
LEFT JOIN profile_type pt ON pt.id = p.type_id
WHERE (:node_first_name IS NULL OR c.first_name = :node_first_name) AND …
```

Two more variants share the shape, each with a count: **Parents** (an `INNER` edge join and `hn.node_id IN
(:node_ids)`) and **Simple** (`SELECT DISTINCT` node columns, `count(DISTINCT hierarchy_id, node_id)`).

With RFC 0020 Part A the edge becomes a grouped `@SourceModel(root = HierarchyEdgeEntity.class)` keyed `(hierarchyId,
endVertex)`, with `hops` and the deleted check in its per-request `where`, joined on `(hierarchyId, nodeId)`. The node's
profile `c` is an ordinary to-one path of `hierarchy_node`. The manager's profile `p` is not: it is reached through
the source's `startVertex`, and Part A joins a source only off an outer entity path, never anything off a source
(RFC 0020 A.1, "Unchanged"). So every manager column has to be an aggregate inside the source:
`MAX(manager.firstName)`, `MAX(manager.type.name)`, beside `MAX(startVertex)`. Under the intended data (one edge per
`(hierarchy_id, end_vertex, hops)`) each `MAX` reads the one row. With more than one row per group, `MAX(startVertex)`
and `MAX(manager.firstName)` can come from different managers, and nothing says so: the library never checks that
data is unique, it only refuses a nullable group key (`MQ3031`, R-SRC-02). `Agg.firstBy` would tie them to one row,
but it is `MQ4015` on MySQL (R-AGG-16), the database this report runs on.

The deleted checks on `c` and `p` sit in the `ON`: a deleted profile blanks its columns and keeps the node. A `where`
filter would drop the node instead, and a static `TableField.on(...)` (R-COL-03, R-COL-04) cannot take
`:include_deleted`, a per-request value.

## Design

### Part A — an entity joined off a joined source (R-SRC-23, R-SRC-24)

**A.1 The model.** A model that declares a `@JoinSource` declares an entity join with `@JoinEntity`, and reads the
entity's columns with a new `join` member on `@Column` and `@FilterColumn`:

```java
@SourceModel(root = HierarchyEdgeEntity.class)
@FilterColumn(name = "hops", path = "hops")
@FilterColumn(name = "deleted", path = "deleted")
public record ManagerEdge(
        @PrimaryKey @GroupBy Long hierarchyId,
        @PrimaryKey @GroupBy Long endVertex,
        @Aggregate(fn = AggregateFunction.MAX, attribute = "startVertex") Long startVertex,
        @Aggregate(fn = AggregateFunction.MAX, attribute = "createdAt") Instant createdAt) {}

@QueryModel(root = HierarchyNodeEntity.class)
@JoinSource(name = "edge", source = ManagerEdge.class, on = {
        @JoinSource.On(column = "hierarchyId", key = "hierarchyId"),
        @JoinSource.On(column = "nodeId", key = "endVertex")})
@JoinEntity(name = "manager", entity = ProfileEntity.class,
        on = @JoinEntity.On(join = "edge", field = "startVertex"))
@FilterColumn(name = "deleted", path = "deleted")
@FilterColumn(name = "nodeDeleted", path = "node.deleted")
@FilterColumn(name = "managerDeleted", join = "manager", path = "deleted")
public record NodeDetail(
        @PrimaryKey Long id,
        Long hierarchyId,
        Long nodeId,
        @Column(attribute = "node.firstName") String nodeFirstName,
        @Column(attribute = "node.type.name") String nodeType,
        @SourceColumn(join = "edge", field = "startVertex") Long managerId,
        @Column(join = "manager", attribute = "firstName") String managerFirstName,
        @Column(join = "manager", attribute = "type.name") String managerType,
        @SourceColumn(join = "edge", field = "createdAt") Instant createdAt) {}
```

`HierarchyNodeEntity` maps `nodeId` as a basic column and `node` as a read-only `@ManyToOne(fetch = LAZY)
@JoinColumn(name = "node_id", insertable = false, updatable = false)` to `ProfileEntity` (A.2, "outer plain column").

- **`on` covers the entity's full id, and only it.** Each `On` pairs a field of a joined source (`join`, `field`) with
  one id attribute of `entity` (`id`, default `""` for a single id). The pairs name every id attribute once: one for a
  single id, one per attribute of an `@IdClass`, one per attribute of an `@EmbeddedId` (`id = "key.part"`). Since the
  id is unique per row of the entity's table, the join adds at most one row per outer row: no row multiplication, so
  the outer key, cursors, counts and export pages keep R-PAG-01 to R-PAG-08 unchanged. A partial id, an extra pair, a
  non-id attribute (a unique natural key included: the metamodel cannot show uniqueness), an id attribute that is an
  association (derived identity), a converted source field, or an id whose boxed type differs from the source field's
  (INV-3; `int` against `Integer` is the same type) is `MQ3046`. The join compares database values, so a converted
  field is refused outright, as D-191 and D-189 refuse it for a joined source's key.
- **The anchor is a joined source of the same model.** `join` names a `@JoinSource` on the model; a field of the root,
  of a `@Join`, or of another `@JoinEntity` is `MQ3046` in this RFC. All pairs of one `@JoinEntity` name the same
  joined source.
- **`type` is `JoinKind.LEFT` by default**; `INNER` keeps only outer rows that have a matching entity row, which also
  drops the rows a `LEFT`-joined anchor source left unmatched (the flat SQL's meaning; A.6 pins the rendering that
  keeps it).
- **The entity's columns.** `@Column(join = "manager", attribute = "...")` reads an attribute path of the joined
  entity, checked against its metamodel with the codes a root path has (`MQ3001`, `MQ3002`, `MQ3014`, `MQ3016`,
  `MQ3029`), through to-one joins as `@Column.attribute` allows (R-COL-25, R-COL-29), so `type.name` is
  `left join profile_type pt on pt.id = p.type_id`. A path that crosses a to-many association is `MQ3046`, since it
  would multiply rows. `@FilterColumn(join = ...)` is the filter-only counterpart (R-COL-07). `join` defaults to `""`,
  the model's root, so every existing model compiles unchanged.
- **Out of Part A**, each `MQ3046`: a nested `Optional<Model>` (`@Join`) on a joined entity, since its presence key and
  mapper rules (R-COL-15, R-GEN-13) are written for association joins and flat columns cover the case; a `@PrimaryKey`
  on a joined-entity column; a `@JoinEntity` on a `@SourceModel`, whether or not R-SRC-21 lets that source join a
  source, since R-SRC-02's key and nullability rules would have to say what a LEFT-joined column does to a group key.
- **Several entity joins** are allowed, each with its own `name`, unique among the model's `@JoinSource` and
  `@JoinEntity` names (`MQ3046`). Two joins of one entity (`ProfileEntity` as the node and as the manager) stay apart
  by name (A.3).

**A.2 An entity off an outer entity's plain column: out.** The same mechanism could anchor `on` on a basic column of
the root (`p.id = hn.manager_id` with no association mapped). It is out of this RFC. An outer entity's column can be
mapped as a read-only association (`insertable = false, updatable = false`), which the metamodel then checks and to
which R-COL-25, R-COL-29 and `@Join` already apply; a source's column has no mapping at all, so there is no such route
for it, which is the gap this RFC closes. The annotation leaves room: a later `On(column = "...")` naming an outer path
in place of `join`/`field` is additive.

**A.3 Generated code and runtime API.** The generated `query(...)` keeps RFC 0020's signature, one `SourceQuery` per
joined source, and registers each entity join, which binds no request value:

```java
public static ModelQuery.Builder<HierarchyNodeEntity, Long, NodeDetail> query(SourceQuery<ManagerEdge> edge) {
    return ModelQuery.builder(ROOT, MAPPER).joinSource(EDGE, edge).joinEntity(MANAGER);
}
public static final SourceJoin<NodeDetail, ManagerEdge> EDGE =
        SourceJoin.of("edge", ManagerEdge.class, JoinKind.LEFT, List.of(
                SourceJoin.on(HIERARCHY_ID, QManagerEdge.HIERARCHY_ID),
                SourceJoin.on(NODE_ID, QManagerEdge.END_VERTEX)));
public static final ColumnField<NodeDetail, Long, Long> MANAGER_ID =
        ColumnField.over(NodeDetail.class, EDGE.root(), QManagerEdge.START_VERTEX);
public static final EntityJoin<NodeDetail, ProfileEntity> MANAGER =
        EntityJoin.of("manager", ProfileEntity.class, JoinKind.LEFT, List.of(EntityJoin.on(MANAGER_ID, "id")));
public static final ColumnField<NodeDetail, ProfileEntity, String> MANAGER_FIRST_NAME =
        ColumnField.of(NodeDetail.class, MANAGER.root(), "firstName", String.class);
public static final TableField<ProfileEntity, ProfileTypeEntity> MANAGER_TYPE_TABLE =
        TableField.join(MANAGER.root(), "type", JoinType.LEFT);
public static final ColumnField<NodeDetail, ProfileTypeEntity, String> MANAGER_TYPE =
        ColumnField.of(NodeDetail.class, MANAGER_TYPE_TABLE, "name", String.class);
```

- `@Incubating EntityJoin<M, E>` (`core`) is immutable (INV-9): a name, the entity class, the kind and the pairs.
  `EntityJoin.on(ColumnField<M, ?, K> anchor, String idAttribute)` pairs a column over a joined source's root with an
  id attribute. `MANAGER.root()` is a `TableField<E, E>` of a new kind, a *joined entity* keyed by the join's name, as
  R-SRC-21's joined source is: it resolves to its own join in `JoinContext`, created once per statement, and is equal
  only to a field of the same join name. `TableField.join(MANAGER.root(), ...)` and `ColumnField.of(...)` over it
  behave as over any entity `From`: INV-3 is checked at first resolution against `E`'s metamodel (`MQ1001`, `MQ1002`),
  and the join key of a child join starts from the join name (R-COL-02), so `MANAGER_TYPE_TABLE` never meets the
  node's `type` join.
- `ModelQuery.Builder#joinEntity(EntityJoin<M, ?>)` registers the join on the definition; every builder copy carries
  it. A definition is built without the metamodel (INV-9), so `MQ1911` is split by what each check needs (D-193,
  Resolution 8):
  - **when the query is built:** an anchor whose `isConverted()` holds, pairs over two joined sources or over a source
    the query does not bind, a second registration of one name;
  - **at first resolution, before any statement:** a partial or extra id pair, a non-id attribute, an id attribute that
    is an association (derived identity), an id whose boxed type differs from the anchor's (`int` against `Integer` is
    the same type, D-191), a to-many path below the joined entity (also in `Filters.exists`).

  A column or table over a joined entity the query does not register is `MQ1003` at first resolution, as a column over
  an unregistered joined source is (R-SRC-21). `MQ1910` stays the `SourceJoin` code.
- `@Incubating` throughout: `@JoinEntity`, `@JoinEntity.On`, `@Column#join`, `@FilterColumn#join`, `EntityJoin`,
  `EntityJoin.On`, `Builder#joinEntity`, `EntityJoinRenderer`, `ProviderSupport#entityJoins`.

**A.4 Nullability, filters, orders, groups, counts.**

- **Every joined-entity column is nullable under `LEFT`**, its id included, as a column of a LEFT `@Join` is. Under
  `INNER` the entity's own non-null attributes are non-null, and a to-one path from it follows R-COL-25. This holds
  because the join is always attached to the statement's root (A.6, D-193, Resolution 5), so `INNER` keeps only rows
  with a matching entity row, whatever kind the anchor source join is. Filters,
  orders and `Row.get` treat a joined-entity column as any column. A keyset order on a nullable one needs `nullsFirst`
  or `nullsLast` by the existing rules (R-COL-12, R-COL-13, R-PAG-05); a NULL in a keyset column is `MQ2202` as today.
  Nothing in R-PAG-* changes.
- **Grouping.** A grouped outer `@QueryModel` may group by a joined-entity column; a nullable group key follows the
  rules a LEFT `@Join` column has today (`MQ2201` in an export). No new rule.
- **`Agg.count` over a joined entity.** `Agg.count(MANAGER.root())` renders `count(p.id)` (the first id attribute) in
  both kinds: an unmatched `LEFT` row counts 0. `count(entity)` would render `count(<id>)` on both lines for a single
  id, but `count((a, b))` for a composite id on PostgreSQL and H2, which counts an unmatched LEFT row, so the
  first-id-attribute rule is required (spike A.4). R-AGG-19's row count (`count(*)`) does not apply: the joined entity is not a
  `jakarta Join` on 6.6 but is one on 7.4 (`JpaEntityJoin` extends `JpaJoin`, A.6), so a `Join#getParent()` walk would
  render `count(*)` on one line and `count(pt.id)` on the other. A joined entity, and every join below it
  (`Agg.count(MANAGER_TYPE_TABLE)` included), counts its identity (`count(<first id attribute>)`) on both lines and in
  both kinds; R-AGG-19's walk stops at a joined entity, as it stops at a joined source (D-193, Resolution 7). Making
  `INNER` under an `INNER` source a row count is a later optimisation.
- **`firstBy`.** `Agg.firstBy` (R-AGG-16) may read a joined-entity column in its value or order: the join is to-one,
  so it is never recorded as a to-many join, and the root id appended as the tie-breaker still picks one row. A
  to-many path below a joined entity is `MQ1911` at first resolution, raised before R-AGG-16's `MQ1412`.
- **Inspection** records the entity join (name, entity, kind, pairs) beside the joined source.

**A.5 Statements, locks, fetch plans, writes.**

- **Every statement carries the join**, even a `LEFT` join nothing reads. The engine creates it in the same step as
  its anchor source join (`ModelQuery.java:636-661`), right after it, so it is on exactly the statements R-SRC-22 puts
  that join on: content, count, the count over groups (through `ProviderSupport#countQuery`), the root-id count of a
  treated path, both primary-key-first steps, the keyset page, every `byKeys` chunk, `one(q, key)`, the `through`
  child load, every export page and the stream, and the parent reads of a fetch plan. The phases keep the same joins (R-QRY-09). A `LEFT` join on a full id never changes the row
  count. PostgreSQL removes an unreferenced LEFT join to `profile` on its primary key, with an ON filter, in the count,
  the page and the export (plan: 0 scans, spike A.4); MySQL and H2 do a primary-key lookup per outer row: 0 to 1% of a
  page of 20, 4 to 5% of a count and 3 to 4% of an export on MySQL, and 12 to 15% on H2. One rule, as R-SRC-22 chose,
  needs no proof per statement that dropping the join keeps the rows.
- **The count over groups.** The entity join adds no rule here. A grouped query that joins a source counts its groups
  with `createCountQuery()` only where Hibernate can: it fails on 6.6 on every vendor and on 7.4 with H2 for any joined
  CTE, entity join or not (spike A.2), and the entity join survives the copy where it runs (7.4 PostgreSQL and MySQL).
  The executor counts the groups of any query that joins a source client-side (R-EXE-03, D-192), so a query with an
  entity join takes that path.
- **Locks.** A query with an entity join has a joined source, so `withLock(...)` is `MQ1903` already (R-SRC-11,
  R-SRC-22). No new code.
- **Fetch plans.** A parent model with an entity join carries it on its parent reads. A `@Child` whose child model has
  one also joins a source, so it is `MQ3039` already.
- **Writes.** `ModelUpdate`, `ModelDelete`, `InsertColumns` and `ModelInsert.select` take no source, so a model with an
  entity join is refused as a model with a joined source is, by the existing codes.

**A.6 Rendering.** The engine builds the key predicate in `core` over standard Criteria, as R-SRC-22 does for a joined
source; a new provider hook creates the join and applies it:

```java
// core, @EngineFacing @Incubating; returned by ProviderSupport#entityJoins(), empty by default (MQ4014)
public interface EntityJoinRenderer {
    <E> From<?, E> join(From<?, ?> anchor, Class<E> entity, JoinKind kind, Function<From<?, E>, Predicate> on);
}
```

- **`JoinKind`, not `JoinType`.** The kind is `annotations.JoinKind` (`INNER`, `LEFT`), as `SourceRenderer#join`
  takes since the M32 gate: JPA's `JoinType.RIGHT` has no meaning here and the type would be costly to change after
  1.0.
- **Why a hook.** An entity join by column equality, not through an association, has no portable Criteria form on the
  6.6 line: Jakarta Persistence 3.1 `From#join` takes an attribute or an attribute name only. 3.2 adds
  `From#join(Class<Y>, JoinType)` and `join(EntityType<Y>, JoinType)`, both returning `Join<X, Y>` (checked with
  `javap` on `jakarta.persistence-api` 3.1.0 and 3.2.0), but `core` compiles against 3.1.0 and the 6.6 line runs it,
  so `core` cannot call it. The hook keeps `core` free of profiles and `jpa` free of `org.hibernate` (INV-6, INV-7).
- **Hibernate.** `JpaFrom#join(Class<X>, SqmJoinType)` exists on both lines and returns `JpaEntityJoin` (`javap`,
  6.6.58 and 7.4.11): abstract on 6.6, a default on 7.4, with the same erased descriptor, so a renderer compiled
  against 6.6 links and runs on 7.4 (spike A.1: a direct `JpaEntityJoin#on` compiled against 6.6 throws
  `NoSuchMethodError` on 7.4, so the `on` is looked up reflectively on the returned type). The return type's place differs: on 6.6 `JpaEntityJoin<T>`
  extends `JpaJoinedFrom<T, T>`, not a `jakarta Join`, and declares `on(Predicate...)`; on 7.4 `JpaEntityJoin<L, R>`
  extends `JpaJoin<L, R>` and inherits `on(Predicate...)` from it, returning `JpaJoin`. So the renderer applies `on`
  reflectively, as RFC 0020's `SourceRenderer#join` does, and the hook returns a plain `From`. `core` never calls `on`
  itself and calls the `on` function once, so a join filter (Part B) is ANDed into the one predicate rather than set by
  a second `on` call: Hibernate 6.6 ANDs successive `on` calls and 7.4 replaces the first (spike B.1), so the engine
  calls `on` exactly once on every line.
- **The anchor.** The engine passes the statement's root as `anchor`, so the entity join is a sibling of the source's
  CTE join in the root's join list, created after it (the anchor source join is resolved first, since the key
  predicate reads it). That renders the flat SQL, `from hierarchy_node hn left join edge x on … left join profile p on
  p.id = x.mq_startVertex`, in which an `INNER` entity join drops the rows a LEFT source left unmatched. The join is
  attached to the root on every line, never under the CTE join (`cteJoin.join(ProfileEntity.class, …)`): the
  contract does not depend on a provider's grouping of nested joins. Spike A.2 rendered the CTE anchor and the root
  anchor identically on both lines and all three vendors. A provider whose hook cannot attach to the root refuses an
  entity join with `MQ4014`; the meaning never changes per line. Creation order is rendering order: spike A.2 confirms
  it on all vendors and both lines.
- **Order of checks.** The vendor gate `MQ4015` (R-VND-21), then the `SourceRenderer` `MQ4014`
  (`ModelQuery.java:631`), then the `EntityJoinRenderer` `MQ4014`.
- **Vendors.** No new database fact. A query with an entity join already has a joined source, gated by
  `sources(SourceFeature.CTE)` (R-VND-21: `MQ4015` on `OTHER`). A provider without the hook is `MQ4014` before any
  statement, and `PortabilityCheck` reports it per target.

**A.7 The motivating query.** The Details variant, with Part B for the profile deleted checks:

```java
SourceQuery<ManagerEdge> edge = QManagerEdge.source()
        .where(f -> f.eq(QManagerEdge.HOPS, hops)
                .eq(QManagerEdge.HIERARCHY_ID, hierarchyId)                        // Optional<Long>, for cost
                .when(!includeDeleted, g -> g.ne(QManagerEdge.DELETED, true)))
        .build();
ModelQuery<HierarchyNodeEntity, Long, NodeDetail> q = QNodeDetail.query(edge)
        .joinFilter(QNodeDetail.NODE_TABLE, f -> f.when(!includeDeleted, g -> g.ne(QNodeDetail.NODE_DELETED, true)))
        .joinFilter(QNodeDetail.MANAGER, f -> f.when(!includeDeleted, g -> g.ne(QNodeDetail.MANAGER_DELETED, true)))
        .where(f -> f.eq(QNodeDetail.HIERARCHY_ID, hierarchyId)
                .eq(QNodeDetail.MANAGER_ID, managerId)                             // Optional<Long>
                .eq(QNodeDetail.NODE_ID, nodeId)
                .eq(QNodeDetail.NODE_FIRST_NAME, nodeFirstName)
                .when(!includeDeleted, g -> g.ne(QNodeDetail.DELETED, true)))
        .build();
Slice<NodeDetail> page = executor.page(q, PageSpec.of(0, 20), CountMode.COUNT);
```

`NODE_TABLE` is `TableField.join(ROOT, "node", JoinType.LEFT)`, equal by join key (R-COL-02) to the join the
`node.*` columns read through (R-COL-25), so the filter lands on that one join. Rendered on MySQL, `include_deleted = 0`, every optional value present; the join
order is creation order (spike A.2):

```sql
with edge as (select e.hierarchy_id mq_hierarchyId, e.end_vertex mq_endVertex,
                     max(e.start_vertex) mq_startVertex, max(e.created_at) mq_createdAt
              from hierarchy_edge e
              where e.hops = ? and e.hierarchy_id = ? and e.is_deleted <> ?
              group by e.hierarchy_id, e.end_vertex)
select hn.id, hn.hierarchy_id, hn.node_id, c.first_name, ct.name, x.mq_startVertex, p.first_name, pt.name, x.mq_createdAt
from hierarchy_node hn
left join edge x           on x.mq_hierarchyId = hn.hierarchy_id and x.mq_endVertex = hn.node_id
left join profile p        on p.id = x.mq_startVertex and p.is_deleted <> ?
left join profile c        on c.id = hn.node_id and c.is_deleted <> ?
left join profile_type ct  on ct.id = c.type_id
left join profile_type pt  on pt.id = p.type_id
where hn.hierarchy_id = ? and x.mq_startVertex = ? and hn.node_id = ? and c.first_name = ? and hn.is_deleted <> ?
order by hn.id
limit ?, ?
```

The count is the same `from … where` with `count(*)` (R-AGG-19), carrying both source and entity joins (A.5).
**Parents** is a second model with `@JoinSource(type = INNER)`, or the Details model with
`isNull(MANAGER_ID, Optional.of(false))`, plus `in(NODE_ID, nodeIds)`. **Simple** is a model of the node columns only.

Mapping notes:

- **`:manager_id` stays an outer filter on the joined source column.** In the native SQL it sits in the derived
  table's `WHERE`, over a LEFT-joined edge, so it drops nodes without that manager. Inside the source's `where` it
  would only empty the source for other managers, and the LEFT join would keep every node with NULL manager columns.
- **`:hierarchy_id` goes into both `where`s.** The outer one gives the rows; the source's one narrows the grouped CTE,
  which otherwise aggregates every edge of every hierarchy on every statement (R-SRC-22's documented cost).
- **The `hierarchy` join is dropped.** `h.id = hn.hierarchy_id`, so `h.id = :hierarchy_id` is `hn.hierarchy_id = ?`,
  and the edge joins on `hn.hierarchy_id`.

What still differs from the native SQL:

1. **Simple's `DISTINCT`.** The model is keyed on `hierarchy_node.id` (R-PAG-01's unique order key). If `(hierarchy_id,
   node_id)` is unique in `hierarchy_node`, the rows are the native ones and the count equals `count(DISTINCT
   hierarchy_id, node_id)`. If it is not, the model returns one row per `hierarchy_node` row; a grouped model keyed
   `(hierarchyId, nodeId)` is the route, which reads only group keys and aggregates.
2. **Without Part B**, the deleted checks on `c` and `p` with `include_deleted = 0` have no `ON` form: a `where`
   filter drops the node, where the native SQL blanks its profile columns. With `include_deleted = 1` the two agree.
3. **The source key must be unique in the data**, which the library cannot check. `(hierarchyId, endVertex)` under a
   fixed `hops` is one edge only if `hierarchy_edge` has NOT NULL and a unique index on `(hierarchy_id, end_vertex,
   hops)` (or the `where` admits one row per group otherwise); the grouping then makes each `MAX` read that row. If the
   data holds two edges, `MAX(start_vertex)` and `MAX(created_at)` can still come from different edges, but the
   manager's profile columns now always belong to the selected `start_vertex`, which is what Part A buys. Each group
   key must be non-nullable in its mapping (`MQ3031`, R-SRC-02): `hierarchyId` mapped as a basic `nullable = false`
   column passes; read through a `@ManyToOne hierarchy` it is `MQ3031` unless that association is `optional = false`.

### Part B — a request-bound `ON` filter (R-COL-39)

**B.1 The call.** A built query binds a filter into the `ON` of one LEFT join:

```java
ModelQuery.Builder<E, K, M> joinFilter(EntityJoin<M, ?> join, UnaryOperator<Filters<M>> on);   // Part A join
ModelQuery.Builder<E, K, M> joinFilter(TableField<?, ?> table, UnaryOperator<Filters<M>> on);   // to-one LEFT join
```

- **It runs once.** The `UnaryOperator` runs at the `joinFilter` call (`FilterGroup.collect`), as `where`'s does, so a
  `null` value is `MQ1301` there; the query keeps only the recorded terms, never the lambda, so no request state is
  captured on the definition.
- **It binds as `where` does.** The filter and its values live on the built `ModelQuery`, an immutable value built per
  request, exactly as its `where` and a `SourceQuery`'s `where` do. Nothing is held on `EntityJoin` or `TableField`, so
  the static constants stay shared (INV-9), and the join key stays what R-COL-02 and A.3 say (no alias needed, unlike
  R-COL-04's static `on`, whose lambda cannot be compared). One join takes one filter per query; a second
  `joinFilter` on the same join key replaces the first, as a second `where` does (D-193, Resolution
  4). Every builder copy carries it.
- **Skipping.** The `Optional` skip contract and `when(...)` apply (R-FLT-01, R-FLT-13): a filter whose terms are all
  skipped adds nothing to the `ON`.
- **Rendering.** The engine ANDs the filter into the join's one `on` call: the entity join's key predicate (A.6), an
  association join's `Join#on` (R-COL-03), together with a static `TableField.on(...)` if the table has one. Values
  bind as statement parameters, once per statement that has the join. No new hook. Hibernate 6.6 ANDs successive `on`
  calls and 7.4 replaces the first (spike B.1), so the engine calls `on` exactly once on every line.

**B.2 What it may reference, and why.** Only columns whose table is the filtered join itself, plus values and
constants (`Expr.value`, R-COL-26). Anything else is `MQ1912` when the query is built:

- **another table** (the root, a sibling join, a joined source). An `ON` that reads a table joined after it is
  invalid SQL, and which tables come before depends on resolution order, which is not part of the contract.
- **a to-one path from the joined table** (`type.name` in the manager's `ON`). The path's own join would be created
  while the `ON` is built and rendered after it, the same ordering problem; filter the next join instead
  (`joinFilter(MANAGER_TYPE_TABLE, …)`).
- **a to-many path or an `exists`.** A to-many join multiplies rows, R-PAG-*'s concern; an `exists` in an `ON`
  renders the same SQL on H2, PostgreSQL and MySQL on both lines (spike B.1), so its refusal is a scope decision, not a
  vendor gap: Part B takes values and constants only, and lifting it is a later RFC.
- **an aggregate, a window or a correlated value**, which have no meaning per joined row.
- **a sub-query term that crosses the filtered join** (`exists`, `in(subSelect)`, a `Correlated` whose path goes through
  a join-filtered join): Hibernate 6 drops the `ON` of a join made off a correlated root (`JoinContext.java:952-957`),
  so the sub-query would read unfiltered rows (spike B.1: both lines, all three vendors).
- **an `INNER` join or a root.** On `INNER` an `ON` filter equals a `where` filter; one spelling stays. A to-many join
  takes the static `on(...)` only.
- **a join an id-form column reads** (R-COL-29: `@Column(attribute = "node") Long nodeId`). That column reads the
  target's id from the joined row, so the filter would blank it too, while the native SQL reads the foreign key.
  Refusing it keeps INV-5; the message names the column and points at mapping the foreign key as a basic column.

**B.3 Filters that read the same join.** One path is one join (R-COL-05), so every column of the filtered join, in
select, `where`, order, group and keyset, reads the filtered row. A `where` filter on a blanked column sees NULL:
`eq(MANAGER_FIRST_NAME, "Ann")` drops a node whose manager is deleted, as the native SQL's outer `WHERE` does. An
order on such a column sorts the blanked rows as NULL (R-COL-12). `Agg.count(MANAGER.root())` counts 0 for them
(A.4), and a LEFT `@Join` nested model reads them as absent through its presence key (R-COL-15), which reads the same
join.

**B.4 Statements.** The filter goes wherever the join renders. The Part A entity join is on every statement (A.5),
so its filter is too. An ordinary to-one join renders where a column reads it, as today (R-COL-05, AC-COL-07); a
`joinFilter` does not create it, since a LEFT to-one join with a filtered `ON` changes no row count. A join filter
whose join key the content statement never creates (a `TableField` with another `JoinType` or alias than the one the
columns read, R-COL-02) would filter nothing and return the deleted rows with no error (INV-5), so it is `MQ1912` at
first resolution. The phases keep
the same joins and `ON`s (R-QRY-09), so primary-key-first paging (R-PAG-07, R-PAG-08) and keyset (R-PAG-04 to
R-PAG-06) are unaffected. Inspection records each join filter.

**B.5 Recommendation: accept with Part A, as a separate slice after it.** The motivating report needs both: without B
its `c` and `p` deleted checks cannot be expressed (A.7, item 2), and the only workaround is a CASE per selected column
over a per-request value, which needs a per-request select set. B needs no hook (A.6's `on` function already takes
the predicate), no annotation and no vendor fact, and its refusals are at query build, save the uncreated join key (B.4), checked at first resolution. It touches ordinary `@Join`
paths (R-COL-03's `on`, R-COL-29's id form), so it is reviewed on its own; if the review finds the id-form refusal or
the replace-on-second-call rule wrong for existing models, Part A ships alone and B waits, with no change to A.

### Vendor and provider SPI

- **`ProviderSupport#entityJoins()`** (`jpa.spi`, R-VND-32), `@Incubating`, returns an `Optional<EntityJoinRenderer>`,
  empty by default, so a query with an entity join on a provider without it is `MQ4014` before any statement. The
  `@EngineFacing @Incubating core.EntityJoinRenderer` has `join` (A.6). A provider fact, no `VendorProfile` change
  (R-VND-11, INV-6).
- **`model-query-hibernate`** implements it on both lines over `JpaFrom#join(Class, SqmJoinType)` with a reflective
  `on`; `jpa` still imports no `org.hibernate` (INV-7).
- **No new `SourceFeature`.** The entity join needs a joined source, which `sources(SourceFeature.CTE)` gates.
- Part B adds nothing to the SPI.

### Spike plan

Done: every item below ran, nothing is deferred; results in `rfc/0021-spike-results.md`. Two acceptance criteria come
from it: the engine passes a join's predicate to `on` exactly once (AC-SRC-58 for the entity join, AC-COL-55 with a
join filter), and `Agg.count` over a composite-id joined entity counts its first id attribute, the PostgreSQL and H2
`count((a, b))` case (AC-SRC-58).

A throwaway branch `spike/rfc-0021` with one test-source module `spike-rfc0021/`, on Hibernate 6.6.58.Final and
7.4.11.Final, against H2, PostgreSQL 16 (`postgres:16-alpine`) and MySQL 8.0.31. Results go to
`rfc/0021-spike-results.md` in `rfc/0020-spike-results.md`'s shape (a verdict per part, **Matrix**, **Findings**,
**Line parity**, **Consequences for the RFC**), raw data in `rfc/0021-spike-data/` with a README of reproduction
commands. Production `src/main` and committed SQL snapshots stay unchanged.

The dataset is the motivating case's shape: 3 hierarchies, about 5,000 nodes each, closure edges up to 6 hops, a
unique index on `(hierarchy_id, end_vertex, hops)`, about 5% deleted profiles and nodes, some nodes with no edge at
the requested `hops`.

1. **A.1 Signatures.** `javap` on 6.6.58 and 7.4.11: `JpaFrom#join(Class, SqmJoinType)` and its descriptor; whether a
   call compiled against 6.6 links on 7.4; `JpaEntityJoin`'s supertypes and the `on(Predicate...)` each line declares;
   whether `join(EntityDomainType, SqmJoinType)` is needed for an `@IdClass` entity.
2. **A.2 Rendering.** The hook with the root as anchor, then with the CTE join as anchor: SQL per vendor and line,
   `LEFT` and `INNER`, an `INNER` entity join under a `LEFT` and an `INNER` source (do unmatched source rows drop?), and
   whether creation order is rendering order. On every statement kind of A.5's list: content page, count, the count over
   groups through `ProviderSupport#countQuery` (which may copy the query), the root-id count, both primary-key-first
   steps, keyset page, export page and stream, `byKeys` chunk, `one(q, key)`, the `through` child load, a fetch plan's
   parent read.
3. **A.3 Paths and keys.** A to-one path from the joined entity (`type.name`), two joins of one entity (node and
   manager), an `@IdClass` and an `@EmbeddedId` target, a filter, an order and a keyset page over a nullable joined
   column with `nullsLast`, `Agg.count` over an unmatched LEFT entity join (expect 0), a grouped outer model grouping
   by a joined column.
4. **A.4 Cost.** The Details page of 20, its count and a full export, with and without the entity join unreferenced in
   the count: `EXPLAIN (ANALYZE, BUFFERS)` on PostgreSQL (is the unreferenced LEFT join on `profile.id` removed?),
   `EXPLAIN ANALYZE` on MySQL, `EXPLAIN` on H2; medians of 7 alternating runs, against the native SQL.
5. **A.5 Parity.** Details, Parents and Simple against the native SQL's rows on all three vendors, `include_deleted`
   0 and 1, each optional filter present and absent; record which rows differ and why (A.7's list).
6. **B.1 Join filters.** A filter in the entity join's `ON` and in an association join's `ON` (alone and beside a
   static `TableField.on`): SQL, one `on` call, rows with a deleted profile blanked not dropped, values bound once per
   statement in content and count; an `exists` inside an `ON` per vendor (recorded, not shipped).
7. **Line parity.** The probe module compiles and behaves the same on 6.6 and 7.4.

### New ids (placeholders)

Checked against the spec and RFC 0020's ids: the highest in use are R-SRC-22, R-COL-38 (released unused by D-184),
R-PROC-45, D-192, R-VND-31 (released unused by D-184), AC-SRC-52 (D-192), AC-COL-53, `MQ1910` and `MQ3045`. Every id below is above
both and unused in the repository.

| Id | What |
|---|---|
| R-SRC-23 | A model that joins a source joins an entity on its full id, equal to fields of one joined source (`@JoinEntity`, `EntityJoin`, `joinEntity`); a joined entity is a table-field kind keyed by the join name; its columns are nullable under `LEFT`; `Agg.count` over it and every join below it counts its id on both lines; `firstBy` may read it |
| R-SRC-24 | The entity join is created right after its anchor source join, so it is on exactly R-SRC-22's statements; it renders through `EntityJoinRenderer` with the root as anchor on every line; locks, fetch plans and writes follow the joined source |
| R-COL-39 | `joinFilter`: a request-bound filter ANDed into one LEFT join's `ON`, over that join's own columns only |
| R-PROC-46 | `@JoinEntity`, `@JoinEntity.On`, `@Column#join`, `@FilterColumn#join` checks |
| R-VND-32 | `ProviderSupport#entityJoins()`, `EntityJoinRenderer` |

| AC | Asserts |
|---|---|
| AC-SRC-53 | A LEFT entity join off a joined source returns the hand-written SQL's rows; an unmatched source or entity row reads NULL; two joins of one entity stay apart; a to-one path from the joined entity reads through its own LEFT join |
| AC-SRC-54 | The entity join is in every statement of R-SRC-22's list, after its anchor source join |
| AC-SRC-55 | `INNER` drops outer rows with no entity row, including those the LEFT source left unmatched, in content and count alike; keyset pages over a nullable joined column with `nullsLast` lose no row; `Agg.count` over an unmatched LEFT entity join is 0 |
| AC-SRC-56 | `MQ3046`: partial id, extra pair, non-id attribute, association id, converted field, boxed-type mismatch, unknown join or field, anchor not a joined source, a to-many path, a nested `@Join`, a `@PrimaryKey` on a joined column, on a `@SourceModel`, a duplicate name; `MQ1911` for the hand-written forms, each at build or at first resolution as A.3 splits them; `MQ1003` for a column over an unregistered joined entity |
| AC-SRC-57 | `MQ1903` for a lock and `MQ3039` for a child model with an entity join, unchanged; an `@IdClass` and an `@EmbeddedId` target join on every id attribute |
| AC-SRC-58 | A provider without `entityJoins()`: `MQ4014` before any statement, after `MQ4015` and the `SourceRenderer` `MQ4014`; `PortabilityCheck` reports it per target; `Agg.count` over a joined entity and over a join below it renders `count(<id>)` on both lines, and over a composite-id joined entity counts its first id attribute; the provider's `on` is called once per entity join; `firstBy` over a joined-entity column returns one row per group |
| AC-COL-54 | A join filter on a LEFT entity join and on a LEFT to-one join blanks the joined columns and keeps the row, on every vendor and line, in content, count, both primary-key-first steps and export |
| AC-COL-55 | A join filter ANDs with a static `TableField.on` in one `ON`, passed to the provider's `on` once; a skipped filter adds nothing; a `where` filter on a blanked column drops the row |
| AC-COL-56 | `MQ1912`: another table, a to-one or to-many path, `exists`, an aggregate, an `INNER` join, a root, a join an id-form column reads, a sub-query term crossing the filtered join, a filter on a join key the content statement never creates; a `null` value is `MQ1301` at the `joinFilter` call |
| AC-COL-57 | A `joinFilter` alone creates no association join; the static constants are unchanged after a filtered build (INV-9) |

| Code | Meaning |
|---|---|
| `MQ1911` | A hand-written `EntityJoin`: at build, a converted anchor, pairs spanning two sources or over an unbound source, a join registered twice; at first resolution, pairs that miss or exceed the id, a non-id or association id attribute, a boxed-type mismatch, a to-many path from a joined entity |
| `MQ1912` | A join filter that reads another table, a path from the filtered table, a to-many path, `exists`, an aggregate, window or correlated value, or a sub-query term crossing the filtered join; on an `INNER` join, a root, a join an id-form column reads, or a join key the content statement never creates |
| `MQ3046` | A `@JoinEntity` whose `on` misses or exceeds the id, names an association id attribute or a converted field, mismatches a boxed type, names an unknown or non-source join or an unknown field; a duplicate join name; on a `@SourceModel`; a `join` member naming an unknown entity join; a to-many path, a nested `@Join` or a `@PrimaryKey` over a joined entity |
| `MQ4014` | Widened to a provider without `entityJoins()`; meaning unchanged |

## Compatibility

All additive. `@Column` and `@FilterColumn` gain a `join` member defaulting to `""`, the root, so every existing model
compiles and generates the same code. `ProviderSupport` gains a default method returning empty, so a third-party
provider fails safe (`MQ4014`). `ModelQuery.Builder` gains `joinEntity` and `joinFilter`; no shipped signature,
result or committed SQL snapshot changes. Everything new is `@Incubating`; the freeze review decides each piece.

## Alternatives

- **Aggregates inside the source** (today's workaround with RFC 0020 Part A). Each manager column is `MAX(...)` over
  the edge's to-one path. It reads the right row only if the data has one row per group, and with two it mixes columns
  of different managers with no error (§Motivation); `Agg.firstBy` ties them to one row but is `MQ4015` on MySQL. It
  also groups profile columns into the source, so the CTE joins `profile` and `profile_type` for every edge the
  `where` admits, not for the page.
- **Chaining `@JoinSource` off another joined source.** Useful only when the target is itself a source, and RFC 0020
  Part A leaves source-to-source out. Wrapping `profile` in an ungrouped `@SourceModel` to join it by its key would
  route every profile column through a CTE, give up the to-one paths and the metamodel checks of an entity, and still
  need the anchor rule this RFC adds.
- **A `QueryCustomizer`** adding the join by hand. It bypasses the definition: the join is not in inspection, the
  count and primary-key-first phases must be kept in line by the author (R-QRY-09 warns or throws, `MQ2206`), and a
  customizer-added selection cannot be read through `Row` (R-QRY-08).
- **A native query.** What the report is today: no keyset, no export, no count derived from the same definition, and
  one string per variant.
- **An entity off an outer entity's plain column**, in this RFC. Out, for A.2's reason: a read-only association
  mapping covers it with the checks the library already has.
- **A join filter held on a `TableField` constant.** It would need a per-request constant, breaking INV-9, or a
  lambda that captures the request, which R-COL-04's comparison rule cannot key.

## Resolutions to record

No question stays open. Each resolution below is recorded in D-193, with the spike's evidence
(`rfc/0021-spike-results.md`).

1. **The annotation shape.** Options: (a) a class-level repeatable `@JoinEntity(name, entity, type, on =
   @JoinEntity.On(join, field, id))`, columns via `@Column(join = ...)`; (b) entity joins nested in the source,
   `@JoinSource(..., entities = @JoinSource.Entity(...))`; (c) a separate `@EntityColumn(join, attribute)` in place of
   `@Column#join`. Decided: (a) with `@Column#join`. A joined entity's column is checked as an entity path, the
   check `@Column` already runs, so a separate annotation (RFC 0020's reason for `@SourceColumn`) does not apply; (a)
   keeps the join named on its own, which A.2's later outer-column anchor needs, where (b) would tie it to a source.
2. **The hook's home.** Options: a new `EntityJoinRenderer` behind `ProviderSupport#entityJoins()`, or a
   `joinEntity` method on `SourceRenderer`. Decided: the new renderer, since an entity join is not a source
   feature and A.2's later anchor would reuse it.
3. **A portable default.** Options: `MQ4014` without a provider, or a `core` default that calls Jakarta
   Persistence 3.2's `From#join(Class, JoinType)` reflectively when present. Decided: `MQ4014` now; the 3.2
   default is additive once the `core` baseline moves to 3.2, and it would need the same one-`on` rule, since a
   `Join#on` on 3.2 replaces the previous one (spike B.1).
4. **A second `joinFilter` on one join.** Options: replace, as `where` does, or `MQ1912`. Decided:
   replace, matching `where`; inspection shows the one in force.
5. **The anchor.** The root, never the CTE join, on every line: `INNER` keeps only rows with a matching entity
   row whatever kind the source join is, so the entity's non-null attributes are non-null and its to-one paths follow
   R-COL-25. The contract does not depend on a provider's grouping of nested joins, although spike A.2 rendered both
   anchors identically on both lines and all three vendors. A provider whose hook cannot attach to the root refuses an
   entity join with `MQ4014`; the meaning never changes per line.
6. **Part B with Part A** (B.5): accepted together, built as a later slice, with the id-form refusal; Part A
   ships alone if Part B's review rejects it.
7. **The count over a joined entity:** a joined entity and every join below it count their identity
   (`count(<first id attribute>)`) on both lines and in both kinds; R-AGG-19's walk stops at a joined entity, as it
   does at a joined source. No row-count form in this RFC.
8. **When `MQ1911` is checked.** At build, without the metamodel: a converted anchor, pairs over two joined
   sources or an unbound source, a name registered twice. At first resolution, before any statement: a partial or
   extra id pair, a non-id attribute, an association id attribute, a boxed-type mismatch (D-191), a to-many path below
   the joined entity. A column over an unregistered entity join is `MQ1003`; `MQ1910` stays the `SourceJoin` code.
9. **The `EntityJoinRenderer` kind parameter:** `annotations.JoinKind`, as `SourceRenderer#join` (M32 gate).
