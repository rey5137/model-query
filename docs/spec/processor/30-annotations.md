# 30 — Annotations

**Covers:** every annotation in `model-query-annotations` and what it means for the generated `QModel`.
**Read when:** adding an annotation or an attribute, or deciding which annotation a use case needs.
**Owns:** `R-PROC-*`, `AC-PROC-*`. What gets generated is `processor/31`; the checks are `processor/32`.

---

## 1. Catalog

| Annotation | Target | Purpose |
|---|---|---|
| `@QueryModel(root = X.class, generateSelectSets = true, prefix = "Q", suffix = "", singleGroup = false, generateChanges = false, generateInserts = false)` | model class or record | Enables generation |
| `@SourceModel(root = X.class, shape = void.class)` | class or record | A query read by field name; generates `source()`, and `query(SourceQuery<S>)` when not a branch. A `shape` makes it a branch of that shape's set operations (§10, R-PROC-35, R-PROC-38) |
| `@UpdateModel(root = X.class, prefix = "Q")` | class or record | The attributes a bulk update may write; generates columns and a change set (§7) |
| `@InsertModel(root = X.class, prefix = "Q")` | class or record | The attributes an insert writes, one instance per row; generates columns, `INSERT_COLUMNS` and the insert builders (§8) |
| `@PrimaryKey` | field or record component | Primary-key column(s); composite keys supported |
| `@Column(attribute = "...", converter = Foo.class, treat = void.class)` | field or component | Rename the attribute or convert the value (`ColumnConverter<C, F>`) |
| `@Join(attribute = "...", type = LEFT, prefix = "CUSTOMER", alias = "", treat = void.class)` | `Optional<NestedModel>` field or component | Join the association and reuse the nested model's QModel columns |
| `@Child(key = {}, foreignKey = {})` | `List<ChildModel>` or `Optional<ChildModel>` field or component | Filled from another model's rows when a fetch plan names it (`api/15`) |
| `@FilterColumn(name = "...", path = "...", joinType = LEFT, alias = "", converter = Foo.class, treat = void.class)` | model type (repeatable) | A filter-only column: a `ColumnField` constant with no model field, left out of every generated `SelectSet` and of `map(Row)` |
| `@FilterColumns` | model type | The container that makes `@FilterColumn` repeatable; never written by hand |
| `@Aggregate(fn = SUM, attribute = "...", expression = Def.class, distinct = false)` | field or component | An `AggregateField` constant, mapped into this field (`api/13`); `expression` aggregates a `@Computed`-style definition instead of an attribute |
| `@Computed(Def.class)` | field or component | An `ExpressionField` constant, mapped into this field (`api/10` §3a); a bound model may leave the class to its binding's `@BindExpression` (R-PROC-29) |
| `@Window(function = ROW_NUMBER, argument = "", partitionBy = {}, orderBy = {}, frame = NONE)` | field or component of a `@SourceModel` | A `WindowField<S, T>` constant, a window function over the source's rows (`api/17` R-SRC-04, R-PROC-36) |
| `@Discriminator("REFUND")` | field or component of a `@SourceModel` | The constant a branch fixes its shape's discriminator column to; part of the key (`api/17` R-SRC-06, R-PROC-38) |
| `@Subtype` | field or component | The exact entity class as `Class<? extends R>`, through `Expr.typeOf(ROOT)` (R-PROC-43, D-154) |
| `@Depth` | field or component of a `@SourceModel` | The level the recursive CTE assigns a row, one `Integer`; makes the model recursive (`api/17` R-SRC-07, R-PROC-37) |
| `@GroupBy` | field or component | The column joins the generated `GROUP_KEYS` set and the query's group-by |
| `@ExcludeFromDefaults` | field or component | Leave the column out of `DEFAULT` (heavy BLOB/TEXT columns) |
| `@Transient` | field or component | Not a column |
| `@ExcludeFromInserts` | field or component | Leave the column out of the inserts `generateInserts` generates (R-PROC-27) |
| `@Selected` | field or component | The one `SelectSet<Model>` the mapper fills with the columns the row selected (§6, R-PROC-25) |
| `@BindQueryModel(model = M.class, root = X.class, generateSelectSets = true, prefix = "Q", suffix = "", singleGroup = false, generateChanges = false, generateInserts = false, expressions = {})` | type or `package-info` (repeatable) | Binds a query model compiled without its entity; its QModel is generated in the binding's package (§9, R-PROC-28) |
| `@BindQueryModels` | type or `package-info` | The container that makes `@BindQueryModel` repeatable; never written by hand |
| `@BindUpdateModel(model = M.class, root = X.class, prefix = "Q")` | type or `package-info` (repeatable) | Binds an update model compiled without its entity; its columns and change set are generated in the binding's package (§9, R-PROC-28) |
| `@BindUpdateModels` | type or `package-info` | The container that makes `@BindUpdateModel` repeatable; never written by hand |
| `@BindInsertModel(model = M.class, root = X.class, prefix = "Q")` | type or `package-info` (repeatable) | Binds an insert model compiled without its entity; its QModel is generated in the binding's package (§9, R-PROC-28) |
| `@BindInsertModels` | type or `package-info` | The container that makes `@BindInsertModel` repeatable; never written by hand |
| `@BindExpression(field = "...", value = Def.class)` | inside `@BindQueryModel(expressions)` | The definition of a bound model's valueless `@Computed`, or of an `@Aggregate` that aggregates an expression (§9, R-PROC-29) |

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

