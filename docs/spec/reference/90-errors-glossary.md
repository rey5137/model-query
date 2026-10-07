# 90 — Codes, Exceptions and Glossary

**Covers:** every `MQnnnn` code, which exception carries it, and the vocabulary the spec and the code both use.
**Read when:** adding a failure, or naming a type.
**Owns:** the code registry. A code's meaning is fixed once released (INV-10).

---

## 1. Code ranges

| Range | Phase | Raised by |
|---|---|---|
| `MQ1xxx` | Query definition — when `build()` or the first resolution runs | `ModelQueryDefinitionException` |
| `MQ2xxx` | Execution, paging and export | `ModelQueryExecutionException` |
| `MQ3xxx` | Annotation processing (compile time) | a javac `ERROR` diagnostic, not an exception |
| `MQ4xxx` | Configuration and vendor resolution | `ModelQueryConfigurationException` |

**R-ERR-01** Every exception the library throws carries a code, the model's simple name where one applies, and the
column or property name. A bare `IllegalArgumentException` from library code is a bug.

**R-ERR-02** A code is added here before the code that raises it (`delivery/61` R-REL-15).

**R-ERR-05** The three exceptions above extend the abstract `ModelQueryException extends RuntimeException`, which
holds `MqCode code()`, so a caller catches every library failure in one clause and reads its code; the one exception
is R-ERR-04's `OptimisticLockException`. Its constructors are protected; no other direct subclass is planned (D-106).
*Amended at the M9 gate:* sealed over its three subclasses, which are `non-sealed`, so a foreign exception cannot
carry an `MqCode`; sealing after 1.0 would break subclasses.

## 2. `MQ1xxx` — query definition

The sub-ranges are `MQ10xx` columns and joins, `MQ11xx` definitions, `MQ12xx` the query builder, `MQ13xx` filters, `MQ14xx`
aggregates, `MQ15xx` expressions (D-115), `MQ16xx` bulk writes, `MQ17xx` fetch plans and `MQ18xx` inserts (D-116).

