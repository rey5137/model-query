# 10 — Columns, Joins and Row Mapping

**Covers:** `TableField`, `SelectField`, `ScalarField`, `ColumnField`, `ExpressionField`, `Expr`, `SelectSet`, `OrderField`, `Row`, `RowMapper`, how joins are
shared within one query, and how null precedence is rendered portably.
**Read when:** defining the columns of a model, adding a join, deciding why two joins appeared, or changing how a row
becomes a model.
**Owns:** `R-COL-*`, `AC-COL-*`. Aggregate selections are `api/13`; filters are `api/12`.

---

## 1. `TableField` — a node in a join path

```java
public final class TableField<P, T> {
    public static <T> TableField<T, T> root(Class<T> entity);
    public static <S> TableField<S, S> source(Class<S> model);              // the root of a query over a source
    public static <P, T> TableField<P, T> join(TableField<?, P> parent, String attribute, JoinType type);
    @Incubating
    public <S extends T> TableField<T, S> treat(Class<S> subtype);          // this node read as an entity subtype
    public TableField<P, T> as(String alias);                              // a separate join to the same path
    public TableField<P, T> on(BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition);   // requires as(...)
    public TableField<P, T> withParent(TableField<?, P> newParent);        // keeps alias, ON condition and presence key
    public TableField<P, T> presentBy(PrimaryKey<?, ?> key);               // the key that tells a match from a miss
    public From<?, T> resolve(JoinContext ctx);                            // for custom predicates and customizers
}
```

**R-COL-01** A `TableField` is a definition, not a join. It resolves to a Criteria `From` only through a
`JoinContext`, which is created per query build with `JoinContext.of(root, cb)` (INV-9, D-18). The same constant may
be used by any number of concurrent queries. A context carries the build's `RenderOptions`: `JoinContext.of` uses
`RenderOptions.portable()`, and `ModelQuery.buildQuery(cb, phase, options)` the executor's (D-34).

**R-COL-02** `JoinContext` caches joins by **join key** — the parent's join key, the attribute, the `JoinType` and the
alias (empty by default) — never by object identity. Two separate `TableField.join(ROOT, "customer", LEFT)` calls
therefore produce one join.

**R-COL-15** **A join may carry a presence key.** `presentBy(key)` names the primary key whose non-null value means
the join matched a row. It is kept by `as`, `on` and `withParent`, is not part of the join key, and throws `MQ1104` on a
root. The engine selects it with any column of the join (`api/11` R-QRY-04), and a nested model's mapper reads it to
tell a LEFT-join miss from a match whose other columns are `NULL` (`processor/31` R-GEN-13, D-38).

**R-COL-03** The join-sharing rules:

- Same path, same type, no alias → one join, whether the column is selected, filtered or ordered on.
- Different attributes are different paths even when they reach the same table (`billingAddress` and `shippingAddress`
  → two joins to `address`).
- `as("x")` gives the same path its own join. Children of an aliased join are separate too
  (`ITEMS.as("b") → product` is not `ITEMS → product`).
- A treated root still rejects `as`, `on` and `presentBy` with `MQ1104` (R-COL-34).
- `on(...)` adds its condition through JPA's `Join#on`, which preserves LEFT-join semantics that a `WHERE` predicate
  would break. Request-bound conditions use `joinFilter(...)` (R-COL-39), ANDed into the same single `on(...)` call.

**R-COL-34** *(D-154)* **A treated table.** The `@Incubating TableField#treat(Class<S>)` returns a
`TableField<T, S>` over an entity root, to-one join or to-many join. Its join key is its parent key and subtype;
one `JoinContext` renders it once per statement through `TreatRenderer#treat`. Columns, joins and `exists` paths
hang off it. It is immutable and safe as a `static final` definition (INV-9). A to-many path keeps R-PAG-13's
filter-only rules. A column over a treated join that names a to-one only the subtype declares is `MQ1001`, whatever its
type: its path would join that to-one `INNER` and drop the rows with a `NULL` foreign key, where a read joins a to-one
`LEFT` (D-124); join it with a `TableField` and read its column.
`presentBy` on a treated join is allowed and retained by copies.
With no `TreatRenderer` every treated node is `MQ4014`, including one that would render untreated. The executor
checks the reachable definition before any statement, including fetch children, sub-queries and source bodies.
`Agg.count` names a treated table from its subtype's simple name with a lower-case first letter: `count(card)`
for `ROOT.treat(Card.class)`, also when the treated table is a join.

`MQ1106` rejects an improper subtype at definition on a root, whose entity class is known, and at first resolution
on a join, whose declared target is obtained from the metamodel. Both checks require a strict subtype and use the
same diagnostic. At definition it also rejects treating a treated node or a source root, and `as`, `on` or `named` on
a treated join (D-166); apply those modifiers before treating. At first resolution an unmapped entity subtype is
`MQ1106`.
Entity mapping comes from the provider, including XML mappings, rather than the presence of an `@Entity` annotation.

The declared-target check is separate from the actual entity reached: the query root or the join target under
which `under` re-roots the node. A strict subtype of that entity renders the treat. The subtype itself, or a
supertype of the entity reached, renders untreated. An unrelated or sibling type is `MQ1106`, naming both types.
Re-rooting retains the original declared target for the improper-subtype check. On a treated root, `as`, `on` and
`presentBy` remain `MQ1104`.

**R-COL-35** *(D-154)* **A subtype column reads NULL on other subtypes.** A column over a treated node reads the
attribute on rows of the subtype and its own subtypes, and NULL on every other row, wherever it is admitted:

- in the select list, alone or as an operand of an `Expr` or `@Computed` function, the row is kept with a NULL
  value; a treat in a select never removes a row;
- in a predicate it compares as NULL: `eq`, `lt`, `like`, `in` and `isNotNull` are false and `isNull` is true; `ne`
  and `notIn` include the row through their D-5 `IS NULL` branch; `not(eq)` excludes it (SQL `NOT` over NULL, D-5);
  a treat inside `or` restricts only its own operand;
- in an order the row sorts as NULL under the column's null precedence (R-COL-12); a keyset key over it is nullable:
  NULL is `MQ2202` by default, and `nullsFirst()`/`nullsLast()` add R-PAG-05's `IS NULL` branches;
- in a count it appears only where it can change the row count: in a predicate or under an `INNER` treated join;
- for every nullability rule the column is nullable, whatever the attribute's mapping says.

A treated column in `GROUP BY`, under an aggregate or as a window function's argument, directly or inside an `Expr`,
in a query or a source body, is `MQ4016` before any statement (Q-27): a provider narrows a grouped statement to the
subtype, and no window over a treated argument was measured (D-162). A correlated count whose
own root is treated (R-COL-31) is unaffected.

**Where the rule holds.** A provider keeps the root's other rows only while the statement also reads the root
untreated, so the engine builds only these renderings. A statement with a treated node is one with a `treat` node or
a downcast join in its selection, order, grouping or filters:

- **An anchored content statement.** Every ungrouped content statement with a treated node over an entity root
  selects the root's single id untreated. When its model selects no untreated `root.<id>`, the engine adds it as a
  hidden column, `mq_0id`, selected last, in every phase, and never in `fields()`, `select()`, `DEFAULT`, `ALL`, a
  mapper or `Rows`. The name sits outside D-146's `mq_<property>` space. A source body's anchor is `api/17` R-SRC-20's.
- **A LEFT treated join,** whose type restriction sits in its `ON` (R-COL-34).
- **A count of the root id.** A count over an ungrouped statement with a treated node is built fresh from the
  definition (`engine/20` R-EXE-05) and selects `count(root.<id>)`, never the row count, since its root is treated
  (`api/13` R-AGG-19): only its predicates and the joins that can change
  its row count, with no selection and no order. A grouped query's total keeps R-AGG's count of groups.

A hidden anchor changes the rows of a `distinct()` query and of a `union`, `intersect` or `except`, so a query whose
customizer makes a statement with the anchor distinct, and a non-ALL set operation whose branch has a treated node and
selects no untreated root id, are `MQ4016` before any statement; selecting the root id in the model or the branch
admits them, and `unionAll` is unaffected. An `@IdClass` or `@EmbeddedId` root with a treated statement is `MQ4016`:
the anchor and the count were measured over a single id attribute. Every other treated statement, a grouped one, a
fetch plan's child statement, a join plan or an enricher, a bulk write's `where` and a returning model among them, is
refused before the first statement by the engine or its `TreatRenderer` (`vendor/40` R-VND-26). An `add(...)`
predicate records no column, so no site reports what it reads: a subtype treat it reaches is `MQ4016` while its
statement renders, before that statement runs (D-156).

On `SINGLE_TABLE`, two subtypes may map one physical column; the provider reads it under a `CASE` on the
discriminator, so a sibling's value never shows. A shared-column site in a shape or use set the provider's table
lacks is `MQ4016`.

