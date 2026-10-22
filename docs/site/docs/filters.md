# Filters

`where` hands you a `Filters` builder that ANDs every filter you add. Every method that takes a value has two forms:

- `eq(COLUMN, value)` always applies. A `null` value fails with `MQ1301`, because "no filter" should be explicit.
- `eq(COLUMN, Optional<value>)` applies only when the `Optional` is present. An empty `Optional` skips the filter
  and creates no join for it.

That makes search forms easy to write, with every field optional:

```java
.where(f -> f
        .eq(QOrderView.STATUS, status)                        // Optional<String>
        .range(QOrderView.CREATED_AT, from, to)               // Optional bounds
        .or(g -> g.likeIgnoreCase(QOrderView.CUSTOMER_NAME, q, LikeMode.CONTAINS),
            g -> g.eq(QOrderView.CUSTOMER_EMAIL, q))
        .exists(QOrderView.ITEMS_TABLE, i -> i.eq(QOrderView.ITEM_SKU, sku))
        .when(!includeCancelled, g -> g.ne(QOrderView.STATUS, "CANCELLED")))
```

The available filters:

| Group | Methods |
|---|---|
| Equality and comparison | `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `range`, `between` |
| Sets | `in`, `notIn` |
| Strings | `like`, `likeIgnoreCase`, `eqIgnoreCase`, with a `LikeMode` of `EXACT`, `CONTAINS`, `STARTS_WITH` or `ENDS_WITH` |
| Nulls | `isNull`, `isNotNull`, and a tri-state `isNull(column, Optional<Boolean>)` |
| Column against column | `compare(left, Op, right)` |
| Entity type | `ofType`, `notOfType` (see [Inheritance roots](inheritance-roots.md#entity-type-typeof-oftype-and-notoftype)) |
| Composition | `or` (two or three branches, or a `List` of them), `not`, `when`, `apply` (reuse a shared fragment) |
| Sub-queries | `exists`, `notExists` |
| Escape hatch | `add((joinContext, criteriaBuilder) -> predicate)`, or `add("label", ...)` to name it for logs and [tests](testing.md); the label is a constant, never a value |

## What the rules mean for you

- **Skipping is local.** A skipped filter disappears from its group. An `or` whose branches were all skipped is
  skipped, rather than turning into `FALSE` and matching nothing. Use `exists(path)` with no inner group to ask for
  "has at least one" explicitly.
- **An empty collection is not "skip".** `in(COLUMN, List.of())` matches nothing, and `notIn(COLUMN, List.of())`
  adds no condition, because an empty selection in a UI means "none of these". To skip, pass `Optional.empty()`.
- **Negation includes NULLs.** `ne` and `notIn` on a nullable column also match rows where the column is NULL, which
  is what a report filter means by "not X". Add `isNotNull` for the strict SQL meaning. `not(group)` is plain
  `NOT (...)`, so rows where the group is unknown are excluded.
- **Strings are escaped.** `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` escape `%`, `_` and the escape character, so a
  search for `50%` finds that literal text. `EXACT` passes the pattern through unchanged.
- **Filtering by entity type.** `ofType(table, Subtype.class)` restricts rows to the named entity subtype and its
  subclasses, and `notOfType` negates it; see
  [Inheritance roots](inheritance-roots.md#entity-type-typeof-oftype-and-notoftype).
- **`likeIgnoreCase` lower-cases the column**, so it needs a functional index to be fast. Without it, case
  sensitivity follows the column's collation; see [Vendor notes](vendors.md).
- **Every value is a bind parameter.** The library never inlines a value into SQL.
- **Inline value handling (`hibernate.criteria.value_handling_mode=inline`).** Under this factory setting, Hibernate
  normally renders criteria values without an explicit parameter as inline SQL literals. ModelQuery detects this mode
  (`ProviderSupport#inlinesValues`, `R-VND-28`) and binds every filter value, comparison, membership chunk, range,
  ignore-case parameter, call-time key (`one(q, key)`, `byKeys`, primary-key-first pages), keyset cursor, fetch-plan
  round key, and update assignment as an explicit statement parameter (`R-FLT-08`). Where the provider's operand
  binder can type the parameter by the column mapping, a mapped parameter is used; where no binder is available or it
  declines, only mapping-independent types (`String`, `java.lang` or `java.math` numbers, and `java.time` types) bind
  by class, and a value of a mapping-dependent type (an enum, `Boolean`, `UUID`, array, or a converter's non-JDK
  attribute type) fails with `MQ1502` before any statement runs.
- **Long `IN` lists are split** into chunks of the vendor's limit, within one statement. A query's own statement is
  never split across statements, so one filter with more values than the vendor's bind parameter limit fails with
  `MQ1306` when the query is built, and filters that only together pass it fail with `MQ1307` before the statement
  runs, instead of failing in the database. A keyset export page or write round binds the previous row's sort keys on
  top of the query's own, so a query that fits the first page can pass the limit on a later one; that `MQ1307` says
  how many of the binds are the cursor's.
