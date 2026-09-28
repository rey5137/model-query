# Contributing

Thanks for helping. Bug reports, TCK cases and vendor profiles are especially welcome.

Model Query is in **early development**: the spec in `docs/spec/` is complete for 0.1 and code starts at milestone M0
(`docs/plan/mvp-plan.md`). Spec feedback, questions and typo fixes are welcome now.

Questions and API ideas go to GitHub Discussions; bugs and tasks to Issues.

---

## Finding your way around the spec

`docs/spec/` is the source of truth. You don't need to read all of it.

- Start at **[`docs/spec/SPEC.md`](docs/spec/SPEC.md)**: its §1 routing table tells you which child file covers your
  task, and §2 lists the invariants `INV-1..10`. The first five lines of each child file say what it covers.
- Every rule has a stable id: `R-*` rules, `AC-*` acceptance criteria, `INV-*` invariants, `MQnnnn` codes, `D-*`
  decisions, `Q-*` open questions. Search for one with `grep -rn 'R-AGG-08' docs/spec`.
- The *why* behind a rule is in `reference/92` (`D-n`). `reference/91` maps sections of the original single-file plan to
  the current spec ids, and the old `R1`–`R18` numbering to the new rule ids.
- **Cite ids, don't restate the spec** in code comments, commit messages or new docs (`R-PAG-04`, `AC-AGG-01`, `INV-5`,
  `D-3`).
- If code and spec disagree, or the spec is silent, open an issue rather than picking one.
- The spec and the plan hold what to build, never progress: no checkboxes, status columns or work logs. Milestone
  status is read from git (`delivery/61` R-REL-06).

## Non-negotiables

- **Invariants `INV-1..10`** (SPEC.md §2). Breaking one is an architecture decision, never a patch: raise it in an
  issue or RFC first. The ones code most often touches: queries are read-only and never return a managed entity (INV-1); a column's type is
  checked, not trusted (INV-3); an export visits every row exactly once (INV-4); loud over silently wrong (INV-5);
  vendor differences only behind `VendorProfile` (INV-6); module dependencies flow one way (INV-7); definitions are
  immutable (INV-9).
- **Correctness beats convenience.** `R-PAG-*` and `R-AGG-*` exist because an earlier in-house version of this pattern
  lost rows, truncated exports at the first NULL and returned arbitrary values for ungrouped columns. Never make one of
  those paths "work" by relaxing a check.
- **Module boundaries** (`delivery/61` §2, INV-7), enforced by ArchUnit: `core` imports only `jakarta.persistence` and
  the JDK; `jpa` never imports `org.hibernate`; only `spring-*` modules import Spring; no Lombok anywhere in the
  library.
- **Diagnostic codes** (`reference/90`): a code is added there before the code that raises it, and its meaning is never
  reused (INV-10).
- **Status tags**: build `0.1`; for `0.1-reserved` only reserve the name or signature slot; never build `Future`.

## Writing code

- **Match surrounding code**: naming, comment density, idiom. No speculative abstractions, no stubs for later
  milestones.
- **Conventions** are in [`docs/code-conventions.md`](docs/code-conventions.md) (`CC-*`); read it before your first PR.
  In particular: codes, property keys and limits are defined once in their owning module (`CC-CONST-*`); every public
  type reachable from a `static final` field is immutable (`CC-IMM-*`); library code throws only the exception types in
  `reference/90` (`CC-ERR-01`).
- **Comments explain *why*,** at the line they guard, once.
- **Tests** follow `delivery/60`: test names carry the criterion id (`ac_pag_07_...`); every rejecting rule asserts the
  exact `MQnnnn` code; paging and export tests page through the whole fixture rather than asserting one page; no mocks
  of JPA or JDBC internals.
- **SQL-snapshot changes are reviewed, not blessed blindly**: say in the PR which snapshots changed and why.

## Building

Requirements: JDK 17+, Docker (for the TCK).

```bash
./mvnw verify                          # all modules, including the TCK
./mvnw verify -pl model-query-core -am # one module and what it depends on
```

## Pull requests

- Branch from `main` and keep PRs focused. `main` is protected.
- Milestone work happens on an `mN-<name>` branch (`m1-core`). Once the milestone's exit criteria are met it is merged
  into `main` and the merge commit is tagged `mN-verified`, the public record that the milestone is done
  (`delivery/61` R-REL-06).
- PR titles follow [Conventional Commits](https://www.conventionalcommits.org/) with the module as scope and the ids
  covered: `feat(core): aggregate selections (AC-AGG-01..05)`. Spec-only edits use `docs(spec)`. PRs are squash-merged,
  so the title becomes the commit message and the changelog entry.
- Add tests. Anything that changes generated SQL or results needs a TCK case that runs on every Tier-1 vendor.
- New public API that may still change is marked `@Incubating`.

## Adding a VendorProfile

1. Implement `VendorProfile` (`docs/spec/vendor/40-vendor-spi.md` §1) for the database.
2. Register it through `ServiceLoader` (`META-INF/services`).
3. Add a Testcontainers-backed TCK configuration and make the whole TCK pass, including every keyset combination in
   `vendor/41` R-PRF-10.
4. Document the vendor's limits — IN-list size, bind-parameter cap, null ordering, timeout behaviour, streaming mode —
   on the vendor notes page.

Community profiles are welcome and never gate a release (`vendor/41` R-PRF-02).

## Changing the spec or the API

- **Spec edits**: change the owning file only, keep ids stable (never renumber; retire an id by marking it `Retired`),
  update `reference/91`/`92` if affected, and list the ids you touched in the PR.
- **New design choices** are recorded as a `D-n` in `reference/92`, or a `Q-n` while still open.
- **A public signature change, a rule's meaning, or an `INV-*`** needs an accepted RFC first (`rfc/README.md`,
  `delivery/61` R-REL-14): Discussion → RFC PR → accepted → implementation, tests and spec update together.

## Using AI assistants

AI-assisted contributions are welcome. You are the author: you must understand every line you submit, be able to
explain it in review, and have run the build yourself. Say in the PR if a substantial part was generated.

Everything that applies to a contributor is in this file and in `docs/spec/` — there is nothing an assistant needs that
a human does not.

## License

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE)
(`delivery/61` R-REL-12). There is no CLA.
