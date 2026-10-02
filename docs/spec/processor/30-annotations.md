# 30 — Annotations

**Covers:** every annotation in `model-query-annotations` and what it means for the generated `QModel`.
**Read when:** adding an annotation or an attribute, or deciding which annotation a use case needs.
**Owns:** `R-PROC-*`, `AC-PROC-*`. What gets generated is `processor/31`; the checks are `processor/32`.

---

## 1. Catalog

| Annotation | Target | Purpose |
|---|---|---|
| `@QueryModel(root = X.class, generateSelectSets = true, prefix = "Q", suffix = "", singleGroup = false, generateChanges = false)` | model class or record | Enables generation |
| `@UpdateModel(root = X.class, prefix = "Q")` | class or record | The attributes a bulk update may write; generates columns and a change set (§7) |
| `@PrimaryKey` | field or record component | Primary-key column(s); composite keys supported |
| `@Column(attribute = "...", converter = Foo.class)` | field or component | Rename the attribute or convert the value (`ColumnConverter<C, F>`) |
| `@Join(attribute = "...", type = LEFT, prefix = "CUSTOMER", alias = "")` | `Optional<NestedModel>` field or component | Join the association and reuse the nested model's QModel columns |
| `@Child(key = {}, foreignKey = {})` | `List<ChildModel>` or `Optional<ChildModel>` field or component | Filled from another model's rows when a fetch plan names it (`api/15`) |
| `@FilterColumn(name = "...", path = "...", joinType = LEFT, alias = "", converter = Foo.class)` | model type (repeatable) | A filter-only column: a `ColumnField` constant with no model field, left out of every generated `SelectSet` and of `map(Row)` |
| `@FilterColumns` | model type | The container that makes `@FilterColumn` repeatable; never written by hand |
| `@Aggregate(fn = SUM, attribute = "...", distinct = false)` | field or component | An `AggregateField` constant, mapped into this field (`api/13`) |
| `@GroupBy` | field or component | The column joins the generated `GROUP_KEYS` set and the query's group-by |
| `@ExcludeFromDefaults` | field or component | Leave the column out of `DEFAULT` (heavy BLOB/TEXT columns) |
| `@Transient` | field or component | Not a column |

**R-PROC-01** The annotations module has no dependencies beyond the JDK (INV-7), so a model can be annotated in a module
that does not depend on JPA or on the engine. Join types and aggregate functions are therefore its own enums, `JoinKind`
(`INNER`, `LEFT`) and `AggregateFunction`, not `jakarta.persistence.criteria.JoinType`.

**R-PROC-02** Every annotation is `RetentionPolicy.CLASS`: Gradle's incremental annotation processing reads only
`CLASS` and `RUNTIME` annotations (`processor/31` R-GEN-05, D-39), and tooling can find generated pairs. None is
`RUNTIME`, so nothing is read reflectively.

## 2. `@QueryModel`

**R-PROC-03** `root` is the JPA entity the model reads from. It may be in the same compilation or on the classpath
(`processor/31` R-GEN-01).

**R-PROC-04** `prefix` and `suffix` (also settable as `-Amodelquery.prefix=` / `-Amodelquery.suffix=`) name the
generated class. A value written on the annotation wins over the option, which wins over the default (D-44). A
project also using Querydsl on the same classes should change one of them, although a collision is
unlikely: Querydsl generates for entities, this processor generates for models. A nested model read from the
classpath (D-45) is named by its own annotation, else by the option of the compilation that reads it, not of the one
that generated it; so a module built with a different `-Amodelquery.prefix` or `-Amodelquery.suffix` from its
consumers' ends in javac's missing-class error. The user guide says to keep one value across modules, or to set it on
the nested model's annotation.

**R-PROC-05** `singleGroup = true` marks a model that has `@Aggregate` fields and deliberately no `@GroupBy` field — a
whole-table total. Without it, that combination is a diagnostic (`processor/32` `MQ3203`). `singleGroup = true` on a
model that has `@GroupBy` fields is `MQ3207` (D-47).

## 3. `@Column` and converters

**R-PROC-06** `attribute` is a dotted path from `root` only when it stays inside `@Embedded` or `@EmbeddedId` values
(`api/10` R-COL-08, D-41); crossing an association needs `@Join` or `@FilterColumn`.

