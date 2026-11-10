# RFC 0023 — Key groups: per-group bound values for child loads and `byKeys`

- **Status:** accepted as D-204 to D-208 (2026-11-10), after the acceptance spike (`rfc/0023-spike-results.md`);
  M35 in 0.12.0, `mvp-plan` §35
- **Affects:**
  - **`api/15`:** new R-FCH-18, `KeyGroups` and `ChildQuery#keyGroups`; R-FCH-05's rounds are formed per group;
    R-FCH-12's `DEBUG` line adds the groups, the re-read keys and whether the call ran grouped; R-FCH-04's "a child model that reads a param" refusal (via `api/10` R-COL-40) is narrowed to params no key group
    binds. R-FCH-16 (enricher lookups) is unchanged. New AC-FCH-19 to AC-FCH-25.
  - **`engine/20`:** new R-EXE-18, `ModelQuery.Builder#keyGroups` and `byKeys` / `one(q, key)` over a grouped
    definition; R-EXE-13's chunking is per group, after its dedupe on the converted value. R-EXE-14's locked keyed
    reads keep their groups. New AC-EXE-25 to AC-EXE-29.
  - **`api/10`:** R-COL-40's "Checked at `build()`" and "Engine-built queries" bullets name params bound by key
    groups, and say that a `withLock`, `withFetch` or `orderedBy` copy keeps the key groups.
  - **`api/16`:** R-INS-12's limits list adds the key-group refusals that follow the parent statement, the grouping
    rule's exception included.
  - **`integration/50`:** `findAllByKeys` and `findByKey` pass a grouped definition through unchanged, locked ones
    (R-SPR-16) included. No new method.
  - **`reference/90`:** new `MQ1708` (a key-group definition that doesn't match its query or child, or whose
    catch-all values are inconsistent), `MQ2215` (a grouped
    definition read by anything but `byKeys` or `one(q, key)`), `MQ2607` (a group function returning `null`),
    `MQ2608` (a logged `WARNING`: a key's row was found only by the `verifyMissing` re-read). A note says `MQ1708`
    also covers `ModelQuery.Builder` and `MQ2607` / `MQ2608` also cover `byKeys`, though they sit in the fetch-plan
    and child-load families.
  - **`reference/92`:** D-204 to D-208; D-114's "partitioning by a key part" recipe points to D-205; D-138's list of
    refusals that follow the first statement adds `MQ2607`, a value's `MQ1502`, a group function's exception and a
    grouping rule's exception.
  - **`docs/site`:** the fetch-plans page (child loads over partitioned tables) and the `byKeys` section.
- **Discussion:** TBD

## Summary

A child load and `byKeys` send the keys they look up in rounds split only by the vendor's bind limits (R-FCH-05,
R-EXE-13). This RFC lets a caller declare **key groups**: a pure function from a key to a group value, and the
`QueryParam`s (RFC 0022 Part C, R-COL-40) bound from that group value. The engine then forms the rounds per group,
splits each group by the bind limits as before, and binds the group's values into the same filter in each round. The
filter is part of the static definition, so it is checked when the plan or query is built. The statement text is the
same for every group, up to the length of the `IN` list (more, smaller rounds can mean more distinct `IN` lengths).

Grouping changes how keys are split into rounds, never which keys are requested. Because the bound filter relies on a
link between a key and a column that the database doesn't enforce, every `KeyGroups` declaration ends by stating how
that link is treated: `verifyMissing(all)` re-reads keys that found no row, `assumeInRange()` accepts the risk in
code (D-206). Grouping costs one statement per group, which loses on small, spread-out loads, so the caller can also
declare a `GroupingRule` that runs a call as one ungrouped statement set instead (D-208).

The case that prompted it is a child table range-partitioned by month whose ids (Snowflake, ULID, UUIDv7) encode the
creation time. With the creation month bound per round, the database prunes to one partition instead of probing the
index of every partition.

## Motivation

```java
// OrderLine is partitioned by month on created_at; its id encodes the creation instant.
FetchPlan<OrderView> plan = FetchPlan.of(QOrderView.class)
        .child(QOrderView.LINES_BY_ID, FetchPlan.of(QOrderLineView.class));   // to-one per line id, or byKeys
```

