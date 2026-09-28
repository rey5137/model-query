# Model Query

[![CI](https://github.com/rey5137/model-query/actions/workflows/ci.yml/badge.svg)](https://github.com/rey5137/model-query/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Typed, projection-first queries on top of JPA.

- Typed column and join definitions (`ColumnField`, `TableField`) that map query results straight into plain model
  classes or records.
- An annotation processor that generates those definitions as `QModel` classes.
- A query engine for list, page, count, stream and large exports (offset or keyset paging).
- Bulk update and delete driven by the same filters, with generated change sets that write only the fields that were
  set (planned, M8).
- Vendor-aware behaviour for **H2, PostgreSQL and MySQL**, behind an SPI other databases can implement.

> **Status: early development.** Nothing is published yet and the API below is the target design.
> The full specification is in [`docs/spec/`](docs/spec/SPEC.md) — start at the routing table in `SPEC.md`.
> Milestones are in [`docs/spec/delivery/62-roadmap.md`](docs/spec/delivery/62-roadmap.md); finished ones are tagged
> `mN-verified`.

## Example

```java
@QueryModel(root = OrderEntity.class)
@FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country")
public record OrderView(
        @PrimaryKey Long id,
        OrderStatus status,
        BigDecimal total,
        Instant createdAt,
        @Join(attribute = "customer") Optional<CustomerView> customer) {}
```

```java
List<OrderView> rows = executor.list(
        QOrderView.query()
                .columns(QOrderView.DEFAULT, QOrderView.CUSTOMER)
                .where(f -> f.eq(QOrderView.STATUS, Optional.of("PAID"))
                             .range(QOrderView.CREATED_AT, from, to)
                             .eq(QOrderView.CUSTOMER_COUNTRY, country))
                .orderBy(QOrderView.CUSTOMER_NAME.asc().nullsFirst(), QOrderView.CREATED_AT.desc())
                .build(),
        Limit.of(100));
```

## Modules

| Artifact | Contents |
|---|---|
| `model-query-bom` | Version alignment for all modules |
| `model-query-annotations` | `@QueryModel`, `@PrimaryKey`, `@Column`, `@Join`, `@FilterColumn`, ... |
| `model-query-core` | Columns, tables, column sets, rows, queries, the filters DSL and SPIs |
| `model-query-jpa` | Executor, paging and export engine, built-in vendor profiles |
| `model-query-hibernate` | Hibernate extras: dialect-based vendor detection, grouped count, null precedence |
| `model-query-processor` | Annotation processor that generates `QModel` classes |
| `model-query-spring-data` | `ModelQueryRepository` and Spring Data `Page`/`Pageable`/`Sort` adapters |
| `model-query-spring-boot-starter` | Spring Boot auto-configuration |

## Usage

Once released, import the BOM and add the modules you need:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.rey5137</groupId>
            <artifactId>model-query-bom</artifactId>
            <version>${model-query.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.rey5137</groupId>
        <artifactId>model-query-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.rey5137</groupId>
        <artifactId>model-query-annotations</artifactId>
    </dependency>
</dependencies>
```

Register the processor in `maven-compiler-plugin`:

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.github.rey5137</groupId>
        <artifactId>model-query-processor</artifactId>
        <version>${model-query.version}</version>
    </path>
</annotationProcessorPaths>
```

## Requirements

Java 17+, Jakarta Persistence 3.1+, Hibernate ORM 6.6+ (tested on 6.6 and 7.x), Spring Boot 3.4+ for the starter.

## Building

```bash
./mvnw verify
```

The TCK module needs Docker (Testcontainers starts PostgreSQL and MySQL).

## Documentation

| Audience | Where |
|---|---|
| Using the library | this README, then the user guide (published at 0.1.0) |
| Contributing | [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/code-conventions.md](docs/code-conventions.md) |
| How it is specified | [docs/spec/SPEC.md](docs/spec/SPEC.md) — invariants, rules, acceptance criteria |
| Why a design choice was made | [docs/spec/reference/92-decisions-questions.md](docs/spec/reference/92-decisions-questions.md) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE)
