# RFC 0015 — Correlated columns and sources over sources

- **Status:** accepted 2026-10-17 as D-150 (after an `architect-review` and the acceptance spike, §8). M27, 0.10.0
  (D-145).
- **Affects:**
  - **`api/10`:** new R-COL-31 to R-COL-33 and AC-COL-36 to AC-COL-42: the `Correlated` vocabulary and the correlated
    expression. R-COL-17's "holds no sub-select" gains the correlated expression as its one exception; R-COL-20 gains
    its equality.
  - **`api/11`:** R-QRY-16 and R-PAG-25 are unchanged and now name the correlated expression: an order key for offset
    pages, offset export and `primaryKeyFirst`, refused by `keyset()` with `MQ1208`.
  - **`api/12`:** R-FLT-17 is amended: a lifted column may sit on a to-one join of the outer root, not on the root alone
    (`MQ1311` narrowed, and checked at first resolution instead of at definition). AC-FLT-15 is amended. New R-FLT-27
    and AC-FLT-28, AC-FLT-29.
  - **`api/17`:** new R-SRC-16 to R-SRC-19 and AC-SRC-26 to AC-SRC-37: a source over a source, a source read directly,
    a chain's CTEs, a correlated expression's rendering; `MQ1908`. R-SRC-08's and R-SRC-10's Q-22 notes point at
    R-SRC-16.
  - **`processor/30`:** R-PROC-35 is amended (a `@SourceModel`'s root may be a source model; `MQ3031`'s key text gains
    "`X`'s key fields"; a source QModel may also declare `query(...)`). R-PROC-39 is amended (`@FilterColumn` on a source
    over a source). R-PROC-37 and R-PROC-38's Q-22 notes point at R-PROC-35. New R-PROC-40.
  - **`processor/31`:** R-GEN-37 is amended (a non-branch source QModel has a `MAPPER`, inside `Rows`). New R-GEN-39 and
    R-GEN-40: `source(SourceQuery<I>)` on a source over a source, and the `Rows` vocabulary and `query(...)` on every
    source QModel that is not a branch.
  - **`processor/32`:** new `MQ3042`, with AC-PROC-41.
  - **`vendor/40`:** new R-VND-25: `ProviderSupport#subqueries()` and `SubqueryRenderer`.
  - **`core` SPI:** a new `@EngineFacing @Incubating` `SubqueryRenderer` with `firstRow`; `SourceRenderer` gains a
    `CteHandle` and three default methods for a chained CTE (§4); `SourceQuery` carries an inner source.
  - **`reference/90`:** new `MQ1314`, `MQ1510`, `MQ1511`, `MQ1907`, `MQ1908`; `MQ1311`, `MQ1905` and `MQ4014` widened;
    `MQ4015`'s row gains the provider-gap use `SourceRenderer`'s `recursive`/`window`/`combine` defaults already make.
  - **`reference/92`:** D-150 resolving Q-22; a new Q-24 (request-time values in a `@Computed`).
  - **`delivery/61`:** the incubating list.
  - **`docs/site`:** "Correlated columns" on the Columns page; "Sources over sources" and "Reading a source" on the
    Source models page; the comparison page's Reach row.
- **Discussion:** to open

## Summary

Three additions, each closing a read that today goes to native SQL, a second query, or a model written only to be
read:

- **A correlated column.** A model field whose value is a scalar sub-query per row: a count, an `exists`, a `min`,
  `max` or `sum`, or the first value in an order ("the latest note"). It is an `ExpressionField`, declared through
  `@Computed`, so it selects, filters and orders like any expression, and pages by offset (`keyset()` refuses an
  expression key, R-QRY-16).
- **A source over a source.** A `@SourceModel` whose root is another source model: window, aggregate and grouping
  fields over a set operation's combined rows or a recursive walk, and chained CTEs in general.
- **Reading a source directly.** A source model's QModel also offers `query(SourceQuery<S>)`, mapping rows into the
  source model itself, so a top-N-per-group read needs no outer model.

```java
@QueryModel(root = OrderEntity.class)
public record OrderRow(@PrimaryKey Long id, String number, @Column(attribute = "customer.id") Long customerId,
                       @Computed(OpenLines.class) Long openLines,
                       @Computed(LastNote.class) String lastNote) {}

public final class OpenLines implements ExpressionDefinition<OrderRow, Long> {
    public ExpressionField<OrderRow, Long> expression() {
        return Correlated.count(OrderRow.class,
                SubSelect.of(QLineView.ID).where(f -> f.eq(QLineView.STATUS, "OPEN")),
                (s, outer) -> s.compare(QLineView.ORDER_ID, EQ, outer.column(QOrderRow.ID)));
    }
}

public final class LastNote implements ExpressionDefinition<OrderRow, String> {
    public ExpressionField<OrderRow, String> expression() {
        return Correlated.first(OrderRow.class, SubSelect.of(QNoteView.TEXT),
                (s, outer) -> s.compare(QNoteView.ORDER_ID, EQ, outer.column(QOrderRow.ID)),
                QNoteView.CREATED_AT.desc().nullsLast());
    }
}

// orders with open lines, busiest first, an offset page (keyset() would be MQ1208: an expression key)
var q = QOrderRow.query().select(QOrderRow.DEFAULT)
        .where(f -> f.gt(QOrderRow.OPEN_LINES, Optional.of(0L)))
        .orderBy(QOrderRow.OPEN_LINES.desc())
        .build();
Slice<OrderRow> page = executor.page(q, PageSpec.of(0, 50), CountMode.NO_COUNT);
```

