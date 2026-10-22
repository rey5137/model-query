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
→ `api/10` R-COL-10.

**D-3 — One sealed `SelectField`, not a parallel aggregate API.**
An aggregate resolves to an `Expression`, a column to a `Path`, so `ColumnField` could not hold both. The alternatives
were a parallel `AggregateSet`/`AggregateRow` (doubling `SelectSet`, `Row` and `orderBy`) or pushing aggregates through
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
Returning an open `Stream` leaks a connection whenever a caller forgets a try-with-resources. `stream(q, limit, body)`
makes the leak unrepresentable. → `engine/20` R-EXE-07.

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
RFC process existed; `Future` (M6) until built. → INV-1, `api/14`, `processor/31` §6.
*Amended by D-118:* an update or delete `throughEntities()` is the second entity write after `persist` (D-116): it
loads and writes the root's entities chunk by chunk so listeners and audits run, and keeps the entity hidden.

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
converter allow-list is empty until a real case needs an entry; a model type other than the attribute's goes through a
`ColumnConverter` (D-37), and `core` ships ordered ones for a `Timestamp` attribute (D-84). Loosening the check later
breaks no one; tightening it would. Rejected: accepting any assignable supertype (hides a wrong column until a value
fails to map). D-122 loosens it for one case: a `java.util.Date` attribute type reads the `java.sql` subtype the
provider reports. → `api/10` R-COL-08, AC-COL-04.

**D-21 — The phase-consistency warning runs on first execution, not in `build()`.**
Checking that a `QueryCustomizer` narrows every phase alike means running it against a real `CriteriaBuilder`, which
`build()` does not have (R-QRY-01: nothing is resolved until execution). The executor runs the check once per
`ModelQuery`, on its first execution, and logs one warning naming the phases that differ. Rejected: passing a
`CriteriaBuilder` to `build()` (ties a definition to a persistence unit) and checking on every execution (log noise).
→ `api/11` R-QRY-09, AC-QRY-06.

**D-22 — Ignore-case filters lower-case the value in Java and bind it.**
JPA 3.1 has no bound value expression: `lower(cb.literal(v))` inlines the value into the SQL, against R-FLT-08, and a
bound `lower(?)` cannot be typed by H2. So `likeIgnoreCase` and `eqIgnoreCase` render `lower(col) LIKE ?` and
`lower(col) = ?`, with the value lower-cased with `Locale.ROOT` and bound, identically on every Tier-1 vendor. A
database whose `lower()` folds only ASCII, such as PostgreSQL with C collation, can disagree with Java on non-ASCII
text. Rejected: `lower(literal)` (inlines the value), a bound value wrapped to force a type (vendor-specific SQL), and
`ParameterExpression`s bound by the executor (every caller of a built query would have to bind them).
→ `api/12` R-FLT-07, R-FLT-08.

**D-23 — `Filters` is a mutable collector frozen into an immutable list.**
The `Filters` a `where(...)` operator, or a fragment, receives lives for that one call: each method adds its filter
and returns the same collector, and the call's result is frozen into an immutable list that the `ModelQuery` keeps, so
INV-9 holds and nothing per-query lives outside `JoinContext`. The operator's return value is ignored, because every
filter added to the collector counts: with an immutable builder, a lambda that drops a return value
(`f -> { f.eq(A, x); return f.eq(B, y); }`) would silently lose `eq(A, x)` (INV-5). The collector is closed when the
call returns: a reference kept past it (in a field, or passed to a deferred helper) throws `MQ1303` on any later use
rather than adding filters nobody reads, and so does the enclosing collector used while a nested operator runs (a branch
lambda adding to the outer builder by mistake). Rejected: an immutable builder
whose returned instance is the result (the same call shape, with a silent failure mode). → `api/12` §1, R-FLT-01.

**D-24 — Custom expressions receive the `CriteriaBuilder` as an argument.**
`Filters.add` and `Agg.of` take a `BiFunction<JoinContext, CriteriaBuilder, …>`, the shape of `TableField.on`, and are
called once per query build with that build's context and builder. A predicate or expression needs a
`CriteriaBuilder`, and one captured in the lambda would tie an immutable definition to one persistence unit (INV-9,
D-21); passing it keeps `JoinContext`'s public surface to `of` and `TableField.resolve` (D-18). A custom predicate that
returns `null` throws `MQ1305`, and an `Agg.of` expression that does `MQ1405`: each is evaluated at build time, when a
skip could no longer promise to create no join (R-FLT-03), so skipping stays explicit through `when(...)` or the
`Optional` forms (P-3). `MQ1301` stays the code for a `null` filter value. Rejected: a public
`JoinContext.cb()` (widens an `@Incubating` type for one use) and `Function<JoinContext, …>` with no builder (only the
few predicates `Path` builds on its own). → `api/12` §1, `api/13` §1.

**D-25 — `Agg.sum` and `Agg.sumAsLong` check their column's type when called.**
R-AGG-03 wants `Agg.sum` over a 32-bit column rejected, but Java cannot overload on a type argument:
`sum(ColumnField<M, ?, Integer>)` and `sum(ColumnField<M, ?, BigDecimal>)` erase to one method, so the signature alone
accepts every `Number`, and `sumAsLong`'s alone lets a `BigDecimal` sum be read as a `Long` and silently truncated
(INV-5). Both factories therefore check the column's Java type when called, the earliest point that can see it
(CC-ERR-03), and throw `MQ1403`; generated columns are caught earlier still, by the processor (`MQ3205`). Rejected:
typed method names (`sumDecimal`, `sumLong`, …), which multiply the surface for a check one call can make.
→ `api/13` R-AGG-03, AC-AGG-02.

**D-26 — The join set is the same in every phase.**
A query's FROM/JOIN is decided by its selection, ordering, grouping and filters together, and only the SELECT list
differs between `MODEL`, `PRIMARY_KEY` and `MODEL_BY_KEYS`. Joins needed outside `or`/`not` are resolved before any
inside them, across `where` and `having`. Rejected: phase-dependent joins (primary-key-first returns different rows,
R-QRY-09), and `or()` never reusing an INNER join (a second join on to-many paths). A customizer runs last, so a path
the query joined LEFT only for an `or`/`not` and that the customizer resolves as INNER gets a second join; that is
documented on `QueryCustomizer` and accepted (P-6). → `api/11` R-QRY-09, `api/12` R-FLT-10, AC-QRY-09.

*(Reversed by D-115.)* **D-27 — No `Col.of`: a non-aggregate derived value belongs in `afterMap`.**
`Agg.of` is the escape hatch for aggregate expressions only, since any aggregate makes the query grouped (R-AGG-07).
A value computed per row from other columns is derived in `afterMap` (or a record's `finisher`) from the columns the
`SelectSet` selects. Rejected: `Col.of(expression)` for computed non-aggregate columns, which would turn the column
model into a general expression builder (P-5). Resolves Q-5. → `api/11` R-QRY-05, R-QRY-08, `api/13` R-AGG-02.

**D-28 — Grouping is structural.**
A query is grouped when it has a `groupBy` or selects an aggregate, and nothing else: `having(...)` on any other query
throws `MQ1407` at build time, even when every filter it recorded was skipped, so whether a query is grouped never
depends on a request's values. Ordering must fit the grouping for the same reason: a grouped query orders by group
keys and aggregates, an ungrouped one never by an aggregate, else `MQ1406`. Rejected: `having` making a query grouped
(a skipped filter would silently turn a grouped query back into a row query with a different shape and row count).
→ `api/13` R-AGG-07, R-AGG-08, AC-AGG-08, AC-AGG-12.

**D-29 — The model phases select every key the executor reads.**
`MODEL` and `MODEL_BY_KEYS` select the `SelectSet`, plus the primary key when one is defined on an ungrouped query,
every ordering key and every group key. None of these changes which rows return, since their joins are made anyway
(D-26), and selecting them lets the executor read a row's key (offset export, R-PAG-01, R-PAG-03), cursor (R-PAG-04)
and group (R-PAG-11) from the `Row` without a second definition of the selection. Rejected: adding the key only for
`keyset()` or `primaryKeyFirst(...)` (offset export needs it too, R-QRY-03). `ModelQuery` and `QuerySpec` expose
`isGrouped()` and `groupBy()` for the executor. → `api/11` R-QRY-04, AC-QRY-03.

**D-30 — A `DEFAULT`-precedence keyset reaches its NULLs in order to refuse them.**
Under the default `fail`, a NULL in a keyset column with `DEFAULT` precedence throws `MQ2202`, but only if a page reads
it: where the database sorts NULLs after every value (PostgreSQL ascending, H2 and MySQL descending), the plain
`a > :ka` branch never matches them, and the export would end early without a word. So each non-key `DEFAULT` column's
branch is `(a > :ka OR a IS NULL)`, every row read is checked, and the NULL is refused wherever the vendor sorts it,
without consulting `VendorProfile`. Primary-key columns get no such branch (R-PAG-03 keeps them non-NULL). Rejected:
checking only the cursor row (it silently drops the NULLs on those vendors); a count probe per export (an extra query).
Under `fail` the branch stays whatever null ordering the profile or provider reports (D-35).
→ `engine/21` R-PAG-05, INV-5, AC-PAG-07.

**D-31 — Keyset export drops repeated keys within a page, and throws on a key of the page before.**
Each keyset page starts strictly after the last row of the page before, in an order closed by the primary key
(R-PAG-04), so no row should reach two pages. A key can still repeat inside one page through a to-many join used only
by predicates, and that repeat is dropped as in offset mode. *Amended at the M2 gate:* a cursor value that does not
compare equal to the stored one once bound (a MySQL `FLOAT` bound as a decimal), or a row whose keyset value moves
after the cursor between pages, brings a key of the page before back, forever when a repeated tie group fills a page.
So the previous page's keys are kept, bounded by one page (INV-4), and a row of the new page holding one throws
`MQ2205`. `Float` and `Double` keysets are refused at build (`MQ1207`, R-QRY-13), since a value stored below its
bound form skips rows with no repeat to catch. Rejected: offset mode's cross-boundary dedupe (the same causes can skip
rows as well as repeat them, and a dedupe would hide both, or loop on a full tie group); no key set across pages (the
original decision, which let the repeat pass silently). → `api/11` R-QRY-13, `engine/21` R-PAG-02, R-PAG-04, R-PAG-14,
AC-PAG-05, AC-PAG-13.

**D-32 — Primary-key-first paging places rows by key, in batches clamped by the vendor profile.**
`page` and offset `export` read a page past the `whenOffsetAbove` threshold key-first; export reads back only the keys
it has not exported. Step 2 re-applies the query's order and also places each row at its key's step-1 position, so the
order holds across batches and a key a predicate's to-many join repeats gives its row twice, as the one-step page does.
A NULL key in step 1 throws `MQ2201`, since step 2 could not read that row back. Each step-2 statement takes at most
the resolved profile's `maxInListSize()` keys and its `maxBindParameters()` binds, less the statement's own binds that
JPA reports as query parameters, at one per key column (until M3, the lowest Tier-1 limits of `vendor/41` §2 stood in
as constants). `ModelQueryConfig.primaryKeyFirstBatchSize(...)` lowers the batch further; unset, the batch is
otherwise the whole page. Rejected: concatenating the batches in statement order (a row changing between the two
steps breaks it); a hidden default batch of 1 000 (it would leave the vendor clamp untested until the setting exists). →
`engine/21` R-PAG-03, R-PAG-07, R-PAG-08, AC-PAG-08, AC-PAG-09.

**D-33 — A customizer never changes ordering or grouping.**
The executor takes its paging keys from the definition, not from the Criteria query: the stable-order tie-breaker and
the keyset cursor from `orderBy` and the primary key, the grouped dedupe key from `groupBy` (R-PAG-01, R-PAG-04,
R-PAG-11). A group-by a customizer adds splits groups the dedupe cannot tell apart, and an order it changes pages by
an order the cursor does not follow; both lose rows silently. So every statement compares its `ORDER BY` and
`GROUP BY` before and after the customizer, in every phase and on grouped queries too, and throws `MQ1205` when either
changed. Rejected: appending the customizer's expressions to the tie-breaker (they have no `SelectField`, so they
cannot be read from the `Row` to dedupe or build a cursor, R-QRY-08); allowing an added `GROUP BY` as before (grouped
export then drops rows). → `api/11` R-QRY-07, R-QRY-11, AC-QRY-10.

**D-34 — Vendor facts reach `core` as vendor-neutral `RenderOptions`; provider behaviour is its own SPI.**
`VendorProfile` and `DatabaseVendor` live in `jpa.spi`, and the executor resolves a profile once per
`EntityManagerFactory`. What a query build may render by (IN-list and bind limits, an optional native
null-precedence renderer) reaches `core` as an immutable `RenderOptions`, passed to
`ModelQuery.buildQuery(cb, phase, options)` and held by that build's `JoinContext`; `JoinContext.of(root, cb)` and
`RenderOptions.portable()` carry the `OTHER` values. The resolver caches by the configured vendor and the MySQL
streaming mode (`vendor/41` R-PRF-07). `ModelQueryConfig` moves to `jpa`, so `vendor(DatabaseVendor)` is
type-safe. Dialect detection, the grouped count and native null precedence vary by persistence provider, not by
database, so they are one `ProviderSupport` SPI in `jpa.spi`, separate from `VendorProfile`, which
`model-query-hibernate` implements. `ProviderSupport.countQuery` only builds the grouped count; the executor runs it,
so the configured timeout applies (`engine/20` R-EXE-11). One explicit vendor applies to every factory that shares the configuration, so M5
needs a configuration per factory. Rejected: profile facts on `ModelQuery` (a definition is shared and immutable,
INV-9); a `ThreadLocal` (invisible, and wrong across threads); a `CriteriaBuilder` decorator (it wraps a whole provider
interface, and code that unwraps the provider's own builder bypasses it); `VendorProfile` in `core` (a vendor name in
`core`, INV-6); `vendor(String)` (a typo compiles); folding `GroupedCountStrategy` into `VendorProfile` (one profile per
provider × database). → `vendor/40` §1, R-VND-03, R-VND-04, R-VND-06, `api/11` R-QRY-10, `engine/20` R-EXE-03.

**D-35 — A `DEFAULT`-precedence keyset column takes the profile's null ordering, and an unknown one refuses on read.**
Each keyset column's NULLs sort where its explicit precedence says, else where the profile's
`defaultAscendingNullOrdering()` puts them in its direction (descending reverses it); the `ORDER BY` of a `DEFAULT`
column stays bare, so the database sorts by that same default. Under `keyset.null-keys=fail` a NULL there still
throws `MQ2202`. Under `fail`, every non-key `DEFAULT` column keeps D-30's `IS NULL` branch whatever the ordering, so
a misreported ordering (H2 `DEFAULT_NULL_ORDERING`, an unreported provider default, a wrong profile) still ends in
`MQ2202` rather than skipped rows. Under
`honour-null-precedence` the column pages its NULLs by that ordering, as an explicit precedence would. Under an
`UNKNOWN` ordering (`OTHER`) neither is possible, so a NULL there throws `MQ2202` when a page reads it, in either
mode, with D-30's branch making sure it is read; "refused" in R-COL-13 and R-VND-06 is that error. Rejected: dropping
the branch where NULLs sort first (a wrong ordering fact loses rows silently in the default mode); refusing
up front any keyset over a column the metamodel reports optional (the metamodel does not know a column's
nullability, and a nullable column holding no NULLs pages correctly); a new `MQnnnn` for the `OTHER` case (it is the
`MQ2202` condition, a NULL key without a known precedence). → `engine/21` R-PAG-05, `api/10` R-COL-13, `vendor/40`
R-VND-06, AC-PRF-07, AC-VND-05.

**D-36 — A provider's configured default null ordering overrides the profile's for keyset NULLs.**
A persistence provider can be configured to sort the NULLs of every order rendered without a null precedence, such as
Hibernate's `hibernate.order_by.default_null_ordering`; a bare `ORDER BY` then no longer sorts by the database's
default, so D-35's profile ordering would disagree with it: under `fail` the NULLs still end in `MQ2202` wherever they sort
(D-35), and under `honour-null-precedence` rows were paged out of order.
`ProviderSupport.defaultNullPrecedence(emf)` reports that setting as `FIRST` or `LAST`, and where it is set it replaces
the profile's ordering for a `DEFAULT` keyset column in both directions, not reversed for a descending one, since the
provider puts the NULLs at that end either way; this holds under `OTHER` too. The `ORDER BY` stays bare (D-35). An
explicit precedence is then never left bare where only the profile's default matches (R-COL-12), since the provider
would re-sort it. Rejected: rendering every keyset column with an explicit precedence (it changes the SQL of every
keyset export for a setting few use); reading the setting in `model-query-jpa` (INV-7). Without
`model-query-hibernate`, nothing reports the setting; that is a documented limitation rather than a refusal of every
Hibernate factory without the module, and the remedy is the module or an explicit precedence. → `engine/21` R-PAG-05, `api/10` R-COL-12, R-COL-13, `vendor/40`
§1, AC-PRF-07.

**D-37 — A column carries its converter; `ColumnConverter` lives in `core`.**
`ColumnConverter<C, F>` (`toModel(F)`, `toAttribute(C)`, never called with `null`) is a `core` type, and
`ColumnField.of(model, table, attribute, type, attributeType, converter)` builds a converted column whose `type()` stays
the model type `C`. `Row.get` converts what it read, value filters bind `toAttribute(value)`, and `MQ1001` compares the
entity attribute against `attributeType`. `Row.raw(column)` returns the value before conversion; the executor reads
primary keys and keyset cursors through it, so a converter that is not a bijection cannot change which rows a page
holds (INV-5). An aggregate function over a converted column throws `MQ1408`, since the database computes over `F`;
D-84 lets `min`, `max` and `countDistinct` take an ordered converter.
The annotation names the converter as `Class<?> converter() default void.class`, which keeps the annotations module on
the JDK alone (INV-7); the processor checks the class by name and reports `MQ3014` when it is not a
`ColumnConverter<fieldType, attributeType>` or has neither a public static `INSTANCE` nor a visible no-arg constructor.
Rejected: converting in the generated mapper only (a filter on the column could not convert, R-PROC-07); the interface
in `annotations` (`core` would then depend on it, or the type would be duplicated). `ColumnField.path(ctx)` on a
converted column is a path of type `F` typed as `Path<C>`; a custom predicate on it compares attribute values.
→ `api/10` §3, §5, R-COL-08, R-COL-14, `api/13` R-AGG-04, `processor/30` R-PROC-07, `processor/31` §1.

**D-38 — A join carries its presence key, and the engine selects it.**
`TableField.presentBy(PrimaryKey)` names the key whose non-null value means the join matched. It survives `as`, `on`
and `withParent`, is not part of the join key, and throws `MQ1104` on a root. When a column of such a join is selected
on an ungrouped query, the model phases also select the presence key of that join and of every `presentBy` join above
it, re-rooted onto the column's model. No join is added, since the column's own join is already made (D-26). A
generated QModel declares `KEY`, and an outer model declares its join as
`TableField.join(ROOT, "customer", LEFT).presentBy(QCustomerView.KEY)`; the mapper reads the key through
`row.scoped(CUSTOMER_TABLE)` and maps the nested model when any key component is non-null. A grouped query adds no key
(R-AGG-09): selecting a column under a `presentBy` join whose key columns are not all group keys throws `MQ1409` at
`build()`, rather than mapping a nested model that reads as absent. Rejected: the outer mapper testing a column the
caller happened to select (a match whose selected columns are all `NULL` would read as a miss, R-GEN-13); the
generator adding the key to every joined `SelectSet` (a hand-built `SelectSet.of(CUSTOMER_NAME)` would lose it).
A customizer that applies `DISTINCT` sees the added key columns in the selection; the user guide says so.
→ `api/10` §1, R-COL-15, `api/11` R-QRY-04, `api/13` R-AGG-09, `processor/31` §1, R-GEN-09, R-GEN-13, D-29.

**D-39 — The processor is isolating, and every annotation is `CLASS`-retained.**
The per-column constants of a nested model stay (R-GEN-04). The processor computes them from the nested model's own
fields and the nested QModel's name, and never looks the nested QModel up, so processing order does not matter. Each
generated file has one originating element, its model's `TypeElement`, which is what Gradle's isolating mode requires;
Gradle recompiles the outer model when the nested model it references changes. Gradle's incremental processing reads
only `CLASS` or `RUNTIME` annotations, so every annotation moves from `SOURCE` to `CLASS` retention. A nested model
that is on the classpath rather than in the compilation was refused by `MQ3005` at first; D-45 accepts it. Rejected: an
aggregating processor (every model is reprocessed on any change); dropping the per-column constants for a run-time
`withTable` over the nested `ALL` (the joined columns would lose their typed constants). Not yet verified under Gradle:
staleness across two levels of nesting; if it shows, the registration falls back to aggregating.
→ `processor/30` R-PROC-02, `processor/31` R-GEN-04, R-GEN-05, AC-GEN-08, `processor/32` `MQ3005`.

**D-40 — Lombok is a test dependency of the processor only.**
`model-query-processor` takes `org.projectlombok:lombok` in `test` scope, so the compile-testing suite can run the
processor beside Lombok (R-GEN-10, R-DIAG-05). The build's ban on Lombok stays for every other module and for every
non-test scope of this one; nothing shipped depends on it. Rejected: hand-written stand-ins for Lombok's output (they
would not exercise processor ordering, which is the point of AC-GEN-05). → `delivery/61` R-REL-03, AC-GEN-05.

**D-41 — A column's attribute may be a dotted path through embedded values.**
`ColumnField.of(..., "address.city", ...)` resolves each segment in turn from the column's table. Every segment but the
last must be an embedded attribute; a segment that is unknown throws `MQ1002` naming the segment, and one that crosses
an association throws `MQ1002` too, since that needs a `TableField` (R-COL-01). This is what `@Column(attribute)` on an
`@Embedded` or `@EmbeddedId` value generates (R-PROC-06, R-GEN-02). Rejected: a `TableField` per embeddable (an
embeddable is not a join, and would enter the join key). → `api/10` R-COL-08, `processor/30` R-PROC-06.

**D-42 — A converted column keeps attribute values wherever the database or the engine compares.**
A value filter converts its value once, when the filter is recorded, and a converter returning `null` there is a
`NullPointerException` naming the column. Keys, keyset cursors and group keys are read with `Row.raw`, so `MQ1206` and
`MQ1207` check the attribute type, not the model type. `MQ1408` covers every column function, `countDistinct`
included, and is checked before `MQ1403`. An operator the database cannot apply to the attribute throws `MQ1001` when
the filter is recorded: `like`, `likeIgnoreCase` and `eqIgnoreCase` on an attribute that is not a `String`, an ordering
operator on one that is not `Comparable`, and `compare` between two columns whose attribute types differ. Two columns
are equal, and a scoped `Row` matches them, only when their converters are of the same class or both absent.
Rejected: a new code for the operator mismatch (it is the declared-type mismatch `MQ1001` already names). →
`api/10` R-COL-14, D-37.

**D-43 — A presence key is selected for `SelectSet` columns only, by the `presentBy` instance.**
Only a column of the query's `SelectSet` brings in the presence keys of the joins it is read through; an order-only
or primary-key column does not, since no nested model is mapped from it. The keys are appended last in the selection.
The key is found on the column's own `TableField`: a join with an equal key built without `presentBy` selects none,
because the presence key is not part of the join's identity. Generated code declares each join once, so it always
carries it. → `api/10` R-COL-15, D-38.

**D-44 — What the processor's flat-column checks accept, and how a QModel is named.**
`MQ3002` compares the model field's type with the entity attribute's type for identity, a primitive counting as its
wrapper: the engine makes the same comparison at first use (`MQ1001`, R-COL-08), so a merely assignable type
(`Number` over a `Long` attribute) would compile and then fail. A column on a to-one association is a column of the
target entity's type, as a hand-written one is, and is reported as the warning `MQ3016`: a read model should hold a
nested model through `@Join`, not a managed entity, but the engine accepts the column, so the processor does too. A column on a collection attribute is `MQ3002`, since a collection
cannot be selected (`engine/21` R-PAG-13). A dotted `@Column(attribute)` whose segment crosses an association, or
walks into a basic value, is `MQ3001` naming the segment, mirroring `MQ1002` (D-41). A `prefix` or `suffix` written on
`@QueryModel` wins over `-Amodelquery.prefix=` / `-Amodelquery.suffix=`, which win over the defaults: the option sets
a project's convention and the annotation is the exception to it. Diagnostics on a record are reported on the
component's field, which carries the component's annotations and its source position. `MQ3008` is not checked on a
class carrying Lombok's `@NoArgsConstructor`: ordered before Lombok, the processor cannot see that constructor, so
javac checks the generated call, as it does a setter (R-GEN-11). A column whose type has type arguments
(`Map<String, String>`) is declared with its raw class cast to that type, since it has no class literal. Rejected: `MQ3002` by
assignability (it would let through what `MQ1001` refuses). → `processor/30` R-PROC-04, `processor/32` §1. Amended by
D-136: a column over a to-one whose type is the target's single basic id type reads the foreign key instead of
`MQ3002` (R-COL-29); amended at the M22 gate (2026-10-14): a target with `@SQLRestriction` or `@Where` is `MQ3029`.

**D-45 — What an outer model declares for its joins, and where a nested model may come from.**
An outer QModel declares every join under a `@Join`, not only the join itself: for `OrderView.customer →
CustomerView.address` it has `CUSTOMER_TABLE`, `CUSTOMER_ADDRESS_TABLE =
QCustomerView.ADDRESS_TABLE.withParent(CUSTOMER_TABLE)`, a column `<PREFIX>_<constant of the nested QModel>` for each
column on either, and one `SelectSet` per join (`CUSTOMER`, `CUSTOMER_ADDRESS`) holding that model's own columns.
Every constant is read from the QModel of the `@Join`'s own nested model, which already declares the joins below it,
so `Row.scoped` finds them where the nested mapper looks. The joined `SelectSet`s follow `generateSelectSets`. When
several `@Join`s read one attribute, each without an explicit `alias` takes its field name. A `@Join(attribute)` is
one to-one association of the root itself; a dotted path is `MQ3003`. A join whose prefix is taken reports `MQ3015`
once, on its first clashing constant. A `prefix` that is not a Java identifier is `MQ3015` too, since it can't start a
constant's name. A to-one column with a converter is not warned of by `MQ3016`: the field holds the converter's value. A converter is taken from a public static `INSTANCE`, else built with a no-arg
constructor the QModel's package can call. A nested model is any `@QueryModel` the compiler can read, a source of the
compilation or a class on its classpath (Q-10): the outer model needs only the nested model's field names, types and
`CLASS`-retained annotations, which a class file keeps, and an incremental build that recompiles the outer model alone
sees the nested model as a class. The nested QModel must be on the classpath too, which it is when the nested
model's module ran the processor; if not, javac reports the missing `Q` class. A class `@Join` field's initialiser
can't be read by a processor, so it is not checked: the mapper assigns the field on every row. Rejected: one joined
`SelectSet` holding the columns of every level (selecting a customer would always join its address); accepting only
models whose source is a root element of the compilation (it fails the incremental build above).
→ `processor/30` R-PROC-09, `processor/31` R-GEN-04, R-GEN-14, `processor/32` `MQ3003`, `MQ3005`, `MQ3015`.

**D-46 — Which join a `@FilterColumn` path is on, and what the processor generates for it.**
A path with no `alias` reuses the first-declared `@Join` on its first association and the joins of that `@Join`'s
nested model below it. An `alias` equal to the alias of a `@Join` on the path's first association, written or
automatic (R-PROC-09), selects that `@Join` and the joins below it; any other alias is a join of its own, shared by
the filter columns that carry it. A reused join keeps its `@Join`'s type: a `joinType` written on the filter column
that differs from the type of any join it reuses is `MQ3012`, naming the filter column, its type, the `@Join` field
and its type; a `joinType` left out never conflicts. A generated table is `<PATH>_TABLE`
(`CUSTOMER_COUNTRY_TABLE`); below a reused join it is `<join prefix>_<SEGMENT>_TABLE`; an aliased path takes the
alias's constant name for its first join (`lineB` → `LINE_B_TABLE`), only that join carries `.as(alias)`, and the
joins below hang from it (`LINE_B_ORIGIN_TABLE`). `joinType` applies to every join the path generates, so two filter
columns that share a generated join with different types are `MQ3012`. Every `@OneToMany` or `@ManyToMany` of the
root declares `<ATTR>_TABLE`, always `LEFT`, which no filter column replaces or retypes: a path with no alias through
the collection reuses it when its `joinType` is `LEFT`, and with `INNER` is laid out as if it carried the alias
`<attr>Inner`, so it gets `<ATTR>_INNER_TABLE` with `.as("<attr>Inner")` and the two joins never merge; a filter
column that writes that alias itself shares the join, and `MQ3012` applies. A field, a `@Join(prefix)` or another
join that produces the name of one of these constants is `MQ3015`. An `@ElementCollection` gets no table constant,
and a path through it is `MQ3011`, as is a path through an association inside an embedded value (a generated join
takes one attribute of its parent) and a path that ends at a collection. A path crossing a collection needs a
written `joinType` (`MQ3011`). A path that ends at a to-one association is accepted, with no `MQ3016`. A `converter`
that does not convert to the attribute's type is `MQ3014`. `alias` and `joinType` on a path with no association are
ignored, with no diagnostic. A filter `name`, a filter `alias` and a `@Join(prefix)` must be a legal Java simple
name, so a keyword is refused like any other non-identifier: the name by `MQ3013`, the alias and the prefix by
`MQ3015`. Every filter-column diagnostic is reported on the model's type. An outer model does not re-export the
filter columns of a model it nests. Rejected: letting a filter column retype the collection's constant (an `INNER`
`ITEMS_TABLE` changes what every other use of it joins); a warning for a `joinType` that repeats the `@Join`'s type;
a diagnostic for an unused `alias` on a root attribute.
→ `processor/30` R-PROC-11, R-PROC-12, R-PROC-13, `processor/32` `MQ3011`, `MQ3012`, `MQ3013`, `MQ3015`.

**D-47 — What the processor accepts and rejects on a summary model.**
`@Aggregate(distinct = true)` on `SUM`, `AVG`, `MIN` or `MAX` is `MQ3206`. `@QueryModel(singleGroup = true)` on a model
with `@GroupBy` fields is `MQ3207`, reported on the model. A `@Join` whose nested model has an `@Aggregate` field is
`MQ3005`. `@Aggregate` combined with `@PrimaryKey`, `@Column`, `@Join` or `@Transient` on one field is `MQ3204`; the
reader no longer drops the aggregate beside `@Transient` or `@Join`. `@ExcludeFromDefaults` on an aggregate is ignored,
with no diagnostic. A primitive `@Aggregate` field is `MQ3201` only, naming the type the function returns (`Long` for
`COUNT`), and gets no `MQ3202` or `MQ3205`. An `@Aggregate` attribute that is an association is `MQ3002`, and the
message asks for a basic attribute. As built: `COUNT` over an attribute with `distinct = false` counts the non-null
values of that attribute. `SUM` over `Integer`, `Short` or `Byte` into a `Long` field uses `Agg.sumAsLong`; any other
field type is `MQ3205`. `AVG` over a non-`Number` and `MIN` or `MAX` over a non-`Comparable` are `MQ3202`. A malformed
`@Aggregate` attribute reports `MQ3001` (missing or unknown), `MQ3002` (association) or `MQ3202` (unsupported source
type). A model with no `@PrimaryKey` emits no `KEY`, and its `query()` has no `primaryKey`. A grouped model with a
`@PrimaryKey` applies `primaryKey(KEY).groupBy(GROUP_KEYS)`. `GROUP_KEYS` is emitted even with
`generateSelectSets = false`. A `@GroupBy` field that raises `MQ3204` is left out of `GROUP_KEYS`.
→ `processor/30` R-PROC-05, R-PROC-15, `processor/32` `MQ3005`, `MQ3201`, `MQ3204`, `MQ3206`, `MQ3207`.

**D-48 — The diagnostic matrix covers the live codes.**
`processor/32` R-DIAG-05 and AC-DIAG-01 cover each row of §1 not tagged `Future`: `MQ3001`–`MQ3016` and
`MQ3201`–`MQ3207`. `MQ3301`–`MQ3307` have no check before M6, which extends the matrix; AC-DIAG-05 (checked by the AC
audit) still lists them, so `reference/90` and §1 never drift, and also checks the live rows against the constants of
the processor's `DiagnosticCode`. The matrix runs each code with Lombok's processor off and on, for a class and a
record where both can raise it: `MQ3008` is a class check, `MQ3009` and `MQ3010` are record checks. An `MQ3012` raised
on a join the user gave no alias names the join by its path (`join 'lines' is INNER here, LEFT on QTY`), never the
generated `<attr>Inner` alias, which the user did not write.
→ `processor/32` R-DIAG-05, AC-DIAG-01, AC-DIAG-05.

**D-49 — A grouped record model's key can't be primitive.**
`MQ3009` exempts a primitive `@PrimaryKey` record component because an ungrouped query always selects the key. A
grouped query doesn't (the key is selected like any other column, and only when it is a group key), so on
a record with `@Aggregate` or `@GroupBy` fields a primitive `@PrimaryKey` is `MQ3009` as well: the generated
constructor call would unbox `null`. A `@Join` to a summary model reports `MQ3005` alone, not `MQ3006` for the key a
summary model never needs; an `@Aggregate` on a `@Join` field reports `MQ3204` alone, not `MQ3015` for the join's own
prefix.
→ `processor/32` `MQ3005`, `MQ3009`, `MQ3204`.

**D-50 — The Spring repository is a fragment.**
`ModelQueryRepository<E>` is a fragment interface a repository extends next to `JpaRepository`, not a base interface
`<E, ID> extends JpaRepository`. `ModelQueryRepositoryFactoryBean` extends `JpaRepositoryFactoryBean` and adds the
fragment's implementation when the repository interface extends it. A base class would collide with a user's own
`repositoryBaseClass`, and the base interface forced `JpaRepository` and an `ID` no method used. The starter swaps
bean definitions whose class is exactly `JpaRepositoryFactoryBean` for it, which keeps Boot's own registrar; any other
subclass gets the fragment beside it (D-113). Spring
Data's exception translation applies to the fragment as to any repository method: a provider exception reaches the
caller as a `DataAccessException`, while `MQnnnn` exceptions pass through unchanged. That is Spring's behaviour for
every repository, not a semantic the library adds (INV-8).
→ `integration/50` §1, R-SPR-02, R-SPR-12.