Today every round renders `where id in (?, …, ?)`. PostgreSQL requires the partition key in every unique constraint,
so the primary key is `(id, created_at)`. An `id`-only lookup probes the local index of every partition, which on
three years of monthly partitions is 36 index probes per key batch. Adding `created_at >= ? and created_at < ?` for
the batch's month restricts it to one partition. The caller can't add it themselves:

- `ChildQuery#where` is one static filter for every round, and a round's keys span months;
- a `QueryCustomizer` never sees which keys a round holds;
- a child model that reads a `QueryParam` is `MQ1512` at the plan build (R-COL-40), since nothing binds it;
- D-114 leaves partitioning by a key part to the caller's lookup, which only `Enricher.byKeys` can host.

The workaround today is to give up `@Child` and use `Enricher.byKeys` with a lookup that groups the ids by month
and calls `executor.byKeys` once per month. That loses R-FCH-04's dedupe and `MQ2601`, R-FCH-11's `maxPerParent`
bound, and the check of child refusals before the parent statement (D-138).

The same shape, a predicate derived from the key that makes the lookup cheaper or routable, recurs in other cases:

| Case | Group value | Bound filter |
|---|---|---|
| Range or time partitioning (PostgreSQL, MySQL, TimescaleDB chunks) | creation month from the id | `created_at >= ? and created_at < ?` |
| Distributed SQL with a distribution column (Citus, Vitess, YugabyteDB, CockroachDB hash-sharded) | shard or tenant from the id | `tenant_id = ?` routes to one shard |
| Multi-tenant table indexed on `(tenant_id, id)`, row-level security on the tenant | tenant from the id | `tenant_id = ?` |
| Polymorphic rows keyed by `(target_type, target_id)`, through `byKeys` only (a composite `@Child` key is `MQ3404`) | `target_type` of the key | `target_type = ?` uses the composite index |
| List partitioning by region, data residency | region prefix of the id | `region = ?` |

## Design

### 1. `KeyGroups`

```java
@Incubating
public final class KeyGroups<K, G> {                      // immutable; may be static final (INV-9)
    public static <K, G> Builder<K, G> by(Class<K> keyType, Function<? super K, ? extends G> group);

    @Incubating
    public static final class Builder<K, G> {             // immutable, each call returns a copy
        public <T> Builder<K, G> bind(QueryParam<T> param, Function<? super G, ? extends T> value);
        public Builder<K, G> groupWhen(GroupingRule rule, G all);  // rule false: one ungrouped run with all's values
        public KeyGroups<K, G> verifyMissing(G all);      // re-read keys that found no row, with all's values
        public KeyGroups<K, G> assumeInRange();           // no re-read: a row outside its key's group may not be read
    }

    @Incubating
    @FunctionalInterface
    public interface GroupingRule {                       // must be pure
        boolean group(int keys, int groups);              // a call's distinct keys and groups, after dedupe
        static GroupingRule minKeysPerGroup(int n);       // keys >= n * groups (long arithmetic); n < 1: IAE
        static GroupingRule maxGroups(int n);             // groups <= n; n < 1: IAE
    }
}
```

`ChildQuery#keyGroups` and `ModelQuery.Builder#keyGroups` take only a finished `KeyGroups`, so the compiler makes
the caller choose between the two endings (D-206).

```java
// The bound values are the attribute's type: Instant for a timestamptz / DATETIME column, never a LocalDateTime
// for a UTC instant, which shifts against timestamptz.
static final QueryParam<Instant> FROM = QueryParam.of("createdFrom", Instant.class);
static final QueryParam<Instant> TO = QueryParam.of("createdTo", Instant.class);

record CreatedRange(Instant from, Instant to) {
    static CreatedRange monthOf(long id) {
        YearMonth m = YearMonth.from(SnowflakeId.instant(id).atZone(ZoneOffset.UTC));
        return new CreatedRange(m.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                m.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}

// Limits every Tier-1 vendor binds for this column type (MySQL DATETIME: 1000-01-01 to 9999-12-31).
static final CreatedRange ALL = new CreatedRange(Instant.parse("1000-01-01T00:00:00Z"),
        Instant.parse("9999-12-31T00:00:00Z"));

static final KeyGroups<Long, CreatedRange> BY_MONTH = KeyGroups
        .by(Long.class, CreatedRange::monthOf)
        .bind(FROM, CreatedRange::from)
        .bind(TO, CreatedRange::to)
        .groupWhen(GroupingRule.minKeysPerGroup(300), ALL)   // the spike's break-even through Hibernate; measure yours
        .verifyMissing(ALL);
```

