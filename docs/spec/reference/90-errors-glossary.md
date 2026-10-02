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

## 2. `MQ1xxx` — query definition

| Code | Meaning | Owner |
|---|---|---|
| `MQ1001` | A column's declared type does not match the entity attribute | `api/10` R-COL-08 |
| `MQ1002` | An attribute named by a column or join does not exist on its entity; the provider's exception is the cause | `api/10` R-COL-01, R-COL-08 |
| `MQ1003` | A column or table sits on a root entity the query is not rooted at | `api/10` R-COL-01 |
| `MQ1101` | Two `TableField`s share a join key but carry different `on(...)` conditions | `api/10` R-COL-04 |
| `MQ1102` | `on(...)` used without `as(...)` | `api/10` R-COL-04 |
| `MQ1103` | Two `Agg.of` fields share a name with different expressions | `api/13` R-AGG-02 |
| `MQ1104` | `as(...)`, `on(...)` or `presentBy(...)` on a root `TableField`, which is not a join | `api/10` R-COL-03, R-COL-04 |
| `MQ1201` | `keyset()` or `primaryKeyFirst(...)` without a primary key | `api/11` R-QRY-03 |
| `MQ1202` | `build()` without `select` | `api/11` R-QRY-02 |
| `MQ1203` | `ModelQuery.builder`, `ModelUpdate.builder` or `ModelDelete.builder` given a join instead of a root `TableField` | `api/11` R-QRY-02, `api/14` R-WRT-12 |
| `MQ1204` | `PrimaryKeyFirst.whenOffsetAbove` with a negative offset | `api/11` R-QRY-03 |
| `MQ1205` | A `QueryCustomizer` changed the `ORDER BY` or `GROUP BY` of a phase | `api/11` R-QRY-11 |
| `MQ1206` | A primary-key column of array type | `api/11` R-QRY-12 |
| `MQ1207` | `keyset()` with a `Float` or `Double` order or primary-key column | `api/11` R-QRY-13 |
| `MQ1301` | A value-form filter received `null` | `api/12` §1 |
| `MQ1302` | A column or nested `exists(...)` path inside `exists(...)` is not on or below the given path | `api/12` R-FLT-11 |
| `MQ1303` | A `Filters` or `Having` used after its operator returned, or while a nested operator runs | `api/12` §1, D-23 |
| `MQ1304` | `exists(...)` given a root instead of a join path | `api/12` R-FLT-11 |
| `MQ1305` | A `Filters.add` predicate returned `null` | `api/12` §1, D-24 |
| `MQ1306` | One `in` or `notIn` filter has more values than `maxBindParameters()` | `api/12` R-FLT-09 |
| `MQ1307` | A statement binds more values than `maxBindParameters()` together, though no one `in` or `notIn` filter does; a keyset statement (page, export page, key-first round) is refused up front when its own binds plus the worst cursor, k(k+1)/2 for k keyset keys, would pass it: `<label>: a keyset statement binds <own> values of its own, and the keyset cursor's values can add up to <worst> more, over the <max> bind parameters one statement takes; narrow the query's own filters by at least <n> or use fewer keyset columns` | `api/12` R-FLT-09, D-80, D-82 |
| `MQ1308` | A value cannot be converted to its column's attribute type: a value filter or a write converts it through `ColumnField.toAttribute` and the converter throws `IllegalArgumentException`, for example `InstantTimestampConverter` with an `Instant` beyond `Timestamp`'s range | `api/10` R-COL-14, D-84 |
| `MQ1401` | A selected non-aggregate column is not in the group-by | `api/13` R-AGG-08 |
| `MQ1402` | Keyset paging or primary-key-first on a grouped query | `api/13` R-AGG-10 |
| `MQ1403` | `Agg.sum` or `Agg.sumAsLong` over a column whose SQL sum type differs from the declared result type | `api/13` R-AGG-03 |
| `MQ1404` | An aggregate passed to `groupBy`, which takes columns only | `api/13` R-AGG-05 |
| `MQ1405` | An `Agg.of` expression returned `null`, or an expression whose Java type is not the declared type | `api/13` R-AGG-02 |
| `MQ1406` | An `orderBy` key that does not fit the grouping: a non-group-key column on a grouped query, or an aggregate on an ungrouped one | `api/13` R-AGG-08 |
| `MQ1407` | `having(...)` on an ungrouped query, one with neither a `groupBy` nor a selected aggregate | `api/13` R-AGG-07 |
| `MQ1408` | `sum`, `sumAsLong` or `avg` over a column that has a `ColumnConverter`; `min`, `max` and `countDistinct` over a column with a converter that is not ordered do not compile (D-93) | `api/13` R-AGG-04 |
| `MQ1409` | A grouped query selects a column under a `presentBy` join whose key columns are not all group keys | `api/13` R-AGG-09 |
| `MQ1601` | A bulk write chose its rows with `where(...)` and no predicate is left | `api/14` R-WRT-12 |
| `MQ1602` | A column is assigned twice in one update | `api/14` R-WRT-13 |
| `MQ1603` | `set(column, null)`; NULL must be written with `setNull` | `api/14` R-WRT-06 |
| `MQ1604` | An assigned column is not on the update's root (a self-referencing join) | `api/14` R-WRT-06 |
| `MQ1605` | A primary-key or `@Version` column is assigned; checked at `build()` for the definition's key, else on first execution | `api/14` R-WRT-13 |
| `MQ1606` | `expectVersion` on a root with no `@Version` or with a value of the wrong type, or a `@Version` of a type a bulk update cannot increment unless `keepVersion` (on first execution), or `expectVersion` with `keepVersion` and nothing to write (at `build()`) | `api/14` R-WRT-16, R-WRT-07, D-60, D-61 |
| `MQ1607` | `Changes.from(...)` names a column that is not writable | `api/14` R-WRT-04 |
| `MQ1608` | A bulk write's `@PrimaryKey` is not the root entity's id; checked on first execution, before the flush | `api/14` R-WRT-08, D-61 |
| `MQ1609` | `setExpression` on a column with a converter | `api/14` R-WRT-14 |

