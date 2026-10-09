# Diagnostics

Every failure the library raises carries a stable code of the form `MQnnnn`, the model's simple name where one
applies, and the column or property involved. The meaning of a released code never changes, so you can search for it.
Messages say what to change, for example `OrderView.totl: no attribute 'totl' on OrderEntity`.

| Range | When | How it surfaces |
|---|---|---|
| `MQ1xxx` | A query or write definition is invalid, found when `build()` or the first resolution runs | `ModelQueryDefinitionException` |
| `MQ2xxx` | Execution, paging, export and bulk writes | `ModelQueryExecutionException` |
| `MQ3xxx` | Your models, at compile time | a compiler error from the annotation processor |
| `MQ4xxx` | Configuration and vendor resolution, usually at startup | `ModelQueryConfigurationException` |

The three exceptions extend `ModelQueryException`, so one clause catches every failure the library raises, and its
`code()` says which one it was. The one exception is the JPA `OptimisticLockException` an `expectVersion` update
throws when it affects no rows (see [Bulk writes](bulk-writes.md)).

```java
try {
    return executor.page(query, PageSpec.of(0, 50), CountMode.NO_COUNT);
} catch (ModelQueryException e) {
    log.warn("query failed with {}", e.code(), e);
    throw e;
}
```

## `MQ1xxx`: query and write definitions

