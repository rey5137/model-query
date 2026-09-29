# 92 — Decisions, Open Questions and Risks

**Covers:** why each non-obvious design choice was made (`D-n`), what is still open (`Q-n`), and the risks the plan
carries with their mitigations.
**Read when:** you are about to change a decision, or you want the reasoning a rule does not carry.
**Owns:** `D-n`, `Q-n`. Rules live in their owning files.

---

## 1. Decisions

**D-1 — Read path only, on JPA Criteria.**
The library stays on the Criteria API over existing entity mappings instead of generating SQL. It inherits type
mapping, converters, naming strategies and dialect handling for free, and a project adopting it keeps one source of
truth for its schema. The cost is that anything Criteria cannot express needs `QueryCustomizer`. Rejected: a jOOQ-style
SQL builder (duplicates the mapping) and JPQL strings (no compile-time typing). → INV-1, INV-2, P-5. Narrowed by D-14:
queries stay read-only, and filter-driven bulk writes are the one write path.

**D-2 — Results are `Tuple` wrapped as a `Row`, keyed by `SelectField`.**
Tuple indices are positional, so inserting a selection silently shifts every mapping below it, and no test that checks
one row catches it. Keying by the same object that produced the selection makes the mapping order-independent.
→ `api/10` R-COL-10, `guide/70` R-MIG-04.

**D-3 — One sealed `SelectField`, not a parallel aggregate API.**
An aggregate resolves to an `Expression`, a column to a `Path`, so `ColumnField` could not hold both. The alternatives
were a parallel `AggregateSet`/`AggregateRow` (doubling `ColumnSet`, `Row` and `orderBy`) or pushing aggregates through
`QueryCustomizer` (which cannot be read back, `api/11` R-QRY-08). Widening three signatures once, pre-1.0, was cheaper
than either. `Filters` deliberately kept `ColumnField`, which is what makes an aggregate in `where` a compile error.
→ `api/10` §2, `api/13`.

**D-4 — Nested models are `Optional<NestedModel>`, presence decided by the joined primary key.**
A LEFT-join miss and "a real row whose columns are all NULL" are indistinguishable from the column values alone, so the
engine selects the join's primary key and uses it as the presence signal. `Optional` makes "no data" unmissable in the
caller's code. The cost is that `Optional` fields are unusual on a DTO and need Jackson's `jdk8` module.
→ `processor/31` §4.

**D-5 — Negation includes NULLs.**
A report filter reading "status is not CANCELLED" means the user wants rows with no status too; plain SQL `<>` drops
them silently. `ne`/`notIn` therefore render `col <> ? OR col IS NULL`. `not(group)` stays plain SQL `NOT`, because
three-valued logic over an arbitrary group cannot be rewritten portably. → `api/12` R-FLT-04, R-FLT-05.

**D-6 — `Optional.empty()` skips, `null` throws, an empty collection means "none".**
Three distinct intentions that a nullable parameter conflates. Making the skip explicit costs one `Optional` at the call
site and removes the largest class of "why did my filter not apply" bug. → P-3, `api/12` R-FLT-01, R-FLT-02.

**D-7 — Grouped queries are offset-paged; keyset is refused.**
Keyset paging needs a unique per-row key as a tie-breaker, and a group has none. Emulating one (hashing the group keys,
say) would be correct only while the group-by is stable across pages. Refusing at build time is the honest option, and
the group keys themselves give grouped offset export a stable order. → INV-5, `api/13` R-AGG-10, `engine/21` R-PAG-11.

**D-8 — The API owns the `Stream`.**
An earlier in-house version returned an open `Stream` and leaked connections whenever a caller forgot a
try-with-resources. `stream(q, limit, body)` makes the leak unrepresentable. → `engine/20` R-EXE-07.

**D-9 — Explicit null precedence is portable, with a documented index cost.**
`nullsFirst()`/`nullsLast()` mean the same on every vendor. With `model-query-hibernate` they render natively or through
Hibernate's emulation; without it the engine prepends `CASE WHEN col IS NULL THEN 0 ELSE 1 END`, which is portable but
prevents an index from serving the sort. Documented rather than hidden. → `api/10` R-COL-12.

**D-10 — `MQ` + four digits, grouped by phase.**
A code tells a user which phase failed before they read the message, and gives support a searchable token. Grouping by
phase keeps the ranges stable as rules are added. → `reference/90`.

**D-11 — `afterMap` instead of a derived-column concept.**
Fields computed from other mapped values (an order status, a ratio) were inline in hand-written `buildResult` methods.
A `@Derived` annotation would need an expression language; a `BiConsumer<M, Row>` needs none and keeps the computation
in Java where it is testable. → `api/11` R-QRY-05.