**R-PROC-07** A `ColumnConverter<C, F>` converts between the model type `C` and the entity attribute type `F`. It must
be stateless and have a public static `INSTANCE` field or a visible no-arg constructor, else `MQ3014`. `converter` is
declared as `Class<?>`, default `void.class`, because `ColumnConverter` is a `core` type (R-PROC-01, D-37). The
generated column carries the converter: `Row.get` applies it, and filters apply it in the other direction, so a
converter that is not a bijection is documented as filter-unsafe (`api/10` R-COL-14). When no `converter` is named and
the field is a `java.time.Instant` or `java.util.Date` over a `java.sql.Timestamp` attribute, the column takes the
built-in `InstantTimestampConverter` or `DateTimestampConverter` instead of failing `MQ3002`; a named converter wins,
and any other mismatch is still `MQ3002` (D-84). A generated column is declared `OrderedColumnField` when it has no
converter or its converter, named or built-in, is an `OrderedColumnConverter`, and `ColumnField` otherwise, following
nested-model joins (D-93).

## 4. `@Join` and nested models

**R-PROC-08** A `@Join` field or component is always `Optional<NestedModel>`. The generated mapper assigns it on every
row, so a mapped model never holds `null` there; a class model that is also built by hand should initialise the field
to `Optional.empty()`, which the processor can't check (D-45). Semantics — empty means "no data", presence follows the joined primary key — are `processor/31` §4.

**R-PROC-09** Two `@Join`s on the same attribute get the field name as their alias automatically, so they become two
joins (`api/10` R-COL-03); an `alias` written on the annotation wins (D-45).

**R-PROC-20** `@Child` marks a `List<ChildModel>` or `Optional<ChildModel>` field that a fetch plan fills from another
`@QueryModel`'s rows (`api/15` R-FCH-03). `key` and `foreignKey` are `String[]` of one attribute path each, on the
model's root and on the child's root; left empty, each is that model's one `@PrimaryKey` attribute. A path may cross
associations, each joined `LEFT` and without alias so that a `@Join` on the same association shares the join, and
embedded values, but not an association inside an embedded value. The field carries no other model annotation, and
an update model has none (`processor/32` `MQ3401`–`MQ3405`). The child model may be a source of the compilation or a
class on its classpath, as a nested model may (D-45).

## 5. `@FilterColumn`

**R-PROC-10** `path` is a dotted attribute path from `root` (`"deleted"`, `"customer.country"`,
`"customer.address.city"`), validated like any other attribute. The constant's type is the entity attribute's type, or
the converter's model type.

**R-PROC-11** Joins are shared: when a path prefix matches a `@Join`, that `TableField` is reused, so filtering and
selecting the same association never joins it twice; of several `@Join`s on one attribute, a path with no `alias`
reuses the first declared. Any other association on the path gets its own generated `TableField` (`<PREFIX>_TABLE`)
with `joinType`, default `LEFT`. A reused join keeps its `@Join`'s type: a `joinType` written on the filter column
that differs from it is `MQ3012`, and one left out never conflicts (D-46).

**R-PROC-12** `alias` puts the whole path on a separate join. Filter columns sharing an alias share that join. An
`alias` equal to the alias of a `@Join` on the path's first association, written or automatic (R-PROC-09), selects
that `@Join`'s join instead of making another (D-46). Join `ON` conditions cannot be expressed in an annotation,
because they are lambdas: declare that `TableField` by hand, or use `Filters.exists` with an inner group (`api/12`
§7).

**R-PROC-13** Every collection association on the root also gets a generated `TableField` constant (`ITEMS_TABLE`), so
`Filters.exists(QOrderView.ITEMS_TABLE, …)` works with no hand-written join. The constant is always `LEFT`: a filter
path through the collection with no `alias` reuses it when its `joinType` is `LEFT`, and with `INNER` gets a table of
its own, `ITEMS_INNER_TABLE` under the alias `itemsInner` (D-46).

**R-PROC-14** A filter-only column may be used in `orderBy`. With keyset paging the engine selects it automatically
(`engine/21` R-PAG-04).

