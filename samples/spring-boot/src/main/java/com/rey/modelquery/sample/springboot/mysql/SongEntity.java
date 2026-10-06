package com.rey.modelquery.sample.springboot.mysql;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of the mysql database. {@code artistId} and {@code catalogId} reference a profile on h2 (recipe 8). */
@Entity
@Table(name = "songs")
public class SongEntity {

    @Id
    Long id;
    String title;
    Integer released;
    Long artistId;
    Integer catalogId;

    protected SongEntity() {
    }

    public SongEntity(Long id, String title, Integer released) {
        this(id, title, released, null, null);
    }

    public SongEntity(Long id, String title, Integer released, Long artistId, Integer catalogId) {
        this.id = id;
        this.title = title;
        this.released = released;
        this.artistId = artistId;
        this.catalogId = catalogId;
    }

    public Long id() {
        return id;
    }

    public Long artistId() {
        return artistId;
    }

    public Integer catalogId() {
        return catalogId;
    }
}