- `group` and each `value` must be pure functions. Per child or per `byKeys` call, the engine calls `group` once per
  distinct key and `value` once per distinct group, **all before the first round's statement**, so a failure in the
  30th group doesn't follow 29 statements. Their exceptions propagate unwrapped.
- `group` returning `null` is `MQ2607` naming the key. A key that can't be decoded (a legacy id, say) maps to the
  catch-all group. The engine never drops the filter, because the statement text has to stay one shape.
- A `value` returning `null`, or a value that isn't `param.type().isInstance(value)`, is `MQ1502`, as `bind` is
  (R-COL-40). A primitive `long` key matches `Long.class`, as it does for `QueryParam`.
- Range limits must lie within what the column type binds on every vendor: `Instant.MIN` / `MAX` and
  `LocalDateTime.MIN` / `MAX` fail to bind on PostgreSQL and MySQL, and a MySQL `TIMESTAMP` column ends at
  2038-01-19 03:14:07 UTC. The user guide lists the limits per type.
- `by` with no `bind` is allowed. It splits the rounds by group and binds nothing. That's useful on its own when a
  caller wants each round to hold keys of one group (the polymorphic case, for plan stability). It ends in
  `assumeInRange()`. `verifyMissing` with no `bind` is `MQ1708`, since nothing can exclude a row.
- A second `bind` of one param replaces the first, as `ModelQuery.Builder#bind` does. A second `groupWhen` replaces
  the first.
