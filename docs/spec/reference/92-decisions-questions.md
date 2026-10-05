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
fails to map). → `api/10` R-COL-08, AC-COL-04.

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
assignability (it would let through what `MQ1001` refuses). → `processor/30` R-PROC-04, `processor/32` §1.

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
(`core`, class retention, methods only; D-86 lets it mark a type too): `ModelQuery.buildQuery`, `checkPhases` and `checkFetch`,
and on `ModelUpdate` and `ModelDelete` `checkMetamodel`, `writesNothing`, every `buildWrite` and `buildKeySelect`
overload, `readsTargetInSubquery`, `distinctKeys`, `startAfter` and `modelKey`. Like `jpa.vendor` (R-REL-10) they may
change in any release; `japicmp` excludes them. This makes D-67's `readsTargetInSubquery` boolean (now
`entitiesReadInSubquery`, D-109) non-API. →
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
(8) enrichers for a row carrying several keys (a payment order's payer, payee, initiator and an optional
requestor, each a user type and id), widened by the adopter's follow-up: each key routed to its role's field, one
lookup for the distinct keys across rows and roles, chunked lookups, a lookup split by a key part (the user type,
one datasource each), the null-key skip stated, values shared with another enricher of the same fetch (a child's),
and request-time parameters; (9) `keyset()` and `primaryKeyFirst` over String and `@EmbeddedId` keys, tested
and documented. Items 2–5 and 8 get their own `architect-review` before their slices and are recorded as their own
`D-n`; this decision fixes the scope and order only. D-114 ships item 8's partitioning, shared values and
request-time parameters as tested recipes rather than library API. → `delivery/62` §1, `docs/plan/mvp-plan.md` §10–11.

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
and a null requestor cannot throw inside `Map.of`. Chunking is library API because any key lookup meets an IN-list or
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
