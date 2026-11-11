# RFC 0024 — Nested models over declared joins: `@Join(entityJoin)`, and nested models with their own joins

- **Status:** draft
- **Affects:**
  - **`processor/30`:** R-PROC-44 (a nested `@Join` model that joins a source or declares an entity join is no
    longer `MQ3044` once Part B lands), R-PROC-46 (`@Join` beside an entity join narrowed); new R-PROC-48
    (`@Join(entityJoin)`).
  - **`processor/31`:** R-GEN-12 / R-GEN-13 (how a nested model's presence is read when its table is a joined entity,
    not an association); new R-GEN-43 (the outer factory over nested source joins, Part B).
  - **`processor/32`:** `MQ3006`, `MQ3039` (also walks nested joins), `MQ3044`, `MQ3046`; new `MQ3047`.
  - **`api/10`:** R-COL-15 / `MQ1104` (`TableField#presentBy` on a joined-entity root), `TableField#propertyPath` for
    a named joined-entity root.
  - **`api/17`:** R-SRC-23, R-SRC-25 (the anchor rule; Part C only).
  - **`api/15`:** `MQ3039` sees the joins of nested models.
  - **`reference/90`, `91`, `92`:** new codes, D-213 onward, D-189 and D-197 amended.
  - **`docs/site`:** `models.md` (Joins), `source-models.md` (Joining an entity), `diagnostics.md`.
- **Discussion:** TBD

## Summary

A `@Join` nested model today follows a mapped to-one association only, and a nested model may not declare joins of
its own (`MQ3044`, D-189). This RFC closes both gaps in three parts:

- **Part A — `@Join(entityJoin = "name")`.** An `Optional<Nested>` read through a `@JoinEntity` the outer model
  declares, so a plain foreign-key column (`Long customerId`) can give a nested model in one statement without
  mapping an association.
- **Part B — nested models with their own `@JoinEntity` / `@JoinSource`.** A model used through `@Join` keeps the
  joins it declares; the outer query registers them under prefixed names, and the outer factory takes the nested
  models' `SourceQuery`s.
- **Part C — an entity join anchored on another entity join.** What Part A and Part B need when combined: a nested
  model read through an entity join that itself declares an entity join. Today `MQ1911` (R-SRC-25, RFC 0022
  Resolution 6) refuses that chain.

Part A is additive and can ship on its own. Part B and Part C each need a spike first.

## Motivation

A table that stores a foreign key as a basic column, with no association mapped, is common: legacy schemas,
entities shared across services, or a key whose association is left out on purpose to keep writes simple.

```java
@Entity
class OrderEntity {
    @Id Long id;
    @Column(name = "customer_id") Long customerId;   // no @ManyToOne
}
```

To read the customer with the order today, the user picks one of three things, each with a cost:

| Today | Cost |
|---|---|
| Map a read-only `@ManyToOne` and use `@Join` | Changes the entity; not always the user's to change |
| `@Child(key = "customerId") Optional<Buyer>` | One more statement per page; can't filter or sort the parent on the customer's columns |
| `@JoinEntity` with flat `@Column(entityJoin = ...)` fields | No nested model: every customer column is repeated on every model that needs it |

The engine already has the join (`EntityJoin`, RFC 0021/0022): it is the model shape that is missing.

Part B follows from the same request. A nested model is a reusable view (`Buyer` is used by orders, invoices and
returns). When `Buyer` reads its region through an entity join or its totals through a joined source, it cannot be
nested today: `MQ3044` refuses it, because the outer statement would carry a join that nested model cannot bind
(D-189).

## Design

### Part A — `@Join(entityJoin)`

```java
@QueryModel(root = CustomerEntity.class)
public record Buyer(@PrimaryKey Long id, String name, @Join Optional<RegionView> region) {}

@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "buyer", entity = CustomerEntity.class,
        on = @JoinEntity.On(column = "customerId"))
public record OrderWithBuyer(
        @PrimaryKey Long id,
        Long customerId,
        @Join(entityJoin = "buyer") Optional<Buyer> buyer) {}
```

**Annotation.** `@Join` gains `String entityJoin() default ""` (`@Incubating`, like `@Join`). It names a
`@JoinEntity` of the same model. It excludes `attribute` and `treat` (a non-empty pair is `MQ3047`).

**Rules (new R-PROC-48, `MQ3047` for each):**

1. The named `@JoinEntity` exists on the model and its `entity` is the nested model's root.
2. `type` is not written on `@Join`: the join kind is the `@JoinEntity`'s.
3. The nested model has a `@PrimaryKey` (as `MQ3006` already requires): its key columns decide presence.
4. A nested `@Join` with `type = INNER` below a `LEFT` entity join is refused. It would drop the outer rows whose
   entity missed, which R-SRC-25 documents for anchors but a model author would not expect from a nested field.
5. Until Part B lands, the nested model may not declare its own `@JoinEntity` or `@JoinSource` (`MQ3044`, as today).
   To-one `@Join`s below it are allowed: R-SRC-23 already allows to-one paths below an entity join and refuses
   to-many ones.