| Code | What went wrong |
|---|---|
| `MQ1001` | A column's declared type does not match the entity attribute. |
| `MQ1002` | An attribute named by a column or join does not exist on its entity. |
| `MQ1003` | A column or table sits on a root entity the query is not rooted at. |
| `MQ1101` | Two TableFields share a join key but carry different `on(...)` conditions. |
| `MQ1102` | `on(...)` used without `as(...)`. |
| `MQ1103` | Two `Agg.of` fields share a name with different expressions. |
| `MQ1104` | `as(...)`, `on(...)` or `presentBy(...)` on a root TableField, which is not a join. |
| `MQ1105` | `FieldIndex.only` names a key the index doesn't hold, or leaves a kept set empty. |
| `MQ1201` | `keyset()` or `primaryKeyFirst(...)` without a primary key. |
| `MQ1202` | `build()` without `select(...)` or `fetch(...)`. |
| `MQ1203` | Builder given a join instead of a root TableField. |
| `MQ1204` | `PrimaryKeyFirst.whenOffsetAbove` with a negative offset. |
| `MQ1205` | QueryCustomizer changed the `ORDER BY` or `GROUP BY` of a phase. |
| `MQ1206` | A primary-key column of array type. |
| `MQ1207` | `keyset()` with a Float or Double order or primary-key column. |
| `MQ1301` | A value-form filter received `null`. |
| `MQ1302` | A column or nested `exists(...)` path inside `exists(...)` is not on or below the given path. |
| `MQ1303` | A Filters or Having used after its operator returned, or while a nested operator runs. |
| `MQ1304` | `exists(...)` given a root instead of a join path. |
| `MQ1305` | A Filters.add predicate returned `null`. |
| `MQ1306` | One `in` or `notIn` filter has more values than `maxBindParameters()`. |
| `MQ1307` | A statement binds more values than `maxBindParameters()` together; narrow its filters. A keyset page, export page or write round is refused before it runs when its own binds plus the worst cursor would pass the limit; the message says how many binds each side has. |
| `MQ1308` | A value cannot be converted to its column's attribute type, for example an `Instant` beyond the range of `Timestamp`; `persist` setting a primitive attribute to `null`. |
| `MQ1401` | Grouped query: selected non-aggregate column is not in the group-by. |
| `MQ1402` | Grouped query: keyset paging or primary-key-first not allowed. |
| `MQ1403` | Grouped query: Agg.sum or Agg.sumAsLong over a column whose SQL sum type differs from the result type. |
| `MQ1404` | Grouped query: aggregate passed to `groupBy`, which takes columns only. |
| `MQ1405` | Grouped query: Agg.of expression returned `null`, or Java type does not match declared type. |
| `MQ1406` | Grouped query: orderBy key does not fit the grouping (non-group-key column on grouped query, or aggregate on ungrouped one). |
| `MQ1407` | Grouped query: `having(...)` on an ungrouped query. |
| `MQ1408` | Grouped query: aggregate function over a column with a ColumnConverter (`sum`, `sumAsLong` and `avg`; `min`, `max` and `countDistinct` take an `OrderedColumnField` and do not compile over any other converted column). |
| `MQ1409` | Grouped query: column selected under a `presentBy` join whose key columns are not all group keys. |
| `MQ1701` | A fetch plan's join plan whose join has no column of the query's selection at or below it. |
| `MQ1702` | A column a fetch plan needs (a child's key, an enricher's column) is read through a to-many join; checked on first execution, and `count` logs a warning instead. |
| `MQ1703` | A fetch plan names the same child or join twice. |
| `MQ1704` | A fetch plan with a child, at any join depth, on a grouped query. |
| `MQ1705` | A join plan selects an aggregate, which cannot be re-rooted under the join. |
| `MQ1601` | Bulk write: no predicate left after skipping rows. |
| `MQ1602` | Bulk write: column is assigned twice. |
| `MQ1603` | Bulk write: `set(column, null)` called; NULL must be written with `setNull`. |
| `MQ1604` | Bulk write: assigned column is not on the root (self-referencing join). |
| `MQ1605` | Bulk write: primary-key or `@Version` column is assigned. |
| `MQ1606` | Bulk write: `expectVersion` used incorrectly or on a root with wrong type. |
| `MQ1607` | Bulk write: `Changes.from(...)` names a column that is not writable. |
| `MQ1608` | Bulk write: `@PrimaryKey` is not the root entity's id. |
| `MQ1609` | Bulk write: `setExpression` on a column with a converter. |
| `MQ1610` | Bulk write: an update `throughEntities()` combined with `keepVersion()`, `expectVersion(v)` or `setExpression`, whatever the call order. |
| `MQ1611` | Write assignment: an unknown path, an id, a `@Version`, a collection, a to-one or a whole embeddable, overlapping kinds for one path, or an entity class that is a subclass of the write's root. |
| `MQ1612` | Write assignment: a type the attribute cannot take, or a supplier returning `null` or the wrong type. |
| `MQ1801` | Insert: a column is not mapped, is mapped or set twice, is not on the written root, or is not in the model's `InsertColumns`; a `map` between columns with different converters or types; `lockKeys()` on insert-values; `insertReturningKeys` with `commitEachChunk()`. |
| `MQ1802` | Insert: the model names a generated id, lacks an id that has no generator, or a row has a `null` assigned id; `persist` names a generated id (where the provider reports the generator) or part of a composite id. |
| `MQ1803` | Insert: a `null` row. |
| `MQ1804` | Insert conflict clause: the conflict columns are not the id, a natural id or a declared unique constraint; a vendor that detects a conflict on any unique key without `anyUniqueKey()`; `doNothing` the provider does not render; a `doUpdate` assigning a key column or reading two or more assigned columns in its `where` without `conflictUpdateWhereOnAssignedColumns(true)`. |
| `MQ1805` | Insert: a generator that is not supported for the call (a pooled sequence or a table or UUID generator on an insert-select), a `JOINED` or `@SecondaryTable` root, a composite id with generated parts, `@MapsId`, or a constructor-only embeddable under `persist`. |
| `MQ1806` | Insert: a `chunked` insert-select whose source and target overlap, or whose tables the provider cannot name, or whose source joins through a link or collection table (`@ManyToMany`, `@ElementCollection`, a `@OneToMany` over a join table). |
| `MQ1807` | Insert: keys requested for an `IDENTITY` or assigned id, or a key type that is not the id's type. |
| `MQ1808` | Insert: two rows of one insert-values call with a conflict clause share a conflict-key tuple. |
| `MQ1809` | `persist(persist, returning)`: a query with a `where` or `having` that recorded a filter, `groupBy`, a fetch plan, `customize`, `orderBy`, `keyset` or `primaryKeyFirst`, or a column the flushed entity cannot fill. |