**R-COL-36** *(D-154, D-158, D-159)* **The row's entity type.** The `@Incubating` `Expr.typeOf(Class<M> model,
TableField<?, T> table)` is an `ExpressionField<M, Class<? extends T>>` reading `TYPE(table)`, the exact entity class
of each row's node, through `From#type()`. It selects, groups, filters with `eq` and `in`, and orders an offset page,
an offset export and `primaryKeyFirst` (`engine/21` R-PAG-25). `eq(typeOf(ROOT), CardPayment.class)` keeps
`CardPayment` rows only, not those of its entity subtypes; `Filters#ofType` (`api/12` R-FLT-28) keeps those too.

- **Order.** Unspecified: the provider's type rendering, the discriminator value on `SINGLE_TABLE` (and on `JOINED`
  with a discriminator) and the union's synthetic type column on `TABLE_PER_CLASS`. With R-PAG-01's id tie-breaker
  it is deterministic per deployment, which is all an offset page needs; order by a `@Computed` `CASE` for a named
  order.
- **Keyset.** A `keyset()` order key over it is `MQ1208` at `build()`, as every expression is (R-QRY-16).
- **No treated statement.** It reads no column and makes no treated statement (R-COL-35): no root-id count and no
  `mq_0id` anchor. A grouped query's total by it keeps R-AGG's count of groups; on a `JOINED` root without a
  physical discriminator the Hibernate renderer refuses that count (`GROUPED_COUNT`), whose `createCountQuery()`
  drops the subtype joins, and the engine counts the groups client-side (`engine/20` R-EXE-03, D-159). Selected in
  a grouped query, it must itself be a group key (`MQ1401`), since it is per row; selected as a group key it is one
  site, `{SELECT, GROUP_BY, TYPE}`.
- **Refusals.** Without the provider's `TreatRenderer` it is `MQ4014`, at the call's site check and at rendering
  (`vendor/40` R-VND-26). Over a node reached through a LEFT join, in a select, order or group key, it is `MQ4016`
  before any statement: on a row whose join is absent both measured lines fail to resolve the type instead of reading
  NULL; `notOfType` over such a join is admitted. An order by it on a `JOINED` root whose persister has no
  discriminator is `MQ4016` from the Hibernate renderer: both lines render `order by case end`, which no vendor
  accepts. Over a treated node, a treat of the root or a downcast join, it is `MQ4016` before any statement, since
  no cell measures it; read the untreated node's type. Over a source root it is `MQ1106`.
- **An unmapped discriminator value.** Reading `typeOf` on a row whose discriminator value no entity maps fails with
  the provider's error, so the row is never skipped silently.

**R-COL-37** *(D-185, `@Incubating`)* **Row-level rounding.** `Expr.round(ScalarField<M, BigDecimal> value, int scale)`
is an `ExpressionField<M, BigDecimal>` rendering the standard `CriteriaBuilder#round` (Jakarta Persistence 3.1, both
Hibernate lines), so it needs no provider. It rounds half away from zero on every Tier-1 vendor and takes a
`BigDecimal` only; a `scale` below 0 or over 30 is `MQ1411` when it is called (`api/13` R-AGG-18). A column with a
`ColumnConverter` is `MQ1501`. Covered by `api/13` AC-AGG-25.

**R-COL-39** *(D-193, D-194, `@Incubating`)* **Request-bound join filters.**
`ModelQuery.Builder.joinFilter(EntityJoin<M, ?>, UnaryOperator<Filters<M>>)` and
`joinFilter(TableField<?, ?>, UnaryOperator<Filters<M>>)` record a filter for one LEFT join. The lambda runs once at
the call; only recorded terms are retained on the query, never on the shared join definition (INV-9). A second call
on the same join key replaces the first. Skipped terms add nothing; an all-skipped filter adds no `ON` predicate, is
not in force, and so is left out of inspection and of `QueryConditions` equality, and it replaces (removes) an earlier
filter on the same join.
The filter is ANDed with the entity key predicate or the association's static `on(...)` in exactly one provider
`on(...)` call, in every statement that renders the join, including count, both primary-key-first steps, keyset and
export, and in the `EXISTS` over a second root that a to-many `where` term moves the joining terms into (R-EXE-16),
whose root is not correlated, so the provider keeps the `ON`. It never creates an association join by itself.
Inspection reports each filter in force.

The filter may read only that join's own columns, values and constants. `MQ1912` is raised at build for another
table, any path below the filtered join regardless of cardinality, `exists`, an aggregate, window or correlated
value, a sub-query term crossing the filtered join, a filter on an INNER join or a root, or a filter on an entity
join the query does not bind. At first resolution,
before any statement, `MQ1912` refuses a join read by an id-form column (R-COL-29; map the foreign key as a basic
column instead), a join key whose filter the content statement never applies, including a different join type or
alias, a different join key that resolves to the filtered join (an `INNER` key of the same path inside `or(...)` or
`not(...)`, which reads the `LEFT` join, whichever reads it first), and any join the filter's own columns create below
the filtered join while its `ON` is built (an id-form or whole-entity to-one column of the filtered table). A sub-query
reading the filtered join's value through `outer.column(...)` (R-FLT-27) reuses the outer join and crosses nothing.
The timing split follows D-194; the first-resolution checks run in the executor's first-run check (so a count-only
first call is refused, adding no content statement to a count) and again on every treated root-id count
(`buildRootIdCount`), so an `orderedBy` copy of the definition is checked too.
A join that renders `INNER` (declared `INNER`, not turned `LEFT` by `or(...)`, `not` or an aggregate filter), with any
ancestor, root or entity join, a `joinFilter` join is `MQ1912` as well (D-195): a join filter only blanks columns, never removes rows. It is refused at
statement build, on the first statement that resolves the join in any phase, and not at `build()`, because a declared
`INNER` that `or(...)`, `not` or an aggregate filter renders `LEFT` is allowed. (An `INNER` forced by `required` cannot
reach this check: an `exists` path crossing a filtered join is already `MQ1912` at `build()`.) Declare such a join `LEFT`.
A null value is `MQ1301` at the `joinFilter` call.

**R-COL-30** *(D-143)* **A `TableField` may be a source's root.** `TableField.source(model)` is the root of a query
over a `@SourceModel` (`api/17` R-SRC-10): its `rootEntity()` is the source model, and the query reads the CTE's
columns by field name rather than from an entity `From`. It is generated, for a `@QueryModel` whose `root` names a
source model; a hand-written `ColumnField.of(...)` over it, or a `TableField.join(...)` from it, is `MQ1905`, since
there is no metamodel for INV-3 to check it against. A source root's column is
`ColumnField.over(Outer.class, ROOT, QSource.CONSTANT)` (`api/17` R-SRC-10).

**R-COL-31** *(D-150)* **Correlated scalar values.** The `@Incubating` static terminals
`Correlated.count(model, rows, correlation)`, `exists`, `min`, `max`, `sum` and `first` return an
`ExpressionField<M, C>`. `model` types the outer vocabulary; `rows` is a `SubSelect<S, C>`;
`correlation` takes `Filters<S>` and `Outer<M, S>` and returns `Filters<S>`, as R-FLT-17 does. The factory records
it once, requires an actual lifted outer column (`MQ1309`) and closes the recorder (INV-9). A lifted column is
usable only in that correlation (`MQ1310`); it must be on the outer root or a to-one path from it (a path crossing a to-many join is `MQ1311` at first
resolution). The sub-select's own filters
and the recorded correlation are combined with AND.

Each terminal is a scalar sub-query in every scalar position: selection, projection, filter and order; there is
no join lowering. `count` counts the sub-select's selected column's non-NULL values, returns `Long` and yields
zero when none match. `exists` ignores that column and renders `case when exists(select 1 …) then true else false
end`, returning a non-NULL `Boolean`. `min` and `max` have per-method `Comparable` bounds and preserve the column's
type; `sum` has a per-method `Number` bound and follows R-AGG-04's SUM types (`BigDecimal`, `Double`, `Long`),
including its converted-column refusal. `min` and `max` refuse a converted column with `MQ1501`. These three
terminals return NULL when no non-NULL value matches. An expression may coalesce or otherwise compose the result.
A correlated value is allowed in an update or delete `where` (MySQL runs it key-first when it reads the target
table, R-WRT-12), refused in a conflict update's `where` (`MQ1804`) and in `setExpression` (`MQ1613`: it reads
the root's lifted columns) (D-153).

Equality is the sub-select, the recorded correlation, the terminal and, for `first`, its order keys, directions and
null precedence; a custom predicate compares by the identity of its recorded condition, even when labelled, at any
depth of either WHERE tree. Correlation-lambda identity and
`named` are outside it. Equal values share one Criteria node per statement (R-COL-19), also when selected, filtered
and ordered.
Only lifted columns contribute outer joins; the inner query's paths belong to its own context. A count statement
keeps row-changing lifted joins of a select-only correlated value (R-EXE-05). Offset pages, offset export and
`primaryKeyFirst` accept a correlated order key; `keyset()` refuses it with `MQ1208` (R-QRY-16).