**R-PROC-41** `@Column`, `@FilterColumn` and `@Join` declare an `@Incubating Class<?> treat()` member, default
`void.class` for none (R-PROC-01, D-154). It names a strict `@Entity` subtype of the model's root: a non-entity,
`@MappedSuperclass`, embeddable, unrelated type or the root itself is `MQ3042`. The processor reads its hierarchy in
source or on the classpath. A column's attribute (including an embedded path), a filter path's first segment, or a
join's association is read from that subtype; a missing attribute is `MQ3001`, naming the subtype. An attribute
already declared by the root's hierarchy is `MQ3042`, directing the caller to drop `treat`. The usual type and
converter checks (`MQ3002`, `MQ3014`) apply. A treated field cannot be a `@PrimaryKey`, a `@Child`'s parent-side key,
a `@Discriminator`, or part of a source-rooted, update or insert model (`MQ3042`). A model declaring a treated or
`@Subtype` field cannot set `generateChanges` or `generateInserts` (`MQ3042`); the field is never silently dropped
from a write (Q-23).

**R-PROC-43** `@Subtype` is an incubating, class-retained field or record-component annotation marking one
`Class<? extends R>` field, where `R` is the model's root (D-154, Q-25). It maps `Expr.typeOf(ROOT)` (R-GEN-42).
A second field, another type, combination with `@Column`, `@Computed`, `@Aggregate` or `@PrimaryKey`, or use on a
source-rooted or write model is `MQ3043`. `@GroupBy @Subtype` is allowed.

## 4. `@Join` and nested models

**R-PROC-08** A `@Join` field or component is always `Optional<NestedModel>`. The generated mapper assigns it on every
row, so a mapped model never holds `null` there; a class model that is also built by hand should initialise the field
to `Optional.empty()`, which the processor can't check (D-45). Semantics — empty means "no data", presence follows the joined primary key — are `processor/31` §4. The nested root follows R-PROC-42 (D-154).

**R-PROC-42** A `@Join` nested model may be rooted at the association's target, a subtype or a supertype of it
(D-154). A subtype generates a downcast join with the nested presence key on the treated join; LEFT is empty for a
sibling or absent target, and INNER drops those parents. A supertype uses a plain join and reads inherited
attributes. Each root-level `treat` the nested model declares through `@Column`, `@FilterColumn` or `@Join` must also
be a subtype or supertype of the association's target, or the target itself. A sibling target is `MQ3003`, naming
the treated field. The check applies at every nested `@Join` level against that association's own target. An
unrelated nested root is `MQ3003`. An owner's `@Join(treat = ...)` and a nested subtype compose: the former reads
the association from the owner's subtype, the latter downcasts its target.

**R-PROC-09** Two `@Join`s on the same attribute get the field name as their alias automatically, so they become two
joins (`api/10` R-COL-03); an `alias` written on the annotation wins (D-45).

**R-PROC-20** `@Child` marks a `List<ChildModel>` or `Optional<ChildModel>` field that a fetch plan fills from another
`@QueryModel`'s rows (`api/15` R-FCH-03). `key` and `foreignKey` are `String[]` of one attribute path each, on the
model's root and on the child's root; left empty, each is that model's one `@PrimaryKey` attribute. A path may cross
associations, each joined `LEFT` and without alias so that a `@Join` on the same association shares the join, and
embedded values, but not an association inside an embedded value. The field carries no other model annotation, and
an update model has none (`processor/32` `MQ3401`–`MQ3406`). `through`, a path of associations from the
model's root to the child's root entity for a many-to-many mapped on the parent's side only, replaces `foreignKey`
(both is `MQ3401`); `key` is then matched on the parent's single `@Id` along that path, and a path or key that
cannot be is `MQ3406` (`api/15` R-FCH-14). The child model may be a source of the compilation or a
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
`@Join` (`processor/32` `MQ3204`). It may mark a `@Computed` field.

**R-PROC-17** Aggregates are in no generated `SelectSet` (`api/13` R-AGG-12), except in a source model's `ALL`, which
is the projection of its CTE body (R-GEN-37).

**R-PROC-21** *(D-115)* **`@Computed`.**
- `value` names a class implementing `ExpressionDefinition<M, C>`, with a public static `INSTANCE` or a visible no-arg
  constructor. It is declared `Class<?>` because the type is in core (R-PROC-01); otherwise `MQ3018`.
  `ExpressionDefinition<M, C>` (core, `@Incubating`) has one method, `ExpressionField<M, C> expression()`, where `M`
  is the model and `C` the field's type.
- The generated constant is `value.expression().named(field)`, emitted in declaration order after the root, joined and
  filter-only column constants and after the `@Computed` constants declared before it (`processor/31` R-GEN-27). The
  definition builds its expression inside `expression()`, may read any `Q<Model>` constant declared earlier in that
  order, and never caches it in a static field of its own. It is mapped like a column and is in `DEFAULT` and `ALL`
  unless `@ExcludeFromDefaults`.
- It cannot be combined with `@PrimaryKey`, `@Column`, `@Join`, `@Child`, `@Aggregate` or `@Transient`, nor be
  primitive (`MQ3019`). A model with one cannot be a `@Join` target (`MQ3005`).

**R-PROC-22** *(D-115)* `@Aggregate(expression = Def.class)` aggregates the definition's expression, emitted after
every other constant of the model so the definition may read any of them (`processor/31` R-GEN-27). `attribute` and
`expression` together are `MQ3208`. `MQ3202`, `MQ3205` and `MQ3206` read the expression's type. A correlated
expression is `MQ1510` when the generated constant initializes; declare it through `@Computed` instead (R-COL-33, D-150).

