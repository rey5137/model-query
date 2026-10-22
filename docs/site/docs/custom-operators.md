# Custom operators

!!! note "Incubating"
    Custom operators are annotated `@Incubating` (see [API stability](stability.md)).

`FilterOperator<C, V>` defines a named predicate for a column of type `C` and an operand of type `V`
(R-FLT-20..22, `@Incubating`). Define it once and reuse it:

```java
private static final FilterOperator<String, String> SAME_TEXT =
        FilterOperator.of("sameText", (ctx, left, right) -> ctx.cb().equal(left, right));

private static final FilterOperator<String, String> TRIMMED_TEXT =
        FilterOperator.<String>of("trimmedText", (ctx, left, right) -> ctx.cb().equal(left, right))
                .requiringAttribute(String.class)
                .preparing(String::trim);
```

The lambda is an `OperatorRenderer<C, V>`; its `OperatorContext` exposes the statement's `CriteriaBuilder` through
`cb()`. Use the `right` expression you are given. In value form it is a parameter bound separately for every
statement, including counts and export pages; in field form it is the right field's expression. Never capture a
request value in a renderer or replace `right` with `cb.literal(value)` or `cb.equal(left, value)`. The one
exception is the catalogue's validated `fullTextMatch` configuration, which is definition text (D-173). An array value
passed to `op` is copied when the filter is added (R-INS-13).

`FilterOperator.of(name, renderer)` takes a value of the column's type (`C, C`). For a different operand type, use
`FilterOperator.of(name, valueType, renderer)` (`C, V`); that value is never passed through a `ColumnConverter`, unlike
`of(name, renderer)`'s, so on a `String`-to-`String` `ColumnConverter` column the two bind differently. Use
`of(name, renderer)` when the value is a column value.
`requiringAttribute(type)` checks the mapped attribute's type (`MQ1001` on a mismatch). `preparing(prepare)`
transforms a value once when the filter is added, after a `ColumnConverter` has converted it. A renderer or preparation
returning `null` or throwing a runtime exception is `MQ1313`; a `ModelQueryException` keeps its own code.

`requires(OperatorFeature.ILIKE)` declares a feature that both vendor and provider must serve; the other features are
`REGEX_MATCH`, `ARRAY_CONTAINMENT`, `JSONB_CONTAINMENT` and `FULL_TEXT`. It does not supply a renderer. Each of
`requiringAttribute`, `requires` and `preparing` returns a new definition, replacing that setting (D-168, D-171).

Keep definitions in `static final` constants (INV-9). Operators compare by identity: two queries sharing a constant
can be equal; creating another operator with the same name does not give it the same identity.

`Filters.op` has value, `Optional` and field forms:

```java
.where(f -> f.op(SAME_TEXT, QOrderView.CUSTOMER_NAME, "Acme"))
.where(f -> f.op(SAME_TEXT, QOrderView.CUSTOMER_NAME, Optional.of("Acme")))
.where(f -> f.op(SAME_TEXT, QOrderView.CUSTOMER_NAME, QOrderView.CUSTOMER_EMAIL))
```

These are alternative filters. An empty `Optional` skips its filter and creates no join. `Having.op` has the same
three forms over aggregates: `h.op(operator, aggregate, value)`, `h.op(operator, aggregate, optionalValue)` and
`h.op(operator, leftAggregate, rightAggregate)`; see [Grouped queries](grouped-queries.md).

Without an operand binder, values are limited to strings, `java.lang` or `java.math` numbers and `java.time` values.
An enum, array, `Boolean` or `UUID` needs a binder that types the parameter by the column's mapping (`MQ1502`
otherwise). Hibernate supplies that binder on its supported lines. A `Collection`, `Map` or nested `Optional` is
never a single operator value (`MQ1502`); a null value is `MQ1301` (R-FLT-21).

Both `op(...)` and `not(g -> g.op(...))` drop rows whose column is NULL: no `OR col IS NULL` is added (R-FLT-22).
The NULL handling of `ne` and `notIn` does not apply here.

**Hibernate catalogue.** Add `model-query-hibernate` to use `HibernateOperators`:

| Factory | Meaning | Vendors served |
|---|---|---|
| `ilike(LikeMode)` | Case-insensitive LIKE; the value uses the same pattern preparation as `like` | PostgreSQL, H2 |
| `regexMatch()` | PostgreSQL `~` regular-expression match; the pattern is text, never converted by a `ColumnConverter` or `@Convert` (D-174) | PostgreSQL |
| `arrayContains()` | Array contains every element of the operand (`@>`); boxed arrays only (`Integer[]`, not `int[]`) | PostgreSQL |
| `arrayOverlaps()` | Arrays share an element (`&&`); boxed arrays only | PostgreSQL |
| `jsonbContains()` | JSON document contains the operand's JSON text (`@>`) | PostgreSQL |
| `fullTextMatch(configuration)` | Plain-text words must all match (`plainto_tsquery`) | PostgreSQL |

