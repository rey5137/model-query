# Model Query

[![CI](https://github.com/rey5137/model-query/actions/workflows/ci.yml/badge.svg)](https://github.com/rey5137/model-query/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Typed, projection-first queries on top of JPA.

- Typed column and join definitions (`ColumnField`, `TableField`) that map query results straight into plain model
  classes or records.
- An annotation processor that generates those definitions as `QModel` classes.
- A query engine for list, page, count, stream and large exports (offset or keyset paging).
- Bulk update and delete driven by the same filters, with generated change sets that write only the fields that were
  set (`@Incubating`).
- Vendor-aware behaviour for **H2, PostgreSQL and MySQL**, behind an SPI other databases can implement.

> **Status: 0.2.0.** Every public type is `@Incubating` and may change in a minor release until the 1.0 freeze
> ([API stability](docs/site/docs/stability.md)). The user guide is at
> <https://rey5137.github.io/model-query/> (source under [`docs/site/docs/`](docs/site/docs/index.md)); the Javadoc is on
> [javadoc.io](https://javadoc.io/doc/io.github.rey5137/model-query-core).

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
                .select(QOrderView.DEFAULT, QOrderView.CUSTOMER)
                .where(f -> f.eq(QOrderView.STATUS, Optional.of("PAID"))
                             .range(QOrderView.CREATED_AT, from, to)
                             .eq(QOrderView.CUSTOMER_COUNTRY, country))
                .orderBy(QOrderView.CUSTOMER_NAME.asc().nullsFirst(), QOrderView.CREATED_AT.desc())
                .build(),
        Limit.of(100));
```

## Usage

Import the BOM and add the modules you need:

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

On Spring Boot, declare `model-query-spring-boot-starter` instead of the two above: it brings Spring Data JPA,
Hibernate and `model-query-hibernate`, so `model-query-annotations` is needed only in a module without the starter.
Keep the processor in `annotationProcessorPaths` either way.

## Requirements

Java 17+, Jakarta Persistence 3.1+, Hibernate ORM 6.6+ (tested on 6.6 and 7.x), Spring Boot 3.4+ for the starter.

## Documentation

| Audience | Where |
|---|---|
| Getting started | [Without Spring](docs/site/docs/getting-started/plain-jpa.md), [with Spring Boot](docs/site/docs/getting-started/spring-boot.md) |
| Using the library | [Models](docs/site/docs/models.md), [queries](docs/site/docs/queries.md), [paging and export](docs/site/docs/paging-export.md), [grouped queries](docs/site/docs/grouped-queries.md), [bulk writes](docs/site/docs/bulk-writes.md), [vendors](docs/site/docs/vendors.md), [Spring](docs/site/docs/spring.md), [diagnostics](docs/site/docs/diagnostics.md) |
| Samples | [plain JPA](samples/plain-jpa) (the quick start in [19 lines](samples/plain-jpa/src/main/java/com/rey/modelquery/sample/plainjpa/QuickStart.java)), [Spring Boot](samples/spring-boot) |
| Contributing | [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/code-conventions.md](docs/code-conventions.md) |
| How it is specified | [docs/spec/SPEC.md](docs/spec/SPEC.md) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE)