**R-PROC-25** *(D-120)* **`@Selected`.** A `@QueryModel` may declare one field or record component marked `@Selected` (no
members, `@Incubating`), typed exactly `SelectSet<M>` with `M` the model itself; the generated mapper fills it with the
columns, expressions and aggregates the row selected (`processor/31` R-GEN-29, R-GEN-30). It is no column: it has no
constant, is in no `SelectSet` and no key, and a record keeps its place in the canonical constructor. The processor
checks the type by its qualified name, `com.rey.modelquery.core.SelectSet`, since the annotations module does not depend
on `core` (R-PROC-01). A type that is not exactly `SelectSet<M>`, or a second `@Selected` field, is `MQ3020`. It cannot be
combined with `@PrimaryKey`, `@Column`, `@Join`, `@Child`, `@Aggregate`, `@GroupBy`, `@Computed`, `@ExcludeFromDefaults` or
`@Transient` (`MQ3021`). An `@UpdateModel` or `@InsertModel` refuses it (`MQ3302`, `MQ3502`).

## 7. Update models

**R-PROC-18** `@UpdateModel(root = …)` declares the root-entity attributes a bulk update may write (`api/14` §2). The
type is only read by the processor and never instantiated. It accepts `@PrimaryKey`, `@Column` (including a to-one
association written by id, `@Column(attribute = "customer") Long customerId`) and `@FilterColumn`, and rejects `@Join`,
`@Aggregate` and `@GroupBy` (`processor/32` §1).

**R-PROC-19** `@QueryModel(generateChanges = true)` also generates a change set over the query model's root, non-key
columns; joined and filter-only columns and the whole-entity to-one columns are left out, as `generateInserts`
leaves them (D-124); a to-one column of the target's id (R-PROC-33) is written by id (D-136), unless the to-one is
`@MapsId`, whose foreign key is the id column and is left out as in an insert (R-PROC-26). It is meant
for internal use: an endpoint binding it from a request can write every root column of the model, so the user guide
recommends one `@UpdateModel` per endpoint.

## 8. Insert models

**R-PROC-23** *(D-116, D-117)* `@InsertModel(root = …)` declares the root-entity attributes an insert writes
(`api/14` §10.2). Unlike an update model it is instantiated: each instance is one row, and every column field is a
column of it, read through its record accessor or getter. It accepts `@PrimaryKey` and `@Column`, including a to-one
association written by id (`@Column(attribute = "customer") Long customerId`) and a dotted embedded path, and rejects
`@Join`, `@FilterColumn`, `@Aggregate`, `@GroupBy`, `@Computed` and `@Child` (`MQ3502`): an insert reads no row of its
root. `@Transient` is `MQ3502` too, since every field is a column of each row (`api/14` R-WRT-25). Its columns take
an update model's write checks (`MQ3301`, `MQ3305`, the `@Version` part of `MQ3303`), and `MQ3304` for an attribute an
insert cannot write: `insertable = false`, or the inverse (`mappedBy`) side of a to-one. `updatable = false` does not
apply, since an insert writes the column first.
- The id. When the root's `@Id` carries `@GeneratedValue` or a generator annotation (one meta-annotated
  `@IdGeneratorType` or `@ValueGenerationType`), the model leaves it out; when it carries none, the model
  names all of it with `@PrimaryKey`, as the id attribute, the `@IdClass` attributes or the `@EmbeddedId` (whole or by
  its components). Anything else the annotations show is `MQ3501`. A generator declared in `orm.xml` is seen on the
  definition's first execution instead (`MQ1802`, `api/14` R-WRT-26).
- The processor generates `Q<Model>` with one column constant per column field, `INSERT_COLUMNS` in declaration order,
  and the `insert`, `insertFrom` and `persist` builders (`processor/31` §7). A model whose root shows no id type is
  generated with keys typed `Object`, and warned (`MQ3504`).

**R-PROC-24** *(D-116)* One type carries at most one of `@QueryModel`, `@UpdateModel` and `@InsertModel`, since each
generates `Q<Model>`. A type with two or more is reported once (`MQ3503`) and generates nothing.

**R-PROC-26** *(D-123)* `@QueryModel(generateInserts = true)` also generates `INSERT_COLUMNS`, `insert`,
`insertFrom` and `persist` on the query model, as an insert model does (`processor/31` R-GEN-34), so a screen that
reads and creates one row shape keeps one model. It writes the model's **root columns**: fields whose attribute path is
a root attribute or a dotted embedded path in it. It leaves out, without a diagnostic:
- `@Join`, `@Child`, `@Computed`, `@Selected` and `@Transient` fields, and the `@FilterColumn`s;
- a to-one column that holds the whole entity (`MQ3016`) or a converter's value of it: the flag writes no foreign
  key from it (D-124), and the Javadoc names it. A to-one column of the target's id (R-PROC-33) is written by id, as an
  `@InsertModel` writes it, its `insertable = false`, inverse-side and `@MapsId` cases left out as below, since a
  `@MapsId` foreign key is the id column (D-136);
- the id when it carries `@GeneratedValue` or a generator annotation, as an insert model must (R-PROC-23);
- the `@Version` column, and one an insert cannot write (`insertable = false`, or the inverse side of a to-one), which
  an insert model would refuse (`MQ3303`, `MQ3304`). This is unlike `generateChanges`, which keeps them and relies on
  `MQ1605` (D-70);
