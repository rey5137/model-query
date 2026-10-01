package com.rey.modelquery.sample.springboot.mysql;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of the mysql database. */
@Entity
@Table(name = "songs")
public class SongEntity {

    @Id
    Long id;
    String title;
    Integer released;

    protected SongEntity() {
    }

    public SongEntity(Long id, String title, Integer released) {
        this.id = id;
        this.title = title;
        this.released = released;
    }
}