- **A join first needed inside `or` or `not` is a LEFT join**, so one branch cannot remove rows another branch should
  match.
- **Filter-only to-many joins render as a semi-join.** When a statement's `where` makes a to-many join that nothing
  else in the statement keeps, its terms that join or nest a sub-query render inside one correlated sub-query over a
  second root of the same entity: `exists (select 1 from <entity> r2 <joins> where r2 = r and <terms>)` (`R-EXE-16`,
  `D-180`). Root terms (those that make no join and no sub-query) stay on the outer statement (`D-181`), so an index
  on the root table can narrow the outer scan. Each root is returned once however many children match: `list`, `stream`
  and offset pages never duplicate root rows, keyset paging and export do not end early with short pages, `one()`,
  `one(q, key)` and `byKeys()` return the root rather than throwing `MQ2003`, and `count` agrees with `list` without
  needing `count(distinct root)`. Where a customizer adds a join, or a shape the semi-join excludes is present (a
  treated node, a having, or a selected column, order or group key that keeps the to-many join, or a fetch-plan through
  load), the query retains the outer join as before and continues to duplicate rows.
  *Performance caveat:* In benchmarks, PostgreSQL ran the semi-join roughly 2–3.5x faster than a join or DISTINCT,
  while MySQL 8.0 was 1.4–3.6x slower due to duplicate weedout and a second root lookup. These are single-laptop
  EXPLAIN observations; check real execution plans and indexes. There is no unique-per-root escape hatch to force a
  join.

## Literal filters

A filter can compare against a value the planner must see as a literal instead of a bind parameter. `Filters#in` and
`#notIn` take a fixed set of operand fields — `in(col, a, b)`, `in(col, a, b, c)` and the same two for `notIn` — and
each operand is a `ScalarField` of the column's own model and type, so an `Expr.constant` puts its literal in the set
(R-FLT-19, `@Incubating`):

```java
private static final ColumnField<Row, ConstantRowEntity, ConstantStatus> STATUS =
        ColumnField.of(Row.class, ROWS, "status", ConstantStatus.class);
private static final ExpressionField<Row, ConstantStatus> PENDING = Expr.constant(STATUS, ConstantStatus.PENDING);
private static final ExpressionField<Row, ConstantStatus> FAILED = Expr.constant(STATUS, ConstantStatus.FAILED);

// status in ('PENDING','FAILED')
.where(f -> f.in(STATUS, PENDING, FAILED))
// status not in ('PENDING','FAILED') or status is null
.where(f -> f.notIn(STATUS, PENDING, FAILED))
```

`notIn` adds `OR col IS NULL`, so rows whose column is NULL match, exactly as the value form does (R-FLT-04). There is
no `Optional` form; a `List` of values uses the value form `in(col, Collection)`, which binds. The operands are inlined
into the statement as SQL text, so use them only for values defined in code — never request input (R-COL-18).
Inlining relies on the server's default quoting mode: MySQL without `NO_BACKSLASH_ESCAPES` (as Hibernate knows it at
boot) and PostgreSQL with `standard_conforming_strings=on`.

Two cautions for the `notIn` field form. A NULL-valued operand makes `NOT IN` unknown for every row, so only rows whose
column is NULL match. And the added `OR col IS NULL` can stop a partial index's predicate from matching; for a plain
`NOT IN`, use `not(g -> g.in(col, a, b))`.

`Expr.constant(typedBy, value)` types the literal by a column. The value goes through the column's converter (`MQ1308`
when it refuses) and then the attribute's JPA mapping, so an `@Enumerated(STRING)` enum inlines its name, an `ORDINAL`
its ordinal, and an `AttributeConverter` its converted database value. The stored value must be a `String`, `Number` or
`Boolean`, `typedBy` must be a basic attribute of a root table's entity (a joined column is none), and the provider must
offer a constant renderer, else `MQ1508`; the message names which of the three applies. A column-typed constant is
operand-only: a comparison or `in`/`notIn` operand. As a model column, projection, order or group key it fails at
definition with `MQ1508`. `typedBy` is a type witness: it is part of the expression's equality, but adds
no column to `columns()` and no join.