- a non-id root column whose attribute carries `org.hibernate.annotations.Generated` (not `jakarta.annotation.Generated`
  or `javax.annotation.processing.Generated`) with `writable = false`, an empty `sql`, and `INSERT` among its events:
  its `value` when present and not `INSERT` (Hibernate 6), otherwise its `event` (D-125). The database fills it. The
  processor reads the annotation by its full name and its values with `Elements.getElementValuesWithDefaults`, reading
  enum constants by simple name; a missing `value` (Hibernate 7) counts as `INSERT`, and `GenerationTime.ALWAYS` has
  `INSERT` among its events. When the annotation type doesn't resolve the column stays written, and `orm.xml` is not
  seen. **Every timestamp annotation stays written**, whatever its `source`: `@CreationTimestamp`, `@UpdateTimestamp`
  and `@CurrentTimestamp` make Hibernate write the value in its own INSERT, but a Hibernate SQM insert applies only the
  id generator and the version seed, so leaving a timestamp out would write `NULL` or fail `NOT NULL`; a model that
  wants the server's time excludes the field (R-PROC-27) and sets it on the write (AC-WRT-40). An `@InsertModel` is
  unchanged: it names its columns.

The generated `INSERT_COLUMNS` Javadoc names each field and filter column left out and why, so a missing column is
visible in the IDE. `MQ3505` refuses the flag on a grouped model (`@Aggregate` or `@GroupBy` fields, or
`singleGroup = true`: a grouped row is not an entity row) and on one with no root column left to write, and is then its
only insert check. Otherwise `MQ3501` fires only when the root's id is assigned and the columns written, annotated `@PrimaryKey` or not,
do not cover all of it (an id left out above, a to-one `@MapsId` or `insertable = false` one, is not written);
a `@PrimaryKey` on another attribute is not `MQ3501`, since `addKey` follows the root's id (R-GEN-34), and a plain
column that writes the id leaves the read key as the model's `@PrimaryKey` fields, or none when it has none
(the id is never inferred as a read key). `MQ3504` warns when the root shows no id type, as on an insert model, and `INSERT_COLUMNS` is a reserved
constant name (`MQ3015`). `generateChanges` and `generateInserts` combine freely; `@QueryModel` and `@InsertModel` on
one type stay `MQ3503` (R-PROC-24). As with `generateChanges` (R-PROC-19), an endpoint that binds the model from a
request can write every listed column, so a public create endpoint keeps its own `@InsertModel`.

