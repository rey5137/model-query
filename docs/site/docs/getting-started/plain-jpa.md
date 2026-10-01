# Getting started without Spring

Everything in the library works with a plain `EntityManager`. Spring only adds wiring.

## 1. Add the dependencies

Import the BOM, then add the modules you need. Replace `0.1.0` with the release you use.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.rey5137</groupId>
            <artifactId>model-query-bom</artifactId>
            <version>0.1.0</version>
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

Register the processor so the `Q` classes are generated at compile time:

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.github.rey5137</groupId>
        <artifactId>model-query-processor</artifactId>
        <version>0.1.0</version>
    </path>
</annotationProcessorPaths>
```

Add `model-query-hibernate` as well when you run on Hibernate: it detects the database from the dialect without a
connection, and lets the library render `NULLS FIRST/LAST` and count grouped queries natively.

## 2. Describe a result as a model

A model is a class or a record annotated with `@QueryModel`, naming the JPA entity it reads from.

```java
@QueryModel(root = OrderEntity.class)
@FilterColumn(name = "ITEM_SKU", path = "items.sku", joinType = JoinKind.LEFT)
public class OrderView {

    @PrimaryKey
    private Long id;
    private String status;
    private BigDecimal total;
    // Empty for a walk-in sale, which has no customer.
    @Join
    private Optional<CustomerView> customer = Optional.empty();

    // getters and setters
}
```

The processor generates `QOrderView` next to it. See [Models and QModels](../models.md) for what it generates.

## 3. Create an executor and query

```java
var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());

// A filtered page of order views, each with its customer, if any.
var paid = QOrderView.query()
        .columns(QOrderView.ALL.with(QOrderView.CUSTOMER))
        .where(f -> f.eq(QOrderView.STATUS, "PAID"))
        .orderBy(QOrderView.ID.asc())
        .build();

Slice<OrderView> page = executor.page(paid, PageSpec.of(0, 20), CountMode.COUNT);
```

A `ModelQuery` is immutable, so you can keep it in a `static final` field and share it. The executor is created once
per `EntityManager` and entity type.

`ModelQueryConfig.defaults()` is a good start. Each tuning knob is a method on the config, for example
`exportPageSize(int)`, `streamFetchSize(int)` or `queryTimeout(Duration)`; the
[Spring Data and the starter](../spring.md) page lists every setting next to its property name.

## 4. A full example

The repository ships a runnable sample, `samples/plain-jpa`, with a small shop on in-memory H2: a filtered page, an
`exists` filter on a collection, and a grouped summary, all read through generated `Q` classes.

Next: [Models and QModels](../models.md).
