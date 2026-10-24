# RFC 0022 sub-step 2 checks

The sub-step 2 runs (A.3, A.4, B.2, B.3), from the repository root, one Hibernate line at a time. No full suite or
verify command ran. The logs themselves were scratch output and are not kept; each entry records the command, its
outcome and its last line. Expected provider FAIL cells are data: a green JUnit run doesn't mean every cell passed.

Every command is `./mvnw -q test -Pspike-rfc0022 -pl spike-rfc0022 -am -Dsurefire.failIfNoSpecifiedTests=false`
followed by the arguments below. `L66` is `-Dhibernate.version=6.6.58.Final`; `L74` is
`-Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0`; `NP` is `-Drfc0022.plans=false`;
`EL` is `-Drfc0022.eclipselink=true`.

| Run | Arguments | Outcome |
|---|---|---|
| iterate | `-Dtest=Rfc0022H2FunctionalTest` L66 NP | green |
| EclipseLink probe | `-Dtest=Rfc0022EclipseLinkTest` L74 NP EL | green |
| adapter | `-Dtest=Rfc0022H2FunctionalTest#a4_columnCreationAdapterPreservesEngineMapping` L74 | red, then fixed |
| functional 6.6 | `-Dtest='Rfc0022*Test'` L66 NP | red, then fixed |
| functional 7.4 | `-Dtest='Rfc0022*Test'` L74 NP EL | green |
| timed 6.6 | `-Dtest='Rfc0022*Test'` L66 | green |
| timed 7.4 | `-Dtest='Rfc0022*Test'` L74 | green |

- **adapter** failed on a harness bug: the association `ON` replaced the explicit entity key. The adapter now merges
  both predicates (`PortableJoinAdapter`). The assertion test emits no acceptance cells.
- **functional 6.6** failed because the API 3.2 adapter sanity test ran on Hibernate 6 entity joins. It is now
  scoped to the 7.4/API 3.2 lane, and 6.6 reports it as one JUnit skip.
- An intermediate EclipseLink probe was refined to gate the dependent engine cells at the direct join failure
  (`NOT_RUN` with the reason) instead of failing each one.

Last lines of the final runs, verbatim:

```text
timed 6.6:      RFC0022 POSTGRESQL 6.6.58.Final: PASS 89, FAIL 1, NOT_RUN 2
timed 7.4:      RFC0022 POSTGRESQL 7.4.11.Final: PASS 281, FAIL 1, NOT_RUN 1
EclipseLink:    RFC0022 POSTGRESQL EclipseLink 5.0.2: PASS 2, FAIL 2, NOT_RUN 7
```

The same counts held for H2 and MySQL on each lane. Final 7.4 Surefire reports: H2, MySQL and PostgreSQL each ran six
tests, with zero failures, errors or skips. The final 6.6 reports showed six tests per vendor, zero failures or errors,
and the one adapter skip. The opt-in EclipseLink wrapper was skipped in both timed runs; its explicit run is above.
Both timing TSVs have 18 rows (two variants of three queries on three vendors). Each of the six vendor plan files holds
the renderer and native plans of all three queries.

The sub-step 3 reproduction of the EclipseLink failure (`A.4-repro`) and the C/D cells superseded these counts. See
`eclipselink-reproduction.log`, `substep3-*.log` and `validate.log` beside this file.

Static checks at the end of sub-step 2: `awk 'length > 120'` over the spike sources printed nothing.
`git diff --check` exited 0 with no output.