**R-PROC-27** *(D-125)* **`@ExcludeFromInserts`.** A field or record component of a `@QueryModel(generateInserts =
true)` marked `@ExcludeFromInserts` (`@Incubating`, `CLASS` retention, no elements; `model-query-annotations`) is left
out of `INSERT_COLUMNS`, `insertFrom`, `insert` and `persist`, so the database default applies under `insert` and the
no-arg constructor's value under `persist` (R-WRT-25: `NULL` unless the entity has a field initializer or
`@DynamicInsert`). The field stays a column of the query model and keeps its constant, so `insert(rows).set(CREATED_AT,
now)` is allowed (an excluded field's constant is no model column for AC-WRT-32); no constant is added, so no name is
reserved under `MQ3015`. The `INSERT_COLUMNS` Javadoc gives the reason "`@ExcludeFromInserts`". `MQ3506` (error): on a
field of a `@QueryModel` without `generateInserts = true`, or of an `@UpdateModel` or `@InsertModel`; or on a field
R-PROC-26 already leaves out (`@Join`, `@Child`, `@Computed`, `@Transient`, a whole-entity to-one, the `@Version`,
`insertable = false`, a generated id, or a `@Generated` column), since it would do nothing. On a `@Selected` field it is
`MQ3021` (every other annotation of the library), not `MQ3506`. On a grouped model `MQ3505` stays the only insert
check, so the "already left out" case does not fire there. Excluding an assigned id is `MQ3501` with its own message;
excluding every writable column is `MQ3505`. There is no `@ExcludeFromChanges` (R-PROC-19).

**R-PROC-32** *(D-135)* **`modelquery.jspecify`.** A boolean processor option, `false` unless set to `true`, read once
per compilation. On, the processor adds JSpecify nullness annotations to the classes it generates (`processor/31`
R-GEN-36); off, every generated class is byte-identical to a build without the option. The annotations are named by
string and the processor has no JSpecify dependency: the model module needs `org.jspecify:jspecify` on its compile
classpath. When the option is on and `org.jspecify.annotations.NullMarked` does not resolve, the processor reports
`MQ3027` once for the compilation, not once per model, and generates no annotation.

**R-PROC-33** *(D-136)* **A to-one read by its id.** A `@QueryModel` column `@Column(attribute = "referrer") Long
referrerId` over a to-one reads the foreign key. Its type, or its converter's attribute type (`ColumnConverter<Long,
CustomerId>`), must be the target's single basic id type, as the update model's spelling is (R-GEN-19); another type is
`MQ3002` naming the target's id type, and a converter that does not convert the id is `MQ3014`. The processor refuses
with `MQ3002` and the reason, reading the annotations by name (no Hibernate dependency, D-135): the inverse side
(`@OneToOne(mappedBy)`), a `@JoinColumn(referencedColumnName)` (the processor cannot tell it from the primary key's
column), `@JoinColumns`, `@JoinFormula` and `@JoinColumnOrFormula`, `@NotFound`, `@JoinTable`, a `@JoinColumn(table = "...")`
(the runtime answers `JOINS` for a key on another table), a target whose id is an association (derived identity,
`@MapsId`), a target with `@IdClass` or `@EmbeddedId`, and a to-one inside an embedded value. A target with
`@SQLRestriction` or `@Where` (on the class or a mapped superclass) is the separate error `MQ3029`: a read takes the id
from the `LEFT`-joined row, so a restricted target reads `null` while the foreign key holds a value, and
`generateChanges` with `from(row)` would write `NULL` over a live key; it holds until Q-20 resolves, and `MQ3016` does
not suggest the id form there. A runtime `@Filter` on the target is a documented caveat. The
dotted `referrer.id` stays `MQ3001`. A column of the whole entity, or a converter's value of it, is unchanged
(R-PROC-06, `MQ3016`). `@GroupBy` on a whole-entity to-one column is `MQ3028`. A `@JoinColumn(referencedColumnName)` is refused even when it
names the primary key's column; drop it, as the default already references the primary key. `generateChanges` and `generateInserts`
include the id form (R-PROC-19, R-PROC-26) and the generated `from` copies it (R-GEN-21). The id form reads through a
`LEFT` join at run time (`api/10` R-COL-29), a locked read of it is `MQ2211`.

## 9. Bound models

**R-PROC-28** *(D-134, D-141)* **`@BindQueryModel`, `@BindUpdateModel`, `@BindInsertModel`.** A model may live in a
module that sees no entity: it carries its field annotations (`@PrimaryKey`, `@Column`, `@Join`, `@Child`, `@Aggregate`,
`@GroupBy`, `@Selected`, `@Transient`, `@FilterColumn`, `@ExcludeFromDefaults`, meaning what they mean today) and no
`@QueryModel`, and a `@BindQueryModel(model = OrderView.class, root = OrderEntity.class)` (`@Incubating`, `CLASS`
retention, repeatable through `@BindQueryModels`, on a type or a `package-info`; `model-query-annotations`) in the
module that sees the entity binds it. Its other elements are `@QueryModel`'s `generateSelectSets`, `prefix`, `suffix`,
`singleGroup`, `generateChanges` and `generateInserts`, with the same defaults and meaning (R-PROC-03 to R-PROC-05,
R-PROC-19, R-PROC-26, D-44), and the `expressions` of R-PROC-29. `@BindUpdateModel(model, root, prefix)` and
`@BindInsertModel(model, root, prefix)` (each repeatable through `@BindUpdateModels` and `@BindInsertModels`, on a type
or a `package-info`) bind an update and an insert model the same way, their elements those of `@UpdateModel` and
`@InsertModel` (§7, §8). A model is bound at most once per kind (query, update, insert) in a compilation, and a bound
model carries none of `@QueryModel`, `@UpdateModel` and `@InsertModel`: `MQ3023` on each of its bindings of that kind,
and no QModel for them (an annotated model in the compilation still generates its own). A model may be bound once for
each kind. A binding that would generate a class of one qualified name (a QModel, or a change set) with another binding
in the compilation (in this or an earlier round, in any package), of the same model or of another, or with an annotated model in the compilation, is `MQ3023` on each
such binding, and none of those bindings generates anything, while the annotated model still generates its own; a prefix
or package keeps the names apart. A class of that name already on the classpath is left to the `Filer`'s error. Where
the generated classes go, and what must be public, is `processor/31` R-GEN-35.

**R-PROC-29** *(D-134, D-141)* **Expressions the model's module cannot compile.** `@Computed.value` defaults to
`void.class`, so a bound model may write `@Computed` with no class, and `@Aggregate` leaves `expression` at its default;
an `ExpressionDefinition` builds columns over the entity, which the model's module does not see. The binding supplies
it: `@BindQueryModel(..., expressions = @BindExpression(field = "net", value = RowNet.class))`. A `@BindExpression`
names a field or record component of the model and gives its `@Computed` that class; on a field with an `@Aggregate` and
no `@Computed` it makes the aggregate an expression aggregate over the class, as `expression = …` does on an annotated
model (R-PROC-16, R-PROC-22). Every check of the annotated forms then runs on it (`MQ3018`, `MQ3019`, `MQ3208`).
`MQ3025` is a valueless `@Computed` of a query model with no matching `@BindExpression`, or on an annotated model,
reported on the field; and, reported on the binding, a `@BindExpression` naming no field of the model, a field with
neither `@Computed` nor `@Aggregate`, a field whose annotation already names a class, or a field an earlier
`@BindExpression` names. An update or insert model refuses `@Computed` with or without a class (`MQ3302`, `MQ3502`).
Converters stay on the model (`@Column(converter = …)`): they convert the model type to the attribute type.

**R-PROC-30** *(D-140)* **A bound model is read from the classpath.** The model a binding names may be a compiled class
on the classpath or a source of the compilation; either reads the same through the `Element` API (D-140): its instance
fields in declaration order with their generic types, and every `CLASS`-retained annotation with the values written on
it, on a field and on a record component. Every check of `processor/32` runs on a bound model with the same codes,
against the binding's `root`; a diagnostic on a classpath model's field names the model and the field, since javac has
no source line to point at. A `root`, or a nested model's `root`, that names no class yet defers the binding to the next
round, as D-107 defers an annotated model, and is `MQ3017` on the binding in the last round (D-142). The processor stays
`isolating` by default: the generated file's only originating element is the binding's type or `package-info` (R-GEN-05,
D-140); a module that nests a bound model whose binding is in another source file sets `modelquery.aggregating` (R-PROC-34).

**R-PROC-31** *(D-134, D-142)* **Nested bound models.** A model that a `@Join` nests or a `@Child` holds is
read as D-45 reads it when it is annotated `@QueryModel`, on the classpath or in the compilation, and also when a
`@BindQueryModel` of the compilation binds it, in this round or an earlier one; its own annotation wins over a binding,
which `MQ3023` reports. The outer QModel names the nested QModel where it is generated: the annotated model's package,
or the nested binding's. Any model may nest a bound one, an annotated model of the compilation included. On a bound
model, a nested model that is neither annotated nor bound in the compilation is `MQ3026`, reported on the field, where
an annotated model's stays `MQ3005` or `MQ3401`; and since its QModel is generated in another package, a nested model
type, or a type enclosing it, that is not public is `MQ3024` on the binding (`processor/31` R-GEN-35). A nested model
bound in another compilation than its outer model's is Q-19: until it is settled, bind both in one module.

**R-PROC-34** *(D-140)* **`modelquery.aggregating`.** A boolean processor option, `false` unless set to `true`, read once
per compilation. The processor is registered with Gradle as `dynamic`: off, it reports Gradle's `isolating` type; on,
`aggregating`. A module sets it when a model nests a bound model whose binding sits in another source file, as every
annotated outer model nesting a bound one does (R-PROC-31): the binding is not reachable from the outer model's
originating element, so an isolating recompile of the outer model alone would miss it. It changes no generated byte.

## 10. Source models

**R-PROC-35** *(D-143)* **`@SourceModel`.** A source model is read and validated exactly as a `@QueryModel` is (§2–§6):
the same field and type annotations, and the same checks, codes and messages. The processor is registered for it, so a
compilation carrying source models alone generates them, as one carrying query models alone does. It is one statement,
so a `@Child` on it is `MQ3030`. `MQ3031` checks its key: the root entity's id, or the inner source's key fields, for an
ungrouped source, the full `@GroupBy` set for a grouped one, no group key nullable in its mapping, and no
`@FilterColumn` crossing a to-many join (a to-many `@Join` is `MQ3003`).
The id is checked as a whole, so an `@IdClass` or `@EmbeddedId` root gives an ungrouped source one key column per id
attribute (D-149, M26.1).
Its QModel declares
`source()` for an entity root or `source(SourceQuery<X> inner)` for a source root, and `query(SourceQuery<S>)` when
not a branch. Its own constants have no `MAPPER`; nested `Rows` maps direct source reads (`processor/31` R-GEN-40).
It has no write method, since no write reads a source (`processor/31` R-GEN-37).
A `shape` makes it a branch (§10, R-PROC-38).

**R-PROC-36** *(D-143)* **`@Window`.** A `@Window` field of a source is a window function over the source's rows
(`api/17` R-SRC-04, `processor/31` R-GEN-38). An ill-written one is `MQ3032`:

1. every term — `argument`, each `partitionBy` and each `orderBy` path — names an attribute of the root, resolved
   exactly as `@Column`'s path is (§3);
2. the argument is written exactly where the function takes one: `ROW_NUMBER`, `RANK` and `DENSE_RANK` take none, and
   every other function needs one;
3. the field's type is the function's result type (INV-3): `Long` for the three ranking functions and `COUNT`, the
   argument's type for `LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE`, `MIN` and `MAX`, and `SUM`'s and `AVG`'s rule as
   `@Aggregate` states it (§6);
4. a function whose value depends on the order, which never has a default one, says how: `ROW_NUMBER`, `RANK`,
   `DENSE_RANK`, `LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE`, and an aggregate with a `ROWS` frame need an `orderBy`;
5. a function that reads a frame needs one written: `FIRST_VALUE`, `LAST_VALUE`, and an aggregate with an `orderBy`
   (whose database default is unspecified, except that `LAST_VALUE` would return the current row);
6. `RANGE` is for an aggregate, whose frame it makes a peer group: `FIRST_VALUE` and `LAST_VALUE` take a `ROWS` frame
   only, since a `RANGE` frame would make every row its own peer group;
7. `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG` and `LEAD` take no frame: the database ignores or rejects one, so it is
   refused rather than dropped;
8. in a grouped source every term names a `@GroupBy` key or an `@Aggregate` field, never an attribute the group has no
   value for.

A `@Window` outside a `@SourceModel` is `MQ3033`: SQL forbids a window function in a `where` predicate, and a keyset
cursor is one, so an outer model reads the value as a plain column.

**R-PROC-37** *(D-144)* **A recursive source.** A source model that declares a `@Depth` field is recursive: its rows
are the nodes of a tree or graph, and the level of a row is what the CTE assigns it (`api/17` R-SRC-07, R-SRC-08).

- The field is an `Integer`, and a model declares at most one. Anything else is `MQ3037`, as is a recursive model
  carrying a `@Window`, an `@Aggregate` or a `@GroupBy`: PostgreSQL forbids all three in the recursive term, and a
  window or aggregate over a recursive result belongs on a source over this source (R-PROC-40, D-150). `@Depth` outside
  a `@SourceModel` is `MQ3037` too — only a
  source recurses.
- The `@Depth` field names no attribute: the CTE's level column is what it maps, so `MQ3001`'s attribute check reads it
  as satisfied, exactly as a `@Discriminator`'s constant does (R-PROC-38).
- A recursive model is neither a branch nor a shape: `@Depth` on a model that declares a `shape`, and a `shape` that
  declares a `@Depth`, are both `MQ3034`.
- Its generated `source()` names the level column, `.depth(LEVEL)`, beside the `source()` a plain source model gets
  (`processor/31` R-GEN-37): it is what makes `recursive(...)` legal on the builder and what refuses a `@Depth` model
  built without its step (`MQ1902`).

**R-PROC-38** *(D-143)* **A branch of a set operation.** `@SourceModel(shape = Shape.class)` makes the model a branch
of `Shape`, the shared shape of a set operation (`api/17` §4, R-SRC-05, R-SRC-06). `shape` must name a `@SourceModel`
of the compilation, and `MQ3034` covers the rest:

1. the branch's fields match the shape's one for one, by name, in declaration order and by Java type (INV-3), and the
   branch has neither a field the shape has none of nor the shape one it has none of;
2. and by what the database stores: the same converter class, the same `@Enumerated` type — an omitted one reading as
   `ORDINAL` — and the same temporal type, on the attributes the two models name. An `ORDINAL` enum and a `STRING` one,
   or a converted column and a raw one, would otherwise compile and produce rows that compare unequal;
3. the branch's `@PrimaryKey` fields are the shape's, by name and in order, since the branches are matched by the
   shape's key;
4. the branch declares the shape's `@Discriminator` fields, and no other; a `@Discriminator` is part of its model's key
   — the constant it fixes is what keeps the branches' keys disjoint — and a model declares at most one, the generated
   builder fixing one constant. The discriminator field names no attribute of its own: its value is that constant;
5. a branch declares no `@Window` field, and neither does a shape that has branches; a window over the combined rows
   belongs on a source over the shape (R-PROC-40, D-150);
6. a branch is a source model like any other: `MQ3031` reads its key as the root entity's id or the source's
   `@GroupBy` set, plus the discriminator, and the branch's own fields are checked against its own root as §2–§6 do.

A recursive model is neither a branch nor a shape, and `@Depth` on either is `MQ3034` (R-PROC-37). The shape is an
ordinary source model over its own root and the first branch of its own operations, so its `source()` builds the same
`SourceQuery<S>` a branch's does (`processor/31` R-GEN-37).

**R-PROC-39** *(D-143)* **The root kind.** `@QueryModel(root = X.class)` reads `X`'s kind from the annotation on `X`:
an entity root when `X` is `@Entity`, a source root when `X` is `@SourceModel`. A root that is neither, or both, is
`MQ3038`. A cyclic source-root chain is also `MQ3038`, naming the root through which the chain cycles.
On a model rooted at a source the processor checks every field against the source model's fields by name and
then by type (INV-3), a `@Column` path being a single segment and taking the converter of the source's field, so an
outer `@Column(converter = ...)` is `MQ3014` and an outer `@FilterColumn` is `MQ3011`; a `@Join` is `MQ3035`, a
`@Child` whose child model is rooted at a source is `MQ3039` (on a parent rooted at a source its parent-side key
names a source field), and an outer `@PrimaryKey` that is not exactly the source's key is `MQ3036`.
`@BindQueryModel.root` may name a source model, as a model's own `root` does (`api/17` R-SRC-10).

These root-kind and field-name checks also apply when the model reading the source is itself a `@SourceModel`
(R-PROC-40); its factory takes the inner source, and its key is checked by `MQ3031` rather than `MQ3036`. An outer
`@QueryModel` over that source still uses this rule unchanged and accepts the complete built chain.

**R-PROC-40** *(D-150)* **Source over a source.** A `@SourceModel` whose root is another `@SourceModel` uses
R-PROC-39's root-kind and field-name/type/converter checks, and `MQ3035` for `@Join`. `@Child` stays `MQ3030`.
Unlike an outer `@QueryModel`, it permits `@FilterColumn` with a single-segment path naming an inner CTE column;
the outer query still reports `MQ3011`. It may add windows, aggregates, group keys and computed fields.
`MQ3031` checks an ungrouped key against the inner source's key fields in order, with a reason naming that source;
a grouped key is the full group set, with nullability inherited from the inner fields, including every branch of a
shape (R-SRC-16). Computed nullability remains a runtime check. A source over a source that is recursive (`@Depth`),
a branch, or a shape is `MQ3041` and generates no file. Rooting a `@SourceModel` or a `@QueryModel` at a branch model is also `MQ3041` (D-152): root at its
shape instead, since a branch builds a `SourceQuery` of its shape. Shape/branch checks and group-key nullability
consider source models within the compilation, including deferred models, as R-PROC-38 does. Recursive-term and
branch/shape refusals (R-PROC-37/38) direct callers to a source over the recursive source or the combined shape
respectively.

## 11. Acceptance criteria

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
| AC-PROC-13 | `@Computed(Def.class)` generates an `ExpressionField` constant named after the field, after the column constants, mapped by the generated mapper, in `DEFAULT` and `ALL`, left out by `@ExcludeFromDefaults` (R-PROC-21). |
| AC-PROC-14 | `@Aggregate(fn = SUM, expression = Def.class)` and `@GroupBy` on a `@Computed` field generate the aggregate constant and a `GROUP_KEYS` holding the expression; the query runs on every Tier-1 vendor (R-PROC-16, R-PROC-22). |
| AC-PROC-15 | An `@InsertModel` with a converter, a to-one by id, an embedded path and an `@EmbeddedId` named whole or by its components generates, as does an `updatable = false` column; `@Join`, `@FilterColumn`, `@Aggregate`, `@GroupBy`, `@Computed`, `@Child` and `@Transient` are `MQ3502`, a column through a join, a collection, the `@Version`, an inverse to-one or a to-one id of the wrong type takes the update model's code, and an `insertable = false` column is `MQ3304` (R-PROC-23). |
| AC-PROC-16 | A type carrying two or three of `@QueryModel`, `@UpdateModel` and `@InsertModel` reports `MQ3503` once, on the type, and generates no file (R-PROC-24). |
| AC-PROC-17 | An `@Id` carrying an annotation meta-annotated `@IdGeneratorType` or `@ValueGenerationType`, with no `@GeneratedValue`, is generated: an insert model leaves it out, and naming it is `MQ3501` (R-PROC-23). |
| AC-PROC-18 | `@Selected SelectSet<M>` on a record component and on a class field is no column, constant, `SelectSet` member or key, and generates; a type other than exactly `SelectSet<M>` or a second `@Selected` is `MQ3020`, another library annotation on the field is `MQ3021`, and an update or insert model refuses it (R-PROC-25). |
| AC-PROC-19 | `generateInserts = true` generates `INSERT_COLUMNS`, `insert`, `insertFrom` and `persist` over the root columns, leaving out with no diagnostic a `@Join`, `@Child`, `@Computed`, `@Selected` and `@Transient` field, a `@FilterColumn`, a to-one, the `@Version`, an `insertable = false` column and a generated id, each named with its reason in the `INSERT_COLUMNS` Javadoc; with `generateChanges = true` as well the model gets both sets of members (R-PROC-26). |
| AC-PROC-20 | `generateChanges = true` on a query model with a whole-entity and a converted to-one column compiles with no error, generates no setter for either, and `generateChanges` and `generateInserts` together leave out the same to-one columns (R-PROC-19, D-124). |
| AC-PROC-21 | `@ExcludeFromInserts` on a field leaves it out of `INSERT_COLUMNS`, `insertFrom`, `insert` and `persist` while the query members keep its column and constant (R-PROC-27, D-125). |
| AC-PROC-22 | `generateInserts` leaves out a plain `@Generated`, written are `@Generated(writable = true)`, `@Generated(sql = …)`, `@Generated(event = UPDATE)`, the Hibernate 6.x `@Generated(GenerationTime.UPDATE)` and `NEVER`, every timestamp annotation, and a `@Generated` whose type doesn't resolve; run on the Hibernate 6.6 and 7.x annotation shapes (R-PROC-26, D-125). |
| AC-PROC-23 | `@BindQueryModel` on a type, repeated on one type and on a `package-info`, over a class and a record compiled into an earlier class directory, generates each QModel in the binding's package, originating from the binding alone; a model bound twice, on one type or on two, reports `MQ3023` once on each binding, and one also annotated `@QueryModel`, on the classpath or in the compilation, reports `MQ3023` on the binding; none generates a QModel for the binding, and the annotated model in the compilation keeps its own. A model bound once as an update model and once as a query model generates both; one bound twice by `@BindUpdateModel`, or carrying `@InsertModel` and bound by `@BindUpdateModel`, is `MQ3023`, and so are an update and a query binding that would both generate its QModel, or its change set, each reported once and generating nothing. Two models of one simple name bound into one package report `MQ3023` once on each binding and generate nothing; a binding whose QModel name an annotated model in the compilation generates reports `MQ3023` on the binding, and the annotated model keeps its own (R-PROC-28, R-PROC-30, D-140, D-141). |
| AC-PROC-24 | A bound model that is not public, a public one nested in a type that is not, a class whose no-arg constructor, a setter or a `@Join` getter its QModel calls is not public, a bound insert model's class whose column getter is not public, and a `model` that is not a class or record each report `MQ3024` on the binding and generate no QModel; a public record compiles with nothing more, and so does a bound update model's class whose getters are not public, since nothing generated calls them (`processor/31` R-GEN-35, D-141). |
| AC-PROC-25 | A bound record with a valueless `@Computed` group key and an `@Aggregate` naming no attribute or expression, whose binding names both definitions with `@BindExpression`, generates the QModel the annotated model with `@Computed(Def.class)` and `@Aggregate(expression = Def.class)` generates, apart from its package; `MQ3025` for a valueless `@Computed` with no `@BindExpression` and on an annotated model, and, on the binding, for a `@BindExpression` naming an unknown field, a `@Transient` field, a `@Computed` or `@Aggregate` already naming a class, and a field named twice; a valueless `@Computed` on an update model stays `MQ3302` (R-PROC-29, D-141). |
| AC-PROC-26 | A bound `CustomerView` with a `@Join` and a to-many `@Child`, whose nested models are bound by `@BindQueryModel`s of the same compilation, generates `golden/bound/QCustomerViewWithInvoices.java`, which equals `golden/QCustomerViewWithInvoices.java` apart from its package and imports, naming the nested QModels in the binding's package; with the nested models annotated, one on the classpath and one in the compilation that joins the bound model back, it generates the same QModel naming theirs in their own package, and the annotated one names the bound QModel. A nested model neither annotated nor bound is `MQ3026` on each field, and a nested model that is not public is `MQ3024`, each generating no QModel. A bound model rooted at a type another processor generates, and a bound model nesting it, are generated in a later round; when the type never appears each reports `MQ3017` on its binding, and a deferred binding whose QModel name a binding of an earlier round generated reports `MQ3023` while the earlier QModel stands (R-PROC-30, R-PROC-31, D-107, D-142). |
| AC-PROC-27 | `-Amodelquery.jspecify=true` adds the annotations of R-GEN-36 and is declared in `@SupportedOptions`; unset or `false` leaves the output unchanged; on with no JSpecify on the classpath is one `MQ3027` and no annotation generated (R-PROC-32, D-135). |
| AC-PROC-28 | A query model column `@Column(attribute = "referrer") Long referrerId` over a to-one of a `Long` id, and one with a converter from `Long`, compiles; another type is `MQ3002` naming the target's id type, and a converter that does not convert the id `MQ3014`; each of the inverse side, a `referencedColumnName`, `@JoinColumns`, `@JoinFormula`, `@JoinColumnOrFormula`, `@NotFound`, `@JoinTable`, a `@JoinColumn(table)`, a `@MapsId` target, an `@IdClass` target, an `@EmbeddedId` target and a to-one inside an embedded value is `MQ3002` with its own reason; a target with `@SQLRestriction` or `@Where` is `MQ3029`, and `MQ3016` does not suggest the id form for it; `referrer.id` is `MQ3001`; `MQ3016` names the id form and `@GroupBy` on a whole-entity to-one is `MQ3028` (R-PROC-33, D-136). |
| AC-PROC-29 | The processor is registered with Gradle as `dynamic`; its supported options name `org.gradle.annotation.processing.isolating` by default, before `init` and when `modelquery.aggregating` is absent or not `true`, and `org.gradle.annotation.processing.aggregating` when it is `true` in any case. A model bound again for the same kind by a binding in a later round, in another package, is `MQ3023` on the later binding, and only the earlier QModel is generated (R-PROC-28, R-PROC-34, D-140). |
