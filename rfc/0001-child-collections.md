# RFC 0001 — Child collections

- **Status:** draft
- **Affects:** `INV-2`, `INV-4` (wording), `INV-9` (adds `ChildQuery`); SPEC.md §1, §7; `api/11` §5, §6;
  `engine/20` R-EXE-02/05/07; `engine/21` §4; `processor/30`, `processor/31` §4, `processor/32`; `vendor/41` notes;
  `integration/50` properties; `reference/90` (new codes); `reference/92` (new D-14). Adds `api/14` with `R-CHD-*` /
  `AC-CHD-*`.
- **Discussion:** TBD
- **Target:** 0.2, `@Incubating`. Nothing is reserved in 0.1: the change is purely additive.

## Summary

A model can carry `List<Child>` fields filled by **separate, batched queries, one level at a time**. After a batch of
parent rows is read, the engine collects their link keys from the `Row`s and runs the child `ModelQuery` with
`childKey IN (…)` in vendor-sized chunks. It then groups the children by key and hands each parent its list. One
definition serves `list`, `page` and `export`. The children are never joined onto the parent query, so paging,
`count` and export correctness are untouched.

## Motivation

0.1 models can nest only to-one associations (`Optional<NestedModel>`, D-4). The only sanctioned route to a
one-to-many is the caller's own batching in `pageTransformer` (R-PAG-09); `afterMap` is forbidden to query
(R-QRY-06). So every use case hand-writes the same loop. The earlier in-house version of this pattern did it like
this:

```java
Map<Integer, Ticket> byId = tickets.stream().collect(toMap(Ticket::getId, t -> t));
historyColumns.include(TicketHistory.TICKET_ID);                 // mutates a possibly shared column set
splitAndConsume(byId.keySet(), maxKeys, true, ids ->
        historyService.findByTicketIds(ids, historyColumns)       // WHERE ticket_id IN (:ids), own ORDER BY
                .forEach(h -> byId.get(h.getTicketId()).addHistory(h)));
```

That loop was repeated once per child type and per level, about 165 call sites in that codebase. It produced the
failures this library exists to prevent:

| Hand-written loop | Failure |
|---|---|
| `toMap` without a merge function | Throws when a to-many join on the parent query duplicates a parent key. |
| Link column added by mutating the caller's column set | A shared or static column set is changed for every later request. |
| `map.get(child.parentKey()).add(…)` | NPE when the link column wasn't selected; nothing ties the key types together. |
| Chunk size from an app property, applied by convention | A call site that forgets to chunk sends an unbounded `IN` list. |
| Children pre-initialised to an empty list | "Not requested" and "has none" are indistinguishable. |
| No cap on children per page | One parent with a huge child set breaks the export's memory bound. |

This RFC keeps the shape that did work: **a separate child query per level, keyed by `IN` on the parent's link key,
chunked, run once per page**. That is never per-row N+1, and never a join that multiplies parent rows.

## Design

### 1. `ChildQuery`: a definition, like `TableField`

```java
@Incubating
public final class ChildQuery<P, K, C> {            // immutable, may be static final (INV-9)

    /** Class parents: attach through a mutator. */
    public static <P, K, CE, C> ChildQuery<P, K, C> of(
            ColumnField<P, ?, K> parentKey,             // a parent column, read from the parent Row
            ColumnField<C, CE, K> childKey,             // a column on the child query's root entity CE
            Supplier<ModelQuery<CE, ?, C>> child,       // resolved when the parent query is built, not at class init
            BiConsumer<P, List<C>> attach);

    /** Records and immutable classes: return a copy carrying the list. */
    public static <P, K, CE, C> ChildQuery<P, K, C> ofCopy(
            ColumnField<P, ?, K> parentKey, ColumnField<C, CE, K> childKey,
            Supplier<ModelQuery<CE, ?, C>> child, BiFunction<P, List<C>, P> attach);

    public <CE> ChildQuery<P, K, C> withQuery(ModelQuery<CE, ?, C> child);   // copy; the root is checked (MQ1503)
    public ChildQuery<P, K, C> columns(ColumnSet<C> columns);              // copy with the child's columns replaced
}
```

On the parent query:

