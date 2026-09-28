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

    boolean targetTableInSubquery();                           // Future (M8): may UPDATE/DELETE read their own table
}
```

**R-VND-01** Every vendor-specific behaviour the engine needs is a method here. No vendor name and no
`if (vendor == …)` exists anywhere else (INV-6). A new behaviour is a new method with a default, never a cast to a
concrete profile.

**R-VND-02** A profile is stateless and thread-safe, and is resolved once per `EntityManagerFactory`.

**R-VND-03** Profiles are discovered with `ServiceLoader`. Spring users may also register one as a bean, which takes
precedence over a `ServiceLoader`-provided profile for the same vendor (`integration/50`).

**R-VND-11** `targetTableInSubquery()` (`Future`, M8) says whether an `UPDATE` or `DELETE` may read its own table in
a sub-query. When it is false, a bulk write whose filter needs a join runs key-first (`api/14` R-WRT-11).

## 2. Detection

**R-VND-04** Resolution order:

1. An explicit `ModelQueryConfig.vendor(...)` or the `modelquery.vendor` property.
2. With `model-query-hibernate` present: `SessionFactoryImplementor#getJdbcServices().getDialect()`, mapped by dialect
   class. This needs no connection and is per `EntityManagerFactory`, so an application with several datasources on
   different databases resolves each correctly.
3. Otherwise `DatabaseMetaData#getDatabaseProductName()`, read once per `EntityManagerFactory` and cached.

**R-VND-05** Detection never runs per query, and never opens a connection when step 1 or 2 answered.

**R-VND-06** An unrecognised database resolves to the `OTHER` profile, which is deliberately conservative: fetch size
500, JPA timeout, IN list 1 000, bind parameters 2 000, null ordering `UNKNOWN`, no target table in sub-queries (R-VND-11). Keyset paging on a nullable column
without explicit null precedence is refused under `OTHER` (`engine/21` R-PAG-05, `api/10` R-COL-13).

**R-VND-07** The resolved profile is logged once at `INFO` with how it was resolved, because a wrong profile produces
correct-looking results with the wrong limits.

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
| AC-VND-07 | `targetTableInSubquery()` is true for H2 and PostgreSQL and false for MySQL and `OTHER`, verified against each container (R-VND-11). |