**R-COL-32** *(D-150)* **First correlated value.** `Correlated.first(model, rows, correlation, order, more...)`
requires at least one order key and returns an `ExpressionField<M, C>` preserving the sub-select column's type.
It orders the matching inner rows, appends the sub-select root's id ascending as the final tie-breaker (every
component of a composite id, by attribute name), and returns the first row's selected value, or NULL when no row
matches. R-COL-31's scalar positions, composition, equality, node reuse and paging rules apply to `first` too.
Null precedence follows R-COL-12: `DEFAULT` renders no null clause (or uses the provider's configured default,
D-36); `nullsFirst()` and `nullsLast()` use the existing `NullPrecedenceRenderer` path, with its portable fallback.
The engine renders the orders in the inner context and calls `SubqueryRenderer#firstRow` to apply
`order by … fetch first 1 rows only` inside the scalar sub-query (R-VND-25). Without that renderer, building the
statement is `MQ4014`, before any statement runs; `PortabilityCheck` reports it per declared target without a
database. Hibernate's `hibernate.jpa.compliance.query=true` also refuses `first` with `MQ4014` before any statement.
Other correlated terminals do not need that renderer.

**R-COL-33** *(D-150)* **Correlated refusals.** A correlated value at any depth of a CASE condition is `MQ1505`;
a CASE result may use it. An aggregate over it is `MQ1510`, including the `Agg` expression factories,
`@Aggregate(expression = …)` when the generated constant initializes, and `Agg.of` at first resolution. Declare
the scalar through `@Computed` (R-PROC-21). `having` keeps R-FLT-14's aggregate-only operands. A correlated group
key, including `@GroupBy @Computed`, is `MQ1314`; in a grouped query's selection and orders every lifted column
of a correlated value must be a group-key column, checked at definition. A correlated value in WHERE may lift
non-group-key columns because it is evaluated per row before grouping.

A correlated `count`, `sum` or `first` whose selected column, sub-select's own WHERE, inner side of the correlation
or (for `first`) order crosses a to-many join is `MQ1511` at first resolution, before any statement runs: joining
can multiply the counted or summed rows or make `first`'s id tie-breaker non-unique. The message points to
`Filters.exists(path, inner)`, whose separate context does not multiply them. `min`, `max` and `exists` are exempt.
These checks also apply through nested expressions.

**R-COL-04** A `TableField` carrying an `on(...)` condition must have an alias, because a lambda cannot be compared.
Two `TableField`s with the same join key and different condition instances throw `MQ1101` when the query is built.

**R-COL-05** *(was R13)* **One path, one join.** Selecting, filtering and ordering on the same path uses exactly one
join. A second join to the same path requires `as(...)`.

```java
TableField<OrderEntity, OrderItemEntity> ITEMS   = TableField.join(ROOT, "items", INNER);
TableField<OrderEntity, OrderItemEntity> ITEMS_B = TableField.join(ROOT, "items", INNER).as("itemB");
TableField<OrderEntity, AddressEntity>   BILLING = TableField.join(CUSTOMER_TABLE, "addresses", LEFT)
        .as("billing").on((a, cb) -> cb.equal(a.get("type"), "BILLING"));
```

Two joins to `order_items` express "has item A **and** has item B". For "has an item matching X", prefer
`Filters.exists` (`api/12` §6): it does not multiply rows, so `count` and export need no distinct or dedupe work.

## 2. `SelectField` — anything selectable

```java
public sealed interface SelectField<M, C> permits ScalarField, AggregateField {
    Class<C> type();
    String name();                                  // used in MQ messages
    Expression<C> expression(JoinContext ctx);      // ColumnField returns path(ctx)
    OrderField<M, C> asc();
    OrderField<M, C> desc();
}

public sealed interface ScalarField<M, C> extends SelectField<M, C> permits ColumnField, ExpressionField {}
```

**R-COL-06** Everything that selects, orders or reads a value accepts a `SelectField`: `SelectSet`, `orderBy`,
`Row.get`. Everything that builds a `WHERE` predicate or a group key, and every `Expr` argument, accepts a
`ScalarField`: a `ColumnField` or an `ExpressionField`. An aggregate in `where`, in `groupBy` or inside an expression is
therefore a compile error rather than a runtime failure (P-2, `api/13` R-AGG-05).

**R-COL-16** *(D-115)* **`ScalarField` is one value per row.** `SelectField` permits `ScalarField` and
`AggregateField`, and `ScalarField` permits `ColumnField` and `ExpressionField`. A `switch` over `SelectField` names
three cases.

## 3. `ColumnField` — one column of a model

```java
public sealed class ColumnField<M, T, C> implements SelectField<M, C> permits OrderedColumnField {
    public static <M, T, C> OrderedColumnField<M, T, C> of(Class<M> model, TableField<?, T> table, String attribute,
            Class<C> type);
    public static <M, T, C, F> ColumnField<M, T, C> of(Class<M> model, TableField<?, T> table, String attribute,
            Class<C> type, Class<F> attributeType, ColumnConverter<C, F> converter);
    public static <M, T, C, F> OrderedColumnField<M, T, C> of(Class<M> model, TableField<?, T> table, String attribute,
            Class<C> type, Class<F> attributeType, OrderedColumnConverter<C, F> converter);
    public static <M, S, C> OrderedColumnField<M, S, C> over(Class<M> model, TableField<S, S> root,
            OrderedColumnField<S, ?, C> field);                                // a source's column, read by name
    public static <M, S, C> ColumnField<M, S, C> over(Class<M> model, TableField<S, S> root, ColumnField<S, ?, C> f);
    public static <M, S, C> OrderedColumnField<M, S, C> over(Class<M> model, TableField<S, S> root,
            SelectField<S, C> field);
    public Path<C> path(JoinContext ctx);
    public <M2> ColumnField<M2, T, C> withTable(Class<M2> model, TableField<?, T> table);   // re-root under a join
}

public final class OrderedColumnField<M, T, C> extends ColumnField<M, T, C> { /* withTable returns OrderedColumnField */ }
```

`OrderedColumnField` is a column with no converter or with an `OrderedColumnConverter`; any other converter gives a
plain `ColumnField`. `ColumnField.of` returns the narrower type whenever it can, also at run time when the converter's
static type is wider; `equals` ignores the subclass (D-93).

`ColumnField.over` is the source counterpart of `of` (R-COL-30, `api/17` R-SRC-10): it reads the CTE column the source
constant names with `named`, and copies that constant's type and converter. It returns an `OrderedColumnField` when
the source constant is one, and `MQ1905` when `root` is not a source root, when the constant has no property, or when
a non-ordered converted column is taken through the `SelectField` overload.

**R-COL-07** A column does not have to be mapped. A **filter-only column** has no field on the model; the mapper only
reads the columns it knows, so a filter-only column never affects the result even if it ends up in a `SelectSet`.

**R-COL-08** *(was R11)* **Column types match entity attributes.** Generated columns are checked by the processor
(`processor/32`). Hand-written columns are checked at first path resolution — `path.getJavaType()` against
`ColumnField.type`, through a converter allow-list (D-20) — and throw `MQ1001` on a mismatch (INV-3). A column
whose attribute type is `java.util.Date` also matches a reported `java.sql.Date`, `java.sql.Time` or `java.sql.Timestamp`:
JPA stores a `Date` attribute as one of the three, and a provider may report that type, as Hibernate does (D-122). The same
resolution throws `MQ1002` for an attribute, of a column or a join, that the entity does not have, and `MQ1003` for a
column whose path starts at a root the query is not rooted at, which would otherwise read the query root's attribute of
the same name. A supertype root is accepted; a strict subtype root is refused with a message naming
`ROOT.treat(<Subtype>.class)` (R-COL-34). An attribute may be a dotted path through embedded values
(`"address.city"`); a segment that is unknown
or crosses an association throws `MQ1002` naming it (D-41).