```java
ModelQuery<OrderEntity, Long, OrderView> q = QOrderView.query()
        .columns(QOrderView.DEFAULT)
        .children(QOrderView.ITEMS.columns(QOrderItemView.DEFAULT))   // opt-in per query
        .children(QOrderView.NOTES)
        .build();
```

The design choices behind these signatures:

- **Keys have one type.** Both link columns share `K`, so a key-type mismatch does not compile (P-2). `K` is the
  column's value type. `Row.get` returns it, and the same value is bound into `IN`.
- **The child key belongs to the child query.** `childKey` names the child query's root entity `CE`, so a link column
  from another root does not compile.
- **The child query is resolved lazily.** It is a `Supplier`, so generated `Q` classes never build each other's
  queries inside `<clinit>`. That rules out the static-initialisation cycle and deadlock between a parent and a child
  that `@Join`s back to it. The supplier is called once, by the parent's `build()`, and the built parent `ModelQuery`
  holds the resolved child.

**R-CHD-01** A `ChildQuery` is loaded only when passed to `children(...)`. A query without it behaves exactly as in
0.1 and issues the same SQL.

**R-CHD-02** The engine selects `parentKey` and `childKey` for that execution when they are missing, as R-QRY-04 does
for primary keys. It never changes the caller's `ColumnSet`.

**R-CHD-03** Build-time checks on each link:

- Both link columns must reach their own query's root through to-one steps only (`MQ1503`). A to-many step would
  duplicate the link.
- Neither may carry a converter, and `K` may not be an array type (`MQ1504`). `byte[]` has identity equality, so no
  row would ever group.
- The child query must have a `primaryKey`, or be grouped with `childKey` among its group keys (`MQ1505`). Either way,
  order within a parent is repeatable (R-CHD-06).

**R-CHD-04** `children(...)` on a grouped *parent* is refused (`MQ1502`). A grouped *child* query is allowed when
`childKey` is a group key: that is the "totals per parent" case.

**R-CHD-05** A supplier that re-enters the `ChildQuery` it is resolving throws `MQ1501`, naming the path. An example is
a self-referencing `Category.CHILDREN` whose query includes `CHILDREN` again. A tree of fixed depth is written as
distinct `ChildQuery` constants. Repeated model *types* are allowed.

**R-CHD-06** A child query's own `keyset()` and `primaryKeyFirst(...)` are ignored. Its `QueryCustomizer` runs with
`Phase.MODEL`, and no new `Phase` constant is added.

### 2. Execution: breadth-first, one batch

A batch is a `list` result, a page's content, or an export page. Each batch runs as follows:

```
level 0 = the batch's (Row, model) pairs; keys come from Row.get(parentKey), never from the model (R-COL-11)
for each level L with requested children, top-down:
    keys = distinct non-null link keys of level L, in first-seen order; empty → no statement
    for chunk in partition(keys, budget):
        rows = child query with (child.where AND childKey IN chunk), ORDER BY child.orderBy + its key,
               maxResults = remainingCap + 1
        keep (Row, model) pairs as level L+1, grouped by Row.get(childKey)
attach bottom-up: each level's lists are complete, including copies of records, before they reach their parents
```

**R-CHD-07** **Chunks are separate statements.** The budget per chunk is
`min(maxInListSize, maxBindParameters − binds already in child.where)`, and the chunk size is the largest power of two
not above it. A provider that pads IN lists pads to the next power of two, so a full chunk gains no binds and the last,
shorter chunk pads to at most the chunk size: the statement stays within budget whether or not padding is on, with no
`VendorProfile` method and no provider lookup. The cost is at most twice the statements of an unpadded split. A budget
below 1 throws `MQ2404` naming the child model: the child query's own filter has used up the statement. There is no
application-level chunk-size setting.

**R-CHD-08** **Order within a parent follows the child query's `orderBy`, then its key.** The key is its primary key,
or its group keys (R-PAG-11). A chunk partitions the distinct keys and each child row has one key value, so every
child of one parent comes from the same statement.

**R-CHD-09** **The result is defined.** A parent's list is exactly what `list(child where AND childKey = key)` would
return, in that order. If the child query's `where` joins a to-many path, child rows repeat, exactly as they do in
`list`. The engine logs a warning at build when the child root has a to-many join, pointing to `Filters.exists`
(R-FLT-12). It does not deduplicate silently.

