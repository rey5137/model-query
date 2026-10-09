# Inserts

!!! warning "Incubating"
    Inserts are new in 0.3.0 and `@Incubating`, like the other [bulk writes](bulk-writes.md): complete and tested, but
    their API may still change in a minor release. See [API stability](stability.md).

Four calls write new rows, each one statement per chunk and none of them an entity write except `persist`:

| Call | Use it for |
|---|---|
| `insert(ModelInsert)` with a select source | Insert-select: write the rows a query returns, into another table. |
| `insert(ModelInsert)` with rows | Insert-values: write rows you hold, as multi-row `VALUES` statements. |
| `insertReturningKeys(ValuesInsert)` | Insert-values that gives back the generated keys, in row order. |
| `persist(ModelPersist)` | One row through JPA, with its lifecycle callbacks; the only portable way to get an `IDENTITY` key. |

Every example below is copied from a test that runs; each block names its test. Like update and delete, an insert
never loads an entity, and the persistence-context rules are those of [Bulk writes](bulk-writes.md): the context is
flushed first and cleared afterwards unless you ask for `PersistenceContextMode.KEEP`.

## Insert models

An **insert model** lists the attributes of an entity a row writes (`@InsertModel`, with the processor generating
`QNewBook.insert(rows)` and `QNewBook.persist(row)`; see the processor guide). Attributes it leaves out take the
database default under `insert`, and the value the no-argument constructor gives under `persist`. When the entity's id
has no generator the model names it; when it has one the model leaves it out. Values pass through the column's
converter and are always bind parameters.

A screen that also reads the row can skip the second model: see
[One model for reading and creating](models.md#one-model-for-reading-and-creating).

## Insert-select

`ModelInsert.select(columns, sourceRoot)` maps source columns, which may sit on joins and to-many paths, onto the
target's columns. It writes exactly the rows the equivalent `list` would return, duplicates from a to-many join
included. Test: `InsertSelectTest.ac_wrt_21_an_insert_select_writes_the_rows_the_list_returns_with_joined_and_to_many_columns`.

```java
ModelInsert.select(ARCHIVE_COLUMNS, ORDERS)
        .map(ARCHIVE_ID, LINE_ITEM_ID)
        .map(ARCHIVE_ORDER_ID, LINE_ORDER_ID)
        .map(ARCHIVE_CUSTOMER, LINE_CUSTOMER_ID)
        .map(ARCHIVE_STATUS, LINE_STATUS)
        .map(ARCHIVE_CUSTOMER_NAME, LINE_CUSTOMER_NAME)
        .map(ARCHIVE_PRODUCT, LINE_PRODUCT)
        .set(ARCHIVED_BY, "tck")            // a constant for a column the model leaves out
        .where(PAID_IN_DE)
// ... then, in the test:
written[0] = archives(em).insert(archive(PAID_IN_DE).build());
```

### Chunked

Add `chunked(ChunkOptions)` to write in key-first chunks over the distinct source ids, so each source row is written
once. Test: `InsertSelectTest.ac_wrt_21_a_chunked_insert_select_over_a_separate_target_writes_each_source_row_once`.

```java
written[0] = archives(em).insert(archive(PAID_IN_DE).chunked(ChunkOptions.size(5)).build());
```

If the target overlaps the source (the same entity or a shared table), the provider cannot name the tables, or the
source joins through a link or collection table (a `@ManyToMany`, an `@ElementCollection`, or a `@OneToMany` over a
join table), a chunked insert-select fails with `MQ1806` before the flush; guard such a copy with `notExists` over the
target, or leave it unchunked. A `@OneToMany` with `mappedBy` or a join column, and any to-one join, stay allowed. Test: `InsertSelectTest.ac_wrt_21_a_chunked_insert_select_over_its_own_entity_or_a_shared_table_throws_mq1806_before_the_flush`.
An insert-select needs a generator Hibernate renders inline: a pooled sequence or a `JOINED` root fails with `MQ1805`.

## Insert-values

`ValuesInsert.builder(columns, keyType, rows)` writes the rows you hold, in list order. Rows per statement stay within
the vendor's bind and `VALUES` limits. An empty list runs no SQL. Test:
`InsertValuesTest.ac_wrt_22_insert_values_with_an_assigned_id_a_converter_a_to_one_by_id_and_a_set_constant_round_trips`.

```java
var insert = ValuesInsert.builder(NEW_ARCHIVE, Long.class, rows).set(ARCHIVED_BY, "tck").build();
written[0] = archives(em, ModelQueryConfig.defaults()).insert(insert);
```

The library does not validate rows. A to-one column binds its target's id, so no row is loaded.

### Returning keys

`insertReturningKeys` takes a `ValuesInsert` and returns the keys in row order. The keys are drawn before the insert,
so it works for pooled and database sequences, tables and UUIDs; an `IDENTITY` or assigned id, or a `K` that is not
the id's type, fails with `MQ1807` (use `persist` for `IDENTITY`). It cannot be combined with a conflict clause (it does not
compile) or `commitEachChunk()` (`MQ1801`). Test:
`InsertValuesTest.ac_wrt_23_insert_returning_keys_returns_drawn_keys_that_read_back_each_row_by_index`.