| Code | Meaning | Owner |
|---|---|---|
| `MQ1001` | A column's declared type does not match the entity attribute, or two columns compared or matched against a sub-select convert to different attribute types | `api/10` R-COL-08, `api/12` R-FLT-15 |
| `MQ1002` | An attribute named by a column or join does not exist on its entity; the provider's exception is the cause | `api/10` R-COL-01, R-COL-08 |
| `MQ1003` | A column or table sits on a root entity the query is not rooted at | `api/10` R-COL-01 |
| `MQ1101` | Two `TableField`s share a join key but carry different `on(...)` conditions | `api/10` R-COL-04 |
| `MQ1102` | `on(...)` used without `as(...)` | `api/10` R-COL-04 |
| `MQ1103` | Two `Agg.of` fields share a name with different expressions | `api/13` R-AGG-02 |
| `MQ1104` | `as(...)`, `on(...)` or `presentBy(...)` on a root `TableField`, which is not a join | `api/10` R-COL-03, R-COL-04 |
| `MQ1201` | `keyset()` or `primaryKeyFirst(...)` without a primary key | `api/11` R-QRY-03 |
| `MQ1202` | `build()` without `select` or `fetch` | `api/11` R-QRY-02 |
| `MQ1203` | `ModelQuery.builder`, `ModelUpdate.builder` or `ModelDelete.builder` given a join instead of a root `TableField` | `api/11` R-QRY-02, `api/14` R-WRT-12 |
| `MQ1204` | `PrimaryKeyFirst.whenOffsetAbove` with a negative offset | `api/11` R-QRY-03 |
| `MQ1205` | A `QueryCustomizer` changed the `ORDER BY` or `GROUP BY` of a phase | `api/11` R-QRY-11 |
| `MQ1206` | A primary-key column of array type | `api/11` R-QRY-12 |
| `MQ1207` | `keyset()` with a `Float` or `Double` order or primary-key column | `api/11` R-QRY-13 |
| `MQ1208` | `keyset()` with an expression order key, whose cursor and fingerprint are defined over attribute values | `api/11` R-QRY-16 |
| `MQ1301` | A value-form filter received `null`, or `add(label, …)` a null or blank label | `api/12` §1 |
| `MQ1302` | A column or nested `exists(...)` path inside `exists(...)` is not on or below the given path | `api/12` R-FLT-11 |
| `MQ1303` | A `Filters` or `Having` used after its operator returned, or while a nested operator runs | `api/12` §1, D-23 |
| `MQ1304` | `exists(...)` given a root instead of a join path | `api/12` R-FLT-11 |
| `MQ1305` | A `Filters.add` predicate returned `null` | `api/12` §1, D-24 |
| `MQ1306` | One `in` or `notIn` filter has more values than `maxBindParameters()` | `api/12` R-FLT-09 |
| `MQ1307` | A statement binds more values than `maxBindParameters()` together, though no one `in` or `notIn` filter does; a keyset statement (page, export page, key-first round) is refused up front when its own binds plus the worst cursor, k(k+1)/2 for k keyset keys, would pass it: `<label>: a keyset statement binds <own> values of its own, and the keyset cursor's values can add up to <worst> more, over the <max> bind parameters one statement takes; narrow the query's own filters by at least <n> or use fewer keyset columns` | `api/12` R-FLT-09, D-80, D-82 |
| `MQ1308` | A value cannot be converted to its column's attribute type: a value filter or a write converts it through `ColumnField.toAttribute` and the converter throws `IllegalArgumentException`, for example `InstantTimestampConverter` with an `Instant` beyond `Timestamp`'s range; `persist` setting a primitive attribute to `null` | `api/10` R-COL-14, D-84 |
| `MQ1309` | `exists` or `notExists` over a sub-select whose correlation lifts no outer column | `api/12` R-FLT-17, D-112 |
| `MQ1310` | An `Outer.column` resolved outside the correlation it was made in, or lifted through two sub-select levels | `api/12` R-FLT-17 |
| `MQ1311` | `Outer.column` given a column that is not on the outer query's root | `api/12` R-FLT-17 |
| `MQ1312` | `in` or `notIn` over a sub-select with an embeddable-valued column on either side; checked at first resolution | `api/12` R-FLT-16 |
| `MQ1401` | A selected non-aggregate column or expression does not fit the group-by | `api/13` R-AGG-08, R-AGG-14 |
| `MQ1402` | Keyset paging or primary-key-first on a grouped query | `api/13` R-AGG-10 |
| `MQ1403` | `Agg.sum` or `Agg.sumAsLong` over a column whose SQL sum type differs from the declared result type | `api/13` R-AGG-03 |
| `MQ1404` | An aggregate passed to `groupBy`, which takes columns only | `api/13` R-AGG-05 |
| `MQ1405` | An `Agg.of` expression returned `null`, or an expression whose Java type is not the declared type | `api/13` R-AGG-02 |
| `MQ1406` | An `orderBy` key that does not fit the grouping: a non-group-key column or an expression that does not fit on a grouped query, or an aggregate on an ungrouped one | `api/13` R-AGG-08, R-AGG-14 |
| `MQ1407` | `having(...)` on an ungrouped query, one with neither a `groupBy` nor a selected aggregate | `api/13` R-AGG-07 |
| `MQ1408` | `sum`, `sumAsLong` or `avg` over a column that has a `ColumnConverter`; `min`, `max` and `countDistinct` over a column with a converter that is not ordered do not compile (D-93) | `api/13` R-AGG-04 |
| `MQ1409` | A grouped query selects a column under a `presentBy` join whose key columns are not all group keys | `api/13` R-AGG-09 |
| `MQ1501` | An expression reads a column with a `ColumnConverter`, which the database computes over without applying the converter | `api/10` R-COL-17 |
| `MQ1502` | An expression is given a null value, or a value of a type it cannot bind (arrays, `Date`, `Calendar`, enums, entities) | `api/10` R-COL-18 |
| `MQ1503` | `dividedBy` over two integral operands, which truncates on PostgreSQL and H2 and not on MySQL | `api/10` R-COL-17 |
| `MQ1504` | A CASE condition has no filter left, so it would always match | `api/10` R-COL-17 |
| `MQ1505` | A CASE condition uses `add(...)`, `exists(...)` or a sub-select | `api/10` R-COL-17 |
| `MQ1506` | `function(name, …)` with a name that is not a plain SQL identifier, or is a built-in aggregate | `api/10` R-COL-17 |
| `MQ1507` | An expression's declared type is not the type the provider resolves; checked at first resolution | `api/10` R-COL-17 |
| `MQ1601` | A bulk write chose its rows with `where(...)` and no predicate is left | `api/14` R-WRT-12 |
| `MQ1602` | A column is assigned twice in one update | `api/14` R-WRT-13 |
| `MQ1603` | `set(column, null)`; NULL must be written with `setNull` | `api/14` R-WRT-06 |
| `MQ1604` | An assigned column is not on the update's root (a self-referencing join) | `api/14` R-WRT-06 |
| `MQ1605` | A primary-key or `@Version` column is assigned; checked at `build()` for the definition's key, else on first execution | `api/14` R-WRT-13 |
| `MQ1606` | `expectVersion` on a root with no `@Version` or with a value of the wrong type, or a `@Version` of a type a bulk update cannot increment unless `keepVersion` (on first execution), or `expectVersion` with `keepVersion` and nothing to write (at `build()`) | `api/14` R-WRT-16, R-WRT-07, D-60, D-61 |
| `MQ1607` | `Changes.from(...)` names a column that is not writable | `api/14` R-WRT-04 |
| `MQ1608` | A bulk write's `@PrimaryKey` is not the root entity's id; checked on first execution, before the flush | `api/14` R-WRT-08, D-61 |
| `MQ1609` | `setExpression` on a column with a converter | `api/14` R-WRT-14 |
| `MQ1610` | An update `throughEntities()` combined with `keepVersion()`, `expectVersion(v)` or any `setExpression`, whatever the call order; at `build()` | `api/14` R-WRT-41, R-WRT-46, D-118 |
| `MQ1611` | A write assignment whose path is unknown, an id, a `@Version`, a collection, a to-one or a whole embeddable, or two assignments of overlapping kinds for one root and path; on the first write per root per factory, before any statement | `api/14` R-WRT-49, D-118 |
| `MQ1612` | A write assignment whose type the attribute cannot take after boxing (on the first write per root per factory), or whose supplier returns `null` or a value of the wrong type (at execution); before any statement | `api/14` R-WRT-49, D-118 |
| `MQ1701` | A join plan whose join ends up with no selected column | `api/15` R-FCH-07 |
| `MQ1702` | A column a fetch plan needs (a child's key, an enricher's column) is read through a to-many join; checked on first execution (`count` logs a `WARNING` instead) | `api/15` R-FCH-02 |
| `MQ1703` | A fetch plan names the same child or join twice | `api/15` R-FCH-01 |
| `MQ1704` | A fetch plan with a child, at any join depth, on a grouped query | `api/15` R-FCH-10 |
| `MQ1705` | A join plan selects an `AggregateField` or an expression, which cannot be re-rooted under the join | `api/15` R-FCH-07 |
| `MQ1706` | `Enricher.byKeys(…).reading(…)` with no `key(…)` | `api/15` R-FCH-15, D-114 |
| `MQ1707` | `Enricher.Keys.batchSize(n)` with `n` below 1 | `api/15` R-FCH-15 |
| `MQ1801` | An insert column not mapped, mapped twice, or not in the model's `InsertColumns`; a `map` whose columns have different converter classes (at `build()`) or entity attribute types (on first execution); a `set` on a column the model has, a column set twice, or a column added or set that is not on the written root; `lockKeys()` on insert-values (at `build()`); `insertReturningKeys` on a definition with `commitEachChunk()` (at that call, before the flush) | `api/14` R-WRT-25, R-WRT-27, R-WRT-29, R-WRT-32, R-WRT-33, D-117 |
| `MQ1802` | An insert model naming a generated id, lacking an id that has no generator, or writing part of a composite id, also under `persist`, which checks a generated id only where the provider reports the generator (on first execution); a row with a `null` assigned id (at `build()` or `ModelPersist.of`) | `api/14` R-WRT-26, R-WRT-30, D-117 |
| `MQ1803` | A `null` insert row, at `build()` or `ModelPersist.of` | `api/14` R-WRT-30, D-117 |
| `MQ1804` | Conflict columns that are not the root's id, natural id or a declared unique constraint, a `doUpdate` assigning an id or `@Version` attribute, a conflict target the vendor does not honour without `anyUniqueKey()`, or `doNothing` the provider does not render for the dialect (on first execution); a `doUpdate` `where` reading two or more assigned columns without `conflictUpdateWhereOnAssignedColumns(true)`, or where the profile's `conflictWhereSeesEarlierAssignments()` (on execution); a conflict column named twice or not in `InsertColumns`, a `doUpdate` assigning a key column, a column twice or a column not on the root, a `setFromRow` column not in `InsertColumns`, or a `doUpdate` `where` with a joined column, `exists` or a sub-select (at `build()`) | `api/14` R-WRT-34, R-WRT-36, D-116, D-117 |
| `MQ1805` | A generator outside R-WRT-26's allowlist (naming its class), a `JOINED` or `@SecondaryTable` root, a composite id with generated parts, `@MapsId`, or a constructor-only embeddable under `persist`; on first execution | `api/14` R-WRT-26, R-WRT-39, D-116 |
| `MQ1806` | A `chunked` insert-select whose source and target overlap, or whose source or target has an empty `tablesOf`, or whose source joins through a link or collection table (a many-to-many, an element collection, a join-table one-to-many); on first execution, before the flush | `api/14` R-WRT-28, D-117 |
| `MQ1807` | Keys requested for an `IDENTITY` or assigned id, or a key type `K` that is not the boxed id type the provider reports (as an `orm.xml` mapping can make it; `Object` passes, and nothing is compared where the metamodel reports no id type); on first execution | `api/14` R-WRT-33, R-WRT-39, §10.1, D-117 |
| `MQ1808` | Two rows of one insert-values call with a conflict clause share a conflict-key tuple, compared by `equals`; at `build()` | `api/14` R-WRT-37, D-117 |
| `MQ1809` | A `persist(persist, returning)` query with `where`, `having`, `groupBy`, a fetch plan, `customize`, `orderBy`, `keyset` or `primaryKeyFirst`, or a selected column it cannot fill from the flushed entity (a join beyond a to-one id, a join with `on(...)`, an expression, an aggregate); on first execution per factory, before any statement | `api/14` R-WRT-48, D-118 |

