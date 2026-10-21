# 12 — Filters DSL

**Covers:** every `Filters` operator, the `Optional` skip contract, `or`/`not`/`when`/`apply`, `exists`, `like`
escaping, and `IN`-list splitting.
**Read when:** adding an operator, or explaining why a filter did or did not narrow a query.
**Owns:** `R-FLT-*`, `AC-FLT-*`. Aggregate conditions are `api/13` (`having`).
What a built query records of its filters (`conditions()`, `add(label, …)`) is `api/16`.

---

## 1. Shape

`where(UnaryOperator<Filters<M>>)` receives a builder that ANDs every filter added to it. Every method that takes a
value has two overloads:

- `(column, C value)` — the filter always applies; `null` throws `MQ1301`, because "no filter" must be explicit (P-3).
  A predicate from `add(...)` that returns `null` throws `MQ1305` (D-24). A `Filters` used outside its own operator,
  or while a nested operator runs, throws `MQ1303` (D-23).
- `(column, Optional<? extends C> value)` — `Optional.empty()` skips the filter, and no join is created for it.

Only the value-form signatures are listed below; each has an `Optional` twin.

```java
public interface Filters<M> {
    // Equality and comparison
    <C> Filters<M> eq(ScalarField<M, C> column, C value);
    <C> Filters<M> ne(ScalarField<M, C> column, C value);                  // NULL rows match, see R-FLT-04
    <C extends Comparable<? super C>> Filters<M> gt (ScalarField<M, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> gte(ScalarField<M, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> lt (ScalarField<M, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> lte(ScalarField<M, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> range(ScalarField<M, C> column,
                                                       Optional<? extends C> fromInclusive,
                                                       Optional<? extends C> toExclusive);
    <C extends Comparable<? super C>> Filters<M> between(ScalarField<M, C> column, C fromInclusive, C toInclusive);

    // Sets
    <C> Filters<M> in(ScalarField<M, C> column, Collection<? extends C> values);
    <C> Filters<M> notIn(ScalarField<M, C> column, Collection<? extends C> values);

    // Strings
    Filters<M> like(ScalarField<M, String> column, String value, LikeMode mode);   // EXACT | CONTAINS | STARTS_WITH | ENDS_WITH
    Filters<M> likeIgnoreCase(ScalarField<M, String> column, String value, LikeMode mode);
    Filters<M> eqIgnoreCase(ScalarField<M, String> column, String value);

    // Nulls
    Filters<M> isNull(ScalarField<M, ?> column);
    Filters<M> isNotNull(ScalarField<M, ?> column);
    Filters<M> isNull(ScalarField<M, ?> column, Optional<Boolean> isNull);         // tri-state request flag

    // Column against column
    <C> Filters<M> compare(ScalarField<M, C> left, Op op, ScalarField<M, C> right);   // EQ, NE, LT, LTE, GT, GTE

    // Composition
    Filters<M> or(UnaryOperator<Filters<M>> a, UnaryOperator<Filters<M>> b);    // each branch is an AND group
    Filters<M> or(UnaryOperator<Filters<M>> a, UnaryOperator<Filters<M>> b, UnaryOperator<Filters<M>> c);
    Filters<M> or(List<? extends UnaryOperator<Filters<M>>> branches);           // built at run time (D-87)
    Filters<M> not(UnaryOperator<Filters<M>> group);
    Filters<M> when(boolean condition, UnaryOperator<Filters<M>> group);
    Filters<M> apply(UnaryOperator<Filters<M>> fragment);    // reuse a shared fragment, e.g. NOT_DELETED

    // Correlated sub-queries
    Filters<M> exists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner);
    Filters<M> exists(TableField<?, ?> path);                 // "has at least one"
    Filters<M> notExists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner);

    // Sub-selects, no Optional twins (R-FLT-15 to R-FLT-17, D-112)
    <C> Filters<M> in(ScalarField<M, C> column, SubSelect<?, C> values);
    <C> Filters<M> notIn(ScalarField<M, C> column, SubSelect<?, C> values);
    <S> Filters<M> exists(SubSelect<S, ?> rows, BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation);
    <S> Filters<M> notExists(SubSelect<S, ?> rows, BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation);

    // Escape hatch
    Filters<M> add(BiFunction<JoinContext, CriteriaBuilder, Predicate> custom);    // D-24
    Filters<M> add(String label, BiFunction<JoinContext, CriteriaBuilder, Predicate> custom); // api/16 R-INS-03
}
```

```java
.where(f -> f
        .eq(QOrderView.STATUS, status)                                   // Optional<String>
        .range(QOrderView.CREATED_AT, from, to)
        .or(g -> g.likeIgnoreCase(QOrderView.CUSTOMER_NAME, q, CONTAINS),
            g -> g.eq(QOrderView.CUSTOMER_EMAIL, q))
        .exists(QOrderView.ITEMS_TABLE, i -> i.eq(QOrderView.ITEM_SKU, sku).gt(QOrderView.ITEM_QTY, 0))
        .when(!includeCancelled, g -> g.ne(QOrderView.STATUS, "CANCELLED")))
```

## 2. Skipping