6. Two `@Join(entityJoin = ...)` fields may name the same join; they read the same row (no second join).
7. `@Column(entityJoin = "buyer")` flat fields and `@Join(entityJoin = "buyer")` may sit on one model.

**Generated code.** The same as an association `@Join`, with the joined-entity root as the table:
`<PREFIX>_TABLE` is the `EntityJoin` constant's `root()` with `presentBy(QBuyer.KEY)`, the nested columns use
`withTable(Outer.class, BUYER.root())`, joins below it use `withParent(BUYER.root())`, and the mapper reads them
through `row.scoped(BUYER_TABLE)`. `Optional.empty()` means every key column is `null`, the same as R-GEN-12/13.

**Core changes (`@Incubating`, in `api/10`):**

- `TableField#presentBy` accepts a joined-entity root. Today it throws `MQ1104` (`requireJoin` treats any root as a
  root), and the copy it builds drops the join name. Both are fixed together: relaxing only the check would make the
  copy lose its entity-join identity.
- `propertyPath()` returns the join name for a named joined-entity root, so a sort or a `FilterSpec` can say
  `buyer.name` instead of a bare `name` that clashes with the root's.

**Interactions:**

- **Paging (R-PAG-*):** unchanged. The join is on the joined entity's full id, so at most one row per outer row
  (R-SRC-25). Keyset cursors over a nested column already render `entity(buyer).attr` (R-PAG-19, D-196).
- **Grouped queries:** the presence key joins the group keys, as for an association `@Join` (`MQ1409`). The count
  walk stops at a `LEFT` entity join (D-199); a column-anchored join counts through `countQuery` (D-197).
- **`joinFilter`:** a filtered `LEFT` entity join returns `NULL` ids on a miss, so the nested field is empty.
  `MQ1912` already refuses a descendant join or an `INNER` join below a filtered one.
- **`@Child`:** a child model with entity joins is already `MQ3039`; the check also walks the joins of its nested
  models (today it reads only `child.entityJoins()`; no live bug, since `MQ3044` refuses the case today).
- **Writes:** `@Join` fields are already left out of `generateInserts` and `generateChanges`; bulk writes over an
  entity join stay `MQ1604` / `MQ1801` / `MQ1809`.
- **Spring:** repositories take a built `ModelQuery`; no change.

### Part B — nested models with their own `@JoinEntity` / `@JoinSource`

```java
@QueryModel(root = CustomerEntity.class)
@JoinSource(name = "totals", source = CustomerTotals.class,
        on = @JoinSource.On(column = "id", key = "customerId"))
@JoinEntity(name = "region", entity = RegionEntity.class,
        on = @JoinEntity.On(column = "regionId"))
public record Buyer(
        @PrimaryKey Long id,
        Long regionId,
        @SourceColumn(join = "totals", field = "spent") BigDecimal spent,
        @Column(entityJoin = "region", attribute = "name") String regionName) {}

@QueryModel(root = OrderEntity.class)
public record OrderView(@PrimaryKey Long id,
        @Join Optional<Buyer> customer,
        @Join Optional<Buyer> referrer) {}

// generated: one SourceQuery per nested source join, depth-first, prefixed
QOrderView.query(SourceQuery<CustomerTotals> customerTotals, SourceQuery<CustomerTotals> referrerTotals)
```

**What blocks it today, and what changes:**

1. **Registration.** A nested model's generated factory never runs, so its joins are not registered. The outer
   model's generated code declares them, one `EntityJoin<Outer, E>` / source join per nested occurrence, with their
   anchors re-rooted onto the `@Join`'s path. They are fresh generated constants (INV-9): an `EntityJoin<Nested, E>`
   can't be reused.
2. **Names.** Two `@Join` fields of the same nested type would declare the same join name twice (`MQ1911`). The
   outer registers each under the `@Join` field's prefix (`customer_region`, `referrer_region`) and keeps a map from
   the nested model's names to the outer names.
3. **Reading the row (the hazard).** `JoinKey.reroot` replaces every parentless key with the scope, and that includes
   joined-entity and joined-source keys. Inside `row.scoped(CUSTOMER_TABLE)`, the nested mapper's
   `REGION_NAME` would resolve to the nested root's own `name`: a silently wrong value (INV-5), which `MQ3044`
   prevents today. `Row.scoped` must stop at a joined root and map it through the name map in point 2. This is
   the core change Part B rests on, and its first spike item.
4. **Factory.** The outer `query(...)` takes one `SourceQuery<S>` per nested source join after its own, depth-first,
   with parameter names from the prefixes. A missing or mistyped source is still a compile error (P-2). Two
   parameters of one `SourceQuery<S>` type can be swapped silently, as two joins of one source can today (D-189).
   Adding a source to a nested model changes every outer `query(...)` signature: source-incompatible, but loud.
5. **Anchors.** A nested entity join anchored on the nested root's columns re-roots to a to-one path from the outer
   root, which R-SRC-25 already allows. A nested source join anchored on a to-one path already resolves first in
   `ModelQuery`. A nested model reached through `@Join(entityJoin)` (Part A) that declares an entity join is a chain:
   Part C.