## 3. `MQ2xxx` — execution

| Code | Meaning | Owner |
|---|---|---|
| `MQ2001` | `pageSize`, `ExportOptions.pageSize`, `ChunkOptions.size` or `ChildQuery.maxPerParent` is not positive, a `KeysetSpec` size is outside `1..Integer.MAX_VALUE - 1`, or a `Limit` is negative | `engine/20` R-EXE-06, `engine/21` R-PAG-16, `api/15` R-FCH-04 |
| `MQ2002` | Negative offset | `engine/20` R-EXE-06 |
| `MQ2101` | Streaming requires a transaction on this vendor | `engine/20` R-EXE-08 |
| `MQ2201` | A row's primary key mapped to `null` during export or primary-key-first paging | `engine/21` R-PAG-03 |
| `MQ2202` | A keyset column is NULL and the column has no explicit null precedence | `engine/21` R-PAG-05 |
| `MQ2203` | An operation needing a primary key (offset export of an ungrouped query, the `PRIMARY_KEY` phase) on a query without one; a grouped query never has one (`api/13` R-AGG-09) | `api/11` R-QRY-03 |
| `MQ2204` | Offset export of an ungrouped query, keyset paging or the primary-key-first phase over a selection, including an expression, read through a to-many join | `engine/21` R-PAG-13 |
| `MQ2205` | A keyset page holds the cursor's own primary key, or a keyset export page holds a key of the page before: a cursor value did not survive being bound, or a row's keyset value moved after the cursor; or a bulk write's key select returns a key the round before wrote | `engine/21` R-PAG-14, R-PAG-24, `api/14` R-WRT-17 |
| `MQ2206` | A customizer narrows the phases of a query with `primaryKeyFirst(...)` differently | `engine/21` R-PAG-15 |
| `MQ2207` | A keyset page on a query without `keyset()`: `<Model>: page(query, KeysetSpec) needs a keyset() query; add keyset() or page by PageSpec` | `api/11` R-QRY-03, `engine/21` R-PAG-16 |
| `MQ2208` | A malformed or edited cursor (null or blank, not base64, unknown version, bad checksum, truncated, a value that fails to decode, a NULL in a refusing or primary-key column, over 8192 characters): `<Model>: the keyset cursor is not one this library issued: <reason>; start again with KeysetSpec.first`; the whole cursor is never echoed | `engine/21` R-PAG-18 |
| `MQ2209` | A cursor's fingerprint names another order (sort, direction, null precedence, entity or deployment changed): `<Model>: the keyset cursor belongs to another order (sort, direction, null precedence, entity or deployment changed); start again with KeysetSpec.first` | `engine/21` R-PAG-19 |
| `MQ2210` | A key column cannot be carried in a cursor, or makes it longer than 8192 characters: `<Model>: keyset column <col> of type <T> cannot be carried in a cursor` or `... makes the cursor longer than 8192 characters` | `engine/21` R-PAG-17, R-PAG-18 |
| `MQ2301` | A sort property resolves to no selected column or to more than one (on any tier), or asks for `ignoreCase`; a sort on an ungrouped query without a primary key; a sorted copy that fails `build()`, as the cause | `api/11` R-QRY-14, `integration/50` R-SPR-06 |
| `MQ2501` | A bulk write, other than `commitEachChunk()`, or `persist` ran without an active transaction | `api/14` R-WRT-18, R-WRT-39 |
| `MQ2502` | A per-chunk write failed; `ChunkedWriteException` carries the committed rows, the last committed key and the keys of a chunk in doubt | `api/14` R-WRT-20 |
| `MQ2601` | A to-one child finds two distinct rows for one key | `api/15` R-FCH-04 |
| `MQ2602` | An `Enricher.of` returns a page of another size, or `null` | `api/15` R-FCH-08 |
| `MQ2603` | A parent has more children than `maxPerParent`, or a round reaches its row cap | `api/15` R-FCH-11 |
| `MQ2604` | A child row's key equals none of its round's keys (a case-insensitive or padding collation) | `api/15` R-FCH-05 |
| `MQ2605` | `stream` with a fetch plan that has a child, join plan or enricher | `api/15` R-FCH-09 |
| `MQ2606` | An `Enricher.byKey` or `byKeys` lookup returned `null` | `api/15` R-FCH-08, R-FCH-15 |