- The terminal call (`verifyMissing` or `assumeInRange`) evaluates every `value(all)` once and keeps the results, so
  a `null` or mistyped catch-all value is `MQ1502` there, and a value function's exception propagates from it, before
  any plan or query uses the groups. That `all` is bindable on the vendor (the range limits below) is still the
  caller's to get right; `bind` functions apply to `all` too, so a margin that shifts a limit belongs in the group
  function (§2's example), not in `bind`.
- `verifyMissing(all)` after `groupWhen(rule, all2)` with `!all.equals(all2)` is `MQ1708`: one definition has one
  catch-all, so the ungrouped run and the re-read can't disagree.

**`verifyMissing(all)`.** After a child's or a `byKeys` call's rounds, the keys that found no row are read again,
in R-FCH-05 / R-EXE-13's chunks, with `value(all)` bound for every param. The statement text is unchanged. Rows found are
returned as if the first round had found them, and `MQ2608` is logged as a `WARNING` once per definition, as
`MQ2214` is, naming the definition and a sample key (no bound values, D-95). When every key finds a row it costs
nothing. Otherwise it costs one unpruned statement per chunk of the run's missing keys across all groups.
That is today's per-partition index probe, and only for the missing keys. A missing key can be read only on a lookup
where each key has at most one row (D-206).

**Margins.** With `verifyMissing`, bind the exact range: stragglers are found by the re-read. With
`assumeInRange()`, a margin hides edge skew at a cost. A one-day margin on each side of a month overlaps three
partitions, not one, so each round probes three local indexes instead of one (still well under 36).

**`groupWhen(rule, all)` (D-208).** Grouping costs one statement per group, so it loses when groups are small: in the
spike a 50-row page whose keys spread over 36 months ran 19–25× slower through Hibernate, and grouping broke even at
about 300 keys per group. Once per call (a child load's run over one parent page or export batch, a `byKeys` call),
after dedupe and grouping and after every group's `value` has run (so a bad value fails on either branch), the engine
calls `rule.group(keys, groups)`, but only when the grouped rounds would outnumber R-FCH-05 / R-EXE-13's ungrouped
chunks for the same keys. Otherwise grouping costs no extra statement and the call runs grouped without asking: one
group (so never on `one(q, key)`), or keys enough that the ungrouped run needs as many chunks. With no keys there is no
call.

- `true`: the call runs per group as below.
- `false`: the call runs as if every key were in the group `all`, so its rounds are R-FCH-05 / R-EXE-13's chunks with
  `value(all)` bound for every param. The statement text is the grouped one, so no new SQL shape exists. There is no
  `verifyMissing` re-read, since `all` already read every key.
- With no `groupWhen`, every call runs grouped.
- The rule's exception propagates unwrapped, at the same point as a group function's (D-138's list).
- `all` must exclude no row: the engine can't check that. It is the same contract as `verifyMissing(all)`, which must
  pass an equal value.
- With `groupWhen`, every group-bound param must be read only as one side of a `compare` against a column with `GT`,
  `GTE`, `LT` or `LTE`. Any other read (`EQ`, `NE`, inside an expression or a `@Computed` column) is `MQ1708` at the
  plan build or `build()`, naming the param: a single `all` value can't stand for every group of an `EQ` param (the
  tenant case), so the `false` branch would silently drop the keys of every other group.
- Under `assumeInRange()`, a row outside its key's group is read on the `false` branch and not on the `true` one, so
  results agree across branches only for rows inside their group. That is the risk `assumeInRange()` states; the
  user guide says so.
- `GroupingRule` is compared by identity in the definition key (D-201), as the group and value functions are, so a
  `static final` rule is checked once (INV-9).
- The library sets no default threshold: the break-even varies about tenfold between Hibernate and plain JDBC and
  between vendors (`rfc/0023-spike-results.md`). The user guide quotes the spike's numbers and says to measure.
- R-FCH-12's `DEBUG` line says whether the call ran grouped.

### 2. Child loads (R-FCH-18)

```java
static final KeyGroups<Long, Instant> BY_ORDER_MONTH = KeyGroups
        .by(Long.class, orderId -> CreatedRange.monthOf(orderId).from().minus(1, ChronoUnit.DAYS)) // skew margin
        .bind(FROM, from -> from)                                      // all is bound as given, so no margin here
        .groupWhen(GroupingRule.minKeysPerGroup(300), Instant.parse("1000-01-01T00:00:00Z"))
        .assumeInRange();                                              // to-many: verifyMissing is MQ1708

FetchPlan.of(QOrderView.class)
        .child(QOrderView.LINES, FetchPlan.of(QOrderLineView.class), q -> q
                .where(f -> f.compare(QOrderLineView.CREATED_AT, Op.GTE, Expr.param(FROM)))   // lower bound only
                .keyGroups(BY_ORDER_MONTH));
```

A to-many child keyed by the parent's id groups on the parent key. Lines are created no earlier than their order, so
the order's month bounds them from below only: a line added two months later lives in a later partition, and an
upper bound would drop it from the list. The pruning comes from excluding every earlier partition only, so it pays
off only where children follow their parent closely and the parents read are recent: in the spike, a group in the
middle of 36 months still scanned 19 partitions. The two-sided range of §1 on a to-one or `byKeys` lookup is the
main case; this form is the exception.

`ChildQuery#keyGroups(KeyGroups<?, ?>)` is checked when the plan is built, with every other child refusal (R-FCH-04,
D-138), so it fails before the parent's statement:

- `keyType` must equal the attribute type of the child's `foreignKey`, or of the parent's `key` for `through`
  (R-FCH-14). Otherwise it is `MQ1708`, naming both types.
- Every param the child definition reads (its `ChildQuery` filters, its own model filters, `@Computed` columns) must be
  bound by the key groups. One that isn't stays `MQ1512`. One the key groups bind but nothing reads is `MQ1708`,
  since a groups-bound param exists only to filter and is almost certainly a filter left off. This deliberately
  differs from R-COL-40, where a bound param nothing reads is allowed.
- On a child whose key isn't unique per row (a `foreignKey` on the child, so to-many), a short list can't be detected,
  so:
  - `verifyMissing` is `MQ1708`: a missing key is normal there, and a short list never shows as one;
  - a group-bound param may be read only as one side of a `compare` against a column, as that column's lower bound
    (`GT` / `GTE` with the param on the right, `LT` / `LTE` with it on the left) or with `EQ`. Any other read (an
    upper bound, `NE`, inside an expression or a `@Computed` column) is `MQ1708`, naming the param. With `groupWhen`,
    `EQ` is `MQ1708` too (§1).
- A `through` child (the parent holds the child's own key, at most one row per key) takes either ending.

Rounds (R-FCH-05 amended): a run's distinct non-null keys are grouped by `group(key)`, with groups in the first-seen
order of their keys and keys in first-seen order within a group. Each group is split into R-FCH-05's chunks (8192
keys at most on every Tier-1 vendor in the spike, below any bind limit), which also count the round's own binds (the
group's params count once each). Each
round renders `foreignKey IN (…) AND <ChildQuery filters>` with the group's values bound. The group function sees the
attribute value before any converter, which is what R-FCH-05 matches on. Each distinct key is in exactly one round.
There is no cap on the number of groups: refusing many groups would turn a cost into an outage. R-FCH-12's `DEBUG`
line adds the number of groups and of re-read keys.

Unchanged: dedupe per parent key, `MQ2601`, `MQ2604`, `maxPerParent` (R-FCH-11 bounds each round, and a key is in
exactly one round), the child plan running once over all the page's child rows, child order per parent (each key's
children come from one round), and export batches (R-FCH-09).

### 3. `byKeys` and `one(q, key)` (R-EXE-18)

```java
static final ModelQuery.Builder<OrderLine, Long, OrderLineView> LINES = QOrderLineView.query()
        .where(f -> f.compare(QOrderLineView.CREATED_AT, Op.GTE, Expr.param(FROM))
                     .compare(QOrderLineView.CREATED_AT, Op.LT, Expr.param(TO)))
        .keyGroups(BY_MONTH);

Map<Long, OrderLineView> lines = executor.byKeys(LINES.build(), ids);      // findAllByKeys on a repository
Optional<OrderLineView> line = executor.one(LINES.build(), id);            // one group, one statement
```

`ModelQuery.Builder#keyGroups(KeyGroups<K, ?>)` makes the params it binds count as bound at `build()` (R-COL-40):

- `keyType` must be the query's key type `K`, otherwise `MQ1708`.
- A param both bound by `bind` and by the key groups is `MQ1708` at `build()`, so there's no question of which one
  wins. A param the key groups bind but nothing reads is `MQ1708`, as for a child.
- `byKeys` first dedupes on the converted value (R-EXE-13, `whereKey`'s conversion, R-WRT-08), then groups each
  distinct value by the **first** caller key that produced it. Two caller keys that convert to one value but would
  group differently are read once, in one round, so `MQ2213` can't fire on them.
- A grouped definition can be read only by `byKeys` and `one(q, key)`, locked or not (R-EXE-14, R-SPR-16). Any other
  read (`list`, `page`, `count`, `exists`, `one(q)`, `first(q)`, keyset, export, stream) is `MQ2215` before any
  statement, naming the params the groups bind.
- A `withLock`, `withFetch` or `orderedBy` copy keeps the key groups (R-COL-40's "Engine-built queries" bullet).
- There is no per-call override: the rule sees the call's key and group counts, which is what a caller knowing "this
  batch is small" knows. A `ModelQuery#ungrouped()` copy can be added later without breaking anything.
- `byKeys` forms chunks per group as above, inside R-EXE-13's limits, then runs `verifyMissing`'s re-read if
  declared. `MQ2213`, `MQ2003` and `MQ2005` apply per row as before, the map stays in the keys' first-occurrence
  order, and the ignored-`orderBy` warning (D-201) is unchanged.
- The definition key (D-201) covers the key groups, so a `static final` builder is checked once.

### 4. What the engine does not do

- **No routing.** Every round runs on the same `EntityManager`. Sending a group to another datasource or table stays
  the lookup's concern (R-FCH-16, `Enricher.byKeys`).
- **No pruning guarantee.** The library binds values. Whether the database prunes is the vendor's behaviour, which the
  spike records but which is not a TCK assertion.
- **No automatic discovery.** The library can't know how a key maps to a partition. No `VendorProfile` method reads
  partition metadata.
- **No parallel rounds.** Rounds run one after another on the calling thread, as today.
- **No enricher grouping.** `Enricher.byKeys` lookups (R-FCH-16) are not grouped; the lookup already sees its keys.

## Vendor and provider SPI

None. Rendering goes through R-COL-40's existing param node on both Hibernate lines. `core` gains `KeyGroups`, which
needs only `QueryParam` and the JDK (INV-7).

## Spike plan

Run on PostgreSQL 16, MySQL 8.0 and H2, Hibernate 6.6 and 7.4; results in `rfc/0023-spike-results.md`, data and
re-run steps in `rfc/0023-spike-data/`. Rendering: as designed, with a group over 8192 keys split within the group.
Pruning: the two-sided range scans 1 of 36 partitions on PostgreSQL (custom and generic plans) and MySQL (client
and server prepare; `TO_DAYS` partitioning always adds the first partition); the lower bound alone skips only
earlier partitions. Cost: grouping wins on large loads (0.26–0.33× the time at 10 000 keys in 1–3 groups) and loses
on small spread-out ones (a 50-row page over 36 months 19–25× slower through Hibernate, 3–10× on JDBC), hence
`groupWhen` (D-208). `verifyMissing` is free when every key is found. Not tested: time zones other than UTC.

On each Tier-1 vendor (H2, PostgreSQL, MySQL) and both Hibernate lines:

1. **Rendering and binds.** A child load and a `byKeys` over keys spanning three groups render one statement text per
   `IN` length, with rounds equal to the groups when no group is over the bind limit, and split by the limit when one
   is.
2. **Pruning.** A table range-partitioned by month (PostgreSQL declarative partitioning, MySQL `PARTITION BY RANGE`),
   with `plan_cache_mode=force_generic_plan` on PostgreSQL so run-time pruning is what's measured. Record
   `EXPLAIN (ANALYZE)` ("Subplans Removed") and MySQL's `EXPLAIN` `partitions` column, for both the two-sided range
   and the lower bound only. H2 asserts correctness only.
3. **Cost.** Statements and wall time, grouped against ungrouped, over 36 partitions for:
   - 10 000 keys;
   - a 50-row page whose keys spread over 36 months (up to 36 statements per child per page, and so per export page);
   - the `verifyMissing` re-read with 1 % and 10 % of keys outside their range.

   Record where grouping stops paying off in `rfc/0023-spike-results.md`, as guidance for the user guide.

## New ids

| Id | Where | What |
|---|---|---|
| R-FCH-18 | `api/15` | `KeyGroups`, `ChildQuery#keyGroups`, per-group rounds, the to-many restrictions |
| R-EXE-18 | `engine/20` | `ModelQuery.Builder#keyGroups`, `byKeys` and `one(q, key)` over a grouped definition |
| AC-FCH-19 | `api/15` | Per vendor and line, a child load over keys of three groups runs one round per group with each group's values bound, one SQL text per `IN` length, children per parent in child order |
| AC-FCH-20 | `api/15` | A group over the bind limit is split within the group; the group's binds count against the budget |
| AC-FCH-21 | `api/15` | At the plan build, before the parent statement, even on an empty page: `MQ1708` for a key-type mismatch, an unread group param, `verifyMissing` with no `bind`, and on a to-many child `verifyMissing` or a group param read as an upper bound; `MQ1512` for a param no group binds |
| AC-FCH-22 | `api/15` | `MQ2607` on a `null` group, `MQ1502` on a `null` or mistyped value, and a group function's unwrapped exception all occur after the parent statement and before the child's first round, with no child round run |
| AC-FCH-23 | `api/15` | A to-many child with a lower bound only loads a line created in the month after its order's month: the list is complete |
| AC-FCH-24 | `api/15` | A `through` child with `verifyMissing` loads a child whose range column lies outside its key's group through the re-read and logs `MQ2608` once per definition; when every key finds a row, no re-read runs (statement count) |
| AC-EXE-25 | `engine/20` | `byKeys` with `verifyMissing` returns the same map as ungrouped `byKeys`, in key order, including a row outside its key's range; with `assumeInRange()` it does so when every row lies in its key's range |
| AC-EXE-26 | `engine/20` | `one(q, key)` binds the key's group; locked `byKeys` and `one(q, key)` keep the groups through `withLock`, `withFetch` and `orderedBy` copies; `list`, `page`, `count`, `exists`, `one(q)`, `first(q)`, keyset, export and stream are `MQ2215` before any statement |
| AC-EXE-27 | `engine/20` | `MQ1708` at `build()` for a key-type mismatch, a param bound by both `bind` and the groups, and an unread group param |
| AC-EXE-28 | `engine/20` | Two caller keys converting to one value but grouping differently are read once, grouped by the first, with no `MQ2213` |
| AC-FCH-25 | `api/15` | With `groupWhen`: a child load whose rule returns `false` runs R-FCH-05's chunks with `all`'s values bound and no re-read, and one whose rule returns `true` runs per group (statement count; children equal for rows inside their group); under `assumeInRange()` a row outside its group is found on the `false` branch only; the rule isn't called for one group, for keys whose ungrouped chunks equal the grouped rounds, or for no keys; a `null` value of any group is `MQ1502` on either branch and the rule's exception propagates unwrapped, both before the child's first round; `MQ1708` at the plan build for a group param read with `EQ` |
| AC-EXE-29 | `engine/20` | The same branches and rule-call conditions for `byKeys` (same map on both branches when rows lie in their group); `MQ1708` at `build()` for an `EQ`-read group param; at the `KeyGroups` terminal call, `MQ1502` for a `null` or mistyped catch-all value and `MQ1708` for `verifyMissing(all)` unequal to `groupWhen`'s `all`; `minKeysPerGroup(0)` and `maxGroups(0)` throw `IllegalArgumentException` |
| `MQ1708` | `reference/90` | A key-group definition that doesn't match its query or child, a group param read other than as a range bound under `groupWhen`, or two unequal catch-alls (fetch-plan family; also `ModelQuery.Builder` and `KeyGroups.Builder`) |
| `MQ2215` | `reference/90` | A grouped definition read by anything but `byKeys` or `one(q, key)` |
| `MQ2607` | `reference/90` | A key-group function returned `null` (child-load family; also `byKeys`) |
| `MQ2608` | `reference/90` | Logged `WARNING`, never thrown: a key's row was found only by the `verifyMissing` re-read |

## Compatibility

Additive and `@Incubating` (R-REL-07). A query or plan that doesn't call `keyGroups` renders and binds exactly as
before, with no SQL snapshot change. D-114's rule stays for every caller that declares no groups.

## Alternatives

- **Do nothing.** The `Enricher.byKeys` workaround works, but it gives up the `@Child` guarantees listed in Motivation,
  and every user reimplements the grouping.
- **A filter built per group at run time** (`(G, Filters<C>) -> Filters<C>`), the first sketch. It is simpler to call,
  but the filter only exists once the parent's keys are known. A wrong column or an unbound param then fails after the
  parent's statement, which breaks D-138, and nothing forces the statement text to stay one shape. Binding
  `QueryParam`s into a static filter keeps both properties.
- **A per-round hook `(Set<K>) -> filter` without grouping.** A round's keys still span groups, so the hook can only
  widen the range to the round's min and max. That prunes little on random key orders.
- **The range as the caller's filter, no ending required** (the first draft of D-206). An ordinary `where` filters
  every key alike; here a row is kept or dropped by an assumed link the database never enforces, so an optimisation
  would change the result (INV-5's "approximate result" by another route).
- **`verifyMissing` always, with no opt-out.** It is free when every key finds a row, but it can't apply to a to-many
  child, and a caller who knows the link holds (a tenant column set from the id) would pay for every deleted key.
- **A typed `KeyedQuery` accepted only by keyed overloads**, so a grouped definition passed to `list` fails at compile
  time instead of as `MQ2215`. It doubles the executor and repository surface for one feature. See D-207.
- **Merging small groups into one covering range** instead of `groupWhen`. For months that aren't adjacent the
  covering range scans everything between them, so it is no better than the catch-all and harder to predict.
- **A built-in threshold** (always ungrouped below N keys per group). The spike's break-even moves about tenfold
  between Hibernate and JDBC and between vendors, so any default would be wrong for many users. See D-208.
- **Hibernate's `@PartitionKey`.** It covers updates and deletes of loaded entities, not `IN` lookups, and `jpa` can't
  import `org.hibernate` (INV-7).

## Resolutions (recorded as D-204 to D-208)

1. **D-204:** key groups bind `QueryParam`s into the static definition, rather than build a filter per group. This
   keeps D-138's "child refusals before the parent statement" for every static check, and one statement text per
   definition and `IN` length. D-138's list of refusals that follow the first statement adds `MQ2607`, a value's
   `MQ1502` and a group function's exception; the engine runs every group and value before the child's first round.
2. **D-205:** D-114's "partitioning by a key part" recipe is promoted to API, for child loads and `byKeys` /
   `one(q, key)` only. D-114 rejected `partitionBy` because it invites parallel calls; that reason doesn't apply here,
   since rounds run one after another on the calling thread. Grouping changes how keys are split, never which keys
   are requested, and each distinct key is in exactly one round. R-FCH-16 (enricher lookups) is unchanged. The
   library still never routes, never runs rounds in parallel, and never derives groups itself.
3. **D-206: rows outside their key's group.** A row whose range column disagrees with the value its key encodes
   (clock skew, backfills, imported rows, a time-zone edge at a month boundary) would be excluded by the bound filter
   and its key would look absent. Every `KeyGroups` must end in `verifyMissing(all)` (keys with no row are re-read
   with the catch-all group's values, rows found are returned, `MQ2608` logged once per definition) or
   `assumeInRange()` (the risk is stated in code: such a row may or may not be read, and with `groupWhen` which one
   depends on the branch a call takes). The compiler enforces the choice. `verifyMissing` is `MQ1708` on a
   to-many child, where a short list can't be detected; there, a group-bound param may be read only as a lower bound
   or with `EQ` (`MQ1708` otherwise), so a list is never cut by an upper bound.
4. **D-207:** a grouped `ModelQuery` is read only by `byKeys` and `one(q, key)`, locked or not (`MQ2215` otherwise).
   The other reads have no key to group on, and a builder without `keyGroups` serves them with explicit `bind`.
   Rejected: a typed `KeyedQuery` that would make the mistake a compile error, because it doubles the executor and
   repository surface.
5. **D-208: when to group is the caller's rule.** Grouping costs a statement per group, which the spike measured as
   a loss on small spread-out loads. `KeyGroups.Builder#groupWhen(GroupingRule, G all)` lets the caller decide per
   call from the distinct key and group counts; `false` runs the grouped statement text once over all keys with
   `all`'s values and no re-read. A definition has one `all`: `verifyMissing`'s must equal `groupWhen`'s (`MQ1708`),
   and its values are evaluated and checked at the `KeyGroups` terminal call. Under `groupWhen` a group param may be
   read only as a range bound (`MQ1708` otherwise), since one `all` can't stand for an `EQ` value. The rule is asked
   only when grouping would add statements, after every group's value has run, so refusals don't depend on the
   branch. No default threshold, and no per-call override (`ungrouped()` can come later). Rejected: merging small
   groups into a covering range, and a built-in threshold.

## Milestone sketch

Targets **0.12.0**: one milestone, M35, with the freeze moving to M36. The plan rows are in `mvp-plan` §35.

| Slice | Content | Tier |
|---|---|---|
| .1 | `KeyGroups` and its builder in `core`, `ChildQuery#keyGroups`, plan-build checks (`MQ1708` incl. the to-many rules, `MQ1512` narrowed), per-group rounds with groups and values run up front, `verifyMissing` re-read and `MQ2608`, `MQ2607`, `groupWhen` / `GroupingRule` (rule-call conditions, range-bound-only check, catch-all checks) | `opus` / `high` (child-load correctness, D-138, D-206) |
| .2 | `ModelQuery.Builder#keyGroups`, `byKeys` (dedupe then group) and `one(q, key)` per group, copies keep groups, `groupWhen` on `byKeys`, `MQ2215`, definition key | `opus` / `high` (public API, R-COL-40 check) |
| .3 | TCK partitioned fixtures (PostgreSQL, MySQL), Spring `findAllByKeys` / `findByKey` tests (locked included), docs pages with both tabs, range limits per type | `sonnet` / `medium` |
