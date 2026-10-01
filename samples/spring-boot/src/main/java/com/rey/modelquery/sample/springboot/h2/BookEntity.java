package com.rey.modelquery.sample.springboot.h2;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of the h2 database. */
@Entity
@Table(name = "books")
public class BookEntity {

    @Id
    Long id;
    String title;
    Integer released;

    protected BookEntity() {
    }

    public BookEntity(Long id, String title, Integer released) {
        this.id = id;
        this.title = title;
        this.released = released;
    }
}
