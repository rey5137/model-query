# 91 — Coverage Map

**Covers:** where each section of the original plan document went, how its `R1`–`R18` numbering maps to the new rule
ids, and where later additions to `docs/plan.md` went.
**Read when:** you have a reference to the old plan and need the current owner.
**Owns:** nothing. This file is a redirect table.

---

## 1. Plan sections → spec files

The original single-document plan (`docs/plan.md`, 1054 lines, kept in the source project's history) was split as
follows. Nothing was dropped.

| Plan § | Content | Now in |
|---|---|---|
| §1 | Goals and non-goals, positioning | SPEC.md §1, §3, §4 |
| §2 | Concepts table, worked example | SPEC.md §2, `api/10`, `processor/31` §1 |
| §3 | Modules, dependency rules, baselines | `delivery/61` §1–§2, SPEC.md §5 |
| §4.1 | Columns, tables, row mapping, null precedence, join sharing | `api/10` |
| §4.2 | Column sets | `api/10` §4 |
| §4.3 | Query definition builder | `api/11` §1–§5 |
| §4.4 | Filters DSL and its semantics | `api/12` |
| §4.5 | Executor signatures | `api/11` §6, `engine/20` |
| §4.6 | Aggregates, `SelectField`, grouping, `having`, `afterMap` | `api/13`, `api/10` §2, `api/11` §3 |
| §5 | Correctness requirements R1–R18 | distributed, see §2 below |
| §6.1 | Annotations | `processor/30` |
| §6.2 | Generated QModel | `processor/31` §1 |
| §6.3 | Processing model, records, classes, nested models | `processor/31` §2–§4 |
| §6.4 | Compile-time diagnostics | `processor/32` |
| §7.1 | Support tiers | `vendor/41` §1 |
| §7.2 | `VendorProfile` SPI | `vendor/40` §1 |
| §7.3 | Tier-1 profile values | `vendor/41` §2 |
| §7.4 | Vendor detection | `vendor/40` §2 |
| §7.5 | Keyset predicate generation | `vendor/41` §5 |
| §7.6 | What isn't abstracted | `vendor/40` §4 |
| §8.1 | Spring Data | `integration/50` §1–§2 |
| §8.2 | Configuration properties | `integration/50` §3 |
| §9.1 | Unit tests | `delivery/60` §1 |
| §9.2 | TCK | `delivery/60` §2 |
| §9.3 | CI | `delivery/60` §3 |
| §10 | Open-source project setup | `delivery/61` §4–§6 |
| §11 | Milestones | `delivery/62` §1, `docs/plan/mvp-plan.md` |
| §12 | Adopting from hand-written columns | — (not carried over) |
| §13 | Risks | `reference/92` §3 |
| §14 | Open questions | `reference/92` §2 |

## 2. Old `Rn` → new rule ids

| Old | New | File |
|---|---|---|
| R1 | R-PAG-01 | `engine/21` |
| R2 | R-PAG-02 | `engine/21` |
| R3 | R-PAG-03 | `engine/21` |
| R4 | R-PAG-04 | `engine/21` |
| R5 | R-PAG-05 | `engine/21` |
| R6 | R-EXE-03 | `engine/20` |
| R7 | R-EXE-04 | `engine/20` |
| R8 | R-EXE-02 | `engine/20` |
| R9 | R-EXE-06 | `engine/20` |
| R10 | R-EXE-07 | `engine/20` |
| R11 | R-COL-08 | `api/10` |
| R12 | R-PAG-07 | `engine/21` |
| R13 | R-COL-05 | `api/10` |
| R14 | R-FLT-10 | `api/12` |
| R15 | R-FLT-13 | `api/12` |
| R16 | R-AGG-03 | `api/13` |
| R17 | R-AGG-08, R-AGG-10 | `api/13` |
| R18 | R-PAG-11 | `engine/21` |

**Note.** Old R17 covered two separate obligations — group-by validity and the keyset refusal — so it became two rules.
Old numbering is never reused.

## 3. Later plan additions

Two PRs changed `docs/plan.md` on `main` after the spec was split from the original plan. They used that file's own
numbering, which collides with the original plan's: their §4.6 and R16–R18 are not the ones in §1 and §2 above.
RFC 0004 (rey5137/model-query#29) is mapped the same way, by its own sections, as D-116 and D-117 amended it.

| PR | Plan § / rule | Now in |
|---|---|---|
| rey5137/model-query#4 | "what to build, never progress" | CONTRIBUTING, `docs/plan/mvp-plan.md` header |
| rey5137/model-query#4 | §11.1 Milestone status | `delivery/61` R-REL-06 |
| rey5137/model-query#5 | §1 goal 7, non-goals | SPEC.md §2 INV-1, §4; D-14 |
| rey5137/model-query#5 | §2 concepts (update model, `Changes`, `ModelUpdate`/`ModelDelete`) | `api/14`, `reference/90` §6 |
| rey5137/model-query#5 | §4.5 `update`/`delete` | `api/14` §8 |
| rey5137/model-query#5 | §4.6 Bulk updates and deletes | `api/14` §1–§6 |
| rey5137/model-query#5 | R16 → R-WRT-10, R17 → R-WRT-11, R18 → R-WRT-12, R19 → R-WRT-13, R20 → R-WRT-14, R21 → R-WRT-15, R22 → R-WRT-16, R23 → R-WRT-17 | `api/14` §5 |
| rey5137/model-query#5 | §6.1 `@UpdateModel`, `generateChanges` | `processor/30` §7 |
| rey5137/model-query#5 | §6.4 update-model diagnostics | `processor/32` `MQ3301`–`MQ3305` |
| rey5137/model-query#5 | §6.5 Generated update models | `processor/31` §6 |
| rey5137/model-query#5 | §7.2–§7.3 `targetTableInSubquery` | `vendor/40` R-VND-11, `vendor/41` §2 |
| rey5137/model-query#5 | §8.1–§8.2 repository methods, properties | `integration/50` R-SPR-10, R-SPR-11, §3 |
| rey5137/model-query#5 | §9.2 Mutations group | `delivery/60` §2 |
| rey5137/model-query#5 | §11 M8 | `delivery/62` §1, R-RDM-01 |
| rey5137/model-query#5 | §13 risks, §14 question 4 | `reference/92` §3, Q-6 (resolved by D-15) |
| rey5137/model-query#29 | RFC 0004 §1 scope, `INV-1`, `INV-9`, `P-5`; R-WRT-24 | SPEC.md §2 INV-1, INV-9, §3 P-5; `api/14` §10.1 |
| rey5137/model-query#29 | RFC 0004 §2 insert models; R-WRT-25, R-WRT-26 | `api/14` §10.2 |
| rey5137/model-query#29 | RFC 0004 §2 `@InsertModel`, one model annotation per type; R-PROC-23, R-PROC-24 | `processor/30` §8, `processor/31` §7 R-GEN-28 |
| rey5137/model-query#29 | RFC 0004 §3 insert-select; R-WRT-27, R-WRT-28 | `api/14` §10.3 |
| rey5137/model-query#29 | RFC 0004 §4 insert-values; R-WRT-29 to R-WRT-33 | `api/14` §10.4 |
| rey5137/model-query#29 | RFC 0004 §5 conflict clauses; R-WRT-34 to R-WRT-38 | `api/14` §10.5 |
| rey5137/model-query#29 | RFC 0004 §6 `persist`; R-WRT-39, R-WRT-40 | `api/14` §10.6 |
| rey5137/model-query#29 | RFC 0004 §7 executor methods | `api/14` §10.1 |
| rey5137/model-query#29 | RFC 0004 §7 `InsertSupport`, `VendorProfile.maxValuesRows()`, `conflictTargetHonoured()`; R-VND-14, `MQ4009` | `vendor/40` R-VND-14, `vendor/41` §2, `reference/90` §2 |
| rey5137/model-query#29 | RFC 0004 §8 codes `MQ1801`–`MQ1808` | `reference/90` §2 |
| rey5137/model-query#29 | RFC 0004 §8 codes `MQ3501`–`MQ3503`; `MQ3504` (D-117) | `processor/32` §1 |
| rey5137/model-query#29 | RFC 0004 §8 acceptance criteria | `api/14` §9 AC-WRT-21 to AC-WRT-33 |
| rey5137/model-query#30 | RFC 0005 §1 scope, `INV-1`, `P-5`, D-118 | SPEC.md §2 INV-1, §3 P-5; `reference/92` D-118 |
| rey5137/model-query#30 | RFC 0005 §2 entity mode; R-WRT-41 to R-WRT-47 | `api/14` §11.1 |
| rey5137/model-query#30 | RFC 0005 §3 `persist` returning a model; R-WRT-48 | `api/14` §11.2, `integration/50` R-SPR-10 |
| rey5137/model-query#30 | RFC 0005 §4 write assignments; R-WRT-49 | `api/14` §11.3, `integration/50` R-SPR-13 |
| rey5137/model-query#30 | RFC 0005 §5 codes `MQ1610`–`MQ1612`, `MQ1809` | `reference/90` §2 |
| rey5137/model-query#30 | RFC 0005 §5 acceptance criteria | `api/14` §9 AC-WRT-34 to AC-WRT-39 |
| RFC 0009 (D-127) | §1 row lock and builder; R-QRY-17, AC-QRY-16 | `api/11` R-QRY-17 |
| RFC 0009 (D-127) | §2 which reads lock; R-EXE-14, AC-EXE-16, AC-EXE-17 | `engine/20` R-EXE-14, `engine/21` |
| RFC 0009 (D-127) | §4 provider surface; R-VND-15, AC-VND-13; rendering table, AC-PRF-10 | `vendor/40` R-VND-15, `vendor/41` §6–§7 |
| RFC 0009 (D-127) | §5 repository reads honour the lock; R-SPR-16, AC-SPR-20 | `integration/50` R-SPR-16 |
| RFC 0009 (D-127) | codes `MQ1209`, `MQ2102`, `MQ2211`, `MQ4010`; `MQ1809` gains a lock; INV-1 | `reference/90` §2, `api/14` R-WRT-48, SPEC.md §2 INV-1 |
| RFC 0018 (D-177) | Part A `CountMode.INLINE_COUNT`, its fallbacks, preflight and logging; R-EXE-15, AC-EXE-18 to AC-EXE-21 | `engine/20` R-EXE-15 |
| RFC 0018 (D-177) | Part A rendering, `ProviderSupport#inlineCounts`; R-VND-27 | `vendor/40` R-VND-27 |
| RFC 0018 (D-177) | Part C.1 `ProviderSupport#inlinesValues`; R-VND-28 | `vendor/40` R-VND-28 |
| RFC 0018 (D-177) | Part C.2 the shared value helper and filter values under inline value handling; R-FLT-06, R-FLT-08, R-FLT-21, AC-FLT-35 (filter paths), AC-FLT-36, code `MQ1502` widened | `api/12` R-FLT-08, `reference/90` §2 |
| RFC 0002 (D-128) | §Outcome 1 literal filters; R-FLT-19, AC-FLT-20 | `api/12` R-FLT-19, `api/16` R-INS-09 |
| RFC 0002 (D-128) | §Outcome 2 constants; R-COL-27, AC-COL-32 | `api/10` R-COL-27, `vendor/40` R-VND-16 |
| RFC 0002 (D-128) | §Outcome 3 bound arguments; R-COL-26, AC-COL-31 | `api/10` R-COL-26 |
| RFC 0002 (D-128) | §Outcome 3 plan cache, volatile function; R-FLT-09 binds | `api/12` R-FLT-09 |
| RFC 0002 (D-128) | §Outcome 4 deferred cast; codes `MQ1502`, `MQ1508` | `reference/90` §2 |
| RFC 0010 (D-131) | §1 the claim pattern, a locked key read then a `whereKeys` write; AC-WRT-47 | `api/14` R-WRT-50 |
| RFC 0010 (D-129) | §4 `Expr.nextval`; R-COL-28, R-VND-18, AC-COL-33, code `MQ1509` | `api/10` R-COL-28, `vendor/40` R-VND-18, `reference/90` §2 |
| RFC 0010 (D-130) | §4 amended, `nextval` in writes through `setExpression(column, ExpressionField)`; R-WRT-53, AC-WRT-46, code `MQ1613`; `Assignment.Computed` | `api/10` R-COL-28, `api/14` R-WRT-53, `reference/90` §2, `delivery/61` |
| RFC 0010 (D-130) | §4 amended, computed insert values through `InsertSupport`'s six-argument `insertValues` | `vendor/40` R-VND-14 |
| LitePOS gaps §3 M17 | docs and TCK for what already works (Gradle tab, view root, runtime `groupBy`, `date_trunc`, counter update) | `docs/site`, `delivery/60` |
| LitePOS gaps §3 M18 | row locks on reads (rows 1, 2); D-127 | `api/11` R-QRY-17, `engine/20` R-EXE-14 |
| LitePOS gaps §3 M19 | literal filters and bound arguments (rows 6, 9, 11); D-128 | `api/12` R-FLT-19, `api/10` R-COL-26, R-COL-27 |
| LitePOS gaps §3 M20 | `RETURNING` and conflict extensions (rows 3, 4, 5, 8); D-129, D-130, D-131 | `api/10` R-COL-28, `api/14` R-WRT-50, R-WRT-53 |
| LitePOS gaps §3 M21 | F4 generated-code nullness; D-135 | `processor/30` R-PROC-32, `processor/31` R-GEN-36 |
| LitePOS gaps §1, §3 M22 | F2 / sugg. 10, scalar FK (Q-14); D-136, D-137 | `api/10` R-COL-29, `processor/30` R-PROC-33, `api/13` R-AGG-15, `reference/90` §2 |
| RFC 0011 (D-133) | §1-§2 declared target vendors, checked per factory and at Spring startup; R-VND-19, R-SPR-17, AC-VND-15, AC-SPR-21, code `MQ4012` | `vendor/40` R-VND-19, `integration/50` R-SPR-17, `reference/90` §5 |
| RFC 0011 (D-133, D-138, D-139) | §3 the offline boot and the dry run, `PortabilityCheck`, the sweep; R-VND-20, R-INS-10..12, AC-INS-10, AC-INS-12, code `MQ4013` | `api/16` R-INS-10..12, `vendor/40` R-VND-20, `delivery/61` |
| RFC 0011 (D-133) | §3-§4 the TCK conformance run on both lines, what the dry run cannot decide; AC-INS-11 | `api/16` AC-INS-11, `delivery/60` §2, `docs/site` portability page |