**R-FLT-01** **Skipping is local.** A skipped filter disappears from its group. An `or(...)` branch whose filters were
all skipped is dropped. If every branch was dropped, the whole `or` is skipped rather than becoming `FALSE` and
matching nothing; an `or(List)` with a single branch is that branch's group. An `or(List)` with an empty list is not
that case: it is "none of these" and renders `FALSE`, as an empty `in` (R-FLT-02, D-92); an allowed-list that is empty
must not return every row. The same holds for `not`, and for `exists` with an inner group: when all its inner filters were
skipped, the `exists` is skipped too. Use `exists(path)` to ask for "has at least one" explicitly.

**R-FLT-02** **Empty collections are not "skip".** `in(col, List.of())` renders `FALSE` and `notIn(col, List.of())`
renders nothing, because an empty selection in a UI means "none of these". `Optional.empty()` is the way to skip
(P-3).

**R-FLT-03** A skipped filter creates no join. A filter-only column whose filter was skipped must not appear in the
rendered SQL at all (`api/10` AC-COL-07).

## 3. Negation and NULLs

**R-FLT-04** `ne` and `notIn` over a nullable column render `col <> ? OR col IS NULL`, which is what a report filter
means by "not X"; plain SQL `<>` drops NULL rows silently. For the strict SQL meaning, combine with `isNotNull(col)`.

**R-FLT-05** `not(group)` is plain SQL `NOT (…)`, because three-valued logic over an arbitrary group cannot be
rewritten portably. The Javadoc states that rows where the group is UNKNOWN are excluded.

## 4. Strings

**R-FLT-06** `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` escape `%`, `_` and the escape character in the value and render
`ESCAPE '\'`, so a search for `50%` finds that literal text. `EXACT` passes the pattern through as given. On a column
with a `ColumnConverter` the value is converted first and the pattern is built from the attribute value (R-COL-14), as
an operator's `prepare` is (R-FLT-21, D-171). On a JPA `@Convert` attribute, Hibernate runs the `AttributeConverter` on
the finished pattern, wildcards and escapes included, for `like` (inlined under `value_handling_mode=inline` too) and
for `HibernateOperators.ilike` through Hibernate's binder (`vendor/40` R-VND-24, D-170), but not for `likeIgnoreCase`
(R-FLT-07), which binds the lower-cased pattern as given and so misses the stored form. `like` and `ilike` thus match a
model value only through a converter that turns a pattern into the stored form's pattern, such as one adding a prefix
(AC-FLT-21, D-171).

**R-FLT-07** `likeIgnoreCase` renders `lower(col) LIKE ?`; the value is lower-cased in Java with `Locale.ROOT` and
bound (R-FLT-08, D-22). A database whose `lower()` folds only ASCII, such as PostgreSQL with C collation, can disagree
on non-ASCII text. The user guide notes that this needs a functional index to be fast, and that case sensitivity
otherwise follows collation (`vendor/40` §4). Known limitation: on a JPA `@Convert` attribute `likeIgnoreCase` (and
probably `eqIgnoreCase`, unconfirmed, Q-30) binds the unconverted, lower-cased value and can miss rows without an error
(D-171, Q-30).

## 5. Bind parameters and large sets

**R-FLT-08** Every value is a bind parameter. The engine never inlines a value into SQL, for any operator. Inside an expression, values bind as `api/10` R-COL-18
states; `Expr.constant` is definition text, not a value, and so is a validated `fullTextMatch` configuration (D-173,
`MQ1315`), which renders as an inline literal.

**R-FLT-09** Lists longer than `VendorProfile.maxInListSize()` render as `col IN (…) OR col IN (…)`, in chunks of at
most that many values in the given order, and `notIn` as an AND of `NOT IN` chunks, ORed once with `col IS NULL` so
NULLs still match (R-FLT-04). An empty list keeps its R-FLT-02 meaning. A query's own statement is never split across
statements, which would merge their rows in memory: a list with more values than `maxBindParameters()` throws `MQ1306`
naming the column when the query is built, and a statement whose binds only together pass that limit, through several
filters, `having`, keyset or `SET` values or a `QueryCustomizer`'s, throws `MQ1307` naming the model, or a write's
entity, before it runs, asking for a narrower filter (INV-5, D-80). The statement check counts the query parameters JPA
reports, as the `vendor/41` R-PRF-11 clamp does: a literal the provider renders inline takes none, an embeddable-valued
parameter counts once though it binds several, and a row limit or offset the provider binds is not counted, so a
statement at the limit can still fail in the database. A keyset statement, which is a keyset page, an export page or a
key-first write round, is checked up front for the worst cursor it can meet: its own binds plus k(k+1)/2 binds for k
keyset keys (all non-null) must fit `maxBindParameters()`, on the first page or round and on a `startAfter` round too,
so a run never fails after rows reached a sink or a round committed. That `MQ1307` says how many binds the statement
has of its own, how many the cursor can add, and that the query's own filters must narrow by at least that much or use
fewer keyset columns (D-82). Only library-built key lists are spread over several statements:
primary-key-first step 2 (`engine/21` R-PAG-07), bulk-write key chunks (`api/14` R-WRT-08) and the keys of
`byKeys(q, keys)` (`engine/20` R-EXE-13; chunked as step 2 without `primaryKeyFirstBatchSize`), each chunk at most the
largest power of two within the limits. The engine counts one bind per value; a provider that pads IN lists, such as
Hibernate with `hibernate.query.in_clause_parameter_padding`, binds up to the next power of two, which keeps a key chunk
within the limits but which a user's own lists must leave room for (`vendor/41`). A statement's chunk sizing counts an
expression's binds too, once per rendering (an `Expr.value` binds one, R-COL-26; a memoised expression renders once,
R-COL-19).