**D-51 — One paging method, whose total is `null` when not counted.**
`findPage(q, pageable, CountMode)` stays one method, so a caller whose client chooses whether it wants totals passes
the mode through without branching. It returns `ModelPage<M>`, a Spring Data `Slice` with `Long getTotalElements()`
and `Integer getTotalPages()`, both `null` under `NO_COUNT`. Spring Data's `Page` was rejected as the return type:
its `getTotalElements()` is a primitive `long`, so an unknown total must either throw, which breaks serialising the
result, or be a number that reads as a total (INV-5). Keyset paging is not exposed through the repository;
`Pageable.unpaged()` is `MQ2001`. `ModelPage` is a public interface, like `Page`, whose `map` keeps the totals; a page
count beyond `int` throws `ArithmeticException` rather than wrapping, which needs over two billion pages.
→ `integration/50` R-SPR-04, R-SPR-07.

**D-52 — A per-call sort is a `core` feature over the selected columns.**
`SortSpec` and `ModelQuery.orderedBy(SortSpec)` live in `core`, so plain JPA has what `Sort` gives Spring (INV-8). A
sort property names a column the query selects, by root-relative attribute path first and then by name (superseded
by D-55 and D-58: property path, attribute path and aggregate name, every tier tried); it never
names an unselected root attribute, which would need an implicit join, could page over a to-many path, and is refused
by PostgreSQL under `DISTINCT`. No match, two matches and `ignoreCase` are all `MQ2301`. A sorted `Sort` replaces the
definition's `orderBy`. → `api/11` R-QRY-14, `integration/50` R-SPR-04, R-SPR-06.

**D-53 — Supplied profiles and the two defaults sit on `ModelQueryConfig`.**
`ModelQueryConfig.vendorProfiles(...)` takes profiles that win over `ServiceLoader` ones, which win over the built-in
ones; the starter passes its `VendorProfile` beans there, so `jpa` never sees Spring (INV-7) and a plain-JPA caller
has the same hook (INV-8). The supplied profiles are applied after the per-factory cached detection, not added to its
key. `exportPageSize` (1000) and `streamFetchSize` (500) join the config; `ExportOptions`' page size becomes optional
so a call can leave it to the config, a breaking change to an `@Incubating` record. The setter is
`vendorProfiles(Collection<? extends VendorProfile>)`: a later call replaces the earlier set, and two profiles for one
vendor throw `MQ4002` at the call. A supplied `OTHER` profile also serves a vendor that fell back to `OTHER`.
A supplied profile that replaces the resolved one is logged at `INFO` once per factory and profile class, naming both.
→ `vendor/40` R-VND-03, `api/11` R-QRY-15, `integration/50` R-SPR-08.

**D-54 — One config, one executor per repository, the repository's own transaction manager.**
One `ModelQueryConfig` bean is shared; each repository builds its executor on the `EntityManager` Spring Data gives
it, so vendor resolution stays per factory. A `ModelQueryConfigurer` bean may vary the config per factory, and
`modelquery.vendor` with several factories and no configurer is `MQ4005`. `stream` runs in a read-only
`TransactionTemplate` on the `transactionManagerRef` of the repository's `@EnableJpaRepositories`, not through
`@Transactional` on the interface, which `enableDefaultTransactions = false` would disable. The R-SPR-09 warning is
logged where the starter builds the config bean, once per context. `modelquery.primary-key-first.batch-size` has no
default of its own: unset means the whole page, as `engine/21` R-PAG-07 says. A `ModelQueryConfig` bean of the
application that would drop a `VendorProfile` bean or a set `modelquery.*` property fails startup with `MQ4006` (see D-74).
→ `integration/50` R-SPR-03, R-SPR-09, R-SPR-13.

**D-55 — A sort property is the model's property path.** A client sorts by the names it sees in the model, not by
entity attributes. A generated column carries its model field name as its property, and a generated `@Join` table
its join field name, so a joined column's property path is `customer.name` even when the nested field reads
`@Column(attribute = "fullName")`, and two `@Join`s on one attribute sort apart (`billing.city`, `shipping.city`).
`orderedBy` matches the property path first, then the attribute path from the root; a bare attribute name no longer
matches a joined column, which could pick the wrong one silently. A hand-written column has no property unless given
one with `named(String)`, which returns a copy (INV-9), and a table likewise. The property is the Java field name,
not a Jackson rename, and it is not part of `equals`/`hashCode`, so the SQL does not change. Aggregates still match
by their name. → `api/11` R-QRY-14.

**D-56 — `ModelQueryConfigurer` takes the shared config and the factory.** It lives in `model-query-spring-data`,
next to the factory bean that calls it: `@FunctionalInterface ModelQueryConfig configure(ModelQueryConfig shared,
EntityManagerFactory factory)`, called once per repository factory bean with the factory of the repository's
`EntityManager`; returning `shared` keeps it, and `null` is refused. A context has at most one configurer bean, so the
choice per factory sits in one place; a second fails startup like any non-unique bean. → `integration/50` R-SPR-13.

**D-57 — A vendor named as text is parsed in `jpa`.** `ModelQueryConfig.vendor(String name)` matches a
`DatabaseVendor` name ignoring case, `-` and `_` (`sql-server`, `SQL_SERVER` and `sqlserver` are one vendor) and throws
`MQ4001` for an unknown name. The Spring starter passes `modelquery.vendor` through it, so the starter never names the
vendor type and INV-6's layering rule stays as it is. → `vendor/40` R-VND-04, `integration/50` §3.

**D-58 — A sort that `orderedBy` can't honour is a request failure.** A sort property that matches different columns
on different tiers (property path, attribute path, aggregate name) is ambiguous: `MQ2301` listing every candidate
with the tier it matched; one column matched on two tiers is not. A sort failure that `orderedBy` detects is always a
`ModelQueryExecutionException` `MQ2301`, with the build failure as cause, because the sort comes from the request.
`orderedBy` refuses an ungrouped definition without a primary key, which has no tie-breaker for a client sort.
→ `api/11` R-QRY-14, `integration/50` R-SPR-06.

**D-59 — Bulk writes come before the first release.** The bulk-write milestone moves ahead of 0.1.0 and is renumbered:
M6 is bulk writes (`api/14`), M7 is 0.1.0, M8 is hardening, shipped as 0.2.0 (D-90). The first release ships the read
API and filter-driven bulk updates and deletes together, both `@Incubating` until the M8 API review. Writes still load
no entity (INV-1). → `delivery/62` §1 R-RDM-01, §2 R-RDM-03, `api/14`, see D-75.

**D-60 — Write builders are staged.** `ModelUpdate.builder(TableField<E,E> root)` returns `Start<E>`, whose
`primaryKey(PrimaryKey<M,K>)` returns `Builder<E,K,M>`; each call returns a new immutable stage, like
`ModelQuery.Builder`. `Builder` takes the assignments (`set(Changes<M>)`, `set(column, value)` with `null` refused as
`MQ1603`, `setNull`, `setExpression`), then exactly one row choice: `whereKey(K)` → `Keyed` (adds one `where`, then
`expectVersion`), `whereKeys(Collection<? extends K>)` → `Narrowable` (adds one `where`), `where(...)` or `all()` →
`Options`. `Options` holds `keepVersion`, `chunked`, `persistenceContext` and `build()`. A second `where`, `where` with
`all()`, and `expectVersion` without `whereKey` don't compile, so `MQ1606`'s "without `whereKey`" case is gone; a write
`where` never replaces an earlier one, which would widen it silently. `ModelDelete` has the same stages without the
assignments, `keepVersion` and `expectVersion`. `Assignment<M,C>` is a sealed interface of three records (value, null,
expression) built by `Assignment.of` and `Assignment.ofNull`; `Changes<M>` exposes `assignments()`, `isSet`, `unset`
and `isEmpty`, and its values are model values: the engine applies converters once (D-37). `MQ1604` is a column whose
`table()` is not the root. → `api/14` §2, R-WRT-16, AC-WRT-11, `processor/31` §6, `reference/90`.

**D-61 — Metamodel checks run on a definition's first execution.** `build()` sees no metamodel (INV-7), and reading
annotations would miss `orm.xml` and `@EmbeddedId` mappings. `jpa` checks each write definition once per
`EntityManagerFactory`, before any statement and before the flush: `MQ1608`, the metamodel parts of `MQ1605`, `MQ1606`
for a root with no `@Version`, the `expectVersion` value's type and a `@Version` of a type a bulk update cannot
increment (unless `keepVersion()`), and `MQ1001` for a to-one column whose type is not the target's id type. A
failure is a `ModelQueryDefinitionException`.
The memo is keyed per factory, not static. The processor reports `MQ3306` earlier where it can. → `api/14` R-WRT-08,
R-WRT-13, R-WRT-16.

**D-62 — Where bulk-write settings live.** `PersistenceContextMode { CLEAR, KEEP }` is in `core`; a write's own
`persistenceContext(...)` wins over `ModelQueryConfig.persistenceContextMode`, which defaults to `CLEAR`. `ChunkOptions`
is a record whose size is an `OptionalInt` (as `ExportOptions`, D-53): `size(n)` sets it, `defaultSize()` leaves it to
`ModelQueryConfig.bulkWriteChunkSize` (default 1000); it also carries `commitEachChunk` and `lockKeys`,
so `lockKeys` is reachable only through `chunked`; `startAfter` is on the builder's `chunked` stage instead (D-68).
`ChunkTransactions` is in `jpa`, set by `ModelQueryConfig.chunkTransactions(...)`, and gains `default void checkServes(EntityManagerFactory)` so `MQ4004` is
thrown before the flush. The executor captures these at construction (INV-8, D-56). Clearing and eviction run in a
`finally`. → `api/14` R-WRT-19, `integration/50` R-SPR-10, R-SPR-11.

**D-63 — Key-first and chunked writes share one keyset loop.** `Keyset.ofKey(PrimaryKey)` carries the ordered column
count and a label instead of a `ModelQuery`; `keyIn`, `keyOf` and the IN-list clamp move to a `Keys` helper with a pure
`clamp(int ownBinds, int keyColumns, OptionalInt configured)` that counts the write statement's own binds, SET values
included. Rendering stays in `core` (`buildWrite` with a whole-tree or root-terms shape, `buildKeySelect`,
`readsTargetInSubquery()`); `jpa` adds the vendor and inheritance checks. Each round selects keys with the write's own
predicate, then writes `pk IN (…)` with the tree or root terms; key-first without `chunked` is the same loop at the
vendor clamp. The loop stops on the number of keys selected, not rows affected, and a round that repeats a key throws,
as R-PAG-14 does; termination relies on `MQ1605`. `whereKeys` deduplicates and converts its keys, then splits them by
the clamp; the keys never enter the filter tree, so `MQ1306` can't fire. Each distinct key is written once.
`startAfter`, the last committed key and `inDoubtKeys()` are model keys (`K`, the type `whereKey` takes). `MQ1608` is
checked before the `EXISTS` correlation. → `api/14` R-WRT-08, §5, `engine/21` R-PAG-07.

**D-64 — M6.1 builder details.** `ModelUpdate.builder` and `ModelDelete.builder` refuse a join root with `MQ1203`, as
`ModelQuery.builder` does, so `MQ1604`'s root comparison can't be bypassed through a self-referencing join.
`notIn(col, List.of())` is an explicit predicate matching every row, not "no predicate", so it never throws `MQ1601`.
`MQ1602` and `MQ1605` compare attribute names, so two hand-written columns over one attribute count as assigned twice.
`expectVersion` comes before `keepVersion`, which returns the plain `Options` stage. `ChunkOptions` keeps the spec's
`commitEachChunk()`/`lockKeys()` and names its components `commitsEachChunk`/`locksKeys`; a bad size throws
`MQ2001`. `startAfter` is typed in M6.5, on a stage that knows `K`. `M6` joins the audit's started scope at M6.11, as
`M5` did at its last slice. → `api/14` R-WRT-12, R-WRT-16, R-WRT-17.

**D-65 — M6.2 write rendering surface.** `ModelUpdate` and `ModelDelete` gain the engine-facing methods `jpa` calls,
as `ModelQuery` has `buildQuery` and `checkPhases`: `checkMetamodel(Metamodel)` (the D-61 checks, memoised by the
executor per factory, weakly on both levels), `writesNothing()` (R-WRT-07, R-WRT-12), and `buildWrite(cb, options)`,
which on `ModelUpdate` also takes a `BiFunction<Class<?>, Object, ?>` the executor makes `EntityManager#getReference`,
so `core` binds a to-one by id without touching an `EntityManager` (R-WRT-14); `ModelUpdate.expectedVersion()` lets
the executor throw `OptimisticLockException`. Whether the tree needs a join is learned by rendering it into the
`EXISTS` sub-query first: a filter is a lambda with no structure to walk, and a tree that made no join renders again on
the statement's root. A tree that made a join renders whole in the `EXISTS` even when no predicate is left, since an
INNER join narrows the read too. A numeric `@Version` renders `version + 1`; a timestamp one binds the JVM's current
time. The TCK's `orders` gains a `version` column. → `api/14` R-WRT-10, R-WRT-14, R-WRT-16, D-61, D-63.

**D-66 — M6.3 key splitting and persistence-context surface.** `ModelUpdate` and `ModelDelete` gain
`distinctKeys()` (the keys of `whereKey`/`whereKeys` converted to attribute values, a list per composite key,
deduplicated in first-seen order, so two model keys a converter maps to one value are one key), a `buildWrite`
overload taking a non-empty run of those keys, and `persistenceContext()`. The executor counts a write's own binds from
the statement rendered over its first key, less that key's binds, so `SET` values and a bound `version + 1` count
(`Keys.clamp`), and renders one statement per run. `ModelQueryConfig.persistenceContextMode(...)` holds the default.
`MQ2501` is checked after the metamodel checks and before the no-op shortcut, so a `whereKeys` with no key outside a
transaction still throws. The flush runs only for a write that runs a statement and stays outside the `finally`;
the clear and the eviction run in it, so a failed statement leaves no stale entity. → `api/14` R-WRT-08, R-WRT-15,
R-WRT-18, D-62, D-63. `build()` does the key conversion, the null check and the deduplication once, and stores the
result with each composite key copied, so `distinctKeys()` and `startAfter()` return stored values and a caller's list
changed after `build()` leaves the definition alone (INV-9); a wrong arity or a null key fails at `build()`.

**D-67 — M6.4 key-first surface.** `ModelUpdate` and `ModelDelete` gain `readsTargetInSubquery(cb, options)` (now
`entitiesReadInSubquery`, D-109),
`buildKeySelect(cb, options)` and an overload over a run of `distinctKeys()`, a `buildWrite` overload over the keys a
key select chose with a `boolean rootTermsOnly` shape, `primaryKey()` and `chunkOptions()`. The key select is a
`BuiltQuery` whose `map` throws, since it maps no model. A top-level `where` term is a root term when, rendered alone
into a scratch sub-query, it makes no join and no `exists`; `JoinContext` records the types each `exists` joins, and
an `exists` reads the target when one of them shares the root's type hierarchy. The metamodel shows a root's
hierarchy but not its strategy, so `jpa` treats every hierarchy as `SINGLE_TABLE` or `JOINED`: key-first is always
correct. The clamp counts the binds of the whole-tree statement, an upper bound of both the root-terms write and the
key select. A key-first `whereKeys` write selects once per run of its distinct keys, with no cursor. A key select that
repeats a key of the round before throws `MQ2205`, as R-PAG-14 does. `lockKeys()` applies on the key-first path now;
`chunked` sizes and chunking where the database could write in one statement are M6.5's. → `api/14` R-WRT-11,
R-WRT-17, `vendor/40` R-VND-11, D-63.

**D-68 — M6.5 chunked surface.** `startAfter` is not a `ChunkOptions` component, which cannot know `K` (D-64): a
write that chose its rows by `where` or `all()` reaches a `Resumable` options stage, whose `chunked(ChunkOptions, K
startAfter)` overload takes it and whose other options return `Resumable`, so the order of the options is free. A
`whereKey` or `whereKeys` write gets none, since it writes its keys in runs in the order given, not in key order; it
may still `commitEachChunk()`, and its `lastCommittedKey()` is the last key of the last committed run as that run's key
select returned it. Every chunked write runs the M6.4 loop, on a vendor that could write in one statement too,
re-applying the whole tree there and the root terms where the write runs key-first; a round takes the options' size,
else `bulkWriteChunkSize`, within `Keys.clamp` of the whole-tree statement's binds. Each `commitEachChunk()` round, key
select and write, runs in one `inNewTransaction`: queries are built with the caller's `CriteriaBuilder` and created on
the chunk's `EntityManager`, whose `getReference` binds a to-one by id. A round whose callback throws after the round
itself returned is in doubt, its commit having failed; one that threw inside it rolled back. Any exception of a round,
`MQ2205` included, becomes `MQ2502`. `ChunkedWriteException` extends `ModelQueryExecutionException` and holds its keys
as `Object`, since an exception cannot be generic; `ModelUpdate` and `ModelDelete` gain `startAfter()` and
`modelKey(Object)` for the executor. `MQ4004` is checked where `MQ2501` is, after the metamodel checks and before the
no-op shortcut; a `checkServes` that throws `MQ4004` itself passes through, and anything else it throws becomes the
cause of one, through a new `ModelQueryConfigurationException(code, detail, cause)`. → `api/14` R-WRT-17, R-WRT-19,
R-WRT-20, D-62, D-64.

**D-69 — M6.6 generated update models.** `@UpdateModel` has `root` and `prefix` only; the `-Amodelquery.prefix` and
`-Amodelquery.suffix` options name its class as they name a query model's, and a change set is always `<Model>Changes`,
neither prefixed nor suffixed. An update model's class holds `ROOT`, its filter joins and its column constants,
`changes()`, `update(changes)` and `delete()`, naming its key in place: no `KEY`, column set, `MAPPER` or `query()`,
since it is never read into or instantiated, so neither `MQ3008` nor `MQ3009` applies and only `ROOT` is a reserved
constant. A change set holds each field boxed, so a primitive field can be set to NULL; `isSet` and `unset` match a
column by `equals`, a column the change set does not write is never set and unsetting it does nothing, and `unset`
also drops the held value. `from(...)` reads a class model through Lombok-named getters (`isX` for a primitive
`boolean`). A query model gets `delete()` when its key attributes are exactly the root's `@Id`, `@IdClass` attributes,
`@EmbeddedId` or that id's components, the comparison `MQ1608` makes, and `changes()` and `update(...)` only when it
has a key. The processor's tests bind a change set with Jackson in test scope. → `processor/30` R-PROC-04, R-PROC-18,
`processor/31` R-GEN-19..R-GEN-22, `api/14` R-WRT-03.

**D-70 — M6.7 update-model diagnostics.** `MQ3301`–`MQ3307` are live. On an `@UpdateModel`, `@Join`, `@Aggregate` and
`@GroupBy` raise `MQ3302` and take no other check, and a keyless model is `MQ3004` even with an `@Aggregate`.
`MQ3303`, `MQ3304` and `MQ3305` are update-model checks only: a `generateChanges` query model reads a `@Version` or an
`updatable = false` column as any query model does, and `MQ1605` guards it at run time. `MQ3304`'s `updatable = false`
is read from `@Column` and `@JoinColumn`, not from `@AttributeOverride`. `MQ3305` compares the boxed field type with
the target's id type as `getReference` takes it (the `@IdClass` for a composite id), as `MQ1001` does at run time; a
converted to-one keeps the `MQ3014` check. `MQ3306` applies to both kinds of model with a change set, and to a keyless
`generateChanges` summary model. `MQ3307` names the clashes a generated change set can have: a field named `isEmpty`,
`isSet`, `unset` or `assignments`, whose fluent setter overloads that member, and a field named `empty`, whose
`getEmpty()`/`setEmpty(...)` pair names the property `Changes.isEmpty()` reads as; the change set has no `isX`
getter (D-69), so `processor/32`'s example names the pair. → `processor/32` §1, `processor/31` R-GEN-19..R-GEN-23.

**D-71 — M6.8 `@ValidChanges`.** A change set's validator finds each set column's model field through a new public
`ColumnField.property()`, an `Optional<String>` of the name `named(...)` gave it, which is not `name()`: `customerId`
for the attribute `customer`, `city` for `address.city`. A column with no property, which only a hand-written change
set has, and an expression assignment are not checked; a NULL assignment is checked as `null`. `@ValidChanges` and
`ValidChangesValidator` are `@Incubating` public types of `com.rey.modelquery.jpa`; the annotation's `groups` are the
groups the field constraints are checked in, and its `message` is never reported, since every violation is re-reported
on its field. The validator checks fields with a `Validator` of the default `ValidatorFactory`, built once on first
use, so a model module needs no configuration; wiring a container's own `Validator` in is left to the Spring starter.
The processor emits the annotation when `Elements.getTypeElement` finds both `com.rey.modelquery.jpa.ValidChanges` and
`jakarta.validation.Constraint`. Within `jpa`, only the `ValidChanges*` classes import `jakarta.validation`, which
ArchUnit checks. `hibernate-validator` and `tomcat-embed-el` are test-scope only, in `jpa` and `processor`.
→ `api/14` R-WRT-21, R-WRT-22, `processor/31` R-GEN-23, `delivery/61` R-REL-03.

**D-72 — Engine-facing members.** Members of public `core` types that only an executor calls carry `@EngineFacing`
(`core`, class retention, methods only; D-86 lets it mark a type too): `ModelQuery.buildQuery`, `checkPhases`,
`checkFetch`, `checkReturning` and `mapReturning` (D-118), and on `ModelUpdate` and `ModelDelete` `checkMetamodel`,
`writesNothing`, every `buildWrite` and `buildKeySelect` overload, `readsTargetInSubquery`, `distinctKeys`,
`startAfter`, `modelKey` and `buildEntityLoad`, on `ModelUpdate` `assignedAttributes` and `assignedValues`, and on
`ModelInsert` `conflictUpdateAdds` (D-118), `ColumnField.identityPath` (R-PAG-19) and `TreatChecks.reachesTreat`.
Like `jpa.vendor` (R-REL-10) they may change in any release; `japicmp`
excludes them. This makes D-67's `readsTargetInSubquery` boolean (now `entitiesReadInSubquery`, D-109) non-API. →
`delivery/61` R-REL-10, R-REL-11.

**D-73 — `lastCommittedKey()` of a keyed write (amends D-68).** For a `whereKey`/`whereKeys` write,
`lastCommittedKey()` is the last key of the last committed run in the order given, after deduplication. Every key up
to and including it, in that order, was written or matched no row. A run's key select has no `ORDER BY`, so the order
it returned is not one a caller can resume in, and a run that matched nothing still passed its keys. The `where` path
keeps the keyset order. → `api/14` R-WRT-20.

**D-74 — What `MQ4006` refuses.** `MQ4006` refuses dropped application intent: an application-defined
`VendorProfile` or `ChunkTransactions` bean that the application's own `ModelQueryConfig` does not hold, or a set
`modelquery.*` property. The starter's own default `ChunkTransactions` is not counted, and such a config's
`commitEachChunk()` writes throw `MQ4004` before any statement. → `integration/50` R-SPR-11, R-SPR-13, D-54.

**D-75 — `Incubating` lives in `annotations`.** `Incubating` lives in `annotations`, so `@UpdateModel` and
`generateChanges` can carry it and japicmp excludes them like every other incubating member. `core` depends on
`annotations`, as INV-7's order already allows; R-REL-03 lets `core` import it. The annotation has CLASS retention,
so users never reference it. → `delivery/61` R-REL-03, R-REL-07, D-59.

**D-76 — The docs site in 0.1.** 0.1 ships an MkDocs source tree under `docs/site` holding the user guide and the
vendor notes page. CI builds it in strict mode on every PR, so a broken link or a missing page fails the build, but
nothing deploys it: publishing the site (GitHub Pages or elsewhere) is what grows later. The samples keep the names on
disk, `samples/plain-jpa` and `samples/spring-boot`; the Spring Boot sample covers several datasources and the PATCH
endpoint. → `delivery/61` R-REL-02, R-REL-13, `delivery/62` M7, SPEC.md §4.

**D-77 — Artifact coordinates (resolves Q-1).** The artifacts keep the `model-query-` prefix under
`io.github.rey5137`: `model-query-core`, `model-query-jpa`, `model-query-bom` and so on. Once 0.1.0 is published, a
rename would need relocation POMs for every published module, and a shorter prefix buys little. → `delivery/61`
R-REL-09.

**D-78 — Minimum Hibernate version (resolves Q-3).** 0.1 supports Hibernate 6.6 and later, built and tested against
Hibernate 6.6 with Spring Boot 3.4. Targeting Hibernate 7 only would exclude Spring Boot 3.x users; whether 1.0 moves to
Hibernate 7 is decided before 1.0, after a Hibernate 7 CI leg (D-91). → `delivery/61`.

**D-79 — Publishing the docs site (amends D-76).** After 0.1.0, the docs site is published to GitHub Pages at
`https://rey5137.github.io/model-query/` from `main` by `.github/workflows/docs.yml`, so it tracks the latest main rather
than a release; versioned docs per release grow later. CI still builds it strictly on every PR. → `delivery/61` R-REL-02.