**R-ERR-04** One JPA exception is thrown deliberately instead of a library type: `OptimisticLockException` when an
`expectVersion` update affects no rows (`api/14` R-WRT-16), because callers already handle it for entity writes.

## 4. `MQ3xxx` — annotation processing

Catalogued with messages in `processor/32` §1: `MQ3001`–`MQ3019` for structural checks, `MQ3201`–`MQ3208` for aggregate
models, `MQ3301`–`MQ3307` for update models, `MQ3401`–`MQ3406` for `@Child` (`api/15`), `MQ3501`–`MQ3504` for
insert models (D-116, D-117). Codes are not repeated here to keep one owner.

## 5. `MQ4xxx` — configuration

| Code | Meaning | Owner |
|---|---|---|
| `MQ4001` | `modelquery.vendor` names an unknown vendor | `vendor/40` R-VND-04 |
| `MQ4002` | Two `VendorProfile`s registered for the same vendor with no precedence rule | `vendor/40` R-VND-03 |
| `MQ4003` | A property value, or its `ModelQueryConfig` setting, is outside its allowed range | `integration/50` §3 |
| `MQ4005` | `modelquery.vendor` set with more than one `EntityManagerFactory` and no `ModelQueryConfigurer` | `integration/50` R-SPR-13 |
| `MQ4006` | A `ModelQueryConfig` bean of the application drops a `VendorProfile`, `WriteAssignment` or `ChunkTransactions` bean, or a set `modelquery.*` property | `integration/50` R-SPR-13 |
| `MQ4007` | A repository declares `ModelQueryRepository` of an entity other than its domain type | `integration/50` R-SPR-12 |
| `MQ4008` | The starter cannot add the repository fragment because the factory bean definition already sets `customImplementation` | `integration/50` R-SPR-02, D-113 |
| `MQ4004` | `commitEachChunk()` with no `ChunkTransactions` configured, or none that serves the write's `EntityManagerFactory` | `api/14` R-WRT-19 |
| `MQ4009` | A bulk insert on a factory whose persistence provider has no `InsertSupport` (`ProviderSupport#inserts()` empty or no `ProviderSupport`); before any statement and before the flush | `vendor/40` R-VND-14, `api/14` R-WRT-24, D-117 |

