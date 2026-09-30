# 61 — Repository, Release and Governance

**Covers:** module layout, dependency rules, branches and reviews, versions, publishing, licensing, and how the API
evolves.
**Read when:** adding a module or a dependency, cutting a release, or proposing an API change.
**Owns:** `R-REL-*`, `AC-REL-*`.

---

## 1. Repository layout

```
model-query/
├── pom.xml  mvnw  mvnw.cmd  .mvn/  .editorconfig  .gitattributes
├── README.md  LICENSE  CONTRIBUTING.md  CODE_OF_CONDUCT.md  SECURITY.md  CHANGELOG.md
├── model-query-bom/                 version alignment
├── model-query-annotations/         @QueryModel and friends                      (no deps)
├── model-query-core/                TableField, SelectField, ColumnField, AggregateField, ColumnSet,
│                                    Row, RowMapper, ModelQuery, Filters, JoinContext, RenderOptions,
│                                    SPI interfaces                               (jakarta.persistence-api)
├── model-query-jpa/                 executor, ModelQueryConfig, paging and export engine;          (core)
│                                    jpa.spi: VendorProfile, ProviderSupport; jpa.vendor: built-in profiles (not API)
├── model-query-hibernate/           Hibernate 6.x extras: dialect detection, grouped count,
│                                    null precedence                              (jpa + hibernate-core, optional)
├── model-query-processor/           annotation processor                          (annotations, JavaPoet shaded)
├── model-query-spring-data/         ModelQueryRepository + factory, Page/Pageable/Sort adapters
├── model-query-spring-boot-starter/ auto-configuration, properties, SPI beans
├── model-query-tck/                 Testcontainers suite per vendor
├── samples/{plain-jpa,spring-boot-multi-datasource}/
├── rfc/                             README.md, 0000-template.md, accepted RFCs
├── docs/{spec,plan}/  docs/code-conventions.md
└── .github/{workflows,ISSUE_TEMPLATE,pull_request_template.md,dependabot.yml}
```

**R-REL-01** A new module needs a real API or dependency boundary and an update to SPEC.md §5 and INV-7's order. A
directory alone is never a reason.

**R-REL-02** Grows later, not in 0.1: an `editors/` module, a `model-query-quarkus` extension, and a published docs site
source tree.

## 2. Dependency rules (INV-7)

**R-REL-03** Enforced with ArchUnit in the build:

- `core` imports only `jakarta.persistence.*` and the JDK.
- `jpa` does not import `org.hibernate.*`. Hibernate features are found with `ServiceLoader` and have a portable
  fallback.
- Only `spring-*` modules import `org.springframework.*`.
- `jakarta.validation` is an optional dependency of `jpa` only, used by `@ValidChanges` (`Future`, M8; `api/14`
  R-WRT-22). Generated change sets reference it only when it is on the model module's classpath.
- `processor` depends only on `annotations` and shaded JavaPoet.
- No Lombok anywhere in the library. Consumers may use Lombok on their models (`processor/31` R-GEN-10).

## 3. Branches, reviews, commits

**R-REL-04** Never commit to `main`, which is protected. Work on a branch (`m2-engine`, `spec/<topic>`, `fix/<topic>`).
Each commit is green on its own. Messages are Conventional Commits with the module as scope and the ids covered:
`feat(core): aggregate selections (AC-AGG-01..05)`; spec edits use `docs(spec)`.

**R-REL-05** PRs are rebase-merged after one approving review with CI green (`delivery/60` R-QA-08). SQL snapshots are
committed with the code that changed them.

**R-REL-06** Milestone status is read from git, never from a tracker file. A milestone is **in progress** while an
`mN-<name>` branch exists (`m1-core`) and all its work happens there. It is **done** once that branch is merged into
`main` after its exit criteria are met (`delivery/62` R-RDM-02); the last commit it brings onto `main` is tagged
`mN-verified` and the branch is deleted. With neither branch nor tag it is **not started**.
`git tag -l 'm*-verified'` lists finished milestones.

## 4. Versions and releases

**R-REL-07** SemVer. `0.x` while the API can still change; `1.0.0` freezes it. Anything that may change before 1.0 is
annotated `@Incubating`.

**R-REL-08** Tag → GitHub Actions → Maven Central through the Central Portal
(`central-publishing-maven-plugin`), GPG-signed, with `-sources` and `-javadoc` jars. The changelog is generated from
Conventional Commits.

**R-REL-09** Coordinates: `groupId` `io.github.rey5137`, artifacts `model-query-*`. The namespace is verified on Central
through the `rey5137` GitHub account. The Java package `com.rey.modelquery` intentionally differs from the `groupId`.

**R-REL-10** Sub-packages follow the modules: `.core`, `.jpa`, `.hibernate`, `.processor`, `.spring.data`,
`.spring.boot`. `com.rey.modelquery.jpa.vendor` (the built-in profiles, `VendorResolver`, `ResolvedVendor`) is not
API, whatever the visibility of its types: it may change in any release, and extensions go through `jpa.spi`.

**R-REL-11** From 1.0, `japicmp` fails the build on a binary-incompatible change to a non-`@Incubating` type outside the
non-API packages (R-REL-10).

## 5. Licensing

**R-REL-12** Apache-2.0 for code and documentation, one `LICENSE` file at the root. Contributions are accepted under the
same license; there is no CLA.

## 6. Documentation

**R-REL-13** `README` carries the quick start only. The docs site (MkDocs or Antora) carries the user guide and the
vendor notes page (`vendor/40` R-VND-09). `docs/spec` stays the source of truth and is not published as user
documentation.

## 7. API evolution: RFCs

**R-REL-14** A change to a public signature, to a rule's meaning, or to an `INV-*` needs an accepted RFC first:
Discussion → RFC PR (`rfc/NNNN-title.md`) → accepted → implementation, tests and spec update in one PR.

**R-REL-15** A new `MQnnnn` code is added to `reference/90` before the code that raises it. A code's meaning never
changes (INV-10).

**R-REL-16** A design choice made while implementing is recorded as a `D-n` in `reference/92`, or a `Q-n` while still
open. Rules are never renumbered; a retired rule is marked `Retired` and keeps its id.

## 8. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-REL-01 | The build produces exactly the modules in §1, and `./mvnw verify` is green from a clean checkout. |
| AC-REL-02 | ArchUnit fails on each forbidden edge in R-REL-03, verified by a deliberately bad test fixture. |
| AC-REL-03 | `core` and `jpa` resolve with no `org.hibernate` and no `org.springframework` artifact in their dependency tree. |
| AC-REL-04 | A release dry run publishes signed `-sources` and `-javadoc` jars for every module and a BOM that lists them all. |
| AC-REL-05 | Two builds of the same tag produce identical artifact checksums. |
| AC-REL-06 | `japicmp` fails on a removed public method of a non-`@Incubating` type (R-REL-11). |
| AC-REL-07 | Every `MQnnnn` raised in code appears in `reference/90` (R-REL-15), checked by the AC audit. |
