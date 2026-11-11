# RFC 0024 acceptance spike: results

**1 Part A presence: keep with change.** `presentBy` on a joined-entity root that keeps its join name is all Part A
needs from the core: a `LEFT` miss is empty, a match whose columns are all `NULL` but the key is present, and a caller
`ColumnSet` without the key still selects it, on every vendor and line. The change: the RFC's `propertyPath()` should
return the `@Join` field's name, not the join name (see 4, Q2).
**2 Part B re-rooting: keep with change.** Today's `Row.scoped` reproduces the INV-5 hazard the RFC names (a nested
`regionName` reads the customer's own `name`); a scoped row that stops at joined roots and maps them through a checked
name map reads each `@Join` field's own row, and every broken map tried fails loudly. The change: the presence key must
also be selected for a column on a nested joined root (a `ColumnSet` of only such columns loses every nested model).
**3 Part B sources: keep, with one finding to document.** Two joins of one `SourceQuery` type under two prefixes read
their own rows and bind their own parameters, keyset pages over the outer model and over each kind of nested column
match the list, and the grouped count through both is D-192's client-side count. Re-rooted anchors read the foreign key
column, so a dangling key matches its source row.
**4 Part C chains: keep; Part C ships.** Two- and three-join chains render in creation order with each `ON` reading the
previous join, return the native rows and count them right in every `LEFT`/`INNER` mix, on H2, PostgreSQL and MySQL and
on both Hibernate lines. The only change to the core is the one `MQ1911` branch; a reversed registration and an `INNER`
join below a filtered one are already loud (`MQ1003`, `MQ1912`).

Spike on `spike/rfc-0024`: test-only module `spike-rfc0024`, data in [`0024-spike-data/`](0024-spike-data/README.md).
Every cell compares the engine's rows with native SQL on the same connection and records the statements it sent.

**Versions.** PostgreSQL 16.15 (`postgres:16-alpine`), MySQL 8.0.34 as the server reports it (image tag
`mysql:8.0.31`), H2 2.5.252; Hibernate 6.6.58.Final (API 3.1.0) and 7.4.14.Final (API 3.2.0).

**What is real and what is emulated.** The production engine runs every query: `ModelQuery`, `ModelQueryExecutor`,
`model-query-hibernate`'s entity-join and source renderers, presence keys, keyset cursors, the count paths. The
"generated" classes are hand-written (`PartA`, `PartB`, `PartC`): what the processor would emit for the RFC's examples.
Emulated, in the spike module only:

- **Part A** `TableField#presentBy` on a joined-entity root: `SpikeCore.presentBy` builds the node with the join name
  and the presence key through `TableField`'s private constructor. Production still throws `MQ1104` (cell
  `baseline-presentBy-on-entity-join-root`).
- **Part B** `Row.scoped` stopping at joined roots: `SpikeCore.scoped` wraps the production `Row.scoped`. A column on
  the nested model's own root or an association below it reads through the production scope; a column on a nested joined
  root reads through the outer join the field's `NameMap` names, and an unmapped one throws. `SpikeCore.nameMap` is
  built once per `@Join` field, as a generated constant would be, and checks each pair: same kind (entity or source
  join), same target, and every anchor of the outer join at or below the field's join. Nesting two levels deep is not
  emulated.
- **Part B** registration under prefixes: the outer classes declare `customer_region`, `referrer_totals`, ... by hand,
  anchored on the `@Join` path (`QBuyerB.REGION_ID.withTable(OrderView.class, CUSTOMER_TABLE)`), and the factory takes
  `query(SourceQuery<CustomerTotals> customerTotals, SourceQuery<CustomerTotals> referrerTotals)`.
- **Part C** the relaxed `MQ1911`: a shadow of `com.rey.modelquery.core.EntityJoin` on the spike's test classpath, a
  verbatim copy whose `checkColumnAnchor` skips the entity-join branch while `SpikeCore.chains(true)` is set. Setup
  asserts the shadow is the class loaded; cell `baseline-chain-refused` runs with the switch off and gets production's
  `MQ1911`.

