# LitePOS gap analysis and support plan

Source: `litepos/docs/plan/model-query-gaps.md` (written against 0.8.0). Checked against `main` at `d60c32a`.

## 1. Already supported, or the gap doc is wrong

| Gap | Status in 0.8.0 | How |
|---|---|---|
| Row 4 / sugg. 5, self-referencing **bulk update** | **Supported** | `ModelUpdate.Builder.setExpression(COL, (path, cb) -> cb.sum(path, 1))` (`ModelUpdate.java:752`). Gap is only the conflict `doUpdate` (see M20). |
| Sugg. 5, `Expr.plus(COL, 1)` | **Supported for reads/filters** | `Expr.plus/minus/times/...` (`Expr.java:68`). Not accepted by `set(...)` or `ConflictUpdate`. |
| Row 9 / sugg. 7, literal-argument functions (`date_trunc('day', col)`) | **Supported** | `Expr.function("date_trunc", LocalDateTime.class, Expr.constant("day"), CREATED_AT)`. `Expr.constant` renders SQL text (R-COL-18, D-115). RFC 0002 is still draft, but this path shipped. |
| Row 9, `AT TIME ZONE` with a **fixed** zone | **Supported (PG)** | `Expr.function("timezone", ..., Expr.constant("Asia/Ho_Chi_Minh"), col)`. A per-request bound zone is not (M19). |
| Row 9 / sugg. 8 / F3, runtime-chosen `GROUP BY` | **Supported** | `ModelQuery.Builder.groupBy(SelectSet)` / `groupBy(ScalarField...)` (`ModelQuery.java:875,889`) plus `having`. `@GroupBy` only sets the default `GROUP_KEYS`. One aggregate model + runtime keys can replace most of F3's ~7 models (unselected group fields come back null). |
| Row 11, `jsonb_exists(tags, 'x')` with a **constant** key | **Supported** | `f.eq(Expr.function("jsonb_exists", Boolean.class, TAGS, Expr.constant("x")), true)`. Bound key and `CAST(:x AS jsonb)` are not (M19). |
| Row 9, views | **Works, undocumented** | A Hibernate `@Immutable` + `@Table(name = "view")` or `@Subselect` entity is a normal root. Needs a recipe + TCK case only. |
| F6, bulk writes always clear | **Doc is wrong** | `PersistenceContextMode.KEEP` (`.persistenceContext(...)`, `modelquery.bulk-write.persistence-context=keep`). `CLEAR` is only the default. |
| F7, everything `@Incubating` | **By design until M29** | M29 freeze (D-85) decides frozen vs incubating per type; LitePOS production feedback is the M16.1 input. |
| F2 / sugg. 10, scalar FK (Q-14) | **Open question, recorded** | `92-decisions-questions.md:1788`. Workaround: D-124 double mapping. |
| Row 5, conflict on any unique key | **MySQL/MariaDB only** | `anyUniqueKey()` (`ModelInsert.java:814`). Not an arbiter predicate. |

## 2. Out of scope (no plan)

Rows 10 (plpgsql call), 12 (`SELECT 1`, pg_dump, `CREATE DATABASE`, savepoints), 13 (Flyway Java migrations, test
seeding). Row 7 (advisory locks): out of scope for a query library; LitePOS keeps its `AdvisoryOperationLock`. Revisit
only if M18's vendor SPI makes a 20-line `VendorLocks` trivial.

## 3. Genuine gaps → plan

Ranked by LitePOS unlock ÷ cost. All new API ships `@Incubating`. Each milestone starts with an RFC under `rfc/`
(next free: 0009) and an `architect-review` (public API + `VendorProfile` surface). the freeze (M29) stays last; these
all land together, with M17, in 0.9.0 before it, since LitePOS is the obvious D-111 adopter and its feedback should shape the freeze.

### M18 — Row locks on reads → 0.9.0 (rows 1, 2; 11 LitePOS classes)

Superseded by RFC 0009 (D-127, planned in `mvp-plan.md` §19): two strengths (`exclusive`, `shared`) × wait, no
`NO_KEY_UPDATE`, no joins on a locked query, a `ProviderSupport#locks()` SPI instead of `VendorProfile`, Tier 1 only,
no `TypedQueryCustomizer` hook. The notes below are the original sketch.

- `ModelQuery.Builder.lock(LockMode.UPDATE | NO_KEY_UPDATE | SHARE)` + `.skipLocked()` / `.noWait()`; also on
  `one`/`first`/`findByKey`.
- JPA path: `LockModeType.PESSIMISTIC_WRITE/READ` + `jakarta.persistence.lock.timeout` (`-2` skip locked, `0` nowait).
  `NO_KEY_UPDATE`: Hibernate `LockMode` / PG dialect hook behind a new `VendorProfile.lockClause(...)` default that
  throws a new `MQ` code where unsupported (H2, MySQL for `NO KEY`).
- Diagnostics: refuse with offset/keyset paging, `count`, export, grouped queries, primary-key-first two-phase reads
  (lock must be on the phase that returns rows), and outside a transaction.
- Second hook: `TypedQueryCustomizer` (or a `Phase`-aware overload) receiving the `TypedQuery` for hints. Keeps
  `QueryCustomizer`'s ORDER/GROUP guard (R-QRY-11) intact.