**R-COL-14** **A converted column.** A column built with a `ColumnConverter<C, F>` has the model type `C` as its type
and reads an attribute of type `F`: `MQ1001` compares the attribute against `attributeType`, `Row.get` returns
`toModel` of a non-null value, and a value filter binds `toAttribute` of its value. The converter is stateless and is
never given `null`. `withTable` keeps it, and two columns are equal only when their converters are of the same class.
`path(ctx)` is the attribute's path, so a custom predicate on it compares attribute values (D-37). An
`OrderedColumnConverter<C, F>`, a `ColumnConverter` with no method of its own, promises that the conversion preserves
order both ways, and so is injective: the column is an `OrderedColumnField`, which `min`, `max` and `countDistinct`
take, while a column with any other converter is a plain `ColumnField` and they do not compile (`api/13` R-AGG-04,
D-93).
`core` ships two, each a singleton `INSTANCE`: `InstantTimestampConverter` (`Timestamp.from`/`toInstant`, nanoseconds
kept) and `DateTimestampConverter`, whose `toModel` returns the `Timestamp` itself typed as `Date`, so a value read
back binds exactly what was read. Range filters and `orderBy` on a converter that is not ordered stay allowed: they
compare stored values, and keyset cursors read `Row.raw` (D-84). A value a converter cannot convert (its `toAttribute`
throws `IllegalArgumentException`, as `InstantTimestampConverter` does for an `Instant` beyond `Timestamp`'s range) is
refused with `MQ1308` when a value filter or a write converts it. A plain `java.util.Date` binds at whole milliseconds, so
an inclusive upper bound is written half-open, `lt(nextDayStart)`: `lte(23:59:59.999)` excludes a stored
`23:59:59.999500`.

## 3a. `ExpressionField` and `Expr` — a value the database computes

```java
public sealed class ColumnField<M, T, C> implements ScalarField<M, C> permits OrderedColumnField { … }   // §3

public final class ExpressionField<M, C> implements ScalarField<M, C> {
    public Class<C> type();
    public String name();                                  // the property when named, else canonical text: coalesce(OrderView.discount, ?)
    public Expression<C> expression(JoinContext ctx);      // one Criteria node per JoinContext for equal expressions
    public ExpressionField<M, C> named(String property);   // not part of equals, as ColumnField.named (D-55)
    public Optional<String> property();
    @EngineFacing public List<ColumnField<M, ?, ?>> columns();   // every column read in the row, CASE conditions included
}

public final class Expr {
    public static <M, C> ExpressionField<M, C> coalesce(ScalarField<M, C> first, ScalarField<M, C> second);
    public static <M, C> ExpressionField<M, C> coalesce(ScalarField<M, C> first, C fallback);
    public static <M, C> ExpressionField<M, C> nullIf(ScalarField<M, C> value, C sentinel);
    // plus, minus, times: same type, a value, or mixed operands with an explicit result type
    public static <M, C extends Number> ExpressionField<M, C> plus(ScalarField<M, C> a, ScalarField<M, C> b);
    public static <M, C extends Number> ExpressionField<M, C> plus(ScalarField<M, C> a, C b);
    public static <M, C extends Number> ExpressionField<M, C> plus(ScalarField<M, ? extends Number> a,
                                                                   ScalarField<M, ? extends Number> b, Class<C> type);
    public static <M, C extends Number> ExpressionField<M, C> dividedBy(ScalarField<M, C> a, ScalarField<M, C> b);
    public static <M, C extends Number> ExpressionField<M, C> dividedBy(ScalarField<M, ? extends Number> a,
                                                                        ScalarField<M, ? extends Number> b, Class<C> type);
    public static <M, C extends Number> ExpressionField<M, C> negate(ScalarField<M, C> value);
    public static <M> ExpressionField<M, String> concat(ScalarField<M, String> a, ScalarField<M, String> b);
    public static <M> ExpressionField<M, String> concat(ScalarField<M, String> a, String b);
    public static <M> ExpressionField<M, String> concat(String a, ScalarField<M, String> b);
    @SafeVarargs public static <M, C> ExpressionField<M, C> function(String name, Class<C> type, ScalarField<M, ?>... args);
    public static <M> ExpressionField<M, String>  constant(String value);   // SQL text, never a request value
    public static <M> ExpressionField<M, Integer> constant(int value);
    public static <M> ExpressionField<M, Long>    constant(long value);
    public static <M> ExpressionField<M, Boolean> constant(boolean value);
    public static <M, R> Cases<M, R> cases(Class<M> model, Class<R> type);

    public static final class Cases<M, R> {                // only when(...): a CASE with no WHEN does not compile
        public When<M, R> when(UnaryOperator<Filters<M>> condition, ScalarField<M, R> result);
        public When<M, R> when(UnaryOperator<Filters<M>> condition, R result);
    }
    public static final class When<M, R> {
        /* the two when(...) */ public ExpressionField<M, R> otherwise(ScalarField<M, R> result);
        public ExpressionField<M, R> otherwise(R result);
        public ExpressionField<M, R> orNull();
    }
}
```

`Expr` mirrors `Agg`: it builds the field, and `ExpressionField` is the field type. `M` is the column vocabulary, a
generated model or the entity class for hand-written columns, and `C` the Java type; there is no `T`, because an
expression may span tables. `cases` takes the model class so the condition lambda's `Filters<M>` is inferred.

**R-COL-17** *(D-115)* **An expression is a typed value computed by the database.**
- It is built only by `Expr`'s factories, each one JPA `CriteriaBuilder` construct: `coalesce`, `nullIf`,
  `cases(...).when(...).otherwise|orNull`, `plus`, `minus`, `times`, `dividedBy`, `negate`, `concat`, `function`,
  `constant`. It reads columns of one vocabulary `M`, and holds no aggregate, join, order or SQL text of its own.
- **Arithmetic** takes operands of one numeric type, or declares the result type when they differ; the provider's
  resolved type must equal the declared one, else `MQ1507` at first resolution; a declared `java.util.Date` also takes
  a resolved `java.sql.Date`, `Time` or `Timestamp`, as R-COL-08 does (D-122). `dividedBy` over two integral operands
  is `MQ1503`.
- **`concat`** is NULL when any operand is NULL.
- **CASE conditions** are a `Filters` group with local skipping (`api/12` R-FLT-01). A condition left with no filter is
  `MQ1504`; `add`, `exists`, sub-selects and an `op` (`api/12` R-FLT-20) are `MQ1505`.
- **`function(name, type, args)`** takes a plain identifier that is not a built-in aggregate, else `MQ1506`. It is
  vendor SQL, and must be deterministic to order a paged or exported query (`engine/21` R-PAG-25).
- **A column with a `ColumnConverter`** is `MQ1501`.

**R-COL-18** *(D-115)* **Values bind, constants are text.**
- A value given to a factory (a `coalesce` fallback, a `nullIf` sentinel, an arithmetic or `concat` operand, a CASE
  result) is a bind parameter typed by its operand (`api/12` R-FLT-08).
- `null`, `Optional`, arrays, `Collection`, `Map`, `Date`, `Calendar`, enums and entities are `MQ1502`.
- `Expr.constant(String|int|long|boolean)` is SQL text of the definition, rendered by the provider's literal formatter,
  for a function's mode argument or a key a functional index must match. It is never a request value.

**R-COL-19** *(D-115)* **One expression, one Criteria node per statement.** `expression(ctx)` returns the same
Criteria expression for equal expressions within one `JoinContext`. Selection, group key, order key, tie-breaker, count
and filters of one statement therefore share it: its values bind once per occurrence, and a provider that references
select items by identity (Hibernate) renders `GROUP BY` and `ORDER BY` as references, so PostgreSQL matches a key that
binds a value. The columns it reads join as each column would where the expression is used (`api/12` R-FLT-10 inside
`or` and `not`).

**R-COL-20** *(D-115)* **Equality and naming.** Two expressions are equal when built from equal calls: node kind,
operands, values (`equals`, so `BigDecimal` scale counts), declared type, function name and CASE conditions.
`named(property)` sets the model field's name for `orderedBy` and messages and is not part of equality, as
`ColumnField.named` is (D-55). Equal expressions are one selection, and each reads it through `Row.get`. An expression
is immutable and may be a constant (INV-9).

## 4. `SelectSet` — an immutable named set

```java
public final class SelectSet<M> {
    @SafeVarargs public static <M> SelectSet<M> of(SelectField<M, ?>... columns);
    public SelectSet<M> with(SelectField<M, ?>... extra);   // returns a copy
    public SelectSet<M> with(SelectSet<M> other);
    public SelectSet<M> without(SelectField<M, ?>... columns);
    public List<SelectField<M, ?>> fields();                // unmodifiable
    @Incubating public boolean contains(SelectField<M, ?> field);
    @Incubating public SelectSet<M> selectedIn(Row row);
}
```

**R-COL-09** A `SelectSet` is immutable. `with` and `without` return copies, so a shared constant cannot be changed by
one caller and affect every later query (INV-9).

**R-COL-21** *(D-120)* **Membership and equality.** `contains(field)` is exact membership by field equality, never
through a converter, as `Row.isSelected` is; it is `@Incubating`. Two sets are equal when they hold the same fields,
whatever their order, and `hashCode` agrees (set semantics), so a record holding one stays comparable. `toString` lists
the fields in set order, like `[OrderView.id, OrderView.status]`. Equality is not separately marked: it freezes with
`SelectSet`.

**R-COL-22** *(D-120)* **`selectedIn(Row)`.** Returns the subset of this set's fields for which `Row.isSelected` is
true (a scoped row answers for its nested model's columns), in set order; returns `this` when all are selected; never
`null`. It is `@Incubating`. It keeps a single-entry memo keyed by the selection as a `long[]` bit mask, held in a
`volatile` immutable holder, so the rows of one query share one instance and the memo is not observable (INV-9). It is
safe under concurrent calls with different selections: a call that loses the race returns a correct result and the
last stored entry wins.

**R-COL-23** *(D-121)* **`FieldIndex.resolve(names)`.** A `FieldIndex<M>` (`@Incubating`) holds a model's select fields,
filter-only columns, sets and children under exact, case-sensitive keys (RFC 0006). `resolve(names)` turns each name
into a select field, a set or a child, looked up in that order of kinds, and collects every name that matches none of
them in `unknown`, distinct, in input order. `select` holds the select fields in input order, a set expanded in place,
the first occurrence of a field kept. `children` holds each child once, in input order. A filter-only column counts as
unknown, and so does `""`; a `null` element throws `NullPointerException`; otherwise `resolve` never throws. An input
with no select field or set gives an empty `select`. The `Resolution` lists are copies (CC-IMM). `select(name)`,
`filter(name)` (a column, filter-only ones included, or an expression), `set(name)` and `child(name)` look one name up
by kind; `names()` is what `resolve` accepts and `filterNames()` what `filter` accepts. The key of a select field is a
column's property path, else its attribute path, an expression's name and an aggregate's `named` property, else its
name: tier 1 of `orderedBy` (R-QRY-14), through one helper both use. A filter-only column's key is its attribute path;
a join set's key is its join's property path. `AggregateField.named(String)` sets the property, outside `equals` and
`hashCode`, as `ExpressionField.named` does (R-COL-20). Within one kind, a key given twice with an equal field keeps one
entry; with a different field, `FieldIndex.Builder.build()` throws `IllegalStateException`. The filter names (select
columns and expressions, then filter-only columns) are one kind for this rule.

**R-COL-24** *(D-121)* **`FieldIndex.only(names)`.** Returns an index holding just those keys, for a public API that
exposes part of a model. A kept set holds only the select fields the narrowed index keeps, so `only(List.of("ALL",
"id"))` can't select more than `id`. A name that is not a key (of `names()` or `filterNames()`), or a kept set left
empty, throws `MQ1105` naming it and the index's `names()` and `filterNames()`.

## 5. `Row` and `RowMapper`

```java
public interface Row {
    <C> C get(SelectField<?, C> column);              // null when the column is NULL or wasn't selected
    Object raw(SelectField<?, ?> column);             // the value as read, before any ColumnConverter
    boolean isSelected(SelectField<?, ?> column);
    Row scoped(TableField<?, ?> join);                // view of a nested model's columns under a join
}

@FunctionalInterface
public interface RowMapper<M> {
    M map(Row row);
    static <M> SetterMapper<M> setters(Supplier<M> factory);    // .bind(ID, OrderView::setId).bind(...)
}
```

**R-COL-10** Queries select a JPA `Tuple`. The engine wraps it as a `Row` keyed by `SelectField` and hands it to the
query's `RowMapper`. Values are never read by tuple index at any layer above the wrapper, so inserting a selection
cannot shift a mapping. Columns of one model over the same attribute of the same table, whatever their types and
converters, are one selection under one alias, and each reads the value through its own converter.

**R-COL-11** Because a model is built from a complete row in one call, classes and records use the same engine.
Primary keys, keyset cursors and export dedupe read from the `Row` before mapping, so a model needs no key accessor and
no base class. They read `Row.raw`, the attribute value, so a converter that maps two attribute values to one model
value cannot merge two keys or move a cursor (D-37).

A hand-written record model passes a lambda: `row -> new OrderView(row.get(ID), row.get(STATUS), …)`. A class model
may use `RowMapper.setters(factory)`, whose `bind` takes a `SelectField<M, C>` of the mapper's own model: another
model's column is never selected, so it would set `null` on every row, and it does not compile (D-88). Generated
mappers are `processor/31`.

## 6. `OrderField` and null precedence

```java
public record OrderField<M, C>(SelectField<M, C> column, boolean ascending, NullPrecedence nulls) {
    public OrderField<M, C> nullsFirst();
    public OrderField<M, C> nullsLast();
    public OrderField<M, C> nulls(NullPrecedence nulls);   // for a value chosen at runtime
}

public enum NullPrecedence { DEFAULT, FIRST, LAST }        // DEFAULT = whatever the database does
```

**R-COL-12** `asc()` and `desc()` start at `NullPrecedence.DEFAULT`, which renders no null clause. `nullsFirst()` and
`nullsLast()` mean the same thing on every vendor regardless of that vendor's default:

- With `model-query-hibernate` present, the order is rendered through
  `HibernateCriteriaBuilder#sort(expr, direction, nullPrecedence)`. Hibernate emits `NULLS FIRST/LAST` where the
  dialect supports it (H2, PostgreSQL) and emulates it where it does not (MySQL).
- Without it — plain JPA 3.1 has no null precedence in `Order` — the engine prepends a sort key
  `CASE WHEN col IS NULL THEN 0 ELSE 1 END` (ASC for `FIRST`, DESC for `LAST`). Portable, but it prevents an index on
  `col` from serving the sort.
- Without it the key is rendered even where the vendor's default already matches the requested precedence: a bare
  order would take a default null ordering the provider is configured with, which nothing reports there (D-36).
  With it, Hibernate omits the clause where the dialect's default matches.
- On a grouped query ordered by an expression with `nullsFirst()` or `nullsLast()`, the engine always renders the
  portable key, wrapped in `MIN(...)`, then the expression, with or without `model-query-hibernate` *(D-115)*. A null
  test over a key that binds a value re-renders it with its own parameters, which PostgreSQL and MySQL do not match to
  the `GROUP BY` item, while an aggregate may read any column; the key is constant within a group, so `MIN` is its
  value. Hibernate's own emulation, where the dialect has no `NULLS FIRST/LAST`, would re-render it the same way.

**R-COL-13** Keyset paging on a nullable column with `DEFAULT` precedence uses
`VendorProfile.defaultAscendingNullOrdering()`, and is refused when that is `UNKNOWN` (`engine/21` R-PAG-05). A
default null ordering the persistence provider is configured with replaces the profile's (D-36).

**R-COL-25** *(D-124)* **To-one columns read through a LEFT join.** In a read, a column whose attribute is a to-one
association, whole-entity (`MQ3016`) or converted (`MQ3014`), resolves through a `LEFT` join of the association, not the
implicit inner join of `root.get`, so a row with a `NULL` foreign key reads back with `null` instead of being dropped.
The rule holds on any table the column belongs to (the root, a `@Join`ed table or a child's root) and in every clause
of the read (select, filter, order, group, keyset), so a filter on such a column adds the `LEFT` join and an order on
it sees the `NULL`-foreign-key rows (a keyset order on it then needs `nullsFirst` or `nullsLast`, `engine/21`
R-PAG-05). It does not hold inside an embedded value (a dotted attribute path), which keeps `root.get`, nor in a bulk
write, which takes no join. A converter is never given the `null`.

**R-COL-29** *(D-136)* **A to-one read by its id.** A column whose attribute is a to-one association and whose type,
or whose converter's attribute type, is the target's single id of persistence type `BASIC` (`ColumnField.of(Order.class,
ORDERS, "referrer", Long.class)`, `@Column(attribute = "referrer") Long referrerId` on a query model) reads the
association's id, the foreign key, instead of `MQ1001`; for another type `MQ1001`'s message names the id type. The
dotted `referrer.id` stays a path through an embeddable (`MQ1002`), so the spelling is one.

- In every clause of a read (select, filter, order, group, keyset, the primary-key-first phases, `count`, export, and
  the source select of an insert-select) it
  reads through R-COL-25's `LEFT` join and the target's id, on every mapping (owning or inverse, a non-key
  `referencedColumnName`, a `@JoinFormula`, `@NotFound`), so a `NULL` foreign key reads back `null` and no row is
  dropped (INV-4, INV-5). The sub-queries of a read (`exists`, `in(subSelect)`, `exists(subSelect)`) read it through
  the same join. A locked read of it has that join, so it is `MQ2211` (`api/11` R-QRY-17); Q-20 keeps a join-free read
  open. The id comes from the joined row, so a target with `@SQLRestriction` or `@Where` reads `null` while the foreign
  key holds a value; the processor refuses the id form there (`MQ3029`, `processor/30` R-PROC-33), and a runtime
  `@Filter` on the target is a documented caveat (the read returns `null`).
- In a write's filter (a bulk update or delete, the sub-select its joined `where` renders, the load of
  `throughEntities()`, a key select) it renders the foreign-key column through the association's path, which takes no
  join only when the foreign key is a column of the entity's own table referencing the target's primary key. The
  executor asks the provider's `ProviderSupport.foreignKeyColumn` for each such column once per definition and factory,
  before any statement, and refuses it with `MQ1004` unless the provider answers `LOCAL_COLUMN` (`vendor/40` R-VND-13).
  It checks every clause the write renders: the `where`, the set clause (a `setExpression` or computed target and the
  columns read in it) and an upsert's `doUpdate(...).where(...)`. Generated models never reach `MQ1004`, since the processor refuses those mappings.
- A returning write reads it from the written row (`api/14` R-WRT-48, R-WRT-50); a set clause writes it by id as an
  update model's column does (R-GEN-19).

**R-COL-26** *(D-128)* **A bound argument.** `Expr.value` renders a value as a statement parameter, for a function
argument or an operand the planner must not see as a literal:

```java
@Incubating public static <M, C> ExpressionField<M, C> value(C value);
```

- The expression is typed by `value.getClass()`, reads no column and adds no join; its value binds on every statement
  that renders it (R-FLT-08), one bind per rendering.
- Two are equal when their classes and values are equal (R-COL-20); `null`, an `Optional`, a `Collection`, a `Map` and
  the R-COL-18 types are `MQ1502` — a collection is never a single bound value.
- A function reading it is evaluated by each statement that renders it, so the pages of a paged read or of an export
  may see different values (INV-4); a value that must be stable across a run is read from a column.

**R-COL-27** *(D-128)* **A column-typed constant.** `Expr.constant` also takes a column as a type witness, for a value
the planner must see as a literal (a partial index's predicate, a selective enum value):

```java
@Incubating public static <M, C> ExpressionField<M, C> constant(ColumnField<M, ?, C> typedBy, C value);
```

- `value` goes through `typedBy`'s `ColumnConverter` (`toAttribute`, `MQ1308` on failure, R-COL-14), then through the
  attribute's JPA mapping — `@Enumerated(STRING)` stores the constant's name, `ORDINAL` its ordinal, an
  `AttributeConverter` its converted database value — through an optional `ProviderSupport` capability (`vendor/40`
  R-VND-16) that `core` reaches through a `JoinContext` hook, so `core` names no provider (INV-7).
- The stored value must be a `String`, `Number` or `Boolean`, else `MQ1508`; a provider without the capability, and a
  `typedBy` that is not a basic attribute of a root table's entity (a joined column names no entity class), are
  `MQ1508` too, because only the provider can read the mapping. The message names which of the three applies.
- A column-typed constant is operand-only: a comparison or `in`/`notIn` operand. As a model column or projection, an
  order or a group key it fails at definition with `MQ1508`.
- The expression's declared type is `typedBy`'s model type, and the rendered literal's provider type is the stored
  value's; those two are not compared, so `MQ1507` does not apply (R-COL-17).
- The result renders as the provider's literal text, as an R-COL-18 constant does, and binds nothing: each distinct
  value is a distinct statement text, so a per-request value uses `Expr.value` (R-COL-26) instead.
- `typedBy` is a type witness only: it is part of the expression's equality (R-COL-20), contributes no column to
  `columns()` and adds no join.

**R-COL-28** *(D-129, D-130)* **A sequence value.** `Expr.nextval` reads a database sequence's next value:

```java
@Incubating public static <M> ExpressionField<M, Long> nextval(String sequence);
```

- It is a model column or projection of a read, `Expr.value`'s sibling, and the value a write writes: typed `Long`,
  reads no column and adds no join. The provider renders the dialect's own sequence call (`nextval('s')` on PostgreSQL,
  `next value for s` on H2) through an optional `ProviderSupport` capability (`vendor/40` R-VND-18) that `core` reaches
  through a `JoinContext` hook, so `core` names no provider (INV-7); the profile states only whether the vendor has
  sequences (`sequences()`, R-VND-11).
- A write takes it through `setExpression(column, ExpressionField)` at four sites, an update assignment, a conflict
  assignment, an insert-values value and an insert-select column (`api/14` R-WRT-53, D-130), each evaluated once per
  written row. The update and conflict hooks that take a `CriteriaBuilder` expression cannot be handed an
  `ExpressionField`, so it is a separate overload.
- `sequence` is a plain SQL identifier, optionally schema-qualified (`a.b`); any other text is `MQ1509` at definition,
  so no text reaches SQL that is not an identifier.
- A vendor whose profile's `sequences()` is false (MySQL), and a provider without the capability, are `MQ1509` at first
  resolution, before any statement, with a message naming both causes (the profile fact and the provider capability);
  on a write, before the flush, a chunk's key select or any statement.
- It is refused with `MQ1509` as the operand of a filter (a CASE condition included), a `having`, an order or a group
  key, at any depth of an expression, and also when the select rendered the same expression first: a sequence advances
  on each evaluation, so a condition or key over it would not be stable.
- A `distinct()` query selecting an `Expr.nextval` is refused with `MQ1509`: every row gets its own value, so `DISTINCT`
  would silently stop deduplicating.
- Every evaluation consumes a value: `one(q)`, which reads with `LIMIT 2`, consumes a second value when a second row
  matches, and a re-run of a query consumes new ones.
- Equality is by sequence name (R-COL-20).

## 7. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-COL-01 | Selecting, filtering and ordering on one path renders exactly one join; `as(...)` renders two; children of an aliased join stay separate (R-COL-03, R-COL-05). |
| AC-COL-02 | `on(...)` without an alias throws; two `TableField`s with the same key and different conditions throw `MQ1101`; `as(...)` or `on(...)` on a root throws `MQ1104` (R-COL-04). |
| AC-COL-03 | `on(...)` on a LEFT join keeps rows with no matching child (R-COL-03). |
| AC-COL-04 | A hand-written `ColumnField` whose type does not match the entity attribute throws `MQ1001` at first resolution; an unknown attribute throws `MQ1002` and a column on another entity's root `MQ1003` (R-COL-08). |
| AC-COL-05 | `SelectSet.with`/`without` leave the original set unchanged (R-COL-09). |
| AC-COL-06 | Every `ColumnField` type round-trips through `Row.get`, including converters, for a class model and an equivalent record model (R-COL-10). |
| AC-COL-07 | A filter-only column filters correctly, adds no join when its filter is skipped, and never reaches the model (R-COL-07). |
| AC-COL-08 | `nullsFirst()`/`nullsLast()` produce identical orderings on every Tier-1 vendor, with and without `model-query-hibernate` (R-COL-12). |
| AC-COL-09 | A `ModelQuery` stored in a `static final` field is used concurrently by 8 threads with identical results (INV-9). |
| AC-COL-10 | A column whose attribute is a dotted path through an embedded value selects and filters that value; an unknown segment, or one crossing an association, throws `MQ1002` naming the segment (R-COL-08, D-41). |
| AC-COL-11 | A column built with a `ColumnConverter` round-trips through `Row.get` and through a filter on the same column, while `Row.raw` returns the value unconverted, which primary keys and keyset cursors are read as; `sum`, `sumAsLong` and `avg` over it throw `MQ1408`, and `min`, `max` and `countDistinct` take an ordered one only (AC-AGG-13) (R-COL-14, R-COL-11, D-37). |
| AC-COL-12 | Selecting a column of a `presentBy` join also selects the join's presence key, and that of every such join above it, in every model phase and once only, so a mapper tells an absent row from a match whose columns are all `NULL`; a grouped query whose group keys lack the key throws `MQ1409`, and `presentBy` on a root `MQ1104` (R-COL-15, D-38). |
| AC-COL-13 | `InstantTimestampConverter` and `DateTimestampConverter` round-trip with nanoseconds kept and keep order both ways; a `Timestamp` attribute holding sub-millisecond digits is read, filtered with `eq`, `gt`, `lte` and `between`, sorted and keyset-paged through each, and a value read back through `DateTimestampConverter` binds exactly what was read; an `Instant` beyond `Timestamp`'s range is refused with `MQ1308` (R-COL-14, D-84). |
| AC-COL-14 | A model selecting one attribute through several columns, a `Timestamp` read as an `Instant`, a `Date` and itself, is read through `list`, offset and primary-key-first `page`, `count`, `stream` and keyset export across several pages, and as two group keys through `list`, `count` and export, each column holding its own converted value (R-COL-10). |
| AC-COL-15 | A generated `@Join` follows a `@JoinColumn(referencedColumnName)` association to a unique non-key column: the nested model is selected, its columns filter and sort the query, and a fetch plan loads it, each matched through the referenced column and not the target's key (R-COL-01, R-COL-03, D-111). |
| AC-COL-16 | A generated `@Join` follows a Hibernate `@JoinFormula` association: the nested model is selected, filtered, sorted and fetched through the computed key (R-COL-01, R-COL-03, D-111). |
| AC-COL-17 | A hand-written `TableField` with `as(...)` and `on(...)` adds an extra ON condition to a `ModelQuery` join, keeping the rows whose join then misses with a `NULL` nested column — the documented replacement for the declined `@Join(on = ...)` (R-COL-03, R-COL-04, D-111). |
| AC-COL-18 | An `AggregateField` passed to a `Filters` operator, to `groupBy(...)` or to an `Expr` factory, and `Expr.cases` with no `when` before `otherwise`, each have a compile-failure case. Two expressions built from equal calls are equal and hash alike, whatever `named` says; they differ by a value, a `BigDecimal` scale, the declared type and a function name. An expression constant is used by 8 threads with identical results (R-COL-16, R-COL-20, INV-9). |
| AC-COL-19 | On every Tier-1 vendor, each factory returns the expected value and Java type: `coalesce` and `nullIf` over NULL and non-NULL; same-type and mixed `times` (`Integer × BigDecimal` gives `BigDecimal`); `dividedBy` over decimals; `concat` with a NULL operand (NULL); a CASE with column and value branches; a CASE whose every branch is a value with three decimal places, returned exactly; `function` with an `Expr.constant` mode argument. `MQ1501` to `MQ1507` each have a case (R-COL-17, R-COL-18). |
| AC-COL-20 | A selected expression round-trips through `Row.get` for a class and a record model. Two equal expressions under different `named` properties are one selection, and each reads the value. The PostgreSQL statement log shows one rendering of an expression used in select, order and filter of one statement (R-COL-19, R-COL-20, R-COL-10). |
| AC-COL-21 | `contains` is exact: a field equal by value is found, a column with another converter or table is not. Sets with the same fields in another order are equal and hash alike; `toString` reads `[OrderView.id, OrderView.status]` (R-COL-21). |
| AC-COL-22 | `selectedIn` returns the selected subset in set order, `this` when all are selected, an empty set when none; it works on a scoped row and on a set of more than 64 fields, returns one shared instance for rows of one selection, and is correct under 8 threads with different selections (R-COL-22, INV-9). |
| AC-COL-23 | `resolve` returns known names' fields in input order with a set expanded in place and a repeated field once, children once each in input order, and unknown names distinct in input order; a filter-only name and `""` are unknown, a `null` element throws `NullPointerException`, an empty input gives an empty `select`, and the `Resolution` lists are copies. `select`, `filter`, `set`, `child`, `names()` and `filterNames()` agree with it (R-COL-23). |
| AC-COL-24 | `only` narrows a kept set to the kept select fields and keeps filter-only columns and children by name; an unknown name, or a set left empty, throws `MQ1105` naming the name and the index's names (R-COL-24). |
| AC-COL-25 | The builder derives each key as R-COL-23 says (property path, else attribute path; a filter-only column by its attribute path; a join set by its join's property path), keeps an equal field given twice once, and throws `IllegalStateException` for a different field under one key. |
| AC-COL-26 | `AggregateField.named` is outside `equals` and `hashCode`, is kept by `as`, and is the aggregate's index key; a sort property equal to the `named` property or to the name selects the aggregate (R-COL-23, R-QRY-14). |
| AC-COL-27 | Names from a `?fields=`-style list resolved through the generated `fields()` narrowed by `only(...)` and put into a query: the executed statement joins only the tables the selected fields need, a `@Selected` field holds exactly the selected columns and the unselected fields stay unfilled, an unknown name is reported in `unknown` and not thrown, and with only a child name `select` is empty and the application's fallback to `DEFAULT` runs (R-COL-23, R-COL-24, R-GEN-32). |
| AC-COL-28 | A generated model whose `Date` fields mirror an entity's `Date` attributes stored as `DATE`, `TIME` and `TIMESTAMP` (`@Temporal(DATE)`, `@Temporal(TIME)`, none) compiles, reads each value as the `java.sql` type the provider returns, filters each with a `Date` value, and keyset-pages over the `DATE` one (R-COL-08, D-122). |
| AC-COL-29 | An `@Immutable` entity over a view created in the TCK schema and an `@Immutable @Subselect` entity are roots on every Tier-1 vendor: a query model over each lists, filters and offset-pages rows in a stable order, and a grouped query returns the counts and sums the view's own rows hold (R-COL-01). |
| AC-COL-30 | `Expr.function` with an `Expr.constant` argument is selected and grouped on every Tier-1 vendor — PostgreSQL and H2 `date_trunc('day', col)`, MySQL `date_format(col, '%Y-%m-%d')` — and on PostgreSQL only `timezone('UTC', timezone('Asia/Ho_Chi_Minh', col))` reads a Ho Chi Minh local time as UTC, 7 hours earlier, whatever the JVM zone (R-COL-18). |
| AC-COL-31 | On every Tier-1 vendor an `Expr.value` argument binds — the snapshot shows `?` where a constant would be text — on each statement path: list, count, offset page, primary-key-first (both steps), keyset, grouped and offset export, `exists` and sub-select bodies, CASE conditions, bulk update and bulk delete, `one`, `one(key)` and `byKeys`, `throughEntities` writes, insert-from-select, and a bulk update or delete with a joined `where`; an `Expr.value` in a model column (projection) and in an order, for `count`, offset `page`, `one`, `byKeys` and primary-key-first. PostgreSQL groups by `timezone(?, created_at)` and filters by `jsonb_exists(tags, ?)`. A `null`, `Optional`, `Collection` or `Map` value is `MQ1502` (R-COL-26, R-COL-18, R-COL-19). |
| AC-COL-32 | On every Tier-1 vendor, `Expr.constant(typedBy, value)` filters and compares through a `@Enumerated(STRING)` enum, an `@Enumerated(ORDINAL)` enum, an `AttributeConverter` attribute (stored codes), a native-enum JDBC type mapping and a column with a `ColumnConverter`, inlining each as its stored literal; the witness is equal by `equals` but adds no column and no join; a null value is `MQ1502`, a value its converter refuses `MQ1308`, a stored value that is not a `String`, `Number` or `Boolean`, a provider without the capability, and a column-typed constant used outside a comparison or `in` operand `MQ1508` (R-COL-27, R-COL-14, R-COL-20). |
| AC-COL-33 | On H2 and PostgreSQL, `Expr.nextval("seq")` selected over a table's rows returns a distinct value per row and a second query continues the sequence above the first's values; the snapshot shows the dialect's own call. A sequence name that is not an identifier (`a b`, `a;b`, `a.b.c`) is `MQ1509` at definition; a `nextval` in a filter, a `having` (inside an aggregate), an order or a group key, and a `distinct()` query selecting it, is `MQ1509` with no statement run, also when the select renders the same expression first; on MySQL it is `MQ1509` at first resolution with no statement run (R-COL-28, R-VND-18). |
| AC-COL-34 | On every Tier-1 vendor, on both Hibernate lines, a hand-written column of type `Long` over the nullable `referrer` keeps the `NULL`-foreign-key orders in a select, an `isNull` and an `eq` filter, an order with `nullsFirst` and an offset page across the last `NULL`s, and `count`; a keyset export ordered by it with `nullsLast` or `nullsFirst` visits each order once in that order, and without a null precedence is `MQ2202`; a primary-key-first page with a to-many filter returns the same rows as the database; an insert-select whose source selects it inserts the `NULL`-foreign-key orders with a `NULL` value; it reads through a `@Join`ed table, as a child's foreign key, through an inverse `@OneToOne` and through a `@NotFound` to-one whose key is `NULL` or names no row, each without dropping a row; a locked read is `MQ2211` before any statement; a bulk update and a bulk delete filtering by it render the foreign-key column with no join, `throughEntities` writes the same rows, and an `updateReturning` returns it; over `SkuOrderLineEntity`'s non-key `referencedColumnName` a bulk update, `throughEntities` update and bulk delete filtering by it are `MQ1004` and a returning write selecting it `MQ1809` (`MQ4011` where the vendor returns no rows), and a bulk delete filtering by the `@NotFound` to-one or by the inverse `@OneToOne` is `MQ1004`, all before any statement; the snapshots also record the read over that mapping and the read and write over a `@JoinFormula` and a `@MapsId` `@OneToOne` (R-COL-29, R-COL-25, R-WRT-48). |
| AC-COL-35 | On every Tier-1 vendor, a runtime `groupBy` on the id form of `referrer` with a `count` returns the `NULL` group first under `nullsFirst` and each referrer's count, and a grouped export returns the same groups; PostgreSQL accepts the statement (R-COL-29, `api/13` R-AGG-09). |
| AC-COL-36 | Correlated count: non-NULL counts, zero for no match, composition and node equality (R-COL-31). |
| AC-COL-37 | Correlated exists: non-NULL Boolean CASE, empty matches and NULL columns (R-COL-31). |
| AC-COL-38 | Correlated min/max: comparable typing, extrema and NULL for no match (R-COL-31). |
| AC-COL-39 | Correlated sum: SUM typing, filtering, ordering and NULL for no match (R-COL-31). |
| AC-COL-40 | Correlated first: required order, id tie-breaker, R-COL-12 null precedence, NULL without a match, selection/filter/order and lifted to-one correlation on Tier 1 and both Hibernate lines; missing `SubqueryRenderer` is MQ4014 before any statement and per portability target (R-COL-32). |
| AC-COL-41 | Refusals, grouped WHERE and counts retaining lifted joins (R-COL-33, R-EXE-05). |
| AC-COL-42 | To-many count/sum: MQ1511; EXISTS filtering and min/max/exists exemptions (R-COL-33). |
| AC-COL-43 | A correlated value in an update or delete `where` over the target table: MySQL runs key-first and matches the other vendors' rows; a conflict `where` is `MQ1804`, `setExpression` is `MQ1613` (R-COL-31, R-WRT-13, D-153). |
| AC-COL-44 | Per strategy, a treated column in the select list keeps every row, reads the value on subtype and grandchild rows and NULL on sibling rows, alone and in a `coalesce`, in a keyed and in a keyless model; a column over a treated join naming the subtype's own to-one is `MQ1001`, typed as its id or as its entity (R-COL-34, R-COL-35, D-154). |
| AC-COL-45 | Per strategy, `eq`/`in`/`like`/`isNotNull` keep only matching subtype rows; `isNull`, `ne`, `notIn` include sibling rows; `not(eq)` excludes them (SQL `NOT` over NULL, D-5, where JPA's "false" would include them); a treat in `or` keeps the base operand's sibling rows; each `page()` total equals the content's rows (R-COL-35, D-154). |
| AC-COL-46 | Per strategy, an offset order by a treated key places sibling rows by NULL under `nullsFirst`/`nullsLast`; a treated group key, aggregate argument or window argument, in a query or a source, is `MQ4016` before any statement (R-COL-35, D-154, D-162, `api/17` R-SRC-20). |
| AC-COL-47 | Per strategy, a LEFT downcast join is empty for a sibling or absent target and present for the subtype, also when its values are all NULL; INNER drops those parents; on `TABLE_PER_CLASS` an INNER downcast that reads an inherited attribute, its presence key included, is `MQ4016` before any statement, and one keyed by and reading only the subtype's own columns still drops them (D-161); an upcast nested model reads inherited attributes; a join from a treated root (R-COL-34, R-COL-35, D-154, D-161). |
| AC-COL-48 | `MQ1106` for an improper subtype at definition on a root and at first resolution on a join; treating a treat or source root; modifiers on a treated join; an unmapped subtype; a sibling root or sibling re-root. `MQ1104` for `as`/`on`/`presentBy` on a treated root. `MQ1003` names `ROOT.treat(...)` for a subtype-root column in a base-root query (R-COL-34, D-154). |
| AC-COL-49 | `typeOf` selects each row's exact class, `eq` matches exactly, a group by type counts per type and its page total counts the groups, an offset order by it repeats across two runs on `SINGLE_TABLE`, `TABLE_PER_CLASS` and `JOINED` with a discriminator; `JOINED` without one and `typeOf` over a LEFT-joined node are `MQ4016` before any statement; keyset is `MQ1208`; `ofType` of an unmapped type is `MQ1106`, and every type read without the provider's `TreatRenderer` is `MQ4014` (R-COL-36, D-158). |
| AC-COL-50 | Per strategy, a model rooted at a mid-level subtype reads its and the grandchild's rows only in content, count, keyset and export; `MQ4010` on a `JOINED` or `TABLE_PER_CLASS` base root; a lock on a `SINGLE_TABLE` subtype root (R-COL-34, D-154). |
| AC-COL-51 | Per line, a treated column on a `SINGLE_TABLE` column a sibling maps, same-named and differently named, reads NULL on the sibling's rows in the select list, an order and a predicate that matches only the sibling's value; a shared-column site the table lacks is `MQ4016` before any statement (R-COL-35, D-154). |
| AC-COL-52 | Per strategy, `page()`'s total and `count(q)` with a select-only or order-only treat and with `isNull`, `ne`, `notIn` and `not(isNotNull)` equal the content's rows, rendered as `count(root.id)` with no selection or order (SQL snapshot); a grouped `@Subtype` total counts groups; a keyless model's content and a source body select the root id last as `mq_0id`, absent from `fields()`, the default sets, the mapper and `Rows`; a `distinct()` query or a `union`/`intersect`/`except` that would need it is `MQ4016`, and runs when the model selects the root id; the grouped `@Subtype` and source-body cases land with M28.5 and M28.8 (R-COL-35, D-154). |
| AC-COL-53 | An eligible flat read returns the same models, keys and keyset cursors as the general path for class and record models over converted columns, an ineligible read (one with a fetch plan, and only that) takes the general path, and the result list is presized from the materialized result, never from `Limit.of(Integer.MAX_VALUE)` (R-COL-11, D-184). |
| AC-COL-54 | A join filter on a LEFT entity join and on a LEFT to-one join blanks the joined columns and keeps the row, on every vendor and line, in content, count, both primary-key-first steps, keyset and export (R-COL-39, D-193). Tests: TCK `JoinFilterExecutionTest#ac_col_54_entity_join_filter_blanks_the_joined_columns_and_keeps_every_row`, `#ac_col_54_to_one_join_filter_blanks_the_joined_columns_and_keeps_every_row`; core `JoinFilterTest#ac_col_54_*`. |
| AC-COL-55 | A join filter ANDs with a static `TableField.on` in one `ON`, passed to the provider's `on` once; a skipped filter adds nothing; a `where` filter on a blanked column drops the row (R-COL-39). Tests: TCK `JoinFilterExecutionTest#ac_col_55_filter_ands_with_the_static_on_in_one_join`, `#ac_col_55_skipped_filters_add_nothing`, `#ac_col_55_where_filter_on_a_blanked_column_drops_the_row`, `#ac_col_55_semi_joined_where_reads_the_filtered_join_like_the_outer_statement`; core `JoinFilterTest#ac_col_55_*`. |
| AC-COL-56 | `MQ1912`: another table, a to-one or to-many path, `exists`, an aggregate, an `INNER` join, a root, a join an id-form column reads, a sub-query term crossing the filtered join, a filter on a join key the content statement never applies, another join key resolving to the filtered join, a column of the filter reading a path below the filtered join, a filter on an entity join the query does not bind (at `build()`); a `null` value is `MQ1301` at the `joinFilter` call (R-COL-39, D-194). Tests: core `JoinFilterValidationTest#ac_col_56_*` (build-time cases and first-resolution cases against a stub criteria builder), `JoinFilterTest#ac_col_56_null_values_fail_with_mq1301_at_the_join_filter_call`; TCK `JoinFilterExecutionTest#ac_col_56_id_form_column_reading_the_filtered_join_is_refused_before_any_statement`, `#ac_col_56_ac_col_57_filter_on_a_join_the_content_never_creates_is_refused_and_creates_none`, `#ac_col_56_inner_key_resolving_to_the_filtered_join_inside_or_is_refused`; core `JoinFilterTest#ac_col_56_another_key_resolving_to_the_filtered_join_is_refused_in_either_order`, `JoinFilterValidationTest#ac_col_56_column_below_the_filtered_join_in_its_own_filter_is_refused_at_first_resolution`, `#ac_col_56_filtered_join_below_a_retyped_parent_is_applied_not_refused`, `#ac_col_56_correlated_value_reading_the_filtered_join_through_the_outer_column_is_valid`. |
| AC-COL-57 | A `joinFilter` alone creates no association join; the static constants are unchanged after a filtered build (INV-9) (R-COL-39). Tests: TCK `JoinFilterExecutionTest#ac_col_56_ac_col_57_filter_on_a_join_the_content_never_creates_is_refused_and_creates_none`, `#ac_col_57_static_constants_are_unchanged_after_a_filtered_build`; core `JoinFilterTest#ac_col_57_*`. |
| AC-COL-58 | A join that renders `INNER` (declared, and not turned `LEFT` by `or(...)`, `not` or an aggregate filter) with a `joinFilter` ancestor, root or entity join, is `MQ1912` at the first statement that resolves it, in any phase and not at `build()`; a declared `INNER` that `or(...)`, `not` or an aggregate filter renders `LEFT` is allowed (R-COL-39, D-195). Tests: `ac_col_58_*`. |

AC-COL-36..39, 41 and 42 run on every Tier-1 vendor and both Hibernate lines. Each scalar terminal selects,
filters and orders. AC-COL-41 covers all R-COL-33 refusals, including generated annotations and
nested operands, with no statement on a resolution refusal. MQ1314 checks the grouped selection and orders;
a grouped WHERE may lift a non-group-key column and returns the expected groups. It accepts correlated order keys
in offset paging, offset export and primary-key-first. Its count checks omit select-only correlated values,
retain correlated WHERE predicates and an INNER lifted path, and use LEFT lifted joins inside OR/NOT (R-FLT-10).
AC-COL-42 crosses
the to-many path separately through the selected column, own WHERE and inner correlation, checks no statement
runs, and verifies the EXISTS remedy and every exempt terminal.