**D-12 — No Lombok in the library, Lombok supported in consumers.**
The library cannot depend on another annotation processor's output ordering, but generated mappers call setters by name
and those calls resolve after Lombok has run, so consumers keep Lombok. The processor must follow Lombok's naming rules
exactly. → `processor/31` R-GEN-10, P-7.

**D-13 — Apache-2.0, no CLA.**
Matches the JPA ecosystem and imposes no paperwork on contributors. → `delivery/61` R-REL-12.

**D-14 — Filter-driven bulk writes, never entity writes.**
Report and back-office code updates or deletes "the rows this filter matches" as often as it reads them, and today
writes a JPQL `UPDATE` by hand with none of the filter semantics (D-5, D-6) or vendor limits the read path has. A bulk
`CriteriaUpdate`/`CriteriaDelete` reuses all of them and still loads no entity, so INV-1 is narrowed rather than
dropped: queries stay read-only, and the persistence context is flushed before and cleared after a write so it is
never left stale (`api/14` R-WRT-15). Change sets exist because "only the fields the client sent" is the common PATCH
case and cannot be expressed with one `set(...)` per field. Rejected: entity-level writes (they duplicate JPA) and an
"ignore nulls" copy (it makes clearing a field impossible). Accepted into the plan by rey5137/model-query#5 before the
RFC process existed; `Future` (M8) until built. → INV-1, `api/14`, `processor/31` §6.

**D-15 — Change sets validate set fields only, against the update model's constraints.**
Copying `@NotNull` onto a change set would reject every PATCH that omits the field, because an unset field is `null`
and Bean Validation checks every property. PATCH needs "if sent, must be valid", which
`Validator.validateValue(model, property, value)` expresses directly against the update model's own annotations. A
class-level `@ValidChanges` on the generated change set runs it for set fields only, so `@Valid @RequestBody` works
unchanged. It lives in `jpa` behind an optional dependency, since `annotations` and `core` may not import
`jakarta.validation` (INV-7). Rejected: copying annotations (wrong for unset fields) and validation groups (every
constraint would need one). → `api/14` R-WRT-21, R-WRT-22, `processor/31` R-GEN-23. Resolves Q-6.

**D-16 — Per-chunk commits go through a callback, not a Spring-only option.**
The library cannot start or suspend a transaction portably: under JTA or container-managed transactions it has no
handle on one. A `ChunkTransactions` callback on `ModelQueryConfig` leaves that to whoever owns transactions. It is given
the write's `EntityManagerFactory`, so one callback serves several datasources, and the starter supplies a default that
uses `REQUIRES_NEW` on the transaction manager bound to that factory, so the feature is reachable without Spring (INV-8) and costs Spring users
nothing. Because committed chunks survive a later failure, the failure reports how much was written (INV-5). Rejected:
a Spring-only option (breaks INV-8) and dropping it (every large delete would hand-roll the loop).
→ `api/14` R-WRT-19, R-WRT-20, `integration/50` R-SPR-11. Resolves Q-7.

