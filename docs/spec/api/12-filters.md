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
    <C> Filters<M> eq(ColumnField<M, ?, C> column, C value);
    <C> Filters<M> ne(ColumnField<M, ?, C> column, C value);                  // NULL rows match, see R-FLT-04
    <C extends Comparable<? super C>> Filters<M> gt (ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> lt (ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, C value);
    <C extends Comparable<? super C>> Filters<M> range(ColumnField<M, ?, C> column,
                                                       Optional<? extends C> fromInclusive,
                                                       Optional<? extends C> toExclusive);
    <C extends Comparable<? super C>> Filters<M> between(ColumnField<M, ?, C> column, C fromInclusive, C toInclusive);

    // Sets
    <C> Filters<M> in(ColumnField<M, ?, C> column, Collection<? extends C> values);
    <C> Filters<M> notIn(ColumnField<M, ?, C> column, Collection<? extends C> values);

    // Strings
    Filters<M> like(ColumnField<M, ?, String> column, String value, LikeMode mode);   // EXACT | CONTAINS | STARTS_WITH | ENDS_WITH
    Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, String value, LikeMode mode);
    Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, String value);

    // Nulls
    Filters<M> isNull(ColumnField<M, ?, ?> column);
    Filters<M> isNotNull(ColumnField<M, ?, ?> column);
    Filters<M> isNull(ColumnField<M, ?, ?> column, Optional<Boolean> isNull);         // tri-state request flag

    // Column against column
    <C> Filters<M> compare(ColumnField<M, ?, C> left, Op op, ColumnField<M, ?, C> right);   // EQ, NE, LT, LTE, GT, GTE

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
    <C> Filters<M> in(ColumnField<M, ?, C> column, SubSelect<?, C> values);
    <C> Filters<M> notIn(ColumnField<M, ?, C> column, SubSelect<?, C> values);
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
`ESCAPE '\'`, so a search for `50%` finds that literal text. `EXACT` passes the pattern through as given.

**R-FLT-07** `likeIgnoreCase` renders `lower(col) LIKE ?`; the value is lower-cased in Java with `Locale.ROOT` and
bound (R-FLT-08, D-22). A database whose `lower()` folds only ASCII, such as PostgreSQL with C collation, can disagree
on non-ASCII text. The user guide notes that this needs a functional index to be fast, and that case sensitivity
otherwise follows collation (`vendor/40` §4).

## 5. Bind parameters and large sets

**R-FLT-08** Every value is a bind parameter. The engine never inlines a value into SQL, for any operator.

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
primary-key-first step 2 (`engine/21` R-PAG-07) and bulk-write key chunks (`api/14` R-WRT-08), each chunk at most the
largest power of two within the limits. The engine counts one bind per value; a provider that pads IN lists, such as
Hibernate with `hibernate.query.in_clause_parameter_padding`, binds up to the next power of two, which keeps a key chunk
within the limits but which a user's own lists must leave room for (`vendor/41`).

## 6. Joins created inside `or` and `not`

**R-FLT-10** *(was R14)* A join first needed inside `or` or `not` is resolved as LEFT. An INNER join created for one
branch would remove rows another branch should match. If the same path is already joined as INNER elsewhere in the
query, that join is reused, because those rows are already required.

## 7. `exists` and sub-selects

**R-FLT-11** `exists(ITEMS_TABLE, inner)` renders `EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id AND
<inner>)`. Columns inside `inner` must sit on `path` or below it and are re-rooted to the sub-query; any other column
throws `MQ1302` naming the column. Aliased paths and `on(...)` conditions are carried into the sub-query. `path` must
be a join, else `MQ1304`. A nested `exists` correlates to the enclosing `exists` path: on a path below it, to rows of
that child; on exactly the same path, to the **same child row**, so its filters narrow the row the enclosing `exists`
found rather than asking for another row of that path.

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

`in(column, sub)` and `notIn(column, sub)` take a `ColumnField<M, ?, C>` and a `SubSelect<?, C>` with the same,
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

- The lifted column must sit on the outer query's root, else `MQ1311` at definition; it is read through a correlation
  of that root (or of a `through` child's join, D-100), so the outer query joins nothing. Hibernate renders joins off
  a correlated root as the sub-query's `FROM` and drops their `ON`, which would turn a LEFT join INNER and lose a
  collection join's row pairing (INV-5); widening this later turns a throw into working code.
- The correlation must record at least one lifted column, else `MQ1309`, so R-FLT-01 skipping never empties it.
- A lifted column resolved outside the correlation it was made in, or lifted through two sub-select levels, throws
  `MQ1310`.
- Lifted columns resolve through the sub-select's link to its outer query, so a sub-select over the outer root's own
  entity is unambiguous.

`in` and `notIn` are never correlated (use `exists`); a sub-select is never compared as a scalar and never used in
`having` (R-FLT-14).

## 8. Scope

**R-FLT-13** *(was R15)* Filter values never change a query's meaning by accident: empty `Optional` skips; an empty
collection means "none"; negation includes NULLs; `like` input is escaped; long `IN` lists are split.

**R-FLT-14** `Filters` builds `WHERE` predicates only. Conditions on aggregates go through `having(...)`
(`api/13` §3), never through `Filters` and never through `QueryCustomizer`.

## 9. Acceptance criteria

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
| AC-FLT-15 | A mismatched `C`, and `Outer.column` given another model's column, have compile-failure cases; `MQ1309`, `MQ1310`, `MQ1311`, `MQ1312` and `MQ1001` (converted attribute types) each have a case (R-FLT-15 to R-FLT-17). |
| AC-FLT-16 | With `in(sub)` and `exists(sub, …)`, `count` equals the list size, and keyset page, primary-key-first and export visit every row once; a bulk delete whose sub-select reads its target runs key-first on MySQL (R-FLT-12, R-WRT-11, INV-4). |