See [Queries and Filters](queries.md), [Grouped queries](grouped-queries.md), [Bulk writes](bulk-writes.md) and [Inserts](inserts.md).

## `MQ2xxx`: execution

| Code | What went wrong |
|---|---|
| `MQ2001`, `MQ2002` | A page, export or chunk size is not positive, a limit is negative, or an offset is negative. |
| `MQ2101` | Streaming needs a transaction on this database (PostgreSQL). |
| `MQ2201` | A row's primary key mapped to `null` during export or primary-key-first paging. |
| `MQ2202` | A keyset column is NULL and has no explicit `nullsFirst()`/`nullsLast()`. |
| `MQ2203` | An operation that needs a primary key was used on a query without one. |
| `MQ2204` | Key-based paging or offset export over a selection read through a to-many join. |
| `MQ2205` | A keyset export page repeated a key of the page before; or a chunked write's key select returned a key it already wrote. |
| `MQ2206` | A `QueryCustomizer` narrows the phases of a primary-key-first query differently. |
| `MQ2301` | A sort property resolves to no column or to several, asks for `ignoreCase`, or names a sort on a query without a primary key. |
| `MQ2501` | A bulk write ran without an active transaction. |
| `MQ2502` | A chunk of a chunked write failed; `ChunkedWriteException` says what was committed. |
| `MQ2503` | An entity-mode update loaded a proxy your persistence context held for a row, and no `ProviderSupport` can unwrap it; add `model-query-hibernate` for Hibernate, or write under `CLEAR`. Nothing in the chunk was changed. |
| `MQ2601` | A to-one `@Child` found two distinct rows for one key. |
| `MQ2602` | An `Enricher.of` returned `null`, or a page of another size than it was given. |
| `MQ2603` | A parent has more children than `maxPerParent`, or one statement of a child load read its row cap. |
| `MQ2604` | A child row's key equals none of the keys it was matched to: the column's collation is case-insensitive or ignores trailing spaces. |
| `MQ2605` | `stream` with a fetch plan that loads children, join plans or enrichers; use `export`. |

See [Paging and export](paging-export.md) and [Bulk writes](bulk-writes.md).

## `MQ3xxx`: your models, at compile time

The processor reports these as compiler errors (a few as warnings) pointing at the field.