**D-17 — A bulk write affects exactly the rows the equivalent read returns.**
A preview query and the write it previews must agree, or a user who checked the count first deletes more than they
saw (INV-5). Three things follow. A filter tree that needs a join renders whole inside one `EXISTS`, because splitting
it per predicate changes what `not` and `or` mean over a missing association. Key-first and chunked writes re-apply the
filter, or its root predicates where the vendor cannot read the target table, so a row that stopped matching is not
written. The write key is the entity's id from the JPA metamodel, because a non-unique key writes rows nobody
selected. Rejected: one `EXISTS` per joined predicate (simpler SQL, different rows) and trusting the selected keys
(wrong under concurrency and under MySQL's snapshot reads). → `api/14` R-WRT-08, R-WRT-10, R-WRT-11, R-WRT-17,
AC-WRT-07.

**D-18 — `JoinContext` is created with a public factory, and `TableField.resolve` is public.**
The executor in `jpa`, `QueryCustomizer` and custom predicates (`Filters.add`) all sit outside `core` yet need a
per-query `JoinContext` and the `From` a `TableField` resolves to. `JoinContext.of(root, cb)` makes a context over the
query's root; `resolve` returns the shared join for the field's join key. Both are `@Incubating` until 1.0. Rejected:
an internal hook exposed only to `jpa` (more machinery, and users would still hand-build joins, bypassing R-COL-02).
→ `api/10` R-COL-01, R-COL-02.

**D-19 — A null argument is a `NullPointerException`, not an `MQnnnn`.**
Passing null where the API does not accept it is a programming error, not an invalid query definition, so it fails
fast through `Objects.requireNonNull` with the parameter's name. `MQ` codes stay for definitions that are well-formed
Java but wrong for the model. Rejected: a catch-all "null argument" code (it would be raised from every method and tell
the reader nothing the NPE doesn't). → `code-conventions` CC-ERR-01.

**D-20 — A hand-written column's type must match its attribute exactly.**
R-COL-08 compares `ColumnField.type` with `path.getJavaType()` after boxing primitives, and a `@Convert` attribute
with its converted type. A supertype is rejected: a `Number` column over an `Integer` attribute throws `MQ1001`. The
converter allow-list is empty until a real case needs an entry. Loosening the check later breaks no one; tightening it
would. Rejected: accepting any assignable supertype (hides a wrong column until a value fails to map).
→ `api/10` R-COL-08, AC-COL-04.

## 2. Open questions

**Q-1 — Project name and coordinates.** `model-query` under `io.github.rey5137` is claimed and in use. Is a shorter
artifact prefix wanted before 1.0, while renaming is still cheap? → `delivery/61` R-REL-09.

**Q-2 — MySQL streaming default.** `row-by-row` is faster but blocks other statements on the connection until the
result is read; `useCursorFetch` does not. The current default is `row-by-row` with the caveat documented. Should the
default flip? → `vendor/41` R-PRF-04, R-PRF-07.

**Q-3 — Minimum Hibernate version.** Is 6.6 right, or should the library target Hibernate 7 only, given Spring Boot 4?
Targeting 7 only drops the `model-query-hibernate` compatibility matrix but excludes Boot 3.x users. → `delivery/61`.

**Q-4 — Cursor serialisation.** `0.1-reserved` mentions a `Cursor` format for passing a keyset position to a client.
Should 0.1 ship an opaque encoded form (so a REST API can page without exposing column values), or leave it to callers?
→ SPEC.md §4.

**Q-5 — `Agg.of` scope.** It is the escape hatch for any expression. Does it need a matching `Col.of(expression)` for
non-aggregate computed columns, or does that invite the SQL-builder scope creep P-5 rules out?

**Q-6 — Bean Validation on change sets.** Resolved by D-15.

**Q-7 — Per-chunk commits without Spring.** Resolved by D-16.

## 3. Risks

| Risk | Mitigation |
|---|---|
| Criteria API differences between Hibernate 6 and 7 | CI matrix on both. Version-specific code only in `model-query-hibernate`, behind small adapters. |
| Lombok naming rules drift | compile-testing cases per rule. A mismatch breaks compilation of the generated code loudly, not silently. |
| PostgreSQL streaming misuse (no transaction) | `checkStreamingPreconditions` fails fast; the Spring module opens a transaction automatically. |
| MySQL row-by-row streaming holds the connection for a whole export | Documented; keyset `export` is the recommended default for large exports. |
| `Optional` fields on models are unusual (not `Serializable`, need Jackson `jdk8`) | Documented. Only `@Join` fields use `Optional`; plain columns stay plain types. |
| `CASE WHEN … IS NULL` null-precedence fallback defeats index use | Used only without `model-query-hibernate`, and only when the requested precedence differs from the vendor default. |
| API churn before 1.0 | `@Incubating`, `0.x` versions, an explicit API review at M7, `japicmp` from 1.0. |
| Scope creep toward a general SQL builder | P-5 and `delivery/62` R-RDM-03. `QueryCustomizer`, `Agg.of`, `Filters.add` and, for bulk updates, `setExpression` are the only escape hatches, and each is documented as one. |
| Bulk writes surprise users who expect entity semantics (listeners, cascades, Envers and Bean Validation don't run) | Stated in the Javadoc of every write method and in the user guide. Flush and clear by default (`api/14` R-WRT-15), version increment by default (R-WRT-16). Clearing also detaches unrelated managed entities, whose later changes are then silently not written; stated in the same Javadoc, and `KEEP` is the alternative. The TCK pins down what the provider does for join tables and element collections. |
| A change set bound from a request lets clients write fields they shouldn't (mass assignment) | An update model lists exactly the writable fields, so the user guide recommends one per endpoint. `generateChanges` on a query model is documented as for internal use (`processor/30` R-PROC-19). |
| A timestamp `@Version` has the database's precision (one second on MySQL `DATETIME`), so two bulk updates in the same second leave the version unchanged and `expectVersion` misses the second | Documented; the user guide recommends a numeric `@Version` for rows that bulk updates touch. A TCK case pins the behaviour per vendor. |
| The spec drifting from the code | Every rule has an `AC-*` with a test named after it; the AC audit fails CI on an uncovered criterion (`delivery/60` R-QA-11). |