```sql
-- PostgreSQL; the note's own id breaks ties in first(...) (R-COL-32)
select o.id, o.number, o.customer_id,
       (select count(l.id) from order_line l where l.status = ? and l.order_id = o.id),
       (select n.text from note n where n.order_id = o.id
        order by n.created_at desc nulls last, n.id fetch first 1 rows only)
from orders o
where (select count(l.id) from order_line l where l.status = ? and l.order_id = o.id) > ?
order by 4 desc, o.id offset ? rows fetch first ? rows only
```

## Motivation

- **Per-row counts and flags are the most common list-screen column the library can't express.** "Orders with their
  open-line count", "customers with an overdue flag", "tickets with the latest comment". Today the choices are a
  grouped model (which can't also show ungrouped columns of the root, and can't be keyset-paged, `MQ1402`), an enricher
  (D-114: one extra statement per page, but the value can't be filtered, ordered or paged on), or native SQL.
- **D-112 stopped at filters.** `exists` and `in` take a sub-select, but a sub-select is never a column, and its lifted
  outer columns must sit on the root (`MQ1311`), so "the customer has an open order" from an order row can't be said.
- **RFC 0013 left two refusals as Q-22.** A window or aggregate over a `union`'s combined rows (rank the activity feed)
  or over a recursive walk (count the subtree under each node) is `MQ3034` / `MQ3037` today, though both are one more
  CTE. And a top-N-per-group read needs an outer model that repeats the source model field for field.

Blaze-Persistence covers the first with `@MappingCorrelatedSimple` and subquery providers, and the second with CTEs
that select from CTEs. This RFC keeps the typed vocabulary of D-112 and RFC 0013 and adds no string mapping.

## Design

### 1. A correlated column (R-COL-31)

```java
@Incubating
public final class Correlated {                             // static vocabulary, as Agg is; no instances
    public static <M, S, C> ExpressionField<M, Long> count(Class<M> model, SubSelect<S, C> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation);      // count(column); 0 when none
    public static <M, S, C> ExpressionField<M, Boolean> exists(Class<M> model, SubSelect<S, C> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation);      // true or false, never NULL
    public static <M, S, C extends Comparable<? super C>> ExpressionField<M, C> min(Class<M> model,
            SubSelect<S, C> rows, BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation);   // NULL when none
    public static <M, S, C extends Comparable<? super C>> ExpressionField<M, C> max(Class<M> model,
            SubSelect<S, C> rows, BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation);
    public static <M, S, N extends Number> ExpressionField<M, N> sum(Class<M> model, SubSelect<S, N> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation);      // api/13's SUM typing; NULL when none
    @SafeVarargs
    public static <M, S, C> ExpressionField<M, C> first(Class<M> model, SubSelect<S, C> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation,
            OrderField<S> order, OrderField<S>... more);                       // the column of the first row
}
```

- **Static terminals, each with its own bound.** `min`/`max` need a comparable column and `sum` a number, as `Agg.min`
  and `Agg.sum` do (P-2): a class-level type parameter can't bound one method, so a step type would let `sum()` over a
  `String` compile. `first` takes at least one order key by signature. There is one calling style, and `Expr` gains
  nothing; `Correlated` sits next to `Agg`, not inside it, because its terminals are per-row, not per-group.
- **The correlation is D-112's**, the same `BiFunction` `Filters#exists` takes, with the same rules: at least one lifted
  column (`MQ1309`), lifted columns resolved only inside it (`MQ1310`), the sub-select's own `where` recorded once and
  never skipped (R-FLT-15). The correlation is recorded once, at the factory call, so the field is immutable and may be
  `static final` (INV-9). `model` types the lambda, as `Expr.cases(model, type)` does.
- **Equality (R-COL-20).** Two correlated expressions are equal when their sub-select, recorded correlation, terminal and
  order are equal; equal ones are one Criteria node per statement (R-COL-19).
- **One scalar sub-query.** Each terminal renders `(select <agg>(s.c) from <root> s where <sub's filters> and
  <correlation>)`; `exists()` renders `case when exists(select 1 …) then true else false end`, so the column is never
  NULL; `count()` counts the sub-select's column, so a nullable column counts its non-NULL values, as `Agg.count` does.
  `max` over a `Boolean` column fails on PostgreSQL, as `Agg.max` does (inherited, not checked).
- **No fan-out inside the sub-select (`MQ1511`).** R-FLT-15 lets a sub-select's column and its own `where` cross any
  join from its root, which is harmless for `exists` and `in`. For `count` and `sum` a to-many join multiplies the
  rows counted, and for `first` it makes the appended id tie-breaker non-unique, so an arbitrary joined row wins. A
  `count`, `sum` or `first` whose sub-select column, own `where`, or inner side of the correlation crosses a to-many
  join from the sub-select root is `MQ1511`, at first resolution, before any statement (as `MQ1906` is). The message
  points at `Filters.exists(path, inner)` inside the sub-select's `where` (the R-SRC-03 precedent). `min`, `max` and
  `exists` are unaffected: duplicates don't change them.
- **It is an `ExpressionField`.** It is declared through `@Computed` (R-PROC-21), selects, filters (R-FLT-18) and orders.
  It is an order key for offset pages, offset export and `primaryKeyFirst` (R-PAG-25); `keyset()` refuses it with
  `MQ1208` (R-QRY-16), as it does every expression key: the cursor, the fingerprint (R-PAG-19) and the D-82 bind budget
  are defined over attribute values, an OR-expansion would re-render the sub-query and its binds in every branch, and
  `min`, `max`, `sum` and `first` are nullable. `Expr.coalesce`, arithmetic and `nullIf` take it as an operand.
- **Where it is refused.** In a CASE condition (`MQ1505`, unchanged); in `having` (R-FLT-14, unchanged); aggregated by
  `@Aggregate(expression = …)` or `Agg.of` (`MQ1510`: PostgreSQL allows `sum((select …))`, MySQL refuses it inside
  `ONLY_FULL_GROUP_BY`, and nothing needs it); in a grouped query's selection or orders whose lifted columns are
  not all group keys (`MQ1314`, at definition: the database would refuse the statement), and as a `@GroupBy` key
  itself (`MQ1314`); inside a source (`MQ1908`, §4). A correlated value in a grouped query's WHERE may lift a
  non-group-key column: it is evaluated per row before grouping.
- **The count is not the content.** A correlated column only in the select list adds nothing to the count statement
  except the joins its lifted columns need where those joins can change the row count (R-EXE-05: an INNER lifted path
  stays). One in `where` is in it, as any filter is. Inside `or`/`not`, the lifted join is LEFT (R-FLT-10).
- **Docs.** `Correlated.exists(...)` is a column; to filter on existence, `Filters.exists` keeps the semi-join plan a
  CASE-wrapped column in `where` blocks.

### 2. The first value in an order (R-COL-32)

`first(..., order, more...)` returns the sub-select's column of the first matching row in that order. The engine
appends the sub-select root's id, ascending, as the last order key, as R-SRC-04 appends the source key to a window's
order, so the value is deterministic under ties (`MQ1511` keeps that id unique).

**Null precedence follows R-COL-12.** An order key inside `first(...)` renders its null precedence as it does in any
`ORDER BY`: `DEFAULT` renders no null clause (or the provider's configured default, D-36), so the vendor's own NULL
ordering applies; `nullsFirst()` and `nullsLast()` mean the same thing on every vendor. Here a row-order difference is
a different value: with `DEFAULT` on a nullable key, "the latest note by `created_at desc`" is the undated note on
PostgreSQL and a dated one on MySQL and H2. That is the same vendor dependence `DEFAULT` already has in a page's order,
so `DEFAULT` keeps one meaning; the docs say to set `nullsFirst()` or `nullsLast()` on a nullable key for the same value
on every vendor, as the examples do. Spike probe 2 showed an explicit precedence returns the same row on every vendor
and both lines.

It renders `order by … fetch first 1 rows only` inside the scalar sub-query, which standard Criteria can't say. That is
not a source feature, so it gets its own capability (R-VND-25), in the shape of `nullPrecedence`, `locks`, `constants`
and `sources`:

```java
@EngineFacing @Incubating
public interface SubqueryRenderer {
    <C> Expression<C> firstRow(CriteriaBuilder cb, Subquery<C> sub, List<Order> order);
}

// ProviderSupport
default Optional<SubqueryRenderer> subqueries() { return Optional.empty(); }
```

`model-query-hibernate` implements it with `JpaSubQuery#orderBy` and `#fetch(1)`, which RFC 0014's spike already ran as
an uncorrelated scalar sub-select on both lines and all Tier-1 vendors (its probe 1); probe 2 here runs it correlated.
Without the module, `first(...)` is `MQ4014` (widened: "the persistence provider lacks an ordered sub-query renderer")
when the statement is built, and `PortabilityCheck` reports it per target; the other terminals are standard Criteria and
need nothing.

*Why not `max` plus a second lookup:* `(select n.text … where n.created_at = (select max(…)))` returns two rows on a
tie, which is a runtime error mid-export.

### 3. Lifting a to-one column (R-FLT-27, amends R-FLT-17)

A lifted column may sit on the outer root **or on a to-one join of it**, at any depth of to-one joins, a `through`
child's join (D-100) included as a base; one on a path that crosses a to-many join is `MQ1311` still, since a
correlation through it would pair the sub-query with one arbitrary child row (INV-5). This applies to
`exists`/`notExists` filters and correlated columns alike.

**When it is checked.** `TableField.join(parent, attribute, JoinType)` doesn't record cardinality, so `core` can't tell
to-one from to-many at definition, where `MQ1311` is raised today. The narrowed check runs at first resolution in `jpa`,
through the metamodel, before any statement. AC-FLT-15's `MQ1311` case moves to a to-many path; `SubSelectTest`'s
root-only cases flip to passing.

The lifted column is resolved through the **outer** query's `JoinContext`, reusing the join a selected column on that
path would use (same join type; LEFT inside `or`/`not`, R-FLT-10), so it multiplies nothing a selected column on that
path wouldn't. The engine renders `Subquery#correlate(outerJoin)` for that join and reads the column off the
correlated join, so the sub-query predicate references the outer join's alias and creates no join of its own off a
correlated root, which is what D-112 avoided: Hibernate renders such a join as a `FROM` item and drops its `ON`. Spike
probe 3 confirmed the rule on every target and both lines: a bare reference to the outer join's path, without
`correlate`, loses the outer `ON` (a wrong count), and `correlate(join)` keeps it. A
comparison with a NULL lifted value matches no row, so a `count()` correlated by equality is 0 and `exists()` false, the
same as no matching row; a correlation written with `isNull(outer.column(…))` matches as written.

The narrowing trusts to-one mappings, as every selected to-one column already does. Two mappings break that trust and
the docs name them: `@OneToOne(mappedBy)` whose owning foreign key has no unique constraint, and
`@JoinColumn(referencedColumnName)` on a column that isn't unique. `jpa` can't detect either portably.

**An outer root that is a source.** A model rooted at a source (R-SRC-10) has only single-segment CTE columns, so the
lift is on the root. It is allowed: spike probe 3 rendered both `correlate(cteRoot)` and a reference to the outer CTE
root correctly on every target and both lines; the engine renders `correlate`, as for a join. A `SubSelect` whose own
root is a source model is `MQ1311` ("the sub-select's root is a source"), a later question.

### 4. A source over a source (R-SRC-16, R-SRC-18, R-PROC-35 amended)

```java
@SourceModel(root = ActivityItem.class)                     // the shape of a union: the combined rows
public record RankedActivity(@PrimaryKey String kind, @PrimaryKey Long id, Long ownerId, Instant at,
                             @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "ownerId",
                                     orderBy = "at desc") Long rank) {}

@SourceModel(root = CategoryTree.class)                     // a tree(...) source: one row per node
public record SubtreeSize(@PrimaryKey @GroupBy Long rootId,
                          @Aggregate(fn = AggregateFunction.COUNT, attribute = "id") Long nodes) {}
```

- **The root may be a source model.** `@SourceModel(root = X.class)` reads `X`'s kind as R-PROC-39 does for a query
  model: an entity, or a `@SourceModel` (a plain source, a grouped one, a shape, a recursive one). Its fields are read
  from `X`'s by name and type, single-segment paths, as an outer model's are (R-SRC-10); `@Join` is `MQ3035`, and
  `@Child` is `MQ3030`, as on any source.
- **What it may add.** `@Window`, `@Aggregate`, `@GroupBy`, `@Computed` and `@FilterColumn` over `X`'s fields. This is
  where Q-22's windows and aggregates go: R-PROC-37's and R-PROC-38's refusals stay on the recursive model and the
  branch themselves, and their messages now name the source-over-a-source form. `@FilterColumn` is allowed here,
  though R-PROC-39 makes it `MQ3011` on a model rooted at a source: the filtered column is one of `X`'s CTE columns, a
  single segment, so there is no path to resolve.
- **What it may not be.** Recursive (`@Depth`), a branch or a shape: `MQ3042`. A recursive walk over a CTE, or a
  `union` of derived sources, is a later question.
- **No correlated column inside (`MQ1908`).** A source is one CTE body, and its rows would carry a per-row sub-query
  the outer query can read once instead. The processor sees a `@Computed` class, not what its `expression()` builds,
  so the check runs when `SourceQuery.builder(...)` / `build()` receives a field list holding a correlated node:
  definition time, outside any QModel `<clinit>` (so never an `ExceptionInInitializerError`).
- **Its key.** Ungrouped, its `@PrimaryKey` is exactly `X`'s key fields; grouped, the full `@GroupBy` set (`MQ3031`,
  whose key text gains "`X`'s key fields", with a reason naming the inner source). A group key's nullability is the
  inner field's: a mapped column's by R-SRC-02; for a union shape, nullable if it is nullable in any branch; `@Depth`
  and a `@Discriminator` non-null; a `ROW_NUMBER`/`RANK`/`DENSE_RANK`/`COUNT` non-null; every other window or aggregate
  nullable. An inner `@Computed` field's nullability is checked at runtime (`MQ2201`/`MQ2202`, R-SRC-02).
- **Uniqueness carries, and so does non-uniqueness.** The outer body reads only `X`'s CTE (no joins, `MQ3035`) and
  windows add no rows, so over a unique source an ungrouped source over it is unique. Over a non-unique source, an
  ungrouped one is non-unique and `MQ1901`/`MQ2212` apply as to `X`. A grouped one is unique by its group set even over
  a non-unique source (R-SRC-09), which is the way to page a non-unique walk's summary by keyset.
- **Aggregates over a walk count walk rows.** Over a plain non-unique `recursive(...)` source, a `COUNT` counts paths,
  not distinct nodes; base a per-node count on `tree(...)`, as `SubtreeSize` does.
- **The generated builder takes the inner source.** The QModel declares `source(SourceQuery<X> inner)` in place of
  `source()` (R-GEN-39), so a chain without its inner source does not compile; a wrong inner one is `MQ1907`.
  `SourceQuery` holds the inner one and stays immutable (INV-9). Chains have no depth limit; each level is one CTE,
  engine-named (D-146).

**A chain's statement (R-SRC-18).**

- **The walk's filter goes into the next body.** R-SRC-08 has the outer statement filter `level <= maxDepth and not
  mark`; in a chain, the next CTE body applies that filter before its windows and aggregates, so a window never ranks a
  row past the depth or on a cycle.
- **Checks stay in the top statement.** The depth, cycle and duplicate-key checks (R-SRC-08, R-SRC-15) are scalar
  sub-selects over the inner CTE, placed in the top statement of every statement kind. R-SRC-14's "no row, no check"
  also covers an outer `having` that drops every group: the checks read the inner CTE, not the outer rows.
- **Key predicates stay in the top statement.** `byKeys`' IN, the keyset cursor, `primaryKeyFirst`'s step 2 and
  `one(q, key)` apply only to the top statement, never inside a body with a window or aggregate, so ranks and counts are
  computed over the full source.

```sql
-- the activity feed ranked per owner over the union's combined rows
with activity (kind, id, owner_id, at) as (
    select 'ORDER', o.id, o.owner_id, o.created_at from orders o where …
    union all
    select 'REFUND', r.id, r.owner_id, r.created_at from refund r where …),
ranked (kind, id, owner_id, at, rank) as (
    select a.kind, a.id, a.owner_id, a.at, row_number() over (partition by a.owner_id order by a.at desc, a.kind, a.id)
    from activity a)
select … from ranked r where r.rank <= ? order by …
```

**The SPI.** `cte(outer, body)` returns a root in `outer`; a chain also needs a root over the inner CTE inside another
query (the next body), and so does `recursive(outer, spec)`. `cte`'s signature does not change, since that would break
every implementor. Spike probe 6 showed, on both lines and every target, that a next body built as its own criteria
can select from a sibling CTE registered on the top statement, through the handle `with(...)` returns (or a lookup
by name). A nested `WITH` inside the body works for plain chains but hides the inner CTE from the top statement, so
the walk checks can't read it; the chain registers every level on the top statement. `SourceRenderer` gains:

```java
@Incubating
interface CteHandle {}                                      // opaque: a CTE registered on one top statement

default CteHandle register(CriteriaQuery<?> top, CriteriaQuery<Tuple> body) { throw unsupported("chained CTEs"); }
default CteHandle registerRecursive(CriteriaQuery<?> top, RecursiveCte spec) { throw unsupported("chained CTEs"); }
default Root<Tuple> from(AbstractQuery<?> query, CteHandle inner) { throw unsupported("chained CTEs"); }
```

The defaults are `MQ4014` ("chained CTEs"); `cte` and `recursive` are unchanged for a single-level source. The engine
registers the innermost level first and builds each next body from `from(body, inner)`; the handles belong to one
top statement and are rebuilt per statement (content, count, each key phase, each export page), so every check reads
that statement's inner CTE.

### 5. Reading a source directly (R-SRC-17, R-GEN-40, R-GEN-37 amended)

Every source QModel that is not a branch also generates:

- a nested `Rows` class holding the read-side vocabulary: one `ColumnField.over(model, sourceRoot, …)` per field,
  window and aggregate fields included, as an outer model's columns are (R-SRC-10), with `DEFAULT`, `ALL`, `KEY` and a
  `MAPPER` into the source model itself. R-GEN-37's "no `MAPPER` … nothing maps a source row" is amended: the source
  QModel's own constants keep no mapper; `Rows` has one;
- `query(SourceQuery<S> source)`, returning a `ModelQuery.Builder` over the source whose rows are the source model.
  R-PROC-35's "`source()` in place of `query()`" is amended to "`source()`, and `query(SourceQuery<S>)` when not a
  branch".

```java
var latest = QRankedOrder.query(QRankedOrder.source().where(f -> f.eq(QRankedOrder.STATUS, status)).build())
        .select(QRankedOrder.Rows.DEFAULT)
        .where(f -> f.le(QRankedOrder.Rows.RANK, Optional.of(3L)))
        .orderBy(QRankedOrder.Rows.CUSTOMER_ID.asc(), QRankedOrder.Rows.RANK.asc())
        .keyset().build();
```

- A branch's rows are read through its shape, so a branch generates neither.
- **Mixing the two vocabularies is `MQ1905`, both ways.** The source QModel's own `ALL`, `DEFAULT`, `KEY` and
  `GROUP_KEYS` (R-GEN-37) have the same types as `Rows.ALL`, `Rows.DEFAULT` and `Rows.KEY`, because `RowMapper<M>` ties
  the result type to the model; so `query(src).select(QRankedOrder.DEFAULT)` compiles, and so does a `Rows` column in
  the source's own `where` (which would filter before the window and mean something else). `MQ1905` covers select
  sets, `where`, order keys on the read side, and a `Rows` column on the source side, raised at `build()` before any
  statement; its message names the constant to use. A window field can't be passed to a source-side filter at all,
  since a `WindowField` is not a `ScalarField`.
- Everything R-SRC-10 says about an outer model holds: the key is the source's, every key-based feature follows the
  source's uniqueness (R-SRC-09, R-SRC-13), key predicates sit in the top statement (§4), and fetch plans are not
  offered (no `@Child` on a source model).
- The generator writes fully qualified type names inside `Rows`, so a user type named `Rows` is not shadowed.

### 6. Rendering a correlated column, and its cost

A correlated column renders as a scalar sub-query in every position. The alternative is a LEFT JOIN to a grouped
derived table or CTE (`left join (select order_id, count(*) c from order_line group by order_id) x on x.order_id =
o.id`). The two trade differently:

- **A page** (20–100 rows) costs one index probe per row and column as a scalar sub-query, and a full aggregation of
  the child table as a join. The scalar form wins as the child table grows.
- **An export** or a filter on the column over the whole table costs one probe per parent row as a scalar sub-query,
  and one hash aggregation as a join. PostgreSQL and MySQL 8.0 run a scalar sub-query in the select list as a
  dependent sub-plan per row; neither decorrelates it.
- **An offset export ordered or filtered by the column** is the expensive case: every page re-evaluates the sub-query
  for every row it sorts past, roughly N sub-queries for each of P pages, and no index helps.

**The scalar form everywhere (spike probe 4).** At 100k parents with 10 children each, on both lines:

| Child FK indexed? | Page of 20 / 100 | Export | Export ordered by it | `where` on it |
|---|---|---|---|---|
| yes | scalar ~1–3 ms on PG and MySQL; join 1–2 ms on PG, ~105 ms on MySQL | PG: join 1.5× faster; MySQL: scalar 4× faster; H2: scalar 6× faster | PG and MySQL: join ~1.3–1.4× faster; H2: scalar faster | PG and MySQL: join ~1.6× faster |
| no | scalar 0.5–6 s; join 40–180 ms on PG and MySQL; H2 the same both ways | scalar over 10 s on every vendor; join 0.6–2.3 s on PG and MySQL; H2 over 10 s both ways | as export | as export |

With an index on the correlation's child column, the scalar form is within a small factor of the join everywhere and
far ahead on MySQL pages and exports; without one, the scalar form is unusable past small tables and the join is
the only thing that finishes. A join lowering would therefore pay only for a missing index, would lose on MySQL
when the index exists, and is not a statement-kind decision. The engine renders the scalar form in every position;
there is no join lowering, no `VendorProfile` fact, and no equivalence AC. The docs (Columns page, "Correlated
columns") say to index the sub-select's correlated columns, as a foreign key to a child table usually needs anyway
(PostgreSQL does not index a foreign key by itself), and give the table above. A join lowering for an unindexed
correlation stays a later question.

### 7. Provider and SPI

- `SubqueryRenderer#firstRow` (§2) through `ProviderSupport#subqueries()` (R-VND-25), `@EngineFacing @Incubating`;
  without it, `MQ4014`. `HibernateProviderSupport` implements it on both lines.
- `SourceRenderer` gains `CteHandle`, `register`, `registerRecursive` and `from` (§4), default `MQ4014`;
  `HibernateProviderSupport`'s renderer implements them on both lines. `cte` and `recursive` are unchanged.
- `SourceQuery` gains a package-private inner source; `SourceQuery.builder(...)` gains an overload taking the inner
  `SourceQuery`, used by the generated `source(inner)`.
- `ColumnField.over(...)` is unchanged; the generated `Rows` class uses it.
- No `VendorProfile` change (§6). Tier-1 vendors all run ordered, limited scalar sub-queries and chained CTEs.
- **A line difference to know about.** A PostgreSQL statement canceled by its query timeout surfaces as
  `QueryTimeoutException` on Hibernate 6.6 and `PersistenceException` on 7.4, with the same root `PSQLException`.
  Code that classifies a timeout inspects the root cause, never the outer wrapper (spike probe 7). The Criteria API and
  every value are otherwise identical on both lines.
- **A pre-existing gap, fixed in the same change:** `SourceRenderer`'s `recursive`/`window`/`combine` defaults throw
  `MQ4015` for a provider gap, which `reference/90`'s `MQ4015` row doesn't describe. They move to `MQ4014`, whose row
  becomes "the persistence provider lacks a renderer the query needs: a source, an ordered sub-query, a chained CTE, or
  a source feature".

### 8. Spike plan

Ran 2026-10-17; results in `rfc/0015-spike-results.md`, raw data in `rfc/0015-spike-data/`. Run on the RFC 0013/0014 harness as a
new `Rfc15SpikeTest`, on Hibernate 6.6.58 and 7.4.11, each against H2, PostgreSQL 16 and MySQL 8.0.31.

1. **Scalar terminals.** `count`, `exists` (as the CASE form), `min`, `max` and `sum` as select items, in `where`, and
   as an offset order key, each sharing one Criteria node per statement. On MySQL with `ONLY_FULL_GROUP_BY`, a grouped
   outer query whose lifted column is a group key.
2. **`first`.** `JpaSubQuery#orderBy` and `#fetch(1)` as a correlated scalar sub-query in the select list, in `where`
   and as an order key; ties broken by the appended id give the same row on every vendor; an order key that is NULL on
   some rows, with an explicit `nullsLast()` (§2), gives the same row on every vendor.
3. **A lifted to-one column.** A correlation on `outer.column(QOrderRow.CUSTOMER_REGION)` (root → customer → region):
   the sub-query references the outer LEFT join's alias, adds no `FROM` item, and the outer `ON` is kept. A NULL path
   gives `count = 0`. A lifted column across a collection join is still `MQ1311`. **A correlation whose outer root is a
   source model** (a CTE root): renders and returns the right values on both lines, or stays `MQ1311` (§3).
4. **Cost.** Scalar sub-query against LEFT JOIN to a grouped derived table and to a grouped CTE, for a page of 20 and
   100, for a full offset export, for an offset export ordered by the correlated column, and for a `where` on it, over
   10k, 100k and 1M parents with 10 children each on average, with and without an index on the child's foreign key.
   `EXPLAIN ANALYZE` on PostgreSQL and MySQL, `EXPLAIN` on H2, and time.
5. **Count.** A correlated column only in the select list is absent from the count statement, except a lifted INNER
   join that can change the row count, which stays; one in `where` is present.
6. **Chained CTEs.** A source over a plain source, over a grouped source, over a `unionAll` (a window over the combined
   rows), over a recursive source (a grouped count per seed, with the walk's filter in the next body) and over a
   `tree(...)` source with its R-SRC-15 checks in the top statement of every statement kind, including an outer
   `having` that drops every group; and a chain three levels deep. Records how the body references the sibling CTE on
   each line, which sets §4's SPI method.
7. **Line parity.** The probe source compiles and behaves the same on 6.6 and 7.4.

Reading a source directly (§5) is generated code over R-SRC-10's machinery: it is covered by ACs, not a probe.

### New ids

None of these exist in `docs/spec` or another RFC. RFC 0017 takes R-FLT-20..22, AC-FLT-21..27, `MQ1313` and R-VND-23,
24; R-FLT-23..26 are skipped because RFC 0002's text (trimmed by D-128) still names superseded R-FLT-20..26. The
acceptance decision is the next free `D-n` (D-150 today; coordinate with RFC 0017's).

| Id | Where | What |
|---|---|---|
| R-COL-31 | `api/10` | `Correlated`'s terminals: a scalar sub-query per row, an `ExpressionField`; equality; `MQ1511` |
| R-COL-32 | `api/10` | `first(...)`: required order, the appended id tie-breaker, null precedence as R-COL-12, `MQ4014` without the renderer |
| R-COL-33 | `api/10` | Correlated refusals, including grouped selection/orders lifting non-group keys |
| R-FLT-27 | `api/12` | A lifted column on a to-one path, resolved through the outer `JoinContext`, checked at first resolution; a source root |
| R-SRC-16 | `api/17` | A source over a source: root kinds, what it adds, its key, uniqueness and non-uniqueness carried, `MQ1908` |
| R-SRC-17 | `api/17` | Reading a source directly: `Rows`, `query(source)`, `MQ1905` both ways |
| R-SRC-18 | `api/17` | A chain's CTEs: one per level, engine-named; the walk's filter in the next body; checks and key predicates in the top statement |
| R-SRC-19 | `api/17` | A correlated column renders as a scalar sub-query everywhere; index the correlated columns (§6) |
| R-PROC-40 | `processor/30` | The source-over-source limits (`MQ3042`) |
| R-GEN-39 | `processor/31` | `source(SourceQuery<X> inner)` on a source over a source |
| R-GEN-40 | `processor/31` | The `Rows` vocabulary (fully qualified names) and `query(SourceQuery<S>)` on a non-branch source QModel |
| R-VND-25 | `vendor/40` | `ProviderSupport#subqueries()` and `SubqueryRenderer` |
| `MQ1314` | `reference/90` | Correlated group key, or grouped selection/orders lifting non-group keys |
| `MQ1510` | `reference/90` | A correlated expression aggregated |
| `MQ1511` | `reference/90` | A `count`, `sum` or `first` whose sub-select crosses a to-many join |
| `MQ1907` | `reference/90` | A source over a source built without, or with the wrong, inner `SourceQuery` |
| `MQ1908` | `reference/90` | A correlated expression in a `SourceQuery`'s field list, at `SourceQuery` build |
| `MQ3042` | `reference/90` | A source over a source that is recursive, a branch or a shape |
| Q-24 | `reference/92` | Request-time values in a `@Computed` expression: a per-call bound parameter node |
| AC-COL-36..42 | `api/10` | One per terminal; `first` ties and NULL order keys; the refusals; `MQ1511` |
| AC-FLT-28, 29 | `api/12` | A lifted to-one column in `exists` and in a correlated column; NULL path; a to-many path still `MQ1311` (AC-FLT-15 amended) |
| AC-SRC-26..37 | `api/17` | Source over plain, grouped, union, recursive and tree sources; three levels; walk filter and top-statement checks; direct read; `MQ1905` select, where, order and source-side; `MQ1908` |
| AC-PROC-41 | `processor/32` | `MQ3042` |

## Compatibility

Additive. `MQ1311` narrows from "not on the root" to "across a to-many path", which turns a throw into working code
(D-112 said so), and moves from definition to first resolution. A `@SourceModel` whose root is a source model is a
compile error today (R-PROC-35 reads the root as an entity); it now compiles. The source QModel gains members (`Rows`,
`query`) whose names collide with nothing generated today; a model field `rows` gets the constant `ROWS`, which does
not clash with the nested class `Rows`. `SourceRenderer`'s provider-gap defaults move from `MQ4015` to `MQ4014`
(0.10.0 is unreleased). Every new type and member is `@Incubating`.

## Alternatives

- **An annotation with strings** (`@Correlated(root = Line.class, on = "orderId = id", fn = COUNT)`), as
  Blaze-Persistence's correlation mappings do. It loses INV-3's typed columns, and `@Computed` already gives a typed
  home for any expression.
- **A step type** (`Expr.correlated(...).count()`). It can't bound `min`/`max`/`sum` per method, and allows `first()`
  with no order; a class-level `Comparable` bound would still leave `sum` open and block `first` over `byte[]`. An
  unchecked bound with `MQ1001` moves a type error to runtime.
- **`firstRow` on `SourceRenderer`**, or renaming that hook before the freeze. An ordered sub-query has nothing to do
  with sources, and one hook would make CTE support and ordered sub-queries depend on each other.
- **A processor check for `MQ1908`** through a marker on `ExpressionDefinition`. It would be opt-in and unenforceable: a
  definition without the marker still builds a correlated node.
- **A grouped source joined to the root** as the API for counts. It needs an entity-to-source join (RFC 0013's "later"
  item), a source model per count, and aggregates the whole child table on every page. It stays a rendering option
  (§6).
- **Lateral joins.** D-145 left them out: H2 has none, and a window source covers top-N per parent.
- **The enricher (D-114).** It stays the right tool when the value is only displayed: one extra statement per page, no
  sub-query per row. A correlated column is for a value that is filtered, ordered or paged on.
- **A trivial outer model per source**, as today. It works, but repeats the source model field for field; Q-22 asked
  for the direct read.
- **One vocabulary for both sides of a source** (the engine reading `QRankedOrder.STATUS` as a CTE column when the
  query is rooted at the source). A `WindowField` is not a `ScalarField`, so a window field still couldn't be filtered
  without a second constant; the `Rows` class is needed anyway. A top-level `QRankedOrderRows` would double the
  generated types.

## Unresolved questions

Settled by the architect review: terminals as static methods on `Correlated` (§1); `min`/`max`/`sum` bounded per
method; `firstRow` on a separate `SubqueryRenderer` (§2); `MQ1908` at `SourceQuery` build, no processor marker (§4); the
`Rows` name kept (§5). Settled by the spike: the scalar form everywhere (§6), the chained-CTE handles (§4), a correlation
over a source root allowed and `correlate(join)` for a lifted column (§3). What is left:

- **Request-time values in a correlated column.** A `@Computed` definition is a constant, so "count lines with status
  = the request's status" is not expressible. Deferred as Q-24: the general fix is a per-call bound parameter node for
  any `@Computed`, not something specific to correlation.
- **A correlated column ordered under concurrent writes** moves rows between offset pages, as RFC 0014 §5's derived
  values do, and a moved row is skipped silently (R-PAG-25's precondition). The docs say so, and that a correlated value
  changes when *another* table is written, so "the parents aren't edited" isn't enough; REPEATABLE READ or a snapshot
  is the full guarantee (R-SRC-15, D-147). It is not a check. A one-time WARNING per `ModelQuery` on an export ordered
  by a correlated key, as R-EXE-05 warns for count, is left out.
- **Later, not in this RFC:** keyset over a correlated column; a join lowering for an unindexed correlation; a recursive source or a set operation over sources; an
  entity join from a model rooted at a source; correlated columns inside a source body.