**D-80 — A statement over the bind limit is refused; key chunks are powers of two (issue #6).** R-FLT-09 splits a long
`IN` list within one statement only, by `maxInListSize()`. A query's own statement (`list`, `page`, `count`, `stream`,
step 1, an export page, a write) whose JPA-reported parameters exceed `maxBindParameters()` throws `MQ1307` before it
runs, while one filter's list over the limit keeps `MQ1306`, which names the column. Only library-built key lists, the
primary-key-first step-2 batch and the bulk-write key chunks, are split across statements; their clamp is the largest
power of two within the IN-list and bind budget, so a provider's IN-list padding cannot pass it, and a configured batch
or chunk size below it is kept, since padding it stays within that power of two. Rejected: splitting a user's statement
across statements (its rows would be merged in memory, and its order, limit and count would break); counting binds in
`core` as the tree renders (a `QueryCustomizer`'s binds are invisible there). → `api/12` R-FLT-09, `api/14` R-WRT-08,
`engine/21` R-PAG-07, `vendor/41` R-PRF-11, AC-PRF-03.

**D-81 — MariaDB Tier 2 moves after 1.0.** M8 ships no MariaDB profile, containers or vendor-notes page; MariaDB stays
the next Tier 2 vendor. A profile is additive, so adding it after the API freeze breaks no one. → `delivery/62` §1,
`vendor/41` §1.

**D-82 — A keyset statement is refused up front when its cursor could pass the bind limit; `MQ1307` names the binds.**
A keyset page, an export page or a key-first round that starts after a cursor binds the cursor's values on top of the
query's own, so a query whose own binds sit within a few of `maxBindParameters()` could pass its first page or round and
be refused on a later one (after rows reached a sink, or a `commitEachChunk` round committed). Amended at the M8 gate: a
keyset statement (keyset page, export page, key-first write round, including the first page or round and a `startAfter`
round) is refused before it runs with `MQ1307` when its own binds plus the worst cursor, k(k+1)/2 binds for k keyset
keys (all non-null), exceed `maxBindParameters()`, so a run never fails after rows reached a sink or a round committed.
The refusal says how many binds the statement has of its own and how many the cursor can add, and that the query's own
filters must drop at least that many (or use fewer keyset columns). Rejected: lowering every clamp by the key's column
count, which shrinks every chunk for a case only a query at the limit reaches; the check does not do that, it refuses
only a query already that close to the limit. → `api/12` R-FLT-09, `engine/21` R-PAG-07, D-80.

**D-83 — The factory bean swap re-registers each definition instead of mutating it.** A repository whose type another
post-processor checked before the swap ran (the JPA repositories auto-configuration's missing-bean scan, a framework)
has a merged definition and an early `JpaRepositoryFactoryBean` instance cached, and changing the registered
definition's class name reaches neither: the repository was built without the `ModelQueryRepository` fragment and
startup failed with `No property 'findPage' found`. The swap copies each definition with
`ModelQueryRepositoryFactoryBean`, removes it and registers the copy under the same name, which drops both caches. A
`RootBeanDefinition` (Spring Data 3.x registers one with a `targetType` of `JpaRepositoryFactoryBean<Repo, S, ID>`) is
copied with `cloneBeanDefinition()` and keeps its `targetType`, now `ModelQueryRepositoryFactoryBean` over the old
generics; any other definition is copied as a `GenericBeanDefinition`. A swapped definition moves to the end of the
registration order, which the registry API cannot prevent: it changes the singleton creation order and the order of an
injected `List<Repository>`.
Rejected: mutating in place (the cached merged definition and early instance survive); making the swap
`PriorityOrdered` alone (another post-processor or a framework can still type-check first). → `integration/50` R-SPR-02,
AC-SPR-13, D-50.

**D-84 — Ordered converters; built-in `Instant` and `Date` over a `Timestamp` attribute.** An
`OrderedColumnConverter<C, F>` promises `a < b` exactly when `toModel(a) < toModel(b)`, and the same for `toAttribute`:
it preserves order both ways, so it is also injective. For such a column the database's `min`, `max` and
`countDistinct` over `F`, with `toModel` applied to the result, equal the same aggregate over the model values, so
`Agg` accepts them; `sum` and `avg` keep `MQ1408`, as does every aggregate over a converter that is not ordered
(D-93 moves the `min`, `max` and `countDistinct` part of that restriction to compile time).
`core` ships two ordered converters: `Instant`↔`Timestamp` (`Timestamp.from`/`toInstant`, nanoseconds kept) and
`Date`↔`Timestamp`, whose `toModel` returns the `Timestamp` itself typed as `Date`, so no sub-millisecond digits are lost
when a value is read. A plain `java.util.Date` bound in a filter has whole milliseconds, so an inclusive upper bound is
written half-open, `lt(nextDayStart)`: `lte(23:59:59.999)` excludes a stored `23:59:59.999500`. A value one converter
cannot represent (an `Instant` beyond `Timestamp`'s range) is refused with `MQ1308`; the `Instant` converter round-trips
the converted value, since on JDK 21 `Timestamp.from(Instant.MAX)` returns a wrong instant without failing. The processor uses one when a model field's
type and its `Timestamp` attribute form one of these pairs and no `converter` is named. `LocalDateTime` is not a
built-in: through the JVM time zone it is not order-preserving across a daylight-saving change. Range filters and
`orderBy` on a converter that is not ordered stay allowed: they compare stored values, which is well defined, and keyset
cursors read `Row.raw`, so no row is lost (D-37). Rejected: `Date` filter overloads (permanent API for one type family,
and a `Date` bound against a `java.sql.Date` truncates); loosening the filter generics (any value type compiles); a
`default boolean ordered()` on `ColumnConverter` (the processor cannot see it at compile time); refusing range filters
and `orderBy` on unordered converters (breaks 0.1 users who sort by a stored code). → `api/10` R-COL-14, `api/13`
R-AGG-04, `processor/30` R-PROC-07, R-PROC-15, `reference/90` `MQ1408`, `MQ1308`, D-20, D-37, D-93.

**D-85 — The planned 1.0 freeze, by type.** In 0.2 every type stays `@Incubating` (D-90); the split below is what 1.0
will freeze. At 1.0 every annotation is frozen except `UpdateModel` and
`QueryModel.generateChanges`. In `core` every public type, `ColumnField` and its subclass `OrderedColumnField` (D-93)
among them, is frozen except the bulk-write types (`ModelUpdate`,
`ModelDelete`, `Changes`, `Assignment`, `ChunkOptions`, `ChunkedWriteException`, `PersistenceContextMode`) and
`NullPrecedenceRenderer`, which the incubating SPI returns. In `jpa`, `ModelQueryExecutor`, `ModelQueryConfig`,
`KeysetNullKeys`, `MysqlStreamingMode` and `DatabaseVendor` are frozen and their bulk-write members stay `@Incubating`;
`VendorProfile`, `ProviderSupport`, `ChunkTransactions`, `ValidChanges`, `ValidChangesValidator` and
`HibernateProviderSupport` stay `@Incubating` (D-108, D-109, D-78). The Spring types are frozen, with
`ModelQueryRepository.update`/`delete` and the bulk-write properties `@Incubating`; the starter's property keys are API.
The generated `changes()`, `update(...)` and `delete()` carry `@Incubating`. Rule: every type generated code links
against is frozen. `Filters` and `Having` become `sealed`, so a new operator can be an abstract method. Bulk writes
freeze in a 1.x minor once one minor ships with no change to them. Deferred as additive: a `TableField.join` taking the
target class. A common `ModelQueryException` superclass is added before 1.0 and frozen (D-106). → `delivery/61`
R-REL-07, R-REL-11, D-59.
*Amended by D-118:* `throughEntities()`, the `persist(persist, returning)` overload, `WriteAssignment`, `WriteKind`
and `ModelQueryConfig.writeAssignments` join the incubating bulk-write types.
*Amended by D-120:* `@Selected` stays `@Incubating` at 1.0, an exception to "every annotation is frozen", and so do
`SelectSet.contains` and `SelectSet.selectedIn`; `SelectSet`'s value equality freezes with it.

**D-86 — `@EngineFacing` may mark a type (amends D-72).** From 0.2 `BuiltQuery`, `RowSelection` and `RenderOptions`
carry it at type level, and `JoinContext.of` and `OrderField.toOrders` at method level; `japicmp` excludes both. A
selection's shape can then change, for example one alias per selected path, without breaking anything. → R-REL-10,
R-REL-11, D-72. The fetch-plan seams join them: the types `ChildLoad` and `JoinPlan`, and the methods
`FetchPlan.childLoads`, `joinPlans`, `enrichers` and `isSelectionOnly` and `Enricher.enrich`.

**D-87 — `or` takes two or three branches, or a list (applied in 0.2).** An interface method cannot be `@SafeVarargs`,
so the generic varargs `or` warned `unchecked generic array creation` at every call and failed under `-Werror`.
`Filters` and `Having` take `or(a, b)`, `or(a, b, c)` and `or(List)`, so a written-out `or` with fewer than two
branches does not compile (P-2); a built list follows R-FLT-01 (one branch is that branch; an empty list is `FALSE`, D-92).
Rejected: keeping varargs with a documented `@SuppressWarnings` (every caller pays). → `api/12`, `api/13`.

**D-88 — `PageSpec` and `ExportOptions` are final classes; `SetterMapper.bind` takes the mapper's model (applied in
0.2).** A record's
public canonical constructor made `new PageSpec(2, 20)` an offset while `PageSpec.of(2, 20)` is a page number, and a
frozen record cannot gain a component, while export options will grow. Both become final classes with factories:
`PageSpec.of(page, size)` and `PageSpec.ofOffset(offset, size)`; `ExportOptions` keeps `defaults()`, `of(int)` and
`withLimit`. `SetterMapper.bind` takes `SelectField<M, C>`: another model's column compiled and set `null` on every
row. → R-EXE-02, R-PAG-09, R-COL-11.

**D-89 — Ordered converters freeze; `@Aggregate.converter` waits.** `OrderedColumnConverter` and the two built-in
converters are planned to freeze at 1.0, because generated code links their `INSTANCE`.
`DateTimestampConverter.toModel` returning a `Timestamp` typed as `Date`, with its asymmetric `equals`, is part of the
contract. A `converter` element on `@Aggregate`, legal for MIN and MAX only, is additive and comes after 1.0; until
then a named ordered converter goes through a hand-written `Agg.min` or `max`. → R-COL-14, R-AGG-04, R-PROC-07, D-84.

**D-90 — M8 ships as 0.2.0; nothing is frozen yet.** The user decided against 1.0.0 for M8. The API changes of D-87
and D-88 (and the `sealed` `Filters` and `Having` of D-85) are applied in 0.2.0, but no type is frozen: `@Incubating`
stays on every public top-level type until the 1.0 freeze (the annotations, `@EngineFacing` and `ModelQueryProcessor`
included; only `@Incubating` itself is unmarked), and D-85, D-86 and D-89 describe what 1.0 will freeze. `japicmp` stays
skipped until a 1.0.0 baseline exists. → `delivery/61` R-REL-07, `delivery/62`.

**D-91 — Decisions closing M8.** (1) Q-2 is resolved: `row-by-row` stays the MySQL streaming default, because
`useCursorFetch` streams only when the user adds `useCursorFetch=true` to the JDBC URL and otherwise silently loads the
whole result into memory. (2) D-78 stands: 0.2 keeps Hibernate 6.6+, and a Hibernate 7 CI leg comes before the 1.0
decision. (3) `or(List)` with fewer than two branches follows R-FLT-01 (one branch is that branch), with no new `MQ`
code; an empty list is not skipped (D-92). (4) `@Aggregate.converter` is deferred and additive. → `vendor/41` R-PRF-04, R-PRF-07, D-78,
D-87, D-89.

**D-92 — An empty `or(List)` is "none of these" and renders `FALSE` (amends D-91(3)).** `or` over an empty list is the
empty disjunction, `FALSE`, as R-FLT-02's empty `in`; a non-empty list whose branches were all skipped stays skipped
(R-FLT-01); one branch is that branch. Rejected: skipping an empty list (an empty allowed-list returned every row,
against P-3); a new `MQ` code (callers would special-case "no permissions"). → `api/12` R-FLT-01, R-FLT-02, AC-FLT-03,
`api/13` AC-AGG-07, D-87, D-91.

**D-93 — `Agg.min`, `max` and `countDistinct` take `OrderedColumnField` (supersedes the run-time part of D-84).**
`ColumnField` becomes `sealed`, with one final subclass, `OrderedColumnField`: a column with no converter or with an
`OrderedColumnConverter`. `ColumnField.of` without a converter, or with an ordered one, returns it (also at run time
through a wider static `ColumnConverter` type); a column with any other converter is a plain `ColumnField`, so `min`,
`max` and `countDistinct` over it do not compile (P-2) rather than throw `MQ1408`. The processor declares each
generated column with the narrower type, following nested-model joins. `equals` ignores the subclass. `MQ1408` stays
for `sum`, `sumAsLong` and `avg` over any converted column, and its text no longer suggests an ordered converter for
`min` or `max`. `OrderedColumnField` is on the D-85 1.0 freeze list next to `ColumnField`. Rejected: a converter type
parameter on `ColumnField` (every user-facing `ColumnField` type changes); keeping the run-time `MQ1408` (P-2).
→ `api/10` R-COL-14, `api/13` R-AGG-04, `processor/31`, `reference/90` `MQ1408`, D-84, D-85.

**D-94 — `ColumnSet` is `SelectSet`, and what a query selects is `select`.** The set holds `SelectField`s, aggregates
included, so a name after `ColumnField` misread it. The user chose `SelectSet` and folded the rename into 0.2.0, which
already breaks the API, over a second break in 0.3. `SelectSet.columns()` is `fields()`;
`ModelQuery.Builder.columns(...)` and `ModelQuery.columns()` are `select(...)` and `select()`;
`@QueryModel(generateColumnSets)` is `generateSelectSets`; `MQ1202` names `select(...)`. `groupBy(SelectSet)`, the generated constant names, `PrimaryKey.columns()` and the
`columns` parameter of a generated `from(model, columns)` stay. Rejected: `SelectFieldSet` (longer for no gain),
`SelectFields` (a plural type name). → `api/10` §4, `api/11` R-QRY-02, `processor/30`, `reference/90` `MQ1202`, D-90.

**D-95 — Debug and trace logging through `System.Logger`.** `ModelQuery.Builder.build()` logs the definition it built at
`DEBUG`: the model and entity, the selected, key, group and order fields by name, the `where` and `having` conditions
with each value as `?` (R-INS-05, D-101), and the paging mode. An `orderedBy` copy is not logged, since it is built per
call. The executor logs each `list`, `stream`, `page`, `count`, `export`, `update` and `delete` call at `DEBUG`; an
`update` or `delete` names how it chose its rows (its number of keys, never a key, or `all()`) and its `where`
conditions the same way. At `TRACE` it logs each statement's bind count against the profile's limit, and its rows read
or written and time taken. No filter value, write key, bind value or keyset cursor is logged at any level: they are
often personal data, and the provider's own bind logging shows them when needed. The statement text is not logged
either, since JPA has no portable way to render a criteria query; the provider's SQL log shows it. Every message is
built only when its level is enabled. Rejected: logging bind values at `TRACE` (personal data in application logs).
Spring Boot's default `spring-boot-starter-logging` carries the records to Logback through `jul-to-slf4j` and keeps the
JUL levels in step with Logback's (`LevelChangePropagator`), refreshes included; an application that excludes it adds
`slf4j-jdk-platform-logging`. Also rejected: logging through SLF4J when present (an optional dependency and a facade,
only for applications that drop the default bridge), and a starter listener copying Logback's levels to JUL (no use
without a bridge, and Spring Boot already does it with one).
→ `docs/site` Diagnostics §Logging.

**D-96 — Fetch plans (resolves Q-8).** A `FetchPlan<M>` attached by
`ModelQuery.Builder.fetch` carries the selection, children loaded by the library (`@Child`, matched on one column
each side, `List` or `Optional`), plans for `@Join`ed models, and caller enrichers run once per page. Children load per
page in key rounds within the vendor's limits (R-PAG-07), recursively, so plans nest to any depth; enrichers are the
caller's code for anything else, and the library never fills a `@Transient` field. The plan lives on the query, so the
executor and repository signatures (`ModelPage`, `findAll`, `export`) do not change. Rejected: per-call overloads taking
a page hook (every method doubled, and plans could not nest); children loaded per row (N+1, INV-2); `stream` running a
plan (it has no page). Keys match on attribute values, as R-COL-11 does for primary keys, and a child row matching no
key throws, since collations can equate unequal strings. A nested plan's selection is re-rooted under its join, so one
plan serves on its own and nested. A plan also attaches per call through `withFetch`, a new definition. Two invariants
widen: columns a plan needs are selected as R-QRY-04's keys are (INV-2), and INV-4's page includes its children,
bounded per round by `maxPerParent`. Also rejected: matching on model values (converters and collations lose rows
silently); requiring a nested plan's selection to be empty (plans could not be reused). A plan column read through a
to-many join (`MQ1702`) is found on first execution, once per query as D-21's phase check is, since `build()` has no
metamodel; `count` only warns of it, since it reads no plan column into a model. A join plan selecting an aggregate
(`MQ1705`) is refused at `build()`. `fetch` and `select` replace each other whole, so `select` after `fetch` drops the
plan, with a warning. `withFetch` remembers each (query, plan) pair
that passed in a static weak set rather than on the query (CC-IMM-01). Also rejected: keeping the plan when `select`
follows `fetch` (two sources of one selection), and caching a checked copy on the query (a lazily filled field on a
`static final` constant). → `api/15`.

**D-97 — One starter dependency.** A Spring Boot application declared the starter, `model-query-hibernate`,
`model-query-annotations`, `spring-boot-autoconfigure`, Spring Data JPA and Hibernate itself, because the starter held
Spring as `provided`. `model-query-spring-boot-starter` now depends on `spring-boot-starter-data-jpa` and on
`model-query-hibernate`, so the starter alone is enough; Hibernate is Spring Boot's default JPA provider, and
`model-query-hibernate` keeps `hibernate-core` `provided`, so it adds nothing Hibernate-specific off the starter. A
`ProviderSupport` whose provider library is missing fails to link; the resolver skips it, so the support stays inert
without Hibernate. `model-query-annotations` is needed only in a module without the starter, and the processor stays
in `annotationProcessorPaths`. INV-7 already puts the starter above `hibernate`. Rejected: keeping Spring `provided`
(every user declared four or more dependencies); pulling the processor through the starter (Maven cannot add an
annotation processor through a dependency). → `integration/50`.

**D-98 — Query inspection and a test-support module.** Unit tests that cannot run a database need to check which
filters a request became, and `Filters` is sealed with each filter held as an opaque lambda, so nothing can be mocked
or read back. Each filter now records a `Condition` (operator, column, values, nested conditions) next to its
predicate, `ModelQuery.conditions()` exposes the tree read-only, and a new `model-query-test` module (core and AssertJ
only) asserts on it with matchers named after the `Filters` operators. A skipped filter records nothing, so the view
matches the statement. `toString` and the D-95 log show `?` for values, which can be personal data. `add(label, …)`
names an otherwise opaque custom filter. Rejected: a mockable
`Filters` (unsealing it opens the DSL to implementations the engine cannot render); asserting on a rendered JPQL or
SQL string (needs a provider and a metamodel, and breaks on any rendering change); a recording `Filters` only inside
the test module (it would duplicate every operator's skipping rules and drift from them). INV-7 widens: `test`
depends only on `core`. All new types `@Incubating`. → `api/16`.

**D-99 — Many-to-many children.** Report lists show children reached through a `@ManyToMany`, often unidirectional
(the target has no collection back), sometimes with the join table mapped as an entity. Three shapes, all supported:
a child model rooted on the join-table entity needs nothing new; a `foreignKey` may cross a collection, so a mapping
seen from the child's side works, while `key` may not (the parent would get a row per element, `MQ3402`); and
`@Child(through = "path")` roots the child query at the parent's entity, joins along the path, and re-roots the child
model's columns, joins, filters and order under that join, as join plans already re-root (R-FCH-07). Child rows are
deduplicated per (parent key, child primary key) instead of per child primary key, since one child can belong to
several parents. `through` excludes `foreignKey`; a bad `through` path is `MQ3406`. Rejected: a model rooted on the
join table only (forces a join entity on mappings that have none); refusing collections in `foreignKey` (the
bidirectional case is free once dedupe is per parent). The re-rooting of `ChildQuery` filters under `through` goes to
an `architect-review` before M8.15b. `@Incubating`. → `api/15` R-FCH-03, -04, -14.

**D-100 — Re-rooting a `through` child.** A `through` child query resolves the child model against a second
`JoinContext` whose root is the `through` join, instead of re-rooting each column, filter and sort with `under`: filters
are closures and `add(...)` is caller code, so only rebinding the context re-roots every kind, `exists` included (it
correlates the join). `ChildField` carries `through` as a generated INNER `TableField` chain, not a string, and
`ChildLoad` builds the child query in core (INV-7). `key` must be the parent root's `@Id`, since a non-unique key would
merge two parents' children silently; a grouped child model is refused, since the parent key would need a GROUP BY.
Rejected: `under`-rewritten filters (impossible for closures and `add`); a second root matched in WHERE (an extra
self-join, and comma joins mixed with JOIN/ON differ by vendor, INV-6). `@Incubating`. → `api/15` R-FCH-06, -14,
`processor/32` `MQ3406`.

**D-101 — The shape of a recorded condition.** One final `Condition` class with a `Kind` enum and optional accessors
(`column`, `right`, `op`, `likeMode`, `path`, `values`, `children`, `label`), and a final `QueryConditions` holding
`where` and `having`, neither a record and neither publicly constructible. An `or` branch of two or more filters is an
`AND` node, so `or(a.and(b), c)` and `or(a, b, c)` differ; a one-sided `range` or `between` records the comparison it
renders; values are recorded as passed, before converters. Order stays on `orderBy()`, so `orderedBy` copies share the
view; a fetch plan's child-query filters are out (Q-13), since `ChildLoad` is `@EngineFacing`. Recording goes through
the single point that records a predicate, which then takes both, so the compiler refuses a predicate without its
condition. Matchers live in `model-query-test` and take value forms only. `toString` and the build log show `?` for
values; `ModelQuery.toString()` stays the model's name, which failure messages use. Rejected: sealed per-kind types (no
record patterns or pattern `switch` on Java 17, and their exhaustiveness breaks like an enum's); string kinds (P-2);
public `Condition` factories (they freeze construction); a flat `children()` for `or` (ambiguous). `@Incubating`. →
`api/16` §1, R-INS-01–07. Amended by D-103.

**D-102 — `Enricher.of` is positional.** A plan serves a query alone and nested (R-FCH-07), and a nested plan's models
are put back into their parents by position, so `of` returns one model per position, in the page's order: position `i`
replaces position `i`, and a wrong size or a `null` element is `MQ2602`. Rejected: matching by identity (a record's
`with` returns a copy, so the result is never the page's instance); matching by key (a model need not expose one). A
possible 1.0 change is an API that cannot reorder: a values function plus a `with` `BiFunction` the engine applies
itself, as `byKey` does. `@Incubating`. → `api/15` R-FCH-07, -08.

**D-103 — Condition paths compare by key; `QueryAssert<M>` (amends D-101).** `TableField` has `equals` and `hashCode` by
its join key (the parent path, the attribute and the join type, R-INS-04 "paths by their key", CC-IMM-04), so two
`TableField`s that differ only in `on` are equal and a matcher compares a path with `Objects.equals`, not by its text:
a join of the same attribute under another parent no longer matches. `TableField.toString` is path-qualified
(`Order.customer.address (INNER)`) and is not API. `QueryAssert` is typed by the query's model, `QueryAssert<M>`, so
`isOrderedBy` and the selection methods refuse a column of another model; `ConditionMatcher` stays non-generic.
`@Incubating`. → `api/16` R-INS-04, R-INS-06.

**D-104 — Child queries are inspected through `model-query-test` (resolves Q-13).** `assertThatQuery(q).child(field)`
returns an assertion over that child's query: its conditions, order, selection and `maxPerParent`, with the same
matchers as the query's own. `model-query-test` reads them through `FetchPlan.childLoads()`, which stays
`@EngineFacing`, so core gains no public view of a plan's children to freeze. A field the plan doesn't load fails the
assertion with the fields it does load. Rejected: a public read-only `FetchPlan.children()` view (new frozen surface in
core for a need only tests have); testing child queries only against a database (a plan's filters are as easy to get
wrong as a query's). `@Incubating`. → `api/16` R-INS-06, `api/15`.

**D-105 — A keyset page with an opaque cursor, both ways, before 1.0 (resolves Q-4).** One method,
`ModelQueryExecutor.page(query, KeysetSpec)`, with `KeysetSpec.first(size)`, `after(cursor, size)` and
`before(cursor, size)`, returns a `KeysetSlice<M>`: `content()`, `hasNext()`, `hasPrevious()`, `nextCursor()` and
`previousCursor()`; `ModelQueryRepository` passes it through. A cursor is an opaque URL-safe string holding the
boundary row's order-column and primary-key values and a fingerprint of the query's order, so a client pages without
reading column values, and a cursor from another order, or an edited one, fails with a new `MQ` code instead of
returning wrong rows. `before` reverses each order column and its null rule, reads `size + 1` rows to learn whether
more exist, and reverses the result, so the slice keeps the query's order. The keyset rules of `engine/21` hold
(R-PAG-04 ties, R-PAG-05 nulls, R-PAG-06 predicates); there is no total. The cursor format is not API: only its
round trip is. Rejected: `pageAfter` and `pageBefore` as two methods (twice the surface on the executor and the
repository for one choice a value carries); leaving encoding to callers (each caller re-implements typed decoding, and
a forged cursor reaches the predicate); a signed cursor (the values are the client's own page; the fingerprint catches
mistakes, and a forged value only moves the client inside rows the query already allows). The design gets an
`architect-review` before its slice. `@Incubating` until the freeze. → `engine/21` §2, `api/11`, `integration/50`,
SPEC.md §4.
*Amended by D-110:* a cursor carries the boundary row's order-column and primary-key values readably, filter-only
columns included; this is documented, not hidden.

**D-106 — `ModelQueryException`, a common superclass, before 1.0 (amends D-85).** An abstract
`ModelQueryException extends RuntimeException` holds `MqCode code()`, and `ModelQueryDefinitionException`,
`ModelQueryExecutionException` and `ModelQueryConfigurationException` extend it, so a caller catches every library
failure in one clause and reads its code. Adding it later would be binary-compatible, but a 1.0 handler written
against three types would never pick it up. Its constructors are protected; no new subclass is planned. Frozen at
1.0. *Amended at the M9 gate:* sealed over its three subclasses, which are `non-sealed`, so a foreign exception cannot
carry an `MqCode`; sealing after 1.0 would break subclasses. → `reference/90` §1, D-85.

**D-107 — A model rooted at a type generated in the same round is deferred (resolves Q-11).** The processor keeps a
model whose `root` is not yet resolvable and retries it each round; in the last round it reports a new `MQ30xx` on the
model, so no model is skipped silently. The two hidden diagnostics stay hidden: an `MQ3015` prefix clash waits for its
`@Join`'s own error and an `MQ3014` for its path to resolve, since each check needs the result the first error denies.
`processor/32` R-DIAG-03 documents both as reported once the first error is fixed. Rejected: reporting the hidden codes
in the same pass (they would be guesses over an unresolved join); documenting the skip (a missing QModel shows as a
compile error far from its cause). *Amended in M9:* the code is `MQ3017`; a model that nests one with an unresolved
`root`, through `@Join` or `@Child` at any depth, is deferred too and in the last round reports `MQ3017` on its own
element, naming the nested model; and a `root` that is not a class, `int.class` included, is deferred and ends in
`MQ3017` rather than being skipped silently. → `processor/32` R-DIAG-03.

**D-108 — The fetch-size hint moves to `ProviderSupport` (resolves Q-9).** `VendorProfile` decides the size, through
`int streamingFetchSize(int requested)` beside `checkStreamingPreconditions`, and no longer touches the `Query`;
`ProviderSupport` gains `applyFetchSize(Query, int)`, which `model-query-hibernate` implements with
`org.hibernate.fetchSize`. With no `ProviderSupport`, `jpa` passes nothing and logs once per factory at `WARN` that
`stream` may buffer the whole result. This keeps D-34's split: database facts in the profile, provider mechanics in
provider support. The R-VND-07 null-ordering warning keeps reading its Hibernate property in `jpa`, by necessity, since
it fires only where no `ProviderSupport` exists. Done before any new vendor profile is written, since it changes what
a profile implements. `@Incubating` (`jpa.spi`). → `vendor/40` §2, `vendor/41` §2, R-PRF-04, D-34.
*Amended in M9:* `streamingFetchSize` defaults to returning `requested`, as R-VND-01 asks of a new profile method.
`applyFetchSize(Query, int)` is replaced by `<T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize)`: setting a
size is not enough for every provider, since EclipseLink's `getResultStream()` is `getResultList().stream()` and
streams only through its cursor API, so the provider support opens the stream itself; a support that only set a size
would buffer silently with no `WARN`. `model-query-hibernate` sets `org.hibernate.fetchSize` and calls
`getResultStream()`; with no `ProviderSupport` the engine calls `getResultStream()` and warns, naming a
`ProviderSupport` for the provider (`model-query-hibernate` for Hibernate). `resultStream` has no default, since a
portable one would let a `ProviderSupport` that forgot it buffer every stream with no `WARN` (the warning fires only
where no `ProviderSupport` serves the factory).

**D-109 — `ProviderSupport.tableOf` detects two entities on one table (resolves Q-12).** `ProviderSupport` gains
`default Optional<String> tableOf(EntityManagerFactory emf, Class<?> entity)` (the factory added at build: a
`ProviderSupport` is shared by every factory it serves), empty by default and implemented by `model-query-hibernate`
from its mapping metamodel. When choosing key-first for a bulk write (R-WRT-11), `jpa` compares the tables of the root
and of each entity a sub-query reads, and falls back to comparing entities when either table is unknown, as today. INV-7
holds: `core` never sees a table name. Rejected: a `VendorProfile` method (table names are provider knowledge, not
database knowledge); always running key-first on MySQL (a needless second statement for every joined write). Ships in
the same SPI change as D-108. `@Incubating` (`jpa.spi`). → `api/14` R-WRT-11, `vendor/40` §2.
*Amended in M9:* `tableOf` is replaced by `default Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity)`,
every table reading the entity touches, empty when unknown, and `jpa` tests the intersection with the root's tables,
ignoring case, falling back to the entity comparison when either set is empty. One table name missed a JOINED
subclass's supertable, a `@SecondaryTable` and a table-per-class parent's subclass tables, each of which a sub-query
reads. `model-query-hibernate` reports the query spaces of the entity's persister and its subclasses', each unquoted
and qualified with the default catalog and schema when it names none, so a quoted or schema-defaulted name still
matches.

**D-110 — The keyset page design (amends D-105, M9.3a review).** D-105 holds, with its gaps closed.
`page(query, KeysetSpec)` needs a `keyset()` query (`MQ2207`); `KeysetSlice` is a final class whose cursors are
`Optional<String>`, present exactly when the matching flag is true, so a last page has no next cursor and tail-polling
is not supported in 1.0. Spring adds `findKeysetPage(q, KeysetSpec, Sort)`, named apart from the `Pageable`
`findPage`. A cursor holds the boundary row's keyset values through a closed codec set (never Java serialization),
a version byte, an 8-byte fingerprint of the order and a CRC32C, capped at 8192 characters: an edited or malformed
cursor is `MQ2208`, one from another order `MQ2209`, and a key type that cannot be carried `MQ2210`. Its values are
readable by whoever decodes it, filter-only order columns and internal primary keys included; that is documented, not
encrypted, and stays non-breaking to change because the format is not API. `before` flips each key's direction and
null precedence, and a precedence resolved from `ProviderSupport.defaultNullPrecedence` (D-36) renders explicitly, or
the reversed order would skip NULLs. The flags need no second statement, and `MQ2205` extends to a page holding the
cursor's own key. Rejected: a typed `Cursor` value; a cursor present on every non-empty page; Spring Data's
`Window`/`KeysetScrollPosition` (exposes the key values as a `Map`, R-SPR-01); a `KeysetQuery` type; always explicit
null precedence (loses the index on MySQL); an `EXISTS` probe or a top-up of a short `before` page (a second
statement each); encrypting the cursor in 1.0. → `engine/21` R-PAG-16 to R-PAG-24, `api/11`, `integration/50`
R-SPR-14, `reference/90`, SPEC.md §4, `delivery/61`.

**D-111 — Adoption before the freeze (amends D-90, M9).** A report service migrating about 35 datasource
configurations asked for nine features (the "adoption feedback"); every one is built before the API freezes. M9 ships
as 0.2.0, carrying the slices built so far and the adoption features, all `@Incubating`. The freeze review and the D-85
freeze move to M10, which starts only once that service has fully migrated onto 0.2.0 and run in production for a
period the user judges enough; tagging `v1.0.0` stays the user's step. The features are:
(1) the repository fragment working beside a custom `JpaRepositoryFactoryBean` subclass and a custom repository base
class; (2) `in`/`notIn` against a sub-query and `exists` on an unmapped root with an explicit correlation;
(3) expressions (`coalesce`, `CASE`, arithmetic, `concat`, function calls, literals) in aggregates, group keys, filters
and selected columns, reversing D-27; (4) ordering by an expression, with a stated rule for `keyset()` and
`primaryKeyFirst`; (5) a correlated `exists` whose inner predicate reads outer columns; (6) enrichers on a nested join
model, already R-FCH-01/R-FCH-08, owed a documented recipe; (7) joins through a non-key `@JoinColumn` or Hibernate
`@JoinFormula`, tested and documented through the mapped association, with no `@Join(on = …)` (R-PROC-12 holds);
(8) enrichers for a row carrying several keys (a delivery's sender, recipient, courier and an optional
approver, each a party type and id), widened by the adopter's follow-up: each key routed to its role's field, one
lookup for the distinct keys across rows and roles, chunked lookups, a lookup split by a key part (the party type,
one datasource each), the null-key skip stated, values shared with another enricher of the same fetch (a child's),
and request-time parameters; (9) `keyset()` and `primaryKeyFirst` over String and `@EmbeddedId` keys, tested
and documented. Items 2–5 and 8 get their own `architect-review` before their slices and are recorded as their own
`D-n`; this decision fixes the scope and order only. D-114 ships item 8's partitioning, shared values and
request-time parameters as tested recipes rather than library API. → `delivery/62` §1, `docs/plan/mvp-plan.md` §10–11.
*Amended by D-116:* the freeze review and the D-85 freeze move to M11, after M10 ships inserts as 0.3.0; the adopter
migrates onto 0.3.0 before it.
*Amended by D-118:* M11 ships entity writes as 0.4.0, and the freeze review and the D-85 freeze move to M12, which
waits for the adopter to run 0.4.0 in production.

**D-112 — Sub-selects and correlated `exists` (M9.11a review, D-111 items 2 and 5).** `SubSelect<S, C>` is one column
of any root with its own filters, immutable, so it can be a constant. `in`/`notIn` take it uncorrelated, with an
invariant `C`; `notIn` adds `IS NOT NULL` inside and `OR col IS NULL` outside, so a NULL value never empties a report.
`exists`/`notExists(sub, (s, outer) -> …)` correlate through `Outer<M, S>.column`, which lifts an outer-root column
into the inner vocabulary, so every operator, `or` and `not` mix inner and outer conditions. An uncorrelated `exists`
is `MQ1309`; outer columns are root-only in 1.0 (`MQ1311`), because Hibernate renders joins off a correlated root as
the sub-query's `FROM` and drops their `ON`; widening it later breaks no one. Inspection gets four `Kind` values and
`subSelect()`, and `model-query-test` a second `@EngineFacing` read, `Outer.reference`. Rejected: relaxing `MQ1302`
(silently changes R-FLT-11); per-operator outer overloads (same erasure); an outer type parameter on the sub-select
(breaks reuse as a constant); reusing the `IN`/`EXISTS` kinds (an `IN` without values reads as an empty set);
rendering `notIn` as `NOT EXISTS` (needs outer-join correlation; the guide advises `notExists` on PostgreSQL); the name
`SubQuery` (one case away from JPA's `Subquery`). → `api/12` R-FLT-12, R-FLT-15 to R-FLT-17; `api/14` R-WRT-11;
`api/16` R-INS-06, R-INS-08; `reference/90`; `delivery/61`.

**D-113 — The starter adds the fragment beside any factory bean subclass (amends D-50, D-111 item 1).** An exact
`JpaRepositoryFactoryBean` is swapped as before, and a `ModelQueryRepositoryFactoryBean` subclass is left alone. Any
other subclass whose repository extends `ModelQueryRepository` keeps its class and gets the fragment through
`customImplementation`, public and not deprecated in Spring Data 3.4 to 4.0, from a `ModelQueryRepositoryFragmentFactoryBean`
in `model-query-spring-data` (INV-7), re-registered per D-83; a definition already setting it is `MQ4008`. Rejected: a
repository proxy post-processor or factory customizer (the query-method composition is already fixed without the
fragment, so `findPage` parses as a derived query); post-processing instances (misses repositories created before the
post-processor); editing Spring Data's inner fragments definition (internal structure); `customImplementation` for the
stock case too (a behaviour change for 0.1 users); `RepositoryFragmentsContributor` (Spring Data 4.0 only, the later
route). → `integration/50` R-SPR-02, `reference/90`.

**D-114 — Enrichers over several keys (M9.11a review, D-111 item 8).**
`Enricher.<M, K, V>byKeys(lookup).key(key, with)….batchSize(n).reading(columns)` makes one lookup per run for the
distinct non-null keys across models and keys, or one per chunk of at most `n` with `batchSize`, and routes each value
through its key's setter; a null key is skipped, and `byKey` is its one-key case. A key builder (Option B) beat a
`Map<Role, K>` with a three-argument router (Option A): no new functional interface, no map per row, no role `switch`,
and a null approver cannot throw inside `Map.of`. Chunking is library API because any key lookup meets an IN-list or
API limit and multi-key pages reach it sooner; a lookup that also splits by source receives each chunk, so each source
is called at most once per chunk. Partitioning by a key part, a cache shared with a child's enricher, and request-time
parameters stay in caller code: a lookup that splits its own keys, and a plan built per call (R-FCH-13) relying on
R-FCH-17's order. Each is a tested recipe, revisited once the adopting service has run in production, and each can
later become API (`Keys.sharing(token)`, a context) without breaking anyone. Rejected: Option A; `partitionBy` (the
recipe is three lines, and it invites parallel calls into the library); a library per-run cache (scope across export
batches undecided, overlaps the caller's cache); `EnrichContext` (an untyped bag or a type parameter on every plan).
Measure lock contention on the first-run check set under the service's load before 1.0; it decides whether a cached
per-parameter plan or a context is needed. Amends D-111's "built" to "a tested recipe" for those three asks.
→ `api/15` R-FCH-08, R-FCH-15 to R-FCH-17; `reference/90`.

**D-115 — Expressions (M9.13a review, D-111 items 3 and 4; reverses D-27).**
`ExpressionField<M, C>`, built only by `Expr`'s factories (`coalesce`, `nullIf`, `cases`, `plus`/`minus`/`times`/
`dividedBy`, `negate`, `concat`, `function`, `constant`), each one `CriteriaBuilder` construct, is a typed, immutable
value over one vocabulary's columns that is equal by structure. With `ColumnField` it is a `ScalarField`, a new sealed
subtype of `SelectField` that excludes aggregates. Every `Filters` operand, `groupBy` key and `Expr` argument takes
either, so an aggregate there still does not compile, and there are no expressions over aggregates (`Agg.of` keeps
them). `Agg` gains overloads over an expression, so conditional counts and sums are `cases`. Values given to a factory
bind; `Expr.constant` is definition text, for a function's mode argument. One expression resolves to one Criteria node
per statement, so Hibernate references the select item in `GROUP BY` and `ORDER BY` and PostgreSQL matches a key that
binds a value. A grouped query's selected or ordered expression equals a group key or reads only group-key columns
(`MQ1401`, `MQ1406`). Offset paging, offset and grouped export and `primaryKeyFirst` order by an expression. `keyset()`
refuses one (`MQ1208`): its cursor, fingerprint and D-82 bind budget are defined over attribute values, and widening it
later turns a throw into working code. A converted column, an integral division, a null or enum value, an emptied or
custom CASE condition and a non-identifier or aggregate function name are refused at the factory (`MQ1501`–`MQ1506`),
and a declared type the provider does not resolve at first resolution (`MQ1507`). The processor adds
`@Computed(Def.class)` naming an `ExpressionDefinition<M, C>`, as `converter` names a class, and
`@Aggregate(expression = …)`. The existing `byte[]` keyset cursor value, which `Keyset` rendered as an inlined literal
against R-FLT-08, is bound instead (AC-PAG-28), so R-FLT-08 stays absolute. Rejected:
- `Col.of(lambda)`: it cannot be compared, inspected or walked for R-PAG-13.
- The name `Expression`: it clashes with JPA's in the same files.
- `Filters` overloads beside every column form: they double the interface, and widening is source-compatible.
- Fluent methods on `ColumnField`: additive later.
- An expression language in annotation strings (P-5).
- An annotation naming a static constant: a class-initialisation cycle.
- Casts: Hibernate casts `BigDecimal` to scale 2.
- A `VendorProfile` function-name map (P-5).
- Keyset over an expression in 1.0.

→ `api/10` R-COL-06, R-COL-16 to R-COL-20; `api/11` R-QRY-08, R-QRY-14, R-QRY-16; `api/12` R-FLT-08, R-FLT-18;
`api/13` R-AGG-02, R-AGG-05, R-AGG-08, R-AGG-13, R-AGG-14; `api/15` R-FCH-07; `api/16` R-INS-04, R-INS-09; `engine/21`
R-PAG-13, R-PAG-25; `processor/30` R-PROC-16, R-PROC-21, R-PROC-22; `processor/32`; `vendor/40` R-VND-09;
`reference/90`; `delivery/61`; `SPEC.md` INV-9.

*Amended at the M9 gate: the generated constant order is the root columns, the joined columns, the filter-only
columns, the `@Computed` constants in declaration order, then the `@Aggregate` constants last. A definition builds its
expression inside `expression()` and may read any constant of its own `Q<Model>` declared earlier in that order — a
`@Computed` reads only earlier `@Computed` constants — never caching it in a static field of its own* (`processor/31`
R-GEN-27).

**D-116 — Inserts (RFC 0004, M10.1 vendor spike; amends D-14, D-85, P-5 and D-111).** Insert-select is the read
path's rows written elsewhere, so it reuses filters, joins and vendor limits exactly as D-14's update and delete do.
Insert-values is accepted for a different reason: it keeps entities inside the persistence layer and makes batching a
per-call option, and it is still not an entity write. Keys come back only where the engine generated them first,
because the provider's bulk insert returns a count. `persist(model)` is the one entity write, accepted because it
hides the entity, not to duplicate JPA: it is the only portable way to get an `IDENTITY` key back, and a single
created row usually wants its callbacks; D-14's "never entity writes" and P-5 gain that one exception. `persist`
detaches only the entity it created; cascade-detach is the mapping's. Insert-select generators are limited to those
Hibernate renders inline. Conditional insert-values is the conflict clause: one statement per chunk, and a
concurrent duplicate surfaces as a constraint violation rather than a silent skip; insert-select can already guard
with `notExists` over the target. Rejected: a per-row guard statement (its cost), partial rows taking column defaults
(rows with different column sets cannot share a statement), constraint-named conflict targets (unsupported on most
vendors), `StatelessSession` for keys (Hibernate-only, and it silently skips Envers and validation), reading a "last
insert id" (not available after a prepared statement on SQL Server, absent on Oracle), and reusing `@UpdateModel`
(opposite id rule, never instantiated, "unset" means "keep"). D-85: `@InsertModel` joins the annotation freeze
exemptions, and `ModelInsert`, `ValuesInsert`, `ModelPersist`, `InsertSupport`, the three executor methods, the
generated builders and the repository methods join the incubating bulk-write types; the generated `insert`,
`insertFrom` and `persist` carry `@Incubating`, as `update(...)` does, so "every type generated code links against is
frozen" still holds. D-111: M10 ships inserts as 0.3.0, and the freeze review and the D-85 freeze move to M11, which
starts once the D-111 adopter runs on 0.3.0.
*Amended by D-118:* `persist` is no longer the one entity write: an update or delete `throughEntities()` is the second,
and `persist` may return a query model (`persist(persist, returning)`). The freeze moves again, to M12, after M11
ships entity writes as 0.4.0.

*Vendor spike.* Plain HQL, criteria and JPA, no insert API, on Hibernate 6.6.58 and 7.4.11, pinned per Tier-1 vendor
by `tck/vnd/ins` (`InsertGeneratorSpikeTest`, `InsertConflictSpikeTest`, `PersistEnhancedSpikeTest`; SQL snapshots
`wrt-d116-*`) on H2, PostgreSQL 14–17 and MySQL 8.0 and 8.4. Oracle 21.3 (XE) and 23.26 (Free) are Tier 3 with no TCK
target: their cells come from a local exploration that is not kept. SQL Server was not probed. Each probe root has a
version, a unique `code`, and generators as named; results hold on both Hibernate versions unless one is named.

| Generator (Hibernate type) | Insert-values, id omitted | Insert-values, keys drawn first | Insert-select |
|---|---|---|---|
| assigned (`id.Assigned`; 7.x `generator.Assigned`) | — (the model names it): one statement | — | one statement, id mapped from the source |
| `IDENTITY` (`IdentityGenerator`) | one statement | an explicit id is accepted on every vendor (Hibernate's DDL); H2, PostgreSQL and Oracle do not advance the identity past it, MySQL does | one statement |
| sequence, increment 1 (`SequenceStyleGenerator`, `NoopOptimizer`) | one statement; Hibernate draws each key first, a round trip per key | one statement, the generator skipped | physical sequence (H2, PostgreSQL, Oracle): one statement, `next value` inline. MySQL (a table): 6.6 writes `row_number()` as the ids, ignoring the generator (an `AssertionError` under `-ea`); 7.x the temporary-table plan, a round trip and an `update` per row |
| pooled sequence (`PooledOptimizer`, increment 20) | as above, a round trip per block | one statement; a later `persist` draws past the drawn keys | temporary-table plan: on H2, MySQL and Oracle an `HTE_` table, one `update` per row, the insert, a clean-up; on PostgreSQL one CTE statement |
| table (`id.enhanced.TableGenerator`) | one statement; a locking select and an update per key | one statement | rejected by Hibernate: `SemanticException` "without bulk insertion capable identifier generator" |
| UUID (6.6 `id.UUIDGenerator`, 7.x `id.uuid.UuidGenerator`) | one statement | one statement | rejected likewise |
| `@MapsId` (6.6 `ForeignGenerator`) | naming the association writes the id column twice (a grammar error); naming the id: one statement | — | naming the association: rejected likewise; naming the id: one statement. `persist` writes it |

The version seed is written, one bind per row, in both forms. Hibernate's `supportsBulkInsertionIdentifierGeneration()`
is true for every physical sequence, pooled included, and false on MySQL, so it does not predict the plan; the
optimizer's increment and `isPhysicalSequence()` do. Multi-row `VALUES` on Oracle: Hibernate renders 21.3 as
`insert … select … from dual union all select … from dual` and 23.26 as `values (…), (…)`, one statement either way,
`MERGE` sources likewise.

| Conflict clause (target `ins_assigned`, ids 1 and 2 stored) | H2 | PostgreSQL | MySQL | Oracle 21/23 |
|---|---|---|---|---|
| `doNothing` rendering | 6.6: dropped, a plain `insert`; 7.x: `merge … when not matched then insert` | `on conflict(id) do nothing` | `values … as excluded(…) on duplicate key update id=t.id` | as H2 |
| `doUpdate` rendering | `merge … using (values …) excluded(…) on (…) when matched then update … when not matched then insert …` | `on conflict(id) do update set …` | `on duplicate key update name=excluded.name` | as H2, the source a `select` list |
| `doUpdate … where` | `when matched and <where>` | `do update … where <where>` | each assignment `case when <where> then excluded.c else t.c end` | `when matched then update … where <where>` |
| count: `doNothing`, 1 conflicting and 1 new row | 6.6: unique violation; 7.x: 1 | 1 | 2 | as H2 |
| count: `doUpdate`, 1 changed and 1 new | 2 | 2 | 3 | 2 |
| count: `doUpdate` leaving the row unchanged | 1 | 1 | 1 | 1 |
| count: `doUpdate … where` keeping 1 of 2 conflicting rows | 1 | 1 | 3 | 1 |
| `set version = version + 1` | increments; count 1 | increments; count 1 | increments; count 2 | increments; count 1 |
| conflict on another unique key than the named one | unique violation | unique violation | matched: `doNothing` skips (1), `doUpdate` updates that row (2) | unique violation |
| two rows of one statement share the key: `doNothing` | unique violation | first row written (1) | first row written (2) | unique violation |
| the same, `doUpdate` | unique violation | fails: "cannot affect row a second time" | last row wins (3) | unique violation; ORA-30926 when the key is stored |
| a conflict clause on insert-select | a `NullPointerException` inside Hibernate (`BaseSqmToSqlAstConverter.visitConflictClause`), HQL and criteria, 6.6 and 7.x, every vendor; without conflict columns only PostgreSQL renders it (`on conflict do nothing`), elsewhere `IllegalQueryOperationException` | | | |

MySQL counts with Connector/J's default found rows. `persist` on an entity enhanced by Hibernate's enhancer (dirty
tracking, a lazy basic attribute), its fields set by reflection before `persist`: one insert writes every set field,
the lazy one included; an unnamed field keeps the no-arg constructor's value and another is NULL, not a column
default; `detach` leaves it unmanaged. H2, PostgreSQL and MySQL on both Hibernate versions.

*Fixed by the spike* (the spec text lands in `api/14` §10 and `reference/90` with M10.2):
- **R-WRT-26**, the id and the `MQ1805` allowlist. Naming a generated id is `MQ1802` because H2, PostgreSQL and
  Oracle accept an explicit `IDENTITY` value without advancing the identity, so a later generated key collides (the
  RFC's "PostgreSQL rejects it" is wrong under `GENERATED BY DEFAULT`). A root `@Version` is never in the model; the
  provider writes its seed, one bind per row. The allowlist is checked on first execution against the generator
  `InsertSupport` reports. Insert-values: assigned, `IDENTITY`, `SequenceStyleGenerator` with any optimizer (MySQL's
  table-backed sequence included), `id.enhanced.TableGenerator`, and UUID (`id.UUIDGenerator`,
  `id.uuid.UuidGenerator`); for all but `IDENTITY` and assigned the engine draws the keys from the provider's
  generator before the statement and writes them as values, Hibernate skipping its own generator for an explicit id.
  Insert-select: assigned (mapped from the source), `IDENTITY`, and `SequenceStyleGenerator` over a physical sequence
  with increment 1. Everything else is `MQ1805` naming the generator class: a pooled sequence (a statement per row,
  except one CTE on PostgreSQL), a table or UUID generator (Hibernate rejects them), and any sequence on MySQL (wrong
  keys on 6.6). Either form: a `JOINED` root, a `@SecondaryTable`, a composite id with generated parts, and `@MapsId`
  (Hibernate writes the id twice or rejects its generator) are `MQ1805`; naming the `@MapsId` id instead works and may
  widen the rule later. `persist` takes every generator and `@MapsId`.
- **R-WRT-29**, rows per statement. Every row of a chunk goes in one statement on every built-in vendor: Hibernate
  renders Oracle before 23 as `insert … select … from dual union all …`, so the RFC's per-row fallback is dropped.
  The version seed counts as one bind per row. `maxValuesRows()` stays (1,000 on SQL Server, unprobed).
- **R-WRT-35**, rendering and count. `ON CONFLICT` on PostgreSQL; `ON DUPLICATE KEY UPDATE` with a row alias on
  MySQL and MariaDB, `doNothing` rendered as a self-assignment, and a `doUpdate`'s `where` as a `CASE` per
  assignment; `MERGE` on H2, Oracle and SQL Server, with the `where` on `WHEN MATCHED`. The count is the provider's:
  rows inserted plus rows updated, except on MySQL, where every conflicting row counts 1 when skipped, filtered out or
  left unchanged and 2 when changed, so `doNothing` counts the rows it skipped. The Javadoc says so and the TCK pins
  it per vendor.

*Decided by the user after the spike* (each contradicts the RFC; M10.2a shapes the stages on them):
1. Hibernate 6.6.58 and 7.4.11 cannot render a conflict clause on an insert-select, so R-WRT-34's "both actions work
   on insert-select as well" and R-WRT-37's insert-select clause do not hold. `onConflict` is on insert-values only;
   an insert-select guards with `notExists` over the target, as this decision already says. Widening later is
   additive.
2. Hibernate 6.6 renders `doNothing` on a `MERGE` vendor (H2 is Tier 1; Oracle too) as a plain insert, so a conflict
   throws a unique violation instead of skipping; 7.x renders it. `InsertSupport` reports whether the provider
   renders `doNothing` for the dialect, and `onConflict(...).doNothing()` throws `MQ1804` on first execution where it
   does not, rather than failing per row at run time.
3. `@MapsId` stays `MQ1805` in both forms, as the RFC has it, even when the model names the id; widening is additive.

→ `api/14` §10 (R-WRT-24 to R-WRT-40), `reference/90` (`MQ18xx`), `vendor/40` R-VND-14, `delivery/62`,
`docs/plan/mvp-plan.md` §11–12, `rfc/0004-bulk-inserts.md`.

**D-117 — Insert API shape (M10.2a review; amends RFC 0004 §3–§7 and D-116).** Built on D-116's three user
decisions; nothing frozen (every type `@Incubating`).
1. *Types.* `ModelInsert<E, M>` is a sealed class permitting `ValuesInsert`; insert-select and insert-values with a
   conflict clause build to it. Insert-values without one builds to `final ValuesInsert<E, K, M>`; persist to
   `final ModelPersist<E, K, M>` (order E, K, M as `ModelQuery`). `E` is the written root; the insert-select source
   is unconstrained. Executor and repository: `long insert(ModelInsert<E, ?>)`,
   `<K> List<K> insertReturningKeys(ValuesInsert<E, K, ?>)`, `<K> K persist(ModelPersist<E, K, ?>)`. Keys from an
   insert-select or a conflict-clause insert, and `set`/conflict/chunking on persist, do not compile.
2. *Key typing* (RFC "Resolved" 1). `K` is a type parameter fixed by the generated `insert(rows)`/`persist(row)` from
   the root's id as the processor sees it (boxed `@Id`, `@EmbeddedId`, `@IdClass`, a `@MappedSuperclass` variable via
   `Types.asMemberOf`), which passes `Class<K>` into the definition. On first execution (D-61) `K` must equal the boxed
   `IdentifiableType#getIdType()` Java type, else `MQ1807` naming `orm.xml`. No id visible: `K = Object` and warning
   `MQ3504`. A composite `K` is the `@IdClass` or embeddable (what `PersistenceUnitUtil#getIdentifier` returns), not
   `PrimaryKey.composite`'s `List<Object>`. Generator strategy stays a run-time check (`MQ1805`, `MQ1807`). Rejected: a
   per-call `Class<K>` (a wrong class compiles, P-2) and processor-only typing (misses `orm.xml`).
3. *`InsertColumns<M, E>`*, a new core type and the type of `INSERT_COLUMNS`: the root `TableField<E, E>` and, in
   declaration order, `<C> add(ColumnField<M, E, C>, Function<? super M, ? extends C>)` with a key flag per column.
   Processor-free entry points (INV-8): `ModelInsert.select(InsertColumns<M, E>, TableField<S, S>)`,
   `ValuesInsert.builder(InsertColumns<M, E>, Class<K>, List<? extends M>)`,
   `ModelPersist.of(InsertColumns<M, E>, Class<K>, M)`.
4. *Row copy (INV-9).* `build()` (or `ModelPersist.of`) reads each row once into an immutable value array; `M` is never
   read again. So `MQ1803` (null row), `MQ1802` (null assigned id) and `MQ1808` (duplicate conflict-key tuple, by
   `equals`) are raised at `build()`.
5. *Insert-select stages* (D-60). `<S> SelectStart<E, M> insertFrom(TableField<S, S>)` (a join `TableField` does not
   compile) → `<Q, C> Mapping<E, M, Q> map(ColumnField<M, E, C>, ColumnField<Q, ?, C>)`, the first `map` fixing `Q` →
   more `map`s, `set(ColumnField<?, E, C>, C)`, then `where(UnaryOperator<Filters<Q>>)` or `all()` →
   `SelectOptions<E, M>`: `chunked(ChunkOptions)`, `persistenceContext(...)`, `build()`. No `onConflict`,
   `keepVersion` or `startAfter`; resume narrows the `where` on the source key. Every target column mapped exactly
   once, checked at `build()` (`MQ1801`).
6. *Insert-values stages.* `ValuesInsert.Rows<E, K, M> insert(List<? extends M>)` offers `set` (null is `MQ1603`; no
   `setNull`), `onConflict`, `chunked`, `persistenceContext`, `build()`; `chunked`/`persistenceContext` move to
   `ValuesInsert.Options`, where `onConflict` is gone. `lockKeys()` is `MQ1801` at `build()`; `commitEachChunk()` with
   `insertReturningKeys` is `MQ1801` at that call, before the flush (`build()` cannot know).
7. *Conflict stages* (insert-values only). `onConflict(ColumnField<M, E, ?> first, ColumnField<M, E, ?>... rest)` →
   `Conflict<E, M>`: `doNothing()` → `ConflictOptions<E, M>`, or
   `doUpdate(Function<ConflictUpdate<E, M>, ConflictUpdate.Action<E, M>>)` → `Upserting<E, M>`. `ConflictUpdate`
   offers `setFromRow(first, rest...)`, `set`, `setNull`, each returning `Assigned` (an `Action`) with more
   assignments and one `where(UnaryOperator<Filters<M>>)` returning a bare `Action`. `Upserting` adds `keepVersion()`
   to `ConflictOptions` (`anyUniqueKey()`, `chunked`, `persistenceContext`, `build()` → `ModelInsert<E, M>`). If every
   filter in the update's `where` is skipped the update applies to every conflicting row (not `MQ1601`). `exists` or
   sub-query filters there are `MQ1804` at `build()`.
8. *Counts.* `insert` returns the provider's count: inserted plus updated where `conflictTargetHonoured()`; elsewhere a
   conflict clause already needs `anyUniqueKey()`, whose Javadoc states that it accepts any-unique-key detection, the
   vendor's count (MySQL: 1 per skipped, filtered or unchanged row, 2 per changed one, at Connector/J's default found
   rows) and the vendor's key collation. `ChunkedWriteException.committedRows()` follows it.
9. *`ChunkedWriteException`* gains `OptionalInt nextRowIndex()` (insert-values only) and `int inDoubtRowCount()`, the
   contiguous chunk from `nextRowIndex`; for insert-select `lastCommittedKey()`/`inDoubtKeys()` hold source ids.
10. *`InsertSupport`* (INV-7, `jpa.spi`, jakarta types only), via `ProviderSupport#inserts()` (default empty):
    `InsertTarget target(EntityManagerFactory, Class<?>)` → `record InsertTarget(IdGeneration id,
    List<String> unsupportedMappings)`, sealed `IdGeneration` = `Assigned | Identity | Sequence(boolean physical,
    int increment) | Table | Uuid | Other(String generatorClass)`; `List<Object> generateKeys(EntityManager,
    Class<?>, int)`; `boolean doNothingRendered(EntityManagerFactory)`; `int providerBindsPerRow(EntityManagerFactory,
    Class<?>)`; `<E> Query insertSelect(EntityManager, Class<E>, List<String>, CriteriaQuery<Tuple>)`;
    `<E> Query insertValues(EntityManager, Class<E>, List<String>, List<List<Object>>, Optional<ConflictClause<E>>)`,
    `ConflictClause<E>` carrying `keyAttributes()`, `doNothing()` and `Optional<Predicate> update(Root<E> target,
    Root<E> excluded, CriteriaBuilder, BiConsumer<Path<?>, Expression<?>> assign)`. The `MQ1805` allowlist lives in
    `jpa` over `IdGeneration` (insert-select `Sequence` needs `physical && increment == 1`). Bind count (D-80): columns
    plus `set` constants plus `providerBindsPerRow` per row, plus the conflict binds as an upper bound (`where` binds ×
    (assignments + 1), plus `set` binds). `model-query-hibernate` ships one `HibernateInsertSupport` compiled against
    6.6 and run on 7.x in the TCK, using only API in both and comparing moved generator classes by name;
    `doNothingRendered` is true on 7+, and on 6.x only for the PostgreSQL and MySQL dialect hierarchies.
11. *`VendorProfile`* (INV-6): `default int maxValuesRows()` = `1_000` (the RFC's `Integer.MAX_VALUE` reversed: a lower
    limit is only slower, and SQL Server resolves to OTHER) and `default boolean conflictTargetHonoured()` = `false`.
    H2 and POSTGRESQL: `Integer.MAX_VALUE`, `true`; MYSQL and MYSQL_CURSOR_FETCH: `Integer.MAX_VALUE`, `false`. No
    per-row fallback (D-116).
12. *Overlap fails closed.* A chunked insert-select whose source or target has an empty `tablesOf` throws `MQ1806`:
    rows written with generated ids above the cursor would otherwise be re-read silently (INV-5).
    The same holds for a source that joins through a link or collection table, which `tablesOf` does not name: a
    many-to-many, an element collection, or a one-to-many not shown by `mappedBy` or a join column to be a foreign key
    on its target. The reason is the unnamed table, not the fan-out, which R-WRT-28 keeps.

RFC text this corrects: R-WRT-33 (`commitEachChunk` check), R-WRT-27 (mismatched source model compiled), R-WRT-26
(the IDENTITY reason; "physical" sequence), R-WRT-35 (MySQL `doNothing` count), R-WRT-36 and R-WRT-29 (no Oracle or
SQL Server profile exists), R-WRT-34 and R-WRT-37 (insert-select conflict). The M10.9 sample's `doNothing` endpoint runs
on its PostgreSQL datasource (Boot 3.4 is Hibernate 6.6, where H2 gives `MQ1804`).
→ `api/14` §10, `reference/90` (`MQ1801`, `MQ1807`, `MQ1808`), `processor/31` §7, `processor/32` (`MQ3504`),
`vendor/40` R-VND-14, `vendor/41`, `rfc/0004-bulk-inserts.md`.

*Amended by M10.2:* item 3's key flag is `InsertColumns.addKey(column, fn)` beside `add`. `InsertColumns.of(root)`
cannot infer `M` from the root, so a hand-written list gives a type witness (`InsertColumns.<M, E>of(root)`); the
generated `INSERT_COLUMNS` (M10.3) hides it. A column of a join, even one naming a model attribute, is not on the root:
`MQ1801` when added, mapped or `set`, `MQ1804` inside a conflict clause. A conflict or `setFromRow` column outside
`InsertColumns`, or named twice, is `MQ1804` at `build()`. Rows are kept as model values, converted when bound (D-37),
so `MQ1808` compares model values by `equals`. AC-WRT-32 (`build()` checks) and AC-WRT-33 (stage shapes) are added
beyond RFC 0004's list.

*Amended by M10.3:* an insert model's columns reuse `MQ3301`, the `@Version` part of `MQ3303`, `MQ3304` and `MQ3305`
with an update model's meaning (INV-10, R-DIAG-04), but `MQ3304` covers what an insert cannot write:
`@Column`/`@JoinColumn(insertable = false)` in place of `updatable = false`, and the inverse (`mappedBy`) side of a
to-one. `@Transient` and `@Child` on an insert model are `MQ3502`: every field is a column (R-WRT-25). `MQ3501` also
covers a `@PrimaryKey` on an attribute that is not the id, and the id named without `@PrimaryKey`.

*Amended by M10.4:* item 2's comparison is skipped where `getIdType()` is null, as Hibernate 6 reports it for an
`@IdClass` root, and `K = Object` passes; an `@IdClass` key still never comes back, since a composite id is assigned
(`MQ1807` from `insertReturningKeys`) or has a generated part (`MQ1805`). Item 10's "only API present in both" has
one exception: whether a composite id generates a part is read from `CompositeNestedGeneratedValueGenerator`'s
private `generationPlans` field (6.6 has no accessor), and the read fails closed: a field missing, inaccessible or of
another type counts as a generated part, so the root is `MQ1805`, never let through.

*Amended by M10.5:* R-WRT-37 leaves a conflict-key tuple holding a `null` out of the `MQ1808` check: SQL `NULL`s
never conflict, and a unique index that treats them as equal (PostgreSQL's `NULLS NOT DISTINCT`) reports its own
constraint error. Item 10's `Sequence` is `SequenceStyleGenerator` itself, compared by class: a subclass is `Other`
(`MQ1805`) for either insert, since an insert-select reads the sequence inside the statement and would bypass the
subclass's own key code. The insert-select statement: a `set` constant is a named criteria parameter the executor
binds, since Hibernate 6.6 inlines `CriteriaBuilder#literal`; a to-one column, written by id, is the target path
`customer.id`, so the select copies the id. A chunked insert-select pages over the source root's id as plain
attribute columns read from the metamodel (the `@Id`, the `@EmbeddedId` components, or the `@IdClass` attributes,
ordered by name), so `lastCommittedKey()` and `inDoubtKeys()` hold the id value, or for a composite id the list of its
component values in that order; `lockKeys()` locks the source rows the key select reads. `MQ1806` compares the target
with the source root and with every entity the select joins, through a `map` or a `where` column at any depth: one
topmost entity type, or intersecting `tablesOf`. A row a chunk writes into a joined table can join a later chunk's
source row, so add to its to-many repeats or change its `where`, which the unchunked statement never sees.

*Amended by M10.6:* R-WRT-28 allows a chunked insert-select whose `where` holds an `exists` or `notExists` sub-query
over the target (no `MQ1806`): a sub-query is not in the select's FROM. Chunked, such a guard gives the unchunked
statement's rows only when correlated on a value unique among the source rows, such as the source id; on a value
several source rows share, a later chunk skips rows an earlier chunk inserted. R-WRT-30's to-one binds its target's id
to the path `customer.id`, as the insert-select copies it, instead of `getReference`. The insert-values statement: each
value is a named parameter that `InsertSupport` creates and binds as its target path's type, since Hibernate infers no
type for an insert's bound values: so a `null` binds typed, and a value an `AttributeConverter`, `@Enumerated` or
`@JdbcTypeCode` maps is written through that mapping, not resolved from its class; a `set` constant is repeated in
every row, one bind per row; a drawn key (sequence, table, UUID) is appended as the id
attribute's value, drawn before each statement on the `EntityManager` that runs it. Rows per statement are
`maxBindParameters()` over the binds of one row (its values, a drawn key and `providerBindsPerRow`), then at most
`maxValuesRows()` and the chunk size (`size`, else the configured `bulkWriteChunkSize`); a chunk is one statement, and
`commitEachChunk()` commits each alone, its `ChunkedWriteException` holding no keys. An empty list still runs the
first-execution and transaction checks, then returns with no flush, clear or eviction.

*Amended by M10.7:* item 10's `InsertSupport` gains `List<Set<String>> uniqueKeys(EntityManagerFactory, Class<?>)`,
the attribute sets the mapping declares unique, which a conflict clause's columns must equal exactly (`MQ1804` on first
execution). Hibernate's reads the id and `@NaturalId` from the persister, `@Column`/`@JoinColumn(unique = true)` from
each attribute's member, and `@Table(uniqueConstraints)` of the entity or a superclass entity (Hibernate maps no other
class's `@Table`), matching the constraint's column names, unquoted and ignoring case, to the persister's or to a
single-column attribute's logical name (its `@Column`/`@JoinColumn` name, else a basic attribute's name), which is how
Hibernate resolves them, so a physical naming strategy that renames columns, as Spring Boot's does, still matches; an
`orm.xml` unique key is not seen, so it fails safe.
`ConflictClause.update` takes an `Assignments` with `value(Path<?>, Object)` and `expression(Path<?>, Expression<?>)`
instead of a `BiConsumer`, so a `set` value is bound as its target path's type, as the rows' values are (Hibernate's
own conflict-action `set` resolves the value's class and fails for a converted one). `insertSelect` gains
`Map<String, Object> constants`: a `set` constant of an insert-select is bound by `InsertSupport` as its attribute's
type, not by the engine's untyped `setParameter`, which failed for an `AttributeConverter` class. Hibernate's binds it
as the attribute's mapped `BasicType` (`findByPath(...).getSingleJdbcMapping()`), since a target path's type is
resolved against the source select's FROM there and binds the wrong type. A conflict update assigning an id attribute
is `MQ1804` on first execution, as `@Version` is; its binds count as item 10 states, the version increment counted
as one assignment and one value. A `setNull` in a conflict update is a `null` value bound as its target path's type,
counted as a `set` bind: a `NULL` literal typed by the attribute's Java class fails in Hibernate for a converted
class. MySQL assigns in order and each `CASE` reads the columns earlier assignments wrote, so an assignment to a column
the `where` reads, the version increment included, renders after the others. That cannot help a `where` reading two
or more assigned columns, which on MySQL silently filters the later assignments on the values the earlier ones wrote.
The user decided: such a `where` (the version increment counted unless `keepVersion()`) is `MQ1804` on every vendor
by default, so a definition behaves alike everywhere; `ModelQueryConfig.conflictUpdateWhereOnAssignedColumns(boolean)`
(`modelquery.bulk-write.conflict-update-where-on-assigned-columns`, default false) opts in where it is correct; and
`VendorProfile.conflictWhereSeesEarlierAssignments()`, true by default so a third-party profile fails safe, false in
the built-in `H2` and `POSTGRESQL` profiles and true in `MYSQL`, `MYSQL_CURSOR_FETCH` and `OTHER`, keeps it `MQ1804`
where the opt-in would still be wrong. The check runs on each execution, after the first-execution checks, rather
than once per factory, since the option belongs to the executor and two executors on one factory may differ. For
the same reason the R-WRT-36 check (`conflictTargetHonoured() || anyUniqueKey()`) also runs on each execution, since
the profile is the executor's.

*Amended by M10.8:* `persist` reuses an embeddable the root's no-arg constructor already set and instantiates one only
where it left `null`, so the embeddable's other attributes are, like the root's, as the constructors leave them. The
record or constructor-only embeddable check (`MQ1805`) runs with `MQ1807` on first execution, over every embeddable on
a column's path, before the `MQ2501` check. `ModelPersist` gains the `@EngineFacing` `attributes()` and
`attributeValues()`, the dotted column names and their converted values, which the executor sets; nothing new is API.

*Amended at the M10 gate:* a join through a collection table (`@ManyToMany`, `@ElementCollection`) is not checked for
overlap; `chunked` fails closed with `MQ1806` on it.

**D-118 — Entity writes (RFC 0005, M11.1 review; amends D-14, D-85, D-116 and P-5).** The D-111 adopter's audit runs
as Hibernate event listeners, which a bulk statement never reaches, so a bulk update or delete on an audited table
silently loses audit records. Entity mode is the second exception to D-14 after `persist`, accepted because it keeps
the entity hidden and reuses the filter DSL, change sets and chunking, not to duplicate JPA: rows are loaded by key per
chunk, changed through the metamodel and flushed, so every listener, callback and audit library runs unchanged. The
persistence context follows `PersistenceContextMode` rather than detaching only what was loaded, which would detach a
caller's own managed instance. `persist` may return a query model built from the flushed entity, with no second
select, and refuses what it cannot fill rather than falling back to one. Server-set columns are `WriteAssignment`s per
entity on the config, since a per-model annotation must be repeated on every model and a forgotten one is the bug it
fixes; an explicit `set` wins. Rejected: a write-event SPI (every listener library would need an adapter, and a bulk
statement has no old state without an extra select), an entity mode for inserts (`persist` in a loop is the same
cost), `IDENTITY` keys from a multi-row insert (outside JPA, and MySQL's key arithmetic depends on
`innodb_autoinc_lock_mode`), saving a parent with its children (cascade through an insert model, deferred past 1.0),
and a `returning(...)` builder stage (it would retype the builder). D-85: `throughEntities()`, the `persist` overload,
`WriteAssignment`, `WriteKind` and `ModelQueryConfig.writeAssignments` join the incubating bulk-write types. D-111:
M11 ships entity writes as 0.4.0, and the freeze review and the D-85 freeze move to M12, which waits for the adopter
to run 0.4.0 in production.

*Placement (M11.1 review).* `throughEntities()` is an options-stage method, also on each `Resumable` stage, and the
incompatible options are `MQ1610` at `build()`, whatever the call order. `setExpression` precedes the row choice and
`update(changes)` returns the assignment stage, so a separate stage could not make them unexpressible without doubling
the update stage types. Assignments name a string attribute path and a `Class<A>`, checked per root on the first write
per factory (a `ColumnField` needs a model the server-set column is kept out of; a static `SingularAttribute` is null
before bootstrap; a config can serve several factories). They live in `jpa` and match the named class and its
subclasses. `persist` returns a model through a `ModelQuery<E, ?, R>`, not a `SelectSet`, which carries neither the
root nor the mapper.

*Addendum (M11.2).* An application's own `ModelQueryConfig` bean that drops a `WriteAssignment` bean fails startup
with `MQ4006`, as a dropped `VendorProfile` bean does (`integration/50` R-SPR-13).

*Addendum (M11.3).* In the caller's transaction an `OptimisticLockException` from an entity-mode flush reaches the
caller unwrapped, as any failure of a write that does not commit per chunk does; only a `commitEachChunk()` write
wraps it in `ChunkedWriteException` (R-WRT-20, R-WRT-46). With `commitEachChunk()` under `CLEAR` the caller's
`EntityManager` is also cleared once after the last chunk, as a bulk write clears it after its last statement, since
its managed copies of the written rows are stale; under `KEEP` they stay stale, as after a bulk write (R-WRT-45). The
query timeout applies to the key select and the load; the flush's statements get none, as JPA has no portable
per-statement hint (R-WRT-45). `ModelUpdate.buildEntityLoad`, `assignedAttributes` and `assignedValues` join D-72's
engine-facing members.

*Addendum (M11.4).* The M11.3 decisions hold for a delete `throughEntities()` as well: its failure in the caller's
transaction reaches the caller unwrapped, `commitEachChunk()` under `CLEAR` clears the caller's `EntityManager` once
after the last chunk, and the query timeout applies to the key select and the load, not to the flush.
`ModelDelete.buildEntityLoad` joins D-72's engine-facing members.

*Addendum (M11.5).* A `persist` returning query's `where` or `having` counts as present only when it recorded a
filter, so one whose `Optional` filters were all skipped is not `MQ1809`: it would add no predicate to a read either. A
root column naming an association itself, typed as the target entity, is `MQ1809`, whose message says to select the
target's id through a join instead, since the entity holds only an uninitialized reference. `ModelQuery.checkReturning`
and `mapReturning` join D-72's engine-facing members.

*Addendum (M11.6).* Write assignments are checked on the first update, insert or `persist` per root per factory for
each configured list, never on a delete, which applies none; `MQ1611` and `MQ1612` are `ModelQueryDefinitionException`s
like every `MQ1xxx`. A supplier is called only where its value is written: not for an update that writes nothing or an
insert-values of no rows, nor for an attribute the definition sets itself, and an insert and its `doUpdate` share one
call. A bulk update sets the assignments after the version increment; an insert-select selects each as a bound
parameter after the `set` constants; a `doUpdate` counts an assignment's binds, MySQL's `CASE` repeat included, and
one its `where` reads goes last and counts toward R-WRT-34's limit of one such column. `ModelInsert.conflictUpdateAdds`
joins D-72's engine-facing members, and `buildConflictUpdate`, `conflictBinds`, `conflictRepeatedBinds` and
`conflictWhereReadsAssigned` take the assignments' paths.

→ INV-1, P-5, `api/14` §11 (R-WRT-41 to R-WRT-49), `reference/90` (`MQ1610`–`MQ1612`, `MQ1809`), `integration/50`
R-SPR-10, `delivery/62`, `docs/plan/mvp-plan.md` §M11, `rfc/0005-entity-writes.md`.

**D-119 — Entity-mode writes on proxies and enhanced entities (M11 gate review; amends R-WRT-42, R-WRT-43, R-WRT-45,
R-WRT-47, R-WRT-49 and D-118).** An entity-mode update set each assignment with a reflective field write on whatever
instance the load returned, and two ordinary cases defeated it silently while the count still said matched. The load
hands back the uninitialised proxy the persistence context already held for a matched row (a caller's `getReference`,
or a lazy to-one), so the write landed in the proxy's own copy of the field: no `UPDATE`, no `@PreUpdate`. A
bytecode-enhanced entity's inline dirty tracking never sees a reflective write, so its flush wrote nothing either.
Under `CLEAR` the engine now also clears the context right after R-WRT-15's flush, which a caller cannot tell from the
first chunk's own clear, and leaves the first load no proxy to return. A loaded instance whose class is no mapped
entity class, under `KEEP` or not, is replaced by the instance behind it through the provider; with no provider able
to, the update throws `MQ2503` before it changes any entity of the chunk rather than count a row it did not write.
Assignments go through the provider's attribute access where it has one, so enhanced dirty tracking, lazy-attribute
interception and embeddable owners see them; the reflective write stays the fallback, and its Javadoc names the
enhanced or woven change tracking it cannot see. Both are `ProviderSupport#entityWrites()`, an
`Optional<EntityWriteSupport>` empty by default, in `jpa.spi` with `jakarta.persistence` types only (INV-7);
`model-query-hibernate`'s uses `Hibernate.unproxy` and the persister's property access, and marks a changed attribute
dirty on a self-tracking entity, since that property access alone does not. A delete needs neither:
`EntityManager#remove` takes a proxy. Rejected: refusing every proxy, since a caller's `getReference` is ordinary JPA
and JPA has no portable way to reach the instance behind one. D-85: `ProviderSupport#entityWrites()` and
`EntityWriteSupport` join the incubating bulk-write types.

*Subclass assignments.* A write assignment naming a strict subclass of a write's root (`CardPayment` under a `Payment`
root, `SINGLE_TABLE`) is `MQ1611` on that root's first write instead of skipped: the write reaches the subclass's rows
too, and one statement cannot apply the assignment to only those, so skipping it silently lost the column. One naming
an unrelated class is still skipped, so one config serves every root.

*Provider write rules.* Entity mode writes through the provider, so the mapping's own write rules hold: a
`@Column(updatable = false)` attribute or a Hibernate `@Immutable` entity, an `UPDATE` write assignment on one
included, is written by a bulk update but not by entity mode, while the count still says matched (R-WRT-44). The
Javadoc of `throughEntities()` and the user guide say so.

→ `api/14` §11 (R-WRT-42, R-WRT-43, R-WRT-45, R-WRT-47, R-WRT-49), `vendor/40` R-VND-14, `reference/90` (`MQ1611`,
`MQ2503`).

**D-120 — Selected fields on the model (`@Selected`; amends D-85 and D-118).** A row cannot tell an unselected
column from a selected one that is `NULL`: a record gets `null` (R-GEN-06), a class field keeps its initialiser (R-GEN-09),
and only `afterMap` with `Row.isSelected` could tell them apart, which needs per-query wiring and fails for a record. A model may declare
one `@Selected SelectSet<M>` field or record component, `M` being the model itself. "Selected" means the row selected
it, even when its value is `NULL`. The generated mapper fills it, never with `null`, with every column, expression and
aggregate constant of `Q<M>` that the mapper reads, joined columns at any depth included, and that the row selected.
Keys and ordering, group, fetch-plan and presence keys the engine adds count, because the field holds them; the set is
therefore not the caller's own `SelectSet`. Filter-only columns never count, since no field holds them. A nested
`@Join` or `@Child` model with its own `@Selected` field fills it from its own row; an empty `Optional` builds none. The
mapper fills it through `SelectSet.selectedIn(Row)`, the subset of the set `row.isSelected` accepts, in the set's order, which keeps a
single-entry memo keyed by a selection bit mask, so every row of a query shares one instance and the memo is not
observable (INV-9). `SelectSet` gains a typed `contains(SelectField<M, ?>)` (P-2), exact as `Row.isSelected` is and never
through a converter, and set-semantics `equals`, `hashCode` and `toString`, so records holding one stay comparable.
`@Selected` targets `FIELD` and `RECORD_COMPONENT`; the processor checks the field type by qualified name, so the
annotations module does not depend on `core` (INV-7). `MQ3020`: the type is not exactly `SelectSet<M>`, or a model has
two. `MQ3021`: combined with another field annotation. An update model (`MQ3302`) or insert model (`MQ3502`) refuses it.
Rejected: a narrower read-only type (one more type to freeze, P-5); only the model's own fields (`contains` of a joined
column would compile and be silently `false`, INV-5); a `Row` method exposing the selection (leaks the `@EngineFacing`
`RowSelection`); computing the set in each generated QModel (duplicated logic); `afterMap` only (no records); `@Populated`
and `@Loaded` (the latter clashes with fetch-plan "unloaded"). D-85: `@Selected`, `SelectSet.contains` and
`SelectSet.selectedIn` stay `@Incubating` through 1.0; `SelectSet` equality freezes with `SelectSet`. Ships as 0.5.0 in
M12; the freeze moves to M13. → `api/10`, `api/11` R-QRY-05, `processor/30`, `processor/31`, `processor/32`
(`MQ3020`, `MQ3021`, `MQ3302`, `MQ3502`), `reference/90`, `delivery/61`, `delivery/62`, `docs/plan/mvp-plan.md` §M12.

**D-121 — Field lookup by name (`FieldIndex`, `Q<M>.fields()`; RFC 0006).** Every query model generates a static
`fields()` returning a `FieldIndex<M>`, held in a private nested holder class so it is built on first use and a call
during `Q<M>`'s own initialisation fails loudly (INV-5). The index is keyed by kind: select fields, filter-only columns,
column sets and children. Core derives each key from the constant, with the tier-1 rule `orderedBy` uses (R-QRY-14)
through one shared helper: a property path, else an attribute path (D-55); an expression's or aggregate's `named`
property, else its name. `AggregateField` gains `named`, outside `equals` (R-AGG-01), emitted by the processor and
matched first by the sort's aggregate tier. Join sets are keyed by their join's property path; `ALL` and `DEFAULT` by
name. The vocabulary is a subset of the sort's. A filter-only column sharing a key is left out with the warning
`MQ3022`, never an error, since such models compile on 0.5. `resolve` never throws and reports unknown names; `only`
narrows sets to the fields it keeps and throws `MQ1105` on a name it doesn't hold. The builder is public
`@Incubating`, not `@EngineFacing`: generated code in users' jars is a binary contract. Rejected: aliases (the API's
naming, kept in the app), a `FIELDS` constant (clashes with a field named `fields`), an eager static field, an
untyped `eq(ScalarField<M, ?>, Object)` (P-2), value parsing and a field grammar (a later RFC), child sub-fields and
Spring binding (not in 0.6). Ships as 0.6.0 in M13. → `rfc/0006`, `api/10` R-COL-23/24, `api/11` R-QRY-14,
`processor/31` R-GEN-32/33, `processor/32` (`MQ3022`), `reference/90` (`MQ1105`), `delivery/61`,
`docs/plan/mvp-plan.md` §M13.

**D-122 — A `java.util.Date` column matches the `java.sql` type the provider reports (amends R-COL-08 and D-20).** JPA stores a
`Date` attribute as a `DATE`, `TIME` or `TIMESTAMP`, and Hibernate reports it as `java.sql.Date`, `Time` or
`Timestamp` (`@Temporal`, defaulting to `TIMESTAMP`), while the processor reads the Java field's declared `Date`. A model
mirroring its entity compiled and then failed `MQ1001` at first use. The run-time check now accepts any of the three for a
column whose attribute type is `java.util.Date`; each is a `Date`, so `Row.get` returns it as the field's type and a
filter binds a plain `Date`, which the provider takes. The processor keeps reading declared types, so it holds no
provider's typing rules and stays right for a provider that reports `Date` itself. `MQ1001` qualifies the two type names
when their simple names agree. The check keys on the column's attribute type, so a hand-written `Date` column with
no converter over a declared `Timestamp` attribute now passes too; it reads a `Timestamp`, which is a `Date`. A
`ColumnConverter` whose attribute type is `Date` is given the `java.sql` instance, on which `toInstant()` throws for
`java.sql.Date` and `Time`; it reads `getTime()` instead. The same rule and naming apply to an expression's resolved
type (`MQ1507`, a `coalesce` over `Date` columns) and to `Agg.of`'s (`MQ1405`). An insert-select types a constant's parameter
as its target attribute's metamodel type where that is one a `Date` column reads, so a `Date` constant on a `DATE` or
`TIME` attribute is not taken as a timestamp. Rejected: deriving the `java.sql` type in the processor from `@Temporal` (Hibernate's
rules in a provider-neutral processor, blind to `orm.xml` and `@JavaType`, and models would have to declare `java.sql`
types), as an optional processor module (a new SPI, and one model compiling two ways), and built-in converters from
`Date` to each `java.sql` type (never chosen while both sides read `Date`; the `DATE` and `TIME` ones truncate, so
they could not be ordered). A wrong `java.sql` model type stays a first-use `MQ1001`, not a compile error. → `api/10`
R-COL-08, R-COL-17, AC-COL-28; `api/13` R-AGG-02.

**D-123 — Fewer models, fewer calls (`generateInserts`, `one`, `first`, `one(q, key)`; RFC 0007).** `@QueryModel`
gains `generateInserts`, the insert twin of `generateChanges`: it generates `INSERT_COLUMNS`, `insert`, `insertFrom`
and `persist` on the query model, each `@Incubating`, so a screen that reads and creates one row shape keeps one
model. It writes root columns only and leaves out `@Join`, `@Child`, `@Computed`, `@Selected`, `@Transient` and
filter-only fields, to-one columns, version columns, columns an insert can't write and generated ids, listing each in
the `INSERT_COLUMNS` Javadoc. It writes no foreign key: a query model cannot name one as a scalar (`MQ3002`, D-44), so
a model that sets one keeps its own `@InsertModel`. `addKey` follows the root's id, not `@PrimaryKey`; a non-id
`@PrimaryKey` is written with `add`. `MQ3505` refuses a grouped model or one with no writable root column;
`MQ3503` is unchanged. The executor gains `one(q)`, `first(q)` and `one(q, key)`, and the repository `findOne`,
`findFirst` and `findByKey`, delegate-only (R-SPR-01). `one` renders `list`'s statement with a limit of 2, throws
`MQ2003` on a second row before the fetch plan runs, and counts `list` rows, so a predicate-only to-many join gives
`MQ2003` naming the join and pointing at `Filters.exists`. `first` appends the key, or the group keys for a grouped
query, as a tie-breaker after any `orderBy`, with explicit `nullsLast` only for a column that can be NULL (an
`@Id` or non-optional column sorts plain ascending, so an index can serve `LIMIT 1`); neither an `orderBy` nor a key is `MQ2203`, except on
an aggregate-only query, which has one row. A
key passed to `one(q, key)` converts as in `whereKey`; a `null` key or component, or a wrong component count, throws
`IllegalArgumentException`, and a keyless or grouped query is `MQ2203`. The methods are abstract on the `@Incubating`
interfaces (R-REL-07, D-110), not defaults: a default on `list` would run the fetch plan on the extra row. Rejected:
allowing `@QueryModel` and `@InsertModel` together (clashing generated members), writing foreign keys from a query
model, `first` in natural order or without a tie-breaker (a different row per vendor), `one` at a limit of 1 (hides
the duplicate) or deduplicating by key (hides a real second row, INV-5), a nullable return or a throwing `getOne`.
Ships as 0.7.0 with D-122. → `rfc/0007`, `processor/30` R-PROC-26, `processor/31` R-GEN-34, `processor/32`
(`MQ3501`, `MQ3504`, `MQ3505`), `api/11` R-QRY-03, `engine/20` R-EXE-12, `reference/90` (`MQ2003`, `MQ2203`),
`integration/50` R-SPR-15, AC-SPR-18, `delivery/61` R-REL-07.

**D-124 — Neither change set nor insert writes a to-one from a query model (amends D-70 and R-GEN-19; clarifies
D-123).** A query model cannot name a foreign key as a scalar (`MQ3002`, D-44), so a column over a to-one holds the
target entity, whole (`MQ3016`) or converted (`MQ3014`). The engine writes a to-one only by its id type (`MQ1001`), so
the setter `generateChanges` generated for such a column could never succeed, nor could `from` copying it.
`generateChanges` now leaves a to-one column out as `generateInserts` does, and the change set's Javadoc names it with
the reason; the column is still read. An `@UpdateModel` or `@InsertModel` still writes a to-one by id (R-GEN-19,
R-PROC-23). A model that writes a foreign key keeps one of those, or reads an entity that maps the column twice, the
association `insertable = false, updatable = false` and a basic attribute beside it, which both flags write as a plain
column. A whole-entity or converted to-one column (on the root, an `@Join`ed table or a child root, not inside an embedded
value) is read through a LEFT join of the association in every clause of a read (`api/10` R-COL-25), never the implicit inner join of `root.get`, so a row with a `NULL` foreign key
reads back with `null` rather than being dropped; a bulk write takes no join and is unchanged. A converter is never
given that `null`. No new code and
no opt-in: rejected are a `writeForeignKeys` member, a per-field opt-in, and cascading writes
into `@Join` or `@Child` (deferred past 1.0 by D-118; D-14, INV-1, INV-5). → `processor/30` R-PROC-19, `processor/31`
R-GEN-19, R-GEN-21, AC-PROC-20, AC-GEN-28, Q-14. Amended by D-136: a column of the target's id type over a to-one
reads its foreign key (R-COL-29), and `generateChanges`/`generateInserts` write that id form; the whole-entity and
converted forms stay left out. Amended by D-137: a runtime `groupBy` on a whole-entity to-one column is
`MQ1410`.

**D-125 — Insert columns a database fills (`@Generated`, `@ExcludeFromInserts`; RFC 0008 §1-§2).** `generateInserts`
wrote every writable root column, so a `null` overrode a `DEFAULT`. R-PROC-26 now also leaves out a non-id root column
whose attribute carries `org.hibernate.annotations.Generated` with `writable = false`, an empty `sql` and `INSERT`
among its events (its `value` when present and not `INSERT`, Hibernate 6, otherwise its `event`), read by full name
with `getElementValuesWithDefaults`; an unresolved annotation type leaves the column written. Every timestamp
annotation stays written: Hibernate writes a DB-sourced one itself, but an SQM insert applies only the id generator and
the version seed, so leaving it out would write `NULL`. The new `@Incubating` field annotation `@ExcludeFromInserts`
(R-PROC-27, `CLASS` retention, no elements, no constant) leaves any other column out of `INSERT_COLUMNS`, `insert`,
`insertFrom` and `persist` while the model still reads it; the server-time recipe is `insert(rows).set(CREATED_AT,
now)`. Under `persist` an excluded column gets the no-arg constructor's value. `MQ3506` refuses it on a model without
`generateInserts`, on an `@UpdateModel` or `@InsertModel` (an accepted no-op would read as excluded while `NULL` is
written; relaxing later is compatible, the reverse is not), and on a field R-PROC-26 already leaves out; on a
`@Selected` field it is `MQ3021`, on a grouped model only `MQ3505` applies, and on an assigned id `MQ3501` gains a
message. Declined: `@ExcludeFromChanges` (a change set writes only the fields set on it, and "read but never patch" is
authorization, given by a per-endpoint `@UpdateModel`, R-PROC-19), a run-time `skipNulls` (P-3; per-row statement
shapes break the batch and R-WRT-33's key order; SQLite), `InsertColumns#without`, a string list on `@QueryModel`,
skipping every `@ValueGenerationType` or DB-sourced timestamp, and honouring `@ColumnDefault`. A 0.7.0 model whose
entity has such a `@Generated` column stops writing it (`delivery/61`). Ships as 0.8.0 with D-126. → `rfc/0008`,
`processor/30` R-PROC-26, R-PROC-27, `processor/31` R-GEN-34, `processor/32` (`MQ3501`, `MQ3021`, `MQ3506`),
AC-PROC-21, AC-PROC-22, AC-GEN-29, AC-DIAG-13, AC-WRT-40.

**D-126 — Reading many keys (`byKeys`, `findAllByKeys`; RFC 0008 §3).** The executor gains `byKeys(q, keys)` and the
repository `findAllByKeys(q, keys)`, abstract and `@Incubating` (R-REL-07), returning an unmodifiable `Map<K, M>` in
the keys' first-occurrence order; a key with no row, or one `q`'s filter excludes, is absent. Keys convert as in
`whereKey`; rows are matched back by the converted attribute value, every distinct caller key that matched gets an
entry (two keys converting to one value share one model, `afterMap` once), and a row matching no requested value (a
case-insensitive or padding collation, a `BigDecimal` scale) is the new `MQ2005` rather than a silent drop. The keys
are chunked as R-PAG-07 step 2 chunks its keys, without `primaryKeyFirstBatchSize` (`api/12` R-FLT-09's third
library-built key list), each statement built in `Phase.MODEL` so a customizer filters it as it filters `one(q,
key)`; `MODEL_BY_KEYS` would pass caller keys a tenant filter on `MODEL` should stop. All chunks are read and checked
(`MQ2003` for a key matching two rows) before any row is mapped, and the fetch plan runs once. Checks before the
empty-keys shortcut: `NullPointerException`, `MQ2203` (no primary key), then key conversion. An `orderBy` is ignored,
no statement renders one (chunks can't be ordered across statements, and the map is in key order), and the call logs
a `WARNING` once per `ModelQuery`, so one definition serves `list` and `byKeys`. `count`, `one(q)` and `one(q, key)`,
which ignored an `orderBy` silently, now log the same way when called directly (not `page`'s own count), and both
`one` methods stop rendering it (amends R-EXE-05, R-EXE-12). Primary-key-first's `readByKeys` is not reused as it
stands, since it skips unmatched rows. Rejected: `List<M>` (missing keys silent), ordering within chunks (order
changes past one chunk), refusing an `orderBy` (a `list` definition would need an unordered twin), ignoring it silently, overloading `one(q, Collection)` (a composite key is a `List`, a keyless query's key is
`Object`), and translating `MQ2003` into Spring's `IncorrectResultSizeDataAccessException` (D-50, INV-8; the Spring
guide shows a user-side `PersistenceExceptionTranslator` on `code()`). → `rfc/0008`, `engine/20` R-EXE-05, R-EXE-12, R-EXE-13,
`integration/50` R-SPR-15, `api/11` R-QRY-03, R-QRY-10, `api/12` R-FLT-09, `api/15` R-FCH-05, `reference/90`
(`MQ2003`, `MQ2005`, `MQ2203`), AC-EXE-14, AC-EXE-15, AC-SPR-19.

**D-127 — Row locks on reads (`RowLock`; RFC 0009).** A row lock is part of the query definition: `RowLock` is
exclusive or shared × a wait policy (`WAIT`, `NO_WAIT`, `SKIP_LOCKED`), set by `ModelQuery.Builder#lock` or
`ModelQuery#withLock`, `@Incubating`. It is applied only to the statement that returns rows, of a query that reads its
root table alone, through the new `ProviderSupport#locks()` SPI (`LockSupport`). That SPI reports what it renders and
disables follow-on locking; whether a lock renders is a provider fact, as in D-117 (Hibernate 6.6 drops `NOWAIT` and
`SKIP LOCKED` on H2, and 7.x drops them on H2 for `shared()`, so `rendered` decides per strength × wait; on PostgreSQL
`exclusive()` renders `FOR NO KEY UPDATE`). A lock whose wait would be dropped, or that would be deferred (follow-on locking), is refused; a shared lock widened to exclusive (H2's `for update`) keeps its guarantee and is accepted (amended at the M18 gate): `MQ1209` for a
grouped query, `MQ2211` for the read or the statement shape (any join; paging, `count`, `stream`, export; `one(q)` with
skip locked), `MQ4010` for the provider (no `locks()`, an unrendered lock, a multi-table root), `MQ1809` for `persist`
returning. A locked read needs a transaction (`MQ2102`); database lock exceptions pass through unchanged (INV-1). Tier 1
only; MariaDB stays Tier 2 (D-81). Rejected: an `UPDATE`/`NO KEY UPDATE`/`SHARE` strength set (PostgreSQL cannot get a
true `FOR UPDATE` per query), chained `skipLocked()`/`noWait()` (P-2), a per-call lock argument,
`VendorProfile#applyLock` (would name Hibernate hint values, INV-7), locking joined queries (every joined table locked,
or an outer join read unlocked), and a `TypedQueryCustomizer` hook. → `rfc/0009`, `api/11` R-QRY-17, `engine/20`
R-EXE-14, `engine/21`, `vendor/40` R-VND-15, `vendor/41`, `api/14` (`MQ1809`), `reference/90` (`MQ1209`, `MQ2102`,
`MQ2211`, `MQ4010`), `integration/50`, AC-QRY-16, AC-EXE-16, AC-EXE-17, AC-VND-13, AC-PRF-10.

**D-128 — Literal filters and bound function arguments (RFC 0002, trimmed).** A literal filter is `Filters.compare`
or `in`/`notIn` against `Expr.constant`; `Filters` gains fixed-arity field forms `in(col, a, b)`, `in(col, a, b, c)`
and the same for `notIn`, with the value forms' semantics, `@Incubating`. An enum or converted constant is
`Expr.constant(ColumnField typedBy, C value)`: the value goes through the column's converter (`MQ1308`) and the
attribute's JPA mapping through an optional `ProviderSupport` capability reached from core by a `JoinContext` hook
(INV-7), and must store as a `String`, `Number` or `Boolean`, else `MQ1508` (also for a provider without the
capability); `typedBy` is a type witness, part of equality, never a column of the expression. `Expr.value(C)` binds a
function argument as a statement parameter on every statement that renders it; `null` and `Optional`, `Collection` or
`Map` values are `MQ1502`. Constants stay definition text (R-COL-18): each distinct constant is a distinct statement,
so per-request values use `Expr.value`. A column-typed constant is operand-only (a comparison or `in` operand), else
`MQ1508`. The `notIn` field form's `OR col IS NULL` can stop a partial index's predicate from matching; a plain `NOT IN`
is `not(g -> g.in(col, a, b))`. Rejected: `eq`/`ne`/`lt`… field overloads (D-115; a field-form `ne` would
include `NULL` rows where a partial-index predicate needs `<>`), varargs `in` (no `@SafeVarargs` on an interface
method, D-87), a `List` form (a raw `List` binds to `in(col, Collection)`), a public `bind` on `JoinContext`, and
`CAST(? AS type)` syntax (deferred; M19.2 spikes function-style casts). RFC 0002's draft ids (R-FLT-15..26,
AC-FLT-12..24, MQ1303–MQ1306, D-19 as drafted) were never assigned. → `rfc/0002`, `api/10` R-COL-26, R-COL-27,
`api/12` R-FLT-19, `vendor/40`, `reference/90` (`MQ1502`, `MQ1508`), AC-COL-31, AC-COL-32, AC-FLT-20.

**D-129 — Returning writes and conflict extensions (RFC 0010).** `updateReturning`, `deleteReturning` and
`insertReturning` return the written rows through a `ModelQuery` (not a `SelectSet`, R-WRT-48), mapped by
`ReturningColumns`. The statement stays the engine's own mutation; the provider translates it and adds the vendor's
returning form, a `VendorProfile` fact (`SUFFIX` on PostgreSQL and MariaDB delete/insert, `DELTA_TABLE` on H2), through
`ProviderSupport#returning()`. A vendor or provider without it is `MQ4011` before any statement; nothing is emulated
(R-VND-10). The Hibernate translation uses internal types behind one probed adapter per Hibernate line, with a
declared tested range, a first-use check of the translated SQL (`MQ4011` on mismatch) and a nightly canary build.
`ConflictUpdate.setExpression` sees the stored and incoming rows. R-WRT-34 is amended: `onConstraint(name)` and an
arbiter predicate `onConflict(cols).where(…)` (literals through D-128's `ConstantRenderer`, root columns and plain
comparisons only) are offered on PostgreSQL and refused elsewhere (`MQ1804`). `Expr.nextval(seq)` is a profile fact
`sequences()` plus a provider renderer reached by a `JoinContext` hook, refused on MySQL and outside select, assignment
and insert-value positions (`MQ1509`). Rejected: a library SQL renderer for writes (P-5), key-first emulation
(R-VND-10), a `SelectSet` parameter (R-WRT-48), a `VendorProfile` SQL hook for sequences (R-VND-11). → `rfc/0010`,
`api/14` R-WRT-34, R-WRT-48, R-WRT-50..52, `api/10` R-COL-28, `engine/20`, `vendor/40` R-VND-17, `vendor/41`,
`reference/90` (`MQ4011`, `MQ1509`, `MQ1804`, `MQ1809`), AC-WRT-42..45, AC-COL-33, AC-VND-14, Q-15.

**D-130 — Write expressions through `setExpression(column, ExpressionField)` (M20.5).** Amends D-129's `nextval`
positions: M20.4 ships `Expr.nextval` in a select only. A write takes an `ExpressionField` through
`setExpression(column, ExpressionField)` at four sites, the update assignment, the conflict assignment, the insert
values and the insert-select columns, restricted to column-free, bind-free expressions (new `MQ1613`), and rendered in
the provider's own Criteria statement, not a library SQL renderer (P-5). Insert values go through a per-row
expression-factory default on `InsertSupport`. The existing hooks take a `CriteriaBuilder` expression, which an
`ExpressionField` cannot be passed to, so a separate overload is needed. Built in M20.5, after a spike of
`HibernateCriteriaBuilder.sql` inside DML on both Hibernate lines. → `rfc/0010` §4, `api/10` R-COL-28, `api/14`
R-WRT-53, `reference/90` (`MQ1613`), AC-WRT-46.

**D-131 — No locked sub-select write.** No locked sub-select write until a supported Hibernate API can put a lock on a
subquery or a CTE in DML: Criteria subqueries carry no lock, PostgreSQL `IN (SELECT … LIMIT n FOR UPDATE SKIP LOCKED)`
can over-claim, MySQL refuses it (1235/1093), and chunking/`commitEachChunk` would release or re-run the lock. Claiming
rows stays two statements in one transaction: a locked key read, then a `whereKeys` write read back by `updateReturning`
or `byKeys`. → `rfc/0010` §1, `api/14` R-WRT-50, AC-WRT-47, Q-16.
**D-132 — Pre-statement capability checks for M20 SPIs (M20 gate, 2026-10-13).** Every provider capability M20 refuses
on is asked through an explicit `@Incubating` default method that the executor calls before the flush (INV-5):
`InsertSupport.arbiterRendered(EntityManagerFactory, Class<?>, List<Condition>)` (default false, `MQ4011`; the
Hibernate one also renders the arbiter literals, so `MQ1508` fires there), `InsertSupport.computedValuesRendered(
EntityManagerFactory)` (default false, `MQ1613`; replaces reflection override detection) and
`ReturningSupport.check(EntityManagerFactory, Class<?>, List<String> attributes)` (default no-op; Hibernate refuses
`MQ1809` for an attribute not on the written table). Rejected: reflection override detection, brittle under delegation;
refusing at statement build, which is after the flush and breaks INV-5. A moved Hibernate type outside `..internal..`
surfaces as a `LinkageError`, now caught and refused as `MQ4011`. The first-use check of an arbiter upsert is recorded
by the statement before its literals are spliced in, so one statement shape is one entry. A `distinct()` query
selecting an `Expr.nextval` is `MQ1509`, as a group key over one is. Amends D-129 and D-130. → `api/10` R-COL-28,
`api/14` R-WRT-50, R-WRT-52, R-WRT-53, `vendor/40` R-VND-14, R-VND-17, `reference/90`.

**D-133 — Target vendors and a portability dry run (RFC 0011, accepted 2026-10-13).** An application declares its
target vendors and startup fails with `MQ4012` when the connected vendor is not one of them. Portability is decided by
a dry run, not by the processor: the real executor on a dialect-booted factory over a stub `DataSource` decides the
refusals the spec places before any statement (Q-17 lists the rest), with no container and no second implementation.
The stub records JDBC access per call and denies by default; a call is portable on a target only if it reached a
statement with no refusal. A call that reaches none, refuses after one, or throws anything else fails the check. The
harness boots with application-supplied properties, refuses an explicit `vendor(...)` (stripped, not refused: D-138) and targets outside a
declared list, and substitutes its own `ChunkTransactions`. `offlineProperties(DatabaseVendor)` returns `Optional`; empty is
`MQ4013`. The dry run lives in a new test-scope module on `jpa` (INV-7 amended); `model-query-test` stays core-only
(D-98). Rejected: processor checks and a pure refusal function. Amended at M23 kickoff (2026-10-14) after the
architect review: the INV-5 premise (loud over silently wrong, not every refusal before a statement), the five
outcomes, the recording stub, unit fidelity, the Spring `BeanPostProcessor` check, the factory cache key. M23,
0.9.0. → `rfc/0011`, `reference/90`, `delivery/61`, Q-17, Q-18.
*Spike (2026-10-14).* Hibernate 6.6.58 and 7.4.11 boot the TCK unit (39 entities: sequences, table and identity
generators, `@Formula`, secondary tables, `@Subselect`) offline for H2, PostgreSQL, MySQL and MariaDB, through both
`Persistence.createEntityManagerFactory(name, overrides)` and `createContainerEntityManagerFactory`, with zero JDBC
calls, given these settings beyond the RFC's: H2's `GlobalTemporaryTableStrategy.prepare` asks for a connection once per
multi-table entity and swallows the failure, so the boot looks clean unless the stub records it; it needs
`hibernate.query.mutation_strategy.global_temporary.create_tables` and `.drop_tables` `false`. A unit's
`hibernate.connection.provider_class` bypasses the stub DataSource (6.6 fails the boot, 7.4 fails the first call with
nothing recorded), so it is overridden with `""`. A unit's schema-generation `scripts.action` writes a DDL file without
a connection, so the generic overrides set it `none` too; the JPA `database.action` `none` beats a unit's
`hbm2ddl.auto=create` and a `javax.` action, and with no override `create` fails the boot at a recorded
`getConnection`. A unit's `hibernate.dialect` silently beats the product name (`H2Dialect` at version 15.4 on a
PostgreSQL target); an override of `""` or the target's dialect class restores product-name resolution. The unit's
`hibernate.connection.url`, `jakarta.persistence.jdbc.url` and JNDI `hibernate.connection.datasource` are ignored
under the stub DataSource override: no driver connect, and calls reach the stub. With
`allow_jdbc_metadata_access` unset, both lines call `getConnection` at boot and fail, so the flag is honoured.
`database-product-version` alone sets the full version; a major alone loses the micro (H2 `2` → 2.0). With no
version the dialect minimum is used: H2 2.1.214 on both lines, PostgreSQL 12.0 (6.6) and 14.0 (7.4), MySQL 8.0, and
MariaDB 10.4 (6.6) and 10.6 (7.4). In a call, `prepareStatement` records and throws, and a read, a bulk update, a
sequence draw (MySQL: the table emulation through isolated work) and a model-query `list` over a `@Formula` join all
surface as `GenericJDBCException` caused by the stub's `SQLException`, with the SQL in the recorder; no other
`Connection` or `DatabaseMetaData` call was made. A Spring Boot app supplies the class-list form, its names from Boot's
`PersistenceManagedTypes` (or `PersistenceManagedTypesScanner` with no context), with Boot's naming strategies and
`spring.jpa.properties` through `properties(...)` and `spring.jpa.mapping-resources` through `mappingFiles(...)`.

*Decided after the spike (2026-10-14).* Hibernate's `offlineProperties` overrides `hibernate.dialect` with `""`, so
the production resolver path (including an application's `hibernate.dialect_resolvers`) picks the target's dialect; an
application's custom dialect subclass is not used in the dry run, and the docs say so. It also sets
`hibernate.query.mutation_strategy.global_temporary.create_tables=false` and `.drop_tables=false` and
`hibernate.connection.provider_class=""`; the generic overrides add
`jakarta.persistence.schema-generation.scripts.action=none` and send the full `database-product-version`. A boot's `MQ4013` is read from the recorder, not from a boot failure.
`Target.of(vendor)` stays, with the per-line dialect minimum documented; the docs steer to `Target.of(vendor, version)`.
A starter helper that builds a Boot app's class list and properties is deferred past M23 (`delivery/62`).

*Amended (2026-10-14, M23.2).* `MQ4012` names
the factory through a new `@Incubating` `ProviderSupport.factoryName(EntityManagerFactory)` (default empty), not by
reflection: Hibernate answers the session factory name read through `SessionFactory.getSessionFactoryOptions()` on
both lines (6.6's `SessionFactory` has no `getName()`), empty when null or blank. With no answer the name is the
factory's class and identity: neither JPA 3.1 nor 3.2 defines a portable property key for the unit name. Spring keeps
its `bean '<name>': ` prefix (R-VND-19, R-SPR-17).

*Amended (2026-10-14, M23.2 review; decision (b)).* Spring's startup check is property-only. It is enabled by
`modelquery.target-vendors` alone and checks each eager factory against that list; it resolves no `ModelQueryConfig`
bean and calls no `ModelQueryConfigurer`, so it looks up no bean, meets no creation cycle and needs no deferral
(this replaces the earlier choice to build each factory's config through the configurer, and the deferral to
`afterSingletonsInstantiated`). Targets declared in code are enforced by `ModelQueryExecutor.create` when a repository
is created; a configurer can narrow the property's list but not widen it past the startup check. Several failing
factories still share one `MQ4012` through the post-processor's sweep. Decided by the user (R-SPR-17).

**D-134 — External model bindings (RFC 0012, accepted 2026-10-13).** A model may live in a module that cannot see its
entity: `@BindQueryModel`, `@BindUpdateModel` and `@BindInsertModel` (`@Incubating`, repeatable, on a type or a
`package-info`) in the persistence module name the model and its root, and the processor reads the model from the
classpath, validates it as an annotated one and generates its QModel in the binding's package (the model and its
accessors public). `@BindExpression` supplies the `ExpressionDefinition` of a valueless `@Computed` or an `@Aggregate`;
`@Computed.value` defaults to `void.class`. Nested models are annotated or bound in the same compilation. No runtime
change. New R-PROC-28..31, R-GEN-35, `MQ3023`..`MQ3026`. M24, 0.9.0. → `rfc/0012`, `processor/30..32`, Q-19.
**D-135 — Generated-code nullness (2026-10-13).** The processor option `-Amodelquery.jspecify=true` (default off)
marks every generated QModel and change set JSpecify `@NullMarked`; change-set backing fields, setter and fluent
parameters, getters and the `assignment` value are `@Nullable` (type-use, so `Outer.@Nullable Inner`); the private
mapper and the `JoinField`/`ChildField` `with` methods are `@NullUnmarked`, since they pass null into the user model
by design (R-GEN-07). `ColumnField` type arguments stay unannotated while `core` is. No model-query artifact depends
on JSpecify: the processor names the annotations by string, and the option without JSpecify on the compile classpath
is `MQ3027`. Off, the output is byte-identical. New R-PROC-32, R-GEN-36, `MQ3027`. M21, 0.9.0. → `processor/30..32`,
`reference/90`, `mvp-plan` §22.

**D-136 — A query model reads a to-one by its id (Q-14, 2026-10-13; amends D-44, D-124 and R-WRT-48).** A query-model
column over a to-one whose type is the target's single basic id type (`@Column(attribute = "referrer") Long referrerId`,
the update model's spelling, R-GEN-19), or whose converter's attribute type is that id type (`ColumnConverter<Long,
CustomerId>`), reads the foreign key instead of `MQ3002`; another type is `MQ3002` naming the id type, as `MQ3305` does
on a write, and the processor and core check the converter the same way (INV-3). The processor refuses with `MQ3002` and
the reason, naming the annotations as strings (D-135): the inverse (`mappedBy`) side, a non-primary-key
`referencedColumnName`, `@JoinColumns`, `@JoinFormula` and `@JoinColumnOrFormula`, `@NotFound`, a target whose id is an
association (derived identity), a target with `@IdClass` or `@EmbeddedId`, and a to-one inside an embedded value. The
dotted `referrer.id` stays `MQ3001`, so the spelling is one. In every clause of a read (select, filter, order, group,
keyset, the primary-key-first phases, `count`, export, and the source select of an insert-select) the id form reads
through a `LEFT` join and its id (R-COL-25), on every mapping, so a `NULL` foreign key reads back `null` and no row is dropped (INV-4, INV-5); a locked read of it is
therefore `MQ2211` (R-QRY-17, D-127). A hand-written `ColumnField` of the id type over a to-one reads the same, instead
of `MQ1001`, whose message now names the id type; core requires a single id of persistence type `BASIC`. A bulk-write,
sub-select-write or `throughEntities` filter renders the foreign-key column (`from.get(toOne).get(id)`), which takes no
join only when the foreign key references the target's primary key (the M22.1 spike: a non-primary-key
`referencedColumnName` renders an inner join on every vendor, dropping `NULL`-foreign-key rows from `isNull` and
`notIn`); so the write path asks the provider (`ProviderSupport`, a provider fact as in D-117 and D-132) whether each
id-form column in a write filter references the target's primary key, and refuses it before any statement with the new
`MQ1004` when the provider says no or cannot tell. A returning write's id-form column (R-WRT-48, amended) is sent as the
path `referrer.id`, which the Hibernate module already maps to the foreign-key column and refuses as `MQ1809` when it
does not reference the primary key. The set clause is unchanged (already by id). Generated models never reach `MQ1004`,
since the processor refuses those mappings. A class model's primitive id field over an optional to-one follows the
existing null-to-primitive rule. Since the query model now names the foreign key as a scalar, `generateChanges` and
`generateInserts` write the id form as an `@UpdateModel` and an `@InsertModel` do (R-PROC-26's insertable rule applies),
and `from` copies it (AC-GEN-28 amended, no `MQ1607`); the whole-entity and converted-entity forms stay left out
(D-124), and the D-124 double mapping stays valid but the docs recommend the id form. `MQ3016`'s message also names the
id form. `@GroupBy` on a whole-entity to-one column is the new error `MQ3028`, since Hibernate groups by the foreign key
and selects every entity column (PostgreSQL refuses it); its message names the id form. A hand-written runtime `groupBy`
on a whole-entity column is accepted as a vendor-dependent database error, and `MQ3028` breaking an existing `@GroupBy`
source is noted in the CHANGELOG. Rejected: the foreign-key column with no join in a read, chosen by the spike for every
mapping (core sees only the JPA metamodel, so on an inverse, non-primary-key, formula or `@NotFound` mapping Hibernate's
implicit join is inner and drops rows; Q-20 keeps a provider-reported shortcut open); grouping a whole-entity column by
its id implicitly; a `@ForeignKey` annotation; accepting the write filter's join on a non-primary-key mapping, or
refusing only `isNull` and `notIn` there. New R-COL-29, R-PROC-33, `MQ1004`, `MQ3028`; AC-COL-34, AC-COL-35, AC-PROC-28,
AC-GEN-33. M22, 0.9.0. → `api/10`, `api/11`, `api/14`, `processor/30..32`, `reference/90`, `mvp-plan` §23.

Amended at the M22 gate (2026-10-14): the sub-queries of a read (`exists`, `in(subSelect)`, `exists(subSelect)`) read the
id form through the `LEFT` join as the outer query does. `MQ1004` covers every clause the write statement renders: the
`where`, the set clause (a `setExpression` or computed target and the columns read in it) and an upsert's
`doUpdate(...).where(...)`. The provider fact is `ProviderSupport.foreignKeyColumn(emf, entity, toOne)`, returning the
`@Incubating` enum `ForeignKeyColumn` (`LOCAL_COLUMN`, `JOINS`, `UNKNOWN`, default `UNKNOWN`) in place of
`referencesPrimaryKey`: `LOCAL_COLUMN` means `toOne.id` renders as a column of the entity's own table, with no join and no
formula, referencing the target's primary key; `model-query-hibernate` answers it only for the key side, a reference to
the primary key, no not-found action on the mapping (orm.xml counts), no formula part and a key on the entity's own
table, so a `@JoinFormula` write and a `@SecondaryTable` or `@JoinTable` key are `MQ1004`; core receives the answer as
`ForeignKeyLookup`, and the `MQ1004` message says whether the provider reports it is not or cannot tell. The processor
refuses `@JoinTable` and `@JoinColumn(table = "...")` with `MQ3002`, and refuses the id form over a target with
`@SQLRestriction` or `@Where` (on the class or a mapped superclass) with the new `MQ3029`: a read takes the id from the
`LEFT`-joined row, so a restricted target reads `null` while the foreign key holds a value, and `generateChanges` with
`from(row)` would write `NULL` over a live key; `MQ3016` does not suggest the id form there. `MQ3029` stays until Q-20
resolves. A runtime `@Filter` on the target is a documented caveat: the read then returns `null`. New `MQ3029`.
Amended by D-137: a runtime `groupBy` on a whole-entity to-one column is `MQ1410` (the id form stays allowed).

**D-137 — A runtime group by a whole-entity to-one column is refused (2026-10-14; amends D-136).** `groupBy` or a group
key over a whole-entity to-one column (the D-124 form) is the new `MQ1410`, before any statement, since Hibernate groups
by the foreign key and selects every entity column (PostgreSQL refuses it), as `MQ3028` refuses `@GroupBy` at compile
time. The id form (D-136) stays allowed. This replaces D-136's acceptance of a runtime `groupBy` on a whole-entity column
as a vendor-dependent database error. Decided by the user at the M22 gate. New `MQ1410`. M22, 0.9.0. → `api/13`,
`reference/90`.

**D-138 — Refusals a dry run must see run before the first statement (resolves Q-17, 2026-10-14).** Of the library's own
refusals, only a fetch plan's child builds (`MQ1306`, `MQ1307`, the `MQ15xx` codes and the child's own refusals) and the
primary-key-first step-2 batch and bind check (`MQ1307`) depended on the vendor and ran after a first statement. Both
now run before any statement: a child is built and checked for a one-key round (memoised per factory and profile once
passed) on every model-returning call, including an empty page, which a broken child now refuses (stricter, INV-5); `page` and
`export` size and check step 2 up front, on every call that can reach it (not memoised). The `page(..., COUNT)` content statement is left where it is (it is on the
limits list). What still follows a first statement depends on data (`MQ2201`, `MQ2202`, `MQ2003`, `MQ2005`, `MQ2205`,
`MQ2210`, `MQ2601`, `MQ2603`, `MQ2604`, `MQ2002`, `MQ2503`) or on the provider translating a later statement; those
operations are on R-INS-12's limits list and reported "first statement only". The sweep marks a definition "first
statement only" from public facts (chunk options, entity mode, child loads, `primaryKeyFirst()`, `CountMode.COUNT`, a
values insert whose id is drawn, an update or delete on a profile without `targetTableInSubquery()`), erring towards
marking; a `call` prints its reached SQL with no qualifier, and R-INS-11 says `REACHED(sql)` certifies the refusals
before the first statement and that statement's rendering only. The sweep runs `persist` by default (every library
refusal precedes `em.persist`). Holders stay explicit, with no package scan (P-7). Rejected: a `ProviderSupport`
translate SPI to pre-translate later statements (new `@Incubating` surface for drift checks only; revisit if AC-INS-11
finds a gap). Harness choices, decided by the user at the M23.3 gate: an explicit `config.vendor(...)` is stripped from
the harness's copy, not refused; the harness's rolling-back `ChunkTransactions` replaces the application's only when it
configured one (with none, `commitEachChunk()` reports the application's `MQ4004`); a throwable from the factory's
provider package is `REFUSED`, labelled by its class; setup mistakes (no target, no call, an empty holder, a bad `skip`,
an empty class list) are an `AssertionError` before anything runs, with no `MQ` code; `assertPortable()` fails on every
outcome but `REACHED`, `REFUSED` included; a skipped definition prints one report line with its reason. Decided by the
user at the M23.3 audit stop point. → INV-5, D-82, D-132, D-133, `api/15` R-FCH-02, R-FCH-04, `engine/21` R-PAG-07,
`api/16` R-INS-11, R-INS-12, `rfc/0011` §3-§4.

**D-139 — `model-query-portability` and `jpa.vendor` (2026-10-14).** `model-query-portability` uses the not-API
`jpa.vendor` members `VendorResolver.productNameOf`, `VendorResolver.resolve`, `VendorResolver.withSupplied` and
`ResolvedVendor.profile()`, and is released and resolved at `model-query-jpa`'s exact version through the BOM; the INV-7
rule allow-lists those two types and no other `jpa.vendor` type. The Spring startup check of D-133 leaves a factory with a
bootstrap executor to run after the singletons are instantiated, so a deferred JPA bootstrap stays in the background, and
reports an application `ModelQueryConfig` bean as `MQ4006` before any `MQ4012`. → INV-7, R-INS-10, R-SPR-17, `delivery/61`.

**D-140 — A bound model read from the classpath, and incremental builds (M24.1 spike, 2026-10-14).** On JDK 17 and 21,
javac gives a model compiled in another module, through the `Element` API, everything the processor reads from a source
model: its instance fields in declaration order with their generic types; every `CLASS`-retained model-query annotation
with the values written on it (a member left at its default is absent from both) on the field and, for a record, on the
record component; type-level annotations with class-literal and enum values; a record's accessors and canonical
constructor, whose parameter names are kept (a class's setter parameters read `arg0` without `-parameters`; the
processor reads neither). A type-use annotation, such as JSpecify's `@Nullable`, is not read from a class file before
JDK 22 (JDK-8225377); the processor reads none from a model, so this changes nothing it generates. `ClasspathModelTest`
keeps the source-against-class-file comparison as a regression on both JDKs. The processor is `isolating` by default, with one
exception (below): each generated file has one originating element, the binding type or its `package-info`, and Gradle (8.11 and
9.7.1) records the model a binding names by class literal as a dependency of the binding, so a change to the bound
model's ABI recompiles the binding and regenerates its QModel, even one that does not name the model. `aggregating`
would add nothing: a change confined to the annotations on a model's private fields or record components is outside
Gradle's compile-avoidance ABI, so Gradle skips the persistence module's compile under either declaration and leaves its
QModel stale until a clean build, as it already does for a classpath nested model (D-45). Maven (maven-compiler-plugin
3.16.0) recompiles the module on a changed dependency and regenerates it. A module where a model nests a bound model whose binding sits in another source file (always the case for an annotated
outer model nesting a bound one) sets `modelquery.aggregating=true` (R-PROC-34): the processor is registered with Gradle
as `dynamic` and then reports `aggregating`, because the binding is not reachable from the outer model's originating
element, so an isolating recompile of the outer model alone would not see it (false `MQ3005`, `MQ3401` or `MQ3026`, or
an outer QModel naming a removed class). The "Models in an API module" docs (M24.4)
state the Gradle limit and its workaround: the API module's classes declared a full-content input of the persistence
module's `compileJava` (`inputs.files(configurations.runtimeClasspath).withNormalizer(ClasspathNormalizer)`), which
recompiles that module in full when they change. Supersedes `rfc/0012`'s "declares itself aggregating for bindings". →
D-45, D-107, `rfc/0012` "Unresolved questions", R-PROC-30.

**D-141 — Bound update and insert models, and binding expressions (M24.2, 2026-10-14).** `MQ3023` counts bindings per
kind: one model may be bound once as a query, once as an update and once as an insert model. A binding that would
generate a class of one qualified name (a QModel, or a change set, whose name no prefix changes) with another binding of
the compilation, of the same model or of another (two models of one simple name bound into one package), or with an
annotated model of the compilation, is `MQ3023` on each such binding, and none of those bindings generates anything,
rather than the `Filer` refusing the second file; the annotated model still generates its own, and a prefix or package
keeps the names apart. The processor remembers the names the annotated models it has read generate, in this round and
earlier ones; an annotated model first seen in a later round than the binding, and a class of that name already on the
classpath, are left to the `Filer`'s error. A bound model carrying any model annotation stays `MQ3023` (`rfc/0012` §1).
The public-access check of R-GEN-35 follows what the generated classes call: a query model's mapper needs the no-arg
constructor, setters and `@Join` getters; a change set's `from` and the insert members need the getter of each column
they read (the columns `ChangedColumns` and `InsertedColumns` find, so the check runs after the model validates); an
update model is never instantiated or read, so its class needs only to be public. A `@BindExpression` goes to the
field's `@Computed` when it carries one, else to its `@Aggregate`; a valueless `@Computed` reads as `void` and is
`MQ3025` on a query model, while an update or insert model refuses any `@Computed` with its own code (`MQ3302`,
`MQ3502`). A second `@BindExpression` for one field is `MQ3025` too, rather than one silently winning. `MQ3506`'s
message names `@BindQueryModel` for a bound model. `@BindUpdateModel` and `@BindInsertModel` take no `expressions`,
since neither kind accepts `@Computed` or `@Aggregate`. → D-134, D-140, R-PROC-28, R-PROC-29, R-GEN-35.

**D-142 — Nested models of a bound model, and bindings across rounds (M24.3, 2026-10-14).** A nested model (`@Join`,
`@Child`) is one annotated `@QueryModel` or one a `@BindQueryModel` of the compilation binds. The lookup is the
compilation's, not the outer model's, so an annotated model may nest a bound one too, and a QModel names a bound nested
QModel in its binding's package. A model both annotated and bound nests as the annotated one (its binding is `MQ3023`);
a model bound twice nests through its first binding while both report `MQ3023`. On a bound model a nested type that is
neither is `MQ3026` on the field rather than `MQ3005` or `MQ3401`, so the message can name the binding, and a nested
type that is not public is `MQ3024`, since the outer QModel names it from another package. A binding whose model's
`root`, or a nested model's, names no class yet is deferred as D-107 defers an annotated model: the processor keeps
where each binding is written, its type or package by name, and reads the bindings again each round, to retry the
deferred ones and so that a nested model bound in an earlier round is still found; the last round reports `MQ3017` on
the binding. The processor also remembers the names the bindings it generated in earlier rounds generate: a deferred
binding, or a binding of a later round, that would generate one of them is `MQ3023` on it, and the earlier QModel
stands; a binding deferred before its own `root` reads has no names yet and is checked when it is retried. Rejected:
`MQ3017` at once (a persistence module whose entities another processor generates could bind nothing); letting only a
bound model nest a bound one (an annotated model of the persistence module could not join an API model). Accepted limit: a failed nested
binding (for example `MQ3023`) generates no QModel, so the outer QModel names a missing class and javac adds "cannot
find symbol" beside the real diagnostic, as for annotated nesting (R-GEN-04, D-39). Binding a
nested model in another compilation stays Q-19. → D-45, D-107, D-134, D-141, R-PROC-30, R-PROC-31, R-GEN-35.

**D-143 — Source models (RFC 0013, accepted 2026-10-15).** A query may read from a CTE whose shape is a
`@SourceModel(root = …)` (`@Incubating`), checked at compile time like an entity root: window fields (`@Window`,
`WindowField` constants), `union`/`unionAll`/`intersect`/`except` over branches of one shape with `@Discriminator`
keys, and recursive sources (`@Depth`, a typed `childKey`/`parentKey` step and a required `maxDepth`). Sources are
CTEs that `jpa` builds from standard Criteria bodies and registers again on every statement, through
`ProviderSupport#sources()` and a jakarta-typed `SourceSupport` (`MQ4014` without one); `VendorProfile#sources` and
`maxRecursionDepth()` gate each vendor (`MQ4015`, at `maxDepth + 2` above the ceiling, since MySQL counts the final
empty iteration). A source key is the root id or the full non-nullable group set, plus any discriminators; it is
unique per row by construction, or the source is non-unique and refused for keyset, primary-key-first (`MQ1901`),
export, `byKeys`, key lookups, fetch plans and enrichers (`MQ2212`); offset paging over it breaks ties on every
selected column. A to-many filter inside a source is an explicit `exists` (`MQ1906`). Order-sensitive windows are
made deterministic by appending the source key; frames are explicit, `FIRST_VALUE` and `LAST_VALUE` take `ROWS`
frames, `LAG` and `LEAD` take none. Recursion fails loudly at its depth limit and on cycles (`MQ2701`, `MQ2702`),
checked by scalar sub-selects in the statement that reads the rows; the race left on an empty result under READ
COMMITTED is documented. The Tier-1 MySQL floor rises to 8.0.31. Rejected: Blaze-Persistence's `@CTE` entities and
SQL splicing (a CTE would need a mapped entity, and the SQL would bypass the provider). The acceptance spike passed on
Hibernate 6.6 and 7.4 with H2, PostgreSQL and MySQL 8.0.31 (`rfc/0013-spike-results.md`). New R-SRC-01..12,
R-PROC-35..39, R-GEN-37, R-GEN-38, R-VND-21, R-VND-22, `MQ1901`..`MQ1906`, `MQ2212`, `MQ2701`, `MQ2702`, `MQ3030`..`MQ3039`,
`MQ4014`, `MQ4015`. M25, 0.10.0. → `rfc/0013`, `api/17`, `processor/30..32`, `vendor/40`, `vendor/41`, Q-21, Q-22.

**D-144 — How a source query is built (M25.1, 2026-10-15).** A source's structure is fixed where it is built and only
predicates are added: `SourceQuery.builder(model, root, fields, key[, groupKeys])` returns an immutable
`SourceQuery.Builder<E, M, S>` carrying `where`, `having` and `build()`, with no `select` (a source carries every field
of its model) and no regroup or re-key. An outer query takes the source through `ModelQuery.builder(root, mapper,
source)`, every `Builder` copy (`withLock`, `withFetch`, `orderedBy`) carries it, and `ModelQuery.source()` reads it
back; a source root reaching the two-argument builder, or an entity root or join the three-argument one, is `MQ1203`,
as `ModelUpdate.builder`, `ModelDelete.builder`, `InsertColumns.of` and `ModelInsert.select` reading a source are.
`TableField.source(model)` is the source root kind, and `ColumnField.over(model, root, sourceConstant)` its typed
column: the source constant's property becomes the CTE column alias and its converter is copied, so the outer constant
is an `OrderedColumnField` exactly when the source's is. A hand-written `ColumnField.of` or `TableField.join` over a
source root is `MQ1905`. An outer model owns no CTE column of its own, so its columns read the source's fields by name
alone: a `@FilterColumn` is `MQ3011`, a `@Column(converter = ...)` is `MQ3014`, a `@Join` is `MQ3035` and a `@Child`
on either side of a source root is `MQ3030` or `MQ3039`, because a source is one statement with no parent query and no
association to join. A source model, and a model rooted at one, generate no `changes()`, `update(...)`, `from(...)` or
`delete()`: a write needs an entity root, so `generateChanges` is ignored there (Q-23 asks whether it should be a code
instead). Rejected: a generated builder per source (it would put the structure back in the QModel, and
the processor is the only place the structure is checked), a `Builder.source(...)` setter (a source is not a setting of
an entity-rooted query), and a string `sourceOf("...")` (a string is exactly what `MQ1905` refuses, since nothing
checks it against the source's metamodel). → `api/17` R-SRC-10, `api/10` R-COL-30, `processor/30` R-PROC-35,
R-PROC-39, `processor/31` R-GEN-37, D-143.

*Amended (2026-10-15, M25.2; user decision).* A `@Child` on a model rooted at a source is legal, as RFC 0013 §7 and
R-SRC-10 say: its parent-side key names a source field and reads the CTE column by that name. `MQ3039` stays only for a
`@Child` whose child model is rooted at a source, and a `through` child on a source-rooted parent is `MQ3406` (no entity
root to join along; use `foreignKey`). → `processor/30`, `processor/32`, `api/17` R-SRC-10.

*Amended (2026-10-15, M25.5; user decision).* `SourceSupport#recursive` and `SourceRenderer#recursive` return an
`@Incubating` record holding the CTE root and the two check expressions the provider builds over the CTE through
`JpaSubQuery.from(cte)`: `min(key)` over rows past `maxDepth` (`MQ2701`) and `min(key)` over marked rows (`MQ2702`),
which also gives the first offending key. RFC 0013 §8's `Root<Tuple> recursive(...)` could not return them, and §5
puts them behind that one hook. Rejected: a second abstract method building the checks from the root (one feature
split over two SPI methods). A recursive model's generated `source()` calls `Builder#depth(LEVEL)`, as a branch's calls
`shape`/`discriminator`; `MQ1902` also covers `recursive(...)` on a model with no `@Depth` and a `@Depth` model built
without it; `@Depth` outside a `@SourceModel` is `MQ3037`, on a branch or a shape `MQ3034`; a recursive `SourceQuery`
handed to `union`, `unionAll`, `intersect` or `except`, on either side, is `MQ1904` where the operation is built (RFC
0013 §4: a recursive model can't be a branch). → `rfc/0013` §8, `api/17` R-SRC-07, R-SRC-08, `processor/30` R-PROC-37.

**D-145 — What else 0.10.0 ships before the freeze (2026-10-15, user decision).** After a gap review against
Blaze-Persistence, 0.10.0 also closes the gaps that fit this library's design and unblock the most list screens, each
as its own milestone with an RFC and an acceptance spike first: M26, a recursive source over a tree counted as unique
(Q-21, `rfc/0014`); M27, per-row correlated sub-query columns (lifting D-112's root-only outer columns) and sources
over sources with a source read directly (Q-22, `rfc/0015`); M28, query models over a polymorphic entity root through
`cb.treat` (`rfc/0016`). Q-23 is resolved in M25.6: `generateChanges` on a source model or a model rooted at one is a
compile-time code, not silently ignored. The freeze moves from M26 to M29 and stays last. Everything new is
`@Incubating`, so the M30.1 freeze review decides each type. Left out, as not fitting: updatable entity views with
dirty tracking (explicit writes, `rfc/0005`), lateral joins (H2 has none; a window source covers top-N per parent),
MULTISET-style fetch strategies (batched fetch plans with row caps cover them), `VALUES` sources and derived tables
(`byKeys` covers the first; the second is a `SourceSupport` rendering choice), DML in a CTE (Q-16, PostgreSQL-only
among Tier 1), and Querydsl, DeltaSpike and JAX-RS modules. → `docs/plan/mvp-plan.md` §26–§29, `delivery/62`, Q-21,
Q-22, Q-23.

**D-146 — Source CTE columns are engine-named; one source hook type (M25 gate, 2026-10-15, user decision).** A source
CTE's columns are named `mq_<property>` by `SourceQuery.columnName`, used by the CTE body and by `ColumnField.over`, so
no field name can collide with a vendor reserved word (INV-6): a `year` or `order` field no longer fails on H2. The
properties, sort matching and generated code are unchanged. `jpa.spi.SourceSupport` is removed: `ProviderSupport#sources()`
returns `Optional<core.SourceRenderer>` (the `ConstantRenderer` precedent), whose `cte` is abstract and whose `window`,
`combine` and `recursive` default to `MQ4015`. Rejected: quoting the names (not portable through Criteria aliases, P-5);
a compile-time reserved-word list (vendor knowledge in the processor, INV-6, INV-7); a documented limit (fails on H2 for
`year`, a Tier-1 database). The gate also tightened the builder: `Builder#discriminator(ColumnField<M, ?, String>,
String)` takes a key column (else `MQ1904`), `Builder#depth(ColumnField<M, ?, Integer>)` a column of the fields (else
`MQ1902`), `combine` is `MQ1904` when its two sides' fields differ in order, property, type or converter, and a
recursive step's `childKey` and `parentKey` share one column type, so a mismatched pair does not compile. Discriminator
values are computed per node (a leaf {v}; `union`, `unionAll` L u R; `intersect` L n R; `except` L) for the `MQ1904`
clash check and for uniqueness; the clash check is skipped for a distinct `union` whose leaves all read one model. A
distinct `union` of one branch model is unique only if it is ungrouped, has no `@Window` field and has equal
discriminator values. → `api/17` R-SRC-06, R-SRC-10, R-SRC-12, `vendor/40` R-VND-21, `delivery/61` R-REL-07.

**D-147 — Trees as unique sources (RFC 0014, 2026-10-16, user decision).** Resolves Q-21. A recursive source built
with `SourceQuery.Builder#tree(step, maxDepth)` instead of `recursive(step, maxDepth)` claims one row per node, so it
is unique and lifts `MQ1901` and `MQ2212`: keyset paging, primary-key-first, `byKeys`, `one(q, key)`, export and fetch
plans work on it. Every statement over it carries a third check beside M25.5's two, a scalar sub-select
`group by key having count(*) > 1` over the whole CTE naming the smallest duplicated key (`MQ2703`), read after
`MQ2701` and `MQ2702` and before any other check or mapping, in one form on every vendor. Every `Tuple` read over a
source goes through one engine check site, the `BuiltQuery` overloads of `rows`, `single` and `resultStream` (an
ArchUnit rule). No state is kept between statements: each checks the whole tree as of its snapshot, so a write that
breaks the tree raises on the next statement, pages already delivered stay delivered, and REPEATABLE READ around the
call is the full guarantee, the one tables have; a walk ordered by `@Depth` or another derived value can skip or
repeat a moved subtree under READ COMMITTED, a documented limit. A count over groups over a source that carries checks
counts client-side, checked on the group rows and unchecked on zero groups, as `list` is on an empty result;
`ProviderSupport` is unchanged (the spike showed `createCountQuery()` can't carry the checks). This is also the M25.5
fix for a `recursive(...)` source's count over groups, which dropped the checks. A composite node key is a vendor fact,
`SourceFeature.RECURSIVE_COMPOSITE_KEY`, false on H2 (Hibernate's multi-column `cycle` emulation fails there), so it is
`MQ4015` before any statement on H2 for both calls. A `SourceRenderer` that returns no duplicate-key check for a tree is
`MQ4014`'s new reason. H2 re-evaluates each check per outer row, a documented test-database limit; the cycle check
stays. Rejected: every recursive source unique (link-table walks repeat keys by design); a `uniqueNodes()` modifier
(P-2); a temporary table, a tree fingerprint, or refusing export and keyset across statements; a grouped-count SPI
taking the query's parts. → `rfc/0014`, `api/17` R-SRC-08, R-SRC-09, R-SRC-13..15, `engine/20` R-EXE-03, R-EXE-05,
`engine/21`, `vendor/40` R-VND-21, Q-21.
Amended 2026-10-16: the pre-tree constructors of `RecursiveCte` and
`RecursiveSpec` are dropped before 0.10.0 (nothing shipped).
A customizer that drops a source's check columns raises `MQ1205` (R-QRY-11, AC-QRY-10), not a new code,
after the customizer runs and before any statement.

**D-148 — Custom filter operators join 0.10.0 (2026-10-16, user decision).** RFC 0017's typed, named
`FilterOperator` (`Filters.op`, `Having.op`) and its `model-query-hibernate` catalogue ship in 0.10.0 as M29, after M28,
with an RFC acceptance spike first (M29.0); the method is named `op`. It is the extension point for operators the
library doesn't ship once D-85 seals `Filters` and `Having`, so it lands before the freeze. The freeze moves from M29 to
M30 and stays last, and the 0.10.0 release prep moves from M28's last slice to M29's. Everything new is `@Incubating`,
so the M30.1 freeze review decides each type. → `rfc/0017`, `docs/plan/mvp-plan.md` §29–§30, `delivery/62`, D-85,
D-145.

**D-149 — A source over a composite id compiles (M26.1, 2026-10-16, user decision).** `MQ3031` checks an ungrouped
source's `@PrimaryKey` against the root entity's id as a whole, as `api/17` R-SRC-02 states, instead of also requiring
one key column: a source over an `@IdClass` or `@EmbeddedId` entity compiles with every id column as its key, each id
column named by exactly one `@PrimaryKey`. This is what makes a recursive source over a composite node key reachable, so
`SourceFeature.RECURSIVE_COMPOSITE_KEY`'s `MQ4015` on H2 and its `PortabilityCheck` report land with M26.1 (D-147,
AC-SRC-21's H2 half). → `api/17` R-SRC-02, R-SRC-11, `processor/30`, `vendor/40` R-VND-21.

**D-150 — Correlated columns and sources over sources (RFC 0015, 2026-10-17, user decision).** Resolves Q-22. A
per-row correlated value is an `ExpressionField` built by a static `Correlated` terminal (`count`, `exists`, `min`,
`max`, `sum`, `first`), each method bounding its own type (`Comparable` for `min`/`max`, `Number` for `sum`), declared
through `@Computed` and rendered as a scalar sub-query in every position; there is no join lowering (the spike: with the
child column indexed the scalar form is within a small factor of a grouped join everywhere and faster on MySQL, and
without the index only the join finishes), and the docs say to index the correlated columns. It selects, filters and
orders, and is an order key for offset pages, offset export and `primaryKeyFirst`; `keyset()` keeps `MQ1208`. Refused:
in a CASE condition, `having`, an aggregate (`MQ1510`), a grouped query's selection or orders whose lifted columns
are not all group keys or as a group key (`MQ1314`), a `count`, `sum` or `first` whose sub-select crosses a to-many
join (`MQ1511`, at first resolution), and inside a `SourceQuery` (`MQ1908`, at its build).
(amended 2026-10-17, M27.1: MQ1314 covers the selection and orders; a correlated value in a grouped query's where is
allowed, evaluated before grouping). `first(...)` requires an order, appends the
sub-select root's id ascending, and renders null precedence as any order does (R-COL-12, D-36: `DEFAULT` follows the
vendor, so the docs say to set `nullsFirst()`/`nullsLast()` on a nullable key for a portable value); it renders through a new `SubqueryRenderer` from `ProviderSupport#subqueries()`, and without one is
`MQ4014`, widened to every renderer the provider lacks (`SourceRenderer`'s provider-gap defaults move from `MQ4015`).
(amended 2026-10-17, M27.3 review: every composite id's tie-breaker parts are ordered by attribute name, for field
and property access; Hibernate's `hibernate.jpa.compliance.query=true` refuses `first` with `MQ4014` before SQL).
A lifted outer column may sit on a to-one path of the outer root (`MQ1311` narrowed to to-many paths, checked at first
resolution), resolved through the outer `JoinContext` and rendered with `Subquery#correlate` of the outer join, which
keeps its `ON` (a bare reference drops it on every target); a source outer root is allowed, a sub-select rooted at a
source is not. A `@SourceModel` may be rooted at a plain, grouped, shape or recursive source model (not itself
recursive, a branch or a shape: `MQ3041`), one CTE per level, every level registered on the top statement through
`SourceRenderer`'s new `CteHandle`, `register`, `registerRecursive` and `from` (defaults `MQ4014`); the walk's filter
goes into the next body, and the walk checks and key predicates stay in the top statement. (amended 2026-10-18, M27.4:
A source rooted at a branch is `MQ3041`, directing the caller to root at the shape, since the branch builds
`SourceQuery<Shape>`. Shape/branch checks include deferred source models within the compilation, using the existing
round lookup without new cross-round tracking). (amended 2026-10-18, M27.5:
`SourceRenderer.registerRecursive(CriteriaQuery<?> top, RecursiveSpec spec)` takes the existing `RecursiveSpec` and
returns `@Incubating record RecursiveHandle(CteHandle handle, Expression<?> depthKey, Expression<?> cycleKey,
Expression<?> duplicateKey)`, mirroring `RecursiveCte` so the top statement receives both the registered CTE handle and
the inner walk's check expressions). A non-branch source QModel
generates `Rows` and `query(SourceQuery<S>)`; mixing the two vocabularies is `MQ1905` both ways. Every new type and
member is `@Incubating`. A PostgreSQL query timeout is wrapped differently on Hibernate 6.6 and 7.4; timeout handling
reads the root cause. Rejected: string annotations, lateral joins, a step type for the terminals, `firstRow` on
`SourceRenderer`, a processor marker for `MQ1908`, a join lowering, a nested `WITH` per body. Request-time values in a
`@Computed` are Q-24. → `rfc/0015`, `api/10`, `api/11` R-QRY-16, R-PAG-25, `api/12` R-FLT-17, R-FLT-27, `api/17`
R-SRC-16..19, `processor/30` R-PROC-35, R-PROC-39, R-PROC-40, `processor/31` R-GEN-37, R-GEN-39, R-GEN-40,
`vendor/40` R-VND-25, Q-22.

**D-151 — `SourceRenderer` SPI and MQ1909 (M27 gate, 2026-10-19, user decision).** `RecursiveHandle` and `CteHandle` are
approved as `@Incubating`; `RecursiveHandle`'s `handle`, `depthKey` and `cycleKey` are non-null, `duplicateKey` stays
nullable, and a handle is valid only on the top statement it was registered on and that statement's sub-queries.
`SourceRenderer#cte` and `#recursive` become defaults over `register`, `registerRecursive` and `from`, so a provider
implements those (a provider may still override the old pair); with nothing overridden every method ends in `MQ4014`. A
sub-select rooted at a source moves from `MQ1311` to the new `MQ1909`, since a released code does not take a new meaning
(INV-10); `MQ1311` keeps the lifted-path meaning only. → `api/12` R-FLT-27, `vendor/40` R-VND-21, D-150.

**D-152 — A `@QueryModel` rooted at a branch is `MQ3041` (M27 gate, 2026-10-19, user decision).** A
`@QueryModel` whose root is a branch of a set-operation source is refused at compile time with `MQ3041`, as a source
rooted at a branch already is (M27.4): the branch builds a fragment of its shape and generates no `Rows` and no `query`
(R-GEN-40), so the caller roots at the shape. The message covers both kinds: `X: root Branch is a branch and cannot be
read directly; root at the shape S instead`. Sources are unreleased, so refusing now and allowing later stays
compatible. → `processor/30` R-PROC-40, `processor/32` AC-PROC-41, `api/17`, D-150.

**D-153 — Correlated values in bulk writes (M27 gate, 2026-10-19, user decision).** A `Correlated.*` terminal (R-COL-31)
is allowed in an update or delete `where`, where a key-first rewrite on MySQL covers a sub-select over the target table
(`targetTableInSubquery()`, R-WRT-12). It stays refused in a conflict update's `where` (`MQ1804`) and in
`ModelUpdate.setExpression(column, ExpressionField)`: a correlated value reads the root's lifted columns, so `MQ1613`
already refuses it, and no new code or `build()` guard is added. → `api/10` R-COL-31, `api/14` R-WRT-13, D-150.

**D-154 — Inheritance roots (RFC 0016, 2026-10-19, user decision).** A query model reads a polymorphic entity root
through `TableField#treat(Sub.class)`, a treated node rendered by `cb.treat`, and a downcast `@Join`; a subtype column
reads NULL on a sibling's rows in select, predicates, order, keyset and count (R-COL-35), including a shared
`SINGLE_TABLE` column, which both lines guard with a `CASE`. Every count over a statement with a treated node (a `treat`
node or a downcast join, not `typeOf` or `ofType` alone) is built fresh with only its predicates and counts
`root.<id>`; a grouped `@Subtype` query's total keeps R-AGG's group count. Content statements and ungrouped source
bodies with a treated node select the untreated root id, hidden as `mq_0id` when the model selects none (outside D-146's
`mq_<property>` space, never in `fields()`, `DEFAULT`, `ALL`, a mapper or `Rows`); a `distinct()` query or a non-ALL set
operation that would need it is `MQ4016` unless the model selects the root id. `Expr.typeOf` is an offset order key of
unspecified order, `MQ1208` in a keyset, and `MQ4016` on `JOINED` without a discriminator; `@Subtype` is `Class` only
(Q-25). The default is strict: a provider without a `TreatRenderer` (`ProviderSupport#treats(emf)`) gets `MQ4014` for
every treated use. `TreatSite` carries its `StatementShape` and `anchored`, the renderer's allow-list is keyed by shape,
anchoring and use set, and anything else is `MQ4016` before the first statement. Refused with `MQ4016` in M28: group
keys and aggregates over treated fields (Q-27), a treated node in a `CHILD` statement, a treated `ModelUpdate` or
`ModelDelete` `where` on every strategy (before R-WRT-11's path choice, so alike on every vendor, INV-6), a subtype
`@SQLRestriction` on 6.6, a `@DiscriminatorValue("null")`/`("not null")` hierarchy, a composite id, and unmeasured
mapping variants; compliance mode is `MQ4014`. `TABLE_PER_CLASS` ids are trusted (Q-26); R-PAG-26's `MQ2213` guards
the reads that rely on a unique key on every root. Five guards cover relying on observed Hibernate behaviour: the
renderer serves only the 6.6.x and 7.4.x minors; every admitted cell is a TCK case; a per-line statement preflight in
`model-query-hibernate` walks each derived select statement's SQL AST before the first statement, fails closed on a
narrowing it can't attribute or a shape it doesn't recognise, and caches nothing; R-PAG-27 logs one `WARNING`
`MQ2214` per `ModelQuery` for an offset page whose rows exceed `total - offset`, with no bound values (D-95), and
returns the page; `PortabilityCheck` reports treat sites offline from the table alone. A union of subtype source
models is M25.4 unchanged. Every new type and member is `@Incubating`. An improper
`treat(T.class)` is `MQ1106` at definition on a root and at first resolution on a join, whose declared target
comes from the metamodel. This check keeps the same message and is separate from comparing the subtype with the
actual query root or re-rooted join target; re-rooting preserves the original declared target. With no renderer,
fail closed for every reachable treated node before any statement, including a treat that would render untreated;
cache successful no-renderer definition checks per factory across executors, including nested query definitions,
never on the immutable query. Executors with a renderer neither populate nor consult this cache.
Rejected: treat in a path string, a portable
default renderer over `cb.treat`, a flat per-use refusal, a site without its statement, repairing the count after the
fact, the LEFT entity join (Q-27), throwing on the page fallback, admitting `JOINED`/`TABLE_PER_CLASS` writes on the
table alone, a `VendorProfile#treats` fact and the `MQ4015` treat path (no vendor split measured), `TABLE_PER_CLASS`
roots as non-unique, "disjoint by hierarchy" for a union without `@Discriminator`, and Blaze-style view inheritance.
→ `rfc/0016`, `api/10` R-COL-03, R-COL-08, R-COL-34..36, `api/12` R-FLT-28, `api/14` R-WRT-11, `api/15`, `api/17`
R-SRC-20, `engine/20` R-EXE-05, `engine/21` R-PAG-19, R-PAG-26, R-PAG-27, `processor/30` R-PROC-08, R-PROC-41..43,
`processor/31` R-GEN-41, R-GEN-42, `processor/32` `MQ3003`, `MQ3042`, `MQ3043`, `vendor/40` R-VND-26, `reference/90`,
Q-25, Q-26, Q-27.

**D-155 — Treat `MQ4014` names its reason in a WARN (M28.3, 2026-10-19).** The core-thrown `MQ4014` for a treated path
without a renderer keeps its generic message ("a treated path needs the provider's TreatRenderer"), since the SPI has no
channel for the provider to name the reason in a refusal the core throws. `model-query-hibernate`'s `treats(emf)` logs
one `WARNING` per factory naming the specific reason — a Hibernate minor outside 6.6.x and 7.4.x, or
`hibernate.jpa.compliance.query=true` — before returning empty. Rejected: a new SPI hook so the provider could name the
reason inside the exception, and a `RenderOptions` override that would bypass the gate. On a served line the renderer is
built once per factory, after the line and compliance checks, and cached weakly on it: it reads the mapping when built,
is immutable and keeps no reference to the factory, so every executor and thread shares it. → `vendor/40` R-VND-26,
`reference/90` `MQ4014`.

**D-156 — The Hibernate treat table is the measured envelopes, not their combinations (M28.3, 2026-10-19).** The
renderer admits a use set only where the spike recorded it and every recorded case passed on both lines, all three
vendors and the strategy; it does not extend a measured context to an unmeasured join or expression form (`{PREDICATE,
SUBQUERY, LEFT_JOIN}` and `{SELECT, EXPRESSION, LEFT_JOIN}` are refused). A shared `SINGLE_TABLE` column takes the
shared probes' own content cells, the `NULL_TEST` ones included; in a count it takes only the `or` cells (a set with
`DISJUNCTION` and no `SUBQUERY`, less `{PREDICATE, NULL_TEST, DISJUNCTION}`), as the accepted RFC's §10 prose says,
though the spike's standalone count rows on it also passed: no root-id count was measured there. On `SINGLE_TABLE` a
dotted path's column is its first segment's (the subtype's own attribute, or its foreign key); a join or path through a
shared column, and an attribute whose columns the renderer can't read, are refused, since only a shared column's own
read was measured. A mapping variant takes
the table less the cells its runs lacked. Because `ne`, `notIn` and their field and sub-select forms render an `IS NULL`
branch (R-FLT-04), the engine reports them as `{PREDICATE, NULL_TEST, DISJUNCTION}`, the spike's envelope for them, not
as a plain `PREDICATE`; otherwise a shared column or a variant would admit a null-accepting count the spike saw
undercount. An `add(...)` predicate's SQL may accept nulls too, but its condition records no column, so it can't report
even that set: the engine refuses a subtype treat reached while it renders (`MQ4016`, before its statement runs),
whatever the table admits; a child statement's `add(...)` renders when the call's fetch plan is built, before the first
statement (R-FCH-02). A correlated count's `{SELECT, AGGREGATE, SUBQUERY, LEFT_JOIN}` is refused: the spike counted that
left treated join from an untreated sub-select root, which the engine reports unanchored, and the only anchored site
with that set treats twice, which nobody ran. The anchored `PREDICATE` sets with `SUBQUERY` run as a path `exists` under
a treat (AC-VND-19). Every admitted cell is a TCK case, so the sets no definition reaches are refused: the `NULL_TEST`
sets with `SUBQUERY` and no `PREDICATE` (a null test inside a path `exists` also reports the exists' `PREDICATE`), and
every shared-column set with `SUBQUERY`, content and count (a path `exists` reads only columns on its path, an outer
column inside it is `MQ1302`, and a path through a shared column is refused). The TCK probes the renderer with
synthesized sites over every use set of at most four uses, per strategy, shape, anchoring, shared column and mapping
variant, and asserts the admitted sets equal the ones its cases ran.
`TYPE` cells wait for M28.5, which adds them with `typeOf`/`ofType` and their TCK cases. Rejected: the RFC table's prose
read as "any join with any predicate context", which admits sets nobody ran. → `vendor/40` R-VND-26, `api/10` R-COL-35.

**D-157 — The engine declares the sub-select roots it narrows; the preflight expects only those (M28.4, 2026-10-20,
user decision).** `TreatRenderer#preflight` takes a fourth argument, the statement's `List<ExpectedNarrowing>`: each
sub-select root the engine narrowed through a treat on purpose, with every subtype it was treated as, in build order and
compared as a multiset, so two equal `exists` are two entries (`BuiltQuery#expectedNarrowings()`, one list per built
statement, each later keyset page shape its own build's). `ExpectedNarrowing(kind, entity, types)` and its `Kind` are
`@EngineFacing @Incubating`; `types` is a non-empty, unmodifiable copy of subtypes of `entity` (INV-9), and a renderer
refuses a kind it doesn't know. M28.4's one kind is `EXISTS_ROOT`: the correlated root of a path `exists`, read through
a treat; M28.5 adds one for `ofType`. The engine declares it only where Hibernate narrows the root, as measured on
6.6.58 and 7.4.11 on every strategy: a treat of the correlated root narrows it, whether a column of the treated root is
read at the top of the `exists`' own filters, under a `not`, inside a `CASE`, or not at all (a join from the treat
alone narrows); but an `or` that reads a column of the treated root in some of its branches only keeps the root whole,
even beside a read at the top, and so does an untreated read of the root (which `MQ1302` keeps out of a path `exists`).
So an `or` that reads such a column in some branches only withdraws the declaration; an `or` reading it in every branch,
which Hibernate narrows, still does, and one reading only the path's joined columns doesn't count. A position the
engine declares and the provider doesn't narrow, or the other way round, is `MQ4016`, never a lost row. Nothing else is declared, so an `in` sub-select's own treated root, a
`CASE WHEN EXISTS`, and any other narrowed sub-select root stay `MQ4016`. A correlated count's own treated sub-select
root is not declared: measured on 6.6.58 and 7.4.11, all three strategies, the treat in the counted column or in a
filter, neither line narrows it (pruning skips projection uses, and a fresh sub-select root stays untreated), so there
is nothing to expect; if a later line did narrow it, the preflight refuses it as unattributed, which is the fail-closed
answer. Hibernate's preflight matches each narrowed sub-select root to a declaration: the root's sub-select must be a
direct operand of an `exists` in its parent's `where` or `having` (under `AND`, `OR` and `NOT`; not inside an
expression or a `CASE`), correlated to an untreated parent root by a top-level `=` of their single id columns, and its
narrowing must equal what a treat as the declared subtype renders: the derived table's discriminator values on
`SINGLE_TABLE` (a column or a `@DiscriminatorFormula`, whose template is rendered over the derived table's alias), the
tables it is `INNER` joined to on `JOINED`, the union's tables on `TABLE_PER_CLASS`, compared lexically as the root
check compares them. Measured on both lines: the union of a treat as a subtype that has subtypes of its own also keeps
the root entity's own table. That table is expected only for a declared subtype with subtypes of its own, and only when
none of its rows can match: its union branch reads a null id (`null as id`, or `cast(null as bigint) as id` on some
dialects), which the required id correlation never matches, as both lines render a read of the subtype's own column; or
a `clazz_` restriction to exactly the declared subtype's concrete entities is a top-level conjunct of the sub-select's
`where` or of the condition of an `INNER` join from the root, which drops a failing row as the `where` does, as both
lines render a join from the treat. A branch that keeps its id and is read only through a `CASE` guard, as both lines
render a read of an inherited column such as `status`, is refused: an `is null` of the guarded column matches the root
table's rows, so a `not exists` would drop the outer row. A leaf subtype never keeps it. The `@DiscriminatorFormula`
derived table is expected only for a declared `EXISTS_ROOT`, and only when its restriction's values are exactly the
declared subtype's concrete discriminator values; on the statement's own root it stays refused. A discriminator
restriction in a `where` is recognised for every strategy: a column, a formula, `TABLE_PER_CLASS`'s `clazz_` and
`JOINED`'s `CASE` (typed by its discriminator mapping; 6.6 renders it with no column, so there it is never attributed
and is refused). A top-level `in` or `=` on a root's discriminator must admit every concrete entity the root is read
as, its own, or the declared subtype's for a root that consumed a declaration; any other read of it outside a `CASE`
guard is refused. A narrowed root that consumes no declaration, a declaration left unconsumed, an unknown kind, a
declaration of several subtypes, and two declarations of one kind and entity naming different subtypes ("cannot
attribute") are `MQ4016`. Rejected: pairing each narrowed root with a `TreatSite`. Sites are a set per call, so two
equal `exists` collapse; a site is a read, not a narrowing; sites are per call while statements differ (a count drops a
correlated count, primary-key-first steps and later keyset pages build their own); and the pairing failed on both
lines, since a correlated root shares its `NavigablePath` with the outer root and `getCorrelatedRoot()` returns itself,
so no ordinal or path matches in the AST. S3 never measured sub-selects, so this is the first rule for them.
→ `vendor/40` R-VND-26, AC-VND-21, `rfc/0016` §11 guard 3.

**D-158 — How `typeOf` and `ofType` reach the build and the preflight (M28.5, 2026-10-20).** Six choices the RFC left to
the slice. (1) The build reads `ofType`'s subtypes from the persistence unit's `Metamodel`, which a `CriteriaBuilder`
doesn't expose, so `RenderOptions` gains `withMetamodel(Metamodel)` and a package-private `metamodel()`. Both sit on the
D-86 `@EngineFacing` type, so they are not API; the executor sets it from its factory and scratch options keep it with
the renderers. An `ofType` rendered without one is `MQ4014`, as a missing renderer is. The expansion keeps the concrete
entities only, in class-name order, so the IN list repeats across runs, and renders type literals rather than binds,
which the preflight reads to attribute it. (2) The varargs forms carry no `@SafeVarargs`, which an interface method
can't (D-87); the RFC's sketch had it. A `Collection` form sits beside them, as `in` has, so an empty collection needs
no `Optional`. (3) `notOfType` renders `TYPE NOT IN (...)`, measured on the root, and only over a node reached through a
LEFT join adds `OR TYPE IS NULL`, the measured `notOfType`-over-LEFT envelope `{PREDICATE, NEGATED, NULL_TEST,
DISJUNCTION, LEFT_JOIN, TYPE}`; over the root or an INNER join its site is `{PREDICATE, TYPE}` (plus the join use). (4)
The engine refuses a `typeOf` read (select, order or group key) over a LEFT-joined node itself, since both lines fail on
it; a `typeOf` group key is exempt from the treated-group-key refusal (Q-27), and a selected group key is one site,
`{SELECT, GROUP_BY}` with its other uses. The `JOINED`-without-discriminator order refusal needs the persister, so it is
the Hibernate renderer's. (5) `ExpectedNarrowing.Kind#TYPE_RESTRICTION` is declared for a non-empty `ofType` only where
it is a top-level conjunct of a select statement's own `where`: not under `or`, `not`, a `CASE`, `add(...)` or in a
sub-select, where narrowing the node to the rendered classes would lose rows, and only over the statement's untreated
root, never a joined node of its entity such as a self-join; its `entity` is the root's entity and its `types` the
rendered classes. A type read over a treated node, a treat of the root or a downcast join, measures no cell and is
`MQ4016` before any statement. The Hibernate preflight lets the statement's single root of that entity be narrowed to any
rows that hold every declared type's, in its table expression, its `INNER` joins or a top-level discriminator
restriction, and needs no narrowing to consume it; any other narrowing stays refused. (6) The Hibernate renderer admits
the `TYPE` cells measured `ALLOW` in the spike's table, the `JOINED` order only where the persister maps a physical
discriminator, and `{PREDICATE, DISJUNCTION, TYPE}` from the `COMBINED_TYPE_TREAT/OR` rows, a type site beside a treated
one admitted per site (RFC 0016 §10). (7) An `ofType` built without a metamodel reuses `MQ4014`: only a custom executor
that wires a `TreatRenderer` without one reaches it. (8) Three cells the table doesn't name as such are admitted:
`{PREDICATE, DISJUNCTION, TYPE}`, a subset of the measured `DISJUNCTION+LEFT_JOIN+NEGATED+NULL_TEST+PREDICATE+TYPE`
envelope and admitted per site; `{SELECT, GROUP_BY, TYPE}`, the engine's site for the table's `GROUP_BY+TYPE` row, whose
count of groups is a `GROUPED_COUNT` site, counted client-side on `JOINED` without a discriminator (D-159); and the
`TYPE` cells on the measured mapping variants, as their content cells are. Each has a TCK case. (9) On 6.6 a `JOINED`
statement with a treated read and an `ofType` stays refused by the preflight (`MQ4016`), which can't attribute the type
restriction to its root; 7.4 admits and runs it. → `api/10` R-COL-36, `api/12` R-FLT-28, `vendor/40` R-VND-26.

**D-159 — A refused count of groups runs client-side (M28.5, 2026-10-20, user decision).** On `JOINED` without a
physical discriminator, on 6.6 and 7.4, Hibernate's `createCountQuery()` drops the subtype joins from its derived
table, and its `CASE` type then reads one type for every row: a page grouped by `typeOf(ROOT)` reported a total of 1
for 4 groups, silently, while its content was right. A count over groups whose type site the renderer refuses as
`GROUPED_COUNT`, the new `@Incubating` `StatementShape` of a grouped statement's count, runs client-side: the engine
runs the grouped statement and counts its rows, as R-EXE-03's portable path does. Its sites are checked as `GROUPED`
instead (`MQ4016` when refused there too), its statement is preflighted as `GROUPED`, and each such count logs a
WARNING with the renderer's reason and no bound value (D-95). The decision is per call, never kept (INV-9). Hibernate
refuses it on `JOINED` without a physical discriminator, the same mapping fact that refuses a `typeOf` order there
(D-158 (4)), because `createCountQuery` drops the subtype joins; it admits it elsewhere as its `GROUPED` cell. Unknown
shapes are refused (R-VND-26), so another renderer counts these groups client-side until it admits the shape.
Refusing the grouped type site outright was rejected: the content is right, and only the provider's count is wrong.
→ `engine/20` R-EXE-03, `api/10` R-COL-36, `vendor/40` R-VND-26.

**D-160 — Duplicate identity and filter-only to-many repeats (M28.6, 2026-10-20, user decision).**
R-PAG-26's `MQ2213` guards identity duplicates only, on every root, including a non-id model key matching two rows.
When `filterOnlyToManyJoin(built).isPresent()`, rows repeated by a to-many join that only a predicate uses retain
existing deduplication (R-PAG-02, D-99, AC-PAG-12/13/23) and never raise `MQ2213`. For `byKeys` and `one(q, key)`,
such a join keeps `MQ2003` and its existing `Filters.exists` hint; without that join, duplicate keys are `MQ2213`.
Child rows remain deduplicated per parent (R-FCH-04, D-99), even with a nested fetch plan; only parent rows are in
R-PAG-26's scope. → `engine/20` R-EXE-12, R-EXE-13, `engine/21` R-PAG-26.

**D-161 — Check TABLE_PER_CLASS treated join targets as well as roots (M28, 2026-10-20, user decision).**
D-157's root preflight does not prove that an `INNER` downcast target excludes other concrete types. The admitted
`READ INNER_JOIN+SELECT TABLE_PER_CLASS` cell (`rfc/0016-spike-data/renderer-allowlist.tsv:238`) was measured with a
concrete base, but only the subtype's `number` selected. On both 6.6.58 and 7.4.11, selecting inherited `id` or
`status` keeps the concrete base branch's real join key; guarding those selected values with a `CASE` still lets
the base match the `INNER` join. Extend R-VND-26 guard 3 before any JDBC statement: collect declared treated joins
from the statement's SQM, associate them with the translated SQL table groups, and check every union branch outside
the declared subtype's tables. Admit it only with a NULL key in a mandatory join equality or an effective subtype
restriction (the `where`, its own `ON`, or an `INNER` join from the target), reusing D-157's lexical NULL projection
and discriminator checks. Unknown or unattributable shapes are `MQ4016`. Subtype-only `number` still runs: the
extra base branch projects a NULL id and cannot match. A to-one `LEFT` join may keep a real base key when all its
target column reads in `select`, `where`, `group by`, `having` and `order by`, including its hidden presence key
and later-page keyset predicates, are NULL for that branch, either projected NULL or guarded by a subtype `CASE`
with a NULL `ELSE`; it then yields `Optional.empty()`. Reads through any join nested under the treated target
count as target reads too. Any read not proven NULL or guarded on every outside branch is `MQ4016`; an unguarded
presence key never produces `Optional.of` a view of NULLs. Walk derived roots and joins and 7.4 FROM-function
arguments when collecting the SQM declarations, including grouped counts wrapped in a derived table. A SQL TPC
union join with no recorded treated declaration fails closed even inside a `QueryPartTableReference`.
Keep the site allow-list as the first guard; this statement-specific
check refines it without a new SPI or diagnostic. → `vendor/40` R-VND-26, AC-VND-21; D-157.

**D-162 — What an anchored source body admits (M28.8, 2026-10-20).** Four choices R-SRC-20 left to the slice. (1) A
body is anchored, its sites `anchored = true`, when it is ungrouped and reads an entity root itself: a plain body and
each branch of a combination. A grouped body stays `GROUPED`, and a recursive body and a body over another source stay
unanchored, since no anchor was measured there. (2) The Hibernate renderer admits the spike's measured source rows
only, `{SELECT}` and `{NULL_TEST}` (`READ SELECT+SOURCE`, `NULL_TEST+SOURCE`, run with the body reading the root id),
on the three core strategies over an unshared column; a mapping variant and a shared `SINGLE_TABLE` column have no
source row, so they are refused. (3) `{SELECT, TYPE}` is admitted too, though the spike ran no type read in a body:
R-SRC-20 names `@Subtype` fields in a source, a type read makes no treated statement (R-COL-36), and it is the content
cell the spike measured, read from a CTE. Its TCK case runs per strategy, as D-158's unnamed cells do, and found that
on `JOINED` without a physical discriminator both lines read the root's class on every row of the body, so there it
is refused (`MQ4016`), as a type order is (R-COL-36). (4) A combined
body's branches select `mq_0id` all or none: when one branch with a treated node reads no untreated root id, each
branch selects its own, so a `unionAll` keeps one arity; a `union`, `intersect` or `except` that would need it stays
`MQ4016` (R-COL-35), as does a combination needing it with such an operation anywhere in it, nested under a
`unionAll` included: every branch would select the anchor, so a nested `union` would stop collapsing and an `except`
would remove nothing. Once a combination needs it, every branch's root id is checked before any statement: a composite
id, or root ids differing in Java type, is `MQ4016`, since the anchor column would have no one type. A window
function's argument over a treated node is reported as an aggregate argument and refused as one, in every statement, a
query's select list as well as a source body: no cell measured a window over a treated argument in any shape, and
D-155 admits measured sets only. → `api/17` R-SRC-20, `api/10` R-COL-35, `vendor/40` R-VND-26.

**D-163 — The offline report skips the preflight (M28.8, 2026-10-20).** `rfc/0016` §10 item 5 has `PortabilityCheck`
report treat sites from the table's answer alone, since the preflight was never run over R-VND-20's offline factory.
The dry run calls the executor as a deployment does, so the skip sits with the provider that boots offline:
Hibernate's `offlineProperties` adds a library setting, `com.rey.modelquery.hibernate.offline_boot`, whose value is a
private token object rather than a string, and a renderer built over a factory carrying that very object accepts every
select statement in `preflight`: a user who sets the key, to `"true"` or anything else, still gets the preflight; `refusal` is unchanged, so each site
is still `MQ4014` without a renderer and `MQ4016` outside the table, per target. No public API or `PortabilityCheck`
change: the executor cannot tell an offline factory from a live one, and a provider's own offline boot is what knows.
A statement the preflight would refuse is therefore reported offline as reached and refused only at execution, the
blind spot `rfc/0016` records for the offline check. → `vendor/40` R-VND-26, AC-VND-17.

**D-164 — A nested `exists` under a treated `exists` path is refused by the table (M28 gate).** A path `exists` nested
under an `exists` whose path holds a treat correlates on a treated node: its sites carry `TreatUse` `CORRELATION`,
which the Hibernate table refuses (`MQ4016`); a lifted `Outer.column` read carries it too. The engine itself refuses
only a non-join correlation target, with `MQ4016` rather than a cast (CC-ERR-01). The mark applies when the outer
path holds a treat anywhere, not only at its leaf (deliberately fail-closed). Amends R-FLT-11, R-VND-26.

**D-165 — Guard 3 also covers the source bodies a definition reads (M28 gate).** The statement preflight runs for a
definition whose own statement, or a source body it reads (sources, set-operation branches, inner sources), holds a
treated node. The `mq_0id` anchor and `MQ2214` still key on the top statement's own treat. Amends R-VND-26.

**D-166 — `named(...)` on a treated join is `MQ1106` (M28 gate).** `named(...)` on a treated join is `MQ1106` like
`as` and `on`; apply those modifiers before treating. Amends R-COL-34, R-GEN-41.

**D-167 — Custom filter operators (RFC 0017, 2026-10-21, user decision).** A filter applies a typed, named
`FilterOperator<C, V>`, an immutable `core` definition compared by identity (R-INS-04 amended), with `Filters.op` and
`Having.op` in value, `Optional` and field form; its renderer gets the column's path and an operand expression, never
the value, and `null` or a thrown `RuntimeException` from it is `MQ1313`. The engine binds the value per statement:
converted for the `<C, C>` form, unconverted for `<C, V>`, typed by the column's mapping through the provider's
`OperandBinder` (`ProviderSupport#operands(emf)`, carried by `RenderOptions.withOperandBinder`) or by the declared
value type. Hibernate's binder is a `JpaCriteriaParameter` typed by the column's node type (the spike's
`ANTICIPATED_PATH`), served on 6.6.x and 7.4.x only, one reflective adapter with two line differences (the
constructor's `BindableType` package, and the `getSqmPathType()` unwrap on 6.6 only); a node type it doesn't expect
is `MQ1502`, never a fallback to `cb.parameter(class)`, and each line has a TCK case. Without a binder only
mapping-independent values bind and the rest are `MQ1502`, because a class-typed parameter binds a `STRING` enum by
ordinal, which MySQL answers silently wrong over a `varchar` column (every row) and a native `enum(...)` column (no
row). `OperatorFeature` is a `core` enum (`ILIKE`, `REGEX_MATCH`, `ARRAY_CONTAINMENT`, `JSONB_CONTAINMENT`,
`FULL_TEXT`); the gate is two checks at render time, before any statement, the vendor's
(`VendorProfile#operators`, `MQ4015`) and then the provider's (`ProviderSupport#operators(emf, f)`, `MQ4014`),
resolved once per executor into a gate `RenderOptions` carries; no gate is `MQ4014`, and `checkPhases` uses a
stand-in (D-132). Hibernate's provider answers a pattern feature by looking its `mq_*` function up in the factory's
registry, and `ILIKE` by dialect (PostgreSQL's and H2's, never MySQL's emulation). `model-query-hibernate`'s
`HibernateOperators` ships `ilike(LikeMode)`, `regexMatch()`, `arrayContains()`, `arrayOverlaps()`, `jsonbContains()`
and `fullTextMatch(String configuration)`; every `mq_*` function, `mq_array_contains` and `mq_array_overlaps`
included, is registered for PostgreSQL's dialect only. Full text is a value-form operator whose configuration is a
required plain, optionally schema-qualified SQL identifier (`MQ1315` otherwise), rendered as a literal so the
expression index matches; the tsquery function (`plainto_tsquery` or `websearch_to_tsquery`) is chosen by a probe
that opens its slice. An `OPERATOR` condition is refused inside `Expr.cases()` (`MQ1505`); any `op` in an arbiter's
`where` and a value-form one in a conflict update's `where` are `MQ1804`; `op` adds no NULL handling (R-FLT-22). The
docs page says a renderer must use the `right` it is given, and that index use is the planner's per plan: under a
generic plan `ILIKE` over a trigram index and `jsonb @>` were a Seq Scan, and `~` over a trigram index is unmeasured.
Every new type and member is `@Incubating`. Rejected: `applyInferableType` (types nothing by the mapping),
`SqmCriteriaNodeBuilder#value` (an `internal` package, inlines under `value_handling_mode=inline`, breaks for enums,
`@Convert` and arrays there), a function's `ARGUMENT_OR_IMPLIED_RESULT_TYPE` (catalogue functions only, not
`@Convert`), Hibernate's own array functions (registered on H2, where they fail or emulate, so the provider check
can't gate them), `ILIKE` answered true on every dialect, full text as a field form over `Expr.function` operands or
with a bound or default configuration, a built-in `ilike` per vendor (D-22, R-VND-10), SQL templates in `core`, a
renderer given the raw value, binding through `Expr.value`, one vendor check only, and `OperatorFeature` in `jpa`.
→ `rfc/0017`, `rfc/0017-spike-results.md`, `api/10` R-COL-17, R-COL-18, `api/12` R-FLT-20..22, `api/13`, `api/14`
R-WRT-34, R-WRT-52, `api/16` R-INS-04, R-INS-07, R-INS-13, `vendor/40` R-VND-23, R-VND-24, `reference/90` `MQ1313`,
`MQ1315`, `MQ1502`, `MQ1804`, `MQ4014`, `MQ4015`, D-148, Q-28.

**D-168 — What `rfc/0017` left to the core slice (M29.1, 2026-10-21).** (1) `requiringAttribute`, `requires` and
`preparing` each replace that setting on the new instance rather than adding to it: an operator has at most one
attribute check, one feature and one `prepare`, and composing them is the caller's. (2) `prepare` runs once, when the
filter is added, before conversion, so its value is converted and bound as is; a `null` from it or a thrown
`RuntimeException` is `MQ1313`, as the renderer's is, since both are the operator's own code, and a
`ModelQueryException` keeps its code (order superseded by D-171 (6)). (3) An `Optional`, `Collection` or `Map` value is `MQ1502` when the filter is
added, since no binder takes one; the mapping-independent check and the feature check run at render time, where the
binder and the gate are known. (4) "A number" without a binder is a `java.lang` or `java.math` `Number` (a
`Long`, a `BigDecimal`), not any `Number` subclass, which a class-typed parameter can't bind. (5) The field form's
`MQ1001` check is `compare`'s, for the `<C, C>` form only: in the `<C, V>` form the sides are of different types by
design. (6) With no `OperandBinder`, a `Boolean` or `UUID` value is `MQ1502` too, amending `rfc/0017` §2 step 5
(user, 2026-10-21): a class-typed parameter binds a `Boolean` as the database's boolean and a `UUID` as its native or
binary form, so a column mapped to `'Y'`/`'N'` or a `UUID` stored as `char(36)` would silently match nothing (INV-5).
Only a `String`, a `java.lang` or `java.math` number and a `java.time` value bind without a binder, and an array of
`java.time` values is an array, not a `java.time` value. → `api/12` R-FLT-20, R-FLT-21, `reference/90` `MQ1313`,
`MQ1502`.

**D-169 — What `rfc/0017` left to the engine slice (M29.2, 2026-10-21).** (1) The gate is `core.OperatorGate`, an
immutable value of the vendor's name and two feature sets, built by `OperatorGate.of(vendor, vendorFeatures,
providerFeatures)`; the executor fills it by asking `VendorProfile#operators` and `ProviderSupport#operators(emf, f)`
for every `OperatorFeature` once, when it is built, so no render asks a profile and `core` raises both codes, the
vendor's `MQ4015` first. Rejected: a gate interface `core` calls per render, which would let a provider's answer change
between statements of one query. (2) `OperandBinder` lives in `core` (`RenderOptions` carries it and `core` can't see
`jpa`) and types the `<C, C>` form only, by the rendered column or aggregate expression. The `<C, V>` form keeps the
no-binder rule even with a binder: its value has no column mapping to type it, and a parameter typed by its declared
class is the bind the rule exists to refuse; this supersedes `rfc/0017` §2 step 4's binder call for the `<C, V>` form
(user, 2026-10-21), and the RFC is left as written. (3) A binder that throws a `RuntimeException`, returns `null` or
returns a parameter the statement already binds is `MQ1502` naming the operator and the column, the exception as the
cause; a parameter binds one value per statement, so a reused one would bind the later value twice (INV-5), and a
write's sub-query whose binds repeat one of its statement's is `MQ1502` too, when they are merged; a
`ModelQueryException` it throws keeps its code. (4) `checkPhases`' stand-in gate serves every feature and, as D-132's
scratch sequence renderer, is a fallback: when the executor passes its own gate, that gate decides the scratch builds
too, with the codes the real build would raise. The stand-in gate has a stand-in `OperandBinder` beside it, returning
`cb.parameter(type)`, so `checkPhases(cb)`, which has no executor options, doesn't refuse an enum, `Boolean`, `UUID` or converted
`op` value with `MQ1502` (D-168 (6)): scratch statements never run, so the bind is never observed. With executor options
the scratch build uses the options' binder or none, as the real build does, so options that carry no binder still refuse
such a value with `MQ1502`. (5) `Expr.cases().when(...)` refuses an `op` with `MQ1505` when the
filter is added, as it refuses `exists`; an arbiter's `where` refuses any `op` with `MQ1804` at `build()`, and a
conflict update's `where` refuses a value-form `op` on first execution, where it refuses `Expr.value`, naming the
operator. → `api/10` R-COL-17, `api/12` R-FLT-20, R-FLT-21, `api/14` R-WRT-34, R-WRT-52, `vendor/40` R-VND-23, R-VND-24,
`reference/90` `MQ1502`, `MQ1505`, `MQ1804`, `MQ4014`, `MQ4015`.

**D-170 — Hibernate's operand binder (M29.3, 2026-10-21).** (1) One adapter, `HibernateOperandBinder`, serves both
measured lines; `operands(emf)` returns it on the 6.6.x and 7.4.x lines only, by the same line check as `treats(emf)`
(R-VND-26), and is empty with one WARN per factory elsewhere, so a later line whose internals moved binds only the
mapping-independent values (D-168) rather than binding through internals nobody measured. (2) It builds
`JpaCriteriaParameter(null, type, false, nodeBuilder)` (`rfc/0017-spike-results.md` consequence 1); the constructor is
found by its shape, so its `BindableType` package (`org.hibernate.query` on 6.6, `org.hibernate.type` on 7.4) needs no
per-line class. (3) Which node type each line expects is fixed per line, not discovered: on 6.6 a path's node type is
a path source unwrapped through `getSqmPathType()`, and an aggregate's may already be the basic type; on 7.4 the node
type is the basic type and is never unwrapped. The unwrapped type must be a `BasicType` the constructor takes; anything
else, an entity or embeddable path included, throws, which the engine reports as `MQ1502` naming the operator and the
column (D-169 (3)). Rejected: the spike's fallback to the raw node type when the unwrap is missing, which on 6.6
fails late with a `ConversionException` for an enum or a `@Convert` value, and on a moved line could bind by class.
(4) Only a path, or an aggregate over one (its single argument a path, `DISTINCT` or not), is typed by its node type:
for any other expression (a function, `coalesce`, `CASE`, arithmetic, a literal, a sub-query or correlated column, an
aggregate over one of these) Hibernate may infer the node type from the expression's Java class alone, so an enum over
`function("f", Phase.class, path)` would bind by ordinal and match nothing (R-FLT-21, INV-5). The binder declines those
through `OperandBinder#typesBy`, a default method that is `true` unless overridden, and the engine applies the
no-binder rule (D-168 (6)): a `String`, `java.lang` or `java.math` number or `java.time` value binds by its class,
anything else is `MQ1502` naming the operator and the column. Its `parameter` throws for a declined expression too.
Rejected: the binder throwing for every such expression, which would refuse a `String` over `upper(name)` that binds
correctly by class; repeating the no-binder check in `model-query-hibernate`. → `vendor/40` R-VND-24, `api/12`
R-FLT-21, AC-FLT-22, `reference/90` `MQ1502`.

**D-171 — Hibernate's provider check, `ilike` and `regexMatch` (M29.4, 2026-10-21).** (1) The `FunctionContributor`,
`HibernateOperatorFunctions`, registers `mq_regex_match` as `(?1 ~ ?2)`, boolean, exactly two arguments (`regexMatch`'s pattern is unconverted, D-174), when the
dialect is a `PostgreSQLDialect` (subclasses included; `CockroachDialect` is not one). The API is the same on both
lines. (2) `ProviderSupport#operators(emf, f)` answers `ILIKE` by the dialect (PostgreSQL's or H2's; D-175 replaces the
list with `supportsCaseInsensitiveLike()`), and a pattern
feature by looking up each `mq_*` function it renders through in the factory's registry, all of which must be there:
`mq_array_contains` and `mq_array_overlaps` for `ARRAY_CONTAINMENT`, `mq_jsonb_contains`, `mq_ts_match`. The three
not registered yet answer `false` until their slices register them. Another provider's factory answers `false`.
(3) `ilike(mode)` prepares its value with `LikeMode#pattern`, the helper `like` uses, moved from `ConditionGroup` to
`LikeMode` as an `@EngineFacing` method beside `escapeCharacter()`, so the two can't drift; `like`'s SQL is unchanged.
Like `like`, `EXACT` passes no escape character, which Hibernate renders as `escape ''` on PostgreSQL and H2. In the
field form there is no value to prepare: the right column is the pattern as stored, so a stored `\` is literal under
`EXACT` and escapes the next character under the other modes. (4) The operator names are `ilike(<MODE>)` and
`regexMatch`; each factory returns one cached instance. (5) A renderer given a criteria builder that isn't Hibernate's
throws `MQ4014`: only a provider check answering `ILIKE` true for another provider reaches it. (6) For a
`FilterOperator<C, C>` a converted column's converter runs before `prepare`, so `prepare` builds on the attribute
value as `like` builds its pattern (user, 2026-10-21: like's order). This supersedes D-168 (2)'s step order, under
which `ilike(CONTAINS)` "12" over a converter adding `SKU-` bound `SKU-%12%` where `like` binds `%SKU-12%`, different
rows with no error (INV-5); `rfc/0017` stays as written. Since `prepare` is a `C -> C`, such an operator on a converted
column whose attribute type is not `C` is `MQ1001` when the filter is added, never a `ClassCastException`; with no
`prepare`, in the field form and in the `<C, V>` form nothing changes. (7) A JPA `@Convert` attribute is not a
`ColumnConverter`: Hibernate itself runs its `AttributeConverter` on the bound pattern, measured on both lines and
Tier 1 (`ConvertedLikeTest`). `like`'s finished pattern is converted, as a parameter and as an inlined literal, and
`ilike`'s, typed by the binder (D-170), is too; `likeIgnoreCase`'s is not, since the parameter meets `lower(col)`, so
over a converter adding `SKU:` its `EXACT` "A12" binds `a12` and misses the stored `SKU:a12`: silently no rows. That is
recorded as found, not changed here (shipped `like` behaviour; see Q-30, resolved by D-177). → `vendor/40` R-VND-23,
`api/12` R-FLT-06, R-FLT-20, R-FLT-21, AC-FLT-21, AC-FLT-24, `reference/90` `MQ1001`, `rfc/0017` §4.

**D-172 — Array and `jsonb` containment (M29.5, 2026-10-21).** (1) `HibernateOperatorFunctions` registers
`mq_array_contains` as `(?1 @> ?2)`, `mq_array_overlaps` as `(?1 && ?2)` and `mq_jsonb_contains` as
`(?1 @> cast(?2 as jsonb))`, boolean-valued with two arguments, in the same PostgreSQL-dialect branch as
`mq_regex_match` (user, 2026-10-21: PostgreSQL's dialect only), so `ARRAY_CONTAINMENT` and `JSONB_CONTAINMENT` now
answer `true` there and `false` on every other dialect; `FULL_TEXT` still answers `false` (D-171 (2); served
since D-173). Never
Hibernate's `array_includes` / `array_overlaps`, which are registered on H2 too (`rfc/0017` §4). (2) The names are
`arrayContains`, `arrayOverlaps` and `jsonbContains`; each factory returns one instance shared by every element or
column type, so `arrayContains()` for `String[]` and for `Integer[]` is the same object. (3) None requires an
attribute type: an array operator's column is an `E[]` by its type, and `jsonbContains` takes any column; an array
guard would refuse a `List<String>` attribute behind a `ColumnConverter` (user, 2026-10-21). `arrayContains` and
`arrayOverlaps` take a boxed-array model type (`Integer[]`, not `int[]`): a primitive array has no binder typing in
`rfc/0017` §4, and support for one would be additive later (user, 2026-10-21; amended at the M29 gate). The array
operators' value has no declared type and binds through the `OperandBinder` typed by the column (D-170); with no
binder it is `MQ1502` (D-168 (6)). `jsonbContains`'s value is the document's text, bound as a `String` and never
converted (the `<C, V>` form). (4) The library doesn't parse that text: no JSON library is a dependency of the
hibernate module (Jackson is absent on 6.6); malformed text is PostgreSQL's error when the statement runs, loud rather
than silently wrong (INV-5), as an invalid `regexMatch` pattern is. (5) PostgreSQL's
semantics pass through unchanged: every array contains the empty one, and a `null` column matches neither a filter
nor its `not(...)`. → `vendor/40` R-VND-23, `api/12` R-FLT-20, AC-FLT-24, `rfc/0017` §4.

**D-173 — Full text through `plainto_tsquery` (M29.6, 2026-10-21).** (1) The request text goes through
`plainto_tsquery`, not `websearch_to_tsquery` (user, 2026-10-21). A probe on PostgreSQL 17, both lines, over the rows
`Acme Corp`, `Acme Industries`, `Global Corp`, `O'Brien and Sons`, `The "Best" Widgets` and a `null`: neither function
raised a database error on any input, and both kept `gin (to_tsvector('english', name))` (a Bitmap Index Scan with
`enable_seqscan = off`, and under `force_generic_plan`). `plainto_tsquery` reads the text as words that must all
match: `acme corp`, `acme, corp.`, `"acme corp"`, `acme or corp` and `acme -corp` are all `'acm' & 'corp'` (Acme
Corp); `o'brien` is `'o' & 'brien'`; operators (`&`, `:*`, `-`) are ignored. `websearch_to_tsquery` reads it as a
query language: `acme or corp` is `'acm' | 'corp'`, quotes make a phrase, and `-` negates, so `-acme` is `!'acm'` and
matches every non-null row without the word (three of five here): a search box turning a narrowing filter into a
nearly full match from a pasted hyphen is silently wrong (INV-5). `websearch_to_tsquery` may come later as a separate
operator. `to_tsquery` stays out (`rfc/0017` §4). (2) Text with no lexemes (empty, blank, only stop words such as
`the`, or only operators such as `&|!():*`) matches no row, never every row, and PostgreSQL raises only a notice
(`text-search query doesn't contain lexemes` / `contains only stop words`), no error; the library doesn't refuse it,
and the javadoc tells callers to skip the filter for blank input (user, 2026-10-21). (3) `HibernateOperatorFunctions`
registers `mq_ts_match` as `(to_tsvector(?1, ?2) @@ plainto_tsquery(?1, ?3))`, boolean-valued with three arguments,
in the PostgreSQL-dialect branch, so `FULL_TEXT` now answers `true` there and `false` elsewhere. Both calls sit in the
pattern and take the one configuration argument, so the two literals can't differ and the statement always has the
form `gin (to_tsvector('<config>', col))` matches. (4) `HibernateOperators.fullTextMatch(String configuration)` is a
`FilterOperator<String, String>` in the `<C, V>` form on a `String` attribute (`MQ1001` otherwise), named
`fullTextMatch(<configuration>)`; the text is bound as a `String`, never converted. The configuration must match
`[A-Za-z_][A-Za-z0-9_]*`, optionally one `.`-qualified more (`english`, `public.my_config`); null, blank or anything
else is `MQ1315` at the factory call, naming it, so nothing can break out of the literal. It is passed as
`cb.literal(configuration)` and renders inline (`to_tsvector('english',…)`), never bound. The factory caches one
instance per configuration in a `ConcurrentHashMap`; an invalid one is never cached. → `vendor/40` R-VND-23,
`api/12` R-FLT-20, R-FLT-21, AC-FLT-32, `reference/90` `MQ1315`, `rfc/0017` §4. (5) The field form
`op(fullTextMatch(c), left, right)` works: it matches one column's text as the query of another (M29 gate).

**D-174 — `regexMatch` takes its pattern unconverted (M29 gate, 2026-10-21).** `regexMatch()` is a
`FilterOperator<String, String>` in the `<C, V>` form: its pattern is text matched against the stored column and is never
passed through a `ColumnConverter` or a JPA `AttributeConverter`, as `jsonbContains`' and `fullTextMatch`'s text isn't
(D-172 (3), D-173). With the `<C, C>` form a converter rewrites the pattern (an upper-casing converter turns `\d` into
`\D`), giving silently wrong rows (INV-5). It binds as a `String` with no binder (D-168 (6), D-169). Rejected: `MQ1001`
on converted columns, which refuses a legitimate regex over converted text. This supersedes D-171's `regexMatch` as a
`<C, C>` operator where it differs. → `api/12` R-FLT-21, AC-FLT-24, `vendor/40` R-VND-23, D-171.

**D-175 — Hibernate answers `ILIKE` by `supportsCaseInsensitiveLike()` (M29 gate, 2026-10-21).** Amends D-171 (2): the
provider's `ILIKE` answer is the dialect's `supportsCaseInsensitiveLike()`, not a list of dialects (user, 2026-10-21).
`AbstractSqlAstTranslator` renders a native `ilike` iff it is `true` and emulates it with `lower()` otherwise, measured
on 6.6.58 and 7.4.11; it is `true` on `PostgreSQLDialect`, `H2Dialect` and `CockroachDialect`. Against the old list,
`CockroachDialect` goes from `false` to `true`, and 7.4's `SpannerPostgreSQLDialect`, which overrides it to `false`, from
`true` to `false` (`MQ4014`). The vendor check still runs first and answers `ILIKE` for PostgreSQL and H2 only
(R-VND-23), so CockroachDB is `MQ4015` unless a profile answers `ILIKE`. → `vendor/40` R-VND-23, AC-FLT-24, D-171.

**D-176 — Inline count, converted ignore-case filters, inline value handling and filter-only to-many joins join 0.10.0
(2026-10-21, user decision).** RFC 0018's four parts ship in 0.10.0 as M30, after M29, with an RFC acceptance spike
first (M30.0): `CountMode.INLINE_COUNT` (Q-29), `likeIgnoreCase` and `eqIgnoreCase` over a JPA `@Convert` attribute
(Q-30), the engine's value paths under `value_handling_mode=inline` (Q-28) and filter-only to-many joins (Q-31). Q-28
keeps R-FLT-08 true under a setting the engine can detect; Q-30 and Q-31 change a shipped filter's rows, which is
cheaper before D-85 freezes it; Q-29 is additive but is decided with them so the freeze review places `CountMode`
once. The freeze moves from M30 to M31 and stays last, and M30's last slice finishes the 0.10.0 release prep.
Everything new is `@Incubating`, so the M31.1 freeze review decides each type. → `rfc/0018`,
`docs/plan/mvp-plan.md` §30–§31, `delivery/62`, D-85, D-148.

**D-177 — Inline count, converted ignore-case filters, inline value handling and filter-only to-many joins
(RFC 0018, 2026-10-21, user decision).** All four parts are accepted for M30 / 0.10.0, with the spike's amendments.

**Part A (Q-29):** keep opt-in `CountMode.INLINE_COUNT`, never the default, with `COUNT`'s exact total and
R-EXE-15's correctness fallbacks and preflight. The window is `count(1) over ()` through `SourceRenderer#window`,
gated by `ProviderSupport#inlineCounts` and a source renderer; Hibernate answers from the dialect's window support.
The user's acceptance gate is no loss beyond run-to-run spread against `COUNT` on selective first pages on both
PostgreSQL and MySQL; a tie qualifies, and flat selective first pages tie on both Hibernate lines and databases.
Grouped middle/deep pages improved to ~0.53–0.71x, while unselective flat pages cost ~8–20x first and ~2–8x
middle/deep (deep PostgreSQL ~1.3–1.4x), and grouped selective first pages cost 14–32x in the laptop fixture
(grouped unselective first pages ~1.04–1.20x), with an offset-0 planner cause inferred, not established by EXPLAIN.
The docs say when it pays and costs and to check real plans; the user chooses the mode, with no automatic
performance fallback. Fallback reasons log once per executor/query, keeping mutable state off shared definitions
(INV-9).

**Part B (Q-30):** keep `OperandBinder#converts`, inspecting the same mapped node type the binder uses, and fold
both sides in SQL with the mapped parameter for converted attributes; unconverted columns render as today.
The spike confirms `eqIgnoreCase`, `EXACT` and `STARTS_WITH` miss over `SKU:` today; `CONTAINS` and `ENDS_WITH`
return the right rows only by accident of their leading `%`, while the proposed rendering works on both lines
and Tier 1. MySQL's default case-folding collation is a control observation. Keep the documented folding-property
and missing-binder limitations; do not refuse converters the provider cannot classify.

**Part C (Q-28):** keep R-FLT-08 under inline handling through `ProviderSupport#inlinesValues` and the shared
`@EngineFacing` value-binding helper: mapped parameter where the binder types the expression, a class-typed
parameter only for R-FLT-21's mapping-independent values, otherwise per-path `MQ1502` before any statement.
The audit observes 23 paths on both lines and Tier 1: 22 input leaks and one already-bound typed-insert control;
explicit-parameter probes preserve mapped types, padding and computed cursors, and the targeted default SQL
control stays equal. Carry the named inventory into R-FLT-08 / AC-FLT-35, classifying structural literals separately;
the broad detector's 20,739 candidates and 912 failing tests per line do not count independent defects.
The ongoing TCK profile covers all execution families. Default rendering stays unchanged; without
`model-query-hibernate`, `inlinesValues` stays false. `MQ1502` remains a `ModelQueryDefinitionException` even for a
call-time key/cursor input the installation cannot bind; its message names that path, not only an operator.

**Part D (Q-31):** keep the whole-predicate correlated semi-join over a second root, with entity equality, only
where no other outer term keeps a to-many join and the root is not treated; customizer joins and §D.1's other
exclusions stay as designed. The spike demonstrates wrong offset-page totals and inflated root aggregates today,
then correct rows, totals, boolean predicates, embedded-id equality and locked reads with the semi-join on both
lines and Tier 1. PostgreSQL ran it roughly 2–3.5x faster than join or DISTINCT, MySQL 8.0 1.4–3.6x slower with
duplicate weedout / a second root lookup; these are single laptop EXPLAINs, so advise checking real plans and
indexes. No unique-per-root escape hatch and no restoration of the duplicating join; document slower shapes.

New ids: R-EXE-15, R-EXE-16, R-VND-27, R-VND-28; AC-EXE-18–24, AC-FLT-33–36. Amend R-EXE-02, R-EXE-04,
R-EXE-14, R-FLT-06–08, R-VND-24, R-PAG-02, R-PAG-21 and AC-PAG-28; move AC-EXE-11, AC-EXE-13 and AC-SPR-19's
`MQ2003` cases to a customizer join or excluded shape. New public types and members are `@Incubating`.
→ `rfc/0018`, `rfc/0018-spike-results.md`, `api/12`, `engine/20–21`, `vendor/40`, `integration/50`,
`reference/90` `MQ1502`, D-167, D-170, D-171, D-175, D-176; Q-28–Q-31 resolved.

**D-178 — A to-one written by id binds its reference past the operand binder (M30.5, 2026-10-22).** Under inline value
handling (D-177 Part C) a to-one assignment written by id binds its reference as `cb.parameter(targetEntityClass)`, not
through the `OperandBinder`: Hibernate's binder throws on an entity path, and R-FLT-21's operator rule would then make
every to-one assignment `MQ1502`, since an entity reference is not a mapping-independent value. The target entity's
class alone gives the parameter its mapping, so the reference binds as its identifier does. Under the default setting
the reference binds as before.
→ `api/12` R-FLT-08, `api/14` R-WRT-14, D-177.

**D-179 — Write, conflict and source values under inline value handling (M30.6, 2026-10-22).** Under inline value
handling (D-177 Part C) a delete's filters and an insert-select's source predicate bind by R-FLT-21's operator rule, as a
query's do. A conflict update's `where` gets a bind channel: its parameters are recorded per statement and the executor
binds them on each insert statement, so `ConflictClause` does not change; `MQ1804` for an `Expr.value` or an operator's
value form still reads the `where` as the default setting renders it. A conflict arbiter predicate stays a literal,
since a partial unique index matches its text. A source's CTE body is no longer built with inline values off: its
parameters are collected per body and bound by the statement that registers it. As a side effect an `Expr.value` in a
source's `where` now binds under the default setting too, where before its parameter was never bound and the statement
could not run. Hibernate 6.6 reports a primitive attribute's node type (`int`, `boolean`) as a JPA basic type that is not a
Hibernate `BasicType`; the operand binder accepts it, since it carries no converter or enum mapping, so a typed insert or
a filter on such a column binds rather than being `MQ1502`.
→ `api/12` R-FLT-08, `api/17` R-SRC-12, `vendor/40` R-VND-24, D-177.

**D-180 — When a read's `where` becomes a semi-join (M30.7, 2026-10-22).** D-177 Part D's eligibility, as built:
(1) the statement must keep no to-many join at all once its selection, order and group keys are rendered, read off the
Criteria root's joins, so a join a selected column, order term or group key makes, by any path, excludes it; with no
outer to-many join, comparing join paths reduces to whether the scratch `where` made any. (2) The scratch reads its
own sub-query root's joins, so a to-many join an `add(...)` predicate makes itself counts too, and moves with the
`where`. (3) "The root is not treated" is applied as no treated node anywhere in the query's own statement
(`TreatChecks.treated`, R-COL-35): such a statement keeps its anchor and root-id count (D-154), and a treat moved into
the sub-query is a site no renderer was measured for. (4) `having` gets a scratch render of its own; one that would
make a to-many join keeps the statement as before, rendering `where` and `having` together (D-26). (5) Sources and a
fetch plan's `through` child loads are never rewritten. (6) Every scratch render runs on a throwaway statement, so no
unused sub-query or parameter reaches the statement; the real sub-query's parameters and repeated binds are added to
the statement's (R-COL-26, R-FLT-09). Which of the `where`'s terms move is D-181.
→ `engine/20` R-EXE-04, R-EXE-14, R-EXE-16, `api/14` R-WRT-10, D-26, D-65, D-154, D-177.

**D-181 — A semi-join leaves the root terms on the statement (M30.7, 2026-10-22).** D-177 Part D moved the whole
`where` into the semi-join's `exists`, so the statement kept no predicate on its own table, and a selective root term
(`id < ?`, a status) could not narrow the outer scan through an index on PostgreSQL or MySQL. Now each top-level
`where` term is rendered alone into a scratch sub-query (D-180 (6)); the split applies only when one of them makes a
to-many join. Then a root term, one that makes no join and no `exists(...)` sub-query, as R-WRT-11 tells a key-first
write's, renders on the statement only, not again inside the sub-query; every other top-level term moves into
`exists (r2 = r and ...)`. A top-level `or` or `not(...)` is one term, so one mixing child and root terms moves whole,
and its meaning is kept as R-WRT-10 keeps it. A term through a to-one join moves too, so a locked read whose `where`
joins only to-one paths beside the to-many one still leaves the statement no join (R-EXE-14). `C(r) and exists (r2 = r
and rest)` chooses the rows `exists (r2 = r and C and rest)` does, since `C` reads only `r` and `r2 = r`; no row, total
or eligibility changes. The root terms render with `having` (D-26), the `exists` after them; each value still binds
once.
→ `engine/20` R-EXE-16, `api/14` R-WRT-10, R-WRT-11, D-26, D-177, D-180.

**D-182 — `ProviderSupport#inlineCounts` keeps its name (M30 gate, 2026-10-22).** The gate review asked whether
`inlineCounts(emf)` reads as a noun returning counts beside `inlinesValues(emf)`, and proposed `rendersInlineCount`.
It keeps its name: both answer in the same third-person verb form ("the provider inline-counts", "the provider
inlines values"), and the method is `@Incubating`, so a later rename stays a marked breaking change.
→ `vendor/40` R-VND-27, D-177.

**D-183 — Immutable builders carry a package-private `@CheckReturnValue` (2026-10-22).** LitePOS reported an
ignored `select(...)` result that compiled and failed only at run time (`MQ1202`). `core` gains a package-private
`CheckReturnValue` annotation (class retention, type target) on the types whose methods return a new immutable
value: `ModelQuery` (and its `Builder`), `SourceQuery`, `FetchPlan`, `ChildQuery`, `SelectSet`, `ModelInsert`,
`ValuesInsert`, `ModelUpdate`, `ModelDelete` and `ConflictUpdate`. Error Prone's `CheckReturnValue` check matches
the annotation by simple name, so this adds no public type, no dependency and no RFC (R-REL-14). `Filters` and
`Having` are left out: they change the receiver, so a dropped result is not a bug. Records, `RenderOptions` and
engine-facing types are left out too. Rejected: a dependency on `error_prone_annotations` or JSR-305, which `core`'s
JDK-plus-`jakarta.persistence` rule (INV-7) excludes; a public annotation, which would be new API surface.
→ `CHANGELOG.md` [Unreleased].

## 2. Open questions

**Q-1 — Project name and coordinates.** Resolved by D-77.

**Q-2 — MySQL streaming default.** Resolved by D-91. `row-by-row` is faster but blocks other statements on the
connection until the result is read; `useCursorFetch` does not. The current default is `row-by-row` with the caveat
documented. Should the default flip? → `vendor/41` R-PRF-04, R-PRF-07.

**Q-3 — Minimum Hibernate version.** Resolved by D-78.

**Q-4 — Cursor serialisation.** Resolved by D-105. Was: `0.1-reserved` mentions a `Cursor` format for passing a keyset position to a client.
Should 0.1 ship an opaque encoded form (so a REST API can page without exposing column values), or leave it to callers?
→ SPEC.md §4.

**Q-5 — `Agg.of` scope.** Resolved by D-27.

**Q-6 — Bean Validation on change sets.** Resolved by D-15.

**Q-7 — Per-chunk commits without Spring.** Resolved by D-16.

**Q-8 — Per-page enrichment and to-many children.** Resolved by D-96. Was: A report model often needs more than its own row: a child
collection with its own columns (an order's lines), or a value computed per row by a lookup outside the query. Today the
caller does it: `export` hands each page to `pageTransformer` (`engine/21` R-PAG-09), but `page` and `list` have no
per-page hook, and `afterMap` runs per row, where a lookup is one query per row (D-11). A selection through a to-many
join is refused (R-PAG-13), so a child collection is a second query on the parents' keys, batched by hand within the
vendor's IN-list limit. Should 0.x add (a) a per-page hook on `page` and `list` matching `pageTransformer`, and/or
(b) a declared to-many child with its own `SelectSet`, loaded by the executor in batches on the parents' keys, reusing
the primary-key-first step-2 batching and clamp (R-PAG-07, D-32)? Either is new public API, and (b) must keep memory
bounded by one page (INV-4). → `api/11`, `engine/21`.

**Q-9 — The fetch-size hint in the built-in profiles.** Resolved by D-108. Was: The built-in profiles stream by passing the
`org.hibernate.fetchSize` hint, as `vendor/41` §2 mandates. That is provider behaviour inside a database profile,
against D-34's split, and under another provider the hint is ignored, so streaming may buffer. Should the hint move
to `ProviderSupport`? The resolver's warning on `hibernate.order_by.default_null_ordering` without
`model-query-hibernate` (`vendor/40` R-VND-07) reads a second Hibernate name in `jpa`, by necessity: it fires only
where no `ProviderSupport` exists to ask. → `vendor/41` §2, R-PRF-04, R-VND-07, D-34.

**Q-10 — A nested model from another module.** Resolved by D-45.

**Q-11 — Processor diagnostics hidden or missing (left from the M4 gate).** Resolved by D-107. Was: Two diagnostics wait for another to be
fixed, against R-DIAG-03: an `MQ3015` prefix clash is not reported while the clashing `@Join` fails its own check, and
an `MQ3014` is not reported while its path does not resolve. A model whose `root` is a type another processor generates
in the same round is skipped with no diagnostic and no QModel. Open: report the hidden codes in the same pass, and
defer such a model to a later round (reporting it if the type never appears), or document the gaps. → `processor/32`
R-DIAG-03.

**Q-12 — Detecting two entities mapped to one table.** Resolved by D-109. Was: A bulk write whose sub-query reads a second entity mapped to
the root's table is not detected (JPA exposes no table names, INV-7), so MySQL fails with error 1093 where the write
should have run key-first. Should `VendorProfile` or a jpa provider hook report an entity's table so that `jpa` can
detect it? → `api/14` R-WRT-11.

**Q-13 — Inspecting a fetch plan's child queries.** Resolved by D-104. Was: `conditions()` covers the query's own `where` and `having`
(D-101); a test cannot read a child query's filters, order or `maxPerParent`, which sit behind the `@EngineFacing`
`ChildLoad`. Should `FetchPlan` gain a public read-only view of its children, or should child queries be tested only
against a database? → `api/16` R-INS-06, `api/15`.

**Q-14 — Reading a to-one by id on a query model.** Resolved by D-136. Was: A query model reads a foreign key only
through the whole entity (`MQ3016`), a converter, or an entity that maps the column twice (D-124). Should
`@Column(attribute = "customer") Long customerId` read the id on a query model, as an update model writes it? Open:
whether it renders a LEFT join or the foreign-key column, and a TCK case with a `NULL` foreign key on every vendor.
Related: a `@GroupBy` on a whole-entity to-one column fails on PostgreSQL, since Hibernate groups by the foreign key but
selects every entity column; whether that is a diagnostic or a group by the id is open. → D-44, D-124.

**Q-15 — An opt-in returning emulation on MySQL.** MySQL has no `RETURNING`, so D-129 refuses returning writes there
(`MQ4011`). A locked key read, a `whereKeys` write and a `byKeys` re-read give the same rows for an update and are
documented as a recipe; whether an explicit opt-in that does this inside the library is ever wanted is left open until
an adopter asks. → `rfc/0010`, D-129.

**Q-16 — A single-statement claim, and a typed `nextval`.** D-131 keeps a claim to a locked key read and a `whereKeys`
write. On PostgreSQL one statement could do both (`WITH c AS (SELECT id … LIMIT n FOR UPDATE SKIP LOCKED) UPDATE … FROM
c … RETURNING …`), once a supported Hibernate API renders a locked CTE in DML; open whether to offer it. Also open: a
typed `nextval(String, Class<C>)` for a sequence read as an `Integer` or a `BigInteger` rather than a `Long`. →
`rfc/0010`, D-130, D-131.

**Q-17 — Refusals after a first statement.** Resolved by D-138.

**Q-18 — A `@RequiresFact` ArchUnit rule.** Whether code that reads a profile fact must declare it, so the dry run's
coverage is checkable. → `rfc/0011` §5, D-133.

**Q-19 — Nested models bound in another module.** Binding a nested model in a different persistence module from its
outer model. → `rfc/0012`, D-134.

**Q-20 — A to-one's id read without a join.** Whether a read of the id form (D-136) renders the foreign-key column
with no join where the provider reports the foreign key references the target's basic id directly (owning side, no
`@NotFound`, no formula), a provider fact as D-117 and D-132 are. It reads the same rows as the `LEFT` join, so adding
it changes no result; it would let a locked read of the id form through instead of `MQ2211`, and lift `MQ3029`, since
the id would then come from the foreign-key column of the entity's own row. → D-136.

**Q-21 — Recursive sources counted as unique.** Resolved by D-147 (`tree(...)`, M26). Was: whether a duplicate-key check in the same statement (the spike's
`count(*)` against `count(distinct key)` over the CTE, counting the cycle-marked row too) lets a recursive source over
a tree count as unique, so fetch plans and keyset paging work on trees. It checks only the statement it runs in, so
export and keyset walks across statements need more. → `rfc/0013` §5, D-143; M26, `rfc/0014` (D-145), D-147.

**Q-22 — Sources over sources, and reading a source directly.** Resolved by D-150 (M27). Was: window and aggregate fields over the combined rows of
a set operation or a recursive source, and a source model's QModel also offering `query()`, so a source can be read
without a trivial outer model. → `rfc/0013` §3–§5, D-143; M27, `rfc/0015` (D-145).

**Q-23 — `generateChanges` on a model rooted at a source.** Resolved by D-145 (a code, M25.6). Was: a write needs an entity root, so a source model and a
model rooted at one generate no `changes()`, `update(...)`, `from(...)` or `delete()` today, and `generateChanges` is
ignored there. Should it instead be an `MQnnnn`, as a `@Child` or a `@Join` on such a model is? → D-144,
`processor/31` R-GEN-37, `processor/32`.

**Q-24 — Request-time values in a `@Computed` expression.** A `@Computed` definition builds once into a constant, so a
value known only per call ("count lines with the status the request names", "days overdue relative to a date the
caller passes") can't appear in it, correlated or not. A per-call bound parameter node in any expression, bound when
the query runs, would answer it without breaking INV-9. → `rfc/0015` (Unresolved), D-150, `api/10` R-COL-17.

**Q-25 — `@Subtype`'s value type.** `Class` only for now (D-154). A `String` (entity name or discriminator value) or
an enum through a converter is additive, once a use shows which; `JOINED` has no discriminator value to give, and a
`@Computed` CASE covers it today. → `rfc/0016` §4, D-154, `processor/30` R-PROC-43.

**Q-26 — `TABLE_PER_CLASS` id uniqueness.** The concrete tables share no key, so `@Id` is unique by mapping, not by
the database, and is trusted as today; R-PAG-26's `MQ2213` is a partial guard on the reads that rely on a unique key,
while `list()` and offset pages stay unchecked. Revisited if a user needs keyset or export over such a root with ids
written outside JPA. → `rfc/0016` §6, D-154, `engine/21` R-PAG-26.

**Q-27 — Group keys and aggregates over treated fields.** Refused in M28 (`MQ4016`): Hibernate narrows a grouped
statement to the treated subtype. The LEFT entity join on the id (spike S2.4) and a `min(root.id)` anchor beside the
original group key (S2.3) keep the NULL rule in every measured shape; a later release designs the rendering, measures
its cost and composition, and lifts the refusal, with `treat` on `@Aggregate`. → `rfc/0016` §2, §11, D-154, `api/10`
R-COL-35.

**Q-28 — The engine's value paths under `value_handling_mode=inline`.** Resolved by D-177. Was: RFC 0017's spike found
that `cb.equal(path, value)`-style binding, where Hibernate turns the value into a parameter itself, inlines the value
under `hibernate.criteria.value_handling_mode=inline`, while an explicit parameter never does. An operator's operand
is explicit (D-167); whether any engine value path (filters, writes, keyset cursors, sources) relies on Hibernate's
conversion, and so breaks R-FLT-08 under that setting, is unchecked. An audit and a TCK case under that setting would
answer it. An operator's value, typed by Hibernate's binder, stays `?` under it on both lines and Tier 1 (D-170,
AC-FLT-22); the other paths are still unchecked. → `rfc/0017` §1, D-167, D-170, `api/12` R-FLT-08, `rfc/0018` Part C
(M30, D-176).

**Q-29 — An inline count.** Resolved by D-177. Was: `CountMode.COUNT` runs a separate count statement (R-EXE-03);
Blaze-Persistence can instead inline `COUNT(*) OVER()` into the page query. That saves a round trip and takes the
total and the page from one snapshot without a transaction (cf. R-FCH-06), and counts groups on a grouped query.
Against it: the window evaluates the whole result before `LIMIT`, losing the top-N early stop, often slower than a
separate count on large sets; a page past the end returns no row and so no total, which needs a count fallback; keyset
pages carry no total (D-105); JPA Criteria has no window functions, so it sits behind `ProviderSupport` with `COUNT`
as the portable fallback (INV-7). If adopted, an opt-in `CountMode` constant, never the default; additive, so not
freeze-blocking. → `engine/20` R-EXE-03, D-34, D-51, D-105, `rfc/0018` Part A (M30, D-176).

**Q-30 — Case-insensitive string filters over a `@Convert` attribute.** Resolved by D-177. Was: on a JPA `@Convert`
`AttributeConverter`, `likeIgnoreCase` renders `lower(col) like ?` and Hibernate does not run the converter on the
bound value, so `EXACT` and `STARTS_WITH` return no rows with no error (`CONTAINS` happens to be right); converting
would not help, since `lower(col)` folds the stored value. `ConvertedLikeTest` pins this. `eqIgnoreCase` is probably
the same and is untested: confirm it first. Proposed direction: refuse `likeIgnoreCase` and `eqIgnoreCase` on a
`@Convert` attribute with `MQ1001` through a new `ProviderSupport` fact (core cannot see `@Convert` in the JPA
metamodel), by RFC, since it narrows shipped operators. Until then `api/12` R-FLT-07 documents the limitation. →
D-171, `api/12` R-FLT-07, INV-5, `rfc/0018` Part B (M30, D-176): proposes converting the value through the binder and
folding both sides in SQL instead of refusing; built so in M30.3 (`OperandBinder#converts`, `vendor/40` R-VND-24,
AC-FLT-33, AC-FLT-34).

**Q-31 — `COUNT` against a page over a predicate-only to-many join.** Resolved by D-177. Was: a filter through a
to-many join that selects no to-many column reaches `page` (R-PAG-13 lets it through), and its content statement has
no `DISTINCT`, so a root matching on several children is several rows, while `COUNT` reports `count(distinct root)`
(R-EXE-04). The total and the rows can then disagree (`warnOfContradictedTotal`). Found by RFC 0018's first review;
Part A falls back to `COUNT` here rather than inherit it. Whether the content statement should be distinct on the
root, or `COUNT` should count rows, is open; `Filters.exists` (R-FLT-12) avoids it today. `rfc/0018` Part D (M30,
D-176, user 2026-10-21) proposes a correlated semi-join holding the whole predicate. → `engine/20` R-EXE-02, R-EXE-04,
`engine/21` R-PAG-02, R-PAG-13, `rfc/0018` §A.3, Part D.

## 3. Risks

| Risk | Mitigation |
|---|---|
| Criteria API differences between Hibernate 6 and 7 | CI matrix on both. Version-specific code only in `model-query-hibernate`, behind small adapters. |
| Lombok naming rules drift | compile-testing cases per rule. A mismatch breaks compilation of the generated code loudly, not silently. |
| PostgreSQL streaming misuse (no transaction) | `checkStreamingPreconditions` fails fast; the Spring module opens a transaction automatically. |
| MySQL row-by-row streaming holds the connection for a whole export | Documented; keyset `export` is the recommended default for large exports. |
| `Optional` fields on models are unusual (not `Serializable`, need Jackson `jdk8`) | Documented. Only `@Join` fields use `Optional`; plain columns stay plain types. |
| `CASE WHEN … IS NULL` null-precedence fallback defeats index use | Used only without `model-query-hibernate`, and only when the requested precedence differs from the vendor default. |
| API churn before 1.0 | `@Incubating`, `0.x` versions, an explicit API review at M8, `japicmp` from 1.0. |
| Scope creep toward a general SQL builder | P-5 and `delivery/62` R-RDM-03. `QueryCustomizer`, `Agg.of`, `Filters.add` and, for bulk updates, `setExpression` are the only escape hatches, and each is documented as one. |
| Bulk writes surprise users who expect entity semantics (listeners, cascades, Envers and Bean Validation don't run) | Stated in the Javadoc of every write method and in the user guide. Flush and clear by default (`api/14` R-WRT-15), version increment by default (R-WRT-16). Clearing also detaches unrelated managed entities, whose later changes are then silently not written; stated in the same Javadoc, and `KEEP` is the alternative. The TCK pins down what the provider does for join tables and element collections. |
| A change set bound from a request lets clients write fields they shouldn't (mass assignment) | An update model lists exactly the writable fields, so the user guide recommends one per endpoint. `generateChanges` on a query model is documented as for internal use (`processor/30` R-PROC-19). |
| A timestamp `@Version` has the database's precision (one second on MySQL `DATETIME`), so two bulk updates in the same second leave the version unchanged and `expectVersion` misses the second | Documented; the user guide recommends a numeric `@Version` for rows that bulk updates touch. A TCK case pins the behaviour per vendor. |
| The spec drifting from the code | Every rule has an `AC-*` with a test named after it; the AC audit fails CI on an uncovered criterion (`delivery/60` R-QA-11). |