```java
List<K> keys = executor(em, root).insertReturningKeys(coded(root, keyType, rows)
        .chunked(ChunkOptions.size(2)).build());
```

### Chunks and failures

`ChunkOptions.commitEachChunk()` commits each chunk on its own. When one fails, `ChunkedWriteException` reports
`committedRows()`, `nextRowIndex()` to resume from, and `inDoubtRowCount()`. Test:
`InsertValuesTest.ac_wrt_26_a_per_chunk_insert_values_failure_reports_committed_rows_and_the_next_row_index`.

```java
var insert = ValuesInsert.builder(BARE_ARCHIVE, Long.class, bareRows(7))
        .chunked(ChunkOptions.size(2).commitEachChunk()).build();
// the third chunk fails:
assertThat(failure[0].committedRows()).isEqualTo(4);
assertThat(failure[0].nextRowIndex()).hasValue(4);
```

## Conflict clauses

`onConflict(columns...)` makes an insert-values conditional: one statement per chunk, and a conflict is detected on the
named key, which must be the id, a natural id or a declared unique constraint in the mapping (`MQ1804` otherwise).
The key is trusted from the mapping, so the database must enforce it: `MERGE` vendors (H2, Oracle, SQL Server) and MySQL with `anyUniqueKey()` do not detect a key the schema does not enforce, and would update every matching row or insert duplicates. It is offered on insert-values only; an insert-select guards with `notExists`. Two rows of one call that share a conflict key throw `MQ1808` at `build()`. The count returned
is the vendor's:
MySQL counts a skipped or changed row differently from PostgreSQL and H2, and the tests pin each.

### `doNothing`

Test: `InsertConflictTest.ac_wrt_25_do_nothing_skips_a_conflicting_row_counting_as_the_vendor_does`.

```java
var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(2L, "c2", "X2"),
        new Named(3L, "c3", "N3"))).onConflict(ID).doNothing().anyUniqueKey().build();
```

Where the provider does not render `doNothing` (H2 on Hibernate 6.6), the first execution fails with `MQ1804`.

### `doUpdate`

`doUpdate` assigns with `setFromRow` (the incoming value), `set`, or `setNull`, optionally limited by one `where`; the
`@Version` is incremented unless `keepVersion()`. Test:
`InsertConflictTest.ac_wrt_25_do_update_from_the_row_where_the_stored_row_matches_increments_the_version`.

```java
var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(1L, "c1", "Y1"),
                new Named(2L, "c2", "Y2"), new Named(3L, "c3", "N3")))
        .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.eq(NAME, "N2"))).anyUniqueKey().build();
```