## 6. Joins created inside `or` and `not`

**R-FLT-10** *(was R14)* A join first needed inside `or` or `not` is resolved as LEFT. An INNER join created for one
branch would remove rows another branch should match. If the same path is already joined as INNER elsewhere in the
query, that join is reused, because those rows are already required.

`notExists(subSelect, correlation)` is a negation like `not(g -> g.exists(subSelect, correlation))`: its lifted
outer joins follow this LEFT rule, including through nested path-exists scopes (R-FLT-27).

## 7. `exists` and sub-selects

**R-FLT-11** `exists(ITEMS_TABLE, inner)` renders `EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id AND
<inner>)`. Columns inside `inner` must sit on `path` or below it and are re-rooted to the sub-query; any other column
throws `MQ1302` naming the column. Aliased paths and `on(...)` conditions are carried into the sub-query. `path` must
be a join, else `MQ1304`. A nested `exists` correlates to the enclosing `exists` path: on a path below it, to rows of
that child; on exactly the same path, to the **same child row**, so its filters narrow the row the enclosing `exists`
found rather than asking for another row of that path. A nested `exists` under an `exists` whose path holds a treat
correlates on a treated node and is `MQ4016` (D-164).

**R-FLT-12** Because `exists` and sub-selects join nothing on the outer query, `count` and export need no distinct or
dedupe work (`engine/20` R-EXE-04). `exists` is the preferred form for "has a child matching X".

**R-FLT-15** *(D-112)* **A sub-select is one column of another root.** `SubSelect.of(column)` selects `column` from
the root its path starts at, any entity, the outer root's own included; `where(filters)` adds filters on that root,
recorded once, and each of their columns must start at that root, else `MQ1003` at definition. A `SubSelect<S, C>` is
immutable and may be a constant (INV-9). It has no order, limit, grouping, second column or aggregate, and is never
skipped, even when every one of its own filters was skipped; skip it with `when`. It is not named `SubQuery`, which
differs from `jakarta.persistence.criteria.Subquery` only by case.

```java
static final SubSelect<RefundView, Long> REFUNDED = SubSelect.of(QRefundView.ORDER_ID)
        .where(f -> f.eq(QRefundView.STATUS, "DONE"));

.where(f -> f.notIn(QOrderView.ID, REFUNDED))
```

`in(column, sub)` and `notIn(column, sub)` take a `ScalarField<M, C>` and a `SubSelect<?, C>` with the same,
invariant `C`, so a `Long` column against an `Integer` sub-select does not compile; when a converter hides different
attribute types on either side, the check is `MQ1001` at definition, as for `compare`.

```java
public final class SubSelect<S, C> {                                   // @Incubating, immutable
    public static <S, C> SubSelect<S, C> of(ColumnField<S, ?, C> column);  // FROM the column's path root
    public SubSelect<S, C> where(UnaryOperator<Filters<S>> filters);      // replaces; recorded once, here
    public ColumnField<S, ?, C> column();
    public TableField<?, ?> root();
    public List<Condition> conditions();                                  // its own where
}
public final class Outer<M, S> {                                       // @Incubating, built by the DSL only
    public <T, C> ColumnField<S, T, C> column(ColumnField<M, T, C> outerColumn);
    public static Optional<ColumnField<?, ?, ?>> referenced(SelectField<?, ?> column);   // api/16 R-INS-08
}
```

`S` is the inner column vocabulary: a generated model's columns, or the entity class for hand-written columns
(`ColumnField.of(Entity.class, …)`). `exists` infers `S` from `rows` before it types the lambda, so implicit lambdas
work, and an outer column passed where an inner one is expected does not compile unless `S` is `M`; then `MQ1309`
catches a correlation that lifts nothing.

**R-FLT-16** *(D-112)* **`in` and `notIn` over a sub-select.** `in` renders `col IN (SELECT s.c FROM … WHERE …)`.
`notIn` renders `(col NOT IN (SELECT s.c FROM … WHERE … AND s.c IS NOT NULL) OR col IS NULL)`: a NULL among the
sub-select's values never empties the result, and rows whose column is NULL match (R-FLT-04), the same rows as a
`notExists` correlated on equality. An empty sub-select keeps R-FLT-02's meaning: `in` matches nothing, `notIn` every
row. An embeddable-valued column on either side throws `MQ1312` at first resolution, because row-value `IN` is not
portable (INV-6). On PostgreSQL the user guide advises `notExists` for a large sub-select.