MySQL serves none of these catalogue features. CockroachDB is refused with `MQ4015` unless a profile answers `ILIKE`,
even though Hibernate's `CockroachDialect` renders a native `ilike`; on Hibernate 7.4 a Spanner PostgreSQL dialect is
`MQ4014` for `ilike`, since it emulates it (D-175). At execution the vendor check runs first (`MQ4015`),
then the provider check (`MQ4014`), before any statement. A custom vendor profile answering yes cannot bypass the
provider check; rendering a feature-requiring operator without a gate is also `MQ4014`.

```java
private static final FilterOperator<String, String> NAME_CONTAINS =
        HibernateOperators.ilike(LikeMode.CONTAINS);
private static final FilterOperator<String, String> ENGLISH_TEXT =
        HibernateOperators.fullTextMatch("english");

var query = QOrderView.query()
        .select(QOrderView.DEFAULT)
        .where(f -> f.op(NAME_CONTAINS, QOrderView.CUSTOMER_NAME, "acme"))
        .build();
```

=== "Plain JPA"

    ```java
    List<OrderView> rows = executor.list(query, Limit.of(100));
    ```

=== "Spring repository"

    ```java
    List<OrderView> rows = orders.findAll(query, Limit.of(100));
    ```

Full text takes a required plain SQL identifier, optionally schema-qualified, as its configuration; null, blank or
invalid text is `MQ1315` at the factory call. Request text is bound, while the configuration is a literal so an
expression index can match. Punctuation is read as plain text, not a query language. Blank or stop-word-only text
matches nothing and PostgreSQL emits a notice; callers should skip the filter when that input means no search
(D-173). The field form `op(fullTextMatch(c), left, right)` works too, matching one column's text as the query of
another. For a field-form `ilike`, the right field holds the pattern as stored; no value preparation runs.

**Converted attributes (R-FLT-07, D-177).** Over a column whose mapping converts a value (a JPA `@Convert`
`AttributeConverter`, or auto-applied), `likeIgnoreCase` and `eqIgnoreCase` bind the parameter through the provider's
mapping-aware operand binder (`OperandBinder#converts`) and fold both sides in SQL: `lower(col) LIKE lower(?)` or
`lower(col) = lower(?)`. The provider runs the converter on the pattern or value (wildcards and escapes included),
and the database folds both sides with `lower()`. Previously, `eqIgnoreCase`, `EXACT` and `STARTS_WITH` silently
missed rows because the unconverted lowercase value was compared against converted stored text; `CONTAINS` and
`ENDS_WITH` succeeded only by accident of their leading `%` wildcard.
*Folding property limitation:* This rendering is correct when the converter satisfies the folding property:
`lower(conv(x)) = lower(conv(y))` if and only if `lower(x) = lower(y)`. This holds for converters adding prefixes or
suffixes, trimming, or changing case, but not for converters that encrypt (matching becomes case-sensitive) or
encode (such as base64, which can collide under `lower()` and return extra rows). The provider cannot classify
converter logic, so none is refused.
*Missing-binder limitation:* Without a mapping-aware binder (e.g. without `model-query-hibernate`, or on an
unsupported Hibernate version), the value is lower-cased in Java and compared unconverted, which can silently miss
rows on converted columns; inline value handling binds the `String` value by class, so it adds no error here.

Give `PortabilityCheck` a query with a present operator value to check each target's feature support. An empty
`Optional` removes the operator and is not reported (AC-FLT-24, AC-FLT-32).

**Indexes.** An operator is only as fast as an index matching its expression; index use is the planner's decision for
each plan, never an operator guarantee (RFC 0017 §6):

- Array `@>` and `&&` can use `gin (col)` under custom and generic plans.
- Full text can use `gin (to_tsvector('english', col))` under both plans when the index's configuration matches the
  operator's configuration.
- `ILIKE '%x%'` can use `gin (col gin_trgm_ops)` (`pg_trgm`), and jsonb `@>` can use `gin (col)`. In the PostgreSQL 16
  spike both used those indexes under a custom plan, but both used a sequential scan at 20,000 rows under
  `plan_cache_mode = force_generic_plan`. A reused server-side prepared statement can get a generic plan; whether
  `auto` chooses it after pgjdbc starts server preparation was not measured.
- Regex `~` can also use a trigram GIN index; the spike did not measure it.
- A `lower(col)` index serves `likeIgnoreCase`, not `ILIKE`.

Tested by `UserOperatorTest`, `CatalogueOperatorsTest`, `ContainmentOperatorsTest`, `FullTextOperatorTest`,
`ConvertedLikeTest`, `OperatorGateExecutionTest`, `OperatorFeaturesTest` and `OperatorPortabilityTest` in the TCK
(AC-FLT-21..27, AC-FLT-32).