**Data.** 12 orders, 6 customers, 4 regions, 3 countries, 9 payments, the same on every vendor; orders keep
`customer_id`/`referrer_id` as basic columns and as read-only `@ManyToOne`s. Edge rows: a missing customer (999), an
order with none, a customer with every column `NULL` but its id, a missing region, a region whose country is missing, a
country with a `NULL` name, one person as both customer and referrer, a payment of the missing customer 999.

## Findings

Each item was run on H2, PostgreSQL and MySQL on both lines: 39 cells per vendor and line, 117 per line, all `PASS`
([`rfc0024-6.6.58.Final.tsv`](0024-spike-data/rfc0024-6.6.58.Final.tsv),
[`rfc0024-7.4.14.Final.tsv`](0024-spike-data/rfc0024-7.4.14.Final.tsv)). The two lines sent the same statements in
every cell, except that 7.4 names a CTE's alias `var_1_21_0` where 6.6 names it `cte01_0`. A "row" below is the same on
every vendor and line unless a cell says otherwise.

### 1 Part A presence: keep with change

| Cell | Result |
|---|---|
| `baseline-presentBy-on-entity-join-root` | production `BUYER.root().presentBy(QBuyer.KEY)`: `MQ1104` |
| `emulated-presentBy-keeps-name` | the node keeps name `buyer`, is a joined-entity root, equals `BUYER.root()` (same `JoinKey`), carries the key |
| `left-rows` | 12 rows = native; orders 2 (customer 999) and 3 (none) are `Optional.empty()`; order 4 (customer 11, `name`/`email` `NULL`) is present; `count` 12 |
| `inner-rows` | 10 rows = native, misses dropped by the join; order 4 present; `count` 10 |
| `left-` / `inner-columnset-without-key` | `select(SelectSet.of(id, buyerName))` still selects the key: `select so1_0.id,sc1_0.name,sc1_0.id from sp_order so1_0 left join sp_customer sc1_0 on sc1_0.id=so1_0.customer_id`; order 4 present |

Nothing else in the core needed changing: the presence-key walk (`ModelQuery#presenceKeys`, D-38) re-roots `QBuyer.ID`
under the joined root through `TableField#under`, which returns the join for a root path, and `Row.scoped(BUYER_TABLE)`
finds the key by its `JoinKey`. Relaxing only the `MQ1104` check without keeping the name would make the copy a plain
`SpCustomer` root (`MQ1003` at the first statement): the RFC is right that both go together.

**Change: the path name.** The joined root's `propertyPath()` is `''` today, so the buyer's `name` column has the
property path `name`, the same as a root column `name`. The RFC fixes that by returning the join name. That's right only
when the `@Join` field and the `@JoinEntity` share a name: `@Join(entityJoin = "buyer") Optional<Buyer> purchaser`
should sort and filter as `purchaser.name`, like an association `@Join` (`named(field)`, D-55). Let `propertyPath()`
return the root's `named(...)` property, and have the generator name it after the field. In Part B this matters more
(item 2, Q2).

### 2 Part B re-rooting: keep with change

