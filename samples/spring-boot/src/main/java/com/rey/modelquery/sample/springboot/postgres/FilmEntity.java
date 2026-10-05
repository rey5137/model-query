package com.rey.modelquery.sample.springboot.postgres;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of the postgres database. {@code genre} is nullable and {@code tickets} feeds recipes 3 and 4. */
@Entity
@Table(name = "films")
public class FilmEntity {

    @Id
    Long id;
    String title;
    Integer released;
    String genre;
    Long tickets;

    protected FilmEntity() {
    }

    public FilmEntity(Long id, String title, Integer released) {
        this(id, title, released, null, null);
    }

    public FilmEntity(Long id, String title, Integer released, String genre, Long tickets) {
        this.id = id;
        this.title = title;
        this.released = released;
        this.genre = genre;
        this.tickets = tickets;
    }
}
