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
| `MQ1101` | Two `TableField`s share a join key but carry different `on(...)` conditions | `api/10` R-COL-04 |
| `MQ1102` | `on(...)` used without `as(...)` | `api/10` R-COL-04 |
| `MQ1103` | Two `Agg.of` fields share a name with different expressions | `api/13` R-AGG-02 |
| `MQ1201` | `keyset()` or `primaryKeyFirst(...)` without a primary key | `api/11` R-QRY-03 |
| `MQ1301` | A value-form filter received `null` | `api/12` §1 |
| `MQ1302` | A column inside `exists(...)` is not on or below the given path | `api/12` R-FLT-11 |
| `MQ1401` | A selected non-aggregate column is not in the group-by | `api/13` R-AGG-08 |
| `MQ1402` | Keyset paging or primary-key-first on a grouped query | `api/13` R-AGG-10 |
| `MQ1601` | A bulk write has no predicate left and `all()` was not called (`Future`, M8) | `api/14` R-WRT-12 |
| `MQ1602` | A column is assigned twice in one update (`Future`, M8) | `api/14` R-WRT-13 |
| `MQ1603` | `set(column, null)`; NULL must be written with `setNull` (`Future`, M8) | `api/14` R-WRT-06 |
| `MQ1604` | An assigned column is not on the update's root (a self-referencing join) (`Future`, M8) | `api/14` R-WRT-06 |
| `MQ1605` | A primary-key or `@Version` column is assigned (`Future`, M8) | `api/14` R-WRT-13 |
| `MQ1606` | `expectVersion` without `whereKey`, or on a root with no `@Version` (`Future`, M8) | `api/14` R-WRT-16 |
| `MQ1607` | `Changes.from(...)` names a column that is not writable (`Future`, M8) | `api/14` R-WRT-04 |

## 3. `MQ2xxx` — execution

| Code | Meaning | Owner |
|---|---|---|
| `MQ2001` | `pageSize` or `ExportOptions.pageSize` is not positive | `engine/20` R-EXE-06 |
| `MQ2002` | Negative offset | `engine/20` R-EXE-06 |
| `MQ2101` | Streaming requires a transaction on this vendor | `engine/20` R-EXE-08 |
| `MQ2201` | A row's primary key mapped to `null` during export | `engine/21` R-PAG-03 |
| `MQ2202` | A keyset column is NULL and the column has no explicit null precedence | `engine/21` R-PAG-05 |
| `MQ2301` | A `Sort` property resolves to neither an attribute path nor a known column | `integration/50` R-SPR-06 |
| `MQ2501` | A bulk write ran without an active transaction (`Future`, M8) | `api/14` R-WRT-18 |

**R-ERR-04** One JPA exception is thrown deliberately instead of a library type: `OptimisticLockException` when an
`expectVersion` update affects no rows (`api/14` R-WRT-16), because callers already handle it for entity writes.

## 4. `MQ3xxx` — annotation processing

Catalogued with messages in `processor/32` §1: `MQ3001`–`MQ3013` for structural checks, `MQ3201`–`MQ3205` for aggregate
models, `MQ3301`–`MQ3305` for update models (`Future`, M8). Codes are not repeated here to keep one owner.

## 5. `MQ4xxx` — configuration

| Code | Meaning | Owner |
|---|---|---|
| `MQ4001` | `modelquery.vendor` names an unknown vendor | `vendor/40` R-VND-04 |
| `MQ4002` | Two `VendorProfile`s registered for the same vendor with no precedence rule | `vendor/40` R-VND-03 |
| `MQ4003` | A property value is outside its allowed range | `integration/50` §3 |

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
| **QModel** | The generated companion class (`QOrderView`) holding a model's constants. |
| **Update model** | A class or record annotated `@UpdateModel` listing the attributes a bulk update may write. Holds no data (`Future`, M8). |
| **Change set** | A generated, mutable `Changes<M>` recording which columns were set, so "set to NULL" and "not set" differ (`Future`, M8). |
| **Bulk write** | A `ModelUpdate` or `ModelDelete`: one statement, or a chunked series, over the rows a filter matches (`Future`, M8). |

**R-ERR-03** Code and spec use these words and not synonyms: *column* not *field* for a `ColumnField`, *model* not *dto*
or *view object*, *selectable* not *expression*, *group* not *bucket*, *profile* not *dialect* (`dialect` means
Hibernate's).
