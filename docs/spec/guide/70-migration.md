# 70 — Migrating from Hand-written Column Definitions

**Covers:** adopting the library in a codebase that already defines `ColumnField`/`TableField` constants and
`QueryBuilder` subclasses by hand.
**Read when:** planning an adoption, or writing an OpenRewrite recipe for one.
**Owns:** `R-MIG-*`, `AC-MIG-*`.

---

## 1. Order of work

**R-MIG-01** **Rename packages first.** An OpenRewrite recipe (`com.rey.modelquery.rewrite.MigrateToModelQuery`)
changes imports to the library packages and maps the old names it can map mechanically: `BaseColumns` → `ColumnSet`,
`BaseQuery` → `QuerySpec`, subclass hooks → `QueryCustomizer`.

**R-MIG-02** **Keep hand-written constants.** They have the same types as generated ones, so hand-written and generated
models live in one codebase indefinitely. There is no big-bang step.

**R-MIG-03** **Convert models one at a time.** Annotate the model with `@QueryModel`, delete its hand-written constants,
and update references from `Model.X` to `QModel.X`.

**R-MIG-04** **Convert summary builders before plain projections.** A hand-written aggregate builder selects
`cb.sum(...)`/`cb.count(...)` and reads results back by **tuple index**:

```java
// before: selection order and buildResult indices must be kept in sync by hand
.setSelections(OPERATOR.getPath(m), SKU_CODE.getPath(m), …,
               cb.sum(ORIGINAL_UNIT_PRICE.getPath(m)), cb.count(root))
.setGroupBy(CATEGORY.getPath(m), CURRENCY.getPath(m), …)

public StockSummary buildResult(Tuple tuple) {
    var s = new StockSummary();
    s.setOperator(tuple.get(0, String.class));
    …
    s.setClosingStock(tuple.get(6, BigDecimal.class));      // shifts silently if a selection is inserted above
    s.setClosingQuantity(tuple.get(7, Long.class));
    s.setOrderStatus(buildOrderStatus(s.getClosingQuantity(), s.getReOrderLevel()));
    return s;
}
```

Inserting a selection shifts every index below it, and the result is wrong numbers rather than a failure — no test
catches it. After migrating, columns are constants, the group-by comes from the same `ColumnSet` as the selection
(`api/13` R-AGG-05), the mapper reads by `SelectField` (`api/10` R-COL-10), and the derived field moves to `afterMap`
(`api/11` R-QRY-05). These are usually also the queries whose output nobody diffs, so the payoff is largest here.

## 2. New failures to expect

**R-MIG-05** The engine's correctness rules turn silent data loss into exceptions. Run exports in a test environment
first. The ones that fire most often on adoption:

| Code | Rule | What it means |
|---|---|---|
| `MQ2201` | `engine/21` R-PAG-03 | The export's primary key was never selected; rows were previously deduped against `null`. |
| `MQ2202` | `engine/21` R-PAG-05 | A keyset column is nullable and had no explicit precedence; pages previously truncated at the first NULL. |
| `MQ1401` | `api/13` R-AGG-08 | A selected column was outside the group-by; MySQL had been returning an arbitrary value. |
| `MQ1402` | `api/13` R-AGG-10 | A grouped query asked for keyset paging, which cannot be correct. |
| `MQ1001` | `api/10` R-COL-08 | A hand-written column's declared type never matched the entity attribute. |

**R-MIG-06** `modelquery.keyset.null-keys=honour-null-precedence` plus explicit `nullsFirst`/`nullsLast` resolves most
`MQ2202` cases without a code change; it is a migration aid, and `integration/50` R-SPR-09 logs it.

## 3. What does not carry over

**R-MIG-07** Mutable per-query state on a builder has no equivalent and is not emulated. A hand-written builder that
mutates a cached `From` map keyed by `Root` identity becomes a `JoinContext` created per build (`api/10` R-COL-01,
INV-9); code that relied on the mutation order must be restructured.

**R-MIG-08** `withTable` creating a new `ColumnField` instance was significant in implementations that matched columns
by identity. Here, columns and joins are matched by key (`api/10` R-COL-02), so re-rooting the same column twice is
safe.

## 4. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-MIG-01 | The OpenRewrite recipe rewrites a sample hand-written model with no manual edits (R-MIG-01). |
| AC-MIG-02 | A codebase with one generated and one hand-written model compiles and runs both (R-MIG-02). |
| AC-MIG-03 | A migrated aggregate builder produces byte-identical output to the hand-written one on the same fixture (R-MIG-04). |
| AC-MIG-04 | Each code in R-MIG-05's table has a migration test that reproduces the old silent behaviour and the new exception. |
| AC-MIG-05 | Re-rooting one `ColumnField` under the same join twice yields one selection (R-MIG-08). |