**Rules:** D-189's "a nested `@Join` model that joins a source" and R-PROC-44's entity-join refusal are lifted for
`@Join` over an association. `MQ3044` keeps the processor-side clashes (two prefixes producing one constant or join
name). `@Child` keeps `MQ3039`: a fetch plan has no source to pass a child.

### Part C — an entity join anchored on another entity join

Combining Part A and Part B means a nested model read through an entity join that declares its own entity join,
anchored on the first join's columns. R-SRC-25 refuses that (`MQ1911`, `EntityJoin.checkColumnAnchor`). RFC 0022
Resolution 6 recorded this as a scope choice, "additive under `On#column` once creation order across entity joins is
specified", not a rendering limit. RFC 0021's spike A.2 found that creation order is rendering order on every vendor
and both Hibernate lines, but no spike tested a chain.

Part C relaxes R-SRC-25 for column anchors over an earlier entity join: the anchored join must be created after the
join it reads from, a `LEFT` anchor join makes every join anchored on it `LEFT` (or refuses `INNER`, as Part A
rule 4), and a source-anchored chain stays refused. If the spike shows a vendor or Hibernate line that renders a chain
wrongly, Part C is dropped and the combination stays `MQ3044` / `MQ1911`.

## Spike plan

On H2, PostgreSQL and MySQL, Hibernate 6.6 and 7.4; results in `rfc/0024-spike-results.md`.

1. **Part A presence.** `presentBy` on a joined-entity root, the name kept: a LEFT miss is empty, a match with every
   nested column `NULL` but the key is present, a caller `ColumnSet` without the key still selects it.
2. **Part B re-rooting.** `Row.scoped` with joined roots under two `@Join` fields of the same nested type: each
   field reads its own row; a deliberately broken name map fails loudly, never reads the root's column.
3. **Part B sources.** Two source joins of one `SourceQuery` type under two prefixes, keyset paging over the outer
   model, and a grouped count through them (D-192).
4. **Part C chains.** Two and three entity joins chained by column anchors, LEFT and INNER, rendered SQL and rows on
   each vendor and line; with a `joinFilter` on the first.

## New ids

| Id | Where | What |
|---|---|---|
| R-PROC-48 | `processor/30` | `@Join(entityJoin)` rules |
| R-GEN-43 | `processor/31` | Nested joins registered under prefixes; the outer factory's nested `SourceQuery` parameters |
| `MQ3047` | `processor/32` | An invalid `@Join(entityJoin)` |
| AC-PROC-51 onward | `processor/30` | One per rule |
| AC-GEN-36 onward | `processor/31` | Presence, prefixes, factory signature |
| D-213 onward | `reference/92` | One per part, on acceptance |

## Compatibility

`@Join`, `@JoinEntity`, `@JoinSource`, `EntityJoin` and `TableField#presentBy` are `@Incubating`. Part A only adds.
Part B changes the generated `query(...)` of an outer model whose nested model joins a source: today that model does
not compile (`MQ3044`), so no released code breaks. Later, adding a source to a nested model changes the signature
of every outer factory; that is source-incompatible and loud, never silent.

## Alternatives

- **Do nothing.** Users map a read-only `@ManyToOne`, use `@Child(key = ...)` or flat `@JoinEntity` columns. Each
  is a workaround with a cost (see Motivation).
- **`@Join(on = ...)` with a free condition.** Considered in RFC 0021; an arbitrary `ON` loses the "at most one row
  per outer row" guarantee that paging relies on. An entity join on the full id keeps it.
- **Part B with a "join names unique across the model tree" rule instead of prefixes.** Cheaper, but refuses the
  common case of one view nested twice (billing and shipping customer).
- **Part B by running the nested factory.** The nested factory builds a `ModelQuery<Nested>`, not joins on another
  query; it would need a second, join-only factory per model, which is more generated surface than the name map.

## Unresolved questions

1. Whether Part C ships at all, decided by its spike.
2. Whether Part B's prefixed names (`customer_region`) are visible in `FilterSpec` paths and sort names, or only the
   `@Join` field path (`customer.region.name`) is. Recommendation: the field path only.

## Milestone sketch

Targets **0.13.0**: one milestone, M36, with the freeze moving to M37. Part A can ship alone if the spike drops Part B.

| Slice | Content | Tier |
|---|---|---|
| .1 | `TableField#presentBy` on a joined-entity root (name kept), `propertyPath` for named joined roots | `opus` / `high` (public API, INV-5) |
| .2 | `@Join(entityJoin)`: annotation, R-PROC-48 / `MQ3047`, generated code, `MQ3039` over nested joins, docs | `sonnet` / `medium` |
| .3 | Part B: `Row.scoped` stopping at joined roots with the name map, prefixed registration | `opus` / `high` (INV-5) |
| .4 | Part B: the outer factory's nested `SourceQuery` parameters, `MQ3044` narrowed, TCK and docs | `sonnet` / `medium` |
| .5 | Part C, if the spike keeps it: chained column anchors in R-SRC-25 | `opus` / `high` |