## 6. `@Aggregate` and `@GroupBy`

**R-PROC-15** `@Aggregate` generates one `AggregateField` constant and maps it into its field. `fn` is `COUNT`, `SUM`,
`AVG`, `MIN` or `MAX`; `attribute` is omitted for `COUNT` over the root; `distinct = true` yields `countDistinct`
and is `MQ3206` on any other function. An `@Aggregate` field can't also carry `@PrimaryKey`, `@Column`, `@Join` or
`@Transient` (`MQ3204`), and a model with an `@Aggregate` field can't be a `@Join` target (`MQ3005`) (D-47). A `MIN`
or `MAX` field of `Instant` or `Date` over a `Timestamp` attribute reads it through the built-in converter of R-PROC-07
and is typed as the field; `SUM` and `AVG` over a `Timestamp` stay `MQ3202`, as does `MIN` or `MAX` into any other
type than the attribute's (D-84). The column a `MIN`, `MAX` or `distinct` `COUNT` reads is the `OrderedColumnField` of
R-PROC-07 (D-93).

**R-PROC-16** `@GroupBy` fields, in declaration order, form the generated `GROUP_KEYS` `SelectSet`, and
`Q<Model>.query()` is pre-configured with `groupBy(GROUP_KEYS)`. `@GroupBy` cannot be combined with `@Aggregate` or
`@Join` (`processor/32` `MQ3204`).

**R-PROC-17** Aggregates are in no generated `SelectSet` (`api/13` R-AGG-12).

## 7. Update models

**R-PROC-18** `@UpdateModel(root = …)` declares the root-entity attributes a bulk update may write (`api/14` §2). The
type is only read by the processor and never instantiated. It accepts `@PrimaryKey`, `@Column` (including a to-one
association written by id, `@Column(attribute = "customer") Long customerId`) and `@FilterColumn`, and rejects `@Join`,
`@Aggregate` and `@GroupBy` (`processor/32` §1).

**R-PROC-19** `@QueryModel(generateChanges = true)` also generates a change set over the query model's root, non-key
columns; joined and filter-only columns are left out. It is meant for internal use: an endpoint binding it from a
request can write every root column of the model, so the user guide recommends one `@UpdateModel` per endpoint.

## 8. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-PROC-01 | `model-query-annotations` has no non-JDK dependency and compiles on a bare JDK 17 (R-PROC-01). |
| AC-PROC-02 | A model whose `root` is only on the classpath generates correctly (R-PROC-03). |
| AC-PROC-03 | `-Amodelquery.prefix=X` renames the generated class and every reference in it (R-PROC-04). |
| AC-PROC-04 | `@Column(converter = …)` round-trips through `Row.get` and through a filter on the same column (R-PROC-07). |
| AC-PROC-05 | Two `@Join`s on one attribute produce two joins with distinct aliases (R-PROC-09). |
| AC-PROC-06 | A `@FilterColumn` path sharing a prefix with a `@Join` reuses that join; a different path creates its own (R-PROC-11). |
| AC-PROC-07 | `@FilterColumn`s sharing an alias share one join; different aliases do not (R-PROC-12). |
| AC-PROC-08 | Every collection association on the root has a generated `TableField` usable in `exists` (R-PROC-13). |
| AC-PROC-09 | `@Aggregate`/`@GroupBy` on a summary model generate `GROUP_KEYS` and a pre-configured `query()` (R-PROC-15, R-PROC-16). |
| AC-PROC-10 | `singleGroup = true` suppresses `MQ3203`; omitting it raises it (R-PROC-05). |
| AC-PROC-11 | An `Instant` or `Date` field over a `Timestamp` attribute with no `converter` takes the built-in converter, filters with an `Optional` of its own type and reads back the `Timestamp` itself as a `Date`; a named converter wins and any other mismatch is `MQ3002` (R-PROC-07, D-84). |
| AC-PROC-12 | `@Aggregate` `MIN` or `MAX` into an `Instant` or `Date` field over a `Timestamp` attribute reads the database's value through the built-in converter, typed as the field; `COUNT` distinct over it stays `Long`, and `SUM`, `AVG` or a `MIN` into another type is `MQ3202` (R-PROC-15, D-84). |