| Code | What went wrong |
|---|---|
| `MQ3001` | Unknown attribute, or a dotted `@Column(attribute)` that leaves embedded values. |
| `MQ3002` | Model type is not the entity attribute's type, and no converter provided or built in (`Instant` or `Date` over a `Timestamp`). |
| `MQ3003` | `@Join` attribute is not a to-one association, or the nested model's root does not match the target. |
| `MQ3004` | Missing `@PrimaryKey` on a model with no `@Aggregate` field. |
| `MQ3005` | `@Join` field is not `Optional<X>`, or `X` is not a `@QueryModel`, or `X` has an `@Aggregate` or `@Computed` field. |
| `MQ3006` | Nested model has no `@PrimaryKey`, so presence cannot be decided. |
| `MQ3007` | Join cycle between nested models. |
| `MQ3008` | Class model has no no-arg constructor visible from its package. |
| `MQ3009` | Record component is primitive and not `@PrimaryKey`. |
| `MQ3010` | Record has only a non-canonical constructor, or is generic. |
| `MQ3011` | `@FilterColumn` path does not resolve, or crosses a collection with no explicit `joinType`. |
| `MQ3012` | Two `@FilterColumn`s with the same `alias` and path prefix but different `joinType`. |
| `MQ3013` | `@FilterColumn` name clashes with a generated constant, is reserved, or is not a legal Java name. |
| `MQ3014` | `converter` is not a `ColumnConverter` between the field type and the attribute type. |
| `MQ3015` | Two generated constants would have the same name, or a constant clashes with a reserved one. |
| `MQ3016` | A warning, not an error: a column on a to-one association selects the whole entity and has no converter. |
| `MQ3017` | The model's `root`, or the root of a model it nests, is not a class, and no annotation processor generated it. A root another processor generates is fine: the model waits for it. |
| `MQ3018` | A `@Computed` or `@Aggregate(expression)` class is not a usable `ExpressionDefinition<Model, FieldType>`: its type arguments are not the model and the field's boxed type, or it has neither a public `INSTANCE` nor a visible no-arg constructor. |
| `MQ3019` | `@Computed` combined with `@PrimaryKey`, `@Column`, `@Join`, `@Child`, `@Aggregate` or `@Transient`, or on a primitive field. |
| `MQ3020` | `@Selected` field that is not exactly `SelectSet<Model>`, or a second `@Selected` field in the model. |
| `MQ3021` | `@Selected` combined with another field annotation of the library. |
| `MQ3022` | Warning: a `@FilterColumn` whose key in `fields()` a mapped column, a `@Computed` field or another `@FilterColumn` already holds is left out of `fields()` and stays a constant. |
| `MQ3201` | Aggregate model: field is primitive. |
| `MQ3202` | Aggregate model: field type does not match the function's result type. |
| `MQ3203` | Aggregate model: no `@GroupBy` field and is not `singleGroup`. |
| `MQ3204` | Aggregate model: `@GroupBy` combined with `@Aggregate` or `@Join`. |
| `MQ3205` | Aggregate model: `@Aggregate(fn = SUM)` over a 32-bit attribute. |
| `MQ3206` | Aggregate model: `@Aggregate(distinct = true)` on `SUM`, `AVG`, `MIN` or `MAX`. |
| `MQ3207` | Aggregate model: `@QueryModel(singleGroup = true)` combined with `@GroupBy` fields. |
| `MQ3208` | Aggregate model: `@Aggregate` takes `attribute` or `expression`, not both. |
| `MQ3301` | Update model: field maps through a join or a collection. |
| `MQ3302` | Update model: `@Join`, `@Aggregate`, `@GroupBy`, `@Computed` or `@Selected` not allowed. |
| `MQ3303` | Update model: field maps to the primary key or `@Version` attribute. |
| `MQ3304` | Update model: field maps to an attribute that can't be written. |
| `MQ3305` | Update model: to-one attribute written by id with the wrong id type. |
| `MQ3306` | Update model: `@PrimaryKey` is not the root entity's id. |
| `MQ3307` | Update model: field generates a change-set member that clashes with `Changes<M>`. |
| `MQ3401` | `@Child` field is not a `List` or `Optional` of a `@QueryModel`, carries `@Join`, `@Transient`, `@Aggregate` or `@GroupBy`, is on an update model, or sets both `through` and `foreignKey`. |
| `MQ3402` | `@Child` `key` or `foreignKey` names no attribute of its root, names an association rather than one of its attributes, or crosses a collection in `key`. A `foreignKey` may cross one, for a many-to-many child. |
| `MQ3403` | `@Child` `key` and `foreignKey` attributes have different types. |
| `MQ3404` | `@Child` key of several attributes, an embedded value or an array: it takes one attribute each side. |
| `MQ3405` | A `List` `@Child` without `foreignKey` (unless it has `through`), or whose model has no `@PrimaryKey`; or an `Optional` `@Child` whose `through` crosses a collection, on a model with no `@PrimaryKey`. |
| `MQ3406` | `@Child(through)` whose path is blank, crosses something other than an association, or ends at another type than the child model's root; whose `key` is not the parent root's single `@Id`; or whose child model is grouped. |
| `MQ3501` | Insert model: names a generated id, or does not name all of an id that has no generator with `@PrimaryKey`. |
| `MQ3502` | Insert model: `@Join`, `@FilterColumn`, `@Aggregate`, `@GroupBy`, `@Computed`, `@Child`, `@Selected` or `@Transient` field. |
| `MQ3503` | A type carries more than one of `@QueryModel`, `@UpdateModel` and `@InsertModel`. To read and create one row shape with one model, use [`@QueryModel(generateInserts = true)`](models.md#one-model-for-reading-and-creating). |
| `MQ3504` | Warning: the insert model's root shows the processor no id type, so the generated `insert` and `persist` type their keys as `Object`. |

See [Models and QModels](models.md).

## `MQ4xxx`: configuration

| Code | What went wrong |
|---|---|
| `MQ4001` | `modelquery.vendor` names an unknown vendor. |
| `MQ4002` | Two vendor profiles are registered for the same vendor with no precedence rule. |
| `MQ4003` | A property value, or its `ModelQueryConfig` setting, is out of range. |
| `MQ4004` | `commitEachChunk()` with no `ChunkTransactions` that serves the write's `EntityManagerFactory`. |
| `MQ4005` | `modelquery.vendor` set with more than one `EntityManagerFactory` and no `ModelQueryConfigurer`. |
| `MQ4006` | A `ModelQueryConfig` bean of your own drops a `VendorProfile`, `WriteAssignment` or `ChunkTransactions` bean, or a set `modelquery.*` property. |
| `MQ4007` | A repository declares `ModelQueryRepository` of an entity other than its domain type. |
| `MQ4009` | A bulk insert on a factory whose persistence provider has no insert support, before any statement. `persist` still runs. |

See [Spring Data and the starter](spring.md) and [Vendor notes](vendors.md).

## Logging

model-query logs through `System.Logger`, which reaches SLF4J, Logback or Log4j through their `System.Logger`
bridges, and `java.util.logging` without one (where `DEBUG` is `FINE` and `TRACE` is `FINER`).

| Logger | Level | What it logs |
|---|---|---|
| `com.rey.modelquery.core.ModelQuery` | `DEBUG` | Each query definition `build()` returns: model, entity, selected fields, primary key, filters (kind and column, each value shown as `?`), group-by, order and paging mode. |
| `com.rey.modelquery.jpa.DefaultModelQueryExecutor` | `DEBUG` | Each `list`, `stream`, `page`, `count`, `export`, `update` and `delete` call, with its limit, page or export options; a write also lists how it chose its rows (the number of keys or all rows) and its filters, each value shown as `?`, and how it runs. |
| `com.rey.modelquery.jpa.DefaultModelQueryExecutor` | `TRACE` | Each statement's bind count against the vendor's limit, then its rows read or written and the time it took. |

```
DEBUG com.rey.modelquery.core.ModelQuery - built OrderView over OrderEntity: select [id, status, total], primaryKey [id], where [EQ(OrderView.status, ?), GT(OrderView.total, ?)], orderBy [total DESC NULLS LAST], paging keyset
DEBUG com.rey.modelquery.jpa.DefaultModelQueryExecutor - page OrderView: offset 100 size 50, NO_COUNT
DEBUG com.rey.modelquery.jpa.DefaultModelQueryExecutor - update OrderEntity (where [EQ(OrderView.status, ?)]): direct
TRACE com.rey.modelquery.jpa.DefaultModelQueryExecutor - OrderView: statement binds 3 of 65535
TRACE com.rey.modelquery.jpa.DefaultModelQueryExecutor - OrderView: 51 rows in 12 ms
```

Filter values (the build log shows each as `?`), bind values and keyset cursors are never logged, since they often hold personal data, and neither is
the statement text. Turn on your provider's SQL and bind logging for those, for example Hibernate's `org.hibernate.SQL`
and `org.hibernate.orm.jdbc.bind`. With Spring Boot:

```properties
logging.level.com.rey.modelquery=DEBUG
logging.level.com.rey.modelquery.jpa.DefaultModelQueryExecutor=TRACE
```

This works with `spring-boot-starter-logging`, Spring Boot's default: its `jul-to-slf4j` bridge carries the records to
Logback, and Spring Boot keeps the `java.util.logging` levels in step with Logback's, at startup and on every later
change, such as a Spring Cloud refresh or the `loggers` endpoint. Through that bridge, a `TRACE` line prints as
`DEBUG`.

If you exclude `spring-boot-starter-logging` and add Logback yourself, nothing carries the records to it, and anything
below `INFO` is dropped. Add `org.slf4j:slf4j-jdk-platform-logging`, which Spring Boot manages: it sends `System.Logger`
straight to SLF4J, keeps `TRACE` as `TRACE`, and follows level changes, since SLF4J reads them from Logback each time.

## Reporting a problem

If you meet a failure without a code, that is a bug: please report it with the stack trace and the model involved.