**R-CHD-10** **Every requested parent is attached exactly once**, with an unmodifiable list. The list is `List.of()`
when the parent has no children or a NULL key. Parents that share a key receive the same list instance.

**R-CHD-11** A child row whose `childKey` equals no requested key in Java throws `MQ2402`, naming both models. This
happens when the database matched what `equals` does not: case-insensitive or pad-space collations (MySQL `_ci`, H2
`IGNORECASE`), `CHAR` padding, `BigDecimal` scale. The message says so. `vendor/41` documents the collation cases
per vendor.

**R-CHD-12** Statements per batch: `Σ_levels ⌈distinct keys at that level / budget⌉`, in addition to the parent
query and any `COUNT`. The count does not depend on how many rows each parent has.

**R-CHD-13** **Memory stays bounded.** One batch holds at most `maxChildRowsPerBatch` child rows across all levels.
The default is 100 000, set through `ModelQueryConfig` or `modelquery.children.max-rows-per-batch`, and
`ExportOptions` may override it. Each chunk statement runs with `maxResults = remaining + 1`. If that extra row comes
back, the engine throws `MQ2401` before reading further, naming the parent model, the child model and, for an export,
the page. It never truncates. Per-parent limits ("latest 5 notes") need window functions and stay `Future`.

**R-CHD-14** Derived values that depend on children go in `attach`, not `afterMap`. `attach` runs only when the
children were loaded, and R-QRY-05 is unchanged: `afterMap` runs straight after the `RowMapper` and never sees child
lists. D-11 already makes `afterMap` row-local.

```java
ChildQuery.of(QOrderView.ID, QOrderItemView.ORDER_ID, QOrderItemView::query,
        (o, items) -> { o.setItems(items); o.setItemCount(items.size()); });
```

**R-CHD-15** Children are loaded only for `children(...)` passed to the query being run, and for the child queries
nested inside them. A `@Join` nested model is mapped from the parent row, so any `children` on it are not loaded.

### 3. Each executor method

| Method | Behaviour |
|---|---|
| `list` | One batch over the whole result. `Limit.of(0)` runs nothing (R-EXE-06). |
| `page` `COUNT` / `NO_COUNT` | Children load for the returned content only: never for the `pageSize + 1` look-ahead row, and never for the count. |
| `page` `ONLY_COUNT` | No child statement. |
| `count` | Ignores `children(...)` (R-EXE-05). |
| `export` | One batch per page, **after** the page-boundary dedupe and **before** `pageTransformer`. Keyset and primary-key-first paging are unaffected: children see the page's final rows. |
| `stream` | Refused with `MQ2403`, **before** a connection is used. Loading children means running a second statement while the stream's result set is open, which fails or blocks on vendors that stream row by row (`vendor/41`, Q-2). A second connection would leave the transaction. Use `export`. |

**R-CHD-16** Parent and child statements run on the same `EntityManager`, and the engine opens no transaction
(INV-1). Parent and child rows are mutually consistent only under snapshot or repeatable-read isolation. MySQL's
default gives that inside a transaction. PostgreSQL and H2 at `READ COMMITTED` take a new snapshot per statement, so
a read-only transaction there is not enough. The Javadoc and the user guide state this.

### 4. Generated models

```java
@QueryModel(root = OrderEntity.class)
public class OrderView {
    @PrimaryKey @Column Long id;
    @Children(attribute = "items") List<OrderItemView> items;   // no initialiser: "not requested" stays null
}
```

**R-CHD-17** `@Children(attribute)` names a collection association on `root` whose inverse is a `@ManyToOne` on the
child entity (`@OneToMany(mappedBy = …)`). The processor generates:

- `QOrderView.ITEMS`, a `ChildQuery`:
  - `parentKey` is a hidden column on the root's `@Id`, not the model's `@PrimaryKey`, which may be a business key.
  - `childKey` is a hidden link column on the inverse path (`order.id`) in `QOrderItemView`.
  - The child query is `QOrderItemView::query` with `columns(QOrderItemView.DEFAULT)`, because `query()` alone has no
    columns (R-QRY-02).