**R-FLT-17** *(D-112)* **Correlated `exists`.** `exists(sub, (s, outer) -> …)` and `notExists(...)` render
`EXISTS (SELECT 1 FROM <root> s WHERE <sub's filters> AND <correlation>)`; the sub-select's column is not rendered.
`outer.column(c)` lifts a column of the enclosing query's model into the sub-select's vocabulary, so it goes wherever
a column of `S` goes (`compare` against an inner column, `eq` against a value) and `or`/`not` mix inner and outer
conditions; an `add(...)` resolves it through its `JoinContext`, and a
nested `exists(path, …)` inside the correlation may use it (exempt from `MQ1302`).

```java
// AuditEntry has no association to Order
.where(f -> f.exists(SubSelect.of(QAuditView.ID), (s, outer) -> s
        .compare(QAuditView.ENTITY_ID, EQ, outer.column(QOrderView.ID))
        .or(g -> g.compare(QAuditView.ACTOR, EQ, outer.column(QOrderView.OWNER)),
            g -> g.isNull(QAuditView.ACTOR))))
```

- The lifted column may sit on the outer root or a to-one join path below it (R-FLT-27, D-150), a `through` child's
  join included as a base (D-100). A path starting at another root is `MQ1311` at definition; cardinality is checked
  at first resolution.
- The correlation must record at least one lifted column, else `MQ1309`, so R-FLT-01 skipping never empties it.
- A lifted column resolved outside the correlation it was made in, or lifted through two sub-select levels, throws
  `MQ1310`.
- Lifted columns resolve through the sub-select's link to its outer query, so a sub-select over the outer root's own
  entity is unambiguous.

`in` and `notIn` are never correlated (use `exists`); a sub-select is never compared as a scalar and never used in
`having` (R-FLT-14).

**R-FLT-18** *(D-115)* **An expression is an operand.**
- Every operator takes a `ScalarField` where it took a column, and `compare` one on each side.
- The skip, NULL (R-FLT-04: `expr <> ? OR expr IS NULL`), escaping, chunking and join rules apply unchanged. An
  expression has no converter, so values bind as given.
- Its columns are checked as columns are: `MQ1302` outside an `exists` path, `MQ1003` in a sub-select's `where`; a
  lifted column inside it counts for `MQ1309`.
- A sub-select still selects a column only.

**R-FLT-19** *(D-128)* **`in` and `notIn` against operands.** Fixed-arity field forms put an expression in the set:

```java
<C> Filters<M> in(ScalarField<M, C> column, ScalarField<M, C> first, ScalarField<M, C> second);
<C> Filters<M> in(ScalarField<M, C> column, ScalarField<M, C> first, ScalarField<M, C> second,
        ScalarField<M, C> third);
// and the same two for notIn
```

- `in` renders `column IN (first, second[, third])`; `notIn` renders `column NOT IN (first, second[, third]) OR column
  IS NULL`, so rows whose column is NULL match, exactly as the value form does (R-FLT-04, R-FLT-02).
- An operand is a `ScalarField` of the column's own model and type, so an `Expr.constant` puts the literal the
  planner must see in the set (`api/10` R-COL-18, R-COL-27) and a mismatched operand, or one of another model, does
  not compile. The operands record as the condition's `operands()` under `IN_FIELDS`/`NOT_IN_FIELDS`, and `values()`
  is empty (`api/16` R-INS-09).
- For `notIn`: (a) a NULL-valued operand makes `NOT IN` unknown for every row, so only rows whose column is NULL
  match; (b) the `OR column IS NULL` can stop a partial index's predicate from matching, so for a plain `NOT IN` use
  `not(g -> g.in(col, a, b))`.
- There is no `Optional` form and no `List` form: a raw `List` would bind to `in(column, Collection)` (R-FLT-02), and
  an `in(column, List.of(a, b))` of fields reads as the value form's `Collection<? extends C>`. There is no varargs
  form, because an interface method cannot carry `@SafeVarargs` (D-87).

**R-FLT-27** *(D-150)* **Lifted to-one paths.** In `exists`/`notExists` and `Correlated` columns, a lifted column may
sit on the outer root or a to-one path at any depth. A path crossing a to-many join is `MQ1311` at first resolution,
before any statement executes (INV-5). A `through` child's join is the base, so only joins below that base are checked.
The outer query's `JoinContext` resolves the path and reuses the join a selected column would use, including its
`ON` and join type; joins needed only inside `or`/`not` or sub-select `notExists` are LEFT (R-FLT-10), including
when the lift is inside nested path-exists scopes. The sub-query calls
`Subquery#correlate(outerJoin)` and reads the column from the correlated join. It never joins off a correlated root
or reads a bare outer join path: either can lose the outer `ON` (D-112, D-150).

Equality with a NULL lifted value matches no row: correlated count is zero and exists is false.
`isNull(outer.column(...))` matches as written. An outer root that is a source is allowed and renders
`correlate(cteRoot)` (R-SRC-10). A sub-select whose own root is a source is `MQ1909` at first resolution.

**R-FLT-28** *(D-154, D-158)* **Filtering by entity type.** `Filters` gains, all `@Incubating`:

```java
<T> Filters<M> ofType(TableField<?, T> table, Class<? extends T> type, Class<? extends T>... more);
<T> Filters<M> ofType(TableField<?, T> table, Collection<? extends Class<? extends T>> types);
<T> Filters<M> ofType(TableField<?, T> table, Optional<? extends Collection<? extends Class<? extends T>>> types);
// and notOfType with the same three forms
```

- **Instance-of.** `ofType(ROOT, CardPayment.class)` keeps rows whose node is a `CardPayment` or one of its entity
  subtypes. It renders `TYPE(table) IN (...)` over every concrete entity the persistence unit's
  `Metamodel#getEntities()` maps whose Java type is assignable to a listed type, in class-name order, so a grandchild
  and a subtype mapped in `orm.xml` count, and an abstract type keeps its concrete subtypes. The engine reads the list,
  not the provider (`vendor/40` R-VND-26); the executor hands the metamodel to the build through
  `RenderOptions#withMetamodel`. `eq(Expr.typeOf(...), type)` (`api/10` R-COL-36) matches the exact class only.
- **`notOfType`** is its negation, `TYPE(table) NOT IN (...)`. Over a node reached through a LEFT join it also keeps
  rows whose join is absent, `OR TYPE(table) IS NULL`, as `notIn` keeps NULL (R-FLT-04, D-5).
- **Empty forms.** An empty `Optional` skips the filter (P-3, R-FLT-03). An empty collection is "none of these
  types" (R-FLT-02): `ofType` keeps no row and `notOfType` keeps every row, and neither reads a type, so neither needs
  a renderer. A null type, collection or varargs array is `MQ1301`.
- **Typing and refusals.** A type that is not the node's entity or one of its subtypes does not compile. A type the
  metamodel doesn't map as an entity, or (through a raw call) one outside the node's hierarchy, is `MQ1106` before
  any statement and at first resolution. Without the provider's `TreatRenderer` a non-empty form is `MQ4014`. Over a
  treated node, a treat of the root or a downcast join, a non-empty form is `MQ4016` before any statement, as `typeOf`
  over it is (R-COL-36). Inside
  `exists(path, ...)` or a sub-select's `where`, the node must sit on the path or the sub-select's root (`MQ1302`,
  `MQ1003`), as a column must.
- **An unmapped discriminator value.** A row whose discriminator value no entity maps is kept by `notOfType`, whose
  `NOT IN` doesn't name it, and dropped by `ofType`.
- **No `@SafeVarargs`.** An interface method cannot carry it (D-87), so a varargs call may warn of an unchecked
  generic array; the implementation only reads the array (D-158).
- **Recorded.** Each records `Condition.Kind#OF_TYPE` or `#NOT_OF_TYPE` (`api/16`), its `column` the node's
  `Expr.typeOf` and its `values` the types as passed.
- **Sites and narrowing.** Each is a `TYPE` site (R-VND-26): `{PREDICATE, TYPE}`, and over a LEFT-joined node
  `notOfType`'s `{PREDICATE, NEGATED, NULL_TEST, DISJUNCTION, LEFT_JOIN, TYPE}`. A non-empty `ofType` that is a
  top-level conjunct of a select statement's own `where` (not under `or`, `not`, a `CASE`, `add(...)` or in a
  sub-select) declares a `TYPE_RESTRICTION` `ExpectedNarrowing` of its node to the rendered classes, a narrowing that
  loses no row the filter keeps (D-157, D-158). `typeOf` and `ofType` alone make no treated statement
  (`api/10` R-COL-35).

## 8. Custom operators

**R-FLT-20** *(D-167, D-168, `@Incubating`)* **`FilterOperator` and `op`.** A `FilterOperator<C, V>` is a named,
immutable `core` definition (INV-9) built by `FilterOperator.of(name, renderer)`, whose value is of the column's own
type (`FilterOperator<C, C>`), or `of(name, valueType, renderer)`, whose value is a `V` (`rfc/0017` §1) and is never converted, unlike
`of(name, renderer)`'s (R-FLT-21): on a `String`-to-`String` `ColumnConverter` column the two bind differently, so use
`of(name, renderer)` when the value is a column value. An array value is copied when the filter is added
(`api/16` R-INS-13). Each of
`requiringAttribute(type)`, `requires(feature)` and `preparing(prepare)` returns a new instance that replaces that
setting, so an operator belongs in a constant, never built at a call site. A null or blank name is `MQ1301`; the name
is for logs, inspection and messages only, and two definitions with one name are two operators.

```java
<C, V> Filters<M> op(FilterOperator<C, V> operator, ScalarField<M, C> column, V value);
<C, V> Filters<M> op(FilterOperator<C, V> operator, ScalarField<M, C> column, Optional<? extends V> value);
<C, V> Filters<M> op(FilterOperator<C, V> operator, ScalarField<M, C> left, ScalarField<M, V> right);
// Having gains the same three over AggregateField (api/13)
```

