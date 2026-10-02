# 16 — Query Inspection and Test Support

**Covers:** the read-only view of what a built `ModelQuery` filters on, in `where` and `having` (`ModelQuery.conditions()`),
labelled custom filters, and the `model-query-test` module that asserts on that view in a unit test with no database.
**Read when:** a test checks which filters a request turned into, or a log needs to say more than "2 filters".
**Owns:** `R-INS-*`, `AC-INS-*`. The `Filters` DSL itself is `api/12`; `having` is `api/13`; the module rules are
`delivery/61`.

---

## 1. Shape

```java
// Service under test: turns a request into a query and hands it to a mocked repository.
ArgumentCaptor<ModelQuery<OrderEntity, ?, OrderView>> query = ArgumentCaptor.forClass(ModelQuery.class);
service.search(new OrderSearch(Optional.of(OPEN), Optional.empty()));
verify(repository).findPage(query.capture(), any(), any());

assertThatQuery(query.getValue())
        .hasFilters(eq(QOrderView.STATUS, OPEN))              // exactly these, in any order; the skipped one is absent
        .isOrderedBy(QOrderView.CREATED_AT.desc());

assertThatQuery(q).hasNoFilters();
assertThatQuery(q).containsFilter(or(eq(QOrderView.STATUS, OPEN), isNull(QOrderView.CLOSED_AT)));
assertThatQuery(q).containsFilter(exists(QOrderView.ITEMS_TABLE, gt(QOrderView.ITEM_QTY, 0)));
assertThatQuery(q).containsFilter(custom("region visible to user"));      // a labelled add(...)
```

```java
// core, @Incubating
QueryConditions conditions = q.conditions();     // where and having; immutable. Order stays q.orderBy().
List<Condition> where = conditions.where();      // top-level AND, in the order the filters were added
Condition c = where.get(0);
c.kind();      // Condition.Kind.EQ
c.column();    // Optional<SelectField<?, ?>>: QOrderView.STATUS
c.values();    // List<Object>: [OPEN], as passed, before any converter
c.children();  // List<Condition>: the operands of AND, OR, NOT, EXISTS, NOT_EXISTS
c.right();     // Optional<SelectField<?, ?>>: the other column of a compare(...)
c.op();        // Optional<Op>: the operator of a compare(...)
c.likeMode();  // Optional<LikeMode>: LIKE and LIKE_IGNORE_CASE
c.path();      // Optional<TableField<?, ?>>: the join path of EXISTS and NOT_EXISTS
c.label();     // Optional<String>: a labelled add(...)
```

`Condition` and `QueryConditions` are final classes, not records, with no public constructor or factory: only the DSL
builds them (D-101). `Condition.Kind` is an enum that may gain constants in a later release, so a `switch` on it needs
a `default`:

| Kind | Recorded by | Fields set |
|---|---|---|
| `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE` | the comparison of that name, and a one-sided `range` or `between` | `column`, one value |
| `RANGE`, `BETWEEN` | `range` and `between` with both bounds | `column`, `[from, to]` |
| `IN`, `NOT_IN` | `in`, `notIn` | `column`, the values (none for an empty collection) |
| `LIKE`, `LIKE_IGNORE_CASE` | `like`, `likeIgnoreCase` | `column`, one value, `likeMode` |
| `EQ_IGNORE_CASE` | `eqIgnoreCase` | `column`, one value |
| `IS_NULL`, `IS_NOT_NULL` | `isNull`, `isNotNull` | `column` |
| `COMPARE` | `compare(left, op, right)` | `column`, `op`, `right` |
| `AND` | an `or` branch of two or more filters | `children` |
| `OR` | `or` | `children`, one per branch |
| `NOT` | `not` | `children` (an implicit AND) |
| `EXISTS`, `NOT_EXISTS` | `exists`, `notExists` | `path`, `children` (an implicit AND) |
| `CUSTOM` | `add` | `label` when given |

`having` records the same kinds over aggregate columns, except `EXISTS`, `NOT_EXISTS` and `CUSTOM`, which it has no
operator for.

## 2. Rules