- The attach function: a setter for a class, a copy for a record.

`Q<Model>.query()` never includes children. The generated mapper never assigns a `@Children` field, so an
unrequested one keeps its initialiser, and a record component stays `null` (R-GEN-06). Since R-CHD-10 always
attaches a list, `null` means "not requested" and an empty list means "none".

The path `order.id` resolves without a join under Hibernate 6. Other JPA providers may join, which gives the same
rows with different SQL. SQL snapshots are pinned for Hibernate.

**R-CHD-18** Processor diagnostics (numbers assigned in `processor/32`). The processor reports an error when:

- the field is not `List<X>`;
- `X` is not a `@QueryModel` whose `root` is the association's target;
- the association has no `mappedBy` inverse;
- the inverse sits inside an embedded id (`@MapsId`), or uses `@JoinColumn(referencedColumnName)` to a non-id column.

Every other link, such as a business key or a unidirectional FK column, is written by hand with `ChildQuery.of`.

### 5. Changed and new ids

**Invariants and existing rules**

- **INV-2:** "…a plain model built from a result row **and, for declared child collections, from one batched query
  per level and chunk**. There is no lazy proxy, no entity graph and no per-row query."
- **INV-4:** "…memory bounded by one page **and its child rows, capped by `maxChildRowsPerBatch`**."
- **INV-9:** the list of immutable types gains `ChildQuery`.
- **SPEC §7:** engine-added columns now also include link columns (R-CHD-02).
- **R-EXE-07:** `stream` refuses a query with children.
- **`engine/21` §4:** the child step goes between the dedupe and `pageTransformer`.
- **New spec file:** `api/14-child-collections.md` owns `R-CHD-01..18`, and area `CHD` is added to SPEC.md §1.
- **New decision D-14:** "Child collections load in separate, breadth-first, batched `IN` queries per level, never
  joins. Link keys come from the `Row`. Children are capped per batch through `maxResults`. Derived values go in
  `attach`, not `afterMap`." It traces to INV-2, INV-4, INV-5 and D-11.

**New codes.** They are added to `reference/90` before the code that raises them (R-ERR-02).

| Code | Meaning |
|---|---|
| `MQ1501` | A `ChildQuery` supplier re-enters the `ChildQuery` being resolved |
| `MQ1502` | `children(...)` on a grouped parent query |
| `MQ1503` | A link column does not reach its query's root through to-one steps, or `withQuery` got a different root |
| `MQ1504` | A link column has a converter, or an array key type |
| `MQ1505` | A child query has no `primaryKey` and is not grouped by its `childKey` |
| `MQ2401` | Child rows in one batch exceed `maxChildRowsPerBatch` |
| `MQ2402` | A child row's link key equals no requested parent key in Java |
| `MQ2403` | `stream` called on a query with `children(...)` |
| `MQ2404` | The child query's own binds leave no room for link keys in one statement |

**Acceptance criteria.** These are `AC-CHD-*`, run on every Tier-1 vendor through the TCK.

1. Children of 1 200 parents with the IN limit forced to 500 equal the unchunked result, including order within each
   parent (R-CHD-07, R-CHD-08).
2. A parent with no children and a parent with a NULL key each get an empty unmodifiable list. An unrequested class
   field keeps its initialiser, and an unrequested record component is `null` (R-CHD-10, R-CHD-17).
3. Parents with duplicated keys each receive the list, without an exception (R-CHD-10).
4. The caller's `ColumnSet` constants are unchanged after a query that needed a link column added (R-CHD-02).
5. An export of 20 000 parents over 3 levels visits every parent once, with each child under the right parent. The
   statement count equals R-CHD-12's formula per page (R-CHD-12, INV-4).
6. `NO_COUNT` issues no child statement for the look-ahead row. `ONLY_COUNT` and `count` issue none at all. An empty
   key set issues none.
7. With a cap of 1 000 and one parent holding 1 000 000 children, `MQ2401` is thrown and the chunk statement fetched
   at most 1 001 rows (R-CHD-13).
8. `QOrderView` and `QOrderItemView`, with a `@Join` back to the parent, initialise in either order and from two
   threads at once (lazy supplier).