- **The renderer** (`OperatorRenderer`) gets an `OperatorContext` (its `cb()`), the column's expression as `left` (of
  the attribute's type on a converted column, D-37) and the operand as `right`: the engine's bind parameter in the
  value form, the right field's expression in the field form. It never sees the value, and must use the `right` it is
  given and never capture a value (`cb.equal(left, someValue)`, `cb.literal`), which a provider may inline (R-FLT-08). The one
  exception is the catalogue's `fullTextMatch` configuration, a validated identifier and definition text (D-173).
  It runs at build time; returning `null` or throwing a `RuntimeException` is `MQ1313` naming the operator and the
  column, the exception as the cause, before any statement. A `ModelQueryException` it throws keeps its own code.
- **`requiringAttribute(type)`** refuses, with `MQ1001` when the filter is added, a column whose attribute type (the
  converter's attribute type on a converted column, R-COL-14; the aggregate's type in `having`, R-AGG-04) is not a
  `type`.
- **`requires(feature)`** names an `OperatorFeature` (`ILIKE`, `REGEX_MATCH`, `ARRAY_CONTAINMENT`,
  `JSONB_CONTAINMENT`, `FULL_TEXT`) the vendor and the provider must both serve (`vendor/40` R-VND-23). When the filter
  is rendered, before any statement, the vendor's answer is checked first (`MQ4015`), then the provider's (`MQ4014`);
  rendered with no operator gate, such an operator is `MQ4014`. An operator that requires none is never gated.
- **Field form.** Both sides are fields of the query's model, as in `compare` (R-FLT-18); an expression on either side
  is an operand. For a `FilterOperator<C, C>`, a converted column on either side whose attribute type differs from the
  other side's is `MQ1001`, as `compare` refuses it. It has no `Optional` twin.
- **`Optional` form.** Empty skips the filter, with no join (R-FLT-01, R-FLT-03).
- **Scope.** Inside `or`, `not`, `exists` and a sub-select an `op` behaves like any operator: a join it first needs
  inside `or` is LEFT (R-FLT-10), and a column outside an `exists` path is `MQ1302`.
- **Recorded** as `Condition.Kind#OPERATOR` (`api/16` R-INS-13).

**R-FLT-21** *(D-167, D-168, D-171)* **An operator's value.** In order, when the filter is added:
1. `null` is `MQ1301` naming the column; an `Optional`, a `Collection` or a `Map` is `MQ1502` naming the operator and
   the column (a `Collection` operand is an `IN`, and a `Map` has no portable binding).
2. For a `FilterOperator<C, C>` the value goes through a converted column's converter to the attribute's type
   (R-COL-14, D-37); for a `FilterOperator<C, V>` it is not converted.
3. `prepare` runs, if the operator has one, on the value step 2 gives, so on a converted column it builds on the
   attribute value, as `like` builds its pattern (R-FLT-06, D-171). Returning `null` or throwing a `RuntimeException`
   is `MQ1313`. A `FilterOperator<C, C>` with a `prepare` on a converted column whose attribute type is not the
   column's type `C` is `MQ1001`, since `prepare` takes a `C`.

Then, each time the filter is rendered, the engine binds it as a statement parameter through `JoinContext`, so every
statement that renders the filter binds it (the content query, the count, each export and keyset page, a sub-select
merged into its outer query) and it counts toward R-FLT-09's bind limit. The parameter is typed by the column's
attribute type (the `<C, C>` form) or by the declared `valueType` (the `<C, V>` form), never by the value's class
alone. With no `OperandBinder` (`vendor/40` R-VND-24), only a value whose binding doesn't depend on the column's
mapping binds: a `String`, a `java.lang` or `java.math` number, or a `java.time` value (D-168). Anything else, such as
an enum, an array, a `Boolean` or a `UUID`, is `MQ1502` naming the operator and the column, before any statement: a
parameter typed by class binds a `STRING` enum by ordinal and a `@Convert` value not at all (`rfc/0017` spike finding
1), and a `Boolean` mapped to `'Y'`/`'N'` or a `UUID` stored as `char(36)` matches nothing.
With a binder, the `<C, C>` form's parameter is the binder's, typed by the column's mapping, so an enum, an array or a
converted value binds; the `<C, V>` form, which no column types, keeps the no-binder rule (D-169), and so does a column
expression the binder declines to type by (`OperandBinder#typesBy`; Hibernate's types a path or an aggregate over one,
not a function of a column, D-170).

**R-FLT-22** *(D-167)* **NULLs and negation.** An operator renders as its renderer builds it, with no `OR col IS NULL`
added: a NULL column or operand makes it unknown, so the row doesn't match. `not(g -> g.op(...))` is plain `NOT (…)`
(R-FLT-05) and also drops the NULL rows. R-FLT-04's NULL rule for `ne` and `notIn` doesn't apply.

## 9. Scope

**R-FLT-13** *(was R15)* Filter values never change a query's meaning by accident: empty `Optional` skips; an empty
collection means "none"; negation includes NULLs; `like` input is escaped; long `IN` lists are split.

**R-FLT-14** `Filters` builds `WHERE` predicates only. Conditions on aggregates go through `having(...)`
(`api/13` §3), never through `Filters` and never through `QueryCustomizer`.

