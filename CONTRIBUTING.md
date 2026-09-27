# Contributing

Thanks for helping. Bug reports, TCK cases and vendor profiles are especially welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the API.
- The design lives in [docs/plan.md](docs/plan.md). Changes that contradict it should update it in the same PR.

## Building

Requirements: JDK 17+, Docker (for the TCK).

```bash
./mvnw verify                          # all modules, including the TCK
./mvnw verify -pl model-query-core -am # one module and what it depends on
```

## Pull requests

- Branch from `main` and keep PRs focused.
- PR titles follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, ...).
  PRs are squash-merged, so the title becomes the commit message and the changelog entry.
- Add tests. Anything that changes generated SQL or results needs a TCK case that runs on every Tier-1 vendor.
- New public API that may still change is marked `@Incubating`.

## Adding a VendorProfile

1. Implement `VendorProfile` (see the plan, section 7.2) for the database.
2. Register it through `ServiceLoader` (`META-INF/services`).
3. Add a Testcontainers-backed TCK configuration for the database and make the whole TCK pass.
4. Document vendor-specific limits (IN-list size, null ordering, timeouts) on the vendor notes page.

## Code style

- 4-space indentation, no wildcard imports.
- Public types and methods have Javadoc.
- `model-query-core` depends only on `jakarta.persistence` and the JDK.
