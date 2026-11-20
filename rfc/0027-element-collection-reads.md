# RFC 0027 — Element collections and maps: value children and filters

- **Status:** accepted as D-245 to D-250 (2026-11-20), after its architect review and its acceptance spike (Spike results)
- **Affects:**
  - **`api/15`:** R-FCH-03 (a `List` child needs a `@PrimaryKey` *unless it is a value child*), R-FCH-04 (dedupe
    and the closing primary-key order do not apply to value children), R-FCH-14 (`through` may end at an element
    collection), R-FCH-18 and D-209 (`verifyMissing` refused for every value child); new R-FCH-19 (value children),
    R-FCH-20 (map children), R-FCH-21 (element models); new AC-FCH-26 onwards.
  - **`api/10`**, **`api/12`**, **`engine/20`:** a filter path may end at an element collection (R-PROC-13's join
    constant). R-EXE-16's "When" list states that the element-collection join is rendered as a semi-join where it
    applies.
  - **`processor/30`:** R-PROC-13 and D-46 (an element collection gets a table constant when a filter column goes
    through it); R-PROC-39 / `MQ3038` (an `@ElementModel` is its own kind, not a `@QueryModel` root); `MQ3405`
    (does not apply to value children); R-GEN-18 (an element model has no `query()`).
  - **`core`:** new `@Incubating` `ValueChildField<M, E>`, `MapChildField<M, K, V>`, `ValueChildQuery` and
    `@ElementModel`, plus `FetchPlan#values(...)` and `FetchPlan#map(...)`. A `ColumnField` for a basic element (a join
    node with no attribute).
  - **`reference/90`:** `MQ3401`–`MQ3406` family line; new compile-time `MQ3407` (value-child and element-model
    definitions) and `MQ3408` (map key/value types); run-time `MQ1713` (a to-many join in a value-child
    statement, at the plan build) and `MQ2609` (two map entries for one key). The numbers are placeholders until
    acceptance.
  - **`reference/92`:** D-245 to D-250; D-46 and D-209 amended.
  - **`docs/plan/mvp-plan.md`:** this is M39; the freeze moves to M40 (D-250).
  - **`delivery/61`**, japicmp: the new public types.
  - **`docs/site`:** the "Child collections" page gains "Values and maps", with JPA and Spring tabs.
- **Discussion:** TBD

## Summary

`@ElementCollection` is the standard JPA mapping for tags, roles, phone numbers, translations
(`Map<Locale, String>`) and lists of embeddables (`List<Address>`). Today a model can't read one and a filter can't
reach one: the processor refuses every path through it (`MQ3011` for filter columns, `MQ3044` for joins), D-46 gives
it no table constant, and the TCK fixture `OrderEntity.tags` says "never queried". This RFC lets a model read an
element collection as a **value child** loaded through `@Child(through = …)`. The load works like the existing
`through` children (R-FCH-14): one statement per round of parent keys, no entity loaded, nothing added to the parent's
statement, so INV-4 paging is untouched. A `Map` attribute loads into a `Map<K, V>` field the same way. Filter columns
and `exists` can reach an element collection's value.

## Motivation

A projection of an entity that holds an element collection must leave that data out today, or the caller must run
and stitch a second query by hand. That second query is exactly what R-FCH-05 already automates for entity children:
batched `IN` rounds, grouping by key and order per parent. Of the standard JPA read features the library does not
cover (stored procedures, named queries, query hints, element collections), element collections are the one most
domain models contain, and they fit INV-1/INV-2 with no exception.

```java
@Entity class OrderEntity {
    @Id Long id;
    @ElementCollection @CollectionTable(name = "order_tags") @Column(name = "tag") List<String> tags;
    @ElementCollection @OrderColumn(name = "pos") List<AddressValue> stops;      // @Embeddable
    @ElementCollection @MapKeyColumn(name = "locale") Map<String, String> notes;
}
```

## Design

### Part A: value children