| Cell | Result |
|---|---|
| `nested-model-standalone` | `QBuyerB.query(totals)`: 6 rows = native (its own region and totals joins) |
| `today-row-scoped-hazard` | the same outer query mapped with today's `row.scoped(CUSTOMER_TABLE)`: 17 nested values read the nested root's own `name` as `regionName`; order 1's customer 10 reads `regionName='Ann'` (its region is 100, `North`); `spent` reads `NULL` (no column at the re-rooted path) |
| `emulated-scoped-two-fields-same-type` | 12 rows = native with the two prefixed joins; order 1: customer North / 12.5, referrer South / 3.25; order 9 (one person twice): both fields equal |
| `columnset-only-nested-joined-columns-loses-presence` | **finding**, below: 10 of 12 present customers read `Optional.empty()` |
| `columnset-only-nested-joined-columns-with-scope-key` | the same selection plus the customer's key: 12 rows = native |
| `broken-map-missing-entry` | the `customer` map without `region`: reading `regionName` throws (never the root's `name`) |
| `broken-map-swapped-prefix` | `region` mapped to `referrer_region` under `customer`: refused when the map is built (`referrer_region` is anchored on `join 'referrer'`, not at or below `join 'customer'`) |
| `broken-map-wrong-kind` | `region` mapped to `customer_totals`: refused when the map is built |
| `paths-of-prefixed-columns` | `entity(customer_region).name` and `entity(referrer_region).name` both have the property path `regionName`; `source(customer_totals).mq_spent` and `source(referrer_totals).mq_spent` both `spent`; `customer.name` is `customer.name` |

The hazard is real and silent on today's engine, and a scoped row that stops at joined roots fixes it on top of the
existing `RowSelection` lookup: inside the map, a nested joined root's column is looked up with the outer prefixed join
as the scope, which re-roots it (and any to-one path below it) exactly as `Row.scoped` does for an association. The
emulation is 60 lines; the map has to be checked when it is built, since a generator that swaps two prefixes would
otherwise read the other field's row without an error. The check that catches it is cheap: every anchor of the outer
join reads at or below the `@Join` field's join (`TableField#isAtOrBelow`).

**Change: the presence key.** D-38 selects the key of every `presentBy` join a selected column is read *through*. A
column on `customer_region` or `customer_totals` isn't below the `customer` join, so `select(ID, CUSTOMER_REGION_NAME,
CUSTOMER_SPENT)` renders `select so1_0.id,sr1_0.name,cte01_0.mq_spent` with no `c1_0.id`, and the mapper reads every
customer as empty, though its region name and totals were read: silently wrong (INV-5). The rule has to say that a
column on a nested joined root mapped to a `@Join` field also selects that field's presence key (and the keys above it).
With the key added the rows match (`-with-scope-key`). The RFC's Part B section should name this, and an `AC-*` should
cover a `ColumnSet` holding only nested joined columns.

**Q2 (prefixed names in `FilterSpec` paths and sort names).** The spike agrees with the recommendation, the field path
only, and shows it needs work: today both prefixed region columns resolve to the bare `regionName`, which clashes. The
generator should name each prefixed joined root after its `@Join` field path (`customer`), so the column's property
path is `customer.regionName`, while `identityPath()` keeps `entity(customer_region).name` for indexes and cursor
fingerprints (R-PAG-19, D-196). If `propertyPath()` returned the join name, as Part A proposes, Part B would expose
`customer_region.regionName`, which the recommendation rules out: one more reason for the item 1 change.

### 3 Part B sources: keep

| Cell | Result |
|---|---|
| `two-sources-one-type-two-prefixes` | `query(all, ge2)`: 12 rows = native; the customer reads totals over every payment, the referrer over payments `>= 2.00`; one statement, binds `[2.00]` (each body binds once, in its own CTE) |
| `swapped-source-parameters` | `query(ge2, all)`: 5 of 12 rows read the other source, no error (as D-189 says of two joins of one source today) |
| `keyset-paging-over-outer` | `orderBy(ID).keyset()`, pages of 5: 3 pages, concatenated = native |
| `keyset-by-nested-name-no-null-order` | ordered by `customer.name` without a null order: `MQ2202` once a page holds a `NULL` name (on PostgreSQL only on a later page, since its nulls sort last) |
| `keyset-by-customer.name-nullsLast` | 3 pages of 4 = the list |
| `keyset-by-entity(customer_region).name-nullsLast` | 3 pages of 4 = the list (a column of a prefixed nested entity join) |
| `keyset-by-source(referrer_totals).mq_spent-nullsLast` | 3 pages of 4 = the list (a column of a prefixed nested source join) |
| `grouped-count-through-both` | grouped by both prefixed `spent` columns: 11 groups; `count` 11, read client-side (the count statement selects the group keys only, D-192) |

The factory shape works as the RFC writes it, and the swap risk is the one D-189 already documents.

**Finding to document: re-rooted anchors read the foreign key column.** A nested source join on `id`, re-rooted onto the
`customer` association, joins on `customer.id`, which Hibernate renders as the order's own `so1_0.customer_id` (the
`sp_customer` join stays, for the selected columns): `left join cte0 cte01_0 on
cte01_0.mq_customerId=so1_0.customer_id`. On a dangling key the nested model is empty (its presence key reads
`c1_0.id`), but the prefixed source still matches: payment 9 belongs to the missing customer 999, so the grouped read
puts order 2 under `customerSpent = 99` and order 10 under `referrerSpent = 99`, where the joined row's id gives `NULL`
(the cell lists both readings). It's today's behaviour for any anchor on a to-one id, not new with Part B, but Part B
generates such anchors without the user writing them. It shows only to a filter, sort or group over a prefixed column;
the RFC should say so in Part B's "Anchors" point, as a schema without the foreign key constraint is what Part A and B
are for.

### 4 Part C chains: keep, Part C ships

| Cell | Rows | `count` |
|---|---|---|
| `baseline-chain-refused` (production rule) | `MQ1911`: "reads the entity join ... not another join" | |
| `chain-LL` | 12 | 12 |
| `chain-LI` | 8 | 8 |
| `chain-IL` | 10 | 10 |
| `chain-II` | 8 | 8 |
| `chain-LLL` | 12 | 12 |
| `chain-III` | 6 | 6 |
| `chain-LLI` | 6 | 6 |
| `chain-LIL` | 8 | 8 |
| `chain-ILL` | 10 | 10 |
| `chain-LLL-joinFilter-on-first` (`c.name = 'Ann'`) | 12 | 12 |
| `chain-LLL-keyset-pages` | 3 pages of 4 = native | |
| `combined-partA-over-partB-over-chain` | 12 | 12 |
| `chain-LLL-reversed-registration` | `MQ1003` | |
| `chain-LIL-joinFilter-on-first` | `MQ1912` | |

Every chain returned the native query's rows, and `count` equalled them, on H2, PostgreSQL and MySQL on both lines. The
joins render in creation order with each `ON` reading the join before it, the same on all six:

```sql
select so1_0.id,sc1_0.name,sr1_0.name,sc2_0.name from sp_order so1_0
left join sp_customer sc1_0 on sc1_0.id=so1_0.customer_id
left join sp_region sr1_0 on sr1_0.id=sc1_0.region_id
left join sp_country sc2_0 on sc2_0.id=sr1_0.country_id order by 1
```

The `count` statements keep every join (`select count(*) from sp_order so1_0 left join ... join sp_country ...`), so an
`INNER` link anywhere in the chain is counted right. A `joinFilter` on the first join renders in its `ON` (`and
sc1_0.name=?`, bound `Ann`), and the joins after it miss where it blanks the anchor, as the native query does. The
combined case, the reason for Part C (a `@Join(entityJoin = "buyer")` nested model with its own `@JoinEntity region`,
registered as `buyer_region` on `buyer`'s `regionId`), reads the same rows as native SQL through the Part A presence
node and the Part B scoped row. The core change Part C needs is the one `MQ1911` branch in
`EntityJoin#checkColumnAnchor`: anchor path checks, `MQ1912` and the count walk handle a chain unchanged.