**Plan cache.** Each distinct constant is a distinct statement text, so it gets its own plan-cache entry in the
database. Use `Expr.constant` for a small fixed set — enum states, the predicate of a partial index — and
[`Expr.value`](#bound-function-arguments) for a value that changes per request.

Tested by `TypedConstantTest` in the TCK (AC-FLT-20, AC-COL-32).

## Bound function arguments

`Expr.value(value)` renders a value as a statement parameter, for a function argument or an operand the planner must
not see as a literal (R-COL-26, `@Incubating`). It is typed by `value.getClass()`, reads no column and adds no join,
and the executor binds it on every statement that renders it — a `list`, `count`, page, export, `exists` or sub-select
body, a CASE condition, and a bulk update or delete — one bind per rendering:

```java
ExpressionField<Hour, CustomerEntity, LocalDateTime> hour = Expr.function("timezone", LocalDateTime.class,
        Expr.value(zone), CREATED_AT);
```

A `null`, an `Optional`, a `Collection` or a `Map` value is `MQ1502`: a collection is never a single bound value. On
PostgreSQL a bound argument reaches a function whose parameter type is text with no cast — `timezone(?, created_at)`
and `jsonb_exists(tags, ?)` both bind and run — but a jsonb column compared to a bound `String` needs an explicit cast
the library does not emit, so cast syntax stays unsupported (RFC 0002 §Outcome).

**Volatile functions.** A function reading a bound argument is evaluated by each statement that renders it, so the
pages of a paged read or an export, or the two steps of a primary-key-first page, may see different values of e.g.
`now()` (INV-4). A value that must be stable across a run is read from a column, not bound.

Both build on the `Expr` surface on [Sub-queries and expressions](subqueries-expressions.md); the
[Call a function with literal arguments](recipes.md#call-a-function-with-literal-arguments) recipe covers function
arguments that never bind.

Tested by `BoundArgumentTest` and `BoundValueFunctionTest` in the TCK (AC-COL-31).

## Sequence values

`Expr.nextval(sequence)` reads a database sequence's next value as a `Long` column of a read (R-COL-28,
`@Incubating`). It reads no column and adds no join; the provider renders the dialect's own call, `nextval('tck_seq')`
on PostgreSQL and `next value for tck_seq` on H2:

```java
private static final ExpressionField<Numbered, Long> NEXT = Expr.nextval("tck_seq");

var query = ModelQuery.builder(ROWS, row -> new Numbered(row.get(ID), row.get(NEXT)))
        .select(SelectSet.of(ID, NEXT))
        .orderBy(ID.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<Numbered> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<Numbered> rows = rowsRepository.findAll(query, Limit.unlimited());
    ```

The name is a plain SQL identifier, optionally schema-qualified (`schema.name`); any other text is `MQ1509` as soon as
`nextval` is called. `MQ1509` also refuses a sequence value in a filter, a `having`, an order or a group key, however
deeply it sits in an expression (a sequence advances on every evaluation, so none of them would be stable), and on
MySQL, which has no sequences, or with a provider that renders none; each refusal comes before any statement runs. A
value is consumed on every evaluation: a re-run of the query takes new values, and `one(q)`, which reads with
`LIMIT 2`, takes a second value when a second row matches.

A write takes a sequence value through `setExpression(column, ExpressionField)`: an update or conflict assignment, an
insert-values value or an insert-select column, each evaluated per written row (R-WRT-53). See
[Bulk writes](bulk-writes.md#sequence-values-and-other-write-expressions) and [Inserts](inserts.md#sequence-values).

Tested by `SequenceNextvalTest` in the TCK (AC-COL-33).

## `exists`

`exists(ITEMS_TABLE, inner)` renders a correlated sub-query. It joins nothing on the outer query, so `count` and
export need no de-duplication, which makes it the preferred form for "has a child matching X". Columns inside
`inner` must sit on the given path or below it.
See [Sub-queries and expressions](subqueries-expressions.md#exists-and-notexists).

## Sub-selects

`in` and `notIn` also take a `SubSelect` — one column of another root with its own filters — instead of a list:

```java
var p007Items = SubSelect.of(ITEM_ORDER_ID).where(f -> f.eq(ITEM_PRODUCT, "P007"));
.where(f -> f.in(ID, p007Items))
.where(f -> f.notIn(ID, p007Items))
```

`notIn` over a sub-select adds `IS NOT NULL` inside and `OR col IS NULL` outside, so a `NULL` among the sub-select's
values never empties the result and rows whose own column is `NULL` are kept — the same rows as a `notExists`
correlated on equality (R-FLT-16). An empty sub-select keeps the empty-list rule: `in` matches nothing, `notIn`
every row.

A sub-select is never correlated; for "a row the sub-select matches" use `exists`, and where there is no association
between the roots, lift the outer column with `outer.column(...)`. The lifted column must sit on the outer root, so
the outer query joins nothing. On PostgreSQL, prefer `notExists` over a large `notIn` sub-select. The full surface,
and the expressions that plug into a filter, is on [Sub-queries and expressions](subqueries-expressions.md).

`Filters` builds `WHERE` predicates only. Conditions on aggregates go through `having`; see
[Grouped queries](grouped-queries.md).