- TCK: two-connection tests on PG/MySQL/MariaDB (blocked, skipped, nowait error); SQL snapshots.
- Risk: Hibernate applies `FOR UPDATE` to joined tables (`OF` clause) — needs a `lock scope` decision.

### M20 — `RETURNING` and conflict extensions → 0.9.0 (rows 3, 4, 5, 8; 13 call sites)

Superseded by RFC 0010 (D-129, planned in `mvp-plan.md` §21): the returning writes take a `ModelQuery`, not a
`SelectSet` (R-WRT-48); no native renderer, the Hibernate module adds `RETURNING` (PostgreSQL, MariaDB delete/insert)
or an H2 delta table to Hibernate's own translation, behind probed per-line adapters; MySQL is refused (`MQ4011`) and
the key-first fallback is a recipe, not emulation; `onConstraint` and the arbiter `where` are PostgreSQL only;
`Expr.nextval` is a profile fact plus a provider renderer, not a `VendorProfile.sequenceNextValue` SQL hook.

### M19 — Literal filters and bound function arguments → 0.9.0 (rows 6, 9 rest, 11 rest)

- Planned in `mvp-plan.md` §20 (RFC 0002 trimmed, D-128). Field-vs-expression comparison already ships as
  `Filters.compare(col, Op, ScalarField)` (R-FLT-18), so `compare(STATUS, NE, Expr.constant("CLOSED"))` is the
  literal-filter idiom; no `eq(ScalarField, ScalarField)` overloads (D-115). New: fixed-arity
  `in`/`notIn(col, a, b[, c])` field forms.
- Enum and converted constants: `Expr.constant(STATUS, Status.PENDING)`, typed by the column and rendered through the
  attribute's JPA mapping; else `MQ1508`.
- Bound argument: `Expr.value(x)` so `jsonb_exists(tags, ?)` and `timezone(?, col)` work. `CAST(? AS jsonb)` is
  deferred; M19.2 spikes function-style casts.
- The plan-cache trade-off of constants is documented (use `Expr.value` for per-request values).

### M17 — Docs and TCK for what already works (now; ships in 0.9.0)

- Gradle tab beside Maven on getting-started pages (F5): `annotationProcessor`, `platform(bom)`, incremental
  processing note (the processor already declares itself `isolating`).
- Recipes: SQL-view root (`@Immutable` + `@Subselect`/`@Table`) with TCK case; runtime `groupBy` replacing
  per-key aggregate models; `date_trunc` / `timezone` via `Expr.function` + `Expr.constant`; counter bulk update via
  `setExpression`. `KEEP` is already documented in `bulk-writes.md`; nothing to add.
- `jsonb_exists` with a constant key is left out of the recipes: the TCK schema has no `jsonb` column, so it has no
  case yet.
- LitePOS has already folded the §1 corrections into its gap doc (its §3).

### M21 — Generated-code nullness → 0.9.0 (F4)

- Processor option `-Amodelquery.jspecify=true`: `@NullMarked` on generated `Q*` classes, `@Nullable` on
  components that can be unselected or are optional. JSpecify as `provided`/optional dependency.
- Document NullAway config. Low risk, compile-testing cases only.

### M22 — Q-14 scalar FK → 0.9.0 (F2)

- Decide Q-14 (render FK column, no join; `NULL` FK TCK on every vendor) as a `D-n`. Architect review needed:
  touches `MQ3016` and the processor's attribute resolution.

### Deferred — revisit after 1.0 or on demand

| Item | Why deferred |
|---|---|
| Sugg. 6, native-SQL bridge mapping into `@QueryModel` records (UNION reports) | New execution path, bypasses `INV-*` guarantees (binds, paging). Views-as-roots (M17) covers most of LitePOS's report need. If done: read-only, `executor.nativeList(sql, params, QModel)`, map by column label, no paging. |
| Sugg. 11, entity-less roots (`@QueryModel(table = ...)`) | Largest structural change: every Criteria path assumes a JPA root. Would need a non-Criteria renderer. Needs its own RFC and architect review; not before 1.0. |
| Sugg. 13, composite value mapping (`Money`) | Multi-column converter touches select, filter, insert, update and grouping. `toDomain()` is an acceptable workaround. (Whether a query model can project an `@Embeddable` path is unverified.) |
| Row 7, advisory locks | Out of scope (§2). |

## 4. Order and gates

The freeze, renumbered M25, then M26 (after RFC 0013), then M29 (D-145), stays last; the new milestones are numbered in build order.

1. M17 docs + TCK (now; also unblocks LitePOS migration of views and grouped reports). Planned in `mvp-plan.md` §18.
2. M18 row locks → 0.9.0 (highest unlock, contained to the read path).
3. M19 literal filters + bound args → 0.9.0 (small; prerequisite for M20's arbiter predicate).
4. M20 `RETURNING` and conflict extensions → 0.9.0 (RFC 0010, D-129).
5. M21 JSpecify, M22 Q-14, M23 target vendors and portability check (RFC 0011, D-133), M24 external model
   bindings (RFC 0012, D-134), all in 0.9.0 (released once M17–M24 are done).
6. M29 freeze → 1.0.0, with LitePOS as the adopter feedback.

Not verified by running code: the §1 "Supported" rows are from reading signatures and specs. M17.1 turns each into a
TCK case before the docs tell LitePOS to rely on it — in particular `Expr.function` with an `Expr.constant` argument
on PG, and runtime `groupBy` on a model whose `@GroupBy` fields are left unselected.
