# 16 — Query Inspection and Test Support

**Covers:** the read-only view of what a built `ModelQuery` filters, groups and sorts on (`ModelQuery.conditions()`),
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
QueryConditions conditions = q.conditions();     // where, having, order; immutable
List<Condition> where = conditions.where();      // top-level AND, in the order the filters were added
Condition c = where.get(0);
c.kind();      // Condition.Kind.EQ
c.column();    // QOrderView.STATUS (a SelectField or TableField)
c.values();    // [OPEN]
c.children();  // nested conditions of OR, NOT, EXISTS
```

The exact names and the choice between one `Condition` class with a `Kind` and a sealed hierarchy are settled by the
`architect-review` before M8.17 (Java 17 has no record patterns, which favours the single class).

## 2. Rules

**R-INS-01** **Every filter records what it is.** Each `Filters` operator (`api/12` §1) and each `having` operator
(`api/13`) records a `Condition` next to the predicate it builds, at the same time, so the view always matches the
statement: the operator, the column or path, the bound values as passed (before any converter), and nested
conditions for `or`, `not` and `exists`.

**R-INS-02** **Skipping is visible as absence.** A filter skipped by an empty `Optional` (R-FLT-01, R-FLT-03) records
nothing; an `or`, `not` or `exists` whose inner filters were all skipped records nothing, exactly as it renders
nothing. `in(col, List.of())` records `IN` with no values and `or(List.of())` records an `OR` with no children, since
both render `FALSE` (R-FLT-02, D-92).

**R-INS-03** **Custom filters are opaque but nameable.** `add(custom)` records `Condition.Kind.CUSTOM` with no label;
the new overload `add(String label, custom)` records the label. Nothing else about a custom predicate is inspected.

**R-INS-04** **The view is a value.** `conditions()` returns an immutable, thread-safe tree (INV-9), equal for two
queries built from equal calls; `withFetch` and `orderedBy` copies share it.

**R-INS-05** **`toString` names conditions, never values.** `ModelQuery.toString()` and the D-95 debug log list each
condition's operator and column, with values shown as `?`, since bound values can be personal data; only
`conditions()` exposes the values.

**R-INS-06** **The test module reads only the public view.** `model-query-test` depends on `core` and AssertJ and
nothing else (INV-7); its assertions use `conditions()`, `select()`, `fetch()` and the order, never an internal class.
It needs no `EntityManager`, metamodel or database.

**R-INS-07** **Matchers mirror `Filters`.** `FilterMatchers` has one factory per `Filters` operator, with the same
name and parameters (`eq`, `ne`, `in`, `like`, `isNull`, `between`, `or`, `not`, `exists`, …), plus `custom(label)`.
`hasFilters` matches the top-level conditions exactly and order-insensitively; `containsFilter` matches one of them;
a failure message prints the whole condition tree.

## 3. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-INS-01 | Each `Filters` and `having` operator records its kind, column and values; `or`, `not` and `exists` record their children (R-INS-01). |
| AC-INS-02 | A request with one of two `Optional` filters empty yields one condition; an `or` with every branch skipped yields none; an empty `in` yields `IN` with no values (R-INS-02). |
| AC-INS-03 | `add(label, …)` records its label and `add(…)` none (R-INS-03). |
| AC-INS-04 | `toString()` of a query with an `eq` on a string column contains the column and `?`, not the value (R-INS-05). |
| AC-INS-05 | A unit test in `model-query-test` asserts a query captured from a mocked `ModelQueryExecutor`, with no persistence provider on its test classpath (R-INS-06, R-INS-07). |
| AC-INS-06 | A failing `hasFilters` names the missing and the unexpected conditions (R-INS-07). |