## 3. `MQ2xxx` — execution

| Code | Meaning | Owner |
|---|---|---|
| `MQ2001` | `pageSize`, `ExportOptions.pageSize` or `ChunkOptions.size` is not positive, or a `Limit` is negative | `engine/20` R-EXE-06 |
| `MQ2002` | Negative offset | `engine/20` R-EXE-06 |
| `MQ2101` | Streaming requires a transaction on this vendor | `engine/20` R-EXE-08 |
| `MQ2201` | A row's primary key mapped to `null` during export or primary-key-first paging | `engine/21` R-PAG-03 |
| `MQ2202` | A keyset column is NULL and the column has no explicit null precedence | `engine/21` R-PAG-05 |
| `MQ2203` | An operation needing a primary key (offset export of an ungrouped query, the `PRIMARY_KEY` phase) on a query without one; a grouped query never has one (`api/13` R-AGG-09) | `api/11` R-QRY-03 |
| `MQ2204` | Offset export of an ungrouped query, keyset paging or the primary-key-first phase over a selection read through a to-many join | `engine/21` R-PAG-13 |
| `MQ2205` | A keyset export page holds a key of the page before: a cursor value did not survive being bound, or a row's keyset value moved after the cursor; or a bulk write's key select returns a key the round before wrote | `engine/21` R-PAG-14, `api/14` R-WRT-17 |
| `MQ2206` | A customizer narrows the phases of a query with `primaryKeyFirst(...)` differently | `engine/21` R-PAG-15 |
| `MQ2301` | A sort property resolves to no selected column or to more than one (on any tier), or asks for `ignoreCase`; a sort on an ungrouped query without a primary key; a sorted copy that fails `build()`, as the cause | `api/11` R-QRY-14, `integration/50` R-SPR-06 |
| `MQ2501` | A bulk write, other than `commitEachChunk()`, ran without an active transaction | `api/14` R-WRT-18 |
| `MQ2502` | A per-chunk write failed; `ChunkedWriteException` carries the committed rows, the last committed key and the keys of a chunk in doubt | `api/14` R-WRT-20 |

**R-ERR-04** One JPA exception is thrown deliberately instead of a library type: `OptimisticLockException` when an
`expectVersion` update affects no rows (`api/14` R-WRT-16), because callers already handle it for entity writes.

## 4. `MQ3xxx` — annotation processing

Catalogued with messages in `processor/32` §1: `MQ3001`–`MQ3016` for structural checks, `MQ3201`–`MQ3207` for aggregate
models, `MQ3301`–`MQ3307` for update models. Codes are not repeated here to keep one owner.

## 5. `MQ4xxx` — configuration

| Code | Meaning | Owner |
|---|---|---|
| `MQ4001` | `modelquery.vendor` names an unknown vendor | `vendor/40` R-VND-04 |
| `MQ4002` | Two `VendorProfile`s registered for the same vendor with no precedence rule | `vendor/40` R-VND-03 |
| `MQ4003` | A property value, or its `ModelQueryConfig` setting, is outside its allowed range | `integration/50` §3 |
| `MQ4005` | `modelquery.vendor` set with more than one `EntityManagerFactory` and no `ModelQueryConfigurer` | `integration/50` R-SPR-13 |
| `MQ4006` | A `ModelQueryConfig` bean of the application drops a `VendorProfile` or `ChunkTransactions` bean, or a set `modelquery.*` property | `integration/50` R-SPR-13 |
| `MQ4007` | A repository declares `ModelQueryRepository` of an entity other than its domain type | `integration/50` R-SPR-12 |
| `MQ4004` | `commitEachChunk()` with no `ChunkTransactions` configured, or none that serves the write's `EntityManagerFactory` | `api/14` R-WRT-19 |

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