Test: `InsertConflictTest.ac_wrt_25_do_update_sets_a_value_or_null_on_a_unique_column_conflict_and_keep_version_keeps_it`.

```java
var kept = ValuesInsert.builder(NAMED, Long.class, rows)
        .onConflict(CODE).doUpdate(u -> u.set(NAME, "Z")).keepVersion().anyUniqueKey().build();
var nulled = ValuesInsert.builder(NAMED, Long.class, rows.subList(0, 1))
        .onConflict(CODE).doUpdate(u -> u.setNull(NAME)).anyUniqueKey().build();
```

### `anyUniqueKey()`

MySQL and MariaDB detect a conflict on any unique key, not the one you named. So the named key is honoured or the call
fails: on those vendors a conflict clause without `anyUniqueKey()` throws `MQ1804` before any statement, and with it
you state that any unique key may trigger the clause. Test:
`InsertConflictTest.ac_wrt_25_mysql_without_any_unique_key_throws_mq1804_before_any_statement`.

### `conflictUpdateWhereOnAssignedColumns`

A `where` that reads two or more columns the update assigns is refused with `MQ1804` by default, because vendors
disagree on whether the filter sees the stored or the new value. The executor option
`ModelQueryConfig.conflictUpdateWhereOnAssignedColumns(true)` allows it where the filter reads the stored row, and a
vendor that cannot (MySQL) still throws `MQ1804`. Tests:
`InsertConflictTest.ac_wrt_25_a_where_reading_two_assigned_columns_throws_mq1804_by_default_on_every_vendor` and
`InsertConflictTest.ac_wrt_25_with_the_option_a_where_reading_two_assigned_columns_reads_the_stored_row_or_throws_mq1804`.

```java
var config = ModelQueryConfig.defaults().conflictUpdateWhereOnAssignedColumns(true);
var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(1L, "c1x", "Y1"),
                new Named(2L, "c2x", "X2"), new Named(3L, "c3", "N3")))
        .onConflict(ID).doUpdate(u -> u.setFromRow(CODE, NAME).where(f -> f.eq(CODE, "c2").eq(NAME, "N2")))
        .anyUniqueKey().build();
```

## `persist`

`persist` writes one row through JPA: it instantiates the entity, sets each attribute, persists, flushes and detaches
only the entity it created. Lifecycle callbacks such as `@PrePersist` run, and `IDENTITY` keys come back. It needs a
transaction (`MQ2501` without one) and takes no `set`, conflict clause or chunking. It also runs on a provider with no
insert support. Test: `PersistTest.ac_wrt_28_persist_with_identity_returns_the_key_runs_pre_persist_and_leaves_the_entity_detached`.

```java
var persist = ModelPersist.of(COLUMNS, Long.class,
        new NewPersist("p1", InsPersistEntity.Status.PAID, true, "#42", "Hanoi", "100000", 2L));
Long key = executor(em, InsPersistEntity.class).persist(persist);
```

A `CascadeType.ALL` to-one detaches the caller's managed target as well, as the mapping's cascade says.

The entity is detached even when the flush fails. Before any statement, a model writing part of a composite id is
`MQ1802`, and so, on Hibernate, is one naming an id its generator generates; a `null` set on a primitive attribute is
`MQ1308`. An embeddable with no no-argument constructor is `MQ1805` under `persist`; `insert` writes it. Tests:
`PersistTest.ac_wrt_26_persist_naming_a_generated_id_or_part_of_a_composite_id_throws_mq1802_before_any_statement`,
`PersistTest.ac_wrt_28_persist_of_null_to_a_primitive_attribute_throws_mq1308_before_any_statement`.

### Returning a model

