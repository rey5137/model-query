# RFC 0027 spike data

Raw data of the RFC 0027 acceptance spike, read by [`../0027-spike-results.md`](../0027-spike-results.md). Research on
`spike/rfc-0027`: one test-source module `spike-rfc0027/` behind the root `pom.xml` profile `spike-rfc0027`.
Production sources and `docs/spec/` are unchanged.

## Files

`rfc0027-<lane>.tsv`: one per lane, columns `vendor`, `probe`, `case`, `status`, `value`, `sql` (SQL `||` JDBC binds,
`;;` between statements; EclipseLink inlines its binds, so none follow). `PASS` means the rows equal the expectation
(a multiset unless the value says `ordered`); a failed expectation or a provider error is `FAIL` with the reason.

Lanes: `hibernate-6.6.58.Final` (API 3.1.0), `hibernate-7.4.11.Final` (API 3.2.0), `eclipselink-4.0.8` (API 3.1.0),
`eclipselink-5.0.2` (API 3.2.0). Vendors: H2 2.5.252, PostgreSQL 16 (`postgres:16-alpine`), MySQL 8.0.31.
`matrix.tsv` is the status grid (P/F) per cell, lane and vendor. `lanes/` holds each lane's stdout/stderr.

Probes: `Q1` (select, order, map key/value/entry), `Q1b` (default order without `@OrderColumn`, three runs each),
`Q1c` (parent filters through a collection). The fixture is `Fixture.java`: data is seeded over JDBC so that the NULL
element and the out-of-order `pos` values are exact.

## Reproduce

Docker running; one lane per command, all three vendors in each (a run replaces that lane's TSV):

```sh
./mvnw -q test -Pspike-rfc0027 -pl spike-rfc0027 '-Dtest=Rfc0027Hibernate*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Drfc0027.lane=hibernate-6.6.58.Final
./mvnw -q test -Pspike-rfc0027 -pl spike-rfc0027 '-Dtest=Rfc0027Hibernate*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Drfc0027.lane=hibernate-7.4.11.Final \
  -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
./mvnw -q test -Pspike-rfc0027 -pl spike-rfc0027 '-Dtest=Rfc0027EclipseLink*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Drfc0027.lane=eclipselink-4.0.8
./mvnw -q test -Pspike-rfc0027 -pl spike-rfc0027 '-Dtest=Rfc0027EclipseLink*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Drfc0027.lane=eclipselink-5.0.2 \
  -Declipselink.version=5.0.2 -Djakarta-persistence.version=3.2.0
```
