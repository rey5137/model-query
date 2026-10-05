package com.rey.modelquery.sample.springboot.mysql;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of the mysql database. {@code userId} and {@code userTypeId} reference a profile on h2 (recipe 8). */
@Entity
@Table(name = "songs")
public class SongEntity {

    @Id
    Long id;
    String title;
    Integer released;
    Long userId;
    Integer userTypeId;

    protected SongEntity() {
    }

    public SongEntity(Long id, String title, Integer released) {
        this(id, title, released, null, null);
    }

    public SongEntity(Long id, String title, Integer released, Long userId, Integer userTypeId) {
        this.id = id;
        this.title = title;
        this.released = released;
        this.userId = userId;
        this.userTypeId = userTypeId;
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public Integer userTypeId() {
        return userTypeId;
    }
}
