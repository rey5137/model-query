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

**D-27 — No `Col.of`: a non-aggregate derived value belongs in `afterMap`.**
`Agg.of` is the escape hatch for aggregate expressions only, since any aggregate makes the query grouped (R-AGG-07).
A value computed per row from other columns is derived in `afterMap` (or a record's `finisher`) from the columns the
`ColumnSet` selects. Rejected: `Col.of(expression)` for computed non-aggregate columns, which would turn the column
model into a general expression builder (P-5). Resolves Q-5. → `api/11` R-QRY-05, R-QRY-08, `api/13` R-AGG-02.

**D-28 — Grouping is structural.**
A query is grouped when it has a `groupBy` or selects an aggregate, and nothing else: `having(...)` on any other query
throws `MQ1407` at build time, even when every filter it recorded was skipped, so whether a query is grouped never
depends on a request's values. Ordering must fit the grouping for the same reason: a grouped query orders by group
keys and aggregates, an ungrouped one never by an aggregate, else `MQ1406`. Rejected: `having` making a query grouped
(a skipped filter would silently turn a grouped query back into a row query with a different shape and row count).
→ `api/13` R-AGG-07, R-AGG-08, AC-AGG-08, AC-AGG-12.

**D-29 — The model phases select every key the executor reads.**
`MODEL` and `MODEL_BY_KEYS` select the `ColumnSet`, plus the primary key when one is defined on an ungrouped query,
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
holds (INV-5). An aggregate function over a converted column throws `MQ1408`, since the database computes over `F`.
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
generator adding the key to every joined `ColumnSet` (a hand-built `ColumnSet.of(CUSTOMER_NAME)` would lose it).
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

**D-43 — A presence key is selected for `ColumnSet` columns only, by the `presentBy` instance.**
Only a column of the query's `ColumnSet` brings in the presence keys of the joins it is read through; an order-only
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
assignability (it would let through what `MQ1001` refuses). → `processor/30` R-PROC-04, `processor/32` §1.

**D-45 — What an outer model declares for its joins, and where a nested model may come from.**
An outer QModel declares every join under a `@Join`, not only the join itself: for `OrderView.customer →
CustomerView.address` it has `CUSTOMER_TABLE`, `CUSTOMER_ADDRESS_TABLE =
QCustomerView.ADDRESS_TABLE.withParent(CUSTOMER_TABLE)`, a column `<PREFIX>_<constant of the nested QModel>` for each
column on either, and one `ColumnSet` per join (`CUSTOMER`, `CUSTOMER_ADDRESS`) holding that model's own columns.
Every constant is read from the QModel of the `@Join`'s own nested model, which already declares the joins below it,
so `Row.scoped` finds them where the nested mapper looks. The joined `ColumnSet`s follow `generateColumnSets`. When
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
`ColumnSet` holding the columns of every level (selecting a customer would always join its address); accepting only
models whose source is a root element of the compilation (it fails the incremental build above).
→ `processor/30` R-PROC-09, `processor/31` R-GEN-04, R-GEN-14, `processor/32` `MQ3003`, `MQ3005`, `MQ3015`.

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

**Q-5 — `Agg.of` scope.** Resolved by D-27.

**Q-6 — Bean Validation on change sets.** Resolved by D-15.

**Q-7 — Per-chunk commits without Spring.** Resolved by D-16.

**Q-8 — Per-page enrichment and to-many children.** A report model often needs more than its own row: a child
collection with its own columns (an order's lines), or a value computed per row by a lookup outside the query. Today the
caller does it: `export` hands each page to `pageTransformer` (`engine/21` R-PAG-09), but `page` and `list` have no
per-page hook, and `afterMap` runs per row, where a lookup is one query per row (D-11). A selection through a to-many
join is refused (R-PAG-13), so a child collection is a second query on the parents' keys, batched by hand within the
vendor's IN-list limit. Should 0.x add (a) a per-page hook on `page` and `list` matching `pageTransformer`, and/or
(b) a declared to-many child with its own `ColumnSet`, loaded by the executor in batches on the parents' keys, reusing
the primary-key-first step-2 batching and clamp (R-PAG-07, D-32)? Either is new public API, and (b) must keep memory
bounded by one page (INV-4). → `api/11`, `engine/21`.

**Q-9 — The fetch-size hint in the built-in profiles.** The built-in profiles stream by passing the
`org.hibernate.fetchSize` hint, as `vendor/41` §2 mandates. That is provider behaviour inside a database profile,
against D-34's split, and under another provider the hint is ignored, so streaming may buffer. Should the hint move
to `ProviderSupport`? The resolver's warning on `hibernate.order_by.default_null_ordering` without
`model-query-hibernate` (`vendor/40` R-VND-07) reads a second Hibernate name in `jpa`, by necessity: it fires only
where no `ProviderSupport` exists to ask. → `vendor/41` §2, R-PRF-04, R-VND-07, D-34.

**Q-10 — A nested model from another module.** Resolved by D-45.

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