What the RFC should add or settle:

- **Creation order should be checked when the query is built.** Registered in reverse (country, region, customer), the
  query builds and fails at the first statement with `MQ1003` "the query binds no entity joined as 'r'; bind it with
  joinEntity(...)", which is wrong advice: it is bound, later. The rule "created after the join it reads from" belongs
  in `checkEntityJoins` as an `MQ1911` naming both joins. Generated code always registers in order, so this only reaches
  hand-written `EntityJoin`s.
- **`INNER` below `LEFT`: refuse it.** The SQL means what it says: `LI` keeps 8 of 12 orders, `LLI` 6, `LIL` 8, dropping
  each order whose customer missed. The RFC leaves the choice open ("makes every join anchored on it `LEFT` (or refuses
  `INNER`)"). Refusing matches Part A rule 4 and `MQ1912`, and quietly turning the user's `INNER` into a `LEFT` would
  change rows without saying so.
- **`MQ1912`'s message** names the filtered join as a raw record (`JoinKey[parent=null, attribute=...SpCustomer,
  type=null, alias=c, entity=true]`) rather than `joined entity 'c'`. A chain reaches this message more often, so fix
  it with Part C.

## Not covered

Nesting two levels deep (a Part B nested model inside another): the emulated scoped row refuses `scoped(...)` on itself,
and the composition of name maps is untested. Generated `FieldIndex` entries for prefixed columns (the duplicate
`regionName` above would clash there too). EclipseLink: not run; `model-query-hibernate`'s renderers only, like RFC 0022
Part A's deferral. Performance: the data set is 12 orders; nothing here measures cost.