## 6. Glossary

| Term | Meaning |
|---|---|
| **Entity** | A JPA `@Entity`, used only as the mapping source. Never returned by this library. |
| **Model** | A plain class or record that receives query results. Needs no base class or interface. |
| **Column** | A `ColumnField`: one table attribute, typed, optionally converted. |
| **Aggregate** | An `AggregateField`: `count`, `sum`, `avg`, `min`, `max` or an `Agg.of` expression. |
| **Selectable** | A `SelectField`: a column or an aggregate. What can be selected, ordered and read from a `Row`. |
| **Filter-only column** | A column used in `where` or `orderBy` with no field on the model. |
| **Column set** | An immutable named group of selectables (`DEFAULT`, `ALL`, `GROUP_KEYS`). |
| **Join key** | Parent key + attribute + join type + alias. Decides join identity, never object identity. |
| **Row** | One result row, keyed by `SelectField`. |
| **Group** | One result row of a grouped query. Has no primary key. |
| **Cursor** | The last row's keyset values, used to fetch the next page. |
| **Page** | One fetch of at most `pageSize` rows. |
| **Slice** | A page plus its metadata: number, size, `hasNext`, and a total if one was counted. |
| **Export** | A bounded-memory walk over every row or group, in pages. |
| **Profile** | A `VendorProfile`: every database-specific behaviour, in one object. |
| **Provider support** | A `ProviderSupport`: what a persistence provider does better than portable JPA (dialect detection, grouped count, native null precedence). Varies by provider, not by database (D-34). |
| **Render options** | `RenderOptions`: the vendor-neutral facts about the database that a query build renders by, made from the profile (D-34). |
| **QModel** | The generated companion class (`QOrderView`) holding a model's constants. |
| **Update model** | A class or record annotated `@UpdateModel` listing the attributes a bulk update may write. Holds no data. |
| **Change set** | A generated, mutable `Changes<M>` recording which columns were set, so "set to NULL" and "not set" differ. |
| **Bulk write** | A `ModelUpdate` or `ModelDelete`: one statement, or a chunked series, over the rows a filter matches. |

**R-ERR-03** Code and spec use these words and not synonyms: *column* not *field* for a `ColumnField`, *model* not *dto*
or *view object*, *selectable* not *expression*, *group* not *bucket*, *profile* not *dialect* (`dialect` means
Hibernate's).