## 10. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-FLT-01 | Every operator behaves identically in value and `Optional` form on every Tier-1 vendor. |
| AC-FLT-02 | A value-form filter with `null` throws `MQ1301` naming the column; an `add(...)` predicate returning `null` throws `MQ1305` (§1). |
| AC-FLT-03 | An `or` whose every branch was skipped disappears; the query returns the same rows as one without it (R-FLT-01). An `or(List.of())` renders `FALSE` and returns no rows (R-FLT-01, D-92). |
| AC-FLT-04 | `exists(path, inner)` with every inner filter skipped is skipped; `exists(path)` still renders (R-FLT-01). |
| AC-FLT-05 | `in(col, List.of())` returns no rows; `notIn(col, List.of())` returns every row (R-FLT-02). |
| AC-FLT-06 | `ne` and `notIn` include rows where the column is NULL (R-FLT-04). |
| AC-FLT-07 | `like` with `%`, `_` and `\` in the value matches those characters literally (R-FLT-06). |
| AC-FLT-08 | An `IN` list above the vendor limit is split and returns the same rows as an unsplit one (R-FLT-09). |
| AC-FLT-09 | An `or` branch over a LEFT-joined column keeps rows that have no joined row (R-FLT-10). |
| AC-FLT-10 | A column outside the `exists` path throws `MQ1302`; a root as the path throws `MQ1304`; an aliased path works inside `exists`; a nested `exists` on the same path tests the same child row (R-FLT-11). |
| AC-FLT-11 | `count` over a query using `exists` equals `count` over the equivalent join query with distinct (R-FLT-12). |
| AC-FLT-12 | `in(col, sub)` returns the rows whose column is among the sub-select's filtered values; an empty sub-select returns none; a sub-select over the outer root's own entity works (R-FLT-15, R-FLT-16, D-112). |
| AC-FLT-13 | `notIn(col, sub)` keeps the rows whose column is NULL, is not emptied by a NULL value of the sub-select, and returns every row for an empty sub-select (R-FLT-16). |
| AC-FLT-14 | `exists` and `notExists` over an entity with no association to the outer root, correlated on an outer-root column; an `or` mixing an inner and a lifted condition matches through either branch; a `through` child's query correlates to its join (R-FLT-17). |
| AC-FLT-15 | Sub-select typing and refusals, including a to-many lifted path (R-FLT-15..17, R-FLT-27). |
| AC-FLT-16 | With `in(sub)` and `exists(sub, …)`, `count` equals the list size, and keyset page, primary-key-first and export visit every row once; a bulk delete whose sub-select reads its target runs key-first on MySQL (R-FLT-12, R-WRT-11, INV-4). |
| AC-FLT-17 | Every operator, value and `Optional` form, over an expression operand, and `compare` between an expression and a column, return on every Tier-1 vendor the rows the same predicate written over a column computed in a view or in Java returns. `ne` and `notIn` over a nullable expression include its NULL rows. A skipped filter over an expression adds no join (R-FLT-18, R-FLT-04, R-FLT-03). |
| AC-FLT-18 | An expression over a column outside the `exists` path throws `MQ1302`. One in a sub-select's `where` over another root throws `MQ1003`. A correlation whose only lifted column sits inside an expression is not `MQ1309` (R-FLT-18). |
| AC-FLT-19 | A bulk update and a bulk delete whose `where` compares an expression change exactly the rows `list` returns for that filter, key-first on MySQL too (R-FLT-18, `api/14` R-WRT-11). |
| AC-FLT-20 | On every Tier-1 vendor, `in(col, a, b)`, `in(col, a, b, c)` and the same two for `notIn` return the rows whose column equals one of the operands, `notIn` also returns the rows whose column is NULL, and a `'` and a `\` in a constant operand round-trip. The statement inlines the operand literals, e.g. `in ('PENDING','FAILED')`. `var` infers the call, and operands of another model or type do not compile (R-FLT-19, R-COL-27). |
| AC-FLT-21 | A user operator and `ilike(mode)` filter on PostgreSQL and H2 on both lines: in value, `Optional` and field form, and inside `or`, `not`, `exists` and a sub-select; an empty `Optional` creates no join; the value is a bind parameter (SQL snapshot); on a `ColumnConverter` column `ilike` returns the rows `like` returns with the same mode, and a `FilterOperator<C, C>` with a `prepare` on a converted column whose attribute is not a `C` is `MQ1001`; on a `@Convert` attribute `like`, `ilike` and `likeIgnoreCase` bind the patterns R-FLT-06 states, on Tier 1 (R-FLT-06, R-FLT-20, R-FLT-21). |
| AC-FLT-22 | An operator's value is typed and converted correctly: with Hibernate's binder, on each served line, an enum (`STRING` and `ORDINAL`), a `@Convert` attribute, an array and a converted value bind typed by the column's mapping, the statement shows `?` with the default value handling and under `hibernate.criteria.value_handling_mode=inline`, and on MySQL a `STRING` enum over a `varchar` column and over a native `enum(...)` column each returns exactly its matching rows, never every row or none; `Having.op` over an aggregate of a path binds typed by the column's mapping, while over a function of a column, which Hibernate's binder declines (`typesBy`), an enum is `MQ1502` before any statement and a `String` binds by its class and returns exactly its rows; the binder refuses a node type its line doesn't expect with `MQ1502` (a test double for the node type), and `operands(emf)` is empty outside 6.6.x and 7.4.x; with no binder, an enum or an array value is `MQ1502`, a `Boolean` or `UUID` value is `MQ1502`, and a `String`, number or `java.time` value binds; a `FilterOperator<C, V>` binds `V` unconverted, and one with `requiringAttribute` over the wrong attribute is `MQ1001` (R-COL-14); a field form over two converted columns of different attribute types is `MQ1001`; a `null` through a typed variable is `MQ1301`, and a `Collection` value is `MQ1502` (R-FLT-21, `vendor/40` R-VND-24). |
| AC-FLT-23 | A renderer returning `null` and one throwing are `MQ1313` naming the operator and column, before any statement; a `ModelQueryException` thrown from a renderer keeps its own code (R-FLT-20). |
| AC-FLT-24 | The catalogue's `regexMatch`, `arrayContains`, `arrayOverlaps` and `jsonbContains` return the right rows on PostgreSQL. On H2 and MySQL each is refused before any statement: `MQ4015` with H2's and MySQL's profiles (the vendor check runs first); `MQ4014` with a custom profile that answers true and Hibernate's provider, which has no `mq_*` function there, and for `arrayOverlaps` on H2 never `Unknown data type`; an operator that requires a feature is `MQ4014` with no gate (`RenderOptions.portable()`), and a query with an `op` and a customizer passes `checkPhases`; `ilike` is `MQ4015` on MySQL with its profile, and `MQ4014` with a custom profile answering true there (the provider answers `ILIKE` by dialect), never the `lower(col) like lower(?)` emulation; `PortabilityCheck` reports each per target when the value is present, and an `op` with an empty `Optional` is not reported (R-FLT-20, `vendor/40` R-VND-23). |
| AC-FLT-25 | Inspection and matching: inspection records `OPERATOR` with the operator, column and unprepared value; `FilterMatchers.op` matches it, and a different operator with the same name doesn't; two queries sharing the constant are equal; `toString` prints no value (`api/16` R-INS-13). |
| AC-FLT-26 | `not(op(...))` and `op(...)` both drop rows whose column is NULL (R-FLT-22); `Having.op` filters groups; any `op` in an arbiter's `where` is `MQ1804` naming the operator at `build()`, a value-form `op` in a conflict update's `where` is `MQ1804` naming the operator on first execution, and a field-form one runs there; an `op` in `Expr.cases().when(...)` is `MQ1505` (R-FLT-22, `api/14` R-WRT-34, R-WRT-52, `api/10` R-COL-17). |
| AC-FLT-27 | Compile failures (P-2, as AC-FLT-15 and AC-FLT-20 have): a value of the wrong type; a field of another model; a `FilterOperator<String, String>` on an `Integer` column (R-FLT-20). |
| AC-FLT-28 | Lifted to-one paths in exists/notExists (R-FLT-27). |
| AC-FLT-29 | Lifted to-one paths in correlated columns and count statements (R-FLT-27, R-EXE-05). |
| AC-FLT-30 | Per strategy, `ofType` keeps a grandchild and an `orm.xml` subtype, accepts an abstract type, skips on an empty `Optional` and keeps no row for an empty collection; beside a treated read in one statement it runs, except on 6.6 `JOINED`, whose preflight refuses it (`MQ4016`) (R-FLT-28, D-158). |
| AC-FLT-31 | Per strategy, `notOfType` includes the rows of an absent LEFT join and, on `SINGLE_TABLE`, of a discriminator value no entity maps, which `ofType` drops and `typeOf` fails on; an empty collection keeps every row (R-FLT-28). |
| AC-FLT-32 | `fullTextMatch(config)` on PostgreSQL, both lines: returns the right rows for request text with spaces and punctuation (`acme corp`, a quote), with no database error; the statement renders the configuration as a literal and the text as `?` (SQL snapshot); a null, blank or non-identifier configuration (`english'`, `a b`) is `MQ1315` at the factory call, and two calls with one configuration return the same instance; it is `MQ4015` on H2 and MySQL with their profiles, and `MQ4014` with a custom profile answering `FULL_TEXT` true there; `PortabilityCheck` reports it per target (`vendor/40` R-VND-23). |

AC-FLT-15 includes compile-failure cases for a mismatched `C` and another model's lifted column, and cases for
`MQ1309`, `MQ1310`, `MQ1311` (a path crossing a to-many join, checked at first resolution), `MQ1312` and `MQ1001`
(converted attribute types). A source-rooted sub-select is `MQ1909`, with no statement executed.

AC-FLT-21..27 are reserved by RFC 0017. AC-FLT-28 and 29 run on every Tier-1 vendor and both Hibernate lines.
AC-FLT-28 covers exists/notExists, a to-one path of depth two, a through child's join as the base, NULL equality
and an explicit lifted isNull, and LEFT joins inside or/not. AC-FLT-29 covers a correlated column lifting a to-one
path, zero count and false exists for a NULL lifted value, and a source outer root. Its count statements omit
select-only correlated values while retaining an INNER lifted join, retain correlated WHERE predicates, and use
LEFT lifted joins inside or/not (R-FLT-10, R-EXE-05).