```java
@QueryModel(root = OrderEntity.class)
record OrderView(@PrimaryKey Long id, String number,
                 @Child(through = "tags") List<String> tags,
                 @Child(through = "stops") List<StopRow> stops,
                 @Child(through = "notes") Map<String, String> notes) {}

@ElementModel(AddressValue.class)                 // an @Embeddable
record StopRow(String city, @Column("geo.lat") BigDecimal lat) {}

OrderView.query().fetch(FetchPlan.of(QOrderView.class)
        .values(QOrderView.TAGS)                                   // ValueChildField<OrderView, String>
        .child(QOrderView.STOPS, FetchPlan.none(), c -> c.where(f -> f.eq(QStopRow.CITY, "Hanoi")))
        .map(QOrderView.NOTES));                                   // MapChildField<OrderView, String, String>
```

**Path.** `through` ends at an `@ElementCollection` attribute, either directly on the parent's root or after
to-one associations only. The following are refused with `MQ3406`:
- a path that crosses a collection before it (`lines.tags` would merge every line's tags; put a child on the line model instead);
- a path through an embedded value (as R-FCH-14 already says);
- a path that continues past the collection (`stops.city`);
- a parent with a composite `@Id`.

`foreignKey` stays `MQ3401`. The parent's `key` is its root's single `@Id`, read on the root (R-FCH-14).

**Field types.** The generated field follows the element type:

| Element | Field | Generated | Plan entry |
|---|---|---|---|
| basic (a converted type included) | `List<E>` or `Set<E>` | `ValueChildField<M, E>` | `values(field)` or `values(field, q -> q.maxPerParent(n))` |
| `@Embeddable` | `List<C>`, `C` an `@ElementModel` | `ChildField<M, C>` | `child(field, plan, query)`, as today |
| map with a basic key and a basic or embeddable value | `Map<K, V>` | `MapChildField<M, K, V>` | `map(field)` (Part B) |

`ValueChildQuery` carries only `maxPerParent`. A basic value has no column for a `where`, `orderBy` or key group to read,
so none of them compiles (P-2). `Optional<E>` and other field types are `MQ3407`.

**Element models.** `@ElementModel(X.class)` names an `@Embeddable`. The processor generates a `Q*` class with
columns over an engine-internal element table. It has no public `ROOT`, no `query()`, and no `@PrimaryKey`, `@Child`
or `@Join`, so misuse cannot be expressed in the first place. The columns keep `@Column` paths into nested embeddeds,
converters and INV-3 type checks. An element model is valid only as the type of a value child. A `@QueryModel` rooted at
an embeddable stays `MQ3038`. An element model takes no `QueryCustomizer`.

**The statement**, per round, is R-FCH-14's: rooted at the parent's root entity, joined INNER along `through`:

```sql
select o.id, t.tag from orders o join order_tags t on t.order_id = o.id
where o.id in (?, ?, ...) order by o.id, t.pos
```

**Bags.** An element collection is a bag: two equal elements are two rows, and a `List` field keeps both.
- Nothing is deduplicated, because there is no primary key to deduplicate on.
- That is safe only while the statement joins nothing to-many besides the collection. JPA forbids collections inside an
  embeddable that is used as a collection element, and the `through` rule above excludes a to-many before it, so only an
  `exists` or a sub-select can reach another collection, and those join nothing.
- A to-many join in a value-child statement is `MQ1713` at the plan build, before the parent's statement (D-138), as a
  backstop.
- `NULL` elements are kept, never skipped; the list allows `null`.
- An embeddable element whose columns are all `NULL` maps to a model with null fields.
- A `Set<E>` field collects into a `LinkedHashSet` in child order; `maxPerParent` counts rows before equal elements
  collapse.

**Order.**
- Rows come in the `@OrderColumn` order (`ListJoin.index()`) if there is one, then the child query's order, then the
  value itself for a basic element that is not a LOB. Otherwise the order is unspecified.
- `index()` needs a `ListJoin`, so the engine joins an `@OrderColumn` attribute with `joinList`.
- `NULL` elements sort where the vendor puts them: first on H2 and MySQL, last on PostgreSQL. The engine adds no null
  ordering of its own, so this follows the vendor's default.
- The TCK compares unordered children as multisets. Grouping is done in Java and going over the bound throws, so an
  unstable order never loses a row.
- Gaps in an `@OrderColumn` are not reproduced as `null`s the way the provider's entity loading does; the guide says so.
- `@OrderBy` on the mapping is not read, because the provider applies it only to entities it loads.

**Bounds, rounds, key groups.** R-FCH-05/-11 apply unchanged: `maxPerParent` bounds each round at `keys × n + 1`
rows, and `byKeys` runs the plan per chunk. `ChildQuery#keyGroups` with `verifyMissing` is `MQ1708` for every value
child (D-209 extended), because no key is unique per row. A basic value child has no `keyGroups`.

**Select audit (RFC 0026).** An element model's rows go through the hand-off as any child's do. Basic values have no
getter, so they are not counted as selected reads. The audit report says so.

### Part B: map children

A `through` path ending at an `@ElementCollection Map<K, V>` loads into a `Map<K, V>` field. The field is a
`LinkedHashMap` in row order, which is unspecified: the statement has no order by key, because EclipseLink renders a
key order over an embeddable-valued map as an invalid path (Spike results 3). A map field promises no order.
- `K` is basic, with `@MapKeyColumn`, `@MapKeyEnumerated` or `@MapKeyTemporal`. An entity or embeddable key is
  `MQ3408`.
- `V` is basic, or an `@ElementModel` for an embeddable.
- The statement selects `MapJoin.key()` and `value()`, never `entry()`. `entry()` fails on both Hibernate lines and loses
  the keys on EclipseLink.
- Converted keys and values (`@Convert(attributeName = "key"/"value")`) come back converted on both Hibernate lines.
  EclipseLink 4.0 and 5.0 refuse such a mapping when the persistence unit starts (`EclipseLink-7355`), so on those
  versions the provider stops it before the library is involved.
- The processor models the key's and the value's converters (`@Convert(attributeName = "key"/"value")`) and the
  `@MapKey*` annotations, so INV-3 checks the field's `K` and `V`. Today it records no element type for an element
  collection.
- Two entries with one Java key for one parent throw `MQ2609`, naming the field, the parent key and the map key. The
  realistic cause is a lossy key converter.
- A `Map<K, SomeEntity>` is an association, not an element collection. It is `MQ3408`, whose message names the
  working form, `@Child(through = "salesByDay") List<SaleRow>`. That form already resolves, because the processor takes
  a map's value type as the target, and a TCK test pins it.

### Part C: filters through an element collection

- A `@FilterColumn` path may end at an element collection: `path = "tags"` (basic) or `"stops.city"` (embeddable).
- It is a generated join with R-PROC-13's constant. `joinType` must be written, as for any collection
  (`MQ3011` otherwise, unchanged).
- R-EXE-16 renders the join as a second-root semi-join where its "When" list applies, and as R-EXE-04's deduplicated
  join where it does not.
- A basic element's value is a `ColumnField` on the join node itself, with no attribute. This is the one new core
  column shape.
- The table constant (`TAGS_TABLE`) is generated only when a `@FilterColumn` goes through the collection (D-46
  amended). It is not generated for every element collection, because constants added to existing `Q*` classes could
  collide with user fields (`MQ3015`). `exists(QOrderView.TAGS_TABLE, t -> t.eq(QOrderView.TAG, "rush"))` and a bare
  `exists(TAGS_TABLE)` both use it.
- `@Join` and `@JoinSource` columns still refuse element-collection paths (`MQ3044` unchanged). A to-many value in
  the parent's own row goes to a child instead.

### What this does not do

- Write element collections. Bulk writes keep `MQ1806`, and `generateInserts` is unchanged.
- Read `size(tags)` as a column. That is a correlated column (RFC 0015) over the collection table, and a follow-up.
- Read an embeddable element's associations, or a map with entity or embeddable keys.

## Compatibility

The change is source and binary compatible: it accepts definitions the processor refuses today and adds
`@Incubating` types. Existing `Q*` classes gain no member unless a model adds a filter column through an element
collection. `MQ3011` and `MQ3044` messages narrow. The TCK fixtures `OrderEntity.tags` and
`JoinSourceModelTest`'s map attributes become queried and gain collection tables.

## Alternatives

- **Do nothing; use `QueryCustomizer` or a second `ModelQuery`.** Neither works. A customizer can't add a to-many value
  to a one-row-per-parent projection, and a second model needs an entity root, which a collection table doesn't have.
- **Read the collection in the parent's statement with `array_agg`/`string_agg`.** Not portable, and it breaks INV-4
  paging. RFC 0020's report SQL already does it per vendor.
- **A separate `@Child` attribute (`collection = "tags"`) instead of `through`.** That is a second way to name the same
  path. `through` already means "the child's rows are reached along this path from the parent's root".
- **`@QueryModel(root = SomeEmbeddable.class)` for element rows.** The generator always emits `ROOT` and `query()`
  (R-GEN-18), and `MQ3405` wants a `@PrimaryKey`. Such a model would compile and then fail at run time, against P-2.
  `@ElementModel` makes the misuse impossible to write.
- **A plain record mapped by component name.** It loses `@Column` paths into nested embeddeds, converters, the INV-3
  checks and the `Q*` field list that the select audit reads.
- **Reuse `ChildField` for basic values.** `ChildField.query()` returns a model builder, which a basic value lacks, and
  `with(M, List<C>)` can't fill a `Map`. Faking a model would leave `where`/`orderBy` typed over nothing.

## Unresolved questions

- How much processor work is the element and map-key converter modelling? `EntityMetamodel` records no element type
  today. The spike checked only the providers, not the processor, and this sizes slice B.

## Spike results

Branch `spike/rfc-0027`, test-only module `spike-rfc0027`, plain JPA Criteria (no engine), data in
[`0027-spike-data/`](0027-spike-data/README.md), details in [`0027-spike-results.md`](0027-spike-results.md). H2
2.5.252, PostgreSQL 16, MySQL 8.0.31; Hibernate 6.6.58 and 7.4.11; EclipseLink 4.0.8 and 5.0.2 (in-process, weaving
off).

1. **Part A: keep, with one change.** Selecting a basic element, a converted element, a whole embeddable and a nested
   embedded path (`geo.lat`) returns the right rows on every vendor and Hibernate line, with duplicates and the `NULL`
   element kept and `index()` order honoured. The change: use `joinList` for an `@OrderColumn` attribute.
2. **Order without `@OrderColumn`: keep, with the null placement stated.** It was stable over three runs. `NULL` comes
   first on H2 and MySQL and last on PostgreSQL.
3. **Part B: keep `key()`/`value()`, drop `entry()`, and add no key order.** `entry()` fails on Hibernate
   (`ArrayIndexOutOfBoundsException`) and returns no keys on EclipseLink. EclipseLink renders an order by key over an
   embeddable-valued map as `t1.place_key.t1.city`.
4. **Part C: keep.** `correlate(root).join("tags")` and R-EXE-16's second-root semi-join each return each parent once
   on every lane, including embeddable paths and map values. A plain join repeats parents and over-counts, as R-EXE-16
   already says.
5. **Map converters: keep.** They convert on Hibernate. EclipseLink refuses the mapping at startup (`EclipseLink-7355`).
6. **Code paths.** `JoinContext.rootedAt` and `ColumnField`'s managed-type lookup already resolve columns on an
   embeddable join (`JoinContext.java:248`, `ColumnField.java:505-517`). The work for `@ElementModel` is in the
   processor (`ModelValidator.java:373-382`, `QueryModelReader.java:367`), not the core. A collection filter step
   already requires `joinType` (`FilterLayout.java:241-246`) and renders R-EXE-16's semi-join
   (`ModelQuery.java:1182-1200`).

## Milestone

M39 in 0.16.0, the freeze moving to M40 (D-250); the acceptance spike is M39.0:

| Slice | Content |
|---|---|
| M39.1 | Basic value children: `ValueChildField`, `FetchPlan#values`, `List`/`Set` fields, `index()` order, bags |
| M39.2 | `@ElementModel` and embeddable value children |
| M39.3 | Maps and the element and map-key converter modelling |
| M39.4 | Filters through an element collection and the table constant; docs and changelog |