9. A key that matches only under a case-insensitive collation throws `MQ2402` on MySQL (R-CHD-11).
10. Every code in the table above is raised with that exact code.
11. With Hibernate's `in_clause_parameter_padding` on and a bind limit forced low, every chunk statement stays within
    `maxBindParameters()`, including the last, partial chunk (R-CHD-07).

## Compatibility

The change is additive: a new type, a new builder method, a new annotation and new codes. Queries without
`children(...)` are byte-for-byte unchanged, and so are their SQL snapshots. No existing rule changes meaning, and
R-QRY-05 is untouched. Everything is `@Incubating`.

`ChildQuery`'s class type parameters `<P, K, C>` do not assume a single-column key, so composite links can arrive
later as an `ofComposite(...)` factory without a breaking change.

## Alternatives

- **Fetch-join the collection and fold rows in the mapper.** This is one statement, but it multiplies parent rows.
  Offset and limit would then page over child rows, `count` would need `distinct` (R-EXE-04), and the export dedupe
  would have to fold rows across page boundaries. That breaks exactly what `R-PAG-*` protects. Rejected.
- **A per-row lookup in `afterMap`.** That is N+1, and R-QRY-06 already forbids it. Rejected.
- **Running children before `afterMap`, so `afterMap` sees them.** A derived field would then silently read an empty
  list whenever the child wasn't requested (INV-5), and the export would have to keep `Row`s until the child batch
  finished. `attach` covers the need (R-CHD-14). Rejected.
- **Entity graphs or `@BatchSize`.** These return managed entities (INV-1, INV-2), and `@BatchSize` is Hibernate-only
  (INV-7). Rejected.
- **Vendor JSON aggregation (`json_agg`, `JSON_ARRAYAGG`) in a correlated subquery.** This is one statement, but it
  needs vendor-specific rendering, and child columns would be decoded from JSON outside the typed `Row`. It could come
  later as a `VendorProfile` optimisation of the same `ChildQuery` definition.
- **Only a public helper for `pageTransformer`.** This is the smallest surface, but it covers only `export`, leaves
  `list` and `page` to hand-written code, and keeps key selection and attachment as the caller's job. It remains the
  engine's internal building block.
- **Do nothing.** Every adopter re-implements the loop above, with its failure modes.

## Resolved during review

| Question | Resolution |
|---|---|
| Grouped parents | Refused (`MQ1502`); allowing them later is additive. Grouped *children* are allowed (R-CHD-04). |
| Composite link keys | Deferred. Later: an OR of ANDs chunked by `maxBindParameters / columns`, as R-PAG-07 does, with no new `VendorProfile` method (P-4). |
| `@ManyToMany`, unidirectional `@OneToMany` | Follow-up RFC. A child may belong to several parents, and re-rooting filters (R-FLT-11) needs its own review. |
| "Not requested" vs "empty" | `List<C>`, not `Optional<List<C>>`. `null` (no initialiser) means not requested; the engine's `List.of()` means none (R-CHD-17). |
| Cap default and location | 100 000 on `ModelQueryConfig`, a Boot property, and an `ExportOptions` override (R-CHD-13). |
| Padding-aware bind count | Neither `VendorProfile` nor `model-query-hibernate`: chunks are sized to a power of two, which padding cannot grow past the budget (R-CHD-07). |

## Unresolved questions

Both are spec gaps outside this RFC, raised as separate issues. Neither blocks it: R-CHD-07 splits by statement, and
R-CHD-03 refuses converters on link columns.

1. **R-FLT-09 and the bind limit** ([#6](https://github.com/rey5137/model-query/issues/6)). R-FLT-09 splits a long
   `IN` into `IN … OR IN …` within one statement, which does not reduce the bind count, so AC-PRF-03 cannot hold for a
   user filter. Proposed: a user statement over `maxBindParameters()` is refused before execution; only library-built
   key lists (`MODEL_BY_KEYS`, child chunks) are split across statements.
2. **Converter typing** ([#7](https://github.com/rey5137/model-query/issues/7)). R-PROC-07 and AC-PROC-04 convert in
   the filter direction, while `processor/31` §1 types columns by the entity value and converts only in the mapper.
   Proposed: the spec follows `processor/31`, and converted filters move to `Future`.
