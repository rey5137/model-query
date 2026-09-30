# 40 — VendorProfile SPI

**Covers:** the `VendorProfile` interface, how a profile is discovered and selected, and what the library deliberately
does not abstract.
**Read when:** adding a database, changing detection, or deciding whether a difference belongs in a profile.
**Owns:** `R-VND-*`, `AC-VND-*`. Tier-1 values are `vendor/41`.

---

## 1. The interface

```java
public interface VendorProfile {

    DatabaseVendor vendor();                                   // H2, POSTGRESQL, MYSQL, MARIADB, ORACLE, SQLSERVER, OTHER

    int maxInListSize();                                       // soft limit for IN (...)

    int maxBindParameters();                                   // hard limit per statement

    void applyStreaming(Query query, int fetchSize);            // forward-only streaming of large results

    default void checkStreamingPreconditions(EntityManager em) {}

    void applyTimeout(Query query, Duration timeout);

    NullOrdering defaultAscendingNullOrdering();               // NULLS_FIRST, NULLS_LAST, UNKNOWN

    default boolean targetTableInSubquery() { return false; }  // Future (M8): may UPDATE/DELETE read their own table
}
```

`VendorProfile` and `DatabaseVendor` are in `com.rey.modelquery.jpa.spi`; `NullOrdering` is in `core`, because it names
no vendor. The built-in profiles and the resolver are in `com.rey.modelquery.jpa.vendor`. `core` sees a profile only as
the vendor-neutral `RenderOptions` the executor passes to each query build. What varies by persistence provider rather
than by database (dialect detection, the grouped count, native null precedence, a configured default null ordering)
is the separate `ProviderSupport` SPI in `jpa.spi`, which `model-query-hibernate` implements (D-34, D-36).

**R-VND-01** Every vendor-specific behaviour the engine needs is a method here. No vendor name and no
`if (vendor == …)` exists anywhere else (INV-6). A new behaviour is a new method with a default, never a cast to a
concrete profile.

**R-VND-02** A profile is stateless and thread-safe, and is resolved once per `EntityManagerFactory`.

**R-VND-03** Profiles are discovered with `ServiceLoader`. The built-in H2, PostgreSQL, MySQL and `OTHER` profiles are
a fixed table in `jpa`, not service registrations, and a discovered profile takes precedence over the built-in one for
its vendor. Two discovered profiles for one vendor throw `MQ4002`. A caller may also supply profiles on
`ModelQueryConfig.vendorProfiles(...)`, which take precedence over a `ServiceLoader`-provided profile for the same
vendor; two supplied for one vendor throw `MQ4002`. The Spring starter passes every `VendorProfile` bean there
(`integration/50`, D-53).

**R-VND-11** `targetTableInSubquery()` (`Future`, M8) says whether an `UPDATE` or `DELETE` may read its own table in
a sub-query. When it is false, a bulk write whose rendering needs such a sub-query runs key-first (`api/14` R-WRT-11).
It defaults to `false`, which is always correct and only slower, so a profile written before M8 keeps compiling and
stays safe (R-VND-01). It is a capability, not a rendering hook, because the engine renders every predicate itself
(R-VND-08, P-5). Tier-1 values are in `vendor/41` §2; otherwise `true` for Oracle, SQL Server and MariaDB 10.3.1+, and
`false` for `OTHER`.

## 2. Detection

**R-VND-04** Resolution order:

1. An explicit `ModelQueryConfig.vendor(DatabaseVendor)` or the `modelquery.vendor` property. Nothing is detected.
2. With a `ProviderSupport` serving the factory, as `model-query-hibernate` does:
   `SessionFactoryImplementor#getJdbcServices().getDialect()`, mapped by dialect class, most specific first (MariaDB's
   dialect extends MySQL's). This needs no connection and is per `EntityManagerFactory`, so an application with
   several datasources on different databases resolves each correctly.
3. Otherwise `DatabaseMetaData#getDatabaseProductName()`, read once per `EntityManagerFactory` on a connection from
   the factory's `jakarta.persistence.nonJtaDataSource` property: `H2`, `PostgreSQL`, `MySQL` and `MariaDB` name their
   vendor, any other name is `OTHER`. A factory without that property, or whose `DataSource` fails, resolves to
   `OTHER` and logs a `WARN`. A factory whose metadata read fails stays `OTHER` for the factory's lifetime: the
   result is cached, not retried.

The result is cached weakly per factory and configured vendor, so detection runs once per factory and an explicit
vendor always wins over an earlier detection.

**R-VND-05** Detection never runs per query, and never opens a connection when step 1 or 2 answered.

**R-VND-06** An unrecognised database resolves to the `OTHER` profile, which is deliberately conservative: fetch size
500, JPA timeout, IN list 1 000, bind parameters 2 000, null ordering `UNKNOWN`, no target table in sub-queries
(R-VND-11). A recognised vendor with no profile (MariaDB, Oracle, SQL Server until theirs exist) uses the `OTHER`
profile too, and the resolution log names the vendor detected. Keyset paging on a nullable column without explicit
null precedence is refused under `OTHER` (`engine/21` R-PAG-05, `api/10` R-COL-13).

**R-VND-07** The resolved profile is logged once at `INFO` with how it was resolved, because a wrong profile produces
correct-looking results with the wrong limits. When no `ProviderSupport` serves the factory and its properties carry
`hibernate.order_by.default_null_ordering` set to anything but `none`, resolution also logs a `WARN` once: the setting
is not honoured without `model-query-hibernate` (`engine/21` R-PAG-05, D-36, Q-9).

## 3. Keyset predicate contract

**R-VND-08** The engine renders keyset predicates itself, portably; a profile only supplies
`defaultAscendingNullOrdering()`. The rendered shape and NULL branches are specified in `vendor/41` §5, so a new profile
inherits correct keyset behaviour without writing SQL.

## 4. What the library does not abstract

**R-VND-09** Documented in the user guide and left to the database and the entity mapping:

- String comparison and case sensitivity follow column collation — MySQL `*_ci` is case-insensitive, PostgreSQL is
  case-sensitive.
- JSON, array and enum column types follow the entity mapping.
- Boolean storage and date/time precision follow Hibernate and the JDBC driver; time zones follow
  `hibernate.jdbc.time_zone`.
- H2's default null ordering must be kept. With `DEFAULT_NULL_ORDERING` set to anything else, Hibernate's H2 dialect,
  which assumes NULLs sort smallest, leaves an explicit `nullsFirst()`/`nullsLast()` bare where it is needed, so NULLs
  are misplaced and keyset paging may skip them even with explicit precedence. It is not detectable without a
  connection (R-VND-05).

**R-VND-10** A behaviour in this list is never silently emulated. If a use case needs uniformity, it asks for it
explicitly (`likeIgnoreCase`, `nullsFirst`), and the library renders it the same way everywhere (`api/12` R-FLT-07,
`api/10` R-COL-12).

## 5. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-VND-01 | ArchUnit fails when a `DatabaseVendor` reference appears outside a profile or the detection code (R-VND-01). |
| AC-VND-02 | Each Testcontainers database resolves to the expected profile, with and without `model-query-hibernate` (R-VND-04). |
| AC-VND-03 | Two `EntityManagerFactory` beans on different databases resolve to different profiles in one application (R-VND-04). |
| AC-VND-04 | Detection opens no connection when the vendor is configured explicitly or a dialect is available (R-VND-05). |
| AC-VND-05 | An unknown `DatabaseMetaData` product name yields `OTHER`, and a nullable-column keyset under `OTHER` is refused (R-VND-06). |
| AC-VND-06 | A Spring-registered profile bean overrides the `ServiceLoader` one (R-VND-03). |
| AC-VND-07 | (`Future`, M8) `targetTableInSubquery()` is true for H2 and PostgreSQL and false for MySQL and `OTHER`, verified against each container (R-VND-11). |
