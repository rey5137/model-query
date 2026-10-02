# Getting started with Spring Boot

The starter wires the library into Spring Data repositories. It reads `modelquery.*` properties into the same
`ModelQueryConfig` you would build by hand, so nothing works only under Spring.

## 1. Add the dependencies

Import the BOM as shown in [Getting started without Spring](plain-jpa.md), then add:

```xml
<dependency>
    <groupId>io.github.rey5137</groupId>
    <artifactId>model-query-spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>io.github.rey5137</groupId>
    <artifactId>model-query-annotations</artifactId>
</dependency>
```

and register `model-query-processor` in `annotationProcessorPaths`, as in the plain-JPA guide.

## 2. Extend `ModelQueryRepository`

`ModelQueryRepository` is a repository fragment: extend it next to `JpaRepository` or any other Spring Data
interface. Its type parameter is the repository's own entity.

```java
public interface BookRepository extends JpaRepository<BookEntity, Long>, ModelQueryRepository<BookEntity> {}
```

```java
@QueryModel(root = BookEntity.class)
public record BookView(@PrimaryKey Long id, String title, Integer released) {}
```

## 3. Query

```java
private static final ModelQuery<BookEntity, Long, BookView> BOOKS = QBookView.query()
        .select(QBookView.ALL)
        .orderBy(QBookView.TITLE.asc())
        .build();

ModelPage<BookView> page = books.findPage(BOOKS, PageRequest.of(0, 20), CountMode.COUNT);
```

A `Pageable` converts to the library's page spec, and its `Sort` is applied to the selected columns by property name.
See [Spring Data and the starter](../spring.md).

## 4. Configure

Set `modelquery.*` properties in `application.properties` when you need to override a default. For example:

```properties
modelquery.export.page-size=2000
modelquery.query-timeout=30s
```

## The sample application

`samples/spring-boot` is one application over three databases (H2, PostgreSQL, MySQL). Each package holds a
datasource, an `EntityManagerFactory`, a transaction manager and its repositories, and the starter wires every
repository; each factory resolves the vendor profile of its own database. It also has a validated `PATCH` endpoint
built on a generated change set; see [Bulk writes](../bulk-writes.md).
