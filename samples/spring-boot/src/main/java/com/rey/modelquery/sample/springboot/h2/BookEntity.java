package com.rey.modelquery.sample.springboot.h2;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of the h2 database. No endpoint sets {@code updatedAt}: the {@code WriteAssignment} bean in
 * {@link BookDataConfig} stamps it on every model-query insert and update of a book.
 */
@Entity
@Table(name = "books")
public class BookEntity {

    @Id
    Long id;
    String title;
    Integer released;
    Instant updatedAt;

    protected BookEntity() {
    }

    public BookEntity(Long id, String title, Integer released) {
        this.id = id;
        this.title = title;
        this.released = released;
    }
}
