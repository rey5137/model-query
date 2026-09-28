# Model Query — Code conventions

**Covers:** how Java code in the modules is written, where the spec does not fix it. Complements `CONTRIBUTING.md`
§Writing code (not repeated here).
**Read when:** writing or reviewing code. Rules carry `CC-*` ids so reviews can cite them.

---

## 1. Constants: one definition, referenced by name

**CC-CONST-01** A value that carries meaning — an `MQnnnn` code, a property key, a vendor limit, a default page size,
an annotation attribute name — is defined **once** and referenced by name everywhere else, tests included. A retyped
`"MQ2201"` compiles and fails silently.

**CC-CONST-02** "Once" means one owner, not one global constants class. A constant lives in the module that owns the
concept. A module needing another's constant depends on it as INV-7 already allows; it never copies it.

**CC-CONST-03** A closed set is an `enum` with its wire form on the enum (`code()`, `propertyName()`), not string
literals at call sites.

| Kind | Form | Home |
|---|---|---|
| Diagnostic codes (`reference/90`) | `enum MqCode` with `code()` and the default message | `core` (processor codes: `processor`) |
| Vendor limits and behaviour | methods on `VendorProfile` | `jpa` profiles |
| Property keys (`integration/50` §3) | `public static final` beside `ModelQueryProperties` | `spring-boot-starter` |
| Config defaults | `ModelQueryConfig.Defaults` | `core` |
| `LikeMode`, `Op`, `CountMode`, `NullPrecedence`, `Phase`, `DatabaseVendor` | `enum` | `core` |

## 2. Exceptions and messages

**CC-ERR-01** Library code throws only the exception types in `reference/90` §1, each carrying an `MqCode`. A bare
`IllegalArgumentException`, `IllegalStateException` or `NullPointerException` escaping public API is a bug.

**CC-ERR-02** A message names the model, the column or property, and both sides of a mismatch, in that order. It never
requires the spec to be understood: `StockSummary.closingStock: selected but not in groupBy` is complete on its own.

**CC-ERR-03** Validate at the earliest point that can see the problem — build time over execution time, compile time
over build time (P-2). A check moved earlier is never a behaviour change to document; a check moved later is.

**CC-ERR-04** Never swallow a vendor exception to fall back to a slower correct path without logging it once at `WARN`
with the code. Silent degradation is how a correct-looking wrong total gets shipped.

## 3. Immutability and thread safety

**CC-IMM-01** Every public type reachable from a `static final` field is immutable: `TableField`, `ColumnField`,
`AggregateField`, `ColumnSet`, `OrderField`, `ModelQuery`, `PrimaryKey`, `VendorProfile` (INV-9). No lazily-populated
cache field on any of them.

**CC-IMM-02** Per-query mutable state lives in `JoinContext` and nowhere else. A method that needs scratch space takes
the context; it does not keep a field.

**CC-IMM-03** Collections crossing a public boundary are unmodifiable copies. `ColumnSet.columns()` returns a view that
throws on mutation.

**CC-IMM-04** Identity is by key, never by object identity or `==` on a `Class`. Joins use the join key
(`api/10` R-COL-02); aggregates use function + source + alias (`api/13` R-AGG-01).

## 4. Module APIs

**CC-API-01** Default to package-private. A new `public` type or method in `core` or `jpa` is an API decision: mention
it in the gate report, and annotate it `@Incubating` if it may still change (`delivery/61` R-REL-07).

**CC-API-02** Dependency direction is INV-7 and is enforced by ArchUnit. A new dependency edge or third-party library
needs a reason in the PR: purpose, maintenance, license.

**CC-API-03** Hibernate types appear only in `model-query-hibernate`. `jpa` finds its features through `ServiceLoader`
and always has a portable fallback.

**CC-API-04** Generics carry meaning: `M` model, `E` root entity, `P` primary key, `T` the table a column sits on, `C`
the column's Java type. A new type parameter uses these letters or explains itself in Javadoc.

**CC-API-05** Javadoc on every public type and method: one line on what it is, plus the spec id it implements
(`@implSpec R-PAG-04`). No essays; the spec is the essay.

## 5. Style

**CC-STY-01** Java 17 language level. `.editorconfig` governs formatting; no reformatting commits mixed with logic.

**CC-STY-02** Names follow `reference/90` §6: *column* not *field*, *model* not *dto*, *selectable* not *expression*,
*group* not *bucket*, *profile* not *dialect*.

**CC-STY-03** `var` where the right-hand side names the type; explicit types in public signatures always.

**CC-STY-04** Comments explain *why*, at the line they guard, once. A comment restating the code is deleted.

## 6. Tests

**CC-TEST-01** Test method names start with the criterion id in snake case:
`ac_pag_07_null_keyset_requires_explicit_precedence`. A test covering several criteria names the main one and lists the
rest in a one-line comment.

**CC-TEST-02** Every rejecting rule asserts the exact `MqCode`, not merely that an exception was thrown
(`delivery/60` R-QA-06).

**CC-TEST-03** Paging and export tests page through the whole fixture and assert the multiset of visited keys. A
single-page assertion cannot detect the bugs `R-PAG-*` exist for (R-QA-07).

**CC-TEST-04** No mocks of JPA or JDBC internals. Unit tests use a real `CriteriaBuilder` over an H2 metamodel; TCK
tests use Testcontainers.

**CC-TEST-05** SQL snapshots are reviewed as diffs, never regenerated blindly. The PR says which changed and why.