`persist(persist, returning)` gives back a query model instead of the key. The model is built from the flushed entity
before it is detached, with no second select: the generated id, what `@PrePersist` and the entity's constructor set,
and any [write assignment](bulk-writes.md#write-assignments) value are in it. The query supplies only the selection,
the mapper, `afterMap` and the finisher, and it can fill root attributes, embeddable paths and the id of a to-one
association, which it reads without initializing the target. A query with a `where` or `having` that recorded a
filter, `groupBy`, a fetch plan, `customize`, `orderBy`, `keyset` or `primaryKeyFirst`, or a column the entity cannot
fill alone (an expression, an aggregate, a join beyond a to-one id or with `on(...)`, a collection), is `MQ1809` on its
first execution per factory, before any statement. A value the database fills, such as a column default or a trigger,
is in the model only where the mapping has the provider read it back (`@Generated`). Test:
`PersistReturningTest.ac_wrt_38_persist_returning_fills_the_generated_id_pre_persist_and_constructor_values_with_one_insert`.

```java
private static final ModelQuery<InsPersistEntity, Long, InsPersistView> VIEW = QInsPersistView.query()
        .select(QInsPersistView.ALL.with(QInsPersistView.SOURCE)).build();

var persist = ModelPersist.of(COLUMNS, Long.class,
        new NewPersist("p1", InsPersistEntity.Status.PAID, true, "#42", "Hanoi", 2L));
```

=== "Plain JPA"

    ```java
    // Needs an active transaction (MQ2501 without one).
    InsPersistView view = executor(em).persist(persist, VIEW);
    ```

=== "Spring repository"

    ```java
    // Joins the current transaction, or opens one and commits it before returning.
    InsPersistView view = repository.persist(persist, VIEW);
    ```

The same overload is on `ModelQueryRepository` (see below). There is no `returning(...)` builder stage, and no
entity-mode insert: `persist` in a loop is the way to fire listeners for many rows.

## With Spring Data

`ModelQueryRepository` has `insert`, `insertReturningKeys` and `persist`, with `persist(persist, returning)` as its
second form. Each opens a transaction on the repository's transaction manager when none is active, so the rows are
committed when the call returns, and joins an active one (R-SPR-10). Test:
`ModelQueryRepositoryTest.ac_wrt_30_insert_insert_returning_keys_and_persist_without_a_transaction_commit_through_the_repository`.

```java
ModelInsert<InsUuidEntity, ?> values = ValuesInsert.builder(CODED_UUIDS, UUID.class,
        List.of(new Coded("a", "A"), new Coded("b", "B"))).build();
assertThat(uuids.insert(values)).isEqualTo(2);
List<UUID> keys = uuids.insertReturningKeys(ValuesInsert.builder(CODED_UUIDS, UUID.class,
        List.of(new Coded("c", "C"), new Coded("d", "D"), new Coded("e", "E"))).build());
Long key = persisted.persist(ModelPersist.of(CODED_PERSIST, Long.class, new Coded("p", null)));
```

The Spring Boot sample has both endpoints. A `POST` creating one book through `persist` and answering with the
persisted model, tested by
`SampleApplicationTest.ac_spr_09_the_post_endpoint_persists_one_book_without_a_surrounding_transaction`:

```java
private static final ModelQuery<BookEntity, Long, SavedBook> SAVED = QSavedBook.query()
        .select(QSavedBook.ALL)
        .build();

@PostMapping("/books")
ResponseEntity<SavedBook> create(@RequestBody NewBook book) {
    SavedBook saved = books.persist(QNewBook.persist(book), SAVED);
    return ResponseEntity.created(URI.create("/books/" + saved.id())).body(saved);
}
```

and an import that skips rows whose id exists, tested by
`SampleApplicationTest.ac_spr_09_the_import_endpoint_inserts_films_skipping_existing_ids`:

```java
@PostMapping("/films/import")
Imported importFilms(@RequestBody List<NewFilm> rows) {
    var insert = QNewFilm.insert(rows).onConflict(QNewFilm.ID).doNothing().build();
    return new Imported(films.insert(insert));
}
```