**R-INS-01** **Every filter records what it is.** Each `Filters` operator (`api/12` §1) and each `having` operator
(`api/13`) records a `Condition` at the one point where it records its predicate, so no predicate can be added without
its condition. The view is the logical tree of the `where` and `having` calls: the kind, the column or path, the bound
values as passed (before any converter, and not lower-cased for the `IgnoreCase` operators), and the children. A
one-sided `range` or `between` records the comparison it renders (`GTE`, `LT` or `LTE`). Rendering may simplify the
tree (`ne` adds `OR IS NULL`, a long `IN` is chunked, an `or` left with one branch renders that branch); predicates
from a `QueryCustomizer` and those the executor adds (keyset, child keys) are not in it.

**R-INS-02** **Skipping is visible as absence.** A filter skipped by an empty `Optional` (R-FLT-01, R-FLT-03) records
nothing; an `or`, `not` or `exists` whose inner filters were all skipped records nothing, exactly as it renders
nothing. `in(col, List.of())` records `IN` with no values and `or(List.of())` records an `OR` with no children, since
both render `FALSE` (R-FLT-02, D-92); `notIn(col, List.of())` records `NOT_IN` with no values, though it renders
nothing.

**R-INS-03** **Custom filters are opaque but nameable.** `add(custom)` records `Condition.Kind.CUSTOM` with no label;
the new overload `add(String label, custom)` records the label, and a null or blank label is `MQ1301`. Labels are
printed in logs and failure messages, so a label is a constant, never a value. Nothing else about a custom predicate
is inspected.

**R-INS-04** **The view is a value.** `conditions()` returns an immutable, thread-safe tree (INV-9), equal for two
queries built from equal calls; `withFetch` and `orderedBy` copies share it. Columns compare as `ColumnField` does,
paths by their key, `CUSTOM` conditions by label alone. Values are held by reference: a mutable value changed after
the call (a `java.util.Date`) changes the view.

**R-INS-05** **Logs name conditions, never values.** `QueryConditions.toString()`, `Condition.toString()` and the D-95
build log list each condition's kind and column, with values shown as `?`, since bound values can be personal data;
only the accessors expose the values. `ModelQuery.toString()` stays the model's name. The text format is not API.

**R-INS-06** **The test module reads only the public view.** `model-query-test` depends on `core` and AssertJ and
nothing else (INV-7); its assertions use `conditions()`, `select()`, `orderBy()` and `fetch().map(FetchPlan::select)`,
never an internal or `@EngineFacing` type. It needs no `EntityManager`, metamodel or database. `conditions()` covers
the query's own `where` and `having` only: the filters of a fetch plan's child queries are not in it (Q-13).

**R-INS-07** **Matchers mirror `Filters`.** `FilterMatchers` has one factory per `Filters` operator, with the same
name and the value form of its parameters (`eq`, `ne`, `in`, `like`, `isNull`, `between`, `compare`, `or`, `not`,
`exists`, …), plus `and(...)` for an `or` branch and `custom(label)`; no `Optional` overloads, and no `when` or `apply`,
which record nothing of their own. A matcher is a test-module type that matches a `Condition`; core exposes no way to
build one. `IN` and `NOT_IN` values match as multisets. `hasFilters` matches the top-level conditions exactly, as a
multiset, in any order; `containsFilter` matches one of them; a failure message prints the whole condition tree,
values included.

## 3. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-INS-01 | Each `Filters` and `having` operator records its kind, column and values; `or`, `not` and `exists` record their children, and an `or` branch of two filters records an `AND`; a one-sided `range` records `GTE` (R-INS-01). |
| AC-INS-02 | A request with one of two `Optional` filters empty yields one condition; an `or` with every branch skipped yields none; an empty `in` yields `IN` and an empty `notIn` yields `NOT_IN`, each with no values (R-INS-02). |
| AC-INS-03 | `add(label, …)` records its label and `add(…)` none; a blank label is `MQ1301` (R-INS-03). |
| AC-INS-04 | `conditions().toString()` and the build log of a query with an `eq` on a string column contain the column and `?`, not the value (R-INS-05). |
| AC-INS-05 | A unit test in `model-query-test` asserts a query captured from a mocked `ModelQueryExecutor`, with no persistence provider on its test classpath (R-INS-06, R-INS-07). |
| AC-INS-06 | A failing `hasFilters` names the missing and the unexpected conditions (R-INS-07). |
