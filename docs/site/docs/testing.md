# Testing queries without a database

*Incubating.* A service that turns a request into a `ModelQuery` can be tested without a database, a persistence
provider or a Spring context: hand the service a mocked repository or executor, capture the query it passes, and assert
on what the query filters, orders and selects. `model-query-test` does the asserting.

## Add the module

```xml
<dependency>
    <groupId>io.github.rey5137</groupId>
    <artifactId>model-query-test</artifactId>
    <scope>test</scope>
</dependency>
```

It depends on `model-query-core` and AssertJ only. The version comes from the BOM.

## Capture and assert

Any mocking library works. With Mockito, capture the query a service gives a mocked `ModelQueryRepository`
(or `ModelQueryExecutor`):

```java
import static com.rey.modelquery.test.FilterMatchers.*;
import static com.rey.modelquery.test.QueryAssertions.assertThatQuery;

ArgumentCaptor<ModelQuery<OrderEntity, ?, OrderView>> query = ArgumentCaptor.forClass(ModelQuery.class);
service.search(new OrderSearch(Optional.of(OPEN), Optional.empty()));
verify(repository).findPage(query.capture(), any(), any());

assertThatQuery(query.getValue())
        .hasFilters(eq(QOrderView.STATUS, OPEN))              // exactly these, in any order; the skipped one is absent
        .isOrderedBy(QOrderView.CREATED_AT.desc());
```

A filter skipped by an empty `Optional` is simply absent, so the test says what the request turned into.

## What you can assert

| Assertion | Checks |
|---|---|
| `hasFilters(matchers...)` | The top-level `where` conditions are exactly these, in any order. |
| `containsFilter(matcher)` | One top-level `where` condition matches. |
| `hasNoFilters()` | There is no `where` condition. |
| `hasHaving(...)`, `containsHaving(...)`, `hasNoHaving()` | The same, over `having`. |
| `isOrderedBy(keys...)`, `isNotOrdered()` | `orderBy()`, in order, with directions and null precedence. |
| `hasSelection(fields...)`, `selectionContains(fields...)` | The query's `select()`. |
| `hasFetchSelection(fields...)`, `fetchSelectionContains(fields...)`, `hasNoFetchPlan()` | The selection of the query's fetch plan. |

The matchers in `FilterMatchers` mirror the `Filters` operators by name, with the value form of their parameters:
`eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `range`, `between`, `in`, `notIn`, `like`, `likeIgnoreCase`, `eqIgnoreCase`,
`isNull`, `isNotNull`, `compare`, `or`, `not`, `exists` and `notExists`. They have no `Optional` forms, and no `when` or
`apply`, which record nothing of their own. The aggregate overloads serve `having`. Two more:

- `and(...)` is a branch of an `or` that holds two or more filters: `or(and(a, b), c)` is not `or(a, b, c)`.
- `custom(label)` matches a filter added with `add("label", ...)`. Nothing else about a custom predicate can be
  inspected, so name the ones you want to test, with a constant, never a value.

```java
assertThatQuery(q).containsFilter(or(eq(QOrderView.STATUS, OPEN), isNull(QOrderView.CLOSED_AT)));
assertThatQuery(q).containsFilter(exists(QOrderView.ITEMS_TABLE, gt(QOrderView.ITEM_QTY, 0)));
assertThatQuery(q).containsFilter(custom("region visible to user"));
```

`in` and `notIn` match their values as a multiset, so their order does not matter. Values are compared as passed to the
query, before any converter. The operands of `or`, `and`, `not` and `exists` match in the order they were added.

When an assertion fails, the message prints the whole condition tree, values included, and `hasFilters` names the
conditions that are missing and those that were not expected.

## Reading the conditions yourself

`ModelQuery.conditions()` (incubating) returns the same view the assertions read: `where()` and `having()`, each a list
of `Condition` with `kind()`, `column()`, `values()`, `children()` and the other accessors. Its `toString()` and the
build log show each value as `?`, since a bound value can be personal data; only the accessors expose values.

What the view does not hold: filters from a `QueryCustomizer` and the ones the executor adds (keyset, child keys), and
the filters of a fetch plan's child queries.
